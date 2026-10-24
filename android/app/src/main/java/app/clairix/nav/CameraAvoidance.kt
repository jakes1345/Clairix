package app.clairix.nav

/**
 * Extra cost the router pays to drive an edge, on top of its travel time: the price of
 * passing something the owner would rather not pass. Seconds, so a penalty of 150 means
 * "take any detour that costs less than two and a half minutes".
 */
interface EdgePenalties {
    /** Seconds of penalty for driving edge [e]; 0 when there is none. */
    fun penaltySec(e: Int): Double

    companion object {
        val NONE: EdgePenalties = object : EdgePenalties {
            override fun penaltySec(e: Int): Double = 0.0
        }
    }
}

/**
 * Maps the known plate readers onto the road graph and prices the edges that pass one, so
 * routes go around them when the owner asks (the default) and every route says how many it
 * passes. A reader is matched to every edge within [MATCH_RADIUS_M], in both directions:
 * the map rarely says which way a camera reads, and a rear plate is read as well as a
 * front one.
 *
 * Lookups are by binary search over sorted arrays so the router's loop never allocates;
 * [update] rebuilds them off the main thread when the list or the setting changes.
 */
class CameraPenalties(private val graph: RoadGraph) : EdgePenalties {

    companion object {
        const val MATCH_RADIUS_M = 35.0
        private const val NEAREST_LIMIT = 12
        /** What passing one reader is worth avoiding, in seconds of driving. */
        const val PENALTY_SEC = 150.0
    }

    private class Table(val edges: IntArray, val cameraIdx: Array<IntArray>, val cameras: List<KnownCamera>) {
        fun indexOf(e: Int): Int = if (edges.isEmpty()) -1 else edges.binarySearch(e)
    }

    @Volatile private var table = Table(IntArray(0), emptyArray(), emptyList())
    @Volatile private var enabled = true

    /** The cameras of the current table, as last given to [update]. */
    val cameras: List<KnownCamera> get() = table.cameras

    val isEnabled: Boolean get() = enabled

    override fun penaltySec(e: Int): Double {
        if (!enabled) return 0.0
        return if (table.indexOf(e) >= 0) PENALTY_SEC else 0.0
    }

    /** Whether any known reader watches edge [e], whatever the setting. */
    fun watches(e: Int): Boolean = table.indexOf(e) >= 0

    /** Re-maps [cameras] onto the graph. Walks the spatial index per camera: call off the main thread. */
    fun update(cameras: List<KnownCamera>, avoid: Boolean) {
        enabled = avoid
        if (cameras === table.cameras) return
        val byEdge = HashMap<Int, ArrayList<Int>>()
        for ((i, c) in cameras.withIndex()) {
            for (hit in graph.nearestEdges(c.lat, c.lon, MATCH_RADIUS_M, NEAREST_LIMIT)) {
                byEdge.getOrPut(hit.edge) { ArrayList(2) }.add(i)
            }
        }
        val edges = byEdge.keys.sorted().toIntArray()
        val idx = Array(edges.size) { byEdge[edges[it]]!!.toIntArray() }
        table = Table(edges, idx, cameras)
    }

    /** The distinct readers a route along [edges] passes, in route order. */
    fun camerasAlong(edges: IntArray): List<KnownCamera> {
        val t = table
        if (t.edges.isEmpty()) return emptyList()
        val seen = HashSet<Int>()
        val out = ArrayList<KnownCamera>()
        for (e in edges) {
            val i = t.indexOf(e)
            if (i < 0) continue
            for (c in t.cameraIdx[i]) if (seen.add(c)) out.add(t.cameras[c])
        }
        return out
    }
}
