package app.clairix.supporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code format, against the same vectors as the relay's test/supporter.test.mjs. */
class SupporterCodeTest {

    @Test
    fun checkSymbolsMatchTheRelays() {
        assertEquals("Y8", SupporterCode.check("0123456789ABCDEFGHJKMN"))
        assertEquals("MY", SupporterCode.check("7KQ2M9XD4TPWHC3R0VNB6Y"))
        assertEquals("JE", SupporterCode.check("ZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun readsWhatPeopleType() {
        assertEquals("7KQ2M9XD4TPWHC3R0VNB6YMY", SupporterCode.normalise("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMY"))
        assertEquals("7KQ2M9XD4TPWHC3R0VNB6YMY", SupporterCode.normalise(" 7kq2 m9xd 4tpw hc3r ovnb 6ymy "))
        // I and L read as 1, O as 0, the way Crockford's base32 means them to.
        assertEquals("0123456789ABCDEFGHJKMNY8", SupporterCode.normalise("o1z3-4567-89ab-cdef-ghjk-mny8".replace('z', '2')))
        assertEquals(SupporterCode.normalise("0123456789ABCDEFGHJKMNY8"), SupporterCode.normalise("O123456789ABCDEFGHJKMNY8"))
        assertEquals(SupporterCode.normalise("0123456789ABCDEFGHJKMNY8"), SupporterCode.normalise("0I23456789ABCDEFGHJKMNY8"))
    }

    @Test
    fun refusesMistypedCodes() {
        assertNull(SupporterCode.normalise("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMZ"))
        assertNull("two symbols swapped", SupporterCode.normalise("7KQ2-M9XD-4TPW-HC3R-0VNB-Y6MY"))
        assertNull("short", SupporterCode.normalise("7KQ2-M9XD-4TPW-HC3R-0VNB"))
        assertNull("U is not in the alphabet", SupporterCode.normalise("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMU"))
        assertNull("too long to be a code", SupporterCode.normalise("7".repeat(100)))
        assertNull(SupporterCode.normalise(""))
        val good = "7KQ2M9XD4TPWHC3R0VNB6YMY"
        var caught = 0
        for (i in 0 until SupporterCode.DATA_LEN) {
            val s = good.toCharArray()
            s[i] = SupporterCode.ALPHABET[(SupporterCode.ALPHABET.indexOf(s[i]) + 1) % SupporterCode.ALPHABET.length]
            if (SupporterCode.normalise(String(s)) == null) caught++
        }
        assertTrue("a single wrong symbol is caught ($caught of ${SupporterCode.DATA_LEN})", caught >= 20)
    }

    @Test
    fun formatsWhileTyping() {
        assertEquals("7KQ2-M9XD-4T", SupporterCode.formatTyping("7kq2m9xd4t"))
        assertEquals("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMY", SupporterCode.formatTyping("7KQ2 M9XD 4TPW HC3R 0VNB 6YMY extra"))
        assertEquals("", SupporterCode.formatTyping("--"))
        assertEquals("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMY", SupporterCode.group("7KQ2M9XD4TPWHC3R0VNB6YMY"))
    }
}
