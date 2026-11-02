package app.clairix.comms.mesh

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * What [MeshAware] says over Wi-Fi Aware, kept apart from the radio so it can be
 * tested: the instance id each phone publishes, the offer that starts a meeting, and
 * the length-prefixed frames a meeting sends over its socket.
 */
object MeshAwareWire {

    /** The service both sides publish and look for. Anyone running Wi-Fi Aware can see that it is offered, as with the Bluetooth service. */
    const val SERVICE_NAME = "org.clairix.mesh"
    const val ID_LEN = 8
    const val PASS_LEN = 24
    private const val OFFER = 0x4D.toByte()

    /** The largest frame a meeting may send: the same bound as the Bluetooth reassembler's. */
    const val MAX_FRAME = 2 + MeshProtocol.MAX_STORE * 2 * MeshProtocol.ID_LEN + MeshProtocol.MAX_ENVELOPE + 64

    private val random = SecureRandom()

    /** A fresh instance id: random, made again every time the mesh starts, so it names no phone for long. */
    fun newId(): ByteArray = ByteArray(ID_LEN).also { random.nextBytes(it) }

    /**
     * Whether this phone, having found [peer], should start the meeting: the lower id
     * starts, so two phones that find each other at once hold one meeting, not two.
     */
    fun initiates(own: ByteArray, peer: ByteArray): Boolean {
        for (i in 0 until ID_LEN) {
            val a = own[i].toInt() and 0xFF
            val b = peer[i].toInt() and 0xFF
            if (a != b) return a < b
        }
        return false
    }

    /** A passphrase for one data path: 24 characters from 18 random bytes, within Wi-Fi Aware's 8 to 63. */
    fun newPassphrase(): String {
        val b = ByteArray(18).also { random.nextBytes(it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** The message that asks the publisher for a meeting: the starter's id and the data path's passphrase. */
    fun offer(id: ByteArray, passphrase: String): ByteArray {
        require(id.size == ID_LEN && passphrase.length == PASS_LEN)
        return byteArrayOf(OFFER) + id + passphrase.toByteArray(Charsets.US_ASCII)
    }

    class Offer(val id: ByteArray, val passphrase: String)

    fun parseOffer(b: ByteArray): Offer? {
        if (b.size != 1 + ID_LEN + PASS_LEN || b[0] != OFFER) return null
        val pass = String(b, 1 + ID_LEN, PASS_LEN, Charsets.US_ASCII)
        if (!pass.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return Offer(b.copyOfRange(1, 1 + ID_LEN), pass)
    }

    fun writeFrame(out: OutputStream, frame: ByteArray) {
        require(frame.size in 1..MAX_FRAME)
        out.write(ByteBuffer.allocate(4).putInt(frame.size).array())
        out.write(frame)
        out.flush()
    }

    /** The next frame, or null when the peer closed cleanly between frames. Throws on a bad length or a cut frame. */
    fun readFrame(input: DataInputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val n = (first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        if (n !in 1..MAX_FRAME) throw IOException("frame of $n bytes")
        val b = ByteArray(n)
        try { input.readFully(b) } catch (e: EOFException) { throw IOException("cut frame", e) }
        return b
    }
}
