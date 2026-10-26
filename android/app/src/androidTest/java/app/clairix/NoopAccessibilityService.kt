package app.clairix

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * An accessibility service that does nothing, enabled on the test emulators
 * (`settings put secure enabled_accessibility_services`) by the emulator
 * workflow.
 *
 * Compose 1.7 sends the accessibility events that keep UiAutomator's node
 * cache current only while some accessibility service is enabled (see
 * FreshUi). From Android 14 the tests clear that cache themselves; before it
 * there is no public way to, so on those versions this service being enabled
 * is what makes Compose report each change, exactly as it does for someone
 * using TalkBack.
 */
class NoopAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
