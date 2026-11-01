package app.clairix

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.security.AppLock
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.IconWell
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/*
 * The places that hand someone the owner's routine or her evidence (Patterns,
 * the unlock ledger, the tracker map and the evidence export) ask for the app
 * passcode, or a biometric, every time they are opened while a passcode is set.
 * The relock delay does not count here: it exists so switching apps does not
 * lock Clairix, and these are what someone holding the unlocked phone for a
 * minute would look for. A wrong passcode here counts towards the lock's limit
 * exactly as one at the lock screen does. With no passcode set they open, and
 * the two pages say plainly that anyone can read them.
 */

/**
 * Wraps a page so that, while a passcode is set, it opens only after the owner
 * confirms it is her; [purpose] finishes "Enter your Clairix passcode to ...".
 * It closes whenever Clairix leaves the screen, even for a moment. Getting past
 * the lock screen with the page underneath opens it again: whoever did that
 * has just entered the passcode or a biometric.
 */
@Composable
internal fun OwnerGate(title: String, purpose: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    val enabled by AppLock.enabled.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val gate = remember { OwnerGateState(passcodeSet = enabled, locked = locked) }
    LaunchedEffect(enabled) { gate.passcodeChanged(enabled) }
    LaunchedEffect(locked) { gate.lockChanged(locked) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { gate.left() }

    if (gate.isOpen(passcodeSet = enabled)) {
        content()
    } else {
        ToolPageList(title = title, onBack = onBack) {
            item(key = "§owner-check") {
                OwnerCheckCard(purpose = purpose, onConfirmed = { gate.ownerConfirmed() })
            }
        }
    }
}

/**
 * When a gated page is open, as things happen to it. With no passcode set it is
 * open. With one, it is closed until the owner confirms, and closed again when
 * Clairix leaves the screen or locks. Two things open it without asking twice:
 * getting past the lock screen with the page underneath, and setting the
 * passcode from the page's own banner, both of which took the passcode a
 * moment ago.
 */
@Stable
internal class OwnerGateState(passcodeSet: Boolean, locked: Boolean) {
    private var confirmed by mutableStateOf(false)
    private var openedWithout by mutableStateOf(!passcodeSet)
    private var behindLock = locked

    fun isOpen(passcodeSet: Boolean): Boolean = !passcodeSet || confirmed || openedWithout

    fun passcodeChanged(set: Boolean) {
        if (!set) {
            openedWithout = true
        } else if (openedWithout) {
            confirmed = true
            openedWithout = false
        }
    }

    fun lockChanged(locked: Boolean) {
        if (locked) {
            confirmed = false
            behindLock = true
        } else if (behindLock) {
            confirmed = true
            behindLock = false
        }
    }

    /** Clairix left the screen: a moment is enough, whatever the relock delay. */
    fun left() {
        confirmed = false
    }

    fun ownerConfirmed() {
        confirmed = true
    }
}

/** An action behind the owner check; made by [rememberOwnerCheck]. */
@Stable
internal class OwnerCheck(private val request: (purpose: String, action: () -> Unit) -> Unit) {
    /** [action] as an onClick, asking first whenever a passcode is set; [purpose] as for [OwnerGate]. */
    fun guard(purpose: String, action: () -> Unit): () -> Unit = { request(purpose, action) }
}

/**
 * For a button whose action must ask first (the evidence export): the dialog
 * that asks is drawn here, where this is called, while it is up.
 */
@Composable
internal fun rememberOwnerCheck(): OwnerCheck {
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    pending?.let { (purpose, action) ->
        OwnerCheckDialog(
            purpose = purpose,
            onConfirmed = { pending = null; action() },
            onDismiss = { pending = null }
        )
    }
    return remember {
        OwnerCheck { purpose, action -> if (AppLock.enabled.value) pending = purpose to action else action() }
    }
}

/** The typed passcode and what became of it, shared by the card and the dialog. */
@Stable
private class OwnerCheckEntry(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConfirmed: () -> Unit
) {
    var code by mutableStateOf("")
    var error by mutableStateOf("")
    var checking by mutableStateOf(false)
        private set

    fun submit() {
        if (checking) return
        checking = true
        error = ""
        scope.launch {
            // Confirms without touching the lock state; a wrong one is counted like any other.
            val verdict = withContext(Dispatchers.IO) { AppLock.attempt(context, code, unlockOnSuccess = false) }
            code = ""
            checking = false
            val problem = ownerCheckProblem(verdict)
            if (problem == null) onConfirmed() else error = problem
        }
    }

    fun biometric(activity: FragmentActivity?) {
        val act = activity ?: return
        scope.launch {
            when (val gate = withContext(Dispatchers.IO) { AppLock.biometricGate(context) }) {
                is AppLock.BiometricGate.Ready -> showConfirmPrompt(act, gate.cipher, onConfirmed) { error = it }
                AppLock.BiometricGate.Invalidated -> error = BIOMETRICS_CHANGED
                AppLock.BiometricGate.Unavailable -> Unit
            }
        }
    }
}

@Composable
private fun rememberOwnerCheckEntry(onConfirmed: () -> Unit): OwnerCheckEntry {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onConfirmed)
    return remember(context, scope) { OwnerCheckEntry(context, scope) { latest() } }
}

/**
 * As at the lock screen, the biometric prompt comes up by itself when biometric
 * unlock is on; never while the lock screen is up over this, which has its own.
 */
@Composable
private fun AutoBiometric(entry: OwnerCheckEntry, activity: FragmentActivity?, biometricEnabled: Boolean) {
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    LaunchedEffect(biometricEnabled, locked) { if (biometricEnabled && !locked) entry.biometric(activity) }
}

@Composable
private fun OwnerCheckCard(purpose: String, onConfirmed: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    val biometricEnabled by AppLock.biometricEnabled.collectAsStateWithLifecycle()
    val entry = rememberOwnerCheckEntry(onConfirmed)
    val c = ClairixTheme.colors
    AutoBiometric(entry, activity, biometricEnabled)

    ClairixCard(tone = Tone.Accent, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconWell(Icons.Filled.Lock, Tone.Accent)
            Text(CONFIRM_TITLE, style = MaterialTheme.typography.titleMedium, color = c.text)
        }
        Text(
            "Enter your Clairix passcode to $purpose. It is asked every time, so someone holding this phone " +
                "while it is unlocked cannot see it.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSubtle
        )
        PasscodeField(entry)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (biometricEnabled && activity != null) {
                GhostButton("Use biometrics", onClick = { entry.biometric(activity) }, tone = Tone.Neutral, enabled = !entry.checking)
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Open", onClick = { entry.submit() }, enabled = !entry.checking)
        }
    }
}

@Composable
private fun OwnerCheckDialog(purpose: String, onConfirmed: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    val biometricEnabled by AppLock.biometricEnabled.collectAsStateWithLifecycle()
    val entry = rememberOwnerCheckEntry(onConfirmed)
    AutoBiometric(entry, activity, biometricEnabled)

    ClairixDialog(
        title = CONFIRM_TITLE,
        text = "Enter your Clairix passcode to $purpose.",
        confirmLabel = "Continue",
        onConfirm = { entry.submit() },
        onDismiss = onDismiss,
        confirmEnabled = !entry.checking
    ) {
        PasscodeField(entry)
        if (biometricEnabled && activity != null) {
            GhostButton("Use biometrics", onClick = { entry.biometric(activity) }, tone = Tone.Neutral, enabled = !entry.checking)
        }
    }
}

@Composable
private fun PasscodeField(entry: OwnerCheckEntry) {
    if (entry.error.isNotEmpty()) {
        Text(entry.error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
    }
    ClairixTextField(
        value = entry.code,
        onValueChange = { v -> entry.code = v.filter { it.isDigit() }.take(AppLock.MAX_LENGTH); entry.error = "" },
        label = "Passcode",
        keyboardType = KeyboardType.NumberPassword,
        isError = entry.error.isNotEmpty(),
        visualTransformation = PasswordVisualTransformation(),
        enabled = !entry.checking,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The system prompt over [cipher], for confirming inside the unlocked app: only
 * a strong biometric can complete it, and the success callback counts only when
 * the Keystore really finishes the operation ([AppLock.confirmWithBiometric]).
 */
private fun showConfirmPrompt(activity: FragmentActivity, cipher: Cipher, onConfirmed: () -> Unit, onError: (String) -> Unit) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (AppLock.confirmWithBiometric(result.cryptoObject?.cipher)) onConfirmed()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // Cancelling, or choosing the passcode instead, is not an error to report.
            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                onError(errString.toString())
            }
        }
        override fun onAuthenticationFailed() {}
    }
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(CONFIRM_TITLE)
        .setSubtitle("Clairix asks again before showing this")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use passcode")
        .build()
    runCatching {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}

private const val CONFIRM_TITLE = "Confirm it's you"
private const val BIOMETRICS_CHANGED =
    "Biometrics on this phone changed. Use your passcode; biometric unlock can be switched on again in Settings."

/**
 * What the in-app check says about [verdict]; null when it passed. A wipe set
 * off here looks like one more wrong passcode until the process goes, as at the
 * lock screen, and so does any outcome this does not know.
 */
internal fun ownerCheckProblem(verdict: AppLock.Verdict): String? = when (verdict) {
    AppLock.Verdict.Accepted -> null
    is AppLock.Verdict.Rejected ->
        if (verdict.lockedOutForMs > 0L) "Wrong passcode. Try again in ${waitText(verdict.lockedOutForMs)}." else "Wrong passcode"
    is AppLock.Verdict.LockedOut -> "Too many attempts. Try again in ${waitText(verdict.remainingMs)}."
    is AppLock.Verdict.Unavailable -> "Passcode check unavailable: ${verdict.message}"
    else -> "Wrong passcode"
}

/** "30 seconds", "2 minutes", "1 hour", rounded up so it is never too short. */
internal fun waitText(ms: Long): String {
    val s = ((ms + 999L) / 1000L).coerceAtLeast(1L)
    return when {
        s < 60L -> plural(s, "second")
        s < 3600L -> plural((s + 59L) / 60L, "minute")
        else -> plural((s + 3599L) / 3600L, "hour")
    }
}

// ── No passcode ────────────────────────────────────────────────────────────

/**
 * The line a page that shows the owner's routine carries while no passcode is
 * set, and the passcode setup right there under it. The page keeps it while
 * [setting] is true even once the passcode is saved, so that the wipe-limit
 * question which follows the save has somewhere to be; it shows nothing more
 * after that.
 */
@Composable
internal fun NoPasscodeBanner(setting: Boolean, onSettingChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val enabled by AppLock.enabled.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled) {
            Notice(
                Tone.Caution,
                "Anyone holding this unlocked phone can read this.",
                action = { if (!setting) GhostButton("Set passcode", onClick = { onSettingChange(true) }) }
            )
        }
        if (setting) {
            PasscodeSetupPanel(
                context,
                isChanging = false,
                onDone = { onSettingChange(false) },
                onCancel = { onSettingChange(false) },
                // These pages are the full edition's: there is evidence to export before a wipe limit is picked.
                onExportEvidence = if (BuildConfig.FULL_ACCESS) { { shareEvidenceReport(context) } } else null
            )
        }
    }
}

// ── After an unlock ────────────────────────────────────────────────────────

/**
 * Once the lock is passed: wrong passcodes entered since Clairix was last
 * opened, other than the owner's own slips at the lock screen she just passed,
 * and when. Drawn over everything, after the lock screen has gone.
 */
@Composable
internal fun WrongPasscodeNotice() {
    val notice by AppLock.wrongAttempts.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val attempts = notice ?: return
    if (locked) return
    ClairixDialog(
        title = "Wrong passcode entered",
        text = wrongAttemptsMessage(attempts, System.currentTimeMillis()) +
            " If that was not you, someone has tried to get into Clairix.",
        confirmLabel = "OK",
        onConfirm = { AppLock.dismissWrongAttempts() },
        onDismiss = { AppLock.dismissWrongAttempts() },
        dismissLabel = null
    )
}

/** "A wrong passcode was entered 12 minutes ago." for [attempts], read at [now]. */
internal fun wrongAttemptsMessage(attempts: AppLock.WrongAttempts, now: Long): String {
    val ago = agoText(now - attempts.lastAt)
    return when {
        attempts.count == 1 && ago != null -> "A wrong passcode was entered $ago."
        attempts.count == 1 -> "A wrong passcode was entered since Clairix was last unlocked."
        ago != null -> "${attempts.count} wrong passcodes were entered, the last $ago."
        else -> "${attempts.count} wrong passcodes were entered since Clairix was last unlocked."
    }
}

/** How long ago, in words; null when the clock has been set back past it. */
private fun agoText(elapsedMs: Long): String? {
    if (elapsedMs < 0L) return null
    val minutes = elapsedMs / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "less than a minute ago"
        hours < 1L -> plural(minutes, "minute") + " ago"
        days < 1L -> plural(hours, "hour") + (if (minutes % 60L > 0L) " ${plural(minutes % 60L, "minute")}" else "") + " ago"
        else -> plural(days, "day") + " ago"
    }
}

private fun plural(n: Long, word: String): String = if (n == 1L) "$n $word" else "$n ${word}s"
