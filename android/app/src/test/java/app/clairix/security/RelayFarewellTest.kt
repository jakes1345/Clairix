package app.clairix.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A wipe's relay deletion outlives the five-minute window a single signature
 * gets: some copy is current at every moment of the day it covers, the right
 * one is chosen on the relay's clock, and what the relay answers decides
 * whether it is tried again.
 */
class RelayFarewellTest {

    private val t0 = 1_700_000_000_000L
    private val dir: File = Files.createTempDirectory("farewell").toFile().apply { deleteOnExit() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun prepared(start: Long = t0, copies: Int = RelayFarewell.COPIES, offsetMs: Long = 0L, viaTor: Boolean = false) =
        RelayFarewell.Prepared(
            url = "https://relay.example/v1/me", pin = "sha256/AAAA", viaTor = viaTor, offsetMs = offsetMs,
            signed = (0 until copies).map { k ->
                val ts = start + k * RelayFarewell.STEP_MS
                RelayFarewell.Signed(ts, listOf("X-Clairix-Number" to "482913605", "X-Clairix-Ts" to ts.toString(), "X-Clairix-Nonce" to "n$k"))
            }
        )

    private fun answer(code: Int, serverTime: Long? = null, error: String? = null) = RelayFarewell.Answer(code, error, serverTime)

    @Test
    fun someCopyIsCurrentAtEveryMinuteOfTheDayItCovers() {
        assertEquals(RelayFarewell.HORIZON_MS, RelayFarewell.COPIES * RelayFarewell.STEP_MS)
        // Copies closer together than the relay's window is wide leave no gap.
        assertTrue(RelayFarewell.STEP_MS < 2 * RelayFarewell.USABLE_MS)
        val p = prepared()
        var t = t0
        while (t < t0 + RelayFarewell.HORIZON_MS - RelayFarewell.STEP_MS) {
            val usable = RelayFarewell.usable(p, t)
            assertTrue("no copy at ${(t - t0) / 60_000} min", usable.isNotEmpty())
            assertTrue(usable.all { kotlin.math.abs(it.ts - t) <= RelayFarewell.USABLE_MS })
            t += 60_000L
        }
        assertFalse(RelayFarewell.expired(p, t0 + RelayFarewell.HORIZON_MS - RelayFarewell.STEP_MS))
        assertTrue(RelayFarewell.expired(p, t0 + RelayFarewell.HORIZON_MS + RelayFarewell.WINDOW_MS))
    }

    @Test
    fun theCopyIsChosenOnTheRelaysClockNotThePhones() {
        // Signed when the phone was ten minutes behind the relay.
        val p = prepared(offsetMs = 10 * 60_000L)
        val best = RelayFarewell.usable(p, t0 - 10 * 60_000L).first()
        assertEquals(t0, best.ts)
    }

    @Test
    fun anAcceptedCopyIsTheEnd() {
        val asked = ArrayList<Long>()
        val outcome = RelayFarewell.deliver(prepared(), t0 + 60_000L) { copy -> asked += copy.ts; answer(200) }
        assertEquals(RelayFarewell.Outcome.DONE, outcome)
        assertEquals(listOf(t0), asked)
    }

    @Test
    fun noAnswerABusyRelayOrAThrottleMeansTryAgainLater() {
        assertEquals(RelayFarewell.Outcome.RETRY, RelayFarewell.deliver(prepared(), t0) { null })
        assertEquals(RelayFarewell.Outcome.RETRY, RelayFarewell.deliver(prepared(), t0) { answer(503) })
        assertEquals(RelayFarewell.Outcome.RETRY, RelayFarewell.deliver(prepared(), t0) { answer(429) })
    }

    @Test
    fun aStaleCopyIsReplacedByTheOneTheRelaysOwnClockCallsCurrent() {
        // The phone's clock was moved half an hour back since the copies were signed.
        val now = t0
        val relayNow = t0 + 30 * 60_000L
        val asked = ArrayList<Long>()
        val outcome = RelayFarewell.deliver(prepared(), now) { copy ->
            asked += copy.ts
            if (kotlin.math.abs(copy.ts - relayNow) > RelayFarewell.WINDOW_MS) answer(401, serverTime = relayNow, error = "Request timestamp outside the allowed window")
            else answer(200)
        }
        assertEquals(RelayFarewell.Outcome.DONE, outcome)
        assertEquals(2, asked.size)
        assertTrue(kotlin.math.abs(asked.last() - relayNow) <= RelayFarewell.USABLE_MS)
    }

    @Test
    fun aRefusedCopyIsFollowedByOneMoreAndASecondRefusalMeansNoAccountToDelete() {
        val asked = ArrayList<Long>()
        // Between two copies: both usable, both refused (the account is already gone).
        val outcome = RelayFarewell.deliver(prepared(), t0 + RelayFarewell.STEP_MS / 2) { copy ->
            asked += copy.ts
            answer(401, serverTime = copy.ts, error = "Signature rejected")
        }
        assertEquals(RelayFarewell.Outcome.DONE, outcome)
        assertEquals(2, asked.toSet().size)
    }

    @Test
    fun aRefusalNoCopyCanChangeEndsIt() {
        assertEquals(RelayFarewell.Outcome.DONE, RelayFarewell.deliver(prepared(), t0) { answer(400, error = "Bad request") })
        assertEquals(RelayFarewell.Outcome.DONE, RelayFarewell.deliver(prepared(), t0) { answer(404) })
    }

    @Test
    fun withNoCurrentCopyNothingIsSentAndItIsTriedAgain() {
        var asked = 0
        val outcome = RelayFarewell.deliver(prepared(start = t0 + 60 * 60_000L, copies = 2), t0) { asked++; answer(200) }
        assertEquals(RelayFarewell.Outcome.RETRY, outcome)
        assertEquals(0, asked)
    }

    @Test
    fun theFileKeepsEveryRelayAndDropsWhatHasExpired() {
        val live = prepared(copies = 10)
        val tor = prepared(copies = 10, viaTor = true).copy(url = "https://onion.example/v1/me", pin = null)
        val old = prepared(start = t0 - 2 * RelayFarewell.HORIZON_MS, copies = 10).copy(url = "https://old.example/v1/me")
        RelayFarewell.save(dir, listOf(live, old))
        assertEquals(listOf(live, old), RelayFarewell.load(dir))

        RelayFarewell.keep(dir, listOf(tor), now = t0)
        assertEquals(listOf(live, tor), RelayFarewell.load(dir))
        assertEquals(listOf(live, tor), RelayFarewell.fromJson(RelayFarewell.toJson(listOf(live, tor))))

        // Nothing left: no file, so nothing says a wipe ever happened.
        RelayFarewell.save(dir, emptyList())
        assertFalse(RelayFarewell.file(dir).exists())
        assertEquals(emptyList<RelayFarewell.Prepared>(), RelayFarewell.load(dir))
    }
}
