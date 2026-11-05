package app.clairix.analysis

import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Key attestation read from certificates built here: a root, an intermediate and the key's own
 * certificate carrying a KeyDescription, each signed by the next, parsed by the JDK as a phone's
 * Keystore would hand them over.
 */
class KeyAttestationTest {

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val root = keys()
    private val intermediate = keys()
    private val leaf = keys()
    private val challenge = ByteArray(32) { it.toByte() }

    private fun chain(description: ByteArray, leafSigner: KeyPair = intermediate): List<X509Certificate> = listOf(
        DerWriter.certificate("Android Keystore Key", "Intermediate", leaf.public, leafSigner.private, description, serial = 3),
        DerWriter.certificate("Intermediate", "Root", intermediate.public, root.private, serial = 2),
        DerWriter.certificate("Root", "Root", root.public, root.private, serial = 1),
    )

    private fun result(description: ByteArray = DerWriter.keyDescription(challenge), asked: ByteArray = challenge) =
        KeyAttestation.assess(chain(description), asked, strongBox = false)

    private fun fact(r: KeyAttestation.Result, propsUnlocked: Boolean? = false) =
        KeyAttestation.fact(KeyAttestation.State.Read(r), propsUnlocked)

    @Test
    fun theRecordIsReadFromTheCertificate() {
        val certs = chain(DerWriter.keyDescription(challenge))
        val r = KeyAttestation.assess(certs, challenge, strongBox = false)
        val d = r.description
        assertEquals(3L, d.attestationVersion)
        assertEquals(KeyAttestation.SecurityLevel.TRUSTED_ENVIRONMENT, d.attestationSecurityLevel)
        assertEquals(KeyAttestation.BootState.VERIFIED, d.rootOfTrust!!.bootState)
        assertTrue(d.rootOfTrust!!.deviceLocked)
        assertEquals(202610L, d.osPatchLevel)
        assertTrue(r.challengeMatches)
        assertNull(r.brokenLink)
        assertTrue(r.rootSelfSigned)
        assertEquals(3, r.chainLength)
        assertTrue(r.checksOut)
        // ECDSA signatures differ each time, so the root is fingerprinted as handed over.
        assertEquals(AppEvidence.sha256(certs.last().encoded), r.rootSha256)
    }

    @Test
    fun aLockedVerifiedPhoneSaysWhatTheCheckDoesNotProve() {
        val f = fact(result())
        assertEquals("Locked, verified", f.value)
        assertNull(f.severity)
        assertEquals("Integrity", f.category)
        val detail = f.detail!!
        assertTrue(detail.contains("Google's list of attestation roots"))
        assertTrue(detail.contains("revocation list"))
        assertTrue(detail.contains("not proof"))
        assertTrue(detail.contains("Security patch the hardware recorded: 2026-10"))
        assertTrue(detail.contains("Trusted Execution Environment"))
    }

    @Test
    fun anUnlockedBootloaderIsHighAndADisagreementSaysSo() {
        val unlocked = result(DerWriter.keyDescription(challenge, locked = false, bootState = 2))
        val f = fact(unlocked, propsUnlocked = true)
        assertEquals("UNLOCKED", f.value)
        assertEquals(Severity.HIGH, f.severity)
        val lie = fact(unlocked, propsUnlocked = false)
        assertEquals("UNLOCKED (disagrees)", lie.value)
        assertTrue(lie.detail!!.contains("say the opposite"))
        // Unknown properties: no disagreement to report.
        assertEquals("UNLOCKED", fact(unlocked, propsUnlocked = null).value)
    }

    @Test
    fun aCustomOsKeyIsMediumAndAFailureCritical() {
        val custom = fact(result(DerWriter.keyDescription(challenge, bootState = 1)))
        assertEquals("Locked, custom OS key", custom.value)
        assertEquals(Severity.MEDIUM, custom.severity)
        val failed = fact(result(DerWriter.keyDescription(challenge, bootState = 3)))
        assertEquals(Severity.CRITICAL, failed.severity)
    }

    @Test
    fun anAnswerForAnotherChallengeDoesNotCheckOut() {
        val r = result(asked = ByteArray(32) { 1 })
        assertFalse(r.challengeMatches)
        assertFalse(r.checksOut)
        val f = fact(r)
        assertEquals("Did not check out", f.value)
        assertEquals(Severity.MEDIUM, f.severity)
        assertTrue(f.detail!!.contains("not made for this check"))
    }

    @Test
    fun aCertificateNotSignedByTheNextIsFound() {
        // The key's certificate signed by a stranger, not by the intermediate.
        val r = KeyAttestation.assess(chain(DerWriter.keyDescription(challenge), leafSigner = keys()), challenge, strongBox = false)
        assertEquals(0, r.brokenLink)
        assertEquals("Did not check out", fact(r).value)
        assertTrue(fact(r).detail!!.contains("do not link up"))
    }

    @Test
    fun aRootThatDoesNotSignItselfIsFound() {
        val good = chain(DerWriter.keyDescription(challenge))
        val notSelf = good.dropLast(1) + DerWriter.certificate("Root", "Elsewhere", root.public, keys().private)
        val r = KeyAttestation.assess(notSelf, challenge, strongBox = false)
        assertNull(r.brokenLink)
        assertFalse(r.rootSelfSigned)
        assertFalse(r.checksOut)
    }

    @Test
    fun softwareAloneSaysNothingAboutTheBoot() {
        val f = fact(result(DerWriter.keyDescription(challenge, level = 0)))
        assertEquals("Software only", f.value)
        assertNull(f.severity)
        val none = fact(result(DerWriter.keyDescription(challenge, rootOfTrust = false)))
        assertEquals("Software only", none.value)
    }

    @Test
    fun noAttestationIsSaidPlainly() {
        val f = KeyAttestation.fact(KeyAttestation.State.Unavailable("no StrongBox"), propsUnlocked = false)
        assertEquals("Not available", f.value)
        assertNull(f.severity)
        assertTrue(f.detail!!.contains("no StrongBox"))
    }

    @Test
    fun aMalformedRecordIsRefused() {
        assertThrows(DerException::class.java) { KeyAttestation.parseKeyDescription(DerWriter.octets(DerWriter.seq(DerWriter.int(3)))) }
        // Not wrapped in an OCTET STRING.
        assertThrows(DerException::class.java) { KeyAttestation.parseKeyDescription(DerWriter.keyDescription(challenge)) }
        // An unknown security level.
        assertThrows(DerException::class.java) {
            KeyAttestation.parseKeyDescription(DerWriter.octets(DerWriter.keyDescription(challenge, level = 7)))
        }
        // A certificate without the extension.
        val plain = listOf(DerWriter.certificate("Root", "Root", root.public, root.private))
        assertThrows(DerException::class.java) { KeyAttestation.assess(plain, challenge, strongBox = false) }
    }

    @Test
    fun noLinkIsBrokenInAOneCertificateChain() {
        assertNull(KeyAttestation.brokenLink(listOf(DerWriter.certificate("Root", "Root", root.public, root.private))))
    }
}
