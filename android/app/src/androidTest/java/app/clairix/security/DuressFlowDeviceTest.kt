package app.clairix.security

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.clairix.FreshUi
import app.clairix.HomeTab
import app.clairix.ONBOARDING_DONE_KEY
import app.clairix.TOUR_SEEN_KEY
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The duress passcode on a real device, as the person holding the phone sees
 * it: the code is taken as a wrong one, the lock screen is still there ten
 * seconds later (in a fresh process: the one that showed the old data has
 * ended), the old data is gone, the code now opens Clairix, and a relaunch
 * shows the lock screen again, never the first-run pages.
 *
 * The wipe ends the process the app ran in, so this runs only under
 * [app.clairix.SeparateProcessRunner], in a process of its own, and with
 * `-e duress true` (it erases the app's data); anywhere else it is skipped.
 * The scanner is left off: the emulator's GNSS HAL deadlocks when measurement
 * collection is stopped and started again (see the emulator workflow), and the
 * scanner coming back after the wipe is MainActivity.onResume's ordinary path.
 */
@RunWith(AndroidJUnit4::class)
class DuressFlowDeviceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val ui = FreshUi(instr, device)
    private val context: Context get() = instr.targetContext
    private val pkg = "app.clairix"
    private val passcode = "73915824"
    private val duress = "61849302"
    private val evidence: File get() = File(context.filesDir, "duress-test-evidence.txt")
    private val prefsDir: File get() = File(context.applicationInfo.dataDir, "shared_prefs")

    @Before
    fun setUp() {
        assumeTrue(
            "erases the app's data; run with -e duress true under SeparateProcessRunner",
            InstrumentationRegistry.getArguments().getString("duress") == "true" && Application.getProcessName() != pkg
        )
        endMainProcess()
        AppLock.disable(context)
        // An owner who has used the app: first run and tour done, a passcode, a
        // duress code, and something on disk that must not survive.
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(ONBOARDING_DONE_KEY, true).putBoolean(TOUR_SEEN_KEY, true).commit()
        AppLock.setPasscode(context, passcode).getOrThrow()
        AppLock.setDuress(context, duress).getOrThrow()
        evidence.writeText("the timeline, the contacts, the conversations")
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("duress") != "true" || Application.getProcessName() == pkg) return
        endMainProcess()
        AppLock.disable(context)
        evidence.delete()
    }

    private fun mainPid(): Int? =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.processName == pkg }?.pid

    private fun endMainProcess() {
        mainPid()?.let { Process.killProcess(it) }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (mainPid() != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun lockScreenShown(timeoutMs: Long = 5_000): Boolean =
        ui.waitHas(By.text("Enter your passcode to continue").pkg(pkg), timeoutMs)

    private fun firstRunShown(): Boolean =
        ui.has(By.text("Nothing hides.").pkg(pkg)) || ui.has(By.text("Not now").pkg(pkg)) || ui.has(By.textStartsWith("Tour · ").pkg(pkg))

    /** The app itself, past the lock: the Comms tab's label in the tab bar, or its title in the comms-only build. */
    private fun appShown(timeoutMs: Long = 10_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui.has(By.text(HomeTab.COMMS.label).pkg(pkg)) || ui.has(By.desc(HomeTab.COMMS.label).pkg(pkg))) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun type(code: String) {
        for (digit in code) {
            val key = ui.waitFor(By.text(digit.toString()).pkg(pkg), 5_000) ?: throw AssertionError("no key $digit on the lock screen")
            key.click()
        }
        val unlock = ui.waitFor(By.text("Unlock").pkg(pkg), 5_000) ?: throw AssertionError("no Unlock button")
        unlock.click()
    }

    @Test
    fun theDuressCodeLeavesTheLockScreenAndADecoyThatItOpens() {
        launch()
        assertTrue("the lock screen did not come up", lockScreenShown(20_000))
        val before = mainPid()
        assertNotNull(before)

        type(duress)
        assertTrue("the duress code did not read as a wrong passcode", ui.waitHas(By.text("Wrong passcode").pkg(pkg), 3_000))

        // Ten seconds on, the holder still sees the lock screen, never a first run,
        // and still the words it ended on.
        SystemClock.sleep(10_000)
        assertTrue("the lock screen was gone ten seconds later", lockScreenShown())
        assertFalse("a first-run page showed", firstRunShown())
        assertTrue(ui.has(By.text("Wrong passcode").pkg(pkg)))
        // In a fresh process: nothing the old one held is left even in memory.
        val after = mainPid()
        assertNotNull("the app is not running", after)
        assertNotEquals("the process that showed the old data is still the one on screen", before, after)

        // The data is gone; what is left is the decoy: its first run done, and a lock.
        assertFalse("data survived the wipe", evidence.exists())
        assertFalse(File(context.filesDir, Wiper.PENDING_MARKER).exists())
        assertTrue(File(prefsDir, "${AppLock.PREFS_NAME}.xml").readText().contains("verifier"))
        assertTrue(File(prefsDir, "prefs.xml").readText().contains("name=\"$ONBOARDING_DONE_KEY\" value=\"true\""))

        // The old passcode is gone with the data; the duress code opens the decoy.
        type(duress)
        assertTrue("the duress code did not open the decoy", appShown())
        assertFalse("a first-run page showed behind the lock", firstRunShown())

        // A relaunch: the lock screen again, then the app, never the first run.
        endMainProcess()
        launch()
        assertTrue("the decoy relaunched without its lock screen", lockScreenShown(20_000))
        assertFalse("a first-run page showed on relaunch", firstRunShown())
        type(duress)
        assertTrue("the decoy did not open on relaunch", appShown())
        assertFalse(firstRunShown())
    }
}
