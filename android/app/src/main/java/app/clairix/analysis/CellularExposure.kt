package app.clairix.analysis

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.telephony.TelephonyManager
import app.clairix.HealthFact
import app.clairix.Rat
import app.clairix.ServingCell
import app.clairix.Severity
import app.clairix.Store
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether this phone will still talk 2G, the path every cheap IMSI catcher uses: a fake
 * tower that refuses 4G and 5G pushes the phone down to 2G, where the network never
 * proves who it is and the call or text may not be encrypted at all.
 *
 * Android keeps the "Allow 2G" switch's state from apps (reading it needs a system-only
 * permission, and the null-cipher setting is hidden outright). What an app can know:
 * whether the phone has the switch; whether a device policy (or Android 16's Advanced
 * Protection) forbids 2G, which apps can read from Android 14; whether the phone has
 * actually used a 2G tower, which proves 2G was allowed at that moment; and what the
 * owner says. The row is judged from all four ([verdict]).
 *
 * An earlier version kept the row at "ALLOWED" for thirty days after any 2G tower and
 * offered no way out but clearing all data, so an owner who did the right thing and
 * turned 2G off was told for a month that it was still on. Now a sighting counts for a
 * week, the owner can say they turned it off, and from then the row believes them
 * until the phone uses a 2G tower again. The sighting stays as history either way.
 *
 * Recorded here: the last 2G sighting (a date and the radio type only) and when the
 * owner said 2G was off, sealed like the scanner's other stores and cleared with them.
 */
object CellularExposure {

    internal const val FILE = "cellular_exposure.json"

    /** A 2G connection this recent, with nothing from the owner since, reads as the switch still on. */
    const val RECENT_MS = 7L * 24 * 3_600_000L

    /** 2G seen this soon after the owner said it was off is the radio moving over, not the switch back on. */
    const val SAID_OFF_GRACE_MS = 2 * 60_000L

    const val FACT_ID = "allow_2g"

    /** The row pointing at Android's own cellular protections; informational, as apps cannot read them. */
    const val NOTIFICATIONS_FACT_ID = "cell_security"

    /** The owner's answer on the 2G row that the switch is off; see [answer]. */
    const val ANSWER_SAID_OFF = "allow_2g_said_off"

    @Volatile private var store: Store? = null

    private fun store(context: Context): Store =
        store ?: synchronized(this) { store ?: Store(context.applicationContext, FILE).also { store = it } }

    fun is2g(rat: Rat): Boolean = rat == Rat.GSM || rat == Rat.CDMA

    /** Records that the phone is on a 2G tower right now. Cheap; call on every cell sample. File I/O when it is 2G. */
    fun noteServing(context: Context, cell: ServingCell, now: Long = System.currentTimeMillis()) {
        if (!is2g(cell.rat)) return
        val s = store(context)
        synchronized(s) {
            s.load { JSONObject() }
            s.json.put("last2gAt", now).put("last2gRat", cell.rat.label)
            s.markDirty()
            s.flush()
        }
    }

    /** When the phone last used 2G, and on which kind of 2G; null when it never has (as far as this app saw). */
    fun last2g(context: Context): Pair<Long, String>? {
        val s = store(context)
        return synchronized(s) {
            val o = s.load { JSONObject() }
            val at = o.optLong("last2gAt", 0L)
            if (at <= 0L) null else at to o.optString("last2gRat", "2G")
        }
    }

    /** Records that the owner says 2G is off now. The last sighting is kept: it is history, not overwritten. File I/O. */
    fun saidOff(context: Context, now: Long = System.currentTimeMillis()) {
        val s = store(context)
        synchronized(s) {
            s.load { JSONObject() }
            s.json.put("saidOffAt", now)
            s.markDirty()
            s.flush()
        }
    }

    /** When the owner last said 2G was off; null when they never have. */
    fun saidOffAt(context: Context): Long? {
        val s = store(context)
        return synchronized(s) {
            s.load { JSONObject() }.optLong("saidOffAt", 0L).takeIf { it > 0L }
        }
    }

    /** Acts on a checklist row's answer; false when [id] is not one of this row's. File I/O. */
    fun answer(context: Context, id: String, now: Long = System.currentTimeMillis()): Boolean {
        if (id != ANSWER_SAID_OFF) return false
        saidOff(context, now)
        return true
    }

    fun clear(context: Context) {
        val s = store(context)
        synchronized(s) {
            // The owner's clear: the file goes even if this run could not read it.
            s.discardUnread()
            s.replace(JSONObject())
        }
    }

    /** Whether the modem takes the allowed-network-types mask that the "Allow 2G" switch sets; null without telephony. */
    fun hasSwitch(context: Context): Boolean? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        return runCatching {
            // TelephonyManager.CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK is a @SystemApi constant; the
            // method is public. It is what the Settings app checks before it shows "Allow 2G" (a carrier
            // can still hide the switch).
            tm.isRadioInterfaceCapabilitySupported("CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK")
        }.getOrNull()
    }

    /**
     * Whether a device policy forbids 2G: the DISALLOW_CELLULAR_2G user restriction a
     * device owner can set from Android 14. Unlike the owner's own switch, apps can read
     * it. Emergency calls may still use 2G under it.
     */
    fun policyBlocks2g(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            context.getSystemService(UserManager::class.java)?.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G) == true
        }.getOrDefault(false)
    }

    enum class Verdict {
        /** Android 16's Advanced Protection blocks 2G whatever the switch says. */
        BLOCKED_ADVANCED_PROTECTION,
        /** A device policy forbids 2G. */
        BLOCKED_POLICY,
        /** The phone used 2G within [RECENT_MS], and the owner has not said otherwise since. */
        ALLOWED,
        /** The owner said 2G is off, and the phone has not used a 2G tower since. */
        SAID_OFF,
        /** The switch exists and nothing current says which way it is set. */
        NOT_READABLE,
        /** The modem cannot be told to refuse 2G. */
        NO_SWITCH
    }

    /**
     * The row's verdict from what can be known at [now]. A 2G sighting is evidence for
     * [RECENT_MS]. The owner saying 2G is off stands until the phone uses 2G after it
     * (with [SAID_OFF_GRACE_MS] for the radio to move over); a sighting before the owner
     * spoke is history and does not count against them.
     */
    fun verdict(
        hasSwitch: Boolean,
        advancedProtection: Boolean?,
        policyBlocks: Boolean,
        last2gAt: Long?,
        saidOffAt: Long?,
        now: Long
    ): Verdict {
        val contradicted = last2gAt != null && saidOffAt != null && last2gAt > saidOffAt + SAID_OFF_GRACE_MS
        val saidOffStands = saidOffAt != null && !contradicted
        val recent = last2gAt != null && now - last2gAt <= RECENT_MS
        return when {
            advancedProtection == true -> Verdict.BLOCKED_ADVANCED_PROTECTION
            policyBlocks -> Verdict.BLOCKED_POLICY
            recent && !saidOffStands -> Verdict.ALLOWED
            saidOffStands && hasSwitch -> Verdict.SAID_OFF
            hasSwitch -> Verdict.NOT_READABLE
            else -> Verdict.NO_SWITCH
        }
    }

    /**
     * The checklist row for [verdict]. [last2g] is the last sighting and its radio type,
     * [saidOffAt] when the owner said 2G was off, and [platformWarnings] whether this
     * Android release has its own cellular protections to point at (14 and later).
     */
    fun row(verdict: Verdict, hasSwitch: Boolean, last2g: Pair<Long, String>?, saidOffAt: Long?, platformWarnings: Boolean): HealthFact {
        val label = "2G"
        val how = "Turn it off: Settings → Network & internet → SIMs → Allow 2G on a Pixel; Settings → Connections → " +
            "Mobile networks → Network mode (or Allow 2G service) on a Samsung. Emergency calls still work."
        val why = "2G is how IMSI catchers work: a fake tower that refuses 4G pushes the phone down to 2G, where the " +
            "network never proves it is real and calls and texts may go unencrypted."
        val warnings = if (platformWarnings) " Turn on Android's own cell network warnings too (the row below)." else ""
        val history = last2g?.let { " The phone last used a ${it.second} tower on ${day(it.first)}." } ?: ""
        return when (verdict) {
            Verdict.BLOCKED_ADVANCED_PROTECTION -> HealthFact(FACT_ID, "Network", label, "Blocked by Advanced Protection")
            Verdict.BLOCKED_POLICY -> HealthFact(
                FACT_ID, "Network", label, "Blocked by a device policy",
                detail = "A policy set on this phone turns 2G off, so the switch cannot turn it back on. Emergency calls " +
                    "can still use 2G.$history"
            )
            Verdict.ALLOWED -> {
                // ALLOWED comes from a sighting; without one there is nothing to say it with.
                val seen = last2g ?: return row(
                    if (hasSwitch) Verdict.NOT_READABLE else Verdict.NO_SWITCH, hasSwitch, null, saidOffAt, platformWarnings
                )
                val saidOn = saidOffAt?.takeIf { seen.first > it + SAID_OFF_GRACE_MS }
                when {
                    !hasSwitch -> HealthFact(
                        FACT_ID, "Network", label, "ALLOWED (used ${day(seen.first)})", Severity.HIGH,
                        "This phone connected to a ${seen.second} tower on ${day(seen.first)}, and its modem cannot be " +
                            "told to refuse 2G. $why The cell checks watch for a forced downgrade.$warnings"
                    )
                    saidOn != null -> HealthFact(
                        FACT_ID, "Network", label, "ALLOWED (used ${day(seen.first)})", Severity.HIGH,
                        "You said on ${day(saidOn)} that 2G was off, but the phone connected to a ${seen.second} tower " +
                            "on ${day(seen.first)}, so 2G is allowed again (an emergency call is the one exception the " +
                            "switch makes). $why $how$warnings",
                        fixAction = Settings.ACTION_DATA_ROAMING_SETTINGS,
                        answer = HealthFact.Answer(ANSWER_SAID_OFF, "I turned 2G off")
                    )
                    else -> HealthFact(
                        FACT_ID, "Network", label, "ALLOWED (used ${day(seen.first)})", Severity.HIGH,
                        "This phone connected to a ${seen.second} tower on ${day(seen.first)}, so 2G was allowed then. " +
                            "$why $how Then tap \"I turned 2G off\": the row stops saying ALLOWED, and turns red again " +
                            "only if the phone uses a 2G tower after that.$warnings",
                        fixAction = Settings.ACTION_DATA_ROAMING_SETTINGS,
                        answer = HealthFact.Answer(ANSWER_SAID_OFF, "I turned 2G off")
                    )
                }
            }
            Verdict.SAID_OFF -> HealthFact(
                FACT_ID, "Network", label, saidOffAt?.let { "Off, you said on ${day(it)}" } ?: "Off, you said",
                detail = "Android does not let apps read the switch, so Clairix takes your word and keeps watching while " +
                    "the scanner runs: if the phone uses a 2G tower again, this row turns red.$history"
            )
            Verdict.NOT_READABLE -> HealthFact(
                FACT_ID, "Network", label, "Switch present, not readable", Severity.LOW,
                "Android keeps the \"Allow 2G\" switch's state from apps, so check it yourself. $why $how Clairix " +
                    "marks this row red if the phone uses a 2G tower.$history If the switch is off, tap \"2G is off\" " +
                    "and the row will say so.$warnings",
                fixAction = Settings.ACTION_DATA_ROAMING_SETTINGS,
                answer = HealthFact.Answer(ANSWER_SAID_OFF, "2G is off")
            )
            Verdict.NO_SWITCH -> HealthFact(
                FACT_ID, "Network", label, "No switch on this phone",
                detail = "This phone's modem cannot be told to refuse 2G. The cell checks still watch for a forced " +
                    "downgrade, and Clairix will mark this row if the phone is seen on a 2G tower.$history"
            )
        }
    }

    /**
     * The row pointing at Android's own protections, which an app can neither read nor
     * set: from Android 14 a setting that refuses unencrypted connections, and from
     * Android 15 notifications when a network turns encryption off or asks for the
     * phone's identity, each on phones whose modem supports it.
     */
    fun notificationsRow(): HealthFact = HealthFact(
        NOTIFICATIONS_FACT_ID, "Network", "Android's cell warnings", "Not readable",
        detail = "Android 14 added a setting that refuses unencrypted mobile connections, and Android 15 notifications " +
            "for when a network turns encryption off or asks for your phone's identity (IMSI or IMEI) before encrypting, " +
            "on phones whose modem supports them. Open Settings and search for \"network security\"; if the page is " +
            "there, turn them on. Android does not let apps see whether they are on, so Clairix cannot check.",
        fixAction = Settings.ACTION_SECURITY_SETTINGS
    )

    /**
     * The checklist rows: 2G, and on Android 14 and later the pointer to Android's own
     * cellular protections. [advancedProtection] is true when Android 16's Advanced
     * Protection is on, which blocks 2G whatever the switch says. Empty without telephony.
     */
    fun facts(context: Context, advancedProtection: Boolean?, now: Long = System.currentTimeMillis()): List<HealthFact> {
        val switch = hasSwitch(context) ?: return emptyList()
        val last = last2g(context)
        val saidOff = saidOffAt(context)
        val platformWarnings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val v = verdict(switch, advancedProtection, policyBlocks2g(context), last?.first, saidOff, now)
        val out = ArrayList<HealthFact>(2)
        out += row(v, switch, last, saidOff, platformWarnings)
        if (platformWarnings) out += notificationsRow()
        return out
    }

    internal fun day(ts: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(ts))
}
