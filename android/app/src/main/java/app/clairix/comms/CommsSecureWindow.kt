package app.clairix.comms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps the comms screens out of screenshots, screen recordings and the
 * Recents thumbnail, whatever the passcode setting. Every comms screen holds
 * [KeepOffScreen] while it is composed; MainActivity sets FLAG_SECURE whenever
 * a hold is taken, a passcode is on, or the build is the comms-only one (where
 * every screen is a comms screen). CallActivity sets it unconditionally.
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
