package app.clairix.comms

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Envelopes that arrived while the [Vault] was sealed, kept as they came:
 * still sealed to this phone, and wrapped once more under [KeystoreBox] (the
 * key that works while the phone is locked) so the relay's copy of an
 * envelope cannot be matched to a file here. Read in arrival order once the
 * owner unlocks, then deleted.
 */
class LockedInbox(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "locked_inbox")

    class Entry(val file: File, val envelope: RelayClient.Envelope)

    /**
     * Keeps [envelope] until the vault opens. A second copy of one already kept
     * is ignored, and false says so: it has been announced already.
     */
    @Synchronized
    fun put(envelope: RelayClient.Envelope): Boolean {
        dir.mkdirs()
        val f = File(dir, "%016d-%s.bin".format(envelope.ts.coerceAtLeast(0), digest(envelope.data)))
        if (f.exists()) return false
        val json = JSONObject()
            .put("id", envelope.id)
            .put("ts", envelope.ts)
            .put("data", Base64.getEncoder().encodeToString(envelope.data))
            .apply { envelope.alert?.let { put("alert", it) } }
        val tmp = File(dir, f.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(KeystoreBox.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw java.io.IOException("could not keep an envelope for after unlock")
        }
        return true
    }

    @Synchronized
    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".bin") }?.size ?: 0

    /** Everything waiting, oldest first. One that cannot be read is deleted: it never will be. */
    @Synchronized
    fun all(): List<Entry> {
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val json = JSONObject(String(KeystoreBox.decrypt(f.readBytes()), Charsets.UTF_8))
                Entry(
                    f,
                    RelayClient.Envelope(
                        json.getString("id"), json.getLong("ts"), Base64.getDecoder().decode(json.getString("data")),
                        json.optString("alert").takeIf { it.isNotEmpty() }
                    )
                )
            }.getOrElse { f.delete(); null }
        }
    }

    @Synchronized
    fun remove(entry: Entry) { entry.file.delete() }

    @Synchronized
    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun digest(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).take(12).joinToString("") { "%02x".format(it) }
}
