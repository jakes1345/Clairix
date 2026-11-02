package app.clairix.nav

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.CantContinueException
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.osmdroid.config.Configuration as OsmConfig

/**
 * [config]'s server as osmdroid's tile source. The policy is the one osmdroid
 * gives OpenStreetMap's server (two downloads at a time, no bulk download, no
 * prefetching, a User-Agent that means something) except that osmdroid's
 * "normalised" User-Agent is not asked for: it would replace Clairix's own,
 * which carries the contact address the tile usage policy asks for
 * ([MapTiles.userAgent]), with the package name and version code.
 */
internal class ConfiguredTileSource(val config: TileConfig) : OnlineTileSourceBase(
    config.cacheName,
    config.minZoom,
    config.maxZoom,
    config.tileSize,
    ".png",
    arrayOf(config.url),
    config.attribution,
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL,
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        config.tileUrl(MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex))
}

/**
 * osmdroid's tile download (TileDownloader in osmdroid 6.1.20), step for
 * step, with the one thing osmdroid keeps to itself: the HTTP status of every
 * tile request, which goes to [MapTiles.recordAnswer] so the maps can say
 * "Map tiles unavailable" when the server refuses or fails them all.
 */
internal class ObservedTileDownloader : TileDownloader() {

    override fun downloadTile(
        pMapTileIndex: Long,
        redirectCount: Int,
        targetUrl: String?,
        pFilesystemCache: IFilesystemCache?,
        pTileSource: OnlineTileSourceBase,
    ): Drawable? {
        if (redirectCount > MAX_REDIRECTS || targetUrl.isNullOrEmpty()) return null
        val osm = OsmConfig.getInstance()
        val policy = pTileSource.tileSourcePolicy
        val userAgent = (if (policy.normalizesUserAgent()) osm.normalizedUserAgent else null) ?: osm.userAgentValue
        if (!policy.acceptsUserAgent(userAgent)) {
            Log.e(TAG, "No meaningful User-Agent configured; tile not requested")
            return null
        }
        var connection: HttpURLConnection? = null
        try {
            val url = URL(targetUrl)
            val proxy = osm.httpProxy
            connection = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
            connection.useCaches = true
            connection.setRequestProperty(osm.userAgentHttpHeader, userAgent)
            for ((name, value) in osm.additionalHttpRequestProperties) connection.setRequestProperty(name, value)
            connection.connect()
            val status = connection.responseCode
            if (status in REDIRECTS && osm.isMapTileDownloaderFollowRedirects) {
                val location = connection.getHeaderField("Location")
                if (location != null) {
                    return downloadTile(pMapTileIndex, redirectCount + 1, URL(url, location).toString(), pFilesystemCache, pTileSource)
                }
            }
            MapTiles.recordAnswer(status)
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Tile ${MapTileIndex.toString(pMapTileIndex)}: HTTP $status")
                connection.errorStream?.close()
                return null
            }
            val expires = policy.computeExpirationTime(connection, System.currentTimeMillis())
            val bytes = connection.inputStream.use { it.readBytes() }
            val data = ByteArrayInputStream(bytes)
            if (pFilesystemCache != null) {
                pFilesystemCache.saveFile(pTileSource, pMapTileIndex, data, expires)
                data.reset()
            }
            return pTileSource.getDrawable(data)
        } catch (e: BitmapTileSourceBase.LowMemoryException) {
            // As osmdroid does: stop this loader's queue rather than keep failing for memory.
            throw CantContinueException(e)
        } catch (e: IOException) {
            Log.w(TAG, "Tile ${MapTileIndex.toString(pMapTileIndex)} not downloaded: $e")
            return null
        } catch (e: IllegalArgumentException) {
            // A malformed tile address or redirect target.
            Log.w(TAG, "Tile ${MapTileIndex.toString(pMapTileIndex)} not requested: $e")
            return null
        } catch (e: ClassCastException) {
            // An address that is not http(s): no connection to read a status from.
            Log.w(TAG, "Tile ${MapTileIndex.toString(pMapTileIndex)} not requested: $e")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "MapTiles"
        const val MAX_REDIRECTS = 3
        val REDIRECTS = setOf(301, 302, 307, 308)
    }
}

/**
 * osmdroid's standard tile provider (memory cache, the on-disk cache in
 * [MapTiles], downloads) with every download going through
 * [ObservedTileDownloader].
 */
internal class ClairixTileProvider(context: Context, source: ITileSource) : MapTileProviderBasic(context, source) {

    // Called by the superclass constructor, before anything of this class is
    // initialised, so it touches nothing of it.
    override fun createDownloaderProvider(networkCheck: INetworkAvailablityCheck?, tileSource: ITileSource?): MapTileDownloader =
        super.createDownloaderProvider(networkCheck, tileSource).apply { setTileDownloader(ObservedTileDownloader()) }
}
