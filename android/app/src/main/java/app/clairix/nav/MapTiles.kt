package app.clairix.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.clairix.BuildConfig
import java.io.File
import org.osmdroid.config.Configuration as OsmConfig

/**
 * Where the map keeps its tiles, for the Map tab and navigation alike.
 *
 * A tile cache is a record of where the owner has been: every area looked at,
 * every route driven, at street level, with when each tile was fetched. By
 * default osmdroid keeps it in shared external app storage, which a file
 * extraction reads and an ADB pull may reach; here it lives in the app's
 * private no-backup directory, is capped in size, and is pruned with the rest
 * of the history ([prune], from [app.clairix.security.HistoryRetention]).
 */
object MapTiles {

    /**
     * Tiles are kept a week past what the server allows, so a map left open
     * does not download its whole viewport again on every redraw; the server's
     * own headers stay the minimum, as the OSM tile usage policy asks.
     */
    const val EXTRA_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    private const val CACHE_MAX_BYTES = 64L * 1024 * 1024
    private const val CACHE_TRIM_BYTES = 48L * 1024 * 1024

    private var configured = false

    private fun base(context: Context) = File(context.noBackupFilesDir, "osmdroid")

    /** Sets osmdroid up once per process. Main thread (called from composition). */
    fun configure(context: Context) {
        if (configured) return
        configured = true
        val app = context.applicationContext
        with(OsmConfig.getInstance()) {
            load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "Clairix/${BuildConfig.VERSION_NAME}"
            expirationExtendedDuration = EXTRA_LIFETIME_MS
            osmdroidBasePath = base(app)
            osmdroidTileCache = File(base(app), "tiles")
            tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
        }
        // Where earlier versions kept it: shared external storage.
        runCatching { app.getExternalFilesDir(null)?.let { File(it, "osmdroid").deleteRecursively() } }
    }

    /**
     * Deletes tiles fetched before [cutoff]. A tile's row keeps when it expires,
     * which is when it was fetched plus the server's lifetime plus
     * [EXTRA_LIFETIME_MS]; one that expired before [cutoff] plus that week was
     * fetched before [cutoff] at the latest. File I/O; off the main thread.
     */
    fun prune(context: Context, cutoff: Long) {
        val db = File(File(base(context.applicationContext), "tiles"), "cache.db")
        if (!db.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.delete("tiles", "expires < ?", arrayOf((cutoff + EXTRA_LIFETIME_MS).toString()))
            }
        }
    }
}
