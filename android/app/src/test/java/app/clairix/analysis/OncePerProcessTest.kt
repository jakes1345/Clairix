package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The memo behind `which su`: one lookup per process once it has answered, another try after a failure. */
class OncePerProcessTest {

    @Test
    fun anAnswerIsLookedUpOnce() {
        var calls = 0
        val once = OncePerProcess { calls++; "/system/xbin/su" }
        repeat(5) { assertEquals("/system/xbin/su", once.get()) }
        assertEquals(1, calls)
    }

    @Test
    fun nothingFoundIsAnAnswerToo() {
        // `which su` prints nothing on a phone without su: that is the answer, kept.
        var calls = 0
        val once = OncePerProcess { calls++; "" }
        repeat(3) { assertEquals("", once.get()) }
        assertEquals(1, calls)
    }

    @Test
    fun aLookupThatFailedIsTriedAgain() {
        var calls = 0
        val once = OncePerProcess { calls++; if (calls < 3) null else "" }
        assertNull(once.get())
        assertNull(once.get())
        assertEquals("", once.get())
        assertEquals("", once.get())
        assertEquals(3, calls)
    }
}
