package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A one-time key's claim ticket survives failed sends until one envelope presenting it is accepted. */
class PendingClaimsTest {

    private val now = 1_760_000_000_000L
    private fun ticket(c: Char) = c.toString().repeat(43)

    @Test
    fun aTicketIsPresentedUntilAnEnvelopeCarryingItIsAccepted() {
        val book = PendingClaims()
        book.put("482913605", ticket('a'), now)
        // The first envelope failed (offline): the retry still presents the ticket.
        assertEquals(ticket('a'), book.get("482913605", now + 1000))
        assertEquals(ticket('a'), book.get("482913605", now + 60_000))
        book.accepted("482913605", ticket('a'))
        assertNull(book.get("482913605", now + 60_000))
    }

    @Test
    fun aNewerSessionReplacesTheTicketAndAStaleAcceptanceChangesNothing() {
        val book = PendingClaims()
        book.put("482913605", ticket('a'), now)
        book.put("482913605", ticket('b'), now + 1)
        book.accepted("482913605", ticket('a'))
        assertEquals(ticket('b'), book.get("482913605", now + 2))
        // A session on the fallback key has no ticket: nothing is presented.
        book.put("482913605", null, now + 3)
        assertNull(book.get("482913605", now + 3))
    }

    @Test
    fun ticketsSurviveARestartAndExpireWithTheRelaysMemoryOfThem() {
        val book = PendingClaims()
        book.put("111111111", ticket('a'), now)
        book.put("222222222", "not a ticket", now)
        val restored = PendingClaims().apply { load(book.encode()) }
        assertEquals(ticket('a'), restored.get("111111111", now))
        assertNull(restored.get("222222222", now))
        assertNull(restored.get("111111111", now + PendingClaims.MAX_AGE_MS + 1))
        restored.load("garbage")
        assertNull(restored.get("111111111", now))
    }
}
