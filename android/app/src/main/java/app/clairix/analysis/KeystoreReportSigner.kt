package app.clairix.analysis

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The per-install key evidence reports are signed with: an ECDSA P-256 key made
 * in the Android Keystore on the first export, whose private half never leaves
 * it. The wipe deletes every Keystore alias the app has, this one included, so a
 * report from after a wipe carries a different public key.
 *
 * A key made now asks for key attestation ([ReportSigning.ATTESTATION_CHALLENGE]),
 * so the Keystore keeps a certificate chain for it in which the secure hardware
 * says where it was made; the report carries that chain ([attestationChain]). A
 * phone that refuses attestation gets the key without it, as does every key made
 * before this, which is kept as it is.
 */
internal object KeystoreReportSigner : ReportSigner {

    const val ALIAS = "clairix_report_signing_v1"
    private const val PROVIDER = "AndroidKeyStore"

    override val algorithm: String = ReportSigning.ALGORITHM

    private val lock = Any()

    private fun keys(): Pair<PrivateKey, PublicKey> = synchronized(lock) {
        val ks = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) return entry.privateKey to entry.certificate.publicKey
        val pair = runCatching { generate(attested = true) }.getOrElse {
            runCatching { ks.deleteEntry(ALIAS) }
            generate(attested = false)
        }
        pair.private to pair.public
    }

    private fun generate(attested: Boolean): KeyPair {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (attested) spec.setAttestationChallenge(ReportSigning.ATTESTATION_CHALLENGE)
        gen.initialize(spec.build())
        return gen.generateKeyPair()
    }

    /** The certificates the Keystore keeps with the key: public, DER, the key's own first. */
    override fun attestationChain(): List<ByteArray> = synchronized(lock) {
        keys()
        val ks = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        ks.getCertificateChain(ALIAS)?.map { it.encoded }.orEmpty()
    }

    override fun publicKey(): ByteArray = keys().second.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance(algorithm).run {
        initSign(keys().first)
        update(data)
        sign()
    }
}
