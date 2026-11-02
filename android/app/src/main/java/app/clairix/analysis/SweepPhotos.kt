package app.clairix.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.clairix.KeystoreStoreCipher
import app.clairix.StoreCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Photos the owner chose to keep from the camera sweep ("Keep a photo of this" on
 * a red candidate): the viewfinder as it was, with the candidates circled and the
 * time, the place and where in the frame burned in, so the picture explains itself
 * to an officer or an advocate without the app.
 *
 * Nothing is kept unless the owner presses the button. Each photo is sealed with
 * the same Keystore key as the scanner's stores ([KeystoreStoreCipher]) under
 * `files/sweep/`, and named on the timeline event it belongs to
 * ([app.clairix.TimelineEvent.attachment]); it is deleted when that event is
 * (Clear all data, the history retention, the timeline's cap), and the wipe
 * deletes the folder and the key.
 *
 * The file and crypto parts take a directory and a cipher so they can be tested;
 * [render] draws with Android's graphics and is not.
 */
object SweepPhotos {

    /** The folder under the app's files directory, and the prefix of every attachment name. */
    const val DIR = "sweep"

    /** Marks a sealed photo; the ciphertext follows. */
    private val MAGIC: ByteArray = "CLXP1\n".toByteArray(Charsets.US_ASCII)

    /** The file names [save] makes: 32 hex digits. Anything else is not one of ours. */
    private val NAME = Regex("[0-9a-f]{32}\\.clxp")

    private const val JPEG_QUALITY = 90

    /** Whether [attachment] names a sweep photo. */
    fun isPhoto(attachment: String?): Boolean = attachment != null && attachment.startsWith("$DIR/")

    /**
     * Seals [jpeg] into a new file under [filesDir]/sweep/ and returns its attachment
     * name ("sweep/<file>"). Written to a temporary name and renamed, so a crash
     * leaves no half-written photo behind. Throws when it cannot be written.
     */
    fun save(filesDir: File, jpeg: ByteArray, cipher: StoreCipher = KeystoreStoreCipher): String {
        val dir = File(filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val name = UUID.randomUUID().toString().replace("-", "") + ".clxp"
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.writeBytes(MAGIC + cipher.sealBytes(jpeg))
            if (!tmp.renameTo(File(dir, name))) throw IOException("cannot write $name")
        } finally {
            tmp.delete()
        }
        return "$DIR/$name"
    }

    /** The photo [attachment] names, opened; null when it is gone, not ours, or cannot be opened. */
    fun open(filesDir: File, attachment: String, cipher: StoreCipher = KeystoreStoreCipher): ByteArray? {
        val f = fileOf(filesDir, attachment) ?: return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (bytes.size <= MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return runCatching { cipher.openBytes(bytes.copyOfRange(MAGIC.size, bytes.size)) }.getOrNull()
    }

    /** Deletes the photos [attachments] name; names that are not sweep photos are left alone. */
    fun delete(filesDir: File, attachments: Collection<String>) {
        for (a in attachments) fileOf(filesDir, a)?.let { runCatching { it.delete() } }
    }

    /**
     * Deletes every sweep photo under [filesDir], and any half-written one, and returns
     * how many went. For "Clear all data", which clears every timeline event a photo can
     * belong to: an event in a timeline this run could not read is cleared without being
     * read, so [delete] by name never hears of its photo.
     */
    fun deleteAll(filesDir: File): Int =
        File(filesDir, DIR).listFiles { f -> f.isFile && (NAME.matches(f.name) || NAME.matches(f.name.removeSuffix(".tmp"))) }
            ?.count { it.delete() } ?: 0

    /** The file an attachment names, or null when it is not a sweep photo (no paths out of the folder). */
    internal fun fileOf(filesDir: File, attachment: String): File? {
        if (!isPhoto(attachment)) return null
        val name = attachment.substring(DIR.length + 1)
        if (!NAME.matches(name)) return null
        return File(File(filesDir, DIR), name)
    }

    /**
     * The lines burned into a kept photo: what and when (UTC, and the phone's own
     * time), where the phone was, and where in the frame the candidate is.
     */
    fun caption(
        ts: Long, zone: TimeZone, lat: Double?, lon: Double?, method: String, where: String
    ): List<String> = listOf(
        "Clairix hidden-camera sweep: possible camera, $method",
        AppEvidence.stamp(ts, zone),
        if (lat != null && lon != null) String.format(Locale.US, "Phone at %.5f, %.5f", lat, lon) else "No position fix when this was taken",
        where
    )

    /**
     * The viewfinder picture [shot] with each of [marks] circled as the sweep screen
     * draws it and [caption] in a band along the bottom, as a JPEG. [density] turns
     * the screen's dp sizes into the picture's pixels. Null when it cannot be drawn.
     */
    fun render(shot: Bitmap, marks: List<GlintBlob>, caption: List<String>, density: Float): ByteArray? {
        val bmp = (if (shot.isMutable && shot.config == Bitmap.Config.ARGB_8888) shot else shot.copy(Bitmap.Config.ARGB_8888, true)) ?: return null
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val red = Color.rgb(255, 59, 48)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = red; strokeWidth = 3f * density }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = red; alpha = 140; strokeWidth = 1.5f * density }
        for (b in marks) {
            val cx = b.x * w
            val cy = b.y * h
            val r = max(14f * density, b.radius * min(w, h) * 3f)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, r * 1.7f, halo)
            canvas.drawLine(cx - r * 1.9f, cy, cx - r * 1.2f, cy, ring)
            canvas.drawLine(cx + r * 1.2f, cy, cx + r * 1.9f, cy, ring)
            canvas.drawLine(cx, cy - r * 1.9f, cx, cy - r * 1.2f, ring)
            canvas.drawLine(cx, cy + r * 1.2f, cx, cy + r * 1.9f, ring)
        }
        val pad = 8f * density
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.MONOSPACE
            textSize = max(11f * density, w / 36f)
        }
        // Shrink the type until the longest line fits the width.
        val widest = caption.maxOfOrNull { text.measureText(it) } ?: 0f
        if (widest > w - 2 * pad && widest > 0f) text.textSize *= (w - 2 * pad) / widest
        val line = text.fontSpacing
        val band = line * caption.size + 2 * pad
        canvas.drawRect(0f, h - band, w, h, Paint().apply { color = Color.argb(190, 0, 0, 0) })
        caption.forEachIndexed { i, s -> canvas.drawText(s, pad, h - band + pad + line * (i + 1) - text.descent(), text) }
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
        return out.toByteArray()
    }
}
