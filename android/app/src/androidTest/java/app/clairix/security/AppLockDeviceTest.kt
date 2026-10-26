package app.clairix.security

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.clairix.FreshUi
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app lock on a real device: what it keeps from accessibility services
 * (which read the window's semantics, not its pixels), and what its settings
 * file gives away. Every test leaves the lock off, as it found it.
 */
@RunWith(AndroidJUnit4::class)
class AppLockDeviceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val ui = FreshUi(instr, device)
    private val context: Context get() = instr.targetContext
    private val pkg = "app.clairix"

    @Before
    fun setUp() {
        AppLock.init(context)
        AppLock.disable(context)
    }

    @After
    fun tearDown() {
        AppLock.disable(context)
    }

    private fun texts(): Set<String> =
        ui.findAll(By.pkg(pkg)).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.toSet()

    /**
     * Locked, the app underneath the lock screen is drawn over and also gone from
     * the accessibility tree: nothing it showed can be read by TalkBack, an
     * accessibility service or uiautomator, and nothing in it can be clicked.
     */
    @Test
    fun aLockedAppShowsAccessibilityNothingButTheLockScreen() {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        assertTrue("app did not come up", ui.waitHas(By.pkg(pkg).depth(0), 20_000))
        device.waitForIdle(3_000)
        val before = texts()
        assertTrue("the app showed no text to compare with", before.isNotEmpty())

        AppLock.setPasscode(context, "73915824").getOrThrow()
        AppLock.lock()
        device.waitForIdle(3_000)
        assertTrue("the lock screen did not come up", ui.waitHas(By.text("Enter your passcode to continue").pkg(pkg), 5_000))
        val after = texts()
        val digits = (0..9).map { it.toString() }.toSet()
        val leaked = (before intersect after) - digits
        assertTrue("visible through the lock: $leaked", leaked.isEmpty())
    }

    /**
     * The lock's settings file looks the same with a duress passcode or without
     * one: a salt and verifier are always there, random ones when none is set.
     */
    @Test
    fun theSettingsFileDoesNotSayWhetherADuressPasscodeIsSet() {
        AppLock.setPasscode(context, "73915824").getOrThrow()
        val prefs = context.getSharedPreferences(AppLock.PREFS_NAME, Context.MODE_PRIVATE)
        val keysWithout = prefs.all.keys.toSet()
        assertTrue(prefs.contains("duress_verifier"))
        assertFalse(AppLock.hasDuress(context))

        AppLock.setDuress(context, "46820135").getOrThrow()
        assertTrue(AppLock.hasDuress(context))
        assertEquals(keysWithout, prefs.all.keys.toSet())

        AppLock.setDuress(context, null).getOrThrow()
        assertFalse(AppLock.hasDuress(context))
        assertEquals(keysWithout, prefs.all.keys.toSet())
        // The real passcode still works; a decoy verifier matches nothing.
        assertEquals(AppLock.Verdict.Accepted, AppLock.attempt(context, "73915824"))
    }
}
