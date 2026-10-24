package app.clairix.comms.mesh

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * The offline mesh: phones carry each other's sealed envelopes, hop to hop over
 * Bluetooth, until one reaches the phone it is for. The envelopes are exactly the
 * ones the relay carries (sealed sender, padded, end to end), so a carrier learns
 * nothing it could not learn from the relay, and less: instead of a Clairix number
 * each bundle is addressed by a routing tag, a hash of the recipient's public
 * sealing key and the day. A stranger carrying it cannot tell whose it is; the
 * recipient, and anyone who already has the recipient's keys, can.
 *
 * Epidemic routing with limits: on meeting, two phones swap the ids they hold and
 * each sends what the other lacks, up to [MAX_HOPS] hops, kept [TTL_MS], [MAX_STORE]
 * bundles at most, each at most [MAX_ENVELOPE] bytes (texts and SOS, not media).
 *
 * Pure functions and plain data; the radio is in [MeshNode].
 */
object MeshProtocol {

    /** The GATT service a mesh phone advertises, and the one characteristic both directions use. */
    val SERVICE_UUID: UUID = UUID.fromString("5c1a7e0d-3f1b-4d8e-9a26-c1a1e0f7a3b2")
    val FRAMES_UUID: UUID = UUID.fromString("5c1a7e0d-3f1b-4d8e-9a26-c1a1e0f7a3b3")
    /** The client characteristic configuration descriptor, for notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAX_ENVELOPE = 4_096
    const val MAX_HOPS = 8
    const val TTL_MS = 48L * 3_600_000L
    const val MAX_STORE = 500
    const val TAG_LEN = 16
    const val ID_LEN = 16
    private const val DAY_MS = 86_400_000L

    private const val T_HELLO: Byte = 1
    private const val T_BUNDLE: Byte = 2
    private const val T_DONE: Byte = 3

    data class Bundle(val id: ByteArray, val tag: ByteArray, val hops: Int, val createdMs: Long, val data: ByteArray) {
        val idHex: String get() = hex(id)
        override fun equals(other: Any?) = other is Bundle && id.contentEquals(other.id)
        override fun hashCode() = id.contentHashCode()
    }

    sealed interface Frame {
        data class Hello(val ids: List<ByteArray>) : Frame
        data class Carry(val bundle: Bundle) : Frame
        data object Done : Frame
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun idOf(envelope: ByteArray): ByteArray = sha256(envelope).copyOf(ID_LEN)

    /** The routing tag for a recipient's public sealing key (base64, as contacts hold it) on [day]. */
    fun tag(sealingB64: String, day: Long): ByteArray {
        val key = Base64.getDecoder().decode(sealingB64)
        return sha256("clairix-mesh-tag-v1".toByteArray(), key, ByteBuffer.allocate(8).putLong(day).array()).copyOf(TAG_LEN)
    }

    fun day(ms: Long): Long = Math.floorDiv(ms, DAY_MS)

    /** The tags a phone answers to around [now]: yesterday, today and tomorrow, for clocks that disagree. */
    fun tagsFor(sealingB64: String, now: Long): Set<String> = (-1L..1L).map { hex(tag(sealingB64, day(now) + it)) }.toSet()

    fun newBundle(envelope: ByteArray, recipientSealingB64: String, now: Long): Bundle? {
        if (envelope.size > MAX_ENVELOPE) return null
        return Bundle(idOf(envelope), tag(recipientSealingB64, day(now)), 0, now, envelope)
    }

    fun encode(frame: Frame): ByteArray = when (frame) {
        is Frame.Hello -> {
            val b = ByteBuffer.allocate(1 + 2 + frame.ids.size * ID_LEN)
            b.put(T_HELLO).putShort(frame.ids.size.toShort())
            frame.ids.forEach { b.put(it, 0, ID_LEN) }
            b.array()
        }
        is Frame.Carry -> {
            val x = frame.bundle
            ByteBuffer.allocate(1 + TAG_LEN + 1 + 8 + 4 + x.data.size)
                .put(T_BUNDLE).put(x.tag, 0, TAG_LEN).put(x.hops.coerceIn(0, 255).toByte()).putLong(x.createdMs)
                .putInt(x.data.size).put(x.data).array()
        }
        Frame.Done -> byteArrayOf(T_DONE)
    }

    /** Parses a frame; null for anything malformed or oversized, which a peer can send. */
    fun decode(bytes: ByteArray): Frame? = runCatching {
        val b = ByteBuffer.wrap(bytes)
        when (b.get()) {
            T_HELLO -> {
                val n = b.short.toInt() and 0xFFFF
                if (n > MAX_STORE * 2 || b.remaining() != n * ID_LEN) return null
                Frame.Hello(List(n) { ByteArray(ID_LEN).also { id -> b.get(id) } })
            }
            T_BUNDLE -> {
                val tag = ByteArray(TAG_LEN).also { b.get(it) }
                val hops = b.get().toInt() and 0xFF
                val created = b.long
                val len = b.int
                if (len <= 0 || len > MAX_ENVELOPE || b.remaining() != len) return null
                val data = ByteArray(len).also { b.get(it) }
                Frame.Carry(Bundle(idOf(data), tag, hops, created, data))
            }
            T_DONE -> if (b.hasRemaining()) null else Frame.Done
            else -> null
        }
    }.getOrNull()

    // ── Chunking over a GATT characteristic ───────────────────────────────

    /**
     * Splits a frame into writes of at most [payload] bytes. The first carries a
     * 0x01 marker and the frame's length; the rest a 0x00 marker. GATT writes and
     * notifications on one connection arrive in order, so nothing more is needed.
     */
    fun chunk(frame: ByteArray, payload: Int): List<ByteArray> {
        require(payload >= 8)
        val out = ArrayList<ByteArray>()
        val first = minOf(frame.size, payload - 5)
        out += ByteBuffer.allocate(5 + first).put(1).putInt(frame.size).put(frame, 0, first).array()
        var at = first
        while (at < frame.size) {
            val n = minOf(frame.size - at, payload - 1)
            out += ByteArray(1 + n).also { it[0] = 0; System.arraycopy(frame, at, it, 1, n) }
            at += n
        }
        return out
    }

    /** Puts frames back together from chunks; one per connection and direction. */
    class Reassembler(private val maxFrame: Int = 2 + MAX_STORE * 2 * ID_LEN + MAX_ENVELOPE) {
        private var want = -1
        private val buf = ByteArrayOutputStream()

        /** Returns a whole frame when [chunk] completes one; throws on a malformed stream. */
        fun add(chunk: ByteArray): ByteArray? {
            require(chunk.isNotEmpty()) { "empty chunk" }
            if (chunk[0].toInt() == 1) {
                require(chunk.size >= 5) { "short start chunk" }
                want = ByteBuffer.wrap(chunk, 1, 4).int
                require(want in 1..maxFrame) { "frame of $want bytes" }
                buf.reset()
                buf.write(chunk, 5, chunk.size - 5)
            } else {
                require(want > 0) { "continuation without a start" }
                buf.write(chunk, 1, chunk.size - 1)
            }
            require(buf.size() <= want) { "frame overran its length" }
            if (buf.size() < want) return null
            want = -1
            return buf.toByteArray()
        }
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
