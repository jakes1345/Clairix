package app.clairix.bugreport

/**
 * Reads the main text of an Android bug report (Developer options → Take bug report) for which
 * apps used the microphone, the camera and location recently. Entirely on the phone, from the
 * text alone: nothing is looked up and nothing is sent anywhere.
 *
 * A bug report is the output of `dumpstate`: many sections, each a system service's own dump,
 * whose format changes between Android versions and phone makers. Three of them say who used what:
 * - `dumpsys appops`: for each app and each protected operation (RECORD_AUDIO, CAMERA,
 *   FINE_LOCATION, ...), when the app last did it ("Access: ... 2026-11-01 10:22:33.123 (-3h2m)"
 *   on Android 10 and newer, "time=+3h2m ago" before).
 * - `dumpsys media.camera`: the camera service's event log ("CONNECT device 0 client for package
 *   com.example (PID 1234)").
 * - `dumpsys audio`: the recording activity log ("rec start ... pack:com.example").
 * Each is read where it is found; a section that is missing or reads as nothing known is named in
 * [Findings.unread] rather than taken as "nobody used it". Only what the report says is reported:
 * a report covers what the phone still remembered when it was taken, often a few days for app
 * ops and less for the event logs, and says nothing about anything before.
 *
 * Pure: lines in, [Findings] out. [BugReportZip] finds the text in the zip and feeds it here.
 */
internal object BugReportParser {

    /** What was used. */
    enum class Resource(val label: String) { MICROPHONE("Microphone"), CAMERA("Camera"), LOCATION("Location") }

    /** Where in the report a use was read. */
    enum class Source(val label: String) { APP_OPS("app ops"), CAMERA_LOG("camera service log"), AUDIO_LOG("audio recording log") }

    /**
     * One app's use of one [resource], as read from [sources]: [last] is the latest time the report
     * gives for it, as the report writes it (the phone's clock and zone), or null when it gives none.
     */
    data class Use(val resource: Resource, val packageName: String, val last: String?, val sources: Set<Source>, val count: Int)

    data class Findings(
        /** Every use found, by resource, then package. */
        val uses: List<Use>,
        /** The sections that were found and read. */
        val read: Set<Source>,
        /** What could not be read, in words. */
        val unread: List<String>,
        /** When the report was taken, as its header says ("== dumpstate: ..."), if it does. */
        val takenAt: String?,
        /** The build it came from, as its header says. */
        val build: String?,
        val lines: Long,
        /** Lines longer than the cap ([BugReportZip.MAX_LINE]), cut short before reading; set by [BugReportZip]. */
        val longLines: Long,
    ) {
        fun of(r: Resource): List<Use> = uses.filter { it.resource == r }
    }

    /** App-op names, as appops prints them, and what each uses. */
    private val OPS = mapOf(
        "RECORD_AUDIO" to Resource.MICROPHONE,
        "PHONE_CALL_MICROPHONE" to Resource.MICROPHONE,
        "CAMERA" to Resource.CAMERA,
        "PHONE_CALL_CAMERA" to Resource.CAMERA,
        "FINE_LOCATION" to Resource.LOCATION,
        "COARSE_LOCATION" to Resource.LOCATION,
        "MONITOR_HIGH_POWER_LOCATION" to Resource.LOCATION,
    )

    private val SERVICE = Regex("""^DUMP OF SERVICE (?:CRITICAL |HIGH |NORMAL )?([\w.\-/]+):""")
    private val SECTION_END = Regex("""^-{5,} [\d.]+s was the duration of""")
    private val PACKAGE = Regex("""^\s+Package ([\w.]+):\s*$""")
    private val UID = Regex("""^\s+Uid \S+:\s*$""")
    private val OP = Regex("""^\s+([A-Z][A-Z0-9_]+)(?: \(([^)]*)\))?:(.*)$""")
    private val ACCESS_TIME = Regex("""Access:.*?(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)""")
    private val OLD_TIME = Regex("""\btime=\+?([\dhmsd]+) ago""")
    private val CAMERA_CONNECT = Regex("""^\s*(\d\d-\d\d \d\d:\d\d:\d\d)\S*\s*:\s*CONNECT device \S+ client for package ([\w.]+)""")
    private val AUDIO_REC = Regex("""^\s*(\d\d-\d\d \d\d:\d\d:\d\d)\S*\s.*\brec start\b.*\bpack:([\w.]+)""")
    private val AUDIO_ACTIVE = Regex("""\bsource:(?:MIC|VOICE_\w+|CAMCORDER|UNPROCESSED)\b.*\bpack:([\w.]+)""")
    private val TAKEN = Regex("""^== dumpstate: (.+)$""")
    private val BUILD = Regex("""^Build fingerprint: '?([^']+)'?$""")

    private class Acc(var last: String?, val sources: MutableSet<Source>, var count: Int)

    /** The parser's place in the text. */
    private class State {
        var service: String? = null
        var pkg: String? = null
        var op: Resource? = null
        val uses = LinkedHashMap<Pair<Resource, String>, Acc>()
        val read = HashSet<Source>()
        val seen = HashSet<String>()
        var takenAt: String? = null
        var build: String? = null
        var lines = 0L

        fun add(r: Resource, pkg: String, at: String?, source: Source) {
            val a = uses.getOrPut(r to pkg) { Acc(null, HashSet(), 0) }
            a.count++
            a.sources += source
            if (at != null && newer(at, a.last)) a.last = at
            read += source
        }
    }

    /**
     * Whether [at] should replace [last] as the latest time shown. The report writes times three
     * ways: a full date (app ops), month and day (the event logs, within the year) and "3h2m before
     * the report" (Android 9's app ops). A full date beats the others; within one way the later
     * date wins; the relative ones are not compared, the first is kept.
     */
    internal fun newer(at: String, last: String?): Boolean {
        if (last == null) return true
        fun rank(t: String) = when {
            FULL_DATE.matches(t) -> 2
            SHORT_DATE.matches(t) -> 1
            else -> 0
        }
        val (a, b) = rank(at) to rank(last)
        return a > b || (a == b && a > 0 && at > last)
    }

    private val FULL_DATE = Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d""")
    private val SHORT_DATE = Regex("""\d\d-\d\d \d\d:\d\d:\d\d""")

    /** Reads [lines], each already cut to [BugReportZip.MAX_LINE] (the reader counts the ones it cut). */
    fun parse(lines: Sequence<String>): Findings {
        val s = State()
        for (line in lines) {
            s.lines++
            step(s, line)
        }
        return finish(s)
    }

    private fun step(s: State, line: String) {
        if (s.takenAt == null) TAKEN.find(line)?.let { s.takenAt = it.groupValues[1].trim() }
        if (s.build == null) BUILD.find(line)?.let { s.build = it.groupValues[1].trim() }
        SERVICE.find(line)?.let {
            s.service = it.groupValues[1]
            s.seen += it.groupValues[1]
            s.pkg = null
            s.op = null
            return
        }
        if (SECTION_END.containsMatchIn(line)) {
            s.service = null
            return
        }
        when (s.service) {
            "appops" -> appOps(s, line)
            "media.camera" -> CAMERA_CONNECT.find(line)?.let { s.add(Resource.CAMERA, it.groupValues[2], it.groupValues[1], Source.CAMERA_LOG) }
            "audio" -> audio(s, line)
        }
    }

    private fun appOps(s: State, line: String) {
        if (UID.matches(line)) {
            s.pkg = null
            s.op = null
            return
        }
        PACKAGE.find(line)?.let {
            s.pkg = it.groupValues[1]
            s.op = null
            return
        }
        val pkg = s.pkg ?: return
        OP.find(line)?.let { m ->
            s.op = OPS[m.groupValues[1]]
            // Android 9 and older put the last use on the op's own line.
            val op = s.op
            if (op != null) OLD_TIME.find(m.groupValues[3])?.let { s.add(op, pkg, "${it.groupValues[1]} before the report", Source.APP_OPS) }
            return
        }
        val op = s.op ?: return
        ACCESS_TIME.find(line)?.let { s.add(op, pkg, it.groupValues[1], Source.APP_OPS) }
    }

    private fun audio(s: State, line: String) {
        AUDIO_REC.find(line)?.let {
            s.add(Resource.MICROPHONE, it.groupValues[2], it.groupValues[1], Source.AUDIO_LOG)
            return
        }
        AUDIO_ACTIVE.find(line)?.let { s.add(Resource.MICROPHONE, it.groupValues[1], null, Source.AUDIO_LOG) }
    }

    private fun finish(s: State): Findings {
        val unread = buildList {
            if (s.lines == 0L) add("The bug report's text was empty.")
            if ("appops" !in s.seen) add("No app ops section (dumpsys appops), which is where microphone, camera and location use is usually recorded.")
            else if (Source.APP_OPS !in s.read) add("The app ops section was there, but Clairix found no microphone, camera or location use it could read in it; its format may be one Clairix does not know.")
            if ("media.camera" !in s.seen) add("No camera service section (dumpsys media.camera).")
            if ("audio" !in s.seen) add("No audio section (dumpsys audio).")
        }
        val uses = s.uses.map { (key, a) -> Use(key.first, key.second, a.last, a.sources, a.count) }
            .sortedWith(compareBy<Use> { it.resource.ordinal }.thenBy { it.packageName })
        return Findings(uses, s.read, unread, s.takenAt, s.build, s.lines, longLines = 0)
    }
}
