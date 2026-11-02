package app.clairix.comms.lora

import uniffi.clairix_comms_crypto.Identity
import uniffi.clairix_comms_crypto.LoraLiteMessage
import uniffi.clairix_comms_crypto.LoraSos
import uniffi.clairix_comms_crypto.loraLiteDecode
import uniffi.clairix_comms_crypto.loraLiteEncode
import uniffi.clairix_comms_crypto.loraParseSos
import uniffi.clairix_comms_crypto.loraSosPayload

/**
 * Lite's sealing and padding from the Rust library (comms-crypto/src/lora.rs), which
 * matches the radio's C++ byte for byte: X25519 from a fresh ephemeral key and from
 * this phone's sealing key, HKDF-SHA-512, the sender's key hidden under ChaCha20, the
 * message under ChaCha20-Poly1305, padded to one of a few sizes.
 */
class UniffiLiteCodec(private val identity: Identity) : LiteCodec {

    override fun encode(kind: Int, id: Long, sentMinutes: Long, payload: ByteArray): ByteArray? = runCatching {
        loraLiteEncode(LoraLiteMessage(kind.toUByte(), id.toUInt(), sentMinutes.toUInt(), payload))
    }.getOrNull()

    override fun decode(inner: ByteArray): LiteCodec.Inner? = runCatching {
        val m = loraLiteDecode(inner)
        LiteCodec.Inner(m.kind.toInt(), m.id.toLong(), m.sentMinutes.toLong(), m.payload)
    }.getOrNull()

    override fun seal(peer: ByteArray, inner: ByteArray): ByteArray? = runCatching { identity.loraLiteSeal(peer, inner) }.getOrNull()

    override fun open(body: ByteArray, contacts: List<ByteArray>): Pair<ByteArray, ByteArray>? =
        runCatching { identity.loraLiteOpen(body, contacts).let { it.sender to it.inner } }.getOrNull()

    override fun sosPayload(position: Pair<Int, Int>?, text: String): ByteArray =
        loraSosPayload(LoraSos(position != null, position?.first ?: 0, position?.second ?: 0, text))

    override fun parseSos(payload: ByteArray): LiteCodec.Sos? = runCatching {
        val s = loraParseSos(payload)
        LiteCodec.Sos(if (s.hasPosition) s.lat7 to s.lon7 else null, s.text)
    }.getOrNull()
}
