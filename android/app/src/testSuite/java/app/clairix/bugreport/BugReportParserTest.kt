package app.clairix.bugreport

import app.clairix.bugreport.BugReportParser.Resource
import app.clairix.bugreport.BugReportParser.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Small, made-up bug-report texts in the shapes dumpstate writes: the app ops dump of Android 10
 * and later and of Android 9, the camera service's event log and the audio recording log.
 */
class BugReportParserTest {

    private fun parse(text: String) = BugReportParser.parse(text.trimIndent().lineSequence())

    private val header = """
        ========================================================
        == dumpstate: 2026-11-01 22:14:03
        ========================================================
        Build: AP4A.250105.002
        Build fingerprint: 'google/husky/husky:15/AP4A.250105.002/12701944:user/release-keys'
    """.trimIndent()

    private val appOps = """
        -------------------------------------------------------------------------------
        DUMP OF SERVICE appops:
        Current AppOps Service state:
          Settings:
            top_state_settle_time=+5s0ms
          Uid 10123:
            state=cch
            RECORD_AUDIO: mode=foreground
            Package com.example.spy:
              RECORD_AUDIO (allow):
                null=[
                  Access: [fg-s] 2026-10-30 03:12:44.120 (-3d19h1m19s) duration=+12m3s
                  Access: [bg-s] 2026-11-01 02:40:01.004 (-19h34m2s) duration=+41m0s
                ]
              FINE_LOCATION (allow / switch COARSE_LOCATION=allow):
                null=[
                  Access: [bg-s] 2026-11-01 21:59:00.000 (-15m3s)
                  Reject: [bg-s] 2026-11-01 20:00:00.000 (-2h14m3s)
                ]
              CAMERA (ignore):
              WAKE_LOCK (allow):
                null=[
                  Access: [bg-s] 2026-11-01 21:00:00.000 (-1h14m)
                ]
          Uid 10200:
            Package com.example.maps:
              COARSE_LOCATION (allow):
                Access: [top-s] 2026-11-01 08:00:00.000 (-14h14m3s)
        --------- 0.051s was the duration of dumpsys appops, ending at: 2026-11-01 22:14:20
    """.trimIndent()

    private val camera = """
        -------------------------------------------------------------------------------
        DUMP OF SERVICE CRITICAL media.camera:
        == Service global info: ==
        Number of camera devices: 4
        == Camera service events log (most recent at top): ==
          11-01 21:40:12 : DISCONNECT device 1 client for package com.example.spy (PID 4242)
          11-01 21:40:02 : CONNECT device 1 client for package com.example.spy (PID 4242)
          10-31 09:00:00 : CONNECT device 0 client for package com.google.android.GoogleCamera (PID 999)
        --------- 0.010s was the duration of dumpsys CRITICAL media.camera, ending at: 2026-11-01 22:14:21
    """.trimIndent()

    private val audio = """
        -------------------------------------------------------------------------------
        DUMP OF SERVICE audio:
        Audio event log: recording activity received by AudioService
        11-01 02:40:01:004 rec start riid:23 uid:10123 session:1201 src:MIC pack:com.example.spy
        11-01 03:21:01:004 rec stop riid:23 uid:10123 session:1201 src:MIC pack:com.example.spy
        RecordActivityMonitor dump time: 22:14:21.000
          session:1300 -- source:VOICE_COMMUNICATION -- uid:10301 -- riid:30 -- pack:org.example.messenger
        --------- 0.020s was the duration of dumpsys audio, ending at: 2026-11-01 22:14:22
    """.trimIndent()

    @Test
    fun appOpsNameEachAppAndItsLastUse() {
        val f = parse("$header\n$appOps\n$camera\n$audio")
        val mic = f.of(Resource.MICROPHONE)
        val spyMic = mic.first { it.packageName == "com.example.spy" }
        // The later of the two accesses, by app ops; the audio log's month-day time does not replace it.
        assertEquals("2026-11-01 02:40:01", spyMic.last)
        assertEquals(setOf(Source.APP_OPS, Source.AUDIO_LOG), spyMic.sources)
        assertEquals("2026-11-01 21:59:00", f.of(Resource.LOCATION).first { it.packageName == "com.example.spy" }.last)
        assertEquals("2026-11-01 08:00:00", f.of(Resource.LOCATION).first { it.packageName == "com.example.maps" }.last)
        // A camera op set to ignore with no access is not a use; the camera log's connect is.
        val cam = f.of(Resource.CAMERA).map { it.packageName to it.last }
        assertEquals(listOf("com.example.spy" to "11-01 21:40:02", "com.google.android.GoogleCamera" to "10-31 09:00:00"), cam.sortedBy { it.first })
        // A wake lock is not one of the three.
        assertTrue(f.uses.none { it.packageName == "com.example.spy" && it.resource == Resource.LOCATION && it.count > 1 })
        // A messenger recording a call right now: listed, with no time.
        val messenger = mic.first { it.packageName == "org.example.messenger" }
        assertNull(messenger.last)
        assertEquals(setOf(Source.APP_OPS, Source.CAMERA_LOG, Source.AUDIO_LOG), f.read)
        assertTrue(f.unread.isEmpty())
        assertEquals("2026-11-01 22:14:03", f.takenAt)
        assertEquals("google/husky/husky:15/AP4A.250105.002/12701944:user/release-keys", f.build)
    }

    @Test
    fun androidNinesAppOpsAreReadToo() {
        val f = parse(
            """
            DUMP OF SERVICE appops:
              Uid u0a123:
                Package com.example.old:
                  CAMERA (allow): time=+2h3m4s ago duration=+5s
                  RECORD_AUDIO (allow): time=+1d2h ago
                  VIBRATE (allow): time=+1m ago
            --------- 0.010s was the duration of dumpsys appops, ending at: 2019-01-01 10:00:00
            """
        )
        assertEquals("2h3m4s before the report", f.of(Resource.CAMERA).single().last)
        assertEquals("1d2h before the report", f.of(Resource.MICROPHONE).single().last)
        assertTrue(f.of(Resource.LOCATION).isEmpty())
    }

    @Test
    fun whatIsMissingIsSaidNotTakenAsNobody() {
        val f = parse("$header\n$camera")
        assertTrue(f.of(Resource.MICROPHONE).isEmpty())
        assertTrue(f.unread.any { it.startsWith("No app ops section") })
        assertTrue(f.unread.any { it.startsWith("No audio section") })
        assertFalse(f.unread.any { it.startsWith("No camera service section") })
    }

    @Test
    fun anAppOpsSectionInAFormatClairixDoesNotKnowIsSaidSo() {
        val f = parse(
            """
            DUMP OF SERVICE appops:
            some future format { pkg=com.example op=RECORD_AUDIO }
            --------- 0.010s was the duration of dumpsys appops, ending at: 2030-01-01 10:00:00
            """
        )
        assertTrue(f.uses.isEmpty())
        assertTrue(f.unread.any { it.contains("format may be one Clairix does not know") })
    }

    @Test
    fun linesOutsideTheirSectionAreNotRead() {
        // The same shapes in the kernel log or another service are not taken as app ops.
        val f = parse(
            """
            DUMP OF SERVICE package:
              Package com.example.spy:
                RECORD_AUDIO (allow):
                  Access: [fg-s] 2026-11-01 01:00:00.000 (-1h)
              11-01 21:40:02 : CONNECT device 1 client for package com.example.spy (PID 4242)
            --------- 0.010s was the duration of dumpsys package, ending at: 2026-11-01 22:14:21
            """
        )
        assertTrue(f.uses.isEmpty())
    }

    @Test
    fun anEmptyTextIsSaidToBeEmpty() {
        val f = BugReportParser.parse(emptySequence())
        assertTrue(f.unread.first().contains("empty"))
    }

    @Test
    fun theLatestTimeWinsWithinOneWayOfWritingIt() {
        assertTrue(BugReportParser.newer("2026-11-01 02:40:01", null))
        assertTrue(BugReportParser.newer("2026-11-01 02:40:01", "2026-10-30 03:12:44"))
        assertFalse(BugReportParser.newer("2026-10-30 03:12:44", "2026-11-01 02:40:01"))
        assertFalse(BugReportParser.newer("11-01 02:40:01", "2026-10-01 00:00:00"))
        assertTrue(BugReportParser.newer("2026-10-01 00:00:00", "11-01 02:40:01"))
        assertFalse(BugReportParser.newer("1h before the report", "3h before the report"))
    }
}
