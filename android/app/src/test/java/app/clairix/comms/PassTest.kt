package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The pass a sender attaches to an envelope must be exactly what the relay
 * computes (comms-worker/src/mailbox.ts, passFor), or contacts are counted as
 * strangers. The expected value was computed independently with Node's crypto.
 */
class PassTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun matchesTheRelaysPass() {
        assertEquals("dd7fPN941hVfm+nBVdCXARCGJYAIWiLlLSRePWgSdOk=", RelayClient.pass(key, "123456789", "QUFBQQ=="))
    }

    @Test
    fun theNumberIsTakenInTheRelaysDigitsOnlyForm() {
        assertEquals(RelayClient.pass(key, "123456789", "QUFBQQ=="), RelayClient.pass(key, "123-456-789", "QUFBQQ=="))
    }

    @Test
    fun aPassBelongsToOneEnvelopeAndOneRecipient() {
        val pass = RelayClient.pass(key, "123456789", "QUFBQQ==")
        assertNotEquals(pass, RelayClient.pass(key, "123456789", "QkJCQg=="))
        assertNotEquals(pass, RelayClient.pass(key, "987654321", "QUFBQQ=="))
        assertNotEquals(pass, RelayClient.pass(ByteArray(32) { 7 }, "123456789", "QUFBQQ=="))
    }
}
