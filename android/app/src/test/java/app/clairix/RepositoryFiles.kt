package app.clairix

import java.io.File

/** What only the repository's root has below it: the settings file of the Gradle build in android/. */
private const val ROOT_MARKER = "android/settings.gradle.kts"

/**
 * The root of the repository the tests run in: the first folder, from [from]
 * upwards, with android/settings.gradle.kts below it. The tests run with
 * android/app as their working directory, and nothing puts such a file under
 * it or under android/.
 *
 * A file the app writes too is no mark of the root. The app keeps the lists it
 * downloads as relays/keys.json and the like in its data directory, and when a
 * test's context has no data directory they land in the working directory,
 * where "the first folder with relays/keys.json" is then android/app.
 */
internal fun repositoryRoot(from: File = File("").absoluteFile): File =
    generateSequence(from) { it.parentFile }.firstOrNull { File(it, ROOT_MARKER).isFile }
        ?: error("$from is not inside the Clairix repository: no folder from there up has $ROOT_MARKER")

/**
 * The repository's own copy of the file at [path], given from the root
 * ("relays/keys.json"), for a test of a file the app is built with or a
 * workflow publishes.
 */
internal fun repositoryFile(path: String): File {
    val root = repositoryRoot()
    return File(root, path).also { check(it.isFile) { "$path is not in the repository at $root" } }
}
