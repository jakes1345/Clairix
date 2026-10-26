package app.clairix.comms

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the comms vault is open: the identity and the message database in
 * memory, under keys ([VaultBox]) that work only while the phone is unlocked.
 *
 * It seals when the screen goes off on a phone with a secure lock screen, and
 * opens again when the owner unlocks (or the screen comes back on before the
 * phone locked). Sealed, [CommsRepository] has dropped the identity and
 * closed the database, and what arrives waits, still sealed to this phone, in
 * the [LockedInbox]. A call in progress holds it open: the screen goes off
 * against the owner's cheek, and the call must go on.
 */
object Vault {

    private val _sealed = MutableStateFlow(false)
    val sealed: StateFlow<Boolean> = _sealed.asStateFlow()

    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private var installed = false
    private var holds = 0
    /** The screen went off while something held the vault open: seal once nothing does. */
    private var sealWhenReleased = false

    @Synchronized
    fun init(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        keyguard = app.getSystemService(KeyguardManager::class.java)
        power = app.getSystemService(PowerManager::class.java)
        VaultBox.init(app)
        // A process started while the phone is locked (a push, a restart) starts sealed.
        _sealed.value = keyguard.isDeviceSecure && keyguard.isDeviceLocked
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // First, while the data key may still be in memory: a lock screen
                // set or removed since is followed now (see VaultBox.reconcile).
                check()
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> if (!keyguard.isDeviceLocked) open()
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Wraps the vault's data key again if the lock screen has been set or
     * removed while the vault was open. Removing it deletes the unlock-bound
     * key, so this must happen while the data key is still in memory: on every
     * screen and unlock event, and whenever Clairix comes to the front.
     */
    fun check() {
        runCatching { VaultBox.reconcile() }.onFailure { CommsLog.add("Could not move the vault to the current lock screen: ${it.message}") }
    }

    /**
     * Whether the phone has a secure lock screen (a PIN, pattern or password),
     * asked afresh each time since the owner can set or remove one at any
     * point. Null before [init].
     */
    fun deviceSecure(): Boolean? = if (::keyguard.isInitialized) keyguard.isDeviceSecure else null

    @Synchronized
    private fun screenOff() {
        // Without a secure lock screen nothing locks: the vault key works always.
        if (!keyguard.isDeviceSecure) return
        if (holds > 0) { sealWhenReleased = true; return }
        _sealed.value = true
    }

    /** The owner unlocked (or the phone never locked): open, and let what waited be read. */
    @Synchronized
    fun open() {
        sealWhenReleased = false
        _sealed.value = false
    }

    /** Seals now, as the screen going off does: for tests, which cannot lock the emulator. */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun sealForTest() { _sealed.value = true }

    /** Keeps the vault open while held (a call in progress). */
    @Synchronized
    fun hold() { holds++ }

    @Synchronized
    fun release() {
        holds = (holds - 1).coerceAtLeast(0)
        if (holds == 0 && sealWhenReleased) {
            sealWhenReleased = false
            // The call ended with the screen still off, or the phone locked meanwhile.
            if (keyguard.isDeviceLocked || !power.isInteractive) _sealed.value = true
        }
    }
}

/** The vault is sealed: the phone is locked, and what this needs is out of reach until it is unlocked. */
class VaultSealedException : IllegalStateException("Locked: unlock the phone to read Clairix")
