package app.clairix

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.CallManager
import app.clairix.comms.formatClairixNumber
import app.clairix.ui.appResources
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
        title = stringResource(R.string.comms_call_directly),
        text = directCallExplanation(appResources(), who, ask.relayUsuallyOffersTurn),
        onDismiss = { CallManager.dismissDirectCallAsk() },
        confirmLabel = stringResource(R.string.comms_call_directly_2),
        onConfirm = { CallManager.place(ask.contact, allowDirect = true) },
        confirmTone = Tone.Caution,
    )
}

/** What the "Call directly?" dialog says about [who], and why it is asking. */
internal fun directCallExplanation(res: Resources, who: String, relayUsuallyOffersTurn: Boolean): String {
    val why = res.getString(if (relayUsuallyOffersTurn) R.string.comms_direct_turn_silent else R.string.comms_direct_no_turn)
    return res.getString(R.string.comms_direct_call_explained, why, who)
}
