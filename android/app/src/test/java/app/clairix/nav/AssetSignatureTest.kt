package app.clairix.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The check that stands between a download and the router, through the real
 * Rust verifier, against a fixed Ed25519 vector in the form tools/signing/sign.py
 * writes (raw key and signature, base64 without padding). The key pair is a
 * throwaway made for this test; its secret half was never kept. The project's
 * own signing key plays no part.
 */
class AssetSignatureTest {

    private val key = "JlzlPKS2n3yzNUznXg1Apy2Qy+S3uFMofsfXBj/vFRA"
    private val otherKey = "mp+wylHYgMxviz27ayLSCxcsLq5BT5bcUczj59rJRfc"
    private val message = "ohio.graph sha256 3f1c0d6a9b2e47c8a1d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b\n"
        .toByteArray(Charsets.US_ASCII)
    private val signature = "XgOGHEKQI1yhiO6XxhZ+nUnvdt43BgB8EJ6ya6W+NL1yyGTr4TPFmoOgh+mxFeUj9pZwln2syLgQMBsuSRsGBw"

    @Test
    fun theSignatureOverTheExactBytesChecks() {
        assertTrue(AssetSignature.verifyWith(key, message, signature))
        // As a .sig file holds it: one line, with its newline.
        assertTrue(AssetSignature.verifyWith(key, message, "$signature\n"))
    }

    @Test
    fun oneFlippedBitInTheSignatureFails() {
        val raw = Base64.getDecoder().decode(signature)
        for (bit in listOf(0, 7, 255, 511)) {
            val flipped = raw.copyOf()
            flipped[bit / 8] = (flipped[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            val b64 = Base64.getEncoder().withoutPadding().encodeToString(flipped)
            assertFalse("bit $bit flipped still verified", AssetSignature.verifyWith(key, message, b64))
        }
    }

    @Test
    fun oneChangedByteInTheFileFails() {
        val changed = message.copyOf()
        changed[changed.size - 2] = 'c'.code.toByte()
        assertFalse(AssetSignature.verifyWith(key, changed, signature))
        assertFalse("a truncated file", AssetSignature.verifyWith(key, message.copyOf(message.size - 1), signature))
    }

    @Test
    fun anotherKeysSignatureFails() {
        assertFalse(AssetSignature.verifyWith(otherKey, message, signature))
    }

    @Test
    fun anEmptyOverlongOrGarbledSignatureFailsWithoutThrowing() {
        assertFalse(AssetSignature.verifyWith(key, message, ""))
        assertFalse(AssetSignature.verifyWith(key, message, "   \n"))
        assertFalse(AssetSignature.verifyWith(key, message, "A".repeat(129)))
        assertFalse(AssetSignature.verifyWith(key, message, "not base64 at all!"))
        assertFalse("half a signature", AssetSignature.verifyWith(key, message, signature.take(43)))
        assertFalse("a broken key", AssetSignature.verifyWith("short", message, signature))
    }
}
