package app.clairix.comms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * How [LiveLink] judges the relay socket, as decisions apart from the socket so
 * they can be tested without a phone.
 *
 * The app sends no pings on a timer. The relay sends a heartbeat (`hb`) that
 * says how long until the next one at most (`next`): every two minutes while
 * envelopes are moving and for ten minutes after, then at the pace the app
 * asks for on the upgrade ([HEARTBEAT_MS], in the `X-Clairix-Heartbeat`
 * header). Any frame at all shows the socket is alive. A socket that has heard
 * from the relay within the announced interval, with [SLACK_MS] to spare, is
 * left alone; one that has gone quiet past that is asked with a ping; one that
 * has missed two heartbeats is replaced without asking. A relay from before
 * the adaptive heartbeat announces nothing and sends one every two minutes
 * ([LEGACY_HEARTBEAT_MS]), and the same rules then come to what they were:
 * a ping after three minutes' silence, a new socket after five.
 */
internal object LinkTiming {

    /** The quiet pace the app asks the relay for: one frame at least this often on an idle connection. */
    const val HEARTBEAT_MS = 5 * 60_000L

    /** What a relay that announces no interval sends: a heartbeat every two minutes. */
    const val LEGACY_HEARTBEAT_MS = 2 * 60_000L

    /** An announced interval is believed within these bounds (the relay keeps its own between two and ten minutes). */
    const val MIN_GAP_MS = 30_000L
    const val MAX_GAP_MS = 10 * 60_000L

    /** On top of the interval: a relay alarm that runs late, and the network in between. */
    const val SLACK_MS = 60_000L

    /** While Clairix is on screen or a call is up, a silence this long is checked with a ping straight away. */
    const val IN_USE_QUIET_MS = 60_000L

    /** The name of the header that carries [HEARTBEAT_MS] on the WebSocket upgrade. */
    const val HEARTBEAT_HEADER = "X-Clairix-Heartbeat"

    /** What the watchdog (or the quiet check) does about the socket. */
    enum class Check {
        /** Heard from the relay within the heartbeat window: nothing to do, the phone may sleep at once. */
        FRESH,
        /** Quiet past the window: a ping is sent, and its answer (or a new socket) awaited. */
        PROBE,
        /** Two heartbeats missed, or no socket at all: a new one is being opened. */
        REPLACE,
    }

    /** The interval a heartbeat announces, as believed; [LEGACY_HEARTBEAT_MS] when it announces none. */
    fun announcedGap(next: Long?): Long = next?.coerceIn(MIN_GAP_MS, MAX_GAP_MS) ?: LEGACY_HEARTBEAT_MS

    /** A socket heard from within this is taken to be alive. */
    fun heardWithinMs(gapMs: Long): Long = gapMs + SLACK_MS

    /** Nothing for longer than this: two heartbeats missed, and the socket is presumed dead. */
    fun silentLimitMs(gapMs: Long): Long = 2 * gapMs + SLACK_MS

    /** The verdict on a connected socket that has heard nothing for [silentForMs], with [gapMs] the announced interval. */
    fun check(silentForMs: Long, gapMs: Long): Check = when {
        silentForMs > silentLimitMs(gapMs) -> Check.REPLACE
        silentForMs <= heardWithinMs(gapMs) -> Check.FRESH
        else -> Check.PROBE
    }

    /**
     * How long the socket may stay quiet before the app pings on its own: a
     * minute while Clairix is in use ([inUse]: on screen, or a call), otherwise
     * until the relay's heartbeat is overdue.
     */
    fun quietLimitMs(inUse: Boolean, gapMs: Long): Long = if (inUse) IN_USE_QUIET_MS else heardWithinMs(gapMs)
}

/**
 * When the relay last sent anything on the live socket, by the caller's clock
 * (the one that keeps running in sleep, in the app), and a way to wait for the
 * next frame: the answer to a ping, a heartbeat, or a new socket opening.
 * Frames only ever move it forward.
 */
internal class FrameMark {

    private val last = MutableStateFlow(0L)

    /** When the newest frame came; 0 before any. */
    val at: Long get() = last.value

    /** A frame came at [time]. */
    fun mark(time: Long) {
        last.update { maxOf(it, time) }
    }

    /** Suspends until a frame at or after [since] has been marked; returns at once if one has. */
    suspend fun await(since: Long) {
        last.first { it >= since }
    }
}
