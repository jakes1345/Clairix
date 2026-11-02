package app.clairix.analysis

import app.clairix.CellStatus
import app.clairix.Detection
import app.clairix.EventKind
import app.clairix.NfcTag
import app.clairix.PhoneHealth
import app.clairix.Threat
import app.clairix.TimelineEvent
import app.clairix.UnlockKind
import app.clairix.WifiAnomaly
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The human-readable evidence report, section by section. Pure: the header, the
 * inputs and the range in, the body out; [Report.toText] adds the integrity block.
 */
internal object ReportText {

    /** The line the integrity block starts with; everything before it is what is hashed and signed. */
    const val INTEGRITY_MARKER = "=== INTEGRITY ==="

    fun body(header: ReportHeader, input: ReportInput, range: ReportRange): String {
        val t = ReportTime(header.zone)
        return buildString {
            appendLine("=== CLAIRIX EVIDENCE REPORT ===")
            appendLine()
            reportBlock(header, range, t)
            summaryBlock(input.detections.filter { it.following && range.overlaps(it.firstSeen, it.lastSeen) }, t)
            locationBlock(input)
            devicesBlock(input.detections.filter { range.overlaps(it.firstSeen, it.lastSeen) }, t)
            cellBlock(input.cell, t)
            wifiBlock(input.wifi.filter { range.contains(it.ts) }, t)
            nfcBlock(input.nfc.filter { range.contains(it.ts) }, t)
            spywareBlock(input.spyware, t)
            integrityBlock(input.health, input.tripwire, t)
            unlocksBlock(input.unlocks, range, t)
            trustBlock(input.trustStore, t)
            camerasBlock(input, range, t)
            timelineBlock(input, range, t)
        }.let(::defuse)
    }

    /**
     * A line of the body that reads exactly like the integrity marker (a Wi-Fi name
     * or an advertised name is chosen by whoever runs the device) gets a space in
     * front, so the check's `sed` cuts the file where the app put the marker and nowhere else.
     */
    private fun defuse(body: String): String =
        body.split('\n').joinToString("\n") { if (it == INTEGRITY_MARKER) " $it" else it }

    /** Text from outside (an alert's detail, a name) that may run over several lines, indented under its own line. */
    private fun indented(text: String, prefix: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n$prefix")

    // ── REPORT and SUMMARY ─────────────────────────────────────────────────

    private fun StringBuilder.reportBlock(h: ReportHeader, range: ReportRange, t: ReportTime) {
        appendLine("REPORT")
        appendLine("  Generated  : ${t.text(h.generatedAt)}")
        appendLine("  Covers     : ${coverage(range, h.retentionDays, t)}")
        appendLine("  App        : Clairix ${h.appVersion} (build ${h.appBuild}, ${h.buildType})")
        appendLine("  Package    : ${h.packageName}")
        appendLine("  Edition    : ${h.edition}")
        appendLine("  Device     : ${h.manufacturer} ${h.model}")
        appendLine("  Android    : ${h.androidRelease} (API ${h.sdkInt}), security patch ${h.securityPatch.ifBlank { "not reported" }}")
        appendLine("  History    : ${retention(h.retentionDays)}")
        appendLine(
            "  Thresholds : persistent after ${minutes(h.persistenceThresholdMin)} of presence; " +
                "following after a ${h.followThresholdM.roundToInt()} m move (the owner's settings when this was written)"
        )
        appendLine("  Time zone  : ${h.zone.id}, UTC${t.offset(h.generatedAt)} when this was written. Every time is UTC, then this phone's local time.")
        val key = h.signing
        if (key.publicKey != null) {
            appendLine("  Signature  : ${key.algorithm} (${ReportSigning.KEY_DESCRIPTION})")
            appendLine("  Public key : ${key.publicKey}")
            ReportSigning.fingerprint(key.publicKey)?.let { appendLine("  Key fingerprint (SHA-256 of the public key): $it") }
        } else {
            appendLine("  Signature  : none, the signing key was not available (${key.unavailable ?: "unknown reason"})")
        }
        appendLine()
    }

    private fun coverage(range: ReportRange, retentionDays: Int, t: ReportTime): String {
        val kept = if (retentionDays == 0) "everything Clairix has kept" else "everything Clairix has kept, up to $retentionDays days"
        val from = range.from
        val to = range.to
        return when {
            from == null && to == null -> kept
            from == null -> "$kept, up to ${t.text(to ?: 0L)}"
            to == null -> "from ${t.text(from)} to when this was written"
            else -> "from ${t.text(from)} to ${t.text(to)}"
        }
    }

    private fun retention(days: Int): String =
        if (days == 0) "kept until the owner deletes it"
        else "anything older than $days days is deleted automatically, so this report cannot show it"

    private fun StringBuilder.summaryBlock(followers: List<Detection>, t: ReportTime) {
        appendLine("SUMMARY")
        if (followers.isEmpty()) {
            appendLine("  No device was found travelling with this phone in this period.")
        } else {
            for (d in followers) appendLine("  - ${summary(d, t)}")
        }
        appendLine()
    }

    /**
     * One device that travelled with the phone, in sentences: what it is, when and
     * where it was first and last with the phone, how far apart and at how many places.
     */
    fun summary(d: Detection, t: ReportTime): String = buildString {
        val what = d.tracker?.label?.let { "${article(it)} $it" } ?: run {
            val maker = d.manufacturer?.let { "$it " }.orEmpty()
            val named = d.advertisedName?.let { " calling itself \"$it\"" }.orEmpty()
            "an unidentified ${maker}Bluetooth device$named"
        }
        append(what.replaceFirstChar { it.uppercaseChar() })
        append(" (address ${d.address}) was heard with this phone at ${plural(d.places, "place")} ")
        append("${d.displacementM.roundToInt()} m apart, from ${t.text(d.firstSeen)} to ${t.text(d.lastSeen)}.")
        val first = d.points.firstOrNull()
        val last = d.lastHeardAt ?: d.points.lastOrNull()
        if (first != null) append(" The first place recorded was near ${coords(first.lat, first.lon)}.")
        if (last != null) append(" It was last heard near ${coords(last.lat, last.lon)}.")
        d.dultLabel?.let { append(" Asked who it is, it answered: $it.") }
        if (d.following && d.threat == Threat.CRITICAL) {
            append(" It identifies itself as a tracker away from its owner, so Clairix counts it as following this phone.")
        } else {
            append(" Nothing identifies it as a tracker; a companion's phone or earbuds travelling with you look the same.")
        }
        if (d.separatedAt != 0L) append(" It went from near its owner to separated from its owner at ${t.text(d.separatedAt)}.")
        if (d.resolvedAt != 0L) append(" The owner marked it found at ${t.text(d.resolvedAt)}.")
    }

    // ── What the scanner holds ─────────────────────────────────────────────

    private fun StringBuilder.locationBlock(input: ReportInput) {
        val s = input.status
        appendLine("LOCATION (this phone when the report was written)")
        val lat = s.lat
        val lon = s.lon
        appendLine("  GPS: ${if (s.hasFix && lat != null && lon != null) coords(lat, lon) else "No fix"}")
        appendLine(String.format(Locale.US, "  Travelled while scanning: %.2f km", s.travelledM / 1000.0))
        appendLine()
    }

    private fun StringBuilder.devicesBlock(detections: List<Detection>, t: ReportTime) {
        appendLine("TRACKED DEVICES (${detections.size})")
        for (d in detections) deviceLines(d, t)
        appendLine()
    }

    private fun StringBuilder.deviceLines(d: Detection, t: ReportTime) {
        appendLine()
        appendLine("  ${indented(d.name, "    ")}")
        appendLine("    Address : ${d.address}${d.addressKind?.let { " ($it)" }.orEmpty()}")
        appendLine("    Maker   : ${d.manufacturer ?: "not advertised"}  Radio: ${d.radio}")
        if (d.advertisedName != null) appendLine("    Adv name: ${indented(d.advertisedName, "              ")}")
        if (d.services.isNotEmpty()) appendLine("    Services: ${d.services.joinToString(" / ")}")
        appendLine("    Signal  : ${d.rssi} dBm")
        // What the threat says, not `following` alone: a recognised tracker that moved
        // with you is confirmed following; anything else that did is travelling with
        // you, which a companion's phone also does.
        val verdict = when {
            d.following && d.threat == Threat.CRITICAL -> "*** CONFIRMED FOLLOWING ***"
            d.following -> "Travelling with you (not a recognised tracker away from its owner)"
            d.persistent -> "Persistent"
            else -> "Brief"
        }
        appendLine("    Status  : $verdict")
        appendLine("    Threat  : ${d.threat} (Clairix's score ${d.score})")
        appendLine("    Confidence: ${d.confidence.name}")
        appendLine("    First seen: ${t.text(d.firstSeen)}")
        appendLine("    Last seen : ${t.text(d.lastSeen)}")
        appendLine("    Sightings: ${d.sightings}  Places: ${d.places}  Displacement: ${d.displacementM.toInt()} m")
        if (d.tracker != null) appendLine("    Type    : ${d.tracker.label} (${d.tracker.brand})")
        d.dultLabel?.let { appendLine("    Identified itself as: $it") }
        if (d.separatedAt != 0L) appendLine("    Separated from owner since: ${t.text(d.separatedAt)}")
        if (d.resolvedAt != 0L) appendLine("    Marked found by the owner: ${t.text(d.resolvedAt)}")
        if (d.rotations > 0) appendLine("    MAC rotations: ${d.rotations}")
        appendLine("    ${plural(d.addresses, "address")} seen")
        if (d.points.isNotEmpty()) {
            appendLine("    PLACES HEARD (this phone's position each time):")
            for (p in d.points) appendLine("      - ${coords(p.lat, p.lon)}")
        }
    }

    private fun StringBuilder.cellBlock(cell: CellStatus?, t: ReportTime) {
        appendLine("CELLULAR STATUS")
        val c = cell?.cell
        if (c != null) {
            appendLine("  Cell    : ${c.cellId}  RAT: ${c.rat.label}")
            appendLine("  MCC/MNC : ${c.mcc}/${c.mnc}  TAC: ${c.tac}")
            appendLine("  Signal  : ${c.signalDbm ?: "??"} dBm  Neighbours: ${c.neighbors ?: "??"}")
            appendLine("  Read    : ${t.text(c.ts)}")
            appendLine("  Threat  : ${cell.level} (Clairix's score ${cell.score})")
        } else {
            appendLine("  ${cell?.reason ?: "No cell data"}")
        }
        if (cell != null && cell.findings.isNotEmpty()) {
            appendLine("  Indicators (${cell.findings.size}):")
            for (f in cell.findings) {
                appendLine("    [${f.severity}] ${f.title}")
                if (f.detail.isNotBlank()) appendLine("      ${indented(f.detail, "      ")}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.wifiBlock(wifi: List<WifiAnomaly>, t: ReportTime) {
        appendLine("WIFI ANOMALIES (${wifi.size})")
        for (w in wifi) {
            appendLine()
            appendLine("  SSID  : ${indented(w.ssid, "          ")}")
            appendLine("  BSSID : ${w.bssid}  Signal: ${w.rssi} dBm")
            appendLine("  Reason: ${w.reason}  Threat: ${w.threat}")
            appendLine("  Seen  : ${t.text(w.ts)}")
        }
        appendLine()
    }

    private fun StringBuilder.nfcBlock(nfc: List<NfcTag>, t: ReportTime) {
        appendLine("NFC TAGS SCANNED (${nfc.size})")
        for (tag in nfc) {
            appendLine()
            appendLine("  UID  : ${tag.uid}")
            appendLine("  Type : ${tag.type}  Techs: ${tag.techs.joinToString()}")
            if (tag.payload != null) appendLine("  Data : ${indented(tag.payload, "         ")}")
            appendLine("  Read : ${t.text(tag.ts)}")
            appendLine("  Note : ${tag.note}${if (tag.suspicious) "  *** SUSPICIOUS ***" else ""}")
        }
        appendLine()
    }

    // ── The phone itself ───────────────────────────────────────────────────

    private fun StringBuilder.spywareBlock(s: SpywareSection?, t: ReportTime) {
        appendLine("APPS WITH SPY-LEVEL ACCESS (${s?.apps?.size ?: 0})")
        if (s == null) {
            appendLine("  The stalkerware check could not be run for this report.")
            appendLine()
            return
        }
        appendLine("  Checked: ${t.text(s.scannedAt)}. Every app Clairix's stalkerware check flagged, and every keyboard and device admin it does not vouch for.")
        if (s.apps.isEmpty()) {
            appendLine("  None. A clean result is not proof: an app sold as parental control can look like an ordinary store install.")
        }
        for (a in s.apps) appLines(a, t)
        appendLine()
    }

    private fun StringBuilder.appLines(a: AppEvidence, t: ReportTime) {
        appendLine()
        appendLine("  ${a.label} (${a.packageName})")
        if (a.severity != null) appendLine("    Flagged     : ${a.severity}${a.score?.let { ", score $it" }.orEmpty()}")
        appendLine("    Installed   : ${a.installedAt?.let(t::text) ?: "unknown"}")
        if (a.updatedAt != null && a.updatedAt != a.installedAt) appendLine("    Last updated: ${t.text(a.updatedAt)}")
        appendLine("    Installed by: ${a.installer ?: "none recorded (adb, root or an unknown source)"}")
        if (a.namedInstaller != null && a.namedInstaller != a.installer) appendLine("    Installer it names: ${a.namedInstaller}")
        appendLine("    Access      : ${a.access.ifEmpty { listOf("none of the special access Clairix checks") }.joinToString(", ")}")
        appendLine("    Permissions : ${a.permissions.ifEmpty { listOf("none of the sensitive ones Clairix checks") }.joinToString(", ")}")
        if (a.reasons.isNotEmpty()) appendLine("    Reasons     : ${a.reasons.joinToString("; ")}")
        appendLine("    Signer SHA-256: ${a.signers.ifEmpty { listOf("not readable") }.joinToString(", ")}")
    }

    private fun StringBuilder.integrityBlock(health: PhoneHealth?, tripwire: TripwireState?, t: ReportTime) {
        appendLine("PHONE INTEGRITY")
        if (health == null || (health.facts.isEmpty() && health.findingsScannedTs == 0L)) {
            appendLine("  The phone check had not run when this report was written.")
        } else {
            if (health.findingsScannedTs != 0L) appendLine("  Checked: ${t.text(health.findingsScannedTs)}")
            appendLine("  Microphone recordings receiving audio: ${health.activeRecordings}; camera held open by another app: ${if (health.cameraInUse) "yes" else "no"}")
            appendLine("  Findings (${health.findings.size}):")
            for (f in health.findings) appendLine("    [${f.severity}] ${f.category}: ${f.title}. ${f.detail}")
            appendLine("  Checklist (${health.facts.size} rows, ${health.facts.count { !it.ok }} not in order):")
            for (f in health.facts) {
                val mark = f.severity?.name ?: "OK"
                appendLine("    [$mark] ${f.category} / ${f.label}: ${f.value}${f.detail?.let { ". $it" }.orEmpty()}")
            }
        }
        if (tripwire != null) {
            val armed = if (tripwire.armedAt != 0L) "armed ${t.text(tripwire.armedAt)}" else "not armed (no strong biometric enrolled)"
            val fired = if (tripwire.lastTripAt != 0L) "last fired ${t.text(tripwire.lastTripAt)}" else "has not fired"
            appendLine("  Biometric tripwire: $armed; $fired")
        }
        appendLine()
    }

    private fun StringBuilder.unlocksBlock(u: UnlockSection?, range: ReportRange, t: ReportTime) {
        val inRange = u?.anomalies?.filter { range.contains(it.ts) }.orEmpty()
        appendLine("UNLOCKS DURING SLEEP WINDOW (${inRange.size})")
        if (u == null) {
            appendLine("  The unlock ledger could not be read for this report.")
            appendLine()
            return
        }
        appendLine(
            "  Sleep window: " + (u.sleepWindow?.let { "$it, this phone's time, as the owner set it" }
                ?: "not set, so the ledger judges no unlock")
        )
        appendLine("  Ledger: device admin ${if (u.adminActive) "on" else "off"}, usage access ${if (u.usageAccess) "granted" else "not granted"}")
        for (e in inRange) appendLine("  ${t.text(e.ts)}  ${unlockLabel(e.kind)}")
        appendLine()
    }

    private fun StringBuilder.trustBlock(audit: CaAudit?, t: ReportTime) {
        appendLine("TRUST STORE (${plural(audit?.roots?.size ?: 0, "certificate")} worth a look)")
        if (audit == null) {
            appendLine("  The trust store could not be read for this report.")
            appendLine()
            return
        }
        appendLine(
            "  Read: hand-installed certificates ${if (audit.userStoreRead) "yes" else "no"}, " +
                "system roots ${if (audit.trustManagerRead) "yes" else "no"}${audit.error?.let { " ($it)" }.orEmpty()}"
        )
        if (audit.roots.isEmpty() && audit.complete) appendLine("  None: no certificate installed by hand and no system root outside Android's own list.")
        for (ca in audit.roots) {
            val where = if (ca.source == "user") "installed by hand" else "in the system store, not in Android's own list"
            appendLine("  [${ca.severity}] $where")
            appendLine("    Subject : ${ca.subjectDn}")
            appendLine("    Issuer  : ${ca.issuerDn}")
            appendLine("    SHA-256 : ${ca.fingerprint}")
            appendLine("    Valid   : ${t.text(ca.validFrom)} to ${t.text(ca.validTo)}")
            if (ca.reason.isNotBlank()) appendLine("    Why     : ${ca.reason}")
        }
        if (audit.unreadableUser > 0) appendLine("  ${plural(audit.unreadableUser, "certificate")} installed by hand would not open.")
        appendLine()
    }

    private fun StringBuilder.camerasBlock(input: ReportInput, range: ReportRange, t: ReportTime) {
        appendLine("HIDDEN-CAMERA SWEEP")
        val c = input.cameras
        if (c == null) {
            appendLine("  The sweep's record could not be read for this report.")
        } else {
            appendLine("  Last run : ${if (c.lastRunTs != 0L) t.text(c.lastRunTs) else "never"}")
            appendLine("  Checklist: ${c.stepsDone} of ${c.stepsTotal} steps ticked")
            val findings = c.findings.filter { range.contains(it.ts) }
            appendLine("  Findings (${findings.size}):")
            for (f in findings) {
                val pct = (f.confidence * 100).roundToInt()
                appendLine("    ${t.text(f.ts)}  ${methodLabel(f.method)}, $pct % confidence")
                appendLine("      Where: ${indented(f.location, "      ")}")
                if (f.detail.isNotBlank()) appendLine("      ${indented(f.detail, "      ")}")
                if (f.lat != null && f.lon != null) appendLine("      Phone at ${coords(f.lat, f.lon)}")
            }
        }
        val kept = photoEvents(input.timeline, range)
        appendLine("  Photos kept (${kept.size}):")
        for (e in kept) {
            appendLine("    ${t.text(e.ts)}  ${indented(e.title, "      ")}")
            appendLine("      ${input.photos[e.id]?.let { "File: $it, shared with this report" } ?: "Kept, but it could not be opened on this phone"}")
        }
        appendLine()
    }

    private fun StringBuilder.timelineBlock(input: ReportInput, range: ReportRange, t: ReportTime) {
        val events = input.timeline.filter { range.contains(it.ts) }
        appendLine("EVENT TIMELINE (${plural(events.size, "event")}, newest first)")
        for (e in events) {
            appendLine("  ${t.text(e.ts)} [${e.severity}] ${e.kind}: ${indented(e.title, "    ")}")
            if (e.detail.isNotBlank()) appendLine("    ${indented(e.detail, "    ")}")
            val lat = e.lat
            val lon = e.lon
            if (lat != null && lon != null) appendLine("    @ ${coords(lat, lon)}")
            input.photos[e.id]?.let { appendLine("    Photo: $it") }
        }
        appendLine()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The camera events in [range] that carry a kept photo. */
    fun photoEvents(timeline: List<TimelineEvent>, range: ReportRange): List<TimelineEvent> =
        timeline.filter { it.kind == EventKind.CAMERA && SweepPhotos.isPhoto(it.attachment) && range.contains(it.ts) }

    fun methodLabel(m: CameraFindMethod): String = when (m) {
        CameraFindMethod.LENS_GLINT -> "lens glint"
        CameraFindMethod.IR_ILLUMINATOR -> "IR illuminator"
        CameraFindMethod.NETWORK -> "camera on the Wi-Fi"
        CameraFindMethod.BLE_SETUP_AP -> "Bluetooth setup beacon"
        CameraFindMethod.WIFI_SETUP_AP -> "Wi-Fi setup hotspot"
        CameraFindMethod.MAGNETIC -> "magnetic anomaly"
    }

    fun unlockLabel(kind: UnlockKind): String = when (kind) {
        UnlockKind.PASSWORD_SUCCEEDED -> "screen lock passed"
        UnlockKind.KEYGUARD_HIDDEN -> "phone unlocked"
        else -> kind.name.lowercase(Locale.ROOT).replace('_', ' ')
    }

    fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun plural(n: Int, word: String): String = when {
        n == 1 -> "1 $word"
        word.endsWith("s") -> "$n ${word}es"
        else -> "$n ${word}s"
    }

    private fun minutes(m: Float): String = plural(m.roundToInt(), "minute")

    private fun article(word: String): String = if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
}
