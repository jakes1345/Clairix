package app.clairix.analysis

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

/**
 * Signs an evidence report. On the phone this is a per-install key in the Android
 * Keystore ([KeystoreReportSigner]); tests use an ordinary JVM key pair.
 */
interface ReportSigner {
    /** The JCA signature algorithm, printed in the report, e.g. "SHA256withECDSA". */
    val algorithm: String

    /** The public key as SubjectPublicKeyInfo DER, which `openssl pkey -pubin -inform DER` reads. */
    fun publicKey(): ByteArray

    /** A signature over [data] with [algorithm]; DER-encoded for ECDSA. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * The report's integrity block: a SHA-256 of the body and a signature over the
 * same bytes, so an advocate can check with OpenSSL that the file is the one the
 * phone wrote. The hash on its own anyone can recompute after an edit; the
 * signature needs the private key, which never leaves this phone's Keystore.
 */
object ReportSigning {

    /** The P-256 curve; Ed25519 keys in the Keystore need API 33 and the app runs from 31. */
    const val ALGORITHM = "SHA256withECDSA"
    const val KEY_DESCRIPTION = "ECDSA P-256 with SHA-256, a per-install key in this phone's Android Keystore"

    /** What [seal] made: the body's SHA-256 (hex) and the signature (base64), or why there is no signature. */
    data class Seal(val sha256: String, val signature: String?, val failure: String?)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * The SHA-256 of the public key's DER bytes, in groups of four hex digits: what
     * `openssl dgst -sha256 key.der` prints, without the spaces. Shown on the phone
     * at export and in every report, so a reader can tell the key is this phone's.
     */
    fun fingerprint(publicKey: String): String? = runCatching {
        sha256Hex(Base64.getDecoder().decode(publicKey)).chunked(4).joinToString(" ")
    }.getOrNull()

    /** The key the header prints, read from [signer]; never throws. */
    fun key(signer: ReportSigner?): SigningKey {
        if (signer == null) return SigningKey(ALGORITHM, null, "no signing key on this phone")
        return runCatching { SigningKey(signer.algorithm, base64(signer.publicKey())) }
            .getOrElse { SigningKey(signer.algorithm, null, reason(it)) }
    }

    /** Hashes [body] and signs it with [signer] when the header has its key; never throws. */
    fun seal(body: ByteArray, signer: ReportSigner?, key: SigningKey): Seal {
        val hash = sha256Hex(body)
        if (signer == null || key.publicKey == null) return Seal(hash, null, key.unavailable ?: "no signing key")
        return runCatching { Seal(hash, base64(signer.sign(body)), null) }.getOrElse { Seal(hash, null, reason(it)) }
    }

    /**
     * Whether [signature] (base64) is a valid [algorithm] signature over [body] by
     * [publicKey] (SubjectPublicKeyInfo DER, base64). What `openssl dgst -verify` checks.
     */
    fun verify(publicKey: String, body: ByteArray, signature: String, algorithm: String = ALGORITHM): Boolean = runCatching {
        val spki = Base64.getDecoder().decode(publicKey)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance(algorithm).run {
            initVerify(key)
            update(body)
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)

    private fun reason(t: Throwable): String = t.message?.takeIf { it.isNotBlank() }?.let { "${t.javaClass.simpleName}: $it" }
        ?: t.javaClass.simpleName
}
