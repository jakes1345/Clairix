package app.clairix

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.AppEvidence
import app.clairix.analysis.StalkwareScanner
import app.clairix.security.AppLock
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
 * Before a flagged app's settings open. The stalkerware rows end in a button to
 * the app's own settings page, where Uninstall is; removing a monitoring app can
 * tell whoever installed it that it was found, and takes the evidence with it.
 * So the button asks first, in a sheet: save the evidence now (the default),
 * talk to someone first, or open the settings anyway.
 */

/**
 * The button on a stalkerware row that opens [packageName]'s settings with
 * [onOpen]. When [gated] (see [SafetyFirst.gatesSettings]) a tap shows the
 * sheet first and [onOpen] runs only from its "Open settings anyway"; otherwise
 * it opens straight away. [flagged] is the scan's record of the app, when it
 * scored one.
 */
@Composable
internal fun FlaggedAppButton(
    text: String,
    packageName: String,
    flagged: StalkwareScanner.SuspiciousApp?,
    gated: Boolean = true,
    onOpen: () -> Unit
) {
    var asking by remember(packageName) { mutableStateOf(false) }
    // A sheet is a window of its own, above the lock screen Clairix draws, so
    // locking closes it rather than leave it showing over the lock.
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) asking = false }
    SecondaryButton(
        text,
        onClick = { if (gated) asking = true else onOpen() },
        tone = Tone.Accent,
        modifier = Modifier.fillMaxWidth()
    )
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
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val c = ClairixTheme.colors

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
                .onSuccess { evidence ->
                    clipboard.setSensitiveText("Clairix evidence", evidence.text())
                    savedAt = evidence.savedAt
                }
                .onFailure { failure = "Could not save it: ${it.message ?: it.javaClass.simpleName}" }
            saving = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surfaceRaised,
        shape = ClairixShapes.Sheet
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Before you remove or switch off $label",
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                modifier = Modifier.semantics { heading() }
            )
            Text(SafetyFirst.NOTICE, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
            Text(SafetyFirst.ADVOCATE, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)

            PrimaryButton(
                if (savedAt == null) "Save evidence now" else "Save evidence again",
                onClick = { save() },
                enabled = !saving,
                loading = saving,
                modifier = Modifier.fillMaxWidth().focusRequester(saveFocus)
            )
            Text(
                "Its package name, who installed it and when, the access it holds, why it was flagged and the " +
                    "fingerprint of its signing certificate go on the timeline with the time, and so into the " +
                    "evidence report. A copy goes to the clipboard: paste it somewhere only you can reach.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSubtle
            )
            savedAt?.let { Notice(Tone.Safe, "Saved at ${tTime(it)}, on the timeline and on the clipboard.") }
            failure?.let { Notice(Tone.Danger, it) }

            SecondaryButton(
                "Talk to someone first",
                onClick = {
                    onDismiss()
                    Registry.requestHelp()
                },
                tone = Tone.Accent,
                modifier = Modifier.fillMaxWidth()
            )
            GhostButton("Open settings anyway", onClick = onOpen, tone = Tone.Neutral, modifier = Modifier.fillMaxWidth())
        }
    }
}
