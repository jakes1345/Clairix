package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camera finding the owner is looking at raises no notification: an optical
 * or magnetic one always (they can only be made with the sweep screen up), any
 * other while the sweep screen is resumed. The alert still goes on the timeline
 * and the alerts list, marked silent.
 */
class CameraAlertSilenceTest {

    private fun finding(id: String, method: CameraFindMethod) = CameraFinding(
        id = id, ts = 1L, method = method, confidence = 0.9f,
        location = "here", detail = "detail", lat = null, lon = null
    )

    private fun silent(id: String): Boolean = Registry.alerts.value.first { it.id == "camera|$id" }.silent

    @Test
    fun opticalAndMagneticFindingsAreSilentWhereverTheOwnerIs() {
        HiddenCameraScanner.sweepScreenResumed(false)
        HiddenCameraScanner.report(finding("optical|glint|s1", CameraFindMethod.LENS_GLINT))
        HiddenCameraScanner.report(finding("optical|ir|s1", CameraFindMethod.IR_ILLUMINATOR))
        HiddenCameraScanner.report(finding("mag|s1", CameraFindMethod.MAGNETIC))
        assertTrue(silent("optical|glint|s1"))
        assertTrue(silent("optical|ir|s1"))
        assertTrue(silent("mag|s1"))
    }

    @Test
    fun anAlertIsFiledAsACameraEventWithWhereThePhoneWas() {
        HiddenCameraScanner.report(finding("net|10.0.0.9|s3", CameraFindMethod.NETWORK).copy(lat = 51.5, lon = -0.12))
        val alert = Registry.alerts.value.first { it.id == "camera|net|10.0.0.9|s3" }
        assertEquals(EventKind.CAMERA, alert.kind)
        assertEquals(51.5, alert.lat!!, 0.0)
        assertEquals(-0.12, alert.lon!!, 0.0)
    }

    @Test
    fun anIrLightWaitingForTheOwnersAnswerRaisesNoAlert() {
        HiddenCameraScanner.report(finding("optical|ir|s4", CameraFindMethod.IR_ILLUMINATOR).copy(needsConfirmation = true))
        assertTrue(Registry.alerts.value.none { it.id == "camera|optical|ir|s4" })
    }

    @Test
    fun aNetworkFindingIsSilentOnlyWhileTheSweepScreenIsUp() {
        HiddenCameraScanner.sweepScreenResumed(true)
        HiddenCameraScanner.report(finding("net|10.0.0.7|s2", CameraFindMethod.NETWORK))
        assertTrue(silent("net|10.0.0.7|s2"))

        HiddenCameraScanner.sweepScreenResumed(false)
        HiddenCameraScanner.report(finding("net|10.0.0.8|s2", CameraFindMethod.NETWORK))
        assertFalse(silent("net|10.0.0.8|s2"))
        HiddenCameraScanner.report(finding("wifi|setup|s2", CameraFindMethod.WIFI_SETUP_AP))
        assertFalse(silent("wifi|setup|s2"))
    }
}
