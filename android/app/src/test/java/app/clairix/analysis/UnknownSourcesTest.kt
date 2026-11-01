package app.clairix.analysis

import android.app.AppOpsManager
import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Install unknown apps": which of the apps that ask for the access are allowed it, judged as
 * Settings judges it, and what Phone check's row says about them.
 */
class UnknownSourcesTest {

    private fun app(pkg: String, label: String, mode: Int?, store: Boolean = false) = UnknownSources.Requester(pkg, label, store, mode)

    @Test
    fun onlyAnExplicitAllowCounts() {
        val reading = UnknownSources.judge(
            listOf(
                app("com.android.chrome", "Chrome", AppOpsManager.MODE_ALLOWED),
                // Never switched on: the package installer treats it as blocked.
                app("com.google.android.apps.nbu.files", "Files", AppOpsManager.MODE_DEFAULT),
                app("org.telegram.messenger", "Telegram", AppOpsManager.MODE_ERRORED),
                app("com.example.ignored", "Ignored", AppOpsManager.MODE_IGNORED),
                app("com.example.unchecked", "Unchecked", null),
            )
        )
        assertEquals(listOf("com.android.chrome"), reading.allowed.map { it.packageName })
        assertEquals(1, reading.unchecked)
        assertEquals(5, reading.requesters)
        assertTrue(UnknownSources.allows(AppOpsManager.MODE_FOREGROUND))
        assertFalse(UnknownSources.allows(AppOpsManager.MODE_DEFAULT))
    }

    @Test
    fun nothingAllowedIsInOrder() {
        for (reading in listOf(
            UnknownSources.judge(emptyList()),
            UnknownSources.judge(listOf(app("com.android.chrome", "Chrome", AppOpsManager.MODE_DEFAULT)))
        )) {
            val fact = UnknownSources.fact(reading)
            assertEquals("install_unknown", fact.id)
            assertEquals("Access", fact.category)
            assertEquals("Install unknown apps", fact.label)
            assertEquals("No app allowed", fact.value)
            assertNull(fact.severity)
        }
    }

    @Test
    fun appStoresAloneAreInOrder() {
        val fact = UnknownSources.fact(
            UnknownSources.judge(listOf(app("org.fdroid.fdroid", "F-Droid", AppOpsManager.MODE_ALLOWED, store = true)))
        )
        assertEquals("App stores only: F-Droid", fact.value)
        assertNull(fact.severity)
    }

    @Test
    fun anyOtherAppAllowedIsLowAndNamed() {
        val fact = UnknownSources.fact(
            UnknownSources.judge(
                listOf(
                    app("org.fdroid.fdroid", "F-Droid", AppOpsManager.MODE_ALLOWED, store = true),
                    app("com.android.chrome", "Chrome", AppOpsManager.MODE_ALLOWED),
                    app("com.example.files", "Files", AppOpsManager.MODE_DEFAULT),
                )
            )
        )
        assertEquals("1 app allowed: Chrome", fact.value)
        assertEquals(Severity.LOW, fact.severity)
        assertTrue(fact.detail!!, fact.detail!!.startsWith("Chrome can install other apps"))
        assertTrue(fact.detail!!, fact.detail!!.contains("Special app access → Install unknown apps"))
        assertTrue(fact.detail!!, fact.detail!!.contains("F-Droid is an app store"))
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, fact.fixAction)
    }

    @Test
    fun aLongListIsShortenedInTheValueAndNamedInFullInTheDetail() {
        val fact = UnknownSources.fact(
            UnknownSources.judge(
                listOf(
                    app("c.c", "Chrome", AppOpsManager.MODE_ALLOWED),
                    app("a.a", "Aria", AppOpsManager.MODE_ALLOWED),
                    app("b.b", "Briefcase", AppOpsManager.MODE_ALLOWED),
                )
            )
        )
        assertEquals("3 apps allowed: Aria, Briefcase, …", fact.value)
        assertTrue(fact.detail!!, fact.detail!!.startsWith("Aria, Briefcase, Chrome can install"))
    }

    @Test
    fun aReadingThatCouldNotBeTakenSaysSo() {
        assertEquals("Not readable", UnknownSources.fact(null).value)
        val allFailed = UnknownSources.judge(listOf(app("a.a", "A", null), app("b.b", "B", null)))
        val fact = UnknownSources.fact(allFailed)
        assertEquals("Not readable", fact.value)
        assertNull(fact.severity)
        // Some checked, some not: what was checked is said, and how many were not.
        val partly = UnknownSources.fact(UnknownSources.judge(listOf(app("a.a", "A", AppOpsManager.MODE_DEFAULT), app("b.b", "B", null))))
        assertEquals("No app allowed", partly.value)
        assertTrue(partly.detail!!, partly.detail!!.contains("1 more app could not be checked"))
    }
}
