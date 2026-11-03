package app.clairix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.AppEvidence
import app.clairix.analysis.CaAuditScanner
import app.clairix.analysis.CameraSection
import app.clairix.analysis.HiddenCameraScanner
import app.clairix.analysis.KeystoreReportSigner
import app.clairix.analysis.Report
import app.clairix.analysis.ReportFormat
import app.clairix.analysis.ReportHeader
import app.clairix.analysis.ReportInput
import app.clairix.analysis.ReportRange
import app.clairix.analysis.ReportSigner
import app.clairix.analysis.ReportSigning
import app.clairix.analysis.SpywareSection
import app.clairix.analysis.StalkwareScanner
import app.clairix.analysis.TripwireState
import app.clairix.analysis.UnlockSection
import app.clairix.security.AppLock
import app.clairix.security.BiometricTripwire
import app.clairix.security.HistoryRetention
import app.clairix.security.UnlockLedger
import app.clairix.ui.SafetyFirst
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Every evidence export goes through here: the Export buttons on the Trackers
 * screen and in Settings, the safety card's "Export the evidence first", Phone
 * check's stalkerware page and the offer before a wipe limit is picked, all behind
 * the same owner check in [EvidenceExportHost]. Each asks for
 * the export ([request]); [EvidenceExportHost], drawn once over the app, shows the
 * period picker and the warning, then reads what Clairix holds, writes the report
 * and the photos that go with it and opens the share sheet.
 */
internal object EvidenceExport {

    private val _pending = MutableStateFlow<ReportFormat?>(null)
    /** The export asked for and not yet shared or cancelled. */
    val pending: StateFlow<ReportFormat?> = _pending.asStateFlow()

    /** Asks for an export in [format]; the warning and the period picker come up over whatever is showing. */
    fun request(format: ReportFormat = ReportFormat.TEXT) {
        _pending.value = format
    }

    fun dismiss() {
        _pending.value = null
    }

    private val signer: ReportSigner = KeystoreReportSigner

    /** The signing key's fingerprint, as the reports print it; null when the Keystore has none to give. Keystore work. */
    fun keyFingerprint(): String? = ReportSigning.key(signer).publicKey?.let(ReportSigning::fingerprint)

    /** "Full, play flavour (playSuite)": the tier, then the flavour the build came from. */
    private fun edition(): String {
        val tier = if (BuildConfig.FULL_ACCESS) "Full" else "Comms"
        val flavour = listOf("play", "foss", "full").firstOrNull { BuildConfig.FLAVOR.startsWith(it) } ?: BuildConfig.FLAVOR
        return "$tier, $flavour flavour (${BuildConfig.FLAVOR})"
    }

    private fun header(context: Context, now: Long): ReportHeader = ReportHeader(
        generatedAt = now,
        zone = TimeZone.getDefault(),
        appVersion = BuildConfig.VERSION_NAME,
        appBuild = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        packageName = context.packageName,
        edition = edition(),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH,
        retentionDays = HistoryRetention.days(context),
        followThresholdM = AppSettings.followThresholdM,
        persistenceThresholdMin = AppSettings.persistenceThresholdMin,
        signing = ReportSigning.key(signer)
    )

    /**
     * What Clairix holds now, for a report over [range]. Each source is read on its
     * own, so one that fails leaves its section saying so rather than losing the
     * report. Runs the stalkerware and trust-store checks when they have not run in
     * this process. Blocking; call off the main thread.
     */
    private fun input(app: Context, range: ReportRange): ReportInput {
        val timeline = runCatching { TimelineLog.get(app).snapshot() }.getOrElse { Registry.timeline.value }
        val full = BuildConfig.FULL_ACCESS
        return ReportInput(
            status = Registry.status.value,
            detections = Registry.detections.value,
            timeline = timeline,
            cell = Registry.cell.value,
            nfc = Registry.nfc.value,
            wifi = Registry.wifi.value,
            spyware = if (full) runCatching { spyware(app) }.getOrNull() else null,
            health = if (full) Registry.phoneHealth.value else null,
            tripwire = if (full) runCatching { tripwire(app, timeline) }.getOrNull() else null,
            unlocks = if (full) runCatching { unlocks(app, range) }.getOrNull() else null,
            trustStore = if (full) runCatching { CaAuditScanner.scan(app) }.getOrNull() else null,
            cameras = if (full) runCatching { cameras(app) }.getOrNull() else null
        )
    }

    /** The evidence for every app the stalkerware check flags or does not vouch for, read fresh. */
    private fun spyware(app: Context): SpywareSection {
        val r = StalkwareScanner.results.value ?: StalkwareScanner.scan(app)
        val apps = SafetyFirst.evidencePackages(r).map { pkg ->
            AppEvidence.collect(app, pkg, r.apps.firstOrNull { it.packageName == pkg })
        }
        return SpywareSection(r.scannedAt, apps)
    }

    private fun tripwire(app: Context, timeline: List<TimelineEvent>): TripwireState {
        val fired = timeline.filter { it.kind == EventKind.BIOMETRIC_ENROLLED }.maxOfOrNull { it.ts }
            ?: Registry.biometricTripwire.value?.timestamp ?: 0L
        return TripwireState(BiometricTripwire.armedAt(app), fired)
    }

    private fun unlocks(app: Context, range: ReportRange): UnlockSection {
        UnlockLedger.init(app)
        val window = if (UnlockLedger.sleepWindowAnswered(app)) {
            "${clock(UnlockLedger.sleepStartMinutes(app))} to ${clock(UnlockLedger.sleepEndMinutes(app))}"
        } else {
            null
        }
        return UnlockSection(
            sleepWindow = window,
            adminActive = UnlockLedger.isAdminActive(app),
            usageAccess = UnlockLedger.hasUsageAccess(app),
            anomalies = UnlockLedger.sleepAnomalies(range.from ?: 0L)
        )
    }

    private fun clock(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun cameras(app: Context): CameraSection {
        val record = HiddenCameraScanner.reportRecord(app)
        val steps = HiddenCameraScanner.SWEEP_STEPS.map { it.id }
        return CameraSection(record.lastRunTs, steps.count { it in record.done }, steps.size, record.findings)
    }

    /**
     * Writes the report over [range] in [format], with the photos kept in it, and
     * returns the share intent. Keystore, package, file and crypto work; call off
     * the main thread.
     */
    fun intent(context: Context, format: ReportFormat, range: ReportRange, now: Long = System.currentTimeMillis()): Intent {
        val app = context.applicationContext
        val header = header(app, now)
        val input = input(app, range)
        return when (format) {
            ReportFormat.TEXT -> Report.share(app, header, input, range, ReportFormat.TEXT, signer)
            ReportFormat.JSON -> Report.shareJson(app, header, input, range, signer)
        }
    }
}

/**
 * The two Export buttons, text and JSON, side by side. The owner check is the
 * host's ([EvidenceExportHost]), so every way into an export asks for it.
 */
@Composable
internal fun EvidenceExportButtons(textLabel: String, modifier: Modifier = Modifier, primary: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val exportText = { EvidenceExport.request(ReportFormat.TEXT) }
        if (primary) {
            PrimaryButton(textLabel, onClick = exportText, icon = Icons.Filled.Share, modifier = Modifier.fillMaxWidth())
        } else {
            SecondaryButton(textLabel, onClick = exportText, icon = Icons.Filled.Share, modifier = Modifier.fillMaxWidth())
        }
        SecondaryButton(
            "Export as JSON",
            onClick = { EvidenceExport.request(ReportFormat.JSON) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Drawn once over the app: the pre-share dialog for an export asked for anywhere.
 * Locking drops the request, so an unlock someone else makes the owner do does
 * not open on it; leaving Clairix takes back the owner's confirmation
 * ([ExportOwnerCheck]).
 */
@Composable
internal fun EvidenceExportHost() {
    val pending by EvidenceExport.pending.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val passcodeSet by AppLock.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) EvidenceExport.dismiss() }
    // With a passcode set, every export asks for it (or a biometric) first, whichever
    // button asked for it: the Export buttons, the safety card, Phone check's
    // stalkerware page or the offer before a wipe limit is picked.
    val check = remember { ExportOwnerCheck() }
    LaunchedEffect(pending) { if (pending == null) check.exportEnded() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { check.left() }
    val format = pending ?: return
    if (locked) return
    if (!check.passes(passcodeSet)) {
        OwnerCheckDialog(
            purpose = "export the evidence report",
            onConfirmed = { check.ownerConfirmed() },
            onDismiss = { EvidenceExport.dismiss() }
        )
        return
    }
    EvidenceExportDialog(format, onDone = { EvidenceExport.dismiss() })
}

/**
 * The owner check in front of an export ([EvidenceExportHost]), as things happen
 * to it. With a passcode set, an export waits for the owner to confirm. Leaving
 * Clairix, even for a moment and well inside the relock delay, takes the
 * confirmation back, as it closes the pages behind [OwnerGate]: an export left
 * open at its period picker asks again when Clairix comes back, so whoever picks
 * up the unlocked phone next cannot share it. An export that ends (shared,
 * cancelled, dropped by the lock) leaves nothing confirmed for the next one.
 */
@Stable
internal class ExportOwnerCheck {
    private var confirmed by mutableStateOf(false)

    /** Whether the export may go past the check now. */
    fun passes(passcodeSet: Boolean): Boolean = !passcodeSet || confirmed

    fun ownerConfirmed() {
        confirmed = true
    }

    /** Clairix left the screen. */
    fun left() {
        confirmed = false
    }

    /** The export was shared, cancelled or dropped. */
    fun exportEnded() {
        confirmed = false
    }
}

private enum class RangeEnd { FROM, TO }

@Composable
private fun EvidenceExportDialog(format: ReportFormat, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ClairixTheme.colors
    val zone = remember { TimeZone.getDefault() }
    val now = remember { System.currentTimeMillis() }
    val retention = remember { HistoryRetention.days(context) }
    var range by remember { mutableStateOf(ReportRange()) }
    var picking by remember { mutableStateOf<RangeEnd?>(null) }
    var working by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { fingerprint = withContext(Dispatchers.IO) { runCatching { EvidenceExport.keyFingerprint() }.getOrNull() } }

    ClairixDialog(
        title = if (format == ReportFormat.JSON) "Export as JSON" else "Export evidence report",
        text = Report.preShareWarning(range, retention, now),
        confirmLabel = if (working) "Preparing…" else "Share",
        confirmEnabled = !working,
        onConfirm = {
            working = true
            scope.launch {
                val made = runCatching { withContext(Dispatchers.IO) { EvidenceExport.intent(context, format, range) } }
                working = false
                made.onSuccess { intent ->
                    val chooser = Intent.createChooser(intent, "Share evidence report")
                    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(chooser) }
                }.onFailure {
                    Toast.makeText(context, "Could not export the evidence report", Toast.LENGTH_LONG).show()
                }
                onDone()
            }
        },
        onDismiss = { if (!working) onDone() }
    ) {
        Text("Period", style = MaterialTheme.typography.titleSmall, color = c.text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                "From ${range.from?.let { dayLabel(it, zone) } ?: "the oldest kept"}",
                onClick = { picking = RangeEnd.FROM },
                enabled = !working,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                "To ${range.to?.let { dayLabel(it, zone) } ?: "now"}",
                onClick = { picking = RangeEnd.TO },
                enabled = !working,
                modifier = Modifier.weight(1f)
            )
        }
        if (range.from != null || range.to != null) {
            GhostButton("Everything kept", onClick = { range = ReportRange() }, enabled = !working)
        }
        Text(
            fingerprint?.let { "The report is signed on this phone. Key fingerprint: $it" }
                ?: "The report carries a SHA-256 of its text; this phone's signing key is not available, so it is not signed.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSubtle
        )
    }

    picking?.let { end ->
        RangeDatePicker(
            initial = (if (end == RangeEnd.FROM) range.from else range.to)?.let { ReportRange.pickerDay(it, zone) },
            today = ReportRange.pickerDay(now, zone),
            onPicked = { day ->
                range = when (end) {
                    RangeEnd.FROM -> range.copy(from = ReportRange.dayStart(day, zone))
                    RangeEnd.TO -> range.copy(to = ReportRange.dayEnd(day, zone))
                }.ordered()
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}

/** "3 Oct 2026", in the phone's own time. */
private fun dayLabel(ts: Long, zone: TimeZone): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply { timeZone = zone }.format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDatePicker(initial: Long?, today: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial ?: today,
        selectableDates = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= today
            }
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { GhostButton("OK", onClick = { state.selectedDateMillis?.let(onPicked) ?: onDismiss() }) },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss) }
    ) {
        DatePicker(state = state)
    }
}
