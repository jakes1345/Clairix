package app.clairix.comms

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
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
    fun settingALockScreenMovesNewWritesToTheUnlockBoundKey() {
        val secret = Random.nextBytes(32)
        val before = VaultBox.encrypt(secret)
        val startedOpen = !VaultBox.writesUnlockBound()
        withPin {
            if (startedOpen) {
                // Still readable, and reported so its owner writes it again.
                val read = VaultBox.open(before)
                assertArrayEquals(secret, read.plain)
                assertTrue("a blob under the open key was not reported for rewriting", read.legacy)
            }
            val after = VaultBox.encrypt(secret)
            assertTrue("a PIN is set but writes did not move to the unlock-bound key", VaultBox.writesUnlockBound())
            val reread = VaultBox.open(after)
            assertArrayEquals(secret, reread.plain)
            assertFalse(reread.legacy)
        }
    }

    /**
     * What Android does to an unlock-bound key when the owner removes the lock
     * screen, on a key of the test's own so the app's are untouched: the vault
     * has to survive it.
     */
    @Test
    fun anUnlockBoundKeyAcrossRemovingTheLockScreen() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val secret = Random.nextBytes(32)
        lateinit var sealed: ByteArray
        try {
            withPin {
                val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(
                        KeyGenParameterSpec.Builder(TEST_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setUnlockedDeviceRequired(true)
                            .build()
                    )
                }.generateKey()
                sealed = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key); iv + doFinal(secret) }
            }
            val after = runCatching {
                val key = (ks.getEntry(TEST_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
                    doFinal(sealed, 12, sealed.size - 12)
                }
            }
            Log.i(TAG, "unlock-bound key after the lock screen was removed: " +
                after.fold({ "opens" }, { "${it.javaClass.name}: ${it.message}" }) + "; alias still there: ${ks.containsAlias(TEST_ALIAS)}")
            after.getOrNull()?.let { assertArrayEquals(secret, it) }
        } finally {
            runCatching { ks.deleteEntry(TEST_ALIAS) }
        }
    }

    private companion object {
        const val PIN = "1590"
        const val TEST_ALIAS = "clairix_test_unlock_bound"
        const val TAG = "VaultLockScreenTest"
    }
}
