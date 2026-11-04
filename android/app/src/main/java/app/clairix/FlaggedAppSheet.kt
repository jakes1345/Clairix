package app.clairix

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.AppEvidence
import app.clairix.analysis.StalkwareScanner
import app.clairix.security.AppLock
import app.clairix.security.ShieldThisWindow
import app.clairix.ui.SafetyFirst
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.components.setSensitiveText
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Before a flagged app's settings open. The stalkerware rows, the grant rows and
 * Phone check's rows about one app end in a button to the Settings page where the
 * app is uninstalled, switched off or replaced; removing a monitoring app can tell
 * whoever installed it that it was found, which for some people makes things more
 * dangerous, and takes the evidence with it. So the button asks first, in a sheet:
 * save the evidence now (the default), contact a helpline (the Get help page),
 * then Continue to the settings or Cancel.
 */

/**
 * The button on a stalkerware row that opens [packageName]'s settings with
 * [onOpen]. When [gated] (see [SafetyFirst.gatesSettings]) a tap shows the
 * sheet first and [onOpen] runs only from its "Continue"; otherwise it opens
 * straight away. [flagged] is the scan's record of the app, when it scored one.
 * [compact] draws a small text button for a row of buttons (Phone check)
 * instead of a full-width one.
 */
@Composable
internal fun FlaggedAppButton(
    text: String,
    packageName: String,
    flagged: StalkwareScanner.SuspiciousApp?,
    gated: Boolean = true,
    compact: Boolean = false,
    onOpen: () -> Unit
) {
    var asking by remember(packageName) { mutableStateOf(false) }
    // A sheet is a window of its own, above the lock screen Clairix draws, so
    // locking closes it rather than leave it showing over the lock.
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) asking = false }
    val onClick = { if (gated) asking = true else onOpen() }
    if (compact) {
        GhostButton(text, onClick = onClick)
    } else {
        SecondaryButton(text, onClick = onClick, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
    }
    if (asking && !locked) {
        BeforeYouOpenSheet(
            packageName = packageName,
            flagged = flagged,
            onDismiss = { asking = false },
            onOpen = {
                asking = false
                onOpen()
            }
        )
    }
}

/** Which Phone check rows go through the sheet before their settings open. */
internal object RemovalGate {
    /**
     * The app [f]'s "Open settings" asks about first: its [HealthFact.subject] when the row is a
     * finding (it has a severity) with a page to open; null when it opens straight away.
     */
    fun subjectOf(f: HealthFact): String? = f.subject?.takeIf { f.severity != null && f.fixAction != null }
}

/**
 * Saves the evidence of every app [SafetyFirst.evidencePackages] names onto the
 * timeline, so the evidence report shared next carries them; returns how many
 * were added. An app whose evidence was saved in the last half hour, by this or
 * by "Save evidence now", is not added again, so exporting twice does not double
 * the record. PackageManager reads and disk writes: call off the main thread.
 */
internal fun saveFlaggedEvidence(context: Context, r: StalkwareScanner.Result): Int =
    SafetyFirst.evidencePackages(r).count { pkg ->
        runCatching {
            val evidence = AppEvidence.collect(context, pkg, r.apps.firstOrNull { it.packageName == pkg })
            TimelineLog.record(context, evidence.timelineEvent(), dedupeKey = AppEvidence.ID_PREFIX + pkg)
        }.getOrDefault(false)
    }

/**
 * The sheet's copy of the evidence on the clipboard. Copying is a button of its
 * own, never part of saving: whatever is on the clipboard can be read by anyone
 * who pastes on this phone and by the keyboard in use, which may be the app the
 * evidence is about. The copy is cleared when the sheet closes.
 */
internal object EvidenceClip {
    const val LABEL = "Clairix evidence"

    /**
     * Whether the sheet offers the copy for [packageName]: not when it is an
     * enabled keyboard ([flagged] says so, or the last scan's [enabledImes]),
     * which would be handed a clip that names it.
     */
    fun offersCopy(packageName: String, flagged: StalkwareScanner.SuspiciousApp?, enabledImes: Collection<String>): Boolean =
        flagged?.imeEnabled != true && packageName !in enabledImes

    /**
     * Whether to clear the clipboard as the sheet closes, given that it [copied]
     * and the label of what the clipboard holds now. [label] is null when Android
     * will not say (Clairix is not in front, or the clipboard is empty): it is
     * cleared then, at the cost of anything copied since. A clip with another
     * label is something the owner copied after, and stays.
     */
    fun shouldClear(copied: Boolean, label: CharSequence?): Boolean =
        copied && (label == null || label.toString() == LABEL)
}

/** Clears the clipboard when [EvidenceClip.shouldClear] says to. */
private fun clearEvidenceClip(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val label = runCatching { clipboard.primaryClipDescription?.label }.getOrNull()
    if (EvidenceClip.shouldClear(copied = true, label = label)) runCatching { clipboard.clearPrimaryClip() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeforeYouOpenSheet(
    packageName: String,
    flagged: StalkwareScanner.SuspiciousApp?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val saveFocus = remember { FocusRequester() }
    val label = remember(packageName, flagged) { flagged?.appLabel ?: StalkwareScanner.appLabel(context, packageName) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<AppEvidence?>(null) }
    var copied by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val offersCopy = remember(packageName, flagged) {
        EvidenceClip.offersCopy(packageName, flagged, StalkwareScanner.results.value?.enabledImes.orEmpty())
    }
    val c = ClairixTheme.colors

    // The copy does not outlive the sheet; locking closes the sheet, so it goes then too.
    DisposableEffect(Unit) {
        onDispose { if (copied) clearEvidenceClip(context.applicationContext) }
    }

    // The safe choice is the default one: focus starts on saving the evidence,
    // once the sheet's window is up to take it.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { saveFocus.requestFocus() }
    }

    fun save() {
        if (saving) return
        saving = true
        failure = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val evidence = AppEvidence.collect(context, packageName, flagged)
                    TimelineLog.record(context, evidence.timelineEvent())
                    evidence
                }
            }
            result
                .onSuccess { evidence -> saved = evidence }
                .onFailure { failure = context.getString(R.string.scan_could_not_save_it, it.message ?: it.javaClass.simpleName) }
            saving = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surfaceRaised,
        shape = ClairixShapes.Sheet
    ) {
        ShieldThisWindow()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.scan_before_you_remove_or_switch_off, label),
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.scan_removal_can_alert), style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
            Text(stringResource(R.string.scan_removal_first_steps), style = MaterialTheme.typography.bodyLarge, color = c.textMuted)

            val savedAt = saved?.savedAt
            PrimaryButton(
                if (savedAt == null) stringResource(R.string.scan_save_evidence_now) else stringResource(R.string.scan_save_evidence_again),
                onClick = { save() },
                enabled = !saving,
                loading = saving,
                modifier = Modifier.fillMaxWidth().focusRequester(saveFocus)
            )
            Text(
                stringResource(R.string.scan_its_package_name_who_installed_it),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSubtle
            )
            saved?.let { evidence ->
                Notice(Tone.Safe, stringResource(R.string.scan_saved_at_on_the_timeline, tTime(evidence.savedAt)))
                if (offersCopy) {
                    GhostButton(
                        if (copied) stringResource(R.string.scan_copied) else stringResource(R.string.scan_copy_to_clipboard),
                        onClick = {
                            clipboard.setSensitiveText(EvidenceClip.LABEL, evidence.text())
                            copied = true
                        },
                        tone = Tone.Neutral,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.scan_anyone_who_pastes_on_this_phone),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSubtle
                    )
                }
            }
            failure?.let { Notice(Tone.Danger, it) }

            // The Get help page: helplines for where the phone is, from its SIM or region.
            SecondaryButton(
                stringResource(R.string.scan_contact_a_helpline),
                onClick = {
                    onDismiss()
                    Registry.requestHelp()
                },
                tone = Tone.Accent,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral, modifier = Modifier.weight(1f))
                GhostButton(stringResource(R.string.scan_removal_continue), onClick = onOpen, tone = Tone.Neutral, modifier = Modifier.weight(1f))
            }
        }
    }
}
