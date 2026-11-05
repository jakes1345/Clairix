package app.clairix.analysis

import app.clairix.CellStatus
import app.clairix.Detection
import app.clairix.NfcTag
import app.clairix.PhoneHealth
import app.clairix.ScanStatus
import app.clairix.TimelineEvent
import app.clairix.UnlockEvent
import app.clairix.WifiAnomaly
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/*
 * The evidence report's inputs as plain data. EvidenceExport (app.clairix) reads
 * them off the phone; Report turns them into text or JSON, which is tested on the
 * JVM with a fixed clock, zone and inputs.
 */

/** Which file an export writes. */
enum class ReportFormat(val extension: String, val mimeType: String) {
    TEXT("txt", "text/plain"),
    JSON("json", "application/json")
}

/**
 * The period the owner chose to export, both ends inclusive; a null end is open.
 * The default covers everything Clairix has kept.
 */
data class ReportRange(val from: Long? = null, val to: Long? = null) {

    fun contains(ts: Long): Boolean = (from == null || ts >= from) && (to == null || ts <= to)

    /** Whether something present from [start] to [end] was there at any moment of the range. */
    fun overlaps(start: Long, end: Long): Boolean = (from == null || end >= from) && (to == null || start <= to)

    /** The same range with its ends swapped when the owner picked them the wrong way round. */
    fun ordered(): ReportRange = if (from != null && to != null && from > to) ReportRange(dayStartOf(to), dayEndOf(from)) else this

    private fun dayStartOf(endOfDay: Long): Long = endOfDay - DAY_MS + 1

    private fun dayEndOf(startOfDay: Long): Long = startOfDay + DAY_MS - 1

    companion object {
        private const val DAY_MS = 24 * 60 * 60_000L

        /**
         * The first millisecond, in [zone], of the calendar day a date picker
         * reports as [utcMidnight] (the picker gives the day as UTC midnight).
         */
        fun dayStart(utcMidnight: Long, zone: TimeZone): Long {
            val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
            return Calendar.getInstance(zone).apply {
                clear()
                set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }

        /** The last millisecond, in [zone], of that day. */
        fun dayEnd(utcMidnight: Long, zone: TimeZone): Long = dayStart(utcMidnight + DAY_MS, zone) - 1

        /** The day containing [ts] in [zone], as the UTC midnight a date picker uses. */
        fun pickerDay(ts: Long, zone: TimeZone): Long {
            val local = Calendar.getInstance(zone).apply { timeInMillis = ts }
            return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
    }
}

/**
 * The key the report is signed with, as the header prints it: the algorithm and
 * the public key (SubjectPublicKeyInfo, DER, base64), or why there is none.
 */
data class SigningKey(
    val algorithm: String,
    val publicKey: String?,
    val unavailable: String? = null,
    /**
     * The key's attestation certificate chain from the Android Keystore, base64 DER, the key's own
     * certificate first; empty when it has none (a key made before Clairix asked for attestation).
     */
    val attestation: List<String> = emptyList(),
    /** What that chain shows, in words ([ReportSigning.attestationNote]). */
    val attestationNote: String? = null,
)

/** What the REPORT block says about the report, the app, the phone and the settings in force. */
data class ReportHeader(
    val generatedAt: Long,
    val zone: TimeZone,
    val appVersion: String,
    val appBuild: Int,
    val buildType: String,
    val packageName: String,
    /** "Full, play flavour (playSuite)". */
    val edition: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    /** Days of history kept; [app.clairix.security.HistoryRetention.FOREVER] (0) keeps everything. */
    val retentionDays: Int,
    /** The owner's own thresholds, as set in Settings. */
    val followThresholdM: Float,
    val persistenceThresholdMin: Float,
    val signing: SigningKey
)

/** The stalkerware check as it stood at export: when it ran, and the evidence for every app it flagged or does not vouch for. */
data class SpywareSection(val scannedAt: Long, val apps: List<AppEvidence>)

/**
 * The unlock ledger's sleep-window anomalies in the range. [sleepWindow] is the
 * window in words ("23:00 to 07:00"), or null while the owner has not given one,
 * in which case the ledger judges nothing.
 */
data class UnlockSection(
    val sleepWindow: String?,
    val adminActive: Boolean,
    val usageAccess: Boolean,
    val anomalies: List<UnlockEvent>
)

/** The biometric tripwire: when its key was armed (0: never) and when it last fired (0: never). */
data class TripwireState(val armedAt: Long, val lastTripAt: Long)

/** The hidden-camera sweep's record: when it last ran, the checklist and the findings. */
data class CameraSection(
    val lastRunTs: Long,
    val stepsDone: Int,
    val stepsTotal: Int,
    val findings: List<CameraFinding>
)

/** Everything a report says, read off the phone at export. */
data class ReportInput(
    val status: ScanStatus,
    val detections: List<Detection>,
    val timeline: List<TimelineEvent>,
    val cell: CellStatus?,
    val nfc: List<NfcTag> = emptyList(),
    val wifi: List<WifiAnomaly> = emptyList(),
    val spyware: SpywareSection? = null,
    val health: PhoneHealth? = null,
    val tripwire: TripwireState? = null,
    val unlocks: UnlockSection? = null,
    val trustStore: CaAudit? = null,
    val cameras: CameraSection? = null,
    /**
     * The file name each kept photo has in the share, by the id of the timeline
     * event it belongs to. A photo on an event in the range that is not here was
     * kept but could not be opened.
     */
    val photos: Map<String, String> = emptyMap(),
    /** Each [timeline] event's place in the hash chain, in the same order; null for one without a link. */
    val timelineLinks: List<TimelineChain.Link?> = emptyList(),
    /** The chain checked at export ([TimelineChain.verify]); null when the timeline could not be read. */
    val chain: TimelineChain.Verification? = null
)

/**
 * Times as the report writes them: UTC first, so reports from anywhere line up,
 * then the phone's own time and offset. One per report; not thread-safe.
 */
internal class ReportTime(private val zone: TimeZone) {

    private val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val isoLocal = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = zone }

    /** "2026-11-01 14:02:11 UTC (2026-11-01 15:02:11 local, UTC+01:00)". */
    fun text(ts: Long): String = "${utc.format(Date(ts))} UTC (${local.format(Date(ts))} local, UTC${offset(ts)})"

    /** "2026-11-01T14:02:11Z". */
    fun isoUtc(ts: Long): String = isoUtc.format(Date(ts))

    /** "2026-11-01T15:02:11+01:00": the same moment in the phone's time, with its offset. */
    fun isoLocal(ts: Long): String = isoLocal.format(Date(ts)) + offset(ts)

    /** "+01:00", "-05:00", "+00:00": the zone's offset from UTC at [ts]. */
    fun offset(ts: Long): String {
        val minutes = zone.getOffset(ts) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        return String.format(Locale.US, "%s%02d:%02d", sign, abs(minutes) / 60, abs(minutes) % 60)
    }
}
