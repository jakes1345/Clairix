package app.clairix.nav

import java.text.Normalizer
import java.util.Locale

/**
 * Destination search over the downloaded road graph, on the phone: every named
 * road in the region, found by name, with nothing sent anywhere.
 *
 * The graph carries each road's name for turn instructions. This indexes those
 * names once per loaded region (a pass over the edges, a second or two for a
 * state, off the main thread) into one entry per name per place: a "Main
 * Street" in each town is its own entry, told apart by distance, and a search
 * offers the one nearest the phone first. What it cannot do: house numbers,
 * places that are not roads (a shop, a park, a town) and spellings it does not
 * share; for those the owner can choose the online search, which sends the
 * text to OpenStreetMap, and the screen says so before it does.
 */
class LocalGeocoder(private val graph: RoadGraph, private val regionName: String) {

    private class Entry(val name: String, val words: List<String>, val lat: Double, val lon: Double)

    @Volatile private var index: List<Entry>? = null

    /** Builds the index now rather than on the first search. Blocking; call off the main thread. */
    fun warmUp() { entries() }

    private fun entries(): List<Entry> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            return build().also { index = it }
        }
    }

    private fun build(): List<Entry> {
        // Per name, the points kept so far: a point within APART_M of one of them
        // is the same stretch of the same road and folds into it.
        val byName = HashMap<Int, ArrayList<Entry>>()
        val names = HashMap<Int, String>()
        val words = HashMap<Int, List<String>>()
        for (n in 0 until graph.nodeCount) {
            val first = graph.firstEdge(n)
            val last = graph.lastEdgeExclusive(n)
            if (first >= last) continue
            val lat = graph.nodeLat(n)
            val lon = graph.nodeLon(n)
            for (e in first until last) {
                val idx = graph.edgeNameIndex(e)
                if (idx == RoadGraph.NO_NAME) continue
                val points = byName.getOrPut(idx) { ArrayList(1) }
                if (points.size >= MAX_PLACES_PER_NAME) continue
                var folded = false
                for (p in points) {
                    if (approxM(lat, lon, p.lat, p.lon) < APART_M) { folded = true; break }
                }
                if (folded) continue
                val name = names.getOrPut(idx) { graph.name(idx) }
                val w = words.getOrPut(idx) { tokens(name) }
                if (w.isEmpty()) continue
                points += Entry(name, w, lat, lon)
            }
        }
        val out = ArrayList<Entry>()
        for (list in byName.values) out.addAll(list)
        return out
    }

    /**
     * Roads whose name matches [query], best matches first and, among equals,
     * the nearest to ([nearLat], [nearLon]) when given. Blocking on the first
     * call for a region; call off the main thread.
     */
    fun search(query: String, nearLat: Double?, nearLon: Double?, limit: Int = 8): List<Place> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        class Hit(val entry: Entry, val score: Int, val distanceM: Double)
        val hits = ArrayList<Hit>()
        for (e in entries()) {
            val score = score(q, e.words) ?: continue
            val d = if (nearLat != null && nearLon != null) RoadGeo.haversineM(nearLat, nearLon, e.lat, e.lon) else 0.0
            hits += Hit(e, score, d)
        }
        hits.sortWith(compareByDescending<Hit> { it.score }.thenBy { it.distanceM })
        return hits.take(limit).map { h -> Place(h.entry.name, "On the offline map of $regionName", h.entry.lat, h.entry.lon) }
    }

    /**
     * Every query word must begin some word of the name; words found in order
     * score higher, exact words higher still, and a name with no words to
     * spare highest. Null when a query word matches nothing.
     */
    private fun score(q: List<String>, words: List<String>): Int? {
        var score = 0
        var pos = 0
        for (t in q) {
            var at = -1
            for (i in pos until words.size) if (words[i].startsWith(t)) { at = i; break }
            if (at < 0) for (i in 0 until pos) if (words[i].startsWith(t)) { at = i; break }
            if (at < 0) return null
            score += if (words[at] == t) 3 else 2
            if (at >= pos) { score += 1; pos = at + 1 }
        }
        if (words.size == q.size) score += 2
        return score
    }

    private companion object {
        /** Points of one road closer than this are one place. */
        const val APART_M = 4_000.0
        /** Entries kept for one name; a road that runs the length of a state is not a hundred results. */
        const val MAX_PLACES_PER_NAME = 24

        /** Suffixes and directions as OpenStreetMap writes them out, folded to how people type them. */
        val ABBREVIATIONS = mapOf(
            "street" to "st", "road" to "rd", "avenue" to "ave", "drive" to "dr", "boulevard" to "blvd",
            "lane" to "ln", "court" to "ct", "highway" to "hwy", "parkway" to "pkwy", "place" to "pl",
            "circle" to "cir", "trail" to "trl", "terrace" to "ter", "turnpike" to "tpke", "route" to "rt",
            "expressway" to "expy", "freeway" to "fwy", "square" to "sq", "north" to "n", "south" to "s",
            "east" to "e", "west" to "w", "northeast" to "ne", "northwest" to "nw", "southeast" to "se",
            "southwest" to "sw", "saint" to "st", "mount" to "mt", "fort" to "ft",
        )

        private val MARKS = Regex("\\p{M}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Lower-case words without accents or punctuation, with suffixes abbreviated. */
        fun tokens(s: String): List<String> =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase(Locale.ROOT)
                .split(SEPARATORS)
                .filter { it.isNotEmpty() }
                .map { ABBREVIATIONS[it] ?: it }

        /** Metres between two points, close enough to fold nearby points of one road. */
        fun approxM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * 111_320.0
            val dLon = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2))
            return Math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
