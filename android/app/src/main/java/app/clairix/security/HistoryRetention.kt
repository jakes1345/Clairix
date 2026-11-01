package app.clairix.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import app.clairix.Registry
import app.clairix.ScanService
import app.clairix.ScannerRecordings
import app.clairix.Store
import app.clairix.TimelineLog
import app.clairix.analysis.HiddenCameraScanner
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.Report
import app.clairix.nav.MapTiles
import app.clairix.nav.TypicalSpeedCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * How long the scanner's history is kept: the location log, the timeline (with
 * any photo kept on it), the vehicle trips, the unlock ledger, the hidden-camera
 * sweep's findings, the radio scanner's recordings, the map's tile cache and the
 * navigation's cache of typical road speeds; and what the cell-tower baseline
 * keeps of when.
 *
 * A seized phone gives up what is on it, however well sealed, once it is
 * unlocked under compulsion; what was deleted last month cannot be given up at
 * all. Thirty days by default: enough for a pattern-of-life report and for a
 * tracker to show itself across trips, not a year of where the owner has been.
 * The cell-tower baseline is what "normal" is measured against, so it is not
 * deleted with the history; it keeps which towers are usual, not when the
 * phone was near them: exact times go after two days, and a tower or area not
 * seen for three retention periods is forgotten (see
 * [app.clairix.analysis.IMSICatcher.applyRetention]). An exported evidence
 * report is kept a day for the app it was shared to, whatever the retention.
 *
 * It runs when the setting changes, at each start of the app, every quarter
 * hour while the scanner runs, and once a day on an alarm the system keeps
 * ([HistoryRetentionAlarm]), so history ages out on a phone whose owner has
 * neither the scanner nor the app open (the unlock ledger keeps growing then).
 */
object HistoryRetention {

    const val PREFS = "history_retention"
    private const val K_DAYS = "days"
    /** Days of history kept by default. */
    const val DEFAULT_DAYS = 30
    /** The choice that keeps everything. */
    const val FOREVER = 0
    val CHOICES = listOf(7, 30, 90, FOREVER)

    private const val ALARM_REQUEST = 0x7A11
    private const val ALARM_ACTION = "app.clairix.HISTORY_RETENTION"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun days(context: Context): Int = prefs(context).getInt(K_DAYS, DEFAULT_DAYS)

    fun setDays(context: Context, days: Int) {
        require(days in CHOICES) { "unsupported retention $days" }
        prefs(context).edit().putInt(K_DAYS, days).apply()
    }

    /** [setDays], written before it returns: a duress wipe's decoy keeps the owner's choice and the process ends moments later. */
    internal fun writeDays(context: Context, days: Int) {
        if (days in CHOICES) prefs(context).edit().putInt(K_DAYS, days).commit()
    }

    /** The oldest moment kept, or null when everything is. */
    fun cutoff(context: Context, now: Long = System.currentTimeMillis()): Long? = cutoffFor(days(context), now)

    /** The oldest moment [days] of retention keep at [now], or null for [FOREVER]. */
    internal fun cutoffFor(days: Int, now: Long): Long? =
        if (days == FOREVER) null else now - TimeUnit.DAYS.toMillis(days.toLong())

    /**
     * Deletes what is older than the cutoff from the location log, the vehicle
     * trips, the unlock ledger, the tile cache, the typical-speed cache, the radio
     * scanner's recordings, the hidden-camera sweep's findings and the timeline
     * (a photo kept on an event goes with it); coarsens and thins the cell-tower baseline; deletes exported
     * reports older than a day and store files set aside as unreadable more than 30 days ago. Runs when the setting changes, at each start of
     * the app, every hour while the scanner runs
     * ([app.clairix.analysis.ScanPacing.RETENTION_EVERY_MS]) and once a day by
     * alarm; the location log and the tile cache look at their oldest entry
     * first and stop there when it is inside the retention. Makes sure the
     * daily run is scheduled. File I/O; call off the main thread.
     */
    fun apply(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        ensureScheduled(app)
        // Whatever the retention: a report is shared once, and kept only for the app it went to.
        runCatching { Report.pruneExports(app.cacheDir, now) }
        // Whatever the retention too: a store file that could not be read was set
        // aside, nothing can open it, and after 30 days it goes.
        runCatching { Store.pruneSetAside(app.filesDir, now) }
        val cutoff = cutoff(app, now)
        runCatching { ScanService.applyCellRetention(app, cutoff, now) }
        if (cutoff == null) return
        runCatching { LocationHistory.pruneOlderThan(app, cutoff) }
        runCatching { ScanService.pruneTripsOlderThan(app, cutoff) }
        // In a process the app's screens never opened (this alarm's, the scanner's
        // at boot) the ledger has not been told where its file is yet.
        runCatching {
            UnlockLedger.init(app)
            UnlockLedger.pruneOlderThan(cutoff)
        }
        runCatching { MapTiles.prune(app, cutoff) }
        runCatching { TypicalSpeedCache.prune(app, cutoff, now) }
        // The radio scanner's recordings, by when each was last written.
        runCatching { ScannerRecordings.pruneOlderThan(app, cutoff) }
        runCatching { HiddenCameraScanner.pruneOlderThan(app, cutoff) }
        runCatching {
            val log = TimelineLog.get(app)
            if (log.pruneOlderThan(cutoff) > 0) Registry.publishTimeline(log.snapshot())
        }
    }

    // ── The daily run ───────────────────────────────────────────────────────

    private fun alarmTarget(app: Context): Intent = Intent(app, HistoryRetentionAlarm::class.java).setAction(ALARM_ACTION)

    /**
     * Arms the daily run unless it is armed already. Inexact and not a wakeup:
     * it waits for the phone to be awake anyway, and costs nothing until then.
     * A reboot clears it; the next start arms it again.
     */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val armed = runCatching {
            PendingIntent.getBroadcast(app, ALARM_REQUEST, alarmTarget(app), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        }.getOrNull() != null
        if (armed) return
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY,
                PendingIntent.getBroadcast(app, ALARM_REQUEST, alarmTarget(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }

    /** Cancels the daily run (a wipe: a fresh install has none until it first starts). */
    fun cancelSchedule(context: Context) {
        val app = context.applicationContext
        val pending = runCatching {
            PendingIntent.getBroadcast(app, ALARM_REQUEST, alarmTarget(app), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        }.getOrNull() ?: return
        runCatching { app.getSystemService(AlarmManager::class.java)?.cancel(pending) }
        pending.cancel()
    }

    /** The daily run, from [HistoryRetentionAlarm]. */
    internal fun onAlarm(app: Context, pending: BroadcastReceiver.PendingResult) {
        scope.launch {
            try {
                if (!Wiper.isActive) apply(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The daily retention run, delivered whether or not Clairix is running; see [HistoryRetention]. */
class HistoryRetentionAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HistoryRetention.onAlarm(context.applicationContext, goAsync())
    }
}
