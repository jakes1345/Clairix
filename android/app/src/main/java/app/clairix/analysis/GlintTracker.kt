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
 *  1. **Grid.** A cell of [SCALE]×[SCALE] pixels is bright when *any* of its pixels
 *     is over the threshold, so a pinhole's glint a few pixels across is never
 *     skipped between sample points.
 *  2. **Spots.** Connected bright cells make a spot; one larger than [MAX_AREA]
 *     cells is a lamp or a window, and one longer than it is wide (by its second
 *     moments) or sprawling across its bounding box is an edge or a streak. A lens
 *     returns a round point.
 *  3. **Stillness.** Each spot is tracked against an *anchor* fixed where it was
 *     first seen. Drifting more than [ANCHOR_RADIUS] cells from it starts the clock
 *     again, so a highlight that slides slowly while the phone pans never counts as
 *     still. Time held is measured in camera time, not frames, so a slow camera in
 *     a dark room does not shorten the wait. A spot may vanish for [MAX_MISSES]
 *     frames (hand tremor, sensor noise) without losing its place.
 *  4. **Light or reflection.** With the torch available, a spot that has held for
 *     [HOLD_MS] is tested once: the torch goes off for a moment ([VERIFY_START_MS] to
 *     [VERIFY_END_MS]). A reflection of the torch goes dark with it; a spot that
 *     stays lit is a light source (a standby LED, a screen, a lamp) and is never
 *     reported. In IR mode the torch is off anyway and a lit point is what is sought.
 */
class GlintTracker(private val ir: Boolean, private val torchAvailable: Boolean) {

    companion object {
        const val SCALE = 4
        const val GLINT_THRESHOLD = 235
        /** IR leaks through the front camera's filter dimly; the dot is bright but rarely saturates. */
        const val IR_THRESHOLD = 200
        /** IR mode: above this mean luma the room has visible light in it and a dot means nothing. */
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
        /** The torch-off test: frames from START to END after the torch goes off are sampled. */
        const val VERIFY_START_MS = 200L
        const val VERIFY_END_MS = 450L
        /** After the torch comes back, the camera's exposure settles before tracking resumes. */
        const val SETTLE_MS = 350L
    }

    enum class Kind { TRACKING, LIGHT_SOURCE, REFLECTION }

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
        val confidence: Float get() = if (kind == Kind.LIGHT_SOURCE) 0f else min(1f, heldMs.toFloat() / FULL_MS)
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
        val checking: Boolean
    )

    private class Track(val id: Int, var ax: Float, var ay: Float, var gx: Float, var gy: Float, var radius: Float, var stillSinceNs: Long) {
        var misses = 0
        var kind = Kind.TRACKING
        var reportedMs = -1L
        var verifyFrames = 0
        var verifyLit = 0
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

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var prevMean = -1f

    private var gw = 0
    private var gh = 0
    private var grid = ByteArray(0)
    private var labels = IntArray(0)
    private var stack = IntArray(0)

    /** Torch-off test: when it began (camera ns), or -1. */
    private var verifyStartNs = -1L
    /** Tracking resumes after this camera time (exposure settling after the test). */
    private var settleUntilNs = -1L

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
     * [timestampNs] is the camera's frame time.
     */
    fun process(luma: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int, timestampNs: Long): Result {
        if (width < SCALE * 8 || height < SCALE * 8) return empty()
        ensureBuffers(width / SCALE, height / SCALE)
        val threshold = if (ir) IR_THRESHOLD else GLINT_THRESHOLD

        // Grid: a cell is bright when any of its pixels is; the mean is over every pixel read.
        var sum = 0L
        var count = 0L
        var bright = 0
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var peak = 0
                for (dy in 0 until SCALE) {
                    val row = (gy * SCALE + dy) * rowStride
                    for (dx in 0 until SCALE) {
                        val idx = row + (gx * SCALE + dx) * pixelStride
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

        // The torch-off test runs instead of tracking until it is over and exposure has settled.
        if (verifyStartNs >= 0) return verifyFrame(timestampNs, mean, bright, washedOut, tooBright)
        if (settleUntilNs >= 0) {
            if (timestampNs < settleUntilNs) return result(mean, 0f, bright, washedOut, tooBright, emptyList(), null, checking = true)
            settleUntilNs = -1
            prevMean = -1f
        }

        val change = if (prevMean > 0f) abs(mean - prevMean) / prevMean else 0f
        prevMean = mean
        val stable = change < LUMA_STABLE_FRACTION

        val spots = if (bright > 0 && !washedOut && !tooBright) findSpots() else emptyList()
        track(spots, stable, timestampNs)

        // Start the torch-off test for spots that have just held long enough.
        if (!ir && torchAvailable) {
            val due = tracks.filter { it.kind == Kind.TRACKING && heldMs(it, timestampNs) >= HOLD_MS }
            if (due.isNotEmpty()) {
                due.forEach { it.verifyFrames = 0; it.verifyLit = 0 }
                verifyStartNs = timestampNs
                return result(mean, change, bright, washedOut, tooBright, emptyList(), torch = false, checking = true, now = timestampNs)
            }
        } else {
            // Nothing to test against: IR is looking for lights, and without a torch there is no reflection to switch off.
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

    private fun verifyFrame(ts: Long, mean: Float, bright: Int, washedOut: Boolean, tooBright: Boolean): Result {
        val elapsed = (ts - verifyStartNs) / 1_000_000L
        val testing = tracks.filter { it.kind == Kind.TRACKING }
        if (elapsed in VERIFY_START_MS..VERIFY_END_MS) {
            for (t in testing) {
                t.verifyFrames++
                if (litNear(t.ax, t.ay)) t.verifyLit++
            }
        }
        if (elapsed <= VERIFY_END_MS) return result(mean, 0f, bright, washedOut, tooBright, emptyList(), null, checking = true)
        for (t in testing) {
            // No frames sampled (a stalled camera): leave it to be tested again.
            if (t.verifyFrames == 0) continue
            t.kind = if (t.verifyLit * 2 > t.verifyFrames) Kind.LIGHT_SOURCE else Kind.REFLECTION
        }
        verifyStartNs = -1
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

    private fun track(found: List<Found>, stable: Boolean, ts: Long) {
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
            for (t in kept) if (t.kind != Kind.LIGHT_SOURCE) { t.stillSinceNs = ts; t.kind = Kind.TRACKING; t.reportedMs = -1 }
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
        report = report, torch = torch, checking = checking
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
