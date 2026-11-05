package app.clairix.analysis

import app.clairix.analysis.DerWriter.bool
import app.clairix.analysis.DerWriter.ctx
import app.clairix.analysis.DerWriter.int
import app.clairix.analysis.DerWriter.octets
import app.clairix.analysis.DerWriter.seq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DER reader reads what DER allows and refuses everything else. */
class DerTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun refused(vararg b: Int) {
        assertThrows(DerException::class.java) { Der.parse(bytes(*b)).let { e -> if (e.constructed) e.children() else e.content() } }
    }

    @Test
    fun aSequenceOfPrimitivesIsReadInOrder() {
        val e = Der.parse(seq(int(5), bool(true), octets(byteArrayOf(1, 2)), int(-129), int(300)))
        assertTrue(e.isUniversal(Der.TAG_SEQUENCE))
        val c = e.children()
        assertEquals(5L, c[0].long())
        assertTrue(c[1].bool())
        assertArrayEquals(byteArrayOf(1, 2), c[2].octets())
        assertEquals(-129L, c[3].long())
        assertEquals(300L, c[4].long())
    }

    @Test
    fun aHighTagNumberInTheLongFormIsRead() {
        // [704] EXPLICIT, the RootOfTrust's tag: BF 85 40.
        val e = Der.parse(ctx(704, int(1)))
        assertEquals(Der.CLASS_CONTEXT, e.tagClass)
        assertEquals(704, e.tag)
        assertEquals(1L, e.explicit().long())
    }

    @Test
    fun aLongLengthIsRead() {
        val big = ByteArray(300) { 3 }
        assertArrayEquals(big, Der.parse(octets(big)).octets())
    }

    @Test
    fun anIndefiniteLengthIsRefused() = refused(0x30, 0x80, 0x00, 0x00)

    @Test
    fun aLengthInMoreBytesThanItNeedsIsRefused() {
        refused(0x04, 0x81, 0x01, 0x00)          // 1 in the long form
        refused(0x04, 0x82, 0x00, 0x81, *IntArray(0x81))  // a leading zero byte
    }

    @Test
    fun aLengthRunningPastTheEndIsRefused() {
        refused(0x04, 0x05, 0x01)
        // Inside a sequence: the child claims more than its parent holds.
        refused(0x30, 0x03, 0x04, 0x05, 0x01)
    }

    @Test
    fun trailingBytesAreRefused() = refused(0x02, 0x01, 0x01, 0x00)

    @Test
    fun aSmallTagNumberInTheLongFormIsRefused() {
        refused(0xBF, 0x05, 0x00)
        refused(0xBF, 0x80, 0x05, 0x00)
    }

    @Test
    fun anIntegerWithANeedlessLeadingByteIsRefused() {
        assertThrows(DerException::class.java) { Der.parse(bytes(0x02, 0x02, 0x00, 0x01)).long() }
        assertThrows(DerException::class.java) { Der.parse(bytes(0x02, 0x02, 0xFF, 0x80)).long() }
        assertThrows(DerException::class.java) { Der.parse(bytes(0x02, 0x00)).long() }
        // 0x0080 needs its zero: it is 128, not -128.
        assertEquals(128L, Der.parse(bytes(0x02, 0x02, 0x00, 0x80)).long())
    }

    @Test
    fun aBooleanIsZeroOrFfAndTheOldKeymasterOne() {
        assertFalse(Der.parse(bytes(0x01, 0x01, 0x00)).bool())
        assertTrue(Der.parse(bytes(0x01, 0x01, 0xFF)).bool())
        assertTrue(Der.parse(bytes(0x01, 0x01, 0x01)).bool())
        assertThrows(DerException::class.java) { Der.parse(bytes(0x01, 0x01, 0x02)).bool() }
        assertThrows(DerException::class.java) { Der.parse(bytes(0x01, 0x02, 0x00, 0x00)).bool() }
    }

    @Test
    fun theWrongTypeIsRefused() {
        assertThrows(DerException::class.java) { Der.parse(octets(byteArrayOf(1))).long() }
        assertThrows(DerException::class.java) { Der.parse(int(1)).octets() }
        assertThrows(DerException::class.java) { Der.parse(int(1)).children() }
    }

    @Test
    fun nestingIsCapped() {
        var e = int(1)
        repeat(Der.MAX_DEPTH + 2) { e = seq(e) }
        var at = Der.parse(e)
        assertThrows(DerException::class.java) { repeat(Der.MAX_DEPTH + 2) { at = at.children().single() } }
    }

    @Test
    fun emptyInputIsRefused() {
        assertThrows(DerException::class.java) { Der.parse(ByteArray(0)) }
    }
}
