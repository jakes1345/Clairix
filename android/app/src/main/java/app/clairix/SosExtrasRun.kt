package app.clairix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.security.EmergencyNumbers
import app.clairix.security.SosExtras
import app.clairix.security.SosLocation
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The SOS sheet's optional extras (security/SosExtras.kt): a text to one trusted
// number and a button for the local emergency number, for the weeks before a
// Clairix group exists. The Clairix SOS itself (CommsRepository.sendSos and its
// outbox) is not touched by anything here, and the duress phrase never reaches it.

/**
 * What the extras came to for the SOS being sent, kept for the process so the sheet
 * still says it after the phone app or the messaging app has been in front. The work
 * runs in its own scope: a text the full edition is sending is not cut short when the
 * shell locks and the SOS button goes away.
 */
internal object SosExtrasRun {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _text = MutableStateFlow<SosExtras.TextOutcome?>(null)
    private val _call = MutableStateFlow<SosExtras.CallOutcome?>(null)

    val text: StateFlow<SosExtras.TextOutcome?> = _text.asStateFlow()
    val call: StateFlow<SosExtras.CallOutcome?> = _call.asStateFlow()

    /** Forgets the last SOS's outcomes; called as a new SOS sheet opens. */
    fun reset() {
        _text.value = null
        _call.value = null
    }

    /**
     * Texts [number]: finds a position first ([SosLocation], never a mock or stale one;
     * up to about eight seconds), then sends it (full edition, permission granted) or
     * opens the messaging app with it written. Once per SOS: a second press while one is
     * under way, or after it went, does nothing.
     */
    fun sendText(context: Context, number: String) {
        if (_text.value != null) return
        val app = context.applicationContext
        _text.value = SosExtras.TextOutcome.Finding(number)
        scope.launch {
            val lookup = withContext(Dispatchers.IO) { SosLocation.locate(app) }
            val at = (lookup as? SosLocation.Lookup.Found)?.at
            val body = SosExtras.smsBody(at)
            _text.value = when (SosExtras.route(DirectSms.available, DirectSms.permitted(app))) {
                SosExtras.TextRoute.DIRECT -> withContext(Dispatchers.IO) { DirectSms.send(app, number, body, at != null) }
                SosExtras.TextRoute.COMPOSER -> compose(app, number, body, at != null)
            }
        }
    }

    /** Opens the phone app with [number] entered (ACTION_DIAL: no permission, and the owner presses call). */
    fun dial(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        _call.value = if (opened) SosExtras.CallOutcome.DiallerOpened(number) else SosExtras.CallOutcome.NoDialler(number)
    }

    private fun compose(app: Context, number: String, body: String, withPosition: Boolean): SosExtras.TextOutcome {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { app.startActivity(intent) }.isSuccess
        return if (opened) {
            SosExtras.TextOutcome.ComposerOpened(number, withPosition, permissionOff = DirectSms.available)
        } else {
            SosExtras.TextOutcome.Failed(number, "no app on this phone can send texts")
        }
    }
}

/** How the text will go on this phone now: sent by Clairix, or written into the messaging app. */
internal fun sosTextRoute(context: Context): SosExtras.TextRoute =
    SosExtras.route(DirectSms.available, DirectSms.permitted(context))

/**
 * The extras on the SOS sheet. On the confirm sheet ([offerText] false) the text is
 * described, and goes when the SOS is sent; on the "Nobody would receive this" sheet
 * ([offerText] true) there is no Clairix SOS to send, so the text has its own button.
 * The emergency call button is the same on both. Each says what came of it once used.
 */
@Composable
internal fun SosExtrasSheet(
    config: SosExtras.Config,
    emergency: EmergencyNumbers.Choice,
    offerText: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val text by SosExtrasRun.text.collectAsStateWithLifecycle()
    val call by SosExtrasRun.call.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        config.textTo?.let { to ->
            val outcome = text
            when {
                outcome != null -> Text(SosExtras.textLine(outcome), style = MaterialTheme.typography.bodyMedium, color = c.text)
                offerText -> SecondaryButton(
                    "Text $to",
                    onClick = { SosExtrasRun.sendText(context, to) },
                    tone = Tone.Danger,
                    modifier = Modifier.fillMaxWidth()
                )
                else -> Text(
                    SosExtras.textPlan(to, sosTextRoute(context), DirectSms.available),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (config.callOn) {
            SecondaryButton(
                "Call ${emergency.number}",
                onClick = { SosExtrasRun.dial(context, emergency.number) },
                tone = Tone.Danger,
                icon = Icons.Filled.Call,
                modifier = Modifier.fillMaxWidth()
            )
            val outcome = call
            Text(
                if (outcome != null) SosExtras.callLine(outcome) else EmergencyNumbers.explain(emergency),
                style = MaterialTheme.typography.bodySmall,
                color = if (outcome != null) c.text else c.textSubtle
            )
        }
    }
}

/**
 * Shown once a Clairix SOS has been sent with an extra set up: what came of the text
 * (or that the position is still being found), and whether the emergency number was
 * dialled, with its button still there. The Clairix SOS's own result comes as before.
 */
@Composable
internal fun SosExtrasResultDialog(config: SosExtras.Config, emergency: EmergencyNumbers.Choice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by SosExtrasRun.text.collectAsStateWithLifecycle()
    val call by SosExtrasRun.call.collectAsStateWithLifecycle()
    ClairixDialog(
        title = "SOS text and call",
        text = SosExtras.resultLines(text, call, if (config.callOn) emergency.number else null).joinToString("\n\n"),
        confirmLabel = "Close",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
        content = if (config.callOn) {
            {
                SecondaryButton(
                    "Call ${emergency.number}",
                    onClick = { SosExtrasRun.dial(context, emergency.number) },
                    tone = Tone.Danger,
                    icon = Icons.Filled.Call,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            null
        }
    )
}

/**
 * Settings, SOS: the two extras, both off until turned on. Turning the text on in the
 * full edition asks for the SMS permission; refused, the text still goes by the
 * messaging app, and the card says so.
 */
@Composable
internal fun SosExtrasSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    var config by remember { mutableStateOf(SosExtras.config(context)) }
    var smsPermitted by remember { mutableStateOf(DirectSms.permitted(context)) }
    val emergency = remember { EmergencyNumbers.local(context) }
    // The permission can be taken back on Android's settings page, outside the app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { smsPermitted = DirectSms.permitted(context) }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsPermitted = granted
    }

    ClairixCard(modifier, spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "An SOS goes to the members of your Clairix groups. Before you have a group, or as well, it can text one " +
                "number and offer a button for the emergency number. Both are off until you turn them on here. " +
                "The duress phrase never texts or calls.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSubtle,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        ToggleRow(
            title = "Also text this number",
            subtitle = if (config.textOn) {
                "Sending an SOS also texts this number your position as an OpenStreetMap link, or says that there is none."
            } else {
                "Off: an SOS goes only to your Clairix groups."
            },
            checked = config.textOn,
            onCheckedChange = { v ->
                config = config.copy(textOn = v)
                SosExtras.setText(context, v)
                if (v && DirectSms.available && !smsPermitted) smsLauncher.launch(Manifest.permission.SEND_SMS)
            }
        )
        if (config.textOn) {
            val typed = config.number
            val bad = typed.isNotBlank() && SosExtras.normalizeNumber(typed) == null
            ClairixTextField(
                value = typed,
                onValueChange = { v ->
                    config = config.copy(number = v)
                    SosExtras.setNumber(context, v)
                },
                label = "Number to text",
                keyboardType = KeyboardType.Phone,
                isError = bad,
                supportingText = when {
                    bad -> "Not a phone number: digits, with + and the country code if it is abroad."
                    typed.isBlank() -> "Nothing is texted until a number is here."
                    else -> null
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Text(
                "A text is not encrypted: it goes through your mobile carrier, who can read it, and stays in your " +
                    "messaging app's sent texts, where anyone holding the phone can see it, until you delete it there. " +
                    "A Clairix SOS is end to end encrypted and leaves nothing in the messaging app.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSubtle,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                SosExtras.editionLine(DirectSms.available, smsPermitted),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSubtle,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }
        Hairline()
        ToggleRow(
            title = "Emergency call button",
            subtitle = if (config.callOn) {
                "The SOS sheet shows Call ${emergency.number}. It opens the phone app with the number entered and the " +
                    "call starts when you press call there; Clairix places no call and needs no phone permission. " +
                    EmergencyNumbers.explain(emergency)
            } else {
                "Off: the SOS sheet shows no call button."
            },
            checked = config.callOn,
            onCheckedChange = { v ->
                config = config.copy(callOn = v)
                SosExtras.setCall(context, v)
            }
        )
    }
}
