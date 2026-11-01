package app.clairix.analysis

import android.provider.Settings
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.analysis.AccountChecklist.Action
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files

/** The "who else can see this phone" checklist: its rows on each kind of phone, what they open, and the ticks it keeps. */
class AccountChecklistTest {

    private val everywhere = listOf(
        AccountChecklist.ROW_GOOGLE_ACCOUNTS, AccountChecklist.ROW_GOOGLE_DEVICES, AccountChecklist.ROW_RECOVERY,
        AccountChecklist.ROW_SIGN_IN, AccountChecklist.ROW_FIND_MY_DEVICE, AccountChecklist.ROW_LOCATION_SHARING,
        AccountChecklist.ROW_FAMILY_LINK, AccountChecklist.ROW_PLAY_PROTECT, AccountChecklist.ROW_QUICK_SHARE,
        AccountChecklist.ROW_FORWARDING,
    )
    private val samsungOnly = listOf(AccountChecklist.ROW_SAMSUNG_FIND, AccountChecklist.ROW_SAMSUNG_ACCOUNT)

    @Test
    fun aPixelGetsEveryGoogleRowAndNoSamsungOnes() {
        val ids = AccountChecklist.rows("Google").map { it.id }
        assertEquals(everywhere, ids)
        assertTrue(samsungOnly.none { it in ids })
        assertEquals(ids, AccountChecklist.rows(null).map { it.id })
    }

    @Test
    fun aSamsungAlsoGetsSamsungFindAndItsAccountDevices() {
        for (maker in listOf("samsung", "Samsung", " SAMSUNG ")) {
            val ids = AccountChecklist.rows(maker).map { it.id }
            assertTrue(maker, ids.containsAll(everywhere) && ids.containsAll(samsungOnly))
            assertEquals(everywhere.size + samsungOnly.size, ids.size)
            assertEquals("ids are unique", ids.size, ids.toSet().size)
        }
        assertFalse(AccountChecklist.isSamsung("Xiaomi"))
        assertFalse(AccountChecklist.isSamsung(null))
    }

    @Test
    fun everyRowSaysWhatToLookForAndHasSomethingToOpen() {
        for (row in AccountChecklist.rows("samsung")) {
            assertTrue(row.id, row.title.isNotBlank())
            assertTrue(row.id, row.lookFor.length > 40)
            assertTrue(row.id, row.actions.isNotEmpty())
            row.actions.forEach { assertTrue("${row.id}: ${it.label}", it.label.isNotBlank()) }
        }
    }

    @Test
    fun everyPageIsAnOfficialHttpsPage() {
        val official = setOf(
            "myaccount.google.com", "support.google.com", "www.google.com", "smartthingsfind.samsung.com", "account.samsung.com"
        )
        val urls = AccountChecklist.rows("samsung").flatMap { it.actions }.mapNotNull {
            when (it) {
                is Action.Web -> it.url
                is Action.App -> it.fallbackUrl
                else -> null
            }
        }
        assertTrue(urls.size >= 10)
        for (url in urls) {
            val uri = URI(url)
            assertEquals(url, "https", uri.scheme)
            assertTrue(url, uri.host in official)
        }
    }

    @Test
    fun settingsAndAppsAreOnlyWhatTheSdkAndThePhoneProvide() {
        val actions = AccountChecklist.rows("samsung").flatMap { it.actions }
        // Settings pages by the SDK's own constants, nothing made up.
        val settings = actions.filterIsInstance<Action.SystemSettings>().map { it.action }.toSet()
        assertEquals(setOf(Settings.ACTION_SYNC_SETTINGS), settings)
        // Apps by their launch intent, with a web page or nothing behind them.
        val apps = actions.filterIsInstance<Action.App>()
        assertEquals(
            setOf(AccountChecklist.PKG_FIND_MY_DEVICE, AccountChecklist.PKG_MAPS, AccountChecklist.PKG_PLAY_STORE),
            apps.map { it.pkg }.toSet()
        )
        assertEquals(AccountChecklist.URL_FIND_DEVICE_WEB, apps.single { it.pkg == AccountChecklist.PKG_FIND_MY_DEVICE }.fallbackUrl)
        // Every Google account on the phone, through Android's own chooser.
        val choose = actions.filterIsInstance<Action.ChooseAccount>().single()
        assertEquals("com.google", choose.accountType)
    }

    @Test
    fun theForwardingRowDialsTheThreeCarrierCodes() {
        val row = AccountChecklist.rows("Google").single { it.id == AccountChecklist.ROW_FORWARDING }
        val dials = row.actions.filterIsInstance<Action.Dial>()
        assertEquals(listOf("*#21#", "*#62#", "*#67#"), dials.map { it.code })
        dials.forEach { assertTrue(it.code, it.shows.length > 20) }
        assertTrue(dials[1].shows.contains("voicemail") && dials[2].shows.contains("voicemail"))
    }

    @Test
    fun progressCountsOnlyTheRowsThisPhoneShows() {
        val pixel = AccountChecklist.rows("Google")
        val none = AccountChecklist.progress(pixel, emptyMap())
        assertFalse(none.started)
        assertFalse(none.done)
        assertNull(none.lastTickAt)
        assertEquals(pixel.size, none.total)

        // A tick from when the phone was a Samsung (a restored record) does not count on a Pixel.
        val some = AccountChecklist.progress(pixel, mapOf(AccountChecklist.ROW_RECOVERY to 1_000L, AccountChecklist.ROW_SAMSUNG_FIND to 9_000L))
        assertEquals(1, some.checked)
        assertEquals(1_000L, some.lastTickAt)
        assertTrue(some.started && !some.done)

        val all = AccountChecklist.progress(pixel, pixel.mapIndexed { i, r -> r.id to (100L + i) }.toMap())
        assertTrue(all.done)
        assertEquals(100L + pixel.size - 1, all.lastTickAt)
    }

    @Test
    fun ticksAreKeptSealedAcrossARestartAndStartAgainClearsThem() {
        val file = File(Files.createTempDirectory("accounts").toFile().apply { deleteOnExit() }, "account_checklist.json")
        fun store() = Store(file, Dispatchers.Unconfined, TestStoreCipher)

        val record = AccountCheckRecord(store())
        assertTrue(record.ticks().isEmpty())
        record.setChecked(AccountChecklist.ROW_GOOGLE_DEVICES, checked = true, now = 1_000L)
        record.setChecked(AccountChecklist.ROW_FORWARDING, checked = true, now = 2_000L)
        record.setChecked(AccountChecklist.ROW_RECOVERY, checked = true, now = 3_000L)
        record.setChecked(AccountChecklist.ROW_RECOVERY, checked = false, now = 4_000L)

        // Sealed on disk: the row ids are not there in the clear.
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.UTF_8).contains(AccountChecklist.ROW_GOOGLE_DEVICES))

        val reopened = AccountCheckRecord(store())
        assertEquals(
            mapOf(AccountChecklist.ROW_GOOGLE_DEVICES to 1_000L, AccountChecklist.ROW_FORWARDING to 2_000L),
            reopened.ticks()
        )

        reopened.clear()
        assertTrue(AccountCheckRecord(store()).ticks().isEmpty())
    }
}
