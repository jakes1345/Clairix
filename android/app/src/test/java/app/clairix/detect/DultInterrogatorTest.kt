package app.clairix.detect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DULT wire format, against draft-ietf-dult-accessory-protocol tables 4 and 18. */
class DultInterrogatorTest {

    private fun le(vararg words: Int): ByteArray =
        words.flatMap { listOf((it and 0xFF).toByte(), ((it shr 8) and 0xFF).toByte()) }.toByteArray()

    private val empty = DultResult("AA:BB:CC:DD:EE:FF")

    @Test
    fun `opcodes go out little-endian`() {
        assertArrayEquals(byteArrayOf(0x00, 0x03), DultInterrogator.encode(DultInterrogator.OP_SOUND_START))
        assertArrayEquals(byteArrayOf(0x04, 0x00), DultInterrogator.encode(DultInterrogator.OP_GET_MANUFACTURER_NAME))
        assertArrayEquals(byteArrayOf(0x04, 0x04), DultInterrogator.encode(DultInterrogator.OP_GET_IDENTIFIER))
    }

    @Test
    fun `an information response is the request opcode with bit 11 set`() {
        val op = DultInterrogator.OP_GET_MANUFACTURER_NAME
        val answer = DultInterrogator.answer(op, le(0x0804) + "Apple Inc.".toByteArray())
        assertNotNull(answer)
        assertEquals("Apple Inc.", DultInterrogator.apply(op, answer!!, empty).manufacturer)
        // The model name's response does not answer the manufacturer request.
        assertNull(DultInterrogator.answer(op, le(0x0805) + "AirTag".toByteArray()))
    }

    @Test
    fun `sound start succeeds only on a success status`() {
        val op = DultInterrogator.OP_SOUND_START
        val ok = DultInterrogator.answer(op, le(0x0302, 0x0300, 0x0000))!!
        assertTrue(DultInterrogator.apply(op, ok, empty).soundStarted)
        // Invalid_state: the tracker refused. This used to read as success.
        val refused = DultInterrogator.answer(op, le(0x0302, 0x0300, 0x0001))!!
        assertFalse(DultInterrogator.apply(op, refused, empty).soundStarted)
    }

    @Test
    fun `a command response for another command, or Sound_Completed, answers nothing in flight`() {
        val op = DultInterrogator.OP_GET_MODEL_NAME
        assertNull(DultInterrogator.answer(op, le(0x0302, 0x0300, 0x0000)))
        assertNull(DultInterrogator.answer(op, le(0x0303)))
        assertNull(DultInterrogator.answer(DultInterrogator.OP_SOUND_START, le(0x0303)))
    }

    @Test
    fun `a refused query leaves the result as it was`() {
        val op = DultInterrogator.OP_GET_IDENTIFIER
        val refused = DultInterrogator.answer(op, le(0x0302, 0x0404, 0x0001))!!
        assertNull(DultInterrogator.apply(op, refused, empty).serialHash)
        val given = DultInterrogator.answer(op, le(0x0405) + ByteArray(32) { it.toByte() })!!
        val hashed = DultInterrogator.apply(op, given, empty).serialHash
        assertEquals(16, hashed?.length)
    }

    @Test
    fun `the accessory category is the first byte`() {
        val op = DultInterrogator.OP_GET_ACCESSORY_CATEGORY
        val answer = DultInterrogator.answer(op, le(0x0806) + byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))!!
        assertEquals("Finder (location tracker)", DultInterrogator.apply(op, answer, empty).category)
    }
}
