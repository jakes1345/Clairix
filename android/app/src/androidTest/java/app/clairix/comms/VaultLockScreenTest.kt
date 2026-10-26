package app.clairix.comms

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.random.Random

/**
 * The vault keys as the owner sets and removes a lock screen. Each test sets
 * a PIN with `locksettings` and clears it again whatever happens; the app's
 * own keys move with it, so these run on their own, after everything else
 * that uses the app's data:
 *
 *   am instrument -e class app.clairix.comms.VaultLockScreenTest -e lockScreen true ...
 */
@RunWith(AndroidJUnit4::class)
class VaultLockScreenTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    @Before
    fun setUp() {
        assumeTrue(
            "pass lockScreen=true to run the tests that change the lock screen",
            InstrumentationRegistry.getArguments().getString("lockScreen") == "true"
        )
        Vault.init(context)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** Runs [block] with PIN [PIN] set as the lock screen, and clears it after. */
    private fun withPin(block: () -> Unit) {
        assumeFalse("needs a phone with no lock screen to start from", keyguard.isDeviceSecure)
        shell("locksettings set-pin $PIN")
        try {
            assertTrue("locksettings did not set a PIN", keyguard.isDeviceSecure)
            block()
        } finally {
            shell("locksettings clear --old $PIN")
        }
        assertFalse("the PIN was not cleared", keyguard.isDeviceSecure)
    }

    @Test
    fun settingALockScreenMovesTheVaultUnderTheUnlockBoundKey() {
        val secret = Random.nextBytes(32)
        val before = VaultBox.encrypt(secret)
        withPin {
            Vault.check()
            assertTrue("a PIN is set but the vault stayed under the open key", VaultBox.writesUnlockBound())
            // The same data key, wrapped anew: nothing written before needs rewriting.
            val read = VaultBox.open(before)
            assertArrayEquals(secret, read.plain)
            assertFalse(read.legacy)
            // Sealed and opened again while unlocked: unwrapped by the unlock-bound key.
            VaultBox.forget()
            assertArrayEquals(secret, VaultBox.open(before).plain)
        }
        Vault.check()
    }

    /**
     * Android 14 deletes every unlock-bound key when the lock screen is
     * removed. The vault survives because its data key, in memory while the
     * vault is open, is wrapped again under the open key before it is needed.
     */
    @Test
    fun removingTheLockScreenKeepsTheVault() {
        val secret = Random.nextBytes(32)
        lateinit var sealed: ByteArray
        withPin {
            Vault.check()
            assertTrue(VaultBox.writesUnlockBound())
            sealed = VaultBox.encrypt(secret)
        }
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        Log.i(TAG, "unlock-bound key still there after the lock screen was removed: ${ks.containsAlias("clairix_vault_v1")}")
        Vault.check()
        assertFalse("no lock screen, yet the vault stayed under the unlock-bound key", VaultBox.writesUnlockBound())
        // As after a restart: the data key comes back from the open key's wrapping.
        VaultBox.forget()
        assertArrayEquals(secret, VaultBox.open(sealed).plain)
    }

    private companion object {
        const val PIN = "1590"
        const val TAG = "VaultLockScreenTest"
    }
}
