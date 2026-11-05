package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timeline's hash chain finds an entry changed, removed or moved, and tells the retention's removals apart. */
class TimelineChainTest {

    private fun content(id: String, ts: Long = 1_000L, detail: String = "d") =
        TimelineChain.Content(id, "PLATFORM", ts, "t-$id", detail, "LOW", null, null, null)

    /** A chain of [ids] written in order from the genesis, with the head. */
    private fun chain(vararg ids: String): Pair<List<Pair<TimelineChain.Link?, TimelineChain.Content>>, String> {
        var prev = TimelineChain.GENESIS
        val out = ArrayList<Pair<TimelineChain.Link?, TimelineChain.Content>>()
        ids.forEachIndexed { i, id ->
            val c = content(id, ts = 1_000L * (i + 1))
            val seq = i + 1L
            val h = TimelineChain.hash(prev, seq, c)
            out += TimelineChain.Link(seq, prev, h) to c
            prev = h
        }
        return out to prev
    }

    @Test
    fun aWholeChainIsIntact() {
        val (entries, head) = chain("a", "b", "c")
        val v = TimelineChain.verify(entries, emptyMap(), head)
        assertTrue(v.problems.toString(), v.intact)
        assertEquals(3, v.entries)
        assertEquals(head, v.head)
        // The order the entries are handed over in does not matter; the seq does.
        assertTrue(TimelineChain.verify(entries.reversed(), emptyMap(), head).intact)
    }

    @Test
    fun anEmptyTimelineIsIntactAtTheGenesis() {
        assertTrue(TimelineChain.verify(emptyList(), emptyMap(), TimelineChain.GENESIS).intact)
    }

    @Test
    fun aChangedEntryIsFound() {
        val (entries, head) = chain("a", "b", "c")
        val changed = entries.toMutableList()
        changed[1] = changed[1].first to changed[1].second.copy(detail = "something else")
        val v = TimelineChain.verify(changed, emptyMap(), head)
        assertFalse(v.intact)
        assertTrue(v.problems.single().contains("entry #2 (b) was changed"))
    }

    @Test
    fun aRemovedEntryIsFound() {
        val (entries, head) = chain("a", "b", "c")
        val v = TimelineChain.verify(entries.filterNot { it.second.id == "b" }, emptyMap(), head)
        assertTrue(v.problems.single().contains("before entry #3 (c) is missing or out of order"))
        // The newest one removed: the head no longer matches.
        val tail = TimelineChain.verify(entries.dropLast(1), emptyMap(), head)
        assertTrue(tail.problems.single().contains("newest entries are missing"))
    }

    @Test
    fun aMovedEntryIsFound() {
        // Swapping two entries' places in the order changes what each hashes over.
        val (entries, head) = chain("a", "b", "c")
        val (la, ca) = entries[0]
        val (lb, cb) = entries[1]
        val swapped = listOf(la!!.copy(seq = 2) to ca, lb!!.copy(seq = 1) to cb, entries[2])
        assertFalse(TimelineChain.verify(swapped, emptyMap(), head).intact)
        // Or keeping the seq and moving the content: changed.
        val moved = listOf(la to cb, lb to ca, entries[2])
        assertFalse(TimelineChain.verify(moved, emptyMap(), head).intact)
    }

    @Test
    fun anEntryWithNoLinkIsFound() {
        val (entries, head) = chain("a", "b")
        val v = TimelineChain.verify(entries + (null to content("x")), emptyMap(), head)
        assertTrue(v.problems.single().contains("1 entry carries no link"))
    }

    @Test
    fun theRetentionsRemovalsAreBridgedAndCounted() {
        val (entries, head) = chain("a", "b", "c", "d", "e")
        val links = entries.map { it.first!! }
        // The retention takes b and c, then later d, beside the gap: one bridge.
        var bridges = TimelineChain.bridged(links, setOf(2L, 3L), emptyMap())
        assertEquals(1, bridges.size)
        val afterFirst = entries.filter { it.first!!.seq !in setOf(2L, 3L) }
        assertTrue(TimelineChain.verify(afterFirst, bridges, head).intact)
        assertEquals(2, TimelineChain.verify(afterFirst, bridges, head).removedByRetention)
        bridges = TimelineChain.bridged(afterFirst.map { it.first!! }, setOf(4L), bridges)
        assertEquals(1, bridges.size)
        val afterSecond = afterFirst.filter { it.first!!.seq != 4L }
        val v = TimelineChain.verify(afterSecond, bridges, head)
        assertTrue(v.problems.toString(), v.intact)
        assertEquals(3, v.removedByRetention)
        // A removal by hand beside the bridged gap is still found.
        assertFalse(TimelineChain.verify(afterSecond.filter { it.second.id != "e" }, bridges, head).intact)
    }

    @Test
    fun removingTheNewestAndThenEverythingStaysAccountedFor() {
        val (entries, head) = chain("a", "b", "c")
        val links = entries.map { it.first!! }
        val tailGone = TimelineChain.bridged(links, setOf(3L), emptyMap())
        assertTrue(TimelineChain.verify(entries.take(2), tailGone, head).intact)
        // Then the front, merging with nothing in between: everything removed, the head bridged from the genesis.
        val allGone = TimelineChain.bridged(links.take(2), setOf(1L, 2L), tailGone)
        val v = TimelineChain.verify(emptyList(), allGone, head)
        assertTrue(v.problems.toString(), v.intact)
        assertEquals(3, v.removedByRetention)
    }

    @Test
    fun theCanonicalFormCannotBeShiftedBetweenFields() {
        val one = content("a").copy(title = "x\ndetail=1:y", detail = "")
        val two = content("a").copy(title = "x", detail = "y")
        assertNotEquals(TimelineChain.canonical(1, one), TimelineChain.canonical(1, two))
        assertNotEquals(TimelineChain.canonical(1, content("a").copy(lat = null)), TimelineChain.canonical(1, content("a").copy(lat = 0.0)))
    }

    @Test
    fun theGenesisIsAFixedValueAnyoneCanRecompute() {
        assertEquals(TimelineChain.sha256Hex("clairix timeline genesis v1"), TimelineChain.GENESIS)
        assertEquals(64, TimelineChain.GENESIS.length)
    }
}
