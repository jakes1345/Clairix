package app.clairix

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.clairix.comms.CommsRepository
import app.clairix.comms.Contact
import app.clairix.comms.formatClairixNumber
import app.clairix.security.DuressPhrase
import app.clairix.security.FakeCall
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
    var canExact by remember { mutableStateOf(FakeCall.canScheduleExactly(context)) }
    var pendingAt by remember { mutableStateOf(FakeCall.pendingAt(context)) }
    val now = rememberToolTick()

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { runCatching { CommsRepository.contacts() }.getOrDefault(emptyList()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canExact = FakeCall.canScheduleExactly(context)
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

    ToolPageList(title = "Fake call", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "A way out of a conversation or a situation that is going wrong. The phone rings with " +
                "its own ringtone and shows an incoming call from whoever you name here.",
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
                                "Put the phone away; it will ring on its own." +
                                    (connectTo?.let { " Answering calls ${it.name} for real." } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClairixTheme.colors.textSubtle
                            )
                        }
                        StatusPill("Scheduled", Tone.Accent)
                    }
                    SecondaryButton("Cancel", onClick = { FakeCall.cancel(context); pendingAt = null }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "§caller-hdr") { SectionHeader("Caller") }
        item(key = "§caller") {
            ClairixCard {
                ClairixTextField(value = name, onValueChange = { name = it.take(40) }, label = "Name shown", placeholder = "Mum", modifier = Modifier.fillMaxWidth())
                ClairixTextField(value = number, onValueChange = { number = it.take(24) }, label = "Number shown (optional)", keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
                Text(
                    "Saved when a call is scheduled. The name and number are only what appears on the " +
                    "screen; no real call is made unless you pick a contact below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }

        item(key = "§connect-hdr") { SectionHeader("On answer") }
        item(key = "§connect") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect to a real contact", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "Answering places an encrypted Clairix call to this contact instead of a pretend " +
                            "one. Leave empty to talk to nobody.",
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
                            Text(connectTo?.name ?: "Nobody", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }, containerColor = ClairixTheme.colors.surfaceRaised, shape = ClairixShapes.Card) {
                            DropdownMenuItem(
                                text = { Text("Nobody", style = if (connectTo == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge) },
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
                    Text("No Clairix contacts yet — add one on the Comms tab.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                }
            }
        }

        item(key = "§when-hdr") { SectionHeader("Ring in") }
        item(key = "§when") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("30 s", onClick = { schedule(FakeCall.DELAY_30S) }, modifier = Modifier.weight(1f))
                    PrimaryButton("2 min", onClick = { schedule(FakeCall.DELAY_2MIN) }, modifier = Modifier.weight(1f))
                    PrimaryButton("5 min", onClick = { schedule(FakeCall.DELAY_5MIN) }, modifier = Modifier.weight(1f))
                }
                if (!canExact) {
                    Text(
                        "Exact alarms are not allowed for Clairix, so Android may ring a little late while " +
                        "the phone is idle. Allow them for the call to land on the second.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.caution
                    )
                    SecondaryButton("Allow exact alarms", onClick = { FakeCall.requestExactAlarms(context) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Exact alarms allowed — the call rings on time even with the screen off.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                }
            }
        }
    }
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
            title = { Text("Remove the duress phrase?", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    "Typing the phrase into a conversation will no longer send an SOS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    "Remove",
                    onClick = {
                        showClear = false
                        DuressPhrase.clear(context)
                        configured = false
                        message = "Duress phrase removed"
                        messageIsError = false
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = {
                GhostButton("Cancel", onClick = { showClear = false }, tone = Tone.Neutral)
            }
        )
    }

    ToolPageList(title = "Duress phrase", onBack = onBack) {
        item(key = "§status") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            if (configured) "Duress phrase is set" else "No duress phrase",
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text
                        )
                        Text(
                            "An ordinary-looking sentence you can type into any Clairix conversation. The " +
                            "message goes out as written, so anyone watching the screen sees a normal chat; " +
                            "underneath, the SOS fires to your groups with your last known position.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (configured) "Configured" else "Not set", if (configured) Tone.Safe else Tone.Neutral)
                }
                if (tooShortStored) {
                    Text(
                        "The saved phrase is shorter than the current minimum and will not send an SOS. " +
                        "Replace it below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.danger
                    )
                }
                if (groupCount == 0) {
                    Text(
                        "You are in no group, so the phrase would send nothing. The SOS goes to your groups: " +
                        "make one in COMMS, or accept an invitation to one, before relying on this.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.danger
                    )
                }
            }
        }

        item(key = "§set-hdr") { SectionHeader(if (configured) "Replace phrase" else "Set phrase") }
        item(key = "§set") {
            ClairixCard {
                ClairixTextField(
                    value = phrase,
                    onValueChange = { phrase = it.take(120); message = null },
                    label = "New phrase",
                    placeholder = "e.g. did you remember to feed the cat",
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Pick something you would plausibly say but never would by accident — at least " +
                    "${DuressPhrase.MIN_WORDS} words and ${DuressPhrase.MIN_CHARS} letters, not counting spaces. " +
                    "Matching ignores " +
                    "case and extra spaces. The phrase is stored encrypted and is never shown again — " +
                    "not here, not in the timeline, not in a notification.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (messageIsError) ClairixTheme.colors.danger else ClairixTheme.colors.safe) }
                PrimaryButton(
                    if (configured) "Replace phrase" else "Save phrase",
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
                                    message = if (configured) "Saved" else "A blank phrase clears the setting"
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
                    SecondaryButton("Clear phrase", onClick = { showClear = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "§how") {
            ClairixCard(spacing = 6.dp) {
                Text("How it works", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "• Type the phrase as a message to any contact or group and send it as usual.\n" +
                    "• The message is delivered normally, so the conversation looks unremarkable.\n" +
                    "• At the same time an SOS goes to all your groups with your last known location, " +
                    "and the timeline records only that an SOS was triggered — never the phrase itself.\n" +
                    "• Nothing on screen changes. Carry on as if nothing happened.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
    }
}

// ── Wipe ───────────────────────────────────────────────────────────────────────

/**
 * The one-tap wipe the lock screen's duress code also triggers, for when there
 * is time to open the app: everything Clairix holds is erased and the relay
 * account deleted. Typing the word is the only safeguard; there is no undo.
 */
@Composable
internal fun WipePage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val ready = typed.trim().equals(WIPE_WORD, ignoreCase = false)

    ToolPageList(title = "Wipe Clairix", onBack = onBack) {
        item(key = "§what") {
            ClairixCard(spacing = 6.dp) {
                Text("What is erased", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "• Every conversation, contact, group and call record, and the keys behind them\n" +
                    "• Your Clairix number: the relay is asked to delete the account, given a few seconds\n" +
                    "• The scanner's timeline, location history, cell-tower baseline and trips\n" +
                    "• The card vault, the passcode, the duress phrase and every setting\n" +
                    "• Downloaded maps and map tiles, voicemails, attachments and the comms log\n" +
                    "• Every notification, the clipboard, the push registration and the device-admin role, " +
                    "so nothing in the shade or in Settings says what was here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    "Clairix then restarts as a fresh install. There is no undo and no backup to restore " +
                    "from. The same wipe runs when the duress passcode is entered at the lock screen, " +
                    "or after too many wrong passcodes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
        item(key = "§do-hdr") { SectionHeader("Wipe now") }
        item(key = "§do") {
            ClairixCard {
                ClairixTextField(
                    value = typed,
                    onValueChange = { typed = it.take(8) },
                    label = "Type $WIPE_WORD to confirm",
                    placeholder = WIPE_WORD,
                    modifier = Modifier.fillMaxWidth()
                )
                PrimaryButton(
                    if (started) "Wiping…" else "Wipe everything now",
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
