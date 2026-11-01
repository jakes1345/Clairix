package app.clairix.detect

import android.net.wifi.WifiManager
import app.clairix.Threat
import app.clairix.analysis.WifiBeacon
import app.clairix.detect.KarmaCanary.Names
import app.clairix.detect.KarmaCanary.Retired
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random

/**
 * The canary's names: shaped like a home network's, one registered at a time, a new
 * one on schedule, and the old ones still recognised when an answer carries them.
 */
class KarmaCanaryTest {

    private val now = 1_790_000_000_000L
    private val hour = 3_600_000L

    private val words = listOf("Juniper", "Heron", "Basalt", "Willow", "Otter")

    /** What a home router is called by its owner: a word, maybe a separator, two to four digits, no leading zero. */
    private val shape = Regex("^([A-Za-z]+)([-_ ]?)([1-9][0-9]{1,3})$")
    private val hex32 = Regex("^[0-9a-f]{32}$")

    /** Stands in for WifiManager: what is registered, and in what order the calls came. */
    private class FakeSuggestions(var answer: Int = WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) : KarmaCanary.Suggestions {
        val registered = LinkedHashSet<String>()
        val calls = ArrayList<String>()
        var mostAtOnce = 0

        override fun removeAll() {
            calls += "remove"
            registered.clear()
        }

        override fun add(ssid: String): Int {
            calls += "add $ssid"
            if (KarmaTrap.added(answer)) registered += ssid
            mostAtOnce = maxOf(mostAtOnce, registered.size)
            return answer
        }
    }

    // ── Names ──────────────────────────────────────────────────────────────────

    @Test
    fun aNameLooksLikeAHomeRouterNotLikeAnIdentifier() {
        val random = Random(7)
        val seen = HashSet<String>()
        val separators = HashSet<String>()
        val lengths = HashSet<Int>()
        repeat(3_000) {
            val name = KarmaCanary.freshName(words, random)
            val m = shape.matchEntire(name)
            assertTrue("\"$name\" is a word and digits", m != null)
            m!!
            assertTrue("\"$name\" starts with a listed word", words.any { it.equals(m.groupValues[1], ignoreCase = true) })
            assertFalse("\"$name\" is not 32 hex digits", hex32.matches(name))
            assertTrue("\"$name\" fits an SSID", name.toByteArray(Charsets.UTF_8).size <= 32)
            seen += name
            separators += m.groupValues[2]
            lengths += m.groupValues[3].length
        }
        // Different every time, and in more than one style, as people's networks are.
        assertTrue("names vary (${seen.size} distinct)", seen.size > 2_500)
        assertEquals(setOf("", "-", "_", " "), separators)
        assertEquals(setOf(2, 3, 4), lengths)
    }

    @Test
    fun aFreshNameIsNeverOneStillWatchedFor() {
        val random = Random(11)
        // With one word and the two-digit range nearly used up, the only names left are few.
        val taken = (10..99).map { "Heron$it" }.toSet() - "Heron42"
        val name = KarmaCanary.freshName(listOf("Heron"), object : Random() {
            private val inner = Random(3)
            // Force the plain, title-case, two-digit style so only "Heron10".."Heron99" can come out.
            override fun nextInt(bound: Int): Int = when (bound) {
                4 -> 1          // not lower-case
                5 -> 0          // no separator
                3 -> 0          // two digits
                else -> inner.nextInt(bound)
            }
        }, avoid = taken)
        assertEquals("Heron42", name)
        assertTrue(KarmaCanary.freshName(words, random, avoid = setOf("Juniper4417")) != "Juniper4417")
    }

    @Test
    fun theBundledWordListIsPlainAndKeepsBrandsAndGiveawaysOut() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/assets/canary_words.txt"), File(it, "android/app/src/main/assets/canary_words.txt")) }
            .first { it.isFile }
        val list = KarmaCanary.parseWords(file.readText())
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals("every line is a usable word, none repeated", lines.size, list.size)
        assertTrue("enough words for tens of millions of names (${list.size})", list.size >= 500)
        for (w in list) {
            assertTrue("\"$w\" is 3 to 12 ASCII letters", w.length in 3..12 && w.all { it in 'A'..'Z' || it in 'a'..'z' })
        }
        // A canary must neither collide with real networks' default names nor point at the app or the trap.
        val banned = setOf(
            "home", "guest", "wifi", "wireless", "router", "network", "hotspot", "default", "free", "public", "office",
            "family", "mobile", "phone", "cable", "fiber", "android", "iphone", "galaxy", "pixel", "apple", "google",
            "amazon", "tesla", "linksys", "netgear", "belkin", "xfinity", "verizon", "spectrum", "cricket", "orange",
            "boost", "metro", "sprint", "optimum", "frontier", "charter", "three", "mint", "sky", "bell", "virgin",
            "starlink", "fritz", "ring", "nest", "echo", "pineapple", "hak5", "canary", "karma", "trap", "bait",
            "decoy", "honey", "clairix", "stingray", "kingfish", "hailstorm", "amberjack", "gossamer", "harpoon",
            "triggerfish", "arrowhead", "dirtbox"
        )
        val hits = list.filter { it.lowercase(Locale.US) in banned }
        assertTrue("kept out: $hits", hits.isEmpty())
    }

    @Test
    fun parsingSkipsCommentsBlanksAndAnythingButLetters() {
        val parsed = KarmaCanary.parseWords("# heading\n\nJuniper\n  Heron  \nheron\nfoo bar\nX9\nBasalt\n")
        assertEquals(listOf("Juniper", "Heron", "Basalt"), parsed)
    }

    // ── Rotation ───────────────────────────────────────────────────────────────

    @Test
    fun rotationKeepsOneNameRegisteredAtATime() {
        val port = FakeSuggestions()
        // An earlier version's fixed name is still registered from before the update.
        val legacy = "3f9a1c0e5b7d2a4c6e8f0a1b2c3d4e5f"
        port.registered += legacy
        var names = Names(current = legacy, rotatedAt = now - 400 * 24 * hour)
        val random = Random(5)

        val (first, s1) = KarmaCanary.rotate(port, names, words, random, now)
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS, s1)
        assertEquals(setOf(first.current), port.registered)
        assertEquals(now, first.rotatedAt)
        assertTrue("the old name is withdrawn but still watched for", legacy in first.live(now))
        assertEquals(listOf("remove", "add ${first.current}"), port.calls)
        names = first

        val later = now + KarmaCanary.ROTATE_MS
        val (second, _) = KarmaCanary.rotate(port, names, words, random, later)
        assertEquals("one name registered, the newest", setOf(second.current), port.registered)
        assertNotEquals(first.current, second.current)
        assertEquals(setOf(second.current, first.current, legacy), second.live(later))
        // Never two at once, at any point in either rotation.
        assertEquals(1, port.mostAtOnce)
        assertEquals(listOf("remove", "add ${first.current}", "remove", "add ${second.current}"), port.calls)
    }

    @Test
    fun aRefusedAddLeavesNothingRegisteredAndNoCurrentName() {
        val port = FakeSuggestions(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED)
        port.registered += "Heron-82"
        val before = Names(current = "Heron-82", rotatedAt = now - hour)
        val (after, status) = KarmaCanary.rotate(port, before, words, Random(1), now)
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED, status)
        assertTrue(port.registered.isEmpty())
        assertNull(after.current)
        assertEquals("the schedule counts from the last name that was registered", now - hour, after.rotatedAt)
        assertTrue("Heron-82" in after.live(now))
    }

    @Test
    fun theScheduleRotatesAnArmedTrapAndRetriesARefusedOne() {
        val armed = KarmaTrap.State.ARMED
        assertFalse(KarmaCanary.rotationDue(armed, rotatedAt = now - hour, attemptedAt = now - hour, now = now))
        assertTrue(KarmaCanary.rotationDue(armed, rotatedAt = now - KarmaCanary.ROTATE_MS, attemptedAt = 0L, now = now))
        assertTrue("never rotated", KarmaCanary.rotationDue(armed, rotatedAt = 0L, attemptedAt = 0L, now = now))
        assertTrue("the clock went back", KarmaCanary.rotationDue(armed, rotatedAt = now + hour, attemptedAt = 0L, now = now))
        assertTrue("at least daily", KarmaCanary.ROTATE_MS <= 24 * hour)

        for (down in listOf(KarmaTrap.State.DISALLOWED, KarmaTrap.State.REFUSED, KarmaTrap.State.UNAVAILABLE)) {
            assertFalse(KarmaCanary.rotationDue(down, rotatedAt = 0L, attemptedAt = now - 60_000L, now = now))
            assertTrue(KarmaCanary.rotationDue(down, rotatedAt = 0L, attemptedAt = now - KarmaCanary.RETRY_MS, now = now))
        }
        // Waiting on the owner's answer: no probe goes out yet, and a new add would ask again.
        assertFalse(KarmaCanary.rotationDue(KarmaTrap.State.PENDING, rotatedAt = 0L, attemptedAt = 0L, now = now + 30 * 24 * hour))
        assertFalse(KarmaCanary.rotationDue(KarmaTrap.State.OFF, rotatedAt = 0L, attemptedAt = 0L, now = now))
    }

    @Test
    fun withdrawnNamesAreKeptForAWeekAndAtMostThirtyTwo() {
        val old = (1..40).map { Retired("Willow$it", now - it * hour) }
        val names = Names(current = "Otter77", rotatedAt = now - hour, retired = old + Retired("Expired10", now - 8 * 24 * hour))
        val after = names.withdrawn(now)
        assertNull(after.current)
        assertEquals(KarmaCanary.MAX_RETIRED, after.retired.size)
        assertEquals("the newest first", "Otter77", after.retired.first().name)
        assertTrue(after.retired.none { it.name == "Expired10" })
        assertFalse("past a week a name is no longer matched", "Expired10" in names.live(now))
    }

    @Test
    fun withdrawnNamesSurviveStorageAndGarbageDoesNotStopTheScanner() {
        val retired = listOf(Retired("Basalt 903", now - hour), Retired("heron-82", now - 2 * hour))
        assertEquals(retired, KarmaCanary.parseRetired(KarmaCanary.encodeRetired(retired)))
        assertTrue(KarmaCanary.parseRetired("not json").isEmpty())
        assertTrue(KarmaCanary.parseRetired(null).isEmpty())
        assertEquals(listOf(Retired("Ok12", 5L)), KarmaCanary.parseRetired("""[{"n":"Ok12","t":5},{"n":"","t":9},{"t":3},7]"""))
    }

    // ── Matching ───────────────────────────────────────────────────────────────

    @Test
    fun anAnswerToTheCurrentOrAnEarlierNameIsCaught() {
        val names = Names(current = "Juniper4417", rotatedAt = now, retired = listOf(Retired("heron-82", now - hour)))
        val beacons = listOf(
            WifiBeacon("Juniper4417", "de:ad:be:ef:00:01", -48, "[ESS]"),
            WifiBeacon("\"heron-82\"", "de:ad:be:ef:00:02", -60, "[ESS]"),
            WifiBeacon("Juniper4418", "de:ad:be:ef:00:03", -50, "[ESS]"),
            WifiBeacon("Juniper4417", "de:ad:be:ef:00:01", -47, "[ESS]")
        )
        val hits = KarmaCanary.checkBeacons(names.live(now), beacons, now)
        assertEquals(hits.toString(), listOf("de:ad:be:ef:00:01", "de:ad:be:ef:00:02"), hits.map { it.bssid })
        assertTrue(hits.all { it.threat == Threat.CRITICAL && it.reason == KarmaCanary.REASON })
        assertTrue(KarmaCanary.checkBeacons(emptySet(), beacons, now).isEmpty())
    }

    @Test
    fun lookalikesHiddenNetworksAndNamesWithdrawnOverAWeekAgoAreNotAnswers() {
        val names = Names(
            current = "Juniper4417", rotatedAt = now,
            retired = listOf(Retired("heron-82", now - KarmaCanary.RETIRED_KEEP_MS - 1), Retired("Basalt_90", now - KarmaCanary.RETIRED_KEEP_MS))
        )
        val beacons = listOf(
            WifiBeacon("juniper4417", "02:00:00:00:00:01", -50, "[ESS]"),       // another case: another network
            WifiBeacon("Juniper 4417", "02:00:00:00:00:02", -50, "[ESS]"),
            WifiBeacon("Juniper44170", "02:00:00:00:00:03", -50, "[ESS]"),
            WifiBeacon("", "02:00:00:00:00:04", -50, "[ESS]"),                  // a hidden network
            WifiBeacon(null, "02:00:00:00:00:05", -50, "[ESS]"),
            WifiBeacon("heron-82", "02:00:00:00:00:06", -50, "[ESS]"),          // withdrawn a week and a moment ago
            WifiBeacon("Juniper4417", null, -50, "[ESS]"),                      // no address to report
            WifiBeacon("Basalt_90", "02:00:00:00:00:07", -70, "[WPA2-PSK-CCMP][ESS]"), // exactly a week: still watched
        )
        val hits = KarmaCanary.checkBeacons(names.live(now), beacons, now)
        assertEquals(hits.toString(), listOf("02:00:00:00:00:07"), hits.map { it.bssid })
    }
}
