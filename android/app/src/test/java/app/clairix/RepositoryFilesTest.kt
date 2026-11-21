package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Where the tests that read the repository's own files find them. */
class RepositoryFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String) = File(tmp.root, path).apply { parentFile?.mkdirs(); writeText("{}") }

    @Test
    fun listsLeftInTheModuleFolderAreNotTakenForTheRepository() {
        write("android/settings.gradle.kts")
        write("relays/keys.json")
        // The app's cache of downloaded lists, as it looks when it lands in the working directory.
        for (name in listOf("keys.json", "keys.json.sig", "latest.json", "relays.json", "tiles.json")) write("android/app/relays/$name")
        File(tmp.root, "android/app/src/test").mkdirs()
        for (start in listOf("android/app", "android/app/src/test", "android/app/relays", "android", "relays", "")) {
            assertEquals("from \"$start\"", tmp.root, repositoryRoot(File(tmp.root, start)))
        }
    }

    @Test
    fun theTestsRunInsideTheRepositoryAndAFileItLacksIsNamed() {
        val root = repositoryRoot()
        assertTrue("$root holds this test", File(root, "android/app/src/test/java/app/clairix/RepositoryFilesTest.kt").isFile)
        assertEquals(File(root, "relays/keys.json"), repositoryFile("relays/keys.json"))
        try {
            repositoryFile("relays/no-such-list.json")
            fail("found a file the repository does not have")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, "relays/no-such-list.json" in expected.message.orEmpty())
        }
    }
}
