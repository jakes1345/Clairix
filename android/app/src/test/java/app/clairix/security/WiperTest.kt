package app.clairix.security

import android.app.Notification
import app.clairix.Store
import app.clairix.TestStoreCipher
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The wipe's reach and order, run over a temporary copy of an app's directories
 * through the same seam the phone runs it through ([Wiper.run], [WipeRoots],
 * [WipeSystem]): every preferences file, database and file goes, the "wipe
 * pending" mark goes last of all, a duress wipe leaves its decoy and its relay
 * deletion behind and nothing else, and no store writes while a wipe runs.
 */
class WiperTest {

    private val base: File = Files.createTempDirectory("wipe").toFile().apply { deleteOnExit() }
    private val dataDir = File(base, "data/app.clairix")
    private val files = File(dataDir, "files")
    private val cache = File(dataDir, "cache")
    private val codeCache = File(dataDir, "code_cache")
    private val noBackup = File(dataDir, "no_backup")
    private val external = File(base, "sdcard/Android/data/app.clairix/files")
    private val externalCache = File(base, "sdcard/Android/data/app.clairix/cache")
    private val events = ArrayList<String>()

    /** The mark, watched: its deletion is an event like any of the steps. */
    private inner class WatchedMarker(path: String) : File(path) {
        override fun delete(): Boolean {
            events += "marker deleted"
            return super.delete()
        }
    }

    private val roots = WipeRoots(
        dataDir, files, cache, codeCache, noBackup, listOf(external, externalCache),
        marker = WatchedMarker(File(files, Wiper.PENDING_MARKER).path),
    )

    private val now = 1_700_000_000_000L

    @After
    fun tearDown() {
        Wiper.endForTest()
        base.deleteRecursively()
    }

    private fun write(f: File, text: String = "x") {
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    /** What an app that has been used for a while keeps, in every place a wipe must reach. */
    private fun populate() {
        write(File(dataDir, "shared_prefs/prefs.xml"))
        write(File(dataDir, "shared_prefs/comms.xml"))
        write(File(dataDir, "shared_prefs/app_lock.xml"))
        write(File(dataDir, "shared_prefs/scan_record.xml"))
        write(File(dataDir, "databases/comms_e2ee.db"))
        write(File(dataDir, "databases/comms_e2ee.db-journal"))
        write(File(files, "timeline.json"))
        write(File(files, "comms_identity.pickle"))
        write(File(files, "tor/state/state"))
        write(File(files, "nav/typical-us-ohio.json"))
        write(File(cache, "reports/clairix_1.txt"))
        write(File(cache, "vm_1.amr"))
        write(File(codeCache, "startup.prof"))
        write(File(noBackup, "tiles/12/1100/1500.png"))
        write(File(external, "scanner/1700000000000_162550000.raw"))
        write(File(externalCache, "glint.jpg"))
        write(File(dataDir, "app_webview/Default/Cookies"))
        write(File(dataDir, "stray.tmp"))
        // The link to the APK's native libraries (a symlink on a phone): left alone.
        write(File(dataDir, "lib/libvodozemac.so"))
    }

    /** Every file left anywhere a wipe reaches, relative to the temp root, leaving out the native libraries. */
    private fun leftovers(): List<String> =
        base.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(base).invariantSeparatorsPath }
            .filterNot { it.startsWith("data/app.clairix/lib/") }
            .sorted()
            .toList()

    /** Records each step, and that the mark was still there when it ran. */
    private inner class RecordingSystem : WipeSystem {
        val cleared = ArrayList<String>()
        val deletedDatabases = ArrayList<String>()
        var keysKept: Set<String>? = null
        var decoy: Pair<Decoy, DecoyLock?>? = null
        var lockInMarkAtServices: DecoyLock? = null

        private fun step(name: String) {
            assertTrue("the mark was gone before \"$name\"", roots.marker.exists())
            if (events.lastOrNull() != name) events += name
        }

        override fun destroyKeys(keep: Set<String>) {
            step("keys")
            keysKept = keep
        }

        override fun stopServices() {
            step("services")
            lockInMarkAtServices = Wiper.readMarker(roots.marker)?.decoyLock
        }

        override fun clearOutside() = step("outside")

        override fun clearLoadedPrefs(name: String) {
            step("prefs")
            cleared += name
        }

        override fun deleteDatabase(name: String) {
            step("databases")
            deletedDatabases += name
            File(roots.databasesDir, name).delete()
        }

        override fun makeDecoyLock(code: String): DecoyLock {
            step("decoy lock")
            return DecoyLock("c2FsdA==", "dmVyaWZpZXI=")
        }

        override fun writeDecoy(decoy: Decoy, lock: DecoyLock?) {
            step("decoy")
            this.decoy = decoy to lock
            // As on the phone: the preferences come back with the decoy's own files and nothing else.
            write(File(roots.prefsDir, "prefs.xml"))
            if (lock != null) write(File(roots.prefsDir, "app_lock.xml"))
        }

        override fun keepFarewells(list: List<RelayFarewell.Prepared>) {
            step("farewells")
            RelayFarewell.keep(roots.filesDir, list, now)
        }
    }

    private fun farewell(url: String, viaTor: Boolean) = RelayFarewell.Prepared(
        url = url, pin = null, viaTor = viaTor, offsetMs = 0L,
        signed = (0 until 4).map { k ->
            val ts = now + k * RelayFarewell.STEP_MS
            RelayFarewell.Signed(ts, listOf("X-Clairix-Number" to "482913605", "X-Clairix-Ts" to ts.toString()))
        }
    )

    private val decoySettings = Decoy(
        prefs = mapOf("theme" to "ACID", "scan_enabled" to true, "follow_threshold_m" to 300f),
        retentionDays = 7, wipeAfter = 3, relockMs = 30_000L, biometric = false,
    )

    @Test
    fun aPlainWipeLeavesNothingAndTheMarkGoesLast() {
        populate()
        Wiper.writeMarker(roots.marker, WipePlan())
        val system = RecordingSystem()

        Wiper.run(WipePlan(), duressCode = null, roots = roots, system = system)

        assertEquals(listOf("keys", "services", "outside", "prefs", "databases", "marker deleted"), events)
        assertEquals(emptySet<String>(), system.keysKept)
        assertEquals(emptyList<String>(), leftovers())
        assertFalse("the preferences directory survived", roots.prefsDir.exists())
        assertFalse("the databases directory survived", roots.databasesDir.exists())
        assertTrue("the native libraries were touched", File(dataDir, "lib/libvodozemac.so").exists())
        // Found on disk and named in the code: an instance loaded in memory is emptied either way.
        assertTrue(system.cleared.containsAll(listOf("prefs", "comms", "app_lock", "scan_record") + Wiper.KNOWN_PREFS))
        assertTrue(system.deletedDatabases.containsAll(listOf("comms_e2ee.db", "comms_e2ee.db-journal") + Wiper.KNOWN_DATABASES))
    }

    @Test
    fun aDuressWipeLeavesItsDecoyAndOnlyTheRelayDeletionThatNeedsNoTor() {
        populate()
        val plan = WipePlan(
            decoy = decoySettings,
            farewells = listOf(farewell("https://relay.example/v1/me", viaTor = false), farewell("https://tor.example/v1/me", viaTor = true)),
        )
        Wiper.writeMarker(roots.marker, plan)
        val system = RecordingSystem()

        val ended = Wiper.run(plan, duressCode = "61849302", roots = roots, system = system)

        assertEquals(
            listOf("keys", "decoy lock", "services", "outside", "prefs", "databases", "decoy", "farewells", "marker deleted"),
            events
        )
        // Nothing survives step 2 but a decoy key made after it.
        assertEquals(emptySet<String>(), system.keysKept)
        // The lock was written into the mark the moment it was made: a wipe cut short from there still leaves it.
        assertEquals(DecoyLock("c2FsdA==", "dmVyaWZpZXI="), system.lockInMarkAtServices)
        assertEquals(DecoyLock("c2FsdA==", "dmVyaWZpZXI="), ended.decoyLock)
        assertEquals(decoySettings, system.decoy?.first)
        assertEquals(ended.decoyLock, system.decoy?.second)
        assertEquals(
            listOf("data/app.clairix/files/${RelayFarewell.FILE}", "data/app.clairix/shared_prefs/app_lock.xml", "data/app.clairix/shared_prefs/prefs.xml"),
            leftovers()
        )
        assertEquals(listOf("https://relay.example/v1/me"), RelayFarewell.load(files).map { it.url })
    }

    @Test
    fun aResumedDuressWipeKeepsTheDecoyKeyItHadMade() {
        populate()
        val plan = WipePlan(decoy = decoySettings, decoyLock = DecoyLock("c2FsdA==", "dmVyaWZpZXI="))
        Wiper.writeMarker(roots.marker, plan)
        val system = RecordingSystem()

        Wiper.run(plan, duressCode = null, roots = roots, system = system)

        assertEquals(setOf(AppLock.KEY_ALIAS), system.keysKept)
        assertFalse("a second decoy lock was made", "decoy lock" in events)
        assertEquals(plan.decoyLock, system.decoy?.second)
        assertEquals("marker deleted", events.last())
    }

    @Test
    fun aDuressWipeCutShortBeforeItsLockWasMadeLeavesTheDecoyUnlockedNotUnopenable() {
        populate()
        val plan = WipePlan(decoy = decoySettings)
        Wiper.writeMarker(roots.marker, plan)
        val system = RecordingSystem()

        // Resumed by a new process: the code was never written down.
        Wiper.run(plan, duressCode = null, roots = roots, system = system)

        assertFalse("decoy lock" in events)
        assertNotNull(system.decoy)
        assertNull(system.decoy?.second)
        assertEquals(listOf("data/app.clairix/shared_prefs/prefs.xml"), leftovers())
    }

    @Test
    fun anEarlierWipesRelayDeletionStillWaitingSurvivesTheNextWipe() {
        populate()
        RelayFarewell.save(files, listOf(farewell("https://old.example/v1/me", viaTor = false)))
        Wiper.writeMarker(roots.marker, WipePlan())

        Wiper.run(WipePlan(), duressCode = null, roots = roots, system = RecordingSystem())

        assertEquals(listOf("data/app.clairix/files/${RelayFarewell.FILE}"), leftovers())
        assertEquals(listOf("https://old.example/v1/me"), RelayFarewell.load(files).map { it.url })
    }

    @Test
    fun storesRefuseEveryWriteWhileAWipeRuns() {
        val file = File(base, "timeline.json")
        val store = Store(file, Dispatchers.Unconfined, TestStoreCipher)
        val owner = Any()

        assertTrue(Wiper.begin())
        assertFalse("a second wipe started alongside the first", Wiper.begin())
        assertTrue(Wiper.isActive)
        synchronized(owner) {
            store.load { JSONObject() }.put("v", 1)
            store.markDirty()
            store.flush()
            store.touch(minIntervalMs = 0)
        }
        store.replace(JSONObject().put("v", 2))
        assertFalse("a store wrote during the wipe", file.exists())

        // The same store writes as soon as no wipe is running: it was the wipe that stopped it.
        Wiper.endForTest()
        store.replace(JSONObject().put("v", 3))
        assertTrue(file.exists())
    }

    @Test
    fun thePlanSurvivesTheMarkAndAnOldOrDamagedMarkIsAPlainWipe() {
        val plan = WipePlan(
            decoy = Decoy(
                prefs = mapOf("theme" to "ACID", "scan_enabled" to true, "follow_threshold_m" to 300f, "x_int" to 7, "x_long" to 7L),
                retentionDays = 90, wipeAfter = 5, relockMs = 60_000L, biometric = true,
            ),
            decoyLock = DecoyLock("c2FsdA==", "dmVyaWZpZXI="),
            farewells = listOf(farewell("https://relay.example/v1/me", viaTor = false)),
        )
        Wiper.writeMarker(roots.marker, plan)
        assertEquals(plan, Wiper.readMarker(roots.marker))

        // No mark: no wipe pending.
        roots.marker.delete()
        assertNull(Wiper.readMarker(roots.marker))
        // An empty mark (what versions before plans wrote) and a damaged one both finish as a plain wipe.
        write(roots.marker, "")
        assertEquals(WipePlan(), Wiper.readMarker(roots.marker))
        write(roots.marker, "{not json")
        assertEquals(WipePlan(), Wiper.readMarker(roots.marker))
    }

    @Test
    fun theLastSendsGoThroughTorOnlyWhileThisProcessHasItAndTheDecoyLeavesTheFileToItsOwnStart() {
        val direct = farewell("https://relay.example/v1/me", viaTor = false)
        val tor = farewell("https://tor.example/v1/me", viaTor = true)

        // A plain wipe with Tor on: the Tor copy now, then the file.
        val plain = Wiper.finalSends(WipePlan(farewells = listOf(direct, tor)), torOn = true)
        assertEquals(listOf(tor), plain.viaTor)
        assertTrue(plain.waiting)

        // The same wipe resumed in a new process, where Tor is not running: never sent directly.
        val resumed = Wiper.finalSends(WipePlan(farewells = listOf(direct, tor)), torOn = false)
        assertEquals(emptyList<RelayFarewell.Prepared>(), resumed.viaTor)
        assertTrue("the direct copies are still tried before Clairix closes", resumed.waiting)

        // The decoy: the Tor copy before the relaunch, the file from the decoy's own process.
        val duress = Wiper.finalSends(WipePlan(decoy = decoySettings, farewells = listOf(direct, tor)), torOn = true)
        assertEquals(listOf(tor), duress.viaTor)
        assertFalse(duress.waiting)
    }

    @Test
    fun everyForegroundServiceInEveryEditionIsStoppedByTheWipe() {
        val declared = listOf("src/main/AndroidManifest.xml", "src/suite/AndroidManifest.xml").flatMap { path ->
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(File(path))
            val nodes = doc.getElementsByTagName("service")
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttributeNS(ANDROID_NS, "foregroundServiceType").isNotEmpty() }
                .map { "app.clairix" + it.getAttributeNS(ANDROID_NS, "name") }
        }
        // ScanService, CommsService, CallService, the fake call's ring, the mesh, drive and navigation.
        assertTrue("foreground services missing from the manifests: $declared", declared.size >= 7)
        assertEquals(declared.toSet(), Wiper.FOREGROUND_SERVICES.map { it.name }.toSet())
    }

    @Test
    fun aForegroundServicesNotificationIsToldFromAnOrdinaryOne() {
        assertFalse(Wiper.anyForeground(emptyList()))
        assertFalse(Wiper.anyForeground(listOf(Notification.FLAG_ONGOING_EVENT)))
        assertTrue(Wiper.anyForeground(listOf(0, Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT)))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
