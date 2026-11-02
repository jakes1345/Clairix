package app.clairix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.clairix.security.Wiper
import org.json.JSONObject
import java.io.File

/** What [CrashJournal] kept of the last crash. */
data class CrashRecord(
    val ts: Long,
    val version: String,
    val model: String,
    val sdk: Int,
    /** Exception classes and stack frames only: no exception messages. */
    val trace: String
) {
    /** The text Copy and Share hand over: the device line, then the trace. */
    fun asText(): String = "Clairix $version on $model, Android SDK $sdk\n\n$trace"
}

/**
 * A local record of the last crash, for the owner to send if they choose.
 *
 * Clairix has no crash reporter and sends nothing about itself anywhere. A
 * crash in the field therefore left no trace a person without adb could pass
 * on. This keeps one: [install] puts an uncaught-exception handler in front of
 * the platform's, which writes the stack trace (exception classes and frames,
 * never an exception's message, which can hold an address, a number or a file
 * name), the app version, the phone model and the Android SDK level to the
 * sealed store `last_crash.json`, then hands the crash on as before. Settings,
 * About, shows it on the next start with Copy, Share and Delete; nothing goes
 * on the network, and only the newest crash is kept.
 *
 * A wipe deletes it with everything else under the app's files, and no crash
 * is recorded while a wipe is running or waiting to finish, so the decoy a
 * duress code leaves never says that the app crashed in the middle of one.
 */
object CrashJournal {

    const val FILE = "last_crash.json"
    const val SUPPORT_ADDRESS = "support@clairix.app"

    /** Throwables of a cause chain written out, and frames of each. */
    private const val MAX_CAUSES = 8
    private const val MAX_FRAMES = 64

    @Volatile
    private var installed = false

    /** Puts the journal's handler in front of the current default one. Once per process. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (!Wiper.isActive && !Wiper.isPending(app)) write(app, error) }
            if (previous != null) previous.uncaughtException(thread, error) else Runtime.getRuntime().halt(1)
        }
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)

    private fun write(context: Context, error: Throwable) {
        val record = CrashRecord(
            ts = System.currentTimeMillis(),
            version = BuildConfig.VERSION_NAME,
            model = Build.MODEL ?: "unknown",
            sdk = Build.VERSION.SDK_INT,
            trace = trace(error)
        )
        Store(file(context)).replace(toJson(record))
    }

    /** The last crash, or null when there is none (or it cannot be read). Opens the sealed file: off the main thread. */
    fun read(context: Context): CrashRecord? {
        val f = file(context)
        if (!f.exists()) return null
        return fromJson(Store(f).load { JSONObject() })
    }

    /** Forgets the last crash. */
    fun delete(context: Context) {
        runCatching { file(context).delete() }
    }

    internal fun toJson(r: CrashRecord): JSONObject = JSONObject()
        .put("ts", r.ts)
        .put("version", r.version)
        .put("model", r.model)
        .put("sdk", r.sdk)
        .put("trace", r.trace)

    internal fun fromJson(o: JSONObject): CrashRecord? {
        val trace = o.optString("trace")
        if (trace.isEmpty()) return null
        return CrashRecord(
            ts = o.optLong("ts"),
            version = o.optString("version"),
            model = o.optString("model"),
            sdk = o.optInt("sdk"),
            trace = trace
        )
    }

    /**
     * [error]'s stack trace without a single message: each throwable of the cause
     * chain by its class name, then its frames, as `printStackTrace` lays them out.
     * At most [MAX_CAUSES] throwables of [MAX_FRAMES] frames each; a cause that
     * points back into the chain ends it.
     */
    internal fun trace(error: Throwable): String {
        val out = StringBuilder()
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            if (depth > 0) out.append("Caused by: ")
            out.append(current.javaClass.name).append('\n')
            val frames = current.stackTrace
            for (frame in frames.take(MAX_FRAMES)) out.append("\tat ").append(frame.toString()).append('\n')
            if (frames.size > MAX_FRAMES) out.append("\t... ").append(frames.size - MAX_FRAMES).append(" more\n")
            current = current.cause
            depth++
        }
        return out.toString().trimEnd()
    }

    /** The subject of a problem report: `Clairix <version> on <model>/<sdk>`. */
    internal fun reportSubject(version: String, model: String, sdk: Int): String = "Clairix $version on $model/$sdk"

    /**
     * The body a problem report starts from: the device line and questions for the
     * owner to answer, nothing else. They edit it, and it goes from their own mail
     * app only if they send it.
     */
    internal fun reportBody(version: String, model: String, sdk: Int): String =
        "What happened:\n\n\nWhat you expected:\n\n\nWhat you were doing just before:\n\n\n" +
            "--\nClairix $version, $model, Android SDK $sdk\n" +
            "Please do not include screenshots of conversations or anyone else's personal information."

    /** A problem report to [SUPPORT_ADDRESS] for the owner's own mail app, prefilled with [reportSubject] and [reportBody]. */
    fun reportIntent(): Intent {
        val subject = reportSubject(BuildConfig.VERSION_NAME, Build.MODEL ?: "unknown", Build.VERSION.SDK_INT)
        val body = reportBody(BuildConfig.VERSION_NAME, Build.MODEL ?: "unknown", Build.VERSION.SDK_INT)
        val uri = Uri.parse("mailto:$SUPPORT_ADDRESS?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        return Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
    }
}
