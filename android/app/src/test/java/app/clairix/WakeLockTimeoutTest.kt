package app.clairix

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every wake lock Clairix takes is taken with a timeout, so a path that forgets to
 * release one (an exception, a process that outlives its job) cannot hold the phone
 * awake for good. Read from the sources themselves: in every file that creates a wake
 * lock (`newWakeLock(`), each `acquire(` must pass a duration. The emulator workflow
 * checks the running app too (.github/scripts/power_budget.py); this keeps the rule
 * from being lost in a refactor before it ever reaches a device.
 */
class WakeLockTimeoutTest {

    private val roots = listOf("src/main/java", "src/suite/java", "src/full/java", "src/fullSuite/java", "src/play/java", "src/comms/java", "src/debug/java")

    private fun sources(): List<File> = roots.map(::File).filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    /** `acquire()` with nothing between the brackets, in code (line comments dropped). */
    private val bareAcquire = Regex("""\bacquire\s*\(\s*\)""")

    @Test
    fun everyWakeLockIsAcquiredWithATimeout() {
        val withWakeLocks = sources().filter { it.readText().contains("newWakeLock(") }
        // The tree has wake locks in the relay link, the watchdog, push sync, calls,
        // the TLS canary, the fake call and the relay farewell; finding none means the
        // test is looking in the wrong place, not that the rule holds.
        assertTrue("found only ${withWakeLocks.size} files with wake locks", withWakeLocks.size >= 7)
        val offences = withWakeLocks.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (bareAcquire.containsMatchIn(code)) "${file.path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("wake locks acquired without a timeout:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test
    fun theRuleCatchesABareAcquire() {
        assertTrue(bareAcquire.containsMatchIn("lock.acquire()"))
        assertTrue(bareAcquire.containsMatchIn("?.apply { acquire( ) }"))
        assertTrue(!bareAcquire.containsMatchIn("lock.acquire(10_000L)"))
        assertTrue(!bareAcquire.containsMatchIn("GnssMonitor.acquire(this, foreground = false)"))
    }
}
