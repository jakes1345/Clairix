package app.clairix

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.CommsRepository
import app.clairix.comms.KeepOffScreen
import app.clairix.comms.PairingCode
import app.clairix.comms.RestoreOutcome
import app.clairix.comms.formatClairixNumber
import app.clairix.security.DiscreetMode
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.components.clairixTextFieldColors
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Said at registration, in the Tour and beside "Save a recovery file": the one thing to know about losing this phone. */
internal const val NO_CLOUD_BACKUP =
    "There is no cloud backup. If this phone is lost, this number and these contacts are lost unless you save a recovery file."

/** What a recovery file holds and what it does not, said before one is saved and before one is restored. */
private const val RECOVERY_CONTENTS =
    "A recovery file holds this phone's identity keys, its relay and Clairix number, your contacts with their keys and whether you verified them, the blocked list, and your groups with their members. It does not hold your messages, and no conversation's encryption session: after a restore every conversation starts a new one. Contacts see the same safety number, so nobody has to verify you again."

/** The largest file "Restore from file" reads; a recovery file with thousands of contacts is a fraction of this. */
private const val MAX_RECOVERY_FILE_BYTES = 4 * 1024 * 1024

private const val RECOVERY_MIME = "application/octet-stream"
private const val READ_CHUNK = 64 * 1024

/** Where the save flow is. The code lives only in memory, never in saved state. */
private sealed interface SaveStep {
    data object Explain : SaveStep
    data class ShowCode(val code: String) : SaveStep
    data class Confirm(val code: String) : SaveStep
}

/**
 * Comms settings → Recovery file: saving one, and when one was last saved.
 * The file goes through the system's file picker (Storage Access Framework)
 * to wherever the owner chooses; Clairix keeps neither the file nor its code.
 */
@Composable
internal fun RecoveryFileCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<SaveStep?>(null) }
    // The code the file being written is sealed under, between the picker opening and closing.
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var savedAt by remember { mutableStateOf(CommsRepository.recoverySavedAt()) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(RECOVERY_MIME)) { uri ->
        val code = pendingCode
        pendingCode = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (code == null) {
            // Clairix was closed while the picker was open, and the code with it
            // (it is never kept): the empty file just made goes, and the owner starts again.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            message = context.getString(R.string.safety_clairix_was_closed_while_you_chose) to Tone.Caution
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val written = writeRecoveryFile(context, uri, code)
            busy = false
            message = written.fold(
                onSuccess = {
                    CommsRepository.noteRecoverySaved()
                    savedAt = CommsRepository.recoverySavedAt()
                    "Saved. Keep the code apart from the file: the file is no use without it, and anyone with both can be this number." to Tone.Safe
                },
                onFailure = { "The recovery file was not saved: ${it.message ?: it.javaClass.simpleName}" to Tone.Danger }
            )
        }
    }

    ClairixCard {
        Text(stringResource(R.string.safety_recovery_file), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        Text(NO_CLOUD_BACKUP, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
        Text(RECOVERY_CONTENTS, style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
        Text(
            if (savedAt > 0L) {
                stringResource(R.string.safety_last_saved_contacts_and_groups_added, DateFormat.getDateInstance().format(Date(savedAt)))
            } else {
                stringResource(R.string.safety_no_recovery_file_has_been_saved)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (savedAt > 0L) ClairixTheme.colors.textMuted else ClairixTheme.colors.caution
        )
        message?.let { (text, tone) -> Notice(tone, text) }
        PrimaryButton(
            if (busy) stringResource(R.string.safety_saving) else stringResource(R.string.safety_save_a_recovery_file),
            onClick = { message = null; step = SaveStep.Explain },
            enabled = !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth()
        )
    }

    when (val s = step) {
        null -> Unit
        SaveStep.Explain -> ClairixDialog(
            title = stringResource(R.string.safety_save_a_recovery_file),
            onDismiss = { step = null },
            confirmLabel = stringResource(R.string.safety_show_the_code),
            onConfirm = { step = SaveStep.ShowCode(CommsRepository.newRecoveryCode()) }
        ) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(RECOVERY_CONTENTS, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.safety_clairix_makes_a_recovery_code_and),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.safety_the_file_is_yours_outside_clairix),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        is SaveStep.ShowCode -> {
            KeepOffScreen()
            ClairixDialog(
                title = stringResource(R.string.safety_your_recovery_code),
                onDismiss = { step = null },
                confirmLabel = stringResource(R.string.safety_i_wrote_it_down),
                onConfirm = { step = SaveStep.Confirm(s.code) },
                text = stringResource(R.string.safety_write_these_seven_groups_down_in)
            ) {
                Text(
                    s.code.replace('-', ' '),
                    style = MaterialTheme.typography.monoLarge,
                    color = ClairixTheme.colors.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is SaveStep.Confirm -> {
            var typed by remember(s.code) { mutableStateOf("") }
            val matches = CommsRepository.sameRecoveryCode(s.code, typed)
            ClairixDialog(
                title = stringResource(R.string.safety_type_the_code_you_wrote_down),
                onDismiss = { step = null },
                confirmLabel = stringResource(R.string.safety_choose_where_to_save),
                onConfirm = {
                    pendingCode = s.code
                    step = null
                    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
                    createDocument.launch(recoveryFileName(DiscreetMode.on(context), day))
                },
                confirmEnabled = matches,
                dismissLabel = stringResource(R.string.settings_cancel)
            ) {
                Text(
                    stringResource(R.string.safety_from_your_paper_not_from_memory),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(MAX_CODE_INPUT) },
                    label = { Text(stringResource(R.string.safety_recovery_code), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = clairixTextFieldColors()
                )
                if (typed.length >= MIN_CODE_INPUT && !matches) {
                    Text(stringResource(R.string.safety_that_is_not_the_code_shown), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.danger)
                }
                GhostButton(stringResource(R.string.safety_show_the_code_again), onClick = { step = SaveStep.ShowCode(s.code) }, tone = Tone.Neutral)
            }
        }
    }
}

/**
 * The name the save picker suggests for a recovery file made on [day]. In
 * discreet mode it names neither Clairix nor recovery, since the file may sit
 * in a shared Downloads folder, computer or cloud drive that someone else looks
 * through; the restore picker opens any file, whatever its name.
 */
internal fun recoveryFileName(discreet: Boolean, day: String): String =
    if (discreet) "backup-$day.bin" else "clairix-recovery-$day.clxr"

/** A code is 28 symbols and up to six separators; anything longer is not one. */
private const val MAX_CODE_INPUT = 48
/** Below this nothing is said yet: the person is still typing. */
private const val MIN_CODE_INPUT = 28

/**
 * Seals the recovery file under [code] and writes it to [uri]. A file the
 * picker made but that could not be written is deleted again, so no empty or
 * half-written recovery file is left where the owner would trust it.
 */
private suspend fun writeRecoveryFile(context: Context, uri: Uri, code: String): Result<Unit> {
    val sealed = CommsRepository.recoveryFile(code)
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = sealed.getOrThrow()
            val out = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "the chosen place could not be opened" }
            out.use { it.write(bytes) }
        }.onFailure {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }
}

/**
 * Comms setup → "Restore from file": a recovery file saved on another phone,
 * opened with its code. When the old mailbox is gone and the relay wants an
 * invite or its enrollment secret to take the keys back, this asks for one.
 */
@Composable
internal fun RestoreFromFileCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<PairingCode?>(null) }
    // What the relay said that needs something more from the person: an
    // invite or the enrollment secret (NeedsAdmission), or, with the relay
    // not answering, an invite to another relay (Unreachable).
    var admission by remember { mutableStateOf<RestoreOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readRecoveryFile(context, uri) }
            read.onSuccess { file = it; fileName = uri.lastPathSegment?.substringAfterLast('/'); error = null; admission = null }
                .onFailure { error = it.message ?: context.getString(R.string.comms_that_file_could_not_be_read) }
        }
    }

    ClairixCard(modifier = modifier) {
        Text(stringResource(R.string.safety_restore_from_a_recovery_file), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
        Text(
            stringResource(R.string.safety_had_this_number_on_another_phone),
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle
        )
        val chosen = file
        if (chosen == null) {
            SecondaryButton(stringResource(R.string.safety_restore_from_file), onClick = { openDocument.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        } else {
            Text("File: ${fileName ?: "chosen"} (${chosen.size / 1024 + 1} KB)", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textMuted)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(MAX_CODE_INPUT) },
                label = { Text(stringResource(R.string.safety_recovery_code), style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = clairixTextFieldColors()
            )
            admission?.let { need ->
                val gone = need is RestoreOutcome.NeedsAdmission
                Notice(
                    Tone.Caution,
                    when (need) {
                        is RestoreOutcome.NeedsAdmission ->
                            "The relay no longer has your old mailbox, so the same keys are registered again and get a new number; " +
                                "your contacts' phones are told it. ${need.relayUrl.removePrefix("https://")} said: ${need.reason} " +
                                "Enter its enrollment secret, or paste an invite someone on it sent you."
                        is RestoreOutcome.Unreachable ->
                            stringResource(R.string.safety_nothing_has_changed_on_this_phone, need.reason)
                        is RestoreOutcome.Suspended ->
                            "${need.relayUrl.removePrefix("https://")} said: ${need.reason} Nothing has changed on this phone. " +
                                "Your keys still belong to that number there, so they cannot be registered on that relay again. " +
                                "Paste an invite someone on another relay sent you: the same keys are registered there under a new " +
                                "number, and your contacts' phones are told it."
                        else -> ""
                    },
                    title = when (need) {
                        is RestoreOutcome.NeedsAdmission -> stringResource(R.string.safety_the_old_number_is_gone)
                        is RestoreOutcome.Suspended -> stringResource(R.string.safety_this_number_is_suspended_on_its)
                        else -> stringResource(R.string.safety_the_relay_did_not_answer)
                    }
                )
                if (gone) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.comms_enrollment_secret), style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = clairixTextFieldColors()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        if (invite != null) stringResource(R.string.safety_invite_pasted) else stringResource(R.string.comms_paste_invite),
                        onClick = {
                            val pasted = clipboard.getText()?.text?.let { PairingCode.fromText(it) }
                            if (pasted?.invite != null) { invite = pasted; error = null } else error = context.getString(R.string.comms_that_is_not_a_clairix_invite)
                        },
                        tone = Tone.Neutral
                    )
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            PrimaryButton(
                if (state.busy) stringResource(R.string.safety_restoring) else stringResource(R.string.safety_restore),
                onClick = {
                    error = null
                    scope.launch {
                        when (val outcome = CommsRepository.restoreFromFile(chosen, code, secret, invite)) {
                            is RestoreOutcome.Restored -> {
                                file = null
                                code = ""
                                Toast.makeText(
                                    context,
                                    if (outcome.sameNumber) "Restored as ${formatClairixNumber(outcome.number)}"
                                    else "Restored under a new number, ${formatClairixNumber(outcome.number)}; contacts are being told",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is RestoreOutcome.NeedsAdmission, is RestoreOutcome.Unreachable, is RestoreOutcome.Suspended -> admission = outcome
                            is RestoreOutcome.Failed -> error = outcome.reason
                        }
                    }
                },
                enabled = !state.busy && code.isNotBlank(),
                loading = state.busy,
                modifier = Modifier.fillMaxWidth()
            )
            GhostButton(stringResource(R.string.safety_choose_another_file), onClick = { file = null; admission = null; invite = null; error = null }, tone = Tone.Neutral)
        }
    }
}

/** The chosen file's bytes, refused when it is far larger than any recovery file. */
private fun readRecoveryFile(context: Context, uri: Uri): Result<ByteArray> = runCatching {
    val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "That file could not be opened" }
    input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            check(out.size() <= MAX_RECOVERY_FILE_BYTES) { "That file is too large to be a Clairix recovery file" }
        }
        out.toByteArray()
    }
}
