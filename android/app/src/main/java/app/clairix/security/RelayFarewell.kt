package app.clairix.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import app.clairix.comms.RelayTls
import app.clairix.comms.TorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The relay account deletion a wipe, or Delete identity and number, asks for,
 * kept until the relay has it.
 *
 * Both destroy the identity key that signs requests, so the deletion is signed
 * before that ([Wiper], [app.clairix.comms.CommsRepository.unpair]). The relay
 * takes a signed request only within five minutes of the time it carries
 * (comms-worker/src/auth.ts), so a single signature would be useless minutes
 * later: the deletion is signed for every [STEP_MS] over the next [HORIZON_MS],
 * and whichever copy is current when the network is there is the one sent.
 * Until the relay has taken one, or the last has passed, they wait in [FILE], a
 * plain file in the app's private files (after a wipe every key that could seal
 * it is gone). It names the relay and the Clairix number and nothing else about
 * the account. It is sent as soon as it can be: after a wipe at the next start
 * of the app (at once, for the duress decoy), and on an alarm the system keeps
 * whether or not Clairix is running, until it goes or expires.
 *
 * A deletion signed while Tor was on is never sent any other way, because sent
 * directly it would give the relay this phone's address beside the number. The
 * wipe tries it through Tor before the app closes and then drops it (a wiped
 * phone has no Tor any more); after Delete identity and number Tor stays on, so
 * it is kept and tried through Tor, and dropped if Tor is turned off.
 */
internal object RelayFarewell {

    const val FILE = "relay_farewell.json"
    /** One copy of the deletion is signed for every step; the relay's window is ±5 min, so 4 min leaves two copies usable at any moment. */
    const val STEP_MS = 4 * 60_000L
    /** How long copies are signed ahead: a wipe made offline still deletes the account if the phone is back online within a day. */
    const val HORIZON_MS = 24 * 60 * 60_000L
    val COPIES = (HORIZON_MS / STEP_MS).toInt()
    /** The relay refuses a timestamp further than this from its own clock (TS_WINDOW_MS in comms-worker/src/auth.ts). */
    const val WINDOW_MS = 5 * 60_000L
    /** A copy is sent only this far inside the window, for clock drift and the time on the wire. */
    const val USABLE_MS = 4 * 60_000L
    /** How soon after a wipe the alarm first tries, should nothing in the app have sent it already. */
    const val FIRST_TRY_MS = 15_000L
    /** Between tries while the relay cannot be reached. Android stretches this in Doze; that is fine. */
    const val RETRY_MS = 10 * 60_000L

    private const val CALL_TIMEOUT_SEC = 20L
    /** The relay's answer to a copy outside its window, when its Date header is missing. */
    private const val STALE_ERROR = "Request timestamp outside the allowed window"
    /** Copies tried in one go for one relay: a stale clock corrected, a spent copy replaced. */
    private const val MAX_TRIES = 4
    private const val ALARM_REQUEST = 0x7A10
    private const val ALARM_ACTION = "app.clairix.RELAY_FAREWELL"
    /** What the receiver waits before letting Android have it back; the send carries on under a wake lock. */
    private const val RECEIVER_BUDGET_MS = 8_000L
    /** The longest the phone is kept awake for one alarm: every try of two relays, each to its call timeout. */
    private const val WAKE_MS = 3 * 60_000L

    /** One signed copy of the deletion, for the relay-clock moment [ts]: the headers that carry it. */
    data class Signed(val ts: Long, val headers: List<Pair<String, String>>)

    /** One relay's deletion: where it goes, how its certificate is checked, and the signed copies. */
    data class Prepared(
        val url: String,
        /** The relay's pinned key ("sha256/…"), or null for the platform's verdict alone. */
        val pin: String?,
        /** Signed while Tor was on: sent through Tor or not at all. */
        val viaTor: Boolean,
        /** Relay time minus this phone's time when the copies were signed. */
        val offsetMs: Long,
        val signed: List<Signed>,
    )

    /** What the relay answered a copy: its status, the error it gave, and its clock (the Date header). */
    data class Answer(val code: Int, val error: String?, val serverTime: Long?)

    /** One relay's deletion as [app.clairix.comms.RelayClient.preparedWipes] signed it; [viaTor]: Tor was on. */
    fun from(w: app.clairix.comms.RelayClient.PreparedWipe, viaTor: Boolean): Prepared =
        Prepared(w.url, w.pin, viaTor, w.offsetMs, w.copies.map { Signed(it.ts, it.headers) })

    /** Every relay account this phone holds, its deletion signed now for the next [HORIZON_MS]; empty without one. Needs the identity. */
    fun prepareAll(): List<Prepared> {
        val viaTor = TorTransport.enabled
        return app.clairix.comms.CommsRepository.preparedRelayWipes(COPIES, STEP_MS).map { from(it, viaTor) }
    }

    enum class Outcome {
        /** The relay deleted the account, or holds none these copies could delete. */
        DONE,
        /** The relay could not be asked; try again later. */
        RETRY,
    }

    // ── Choosing a copy ─────────────────────────────────────────────────────

    /** The copies the relay would take at [now] (this phone's clock), the one nearest its time first. */
    fun usable(p: Prepared, now: Long): List<Signed> {
        val relayNow = now + p.offsetMs
        return p.signed.filter { abs(it.ts - relayNow) <= USABLE_MS }.sortedBy { abs(it.ts - relayNow) }
    }

    /** Whether every copy is past the relay's window: nothing is left that it would take. */
    fun expired(p: Prepared, now: Long): Boolean = p.signed.all { it.ts + WINDOW_MS < now + p.offsetMs }

    /**
     * Sends [p]'s current copy through [ask] (null: no answer at all). A copy the
     * relay calls stale moves this phone's idea of the relay's time to the one in
     * its answer and the right copy goes instead; a copy refused outright is
     * followed by one more, since a copy whose answer was lost has had its nonce
     * spent, and a second refusal means the relay has no account these keys
     * could delete (it is gone). Busy, throttled or silent: [Outcome.RETRY].
     */
    fun deliver(p: Prepared, now: Long, ask: (Signed) -> Answer?): Outcome {
        var offset = p.offsetMs
        val tried = HashSet<Long>()
        var refused = 0
        repeat(MAX_TRIES) {
            val copy = usable(p.copy(offsetMs = offset), now).firstOrNull { it.ts !in tried }
                ?: return if (refused > 0) Outcome.DONE else Outcome.RETRY
            tried += copy.ts
            val answer = ask(copy) ?: return Outcome.RETRY
            val stale = answer.serverTime?.let { abs(copy.ts - it) > WINDOW_MS } ?: (answer.error == STALE_ERROR)
            when {
                answer.code in 200..299 -> return Outcome.DONE
                answer.code == 401 && stale -> {
                    val server = answer.serverTime ?: return Outcome.RETRY
                    offset = server - now
                    // The copies tried were judged on the wrong clock; any may be the right one now.
                    tried.clear()
                    tried += copy.ts
                }
                answer.code == 401 -> if (++refused >= 2) return Outcome.DONE
                answer.code == 429 || answer.code >= 500 -> return Outcome.RETRY
                // Any other refusal is one no copy of this request can change.
                else -> return Outcome.DONE
            }
        }
        return if (refused > 0) Outcome.DONE else Outcome.RETRY
    }

    // ── On disk ─────────────────────────────────────────────────────────────

    fun toJson(list: List<Prepared>): JSONObject {
        val relays = JSONArray()
        for (p in list) {
            val signed = JSONArray()
            for (s in p.signed) {
                val headers = JSONArray()
                for ((name, value) in s.headers) headers.put(JSONArray().put(name).put(value))
                signed.put(JSONObject().put("ts", s.ts).put("headers", headers))
            }
            relays.put(
                JSONObject()
                    .put("url", p.url)
                    .put("pin", p.pin ?: JSONObject.NULL)
                    .put("tor", p.viaTor)
                    .put("offset", p.offsetMs)
                    .put("signed", signed)
            )
        }
        return JSONObject().put("v", 1).put("relays", relays)
    }

    /** Reads what [toJson] wrote; anything unreadable in it is skipped. */
    fun fromJson(json: JSONObject): List<Prepared> {
        val relays = json.optJSONArray("relays") ?: return emptyList()
        val out = ArrayList<Prepared>()
        for (i in 0 until relays.length()) {
            val r = relays.optJSONObject(i) ?: continue
            val url = r.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
            val signed = ArrayList<Signed>()
            val arr = r.optJSONArray("signed") ?: continue
            for (j in 0 until arr.length()) {
                val s = arr.optJSONObject(j) ?: continue
                val headers = ArrayList<Pair<String, String>>()
                val h = s.optJSONArray("headers") ?: continue
                for (k in 0 until h.length()) {
                    val pair = h.optJSONArray(k) ?: continue
                    if (pair.length() == 2) headers += pair.optString(0) to pair.optString(1)
                }
                signed += Signed(s.optLong("ts"), headers)
            }
            if (signed.isEmpty()) continue
            out += Prepared(
                url = url,
                pin = if (r.isNull("pin")) null else r.optString("pin").takeIf { it.isNotEmpty() },
                viaTor = r.optBoolean("tor", false),
                offsetMs = r.optLong("offset", 0L),
                signed = signed,
            )
        }
        return out
    }

    fun file(filesDir: File): File = File(filesDir, FILE)

    fun load(filesDir: File): List<Prepared> = runCatching {
        val f = file(filesDir)
        if (!f.isFile) emptyList() else fromJson(JSONObject(f.readText()))
    }.getOrDefault(emptyList())

    /** Writes [list] in place of what is waiting, or deletes the file when it is empty. */
    fun save(filesDir: File, list: List<Prepared>) {
        val f = file(filesDir)
        if (list.isEmpty()) {
            f.delete()
            return
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(toJson(list).toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("could not replace $FILE")
        }
    }

    /** Guards reading and rewriting [FILE], which [keep] and [flush] both do. */
    private val fileLock = Any()

    /** Adds [list] to what is waiting (an earlier deletion may still be), dropping anything expired. */
    fun keep(filesDir: File, list: List<Prepared>, now: Long = System.currentTimeMillis()) {
        synchronized(fileLock) { save(filesDir, (load(filesDir) + list).filterNot { expired(it, now) }) }
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()

    /** Whether a deletion is waiting to be sent. */
    fun waiting(context: Context): Boolean = file(context.applicationContext.filesDir).isFile

    /** Sends whatever is waiting, if anything, off the calling thread. Called as the app starts. */
    fun resume(context: Context) {
        val app = context.applicationContext
        if (!waiting(app)) return
        scope.launch { runCatching { flush(app) } }
    }

    /**
     * Tries every waiting deletion once and keeps what is left for the alarm.
     * Not while a wipe runs: it writes this file itself when it ends.
     */
    suspend fun flush(app: Context) {
        sending.withLock {
            if (Wiper.isActive) return
            val now = System.currentTimeMillis()
            val waiting = synchronized(fileLock) { load(app.filesDir) }
            val left = waiting.filterNot { expired(it, now) }.filter { p ->
                // Through Tor or not at all: one signed with Tor on is dropped once Tor is off (see the class comment).
                if (p.viaTor && !TorTransport.enabled) false
                else deliver(p, now) { copy -> ask(p, copy) } == Outcome.RETRY
            }
            val after = synchronized(fileLock) {
                // A deletion kept while these were being sent goes back with what is left.
                val added = load(app.filesDir).filter { it !in waiting }
                (left + added).also { runCatching { save(app.filesDir, it) } }
            }
            if (after.isEmpty()) cancel(app) else arm(app, RETRY_MS)
        }
    }

    /**
     * Sends [list] now, on the calling thread, each request given [callTimeoutSec]
     * (a wipe, for the deletions that must go through Tor while it is still up).
     * Returns what could not go.
     */
    fun deliverNow(list: List<Prepared>, callTimeoutSec: Long): List<Prepared> {
        val now = System.currentTimeMillis()
        return list.filter { p -> !expired(p, now) && deliver(p, now) { copy -> ask(p, copy, callTimeoutSec) } == Outcome.RETRY }
    }

    /**
     * Puts one copy to the relay, on a connection of its own (no pooled session
     * ties it to anything else), checked against the pin it was signed with and
     * through Tor while that is on in this process.
     */
    private fun ask(p: Prepared, copy: Signed, callTimeoutSec: Long = CALL_TIMEOUT_SEC): Answer? {
        val client = RelayTls.pinned(OkHttpClient.Builder()) { p.pin }
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder().url(p.url).delete().apply { for ((name, value) in copy.headers) header(name, value) }.build()
        return try {
            client.newCall(request).execute().use { response ->
                val error = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error").takeIf { it.isNotEmpty() } }.getOrNull()
                Answer(response.code, error, response.headers.getDate("Date")?.time)
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // A header the file mangled: no copy of this one will ever go.
            Answer(400, e.message, null)
        }
    }

    // ── The alarm ───────────────────────────────────────────────────────────

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, ALARM_REQUEST, Intent(app, RelayFarewellAlarm::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Arms the next try [delayMs] from now, replacing any armed before. Allowed in Doze; needs no exact-alarm permission. */
    fun arm(context: Context, delayMs: Long) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, alarmIntent(app))
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app)) }
    }

    /** The alarm's try, from [RelayFarewellAlarm]: the phone kept awake for it, the receiver let go within its grace. */
    internal fun onAlarm(app: Context, pending: BroadcastReceiver.PendingResult) {
        scope.launch {
            var finished = false
            val finish = { if (!finished) { finished = true; pending.finish() } }
            val lock = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clairix:relay-farewell")
                ?.apply { acquire(WAKE_MS) }
            try {
                val run = launch { runCatching { flush(app) } }
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { run.join() }
                finish()
                run.join()
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
                finish()
            }
        }
    }
}

/** A wipe's relay deletion, tried again by an alarm the system keeps whether or not Clairix is running; see [RelayFarewell]. */
class RelayFarewellAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RelayFarewell.onAlarm(context.applicationContext, goAsync())
    }
}
