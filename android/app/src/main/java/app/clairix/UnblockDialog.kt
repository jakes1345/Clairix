package app.clairix

import androidx.compose.runtime.Composable
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
        title = "Unblock $who?",
        text = unblockExplanation(kept),
        onDismiss = onDismiss,
        confirmLabel = "Unblock",
        onConfirm = onConfirm,
        confirmTone = Tone.Caution,
    )
}

/** What unblocking does, said before it is done. */
internal fun unblockExplanation(kept: Boolean): String =
    if (kept) {
        "They become a contact again: their messages and calls reach you and ring, under their name. " +
            "Their phone is sent this phone's pass for your mailbox in an encrypted message that Clairix does not show them."
    } else {
        "Anything they send next reaches this phone again, as a message request. They are not told."
    }
