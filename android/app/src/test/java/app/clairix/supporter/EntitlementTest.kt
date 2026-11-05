package app.clairix.supporter

import app.clairix.comms.MemoryTokenPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Entitlement] answers from the credentials held, and from nothing else: a
 * pool with an unexpired credential makes a Supporter, an empty or expired
 * one does not, and spending the last one ends it.
 */
class EntitlementTest {

    @After
    fun unbind() = Entitlement.bind { 0 }

    private fun credential(n: Int) = "2.3.${"A".repeat(42)}${"ABCDEFGHIJ"[n]}.${"B".repeat(86)}"

    @Test
    fun nobodyIsASupporterUntilCredentialsAreBound() {
        Entitlement.bind { 0 }
        assertFalse(Entitlement.isSupporter())
        assertEquals(Entitlement.FREE_MEDIA_MAX_BYTES, Entitlement.mediaSendLimitBytes())
    }

    @Test
    fun aHeldCredentialMakesASupporterAndSpendingTheLastEndsIt() {
        val pool = MemoryTokenPool()
        Entitlement.bind { now -> pool.size(now) }
        val now = 1_000_000_000L
        val expires = now + 400L * 24 * 60 * 60_000
        pool.add(listOf(credential(0), credential(1)), expires)
        assertTrue(Entitlement.isSupporter(now))
        assertEquals(2, Entitlement.credentialsLeft(now))
        assertEquals(Entitlement.SUPPORTER_MEDIA_MAX_BYTES, Entitlement.mediaSendLimitBytes(now))
        pool.take(now)
        assertTrue(Entitlement.isSupporter(now))
        pool.take(now)
        assertFalse("the last credential spent", Entitlement.isSupporter(now))
        assertEquals(Entitlement.FREE_MEDIA_MAX_BYTES, Entitlement.mediaSendLimitBytes(now))
    }

    @Test
    fun expiredCredentialsDoNotCount() {
        val pool = MemoryTokenPool()
        Entitlement.bind { now -> pool.size(now) }
        pool.add(listOf(credential(2)), 5_000_000L)
        assertTrue(Entitlement.isSupporter(1_000L))
        assertFalse(Entitlement.isSupporter(5_000_000L))
    }

    @Test
    fun aFailingSourceIsNotASupporter() {
        Entitlement.bind { throw IllegalStateException("store locked") }
        assertFalse(Entitlement.isSupporter())
        assertEquals(0, Entitlement.credentialsLeft())
    }

    @Test
    fun supporterMediaIsLargerAndEveryoneReceivesIt() {
        assertTrue(Entitlement.SUPPORTER_MEDIA_MAX_BYTES > Entitlement.FREE_MEDIA_MAX_BYTES)
        assertEquals(Entitlement.SUPPORTER_MEDIA_MAX_BYTES, app.clairix.comms.CommsMedia.MAX_BYTES)
    }
}
