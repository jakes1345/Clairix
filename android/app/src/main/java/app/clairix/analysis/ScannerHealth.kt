package app.clairix.analysis

import android.content.Context
import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.TimelineEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether a running scan can hear anything, and what the scanner leaves behind
 * about its own life, as decisions separate from the service that acts on them.
 *
 * A Bluetooth scan registered without `neverForLocation` gets no results while
 * the system Location switch is off: the scan stays registered, nothing fails,
 * and the table empties within half an hour. The scanner then looked healthy
 * with nothing to show, which the hub read as all clear. The same silence
 * follows a stack that has stopped delivering. Both are judged here from what
 * the service knows: whether a scan is registered, whether Location is on,
 * whether the scan is the unfiltered one (screen on), and when the last packet
 * arrived.
 */
object ScannerHealth {

    /** No packet for this long on an unfiltered scan is a scan that hears nothing. */
    const val QUIET_MS = 5 * 60_000L

    /** How often the service notes that it is alive; see [ScanRecord.heartbeat]. */
    const val HEARTBEAT_EVERY_MS = 60_000L

    /**
     * The time of the last packet is published at most this often. Packets arrive
     * every few milliseconds; a status that changed with each one would redraw every
     * screen reading it on every publish cycle.
     */
    const val HEARD_PUBLISH_STEP_MS = 30_000L

    const val LOCATION_OFF = "Location is off, so Android hides Bluetooth scan results. Turn Location on."
    const val QUIET = "Scanning, but nothing has been heard for 5 minutes."

    /** Timeline id prefix of a stop the owner did not ask for; the timeline offers to resume from such a row. */
    const val SYSTEM_STOP_ID_PREFIX = "scan_stop_system@"

    /**
     * Why a registered scan hears nothing, or null while it is healthy. The quiet
     * rule applies only to the unfiltered scan: with the screen off the scan is
     * filtered to tracker signatures, and a bedroom with no tracker in it is
     * silent all night by design. Silence is measured from the later of the last
     * packet and the scan's (re)start, so a fresh scan gets its five minutes.
     */
    fun deafCause(
        scanActive: Boolean,
        locationOn: Boolean,
        unfiltered: Boolean,
        lastPacketAt: Long,
        scanStartedAt: Long,
        now: Long
    ): String? = when {
        !scanActive -> null
        !locationOn -> LOCATION_OFF
        unfiltered && now - maxOf(lastPacketAt, scanStartedAt) > QUIET_MS -> QUIET
        else -> null
    }

    /** Whether [error] is one of this object's own messages rather than a scan failure. */
    fun isHealthMessage(error: String?): Boolean = error == LOCATION_OFF || error == QUIET

    /**
     * The scan status's error after a health check: a cause replaces nothing but
     * an earlier health message (a scan failure is more specific and stays), and
     * a recovery clears only a health message.
     */
    fun reconcileError(current: String?, cause: String?): String? = when {
        cause != null -> if (current == null || isHealthMessage(current)) cause else current
        isHealthMessage(current) -> null
        else -> current
    }

    /**
     * The last-packet time to publish, given the one already [published] and the
     * one just [heard]: moved on once it is [HEARD_PUBLISH_STEP_MS] newer, or when
     * the clock went back, so the screens redraw for it at most twice a minute.
     */
    fun publishedHeard(published: Long, heard: Long): Long =
        if (heard - published >= HEARD_PUBLISH_STEP_MS || heard < published) heard else published

    /** Whether the heartbeat is due: at once, then every [HEARTBEAT_EVERY_MS], and again if the clock went back. */
    fun heartbeatDue(lastWrittenAt: Long, now: Long): Boolean =
        lastWrittenAt == 0L || now - lastWrittenAt >= HEARTBEAT_EVERY_MS || lastWrittenAt > now

    /** How the scanner ended, as recorded by the service's onDestroy. */
    enum class StopReason { USER, SYSTEM }

    data class StopRecord(val reason: StopReason, val at: Long)

    /**
     * When the scanner was lost without the owner stopping it, or null when it was
     * not. Stopping from the app clears [scanEnabled] first, so an enabled scanner
     * that is not running was ended by something else: the service's own record of
     * a system stop when it got to write one, otherwise the last heartbeat (a
     * process killed outright writes nothing on the way out).
     */
    fun systemStopAt(scanEnabled: Boolean, scanning: Boolean, lastHeartbeat: Long, lastStop: StopRecord?): Long? {
        if (!scanEnabled || scanning) return null
        if (lastStop != null && lastStop.at >= lastHeartbeat) {
            return if (lastStop.reason == StopReason.SYSTEM) lastStop.at else null
        }
        return lastHeartbeat.takeIf { it > 0L }
    }

    /**
     * Whether the system stop at [at] is already on record: the service wrote it on
     * its way out, or an earlier return to the app dated it from the heartbeat. Only
     * a stop not yet on record gets its timeline row.
     */
    fun alreadyRecorded(lastStop: StopRecord?, at: Long): Boolean =
        lastStop != null && lastStop.reason == StopReason.SYSTEM && lastStop.at == at

    /** The line the hub and the Trackers hero show for a scanner lost at [stoppedAt]. */
    fun stoppedBySystemLine(stoppedAt: Long): String =
        "Scanner was stopped by the system at ${clock(stoppedAt)} — tap to resume."

    /** The timeline row for a scanner the owner did not stop. */
    fun systemStopEvent(at: Long): TimelineEvent = TimelineEvent(
        id = "$SYSTEM_STOP_ID_PREFIX$at", kind = EventKind.SCAN_STOP, ts = at,
        title = "Scanner was stopped by the system at ${clock(at)}",
        detail = "It was not stopped from Clairix: an update, Android or the phone's battery manager ended it, " +
            "and nothing was watched from then until it was started again. Opening Clairix starts it again; " +
            "letting Clairix run unrestricted in the battery settings makes this rarer.",
        severity = Severity.MEDIUM
    )

    /** The timeline row for a scanner stopped from Clairix. */
    fun userStopEvent(at: Long): TimelineEvent = TimelineEvent(
        id = "scan_stop@$at", kind = EventKind.SCAN_STOP, ts = at,
        title = "Scanning stopped", detail = "Stopped from Clairix.",
        severity = Severity.LOW
    )

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.US)

    /** "HH:MM" of [ts], for the hub and the timeline. */
    fun clock(ts: Long): String = synchronized(clockFmt) { clockFmt.format(Date(ts)) }
}

/**
 * What the scanner leaves in a preferences file of its own about its own life,
 * read after the process that wrote it is gone: a heartbeat while it runs, and
 * how it ended. Its own file, so the minute-by-minute heartbeat does not rewrite
 * the settings file each time.
 */
object ScanRecord {

    private const val PREFS = "scan_record"
    private const val KEY_HEARTBEAT = "last_scan_heartbeat"
    private const val KEY_STOP_REASON = "last_scan_stop_reason"
    private const val KEY_STOPPED_AT = "last_scan_stopped_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The service was alive at [now]. */
    fun heartbeat(context: Context, now: Long) {
        prefs(context).edit().putLong(KEY_HEARTBEAT, now).apply()
    }

    fun lastHeartbeat(context: Context): Long = prefs(context).getLong(KEY_HEARTBEAT, 0L)

    /** [sync] writes before returning, for the service's onDestroy, which the process may not outlive. */
    fun recordStop(context: Context, reason: ScannerHealth.StopReason, at: Long, sync: Boolean = false) {
        val edit = prefs(context).edit().putString(KEY_STOP_REASON, reason.name).putLong(KEY_STOPPED_AT, at)
        if (sync) edit.commit() else edit.apply()
    }

    fun lastStop(context: Context): ScannerHealth.StopRecord? {
        val p = prefs(context)
        val name = p.getString(KEY_STOP_REASON, null) ?: return null
        val reason = ScannerHealth.StopReason.entries.firstOrNull { it.name == name } ?: return null
        return ScannerHealth.StopRecord(reason, p.getLong(KEY_STOPPED_AT, 0L))
    }

    /** When the scanner was lost without the owner stopping it, or null; see [ScannerHealth.systemStopAt]. */
    @Synchronized
    fun systemStopAt(context: Context, scanEnabled: Boolean): Long? =
        ScannerHealth.systemStopAt(scanEnabled, scanning = false, lastHeartbeat(context), lastStop(context))

    /**
     * Puts a system stop the service could not record (a process killed outright,
     * an update) on record, once. Returns its time the first time it is found, for
     * the caller to write its timeline row, and null when there is none or it is
     * already recorded. Synchronized with [systemStopAt], so the service coming
     * back and the app opening at the same moment write one row between them. Call
     * before the scanner's next heartbeat, which would otherwise date it now.
     */
    @Synchronized
    fun claimSystemStop(context: Context, scanEnabled: Boolean): Long? {
        val lastStop = lastStop(context)
        val at = ScannerHealth.systemStopAt(scanEnabled, scanning = false, lastHeartbeat(context), lastStop) ?: return null
        if (ScannerHealth.alreadyRecorded(lastStop, at)) return null
        recordStop(context, ScannerHealth.StopReason.SYSTEM, at)
        return at
    }
}
