package app.clairix.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.clairix.BuildConfig
import app.clairix.analysis.ScanPacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.views.MapView
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
 *
 * Which server the tiles come from is [config]: the signed tile setting from
 * the `relays` release once one has been verified ([adopt], called by
 * [app.clairix.comms.RelayDirectory]), OpenStreetMap's own server until then.
 * Every tile request's answer is counted ([outage]), so a server that refuses
 * or fails them all is named on the map instead of leaving it grey.
 */
object MapTiles {

    private const val TAG = "MapTiles"

    /** The project's site and support address, published on clairix.app and in the terms. */
    private const val CONTACT_URL = "https://clairix.app"
    private const val CONTACT_EMAIL = "support@clairix.app"

    /**
     * What the tile server sees on every request: the app, its version and
     * how to reach the project, as the OpenStreetMap tile usage policy asks.
     */
    internal fun userAgent(version: String): String = "Clairix/$version (+$CONTACT_URL; $CONTACT_EMAIL)"

    private val _config = MutableStateFlow(TileConfig.DEFAULT)

    /** The server the maps draw from now. */
    val config: StateFlow<TileConfig> = _config.asStateFlow()

    @Volatile private var heldLoaded = false

    private val health = TileHealth()
    private val healthLock = Any()
    private val _outage = MutableStateFlow<TileHealth.Outage?>(null)

    /** Non-null while the tile server refuses or fails every tile ([TileHealth]). */
    val outage: StateFlow<TileHealth.Outage?> = _outage.asStateFlow()

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
            userAgentValue = userAgent(BuildConfig.VERSION_NAME)
            expirationExtendedDuration = EXTRA_LIFETIME_MS
            osmdroidBasePath = base(app)
            osmdroidTileCache = File(base(app), "tiles")
            tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
        }
        // Where earlier versions kept it: shared external storage.
        runCatching { app.getExternalFilesDir(null)?.let { File(it, "osmdroid").deleteRecursively() } }
        held(app)
    }

    /**
     * A map view drawing from [config]'s server, every tile answer counted
     * towards [outage]. Call [configure] first; follow a later server with
     * [tileSource] (see [FollowTileServer]).
     */
    fun newMapView(context: Context): MapView =
        MapView(context, ClairixTileProvider(context.applicationContext, tileSource(config.value)))

    /** [config] as osmdroid's tile source. */
    fun tileSource(config: TileConfig): OnlineTileSourceBase = ConfiguredTileSource(config)

    private fun settingFile(context: Context) = File(File(context.applicationContext.filesDir, "relays"), "tiles.json")

    /**
     * The verified tile setting this phone holds, read from disk on first use
     * (it is written there only after its signature checked, by [adopt]);
     * null while it holds none and the maps use [TileConfig.DEFAULT].
     */
    fun held(context: Context): TileConfig? {
        if (!heldLoaded) {
            synchronized(this) {
                if (!heldLoaded) {
                    val stored = runCatching {
                        settingFile(context).takeIf { it.isFile && it.length() <= MAX_SETTING_BYTES }
                            ?.let { TileConfig.parse(it.readText(Charsets.UTF_8), System.currentTimeMillis() / 1000) }
                    }.onFailure { Log.w(TAG, "The stored tile setting is unreadable: ${it.message}") }.getOrNull()
                    if (stored != null && stored.issued > _config.value.issued) _config.value = stored
                    heldLoaded = true
                }
            }
        }
        return _config.value.takeIf { it.issued > 0 }
    }

    /**
     * Keeps a downloaded setting whose signature and issue date
     * [TileConfig.accept] has checked, and moves the maps to its server.
     */
    fun adopt(context: Context, raw: ByteArray, config: TileConfig) {
        val file = settingFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(raw)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        synchronized(this) {
            heldLoaded = true
            if (config.issued > _config.value.issued) _config.value = config
        }
        synchronized(healthLock) {
            health.reset()
            _outage.value = null
        }
    }

    /** The HTTP status one tile request was answered with ([ObservedTileDownloader]). Any thread. */
    internal fun recordAnswer(status: Int) {
        synchronized(healthLock) { _outage.value = health.record(status, System.currentTimeMillis()) }
    }

    /** A tile setting is a few hundred bytes; anything this large is not one. */
    const val MAX_SETTING_BYTES = 16L * 1024

    /**
     * Deletes tiles fetched before [cutoff]. A tile's row keeps when it expires,
     * which is when it was fetched plus the server's lifetime plus
     * [EXTRA_LIFETIME_MS]; one that expired before [cutoff] plus that week was
     * fetched before [cutoff] at the latest. The oldest expiry is read first, from
     * the index osmdroid keeps on it, and the delete runs only when that tile is
     * past the cutoff; on most passes none is. File I/O; off the main thread.
     */
    fun prune(context: Context, cutoff: Long) {
        val db = File(File(base(context.applicationContext), "tiles"), "cache.db")
        if (!db.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                val oldest = it.rawQuery("SELECT MIN(expires) FROM tiles", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
                if (pruneDue(oldest, cutoff)) {
                    it.delete("tiles", "expires < ?", arrayOf((cutoff + EXTRA_LIFETIME_MS).toString()))
                }
            }
        }
    }

    /** Whether a cache whose oldest tile expires at [oldestExpires] (null when it holds none) has any to delete for [cutoff]. */
    internal fun pruneDue(oldestExpires: Long?, cutoff: Long): Boolean =
        ScanPacing.holdsOlderThan(oldestExpires, cutoff + EXTRA_LIFETIME_MS)
}
