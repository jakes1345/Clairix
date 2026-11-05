package app.clairix.analysis

import app.clairix.CellStatus
import app.clairix.Detection
import app.clairix.EventKind
import app.clairix.FollowConfidence
import app.clairix.HealthFact
import app.clairix.LatLon
import app.clairix.PhoneHealth
import app.clairix.PhoneHealthFinding
import app.clairix.ScanStatus
import app.clairix.Severity
import app.clairix.TestStoreCipher
import app.clairix.Threat
import app.clairix.TimelineEvent
import app.clairix.TrackerType
import app.clairix.UnlockEvent
import app.clairix.UnlockKind
import app.clairix.WifiAnomaly
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.TimeZone

/**
 * The evidence report: what it says (header, summary, sections, every time as UTC
 * then local), what period it covers, that its signature checks and breaks on an
 * edit, and which files a share carries.
 */
class ReportTest {

    /** 2026-11-01 14:02:11 UTC, a Sunday; Berlin is on CET (UTC+01:00) by then. */
    private val now = 1_793_541_731_000L
    /** 2026-10-03 12:00:00 UTC; Berlin is on CEST (UTC+02:00). */
    private val oct3 = 1_791_028_800_000L
    private val day = 24 * 60 * 60_000L
    private val berlin: TimeZone = TimeZone.getTimeZone("Europe/Berlin")
    private val airtag = TrackerType("airtag", "Apple AirTag", "Apple", Threat.CRITICAL, "")

    /** An ordinary JVM key standing in for the Keystore's. */
    private class JvmSigner(private val pair: KeyPair) : ReportSigner {
        override val algorithm = ReportSigning.ALGORITHM
        override fun publicKey(): ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance(algorithm).run {
            initSign(pair.private)
            update(data)
            sign()
        }
    }

    private val signer = JvmSigner(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())

    private fun header(signer: ReportSigner? = this.signer, retention: Int = 30) = ReportHeader(
        generatedAt = now, zone = berlin, appVersion = "3.0.0-beta.8", appBuild = 28, buildType = "release",
        packageName = "app.clairix", edition = "Full, play flavour (playSuite)", manufacturer = "Google", model = "Pixel 8",
        androidRelease = "15", sdkInt = 35, securityPatch = "2026-10-05", retentionDays = retention,
        followThresholdM = 450f, persistenceThresholdMin = 15f, signing = ReportSigning.key(signer)
    )

    private fun follower(key: String, name: String, threat: Threat, first: Long = oct3, separatedAt: Long = 0L) = Detection(
        key = key, address = key, name = name, rssi = -60,
        tracker = if (threat == Threat.CRITICAL) airtag else null,
        threat = threat, score = 80, firstSeen = first, lastSeen = first + 49 * 60_000L, sightings = 40,
        persistent = true, following = true, confidence = FollowConfidence.CONFIRMED,
        displacementM = 412.4, places = 4, rotations = 0, addresses = 1, approxMetres = null,
        points = listOf(LatLon(52.52, 13.405), LatLon(52.523, 13.41)), lastHeardAt = LatLon(52.525, 13.412),
        manufacturer = if (threat == Threat.CRITICAL) "Apple" else "Samsung",
        separatedAt = separatedAt
    )

    private fun event(id: String, ts: Long, kind: EventKind = EventKind.FOLLOWING, attachment: String? = null) =
        TimelineEvent(id, kind, ts, "Event $id", "detail $id", Severity.HIGH, 52.5, 13.4, attachment)

    private fun input(timeline: List<TimelineEvent> = emptyList(), detections: List<Detection> = emptyList()) = ReportInput(
        status = ScanStatus(scanning = true),
        detections = detections,
        timeline = timeline,
        cell = CellStatus(),
        wifi = listOf(WifiAnomaly("FreeWifi", "aa:bb", -50, "open_twin_of_secured", Threat.HIGH, oct3)),
        spyware = SpywareSection(
            scannedAt = now - 60_000L,
            apps = listOf(
                AppEvidence(
                    packageName = "com.spy.sync", label = "System Sync", severity = Severity.HIGH, score = 9,
                    installer = null, namedInstaller = "com.android.vending", installedAt = oct3, updatedAt = oct3,
                    access = listOf("accessibility service on (reads the screen)"), permissions = listOf("read text messages"),
                    reasons = listOf("hidden icon"), signers = listOf("AB:CD:EF"), savedAt = now,
                    batteryExempt = true, requestedOnly = listOf("camera"),
                    dataUsage = DataUsage.Summary(List(DataUsage.DAYS) { DataUsage.Day(100_000L, 2_000_000L) })
                )
            )
        ),
        health = PhoneHealth(
            findings = listOf(PhoneHealthFinding("adb", Severity.MEDIUM, "Debugging", "USB debugging on", "Anyone with a cable can install apps.")),
            facts = listOf(HealthFact("lock", "Lock", "Screen lock", "PIN")),
            findingsScannedTs = now - 120_000L
        ),
        tripwire = TripwireState(armedAt = oct3 - 10 * day, lastTripAt = oct3 + day),
        unlocks = UnlockSection("23:00 to 07:00", adminActive = true, usageAccess = true,
            anomalies = listOf(UnlockEvent(1, oct3 + 3_600_000L, UnlockKind.KEYGUARD_HIDDEN, null), UnlockEvent(2, oct3 - 20 * day, UnlockKind.KEYGUARD_HIDDEN, null))),
        trustStore = CaAudit(
            roots = listOf(SuspiciousCa("CN=Proxy CA", "CN=Proxy CA", "11:22", oct3 - day, oct3 + 365 * day, Severity.HIGH, "user", "installed by hand")),
            userStoreRead = true, trustManagerRead = true
        ),
        cameras = CameraSection(
            lastRunTs = oct3, stepsDone = 4, stepsTotal = 11,
            findings = listOf(
                CameraFinding("glint|1", oct3, CameraFindMethod.LENS_GLINT, 0.9f, "In the viewfinder, 37 % from the left", "Held still", 52.5, 13.4),
                CameraFinding("mag|1", oct3 - 20 * day, CameraFindMethod.MAGNETIC, 0.5f, "Near the smoke alarm", "", null, null)
            )
        )
    )

    // ── Header and times ─────────────────────────────────────────────────

    @Test
    fun timesAreUtcThenTheLocalTimeWithItsOffset() {
        val t = ReportTime(berlin)
        assertEquals("2026-11-01 14:02:11 UTC (2026-11-01 15:02:11 local, UTC+01:00)", t.text(now))
        assertEquals("2026-10-03 12:00:00 UTC (2026-10-03 14:00:00 local, UTC+02:00)", t.text(oct3))
        assertEquals("2026-11-01T14:02:11Z", t.isoUtc(now))
        assertEquals("2026-11-01T15:02:11+01:00", t.isoLocal(now))
        assertEquals("-05:00", ReportTime(TimeZone.getTimeZone("America/Bogota")).offset(now))
    }

    @Test
    fun theReportBlockSaysWhatWroteItWhereAndWithWhichSettings() {
        val text = Report.toText(header(), input(), ReportRange(), signer)
        val block = text.substringAfter("REPORT\n").substringBefore("\n\n")
        listOf(
            "Generated  : 2026-11-01 14:02:11 UTC (2026-11-01 15:02:11 local, UTC+01:00)",
            "Covers     : everything Clairix has kept, up to 30 days",
            "App        : Clairix 3.0.0-beta.8 (build 28, release)",
            "Package    : app.clairix",
            "Edition    : Full, play flavour (playSuite)",
            "Device     : Google Pixel 8",
            "Android    : 15 (API 35), security patch 2026-10-05",
            "History    : anything older than 30 days is deleted automatically",
            "persistent after 15 minutes of presence; following after a 450 m move",
            "Time zone  : Europe/Berlin, UTC+01:00",
            "Signature  : SHA256withECDSA",
            "Public key : " + ReportSigning.base64(signer.publicKey()),
            "Key fingerprint (SHA-256 of the public key): " + ReportSigning.fingerprint(ReportSigning.base64(signer.publicKey()))
        ).forEach { assertTrue("missing: $it\n$block", it in block) }
    }

    @Test
    fun everySectionIsInTheTextAndTheJson() {
        val text = Report.toText(header(), input(), ReportRange(), signer)
        listOf(
            "SUMMARY", "LOCATION", "TRACKED DEVICES", "CELLULAR STATUS", "WIFI ANOMALIES (1)", "NFC TAGS SCANNED",
            "APPS WITH SPY-LEVEL ACCESS (1)", "PHONE INTEGRITY", "UNLOCKS DURING SLEEP WINDOW (2)",
            "TRUST STORE (1 certificate worth a look)", "HIDDEN-CAMERA SWEEP", "EVENT TIMELINE", ReportText.INTEGRITY_MARKER
        ).forEach { assertTrue("missing section: $it", it in text) }
        // The evidence a protective order needs, app by app.
        assertTrue("Installed by: none recorded (adb, root or an unknown source)" in text)
        assertTrue("Installer it names: com.android.vending" in text)
        assertTrue("Signer SHA-256: AB:CD:EF" in text)
        assertTrue("Access      : accessibility service on (reads the screen)" in text)
        assertTrue("Asked for, not granted: camera" in text)
        assertTrue("Battery     : exempt (Android never pauses it in the background)" in text)
        assertTrue("Data, 7 days: sent 14.0 MB and received 700.0 kB in 7 days, sending on 7 of them" in text)
        assertTrue("Biometric tripwire: armed 2026-09-23" in text)
        assertTrue("Sleep window: 23:00 to 07:00" in text)
        assertTrue("[HIGH] installed by hand" in text)
        assertTrue("Checklist: 4 of 11 steps ticked" in text)
        assertTrue("lens glint, 90 % confidence" in text)

        val root = JSONObject(Report.toJson(header(), input(), ReportRange(), signer)).getJSONObject("report")
        listOf("header", "summary", "status", "ble", "cell", "wifi", "nfc", "spyware", "phone_integrity", "sleep_unlocks", "trust_store", "cameras", "timeline")
            .forEach { assertTrue("missing key: $it", root.has(it)) }
        val app = root.getJSONObject("spyware").getJSONArray("apps").getJSONObject(0)
        assertEquals("com.spy.sync", app.getString("package"))
        assertEquals("AB:CD:EF", app.getJSONArray("signer_sha256").getString(0))
        assertEquals("camera", app.getJSONArray("requested_not_granted").getString(0))
        assertTrue(app.getBoolean("battery_exempt"))
        val data = app.getJSONObject("data_usage")
        assertEquals(14_000_000L, data.getLong("sent_bytes"))
        assertTrue(data.getBoolean("steady_uploader"))
        assertEquals(7, data.getJSONArray("per_day").length())
        assertEquals("2026-10-03T12:00:00Z", app.getString("installed_utc"))
        assertEquals("2026-10-03T14:00:00+02:00", app.getString("installed_local"))
        val h = root.getJSONObject("header")
        assertEquals("3.0.0-beta.8", h.getString("app_version"))
        assertEquals("Pixel 8", h.getString("model"))
        assertEquals("2026-10-05", h.getString("security_patch"))
        assertEquals(30, h.getInt("retention_days"))
        assertEquals(450.0, h.getDouble("follow_threshold_m"), 0.0)
        assertEquals("Europe/Berlin", h.getString("time_zone"))
        assertEquals("2026-11-01T15:02:11+01:00", h.getString("generated_local"))
        assertEquals(1, root.getJSONArray("wifi").length())
    }

    // ── Summary ──────────────────────────────────────────────────────────

    @Test
    fun aFollowingDeviceIsSummarisedInSentences() {
        val tag = follower("tag", "Apple AirTag", Threat.CRITICAL, separatedAt = oct3 + 10 * 60_000L)
        assertEquals(
            "An Apple AirTag (address tag) was heard with this phone at 4 places 412 m apart, " +
                "from 2026-10-03 12:00:00 UTC (2026-10-03 14:00:00 local, UTC+02:00) to 2026-10-03 12:49:00 UTC (2026-10-03 14:49:00 local, UTC+02:00). " +
                "The first place recorded was near 52.52000, 13.40500. It was last heard near 52.52500, 13.41200. " +
                "It identifies itself as a tracker away from its owner, so Clairix counts it as following this phone. " +
                "It went from near its owner to separated from its owner at 2026-10-03 12:10:00 UTC (2026-10-03 14:10:00 local, UTC+02:00).",
            Report.summary(tag, header())
        )
        val buds = Report.summary(follower("buds", "Galaxy Buds", Threat.HIGH), header())
        assertTrue(buds, buds.startsWith("An unidentified Samsung Bluetooth device (address buds)"))
        assertTrue(buds, "Nothing identifies it as a tracker" in buds)
    }

    @Test
    fun theStatusLineFollowsTheThreat() {
        val text = Report.toText(
            header(),
            input(detections = listOf(follower("tag", "Apple AirTag", Threat.CRITICAL, separatedAt = oct3 + 300_000L), follower("buds", "Galaxy Buds", Threat.HIGH))),
            ReportRange(), signer
        )
        assertEquals(1, Regex("CONFIRMED FOLLOWING").findAll(text).count())
        assertTrue(text, "Travelling with you (not a recognised tracker away from its owner)" in text)
        assertTrue(text, "Separated from owner since" in text)
        assertEquals(2, text.substringAfter("SUMMARY\n").substringBefore("\n\n").lines().count { it.startsWith("  - ") })
    }

    // ── The period ───────────────────────────────────────────────────────

    @Test
    fun everythingKeptIsExportedWithNoCap() {
        val events = (0 until 150).map { event("e$it", now - it * 60_000L) }
        val text = Report.toText(header(), input(events), ReportRange(), signer)
        assertTrue("EVENT TIMELINE (150 events, newest first)" in text)
        assertTrue("Event e149" in text)
        val json = JSONObject(Report.toJson(header(), input(events), ReportRange(), signer)).getJSONObject("report")
        assertEquals(150, json.getJSONArray("timeline").length())
    }

    @Test
    fun aPickedPeriodLeavesOutWhatFellOutsideIt() {
        val range = ReportRange(from = oct3 - day, to = oct3 + 2 * day)
        val events = listOf(event("inside", oct3), event("before", oct3 - 5 * day), event("after", oct3 + 5 * day))
        val detections = listOf(follower("early", "Early tag", Threat.CRITICAL, first = oct3 - 10 * day), follower("tag", "Apple AirTag", Threat.CRITICAL))
        val text = Report.toText(header(), input(events, detections), range, signer)
        assertTrue("EVENT TIMELINE (1 event, newest first)" in text)
        assertTrue("Event inside" in text)
        assertFalse("Event before" in text)
        assertFalse("Event after" in text)
        assertTrue("TRACKED DEVICES (1)" in text)
        assertFalse("Early tag" in text)
        assertTrue("UNLOCKS DURING SLEEP WINDOW (1)" in text)
        assertTrue("Findings (1):" in text)
        assertFalse("Near the smoke alarm" in text)
        assertTrue("Covers     : from 2026-10-02 12:00:00 UTC" in text)
        val json = JSONObject(Report.toJson(header(), input(events, detections), range, signer)).getJSONObject("report")
        assertEquals(1, json.getJSONArray("timeline").length())
        assertEquals(1, json.getJSONArray("ble").length())
        assertEquals(1, json.getJSONObject("sleep_unlocks").getJSONArray("unlocks").length())
        assertFalse(json.getJSONObject("header").getJSONObject("range").getBoolean("everything_kept"))
    }

    @Test
    fun aDayPickedOnTheCalendarIsTheWholeLocalDay() {
        // The picker reports 3 October as UTC midnight.
        val picked = 1_791_000_000_000L - (1_791_000_000_000L % day)
        val start = ReportRange.dayStart(picked, berlin)
        val end = ReportRange.dayEnd(picked, berlin)
        assertEquals("2026-10-03 00:00:00", ReportTime(berlin).text(start).substringAfter("(").substringBefore(" local"))
        assertEquals("2026-10-03 23:59:59", ReportTime(berlin).text(end).substringAfter("(").substringBefore(" local"))
        assertEquals(picked, ReportRange.pickerDay(start, berlin))
        // Picked the wrong way round: the ends swap, each still a whole day.
        val swapped = ReportRange(from = end - day + 1 + day, to = end).ordered()
        assertTrue((swapped.from ?: 0L) < (swapped.to ?: 0L))
    }

    // ── Integrity ────────────────────────────────────────────────────────

    @Test
    fun theTextSignatureChecksAndAnEditBreaksIt() {
        val text = Report.toText(header(), input(listOf(event("e1", oct3))), ReportRange(), signer)
        val body = text.substringBefore(ReportText.INTEGRITY_MARKER + "\n")
        val block = text.substringAfter(ReportText.INTEGRITY_MARKER + "\n")
        val hash = block.substringAfter("SHA-256 of everything above this block (UTF-8): ").substringBefore("\n")
        val sig = block.substringAfter("Signature (SHA256withECDSA, DER, base64): ").substringBefore("\n")
        val key = ReportSigning.base64(signer.publicKey())
        assertEquals(ReportSigning.sha256Hex(body.toByteArray(Charsets.UTF_8)), hash)
        assertTrue(ReportSigning.verify(key, body.toByteArray(Charsets.UTF_8), sig))
        // One coordinate changed after the fact.
        val edited = body.replace("52.50000, 13.40000", "52.60000, 13.40000")
        assertFalse(edited == body)
        assertFalse(ReportSigning.verify(key, edited.toByteArray(Charsets.UTF_8), sig))
        assertFalse(ReportSigning.sha256Hex(edited.toByteArray(Charsets.UTF_8)) == hash)
        // The commands it prints are the ones that check it.
        assertTrue("sed '/^=== INTEGRITY ===\$/,\$d' report.txt > body.txt" in block)
        assertTrue("openssl dgst -sha256 -verify key.pem -signature sig.der body.txt" in block)
    }

    @Test
    fun theJsonSignatureCoversTheFirstLine() {
        val json = Report.toJson(header(), input(listOf(event("e1", oct3))), ReportRange(), signer)
        val body = json.lines().first().removePrefix("{\"report\":").removeSuffix(",")
        val integrity = JSONObject(json).getJSONObject("integrity")
        assertEquals(ReportSigning.sha256Hex(body.toByteArray(Charsets.UTF_8)), integrity.getString("sha256"))
        val key = ReportSigning.base64(signer.publicKey())
        assertTrue(ReportSigning.verify(key, body.toByteArray(Charsets.UTF_8), integrity.getString("signature")))
        assertFalse(ReportSigning.verify(key, body.replace("e1", "e2").toByteArray(Charsets.UTF_8), integrity.getString("signature")))
        // The body as written parses on its own: it is what a tool reads.
        assertEquals(ReportJson.VERSION, JSONObject(body).getInt("v"))
    }

    @Test
    fun withoutAKeyTheReportSaysItIsNotSigned() {
        val broken = object : ReportSigner {
            override val algorithm = ReportSigning.ALGORITHM
            override fun publicKey(): ByteArray = error("Keystore unavailable")
            override fun sign(data: ByteArray): ByteArray = error("Keystore unavailable")
        }
        val text = Report.toText(header(broken), input(), ReportRange(), broken)
        assertTrue("Signature  : none, the signing key was not available (IllegalStateException: Keystore unavailable)" in text)
        assertTrue("Signature: none (" in text)
        assertFalse("Public key :" in text)
        assertTrue("SHA-256 of everything above this block" in text)
    }

    @Test
    fun textFromOutsideCannotMoveTheIntegrityMarker() {
        val forged = TimelineEvent("w", EventKind.WIFI_ANOMALY, oct3, "Open twin", "SSID x\n=== INTEGRITY ===\nforged", Severity.HIGH)
        val text = Report.toText(header(), input(listOf(forged)), ReportRange(), signer)
        assertEquals(1, text.lines().count { it == ReportText.INTEGRITY_MARKER })
        val body = text.substringBefore("\n" + ReportText.INTEGRITY_MARKER + "\n") + "\n"
        val sig = text.substringAfter("Signature (SHA256withECDSA, DER, base64): ").substringBefore("\n")
        assertTrue(ReportSigning.verify(ReportSigning.base64(signer.publicKey()), body.toByteArray(Charsets.UTF_8), sig))
    }

    // ── Sharing ──────────────────────────────────────────────────────────

    @Test
    fun theShareCarriesTheReportAndEveryPhotoKeptInThePeriod() {
        val files = Files.createTempDirectory("files").toFile()
        val cache = Files.createTempDirectory("cache").toFile()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val kept = SweepPhotos.save(files, jpeg, TestStoreCipher)
        val old = SweepPhotos.save(files, jpeg, TestStoreCipher)
        val timeline = listOf(
            event("photo", oct3, EventKind.CAMERA, kept),
            event("gone", oct3 + 60_000L, EventKind.CAMERA, "sweep/" + "0".repeat(32) + ".clxp"),
            event("oldphoto", oct3 - 30 * day, EventKind.CAMERA, old),
            event("plain", oct3)
        )
        val range = ReportRange(from = oct3 - day)
        val photos = Report.sharedPhotos(timeline, range, now) { SweepPhotos.open(files, it, TestStoreCipher) }
        assertEquals(mapOf("photo" to "clairix_${now}_photo_1.jpg"), photos.names)

        val text = Report.toText(header(), input(timeline).copy(photos = photos.names), range, signer)
        assertTrue("File: clairix_${now}_photo_1.jpg, shared with this report" in text)
        assertTrue("Kept, but it could not be opened on this phone" in text)
        assertTrue("Photos kept (2):" in text)

        val written = Report.writeExport(cache, now, ReportFormat.TEXT, text, photos.files)
        assertEquals(listOf("clairix_$now.txt", "clairix_${now}_photo_1.jpg"), written.map { it.name })
        assertTrue(written.all { it.parentFile == File(cache, "reports") })
        assertEquals(text, written[0].readText())
        assertTrue(jpeg.contentEquals(written[1].readBytes()))
        assertEquals("*/*", Report.shareMime(ReportFormat.TEXT, written.size))
        assertEquals("application/json", Report.shareMime(ReportFormat.JSON, 1))
        assertEquals("clairix_$now.json", Report.reportName(now, ReportFormat.JSON))

        // A day later the photo goes with the report.
        written.forEach { it.setLastModified(now) }
        assertEquals(2, Report.pruneExports(cache, now + Report.EXPORT_RETENTION_MS + 1))
        files.deleteRecursively()
        cache.deleteRecursively()
    }

    /**
     * An exported report is the one file the app writes in the clear: kept a day
     * for the app it was shared to, then deleted by the retention schedule
     * without waiting for the next export.
     */
    @Test
    fun exportedReportsGoAfterADayAndStrayOnesAtOnce() {
        val cache = Files.createTempDirectory("cache").toFile().apply { deleteOnExit() }
        val later = now + 10 * day
        val dir = File(cache, "reports").apply { mkdirs() }
        val fresh = File(dir, "clairix_${later - 60_000L}.txt").apply { writeText("r"); setLastModified(later - 60_000L) }
        val old = File(dir, "clairix_${later - 2 * Report.EXPORT_RETENTION_MS}.json").apply { writeText("r"); setLastModified(later - 2 * Report.EXPORT_RETENTION_MS) }
        val stray = File(cache, "clairix_1.txt").apply { writeText("r"); setLastModified(later - 60_000L) }
        val other = File(dir, "notes.txt").apply { writeText("n"); setLastModified(later - 2 * Report.EXPORT_RETENTION_MS) }

        assertEquals(2, Report.pruneExports(cache, later))
        assertTrue(fresh.exists())
        assertFalse(old.exists())
        assertFalse(stray.exists())
        // Only Clairix's own reports are its business.
        assertTrue(other.exists())
        cache.deleteRecursively()
    }

    // ── What the screens say ─────────────────────────────────────────────

    @Test
    fun theWarningNamesThePeriodTheReportCovers() {
        val tail = " and device identifiers. Share it only with people you trust."
        assertEquals("This report contains your own location history for the last 30 days$tail", Report.preShareWarning(ReportRange(), 30, now))
        assertEquals("This report contains your own location history for as far back as Clairix has kept it$tail", Report.preShareWarning(ReportRange(), 0, now))
        assertEquals("This report contains your own location history for the last 7 days$tail", Report.preShareWarning(ReportRange(from = now - 7 * day), 30, now))
        assertEquals(
            "This report contains your own location history for the 3 days you picked$tail",
            Report.preShareWarning(ReportRange(from = oct3, to = oct3 + 3 * day - 1), 30, now)
        )
    }

    @Test
    fun theTimelineFooterSaysWhatIsDeletedUnlessNothingIs() {
        assertEquals(
            "Entries older than 30 days are deleted automatically. Export the evidence report to keep them: Settings, Export evidence.",
            Report.timelineFooter(30)
        )
        assertNull(Report.timelineFooter(0))
    }
}
