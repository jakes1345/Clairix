package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTokensTest {

    private fun token(i: Int) = ("T" + i.toString().padStart(4, '0')).padEnd(43, 'x')
    private val now = 1_760_000_000_000L
    private val day = 24 * 3600_000L

    @Test
    fun tokensAreSpentOnceInRandomOrder() {
        val pool = MemoryTokenPool(java.util.Random(7))
        val issued = (0 until 50).map { token(it) }
        pool.add(issued, now + day)
        assertEquals(50, pool.size(now))
        val spent = (0 until 50).map { pool.take(now)!! }
        assertEquals(issued.toSet(), spent.toSet())
        assertTrue("tokens came out in the order they were issued", spent != issued)
        assertNull(pool.take(now))
    }

    @Test
    fun expiredOrMalformedTokensAreNeverHandedOut() {
        val pool = MemoryTokenPool()
        pool.add(listOf(token(1)), now + 60_000)       // inside the expiry margin
        pool.add(listOf("short", token(2) + "!"), now + day)
        assertEquals(0, pool.size(now))
        assertNull(pool.take(now))
        pool.add(listOf(token(3), token(3)), now + day)
        assertEquals(1, pool.size(now))
    }

    @Test
    fun aPoolSurvivesEncodeAndLoad() {
        val pool = MemoryTokenPool()
        pool.add(listOf(token(1), token(2)), now + day)
        val copy = MemoryTokenPool().apply { load(pool.encode()) }
        assertEquals(2, copy.size(now))
        copy.load("not json")
        assertEquals(0, copy.size(now))
    }

    @Test
    fun pushChecksAreToldApartFromWakes() {
        val code = "A".repeat(43)
        assertEquals(code, PushChallenge.codeFrom("clairix-verify:$code".toByteArray()))
        assertNull(PushChallenge.codeFrom("wake".toByteArray()))
        assertNull(PushChallenge.codeFrom("clairix-verify:short".toByteArray()))
        assertNull(PushChallenge.codeFrom(ByteArray(1000)))
    }

    @Test
    fun commsScreensAreAlwaysKeptOffScreenshots() {
        assertTrue(CommsSecureWindow.secure(passcodeOn = false, holds = 1, commsOnlyBuild = false))
        assertTrue(CommsSecureWindow.secure(passcodeOn = false, holds = 0, commsOnlyBuild = true))
        assertTrue(CommsSecureWindow.secure(passcodeOn = true, holds = 0, commsOnlyBuild = false))
        assertFalse(CommsSecureWindow.secure(passcodeOn = false, holds = 0, commsOnlyBuild = false))
    }
}
