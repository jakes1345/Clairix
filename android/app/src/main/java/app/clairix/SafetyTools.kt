package app.clairix

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CaAuditScanner
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.PatternOfLife
import app.clairix.analysis.PatternReport
import app.clairix.analysis.StalkwareScanner
import app.clairix.analysis.SuspiciousCa
import app.clairix.analysis.TlsCanary
import app.clairix.analysis.TlsCanaryResult
import app.clairix.analysis.clock
import app.clairix.comms.CommsRepository
import app.clairix.comms.Contact
import app.clairix.comms.formatClairixNumber
import app.clairix.detect.BleCanary
import app.clairix.detect.BleCanaryState
import app.clairix.detect.DultInterrogator
import app.clairix.detect.DultResult
import app.clairix.detect.LocatorState
import app.clairix.detect.RssiLocator
import app.clairix.detect.RssiLocatorView
import app.clairix.security.BiometricTripwire
import app.clairix.security.BiometricTripwireResult
import app.clairix.security.DuressPhrase
import app.clairix.security.FakeCall
import app.clairix.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    ToolPageList(title = "FAKE CALL", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "A way out of a conversation or a situation that is going wrong. The phone rings with " +
                "its own ringtone and shows an incoming call from whoever you name here.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }

        val pending = pendingAt
        if (pending != null) {
            item(key = "§pending") {
                val secs = ((pending - now) / 1000L).coerceAtLeast(0L)
                TCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Rings in ${secs / 60}:${"%02d".format(Locale.US, secs % 60)}", color = TInkClr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(
                                "Put the phone away; it will ring on its own." +
                                    (connectTo?.let { " Answering calls ${it.name} for real." } ?: ""),
                                color = TMutedClr, fontSize = 12.sp
                            )
                        }
                        TBadge("SCHEDULED", TAccentClr)
                    }
                    TButton("CANCEL", onClick = { FakeCall.cancel(context); pendingAt = null }, style = TButtonStyle.CRITICAL)
                }
            }
        }

        item(key = "§caller-hdr") { TSectionLabel("CALLER") }
        item(key = "§caller") {
            TCard {
                TField(value = name, onValueChange = { name = it.take(40) }, label = "Name shown", placeholder = "Mum")
                TField(value = number, onValueChange = { number = it.take(24) }, label = "Number shown (optional)", keyboardType = KeyboardType.Phone)
                Text(
                    "Saved when a call is scheduled. The name and number are only what appears on the " +
                    "screen; no real call is made unless you pick a contact below.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }

        item(key = "§connect-hdr") { TSectionLabel("ON ANSWER") }
        item(key = "§connect") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect to a real contact", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Answering places an encrypted Clairix call to this contact instead of a pretend " +
                            "one. Leave empty to talk to nobody.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box {
                        TextButton(
                            onClick = { pickerOpen = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = TAccentClr),
                            shape = TCardShape
                        ) {
                            Text(connectTo?.name ?: "Nobody", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Nobody", fontWeight = if (connectTo == null) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { pickerOpen = false; connectTo = null }
                            )
                            for (c in contacts) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(c.name, fontWeight = if (connectTo?.number == c.number) FontWeight.Bold else FontWeight.Normal)
                                            Text(formatClairixNumber(c.number), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TMutedClr)
                                        }
                                    },
                                    onClick = { pickerOpen = false; connectTo = c }
                                )
                            }
                        }
                    }
                }
                if (contacts.isEmpty()) {
                    Text("No Clairix contacts yet — add one on the Comms tab.", color = TMutedClr, fontSize = 12.sp)
                }
            }
        }

        item(key = "§when-hdr") { TSectionLabel("RING IN") }
        item(key = "§when") {
            TCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TButton("30 S", onClick = { schedule(FakeCall.DELAY_30S) }, style = TButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                    TButton("2 MIN", onClick = { schedule(FakeCall.DELAY_2MIN) }, style = TButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                    TButton("5 MIN", onClick = { schedule(FakeCall.DELAY_5MIN) }, style = TButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                }
                if (!canExact) {
                    Text(
                        "Exact alarms are not allowed for Clairix, so Android may ring a little late while " +
                        "the phone is idle. Allow them for the call to land on the second.",
                        color = TCautionClr, fontSize = 12.sp, lineHeight = 16.sp
                    )
                    TButton("ALLOW EXACT ALARMS", onClick = { FakeCall.requestExactAlarms(context) })
                } else {
                    Text("Exact alarms allowed — the call rings on time even with the screen off.", color = TMutedClr, fontSize = 12.sp)
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

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            containerColor = TPanelClr,
            title = { Text("Remove the duress phrase?", color = TInkClr, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Typing the phrase into a conversation will no longer send an SOS.",
                    color = TInkDimClr, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    DuressPhrase.clear(context)
                    configured = false
                    message = "Duress phrase removed"
                    messageIsError = false
                }) {
                    Text("REMOVE", color = TCriticalClr, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text("CANCEL", color = TMutedClr, letterSpacing = 1.sp)
                }
            }
        )
    }

    ToolPageList(title = "DURESS PHRASE", onBack = onBack) {
        item(key = "§status") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            if (configured) "Duress phrase is set" else "No duress phrase",
                            color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "An ordinary-looking sentence you can type into any Clairix conversation. The " +
                            "message goes out as written, so anyone watching the screen sees a normal chat; " +
                            "underneath, the SOS fires to your groups with your last known position.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    TBadge(if (configured) "CONFIGURED" else "NOT SET", if (configured) TClearClr else TMutedClr)
                }
            }
        }

        item(key = "§set-hdr") { TSectionLabel(if (configured) "REPLACE PHRASE" else "SET PHRASE") }
        item(key = "§set") {
            TCard {
                TField(
                    value = phrase,
                    onValueChange = { phrase = it.take(120); message = null },
                    label = "New phrase",
                    placeholder = "e.g. did you remember to feed the cat"
                )
                Text(
                    "Pick something you would plausibly say but never would by accident. Matching ignores " +
                    "case and extra spaces. The phrase is stored encrypted and is never shown again — " +
                    "not here, not in the timeline, not in a notification.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                message?.let { Text(it, color = if (messageIsError) TCriticalClr else TClearClr, fontSize = 12.sp) }
                TButton(
                    if (configured) "REPLACE PHRASE" else "SAVE PHRASE",
                    onClick = {
                        val value = phrase
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { DuressPhrase.set(context, value) } }
                            r.onSuccess {
                                phrase = ""
                                configured = DuressPhrase.isConfigured(context)
                                message = if (configured) "Saved" else "A blank phrase clears the setting"
                                messageIsError = false
                            }.onFailure {
                                message = "Could not save: ${it.message ?: it.javaClass.simpleName}"
                                messageIsError = true
                            }
                        }
                    },
                    style = TButtonStyle.PRIMARY,
                    enabled = phrase.isNotBlank()
                )
                if (configured) {
                    TButton("CLEAR PHRASE", onClick = { showClear = true }, style = TButtonStyle.CRITICAL)
                }
            }
        }

        item(key = "§how") {
            TCard(spacing = 6) {
                Text("How it works", color = TInkClr, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "• Type the phrase as a message to any contact or group and send it as usual.\n" +
                    "• The message is delivered normally, so the conversation looks unremarkable.\n" +
                    "• At the same time an SOS goes to all your groups with your last known location, " +
                    "and the timeline records only that an SOS was triggered — never the phrase itself.\n" +
                    "• Nothing on screen changes. Carry on as if nothing happened.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }
}
