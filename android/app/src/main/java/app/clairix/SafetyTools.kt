package app.clairix

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.clairix.comms.CommsNotifications
import app.clairix.comms.CommsRepository
import app.clairix.comms.Contact
import app.clairix.comms.formatClairixNumber
import app.clairix.security.DuressPhrase
import app.clairix.security.FakeCall
import app.clairix.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.mono
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.SafetyFirst
import app.clairix.ui.SafetyFirstCard
import app.clairix.security.Wiper

// The personal-safety pages reached from the Comms tab: fake call and the
// duress phrase.

// ── 6. Fake call ───────────────────────────────────────────────────────────────

@Composable
internal fun FakeCallPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val initial = remember { FakeCall.caller(context) }
    var name by remember { mutableStateOf(initial.name) }
    var number by remember { mutableStateOf(initial.number ?: "") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var connectTo by remember { mutableStateOf<Contact?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var lockMenuOpen by remember { mutableStateOf(false) }
    // What the ring needs, checked the way Comms checks them for real calls:
    // without notifications the call shows nowhere, and without full-screen
    // calls a locked phone shows only a notification, so nothing is scheduled
    // until both pass.
    var readiness by remember { mutableStateOf(FakeCall.readiness(context)) }
    var canLock by remember { mutableStateOf(FakeCall.canLock(context)) }
    var adminActive by remember { mutableStateOf(UnlockLedger.isAdminActive(context)) }
    var pendingAt by remember { mutableStateOf(FakeCall.pendingAt(context)) }
    val now = rememberToolTick()
    // Once refused twice, Android answers the request with "denied" without
    // showing anything; the owner is then sent to the settings page instead.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        readiness = FakeCall.readiness(context)
        val blocked = context.findActivity()
            ?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && blocked) {
            CommsNotifications.openAppNotificationSettings(context)
        }
    }
    val allowNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Before API 33 there is no runtime permission; the switch lives in system settings.
            CommsNotifications.openAppNotificationSettings(context)
        }
    }

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { runCatching { CommsRepository.contacts() }.getOrDefault(emptyList()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Every grant happens on Android's own pages, so they are re-read on return.
        readiness = FakeCall.readiness(context)
        canLock = FakeCall.canLock(context)
        adminActive = UnlockLedger.isAdminActive(context)
        pendingAt = FakeCall.pendingAt(context)
    }
    // A pending call that has rung drops off the countdown.
    LaunchedEffect(now) {
        val at = pendingAt
        if (at != null && at <= now) pendingAt = null
    }

    fun schedule(delayMs: Long) {
        FakeCall.setCaller(context, name.ifBlank { "Mum" }, number.ifBlank { null })
        FakeCall.schedule(context, delayMs, connectTo?.number)
        pendingAt = FakeCall.pendingAt(context)
    }

    // Schedule, hide Clairix, lock the phone: the call then comes over the lock
    // screen like any other, with no Clairix window behind it to be found.
    fun scheduleAndLock(delayMs: Long) {
        schedule(delayMs)
        context.findActivity()?.moveTaskToBack(true)
        FakeCall.lockNow(context)
    }

    ToolPageList(title = stringResource(R.string.safety_fake_call), onBack = onBack) {
        item(key = "§intro") {
            Text(
                stringResource(R.string.safety_a_way_out_of_a_conversation),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }

        val pending = pendingAt
        if (pending != null) {
            item(key = "§pending") {
                val secs = ((pending - now) / 1000L).coerceAtLeast(0L)
                ClairixCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Rings in ${secs / 60}:${"%02d".format(Locale.US, secs % 60)}", style = MaterialTheme.typography.mono, color = ClairixTheme.colors.text)
                            Text(
                                stringResource(R.string.safety_lock_the_phone_and_put_it, (connectTo?.let { stringResource(R.string.safety_answering_calls_for_real, it.name) } ?: "")),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClairixTheme.colors.textSubtle
                            )
                        }
                        StatusPill(stringResource(R.string.safety_scheduled), Tone.Accent)
                    }
                    SecondaryButton(stringResource(R.string.settings_cancel), onClick = { FakeCall.cancel(context); pendingAt = null }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "§caller-hdr") { SectionHeader(stringResource(R.string.safety_caller)) }
        item(key = "§caller") {
            ClairixCard {
                ClairixTextField(value = name, onValueChange = { name = it.take(40) }, label = stringResource(R.string.safety_name_shown), placeholder = stringResource(R.string.safety_mum), modifier = Modifier.fillMaxWidth())
                ClairixTextField(value = number, onValueChange = { number = it.take(24) }, label = stringResource(R.string.safety_number_shown_optional), keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.safety_saved_when_a_call_is_scheduled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }

        item(key = "§connect-hdr") { SectionHeader(stringResource(R.string.safety_on_answer)) }
        item(key = "§connect") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.safety_connect_to_a_real_contact), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            stringResource(R.string.safety_answering_places_an_encrypted_clairix_call),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box {
                        TextButton(
                            onClick = { pickerOpen = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = ClairixTheme.colors.accent),
                            shape = ClairixShapes.Card
                        ) {
                            Text(connectTo?.name ?: stringResource(R.string.safety_nobody), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }, containerColor = ClairixTheme.colors.surfaceRaised, shape = ClairixShapes.Card) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.safety_nobody), style = if (connectTo == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge) },
                                onClick = { pickerOpen = false; connectTo = null }
                            )
                            for (c in contacts) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(c.name, style = if (connectTo?.number == c.number) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
                                            Text(formatClairixNumber(c.number), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                                        }
                                    },
                                    onClick = { pickerOpen = false; connectTo = c }
                                )
                            }
                        }
                    }
                }
                if (contacts.isEmpty()) {
                    Text(stringResource(R.string.safety_no_clairix_contacts_yet_add_one), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                }
            }
        }

        item(key = "§when-hdr") { SectionHeader(stringResource(R.string.safety_ring_in)) }
        item(key = "§when") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("30 s", onClick = { schedule(FakeCall.DELAY_30S) }, enabled = readiness.canSchedule, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.safety_2_min), onClick = { schedule(FakeCall.DELAY_2MIN) }, enabled = readiness.canSchedule, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.safety_5_min), onClick = { schedule(FakeCall.DELAY_5MIN) }, enabled = readiness.canSchedule, modifier = Modifier.weight(1f))
                }
                if (FakeCall.Missing.NOTIFICATIONS in readiness.missing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.safety_notifications_are_off_for_clairix_so),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.caution,
                            modifier = Modifier.weight(1f)
                        )
                        GhostButton(stringResource(R.string.comms_allow), onClick = allowNotifications)
                    }
                } else if (FakeCall.Missing.CALL_CHANNEL in readiness.missing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.safety_clairixs_call_notifications_are_switched_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.caution,
                            modifier = Modifier.weight(1f)
                        )
                        GhostButton(stringResource(R.string.comms_fix), onClick = { FakeCall.openChannelSettings(context) })
                    }
                }
                if (FakeCall.Missing.FULL_SCREEN in readiness.missing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.safety_full_screen_calls_off_on_a),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.caution,
                            modifier = Modifier.weight(1f)
                        )
                        GhostButton(stringResource(R.string.comms_allow), onClick = { CommsNotifications.openFullScreenSettings(context) })
                    }
                }
                if (!readiness.ringsOnTime) {
                    Text(
                        stringResource(R.string.safety_exact_alarms_are_not_allowed_for),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.caution
                    )
                    SecondaryButton(stringResource(R.string.safety_allow_exact_alarms), onClick = { FakeCall.requestExactAlarms(context) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                } else if (readiness.missing.isEmpty()) {
                    Text(stringResource(R.string.safety_ready_the_call_rings_on_time), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                }
            }
        }

        item(key = "§lock-hdr") { SectionHeader(stringResource(R.string.safety_put_the_phone_away)) }
        // The comms-only build declares no device admin (the unlock ledger's is the
        // full app's), so there is nothing to lock the phone with, nor to enable.
        if (!BuildConfig.FULL_ACCESS) item(key = "§lock") {
            ClairixCard {
                Text(
                    stringResource(R.string.safety_schedule_the_call_then_lock_the),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
        if (BuildConfig.FULL_ACCESS) item(key = "§lock") {
            ClairixCard {
                Box(Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        stringResource(R.string.safety_schedule_and_lock),
                        onClick = { lockMenuOpen = true },
                        tone = Tone.Accent,
                        enabled = readiness.canSchedule && canLock,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = lockMenuOpen, onDismissRequest = { lockMenuOpen = false }, containerColor = ClairixTheme.colors.surfaceRaised, shape = ClairixShapes.Card) {
                        for ((label, delayMs) in listOf(stringResource(R.string.safety_in_30_s) to FakeCall.DELAY_30S, stringResource(R.string.safety_in_2_min) to FakeCall.DELAY_2MIN, stringResource(R.string.safety_in_5_min) to FakeCall.DELAY_5MIN)) {
                            DropdownMenuItem(
                                text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                                onClick = { lockMenuOpen = false; scheduleAndLock(delayMs) }
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.safety_schedules_the_call_hides_clairix_and),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                if (!canLock) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (adminActive) {
                                stringResource(R.string.safety_clairixs_device_admin_was_approved_before)
                            } else {
                                stringResource(R.string.safety_locking_needs_clairixs_device_admin_the)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ClairixTheme.colors.caution,
                            modifier = Modifier.weight(1f)
                        )
                        GhostButton(if (adminActive) stringResource(R.string.safety_approve) else stringResource(R.string.safety_enable), onClick = { openExternal(context, UnlockLedger.requestAdminIntent(context)) })
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ── 7. Duress phrase ───────────────────────────────────────────────────────────

@Composable
internal fun DuressPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(DuressPhrase.isConfigured(context)) }
    var phrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    // A phrase saved before the length rule is never matched; say so instead of
    // letting the owner believe it still works.
    var tooShortStored by remember { mutableStateOf(false) }
    LaunchedEffect(configured) {
        tooShortStored = configured &&
            withContext(Dispatchers.IO) { runCatching { DuressPhrase.needsReplacing(context) }.getOrDefault(false) }
    }
    // The SOS goes to the owner's groups; in none, the phrase sends nothing at all.
    var groupCount by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        groupCount = withContext(Dispatchers.IO) { runCatching { CommsRepository.groups().size }.getOrDefault(0) }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            shape = ClairixShapes.Sheet,
            containerColor = ClairixTheme.colors.surfaceRaised,
            title = { Text(stringResource(R.string.safety_remove_the_duress_phrase), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    stringResource(R.string.safety_typing_the_phrase_into_a_conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    stringResource(R.string.settings_remove),
                    onClick = {
                        showClear = false
                        DuressPhrase.clear(context)
                        configured = false
                        message = context.getString(R.string.safety_duress_phrase_removed)
                        messageIsError = false
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = {
                GhostButton(stringResource(R.string.settings_cancel), onClick = { showClear = false }, tone = Tone.Neutral)
            }
        )
    }

    ToolPageList(title = stringResource(R.string.safety_duress_phrase), onBack = onBack) {
        item(key = "§status") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            if (configured) stringResource(R.string.safety_duress_phrase_is_set) else stringResource(R.string.safety_no_duress_phrase),
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text
                        )
                        Text(
                            stringResource(R.string.safety_an_ordinary_looking_sentence_you_can),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (configured) stringResource(R.string.safety_configured) else stringResource(R.string.settings_not_set), if (configured) Tone.Safe else Tone.Neutral)
                }
                if (tooShortStored) {
                    Text(
                        stringResource(R.string.safety_the_saved_phrase_is_shorter_than),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.danger
                    )
                }
                if (groupCount == 0) {
                    Text(
                        stringResource(R.string.safety_you_are_in_no_group_so),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.danger
                    )
                }
            }
        }

        item(key = "§set-hdr") { SectionHeader(if (configured) stringResource(R.string.safety_replace_phrase) else stringResource(R.string.safety_set_phrase)) }
        item(key = "§set") {
            ClairixCard {
                ClairixTextField(
                    value = phrase,
                    onValueChange = { phrase = it.take(120); message = null },
                    label = stringResource(R.string.safety_new_phrase),
                    placeholder = stringResource(R.string.safety_e_g_did_you_remember_to),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.safety_pick_something_you_would_plausibly_say, DuressPhrase.MIN_WORDS, DuressPhrase.MIN_CHARS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (messageIsError) ClairixTheme.colors.danger else ClairixTheme.colors.safe) }
                PrimaryButton(
                    if (configured) stringResource(R.string.safety_replace_phrase) else stringResource(R.string.safety_save_phrase),
                    onClick = {
                            val value = phrase
                            val problem = DuressPhrase.validate(value)
                            if (problem != null) {
                                message = problem
                                messageIsError = true
                                return@PrimaryButton
                            }
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { DuressPhrase.set(context, value) } }
                                r.onSuccess {
                                    phrase = ""
                                    configured = DuressPhrase.isConfigured(context)
                                    tooShortStored = false
                                    message = if (configured) context.getString(R.string.safety_saved) else context.getString(R.string.safety_a_blank_phrase_clears_the_setting)
                                    messageIsError = false
                                }.onFailure {
                                    message = if (it is IllegalArgumentException) it.message ?: DuressPhrase.TOO_SHORT_MESSAGE
                                        else "Could not save: ${it.message ?: it.javaClass.simpleName}"
                                    messageIsError = true
                                }
                            }
                        },
                    enabled = phrase.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (configured) {
                    SecondaryButton(stringResource(R.string.safety_clear_phrase), onClick = { showClear = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "§how") {
            ClairixCard(spacing = 6.dp) {
                Text(stringResource(R.string.shell_how_it_works), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.safety_type_the_phrase_as_a_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
    }
}

// ── Wipe ───────────────────────────────────────────────────────────────────────

/**
 * The wipe on demand, for when there is time to open the app: everything
 * Clairix holds is erased and the relay asked to delete the account. Reached
 * from the Comms menu and from Settings, in both editions. The wrong-passcode
 * limit runs the same wipe, and so does the duress code, which leaves a decoy
 * instead of closing ([Wiper]). Typing the word is the only safeguard; there
 * is no undo.
 */
@Composable
internal fun WipePage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val ready = typed.trim().equals(WIPE_WORD, ignoreCase = false)

    ToolPageList(title = stringResource(R.string.settings_wipe_clairix), onBack = onBack) {
        // A wipe takes the evidence with it, and a phone that changes can tell whoever
        // watches it: the safety card comes before anything else on this page.
        item(key = "§safety-first") { SafetyFirstCard(title = SafetyFirst.TITLE_WIPE) }
        item(key = "§what") {
            ClairixCard(spacing = 6.dp) {
                Text(stringResource(R.string.safety_what_is_erased), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.safety_every_conversation_contact_group_and_call),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    stringResource(R.string.safety_in_this_order_every_key_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    stringResource(R.string.safety_if_the_relay_cannot_be_reached),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    stringResource(R.string.safety_android_keeps_what_is_its_own),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    stringResource(R.string.safety_the_wrong_passcode_limit_runs_the),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
        item(key = "§do-hdr") { SectionHeader(stringResource(R.string.safety_wipe_now)) }
        item(key = "§do") {
            ClairixCard {
                ClairixTextField(
                    value = typed,
                    onValueChange = { typed = it.take(8) },
                    label = stringResource(R.string.safety_type_to_confirm, WIPE_WORD),
                    placeholder = WIPE_WORD,
                    modifier = Modifier.fillMaxWidth()
                )
                PrimaryButton(
                    if (started) stringResource(R.string.safety_wiping) else stringResource(R.string.safety_wipe_everything_now),
                    onClick = {
                        if (!ready || started) return@PrimaryButton
                        started = true
                        Wiper.start(context)
                    },
                    enabled = ready && !started,
                    tone = Tone.Danger,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private const val WIPE_WORD = "WIPE"
