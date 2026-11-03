package app.clairix

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.Tone

/**
 * Asks before unblocking [who], as Block asks before blocking: a mis-tap, or
 * someone else holding the phone, must not let a blocked person back in.
 * [kept] is whether their conversation was kept as a record, so that they
 * become a contact again rather than someone whose next message is a request.
 */
@Composable
fun UnblockDialog(who: String, kept: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ClairixDialog(
        title = stringResource(R.string.settings_unblock, who),
        text = unblockExplanation(kept),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.settings_unblock_2),
        onConfirm = onConfirm,
        confirmTone = Tone.Caution,
    )
}

/** What unblocking does, said before it is done. */
@Composable
private fun unblockExplanation(kept: Boolean): String =
    stringResource(if (kept) R.string.settings_unblock_kept else R.string.settings_unblock_not_kept)
