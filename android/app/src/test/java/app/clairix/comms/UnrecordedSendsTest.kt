package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A send the relay took, then a seal before it was marked sent: the mark is
 * kept and written later, so the message is not encrypted and sent again.
 */
class UnrecordedSendsTest {

    /** A stand-in for the message table: what is marked sent, and whether it is open. */
    private class Table {
        var open = true
        val sent = mutableListOf<String>()
        fun markSent(id: String) {
            if (!open) throw VaultSealedException()
            sent += id
        }
    }

    @Test
    fun aMarkThatGoesThroughIsNotKept() {
        val table = Table()
        val u = UnrecordedSends { false }
        u.record("a") { table.markSent("a") }
        assertEquals(listOf("a"), table.sent)
        assertFalse(u.has("a"))
    }

    @Test
    fun aMarkTheSealCutOffIsKeptAndWrittenOnReplay() {
        val table = Table()
        var sealed = false
        val u = UnrecordedSends { sealed }
        sealed = true
        table.open = false
        val thrown = runCatching { u.record("a") { table.markSent("a") } }.exceptionOrNull()
        assertTrue("the pass still stops: $thrown", thrown is VaultSealedException)
        assertTrue(u.has("a"))
        assertTrue(table.sent.isEmpty())

        sealed = false
        table.open = true
        u.replay()
        assertEquals(listOf("a"), table.sent)
        assertFalse(u.has("a"))
        // Nothing is written twice.
        u.replay()
        assertEquals(listOf("a"), table.sent)
    }

    @Test
    fun aDatabaseClosedMidWriteBySealCountsAsSealed() {
        val u = UnrecordedSends { true }
        val closed = IllegalStateException("attempt to re-open an already-closed object")
        val thrown = runCatching { u.record("a") { throw closed } }.exceptionOrNull()
        assertSame(closed, thrown)
        assertTrue(u.has("a"))
    }

    @Test
    fun anOtherFailureWithTheVaultOpenIsNotKept() {
        val u = UnrecordedSends { false }
        val broken = IllegalStateException("something else")
        val thrown = runCatching { u.record("a") { throw broken } }.exceptionOrNull()
        assertSame(broken, thrown)
        assertFalse("only a seal keeps a write", u.has("a"))
    }

    @Test
    fun aReplayCutOffBySealingAgainKeepsTheRestInOrder() {
        val table = Table()
        val u = UnrecordedSends { true }
        table.open = false
        for (id in listOf("a", "b", "c")) runCatching { u.record(id) { table.markSent(id); if (id == "a") table.open = false } }
        assertTrue(u.has("a") && u.has("b") && u.has("c"))

        // Unlocked: "a" is written, then the phone locks again before "b".
        table.open = true
        assertTrue(runCatching { u.replay() }.exceptionOrNull() is VaultSealedException)
        assertEquals(listOf("a"), table.sent)
        assertFalse(u.has("a"))
        assertTrue(u.has("b") && u.has("c"))

        table.open = true
        u.replay()
        assertEquals(listOf("a", "b", "c"), table.sent)
    }

    @Test
    fun aReplayThatFailsStopsAndKeepsWhatIsLeft() {
        val table = Table()
        val u = UnrecordedSends { true }
        table.open = false
        for (id in listOf("a", "b")) runCatching { u.record(id) { table.markSent(id) } }
        // Still sealed: the first write fails, and both stay kept.
        assertTrue("a replay into a sealed vault throws", runCatching { u.replay() }.exceptionOrNull() is VaultSealedException)
        assertTrue(u.has("a") && u.has("b"))
        assertTrue(table.sent.isEmpty())
    }

    @Test
    fun aKeyRecordedAgainWhileKeptKeepsTheLatestWrite() {
        val written = mutableListOf<String>()
        var open = false
        val u = UnrecordedSends { true }
        fun write(what: String) {
            if (!open) throw VaultSealedException()
            written += what
        }
        runCatching { u.record("row") { write("first") } }
        runCatching { u.record("row") { write("second") } }
        open = true
        u.replay()
        assertEquals(listOf("second"), written)
    }
}
