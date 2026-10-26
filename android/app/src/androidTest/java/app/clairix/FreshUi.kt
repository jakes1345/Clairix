package app.clairix

import android.app.Instrumentation
import android.os.Build
import android.os.SystemClock
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * UiAutomator lookups that always read the screen as it is now.
 *
 * UiAutomator reads windows through the test process's accessibility
 * connection, which caches every node it has fetched and drops them only on
 * the app's accessibility events. Compose 1.7 sends those events only while
 * an accessibility service is enabled (AndroidComposeViewAccessibilityDelegateCompat.isEnabled
 * wants a non-empty getEnabledAccessibilityServiceList), and the test's own
 * connection does not count. So on an emulator with no service on, the
 * cache kept the page that was left for many seconds after the app had
 * drawn the one behind it: CI runs 40259743876, 40259840224 and 40262706497
 * failed or stalled on exactly that, with the app idle and its window
 * already redrawn. The app's own nodes are recomputed whenever they are
 * asked for; only the cache was stale. Every lookup here clears it first
 * (UiAutomation.clearCache, Android 14 on; the CI emulator runs 34).
 */
internal class FreshUi(private val instrumentation: Instrumentation, val device: UiDevice) {

    fun clear() {
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    }

    fun find(selector: BySelector): UiObject2? { clear(); return device.findObject(selector) }

    fun has(selector: BySelector): Boolean { clear(); return device.hasObject(selector) }

    fun findAll(selector: BySelector): List<UiObject2> { clear(); return device.findObjects(selector) }

    /** The first match within [timeoutMs], looked up afresh every [POLL_MS]; null if none showed. */
    fun waitFor(selector: BySelector, timeoutMs: Long): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            find(selector)?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_MS)
        }
    }

    fun waitHas(selector: BySelector, timeoutMs: Long): Boolean = waitFor(selector, timeoutMs) != null

    private companion object {
        const val POLL_MS = 150L
    }
}
