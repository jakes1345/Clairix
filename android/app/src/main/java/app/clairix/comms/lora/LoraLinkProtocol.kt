package app.clairix.comms.lora

import java.nio.ByteBuffer
import java.util.UUID

/**
 * The link between this phone and a Clairix LoRa radio over Bluetooth Low Energy,
 * byte for byte as the radio's firmware writes it (lora/core/include/clx/link.h; the
 * known answers in lora/core/tests/known_answers.h keep the two in step).
 *
 * The radio is a GATT server with one service and one characteristic: the phone
 * writes, the radio notifies, and both cut frames into chunks the way the
 * phone-to-phone mesh does ([app.clairix.comms.mesh.MeshProtocol.chunk]). The radio
 * lets only a bonded phone in, and bonding asks for the six digits on its screen.
 *
 * Frames, by their first byte:
 *
 *   0x40 Info       radio to phone   what the radio is and does
 *   0x41 Config     phone to radio   preset, power, hop limit, network key, the time
 *   0x42 BundleOut  phone to radio   flags | bundle: put it on the air
 *   0x43 BundleIn   radio to phone   bundle id(8) | bundle: heard off the air
 *   0x44 Got        phone to radio   bundle id(8): the phone has it
 *   0x45 LiteOut    phone to radio   hop limit | Lite body
 *   0x46 LiteIn     radio to phone   rssi(2) | snr x4 (1) | Lite body
 *   0x47 PairOut    phone to radio   Pair body
 *   0x48 PairIn     radio to phone   rssi(2) | snr x4 (1) | Pair body
 *   0x49 Hello      phone to radio   version: send Info and what is waiting
 *
 * A bundle on the link: tag(16) | hops(1) | created, ms since 1970 (8) | length(2) | envelope.
 * Every number is big-endian.
 */
object LoraLinkProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("5c1a7e0d-3f1b-4d8e-9a26-c1a1e0f7a4c0")
    val FRAMES_UUID: UUID = UUID.fromString("5c1a7e0d-3f1b-4d8e-9a26-c1a1e0f7a4c1")

    const val VERSION = 1

    const val T_INFO: Byte = 0x40
    const val T_CONFIG: Byte = 0x41
    const val T_BUNDLE_OUT: Byte = 0x42
    const val T_BUNDLE_IN: Byte = 0x43
    const val T_GOT: Byte = 0x44
    const val T_LITE_OUT: Byte = 0x45
    const val T_LITE_IN: Byte = 0x46
    const val T_PAIR_OUT: Byte = 0x47
    const val T_PAIR_IN: Byte = 0x48
    const val T_HELLO: Byte = 0x49

    /** BundleOut flag: this phone's owner wrote it, so the radio keeps sending it until it is answered or expires. */
    const val BUNDLE_OWN = 0x01

    /** Info's channel while the radio has no clock and sweeps every channel. */
    const val CHANNEL_SCANNING = 0xFF

    const val TAG_LEN = 16
    const val KEY_LEN = 32

    /** The largest body one LoRa frame carries (a Lite or Pair body): 255 bytes less the frame's own. */
    const val BODY_MAX = 236

    /** The largest envelope a bundle may carry over LoRa: ten fragments of 226 bytes, less the bundle's header. */
    const val MAX_ENVELOPE = 10 * 226 - 21

    /** The radio's transmit power, dBm: within the 915 MHz band's limit for these radios. */
    const val MIN_TX_DBM = 2
    const val MAX_TX_DBM = 22
    const val MAX_HOP_LIMIT = 7

    /** The radio settings, slowest last; spreading factor 8, 10 and 12, all 500 kHz wide. */
    enum class Preset(val code: Int, val label: String, val detail: String) {
        FAST(0, "Fast", "Spreading factor 8: shortest airtime, shortest reach"),
        BALANCED(1, "Balanced", "Spreading factor 10: the default"),
        LONG(2, "Long range", "Spreading factor 12: farthest reach, about 8 times the airtime of Fast");

        companion object {
            fun of(code: Int): Preset = entries.firstOrNull { it.code == code } ?: BALANCED
        }
    }

    enum class Board(val code: Int, val label: String) {
        UNKNOWN(0, "Unknown board"),
        HELTEC_V3(1, "Heltec WiFi LoRa 32 V3"),
        T_DECK(2, "LilyGO T-Deck"),
        XIAO_S3(3, "Seeed XIAO ESP32S3 + Wio-SX1262");

        companion object {
            fun of(code: Int): Board = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class Info(
        val version: Int,
        val board: Board,
        val firmware: String,
        val preset: Int,
        val txPower: Int,
        val hopLimit: Int,
        val channel: Int,
        val networkId: ByteArray,
        val publicNetwork: Boolean,
        val standalone: Boolean,
        /** 0: not known, or on USB power. */
        val batteryMv: Int,
        val stored: Int,
        val heard: Long,
        val sent: Long,
        val relayed: Long,
        /** Share of the airtime budget window spent, in thousandths. */
        val airtimePermille: Int
    ) {
        val scanning: Boolean get() = channel == CHANNEL_SCANNING
        override fun equals(other: Any?) = other is Info && encodeInfo(this).contentEquals(encodeInfo(other))
        override fun hashCode() = encodeInfo(this).contentHashCode()
    }

    data class Config(val preset: Int, val txPower: Int, val hopLimit: Int, val networkKey: ByteArray, val nowMs: Long) {
        init { require(networkKey.size == KEY_LEN) { "a network key is $KEY_LEN bytes" } }
        override fun equals(other: Any?) = other is Config && config(this).contentEquals(config(other))
        override fun hashCode() = config(this).contentHashCode()
    }

    /** A bundle as the link carries it; [createdMs] is the hour it was made, in ms since 1970. */
    data class LinkBundle(val tag: ByteArray, val hops: Int, val createdMs: Long, val envelope: ByteArray) {
        override fun equals(other: Any?) = other is LinkBundle && tag.contentEquals(other.tag) && hops == other.hops &&
            createdMs == other.createdMs && envelope.contentEquals(other.envelope)
        override fun hashCode() = 31 * envelope.contentHashCode() + createdMs.hashCode()
    }

    /** What the radio sends. */
    sealed interface Frame {
        data class InfoFrame(val info: Info) : Frame
        data class BundleIn(val bid: Long, val bundle: LinkBundle) : Frame
        class LiteIn(val body: ByteArray, val rssi: Int, val snr: Float) : Frame
        class PairIn(val body: ByteArray, val rssi: Int, val snr: Float) : Frame
    }

    // ── Phone to radio ────────────────────────────────────────────────────

    fun hello(): ByteArray = byteArrayOf(T_HELLO, VERSION.toByte())

    fun config(c: Config): ByteArray = ByteBuffer.allocate(4 + KEY_LEN + 8)
        .put(T_CONFIG).put(c.preset.toByte()).put(c.txPower.toByte()).put(c.hopLimit.toByte())
        .put(c.networkKey).putLong(c.nowMs).array()

    /** Null when [b] cannot go on LoRa: a tag of the wrong size, or an envelope over [MAX_ENVELOPE]. */
    fun bundleOut(b: LinkBundle, flags: Int): ByteArray? {
        val body = linkBundle(b) ?: return null
        return ByteBuffer.allocate(2 + body.size).put(T_BUNDLE_OUT).put(flags.toByte()).put(body).array()
    }

    fun got(bid: Long): ByteArray = ByteBuffer.allocate(9).put(T_GOT).putLong(bid).array()

    fun liteOut(body: ByteArray, hopLimit: Int): ByteArray {
        require(body.isNotEmpty() && body.size <= BODY_MAX) { "a Lite body is 1 to $BODY_MAX bytes" }
        return byteArrayOf(T_LITE_OUT, hopLimit.coerceIn(1, MAX_HOP_LIMIT).toByte()) + body
    }

    fun pairOut(body: ByteArray): ByteArray {
        require(body.isNotEmpty() && body.size <= BODY_MAX) { "a Pair body is 1 to $BODY_MAX bytes" }
        return byteArrayOf(T_PAIR_OUT) + body
    }

    private fun linkBundle(b: LinkBundle): ByteArray? {
        if (b.tag.size != TAG_LEN || b.envelope.isEmpty() || b.envelope.size > MAX_ENVELOPE) return null
        return ByteBuffer.allocate(TAG_LEN + 1 + 8 + 2 + b.envelope.size)
            .put(b.tag).put(b.hops.coerceIn(0, 255).toByte()).putLong(b.createdMs)
            .putShort(b.envelope.size.toShort()).put(b.envelope).array()
    }

    // ── Radio to phone ────────────────────────────────────────────────────

    /** Parses what the radio sent; null for anything malformed, unknown or meant for the radio. */
    fun decode(bytes: ByteArray): Frame? = runCatching {
        if (bytes.isEmpty()) return null
        when (bytes[0]) {
            T_INFO -> decodeInfo(bytes)?.let { Frame.InfoFrame(it) }
            T_BUNDLE_IN -> {
                val b = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
                val bid = b.long
                readLinkBundle(b)?.let { Frame.BundleIn(bid, it) }
            }
            T_LITE_IN, T_PAIR_IN -> {
                if (bytes.size < 5) return null
                val rssi = ((bytes[1].toInt() shl 8) or (bytes[2].toInt() and 0xFF)).toShort().toInt()
                val snr = bytes[3] / 4f
                val body = bytes.copyOfRange(4, bytes.size)
                if (body.size > BODY_MAX) return null
                if (bytes[0] == T_LITE_IN) Frame.LiteIn(body, rssi, snr) else Frame.PairIn(body, rssi, snr)
            }
            else -> null
        }
    }.getOrNull()

    private fun readLinkBundle(b: ByteBuffer): LinkBundle? {
        val tag = ByteArray(TAG_LEN).also { b.get(it) }
        val hops = b.get().toInt() and 0xFF
        val created = b.long
        val len = b.short.toInt() and 0xFFFF
        if (len == 0 || len > MAX_ENVELOPE || b.remaining() != len) return null
        return LinkBundle(tag, hops, created, ByteArray(len).also { b.get(it) })
    }

    private fun decodeInfo(bytes: ByteArray): Info? {
        val b = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
        val version = b.get().toInt() and 0xFF
        val board = Board.of(b.get().toInt() and 0xFF)
        val fwLen = b.get().toInt() and 0xFF
        if (b.remaining() < fwLen) return null
        val fw = ByteArray(fwLen).also { b.get(it) }
        val preset = b.get().toInt() and 0xFF
        val power = b.get().toInt()
        val hop = b.get().toInt() and 0xFF
        val channel = b.get().toInt() and 0xFF
        val nid = ByteArray(8).also { b.get(it) }
        val flags = b.get().toInt()
        return Info(
            version, board, String(fw, Charsets.UTF_8), preset, power, hop, channel, nid,
            publicNetwork = flags and 1 != 0, standalone = flags and 2 != 0,
            batteryMv = b.short.toInt() and 0xFFFF, stored = b.short.toInt() and 0xFFFF,
            heard = b.int.toLong() and 0xFFFFFFFFL, sent = b.int.toLong() and 0xFFFFFFFFL,
            relayed = b.int.toLong() and 0xFFFFFFFFL, airtimePermille = b.short.toInt() and 0xFFFF
        )
    }

    /** The radio's own encoding of [i]; the phone never sends Info, but tests and equality use it. */
    fun encodeInfo(i: Info): ByteArray {
        val fw = i.firmware.toByteArray(Charsets.UTF_8).let { if (it.size > 32) it.copyOf(32) else it }
        return ByteBuffer.allocate(4 + fw.size + 4 + 8 + 1 + 2 + 2 + 12 + 2)
            .put(T_INFO).put(i.version.toByte()).put(i.board.code.toByte()).put(fw.size.toByte()).put(fw)
            .put(i.preset.toByte()).put(i.txPower.toByte()).put(i.hopLimit.toByte()).put(i.channel.toByte())
            .put(i.networkId, 0, 8)
            .put(((if (i.publicNetwork) 1 else 0) or (if (i.standalone) 2 else 0)).toByte())
            .putShort(i.batteryMv.toShort()).putShort(i.stored.toShort())
            .putInt(i.heard.toInt()).putInt(i.sent.toInt()).putInt(i.relayed.toInt())
            .putShort(i.airtimePermille.toShort()).array()
    }

    /** The radio's encoding of a heard Lite or Pair body, for tests: SNR is kept in quarter decibels. */
    fun heard(type: Byte, body: ByteArray, rssi: Int, snr: Float): ByteArray {
        // As the radio rounds: half away from zero.
        val s = snr * 4f
        val q = (if (s >= 0f) s + 0.5f else s - 0.5f).toInt().coerceIn(-128, 127)
        return ByteBuffer.allocate(4 + body.size).put(type).putShort(rssi.toShort()).put(q.toByte()).put(body).array()
    }

    /** The radio's encoding of a BundleIn, for tests. */
    fun bundleIn(bid: Long, b: LinkBundle): ByteArray? {
        val body = linkBundle(b) ?: return null
        return ByteBuffer.allocate(9 + body.size).put(T_BUNDLE_IN).putLong(bid).put(body).array()
    }
}
