package app.clairix.security

import app.clairix.EventKind
import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which channel a platform alert goes out on: someone at the phone as an alarm, a phone setting
 * to check at default importance, chosen by what the alert is about.
 */
class PlatformAlertsTest {

    @Test
    fun someoneAtThePhoneGoesOutAsAnAlarm() {
        val atPhone = listOf(
            EventKind.BIOMETRIC_ENROLLED to Severity.HIGH,   // a fingerprint or face added
            EventKind.UNLOCK_ANOMALY to Severity.HIGH,       // unlocked while you slept, the screen lock changed
            EventKind.UNLOCK_ANOMALY to Severity.MEDIUM,     // unlock recording switched off
            EventKind.PLATFORM to Severity.HIGH,             // stalkerware, a relay certificate nobody trusts
            EventKind.PLATFORM to Severity.CRITICAL,         // an intercepted connection
            EventKind.AUDIO_ROUTE to Severity.MEDIUM,        // a microphone became the call audio, screen off
            EventKind.FOLLOWING to Severity.HIGH,
            EventKind.FOLLOWING to Severity.MEDIUM,
            EventKind.WIFI_ANOMALY to Severity.CRITICAL,
            EventKind.GNSS to Severity.HIGH,
            EventKind.INFRASTRUCTURE to Severity.MEDIUM,
        )
        for ((kind, severity) in atPhone) {
            assertEquals("$kind $severity", AlertChannel.SOMEONE_AT_PHONE, PlatformAlerts.channelFor(kind, severity))
        }
    }

    @Test
    fun phoneSettingsToCheckGoOutAtDefaultImportance() {
        val settings = listOf(
            EventKind.PLATFORM to Severity.MEDIUM,           // discoverable, a proxy, a scanner that cannot hear
            EventKind.SCREEN_RECORDING to Severity.MEDIUM,
            EventKind.CLOCK_DRIFT to Severity.MEDIUM,
            EventKind.CLOCK_DRIFT to Severity.HIGH,
        )
        for ((kind, severity) in settings) {
            assertEquals("$kind $severity", AlertChannel.SETTINGS_TO_CHECK, PlatformAlerts.channelFor(kind, severity))
        }
    }

    @Test
    fun eachChannelHasItsOwnSecretIdAndTheRetiredOneIsNotReused() {
        assertEquals(PlatformAlerts.CHANNEL_SECURITY, PlatformAlerts.channelId(AlertChannel.SOMEONE_AT_PHONE))
        assertEquals(PlatformAlerts.CHANNEL_SETTINGS, PlatformAlerts.channelId(AlertChannel.SETTINGS_TO_CHECK))
        assertNotEquals(PlatformAlerts.CHANNEL_SECURITY, PlatformAlerts.CHANNEL_SETTINGS)
        for (id in listOf(PlatformAlerts.CHANNEL_SECURITY, PlatformAlerts.CHANNEL_SETTINGS)) {
            assertNotEquals("platform_alerts_secret", id)
            assertNotEquals("platform_alerts", id)
            // As every alert channel since they were made secret on the lock screen.
            assertTrue(id, id.endsWith("_secret"))
        }
    }
}
