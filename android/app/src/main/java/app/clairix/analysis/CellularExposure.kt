package app.clairix.analysis

import android.content.Context
import android.content.pm.PackageManager
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
 * whether the phone has the switch, and whether the phone has actually used a 2G tower,
 * which proves 2G is allowed. The second is recorded here, as a date and the radio type
 * only, sealed like the scanner's other stores and cleared with them.
 */
object CellularExposure {

    private const val FILE = "cellular_exposure.json"
    /** A 2G connection this recent means the switch is on now, as far as anyone can tell. */
    const val RECENT_MS = 30L * 24 * 3_600_000L

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

    fun clear(context: Context) {
        val s = store(context)
        synchronized(s) { s.replace(JSONObject()) }
    }

    /** Whether the modem takes the allowed-network-types mask that the "Allow 2G" switch sets; null without telephony. */
    fun hasSwitch(context: Context): Boolean? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        return runCatching {
            tm.isRadioInterfaceCapabilitySupported(TelephonyManager.CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK)
        }.getOrNull()
    }

    /**
     * The checklist row. [advancedProtection] is true when Android 16's Advanced
     * Protection is on, which blocks 2G whatever the switch says.
     */
    fun fact(context: Context, advancedProtection: Boolean?, now: Long = System.currentTimeMillis()): HealthFact? {
        val switch = hasSwitch(context) ?: return null
        val last = last2g(context)
        val id = "allow_2g"
        val label = "2G"
        val how = "Turn it off: Settings → Network & internet → SIMs → Allow 2G on a Pixel; Settings → Connections → " +
            "Mobile networks → Network mode (or Allow 2G service) on a Samsung. Emergency calls still work."
        return when {
            advancedProtection == true -> HealthFact(id, "Network", label, "Blocked by Advanced Protection")
            last != null && now - last.first <= RECENT_MS -> HealthFact(
                id, "Network", label, "ALLOWED (used ${day(last.first)})", Severity.HIGH,
                "This phone connected to a ${last.second} tower on ${day(last.first)}, so 2G is allowed. 2G is how IMSI " +
                    "catchers work: a fake tower that refuses 4G pushes the phone down to 2G, where the network never proves " +
                    "it is real and calls and texts may go unencrypted. $how",
                fixAction = Settings.ACTION_DATA_ROAMING_SETTINGS
            )
            switch -> HealthFact(
                id, "Network", label, "Switch present, not readable", Severity.LOW,
                "Android keeps the \"Allow 2G\" switch's state from apps, so check it yourself. 2G is how IMSI catchers " +
                    "work: a fake tower that refuses 4G pushes the phone down to it. $how Clairix will mark this row if the " +
                    "phone is ever seen on a 2G tower.",
                fixAction = Settings.ACTION_DATA_ROAMING_SETTINGS
            )
            else -> HealthFact(
                id, "Network", label, "No switch on this phone",
                detail = "This phone's modem cannot be told to refuse 2G. The cell checks still watch for a forced downgrade, " +
                    "and Clairix will mark this row if the phone is ever seen on a 2G tower."
            )
        }
    }

    private fun day(ts: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(ts))
}
