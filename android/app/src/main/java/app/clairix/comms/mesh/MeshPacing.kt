package app.clairix.comms.mesh

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings

/**
 * How hard the offline mesh works, as decisions apart from the radio so they
 * can be tested.
 *
 * A phone carrying nothing, with no conversation open and no other phone met
 * lately, has nothing to hurry for: it listens with the Bluetooth controller's
 * low-power scan (a short window every few seconds, about a tenth of the
 * time, timed by the controller itself, so the pace holds while the phone
 * sleeps, which a timer in the app would not) and advertises once a second
 * instead of four times. Other phones still find it, a little more slowly; a
 * busy one, listening at the full balanced pace, soonest. With something to carry, a conversation
 * open on screen, or a meeting in the last [AFTER_MEETING_MS], the mesh runs
 * at that full pace. The long-range (LE Coded) advertisement, sent at full
 * power and eight times as long on the air, stops while the phone is still
 * ([MotionPolicy][app.clairix.analysis.MotionPolicy], from the
 * significant-motion sensor) and comes back when it moves; a phone without
 * that sensor is never taken to be still.
 */
internal object MeshPacing {

    /** After a meeting, other phones are likely still about: full pace this long. */
    const val AFTER_MEETING_MS = 10 * 60_000L

    /** No significant motion for this long and the phone is taken to be still: longer than a stop at a light or a wait in a queue. */
    const val STILL_AFTER_MS = 10 * 60_000L

    /** How often the node looks again at what it carries, the conversation on screen and the motion, while the phone is awake. */
    const val REVIEW_MS = 15_000L

    /** What the radio runs: the scan mode, the ordinary advertisement's mode, and whether the long-range set is on. */
    data class Plan(val scanMode: Int, val advertiseMode: Int, val longRange: Boolean)

    /**
     * Whether the mesh has work: something to carry, a conversation open on screen, or a
     * meeting in the last ten minutes. In low power ([lowPower], see
     * [LowPower][app.clairix.analysis.LowPower]) only something to carry counts: a
     * message or an SOS waiting to go still gets the full pace, an open conversation or a
     * recent meeting does not.
     */
    fun busy(carrying: Int, conversationOpen: Boolean, lastMeetingAt: Long, now: Long, lowPower: Boolean = false): Boolean =
        carrying > 0 || (!lowPower && (conversationOpen || (lastMeetingAt > 0L && now - lastMeetingAt < AFTER_MEETING_MS)))

    /**
     * The radio for a mesh that is [busy] or not, on a phone that is [still]
     * or not, whose Bluetooth has the long-range mode ([longRangeSupported])
     * or not. Busy is what the mesh always ran: the balanced scan and
     * advertisement.
     */
    fun plan(busy: Boolean, still: Boolean, longRangeSupported: Boolean): Plan = Plan(
        scanMode = if (busy) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER,
        advertiseMode = if (busy) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
        longRange = longRangeSupported && !still
    )
}
