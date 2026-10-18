package app.clairix

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import app.clairix.analysis.PhoneHealthMonitor
import app.clairix.comms.ActiveCall
import app.clairix.comms.CallManager
import app.clairix.comms.CallPhase
import app.clairix.comms.COIN_SYMBOL
import app.clairix.comms.ChatMessage
import app.clairix.comms.ChatThread
import app.clairix.comms.CommsMedia
import app.clairix.comms.CommsWire
import app.clairix.comms.Group
import app.clairix.comms.GroupMember
import app.clairix.comms.GroupThread
import app.clairix.comms.MediaBody
import app.clairix.comms.OrgRole
import app.clairix.comms.STATUS_CALL
import app.clairix.comms.STATUS_PAYMENT
import app.clairix.comms.STATUS_RECEIVING
import app.clairix.comms.STATUS_VOICEMAIL
import app.clairix.comms.VoicemailBody
import app.clairix.comms.VoicemailPlayer
import app.clairix.comms.durationLabel
import app.clairix.comms.label
import app.clairix.comms.preview
import app.clairix.comms.CommsLog
import app.clairix.comms.CommsNotifications
import app.clairix.comms.CommsRepository
import app.clairix.comms.Contact
import app.clairix.comms.Direction
import app.clairix.comms.PairingCode
import app.clairix.comms.formatClairixNumber
import app.clairix.comms.parseClairixNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.toArgb
import app.clairix.ui.theme.ClairixBrand
import app.clairix.ui.components.*
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.monoLarge
import app.clairix.ui.theme.mono

// ── Colour tokens local to this file ──────────────────────────────────────────

private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
private val dateTimeFmt = SimpleDateFormat("MMM d HH:mm", Locale.US)

private const val UI_PREFS = "comms_ui"
private const val KEY_MIC_ASKED = "microphone_asked"

private const val NOT_A_PHONE = "Clairix numbers are not phone numbers. They only reach other Clairix apps on the same relay; they cannot call or text a phone."

/** Per-group switch: whether threats this phone confirms are sent to the group. Suffixed with the group id. */
private const val KEY_SHARE_THREATS = "share_threats_"

/** Where the COMMS tab is, apart from the conversation list. */
private sealed class CommsPage {
    object List : CommsPage()
    data class Thread(val peer: String) : CommsPage()
    data class Verify(val peer: String) : CommsPage()
    object MyCode : CommsPage()
    object Invite : CommsPage()
    object Settings : CommsPage()
    /** A group's conversation. */
    data class Group(val groupId: String) : CommsPage()
    /** A group's members and settings. */
    data class OrgManage(val groupId: String) : CommsPage()
}

/**
 * The COMMS tab: this device's Clairix number, its contacts and end-to-end
 * encrypted conversations, and the registration screen before all that.
 *
 * [openPeer] is a conversation to jump straight into (from a notification),
 * consumed once; [openGroup] is the same for a group conversation.
 */
@Composable
fun CommsScreen(
    openPeer: String?,
    onPeerConsumed: () -> Unit,
    openInvite: String? = null,
    onInviteConsumed: () -> Unit = {},
    openGroup: String? = null,
    onGroupConsumed: () -> Unit = {},
    onAppSettings: (() -> Unit)? = null
) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val activeCall by CallManager.call.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf<CommsPage>(CommsPage.List) }
    // An invite from a link: it fills in the setup screen, or, once this phone
    // has a number, offers to add the person who sent it.
    var invite by remember { mutableStateOf<PairingCode?>(null) }
    var inviterToAdd by remember { mutableStateOf<PairingCode?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openInvite) {
        if (openInvite != null) {
            val code = PairingCode.fromText(openInvite)
            when {
                code == null -> inviteError = "That invite link is incomplete; ask for it to be sent again."
                state.registered -> inviterToAdd = code
                else -> invite = code
            }
            onInviteConsumed()
        }
    }

    LaunchedEffect(openPeer) {
        if (openPeer != null) {
            page = CommsPage.Thread(openPeer)
            onPeerConsumed()
        }
    }
    LaunchedEffect(openGroup) {
        if (openGroup != null) {
            page = CommsPage.Group(openGroup)
            onGroupConsumed()
        }
    }

    // Calls need the microphone. Asked for once as soon as this phone has a
    // number, so the first incoming call is not also the first time the
    // question comes up, on a locked phone, with the caller waiting.
    val context = LocalContext.current
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.registered) {
        if (!state.registered || hasMicrophone(context)) return@LaunchedEffect
        val prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIC_ASKED, false)) return@LaunchedEffect
        prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply()
        askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    // A call, ringing or in progress, takes the whole tab; so does the offer to
    // leave a voicemail after a call this phone placed went unanswered.
    val call = activeCall
    val voicemail = rememberVoicemailOffer(call)
    if (voicemail != null && (call == null || call.phase == CallPhase.ENDED)) {
        VoicemailScreen(voicemail.contact, call, onDone = voicemail.dismiss)
        return
    }
    if (call != null) {
        InCallScreen(call)
        return
    }

    if (!state.registered) {
        SetupScreen(invite = invite, linkError = inviteError, onInvite = { invite = it; inviteError = null })
        return
    }
    inviterToAdd?.let { code ->
        AddInviterDialog(code, onDone = { inviterToAdd = null }, onAdded = { page = CommsPage.Thread(it) })
    }
    when (val p = page) {
        CommsPage.List -> ThreadListScreen(
            onOpen = { page = CommsPage.Thread(it) },
            onOpenGroup = { page = CommsPage.Group(it) },
            onMyCode = { page = CommsPage.MyCode },
            onInvite = { page = CommsPage.Invite },
            onSettings = { page = CommsPage.Settings }
        )
        is CommsPage.Group -> GroupThreadScreen(
            groupId = p.groupId,
            onBack = { page = CommsPage.List },
            onMembers = { page = CommsPage.OrgManage(p.groupId) }
        )
        is CommsPage.OrgManage -> OrgScreen(
            groupId = p.groupId,
            onBack = { page = CommsPage.Group(p.groupId) },
            onLeft = { page = CommsPage.List }
        )
        is CommsPage.Thread -> ThreadScreen(
            peer = p.peer,
            onBack = { page = CommsPage.List },
            onVerify = { page = CommsPage.Verify(p.peer) }
        )
        is CommsPage.Verify -> VerifyScreen(
            peer = p.peer,
            onBack = { page = CommsPage.Thread(p.peer) },
            onDeleted = { page = CommsPage.List }
        )
        CommsPage.MyCode -> MyCodeScreen(onBack = { page = CommsPage.List })
        CommsPage.Invite -> InviteScreen(onBack = { page = CommsPage.List })
        CommsPage.Settings -> CommsSettingsScreen(onBack = { page = CommsPage.List }, onAppSettings = onAppSettings)
    }
}

/** Message bubbles: chamfered on the corner nearest the sender. */
private val MineBubble = CutCornerShape(topStart = 12.dp, bottomEnd = 4.dp, topEnd = 0.dp, bottomStart = 0.dp)
private val TheirBubble = CutCornerShape(topEnd = 12.dp, bottomStart = 4.dp)

// ── Shared pieces ────────────────────────────────────────────────────────────

/**
 * The heading of a Comms page, under the shell's top bar: a back arrow when the
 * page is nested, the page title, then the page's actions.
 */
@Composable
private fun Header(title: String, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ClairixTheme.colors.text)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = ClairixTheme.colors.text,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing()
    }
}

private fun hasMicrophone(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Opens Clairix's page in the system settings, where a blocked permission can be allowed. */
internal fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Asks for the microphone if needed, then runs [onGranted]. A refusal used to
 * leave CALL and ACCEPT doing nothing at all; it is now said, and once Android
 * stops showing the dialog, the settings page where it can be allowed opens.
 * On a locked phone the lock screen is dismissed first, since the permission
 * dialog cannot show over it.
 */
@Composable
private fun rememberMicrophoneGate(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onGranted()
        } else {
            val activity = context.findActivity()
            val blocked = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            Toast.makeText(
                context,
                if (blocked) "The microphone is blocked for Clairix. Allow it under Permissions, then try again." else "Calls need the microphone.",
                Toast.LENGTH_LONG
            ).show()
            if (blocked) openAppDetails(context)
        }
    }
    return {
        if (hasMicrophone(context)) {
            onGranted()
        } else {
            val activity = context.findActivity()
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (activity != null && keyguard?.isKeyguardLocked == true) {
                keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { launcher.launch(Manifest.permission.RECORD_AUDIO) }
                })
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The name the QR scanner's camera claim is held under; see [PhoneHealthMonitor.ownCameraStarted]. */
private const val QR_CAMERA_CLAIM = "qr"

/** Launches the QR scanner and hands back the decoded pairing code. */
@Composable
private fun rememberCodeScanner(onCode: (PairingCode?) -> Unit): () -> Unit {
    // The scanner activity opens the camera in this app's process; the claim keeps
    // that from being reported as another app using the camera. The result arrives
    // on cancel and on failure too, and that is where the claim is released.
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        PhoneHealthMonitor.ownCameraStopped(QR_CAMERA_CLAIM)
        val text = result.contents
        if (text != null) onCode(PairingCode.decode(text))
    }
    return {
        PhoneHealthMonitor.ownCameraStarted(QR_CAMERA_CLAIM)
        try {
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Point at the other person's Clairix code")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        } catch (e: RuntimeException) {
            // No result will come for a launch that never happened.
            PhoneHealthMonitor.ownCameraStopped(QR_CAMERA_CLAIM)
            throw e
        }
    }
}

/**
 * QR modules in the brand's fixed Ink and Bone, not the theme's: a code has to
 * scan the same whichever theme is set, and scanners want dark on light.
 */
private val QR_DARK = ClairixBrand.Ink.toArgb()
private val QR_LIGHT = ClairixBrand.Bone.toArgb()

private fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) for (x in 0 until sizePx) pixels[y * sizePx + x] = if (matrix[x, y]) QR_DARK else QR_LIGHT
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

@Composable
private fun rememberTick(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

private fun agoText(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> dateTimeFmt.format(Date(ts))
    }
}

private fun contactLabel(c: Contact) = c.name.ifBlank { formatClairixNumber(c.number) }

// ── Setup ────────────────────────────────────────────────────────────────────

@Composable
private fun SetupScreen(invite: PairingCode?, linkError: String?, onInvite: (PairingCode?) -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var listed by rememberSaveable { mutableStateOf(true) }
    var inviteError by remember(linkError) { mutableStateOf(linkError) }
    val takeInvite: (PairingCode?) -> Unit = { code ->
        when {
            code == null -> inviteError = "That is not a Clairix invite."
            code.invite == null -> inviteError = "That's someone's contact code, not an invite. Ask them to tap Invite in Comms on their phone."
            else -> { inviteError = null; onInvite(code) }
        }
    }
    val scanInvite = rememberCodeScanner { takeInvite(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        StatusHero(
            eyebrow = "Messages and calls",
            headline = if (invite != null) "Invite ready" else "Not set up",
            body = "End-to-end encrypted messages and calls between Clairix apps, through a relay you or a friend run.",
            tone = if (invite != null) Tone.Accent else Tone.Neutral,
            trailing = { LogoMark(size = 48.dp, contentDescription = null) }
        )

        if (invite != null) {
            ClairixCard {
                Text("Join with an invite", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text("Invited by", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                Text(invite.name.ifBlank { formatClairixNumber(invite.number) }, style = MaterialTheme.typography.headlineMedium, color = ClairixTheme.colors.accent)
                Text("${formatClairixNumber(invite.number)} · ${invite.relayUrl.removePrefix("https://")}", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
                Text(
                    "This phone makes its own keys and gets its own Clairix number on their relay. " +
                        "${invite.name.ifBlank { "They" }} will be in your contacts, verified, with a first message telling them you joined.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Your name shown to contacts (optional)", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                ToggleRow(
                    title = "Listed number",
                    checked = listed,
                    onCheckedChange = { listed = it },
                    subtitle = if (listed) "Anyone with your number on this relay can add you." else "Unlisted: people can add you only by scanning your code. You can change this later."
                )
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
                PrimaryButton(
                    if (state.busy) "Registering…" else "Register",
                    onClick = {
                        scope.launch { CommsRepository.register(invite.relayUrl, "", name, listed, invite) }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                GhostButton("Use a relay URL and secret instead", onClick = { onInvite(null) }, tone = Tone.Neutral)
            }
            Notice(Tone.Info, NOT_A_PHONE, title = "Not a phone number")
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        ClairixCard {
            Text("Have an invite?", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            Text(
                "Someone already on Clairix can invite you: they tap Invite in Comms on their phone. Scan the QR code " +
                    "it shows, or copy the invite they sent and paste it here. No relay URL or secret needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "Scan invite",
                    onClick = { inviteError = null; scanInvite() },
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    "Paste invite",
                    onClick = { takeInvite(clipboard.getText()?.text?.let { PairingCode.fromText(it) }) },
                    modifier = Modifier.weight(1f)
                )
            }
            inviteError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }

        ClairixCard {
            Text("Or run your own relay", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            Text(
                "Deploy comms-worker from the Clairix repository to Cloudflare, then enter its URL and the enrollment " +
                    "secret you set. This phone generates its keys here, never shares the private half, and the relay " +
                    "hands it a random nine-digit Clairix number. Clairix then keeps a quiet background connection to the " +
                    "relay so messages arrive and calls ring while it's closed; Online in Comms settings switches that off.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Relay URL", style = MaterialTheme.typography.bodyMedium) },
                placeholder = { Text("https://relay.clairix.app", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = clairixTextFieldColors()
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text("Enrollment secret", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = clairixTextFieldColors()
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Name shown to contacts (optional)", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
            )
            ToggleRow(
                title = "Listed number",
                checked = listed,
                onCheckedChange = { listed = it },
                subtitle = if (listed) "Anyone with your number on this relay can add you." else "Unlisted: people can add you only by scanning your code. You can change this later."
            )
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            PrimaryButton(
                if (state.busy) "Registering…" else "Register",
                onClick = {
                    scope.launch { CommsRepository.register(url, secret, name, listed) }
                },
                enabled = !state.busy && url.isNotBlank() && secret.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Notice(Tone.Info, NOT_A_PHONE, title = "Not a phone number")

        ClairixCard {
            Text("What's protected", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            Text(
                "Every message is encrypted on this phone for one contact with the Olm double ratchet (vodozemac) and " +
                    "sealed again so the relay cannot see who sent it. The relay stores only opaque envelopes, your public " +
                    "keys and a push endpoint. Contacts are pinned by their keys; scanning a code or comparing safety " +
                    "numbers in person proves nobody sits in between. Nothing here uses Google or a phone carrier.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Conversation list ────────────────────────────────────────────────────────

@Composable
private fun ThreadListScreen(onOpen: (String) -> Unit, onOpenGroup: (String) -> Unit, onMyCode: () -> Unit, onInvite: () -> Unit, onSettings: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val threads by produceState(initialValue = emptyList<ChatThread>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.threads() }
    }
    val groupThreads by produceState(initialValue = emptyList<GroupThread>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.groupThreads() }
    }
    var addContact by remember { mutableStateOf(false) }
    var createGroup by remember { mutableStateOf(false) }
    val now = rememberTick()

    if (addContact) AddContactDialog(onDismiss = { addContact = false }, onAdded = { addContact = false; onOpen(it) })
    if (createGroup) CreateGroupDialog(onDismiss = { createGroup = false }, onCreated = { createGroup = false; onOpenGroup(it) })
    // The coin balance is the relay's figure; it is asked for when the list comes up.
    // Editions built without ClairixCoin (the Play edition) never ask.
    LaunchedEffect(state.registered) {
        if (BuildConfig.CLAIRIX_COIN && state.registered) CommsRepository.refreshBalance()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        // ── Status first: this phone's number and whether anything reaches it ──
        // The socket is held open whenever this screen is up, so "Live" is the
        // normal state; what differs is whether anything reaches the phone once
        // Clairix is closed.
        val reachTone = when {
            !state.connected -> Tone.Caution
            state.online || state.pushRegistered -> Tone.Safe
            else -> Tone.Caution
        }
        ClairixCard(spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your Clairix number", style = MaterialTheme.typography.labelSmall, color = ClairixTheme.colors.textSubtle)
                    Text(
                        state.number?.let { formatClairixNumber(it) } ?: "—",
                        style = MaterialTheme.typography.monoLarge,
                        color = ClairixTheme.colors.accent
                    )
                }
                StatusPill(if (state.connected) "Live" else "Connecting", if (state.connected) Tone.Safe else Tone.Caution)
            }
            Text(
                buildString {
                    append(
                        when {
                            state.online -> "Online in the background"
                            state.pushRegistered -> "Push wake-ups on"
                            else -> "Nothing arrives while Clairix is closed"
                        }
                    )
                    if (!state.listed) append(" · unlisted")
                    if (BuildConfig.CLAIRIX_COIN && state.balance >= 0L) append(" · ${state.balance} $COIN_SYMBOL")
                    if (state.lastSync > 0L) append(" · synced ${agoText(now, state.lastSync)}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = reachTone.color()
            )
            Text(
                "Not a phone number. It reaches Clairix apps on your relay only.",
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("My code", onClick = onMyCode, tone = Tone.Accent)
                GhostButton(if (state.busy) "Syncing" else "Sync", onClick = { scope.launch { CommsRepository.sync() } }, tone = Tone.Neutral, enabled = !state.busy)
                Spacer(Modifier.weight(1f))
                GhostButton("Settings", onClick = onSettings, tone = Tone.Neutral)
            }
        }
        Spacer(Modifier.height(8.dp))
        // ── Actions ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Add contact", onClick = { addContact = true }, modifier = Modifier.weight(1f))
            SecondaryButton("Invite", onClick = onInvite, tone = Tone.Accent, modifier = Modifier.weight(1f))
            SecondaryButton("Group", onClick = { createGroup = true }, tone = Tone.Accent, modifier = Modifier.weight(1f))
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Notice(
                Tone.Danger, it,
                action = { GhostButton("Dismiss", onClick = { CommsRepository.clearError() }, tone = Tone.Neutral) }
            )
        }
        val batteryFree by rememberBatteryUnrestricted()
        if (state.online && !batteryFree) {
            val context = LocalContext.current
            Spacer(Modifier.height(6.dp))
            Notice(
                Tone.Caution,
                "Battery saving can cut Clairix off in the background, and then calls don't ring. Let Clairix run unrestricted.",
                action = { GhostButton("Allow", onClick = { openBatterySettings(context) }) }
            )
        }
        Spacer(Modifier.height(8.dp))

        if (threads.isEmpty() && groupThreads.isEmpty()) {
            NoContactsCard()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Groups first: a group's stripe is always the accent colour, so
                // the section label keeps them apart from a contact with unread messages.
                if (groupThreads.isNotEmpty()) {
                    item(key = "§groups") { SectionHeader("Groups", supporting = "${groupThreads.size}") }
                    items(groupThreads, key = { "g:${it.group.id}" }) { gt -> GroupRow(gt, onClick = { onOpenGroup(gt.group.id) }) }
                    item(key = "§contacts") { SectionHeader("Contacts", supporting = "${threads.size}") }
                }
                if (threads.isEmpty()) {
                    item(key = "§no-contacts") { NoContactsCard() }
                }
                items(threads, key = { it.contact.number }) { t -> ThreadRow(t, onClick = { onOpen(t.contact.number) }) }
                item(key = "§footer") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun NoContactsCard() {
    EmptyState(
        title = "No contacts yet",
        body = "Tap Add contact and scan their Clairix code, or enter their listed number. Show yours under My code.",
        icon = ClairixIcons.Forum,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Names a new group; the phone that makes it is its first member and admin. */
@Composable
private fun CreateGroupDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var groupName by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val create = {
        val name = groupName.trim()
        if (name.isNotEmpty() && !busy) {
            busy = true
            error = null
            scope.launch {
                runCatching { CommsRepository.createGroup(name) }
                    .onSuccess { onCreated(it) }
                    .onFailure { busy = false; error = it.message ?: "Could not create the group" }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text("Create a group", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "A group is a conversation with several contacts at once, each message encrypted for every member. " +
                        "You start as its admin and add members from your contacts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                OutlinedTextField(
                    value = groupName, onValueChange = { groupName = it.take(60) },
                    label = { Text("Group name", style = MaterialTheme.typography.bodyMedium) }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (busy) "Creating…" else "Create",
                onClick = create,
                tone = if (groupName.isNotBlank() && !busy) Tone.Accent else Tone.Neutral,
                enabled = groupName.isNotBlank() && !busy
            )
        },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss, tone = Tone.Neutral, enabled = !busy) }
    )
}

@Composable
private fun ThreadRow(t: ChatThread, onClick: () -> Unit) {
    val m = t.lastMessage
    val c = ClairixTheme.colors
    val tone = when {
        t.contact.keyChanged -> Tone.Danger
        m?.failed == true -> Tone.Danger
        t.unread > 0 -> Tone.Accent
        else -> null
    }
    ClairixCard(tone = tone, onClick = onClick, onClickLabel = "Open conversation", spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(contactLabel(t.contact), highlighted = t.unread > 0)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        contactLabel(t.contact),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (m != null) Text(dateTimeFmt.format(Date(m.ts)), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        formatClairixNumber(t.contact.number),
                        style = MaterialTheme.typography.monoSmall,
                        color = c.textSubtle
                    )
                    when {
                        t.contact.keyChanged -> StatusPill("Keys changed", Tone.Danger)
                        t.contact.verified -> StatusPill("Verified", Tone.Safe)
                        else -> StatusPill("Unverified", Tone.Neutral)
                    }
                }
            }
            if (t.unread > 0) UnreadCount(t.unread)
        }
        if (m != null) {
            Text(
                (if (m.direction == Direction.OUT) "You: " else "") + m.preview(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (t.unread > 0) c.text else c.textSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (m.failed) Text(m.error ?: "Not sent", style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

/** A group in the conversation list. Groups carry no per-contact trust state, so no verification pill. */
@Composable
private fun GroupRow(gt: GroupThread, onClick: () -> Unit) {
    val m = gt.lastMessage
    val unread = gt.unread > 0
    val c = ClairixTheme.colors
    ClairixCard(tone = if (unread) Tone.Accent else null, onClick = onClick, onClickLabel = "Open group", spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).background(if (unread) c.accentContainer else c.surfaceOverlay, ClairixShapes.Octagon),
                contentAlignment = Alignment.Center
            ) {
                GroupGlyph(if (unread) c.accent else c.textMuted, Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        gt.group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (m != null) Text(dateTimeFmt.format(Date(m.ts)), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
                }
                Text("${gt.memberCount} member${if (gt.memberCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
            }
            if (unread) UnreadCount(gt.unread)
        }
        if (m != null) {
            Text(
                (if (m.direction == Direction.OUT) "You: " else "") + m.preview(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (unread) c.text else c.textSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A contact's initials in an octagon, the mark's shape. */
@Composable
private fun Avatar(name: String, highlighted: Boolean) {
    val c = ClairixTheme.colors
    val initials = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase(Locale.getDefault()) }.ifBlank { "#" }
    Box(
        Modifier.size(44.dp).background(if (highlighted) c.accentContainer else c.surfaceOverlay, ClairixShapes.Octagon),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, style = MaterialTheme.typography.titleSmall, color = if (highlighted) c.accent else c.textMuted)
    }
}

/** How many messages are unread, on the accent. */
@Composable
private fun UnreadCount(count: Int) {
    val c = ClairixTheme.colors
    Box(
        Modifier
            .heightIn(min = 24.dp)
            .background(c.accent, ClairixShapes.Tag)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "$count unread" },
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = c.onAccent)
    }
}

/** Two people, one behind the other: the group icon. */
@Composable
private fun GroupGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val back = colour.copy(alpha = 0.5f)
        drawCircle(back, radius = w * 0.13f, center = Offset(w * 0.68f, h * 0.32f))
        drawArc(back, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.44f, h * 0.5f), size = Size(w * 0.48f, h * 0.46f))
        drawCircle(colour, radius = w * 0.15f, center = Offset(w * 0.36f, h * 0.36f))
        drawArc(colour, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.06f, h * 0.56f), size = Size(w * 0.6f, h * 0.5f))
    }
}

// ── Add contact ──────────────────────────────────────────────────────────────

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var number by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scan = rememberCodeScanner { code ->
        if (code == null) { error = "That is not a Clairix code"; return@rememberCodeScanner }
        scope.launch {
            CommsRepository.addContactFromCode(code, name.takeIf { it.isNotBlank() })
                .onSuccess { onAdded(it.number) }
                .onFailure { error = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text("Add a contact", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Scanning their Clairix code pins their keys directly and marks them verified. Adding by number " +
                        "asks the relay for their keys instead, so they stay unverified until you compare safety numbers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name (optional)", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                SecondaryButton("Scan their code", onClick = { error = null; scan() }, modifier = Modifier.fillMaxWidth())
                Text("or, if their number is listed", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle, modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedTextField(
                    value = number, onValueChange = { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(11) },
                    label = { Text("Clairix number (9 digits)", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                Text(NOT_A_PHONE, style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (state.busy) "Looking up…" else "Add by number",
                onClick = {
                        error = null
                        scope.launch {
                            CommsRepository.addContactByNumber(number, name)
                                .onSuccess { onAdded(it.number) }
                                .onFailure { error = it.message }
                        }
                    },
                enabled = !state.busy && parseClairixNumber(number) != null
            )
        },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss, tone = Tone.Neutral) }
    )
}

// ── Conversation ─────────────────────────────────────────────────────────────

@Composable
private fun ThreadScreen(peer: String, onBack: () -> Unit, onVerify: () -> Unit) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val contact by produceState<Contact?>(initialValue = null, version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.contact(peer) }
    }
    val messages by produceState(initialValue = emptyList<ChatMessage>(), version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.messages(peer) }
    }
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    var sendError by remember(peer) { mutableStateOf<String?>(null) }
    var paying by remember(peer) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Voicemails in this conversation play through one player, stopped when the screen goes.
    val player = remember { VoicemailPlayer(context) }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) { onDispose { player.stop() } }

    // A photo or video picked (or taken) and made ready to send, waiting for SEND;
    // the text typed alongside goes with it as the caption.
    val mediaProgress by CommsRepository.mediaProgress.collectAsStateWithLifecycle()
    var attachment by remember(peer) { mutableStateOf<CommsMedia.Prepared?>(null) }
    var preparing by remember(peer) { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ChatMessage?>(null) }
    // Where the camera writes; kept across the trip to the camera app.
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun prepare(uri: Uri, video: Boolean?, deleteAfter: File? = null) {
        preparing = true
        sendError = null
        scope.launch {
            val isVideo = video ?: (context.contentResolver.getType(uri)?.startsWith("video/") == true)
            val result = withContext(Dispatchers.IO) {
                runCatching { if (isVideo) CommsMedia.prepareVideo(context, uri) else CommsMedia.prepareImage(context, uri) }
            }
            deleteAfter?.let { runCatching { it.delete() } }
            preparing = false
            result.onSuccess { attachment = it }
                .onFailure { sendError = it.message ?: "That file could not be read" }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepare(uri, null)
    }
    fun captureFile(extension: String): Pair<File, Uri> {
        val f = File(File(context.cacheDir, "camera").also { it.mkdirs() }, "capture_${System.currentTimeMillis()}.$extension")
        return f to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    fun capturedFile(): File? = captureUri?.let { File(File(context.cacheDir, "camera"), Uri.parse(it).lastPathSegment ?: return null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri?.let { Uri.parse(it) }
        val file = capturedFile()
        captureUri = null
        if (ok && uri != null) prepare(uri, video = false, deleteAfter = file) else file?.delete()
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = captureUri?.let { Uri.parse(it) }
        val file = capturedFile()
        captureUri = null
        if (ok && uri != null) prepare(uri, video = true, deleteAfter = file) else file?.delete()
    }
    var cameraWanted by remember { mutableStateOf<Boolean?>(null) } // true: photo, false: video
    fun openCamera(video: Boolean) {
        val (f, uri) = captureFile(if (video) "mp4" else "jpg")
        runCatching { f.parentFile?.listFiles()?.forEach { old -> if (old != f) old.delete() } }
        captureUri = uri.toString()
        if (video) recordVideo.launch(uri) else takePhoto.launch(uri)
    }
    // Clairix declares the camera permission (for scanning codes), so the camera
    // app refuses to hand a picture back until it is granted.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val video = cameraWanted ?: return@rememberLauncherForActivityResult
        cameraWanted = null
        if (granted) openCamera(video) else sendError = "Taking a ${if (video) "video" else "photo"} needs the camera. Allow it under Permissions."
    }
    fun camera(video: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(video)
        else { cameraWanted = video; askCamera.launch(Manifest.permission.CAMERA) }
    }
    if (attachMenu) {
        AttachMenu(
            onDismiss = { attachMenu = false },
            onGallery = { attachMenu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPhoto = { attachMenu = false; camera(video = false) },
            onVideo = { attachMenu = false; camera(video = true) }
        )
    }
    viewing?.let { MediaViewer(it, onDismiss = { viewing = null }) }

    // While this conversation is on screen and the app is in the foreground, its
    // messages are read on arrival and raise no notification. The composition
    // survives Home and the screen turning off, so the lifecycle decides, not
    // the composition: otherwise a message that arrived to a pocketed phone
    // would be receipted as read.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(peer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    CommsRepository.openPeer = peer
                    CommsNotifications.cancel(context, peer)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    if (CommsRepository.openPeer == peer) CommsRepository.openPeer = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (resumed) {
            CommsRepository.openPeer = peer
            CommsNotifications.cancel(context, peer)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (CommsRepository.openPeer == peer) CommsRepository.openPeer = null
        }
    }
    LaunchedEffect(peer, version, resumed) { if (resumed) CommsRepository.markRead(peer) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    val c = contact
    val placeCall = rememberMicrophoneGate { c?.let { CallManager.place(it) } }
    if (BuildConfig.CLAIRIX_COIN && paying && c != null) PayDialog(c, onDismiss = { paying = false })
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(c?.let { contactLabel(it) } ?: formatClairixNumber(peer), onBack = onBack) {
            val (label, tone) = when {
                c == null -> "" to Tone.Neutral
                c.keyChanged -> "Keys changed" to Tone.Danger
                c.verified -> "Verified" to Tone.Safe
                else -> "Verify" to Tone.Caution
            }
            if (label.isNotEmpty()) GhostButton(label, onClick = onVerify, tone = tone)
            if (BuildConfig.CLAIRIX_COIN) {
                GhostButton("Pay", onClick = { paying = true }, enabled = c != null && !c.keyChanged)
            }
            GhostButton("Call", onClick = placeCall, tone = Tone.Safe, enabled = c != null && !c.keyChanged)
        }
        Text(
            "${formatClairixNumber(peer)} · end-to-end encrypted · Clairix number, not a phone number",
            style = MaterialTheme.typography.monoSmall,
            color = ClairixTheme.colors.textSubtle
        )
        if (c?.keyChanged == true) {
            Spacer(Modifier.height(6.dp))
            Notice(
                Tone.Danger,
                "They may have reinstalled Clairix, or someone may be in between. Scan their code or compare safety numbers before you carry on.",
                title = "This contact's keys changed"
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) {
                item(key = "§empty") {
                    Text(
                        "No messages yet. The first one you send starts an encrypted session with ${c?.let { contactLabel(it) } ?: "this contact"}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }
            items(messages, key = { it.id }) { m ->
                MessageBubble(
                    m,
                    onRetry = { scope.launch { CommsRepository.retry(m.id) } },
                    playing = playing == m.id,
                    progress = mediaProgress[m.id],
                    onOpen = { viewing = m },
                    onPlay = {
                        if (playing == m.id) {
                            player.stop()
                            playing = null
                        } else {
                            val audio = VoicemailBody.decode(m.body)
                            if (audio != null && player.play(m.id, audio.audioB64) { playing = null }) playing = m.id
                            else Toast.makeText(context, "This voicemail cannot be played", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item(key = "§footer") { Spacer(Modifier.height(4.dp)) }
        }

        sendError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        val pending = attachment
        if (pending != null || preparing) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val thumb = remember(pending) { pending?.thumbB64?.takeIf { it.isNotBlank() }?.let { CommsMedia.decodeB64(it, 160)?.asImageBitmap() } }
                Box(Modifier.size(56.dp).clip(ClairixShapes.Tag).background(ClairixTheme.colors.surfaceRaised), contentAlignment = Alignment.Center) {
                    if (thumb != null) Image(thumb, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else if (preparing) CircularProgressIndicator(color = ClairixTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    else PictureGlyph(ClairixTheme.colors.textSubtle, Modifier.size(26.dp))
                    if (pending?.isVideo == true) Text("▶", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            pending == null -> "Preparing…"
                            pending.isVideo -> "Video · ${durationLabel(pending.durationMs)}"
                            else -> "Photo · ${pending.width}×${pending.height}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = ClairixTheme.colors.text
                    )
                    Text(
                        if (pending == null) "Reading and compressing the file"
                        else "${sizeLabel(pending.bytes.size.toLong())} · ${(pending.bytes.size + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES} encrypted envelope(s) · type a caption below, or none",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                GhostButton("Remove", onClick = { attachment = null }, tone = Tone.Neutral, enabled = !preparing)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { attachMenu = true },
                enabled = c != null && !c.keyChanged && attachment == null && !preparing,
                shape = ClairixShapes.Card, border = BorderStroke(1.dp, ClairixTheme.colors.border), contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(56.dp)
            ) { PictureGlyph(if (c != null && attachment == null && !preparing) ClairixTheme.colors.accent else ClairixTheme.colors.textSubtle, Modifier.size(24.dp)) }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(4000) },
                placeholder = { Text(if (attachment != null) "Caption (optional)" else "Encrypted message", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) },
                modifier = Modifier.weight(1f), maxLines = 5, colors = clairixTextFieldColors()
            )
            PrimaryButton(
                "Send",
                onClick = {
                        val text = draft.trim()
                        val media = attachment
                        if (text.isEmpty() && media == null) return@PrimaryButton
                        sendError = null
                        draft = ""
                        attachment = null
                        scope.launch {
                            if (media != null) CommsRepository.sendMedia(peer, media, text).onFailure { sendError = it.message ?: "Not sent" }
                            else CommsRepository.send(peer, text).onFailure { sendError = it.message ?: "Not sent" }
                        }
                    },
                enabled = (draft.isNotBlank() || attachment != null) && c != null && !preparing,
                modifier = Modifier.height(56.dp)
            )
        }
        when {
            !state.connected -> Text("Connecting to the relay… messages you send are queued until it is back.", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution, modifier = Modifier.padding(bottom = 8.dp))
            !state.online && !state.pushRegistered -> Text("Live while Clairix is open. Once it's closed nothing arrives and calls can't ring: turn on Online or push in Comms settings.", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun MessageBubble(
    m: ChatMessage,
    onRetry: () -> Unit,
    playing: Boolean = false,
    onPlay: () -> Unit = {},
    /** (chunks sent, chunks in all) while an outgoing photo or video is on its way. */
    progress: Pair<Int, Int>? = null,
    onOpen: () -> Unit = {}
) {
    val mine = m.direction == Direction.OUT
    if (m.isMedia) {
        MediaBubble(m, mine, progress, onOpen, onRetry)
        return
    }
    if (m.status == STATUS_CALL || m.status == STATUS_PAYMENT) {
        // A call or payment record: one quiet centred line in the conversation.
        val missed = m.status == STATUS_CALL && m.body.startsWith("Missed")
        val colour = when {
            missed -> ClairixTheme.colors.danger
            m.status == STATUS_PAYMENT -> ClairixTheme.colors.safe
            else -> ClairixTheme.colors.textSubtle
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (mine) "↗ " else "↙ ") + m.body + " · " + timeFmt.format(Date(m.ts)),
                style = MaterialTheme.typography.monoSmall,
                color = colour,
                modifier = Modifier.background(ClairixTheme.colors.surface, ClairixShapes.Tag).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        return
    }
    if (m.status == STATUS_VOICEMAIL) {
        val duration = VoicemailBody.decode(m.body)?.durationMs ?: 0L
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Row(
                Modifier
                    .widthIn(max = 300.dp)
                    .background(if (mine) ClairixTheme.colors.accentContainer else ClairixTheme.colors.surfaceRaised, if (mine) MineBubble else TheirBubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrimaryButton(
                    if (playing) "Stop" else "Play",
                    onClick = onPlay,
                    tone = if (playing) Tone.Danger else Tone.Safe
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Voicemail · ${durationLabel(duration)}", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
                    Text(timeFmt.format(Date(m.ts)), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) ClairixTheme.colors.accentContainer else ClairixTheme.colors.surfaceRaised, if (mine) MineBubble else TheirBubble)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(m.body, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(timeFmt.format(Date(m.ts)), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                if (mine) {
                    DeliveryMark(m.status)
                }
            }
        }
        if (m.failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.error ?: "Not sent", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.danger, modifier = Modifier.widthIn(max = 240.dp))
                GhostButton("Retry", onClick = onRetry)
            }
        }
    }
}

// ── Photos and videos ────────────────────────────────────────────────────────

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    else -> "${(bytes + 1023) / 1024} KB"
}

/**
 * The mark a message's delivery status shows after its time, for the sender:
 * a clock-face ellipsis while queued, one tick when sent, two when delivered,
 * two in green when read, and a warning when it failed.
 */
@Composable
private fun DeliveryMark(status: String) {
    val size = Modifier.size(12.dp)
    when (status) {
        "queued" -> Text("…", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
        "sent" -> Icon(Icons.Filled.Done, contentDescription = "Sent", tint = ClairixTheme.colors.textSubtle, modifier = size)
        "delivered" -> Icon(ClairixIcons.DoneAll, contentDescription = "Delivered", tint = ClairixTheme.colors.textSubtle, modifier = size)
        "read" -> Icon(ClairixIcons.DoneAll, contentDescription = "Read", tint = ClairixTheme.colors.safe, modifier = size)
        "failed" -> Icon(Icons.Filled.Warning, contentDescription = "Not sent", tint = ClairixTheme.colors.danger, modifier = size)
        else -> Unit
    }
}

/**
 * A photo or video in the conversation: the picture itself (the thumbnail that
 * came in the first envelope until the whole file is here, then the file),
 * its caption, and how far it has got. A tap opens it full-screen once it is
 * complete.
 */
@Composable
private fun MediaBubble(m: ChatMessage, mine: Boolean, progress: Pair<Int, Int>?, onOpen: () -> Unit, onRetry: () -> Unit) {
    val media = remember(m.body) { MediaBody.decode(m.body) }
    val receiving = m.status == STATUS_RECEIVING
    val complete = !receiving && m.status != "failed" || mine
    val thumb = remember(m.id, media?.thumbB64) { media?.thumbB64?.takeIf { it.isNotBlank() }?.let { CommsMedia.decodeB64(it, 480)?.asImageBitmap() } }
    // A photo that is here in full is shown from the file, sharper than its thumbnail.
    val full by produceState<ImageBitmap?>(initialValue = null, m.id, m.status) {
        if (media != null && !media.isVideo && (mine || !receiving)) {
            value = withContext(Dispatchers.IO) { CommsRepository.mediaBytes(m.id)?.let { CommsMedia.decode(it, 900)?.asImageBitmap() } }
        }
    }
    val picture = full ?: thumb
    val ratio = if (media != null && media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 4f / 3f
    val width = 240.dp
    val height = (width / ratio.coerceIn(0.6f, 2.2f))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 264.dp)
                .background(if (mine) ClairixTheme.colors.accentContainer else ClairixTheme.colors.surfaceRaised, if (mine) MineBubble else TheirBubble)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .width(width).height(height)
                    .clip(ClairixShapes.Tag)
                    .background(ClairixTheme.colors.base)
                    .clickable(enabled = complete && media != null, onClick = onOpen),
                contentAlignment = Alignment.Center
            ) {
                if (picture != null) {
                    Image(
                        picture, contentDescription = media?.label() ?: "Attachment",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    PictureGlyph(ClairixTheme.colors.textSubtle, Modifier.size(40.dp))
                }
                if (media?.isVideo == true) {
                    Box(Modifier.size(52.dp).background(ClairixTheme.colors.base.copy(alpha = 0.67f), ClairixShapes.Octagon), contentAlignment = Alignment.Center) {
                        Text("▶", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text)
                    }
                    Text(
                        durationLabel(media.durationMs),
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.text,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(ClairixTheme.colors.base.copy(alpha = 0.67f), ClairixShapes.Tag).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (receiving || progress != null) {
                    Column(
                        Modifier.fillMaxSize().background(ClairixTheme.colors.base.copy(alpha = 0.53f)),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = ClairixTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (progress != null) "Sending ${progress.first}/${progress.second}" else "Receiving…",
                            style = MaterialTheme.typography.monoSmall,
                            color = ClairixTheme.colors.text
                        )
                    }
                }
            }
            if (media != null && media.caption.isNotBlank()) {
                Text(media.caption, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (media?.let { if (it.isVideo) "Video" else "Photo" } ?: "Attachment") + (media?.let { " · ${sizeLabel(it.size)}" } ?: "") + " · " + timeFmt.format(Date(m.ts)),
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.textSubtle
                )
                if (mine) {
                    DeliveryMark(m.status)
                }
            }
        }
        if (m.failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.error ?: "Not sent", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.danger, modifier = Modifier.widthIn(max = 240.dp))
                if (mine) GhostButton("Retry", onClick = onRetry)
            }
        }
    }
}

/** Where an attachment comes from. */
@Composable
private fun AttachMenu(onDismiss: () -> Unit, onGallery: () -> Unit, onPhoto: () -> Unit, onVideo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text("Send a photo or video", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Photos are resized to at most ${CommsMedia.MAX_IMAGE_EDGE} px and sent as JPEG; videos go as they are, up to " +
                        "${CommsMedia.MAX_BYTES / (1024 * 1024)} MB. Either travels end-to-end encrypted in pieces the relay cannot read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                SecondaryButton("Choose from gallery", onClick = onGallery, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Take a photo", onClick = onPhoto, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Record a video", onClick = onVideo, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton("Cancel", onClick = onDismiss, tone = Tone.Neutral) }
    )
}

/**
 * A photo or video full-screen. The bytes are decrypted from the message's
 * file; a video is written to the cache only for as long as this is open,
 * since VideoView plays from a path.
 */
@Composable
private fun MediaViewer(m: ChatMessage, onDismiss: () -> Unit) {
    val media = remember(m.body) { MediaBody.decode(m.body) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(ClairixTheme.colors.base.copy(alpha = 0.95f))) {
            if (media == null) {
                Text("This attachment cannot be shown", color = ClairixTheme.colors.danger, modifier = Modifier.align(Alignment.Center))
            } else if (media.isVideo) {
                var file by remember { mutableStateOf<File?>(null) }
                var missing by remember { mutableStateOf(false) }
                LaunchedEffect(m.id) {
                    var f: File? = null
                    try {
                        // Assigned inside the IO block: a withContext cancelled
                        // mid-copy still runs the block to its end but drops
                        // the result, and the finished copy would be orphaned.
                        withContext(Dispatchers.IO) { f = CommsMedia.tempCopy(m.id, "mp4") }
                        if (f == null) missing = true else file = f
                    } finally {
                        // Cancelled before `file` was assigned (the viewer closed
                        // mid-copy): the dispose below would see null and miss it.
                        if (file == null) CommsMedia.discardTemp(f)
                    }
                }
                DisposableEffect(m.id) { onDispose { CommsMedia.discardTemp(file) } }
                val f = file
                if (f != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setOnPreparedListener { it.isLooping = false; start() }
                                setOnErrorListener { _, _, _ -> Toast.makeText(ctx, "This video cannot be played", Toast.LENGTH_SHORT).show(); true }
                                setVideoPath(f.absolutePath)
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(vertical = 48.dp)
                    )
                } else if (missing) {
                    Text("The video is no longer on this phone", color = ClairixTheme.colors.danger, modifier = Modifier.align(Alignment.Center))
                } else {
                    CircularProgressIndicator(color = ClairixTheme.colors.accent, modifier = Modifier.align(Alignment.Center))
                }
            } else {
                val image by produceState<ImageBitmap?>(initialValue = null, m.id) {
                    value = withContext(Dispatchers.IO) {
                        (CommsRepository.mediaBytes(m.id)?.let { CommsMedia.decode(it, 2048) } ?: CommsMedia.decodeB64(media.thumbB64, 480))?.asImageBitmap()
                    }
                }
                val bitmap = image
                if (bitmap != null) {
                    Image(bitmap, contentDescription = media.label(), modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss), contentScale = ContentScale.Fit)
                } else {
                    CircularProgressIndicator(color = ClairixTheme.colors.accent, modifier = Modifier.align(Alignment.Center))
                }
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    media?.let { (if (it.isVideo) "Video · ${durationLabel(it.durationMs)}" else "Photo · ${it.width}×${it.height}") + " · ${sizeLabel(it.size)}" } ?: "",
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.textMuted,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Close", onClick = onDismiss)
            }
            if (media != null && media.caption.isNotBlank()) {
                Text(
                    media.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.text,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().background(ClairixTheme.colors.base.copy(alpha = 0.69f)).padding(16.dp)
                )
            }
        }
    }
}

/** A picture drawn in place: a framed landscape with a sun, the attach button's icon. */
@Composable
private fun PictureGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(colour, topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, h - stroke), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(stroke))
        drawCircle(colour, radius = w * 0.09f, center = Offset(w * 0.34f, h * 0.34f))
        val hills = Path().apply {
            moveTo(w * 0.16f, h * 0.78f)
            lineTo(w * 0.42f, h * 0.5f)
            lineTo(w * 0.56f, h * 0.64f)
            lineTo(w * 0.66f, h * 0.55f)
            lineTo(w * 0.84f, h * 0.78f)
            close()
        }
        drawPath(hills, colour)
    }
}

// ── ClairixCoin ────────────────────────────────────────────────────────────────

/**
 * Pays a contact. The amount is a whole number of coins, the note goes to the
 * contact inside the encrypted envelope and never to the relay. The balance
 * shown is the relay's, refreshed when the dialog opens.
 */
@Composable
private fun PayDialog(contact: Contact, onDismiss: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { CommsRepository.refreshBalance() }
    val coins = amount.toLongOrNull()?.takeIf { it > 0L }
    val canPay = coins != null && !sending && (state.balance < 0L || coins <= state.balance)

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text("Pay ${contactLabel(contact)}", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (state.balance >= 0L) "Balance: ${state.balance} $COIN_SYMBOL on this relay" else "Balance: asking the relay…",
                    style = MaterialTheme.typography.monoSmall,
                    color = if (coins != null && state.balance >= 0L && coins > state.balance) ClairixTheme.colors.danger else ClairixTheme.colors.textMuted
                )
                OutlinedTextField(
                    value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() }.take(7); error = null },
                    label = { Text("Amount ($COIN_SYMBOL)") }, singleLine = true, enabled = !sending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = clairixTextFieldColors()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(140) },
                    label = { Text("Note (optional, encrypted)") }, maxLines = 3, enabled = !sending, colors = clairixTextFieldColors()
                )
                Text(
                    "ClairixCoin is this relay's own currency. The relay moves the coins on your signed request; the note goes to ${contactLabel(contact)} end-to-end encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (sending) "Paying…" else "Pay",
                onClick = {
                        val value = coins ?: return@GhostButton
                        sending = true
                        error = null
                        scope.launch {
                            CommsRepository.sendPayment(contact, value, note)
                                .onSuccess {
                                    Toast.makeText(context, "Paid $value $COIN_SYMBOL to ${contactLabel(contact)}", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                .onFailure {
                                    sending = false
                                    error = it.message ?: "Payment failed"
                                }
                        }
                    },
                tone = if (canPay) Tone.Accent else Tone.Neutral,
                enabled = canPay
            )
        },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss, tone = Tone.Neutral, enabled = !sending) }
    )
}

// ── In a call ────────────────────────────────────────────────────────────────

@Composable
internal fun InCallScreen(call: ActiveCall, modifier: Modifier = Modifier) {
    val now = rememberTick(1_000L)
    val accept = rememberMicrophoneGate { CallManager.accept() }
    val (title, colour) = when (call.phase) {
        CallPhase.INCOMING -> "Incoming call" to ClairixTheme.colors.safe
        CallPhase.DIALING -> if (call.ringing) "Ringing" to ClairixTheme.colors.safe else "Calling" to ClairixTheme.colors.textSubtle
        CallPhase.CONNECTING -> "Connecting" to ClairixTheme.colors.textSubtle
        CallPhase.CONNECTED -> "Encrypted call" to ClairixTheme.colors.safe
        CallPhase.RECONNECTING -> "Reconnecting" to ClairixTheme.colors.caution
        CallPhase.ENDED -> "Call ended" to ClairixTheme.colors.textSubtle
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = colour)
        Spacer(Modifier.height(12.dp))
        Text(contactLabel(call.peer), style = MaterialTheme.typography.headlineLarge, color = ClairixTheme.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            formatClairixNumber(call.peer.number) + when {
                call.peer.keyChanged -> " · Keys changed"
                call.peer.verified -> " · verified"
                else -> " · unverified"
            },
            style = MaterialTheme.typography.monoSmall,
            color = if (call.peer.verified && !call.peer.keyChanged) ClairixTheme.colors.textSubtle else ClairixTheme.colors.caution
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                call.phase == CallPhase.ENDED -> call.endReason?.replaceFirstChar { it.uppercase() } ?: "Ended"
                call.connectedAt > 0L -> CallManager.durationText(now - call.connectedAt)
                call.phase == CallPhase.INCOMING -> "Clairix call · not a phone call"
                call.phase == CallPhase.DIALING && call.ringing -> "Their phone is ringing"
                call.phase == CallPhase.DIALING -> "Reaching their phone"
                call.phase == CallPhase.RECONNECTING -> "The connection dropped; bringing it back"
                else -> "Connecting the encrypted audio"
            },
            style = MaterialTheme.typography.mono,
            color = if (call.phase == CallPhase.ENDED && call.connectedAt == 0L) ClairixTheme.colors.caution else ClairixTheme.colors.textMuted
        )
        Spacer(Modifier.height(48.dp))
        when (call.phase) {
            CallPhase.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CallButton("Decline", Tone.Danger) { CallManager.reject() }
                CallButton("Accept", Tone.Safe) { accept() }
            }
            CallPhase.ENDED -> Unit
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToggleChip(if (call.muted) "Unmute" else "Mute", call.muted) { CallManager.toggleMute() }
                    ToggleChip("Speaker", call.speaker) { CallManager.toggleSpeaker() }
                }
                CallButton("Hang up", Tone.Danger) { CallManager.hangUp() }
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            buildString {
                append("End-to-end encrypted: the keys for the audio were exchanged inside your encrypted session, so the relay cannot listen or step in.")
                if (call.relayed) append(" Audio is being forwarded by a TURN relay, which carries it but cannot decrypt it.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CallButton(label: String, tone: Tone, onClick: () -> Unit) {
    PrimaryButton(label, onClick = onClick, tone = tone, modifier = Modifier.height(56.dp).widthIn(min = 136.dp))
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    GhostButton(label, onClick = onClick, tone = if (on) Tone.Accent else Tone.Neutral)
}

// ── Verify a contact ─────────────────────────────────────────────────────────

@Composable
private fun VerifyScreen(peer: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // Loading and "no such contact" are different states: the first render has
    // not looked the contact up yet and must not navigate away.
    val lookup by produceState<Pair<Boolean, Contact?>>(initialValue = false to null, version, peer) {
        value = true to withContext(Dispatchers.IO) { CommsRepository.contact(peer) }
    }
    val (loaded, contact) = lookup
    if (!loaded) return
    val c = contact ?: run {
        LaunchedEffect(Unit) { onDeleted() }
        return
    }
    val safety = remember(c.ed25519) { CommsRepository.safetyNumber(c) }
    var name by rememberSaveable(c.number) { mutableStateOf(c.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scan = rememberCodeScanner { code ->
        when {
            code == null -> error = "That is not a Clairix code"
            code.number != c.number -> error = "That code belongs to ${formatClairixNumber(code.number)}, not this contact"
            else -> scope.launch {
                CommsRepository.addContactFromCode(code, name).onFailure { error = it.message }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ClairixTheme.colors.surfaceRaised,
            shape = ClairixShapes.Sheet,
            title = { Text("Remove ${contactLabel(c)}?", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = { Text("Their keys, the encrypted session and every message with them are deleted from this phone.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) },
            confirmButton = {
                GhostButton(
                    "Remove",
                    onClick = { confirmDelete = false; scope.launch { CommsRepository.deleteContact(c.number); onDeleted() } },
                    tone = Tone.Danger
                )
            },
            dismissButton = { GhostButton("Cancel", onClick = { confirmDelete = false }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(contactLabel(c), onBack = onBack)
        Text("${formatClairixNumber(c.number)} · Clairix number, not a phone number", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)

        ClairixCard {
            val (label, colour) = when {
                c.keyChanged -> "Keys changed. Verify again" to ClairixTheme.colors.danger
                c.verified -> "Verified" to ClairixTheme.colors.safe
                else -> "Unverified" to ClairixTheme.colors.caution
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
            Text(
                if (c.verified && !c.keyChanged) "You scanned this contact's code or compared safety numbers, so their keys came from them, not from the relay."
                else "Their keys came from the relay (or from their first message). A relay that lied could put itself in between. Verifying rules that out.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }

        ClairixCard {
            Text("Safety number", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text("Both phones show the same 60 digits for this pair. Compare them in person or over a call you trust.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            if (safety != null) {
                val groups = safety.split(" ")
                Column(Modifier.fillMaxWidth().background(ClairixTheme.colors.base, ClairixShapes.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.chunked(4).forEach { row ->
                        Text(row.joinToString("   "), style = MaterialTheme.typography.mono, color = ClairixTheme.colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Copy", onClick = { clipboard.setText(AnnotatedString(safety)) }, tone = Tone.Neutral)
                    if (!c.verified || c.keyChanged) GhostButton("They match — mark verified", onClick = { scope.launch { CommsRepository.markVerified(c.number) } }, tone = Tone.Safe)
                }
            } else {
                Text("Safety number unavailable: this device has no identity", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
            }
        }

        ClairixCard {
            Text("Or scan their code", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text("Their My code screen carries their keys. A matching scan verifies them in one step.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            SecondaryButton("Scan their code", onClick = { error = null; scan() }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }

        ClairixCard {
            Text("Name", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f), colors = clairixTextFieldColors())
                GhostButton("Save", onClick = { scope.launch { CommsRepository.renameContact(c.number, name) } }, enabled = name.trim() != c.name)
            }
            Text("Identity key ${c.ed25519.take(16)}…", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
        }

        GhostButton("Remove contact", onClick = { confirmDelete = true }, tone = Tone.Danger)
        Spacer(Modifier.height(16.dp))
    }
}

// ── My code ──────────────────────────────────────────────────────────────────

/**
 * Makes a one-time invite and shows it three ways: a QR code for someone
 * standing next to you (they scan it in Clairix setup), a link to send them
 * (it opens a page with the download and an Open in Clairix button), and the
 * share sheet.
 */
@Composable
private fun InviteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var made by remember { mutableStateOf<Pair<PairingCode, Long>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val create: () -> Unit = {
        busy = true
        error = null
        scope.launch {
            CommsRepository.createInvite()
                .onSuccess { made = it }
                .onFailure { error = it.message ?: "Could not make an invite" }
            busy = false
        }
    }
    val invite = made
    val bitmap = remember(invite) { invite?.first?.let { qrBitmap(it.encode(), 720) } }
    val until = invite?.second?.let { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("Invite", onBack = onBack)
        ClairixCard {
            Text("Invite someone to Clairix", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                "An invite lets one person get a Clairix number on your relay without the relay URL or its enrollment secret. " +
                    "It works once and expires after seven days. They start with you as a verified contact, and you get a message when they join.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            if (invite == null) {
                PrimaryButton(if (busy) "Making invite…" else "Make an invite", enabled = !busy, loading = busy, onClick = create, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }
        if (invite != null) {
            val link = invite.first.inviteLink()
            ClairixCard {
                Text("Next to you: let them scan this", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text("In Clairix on their phone: Comms, then Scan invite.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                if (bitmap != null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Image(bitmap.asImageBitmap(), contentDescription = "Clairix invite code", modifier = Modifier.size(260.dp).clip(ClairixShapes.Tag))
                    }
                }
            }
            ClairixCard {
                Text("Far away: send them the link", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "The link opens a page with the Clairix download and an Open in Clairix button. Anyone who gets the link can use it, " +
                        "so send it only to the person you mean. Works once, until $until.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        "Share link",
                        onClick = {
                            val text = "Join me on Clairix for end-to-end encrypted messages and calls: $link\n" +
                                "The invite works once, until $until."
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            runCatching { context.startActivity(Intent.createChooser(send, "Send invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Copy link", onClick = { clipboard.setText(AnnotatedString(link)) }, tone = Tone.Neutral)
                    GhostButton("New invite", onClick = { made = null; create() }, tone = Tone.Neutral, enabled = !busy)
                }
            }
        }
        Notice(Tone.Info, NOT_A_PHONE, title = "Not a phone number")
        Spacer(Modifier.height(16.dp))
    }
}

/** An invite link opened on a phone that already has a number: offer to add the inviter instead. */
@Composable
private fun AddInviterDialog(code: PairingCode, onDone: () -> Unit, onAdded: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val mine = CommsRepository.state.collectAsStateWithLifecycle().value.number == code.number
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text(if (mine) "Your own invite" else "Add ${code.name.ifBlank { formatClairixNumber(code.number) }}?", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (mine) "This invite is for someone who does not have Clairix yet. Send them the link."
                    else "This phone already has a Clairix number, so the invite is not needed. You can add " +
                        "${formatClairixNumber(code.number)} as a verified contact from it; the invite stays unused.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            if (!mine) GhostButton(
                "Add contact",
                onClick = {
                    scope.launch {
                        CommsRepository.addContactFromCode(code.copy(invite = null))
                            .onSuccess { onDone(); onAdded(it.number) }
                            .onFailure { error = it.message }
                    }
                }
            )
        },
        dismissButton = { GhostButton(if (mine) "OK" else "Cancel", onClick = onDone, tone = Tone.Neutral) }
    )
}

@Composable
private fun MyCodeScreen(onBack: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val code = remember(state.number, state.displayName) { CommsRepository.pairingCode() }
    val bitmap = remember(code) { code?.let { qrBitmap(it.encode(), 720) } }
    val myKey = remember(state.number) { CommsRepository.myEd25519() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("My code", onBack = onBack)
        ClairixCard {
            Text(state.number?.let { formatClairixNumber(it) } ?: "—", style = MaterialTheme.typography.monoLarge, color = ClairixTheme.colors.accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (state.displayName.isNotBlank()) Text(state.displayName, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("Clairix number · ${if (state.listed) "listed" else "unlisted"}", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (bitmap != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Your Clairix pairing code", modifier = Modifier.size(260.dp).clip(ClairixShapes.Tag))
                }
            }
            Text(
                "Let a contact scan this to add you with your keys pinned and verified. The code holds your relay, number and public keys only.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.number?.let { n -> GhostButton("Copy number", onClick = { clipboard.setText(AnnotatedString(formatClairixNumber(n))) }, tone = Tone.Neutral) }
                code?.let { c -> GhostButton("Copy code text", onClick = { clipboard.setText(AnnotatedString(c.encode())) }, tone = Tone.Neutral) }
            }
        }
        Notice(Tone.Info, NOT_A_PHONE, title = "Not a phone number")
        if (myKey != null) {
            ClairixCard {
                Text("Identity key", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(myKey, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
                Text("Generated on this phone; the private half never leaves it. A reinstall makes a new identity and a new number.", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────

@Composable
private fun CommsSettingsScreen(onBack: () -> Unit, onAppSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var notificationsGranted by remember { mutableStateOf(CommsNotifications.canPost(context)) }
    var callsChannelOk by remember { mutableStateOf(CommsNotifications.callsChannelOk(context)) }
    var microphoneGranted by remember { mutableStateOf(hasMicrophone(context)) }
    // Once refused twice, Android answers the request with "denied" without
    // showing anything; the owner is then sent to the settings page instead.
    fun blocked(permission: String) =
        context.findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && blocked(Manifest.permission.POST_NOTIFICATIONS)) {
            CommsNotifications.openAppNotificationSettings(context)
        }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (!granted && blocked(Manifest.permission.RECORD_AUDIO)) openAppDetails(context)
    }
    // Re-check after coming back from the system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = CommsNotifications.canPost(context)
                callsChannelOk = CommsNotifications.callsChannelOk(context)
                microphoneGranted = hasMicrophone(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allowNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Before API 33 there is no runtime permission; the switch lives in system settings.
            CommsNotifications.openAppNotificationSettings(context)
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = ClairixTheme.colors.surfaceRaised,
            shape = ClairixShapes.Sheet,
            title = { Text("Give up this Clairix number?", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    "The relay deletes your mailbox and keys, and this phone deletes its identity, contacts and messages. " +
                        "Registering again gives a different number; contacts will have to add you afresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    "Delete everything",
                    onClick = { confirmUnpair = false; scope.launch { CommsRepository.unpair() } },
                    tone = Tone.Danger
                )
            },
            dismissButton = { GhostButton("Cancel", onClick = { confirmUnpair = false }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header("Comms settings", onBack = onBack)
        Text("${state.number?.let { formatClairixNumber(it) } ?: "—"} on ${state.relayUrl ?: "—"}", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)

        ClairixCard {
            Text("Delivery", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            ToggleRow(
                title = "Online",
                checked = state.online,
                onCheckedChange = { CommsRepository.setOnline(it) },
                subtitle = "Keeps a connection to the relay open in the background, with a quiet notification, so messages arrive and calls ring while Clairix is closed. Uses some battery. While Clairix is on screen it is connected either way."
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Push wake-ups", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                    Text(
                        if (state.pushRegistered) "Registered with your UnifiedPush distributor. The relay pokes it when a message waits; the poke carries nothing."
                        else "Install a UnifiedPush distributor (ntfy from F-Droid works) and tap Set up. No Google services are used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                GhostButton(
                    if (state.pushRegistered) "Renew" else "Set up",
                    onClick = {
                        context.findActivity()?.let { CommsRepository.registerPush(it) }
                    }
                )
            }
            if (!notificationsGranted) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications are off, so new messages and incoming calls will not show until you open Clairix.", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution, modifier = Modifier.weight(1f))
                    GhostButton("Allow", onClick = allowNotifications)
                }
            } else if (!callsChannelOk) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Incoming-call notifications are switched off or set to silent, so calls do not ring or pop up. Set \"Incoming calls\" to alert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.caution,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton("Fix", onClick = { CommsNotifications.openCallsChannelSettings(context) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (microphoneGranted) "Microphone: allowed for calls."
                    else "Microphone: not allowed. Calls cannot be made or answered until it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (microphoneGranted) ClairixTheme.colors.textSubtle else ClairixTheme.colors.caution,
                    modifier = Modifier.weight(1f)
                )
                if (!microphoneGranted) GhostButton("Allow", onClick = { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) })
            }
            val batteryFree by rememberBatteryUnrestricted()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (batteryFree) "Battery: unrestricted. Android will not cut the background connection."
                    else "Battery: optimised. Android may cut the background connection, and then calls do not ring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryFree) ClairixTheme.colors.textSubtle else ClairixTheme.colors.caution,
                    modifier = Modifier.weight(1f)
                )
                if (!batteryFree) GhostButton("Allow", onClick = { openBatterySettings(context) })
            }
            var fullScreen by remember { mutableStateOf(CommsNotifications.canUseFullScreen(context)) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) fullScreen = CommsNotifications.canUseFullScreen(context)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            if (!fullScreen) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Full-screen calls: off. On a locked phone an incoming call shows only as a notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.caution,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton("Allow", onClick = { CommsNotifications.openFullScreenSettings(context) })
                }
            }
            Text(
                "Relay one-time keys: ${if (state.relayOneTimeKeys >= 0) state.relayOneTimeKeys else "unknown"} · topped up on sync",
                style = MaterialTheme.typography.monoSmall,
                color = ClairixTheme.colors.textSubtle
            )
        }

        ClairixCard {
            Text("Identity", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Name shown to contacts", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                    modifier = Modifier.weight(1f), colors = clairixTextFieldColors()
                )
                GhostButton("Save", onClick = { scope.launch { CommsRepository.setDisplayName(name) } }, enabled = name.trim() != state.displayName)
            }
            ToggleRow(
                title = "Listed number",
                checked = state.listed,
                onCheckedChange = { scope.launch { CommsRepository.setListed(it) } },
                subtitle = if (state.listed) "Anyone on this relay who knows your number can look up your keys and message you."
                    else "Unlisted: only people who scanned your code can reach you. Lookups by number fail.",
                enabled = !state.busy
            )
        }

        // The app-wide settings — passcode, biometric unlock, duress code — live on
        // their own screen. In the full app that is reached from the Scan header
        // too; in the comms-only build this row is the only way to it.
        if (onAppSettings != null) {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("App passcode and lock", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "A passcode for the app itself, biometric unlock, a duress code that wipes everything, and the version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    GhostButton("Open", onClick = onAppSettings)
                }
            }
        }

        ClairixCard {
            Text("About Clairix numbers", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.caution)
            Text(NOT_A_PHONE, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            Text(
                "Numbers are random and handed out by your relay at registration. Two people on different relays cannot message each other.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }

        state.error?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger, modifier = Modifier.weight(1f))
                GhostButton("Dismiss", onClick = { CommsRepository.clearError() }, tone = Tone.Neutral)
            }
        }

        DiagnosticsCard()

        GhostButton(
            "Delete identity and number",
            onClick = { confirmUnpair = true },
            tone = Tone.Danger,
            enabled = !state.busy
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ── Background reliability and diagnostics ───────────────────────────────────

/**
 * Whether Android exempts Clairix from battery optimisation, re-read whenever the
 * screen resumes (the owner comes back from the system settings page).
 */
@Composable
private fun rememberBatteryUnrestricted(): State<Boolean> {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember { mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted.value = power?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return unrestricted
}

// ── Group conversation ───────────────────────────────────────────────────────

@Composable
private fun GroupThreadScreen(groupId: String, onBack: () -> Unit, onMembers: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version by CommsRepository.version.collectAsStateWithLifecycle()

    val group by produceState<Group?>(null, groupId, version) {
        value = withContext(Dispatchers.IO) { CommsRepository.group(groupId) }
    }
    val messages by produceState(emptyList<ChatMessage>(), groupId, version) {
        value = withContext(Dispatchers.IO) { CommsRepository.groupMessages(groupId) }
    }
    val members by produceState(emptyList<GroupMember>(), groupId, version) {
        value = withContext(Dispatchers.IO) { CommsRepository.groupMembers(groupId) }
    }

    // As in ThreadScreen: while this group is on screen and the app is in the
    // foreground, its messages arrive read and raise no notification.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(groupId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    CommsRepository.openPeer = groupId
                    CommsNotifications.cancel(context, groupId)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    if (CommsRepository.openPeer == groupId) CommsRepository.openPeer = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (resumed) {
            CommsRepository.openPeer = groupId
            CommsNotifications.cancel(context, groupId)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (CommsRepository.openPeer == groupId) CommsRepository.openPeer = null
        }
    }
    LaunchedEffect(groupId, version, resumed) { if (resumed) CommsRepository.markGroupRead(groupId) }

    var draft by rememberSaveable(groupId) { mutableStateOf("") }
    var sendError by remember(groupId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun senderName(number: String): String =
        members.find { it.number == number }?.name?.takeIf { it.isNotBlank() }
            ?: formatClairixNumber(number)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val detections by Registry.detections.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val topThreat = detections.filter { it.following }.maxByOrNull { it.threat.ordinal }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(group?.name ?: "Group", onBack = onBack) {
            GhostButton("Members", onClick = onMembers, tone = Tone.Neutral)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${members.size} member${if (members.size == 1) "" else "s"} · end-to-end encrypted",
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (messages.isEmpty()) {
                item {
                    ClairixCard {
                        Text("No messages yet", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                        Text(
                            "Messages are encrypted for every member individually and sent to the relay once per member.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                val isOut = msg.direction == Direction.OUT
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isOut) Alignment.End else Alignment.Start
                ) {
                    if (!isOut && msg.sender != null) {
                        Text(
                            senderName(msg.sender),
                            style = MaterialTheme.typography.monoSmall,
                            color = ClairixTheme.colors.textSubtle,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )
                    }
                    Box(
                        Modifier
                            .widthIn(max = 280.dp)
                            .background(
                                if (isOut) ClairixTheme.colors.accent.copy(alpha = 0.20f) else ClairixTheme.colors.surface,
                                ClairixShapes.Card
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(msg.body, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    timeFmt.format(Date(msg.ts)),
                                    style = MaterialTheme.typography.monoSmall,
                                    color = ClairixTheme.colors.textSubtle
                                )
                                if (isOut && msg.status == "failed") {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Filled.Close, contentDescription = "Not sent", tint = ClairixTheme.colors.danger, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        sendError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (topThreat != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().background(ClairixTheme.colors.danger.copy(alpha = 0.10f), ClairixShapes.Card).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Active tracker: ${topThreat.tracker?.label ?: topThreat.name}", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.danger)
                    Text("Tap to share with the group", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                }
                GhostButton(
                    "Share threat",
                    onClick = {
                        scope.launch {
                            CommsRepository.shareThreats(
                                gid = groupId,
                                kind = "FOLLOWING",
                                severity = topThreat.threat.name,
                                lat = status.lat,
                                lon = status.lon,
                                title = topThreat.tracker?.label ?: topThreat.name,
                                detail = "Clairix has detected a possible following tracker."
                            ).onFailure { sendError = it.message }
                        }
                    },
                    tone = Tone.Danger
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) },
                maxLines = 4,
                colors = clairixTextFieldColors()
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                "Send",
                onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@PrimaryButton
                        val toSend = text
                        sendError = null
                        draft = ""
                        scope.launch {
                            CommsRepository.sendGroupMessage(groupId, toSend)
                                .onFailure { sendError = it.message ?: "Could not send" }
                        }
                    },
                enabled = draft.isNotBlank()
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Group member management ───────────────────────────────────────────────────

@Composable
private fun OrgScreen(groupId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val myNumber = CommsRepository.state.value.number ?: ""

    val group by produceState<Group?>(null, groupId, version) {
        value = withContext(Dispatchers.IO) { CommsRepository.group(groupId) }
    }
    val members by produceState(emptyList<GroupMember>(), groupId, version) {
        value = withContext(Dispatchers.IO) { CommsRepository.groupMembers(groupId) }
    }

    val myRole = members.find { it.number == myNumber }?.role ?: group?.myRole ?: OrgRole.MEMBER
    var addMember by remember { mutableStateOf(false) }
    var leaveConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (addMember) {
        AddGroupMemberDialog(
            groupId = groupId,
            existing = members.map { it.number },
            onDismiss = { addMember = false }
        )
    }

    if (leaveConfirm) {
        AlertDialog(
            onDismissRequest = { leaveConfirm = false },
            containerColor = ClairixTheme.colors.surfaceRaised,
            shape = ClairixShapes.Sheet,
            title = { Text("Leave group?", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    "You will leave \"${group?.name ?: "this group"}\" and will no longer receive its messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            },
            confirmButton = {
                GhostButton(
                    "Leave",
                    onClick = {
                        leaveConfirm = false
                        scope.launch {
                            CommsRepository.leaveGroup(groupId)
                                .onSuccess { onLeft() }
                                .onFailure { error = it.message }
                        }
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = { GhostButton("Cancel", onClick = { leaveConfirm = false }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        Header(group?.name ?: "Group", onBack = onBack) {
            if (myRole == OrgRole.ADMIN) GhostButton("+ Add", onClick = { addMember = true }, tone = Tone.Safe)
        }
        Spacer(Modifier.height(4.dp))
        Text("${members.size} member${if (members.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
        Spacer(Modifier.height(12.dp))

        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger, modifier = Modifier.padding(bottom = 8.dp))
        }

        SectionHeader("Members")
        Spacer(Modifier.height(6.dp))
        ClairixCard {
            if (members.isEmpty()) {
                Text("No members yet.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    members.forEach { member ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    member.name.ifBlank { formatClairixNumber(member.number) },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ClairixTheme.colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatClairixNumber(member.number),
                                    style = MaterialTheme.typography.monoSmall,
                                    color = ClairixTheme.colors.textSubtle
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusPill(member.role.name, if (member.role == OrgRole.ADMIN) Tone.Accent else Tone.Neutral)
                            if (myRole == OrgRole.ADMIN && member.number != myNumber) {
                                GhostButton(
                                    "Remove",
                                    onClick = {
                                        scope.launch {
                                            CommsRepository.removeGroupMember(groupId, member.number)
                                                .onFailure { error = it.message }
                                        }
                                    },
                                    tone = Tone.Danger
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Actions")
        Spacer(Modifier.height(6.dp))
        SecondaryButton("Leave group", onClick = { leaveConfirm = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AddGroupMemberDialog(groupId: String, existing: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val contacts by produceState(emptyList<Contact>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.contacts() }
    }
    val available = contacts.filter { it.number !in existing }
    var selected by remember { mutableStateOf<Contact?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text("Add member", style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available.isEmpty()) {
                    Text(
                        if (contacts.isEmpty()) "Add contacts first, then add them to groups."
                        else "All your contacts are already in this group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                } else {
                    Text("Choose a contact to add:", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(available, key = { it.number }) { c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(ClairixShapes.Card)
                                    .background(if (selected?.number == c.number) ClairixTheme.colors.accent.copy(alpha = 0.15f) else ClairixTheme.colors.surfaceRaised)
                                    .clickable { selected = c }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatClairixNumber(c.number), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                                }
                                if (selected?.number == c.number) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = ClairixTheme.colors.accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (busy) "Adding…" else "Add",
                onClick = {
                        val c = selected ?: return@GhostButton
                        busy = true
                        error = null
                        scope.launch {
                            CommsRepository.addGroupMember(groupId, c.number)
                                .onSuccess { onDismiss() }
                                .onFailure { busy = false; error = it.message ?: "Could not add member" }
                        }
                    },
                tone = if (selected != null && !busy) Tone.Accent else Tone.Neutral,
                enabled = selected != null && !busy
            )
        },
        dismissButton = { GhostButton("Cancel", onClick = { if (!busy) onDismiss() }, tone = Tone.Neutral, enabled = !busy) }
    )
}

/**
 * Asks Android to let Clairix run unrestricted. The direct request shows a
 * one-tap system dialog; where a phone does not offer it, the battery
 * optimisation list opens instead so the owner can find Clairix there.
 */
private fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(list) }
}

private val logTimeFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

/**
 * The comms event log: connections, envelopes that could not be read, calls
 * that arrived too late to ring. COPY puts it on the clipboard to send to
 * whoever is helping; it holds no message text.
 */
@Composable
private fun DiagnosticsCard() {
    val entries by CommsLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    ClairixCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
            GhostButton(
                "Copy",
                onClick = {
                    val text = entries.joinToString("\n") { "${logTimeFmt.format(Date(it.ts))}  ${it.text}" }
                    clipboard.setText(AnnotatedString("Clairix comms log\n$text"))
                },
                enabled = entries.isNotEmpty()
            )
            GhostButton("Clear", onClick = { CommsLog.clear() }, tone = Tone.Neutral, enabled = entries.isNotEmpty())
        }
        Text(
            "What happened with the relay, calls and envelopes on this phone. No message text is kept. " +
                "If a call or message goes missing, copy this and send it along.",
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle
        )
        if (entries.isEmpty()) {
            Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
        } else {
            val shown = entries.asReversed().let { if (expanded) it else it.take(12) }
            for (e in shown) {
                Text(
                    "${logTimeFmt.format(Date(e.ts))}  ${e.text}",
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.textMuted
                )
            }
            if (entries.size > 12) {
                GhostButton(if (expanded) "Show less" else "Show all ${entries.size}", onClick = { expanded = !expanded }, tone = Tone.Neutral)
            }
        }
    }
}
