package app.clairix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.clairix.comms.PairingCode
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the installed app the way a person does: through onboarding, the whole tour,
 * every Scan category and tracker tool, every tab and Settings, with a screenshot of
 * each. Fails on a crash, an "isn't responding" dialog, or a screen that never shows.
 * Screenshots land in the app's external files dir under smoke/, which the workflow pulls.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val ui = FreshUi(instr, device)
    private val pkg = "app.clairix"
    private val target: Context get() = instr.targetContext
    private val shots: File by lazy { File(target.getExternalFilesDir(null), "smoke").apply { mkdirs() } }
    private var shotIndex = 0

    @Before
    fun setUp() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        perms.forEach { runCatching { instr.uiAutomation.grantRuntimePermission(pkg, it) } }
        device.pressHome()
        val launch = target.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        target.startActivity(launch)
        assertTrue("app did not come up", ui.waitHas(By.pkg(pkg).depth(0), 20_000))
    }

    private fun shot(name: String) {
        device.waitForIdle(2_000)
        val f = File(shots, "%02d-%s.png".format(shotIndex++, name.replace(Regex("[^A-Za-z0-9]+"), "-")))
        device.takeScreenshot(f)
        // Secure screens (Comms) come out black, so a failure also keeps the
        // window hierarchy: what was on screen, as text.
        if (name.startsWith("MISSING") || name.startsWith("LEFT") || name.startsWith("STALE") || name.startsWith("BACK-") || name.startsWith("FROZEN")) {
            runCatching { ui.clear(); device.dumpWindowHierarchy(File(shots, f.nameWithoutExtension + ".xml")) }
        }
    }

    /** Fails if the app has crashed, stopped responding or left the foreground. */
    private fun alive(step: String) {
        device.waitForIdle(2_000)
        if (ui.has(By.textContains("keeps stopping")) || ui.has(By.textContains("has stopped"))) {
            shot("CRASH-$step"); fail("Clairix crashed at: $step")
        }
        if (ui.has(By.textContains("isn't responding"))) {
            shot("ANR-$step"); fail("Clairix stopped responding at: $step")
        }
        // A system dialog the app asked for is not a failure: a permission, or
        // Android asking whether Clairix may suggest Wi-Fi networks (the Karma trap's
        // decoy network, added when scanning starts). Answer it as an owner would and
        // carry on; the screenshot shows it was there.
        ui.find(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"))?.click()
        ui.find(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()
        val current = device.currentPackageName
        if (current != pkg && current in SYSTEM_PROMPTS) {
            shot("system-prompt-$step")
            val allow = ui.find(By.pkg(current).text(ALLOW)) ?: ui.find(By.pkg(current).clickable(true).text(ALLOW))
            if (allow != null) allow.click() else device.pressBack()
            ui.waitHas(By.pkg(pkg).depth(0), 5_000)
            device.waitForIdle(2_000)
        }
        if (device.currentPackageName != pkg) { shot("LEFT-$step"); fail("Clairix left the foreground at: $step (now ${device.currentPackageName})") }
    }

    private companion object {
        /** How long a Back may take to show the screen behind on CI's emulator (see backTo). */
        const val BACK_PATIENCE_MS = 10_000L
        /**
         * System apps that show a dialog over Clairix on its behalf. Android 12
         * shows the Wi-Fi suggestion prompt from the framework itself ("android").
         */
        val SYSTEM_PROMPTS = setOf("com.google.android.wifi.dialog", "com.android.wifi.dialog", "com.android.permissioncontroller", "android")
        val ALLOW: java.util.regex.Pattern = java.util.regex.Pattern.compile("(?i)^(allow|yes|ok|while using the app|only this time)$")
    }

    private fun text(t: String, timeout: Long = 8_000): UiObject2? = ui.waitFor(By.text(t), timeout)

    private fun tap(t: String, step: String = t) {
        text(t) ?: run { shot("MISSING-$step"); fail("No \"$t\" on screen at: $step"); return }
        if (!clickFresh(By.text(t))) { shot("STALE-$step"); fail("\"$t\" kept redrawing before it could be tapped at: $step") }
        alive(step)
    }

    /**
     * Taps the centre of what [selector] finds, read from a fresh lookup each
     * try. Live screens (Trackers redraws as scan results arrive) change the
     * window's content often enough that UiAutomator's handle on a button goes
     * stale between finding it and clicking it, though the button itself
     * never moves; its position, read and used at once, does not go stale.
     */
    private fun clickFresh(selector: BySelector, tries: Int = 10): Boolean {
        repeat(tries) {
            try {
                val bounds = ui.find(selector)?.visibleBounds
                if (bounds != null && !bounds.isEmpty) {
                    device.click(bounds.centerX(), bounds.centerY())
                    return true
                }
            } catch (_: StaleObjectException) {
                // Found again on the next try.
            }
            device.waitForIdle(500)
        }
        return false
    }

    /**
     * Scrolls the main list until [t] is on screen, then taps it. Settings is a
     * long list (its About section, with "Take the tour", is near the bottom),
     * so this goes up to 30 swipes.
     */
    private fun tapScrolling(t: String) {
        repeat(30) {
            ui.find(By.text(t))?.let {
                try { it.click(); alive(t); return } catch (_: StaleObjectException) { device.waitForIdle(1_000) }
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            device.waitForIdle(1_000)
        }
        shot("MISSING-$t"); fail("Could not find \"$t\" by scrolling")
    }

    private fun back(step: String) { device.pressBack(); alive("back from $step") }

    /**
     * Back from [step] to a screen with [expect] on it, pressed once: run
     * 40258058822 pressed a second time when the first seemed not to take, and
     * closed the app. What looked like a slow Back was UiAutomator's node
     * cache (see FreshUi); read afresh, the screen behind shows at once, and
     * [BACK_PATIENCE_MS] is only room for a slow emulator.
     */
    private fun backTo(step: String, expect: BySelector) {
        back(step)
        if (ui.waitHas(expect, BACK_PATIENCE_MS)) return
        shot("FROZEN-$step")
        fail("Clairix did not leave \"$step\" within ${BACK_PATIENCE_MS / 1000} s of Back")
    }

    /** Scrolls the main list until [selector] is on screen; null if 30 swipes do not bring it. */
    private fun scrollTo(selector: BySelector): UiObject2? {
        repeat(30) {
            ui.find(selector)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            device.waitForIdle(1_000)
        }
        return null
    }

    /**
     * An invite link as Android hands it over (finding 30): https://clairix.app/i,
     * the invite in the fragment, naming a relay that is not on the signed list.
     * It fills in the setup, warns about the relay, and Register waits until the
     * owner says they know who runs it. Nothing is registered: the invite is
     * cleared again.
     */
    private fun inviteLinkToAnUnlistedRelay() {
        val code = PairingCode(
            relayUrl = "https://relay.unlisted.example", number = "482913605", ed25519 = "ed", curve25519 = "curve",
            sealing = "seal", signature = "sig", name = "Smoke", invite = "AbCdEfGhIjKlMnOpQrStUv"
        )
        target.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.inviteLink())).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        alive("invite link")
        if (text("Join with an invite", 10_000) == null) { shot("MISSING-invite"); fail("The invite link did not open the setup") }
        if (scrollTo(By.text("A relay not on Clairix's list")) == null) { shot("MISSING-invite-warning"); fail("No warning for a relay off the list") }
        if (scrollTo(By.text("Register").enabled(false)) == null) { shot("MISSING-register-disabled"); fail("Register was not waiting for the owner") }
        tapScrolling("I know who runs this relay")
        if (scrollTo(By.text("Register").enabled(true)) == null) { shot("MISSING-register-enabled"); fail("Register stayed off after the owner confirmed") }
        tapScrolling("Use a relay URL and secret instead")
        if (ui.waitFor(By.text("Have an invite?"), 5_000) == null && scrollTo(By.text("Have an invite?")) == null) {
            shot("MISSING-invite-cleared"); fail("The invite was not cleared")
        }
    }

    @Test
    fun everyScreenOpensWithoutCrashing() {
        // Onboarding.
        text("Skip", 15_000)?.let { it.click(); alive("onboarding skip") }
        text("Start scanning", 5_000)?.let {
            shot("onboarding-last")
            // Scanning can be left for later: the last page offers that too.
            assertTrue("no Not now on the last page", ui.has(By.text("Not now")))
            it.click(); alive("start scanning")
        }

        // The tour, every page.
        assertTrue("the tour did not open", text("Where everything is", 15_000) != null)
        shot("tour-index")
        // Step by the tour's own counter ("Tour · 3 of 25"), waiting for each page
        // to settle: while the pager slides onto the last page, Next is still on
        // screen for a moment before it turns into Done.
        val counter = ui.find(By.textStartsWith("Tour · 1 of "))?.text ?: run { shot("MISSING-tour-counter"); fail("No tour page counter"); return }
        val total = counter.substringAfterLast(' ').toInt()
        for (page in 2..total) {
            tap("Next", "tour page $page")
            if (!ui.waitHas(By.text("Tour · $page of $total"), 5_000)) {
                shot("MISSING-tour-page-$page"); fail("The tour did not reach page $page of $total")
            }
            if (page % 4 == 2) shot("tour-page-$page")
        }
        tap("Done", "tour done")

        // Every Scan category, and the tracker tools.
        val categories = listOf("Trackers", "Hidden cameras", "Cell towers", "GPS", "Ultrasound", "Wi-Fi", "NFC", "Phone check")
        for (c in categories) {
            tapScrolling(c)
            device.waitForIdle(3_000)
            shot("scan-$c")
            if (c == "Trackers") {
                for (tool in listOf("Find a tracker", "Self-test beacon", "Bluetooth exposure")) {
                    val b = ui.waitFor(By.text(tool), 5_000)
                        ?: run {
                            // The row scrolls sideways; the last button may be off screen.
                            device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 4, device.displayWidth / 5, device.displayHeight / 4, 20)
                            ui.waitFor(By.text(tool), 3_000)
                        }
                    if (b == null) { shot("MISSING-$tool"); fail("No tracker tool \"$tool\"") }
                    if (!clickFresh(By.text(tool))) { shot("STALE-$tool"); fail("\"$tool\" kept redrawing before it could be tapped") }
                    alive(tool)
                    device.waitForIdle(3_000)
                    shot("tool-$tool")
                    back(tool)
                }
                tap("Map", "trackers map"); shot("trackers-map"); tap("Nearby", "trackers nearby")
            }
            if (c == "Phone check") {
                for (sub in listOf("Stalkerware", "Certificates", "Health")) { tap(sub, "phone $sub"); device.waitForIdle(3_000); shot("phone-$sub") }
            }
            back(c)
        }

        // The other tabs, and the Comms menu's pages (opened and left; nothing is sent or wiped).
        for (tab in listOf("Comms", "Drive", "Activity")) {
            tap(tab, "tab $tab"); device.waitForIdle(3_000); shot("tab-$tab")
            if (tab == "Comms") {
                // Several rounds: going in and back out of these pages has failed
                // now and then, and one round seldom shows it.
                for (round in 1..5) for (page in listOf("Fake call", "Duress phrase", "Offline mesh", "Wipe Clairix")) {
                    val more = ui.waitFor(By.desc("More options"), 5_000) ?: run { shot("MISSING-menu"); fail("No Comms menu"); return }
                    more.click(); alive("comms menu")
                    tap(page, "comms $page"); device.waitForIdle(2_000)
                    if (round == 1) shot("comms-$page")
                    backTo(page, By.desc("More options"))
                }
                inviteLinkToAnUnlistedRelay()
            }
        }
        for (sub in listOf("Patterns", "Unlocks", "Timeline")) { tap(sub, "activity $sub"); shot("activity-$sub") }
        tap("Drive", "tab Drive again")
        for (sub in listOf("Radio", "Vehicle", "Navigate")) { tap(sub, "drive $sub"); device.waitForIdle(3_000); shot("drive-$sub") }

        // Settings, and the tour from there.
        ui.find(By.desc("Settings"))?.click() ?: fail("No Settings button")
        alive("settings"); shot("settings")
        tapScrolling("Take the tour")
        assertTrue("the tour did not reopen from Settings", text("Where everything is") != null)
        tap("Skip", "tour skip")
        alive("end")
    }
}
