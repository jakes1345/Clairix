package app.clairix

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.CallManager
import app.clairix.comms.CommsNotifications
import app.clairix.comms.Vault
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.delay

/**
 * A call ringing on a locked phone, before its offer can be opened: the
 * envelope said only that it is a call, so this names no one. Answer asks
 * Android to unlock; once it is unlocked the offer is read, and answered as it
 * arrives (see CallManager.answerWhenUnlocked), and the call screen takes
 * over. Not now silences the ring; the caller hears it ring out.
 */
class LockedRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (intent?.action == ACTION_ANSWER) answer()
        setContent {
            val theme by AppSettings.theme.collectAsStateWithLifecycle()
            val sealed by Vault.sealed.collectAsStateWithLifecycle()
            // Unlocked: the call screen (or nothing, if the call is over) takes it from here.
            LaunchedEffect(sealed) { if (!sealed) finish() }
            LaunchedEffect(Unit) { delay(RING_MS); finish() }
            ClairixTheme(theme = theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Incoming Clairix call", style = MaterialTheme.typography.headlineMedium, color = ClairixTheme.colors.text, textAlign = TextAlign.Center)
                        Text(
                            "Unlock to answer. Who is calling shows once the phone is unlocked: until then nothing on it can be read.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle,
                            textAlign = TextAlign.Center
                        )
                        PrimaryButton("Answer", onClick = { answer() }, modifier = Modifier.fillMaxWidth(), tone = Tone.Accent)
                        SecondaryButton("Not now", onClick = { decline() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_ANSWER) answer()
    }

    private fun answer() {
        CallManager.answerWhenUnlocked()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) { Vault.open(); finish(); return }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
        })
    }

    private fun decline() {
        getSystemService(NotificationManager::class.java)?.cancel(CommsNotifications.LOCKED_CALL_NOTIFICATION_ID)
        CallManager.stopLockedRing()
        finish()
    }

    companion object {
        const val ACTION_ANSWER = "app.clairix.LOCKED_CALL_ANSWER"
        /** As long as a caller waits; then the call is over, unanswered. */
        private const val RING_MS = 45_000L
    }
}
