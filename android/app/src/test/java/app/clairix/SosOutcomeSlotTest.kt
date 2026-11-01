package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SOS text's outcome across two SOS sheets: a text still being sent for the
 * first SOS when the second sheet opens must neither be reported as the second
 * SOS's text nor stop the second SOS's text from going.
 */
class SosOutcomeSlotTest {

    @Test
    fun oneTextPerSos() {
        val slot = SosOutcomeSlot<String>()
        val ticket = slot.claim("finding")
        assertNotNull(ticket)
        assertNull("a second press during the same SOS does nothing", slot.claim("finding again"))
        assertTrue(slot.post(ticket!!, "sent"))
        assertEquals("sent", slot.value.value)
        assertNull("nor after it went", slot.claim("finding again"))
    }

    @Test
    fun anEarlierSosTextFinishingLateIsNotTheNewSheetsAndDoesNotBlockItsText() {
        val slot = SosOutcomeSlot<String>()
        val first = slot.claim("first: finding")!!
        // The SOS button is held again while the first text is still under way.
        slot.reset()
        assertNull(slot.value.value)
        // The first text finishes before the new SOS is confirmed.
        assertFalse(slot.post(first, "first: sent"))
        assertNull("not shown as the new SOS's text", slot.value.value)
        // The new SOS's text still goes.
        val second = slot.claim("second: finding")
        assertNotNull("the new SOS's text is not blocked", second)
        assertFalse(slot.post(first, "first: sent"))
        assertEquals("second: finding", slot.value.value)
        assertTrue(slot.post(second!!, "second: sent"))
        assertEquals("second: sent", slot.value.value)
    }
}
