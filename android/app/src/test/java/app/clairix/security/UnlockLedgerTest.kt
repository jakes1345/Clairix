package app.clairix.security

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.UnlockKind
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The unlock ledger, on a real Context (files, preferences and the legacy SQLite
 * database) with a test cipher in place of the Keystore: a moment is written once
 * whichever source sees it, the retention prunes every row past it wherever it
 * sits in the document, the plain database of older versions is read in once and
 * deleted, and only unlocks inside the owner's sleep window are anomalies.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockLedgerTest {

    private lateinit var app: Application
    private val now = System.currentTimeMillis()
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun storeFile() = File(app.filesDir, UnlockLedger.FILE)

    /** The store the ledger opened last. */
    private var store: Store? = null

    /**
     * Opens the ledger as a fresh process would, after writing what the last one
     * held: a row lands on disk within seconds of the one before it, not with it.
     */
    private fun open() {
        store?.flush()
        UnlockLedger.closeForTest()
        UnlockLedger.openStore = { _ -> Store(storeFile(), Dispatchers.Unconfined, TestStoreCipher).also { store = it } }
        UnlockLedger.init(app)
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        HistoryRetention.setDays(app, HistoryRetention.FOREVER)
        open()
    }

    @After
    fun tearDown() {
        UnlockLedger.closeForTest()
        UnlockLedger.openStore = { Store(it, UnlockLedger.FILE) }
    }

    private fun kinds() = UnlockLedger.events(0L).map { it.ts to it.kind }

    // ── One row per moment ──────────────────────────────────────────────────

    @Test
    fun aMomentIsWrittenOnceWhicheverSourceSeesIt() {
        val ts = now - 60_000L
        assertNotNull(UnlockLedger.insert(ts, UnlockKind.KEYGUARD_HIDDEN, "usage_stats"))
        assertNull("the backfill wrote a moment twice", UnlockLedger.insert(ts, UnlockKind.KEYGUARD_HIDDEN, "usage_stats"))
        assertNull(UnlockLedger.insert(ts, UnlockKind.KEYGUARD_HIDDEN, null))
        // The same moment as another kind is a different row: the admin and the usage log describe different things.
        assertNotNull(UnlockLedger.insert(ts, UnlockKind.PASSWORD_SUCCEEDED, "device_admin"))
        assertEquals(2, UnlockLedger.events(0L).size)
    }

    @Test
    fun rowsSurviveAReopenAndAReopenedLedgerStillRefusesDuplicates() {
        UnlockLedger.insert(now - 3_000L, UnlockKind.PASSWORD_FAILED, "device_admin")
        UnlockLedger.insert(now - 2_000L, UnlockKind.PASSWORD_SUCCEEDED, "device_admin")
        assertTrue(storeFile().exists())
        assertFalse("the ledger is on disk in the clear", storeFile().readText(Charsets.ISO_8859_1).contains("PASSWORD"))

        open()
        assertEquals(
            listOf(now - 2_000L to UnlockKind.PASSWORD_SUCCEEDED, now - 3_000L to UnlockKind.PASSWORD_FAILED),
            kinds()
        )
        assertNull(UnlockLedger.insert(now - 2_000L, UnlockKind.PASSWORD_SUCCEEDED, "device_admin"))
        val next = UnlockLedger.insert(now - 1_000L, UnlockKind.KEYGUARD_SHOWN, null)
        assertEquals("ids carry on from where they were", 3L, next?.id)
    }

    @Test
    fun eventsAreNewestFirstAndFromTheGivenMoment() {
        UnlockLedger.insert(now - 3 * day, UnlockKind.KEYGUARD_HIDDEN, null)
        UnlockLedger.insert(now - 1 * day, UnlockKind.KEYGUARD_HIDDEN, null)
        UnlockLedger.insert(now - 2 * day, UnlockKind.KEYGUARD_HIDDEN, null) // a backfill, after a newer row
        assertEquals(listOf(now - day, now - 2 * day, now - 3 * day), UnlockLedger.events(0L).map { it.ts })
        assertEquals(listOf(now - day, now - 2 * day), UnlockLedger.events(now - 2 * day).map { it.ts })
    }

    // ── The retention ──────────────────────────────────────────────────────

    @Test
    fun pruningDropsEveryRowPastTheCutoffEvenOneABackfillAddedLate() {
        UnlockLedger.insert(now - 2 * day, UnlockKind.PASSWORD_SUCCEEDED, null)
        UnlockLedger.insert(now - 40 * day, UnlockKind.KEYGUARD_HIDDEN, "usage_stats") // older, written after
        UnlockLedger.insert(now - day, UnlockKind.PASSWORD_FAILED, null)
        UnlockLedger.insert(now - 31 * day, UnlockKind.SCREEN_INTERACTIVE, "usage_stats")

        assertEquals(2, UnlockLedger.pruneOlderThan(now - 30 * day))
        assertEquals(listOf(now - day, now - 2 * day), UnlockLedger.events(0L).map { it.ts })
        assertEquals("nothing left to prune", 0, UnlockLedger.pruneOlderThan(now - 30 * day))

        // On disk too, and a pruned moment can be written again (the dedupe forgot it with the row).
        open()
        assertEquals(2, UnlockLedger.events(0L).size)
        assertNotNull(UnlockLedger.insert(now - 31 * day, UnlockKind.SCREEN_INTERACTIVE, "usage_stats"))
    }

    @Test
    fun aNewRowDropsWhatHasAgedPastTheOwnersRetention() {
        UnlockLedger.insert(now - 20 * day, UnlockKind.PASSWORD_SUCCEEDED, null)
        UnlockLedger.insert(now - 3 * day, UnlockKind.PASSWORD_SUCCEEDED, null)
        HistoryRetention.setDays(app, 7)
        // The admin receiver writes whether or not the app runs: the ledger cannot outgrow the retention.
        UnlockLedger.insert(now, UnlockKind.PASSWORD_SUCCEEDED, null)
        assertEquals(listOf(now, now - 3 * day), UnlockLedger.events(0L).map { it.ts })
    }

    @Test
    fun theCapKeepsTheNewestRows() {
        val extra = UnlockLedger.CAP / 10 + 1
        val base = now - (UnlockLedger.CAP + extra) * 1_000L
        for (i in 0 until UnlockLedger.CAP + extra) UnlockLedger.insert(base + i * 1_000L, UnlockKind.SCREEN_INTERACTIVE, null)
        val rows = UnlockLedger.events(0L)
        assertEquals(UnlockLedger.CAP, rows.size)
        assertEquals(base + (UnlockLedger.CAP + extra - 1) * 1_000L, rows.first().ts)
        assertEquals("the oldest went first", base + extra * 1_000L, rows.last().ts)
    }

    // ── The plain database of older versions ────────────────────────────────

    @Test
    fun theLegacyDatabaseIsReadInOnceAndDeleted() {
        UnlockLedger.closeForTest()
        val path = app.getDatabasePath(UnlockLedger.LEGACY_DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE unlock_events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, event TEXT NOT NULL, extra TEXT)")
            fun row(ts: Long, event: String, extra: String?) = db.insert(
                "unlock_events", null,
                ContentValues().apply { put("ts", ts); put("event", event); put("extra", extra) }
            )
            row(now - 5_000L, "PASSWORD_FAILED", "device_admin")
            row(now - 4_000L, "KEYGUARD_HIDDEN", null)
            row(now - 4_000L, "KEYGUARD_HIDDEN", "usage_stats") // the same moment twice: one row
            row(now - 3_000L, "SOMETHING_RETIRED", null)        // a kind this build does not know: skipped
        }
        open()

        assertEquals(
            listOf(now - 4_000L to UnlockKind.KEYGUARD_HIDDEN, now - 5_000L to UnlockKind.PASSWORD_FAILED),
            kinds()
        )
        assertFalse("the plain database outlived the migration", path.exists())
        // Written sealed straight away: a reopen finds the rows without the database.
        open()
        assertEquals(2, UnlockLedger.events(0L).size)
    }

    // ── The sleep window ───────────────────────────────────────────────────

    /** [hour]:[minute] local time, [daysAgo] days ago. */
    private fun at(daysAgo: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun onlyUnlocksInsideAWindowAcrossMidnightAreAnomalies() {
        UnlockLedger.setSleepWindow(app, 23 * 60, 7 * 60)
        val lateUnlock = at(2, 23, 30)
        val nightUnlock = at(1, 3, 10)
        val nightAdmin = at(1, 4, 0)
        UnlockLedger.insert(lateUnlock, UnlockKind.KEYGUARD_HIDDEN, null)
        UnlockLedger.insert(nightUnlock, UnlockKind.PASSWORD_SUCCEEDED, null)
        UnlockLedger.insert(nightAdmin, UnlockKind.PASSWORD_SUCCEEDED, null)
        UnlockLedger.insert(at(1, 3, 12), UnlockKind.KEYGUARD_SHOWN, null)     // the phone locking is not an unlock
        UnlockLedger.insert(at(1, 3, 11), UnlockKind.PASSWORD_FAILED, null)    // nor is a wrong PIN
        UnlockLedger.insert(at(1, 7, 0), UnlockKind.KEYGUARD_HIDDEN, null)     // the window ends at 07:00
        UnlockLedger.insert(at(1, 22, 59), UnlockKind.KEYGUARD_HIDDEN, null)   // and starts at 23:00

        assertEquals(listOf(nightAdmin, nightUnlock, lateUnlock), UnlockLedger.sleepAnomalies(0L).map { it.ts })
        assertTrue(UnlockLedger.inSleepWindow(at(0, 0, 0)))
        assertFalse(UnlockLedger.inSleepWindow(at(0, 12, 0)))
    }

    @Test
    fun aWindowInsideOneDayAndAnEmptyWindow() {
        UnlockLedger.setSleepWindow(app, 9 * 60, 17 * 60) // a night worker
        assertTrue(UnlockLedger.inSleepWindow(at(0, 9, 0)))
        assertTrue(UnlockLedger.inSleepWindow(at(0, 16, 59)))
        assertFalse(UnlockLedger.inSleepWindow(at(0, 17, 0)))
        assertFalse(UnlockLedger.inSleepWindow(at(0, 3, 0)))

        UnlockLedger.setSleepWindow(app, 6 * 60, 6 * 60)
        assertFalse("a window that starts where it ends holds nothing", UnlockLedger.inSleepWindow(at(0, 6, 0)))
    }

    @Test
    fun beforeTheOwnerHasSaidWhenTheySleepNothingIsAnAnomaly() {
        assertFalse(UnlockLedger.sleepWindowAnswered(app))
        UnlockLedger.insert(at(1, 3, 0), UnlockKind.PASSWORD_SUCCEEDED, null)
        assertFalse(UnlockLedger.inSleepWindow(at(1, 3, 0)))
        assertTrue(UnlockLedger.sleepAnomalies(0L).isEmpty())
        assertTrue(UnlockLedger.checkSleepWindow(app).isEmpty())
    }
}
