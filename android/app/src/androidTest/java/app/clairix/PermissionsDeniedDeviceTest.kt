package app.clairix

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * First run and the Scan hub with runtime permissions refused, as an owner who
 * says no to every prompt leaves the app. Every other device test runs with the
 * permissions granted (the app is installed with `adb install -g`), so the paths
 * a refusal takes were never run.
 *
 * Revoking a permission ends the app's process, which is where an instrumentation
 * runs, so the permissions are taken away by the emulator workflow before the run
 * (`pm revoke`, on an install made without -g) and named in the argument `denied`
 * (comma-separated); this test checks they are refused, then walks onboarding and
 * every Scan category, answering every permission prompt "Don't allow". Fails on a
 * crash, an "isn't responding" dialog, the app leaving the foreground, or a screen
 * that never shows. Runs only when the workflow passes `-e permissions denied`.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsDeniedDeviceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val ui = FreshUi(instr, device)
    private val pkg = "app.clairix"
    private val target: Context get() = instr.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val shots: File by lazy { File(target.getExternalFilesDir(null), "denied").apply { mkdirs() } }
    private val label: String by lazy { args.getString("label") ?: "all" }
    private var shotIndex = 0

    private companion object {
        const val BACK_PATIENCE_MS = 10_000L
        /** System apps that show a dialog over Clairix on its behalf; Android 12 shows the Wi-Fi suggestion prompt itself ("android"). */
        val SYSTEM_PROMPTS = setOf("com.google.android.wifi.dialog", "com.android.wifi.dialog", "com.android.permissioncontroller", "android")
        val DENY: Pattern = Pattern.compile("(?i)^(don.t allow|deny|no|not now|cancel)$")
        val DENY_IDS = listOf(
            "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
            "com.android.permissioncontroller:id/permission_deny_button",
        )
        val CATEGORIES = listOf("Trackers", "Hidden cameras", "Cell towers", "GPS", "Ultrasound", "Wi-Fi", "NFC", "Phone check")
        /** Any category's row: the hub is showing, however far it is scrolled. */
        val HUB: Pattern = Pattern.compile(CATEGORIES.joinToString("|") { "^" + Pattern.quote(it) + "$" })
    }

    @Before
    fun setUp() {
        assumeTrue("run by the emulator workflow's denied-permissions step", args.getString("permissions") == "denied")
        val denied = args.getString("denied").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("no permissions named as denied", denied.isNotEmpty())
        for (p in denied) {
            // A permission this Android version does not have (POST_NOTIFICATIONS before 13) is refused as well.
            assertEquals("$p was not taken away before the run", PackageManager.PERMISSION_DENIED, target.checkSelfPermission(p))
        }
        device.pressHome()
        val launch = target.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        target.startActivity(launch)
        assertTrue("app did not come up", ui.waitHas(By.pkg(pkg).depth(0), 20_000))
    }

    private fun shot(name: String) {
        device.waitForIdle(2_000)
        val f = File(shots, "%s-%02d-%s.png".format(label, shotIndex++, name.replace(Regex("[^A-Za-z0-9]+"), "-")))
        device.takeScreenshot(f)
        if (name.startsWith("MISSING") || name.startsWith("LEFT") || name.startsWith("FROZEN")) {
            runCatching { ui.clear(); device.dumpWindowHierarchy(File(shots, f.nameWithoutExtension + ".xml")) }
        }
    }

    /** Refuses whatever the system asks on the app's behalf, then fails if the app crashed, hung or left. */
    private fun alive(step: String) {
        device.waitForIdle(2_000)
        // Several prompts can come one after another (location, then nearby devices, then notifications).
        var prompts = 0
        while (prompts++ < 6) {
            if (ui.has(By.textContains("keeps stopping")) || ui.has(By.textContains("has stopped"))) {
                shot("CRASH-$step"); fail("Clairix crashed at: $step")
            }
            if (ui.has(By.textContains("isn't responding"))) {
                shot("ANR-$step"); fail("Clairix stopped responding at: $step")
            }
            val current = device.currentPackageName
            if (current == pkg || current !in SYSTEM_PROMPTS) break
            shot("prompt-refused-$step")
            val deny = DENY_IDS.firstNotNullOfOrNull { ui.find(By.res(it)) } ?: ui.find(By.pkg(current).text(DENY))
            if (deny != null) deny.click() else device.pressBack()
            ui.waitHas(By.pkg(pkg).depth(0), 5_000)
            device.waitForIdle(2_000)
        }
        if (device.currentPackageName != pkg) { shot("LEFT-$step"); fail("Clairix left the foreground at: $step (now ${device.currentPackageName})") }
    }

    private fun tapIfShown(selector: BySelector, step: String, timeoutMs: Long = 5_000): Boolean {
        ui.waitFor(selector, timeoutMs) ?: return false
        repeat(10) {
            try {
                val bounds = ui.find(selector)?.visibleBounds
                if (bounds != null && !bounds.isEmpty) {
                    device.click(bounds.centerX(), bounds.centerY())
                    alive(step)
                    return true
                }
            } catch (_: StaleObjectException) {
                // Looked up again on the next try.
            }
            device.waitForIdle(500)
        }
        return false
    }

    /** Scrolls the list a third of a screen; false at its end, or when nothing on screen scrolls. */
    private fun scrollOnce(direction: Direction): Boolean {
        val list = runCatching { ui.find(By.scrollable(true)) }.getOrNull() ?: return false
        val moved = runCatching { list.scroll(direction, 0.3f) }.getOrDefault(false)
        device.waitForIdle(1_000)
        return moved
    }

    /** Scrolls the hub's list until [t] shows, then taps it. */
    private fun tapScrolling(t: String) {
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            var swipes = 0
            while (swipes++ < 30) {
                if (tapIfShown(By.text(t), t, timeoutMs = 500)) return
                if (!scrollOnce(direction)) break
            }
        }
        if (tapIfShown(By.text(t), t, timeoutMs = 500)) return
        shot("MISSING-$t"); fail("Could not find \"$t\" by scrolling")
    }

    /** The tour, if it opened: left at once, by Skip or, without one, by stepping to the end. */
    private fun leaveTheTour() {
        if (!ui.waitHas(By.text("Where everything is"), 15_000)) return
        if (tapIfShown(By.text("Skip"), "tour skip")) return
        var pages = 0
        while (pages++ < 60) if (!tapIfShown(By.text("Next"), "tour next", timeoutMs = 3_000)) break
        tapIfShown(By.text("Done"), "tour done")
    }

    @Test
    fun firstRunAndTheScanHubSurviveEveryRefusal() {
        // Onboarding, with scanning started: the app asks for what it needs and is refused.
        tapIfShown(By.text("Skip"), "onboarding skip", timeoutMs = 15_000)
        tapIfShown(By.text("Start scanning"), "start scanning")
        alive("after onboarding")
        shot("after-onboarding")

        leaveTheTour()

        // Every Scan category with its permissions refused: each must open and say what it cannot do, not crash.
        for (c in CATEGORIES) {
            tapScrolling(c)
            device.waitForIdle(3_000)
            alive("scan $c")
            shot("scan-$c")
            device.pressBack()
            alive("back from $c")
            if (!ui.waitHas(By.text(HUB), BACK_PATIENCE_MS)) {
                shot("FROZEN-$c"); fail("Did not come back to the Scan hub from \"$c\"")
            }
        }
        alive("end")
    }
}
