package app.clairix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.CallManager
import app.clairix.comms.formatClairixNumber
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.Tone

/**
 * "Call directly?": shown when a call this phone was asked to place could
 * not go through the relay's TURN server and the owner has not chosen either
 * way in Comms settings ([CallManager.directCallAsk]). Nothing has rung yet;
 * the call is placed only if the owner confirms, for this call alone.
 */
@Composable
fun DirectCallDialog() {
    val pending by CallManager.directCallAsk.collectAsStateWithLifecycle()
    val ask = pending ?: return
    val who = ask.contact.name.ifBlank { formatClairixNumber(ask.contact.number) }
    ClairixDialog(
        title = "Call directly?",
        text = directCallExplanation(who, ask.relayUsuallyOffersTurn),
        onDismiss = { CallManager.dismissDirectCallAsk() },
        confirmLabel = "Call directly",
        onConfirm = { CallManager.place(ask.contact, allowDirect = true) },
        confirmTone = Tone.Caution,
    )
}

/** What the "Call directly?" dialog says about [who], and why it is asking. */
internal fun directCallExplanation(who: String, relayUsuallyOffersTurn: Boolean): String {
    val why = if (relayUsuallyOffersTurn) {
        "Your relay's call server did not answer for this call."
    } else {
        "Your relay offers no call server (TURN), so this call cannot go through the relay."
    }
    return "$why Your network address will be shared with $who, whether or not they answer. " +
        "To never be asked, choose in Comms settings under Relay-only calls."
}
