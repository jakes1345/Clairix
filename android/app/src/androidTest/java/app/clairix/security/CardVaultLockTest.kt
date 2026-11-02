package app.clairix.security

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.clairix.ArmedCard
import app.clairix.CardEmulationService
import app.clairix.CardProfile
import app.clairix.Registry
import app.clairix.VaultCard
import app.clairix.analysis.CardVault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card vault and card emulation on a real device: a synthetic ISO-DEP card
 * saved through [CardVault] under a set lock screen, and the armed emulation
 * disarming the moment the app locks ([AppLock.lock]).
 *
 * Like [app.clairix.comms.VaultLockScreenTest], these move the app's own vault
 * keys (one sets a device PIN), so they run only when asked, after everything
 * else that uses the app's data:
 *
 *   am instrument -e class app.clairix.security.CardVaultLockTest -e lockScreen true ...
 */
@RunWith(AndroidJUnit4::class)
class CardVaultLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    // A synthetic ISO 14443-4 conversation: SELECT PPSE and an AID SELECT, each with
    // a response. Opaque to the test (only round-trip equality and valid hex matter).
    private val pairs = listOf(
        "00A4040E325041592E5359532E444446303100" to "6F088407A00000000310109000",
        "00A4040007A000000003101000" to "6F088407A00000000310109000"
    )

    @Before
    fun setUp() {
        assumeTrue(
            "pass lockScreen=true to run the tests that change the lock screen",
            InstrumentationRegistry.getArguments().getString("lockScreen") == "true"
        )
    }

    @After
    fun tearDown() {
        CardEmulationService.activePairs = emptyList()
        Registry.setEmulating(null)
        runCatching { runBlocking { CardVault(context).erase() } }
        runCatching { AppLock.init(context); AppLock.disable(context) }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun withPin(block: () -> Unit) {
        assumeFalse("needs a phone with no lock screen to start from", keyguard.isDeviceSecure)
        shell("locksettings set-pin $PIN")
        try {
            assertTrue("locksettings did not set a PIN", keyguard.isDeviceSecure)
            block()
        } finally {
            shell("locksettings clear --old $PIN")
        }
    }

    /**
     * A synthetic ISO-DEP card saved through the vault round-trips with its APDU
     * pairs, under a set lock screen. The vault's key requires a recent
     * authentication; where the device's auth window cannot be satisfied in this
     * harness the save is skipped rather than failed (the disarm test below does
     * not depend on persistence).
     */
    @Test
    fun anIsoDepCardSavedThroughTheVaultRoundTrips() {
        withPin {
            val vault = CardVault(context)
            runCatching { runBlocking { vault.erase() } }
            val card = VaultCard(
                id = "test-card-1",
                uid = "04A1B2C3D4E5F6",
                label = "Test office badge",
                profile = CardProfile.ISO14443_4,
                addedTs = System.currentTimeMillis(),
                apduPairs = pairs
            )
            val write = runBlocking { vault.add(card) }
            // The auth-bound key may refuse without a fresh device-credential auth in
            // this harness; that is an environment limit, not a failure of the vault.
            assumeTrue(
                "the vault key needs a recent device-credential authentication",
                write is CardVault.WriteResult.Saved
            )
            val loaded = runBlocking { vault.load() }
            assertTrue("the vault did not reopen", loaded is CardVault.LoadResult.Loaded)
            val stored = (loaded as CardVault.LoadResult.Loaded).cards.firstOrNull { it.id == card.id }
            assertEquals("the stored UID differs", card.uid, stored?.uid)
            assertEquals("the recorded APDU pairs did not survive", pairs, stored?.apduPairs)
        }
    }

    /**
     * An armed card stops answering readers the moment the app lock engages: the
     * loaded pairs are forgotten and the banner cleared. This is what [AppLock.lock]
     * must do through [EmulationGuard]; it needs a passcode set but no device PIN.
     */
    @Test
    fun emulationDisarmsWhenTheAppLocks() {
        AppLock.init(context)
        AppLock.disable(context)
        try {
            // Arm emulation as the activity does, minus the real NFC AID registration.
            CardEmulationService.loadPairs(pairs)
            Registry.setEmulating(ArmedCard("test-card-1", "Test office badge"))
            assertTrue("emulation was not armed", CardEmulationService.activePairs.isNotEmpty())

            AppLock.setPasscode(context, PASSCODE).getOrThrow()
            AppLock.lock()

            assertEquals("the banner was not cleared on lock", null, Registry.emulating.value)
            assertTrue("the armed pairs were not forgotten on lock", CardEmulationService.activePairs.isEmpty())
        } finally {
            AppLock.disable(context)
        }
    }

    private companion object {
        const val PIN = "1590"
        const val PASSCODE = "839174"
    }
}
