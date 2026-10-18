package app.clairix.security

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import app.clairix.ClairixIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import app.clairix.ui.theme.ClairixTheme
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import app.clairix.ui.components.*
import androidx.compose.material3.MaterialTheme
import app.clairix.ui.theme.monoSmall

/**
 * Full-screen PIN entry that gates the rest of the app.
 * Appears when [AppLock.locked] is true. Never tells the user how
 * many attempts remain, or that a wipe limit exists.
 */
@Composable
fun LockScreen() {
    val context  = LocalContext.current
    val activity = context as? FragmentActivity
    val scope    = rememberCoroutineScope()

    var code        by remember { mutableStateOf("") }
    var checking    by remember { mutableStateOf(false) }
    var shake       by remember { mutableStateOf(false) }
    var errorText   by remember { mutableStateOf("") }
    var lockoutMs   by remember { mutableLongStateOf(0L) }
    var countdown   by remember { mutableLongStateOf(0L) }
    val maxLen = AppLock.MAX_LENGTH
    val biometricEnabled by AppLock.biometricEnabled.collectAsStateWithLifecycle()

    // Countdown ticker for the lockout display, on the same monotonic clock as the lock itself.
    LaunchedEffect(lockoutMs) {
        if (lockoutMs <= 0L) return@LaunchedEffect
        val deadline = SystemClock.elapsedRealtime() + lockoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) { countdown = 0L; break }
            countdown = remaining
            delay(500L)
        }
    }

    // The prompt only opens with a cipher the Keystore will complete for an
    // authenticated strong biometric; the key behind it dies with a new enrolment.
    fun promptBiometric() {
        val act = activity ?: return
        scope.launch {
            when (val gate = withContext(Dispatchers.IO) { AppLock.biometricGate(context) }) {
                is AppLock.BiometricGate.Ready -> showBiometricPrompt(act, gate.cipher) { msg -> errorText = msg }
                AppLock.BiometricGate.Invalidated ->
                    errorText = "Biometrics on this phone changed. Enter your passcode; biometric unlock can be switched on again in Settings."
                AppLock.BiometricGate.Unavailable -> Unit
            }
        }
    }

    // Auto-show the biometric prompt when the lock screen first appears.
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled && activity != null) promptBiometric()
    }

    // Auto-submit once the code reaches the set length, OR on an explicit
    // check for shorter codes (≥ MIN_LENGTH) by tapping the last key.
    fun submit() {
        if (code.length < AppLock.MIN_LENGTH || checking) return
        checking = true
        errorText = ""
        scope.launch {
            val verdict = withContext(Dispatchers.IO) {
                AppLock.attempt(context, code, unlockOnSuccess = true)
            }
            code = ""
            checking = false
            when (verdict) {
                AppLock.Verdict.Accepted -> { /* AppLock.locked will flip to false */ }
                // A wipe, whether from the duress passcode or the failure limit,
                // must look exactly like one more wrong passcode until the
                // process goes: the same shake, the same words, no blank pause.
                AppLock.Verdict.Wiping -> {
                    shake = true
                    errorText = "Wrong passcode"
                    delay(400L)
                    shake = false
                }
                is AppLock.Verdict.Rejected -> {
                    shake = true
                    lockoutMs = verdict.lockedOutForMs
                    errorText = if (verdict.lockedOutForMs > 0L) "" else "Wrong passcode"
                    delay(400L)
                    shake = false
                }
                is AppLock.Verdict.LockedOut -> {
                    lockoutMs = verdict.remainingMs
                    errorText = ""
                }
                is AppLock.Verdict.Unavailable -> {
                    errorText = "Passcode check unavailable — ${verdict.message}"
                }
            }
        }
    }

    fun tap(digit: Char) {
        if (checking || countdown > 0L) return
        if (code.length < maxLen) {
            code += digit
            if (code.length == maxLen) submit()
        }
    }

    fun backspace() {
        if (checking || countdown > 0L) return
        if (code.isNotEmpty()) code = code.dropLast(1)
    }

    // Horizontal shake offset on wrong passcode.
    val shakeAnim = rememberInfiniteTransition(label = "shake")
    val shakeX by shakeAnim.animateFloat(
        initialValue = 0f,
        targetValue = if (shake) 10f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeX"
    )

    val c = ClairixTheme.colors
    val showLockout = countdown > 0L
    val active = !checking && countdown == 0L

    Box(
        Modifier
            .fillMaxSize()
            .background(c.base)
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── The mark and what this screen is ───────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LogoMark(size = 56.dp)
                StencilText(
                    "LOCKED",
                    style = MaterialTheme.typography.headlineLarge,
                    color = c.text,
                    ground = c.base,
                    modifier = Modifier.semantics { heading(); contentDescription = "Clairix is locked" }
                )
                Text(
                    "Enter your passcode to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSubtle
                )
            }

            // ── Entered digits ───────────────────────────────────────────────
            val dotSpacing = if (maxLen > 6) 8.dp else 12.dp
            val dotSize = if (maxLen > 6) 12.dp else 14.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(dotSpacing),
                modifier = Modifier
                    .offset(x = if (shake) shakeX.dp else 0.dp)
                    .semantics { contentDescription = "${code.length} digits entered" }
            ) {
                repeat(maxLen) { i ->
                    val filled = i < code.length
                    Box(
                        Modifier
                            .size(dotSize)
                            .background(if (filled) c.accent else c.surfaceRaised, ClairixShapes.Tag)
                            .then(if (!filled) Modifier.border(1.dp, c.borderStrong, ClairixShapes.Tag) else Modifier)
                    )
                }
            }

            // ── Error / lockout text ───────────────────────────────────────
            // A fixed-height slot, so the keypad doesn't jump when a message appears.
            val msg = when {
                showLockout -> {
                    val s = (countdown / 1000L).coerceAtLeast(1L)
                    if (s >= 3600L) "Try again in ${s / 3600}h ${(s % 3600) / 60}m"
                    else if (s >= 60L) "Try again in ${s / 60}m ${s % 60}s"
                    else "Try again in ${s}s"
                }
                else -> errorText
            }
            val msgAlpha by animateFloatAsState(if (msg.isNotEmpty()) 1f else 0f, label = "message")
            Box(Modifier.height(20.dp).alpha(msgAlpha), contentAlignment = Alignment.Center) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = c.danger, textAlign = TextAlign.Center)
            }

            // ── Keypad ─────────────────────────────────────────────────────
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9'),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        for (d in row) {
                            NumKey(label = d.toString(), active = active) { tap(d) }
                        }
                    }
                }
                // Bottom row: biometrics (when on) · 0 · backspace
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(KEY_SIZE))
                    NumKey(label = "0", active = active) { tap('0') }
                    NumKey(label = KEY_BACKSPACE, active = active && code.isNotEmpty(), accent = false) { backspace() }
                }
            }

            // ── Submit (for codes shorter than MAX_LENGTH), or biometrics ──
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                if (code.length in AppLock.MIN_LENGTH until maxLen) {
                    PrimaryButton("Unlock", onClick = { submit() }, enabled = active)
                } else if (biometricEnabled && activity != null && code.isEmpty()) {
                    GhostButton("Use biometrics", onClick = { promptBiometric() }, tone = Tone.Neutral, enabled = active)
                }
            }
        }
    }
}

/**
 * Shows the system prompt over [cipher]. Only a strong (class 3) biometric may
 * satisfy it, because that is all the key behind the cipher accepts; the
 * success callback then proves itself by finishing the operation, and a
 * callback whose cipher the Keystore still refuses unlocks nothing.
 */
private fun showBiometricPrompt(activity: FragmentActivity, cipher: Cipher, onError: (String) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            AppLock.unlockWithBiometric(result.cryptoObject?.cipher)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // A cancel or a tap on "Use passcode" is not something to report; anything
            // else (lockout, hardware failure) leaves the user staring at nothing.
            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                onError(errString.toString())
            }
        }
        override fun onAuthenticationFailed() {}
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Clairix")
        .setSubtitle("Confirm your identity to continue")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use passcode")
        .build()
    runCatching { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
}

/** The keypad's delete key, drawn as the Material backspace icon. */
private const val KEY_BACKSPACE = "backspace"

private val KEY_SIZE = 72.dp

/** A keypad key: an octagon, the mark's own shape, with the digit in the display face. */
@Composable
private fun NumKey(label: String, active: Boolean, accent: Boolean = true, onClick: () -> Unit) {
    val c = ClairixTheme.colors
    val color = if (active) (if (accent) c.text else c.textMuted) else c.textSubtle
    Box(
        Modifier
            .size(KEY_SIZE)
            .clip(ClairixShapes.Octagon)
            .background(if (active) c.surface else c.base)
            .border(1.dp, if (active) c.border else c.surface, ClairixShapes.Octagon)
            .then(
                if (active) Modifier.clickable(
                    onClickLabel = if (label == KEY_BACKSPACE) "Delete last digit" else "Enter $label",
                    role = Role.Button,
                    onClick = onClick
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (label == KEY_BACKSPACE) {
            Icon(ClairixIcons.Backspace, contentDescription = "Delete", tint = color, modifier = Modifier.size(24.dp))
        } else {
            Text(label, style = MaterialTheme.typography.headlineMedium, color = color)
        }
    }
}
