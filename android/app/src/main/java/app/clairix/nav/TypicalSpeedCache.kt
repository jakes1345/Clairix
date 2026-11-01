package app.clairix.nav

import android.content.Context
import app.clairix.Store
import app.clairix.security.HistoryRetention
import org.json.JSONObject
import java.io.File

/**
 * The hour-of-week speed profiles the router fetched for a region, kept on disk
 * so a week's routes are not asked for again ([TrafficClient]). The road
 * segments in it are the ones on routes the router was asked about, which says
 * where the owner meant to go, so the file is sealed like the scanner's stores
 * ([Store]: a key in the phone's secure hardware; one written in the clear by an
 * earlier version is sealed the first time it is read). A profile is dropped
 * once it is a week old, or older than the owner's history retention when that
 * is shorter: as the cache is read, as it is written, and on the retention
 * schedule ([HistoryRetention]).
 */
internal object TypicalSpeedCache {

    /** A profile fetched this long ago (seconds) is fetched again rather than used. */
    const val FRESH_SEC = 7L * 24 * 3600

    private const val DIR = "nav"
    private const val PREFIX = "typical-"
    private const val SUFFIX = ".json"

    fun file(context: Context, region: String): File = File(File(context.applicationContext.filesDir, DIR), "$PREFIX$region$SUFFIX")

    /** The oldest fetch time (epoch seconds) kept at [nowSec]: a week back, or the retention cutoff when that is later. */
    fun oldestKeptSec(context: Context, nowSec: Long): Long =
        oldestKeptSec(HistoryRetention.cutoff(context, nowSec * 1000), nowSec)

    internal fun oldestKeptSec(cutoffMs: Long?, nowSec: Long): Long {
        val week = nowSec - FRESH_SEC
        return if (cutoffMs == null) week else maxOf(week, cutoffMs / 1000)
    }

    /** Drops the profiles fetched before [oldestSec] from a cache document; returns how many. */
    fun dropOlderThan(root: JSONObject, oldestSec: Long): Int {
        val segs = root.optJSONObject("segments") ?: return 0
        val stale = ArrayList<String>()
        for (key in segs.keys()) {
            val t = segs.optJSONObject(key)?.optLong("t", 0L) ?: 0L
            if (t < oldestSec) stale += key
        }
        for (key in stale) segs.remove(key)
        return stale.size
    }

    /**
     * Applies the retention to every region's cache: profiles fetched before
     * [cutoff] (or over a week before [now]) go, and a cache left with none is
     * deleted, with anything a failed write or an unreadable file left beside
     * it. Returns how many profiles went. File I/O and Keystore work.
     */
    fun prune(context: Context, cutoff: Long, now: Long): Int {
        val dir = File(context.applicationContext.filesDir, DIR)
        // Leftovers: a write's temp file, a cache Store set aside as unreadable.
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && (f.name.endsWith(".tmp") || f.name.endsWith(".unreadable")) }
            ?.forEach { it.delete() }
        val caches = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) } ?: return 0
        val oldest = oldestKeptSec(cutoff, now / 1000)
        var removed = 0
        for (f in caches) {
            val store = Store(f)
            val root = store.json
            val dropped = dropOlderThan(root, oldest)
            removed += dropped
            val empty = (root.optJSONObject("segments")?.length() ?: 0) == 0
            // Written even when it is about to go: a cache found in the clear has
            // its sealed copy queued by the store, and this newer write is what
            // stops that one landing after the delete.
            if (dropped > 0 || empty) store.replace(root)
            if (empty) f.delete()
        }
        return removed
    }
}
