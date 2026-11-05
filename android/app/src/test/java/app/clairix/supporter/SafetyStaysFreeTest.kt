package app.clairix.supporter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every safety feature stays free (docs/SUPPORTER.md): none of them may ask
 * whether this phone is a Supporter. This fails when code on a safety path
 * names the entitlement API (the supporter package, [Entitlement], or the
 * billing that leads to it): the scanner and its analysis, the security
 * package (alerts, discreet mode, SOS, duress, wiping, helplines), SOS in
 * Comms, detection,
 * evidence, Phone check, Clean link and bug-report import.
 */
class SafetyStaysFreeTest {

    private val src = File("src")

    /** Directories whose every source file is a safety feature. */
    private val safetyDirs = listOf("analysis", "security", "detect", "bugreport", "links")

    /** Single files in the app's root package that are safety features. */
    private val safetyFiles = listOf(
        "ScanService.kt", "ScanLoopPolicy.kt", "EvidenceExport.kt", "PhoneCheckTools.kt", "FlaggedAppSheet.kt",
        "HelpTools.kt", "SafetyTools.kt", "TimelineLog.kt", "CameraSweepScreen.kt", "TrackerTools.kt",
        "OwnerCheck.kt", "AccountChecklistPage.kt", "UltrasoundTools.kt", "BluetoothTools.kt", "GnssTools.kt",
        "SosExtrasRun.kt", "UnblockDialog.kt", "WipeLimitQuestion.kt", "FollowerStatus.kt",
    )

    private val forbidden = Regex("""\bapp\.clairix\.supporter\b|\bEntitlement\b|\bSupporterBilling\b|\bEditionBilling\b""")

    private fun safetySources(): List<File> {
        val sets = src.listFiles { f -> f.isDirectory && !f.name.startsWith("test") && !f.name.startsWith("androidTest") }.orEmpty()
        val out = ArrayList<File>()
        for (set in sets) {
            val root = File(set, "java/app/clairix")
            for (dir in safetyDirs) File(root, dir).walkTopDown().filterTo(out) { it.isFile && it.extension == "kt" }
            for (name in safetyFiles) File(root, name).takeIf { it.isFile }?.let { out += it }
            // SOS, which rides on Comms.
            File(root, "comms").listFiles { f -> f.isFile && f.name.startsWith("Sos") }?.let { out += it }
            // The suite tier is the scanner and its tools: all of it is safety.
            if (set.name == "suite" || set.name == "fullSuite") root.walkTopDown().filterTo(out) { it.isFile && it.extension == "kt" }
        }
        return out.distinct()
    }

    @Test
    fun noSafetyFeatureAsksForTheEntitlement() {
        val files = safetySources()
        assertTrue("the safety paths were found (${files.size} files)", files.size > 100)
        val offenders = files.filter { forbidden.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("safety features must not ask whether this phone is a Supporter: $offenders", offenders.isEmpty())
    }

    @Test
    fun theCheckWouldSeeAReference() {
        // The page that does ask names it, so the pattern is the one used.
        assertTrue(forbidden.containsMatchIn(File(src, "main/java/app/clairix/SupporterSettings.kt").readText()))
        assertTrue(forbidden.containsMatchIn("import app.clairix.supporter.Entitlement"))
        assertTrue(forbidden.containsMatchIn("if (Entitlement.isSupporter()) scan()"))
    }
}
