package app.clairix.nav

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class WorkZoneFeedTest {
    private val now = 1_760_500_000L

    private val file = """
        {"version":1,"generated":1760499900,"slug":"north-america-us-ohio","items":[
          {"id":"ohgo:WZ-1","type":"closure","impact":"all_closed","speed_kph":null,"road":"I-71",
           "direction":"northbound","description":"Bridge deck replacement","start":1760400000,"end":1760600000,
           "geom":[[39.95,-82.99],[39.96,-82.99],[39.97,-82.985]],"source":"Ohio Department of Transportation"},
          {"id":"ohgo:WZ-2","type":"lane_closure","impact":"some_closed","speed_kph":72.4,"road":"US-33",
           "direction":"eastbound","description":null,"start":null,"end":null,
           "geom":[[39.9,-83.0],[39.9,-82.98]],"source":"Ohio Department of Transportation"},
          {"id":"ohgo:WZ-3","type":"work_zone","impact":"shoulder","speed_kph":null,"road":"SR-315",
           "direction":null,"description":"Ended","start":1760300000,"end":1760400000,
           "geom":[[39.99,-83.03],[40.0,-83.03]],"source":"Ohio Department of Transportation"},
          {"id":"ohgo:WZ-4","type":"weird","impact":"bogus","geom":[[40.1,-83.1],[91.0,-83.1],[40.11,-83.1]],"source":""},
          {"id":"","type":"closure","geom":[[40.0,-83.0],[40.01,-83.0]]},
          {"id":"ohgo:WZ-5","type":"closure","geom":[]},
          {"id":"ohgo:WZ-1","type":"work_zone","impact":"open","geom":[[1.0,1.0],[1.1,1.0]],"source":"dup"}
        ]}
    """.trimIndent()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun parsesItemsAndDropsEndedOnesAndJunk() {
        val zones = WorkZoneFeed.parse(file, now, "north-america-us-ohio")
        assertEquals(listOf("ohgo:WZ-1", "ohgo:WZ-2", "ohgo:WZ-4"), zones.map { it.id })

        val closure = zones[0]
        assertEquals(WorkZone.CLOSURE, closure.type)
        assertEquals(WorkZone.IMPACT_ALL_CLOSED, closure.impact)
        assertNull(closure.speedKph)
        assertEquals("I-71", closure.road)
        assertEquals("northbound", closure.direction)
        assertEquals(1_760_400_000L, closure.start)
        assertEquals(1_760_600_000L, closure.end)
        assertEquals(3, closure.geom.size)
        assertEquals(LatLon(39.96, -82.99), closure.geom[1])
        assertTrue(closure.isActive(now))
        assertFalse(closure.isActive(1_760_600_000L))

        val lanes = zones[1]
        assertEquals(72.4, lanes.speedKph!!, 1e-9)
        assertNull(lanes.description)
        assertNull(lanes.start)
        assertTrue(lanes.isActive(now))

        // Unknown type and impact fall back to the mildest reading; the out-of-range point is skipped.
        val odd = zones[2]
        assertEquals(WorkZone.WORK_ZONE, odd.type)
        assertEquals(WorkZone.IMPACT_UNKNOWN, odd.impact)
        assertEquals(2, odd.geom.size)
        assertEquals("State DOT", odd.source)
    }

    @Test
    fun rejectsAnotherRegionsFileAndUnknownVersions() {
        try {
            WorkZoneFeed.parse(file, now, "north-america-us-texas")
            fail("parsed a file for another region")
        } catch (e: JSONException) {
            assertTrue(e.message!!.contains("north-america-us-ohio"))
        }
        try {
            WorkZoneFeed.parse("""{"version":2,"items":[]}""", now)
            fail("parsed version 2")
        } catch (e: JSONException) { /* expected */ }
        assertEquals(0, WorkZoneFeed.parse("""{"version":1,"generated":1,"slug":"x","items":[]}""", now, "x").size)
    }

    @Test
    fun gunzipHandlesGzipPlainAndTheCap() {
        val json = file.toByteArray()
        assertArrayEquals(json, WorkZoneFeed.gunzipCapped(gzip(json)))
        // OkHttp may already have removed a Content-Encoding: gzip layer.
        assertArrayEquals(json, WorkZoneFeed.gunzipCapped(json))
        val bomb = gzip(ByteArray(2 * 1024 * 1024))
        assertTrue(bomb.size < 64 * 1024)
        try {
            WorkZoneFeed.gunzipCapped(bomb, cap = 1024 * 1024)
            fail("decompressed past the cap")
        } catch (e: IOException) { /* expected */ }
        assertEquals(2 * 1024 * 1024, WorkZoneFeed.gunzipCapped(bomb).size)
    }

    @Test
    fun urlFollowsTheReleaseLayout() {
        assertEquals(
            "https://github.com/jakes1345/Clairix/releases/download/workzones/workzones-north-america-us-ohio.json.gz",
            WorkZoneFeed.url("north-america-us-ohio"),
        )
    }

    // ── Provider incidents and merging ─────────────────────────────────────

    private val providerReply = JSONObject(
        """
        {"now":1760500000,"providers":{"here":"ok","tomtom":"quota"},"incidents":[
          {"id":"here:123","type":"crash","lat":39.96,"lon":-83.0,"geom":null,"road":"I-70",
           "description":"Accident. Right lane blocked.","start":1760499000,"end":1760503600,"delay_s":420,"source":"here"},
          {"id":"tomtom:abc","type":"closure","lat":39.97,"lon":-83.01,"geom":[[39.97,-83.01],[39.975,-83.01]],
           "road":null,"description":"Road closed","start":null,"end":null,"delay_s":null,"source":"tomtom"},
          {"id":"tomtom:old","type":"jam","lat":39.9,"lon":-83.0,"geom":null,"end":1760400000,"source":"tomtom"},
          {"id":"here:999","type":"sinkhole","geom":[[39.8,-83.2],[39.81,-83.2]],"source":"here"},
          {"type":"crash","lat":1,"lon":1}
        ]}
        """.trimIndent()
    )

    @Test
    fun parsesProviderIncidents() {
        val list = parseProviderIncidents(providerReply, now)
        assertEquals(listOf("here:123", "tomtom:abc", "here:999"), list.map { it.id })
        val crash = list[0]
        assertEquals(Incident.CRASH, crash.type)
        assertEquals(Incident.Origin.PROVIDER, crash.origin)
        assertFalse(crash.votable)
        assertEquals("here", crash.source)
        assertEquals(420, crash.delaySec)
        assertEquals(1_760_503_600L, crash.expiresAt)
        assertEquals("I-70", crash.road)
        assertNull(crash.geom)
        val closure = list[1]
        assertEquals(2, closure.geom!!.size)
        assertEquals(0L, closure.expiresAt)
        // Unknown types become "other"; a missing position falls back to the geometry's start.
        assertEquals(Incident.OTHER, list[2].type)
        assertEquals(39.8, list[2].lat, 1e-9)
    }

    @Test
    fun mergeDedupesByIdAndShowsOnlyActiveZones() {
        val relay = listOf(
            Incident("r1", Incident.POLICE, 39.9, -83.0, null, "user", 2, now - 60, now + 600, null),
            Incident("r2", Incident.HAZARD, 39.9, -83.0, null, "user", 1, now - 900, now - 1, null),
        )
        val provider = parseProviderIncidents(providerReply, now) +
            Incident("r1", Incident.CRASH, 0.0, 0.0, null, "here", 0, 0, 0, null, origin = Incident.Origin.PROVIDER)
        val zones = WorkZoneFeed.parse(file, now) + WorkZone(
            "ohgo:future", WorkZone.WORK_ZONE, WorkZone.IMPACT_SHOULDER, null, null, null, null,
            now + 3600, now + 7200, listOf(LatLon(39.0, -83.0), LatLon(39.01, -83.0)), "Ohio DOT",
        )
        val merged = mergeIncidents(relay, provider, zones, now)
        assertEquals(listOf("r1", "here:123", "tomtom:abc", "here:999", "ohgo:WZ-1", "ohgo:WZ-2", "ohgo:WZ-4"), merged.map { it.id })
        assertEquals(Incident.POLICE, merged[0].type)
        val wz = merged.first { it.id == "ohgo:WZ-1" }
        assertEquals(Incident.Origin.WORK_ZONE, wz.origin)
        assertEquals(Incident.CLOSURE, wz.type)
        assertEquals(39.95, wz.lat, 1e-9)
        assertEquals(1_760_600_000L, wz.expiresAt)
        assertEquals("Ohio Department of Transportation", wz.source)
        assertNotNull(wz.geom)
        assertEquals(Incident.LANE_CLOSURE, merged.first { it.id == "ohgo:WZ-2" }.type)
    }

    private fun relayWzdx(id: String, lat: Double, lon: Double, description: String?, source: String = RELAY_WZDX_SOURCE) =
        Incident(id, Incident.HAZARD, lat, lon, null, source, 0, now - 600, now + 3600, description)

    /** IDs that survive merging [relay] with the Ohio file (WZ-1: I-71 northbound, 39.95..39.97 N along -82.99). */
    private fun mergedIds(vararg relay: Incident): List<String> =
        mergeIncidents(relay.toList(), emptyList(), WorkZoneFeed.parse(file, now), now).map { it.id }

    @Test
    fun sameZoneFromTheRelayIsDedupedIntoTheWorkZone() {
        // ~9 m off WZ-1's stretch, same road and direction: the relay's copy of WZ-1.
        val ids = mergedIds(relayWzdx("copy", 39.955, -82.9899, "Work zone on I-71 (northbound): Bridge deck replacement"))
        assertFalse("copy" in ids)
        assertTrue("ohgo:WZ-1" in ids)
        // Older relays write no direction; the road still matches, however it is spelled.
        assertFalse("old" in mergedIds(relayWzdx("old", 39.955, -82.9898, "Work zone on Interstate 71: Bridge deck replacement")))
        // A description in no known form: the distance alone decides.
        assertFalse("bare" in mergedIds(relayWzdx("bare", 39.955, -82.9899, null)))
    }

    @Test
    fun oppositeCarriagewayFortyMetresAwayIsKept() {
        // The southbound zone 40 m east of WZ-1: beyond the radius, and the other direction anyway.
        assertTrue("sb" in mergedIds(relayWzdx("sb", 39.955, -82.98953, "Work zone on I-71 (southbound): Resurfacing")))
        // Even with nothing to compare but the distance, 40 m apart is two zones.
        assertTrue("sb-bare" in mergedIds(relayWzdx("sb-bare", 39.955, -82.98953, null)))
    }

    @Test
    fun aDifferentDirectionOrRoadWithinTheRadiusIsKept() {
        // 17 m away, but the other carriageway.
        assertTrue("sb" in mergedIds(relayWzdx("sb", 39.955, -82.9898, "Work zone on I-71 (southbound)")))
        // 17 m away, but a ramp onto another road.
        assertTrue("ramp" in mergedIds(relayWzdx("ramp", 39.955, -82.9898, "Work zone on US-33: Ramp closed")))
    }

    @Test
    fun relayWzdxCopiesOfFarOrEndedZonesAndDriverReportsAreKept() {
        val ids = mergedIds(
            relayWzdx("far", 39.96, -82.97, "Work zone on I-71 (northbound)"),
            // On WZ-3, which has ended: nothing replaces the relay's copy.
            relayWzdx("ended-zone", 39.995, -83.03, "Work zone on SR-315"),
            // A driver's report on the stretch is not a feed copy.
            relayWzdx("user", 39.955, -82.99, null, source = "user"),
        )
        assertTrue("far" in ids && "ended-zone" in ids && "user" in ids)
    }

    @Test
    fun relayDescriptionsAndRoadNamesParse() {
        assertEquals(RelayWzdxHint(setOf("I71", "US33"), "northbound"), relayWzdxHint("Work zone on I-71, US-33 (northbound): Bridge"))
        assertEquals(RelayWzdxHint(setOf("SR315"), null), relayWzdxHint("Work zone on SR-315"))
        assertEquals(RelayWzdxHint(emptySet(), null), relayWzdxHint("Lane closed for paving"))
        assertEquals(RelayWzdxHint(emptySet(), null), relayWzdxHint(null))
        assertEquals(setOf("I71"), roadKeys("Interstate 71"))
        assertEquals(setOf("US33", "SR315"), roadKeys("U.S. 33 / State Route 315"))
        assertEquals(setOf("I71"), roadKeys("i-71"))
        assertNull(normalizeDirection("both"))
        assertEquals("northbound", normalizeDirection(" Northbound "))
    }

    @Test
    fun nearStretchMeasuresToSegmentsNotJustVertices() {
        val line = listOf(LatLon(40.0, -83.0), LatLon(40.0, -82.9))
        // Midway between the two vertices (~4.3 km from each), 50 m north of the segment.
        assertTrue(nearStretch(40.00045, -82.95, line, 100.0))
        assertFalse(nearStretch(40.0018, -82.95, line, 100.0))
        // Past the end, the distance is to the end vertex.
        assertFalse(nearStretch(40.0, -82.898, line, 100.0))
        assertTrue(nearStretch(40.0, -82.8995, line, 100.0))
        // A one-point stretch is a circle.
        assertTrue(nearStretch(40.0005, -83.0, listOf(LatLon(40.0, -83.0)), 100.0))
        assertFalse(nearStretch(40.0, -83.0, emptyList(), 100.0))
    }

    @Test
    fun incidentBoxStaysWithinOneDegree() {
        // A lone position gets a quarter-degree box around it.
        val single = incidentBox(listOf(LatLon(40.0, -83.0)))!!
        assertEquals(0.25, single[2] - single[0], 1e-9)
        assertEquals(0.25, single[3] - single[1], 1e-9)
        assertEquals(40.0, (single[0] + single[2]) / 2, 1e-9)

        // A long route north is cut where the box would pass INCIDENT_BOX_SPAN_DEG.
        val route = (0..300).map { LatLon(40.0 + it * 0.01, -83.0 + it * 0.001) }
        val box = incidentBox(route)!!
        assertTrue(box[2] - box[0] <= INCIDENT_BOX_SPAN_DEG + 1e-9)
        assertTrue(box[3] - box[1] <= INCIDENT_BOX_SPAN_DEG + 1e-9)
        assertTrue(box[0] < 40.0 && box[2] > 40.0 + INCIDENT_BOX_SPAN_DEG - 0.1)
        // The relay's hard limit still holds when a caller asks for the most it allows.
        val wide = incidentBox(route, maxSpanDeg = 1.0)!!
        assertTrue(wide[2] - wide[0] <= 1.0 + 1e-9 && wide[2] > 40.9)

        assertNull(incidentBox(emptyList()))
    }
}
