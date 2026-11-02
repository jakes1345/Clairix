package app.clairix.analysis

import android.media.AudioDeviceInfo
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.analysis.AudioRouteMonitor.Facts
import app.clairix.analysis.AudioRouteMonitor.Mic
import app.clairix.analysis.AudioRouteMonitor.Route
import app.clairix.analysis.AudioRouteMonitor.Verdict
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * When a microphone that attaches itself is alerted: never for the owner's headset or a paired
 * accessory seen on an earlier day, never for what was connected when scanning started (listed
 * instead), MEDIUM only when it becomes the call audio with the screen off, LOW otherwise.
 */
class AudioRouteMonitorTest {

    private fun facts(
        atStart: Boolean = false,
        acknowledged: Boolean = false,
        known: Boolean = false,
        busy: Boolean = false,
        route: Boolean = false,
        screenOn: Boolean = true
    ) = Facts(atStart, acknowledged, known, busy, route, screenOn)

    @Test
    fun aFreshMicrophoneWithNothingPlayingIsLowUnlessItBecomesTheCallAudioWithTheScreenOff() {
        assertEquals(Verdict.LOW, AudioRouteMonitor.verdict(facts()))
        assertEquals(Verdict.MEDIUM, AudioRouteMonitor.verdict(facts(route = true, screenOn = false)))
        // The call audio with the screen on is someone using the phone: LOW.
        assertEquals(Verdict.LOW, AudioRouteMonitor.verdict(facts(route = true, screenOn = true)))
        // The screen off but not the call audio: LOW.
        assertEquals(Verdict.LOW, AudioRouteMonitor.verdict(facts(route = false, screenOn = false)))
    }

    @Test
    fun whatWasConnectedWhenScanningStartedIsListedNotAlerted() {
        assertEquals(Verdict.LIST, AudioRouteMonitor.verdict(facts(atStart = true)))
        assertEquals(Verdict.LIST, AudioRouteMonitor.verdict(facts(atStart = true, route = true, screenOn = false)))
        assertEquals(Verdict.LIST, AudioRouteMonitor.verdict(facts(atStart = true, busy = true)))
    }

    @Test
    fun theOwnersHeadsetAndAKnownPairedAccessoryRaiseNothing() {
        for (f in listOf(
            facts(acknowledged = true),
            facts(acknowledged = true, route = true, screenOn = false),
            facts(acknowledged = true, atStart = true),
            facts(known = true),
            facts(known = true, route = true, screenOn = false),
            facts(known = true, atStart = true),
        )) assertEquals("$f", Verdict.NONE, AudioRouteMonitor.verdict(f))
    }

    @Test
    fun aMicrophoneThatConnectsWhileAudioIsInUseIsAHeadset() {
        assertEquals(Verdict.NONE, AudioRouteMonitor.verdict(facts(busy = true)))
        assertEquals(Verdict.NONE, AudioRouteMonitor.verdict(facts(busy = true, route = true, screenOn = false)))
    }

    @Test
    fun theCallAudioMovingToAMicrophoneLaterIsMediumOnlyWithTheScreenOffAndNoCall() {
        fun up(acknowledged: Boolean = false, known: Boolean = false, already: Boolean = false, screenOn: Boolean = false, callUp: Boolean = false) =
            AudioRouteMonitor.escalates(acknowledged, known, already, communicationRoute = true, screenOn = screenOn, callUp = callUp)
        assertTrue(up())
        assertFalse("screen on", up(screenOn = true))
        assertFalse("a call is what the call audio is for", up(callUp = true))
        assertFalse("the owner's headset", up(acknowledged = true))
        assertFalse("a known paired accessory", up(known = true))
        assertFalse("reported at MEDIUM already", up(already = true))
        assertFalse(AudioRouteMonitor.escalates(false, false, false, communicationRoute = false, screenOn = false, callUp = false))
    }

    @Test
    fun aPairedAccessoryIsKnownFromTheDayAfterItFirstConnected() {
        val zone = ZoneId.of("Europe/London")
        fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        val now = at("2026-11-01T08:30:00")
        assertTrue(AudioRouteMonitor.seenOnEarlierDay(at("2026-10-31T23:59:00"), now, zone))
        assertTrue(AudioRouteMonitor.seenOnEarlierDay(at("2026-09-02T12:00:00"), now, zone))
        // First connected this morning: the same day, not yet known.
        assertFalse(AudioRouteMonitor.seenOnEarlierDay(at("2026-11-01T00:01:00"), now, zone))
        assertFalse(AudioRouteMonitor.seenOnEarlierDay(null, now, zone))
        // A clock set back is no history.
        assertFalse(AudioRouteMonitor.seenOnEarlierDay(at("2026-11-02T07:00:00"), now, zone))
    }

    @Test
    fun theCallAudioIsMatchedToTheMicrophoneByAddressOrName() {
        val buds = Mic(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB:CC:DD:EE:FF", "Buds")
        assertTrue(AudioRouteMonitor.matches(buds, Route(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB:CC:DD:EE:FF", "Buds")))
        assertFalse(AudioRouteMonitor.matches(buds, Route(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "11:22:33:44:55:66", "Buds")))
        assertFalse(AudioRouteMonitor.matches(buds, Route(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, null, "Phone")))
        // No address on one side: the name decides.
        assertTrue(AudioRouteMonitor.matches(buds, Route(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null, "Buds")))
        val le = Mic(AudioDeviceInfo.TYPE_BLE_HEADSET, "AA:BB:CC:DD:EE:01", "LE Buds")
        assertTrue(AudioRouteMonitor.matches(le, Route(AudioDeviceInfo.TYPE_BLE_HEADSET, "AA:BB:CC:DD:EE:01", "LE Buds")))

        val usb = Mic(AudioDeviceInfo.TYPE_USB_HEADSET, null, "USB-C Headset")
        assertFalse(usb.bluetooth)
        assertTrue(AudioRouteMonitor.matches(usb, Route(AudioDeviceInfo.TYPE_USB_HEADSET, null, "USB-C Headset")))
        assertFalse(AudioRouteMonitor.matches(usb, Route(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null, "USB-C Headset")))
    }

    @Test
    fun aMicrophoneIsRememberedByItsAddressOrByItsKindAndName() {
        assertEquals("bt:AA:BB:CC:DD:EE:FF", Mic(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB:CC:DD:EE:FF", "Buds").key)
        assertEquals("name:22:USB-C Headset", Mic(AudioDeviceInfo.TYPE_USB_HEADSET, null, "USB-C Headset").key)
    }

    private fun store(file: File) = Store(file, Dispatchers.Unconfined, TestStoreCipher)

    @Test
    fun theRecordKeepsTheOwnersMarksAndEachPairedDevicesFirstConnection() {
        val file = File(Files.createTempDirectory("audio").toFile().apply { deleteOnExit() }, AudioRouteMonitor.FILE)
        val buds = "AA:BB:CC:DD:EE:FF"
        val stranger = "11:22:33:44:55:66"
        val record = AccessoryRecord(store(file))
        assertTrue(record.marks().isEmpty())
        record.mark("bt:$buds", "Pixel Buds", 1_000L)
        record.noteConnected(listOf(buds, stranger), bonded = setOf(buds), now = 2_000L)
        // Only paired devices are kept.
        assertEquals(2_000L, record.firstConnected(buds))
        assertNull(record.firstConnected(stranger))
        // A later connection keeps the first.
        record.noteConnected(listOf(buds), bonded = setOf(buds), now = 9_000L)
        assertEquals(2_000L, record.firstConnected(buds))
        // Bluetooth off reads as no paired devices: nothing is forgotten for it.
        record.noteConnected(emptyList(), bonded = emptySet(), now = 9_500L)
        assertEquals(2_000L, record.firstConnected(buds))

        val reopened = AccessoryRecord(store(file))
        assertEquals("Pixel Buds", reopened.marks()["bt:$buds"]?.name)
        assertEquals(1_000L, reopened.marks()["bt:$buds"]?.at)
        assertEquals(2_000L, reopened.firstConnected(buds))

        // A device no longer paired is dropped the next time the paired list is read.
        reopened.noteConnected(emptyList(), bonded = setOf(stranger), now = 10_000L)
        assertNull(reopened.firstConnected(buds))
        // Undo takes the mark back.
        reopened.unmark("bt:$buds")
        assertTrue(AccessoryRecord(store(file)).marks().isEmpty())
    }

    @Test
    fun clearAllDataForgetsFirstConnectionsAndKeepsTheOwnersMarks() {
        val file = File(Files.createTempDirectory("audio").toFile().apply { deleteOnExit() }, AudioRouteMonitor.FILE)
        val buds = "AA:BB:CC:DD:EE:FF"
        val record = AccessoryRecord(store(file))
        record.mark("bt:$buds", "Pixel Buds", 1_000L)
        record.noteConnected(listOf(buds), bonded = setOf(buds), now = 2_000L)
        record.clearSeen()
        val reopened = AccessoryRecord(store(file))
        assertNull(reopened.firstConnected(buds))
        assertEquals("Pixel Buds", reopened.marks()["bt:$buds"]?.name)
        // Seen again, it is a first connection again.
        reopened.noteConnected(listOf(buds), bonded = setOf(buds), now = 5_000L)
        assertEquals(5_000L, reopened.firstConnected(buds))
    }
}
