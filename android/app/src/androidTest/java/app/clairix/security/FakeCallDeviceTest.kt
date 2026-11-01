package app.clairix.security

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fake call on a real device, in the scene it exists for: scheduled and
 * left with another task in front, it must be heard before anyone taps
 * anything. The old ring lived only in the call screen, which Android does not
 * open over another app, so that scene got a silent card.
 */
@RunWith(AndroidJUnit4::class)
class FakeCallDeviceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val context: Context get() = instr.targetContext
    private val pkg = "app.clairix"
    private val audio: AudioManager get() = context.getSystemService(AudioManager::class.java)
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /**
     * Whether a player tagged as a ringtone is playing anywhere on the phone, as
     * the platform's audio service sees it (it lists only started players to an
     * app): sound actually going out, not Clairix's belief that it is.
     */
    private fun ringtoneAudible(): Boolean =
        audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }

    private fun card(): StatusBarNotification? = notifications.activeNotifications.firstOrNull { it.id == FakeCall.NOTIFICATION_ID }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) return false
            SystemClock.sleep(250)
        }
        return true
    }

    @Before
    fun setUp() {
        // What the Fake call page asks the owner for, granted the way adb would.
        if (Build.VERSION.SDK_INT >= 33) runCatching { instr.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS) }
        shell("appops set $pkg SCHEDULE_EXACT_ALARM allow")
        if (Build.VERSION.SDK_INT >= 34) shell("appops set $pkg USE_FULL_SCREEN_INTENT allow")
        runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        instr.runOnMainSync { FakeCall.cancel(context) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync { FakeCall.cancel(context) }
    }

    @Test
    fun aScheduledCallRingsWithAnotherTaskInFrontBeforeAnyTap() {
        assumeTrue("the phone must be able to ring aloud", audio.ringerMode == AudioManager.RINGER_MODE_NORMAL)
        assumeTrue("the ring volume is at zero", audio.getStreamVolume(AudioManager.STREAM_RING) > 0)
        assumeFalse("something else is already ringing", ringtoneAudible())
        val readiness = FakeCall.readiness(context)
        assertTrue("the page would show something missing: ${readiness.missing}", readiness.missing.isEmpty())
        assertFalse(FakeCallRinger.isRinging)

        // The launcher in front: Clairix is in the background when the alarm lands.
        device.pressHome()
        device.waitForIdle(2_000)
        val scheduledAt = System.currentTimeMillis()
        instr.runOnMainSync { FakeCall.schedule(context, FakeCall.DELAY_30S, null) }
        val due = scheduledAt + FakeCall.DELAY_30S
        val pending = FakeCall.pendingAt(context)
        assertNotNull("nothing pending after scheduling", pending)
        assertTrue("pending at $pending, expected about $due", pending!! in due..due + 2_000L)

        // An exact alarm lands on the second; the service then has a moment to start.
        val waitMs = due + 15_000L - System.currentTimeMillis()
        assertTrue("the call did not ring within 15 s of its time", waitUntil(waitMs) { FakeCallRinger.isRinging })
        val rang = System.currentTimeMillis()
        assertTrue("it rang early, ${due - rang} ms before its time", rang >= due - 1_000L)

        // Audible to the platform, and nothing has been tapped.
        assertTrue("no ringtone is playing", waitUntil(5_000) { ringtoneAudible() })

        // The card is up as the ringing service's own notification, on the quiet
        // channel: the ring comes from the service, and the card adds no second one.
        assertTrue("no incoming-call card in the shade", waitUntil(3_000) { card() != null })
        val shown = card()!!.notification
        assertTrue("the card is not the ringing service's notification", shown.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals(FakeCall.CHANNEL, shown.channelId)

        // Decline, as the card's button does: the ring and the card both go.
        instr.runOnMainSync { FakeCall.cancel(context) }
        assertFalse(FakeCallRinger.isRinging)
        assertTrue("the ringtone kept playing after decline", waitUntil(3_000) { !ringtoneAudible() })
        assertTrue("the card stayed in the shade after decline", waitUntil(3_000) { card() == null })
    }
}
