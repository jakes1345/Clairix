package app.clairix.analysis

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.clairix.Detection
import app.clairix.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The evidence report: the text a person reads ([toText]) or the JSON a tool reads
 * ([toJson]), each ending in a SHA-256 of the body and a signature over it by a
 * per-install Keystore key, written to the share area with any photos kept from
 * the camera sweep and handed to the share sheet ([share], [shareJson]).
 */
object Report {

    /** How long an exported report stays in the cache for a share target to read. */
    const val EXPORT_RETENTION_MS = 24 * 60 * 60_000L

    /**
     * Reports live in a folder of their own, the only cache folder the file
     * provider exposes besides the camera's; earlier versions wrote them at the
     * cache root, where a share target could reach everything else too.
     */
    private const val REPORTS_DIR = "reports"

    /** What an export writes: the report and the photos that go with it, each starting "clairix_". */
    private val EXPORT_EXTENSIONS = setOf("txt", "json", "jpg")

    private const val SUBJECT = "Clairix Evidence Report"

    /**
     * Deletes exported reports and photos older than [EXPORT_RETENTION_MS], and any
     * left at the cache root by earlier versions; returns how many went. A report is
     * the one file the app writes in the clear, with the positions where devices were
     * seen, and a photo shared with it is decrypted for the share, so neither is left
     * lying in the cache: they are kept a day, not deleted at once, because a mail
     * client reads the attachment when it actually sends, which offline can be well
     * after the share, and deleting the file underneath it lost the report. Runs at
     * every export and on the retention schedule
     * ([app.clairix.security.HistoryRetention]), so the last export does not wait
     * for the next one to go.
     */
    fun pruneExports(cacheDir: File, now: Long): Int {
        val isExport = java.io.FileFilter { f -> f.isFile && f.name.startsWith("clairix_") && f.extension in EXPORT_EXTENSIONS }
        var removed = 0
        cacheDir.listFiles(isExport)?.forEach { if (it.delete()) removed++ }
        File(cacheDir, REPORTS_DIR).listFiles(isExport)
            ?.filter { now - it.lastModified() > EXPORT_RETENTION_MS }
            ?.forEach { if (it.delete()) removed++ }
        return removed
    }

    // ── Building ────────────────────────────────────────────────────────────

    /**
     * The human-readable report: the body ([ReportText]), then the integrity block
     * with the SHA-256 of the body and [signer]'s signature over the same bytes.
     */
    fun toText(header: ReportHeader, input: ReportInput, range: ReportRange, signer: ReportSigner?): String {
        val body = ReportText.body(header, input, range)
        val seal = ReportSigning.seal(body.toByteArray(Charsets.UTF_8), signer, header.signing)
        return body + integrityText(seal, header.signing)
    }

    /**
     * The machine-readable report. The first line is `{"report":` followed by the
     * body as compact JSON and a comma; the body's bytes are what is hashed and
     * signed, and the `integrity` member after it says so and how to check.
     */
    fun toJson(header: ReportHeader, input: ReportInput, range: ReportRange, signer: ReportSigner?): String {
        val body = ReportJson.body(header, input, range).toString()
        val seal = ReportSigning.seal(body.toByteArray(Charsets.UTF_8), signer, header.signing)
        val integrity = JSONObject().apply {
            put("sha256", seal.sha256)
            put("algorithm", header.signing.algorithm)
            seal.signature?.let { put("signature", it) }
            seal.failure?.let { put("signature_unavailable", it) }
            put("signed_bytes", "the value of \"report\" exactly as written on the first line of this file, without the comma that ends the line")
            put("how_to_check", JSONArray().also { a -> checkCommands(ReportFormat.JSON, header.signing, seal).forEach { a.put(it) } })
        }
        return "{\"report\":$body,\n\"integrity\":${integrity.toString(2)}}\n"
    }

    fun render(format: ReportFormat, header: ReportHeader, input: ReportInput, range: ReportRange, signer: ReportSigner?): String =
        when (format) {
            ReportFormat.TEXT -> toText(header, input, range, signer)
            ReportFormat.JSON -> toJson(header, input, range, signer)
        }

    /** "Apple AirTag (address …) was heard with this phone at 4 places 412 m apart, …": one device, in sentences. */
    fun summary(d: Detection, header: ReportHeader): String = ReportText.summary(d, ReportTime(header.zone))

    private fun integrityText(seal: ReportSigning.Seal, key: SigningKey): String = buildString {
        appendLine(ReportText.INTEGRITY_MARKER)
        appendLine("SHA-256 of everything above this block (UTF-8): ${seal.sha256}")
        if (seal.signature != null) {
            appendLine("Signature (${key.algorithm}, DER, base64): ${seal.signature}")
        } else {
            appendLine(
                "Signature: none (${seal.failure}). Anyone can recompute a SHA-256 after an edit, " +
                    "so on its own it does not show the text is unchanged."
            )
        }
        appendLine()
        appendLine("To check this file on a computer with OpenSSL, save it as report.txt and run:")
        checkCommands(ReportFormat.TEXT, key, seal).forEach { appendLine("  $it") }
        appendLine()
        appendLine(VERIFY_MEANING)
    }

    private const val VERIFY_MEANING =
        "The second command prints the hash above. The last prints \"Verified OK\" when the text above this block is " +
            "exactly what this phone wrote and signed. The key fingerprint (the SHA-256 of key.der) is shown on the " +
            "phone when a report is exported and is the same in every report from this install, until Clairix is " +
            "wiped or reinstalled; compare it to know the key is this phone's. A match says nothing about who held the phone."

    /** The OpenSSL commands that check a report saved as report.txt or report.json. */
    internal fun checkCommands(format: ReportFormat, key: SigningKey, seal: ReportSigning.Seal): List<String> = buildList {
        when (format) {
            ReportFormat.TEXT -> add("sed '/^${ReportText.INTEGRITY_MARKER}\$/,\$d' report.txt > body.txt")
            ReportFormat.JSON -> add("head -n 1 report.json | sed -e 's/^{\"report\"://' -e 's/,\$//' | tr -d '\\n' > body.txt")
        }
        add("openssl dgst -sha256 body.txt")
        val publicKey = key.publicKey
        val signature = seal.signature
        if (publicKey != null && signature != null) {
            add("echo '$publicKey' | base64 -d > key.der")
            add("openssl dgst -sha256 key.der")
            add("openssl pkey -pubin -inform DER -in key.der -out key.pem")
            add("echo '$signature' | base64 -d > sig.der")
            add("openssl dgst -sha256 -verify key.pem -signature sig.der body.txt")
        }
    }

    // ── Sharing ─────────────────────────────────────────────────────────────

    /** The photos going with a report: the share name of each by its event's id, and the opened bytes by share name. */
    data class SharedPhotos(val names: Map<String, String>, val files: List<Pair<String, ByteArray>>)

    /** "clairix_<now>.<ext>": the report's file name. */
    fun reportName(now: Long, format: ReportFormat): String = "clairix_$now.${format.extension}"

    /** "clairix_<now>_photo_<n>.jpg", counting from 1: the share name of a kept photo. */
    fun photoName(now: Long, index: Int): String = "clairix_${now}_photo_$index.jpg"

    /**
     * The photos kept on camera events in [range], opened with [open] (which
     * decrypts one by its attachment name, null when it cannot); a photo that does
     * not open is left out and the report says so.
     */
    fun sharedPhotos(timeline: List<TimelineEvent>, range: ReportRange, now: Long, open: (String) -> ByteArray?): SharedPhotos {
        val names = LinkedHashMap<String, String>()
        val files = ArrayList<Pair<String, ByteArray>>()
        for (e in ReportText.photoEvents(timeline, range)) {
            val bytes = e.attachment?.let { runCatching { open(it) }.getOrNull() } ?: continue
            val name = photoName(now, files.size + 1)
            names[e.id] = name
            files += name to bytes
        }
        return SharedPhotos(names, files)
    }

    /**
     * Writes the report [content] and the opened [photos] into the share folder
     * under [cacheDir], and returns the files, the report first. The photos are
     * plain JPEGs there, as the report is plain text: both go after a day
     * ([pruneExports]).
     */
    fun writeExport(cacheDir: File, now: Long, format: ReportFormat, content: String, photos: List<Pair<String, ByteArray>>): List<File> {
        val dir = File(cacheDir, REPORTS_DIR).also { it.mkdirs() }
        val report = File(dir, reportName(now, format)).also { it.writeText(content, Charsets.UTF_8) }
        return listOf(report) + photos.map { (name, bytes) -> File(dir, name).also { it.writeBytes(bytes) } }
    }

    /** The share's MIME type: the report's own alone, any type once photos go with it. */
    fun shareMime(format: ReportFormat, fileCount: Int): String = if (fileCount <= 1) format.mimeType else "*/*"

    /**
     * Writes the report in [format] with the photos kept in [range] and returns the
     * share intent: one file with ACTION_SEND, the report and its photos with
     * ACTION_SEND_MULTIPLE, all through the file provider. Keystore, decryption
     * and file I/O; call off the main thread.
     */
    fun share(
        context: Context,
        header: ReportHeader,
        input: ReportInput,
        range: ReportRange,
        format: ReportFormat = ReportFormat.TEXT,
        signer: ReportSigner? = KeystoreReportSigner
    ): Intent {
        val app = context.applicationContext
        val now = header.generatedAt
        pruneExports(app.cacheDir, now)
        val photos = sharedPhotos(input.timeline, range, now) { SweepPhotos.open(app.filesDir, it) }
        val content = render(format, header, input.copy(photos = photos.names), range, signer)
        val files = writeExport(app.cacheDir, now, format, content, photos.files)
        val uris: List<Uri> = files.map { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return intent.apply {
            type = shareMime(format, uris.size)
            clipData = ClipData.newRawUri(SUBJECT, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            if (format == ReportFormat.TEXT) putExtra(Intent.EXTRA_TEXT, content.take(2000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** [share] as clairix_<ts>.json, application/json. */
    fun shareJson(
        context: Context,
        header: ReportHeader,
        input: ReportInput,
        range: ReportRange,
        signer: ReportSigner? = KeystoreReportSigner
    ): Intent = share(context, header, input, range, ReportFormat.JSON, signer)

    // ── What the screens say about it ───────────────────────────────────────

    /**
     * The warning in front of every export. N comes from the range the owner
     * picked, or from the retention when the report covers everything kept.
     */
    fun preShareWarning(range: ReportRange, retentionDays: Int, now: Long): String {
        val from = range.from
        val to = range.to
        val span = when {
            from != null && to != null && to < now - TimeUnit.DAYS.toMillis(1) ->
                "for the ${days(daysBetween(from, to))} you picked"
            from != null -> "for the last ${days(daysBetween(from, now))}"
            retentionDays == 0 -> "for as far back as Clairix has kept it"
            else -> "for the last ${days(retentionDays)}"
        }
        return "This report contains your own location history $span and device identifiers. " +
            "Share it only with people you trust."
    }

    /** The Timeline's footer: what the retention deletes and that an export keeps it; null when nothing is deleted. */
    fun timelineFooter(retentionDays: Int): String? =
        if (retentionDays == 0) null
        else "Entries older than ${days(retentionDays)} are deleted automatically. " +
            "Export the evidence report to keep them: Settings, Export evidence."

    private fun daysBetween(from: Long, to: Long): Int =
        ((to - from + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)).toInt().coerceAtLeast(1)

    private fun days(n: Int): String = if (n == 1) "1 day" else "$n days"
}
