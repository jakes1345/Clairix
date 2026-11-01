package app.clairix.analysis

import app.clairix.NfcTag
import app.clairix.PhoneHealth
import app.clairix.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The machine-readable evidence report: the same sections as [ReportText], as
 * JSON. Every moment is given as epoch milliseconds, ISO-8601 UTC ("..._utc") and
 * the phone's local time with its offset ("..._local"). Pure, like the text.
 */
internal object ReportJson {

    /** Bumped when a field changes meaning; added fields do not bump it. */
    const val VERSION = 2

    fun body(header: ReportHeader, input: ReportInput, range: ReportRange): JSONObject {
        val t = ReportTime(header.zone)
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("app", "clairix")
        root.put("header", headerJson(header, range, t))
        root.put("summary", JSONArray().also { a ->
            input.detections.filter { it.following && range.overlaps(it.firstSeen, it.lastSeen) }
                .forEach { a.put(JSONObject().put("addr", it.address).put("text", ReportText.summary(it, t))) }
        })
        root.put("status", statusJson(input))
        root.put("ble", bleJson(input, range, t))
        radioSections(root, input, range, t)
        phoneSections(root, input, range, t)
        input.trustStore?.let { root.put("trust_store", trustJson(it, t)) }
        root.put("cameras", camerasJson(input, range, t))
        root.put("timeline", JSONArray().also { a -> input.timeline.filter { range.contains(it.ts) }.forEach { a.put(eventJson(it, input, t)) } })
        return root
    }

    /** The cell, Wi-Fi and NFC sections, each within [range]. */
    private fun radioSections(root: JSONObject, input: ReportInput, range: ReportRange, t: ReportTime) {
        input.cell?.let { cell ->
            root.put("cell", ThreatPayload.buildCellSection(cell).also { o -> cell.cell?.let { time(o, "cell_ts", it.ts, t) } })
        }
        root.put("wifi", JSONArray().also { a ->
            input.wifi.filter { range.contains(it.ts) }.forEach { w -> a.put(ThreatPayload.buildWifiSection(w).also { time(it, "ts", w.ts, t) }) }
        })
        root.put("nfc", JSONArray().also { a -> input.nfc.filter { range.contains(it.ts) }.forEach { a.put(nfcJson(it, t)) } })
    }

    /** The spyware verdict, the phone's integrity and the unlocks during the sleep window. */
    private fun phoneSections(root: JSONObject, input: ReportInput, range: ReportRange, t: ReportTime) {
        input.spyware?.let { root.put("spyware", spywareJson(it, t)) }
        healthJson(input.health, input.tripwire, t)?.let { root.put("phone_integrity", it) }
        input.unlocks?.let { u ->
            root.put("sleep_unlocks", JSONObject().apply {
                u.sleepWindow?.let { put("sleep_window", it) }
                put("admin_active", u.adminActive)
                put("usage_access", u.usageAccess)
                put("unlocks", JSONArray().also { a ->
                    u.anomalies.filter { range.contains(it.ts) }.forEach { e ->
                        a.put(JSONObject().put("kind", e.kind.name).put("what", ReportText.unlockLabel(e.kind)).also { time(it, "ts", e.ts, t) })
                    }
                })
            })
        }
    }

    /** Puts [ts] under [key] (milliseconds), "[key]_utc" and "[key]_local". */
    fun time(o: JSONObject, key: String, ts: Long, t: ReportTime) {
        o.put(key, ts)
        o.put("${key}_utc", t.isoUtc(ts))
        o.put("${key}_local", t.isoLocal(ts))
    }

    private fun headerJson(h: ReportHeader, range: ReportRange, t: ReportTime) = JSONObject().apply {
        time(this, "generated", h.generatedAt, t)
        put("range", JSONObject().apply {
            range.from?.let { time(this, "from", it, t) }
            range.to?.let { time(this, "to", it, t) }
            put("everything_kept", range.from == null && range.to == null)
        })
        put("app_version", h.appVersion)
        put("app_build", h.appBuild)
        put("build_type", h.buildType)
        put("package", h.packageName)
        put("edition", h.edition)
        put("manufacturer", h.manufacturer)
        put("model", h.model)
        put("android_release", h.androidRelease)
        put("android_sdk", h.sdkInt)
        put("security_patch", h.securityPatch)
        put("retention_days", h.retentionDays)
        put("follow_threshold_m", h.followThresholdM.toDouble())
        put("persistence_threshold_min", h.persistenceThresholdMin.toDouble())
        put("time_zone", h.zone.id)
        put("utc_offset", t.offset(h.generatedAt))
        put("signature_algorithm", h.signing.algorithm)
        h.signing.publicKey?.let {
            put("public_key", it)
            ReportSigning.fingerprint(it)?.let { fp -> put("public_key_sha256", fp.replace(" ", "")) }
        }
        h.signing.unavailable?.let { put("signature_unavailable", it) }
    }

    private fun statusJson(input: ReportInput) = JSONObject().apply {
        val s = input.status
        put("scanning", s.scanning)
        put("fix", s.hasFix)
        val lat = s.lat
        val lon = s.lon
        if (s.hasFix && lat != null && lon != null) {
            put("lat", lat)
            put("lon", lon)
        }
        put("travelled_m", s.travelledM)
        put("nearby", s.nearbyCount)
    }

    private fun bleJson(input: ReportInput, range: ReportRange, t: ReportTime) = JSONArray().also { a ->
        for (d in input.detections.filter { range.overlaps(it.firstSeen, it.lastSeen) }) {
            a.put(ThreatPayload.buildBleSection(d, includeTrail = true).also {
                it.put("name", d.name)
                it.put("threat", d.threat.name)
                it.put("score", d.score)
                it.put("following", d.following)
                it.put("persistent", d.persistent)
                time(it, "first", d.firstSeen, t)
                time(it, "last", d.lastSeen, t)
                if (d.separatedAt != 0L) time(it, "separated", d.separatedAt, t)
                if (d.resolvedAt != 0L) time(it, "resolved_at", d.resolvedAt, t)
                d.tracker?.let { tr -> it.put("tracker", tr.label); it.put("brand", tr.brand) }
                d.dultLabel?.let { label -> it.put("identified_as", label) }
            })
        }
    }

    private fun nfcJson(tag: NfcTag, t: ReportTime) = JSONObject().apply {
        put("uid", tag.uid)
        put("type", tag.type)
        put("techs", JSONArray().also { a -> tag.techs.forEach { a.put(it) } })
        tag.payload?.let { put("payload", it) }
        put("suspicious", tag.suspicious)
        put("note", tag.note)
        time(this, "ts", tag.ts, t)
    }

    private fun strings(list: List<String>) = JSONArray().also { a -> list.forEach { a.put(it) } }

    private fun spywareJson(s: SpywareSection, t: ReportTime) = JSONObject().apply {
        time(this, "scanned", s.scannedAt, t)
        put("apps", JSONArray().also { a ->
            for (app in s.apps) {
                a.put(JSONObject().apply {
                    put("package", app.packageName)
                    put("label", app.label)
                    app.severity?.let { put("severity", it.name) }
                    app.score?.let { put("score", it) }
                    app.installer?.let { put("installer", it) }
                    app.namedInstaller?.let { put("named_installer", it) }
                    app.installedAt?.let { time(this, "installed", it, t) }
                    app.updatedAt?.let { time(this, "updated", it, t) }
                    put("access", strings(app.access))
                    put("permissions", strings(app.permissions))
                    put("reasons", strings(app.reasons))
                    put("signer_sha256", strings(app.signers))
                })
            }
        })
    }

    private fun healthJson(health: PhoneHealth?, tripwire: TripwireState?, t: ReportTime): JSONObject? {
        if (health == null && tripwire == null) return null
        return JSONObject().apply {
            if (health != null) {
                if (health.findingsScannedTs != 0L) time(this, "checked", health.findingsScannedTs, t)
                put("active_recordings", health.activeRecordings)
                put("camera_in_use", health.cameraInUse)
                put("findings", JSONArray().also { a ->
                    health.findings.forEach { f ->
                        a.put(JSONObject().put("id", f.id).put("severity", f.severity.name).put("category", f.category).put("title", f.title).put("detail", f.detail))
                    }
                })
                put("checklist", JSONArray().also { a ->
                    health.facts.forEach { f ->
                        a.put(JSONObject().apply {
                            put("id", f.id); put("category", f.category); put("label", f.label); put("value", f.value)
                            put("severity", f.severity?.name ?: "OK")
                            f.detail?.let { put("detail", it) }
                        })
                    }
                })
            }
            if (tripwire != null) {
                put("tripwire", JSONObject().apply {
                    if (tripwire.armedAt != 0L) time(this, "armed", tripwire.armedAt, t)
                    if (tripwire.lastTripAt != 0L) time(this, "last_trip", tripwire.lastTripAt, t)
                })
            }
        }
    }

    private fun trustJson(audit: CaAudit, t: ReportTime) = JSONObject().apply {
        put("user_store_read", audit.userStoreRead)
        put("system_roots_read", audit.trustManagerRead)
        audit.error?.let { put("error", it) }
        put("unreadable_user", audit.unreadableUser)
        put("roots", JSONArray().also { a ->
            audit.roots.forEach { ca ->
                a.put(JSONObject().apply {
                    put("source", ca.source); put("severity", ca.severity.name)
                    put("subject", ca.subjectDn); put("issuer", ca.issuerDn); put("sha256", ca.fingerprint)
                    time(this, "valid_from", ca.validFrom, t)
                    time(this, "valid_to", ca.validTo, t)
                    if (ca.reason.isNotBlank()) put("reason", ca.reason)
                })
            }
        })
    }

    private fun camerasJson(input: ReportInput, range: ReportRange, t: ReportTime) = JSONObject().apply {
        input.cameras?.let { c ->
            if (c.lastRunTs != 0L) time(this, "last_run", c.lastRunTs, t)
            put("steps_done", c.stepsDone)
            put("steps_total", c.stepsTotal)
            put("findings", JSONArray().also { a ->
                c.findings.filter { range.contains(it.ts) }.forEach { f ->
                    a.put(JSONObject().apply {
                        put("id", f.id); put("method", f.method.name); put("what", ReportText.methodLabel(f.method))
                        put("confidence", f.confidence.toDouble()); put("location", f.location); put("detail", f.detail)
                        f.lat?.let { put("lat", it) }
                        f.lon?.let { put("lon", it) }
                        time(this, "ts", f.ts, t)
                    })
                }
            })
        }
        put("photos", JSONArray().also { a ->
            ReportText.photoEvents(input.timeline, range).forEach { e ->
                a.put(JSONObject().apply {
                    put("event", e.id)
                    input.photos[e.id]?.let { put("file", it) } ?: put("unreadable", true)
                    time(this, "ts", e.ts, t)
                })
            }
        })
    }

    private fun eventJson(e: TimelineEvent, input: ReportInput, t: ReportTime) = JSONObject().apply {
        put("id", e.id); put("kind", e.kind.name)
        time(this, "ts", e.ts, t)
        put("sev", e.severity.name); put("title", e.title); put("detail", e.detail)
        e.lat?.let { put("lat", it) }
        e.lon?.let { put("lon", it) }
        input.photos[e.id]?.let { put("photo", it) }
    }
}
