package app.clairix.analysis

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        val pair = gen.generateKeyPair()
        pair.private to pair.public
    }

    override fun publicKey(): ByteArray = keys().second.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance(algorithm).run {
        initSign(keys().first)
        update(data)
        sign()
    }
}
