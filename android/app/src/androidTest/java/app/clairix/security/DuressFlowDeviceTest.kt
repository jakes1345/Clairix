package app.clairix.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.clairix.FreshUi
import app.clairix.HomeTab
import app.clairix.ONBOARDING_DONE_KEY
import app.clairix.TOUR_SEEN_KEY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The duress passcode on a real device, as the person holding the phone sees it.
 *
 * The wipe ends the process the app ran in, and an instrumentation runs in that
 * process, so the emulator workflow ("Run the duress passcode test") makes three
 * runs in turn:
 *
 * 1. [prepare] (`-e duress prepare`): an owner who has used the app, with the first
 *    run and tour done, a passcode, a duress code, a file that must not survive and
 *    the offline mesh's notification channel, which the decoy never makes.
 * 2. From outside the app (.github/scripts/duress-ui.py): Clairix is opened, the
 *    duress code typed on its lock screen as a person types it, and ten seconds later
 *    the lock screen must still be there saying "Wrong passcode", in a new process,
 *    with no first-run page.
 * 3. [decoy] (`-e duress decoy`): the data and the channel are gone; what is left is the decoy, its
 *    first run done and a lock that the old passcode does not open and the duress
 *    code does. The workflow then relaunches Clairix from outside: the lock screen
 *    again, never the first run.
 *
 * Without `-e duress` both are skipped: [prepare] sets the lock this flow needs, and
 * step 2 erases the app's data. The scanner is left off: the emulator's GNSS HAL
 * deadlocks when measurement collection is stopped and started again (see the
 * emulator workflow), and the scanner coming back after the wipe is
 * MainActivity.onResume's ordinary path.
 */
@RunWith(AndroidJUnit4::class)
class DuressFlowDeviceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val ui = FreshUi(instr, device)
    private val context: Context get() = instr.targetContext
    private val pkg = "app.clairix"
    private val passcode = "73915824"
    /** Also typed by the workflow (duress-ui.py), which takes it from the step. */
    private val duress = "61849302"
    private val evidence: File get() = File(context.filesDir, "duress-test-evidence.txt")
    private val prefsDir: File get() = File(context.applicationInfo.dataDir, "shared_prefs")
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    /** The offline mesh's channel (comms/mesh/MeshService.kt): listed in Settings, it would say the mesh was used. */
    private val meshChannel = "mesh"

    private fun assumeStep(step: String) =
        assumeTrue("part of the workflow's duress flow; run with -e duress $step", InstrumentationRegistry.getArguments().getString("duress") == step)

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun lockScreenShown(timeoutMs: Long = 5_000): Boolean =
        ui.waitHas(By.text("Enter your passcode to continue").pkg(pkg), timeoutMs)

    private fun firstRunShown(): Boolean =
        ui.has(By.text("Nothing hides.").pkg(pkg)) || ui.has(By.text("Not now").pkg(pkg)) || ui.has(By.textStartsWith("Tour · ").pkg(pkg))

    /** True once "Wrong passcode" shows, false once the app opens, null if neither within [timeoutMs]. */
    private fun refusedOrOpened(timeoutMs: Long): Boolean? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui.has(By.text("Wrong passcode").pkg(pkg))) return true
            if (ui.has(By.text(HomeTab.COMMS.label).pkg(pkg)) || ui.has(By.desc(HomeTab.COMMS.label).pkg(pkg))) return false
            SystemClock.sleep(150)
        }
        return null
    }

    /** What the screen says, for a failure message. */
    private fun screenTexts(): String =
        ui.findAll(By.pkg(pkg)).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.distinct().joinToString(" | ")

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
    fun prepare() {
        assumeStep("prepare")
        AppLock.disable(context)
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(ONBOARDING_DONE_KEY, true).putBoolean(TOUR_SEEN_KEY, true).commit()
        AppLock.setPasscode(context, passcode).getOrThrow()
        AppLock.setDuress(context, duress).getOrThrow()
        evidence.writeText("the timeline, the contacts, the conversations")
        notifications.createNotificationChannel(NotificationChannel(meshChannel, "Offline mesh", NotificationManager.IMPORTANCE_LOW))
        assertTrue("the duress code was not set", AppLock.hasDuress(context))
        assertTrue(evidence.exists())
        assertNotNull(notifications.getNotificationChannel(meshChannel))
    }

    @Test
    fun decoy() {
        assumeStep("decoy")
        // The data is gone; what is left is the decoy: its first run done, and a lock.
        assertFalse("data survived the wipe", evidence.exists())
        assertFalse(File(context.filesDir, Wiper.PENDING_MARKER).exists())
        assertTrue(File(prefsDir, "${AppLock.PREFS_NAME}.xml").readText().contains("verifier"))
        assertTrue(File(prefsDir, "prefs.xml").readText().contains("name=\"$ONBOARDING_DONE_KEY\" value=\"true\""))
        assertFalse("the decoy carries a duress passcode", AppLock.hasDuress(context))
        assertNull("a notification channel from before the wipe survived it", notifications.getNotificationChannel(meshChannel))
        assertFalse("the relaunch's one-time mark outlived the relaunch", File(prefsDir, "${AppLock.PREFS_NAME}.xml").readText().contains("wipe_notice_at"))

        launch()
        assertTrue("the decoy's lock screen did not come up", lockScreenShown(20_000))
        assertFalse("a first-run page showed", firstRunShown())
        // The old passcode is gone with the data; the duress code opens the decoy.
        type(passcode)
        // The verdict is Keystore work, which an emulator can take seconds over:
        // waited for until one outcome or the other shows.
        val refused = refusedOrOpened(15_000)
        assertFalse("the old passcode opened the decoy", refused == false)
        assertTrue("no verdict on the old passcode within 15 s; on screen: ${screenTexts()}", refused == true)
        type(duress)
        assertTrue("the duress code did not open the decoy", appShown())
        assertFalse("a first-run page showed behind the lock", firstRunShown())
    }
}
