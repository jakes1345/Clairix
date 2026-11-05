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
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import app.clairix.comms.CoinRules
import app.clairix.comms.ChatMessage
import app.clairix.comms.ChatThread
import app.clairix.comms.CommsMedia
import app.clairix.comms.CommsWire
import app.clairix.comms.Group
import app.clairix.comms.GroupMember
import app.clairix.comms.GroupThread
import app.clairix.comms.MediaBody
import app.clairix.comms.OrgRole
import app.clairix.comms.ListedRelay
import app.clairix.comms.RelayClient
import app.clairix.comms.RelayDirectory
import app.clairix.comms.TorTransport
import app.clairix.comms.STATUS_CALL
import app.clairix.comms.STATUS_PAYMENT
import app.clairix.comms.STATUS_RECEIVING
import app.clairix.comms.STATUS_SYSTEM
import app.clairix.comms.KIND_NOTE
import app.clairix.comms.LinkHealth
import app.clairix.comms.ttlLabel
import app.clairix.comms.TIMER_CHOICES
import androidx.compose.material3.RadioButton
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
import app.clairix.comms.ContactGate
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
import app.clairix.ui.makerBackgroundSteps
import app.clairix.ui.openBatterySettings
import app.clairix.ui.rememberBatteryUnrestricted
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.monoLarge
import app.clairix.ui.theme.mono
import app.clairix.security.AppLock
import app.clairix.security.NotificationPresentation
import app.clairix.ui.components.setSensitiveText

// ── Colour tokens local to this file ──────────────────────────────────────────

private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
private val dateTimeFmt = SimpleDateFormat("MMM d HH:mm", Locale.US)

private const val UI_PREFS = "comms_ui"
private const val KEY_MIC_ASKED = "microphone_asked"

private const val NOT_A_PHONE = "Clairix numbers are not phone numbers. They only reach other Clairix apps, on your relay or on another relay that yours can reach; they cannot call or text a phone."

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
    /** Moving this phone to another relay. */
    object Move : CommsPage()
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
    val context = LocalContext.current
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
                code == null -> inviteError = context.getString(R.string.comms_that_invite_link_is_incomplete_ask)
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
    // A call that would share this phone's address waits here for the owner's yes.
    DirectCallDialog()
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
        CommsPage.Settings -> CommsSettingsScreen(
            onBack = { page = CommsPage.List }, onAppSettings = onAppSettings, onMove = { page = CommsPage.Move },
            onOpenThread = { page = CommsPage.Thread(it) }
        )
        CommsPage.Move -> MoveRelayScreen(onBack = { page = CommsPage.Settings })
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.comms_back), tint = ClairixTheme.colors.text)
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
                context.getString(if (blocked) R.string.comms_microphone_blocked_retry else R.string.comms_calls_need_microphone),
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
    val context = LocalContext.current
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
                    .setPrompt(context.getString(R.string.comms_point_at_the_other_persons_clairix))
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

@Composable
private fun agoText(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> stringResource(R.string.comms_just_now)
        s < 3600 -> stringResource(R.string.comms_minutes_ago, s / 60)
        s < 86_400 -> stringResource(R.string.comms_hours_ago, s / 3600)
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
    val context = LocalContext.current
    val relayList by RelayDirectory.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RelayDirectory.current(context) }
        RelayDirectory.refreshIfStale(context)
    }
    val takeInvite: (PairingCode?) -> Unit = { code ->
        when {
            code == null -> inviteError = context.getString(R.string.comms_that_is_not_a_clairix_invite)
            code.invite == null -> inviteError = context.getString(R.string.comms_thats_someones_contact_code_not_an)
            else -> { inviteError = null; onInvite(code) }
        }
    }
    val scanInvite = rememberCodeScanner { takeInvite(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        StatusHero(
            eyebrow = stringResource(R.string.comms_messages_and_calls),
            headline = if (invite != null) stringResource(R.string.comms_invite_ready) else stringResource(R.string.comms_not_set_up),
            body = stringResource(R.string.comms_end_to_end_encrypted_messages_and),
            tone = if (invite != null) Tone.Accent else Tone.Neutral,
            trailing = { LogoMark(size = 48.dp, contentDescription = null) }
        )
        val update by app.clairix.comms.AppUpdate.notice.collectAsStateWithLifecycle()
        app.clairix.ui.UnsafeVersionBanner(update)

        if (invite != null) {
            val listedRelay = relayList?.find(RelayClient.originOf(invite.relayUrl) ?: invite.relayUrl)
            var trustRelay by remember(invite) { mutableStateOf(false) }
            ClairixCard {
                Text(stringResource(R.string.comms_join_with_an_invite), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(stringResource(R.string.comms_invited_by), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                Text(invite.name.ifBlank { formatClairixNumber(invite.number) }, style = MaterialTheme.typography.headlineMedium, color = ClairixTheme.colors.accent)
                Text("${formatClairixNumber(invite.number)} · ${invite.relayUrl.removePrefix("https://")}", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
                Text(
                    stringResource(R.string.comms_invite_join_explained, invite.name.ifBlank { stringResource(R.string.comms_they) }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                InviteRelayNotice(invite, listedRelay)
                if (listedRelay == null) {
                    ToggleRow(
                        title = stringResource(R.string.comms_i_know_who_runs_this_relay),
                        checked = trustRelay,
                        onCheckedChange = { trustRelay = it },
                        subtitle = stringResource(R.string.comms_register_only_if, invite.name.ifBlank { stringResource(R.string.comms_the_person_who_invited_you) })
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.comms_your_name_shown_to_contacts_optional), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                ToggleRow(
                    title = stringResource(R.string.comms_listed_number),
                    checked = listed,
                    onCheckedChange = { listed = it },
                    subtitle = if (listed) stringResource(R.string.comms_anyone_with_your_number_on_this) else stringResource(R.string.comms_unlisted_people_can_add_you_only)
                )
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
                PrimaryButton(
                    if (state.busy) stringResource(R.string.comms_registering) else stringResource(R.string.comms_register),
                    onClick = {
                        scope.launch { CommsRepository.register(invite.relayUrl, "", name, listed, invite) }
                    },
                    enabled = !state.busy && (listedRelay != null || trustRelay),
                    modifier = Modifier.fillMaxWidth()
                )
                GhostButton(stringResource(R.string.comms_use_a_relay_url_and_secret), onClick = { onInvite(null) }, tone = Tone.Neutral)
            }
            Notice(Tone.Caution, NO_CLOUD_BACKUP, title = stringResource(R.string.comms_no_cloud_backup))
            Notice(Tone.Info, NOT_A_PHONE, title = stringResource(R.string.comms_not_a_phone_number))
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        ClairixCard {
            Text(stringResource(R.string.comms_have_an_invite), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.comms_someone_already_on_clairix_can_invite),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    stringResource(R.string.comms_scan_invite),
                    onClick = { inviteError = null; scanInvite() },
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    stringResource(R.string.comms_paste_invite),
                    onClick = { takeInvite(clipboard.getText()?.text?.let { PairingCode.fromText(it) }) },
                    modifier = Modifier.weight(1f)
                )
            }
            inviteError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }

        relayList?.relays?.takeIf { it.isNotEmpty() }?.let { relays ->
            ClairixCard {
                Text(stringResource(R.string.comms_relays_you_can_join), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.comms_from_the_clairix_relay_list_pick),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                for (relay in relays) {
                    HorizontalDivider(color = ClairixTheme.colors.border)
                    Text(relay.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                    Text("${relay.country} · ${relay.host}", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textMuted)
                    Text(stringResource(R.string.comms_run_by, relay.operator), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textMuted)
                    Text(relay.url.removePrefix("https://"), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                    relay.join?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) }
                    SecondaryButton(
                        if (url.trim().trimEnd('/') == relay.url) stringResource(R.string.comms_selected_below) else stringResource(R.string.comms_use_this_relay),
                        onClick = { url = relay.url },
                        enabled = url.trim().trimEnd('/') != relay.url,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        ClairixCard {
            Text(stringResource(R.string.comms_or_enter_a_relay), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.comms_run_comms_worker_from_the_clairix),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text(stringResource(R.string.comms_relay_url), style = MaterialTheme.typography.bodyMedium) },
                placeholder = { Text("https://relay.clairix.app", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = clairixTextFieldColors()
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text(stringResource(R.string.comms_enrollment_secret_if_the_relay_asks), style = MaterialTheme.typography.bodyMedium) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = clairixTextFieldColors()
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.comms_name_shown_to_contacts_optional), style = MaterialTheme.typography.bodyMedium) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
            )
            ToggleRow(
                title = stringResource(R.string.comms_listed_number),
                checked = listed,
                onCheckedChange = { listed = it },
                subtitle = if (listed) stringResource(R.string.comms_anyone_with_your_number_on_this) else stringResource(R.string.comms_unlisted_people_can_add_you_only)
            )
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            PrimaryButton(
                if (state.busy) stringResource(R.string.comms_registering) else stringResource(R.string.comms_register),
                onClick = {
                    scope.launch { CommsRepository.register(url, secret, name, listed) }
                },
                // The relay decides: without a secret it registers only when its operator opened registration.
                enabled = !state.busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Notice(Tone.Caution, NO_CLOUD_BACKUP, title = stringResource(R.string.comms_no_cloud_backup))
        RestoreFromFileCard()

        Notice(Tone.Info, NOT_A_PHONE, title = stringResource(R.string.comms_not_a_phone_number))

        ClairixCard {
            Text(stringResource(R.string.comms_whats_protected), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.comms_every_message_is_encrypted_on_this),
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
    // People who wrote first and are not contacts: kept apart until the owner accepts them.
    val requests by produceState(initialValue = emptyList<ChatThread>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.requests() }
    }
    var addContact by remember { mutableStateOf(false) }
    var createGroup by remember { mutableStateOf(false) }
    val now = rememberTick()

    if (addContact) AddContactDialog(onDismiss = { addContact = false }, onAdded = { addContact = false; onOpen(it) })
    if (createGroup) CreateGroupDialog(onDismiss = { createGroup = false }, onCreated = { createGroup = false; onOpenGroup(it) })
    // The coin balance is the relay's figure; it is asked for when the list comes up.
    // Editions built without ClairixCoin (the Play edition) never ask, and nor does a
    // phone whose relay does not offer it (its GET / says so).
    val coinShown = CoinRules.shown(BuildConfig.CLAIRIX_COIN, state.registered, state.coin)
    LaunchedEffect(coinShown) {
        if (coinShown) CommsRepository.refreshBalance()
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
                    Text(stringResource(R.string.comms_your_clairix_number), style = MaterialTheme.typography.labelSmall, color = ClairixTheme.colors.textSubtle)
                    Text(
                        state.number?.let { formatClairixNumber(it) } ?: "—",
                        style = MaterialTheme.typography.monoLarge,
                        color = ClairixTheme.colors.accent
                    )
                }
                StatusPill(if (state.connected) stringResource(R.string.comms_live) else stringResource(R.string.comms_connecting), if (state.connected) Tone.Safe else Tone.Caution)
            }
            Text(
                buildString {
                    append(
                        when {
                            state.online -> stringResource(R.string.comms_online_in_the_background)
                            state.pushRegistered -> stringResource(R.string.comms_push_wake_ups_on)
                            else -> stringResource(R.string.comms_nothing_arrives_while_clairix_is_closed)
                        }
                    )
                    if (!state.listed) append(stringResource(R.string.comms_unlisted))
                    if (coinShown && state.balance >= 0L) append(" · ${state.balance} $COIN_SYMBOL")
                    if (state.lastSync > 0L) append(stringResource(R.string.comms_synced, agoText(now, state.lastSync)))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = reachTone.color()
            )
            Text(
                stringResource(R.string.comms_not_a_phone_number_it_reaches),
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(stringResource(R.string.comms_my_code), onClick = onMyCode, tone = Tone.Accent)
                GhostButton(if (state.busy) stringResource(R.string.comms_syncing) else stringResource(R.string.comms_sync), onClick = { scope.launch { CommsRepository.sync() } }, tone = Tone.Neutral, enabled = !state.busy)
                Spacer(Modifier.weight(1f))
                GhostButton(stringResource(R.string.settings_settings), onClick = onSettings, tone = Tone.Neutral)
            }
        }
        Spacer(Modifier.height(8.dp))
        // ── Actions ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.comms_add_contact), onClick = { addContact = true }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.comms_invite), onClick = onInvite, tone = Tone.Accent, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.comms_group), onClick = { createGroup = true }, tone = Tone.Accent, modifier = Modifier.weight(1f))
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Notice(
                Tone.Danger, it,
                action = { GhostButton(stringResource(R.string.comms_dismiss), onClick = { CommsRepository.clearError() }, tone = Tone.Neutral) }
            )
        }
        val batteryFree by rememberBatteryUnrestricted()
        // Android refused twice in a row to restart the background connection (LinkHealth).
        val linkStopped by LinkHealth.stopped.collectAsStateWithLifecycle()
        if (state.online && (!batteryFree || linkStopped)) {
            val context = LocalContext.current
            // The maker's own switch where Clairix knows its path; otherwise where such switches are.
            val makerSteps = remember { makerBackgroundSteps(Build.MANUFACTURER) }
            Spacer(Modifier.height(6.dp))
            Notice(
                Tone.Caution,
                text = buildString {
                    append(
                        if (linkStopped) stringResource(R.string.comms_android_refused_twice_in_a_row)
                        else stringResource(R.string.comms_battery_saving_can_cut_clairix_off)
                    )
                    if (!batteryFree) append(stringResource(R.string.comms_let_clairix_run_unrestricted))
                    append(makerSteps)
                },
                title = if (linkStopped) NotificationPresentation.LINK_STOPPED_TITLE else null,
                action = {
                    if (!batteryFree) GhostButton(stringResource(R.string.comms_allow), onClick = { openBatterySettings(context) })
                    else GhostButton(stringResource(R.string.comms_app_info), onClick = { openAppDetails(context) })
                }
            )
        }
        // Below the oldest safe version nothing but an SOS is sent; this says why, until the update.
        val update by app.clairix.comms.AppUpdate.notice.collectAsStateWithLifecycle()
        app.clairix.ui.UnsafeVersionBanner(update, Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))

        if (threads.isEmpty() && groupThreads.isEmpty() && requests.isEmpty()) {
            NoContactsCard()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (requests.isNotEmpty()) {
                    item(key = "§requests") { SectionHeader(stringResource(R.string.comms_requests), supporting = "${requests.size}", tone = Tone.Caution) }
                    items(requests, key = { "r:${it.contact.number}" }) { t -> RequestRow(t, onClick = { onOpen(t.contact.number) }) }
                }
                // Groups first: a group's stripe is always the accent colour, so
                // the section label keeps them apart from a contact with unread messages.
                if (groupThreads.isNotEmpty()) {
                    item(key = "§groups") { SectionHeader(stringResource(R.string.comms_groups), supporting = "${groupThreads.size}") }
                    items(groupThreads, key = { "g:${it.group.id}" }) { gt -> GroupRow(gt, onClick = { onOpenGroup(gt.group.id) }) }
                }
                if (groupThreads.isNotEmpty() || requests.isNotEmpty()) {
                    item(key = "§contacts") { SectionHeader(stringResource(R.string.comms_contacts), supporting = "${threads.size}") }
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
        title = stringResource(R.string.comms_no_contacts_yet),
        body = stringResource(R.string.comms_tap_add_contact_and_scan_their),
        icon = ClairixIcons.Forum,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Names a new group; the phone that makes it is its first member and admin. */
/**
 * The conversation's disappearing-messages timer, and the button that changes it.
 * [peer] is a contact's number or a group id.
 */
@Composable
private fun DisappearingRow(peer: String, version: Long, enabled: Boolean) {
    val timer by produceState(initialValue = 0, version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.timer(peer) }
    }
    var choosing by remember(peer) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (timer > 0) stringResource(R.string.comms_messages_disappear_after_they_are_read, ttlLabel(timer)) else stringResource(R.string.comms_disappearing_messages_are_off),
            style = MaterialTheme.typography.bodySmall,
            color = if (timer > 0) ClairixTheme.colors.accent else ClairixTheme.colors.textSubtle,
            modifier = Modifier.weight(1f)
        )
        GhostButton(stringResource(R.string.comms_disappearing), onClick = { choosing = true }, tone = Tone.Neutral, enabled = enabled)
    }
    if (choosing) DisappearingDialog(peer, timer, onDismiss = { choosing = false })
}

@Composable
private fun DisappearingDialog(peer: String, current: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text(stringResource(R.string.comms_disappearing_messages), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.comms_each_message_is_deleted_from_this),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                for (choice in TIMER_CHOICES) {
                    val label = if (choice == 0) stringResource(R.string.comms_off) else ttlLabel(choice).replaceFirstChar { it.uppercase() }
                    Row(
                        Modifier.fillMaxWidth().clip(ClairixShapes.Tag)
                            .clickable(enabled = !busy, onClickLabel = stringResource(R.string.comms_set_disappearing_messages_to, label)) {
                                if (choice == current) { onDismiss(); return@clickable }
                                busy = true
                                error = null
                                scope.launch {
                                    CommsRepository.setTimer(peer, choice)
                                        .onSuccess { onDismiss() }
                                        .onFailure { busy = false; error = it.message ?: context.getString(R.string.comms_could_not_change_it) }
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice == current, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = ClairixTheme.colors.text)
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = { GhostButton(stringResource(R.string.comms_close), onClick = onDismiss, tone = Tone.Neutral, enabled = !busy) }
    )
}

@Composable
private fun CreateGroupDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
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
                    .onFailure { busy = false; error = it.message ?: context.getString(R.string.comms_could_not_create_the_group) }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text(stringResource(R.string.comms_create_a_group), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.comms_a_group_is_a_conversation_with),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                OutlinedTextField(
                    value = groupName, onValueChange = { groupName = it.take(60) },
                    label = { Text(stringResource(R.string.comms_group_name), style = MaterialTheme.typography.bodyMedium) }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (busy) stringResource(R.string.comms_creating) else stringResource(R.string.comms_create),
                onClick = create,
                tone = if (groupName.isNotBlank() && !busy) Tone.Accent else Tone.Neutral,
                enabled = groupName.isNotBlank() && !busy
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral, enabled = !busy) }
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
    ClairixCard(tone = tone, onClick = onClick, onClickLabel = stringResource(R.string.comms_open_conversation), spacing = 4.dp) {
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
                        t.contact.keyChanged -> StatusPill(stringResource(R.string.comms_keys_changed), Tone.Danger)
                        t.contact.verified -> StatusPill(stringResource(R.string.comms_verified), Tone.Safe)
                        else -> StatusPill(stringResource(R.string.comms_unverified), Tone.Neutral)
                    }
                }
            }
            if (t.unread > 0) UnreadCount(t.unread)
        }
        if (m != null) {
            Text(
                (if (m.direction == Direction.OUT) stringResource(R.string.comms_you) else "") + m.preview(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (t.unread > 0) c.text else c.textSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (m.failed) Text(m.error ?: stringResource(R.string.comms_not_sent), style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

/**
 * A message request in the conversation list: the number, the name they gave
 * (theirs to choose, so said as theirs), and how much is waiting, but never
 * what it says, until the request is opened.
 */
@Composable
private fun RequestRow(t: ChatThread, onClick: () -> Unit) {
    val c = ClairixTheme.colors
    val m = t.lastMessage
    ClairixCard(tone = Tone.Caution, onClick = onClick, onClickLabel = stringResource(R.string.comms_open_message_request), spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar("#", highlighted = t.unread > 0)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        formatClairixNumber(t.contact.number),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (m != null) Text(dateTimeFmt.format(Date(m.ts)), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
                }
                Text(
                    buildString {
                        if (t.contact.name.isNotBlank()) append(stringResource(R.string.comms_says_they_are)).append(t.contact.name).append(" · ")
                        append(if (t.contact.relay != null) "on ${relayHost(t.contact.relay)}" else stringResource(R.string.comms_not_in_your_contacts))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (t.unread > 0) UnreadCount(t.unread)
        }
    }
}

/** A group in the conversation list. Groups carry no per-contact trust state, so no verification pill. */
@Composable
private fun GroupRow(gt: GroupThread, onClick: () -> Unit) {
    val m = gt.lastMessage
    val unread = gt.unread > 0
    val c = ClairixTheme.colors
    ClairixCard(tone = if (unread) Tone.Accent else null, onClick = onClick, onClickLabel = stringResource(R.string.comms_open_group), spacing = 4.dp) {
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
                (if (m.direction == Direction.OUT) stringResource(R.string.comms_you) else "") + m.preview(),
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
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var number by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scan = rememberCodeScanner { code ->
        if (code == null) { error = context.getString(R.string.comms_that_is_not_a_clairix_code); return@rememberCodeScanner }
        scope.launch {
            CommsRepository.addContactFromCode(code, name.takeIf { it.isNotBlank() }, CommsRepository.CodeSource.SCANNED)
                .onSuccess { onAdded(it.number) }
                .onFailure { error = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text(stringResource(R.string.comms_add_a_contact), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.comms_scanning_their_clairix_code_pins_their),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.comms_name_optional), style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                SecondaryButton(stringResource(R.string.comms_scan_their_code), onClick = { error = null; scan() }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.comms_or_if_their_number_is_listed), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle, modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedTextField(
                    value = number, onValueChange = { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(11) },
                    label = { Text(stringResource(R.string.comms_clairix_number_9_digits), style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), colors = clairixTextFieldColors()
                )
                Text(NOT_A_PHONE, style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (state.busy) stringResource(R.string.comms_looking_up) else stringResource(R.string.comms_add_by_number),
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
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral) }
    )
}

// ── Conversation ─────────────────────────────────────────────────────────────

@Composable
private fun ThreadScreen(peer: String, onBack: () -> Unit, onVerify: () -> Unit) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // A message request or a blocked contact kept as a record opens here too, read-only.
    val contact by produceState<Contact?>(initialValue = null, version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.contactEntry(peer) }
    }
    val messages by produceState(initialValue = emptyList<ChatMessage>(), version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.messages(peer) }
    }
    // What arrived from this contact's new keys, held until the owner looks.
    val held by produceState(initialValue = 0, version, peer) {
        value = withContext(Dispatchers.IO) { CommsRepository.heldCount(peer) }
    }
    var reviewing by remember(peer) { mutableStateOf(false) }
    var unblocking by remember(peer) { mutableStateOf(false) }
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
                .onFailure { sendError = it.message ?: context.getString(R.string.comms_that_file_could_not_be_read) }
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
        if (granted) openCamera(video) else sendError = context.getString(if (video) R.string.comms_camera_needed_video else R.string.comms_camera_needed_photo)
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
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    DisposableEffect(peer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (CommsRepository.openPeer == peer) CommsRepository.openPeer = null
        }
    }
    // On screen means resumed and not behind the lock screen, which is drawn over
    // this one: only then do messages arrive read, go out as read receipts and
    // clear their notification.
    val onScreen = resumed && !locked
    LaunchedEffect(peer, onScreen) {
        if (onScreen) {
            CommsRepository.openPeer = peer
            CommsNotifications.cancel(context, peer)
        } else if (CommsRepository.openPeer == peer) {
            CommsRepository.openPeer = null
        }
    }
    LaunchedEffect(peer, version, onScreen) { if (onScreen) CommsRepository.markRead(peer) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    // The one "Someone new wrote to you" notification has been answered once a request is read.
    LaunchedEffect(onScreen, contact?.request) { if (onScreen && contact?.request == true) CommsNotifications.cancelRequest(context) }

    val c = contact
    // A message request or a blocked contact is read here, never written to or called.
    val usable = c != null && !c.request && !c.blocked
    val placeCall = rememberMicrophoneGate { c?.takeIf { usable }?.let { CallManager.place(it) } }
    var reporting by remember(peer) { mutableStateOf(false) }
    // Pay shows only where the relay offers ClairixCoin, in an edition built with it.
    val coinShown = CoinRules.shown(BuildConfig.CLAIRIX_COIN, state.registered, state.coin)
    if (coinShown && paying && c != null) PayDialog(c, onDismiss = { paying = false })
    if (reporting && c != null) ReportDialog(c, onDismiss = { reporting = false })
    if (reviewing && c != null) {
        HeldReviewDialog(
            c, held,
            onVerify = { reviewing = false; onVerify() },
            onAccept = { reviewing = false; scope.launch { CommsRepository.acceptNewKeys(c.number) } },
            onDiscard = { reviewing = false; scope.launch { CommsRepository.discardHeld(c.number) } },
            onDismiss = { reviewing = false }
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(c?.takeIf { usable }?.let { contactLabel(it) } ?: formatClairixNumber(peer), onBack = onBack) {
            val (label, tone) = when {
                c == null || !usable -> "" to Tone.Neutral
                c.keyChanged -> stringResource(R.string.comms_keys_changed) to Tone.Danger
                c.verified -> stringResource(R.string.comms_verified) to Tone.Safe
                else -> stringResource(R.string.comms_verify) to Tone.Caution
            }
            if (label.isNotEmpty()) GhostButton(label, onClick = onVerify, tone = tone)
            if (coinShown && usable) {
                GhostButton(stringResource(R.string.comms_pay), onClick = { paying = true }, enabled = c?.keyChanged == false)
            }
            if (usable) GhostButton(stringResource(R.string.comms_call), onClick = placeCall, tone = Tone.Safe, enabled = c?.keyChanged == false)
        }
        Text(
            stringResource(R.string.comms_end_to_end_encrypted_clairix_number, formatClairixNumber(peer)),
            style = MaterialTheme.typography.monoSmall,
            color = ClairixTheme.colors.textSubtle
        )
        if (usable) DisappearingRow(peer, version, enabled = true)
        if (c?.request == true) {
            Spacer(Modifier.height(6.dp))
            ClairixCard(tone = Tone.Caution, spacing = 6.dp) {
                Text(stringResource(R.string.comms_someone_new_wrote_to_you), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(
                        R.string.comms_request_explained,
                        if (c.name.isNotBlank()) stringResource(R.string.comms_they_say_they_are_a_name, c.name) else "",
                        formatClairixNumber(c.number),
                        if (held > 0) stringResource(R.string.comms_an_invitation_to_a_group_from) else ""
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(stringResource(R.string.comms_accept), onClick = { scope.launch { CommsRepository.acceptRequest(c.number) } })
                    SecondaryButton(stringResource(R.string.comms_decline), onClick = { scope.launch { CommsRepository.declineRequest(c.number); onBack() } }, tone = Tone.Danger)
                    GhostButton(stringResource(R.string.comms_report), onClick = { reporting = true }, tone = Tone.Caution)
                }
            }
        }
        if (c?.blocked == true) {
            Spacer(Modifier.height(6.dp))
            Notice(
                Tone.Danger,
                stringResource(R.string.comms_nothing_they_send_reaches_this_phone),
                title = stringResource(R.string.comms_you_blocked_this_number),
                action = {
                    Row {
                        GhostButton(stringResource(R.string.comms_report), onClick = { reporting = true }, tone = Tone.Caution)
                        GhostButton(stringResource(R.string.settings_unblock_2), onClick = { unblocking = true }, tone = Tone.Neutral)
                    }
                }
            )
            if (unblocking) {
                UnblockDialog(
                    who = c.name.ifBlank { formatClairixNumber(c.number) },
                    kept = true,
                    onConfirm = { unblocking = false; scope.launch { CommsRepository.unblockContact(c.number) } },
                    onDismiss = { unblocking = false }
                )
            }
        }
        if (c?.keyChanged == true) {
            Spacer(Modifier.height(6.dp))
            Notice(
                Tone.Danger,
                stringResource(R.string.comms_they_may_have_reinstalled_clairix_or),
                title = stringResource(R.string.comms_this_contacts_keys_changed)
            )
        }
        if (c != null && usable && (c.keyChanged || held > 0)) {
            Spacer(Modifier.height(6.dp))
            ClairixCard(tone = Tone.Caution, onClick = { reviewing = true }, onClickLabel = stringResource(R.string.comms_review_held_messages), spacing = 4.dp) {
                Text(
                    if (held > 0) stringResource(R.string.comms_held_from_their_new_keys, held) else stringResource(R.string.comms_anything_from_their_new_keys_is),
                    style = MaterialTheme.typography.titleSmall,
                    color = ClairixTheme.colors.text
                )
                Text(
                    stringResource(R.string.comms_messages_files_and_group_posts_from),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) {
                item(key = "§empty") {
                    Text(
                        if (c != null && !usable) stringResource(R.string.comms_no_messages_here)
                        else stringResource(R.string.comms_no_messages_yet_first_starts, c?.let { contactLabel(it) } ?: stringResource(R.string.comms_this_contact)),
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
                            else Toast.makeText(context, context.getString(R.string.comms_this_voicemail_cannot_be_played), Toast.LENGTH_SHORT).show()
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
                            pending == null -> stringResource(R.string.comms_preparing)
                            pending.isVideo -> stringResource(R.string.comms_video, durationLabel(pending.durationMs))
                            else -> stringResource(R.string.comms_photo, pending.width, pending.height)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = ClairixTheme.colors.text
                    )
                    Text(
                        if (pending == null) stringResource(R.string.comms_reading_and_compressing_the_file)
                        else stringResource(R.string.comms_encrypted_envelope_s_type_a_caption, sizeLabel(pending.bytes.size.toLong()), (pending.bytes.size + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                GhostButton(stringResource(R.string.settings_remove), onClick = { attachment = null }, tone = Tone.Neutral, enabled = !preparing)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { attachMenu = true },
                enabled = usable && c?.keyChanged == false && attachment == null && !preparing,
                shape = ClairixShapes.Card, border = BorderStroke(1.dp, ClairixTheme.colors.border), contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(56.dp)
            ) { PictureGlyph(if (usable && attachment == null && !preparing) ClairixTheme.colors.accent else ClairixTheme.colors.textSubtle, Modifier.size(24.dp)) }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(4000) },
                placeholder = {
                    Text(
                        when {
                            c?.request == true -> stringResource(R.string.comms_accept_the_request_to_reply)
                            c?.blocked == true -> stringResource(R.string.comms_unblock_to_write_to_them)
                            attachment != null -> stringResource(R.string.comms_caption_optional)
                            else -> stringResource(R.string.comms_encrypted_message)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                },
                enabled = usable || c == null,
                modifier = Modifier.weight(1f), maxLines = 5, colors = clairixTextFieldColors()
            )
            PrimaryButton(
                stringResource(R.string.comms_send),
                onClick = {
                        val text = draft.trim()
                        val media = attachment
                        if (text.isEmpty() && media == null) return@PrimaryButton
                        sendError = null
                        draft = ""
                        attachment = null
                        scope.launch {
                            if (media != null) CommsRepository.sendMedia(peer, media, text).onFailure { sendError = it.message ?: context.getString(R.string.comms_not_sent) }
                            else CommsRepository.send(peer, text).onFailure { sendError = it.message ?: context.getString(R.string.comms_not_sent) }
                        }
                    },
                enabled = (draft.isNotBlank() || attachment != null) && usable && !preparing,
                modifier = Modifier.height(56.dp)
            )
        }
        when {
            !state.connected -> Text(stringResource(R.string.comms_connecting_to_the_relay_messages_you), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution, modifier = Modifier.padding(bottom = 8.dp))
            !state.online && !state.pushRegistered -> Text(stringResource(R.string.comms_live_while_clairix_is_open_once), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/**
 * What to do with what arrived from a contact's new keys: verify them (the
 * safety number or their code), accept them unverified, or throw it away.
 */
@Composable
private fun HeldReviewDialog(c: Contact, held: Int, onVerify: () -> Unit, onAccept: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text(stringResource(R.string.comms_s_new_keys, contactLabel(c)), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.comms_if_they_reinstalled_clairix_compare_safety, (if (held > 0) stringResource(R.string.comms_item_s_arrived_from_keys_you, held) else stringResource(R.string.comms_nothing_has_arrived_from_the_new))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Text(
                    stringResource(R.string.comms_accepting_shows_them_and_lets_you),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle
                )
                GhostButton(stringResource(R.string.comms_compare_safety_numbers), onClick = onVerify, tone = Tone.Safe)
                if (c.keyChanged) GhostButton(stringResource(R.string.comms_accept_new_keys), onClick = onAccept, tone = Tone.Caution)
                if (held > 0) GhostButton(stringResource(R.string.comms_discard_held_items), onClick = onDiscard, tone = Tone.Danger)
            }
        },
        confirmButton = { GhostButton(stringResource(R.string.comms_close), onClick = onDismiss, tone = Tone.Neutral) }
    )
}

/**
 * Reports [c] to this phone's relay: a reason, and what goes with it. The
 * relay's operator learns the number, its relay, the reason and the hash of
 * the last envelope it sent, never what it said or who reported it.
 */
@Composable
private fun ReportDialog(c: Contact, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<ContactGate.ReportReason?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = ClairixTheme.colors.surfaceRaised,
        shape = ClairixShapes.Sheet,
        title = { Text(stringResource(R.string.comms_report_2, formatClairixNumber(c.number)), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.comms_tells_the_operator_of_your_relay),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                for (choice in ContactGate.ReportReason.entries) {
                    Row(
                        Modifier.fillMaxWidth().clip(ClairixShapes.Tag)
                            .clickable(enabled = !busy, onClickLabel = stringResource(R.string.comms_report_for, choice.label)) { reason = choice }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = reason == choice, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = ClairixTheme.colors.text)
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (busy) stringResource(R.string.comms_sending) else stringResource(R.string.comms_send_report),
                onClick = {
                    val chosen = reason ?: return@GhostButton
                    busy = true
                    error = null
                    scope.launch {
                        CommsRepository.report(c.number, chosen)
                            .onSuccess {
                                Toast.makeText(context, context.getString(R.string.comms_report_sent_to_your_relays_operator), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .onFailure { busy = false; error = it.message ?: context.getString(R.string.comms_the_report_could_not_be_sent) }
                    }
                },
                tone = Tone.Caution,
                enabled = reason != null && !busy
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral, enabled = !busy) }
    )
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
    if (m.status == STATUS_SYSTEM) {
        // Something this phone noted in the conversation: a key change (a warning), or a timer change (a note).
        val warning = m.kind != KIND_NOTE
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (warning) "⚠ " else "") + m.body + " · " + timeFmt.format(Date(m.ts)),
                style = MaterialTheme.typography.monoSmall,
                color = if (warning) ClairixTheme.colors.caution else ClairixTheme.colors.textSubtle,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(ClairixTheme.colors.surface, ClairixShapes.Tag).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
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
                    if (playing) stringResource(R.string.comms_stop) else stringResource(R.string.comms_play),
                    onClick = onPlay,
                    tone = if (playing) Tone.Danger else Tone.Safe
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.comms_voicemail, durationLabel(duration)), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
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
                Text(m.error ?: stringResource(R.string.comms_not_sent), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.danger, modifier = Modifier.widthIn(max = 240.dp))
                GhostButton(stringResource(R.string.comms_retry), onClick = onRetry)
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
        "sent" -> Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.comms_sent), tint = ClairixTheme.colors.textSubtle, modifier = size)
        "delivered" -> Icon(ClairixIcons.DoneAll, contentDescription = stringResource(R.string.comms_delivered), tint = ClairixTheme.colors.textSubtle, modifier = size)
        "read" -> Icon(ClairixIcons.DoneAll, contentDescription = stringResource(R.string.comms_read), tint = ClairixTheme.colors.safe, modifier = size)
        "failed" -> Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.comms_not_sent), tint = ClairixTheme.colors.danger, modifier = size)
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
                        picture, contentDescription = media?.label() ?: stringResource(R.string.comms_attachment),
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
                            if (progress != null) stringResource(R.string.comms_sending_2, progress.first, progress.second) else stringResource(R.string.comms_receiving),
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
                    (media?.let { if (it.isVideo) stringResource(R.string.comms_video_2) else stringResource(R.string.comms_photo_2) } ?: stringResource(R.string.comms_attachment)) + (media?.let { " · ${sizeLabel(it.size)}" } ?: "") + " · " + timeFmt.format(Date(m.ts)),
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
                Text(m.error ?: stringResource(R.string.comms_not_sent), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.danger, modifier = Modifier.widthIn(max = 240.dp))
                if (mine) GhostButton(stringResource(R.string.comms_retry), onClick = onRetry)
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
        title = { Text(stringResource(R.string.comms_send_a_photo_or_video), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.comms_photos_are_resized_to_at_most, CommsMedia.MAX_IMAGE_EDGE, CommsMedia.sendLimitBytes() / (1024 * 1024)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                SecondaryButton(stringResource(R.string.comms_choose_from_gallery), onClick = onGallery, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.comms_take_a_photo), onClick = onPhoto, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.comms_record_a_video), onClick = onVideo, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral) }
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
                Text(stringResource(R.string.comms_this_attachment_cannot_be_shown), color = ClairixTheme.colors.danger, modifier = Modifier.align(Alignment.Center))
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
                                setOnErrorListener { _, _, _ -> Toast.makeText(ctx, ctx.getString(R.string.comms_video_cannot_be_played), Toast.LENGTH_SHORT).show(); true }
                                setVideoPath(f.absolutePath)
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(vertical = 48.dp)
                    )
                } else if (missing) {
                    Text(stringResource(R.string.comms_the_video_is_no_longer_on), color = ClairixTheme.colors.danger, modifier = Modifier.align(Alignment.Center))
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
                    media?.let { (if (it.isVideo) stringResource(R.string.comms_video, durationLabel(it.durationMs)) else stringResource(R.string.comms_photo, it.width, it.height)) + " · ${sizeLabel(it.size)}" } ?: "",
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.textMuted,
                    modifier = Modifier.weight(1f)
                )
                GhostButton(stringResource(R.string.comms_close), onClick = onDismiss)
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
        title = { Text(stringResource(R.string.comms_pay_2, contactLabel(contact)), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (state.balance >= 0L) stringResource(R.string.comms_balance_on_this_relay, state.balance, COIN_SYMBOL) else stringResource(R.string.comms_balance_asking_the_relay),
                    style = MaterialTheme.typography.monoSmall,
                    color = if (coins != null && state.balance >= 0L && coins > state.balance) ClairixTheme.colors.danger else ClairixTheme.colors.textMuted
                )
                OutlinedTextField(
                    value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() }.take(7); error = null },
                    label = { Text(stringResource(R.string.comms_amount, COIN_SYMBOL)) }, singleLine = true, enabled = !sending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = clairixTextFieldColors()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(140) },
                    label = { Text(stringResource(R.string.comms_note_optional_encrypted)) }, maxLines = 3, enabled = !sending, colors = clairixTextFieldColors()
                )
                Text(
                    stringResource(R.string.comms_clairixcoin_is_this_relays_own_currency, contactLabel(contact)),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            GhostButton(
                if (sending) stringResource(R.string.comms_paying) else stringResource(R.string.comms_pay),
                onClick = {
                        val value = coins ?: return@GhostButton
                        sending = true
                        error = null
                        scope.launch {
                            CommsRepository.sendPayment(contact, value, note)
                                .onSuccess {
                                    Toast.makeText(context, context.getString(R.string.comms_paid_to, value, COIN_SYMBOL, contactLabel(contact)), Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                .onFailure {
                                    sending = false
                                    error = it.message ?: context.getString(R.string.comms_payment_failed)
                                }
                        }
                    },
                tone = if (canPay) Tone.Accent else Tone.Neutral,
                enabled = canPay
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral, enabled = !sending) }
    )
}

// ── In a call ────────────────────────────────────────────────────────────────

@Composable
internal fun InCallScreen(call: ActiveCall, modifier: Modifier = Modifier) {
    val now = rememberTick(1_000L)
    val accept = rememberMicrophoneGate { CallManager.accept() }
    val (title, colour) = when (call.phase) {
        CallPhase.INCOMING -> stringResource(R.string.comms_incoming_call) to ClairixTheme.colors.safe
        CallPhase.DIALING -> if (call.ringing) stringResource(R.string.comms_ringing) to ClairixTheme.colors.safe else stringResource(R.string.comms_calling) to ClairixTheme.colors.textSubtle
        CallPhase.CONNECTING -> stringResource(R.string.comms_connecting) to ClairixTheme.colors.textSubtle
        CallPhase.CONNECTED -> stringResource(R.string.comms_encrypted_call) to ClairixTheme.colors.safe
        CallPhase.RECONNECTING -> stringResource(R.string.comms_reconnecting) to ClairixTheme.colors.caution
        CallPhase.ENDED -> stringResource(R.string.comms_call_ended) to ClairixTheme.colors.textSubtle
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
                call.peer.keyChanged -> stringResource(R.string.comms_keys_changed_2)
                call.peer.verified -> stringResource(R.string.comms_verified_2)
                else -> stringResource(R.string.comms_unverified_2)
            },
            style = MaterialTheme.typography.monoSmall,
            color = if (call.peer.verified && !call.peer.keyChanged) ClairixTheme.colors.textSubtle else ClairixTheme.colors.caution
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                call.phase == CallPhase.ENDED -> call.endReason?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.comms_ended)
                call.connectedAt > 0L -> CallManager.durationText(now - call.connectedAt)
                call.phase == CallPhase.INCOMING -> stringResource(R.string.comms_clairix_call_not_a_phone_call)
                call.phase == CallPhase.DIALING && call.ringing -> stringResource(R.string.comms_their_phone_is_ringing)
                call.phase == CallPhase.DIALING -> stringResource(R.string.comms_reaching_their_phone)
                call.phase == CallPhase.RECONNECTING -> stringResource(R.string.comms_the_connection_dropped_bringing_it_back)
                else -> stringResource(R.string.comms_connecting_the_encrypted_audio)
            },
            style = MaterialTheme.typography.mono,
            color = if (call.phase == CallPhase.ENDED && call.connectedAt == 0L) ClairixTheme.colors.caution else ClairixTheme.colors.textMuted
        )
        Spacer(Modifier.height(48.dp))
        when (call.phase) {
            CallPhase.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CallButton(stringResource(R.string.comms_decline), Tone.Danger) { CallManager.reject() }
                CallButton(stringResource(R.string.comms_accept), Tone.Safe) { accept() }
            }
            CallPhase.ENDED -> Unit
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToggleChip(if (call.muted) stringResource(R.string.comms_unmute) else stringResource(R.string.comms_mute), call.muted) { CallManager.toggleMute() }
                    ToggleChip(stringResource(R.string.comms_speaker), call.speaker) { CallManager.toggleSpeaker() }
                }
                CallButton(stringResource(R.string.comms_hang_up), Tone.Danger) { CallManager.hangUp() }
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            buildString {
                append(stringResource(R.string.comms_end_to_end_encrypted_the_keys))
                if (call.relayed) append(stringResource(R.string.comms_audio_is_being_forwarded_by_a))
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
    val context = LocalContext.current
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
    var confirm by remember { mutableStateOf<ContactAction?>(null) }
    var reporting by remember { mutableStateOf(false) }
    val scan = rememberCodeScanner { code ->
        when {
            code == null -> error = context.getString(R.string.comms_that_is_not_a_clairix_code)
            code.number != c.number -> error = context.getString(R.string.comms_that_code_belongs_to_not_this, formatClairixNumber(code.number))
            else -> scope.launch {
                CommsRepository.addContactFromCode(code, name, CommsRepository.CodeSource.SCANNED_FOR_CONTACT).onFailure { error = it.message }
            }
        }
    }

    if (reporting) ReportDialog(c, onDismiss = { reporting = false })
    confirm?.let { action ->
        val label = contactLabel(c)
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = ClairixTheme.colors.surfaceRaised,
            shape = ClairixShapes.Sheet,
            title = { Text(stringResource(R.string.comms_contact_action_title, stringResource(action.title), label), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = { Text(stringResource(action.explanation), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) },
            confirmButton = {
                GhostButton(
                    stringResource(action.title),
                    onClick = {
                        confirm = null
                        scope.launch {
                            when (action) {
                                ContactAction.DELETE -> CommsRepository.deleteContact(c.number)
                                ContactAction.BLOCK -> CommsRepository.block(c.number, delete = false)
                                ContactAction.BLOCK_AND_DELETE -> CommsRepository.block(c.number, delete = true)
                            }
                            onDeleted()
                        }
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = { confirm = null }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(contactLabel(c), onBack = onBack)
        Text(stringResource(R.string.comms_clairix_number_not_a_phone_number, formatClairixNumber(c.number)), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)

        c.relay?.let { theirs ->
            ClairixCard {
                Text(stringResource(R.string.comms_on_another_relay), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(theirs, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.comms_everything_for_this_contact_goes_to),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }

        ClairixCard {
            val (label, colour) = when {
                c.keyChanged -> stringResource(R.string.comms_keys_changed_verify_again) to ClairixTheme.colors.danger
                c.verified -> stringResource(R.string.comms_verified) to ClairixTheme.colors.safe
                else -> stringResource(R.string.comms_unverified) to ClairixTheme.colors.caution
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
            Text(
                if (c.verified && !c.keyChanged) stringResource(R.string.comms_you_scanned_this_contacts_code_or)
                else stringResource(R.string.comms_their_keys_came_from_the_relay),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }

        ClairixCard {
            Text(stringResource(R.string.comms_safety_number), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(stringResource(R.string.comms_both_phones_show_the_same_60), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            if (safety != null) {
                val groups = safety.split(" ")
                Column(Modifier.fillMaxWidth().background(ClairixTheme.colors.base, ClairixShapes.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.chunked(4).forEach { row ->
                        Text(row.joinToString("   "), style = MaterialTheme.typography.mono, color = ClairixTheme.colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(stringResource(R.string.settings_copy), onClick = { clipboard.setSensitiveText(context.getString(R.string.comms_safety_number), safety) }, tone = Tone.Neutral)
                    if (!c.verified || c.keyChanged) GhostButton(stringResource(R.string.comms_they_match_mark_verified), onClick = { scope.launch { CommsRepository.markVerified(c.number) } }, tone = Tone.Safe)
                }
            } else {
                Text(stringResource(R.string.comms_safety_number_unavailable_this_device_has), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
            }
        }

        ClairixCard {
            Text(stringResource(R.string.comms_or_scan_their_code), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(stringResource(R.string.comms_their_my_code_screen_carries_their), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            SecondaryButton(stringResource(R.string.comms_scan_their_code), onClick = { error = null; scan() }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }

        ClairixCard {
            Text(stringResource(R.string.comms_name), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f), colors = clairixTextFieldColors())
                GhostButton(stringResource(R.string.settings_save), onClick = { scope.launch { CommsRepository.renameContact(c.number, name) } }, enabled = name.trim() != c.name)
            }
            Text(stringResource(R.string.comms_identity_key, c.ed25519.take(16)), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
        }

        ClairixCard {
            Text(stringResource(R.string.comms_if_they_are_bothering_you), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.comms_report_tells_your_relays_operator_about),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            GhostButton(stringResource(R.string.comms_report), onClick = { reporting = true }, tone = Tone.Caution)
            GhostButton(stringResource(R.string.comms_block), onClick = { confirm = ContactAction.BLOCK }, tone = Tone.Danger)
            GhostButton(stringResource(R.string.comms_block_and_delete), onClick = { confirm = ContactAction.BLOCK_AND_DELETE }, tone = Tone.Danger)
            GhostButton(stringResource(R.string.settings_delete), onClick = { confirm = ContactAction.DELETE }, tone = Tone.Danger)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** What the contact page can do to a contact, and what each one says before it does it. */
private enum class ContactAction(@StringRes val title: Int, @StringRes val explanation: Int) {
    BLOCK(R.string.comms_block, R.string.comms_block_explained),
    BLOCK_AND_DELETE(R.string.comms_block_and_delete, R.string.comms_block_and_delete_explained),
    DELETE(R.string.settings_delete, R.string.comms_delete_explained)
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
                .onFailure { error = it.message ?: context.getString(R.string.comms_could_not_make_an_invite) }
            busy = false
        }
    }
    val invite = made
    val bitmap = remember(invite) { invite?.first?.let { qrBitmap(it.encode(), 720) } }
    val until = invite?.second?.let { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(stringResource(R.string.comms_invite), onBack = onBack)
        ClairixCard {
            Text(stringResource(R.string.comms_invite_someone_to_clairix), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.comms_an_invite_lets_one_person_get),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            if (invite == null) {
                PrimaryButton(if (busy) stringResource(R.string.comms_making_invite) else stringResource(R.string.comms_make_an_invite), enabled = !busy, loading = busy, onClick = create, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }
        if (invite != null) {
            val link = invite.first.inviteLink()
            ClairixCard {
                Text(stringResource(R.string.comms_next_to_you_let_them_scan), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(stringResource(R.string.comms_in_clairix_on_their_phone_comms), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                if (bitmap != null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.comms_clairix_invite_code), modifier = Modifier.size(260.dp).clip(ClairixShapes.Tag))
                    }
                }
            }
            ClairixCard {
                Text(stringResource(R.string.comms_far_away_send_them_the_link), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.comms_on_a_phone_with_clairix_the, until.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        stringResource(R.string.comms_share_link),
                        onClick = {
                            val text = context.getString(R.string.comms_join_me_on_clairix_for_end, link, until.toString())
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.comms_send_invite)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(stringResource(R.string.comms_copy_link), onClick = { clipboard.setSensitiveText(context.getString(R.string.comms_clairix_invite), link) }, tone = Tone.Neutral)
                    GhostButton(stringResource(R.string.comms_new_invite), onClick = { made = null; create() }, tone = Tone.Neutral, enabled = !busy)
                }
            }
        }
        Notice(Tone.Info, NOT_A_PHONE, title = stringResource(R.string.comms_not_a_phone_number))
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
        title = { Text(if (mine) stringResource(R.string.comms_your_own_invite) else stringResource(R.string.comms_add, code.name.ifBlank { formatClairixNumber(code.number) }), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (mine) stringResource(R.string.comms_this_invite_is_for_someone_who)
                    else stringResource(R.string.comms_this_phone_already_has_a_clairix, formatClairixNumber(code.number)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
            }
        },
        confirmButton = {
            if (!mine) GhostButton(
                stringResource(R.string.comms_add_contact),
                onClick = {
                    scope.launch {
                        CommsRepository.addContactFromCode(code.copy(invite = null), source = CommsRepository.CodeSource.LINK)
                            .onSuccess { onDone(); onAdded(it.number) }
                            .onFailure { error = it.message }
                    }
                }
            )
        },
        dismissButton = { GhostButton(if (mine) "OK" else stringResource(R.string.settings_cancel), onClick = onDone, tone = Tone.Neutral) }
    )
}

@Composable
private fun MyCodeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val code = remember(state.number, state.displayName) { CommsRepository.pairingCode() }
    val bitmap = remember(code) { code?.let { qrBitmap(it.encode(), 720) } }
    val myKey = remember(state.number) { CommsRepository.myEd25519() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(stringResource(R.string.comms_my_code), onBack = onBack)
        ClairixCard {
            Text(state.number?.let { formatClairixNumber(it) } ?: "—", style = MaterialTheme.typography.monoLarge, color = ClairixTheme.colors.accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (state.displayName.isNotBlank()) Text(state.displayName, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(stringResource(if (state.listed) R.string.comms_number_listed else R.string.comms_number_unlisted), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (bitmap != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.comms_your_clairix_pairing_code), modifier = Modifier.size(260.dp).clip(ClairixShapes.Tag))
                }
            }
            Text(
                stringResource(R.string.comms_let_a_contact_scan_this_to),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.number?.let { n -> GhostButton(stringResource(R.string.comms_copy_number), onClick = { clipboard.setSensitiveText(context.getString(R.string.comms_clairix_number), formatClairixNumber(n)) }, tone = Tone.Neutral) }
                code?.let { c -> GhostButton(stringResource(R.string.comms_copy_code_text), onClick = { clipboard.setSensitiveText(context.getString(R.string.comms_clairix_pairing_code), c.encode()) }, tone = Tone.Neutral) }
            }
        }
        Notice(Tone.Info, NOT_A_PHONE, title = stringResource(R.string.comms_not_a_phone_number))
        if (myKey != null) {
            ClairixCard {
                Text(stringResource(R.string.comms_identity_key_2), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(myKey, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
                Text(stringResource(R.string.comms_generated_on_this_phone_the_private), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────

@Composable
private fun CommsSettingsScreen(onBack: () -> Unit, onAppSettings: (() -> Unit)? = null, onMove: () -> Unit, onOpenThread: (String) -> Unit = {}) {
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
            title = { Text(stringResource(R.string.settings_give_up_this_clairix_number), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    stringResource(R.string.comms_the_relay_deletes_your_mailbox_and),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    stringResource(R.string.comms_delete_everything),
                    onClick = { confirmUnpair = false; scope.launch { CommsRepository.unpair() } },
                    tone = Tone.Danger
                )
            },
            dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = { confirmUnpair = false }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(stringResource(R.string.comms_comms_settings), onBack = onBack)
        Text("${state.number?.let { formatClairixNumber(it) } ?: "—"} on ${state.relayUrl ?: "—"}", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)

        ClairixCard {
            Text(stringResource(R.string.comms_delivery), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            ToggleRow(
                title = stringResource(R.string.comms_online),
                checked = state.online,
                onCheckedChange = { CommsRepository.setOnline(it) },
                subtitle = stringResource(R.string.comms_keeps_a_connection_to_the_relay)
            )
            var tor by remember { mutableStateOf(TorTransport.enabled) }
            val torState by TorTransport.state.collectAsStateWithLifecycle()
            // Read now rather than at Tor's next once-a-minute look.
            LaunchedEffect(tor) { if (tor) TorTransport.refreshStatus() }
            ToggleRow(
                title = stringResource(R.string.comms_connect_through_tor),
                checked = tor,
                onCheckedChange = { on -> tor = on; TorTransport.setEnabled(on) },
                subtitle = stringResource(R.string.comms_everything_clairix_says_to_relays_goes)
            )
            if (tor) {
                val (line, tone) = when (val s = torState) {
                    TorTransport.State.Off -> stringResource(R.string.comms_off) to ClairixTheme.colors.textSubtle
                    is TorTransport.State.Starting -> stringResource(R.string.comms_connecting_to_tor, s.percent) to ClairixTheme.colors.textMuted
                    TorTransport.State.Ready -> stringResource(R.string.comms_connected_through_tor) to ClairixTheme.colors.accent
                    TorTransport.State.Asleep -> stringResource(R.string.comms_tor_is_asleep_until_clairix_next) to ClairixTheme.colors.textSubtle
                    is TorTransport.State.Blocked -> stringResource(R.string.comms_tor_is_stuck_at, s.percent, s.reason) to ClairixTheme.colors.danger
                    is TorTransport.State.Failed -> stringResource(R.string.comms_tor_did_not_start, s.reason) to ClairixTheme.colors.danger
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = tone)
            }
            var hidePreviews by remember { mutableStateOf(CommsNotifications.hidePreviews(context)) }
            ToggleRow(
                title = stringResource(R.string.comms_hide_names_and_previews_in_notifications),
                checked = hidePreviews,
                onCheckedChange = { on -> hidePreviews = on; CommsRepository.setHidePreviews(on) },
                subtitle = stringResource(R.string.comms_notifications_say_only_that_a_message)
            )
            var relayOnlyChoice by remember { mutableStateOf(CommsRepository.relayOnlyCallsChoice()) }
            val relayTurn = remember { CommsRepository.relayTurnOffered() }
            ToggleRow(
                title = stringResource(R.string.comms_relay_only_calls),
                // The default is on wherever the relay offers TURN; until a call has
                // asked, it is shown as the default it will be.
                checked = relayOnlyChoice ?: (relayTurn != false),
                onCheckedChange = { on -> relayOnlyChoice = on; CommsRepository.setRelayOnlyCalls(on) },
                subtitle = stringResource(
                    R.string.comms_relay_only_explained,
                    when (relayOnlyChoice) {
                        true -> stringResource(R.string.comms_you_switched_it_on_so_a)
                        false -> stringResource(R.string.comms_you_switched_it_off_calls_connect)
                        null -> when (relayTurn) {
                            true -> stringResource(R.string.comms_on_by_default_because_your_relay)
                            false -> stringResource(R.string.comms_on_by_default_whenever_your_relay)
                            null -> stringResource(R.string.comms_on_by_default_whenever_your_relay_2)
                        }
                    }
                )
            )
            var coverTraffic by remember { mutableStateOf(CommsRepository.coverTraffic()) }
            ToggleRow(
                title = stringResource(R.string.comms_cover_traffic),
                checked = coverTraffic,
                onCheckedChange = { on -> coverTraffic = on; CommsRepository.setCoverTraffic(on) },
                subtitle = stringResource(R.string.comms_while_the_background_connection_is_on)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.comms_push_wake_ups), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                    Text(
                        if (state.pushRegistered) stringResource(R.string.comms_registered_with_your_unifiedpush_distributor_the)
                        else stringResource(R.string.comms_install_a_unifiedpush_distributor_ntfy_from),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                GhostButton(
                    if (state.pushRegistered) stringResource(R.string.comms_renew) else stringResource(R.string.comms_set_up),
                    onClick = {
                        context.findActivity()?.let { CommsRepository.registerPush(it) }
                    }
                )
            }
            if (!notificationsGranted) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.comms_notifications_are_off_so_new_messages), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution, modifier = Modifier.weight(1f))
                    GhostButton(stringResource(R.string.comms_allow), onClick = allowNotifications)
                }
            } else if (!callsChannelOk) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.comms_incoming_call_notifications_are_switched_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.caution,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(stringResource(R.string.comms_fix), onClick = { CommsNotifications.openCallsChannelSettings(context) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (microphoneGranted) stringResource(R.string.comms_microphone_allowed_for_calls)
                    else stringResource(R.string.comms_microphone_not_allowed_calls_cannot_be),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (microphoneGranted) ClairixTheme.colors.textSubtle else ClairixTheme.colors.caution,
                    modifier = Modifier.weight(1f)
                )
                if (!microphoneGranted) GhostButton(stringResource(R.string.comms_allow), onClick = { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) })
            }
            val batteryFree by rememberBatteryUnrestricted()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (batteryFree) stringResource(R.string.comms_battery_unrestricted_android_will_not_cut)
                    else stringResource(R.string.comms_battery_optimised_android_may_cut_the),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryFree) ClairixTheme.colors.textSubtle else ClairixTheme.colors.caution,
                    modifier = Modifier.weight(1f)
                )
                if (!batteryFree) GhostButton(stringResource(R.string.comms_allow), onClick = { openBatterySettings(context) })
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
                        stringResource(R.string.comms_full_screen_calls_off_on_a),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.caution,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(stringResource(R.string.comms_allow), onClick = { CommsNotifications.openFullScreenSettings(context) })
                }
            }
            Text(
                stringResource(
                    R.string.comms_relay_one_time_keys,
                    if (state.relayOneTimeKeys >= 0) state.relayOneTimeKeys.toString() else stringResource(R.string.comms_unknown)
                ),
                style = MaterialTheme.typography.monoSmall,
                color = ClairixTheme.colors.textSubtle
            )
        }

        ClairixCard {
            Text(stringResource(R.string.comms_identity), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.comms_name_shown_to_contacts), style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                    modifier = Modifier.weight(1f), colors = clairixTextFieldColors()
                )
                GhostButton(stringResource(R.string.settings_save), onClick = { scope.launch { CommsRepository.setDisplayName(name) } }, enabled = name.trim() != state.displayName)
            }
            ToggleRow(
                title = stringResource(R.string.comms_listed_number),
                checked = state.listed,
                onCheckedChange = { scope.launch { CommsRepository.setListed(it) } },
                subtitle = if (state.listed) stringResource(R.string.comms_anyone_on_this_relay_who_knows)
                    else stringResource(R.string.comms_unlisted_only_people_who_scanned_your),
                enabled = !state.busy
            )
        }

        RecoveryFileCard()

        BlockedCard(onOpen = onOpenThread)

        RelayCard(busy = state.busy, relayUrl = state.relayUrl, onMove = onMove)

        // The app-wide settings — passcode, biometric unlock, duress code — live on
        // their own screen. In the full app that is reached from the Scan header
        // too; in the comms-only build this row is the only way to it.
        if (onAppSettings != null) {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.comms_app_passcode_and_lock), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            stringResource(R.string.comms_a_passcode_for_the_app_itself),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    GhostButton(stringResource(R.string.comms_open), onClick = onAppSettings)
                }
            }
        }

        ClairixCard {
            Text(stringResource(R.string.comms_about_clairix_numbers), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.caution)
            Text(NOT_A_PHONE, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            Text(
                stringResource(R.string.comms_numbers_are_random_and_handed_out),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }

        state.error?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger, modifier = Modifier.weight(1f))
                GhostButton(stringResource(R.string.comms_dismiss), onClick = { CommsRepository.clearError() }, tone = Tone.Neutral)
            }
        }

        DiagnosticsCard()

        GhostButton(
            stringResource(R.string.settings_delete_identity_and_number),
            onClick = { confirmUnpair = true },
            tone = Tone.Danger,
            enabled = !state.busy
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The blocked list: every number blocked, when, and the way to unblock it.
 * A blocked contact kept as a record opens from here, read-only.
 */
@Composable
private fun BlockedCard(onOpen: (String) -> Unit) {
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val blocked by produceState(initialValue = emptyList<ContactGate.Blocked>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.blockedList() }
    }
    val kept by produceState(initialValue = emptyMap<String, Contact>(), version) {
        value = withContext(Dispatchers.IO) { CommsRepository.blockedKept() }
    }
    var unblocking by remember { mutableStateOf<ContactGate.Blocked?>(null) }
    unblocking?.let { b ->
        UnblockDialog(
            who = b.name.ifBlank { formatClairixNumber(b.number) },
            kept = kept[b.number]?.let { ContactGate.matches(b, it.ed25519, it.curve25519, null, null) } == true,
            onConfirm = { unblocking = null; scope.launch { CommsRepository.unblock(b.id) } },
            onDismiss = { unblocking = null }
        )
    }
    ClairixCard {
        Text(stringResource(R.string.comms_blocked), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        Text(
            if (blocked.isEmpty()) stringResource(R.string.comms_no_one_is_blocked_block_someone)
            else stringResource(R.string.comms_nothing_these_numbers_send_reaches_this),
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle
        )
        for (b in blocked) {
            HorizontalDivider(color = ClairixTheme.colors.border)
            Text(b.name.ifBlank { formatClairixNumber(b.number) }, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                stringResource(
                    R.string.comms_blocked_line,
                    formatClairixNumber(b.number),
                    if (b.relay != null) relayHost(b.relay) else stringResource(R.string.comms_your_relay),
                    dateTimeFmt.format(Date(b.ts))
                ),
                style = MaterialTheme.typography.monoSmall,
                color = ClairixTheme.colors.textSubtle
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val record = kept[b.number]?.takeIf { ContactGate.matches(b, it.ed25519, it.curve25519, null, null) }
                if (record != null) GhostButton(stringResource(R.string.comms_open_conversation), onClick = { onOpen(record.number) }, tone = Tone.Neutral)
                GhostButton(stringResource(R.string.settings_unblock_2), onClick = { unblocking = b }, tone = Tone.Accent)
            }
        }
    }
}

// ── Moving to another relay ──────────────────────────────────────────────────

/** A relay's host, as the owner reads it. */
/**
 * Which relay an invite would put this phone on, and whether that relay is on
 * the signed relay list. An invite link names its relay, and anyone can make
 * one that names a relay of their own: unlisted, the owner is told plainly
 * what its operator would see.
 */
@Composable
private fun InviteRelayNotice(invite: PairingCode, listed: ListedRelay?) {
    if (listed != null) {
        Notice(
            Tone.Info,
            stringResource(R.string.comms_on_the_clairix_relay_list_run, listed.name, listed.operator, listed.host),
            title = relayHost(invite.relayUrl)
        )
    } else {
        Notice(
            Tone.Caution,
            stringResource(R.string.comms_this_invite_is_for_which_is, relayHost(invite.relayUrl)),
            title = stringResource(R.string.comms_a_relay_not_on_clairixs_list)
        )
    }
}

private fun relayHost(url: String?): String =
    url?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it.removePrefix("https://") } ?: "—"

/** The relay this phone is on, what is still collected from the one it left, and the way to move. */
@Composable
private fun RelayCard(busy: Boolean, relayUrl: String?, onMove: () -> Unit) {
    val version by CommsRepository.version.collectAsStateWithLifecycle()
    val previous = remember(version, relayUrl) { CommsRepository.previousRelay() }
    val until = remember(version, relayUrl) { CommsRepository.previousRelayUntil() }
    val owed = remember(version, relayUrl) { CommsRepository.moveNoticesOwed() }
    ClairixCard {
        Text(stringResource(R.string.comms_relay), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        Text(relayHost(relayUrl), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
        Text(
            stringResource(R.string.comms_if_this_relay_is_blocked_where),
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle
        )
        previous?.let { (url, number) ->
            Text(
                stringResource(
                    R.string.comms_still_collecting,
                    formatClairixNumber(number),
                    relayHost(url),
                    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(until))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.textMuted
            )
        }
        if (owed > 0) {
            Text(
                if (owed == 1) stringResource(R.string.comms_1_contact_has_not_been_told) else stringResource(R.string.comms_contacts_have_not_been_told_your, owed),
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.caution
            )
        }
        SecondaryButton(stringResource(R.string.comms_move_to_another_relay), onClick = onMove, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Moves this phone to another relay: one from the relay list, an address and
 * its enrollment secret, or an invite from someone already there; or back to
 * the relay it just left. Says plainly what moves and what does not first.
 */
@Composable
private fun MoveRelayScreen(onBack: () -> Unit) {
    val state by CommsRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var invite by remember { mutableStateOf<PairingCode?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var moved by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val relayList by RelayDirectory.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RelayDirectory.current(context) }
        RelayDirectory.refreshIfStale(context)
    }
    val previous = remember { CommsRepository.previousRelay() }
    val here = state.relayUrl?.let { RelayClient.originOf(it) }
    val takeInvite: (PairingCode?) -> Unit = { code ->
        when {
            code == null -> inviteError = context.getString(R.string.comms_that_is_not_a_clairix_invite)
            code.invite == null -> inviteError = context.getString(R.string.comms_thats_someones_contact_code_not_an)
            RelayClient.originOf(code.relayUrl) == here -> inviteError = context.getString(R.string.comms_that_invite_is_for_the_relay)
            else -> { inviteError = null; invite = code; url = code.relayUrl }
        }
    }
    val scanInvite = rememberCodeScanner { takeInvite(it) }
    val target = (invite?.relayUrl ?: url).trim().trimEnd('/')
    val targetOrigin = RelayClient.originOf(target)
    val goingBack = previous != null && targetOrigin != null && RelayClient.originOf(previous.first) == targetOrigin
    val ready = targetOrigin != null && targetOrigin != here && (invite != null || goingBack || secret.isNotBlank())

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = ClairixTheme.colors.surfaceRaised,
            shape = ClairixShapes.Sheet,
            title = { Text(stringResource(R.string.comms_move_to, relayHost(target)), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    stringResource(R.string.comms_your_keys_contacts_and_conversations_stay, (if (goingBack) stringResource(R.string.comms_you_take_up_your_old_number, formatClairixNumber(previous!!.second))
                        else stringResource(R.string.comms_you_get_a_new_clairix_number)), relayHost(state.relayUrl)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    stringResource(R.string.comms_move),
                    onClick = {
                        confirming = false
                        failure = null
                        scope.launch {
                            CommsRepository.moveTo(target, secret, invite)
                                .onSuccess { moved = it; secret = "" }
                                .onFailure { failure = it.message ?: context.getString(R.string.comms_the_move_failed) }
                        }
                    }
                )
            },
            dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = { confirming = false }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Header(stringResource(R.string.comms_move_to_another_relay), onBack = onBack)

        moved?.let { number ->
            StatusHero(
                eyebrow = stringResource(R.string.comms_moved),
                headline = formatClairixNumber(number),
                body = stringResource(R.string.comms_your_number_on_your_contacts_are, relayHost(state.relayUrl)),
                tone = Tone.Accent
            )
            PrimaryButton(stringResource(R.string.comms_done), onClick = onBack, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        Notice(
            Tone.Info,
            stringResource(R.string.comms_your_keys_contacts_and_conversations_come),
            title = stringResource(R.string.comms_what_moves)
        )

        previous?.let { (prevUrl, prevNumber) ->
            ClairixCard {
                Text(stringResource(R.string.comms_move_back), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.comms_still_has_your_old_number_moving, relayHost(prevUrl), formatClairixNumber(prevNumber)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                SecondaryButton(
                    if (goingBack) stringResource(R.string.comms_selected) else stringResource(R.string.comms_move_back_to, relayHost(prevUrl)),
                    onClick = { invite = null; url = prevUrl },
                    enabled = !goingBack,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        relayList?.relays?.filter { RelayClient.originOf(it.url) != here }?.takeIf { it.isNotEmpty() }?.let { relays ->
            ClairixCard {
                Text(stringResource(R.string.comms_relays_you_can_move_to), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.comms_from_the_clairix_relay_list_clairix),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                for (relay in relays) {
                    HorizontalDivider(color = ClairixTheme.colors.border)
                    Text(relay.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                    Text("${relay.country} · ${relay.host}", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textMuted)
                    Text(stringResource(R.string.comms_run_by, relay.operator), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textMuted)
                    relay.join?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) }
                    SecondaryButton(
                        if (target == relay.url) stringResource(R.string.comms_selected_below) else stringResource(R.string.comms_use_this_relay),
                        onClick = { invite = null; url = relay.url },
                        enabled = target != relay.url,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        ClairixCard {
            Text(stringResource(R.string.comms_with_an_invite), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            invite?.let { code ->
                Text(
                    stringResource(R.string.comms_invited_by_to, code.name.ifBlank { formatClairixNumber(code.number) }, relayHost(code.relayUrl)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.accent
                )
                InviteRelayNotice(code, relayList?.find(RelayClient.originOf(code.relayUrl) ?: code.relayUrl))
                GhostButton(stringResource(R.string.comms_clear_the_invite), onClick = { invite = null; url = "" }, tone = Tone.Neutral)
            } ?: run {
                Text(
                    stringResource(R.string.comms_someone_on_the_relay_you_want),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.comms_scan_invite), onClick = { inviteError = null; scanInvite() }, modifier = Modifier.weight(1f))
                    SecondaryButton(
                        stringResource(R.string.comms_paste_invite),
                        onClick = { takeInvite(clipboard.getText()?.text?.let { PairingCode.fromText(it) }) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            inviteError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        }

        if (invite == null) {
            ClairixCard {
                Text(stringResource(R.string.comms_or_enter_a_relay), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.comms_relay_url), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = clairixTextFieldColors()
                )
                if (!goingBack) {
                    OutlinedTextField(
                        value = secret, onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.comms_enrollment_secret), style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = clairixTextFieldColors()
                    )
                }
                if (targetOrigin != null && targetOrigin == here) {
                    Text(stringResource(R.string.comms_this_phone_is_already_on_that), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution)
                }
            }
        }

        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
        PrimaryButton(
            if (state.busy) stringResource(R.string.comms_moving) else stringResource(R.string.comms_move),
            onClick = { confirming = true },
            enabled = ready && !state.busy,
            loading = state.busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
    }
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
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    DisposableEffect(groupId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (CommsRepository.openPeer == groupId) CommsRepository.openPeer = null
        }
    }
    // On screen means resumed and not behind the lock screen, which is drawn over
    // this one: only then do messages arrive read, go out as read receipts and
    // clear their notification.
    val onScreen = resumed && !locked
    LaunchedEffect(groupId, onScreen) {
        if (onScreen) {
            CommsRepository.openPeer = groupId
            CommsNotifications.cancel(context, groupId)
        } else if (CommsRepository.openPeer == groupId) {
            CommsRepository.openPeer = null
        }
    }
    LaunchedEffect(groupId, version, onScreen) { if (onScreen) CommsRepository.markGroupRead(groupId) }

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
        Header(group?.name ?: stringResource(R.string.comms_group), onBack = onBack) {
            GhostButton(stringResource(R.string.comms_members), onClick = onMembers, tone = Tone.Neutral)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            pluralStringResource(R.plurals.comms_group_members, members.size, members.size),
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle
        )
        DisappearingRow(groupId, version, enabled = group != null)
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (messages.isEmpty()) {
                item {
                    ClairixCard {
                        Text(stringResource(R.string.comms_no_messages_yet), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                        Text(
                            stringResource(R.string.comms_messages_are_encrypted_for_every_member),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                if (msg.status == STATUS_SYSTEM) {
                    // A line this phone wrote itself, such as a timer change: not a member's message.
                    Text(
                        msg.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.textSubtle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    return@items
                }
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
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.comms_not_sent), tint = ClairixTheme.colors.danger, modifier = Modifier.size(12.dp))
                                } else if (isOut && msg.status == "queued") {
                                    // Still owed to some members: the outbox is trying them.
                                    Spacer(Modifier.width(4.dp))
                                    Text("…", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                                }
                            }
                        }
                    }
                    // How far it got when not every member has it: "Reached 3 of 4 members; trying 1 more".
                    if (isOut && msg.error != null) {
                        Text(
                            msg.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.status == "failed") ClairixTheme.colors.danger else ClairixTheme.colors.textSubtle,
                            modifier = Modifier.widthIn(max = 280.dp).padding(top = 2.dp, end = 4.dp)
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        sendError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (topThreat != null) {
            // A recognised tracker following you is a tracker; anything else that moved
            // with you only travelled with you, and is shared as that.
            val isTracker = topThreat.threat == Threat.CRITICAL
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().background(ClairixTheme.colors.danger.copy(alpha = 0.10f), ClairixShapes.Card).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${if (isTracker) "Active tracker" else "Travelling with you"}: ${topThreat.tracker?.label ?: topThreat.name}",
                        style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.danger
                    )
                    Text(stringResource(R.string.comms_tap_to_share_with_the_group), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                }
                GhostButton(
                    stringResource(R.string.comms_share_threat),
                    onClick = {
                        scope.launch {
                            CommsRepository.shareThreats(
                                gid = groupId,
                                kind = "FOLLOWING",
                                severity = topThreat.threat.name,
                                lat = status.lat,
                                lon = status.lon,
                                title = topThreat.tracker?.label ?: topThreat.name,
                                detail = context.getString(if (isTracker) R.string.comms_alert_tracker else R.string.comms_alert_unidentified)
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
                placeholder = { Text(stringResource(R.string.comms_message), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle) },
                maxLines = 4,
                colors = clairixTextFieldColors()
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                stringResource(R.string.comms_send),
                onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@PrimaryButton
                        val toSend = text
                        sendError = null
                        draft = ""
                        scope.launch {
                            CommsRepository.sendGroupMessage(groupId, toSend)
                                .onFailure { sendError = it.message ?: context.getString(R.string.comms_could_not_send) }
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
            title = { Text(stringResource(R.string.comms_leave_group), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    stringResource(R.string.comms_leave_group_explained, group?.name ?: stringResource(R.string.comms_this_group)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            },
            confirmButton = {
                GhostButton(
                    stringResource(R.string.comms_leave),
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
            dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = { leaveConfirm = false }, tone = Tone.Neutral) }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        Header(group?.name ?: stringResource(R.string.comms_group), onBack = onBack) {
            if (myRole == OrgRole.ADMIN) GhostButton(stringResource(R.string.comms_add_2), onClick = { addMember = true }, tone = Tone.Safe)
        }
        Spacer(Modifier.height(4.dp))
        Text("${members.size} member${if (members.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
        Spacer(Modifier.height(12.dp))

        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger, modifier = Modifier.padding(bottom = 8.dp))
        }

        SectionHeader(stringResource(R.string.comms_members))
        Spacer(Modifier.height(6.dp))
        ClairixCard {
            if (members.isEmpty()) {
                Text(stringResource(R.string.comms_no_members_yet), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
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
                                    stringResource(R.string.settings_remove),
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
        SectionHeader(stringResource(R.string.comms_actions))
        Spacer(Modifier.height(6.dp))
        SecondaryButton(stringResource(R.string.comms_leave_group_2), onClick = { leaveConfirm = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AddGroupMemberDialog(groupId: String, existing: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
        title = { Text(stringResource(R.string.comms_add_member), style = MaterialTheme.typography.titleLarge, color = ClairixTheme.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available.isEmpty()) {
                    Text(
                        if (contacts.isEmpty()) stringResource(R.string.comms_add_contacts_first_then_add_them)
                        else stringResource(R.string.comms_all_your_contacts_are_already_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                } else {
                    Text(stringResource(R.string.comms_choose_a_contact_to_add), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
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
                                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.comms_selected), tint = ClairixTheme.colors.accent, modifier = Modifier.size(18.dp))
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
                if (busy) stringResource(R.string.comms_adding) else stringResource(R.string.comms_add_3),
                onClick = {
                        val c = selected ?: return@GhostButton
                        busy = true
                        error = null
                        scope.launch {
                            CommsRepository.addGroupMember(groupId, c.number)
                                .onSuccess { onDismiss() }
                                .onFailure { busy = false; error = it.message ?: context.getString(R.string.comms_could_not_add_member) }
                        }
                    },
                tone = if (selected != null && !busy) Tone.Accent else Tone.Neutral,
                enabled = selected != null && !busy
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = { if (!busy) onDismiss() }, tone = Tone.Neutral, enabled = !busy) }
    )
}

private val logTimeFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

/**
 * The comms event log: connections, envelopes that could not be read, calls
 * that arrived too late to ring. COPY puts it on the clipboard to send to
 * whoever is helping; it holds no message text.
 */
@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val entries by CommsLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    ClairixCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.comms_diagnostics), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
            GhostButton(
                stringResource(R.string.settings_copy),
                onClick = {
                    val text = entries.joinToString("\n") { "${logTimeFmt.format(Date(it.ts))}  ${it.text}" }
                    clipboard.setSensitiveText(context.getString(R.string.comms_clairix_comms_log), context.getString(R.string.comms_clairix_comms_log_2, text))
                },
                enabled = entries.isNotEmpty()
            )
            GhostButton(stringResource(R.string.comms_clear), onClick = { CommsLog.clear() }, tone = Tone.Neutral, enabled = entries.isNotEmpty())
        }
        Text(
            stringResource(R.string.comms_what_happened_with_the_relay_calls),
            style = MaterialTheme.typography.bodySmall,
            color = ClairixTheme.colors.textSubtle
        )
        if (entries.isEmpty()) {
            Text(stringResource(R.string.comms_nothing_recorded_yet), style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
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
                GhostButton(if (expanded) stringResource(R.string.comms_show_less) else stringResource(R.string.comms_show_all, entries.size), onClick = { expanded = !expanded }, tone = Tone.Neutral)
            }
        }
    }
}
