package app.clairix.comms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which screens asked to be kept out of screenshots, screen recordings and
 * the Recents thumbnail. MainActivity sets FLAG_SECURE for every screen
 * unconditionally, as CallActivity always did, so the holds no longer decide
 * anything about the window; they remain the record of which screens are
 * showing something that must not leave the phone, which [secure] answers.
 */
object CommsSecureWindow {
    private val _holds = MutableStateFlow(0)
    val holds: StateFlow<Int> = _holds.asStateFlow()

    fun hold() = _holds.update { it + 1 }
    fun release() = _holds.update { (it - 1).coerceAtLeast(0) }

    /** Whether the window must be secure. */
    fun secure(passcodeOn: Boolean, holds: Int, commsOnlyBuild: Boolean): Boolean =
        passcodeOn || commsOnlyBuild || holds > 0
}

/** Holds the window secure for as long as the calling screen is composed. */
@Composable
fun KeepOffScreen() {
    DisposableEffect(Unit) {
        CommsSecureWindow.hold()
        onDispose { CommsSecureWindow.release() }
    }
}
