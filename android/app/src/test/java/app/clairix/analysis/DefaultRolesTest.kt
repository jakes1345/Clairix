package app.clairix.analysis

import android.provider.Settings
import app.clairix.Severity
import app.clairix.analysis.DefaultRoles.Holder
import app.clairix.analysis.DefaultRoles.Role
import app.clairix.analysis.StalkwareScanner.AppFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default SMS app, phone app and home screen: which holders Phone check flags, what its row
 * says, and what holding one adds to an app's stalkerware score.
 */
class DefaultRolesTest {

    private val messages = Holder(Role.SMS, "com.google.android.apps.messaging", "Messages", system = true, fromStore = false)
    private val dialer = Holder(Role.DIALER, "com.google.android.dialer", "Phone", system = true, fromStore = false)
    private val launcher = Holder(Role.HOME, "com.teslacoilsw.launcher", "Nova Launcher", system = false, fromStore = true)

    @Test
    fun preloadedAndStoreHoldersAreInOrder() {
        val fact = DefaultRoles.fact(listOf(launcher, dialer, messages))
        assertEquals(DefaultRoles.ID, fact.id)
        assertEquals("Apps", fact.category)
        assertNull(fact.severity)
        // In role order, whatever order they were read in.
        assertEquals("SMS: Messages · Phone: Phone · Home: Nova Launcher", fact.value)
        assertNull(fact.fixAction)
    }

    @Test
    fun aDefaultHeldByAnAppFromOutsideAStoreIsMediumAndSaysWhatItCanDo() {
        val spy = Holder(Role.SMS, "com.example.sync", "Sync Service", system = false, fromStore = false)
        val fact = DefaultRoles.fact(listOf(spy, dialer, launcher))
        assertEquals(Severity.MEDIUM, fact.severity)
        assertEquals("Sync Service is the default SMS app", fact.value)
        val detail = fact.detail!!
        assertTrue(detail, detail.startsWith("Sync Service (com.example.sync) is the default SMS app, which receives every text message first"))
        assertTrue(detail, detail.contains("Settings → Apps → Default apps"))
        assertTrue(detail, detail.contains("can be noticed"))
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, fact.fixAction)
    }

    @Test
    fun severalAreCountedAndEachIsNamed() {
        val sms = Holder(Role.SMS, "com.example.a", "A", system = false, fromStore = false)
        val home = Holder(Role.HOME, "com.example.b", "B", system = false, fromStore = false)
        val fact = DefaultRoles.fact(listOf(home, sms, dialer))
        assertEquals("2 defaults held by apps from outside a store", fact.value)
        assertTrue(fact.detail!!.indexOf("A (com.example.a)") < fact.detail!!.indexOf("B (com.example.b)"))
        assertEquals(listOf(sms, home), DefaultRoles.judge(listOf(home, sms, dialer)))
    }

    @Test
    fun aHolderWhoseOriginIsUnknownIsNamedNotFlagged() {
        val unknown = Holder(Role.HOME, "com.example.home", "Home X", system = false, fromStore = false, checked = false)
        assertFalse(unknown.outsideStore)
        val fact = DefaultRoles.fact(listOf(messages, unknown))
        assertNull(fact.severity)
        assertTrue(fact.detail!!, fact.detail!!.contains("Android did not say where Home X came from"))
    }

    @Test
    fun noHolderAtAllIsNotReadableRatherThanFine() {
        val fact = DefaultRoles.fact(emptyList())
        assertEquals("Not readable", fact.value)
        assertNull(fact.severity)
    }

    // ── The stalkerware score ───────────────────────────────────────────────

    private fun score(f: AppFacts): Int = StalkwareScanner.assess(f).sumOf { it.points }

    @Test
    fun aDefaultCountsOnlyForAnAppFromOutsideAStore() {
        val store = AppFacts("com.example.sms", system = false, installer = "com.android.vending", hasLauncher = true, defaultRoles = setOf(Role.SMS))
        assertEquals(0, score(store))
        val sideloaded = store.copy(installer = "com.android.packageinstaller")
        assertEquals(StalkwareScanner.SIDELOAD_SCORE + Role.SMS.score, score(sideloaded))
        assertTrue(StalkwareScanner.assess(sideloaded).map { it.reason }.contains(DefaultRoles.reason(Role.SMS)))
        // A launcher from outside a store is listed (MEDIUM) but does not reach HIGH on that alone.
        val home = store.copy(installer = null, defaultRoles = setOf(Role.HOME))
        assertEquals(StalkwareScanner.SIDELOAD_SCORE + Role.HOME.score, score(home))
    }

    @Test
    fun aPreloadedDefaultIsNeverScored() {
        val oem = AppFacts("com.sec.android.app.launcher", system = true, installer = null, hasLauncher = false, defaultRoles = Role.entries.toSet())
        assertEquals(0, score(oem))
    }
}
