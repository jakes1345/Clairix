package app.clairix.security

import android.content.Context
import android.content.SharedPreferences
import app.clairix.Registry
import app.clairix.ScanService
import app.clairix.TimelineLog
import app.clairix.analysis.LocationHistory
import app.clairix.nav.MapTiles
import java.util.concurrent.TimeUnit

/**
 * How long the scanner's history is kept: the location log, the timeline, the
 * vehicle trips, the unlock ledger and the map's tile cache.
 *
 * A seized phone gives up what is on it, however well sealed, once it is
 * unlocked under compulsion; what was deleted last month cannot be given up at
 * all. Thirty days by default: enough for a pattern-of-life report and for a
 * tracker to show itself across trips, not a year of where the owner has been.
 * The cell-tower baseline is not history and is kept: it is what "normal" is
 * measured against.
 */
object HistoryRetention {

    const val PREFS = "history_retention"
    private const val K_DAYS = "days"
    /** Days of history kept by default. */
    const val DEFAULT_DAYS = 30
    /** The choice that keeps everything. */
    const val FOREVER = 0
    val CHOICES = listOf(7, 30, 90, FOREVER)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun days(context: Context): Int = prefs(context).getInt(K_DAYS, DEFAULT_DAYS)

    fun setDays(context: Context, days: Int) {
        require(days in CHOICES) { "unsupported retention $days" }
        prefs(context).edit().putInt(K_DAYS, days).apply()
    }

    /** The oldest moment kept, or null when everything is. */
    fun cutoff(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val d = days(context)
        return if (d == FOREVER) null else now - TimeUnit.DAYS.toMillis(d.toLong())
    }

    /**
     * Deletes what is older than the cutoff from the location log, the vehicle
     * trips, the unlock ledger and the timeline. Runs when the setting changes,
     * at each start of the app and every quarter hour while the scanner runs.
     * File I/O; call off the main thread.
     */
    fun apply(context: Context) {
        val cutoff = cutoff(context) ?: return
        val app = context.applicationContext
        runCatching { LocationHistory.pruneOlderThan(app, cutoff) }
        runCatching { ScanService.pruneTripsOlderThan(app, cutoff) }
        runCatching { UnlockLedger.pruneOlderThan(cutoff) }
        runCatching { MapTiles.prune(app, cutoff) }
        runCatching {
            val log = TimelineLog.get(app)
            if (log.pruneOlderThan(cutoff) > 0) Registry.publishTimeline(log.snapshot())
        }
    }
}
