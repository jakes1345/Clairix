package app.clairix.detect

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import app.clairix.AppSettings
import app.clairix.Registry
import app.clairix.Threat
import app.clairix.WifiAnomaly
import app.clairix.analysis.WifiBeacon
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.Random

/**
 * Catches Karma-style rogue access points — a Wi-Fi Pineapple, hcxdumptool in
 * "respond to everything" mode, a hotel-lobby interception box — by giving them a
 * question only a liar would answer.
 *
 * A Karma attack works by answering every probe request: a phone asks "is network X
 * here?" and the attacker says yes, whatever X is, so the phone connects to it. This
 * phone therefore asks for a network that does not exist. While the scanner runs a
 * made-up name is registered as a *hidden* network suggestion, which is what makes
 * Android include it in directed probe requests (hidden networks can only be found by
 * asking for them by name). Nobody else knows the name, so any scan result carrying it
 * is an access point that heard the probe and answered it.
 *
 * A probe goes out in the clear, so the name is something anyone nearby with a
 * sniffer can read. An earlier version used one 32-hex-character name for the life of
 * the install and never withdrew it: a fixed identifier for this phone, in every probe,
 * that no MAC randomisation hides and that no real network looks like. So now:
 * - the name looks like a home network's ("Juniper4417", from a bundled word list
 *   and digits, [freshName]);
 * - it is replaced every time the scanner starts and every [ROTATE_MS] while it runs,
 *   with the old one withdrawn first, so one name is registered at a time ([rotate]);
 * - it is withdrawn when the scanner stops ([stop]), so the phone stops asking when
 *   nobody reads the answers.
 *
 * And the phone must never join whatever answers. The suggestion carries a random
 * WPA2 password nobody keeps, so an open answer (which is what Karma kit sends) does
 * not even match it; it is untrusted, which Android only uses when an app asks for an
 * untrusted network; and auto-join is off. Android refuses auto-join off on an open
 * suggestion (the builder throws), which is another reason for the password.
 *
 * Old names stay matchable for [RETIRED_KEEP_MS]: an answer-everything box keeps the
 * names it has heard and may broadcast them for days, and an answer to the previous
 * name can still be in the scan cache when the name changes.
 */
object KarmaCanary {

    const val REASON = "karma_canary_answered"

    /** How often a new name replaces the one in place while the scanner runs; see [rotationDue]. */
    const val ROTATE_MS = 6 * 3_600_000L

    /** How often a suggestion Android refused is tried again, so allowing it in Settings takes effect without a restart. */
    const val RETRY_MS = 10 * 60_000L

    /** How long a withdrawn name is still matched against the scan results. */
    const val RETIRED_KEEP_MS = 7 * 24 * 3_600_000L

    /** The most withdrawn names kept for matching; the oldest go first. */
    const val MAX_RETIRED = 32

    private const val WORDS_ASSET = "canary_words.txt"
    private const val KEY_SSID = "karma_canary_ssid"
    private const val KEY_ROTATED_AT = "karma_canary_rotated_at"
    private const val KEY_RETIRED = "karma_canary_retired"
    /** Earlier versions marked the trap armed here on a successful add; removed on the next save. */
    private const val KEY_SUGGESTED_LEGACY = "karma_canary_suggested"

    /** What an add returns here when the platform threw instead of answering. */
    private const val STATUS_THREW = -1

    /** Separators between the word and the digits; none is the most common, as in home router names. */
    private val SEPARATORS = listOf("", "", "-", "_", " ")

    private val random = SecureRandom()

    // ── Bookkeeping of names (plain Kotlin) ───────────────────────────────────

    /** A name the phone asked for before, and when it was withdrawn. */
    data class Retired(val name: String, val at: Long)

    /** The name registered now (null when none is), when it was registered, and the withdrawn ones. */
    data class Names(
        val current: String? = null,
        val rotatedAt: Long = 0L,
        val retired: List<Retired> = emptyList()
    ) {
        /** Every name an answer can carry at [now]: the current one and those withdrawn within [RETIRED_KEEP_MS]. */
        fun live(now: Long): Set<String> = buildSet {
            current?.let { add(it) }
            for (r in retired) if (now - r.at <= RETIRED_KEEP_MS) add(r.name)
        }

        /** The current name withdrawn at [now]; the withdrawn list keeps [MAX_RETIRED] at most, none past [RETIRED_KEEP_MS]. */
        fun withdrawn(now: Long): Names {
            val all = if (current != null) retired + Retired(current, now) else retired
            val kept = all.filter { now - it.at <= RETIRED_KEEP_MS }.sortedByDescending { it.at }
                .distinctBy { it.name }.take(MAX_RETIRED)
            return Names(current = null, rotatedAt = rotatedAt, retired = kept)
        }

        /** [fresh] registered at [now]. */
        fun adopted(fresh: String, now: Long): Names = copy(current = fresh, rotatedAt = now)
    }

    /**
     * A made-up network name shaped like one a person gives a home router: a word from
     * the bundled list, sometimes lower-case, then two to four digits, sometimes after a
     * separator — "Juniper4417", "heron-82", "Basalt 903". With the 743 words shipped
     * that is about sixty million names, so a real network carrying the one in use is
     * not a thing that happens by chance. Never one of [avoid] (the names still live).
     */
    fun freshName(words: List<String>, random: Random, avoid: Set<String> = emptySet()): String {
        require(words.isNotEmpty()) { "no words to make a name from" }
        while (true) {
            val word = words[random.nextInt(words.size)]
            val cased = if (random.nextInt(4) == 0) word.lowercase(Locale.US) else word
            val separator = SEPARATORS[random.nextInt(SEPARATORS.size)]
            val digits = 2 + random.nextInt(3)
            var low = 1
            repeat(digits - 1) { low *= 10 }
            val number = low + random.nextInt(9 * low)
            val name = "$cased$separator$number"
            if (name !in avoid) return name
        }
    }

    /** The bundled word list: one word per line, `#` starts a comment, anything but ASCII letters is skipped. */
    fun parseWords(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") && line.all { it in 'A'..'Z' || it in 'a'..'z' } }
            .distinctBy { it.lowercase(Locale.US) }
            .toList()

    /**
     * Whether the scanner should register a new name now. Armed, every [ROTATE_MS] (and
     * at once if the clock went backwards). Refused or disallowed, another try every
     * [RETRY_MS]: a refusal costs nothing and shows nothing, and the owner may have
     * allowed suggestions in Settings since. Waiting for the owner's answer, never: the
     * phone does not probe for a suggestion it has not been allowed to use, and every
     * new add would put the question in front of them again. Off, never.
     */
    fun rotationDue(state: KarmaTrap.State, rotatedAt: Long, attemptedAt: Long, now: Long): Boolean = when (state) {
        KarmaTrap.State.OFF, KarmaTrap.State.PENDING -> false
        KarmaTrap.State.ARMED -> rotatedAt <= 0L || now < rotatedAt || now - rotatedAt >= ROTATE_MS
        KarmaTrap.State.DISALLOWED, KarmaTrap.State.REFUSED, KarmaTrap.State.UNAVAILABLE ->
            attemptedAt <= 0L || now < attemptedAt || now - attemptedAt >= RETRY_MS
    }

    /** The two platform calls a rotation makes, behind an interface so a test can see what is registered. */
    interface Suggestions {
        /** Withdraws every network suggestion this app has made. Clairix makes no other. */
        fun removeAll()

        /** Registers the canary under [ssid]; the platform's `STATUS_NETWORK_SUGGESTIONS_` answer. */
        fun add(ssid: String): Int
    }

    /**
     * One rotation: everything registered is withdrawn first, then a fresh name is
     * added, so there is never more than one. The withdrawn name joins the retired ones
     * whether or not the add then works. Returns the bookkeeping and the add's status.
     */
    fun rotate(port: Suggestions, before: Names, words: List<String>, random: Random, now: Long): Pair<Names, Int> {
        port.removeAll()
        val withdrawn = before.withdrawn(now)
        val fresh = freshName(words, random, avoid = withdrawn.live(now))
        val status = port.add(fresh)
        val after = if (KarmaTrap.added(status)) withdrawn.adopted(fresh, now) else withdrawn
        return after to status
    }

    /** The withdrawn names as stored in preferences. */
    fun encodeRetired(retired: List<Retired>): String {
        val array = JSONArray()
        for (r in retired) array.put(JSONObject().put("n", r.name).put("t", r.at))
        return array.toString()
    }

    /** Reads [encodeRetired]'s output; anything unreadable is dropped rather than failing the scanner. */
    fun parseRetired(raw: String?): List<Retired> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Retired>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("n", "").takeIf { it.isNotBlank() } ?: continue
            val at = o.optLong("t", 0L).takeIf { it > 0L } ?: continue
            out += Retired(name, at)
        }
        return out
    }

    // ── The scanner's side: register, rotate, withdraw ────────────────────────

    private val lock = Any()
    @Volatile private var names: Names? = null
    @Volatile private var words: List<String>? = null
    private var running = false
    private var wifiAvailable = true
    private var addStatus: Int? = null
    private var approval: Int? = null
    private var attemptedAt = 0L
    private var trap = KarmaTrap()
    private var listener: WifiManager.SuggestionUserApprovalStatusListener? = null

    /**
     * Sets the trap for a scanner run: withdraws whatever an earlier run (or an earlier
     * version, with its fixed name) left registered, and registers a fresh name. Call
     * on every scanner start.
     */
    fun start(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        synchronized(lock) {
            running = true
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi == null) {
                wifiAvailable = false
                attemptedAt = now
                publish(now)
                return
            }
            wifiAvailable = true
            rotateLocked(app, wifi, now)
        }
    }

    /** Registers a new name if [rotationDue] says so. Cheap otherwise; the scanner calls it with each Wi-Fi check. */
    fun rotateIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        synchronized(lock) {
            if (!running) return
            val rotatedAt = loaded(app).rotatedAt
            if (!rotationDue(trap.state, rotatedAt, attemptedAt, now)) return
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi == null) {
                wifiAvailable = false
                attemptedAt = now
                publish(now)
                return
            }
            wifiAvailable = true
            rotateLocked(app, wifi, now)
        }
    }

    /** Withdraws the name and stops listening: the scanner has stopped, so nobody would read an answer. */
    fun stop(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        synchronized(lock) {
            running = false
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi != null) {
                Platform(wifi).removeAll()
                listener?.let { l -> runCatching { wifi.removeSuggestionUserApprovalStatusListener(l) } }
            }
            listener = null
            val after = loaded(app).withdrawn(now)
            names = after
            runCatching { save(prefs(app), after) }
            addStatus = null
            approval = null
            publish(now)
        }
    }

    /**
     * Withdraws every suggestion this app has made and stops listening, for a wipe
     * ([app.clairix.security.Wiper]). Android keeps an app's network suggestions until
     * the app withdraws them or is uninstalled, so one left behind would outlive the
     * wipe in the phone's Wi-Fi settings. Nothing is written: the wipe deletes this
     * object's preferences with everything else, and may already have.
     */
    fun withdrawForWipe(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            running = false
            val wifi = app.getSystemService(WifiManager::class.java) ?: return
            Platform(wifi).removeAll()
            listener?.let { l -> runCatching { wifi.removeSuggestionUserApprovalStatusListener(l) } }
            listener = null
        }
    }

    private fun rotateLocked(app: Context, wifi: WifiManager, now: Long) {
        val list = words(app)
        if (list.isEmpty()) {
            // No word list, no name to register; and nothing earlier stays registered either.
            Platform(wifi).removeAll()
            val after = loaded(app).withdrawn(now)
            names = after
            runCatching { save(prefs(app), after) }
            addStatus = STATUS_THREW
            attemptedAt = now
            publish(now)
            return
        }
        val (after, status) = rotate(Platform(wifi), loaded(app), list, random, now)
        names = after
        runCatching { save(prefs(app), after) }
        addStatus = status
        attemptedAt = now
        // The listener reports the approval once when it is registered and again on each
        // change. Registering it afresh after every add reads the approval as it stands
        // now: allowing suggestions again in Settings is not always reported as a change.
        // The last reading stands until the new one arrives, so a rotation of an armed
        // trap does not show it as down for a moment and restart its "since".
        listener?.let { old -> runCatching { wifi.removeSuggestionUserApprovalStatusListener(old) } }
        val fresh = WifiManager.SuggestionUserApprovalStatusListener { reported -> onApproval(reported) }
        listener = if (runCatching { wifi.addSuggestionUserApprovalStatusListener(app.mainExecutor, fresh) }.isSuccess) fresh else null
        publish(now)
    }

    private fun onApproval(status: Int) {
        synchronized(lock) {
            if (!running) return
            approval = status
            publish(System.currentTimeMillis())
        }
    }

    /** Judges the trap from what the platform last said and hands it to the Wi-Fi tab. Call with [lock] held. */
    private fun publish(now: Long) {
        trap = KarmaTrap.judge(trap, running, wifiAvailable, addStatus, approval, now)
        Registry.publishKarmaTrap(trap)
    }

    /** The real calls. Every failure is an answer, never a crash: the rest of the scanner runs regardless. */
    private class Platform(private val wifi: WifiManager) : Suggestions {
        override fun removeAll() {
            // An empty list withdraws every suggestion this app has made: the name in place,
            // one a killed run left behind, and an earlier version's fixed name.
            runCatching { wifi.removeNetworkSuggestions(emptyList()) }
        }

        override fun add(ssid: String): Int =
            runCatching { wifi.addNetworkSuggestions(listOf(suggestion(ssid))) }.getOrDefault(STATUS_THREW)
    }

    /**
     * The canary as Android takes it. Hidden, which is what makes the platform ask for
     * it by name. A WPA2 password nobody keeps, untrusted, and auto-join off, so the
     * phone does not join whatever answers; see the class comment.
     */
    private fun suggestion(ssid: String): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsHiddenSsid(true)
            .setWpa2Passphrase(passphrase())
            .setUntrusted(true)
            .setIsInitialAutojoinEnabled(false)
            .build()

    /** 32 random letters and digits: a valid WPA2 passphrase that nobody knows and nothing stores. */
    private fun passphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return buildString(32) { repeat(32) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun prefs(app: Context): SharedPreferences =
        app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    /** The names in memory, read from preferences on first use. */
    private fun loaded(app: Context): Names = names ?: load(prefs(app)).also { names = it }

    private fun load(prefs: SharedPreferences): Names = Names(
        current = prefs.getString(KEY_SSID, null)?.takeIf { it.isNotBlank() },
        rotatedAt = prefs.getLong(KEY_ROTATED_AT, 0L),
        retired = parseRetired(prefs.getString(KEY_RETIRED, null))
    )

    private fun save(prefs: SharedPreferences, n: Names) {
        val edit = prefs.edit()
        if (n.current != null) edit.putString(KEY_SSID, n.current) else edit.remove(KEY_SSID)
        edit.putLong(KEY_ROTATED_AT, n.rotatedAt)
            .putString(KEY_RETIRED, encodeRetired(n.retired))
            .remove(KEY_SUGGESTED_LEGACY)
            .apply()
    }

    private fun words(app: Context): List<String> =
        words ?: runCatching { app.assets.open(WORDS_ASSET).bufferedReader().use { parseWords(it.readText()) } }
            .getOrDefault(emptyList())
            .also { if (it.isNotEmpty()) words = it }

    // ── Matching answers ──────────────────────────────────────────────────────

    /**
     * Pure check of a scan-result list against the live names. One anomaly per
     * answering BSSID; a Karma box with several radios is several anomalies.
     */
    fun check(canaries: Set<String>, results: List<ScanResult>, now: Long): List<WifiAnomaly> =
        checkBeacons(canaries, results.map { WifiBeacon.of(it) }, now)

    /** The same check on plain beacons, which is what a JVM test can build. */
    fun checkBeacons(canaries: Set<String>, beacons: List<WifiBeacon>, now: Long): List<WifiAnomaly> {
        if (canaries.isEmpty()) return emptyList()
        val hits = ArrayList<WifiAnomaly>(1)
        for (b in beacons) {
            val seen = b.ssid?.trim()?.trim('"') ?: continue
            if (seen !in canaries) continue
            val bssid = b.bssid ?: continue
            hits.add(WifiAnomaly(seen, bssid, b.rssi, REASON, Threat.CRITICAL, now))
        }
        return hits.distinctBy { it.bssid }
    }

    /** One name only; see [checkBeacons]. */
    fun checkBeacons(canary: String, beacons: List<WifiBeacon>, now: Long): List<WifiAnomaly> =
        checkBeacons(setOf(canary), beacons, now)

    /** Reads the platform's scan cache and checks it against every live name. Empty when nothing answered. */
    fun scan(context: Context): List<WifiAnomaly> {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java) ?: return emptyList()
        val results = try { wifi.scanResults } catch (_: SecurityException) { return emptyList() } ?: return emptyList()
        val now = System.currentTimeMillis()
        val live = synchronized(lock) { loaded(app) }.live(now)
        return check(live, results, now)
    }

    /** Human explanation for the alert and the timeline. */
    fun describe(anomaly: WifiAnomaly): String =
        "A Wi-Fi hotspot nearby (${anomaly.bssid}, ${anomaly.rssi} dBm) claimed to be a network whose name Clairix " +
            "made up for this phone and nobody else knows. Only equipment built to trick phones into connecting to it " +
            "does that (a Karma or Wi-Fi Pineapple device). Do not join any Wi-Fi here; turn Wi-Fi off and leave."
}
