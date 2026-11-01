package app.clairix.security

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.clairix.Alert
import app.clairix.AppSettings
import app.clairix.FreshUi
import app.clairix.Severity
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

    /** Whether [condition] comes true within [timeoutMs]; a posted notification takes a moment to reach the shade. */
    private fun settles(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /**
     * Locking collapses every alert notification, the platform monitors' and
     * the scanner's, into one that names nothing; an alert posted while locked
     * names nothing either; and the stand-in goes once the lock is passed.
     * Checked on the notifications actually in the shade, through their extras,
     * not on what built them.
     */
    @Test
    fun lockingLeavesNoAlertTextInTheShade() {
        if (Build.VERSION.SDK_INT >= 33) runCatching { instr.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS) }
        AppSettings.load(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val names = listOf("AirTag", "wheel wells", "02:14")
        fun shade(): List<String> = manager.activeNotifications.flatMap { sbn ->
            val extras = sbn.notification.extras
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .mapNotNull { extras.getCharSequence(it)?.toString() }
        }
        fun leaked(): List<String> = shade().filter { line -> names.any { it in line } }
        val now = System.currentTimeMillis()
        val tracker = Alert(
            id = "test_tracker@$now", ts = now, severity = Severity.CRITICAL,
            title = "AirTag is following you", detail = "Check wheel wells, bumper covers, OBD-II port.",
            dedupeKey = "test_tracker"
        )
        val sleep = tracker.copy(
            id = "test_sleep@$now", severity = Severity.HIGH,
            title = "Your phone was unlocked while you slept", detail = "1 unlock between 23:00 and 07:00: at 02:14",
            dedupeKey = "test_sleep"
        )
        try {
            // Unlocked, with details on: the shade names the tracker.
            AppSettings.setShowAlertDetails(context, true)
            PlatformAlerts.notify(context, tracker)
            assertTrue("the alert was not posted with its detail: ${shade()}", settles { leaked().isNotEmpty() })

            AppLock.setPasscode(context, "73915824").getOrThrow()
            AppLock.lock()
            assertTrue("alert text survived the lock: ${leaked()}", settles { leaked().isEmpty() })
            assertTrue("no stand-in for the alerts: ${shade()}", settles { "Unlock to see alerts" in shade() })

            // Posted while locked: nothing named, whatever the setting says.
            PlatformAlerts.notify(context, sleep)
            assertTrue("the locked alert was not posted: ${shade()}", settles { "Unlock to read" in shade() })
            assertTrue("a locked alert named something: ${leaked()}", leaked().isEmpty())

            assertEquals(AppLock.Verdict.Accepted, AppLock.attempt(context, "73915824"))
            assertTrue("the stand-in outlived the unlock: ${shade()}", settles { "Unlock to see alerts" !in shade() })
        } finally {
            AppSettings.setShowAlertDetails(context, false)
            manager.cancelAll()
        }
    }

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
