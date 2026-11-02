package app.clairix.analysis

import kotlin.math.max
import kotlin.math.min

/**
 * The IR view's self-test: can this phone's front camera see infrared at all?
 *
 * A TV remote held to the front camera with a button pressed flashes its code in
 * bursts tens of milliseconds apart. On a camera that lets infrared through, one
 * small spot of the frame turns on and off from frame to frame; on one whose
 * filter blocks it, nothing happens. This watches for exactly that: a small patch
 * that toggles [TOGGLES] times within [WINDOW_MS]. A steady light toggles once, a
 * remote waved about crosses block edges without blinking (each block counts its
 * neighbours too), and a frame with too much of it lit (a lamp switched on, a
 * flickering screen) is not counted at all.
 *
 * Kept free of Android types so it can be tested on synthetic frames.
 */
class IrSelfTest {

    companion object {
        /** The coarse grid is kept about this many blocks across. */
        const val BLOCK_COLUMNS = 40
        const val MIN_BLOCK = 8
        /** A pixel over this is lit: the IR view's own threshold. */
        const val THRESHOLD = GlintTracker.IR_THRESHOLD
        /** Pixels over the threshold a block needs to be lit, so one noisy pixel is not a remote. */
        const val MIN_LIT_PIXELS = 3
        /** Toggles one patch must make within [WINDOW_MS]. */
        const val TOGGLES = 5
        const val WINDOW_MS = 2_000L
        /** A frame with more of its blocks lit than this is the scene changing, not a remote: not counted. */
        const val MAX_LIT_FRACTION = 0.02f
    }

    private var bw = 0
    private var bh = 0
    private var lit = BooleanArray(0)
    /** Each block's state (itself or a neighbour lit) on the last counted frame. */
    private var on = BooleanArray(0)
    /** The last [TOGGLES] toggle times of each block, as a ring. */
    private var toggles = LongArray(0)
    private var heads = IntArray(0)
    private var counts = IntArray(0)

    /** True once a blinking spot has been seen; stays true. */
    var passed = false
        private set

    /**
     * One frame. [luma] holds the Y plane: pixel (x, y) is at `y * rowStride + x * pixelStride`.
     * Returns whether the test has passed.
     */
    fun process(luma: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int, timestampNs: Long): Boolean {
        if (passed) return true
        if (width < MIN_BLOCK * 4 || height < MIN_BLOCK * 4) return false
        val block = max(MIN_BLOCK, width / BLOCK_COLUMNS)
        val w = width / block
        val h = height / block
        if (w != bw || h != bh) {
            bw = w; bh = h
            lit = BooleanArray(w * h)
            on = BooleanArray(w * h)
            toggles = LongArray(w * h * TOGGLES)
            heads = IntArray(w * h)
            counts = IntArray(w * h)
        }

        var litBlocks = 0
        for (by in 0 until h) {
            for (bx in 0 until w) {
                var over = 0
                scan@ for (dy in 0 until block) {
                    val row = (by * block + dy) * rowStride
                    for (dx in 0 until block) {
                        val idx = row + (bx * block + dx) * pixelStride
                        if (idx >= luma.size) continue
                        if ((luma[idx].toInt() and 0xFF) > THRESHOLD && ++over >= MIN_LIT_PIXELS) break@scan
                    }
                }
                val l = over >= MIN_LIT_PIXELS
                lit[by * w + bx] = l
                if (l) litBlocks++
            }
        }
        // Too much of the frame lit: the scene is changing. Neither counted nor remembered.
        if (litBlocks > max(1, (w * h * MAX_LIT_FRACTION).toInt())) return false

        val nowMs = timestampNs / 1_000_000L
        for (by in 0 until h) {
            for (bx in 0 until w) {
                val i = by * w + bx
                val state = litAround(bx, by)
                if (state == on[i]) continue
                on[i] = state
                val base = i * TOGGLES
                toggles[base + heads[i]] = nowMs
                heads[i] = (heads[i] + 1) % TOGGLES
                if (counts[i] < TOGGLES) counts[i]++
                // With the ring full, the slot the head now points at holds the oldest of the last TOGGLES.
                if (counts[i] == TOGGLES && nowMs - toggles[base + heads[i]] <= WINDOW_MS) {
                    passed = true
                    return true
                }
            }
        }
        return false
    }

    /** Whether the block at (bx, by) or one of its eight neighbours is lit. */
    private fun litAround(bx: Int, by: Int): Boolean {
        for (y in max(0, by - 1)..min(bh - 1, by + 1)) {
            for (x in max(0, bx - 1)..min(bw - 1, bx + 1)) {
                if (lit[y * bw + x]) return true
            }
        }
        return false
    }
}
