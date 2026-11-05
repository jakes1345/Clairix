package app.clairix.analysis

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Writes DER for the tests, so [Der] and [KeyAttestation] are read against bytes built here,
 * certificates included: real X.509 certificates, signed with JVM keys and parsed by the JDK.
 */
internal object DerWriter {

    fun tlv(tag: ByteArray, content: ByteArray): ByteArray = tag + length(content.size) + content

    fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }

    private fun cat(parts: Array<out ByteArray>): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    fun seq(vararg parts: ByteArray) = tlv(byteArrayOf(0x30), cat(parts))
    fun set(vararg parts: ByteArray) = tlv(byteArrayOf(0x31), cat(parts))
    fun int(v: Long) = tlv(byteArrayOf(0x02), BigInteger.valueOf(v).toByteArray())
    fun enum(v: Long) = tlv(byteArrayOf(0x0A), BigInteger.valueOf(v).toByteArray())
    fun bool(b: Boolean) = tlv(byteArrayOf(0x01), byteArrayOf(if (b) 0xFF.toByte() else 0))
    fun octets(b: ByteArray) = tlv(byteArrayOf(0x04), b)
    fun utf8(s: String) = tlv(byteArrayOf(0x0C), s.toByteArray())
    fun utcTime(s: String) = tlv(byteArrayOf(0x17), s.toByteArray())
    fun bitString(b: ByteArray) = tlv(byteArrayOf(0x03), byteArrayOf(0) + b)

    /** [n] EXPLICIT around [inner], in the long tag form from 31 up. */
    fun ctx(n: Int, inner: ByteArray): ByteArray = tlv(contextTag(n), inner)

    fun contextTag(n: Int): ByteArray {
        if (n < 31) return byteArrayOf((0xA0 or n).toByte())
        val groups = ArrayList<Int>()
        var v = n
        while (v > 0) { groups.add(0, v and 0x7F); v = v ushr 7 }
        return byteArrayOf(0xBF.toByte()) + groups.mapIndexed { i, g -> (if (i < groups.size - 1) g or 0x80 else g).toByte() }.toByteArray()
    }

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val groups = ArrayList<Int>()
            var v = arc
            do { groups.add(0, (v and 0x7F).toInt()); v = v ushr 7 } while (v > 0)
            groups.forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        return tlv(byteArrayOf(0x06), out.toByteArray())
    }

    // ── Attestation ──────────────────────────────────────────────────────────

    /** A KeyDescription as a keystore writes it, attestation version 3, with a RootOfTrust in the hardware list. */
    fun keyDescription(
        challenge: ByteArray,
        level: Long = 1,
        locked: Boolean = true,
        bootState: Long = 0,
        patch: Long? = 202610,
        rootOfTrust: Boolean = true,
    ): ByteArray {
        val hardware = buildList {
            if (rootOfTrust) add(ctx(704, seq(octets(ByteArray(32) { 7 }), bool(locked), enum(bootState), octets(ByteArray(32) { 9 }))))
            if (patch != null) add(ctx(706, int(patch)))
        }
        return seq(
            int(3), enum(level), int(4), enum(level), octets(challenge), octets(ByteArray(0)),
            seq(ctx(709, octets("app.clairix".toByteArray()))),
            seq(*hardware.toTypedArray()),
        )
    }

    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    private fun name(cn: String) = seq(set(seq(oid("2.5.4.3"), utf8(cn))))

    /** An X.509 v3 certificate for [subjectKey] named [subject], issued and signed as [issuer] with [signer]. */
    fun certificate(subject: String, issuer: String, subjectKey: PublicKey, signer: PrivateKey, keyDescription: ByteArray? = null, serial: Long = 1): X509Certificate {
        val alg = seq(oid(ECDSA_SHA256))
        val parts = mutableListOf(
            ctx(0, int(2)), int(serial), alg, name(issuer), seq(utcTime("250101000000Z"), utcTime("491231235959Z")),
            name(subject), subjectKey.encoded,
        )
        if (keyDescription != null) parts += ctx(3, seq(seq(oid(KeyAttestation.KEY_DESCRIPTION_OID), octets(keyDescription))))
        val tbs = seq(*parts.toTypedArray())
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(signer); update(tbs); sign() }
        val der = seq(tbs, alg, bitString(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}
