package app.clairix.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The optical sweep's detector, kept free of Android types so it can be tested on
 * synthetic frames ([LensGlintAnalyzer] feeds it camera frames).
 *
 * Per frame, over the luma plane:
 *  1. **Grid.** A cell of [cellSize] pixels square is bright when *any* of its
 *     pixels is over the threshold, so a pinhole's glint a few pixels across is
 *     never skipped between sample points. The cell grows with the frame (4 px at
 *     640 wide, 8 px at 1280) so the grid stays about [GRID_COLUMNS] cells across:
 *     a sharper frame catches a smaller glint without making hand tremor look like
 *     movement.
 *  2. **Spots.** Connected bright cells make a spot; one larger than [MAX_AREA]
 *     cells is a lamp or a window, and one longer than it is wide (by its second
 *     moments) or sprawling across its bounding box is an edge or a streak. A lens
 *     returns a round point.
 *  3. **Stillness.** Each spot is tracked against an *anchor* fixed where it was
 *     first seen. Drifting more than [ANCHOR_RADIUS] cells from it starts the clock
 *     again, so a highlight that slides slowly while the phone pans never counts as
 *     still. Time held is measured in camera time, not frames, so a slow camera in
 *     a dark room does not shorten the wait. A spot may vanish for [MAX_MISSES]
 *     frames (hand tremor, sensor noise) without losing its place. A spot that
 *     stays on its anchor while the gyroscope says the phone turned [FIXED_TURN_DEG]
 *     is on the sensor (a hot pixel, a mark on the lens), not in the room: it is
 *     [Kind.FIXED] and never reported.
 *  4. **Light or reflection.** Lens glint needs the torch; a camera without one
 *     tracks nothing ([torchAvailable]). With the torch, a spot that has held for
 *     [HOLD_MS] is tested: the torch goes off, and the test waits until the torch
 *     is *seen* to go off (the scene darkens below [DARK_FRACTION] of its level, the
 *     torch being the largest light in it) or the camera confirms it, then samples
 *     [VERIFY_WINDOW_MS] of frames. A reflection of the torch goes dark with it; a
 *     spot that stays lit is a light source (a standby LED, a screen, a lamp) and
 *     is not reported. A torch that never goes dark within [VERIFY_TIMEOUT_MS]
 *     decides nothing: the spots are tried again after [RETEST_MS], and so is any
 *     spot filed as a light, so a lens misfiled once is not grey for good. In IR
 *     mode the torch is off anyway and a lit point is what is sought.
 */
class GlintTracker(private val ir: Boolean, torchAvailable: Boolean) {

    companion object {
        /** The smallest cell, in pixels; the cell grows with the frame (see [cellSize]). */
        const val SCALE = 4
        /** The grid is kept about this many cells across, whatever the frame size. */
        const val GRID_COLUMNS = 160
        const val GLINT_THRESHOLD = 235
        /** IR leaks through the front camera's filter dimly; the dot is bright but rarely saturates. */
        const val IR_THRESHOLD = 200
        /**
         * IR mode: above this mean luma the room has visible light in it and a dot means
         * nothing. A night-vision camera's light sensor only switches its infrared LEDs
         * on in the dark, so a dark room is the only place there is one to see; and as
         * the camera's auto-exposure lifts any lit scene towards mid-grey, a mean this
         * low means the exposure has run out of range, which is what a dark room does.
         */
        const val IR_MAX_AMBIENT = 60f
        const val MAX_AREA = 60
        /** Below this many cells a spot is too small for its shape to mean anything. */
        private const val SHAPE_MIN_AREA = 3
        /** Smaller over larger spread (second moments); a line is 0, a disc 1. */
        const val MIN_ROUNDNESS = 0.3f
        /** Share of its bounding box a spot must fill; a diagonal streak fills little of it. */
        const val MIN_FILL = 0.5f
        /** Fraction of the grid allowed over threshold before the frame is called washed out. */
        const val WASHOUT_FRACTION = 0.15f
        /** Frame-to-frame change in mean luma above which the scene is moving. */
        const val LUMA_STABLE_FRACTION = 0.06f
        /** Grid cells a spot may move between frames and still be the same spot. */
        const val MATCH_RADIUS = 3f
        /** Grid cells a spot may wander from where it was first seen and still be held still. */
        const val ANCHOR_RADIUS = 1.5f
        /** Frames a spot may be missing before its track is dropped. */
        const val MAX_MISSES = 2
        /** Held still this long (camera time) before a spot is a candidate. */
        const val HOLD_MS = 700L
        /** Held still this long for full confidence; alerts start at 0.7 of it. */
        const val FULL_MS = 2_000L
        /** The torch-off test: the scene below this fraction of its pre-test mean luma means the torch has gone off. */
        const val DARK_FRACTION = 0.75f
        /** Frames sampled for this long once the torch is seen (or confirmed) off. */
        const val VERIFY_WINDOW_MS = 250L
        /** A torch not seen to go off within this long after asking: the test decides nothing. */
        const val VERIFY_TIMEOUT_MS = 800L
        /** A spot filed as a light, or one whose test decided nothing, is tested again after this long. */
        const val RETEST_MS = 4_000L
        /** After the torch comes back, the camera's exposure settles before tracking resumes. */
        const val SETTLE_MS = 350L
        /** Turning the phone this far (degrees, across the view) moves anything in the room well off its anchor. */
        const val FIXED_TURN_DEG = 5f

        /** Cell size, in pixels, for a frame [width] pixels wide: the grid stays about [GRID_COLUMNS] across. */
        fun cellSize(width: Int): Int = max(SCALE, width / GRID_COLUMNS)
    }

    enum class Kind {
        TRACKING,
        LIGHT_SOURCE,
        REFLECTION,
        /** Stayed put on the sensor while the phone turned: a hot pixel or a mark on the lens. Never reported. */
        FIXED
    }

    /** A tracked spot as the caller sees it; coordinates are grid cells (sensor orientation). */
    data class Spot(
        val id: Int,
        val gx: Float,
        val gy: Float,
        val radius: Float,
        val heldMs: Long,
        val kind: Kind
    ) {
        /** A spot worth showing as a likely lens: held still and not a light source. */
        val candidate: Boolean get() = heldMs >= HOLD_MS && kind == Kind.REFLECTION
        val confidence: Float get() = if (kind == Kind.LIGHT_SOURCE || kind == Kind.FIXED) 0f else min(1f, heldMs.toFloat() / FULL_MS)
    }

    data class Result(
        val gridWidth: Int,
        val gridHeight: Int,
        val meanLuma: Float,
        val lumaChange: Float,
        val brightCells: Int,
        val washedOut: Boolean,
        val tooBright: Boolean,
        val spots: List<Spot>,
        /** Candidates to report this frame: newly confirmed, or held markedly longer since last reported. */
        val report: List<Spot>,
        /** Non-null when the caller should switch the torch: false to test, true when done. */
        val torch: Boolean?,
        /** True while the torch-off test is running; the view should say so. */
        val checking: Boolean,
        /** The last torch-off test never saw the torch go off, so it decided nothing. */
        val torchUnseen: Boolean = false
    )

    private class Track(val id: Int, var ax: Float, var ay: Float, var gx: Float, var gy: Float, var radius: Float, var stillSinceNs: Long) {
        var misses = 0
        var kind = Kind.TRACKING
        var reportedMs = -1L
        var verifyFrames = 0
        var verifyLit = 0
        /** When the last torch test decided this spot (camera ns); read only once one has. */
        var decidedNs = 0L
        /** Not tested again before this camera time. */
        var retestAfterNs = Long.MIN_VALUE
        /** The phone's turn, degrees about its x and y axes, since this spot was anchored. */
        var turnX = 0f
        var turnY = 0f
    }

    private class Blob {
        var area = 0
        var sx = 0L
        var sy = 0L
        var sxx = 0L
        var syy = 0L
        var sxy = 0L
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
    }

    /**
     * Whether this camera has a torch. Lens glint tracks nothing without one: there
     * is no reflection to switch off, and a steady spot is as likely a lamp as a lens.
     * Set by the analyzer once the camera is bound; read on every frame.
     */
    @Volatile var torchAvailable: Boolean = torchAvailable

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var prevMean = -1f

    private var gw = 0
    private var gh = 0
    private var grid = ByteArray(0)
    private var labels = IntArray(0)
    private var stack = IntArray(0)

    /** Torch-off test: when the torch was asked off (camera ns), or -1. */
    private var verifyStartNs = -1L
    /** The scene's mean luma when the test began, with the torch on. */
    private var preTestMean = 0f
    /** The first frame the torch was seen (or confirmed) off, or -1: the sampling window starts here. */
    private var darkSinceNs = -1L
    /** The first frame that arrived with the camera's confirmation that the torch is off, or -1. */
    private var confirmedOffNs = -1L
    /** Tracking resumes after this camera time (exposure settling after the test). */
    private var settleUntilNs = -1L
    /** The last test timed out without seeing the torch go off. */
    private var torchUnseen = false

    private fun ensureBuffers(w: Int, h: Int) {
        if (w == gw && h == gh) return
        gw = w; gh = h
        val n = w * h
        grid = ByteArray(n)
        labels = IntArray(n)
        stack = IntArray(n)
        tracks.clear()
    }

    /**
     * One frame. [luma] holds the Y plane: pixel (x, y) is at `y * rowStride + x * pixelStride`.
     * [timestampNs] is the camera's frame time. [torchOffConfirmed] is true once the
     * camera has confirmed the torch went off as asked. [turnXDeg] and [turnYDeg] are
     * how far the phone turned about its x and y axes since the last frame (the
     * gyroscope; zero when there is none).
     */
    fun process(
        luma: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int, timestampNs: Long,
        torchOffConfirmed: Boolean = false, turnXDeg: Float = 0f, turnYDeg: Float = 0f
    ): Result {
        if (width < SCALE * 8 || height < SCALE * 8) return empty()
        val scale = cellSize(width)
        ensureBuffers(width / scale, height / scale)
        val threshold = if (ir) IR_THRESHOLD else GLINT_THRESHOLD

        // Grid: a cell is bright when any of its pixels is; the mean is over every pixel read.
        var sum = 0L
        var count = 0L
        var bright = 0
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var peak = 0
                for (dy in 0 until scale) {
                    val row = (gy * scale + dy) * rowStride
                    for (dx in 0 until scale) {
                        val idx = row + (gx * scale + dx) * pixelStride
                        if (idx >= luma.size) continue
                        val v = luma[idx].toInt() and 0xFF
                        sum += v
                        count++
                        if (v > peak) peak = v
                    }
                }
                val on = peak > threshold
                grid[gy * gw + gx] = if (on) 1 else 0
                if (on) bright++
            }
        }
        val n = gw * gh
        val mean = if (count == 0L) 0f else sum.toFloat() / count
        val washedOut = bright > n * WASHOUT_FRACTION
        val tooBright = ir && mean > IR_MAX_AMBIENT

        // Lens glint without a torch: nothing to test a spot against, so nothing is tracked.
        if (!ir && !torchAvailable) {
            tracks.clear()
            verifyStartNs = -1L
            darkSinceNs = -1L
            settleUntilNs = -1L
            prevMean = -1f
            torchUnseen = false
            return result(mean, 0f, bright, washedOut, tooBright, emptyList(), null, checking = false)
        }

        // The torch-off test runs instead of tracking until it is over and exposure has settled.
        if (verifyStartNs >= 0) return verifyFrame(timestampNs, mean, bright, washedOut, tooBright, torchOffConfirmed)
        if (settleUntilNs >= 0) {
            if (timestampNs < settleUntilNs) return result(mean, 0f, bright, washedOut, tooBright, emptyList(), null, checking = true)
            settleUntilNs = -1
            prevMean = -1f
        }

        val change = if (prevMean > 0f) abs(mean - prevMean) / prevMean else 0f
        prevMean = mean
        val stable = change < LUMA_STABLE_FRACTION

        val spots = if (bright > 0 && !washedOut && !tooBright) findSpots() else emptyList()
        track(spots, stable, timestampNs, turnXDeg, turnYDeg)
        // The spot a test could not settle is gone: the warning about it goes with it.
        if (tracks.isEmpty()) torchUnseen = false

        if (!ir) {
            // A spot filed as a light is tried again after a while: the test can be fooled
            // (a torch slow to go dark), and holding still on a lens must not leave it grey.
            for (t in tracks) {
                if (t.kind == Kind.LIGHT_SOURCE && timestampNs - t.decidedNs >= RETEST_MS * 1_000_000L) t.kind = Kind.TRACKING
            }
            // Start the torch-off test for spots that have held long enough.
            val due = tracks.filter { it.kind == Kind.TRACKING && heldMs(it, timestampNs) >= HOLD_MS && timestampNs >= it.retestAfterNs }
            if (due.isNotEmpty()) {
                due.forEach { it.verifyFrames = 0; it.verifyLit = 0 }
                verifyStartNs = timestampNs
                preTestMean = mean
                darkSinceNs = -1L
                confirmedOffNs = -1L
                return result(mean, change, bright, washedOut, tooBright, emptyList(), torch = false, checking = true, now = timestampNs)
            }
        } else {
            // IR is looking for lights, so there is nothing to switch off: a held point is the find.
            tracks.filter { it.kind == Kind.TRACKING && heldMs(it, timestampNs) >= HOLD_MS }.forEach { it.kind = Kind.REFLECTION }
        }

        val report = ArrayList<Spot>()
        for (t in tracks) {
            if (t.kind != Kind.REFLECTION) continue
            val held = heldMs(t, timestampNs)
            if (held < HOLD_MS) continue
            // First report on confirmation, then every 200 ms of further holding, so confidence climbs.
            if (t.reportedMs < 0 || held - t.reportedMs >= 200) {
                t.reportedMs = held
                report.add(spot(t, timestampNs))
            }
        }
        return result(mean, change, bright, washedOut, tooBright, report, null, checking = false, now = timestampNs)
    }

    private fun verifyFrame(ts: Long, mean: Float, bright: Int, washedOut: Boolean, tooBright: Boolean, torchOffConfirmed: Boolean): Result {
        val testing = tracks.filter { it.kind == Kind.TRACKING }
        if (darkSinceNs < 0) {
            // The window opens when the torch is seen to go off, not at a fixed time after
            // asking: on a phone whose torch is slow, a fixed window samples it still lit.
            // The camera's confirmation counts from the frame after it arrives: the frame
            // in hand when it lands may have been exposed just before.
            val confirmedEarlier = confirmedOffNs >= 0
            if (torchOffConfirmed && confirmedOffNs < 0) confirmedOffNs = ts
            if (mean < DARK_FRACTION * preTestMean || confirmedEarlier) {
                darkSinceNs = ts
            } else if ((ts - verifyStartNs) / 1_000_000L > VERIFY_TIMEOUT_MS) {
                // It never went dark: decide nothing, put the torch back, try again later.
                for (t in testing) t.retestAfterNs = ts + RETEST_MS * 1_000_000L
                verifyStartNs = -1L
                torchUnseen = true
                settleUntilNs = ts + SETTLE_MS * 1_000_000L
                return result(mean, 0f, bright, washedOut, tooBright, emptyList(), torch = true, checking = true)
            } else {
                return result(mean, 0f, bright, washedOut, tooBright, emptyList(), null, checking = true)
            }
        }
        if ((ts - darkSinceNs) / 1_000_000L < VERIFY_WINDOW_MS) {
            for (t in testing) {
                t.verifyFrames++
                if (litNear(t.ax, t.ay)) t.verifyLit++
            }
            return result(mean, 0f, bright, washedOut, tooBright, emptyList(), null, checking = true)
        }
        for (t in testing) {
            // No frames sampled (a stalled camera): leave it to be tested again.
            if (t.verifyFrames == 0) continue
            t.kind = if (t.verifyLit * 2 > t.verifyFrames) Kind.LIGHT_SOURCE else Kind.REFLECTION
            t.decidedNs = ts
        }
        verifyStartNs = -1L
        darkSinceNs = -1L
        torchUnseen = false
        settleUntilNs = ts + SETTLE_MS * 1_000_000L
        return result(mean, 0f, bright, washedOut, tooBright, emptyList(), torch = true, checking = true)
    }

    /** Whether any bright cell lies within [ANCHOR_RADIUS] + 1 of (ax, ay). */
    private fun litNear(ax: Float, ay: Float): Boolean {
        val r = ANCHOR_RADIUS + 1f
        val x0 = max(0, (ax - r).toInt()); val x1 = min(gw - 1, (ax + r).toInt() + 1)
        val y0 = max(0, (ay - r).toInt()); val y1 = min(gh - 1, (ay + r).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            if (grid[y * gw + x].toInt() != 1) continue
            val dx = x - ax; val dy = y - ay
            if (dx * dx + dy * dy <= r * r) return true
        }
        return false
    }

    private data class Found(val gx: Float, val gy: Float, val radius: Float)

    private fun findSpots(): List<Found> {
        val n = gw * gh
        java.util.Arrays.fill(labels, 0, n, 0)
        val out = ArrayList<Found>()
        var label = 0
        for (i in 0 until n) {
            if (grid[i].toInt() != 1 || labels[i] != 0) continue
            val b = flood(i, ++label)
            if (b.area > MAX_AREA) continue
            if (b.area >= SHAPE_MIN_AREA && !round(b)) continue
            out.add(Found(b.sx.toFloat() / b.area, b.sy.toFloat() / b.area, sqrt(b.area / Math.PI.toFloat())))
        }
        return out
    }

    /** Round enough to be a lens: the spread across is close to the spread along, and the box is mostly filled. */
    private fun round(b: Blob): Boolean {
        val a = b.area.toFloat()
        val mx = b.sx / a; val my = b.sy / a
        // Variance of a cell is 1/12 in each axis even for a single row; it keeps a 1-wide line from scoring 0/0.
        val vxx = b.sxx / a - mx * mx + 1f / 12f
        val vyy = b.syy / a - my * my + 1f / 12f
        val vxy = b.sxy / a - mx * my
        val half = (vxx + vyy) / 2f
        val diff = sqrt(((vxx - vyy) / 2f) * ((vxx - vyy) / 2f) + vxy * vxy)
        val big = half + diff
        val small = half - diff
        if (big <= 0f || small / big < MIN_ROUNDNESS) return false
        val box = (b.maxX - b.minX + 1) * (b.maxY - b.minY + 1)
        return a / box >= MIN_FILL
    }

    private fun track(found: List<Found>, stable: Boolean, ts: Long, turnX: Float, turnY: Float) {
        val matched = BooleanArray(tracks.size)
        val born = ArrayList<Track>()
        for (c in found) {
            var best = -1
            var bestD = MATCH_RADIUS * MATCH_RADIUS
            for (i in tracks.indices) {
                if (matched[i]) continue
                val t = tracks[i]
                val dx = t.gx - c.gx; val dy = t.gy - c.gy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = i }
            }
            if (best < 0) {
                born.add(Track(nextId++, c.gx, c.gy, c.gx, c.gy, c.radius, ts))
                continue
            }
            matched[best] = true
            val t = tracks[best]
            t.gx = c.gx; t.gy = c.gy; t.radius = c.radius; t.misses = 0
            val ax = t.gx - t.ax; val ay = t.gy - t.ay
            if (ax * ax + ay * ay > ANCHOR_RADIUS * ANCHOR_RADIUS) {
                // It has wandered: a highlight sliding with the phone, not a point held in place.
                t.ax = t.gx; t.ay = t.gy
                t.stillSinceNs = ts
                t.kind = Kind.TRACKING
                t.reportedMs = -1
                t.turnX = 0f; t.turnY = 0f
            } else if (t.kind != Kind.FIXED) {
                // Net turn, not the sum of every tremor: hand shake cancels out, a real turn adds up.
                t.turnX += turnX; t.turnY += turnY
                if (t.turnX * t.turnX + t.turnY * t.turnY >= FIXED_TURN_DEG * FIXED_TURN_DEG) {
                    // Anything in the room would have slid across the view by now.
                    t.kind = Kind.FIXED
                    t.reportedMs = -1
                }
            }
        }
        val kept = ArrayList<Track>(tracks.size + born.size)
        for (i in tracks.indices) {
            val t = tracks[i]
            if (!matched[i] && ++t.misses > MAX_MISSES) continue
            kept.add(t)
        }
        kept.addAll(born)
        if (!stable) {
            // The scene itself changed (a pan, a light switched): every spot starts its wait again.
            for (t in kept) if (t.kind != Kind.LIGHT_SOURCE && t.kind != Kind.FIXED) { t.stillSinceNs = ts; t.kind = Kind.TRACKING; t.reportedMs = -1 }
        }
        tracks.clear()
        tracks.addAll(kept)
    }

    private fun heldMs(t: Track, ts: Long): Long = (ts - t.stillSinceNs) / 1_000_000L

    private fun spot(t: Track, ts: Long) = Spot(t.id, t.gx, t.gy, t.radius, heldMs(t, ts), t.kind)

    private fun result(
        mean: Float, change: Float, bright: Int, washedOut: Boolean, tooBright: Boolean,
        report: List<Spot>, torch: Boolean?, checking: Boolean, now: Long = -1L
    ) = Result(
        gridWidth = gw, gridHeight = gh, meanLuma = mean, lumaChange = change, brightCells = bright,
        washedOut = washedOut, tooBright = tooBright,
        spots = if (now < 0) tracks.map { Spot(it.id, it.gx, it.gy, it.radius, 0L, it.kind) } else tracks.map { spot(it, now) },
        report = report, torch = torch, checking = checking, torchUnseen = torchUnseen
    )

    private fun empty() = Result(0, 0, 0f, 0f, 0, false, false, emptyList(), emptyList(), null, false)

    /** Iterative 4-connected flood fill from [start]. */
    private fun flood(start: Int, label: Int): Blob {
        val b = Blob()
        var sp = 0
        stack[sp++] = start
        labels[start] = label
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % gw
            val y = i / gw
            b.area++
            b.sx += x; b.sy += y
            b.sxx += x.toLong() * x; b.syy += y.toLong() * y; b.sxy += x.toLong() * y
            if (x < b.minX) b.minX = x
            if (x > b.maxX) b.maxX = x
            if (y < b.minY) b.minY = y
            if (y > b.maxY) b.maxY = y
            if (x > 0) push(i - 1, label).also { if (it) stack[sp++] = i - 1 }
            if (x < gw - 1) push(i + 1, label).also { if (it) stack[sp++] = i + 1 }
            if (y > 0) push(i - gw, label).also { if (it) stack[sp++] = i - gw }
            if (y < gh - 1) push(i + gw, label).also { if (it) stack[sp++] = i + gw }
        }
        return b
    }

    private fun push(j: Int, label: Int): Boolean {
        if (grid[j].toInt() != 1 || labels[j] != 0) return false
        labels[j] = label
        return true
    }
}
