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
import androidx.compose.ui.res.stringResource
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
    private val textSlot = SosOutcomeSlot<SosExtras.TextOutcome>()
    private val _call = MutableStateFlow<SosExtras.CallOutcome?>(null)

    val text: StateFlow<SosExtras.TextOutcome?> = textSlot.value
    val call: StateFlow<SosExtras.CallOutcome?> = _call.asStateFlow()

    /**
     * Forgets the last SOS's outcomes; called as a new SOS sheet opens. A text the last
     * SOS is still finding a position for or sending carries on, but what it comes to is
     * no longer this sheet's to say, and it does not stop this SOS's text from going.
     */
    fun reset() {
        textSlot.reset()
        _call.value = null
    }

    /**
     * Texts [number]: finds a position first ([SosLocation], never a mock or stale one;
     * up to about eight seconds), then sends it (full edition, permission granted) or
     * opens the messaging app with it written. Once per SOS: a second press while one is
     * under way, or after it went, does nothing.
     */
    fun sendText(context: Context, number: String) {
        val ticket = textSlot.claim(SosExtras.TextOutcome.Finding(number)) ?: return
        val app = context.applicationContext
        scope.launch {
            val lookup = withContext(Dispatchers.IO) { SosLocation.locate(app) }
            val at = (lookup as? SosLocation.Lookup.Found)?.at
            val body = SosExtras.smsBody(at)
            val outcome = when (SosExtras.route(DirectSms.available, DirectSms.permitted(app))) {
                SosExtras.TextRoute.DIRECT -> withContext(Dispatchers.IO) { DirectSms.send(app, number, body, at != null) }
                SosExtras.TextRoute.COMPOSER -> compose(app, number, body, at != null)
            }
            textSlot.post(ticket, outcome)
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
                    stringResource(R.string.safety_text, to),
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
                stringResource(R.string.safety_call, emergency.number),
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
        title = stringResource(R.string.safety_sos_text_and_call),
        text = SosExtras.resultLines(text, call, if (config.callOn) emergency.number else null).joinToString("\n\n"),
        confirmLabel = stringResource(R.string.comms_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
        content = if (config.callOn) {
            {
                SecondaryButton(
                    stringResource(R.string.safety_call, emergency.number),
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
            stringResource(R.string.safety_an_sos_goes_to_the_members),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSubtle,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        ToggleRow(
            title = stringResource(R.string.safety_also_text_this_number),
            subtitle = if (config.textOn) {
                stringResource(R.string.safety_sending_an_sos_also_texts_this)
            } else {
                stringResource(R.string.safety_off_an_sos_goes_only_to)
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
                label = stringResource(R.string.safety_number_to_text),
                keyboardType = KeyboardType.Phone,
                isError = bad,
                supportingText = when {
                    bad -> stringResource(R.string.safety_not_a_phone_number_digits_with)
                    typed.isBlank() -> stringResource(R.string.safety_nothing_is_texted_until_a_number)
                    else -> null
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Text(
                stringResource(R.string.safety_a_text_is_not_encrypted_it),
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
            title = stringResource(R.string.safety_emergency_call_button),
            subtitle = if (config.callOn) {
                stringResource(R.string.safety_the_sos_sheet_shows_call_it, emergency.number, EmergencyNumbers.explain(emergency))
            } else {
                stringResource(R.string.safety_off_the_sos_sheet_shows_no)
            },
            checked = config.callOn,
            onCheckedChange = { v ->
                config = config.copy(callOn = v)
                SosExtras.setCall(context, v)
            }
        )
    }
}

/**
 * One SOS's text outcome. A new SOS sheet [reset]s it; work started for an earlier SOS
 * holds the ticket [claim] gave it, and what it [post]s once that SOS is gone is dropped,
 * so it can neither be shown as this SOS's text nor keep this SOS's text from going.
 */
internal class SosOutcomeSlot<T : Any> {
    private val _value = MutableStateFlow<T?>(null)
    private var generation = 0L

    val value: StateFlow<T?> = _value.asStateFlow()

    @Synchronized
    fun reset() {
        generation++
        _value.value = null
    }

    /** Takes the slot with [start] when this SOS has nothing in it yet: the ticket to [post] with, or null. */
    @Synchronized
    fun claim(start: T): Long? {
        if (_value.value != null) return null
        _value.value = start
        return generation
    }

    /** Puts [outcome] in the slot when [ticket] is still this SOS's; false (and nothing changes) when a newer SOS reset it. */
    @Synchronized
    fun post(ticket: Long, outcome: T): Boolean {
        if (ticket != generation) return false
        _value.value = outcome
        return true
    }
}
