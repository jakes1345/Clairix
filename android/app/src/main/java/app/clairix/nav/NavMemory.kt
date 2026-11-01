package app.clairix.nav

import android.content.ComponentCallbacks2

/**
 * What navigation gives back when Android says memory is short (onTrimMemory), as a
 * decision apart from the view model and the map that act on it.
 *
 * A state's routing arrays and spatial index take a few hundred MB of heap, and the map
 * keeps decoded tiles in memory. Once the app's screen is hidden the tiles are for nobody
 * and are dropped; they come back from the tile cache on disk when the map is seen again.
 * Once the app is in the background (or memory is critically short while it is in front),
 * the router's per-edge arrays, the graph's spatial index and the road-name search index
 * go too, unless a drive is under way: then map matching and rerouting use them every
 * second, and dropping them would only make the phone build them again mid-drive. Each is
 * rebuilt on its next use.
 */
object NavMemory {

    data class Trim(val tiles: Boolean, val routing: Boolean)

    fun plan(level: Int, navigating: Boolean): Trim {
        val hidden = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        @Suppress("DEPRECATION") // still delivered on 12 and 13
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val background = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        return Trim(tiles = hidden, routing = !navigating && (background || critical))
    }
}
