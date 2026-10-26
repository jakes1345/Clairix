package app.clairix.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.clairix.comms.KeystoreBox
import kotlin.math.abs

/**
 * The relay's clock as last seen, kept for the Device tab's clock-integrity row.
 *
 * Every relay response carries a `Date` header, and the WebSocket handshake is
 * the one that arrives on every (re)connection. [app.clairix.comms.RelayClient]
 * reads it to sign requests with the relay's time; it is also handed here, with
 * the phone's own time at the same instant, so a phone whose clock has been
 * moved can be told so even when the relay is not reachable right now.
 *
 * A clock that is off by more than a few seconds with automatic time switched on
 * is unusual; with it switched off, it is how certificate checks, one-time
 * codes and "this message is from an hour ago" are quietly broken.
 */
object RelayClock {

    internal const val PREFS_NAME = "relay_clock"
    private const val K_SERVER_TS = "server_ts"
    private const val K_LOCAL_TS = "local_ts"
    /** Both times, sealed under the comms Keystore key: in the clear they said when this phone last reached its relay. */
    private const val K_SEALED = "sample"
    /** A sample is written at most this often, unless the drift moved by more than [REWRITE_DRIFT_MS]. */
    private const val REWRITE_MS = 10 * 60_000L
    private const val REWRITE_DRIFT_MS = 5_000L

    /** Drift beyond this is reported as a HIGH health fact. */
    const val DRIFT_WARN_MS = 30_000L

    /** A relay timestamp and what this phone's clock read when it arrived. */
    data class Sample(val serverTs: Long, val localTs: Long) {
        /** Relay time minus phone time: positive when the phone is behind. */
        val driftMs: Long get() = serverTs - localTs
        val drifted: Boolean get() = abs(driftMs) > DRIFT_WARN_MS
    }

    @Volatile
    private var appContext: Context? = null

    /** Call once per process; [record] is a no-op until it has run. */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile private var latest: Sample? = null
    @Volatile private var written: Sample? = null

    /** Stores [serverMs] against [localMs]; called from the relay client on every response. */
    fun record(serverMs: Long, localMs: Long = System.currentTimeMillis()) {
        val ctx = appContext ?: return
        val sample = Sample(serverMs, localMs)
        latest = sample
        val last = written
        if (last != null && localMs - last.localTs < REWRITE_MS && abs(sample.driftMs - last.driftMs) < REWRITE_DRIFT_MS) return
        runCatching {
            val sealed = KeystoreBox.encryptString("$serverMs,$localMs")
            prefs(ctx).edit()
                .putString(K_SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .remove(K_SERVER_TS).remove(K_LOCAL_TS)
                .apply()
            written = sample
        }
    }

    /** The most recent sample, or null when the relay has never answered on this install. */
    fun lastSample(context: Context): Sample? = latest ?: runCatching {
        val p = prefs(context)
        p.getString(K_SEALED, null)?.let { blob ->
            val (server, local) = KeystoreBox.decryptString(Base64.decode(blob, Base64.NO_WRAP)).split(',').map { it.toLong() }
            return@runCatching Sample(server, local)
        }
        // Written in the clear by an earlier version; sealed on the next record.
        val server = p.getLong(K_SERVER_TS, 0L)
        val local = p.getLong(K_LOCAL_TS, 0L)
        if (server == 0L || local == 0L) null else Sample(server, local)
    }.getOrNull()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
