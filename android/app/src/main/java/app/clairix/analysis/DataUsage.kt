package app.clairix.analysis

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Locale

/**
 * How much each app sent and received over mobile data and Wi-Fi in the last [DAYS] days, read
 * from Android's own traffic counters (NetworkStatsManager). Monitoring apps exist to send what
 * they collect somewhere else: recordings, screenshots, messages, positions. An app that keeps
 * sending more than it receives, day after day, is doing what such an app does; so is a backup
 * app, a cloud photo library or a messenger whose owner sends a lot of videos, which is why this
 * is weighed only for an app that already has other signals ([StalkwareScanner.dataSignal]).
 *
 * What the counters can and cannot say:
 * - Android adds traffic up per app in blocks of an hour or two, so a day here is the 24 hours
 *   up to the moment of the check, give or take a block at each end. It is coarse by design.
 * - The counters are per Linux user id, and apps that share one (rare outside the system) are
 *   counted together.
 * - They say how much, never where to or what. Nothing here reads the traffic itself.
 * - Reading other apps' counters needs usage access (Settings → Usage access), which the suite
 *   already asks for; without it [read] returns null and the scan says nothing about traffic.
 *
 * [accumulate], [Summary] and [text] are plain Kotlin, tested on the JVM; [read] asks the phone.
 */
object DataUsage {

    /** How many 24-hour windows back the scan looks. */
    const val DAYS = 7

    /** A day counts as one the app uploaded on when it sent at least this much (256 KiB). */
    const val UPLOAD_DAY_BYTES = 256L * 1024

    /** Steady: uploads on at least this many of the [DAYS] days. */
    const val STEADY_DAYS = 4

    /** Heavy: at least this much sent over the [DAYS] days (5 MiB). */
    const val HEAVY_BYTES = 5L * 1024 * 1024

    private const val DAY_MS = 24 * 3600_000L

    /** What one app sent ([tx]) and received ([rx]) in one 24-hour window, in bytes. */
    data class Day(val rx: Long, val tx: Long)

    /** One app's last [DAYS] days, oldest first. */
    data class Summary(val days: List<Day>) {
        val rx: Long get() = days.sumOf { it.rx }
        val tx: Long get() = days.sumOf { it.tx }

        /** The days on which it sent at least [UPLOAD_DAY_BYTES]. */
        val uploadDays: Int get() = days.count { it.tx >= UPLOAD_DAY_BYTES }

        /**
         * Sends more than it receives, at least [HEAVY_BYTES] in all, on at least [STEADY_DAYS] of
         * the [DAYS] days: what an app that reports on the phone looks like from its counters. An app
         * installed in the last few days cannot meet this yet.
         */
        val steadyUploader: Boolean get() = tx >= HEAVY_BYTES && tx > rx && uploadDays >= STEADY_DAYS
    }

    /** One count from the phone: [window] days back (0 is the last 24 hours) for app user id [uid]. */
    data class Sample(val window: Int, val uid: Int, val rx: Long, val tx: Long)

    /**
     * The [DAYS] windows to ask for, oldest first, each `[start, end)` in epoch milliseconds,
     * ending at [now].
     */
    fun windows(now: Long): List<Pair<Long, Long>> =
        (DAYS - 1 downTo 0).map { back -> (now - (back + 1) * DAY_MS) to (now - back * DAY_MS) }

    /**
     * Adds [samples] up per app user id: one [Summary] per uid with [DAYS] days, oldest first.
     * A sample outside the windows, or with a negative count (a counter Android reset), is
     * skipped rather than trusted.
     */
    fun accumulate(samples: Sequence<Sample>): Map<Int, Summary> {
        val rx = HashMap<Int, LongArray>()
        val tx = HashMap<Int, LongArray>()
        for (s in samples) {
            if (s.window !in 0 until DAYS || s.rx < 0 || s.tx < 0) continue
            val i = DAYS - 1 - s.window
            rx.getOrPut(s.uid) { LongArray(DAYS) }[i] += s.rx
            tx.getOrPut(s.uid) { LongArray(DAYS) }[i] += s.tx
        }
        return rx.keys.associateWith { uid ->
            val r = rx.getValue(uid)
            val t = tx.getValue(uid)
            Summary(List(DAYS) { Day(r[it], t[it]) })
        }
    }

    /** "sent 42.0 MB and received 3.1 MB in 7 days, sending on 6 of them": no semicolon, so it reads inside a "; " list. */
    fun text(s: Summary): String =
        "sent ${bytes(s.tx)} and received ${bytes(s.rx)} in $DAYS days, sending on ${s.uploadDays} of them"

    /** [n] bytes in words: "812 B", "4.2 kB", "42.0 MB", "1.3 GB" (powers of 1000, as Android's own Settings counts). */
    fun bytes(n: Long): String {
        val units = listOf("kB", "MB", "GB", "TB")
        if (n < 1000) return "$n B"
        var v = n.toDouble()
        var u = -1
        while (v >= 1000 && u < units.size - 1) {
            v /= 1000
            u++
        }
        return String.format(Locale.US, "%.1f %s", v, units[u])
    }

    /** What the card and the evidence say about where the counts come from. */
    const val CAVEAT = "Android's own traffic counters, mobile data and Wi-Fi together, kept in blocks of an hour or two, " +
        "so the days are approximate. They say how much, never to where or what."

    /**
     * Every app's last [DAYS] days of traffic by app user id, mobile data and Wi-Fi together, or
     * null when the phone would not give them (no usage access, or no NetworkStatsManager). Fourteen
     * queries of Android's counters; call off the main thread.
     */
    fun read(context: Context, now: Long = System.currentTimeMillis()): Map<Int, Summary>? {
        val nsm = context.applicationContext.getSystemService(NetworkStatsManager::class.java) ?: return null
        var answered = false
        val samples = ArrayList<Sample>()
        val windows = windows(now)
        @Suppress("DEPRECATION")
        val types = listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)
        for ((i, w) in windows.withIndex()) {
            val back = DAYS - 1 - i
            for (type in types) {
                // A null subscriber id asks for every mobile network, which is all an app without
                // carrier privileges may ask for (API 29+). Each query is tried on its own, so a
                // phone without mobile data still gives its Wi-Fi counts.
                val stats = runCatching { nsm.querySummary(type, null, w.first, w.second) }.getOrNull() ?: continue
                answered = true
                runCatching {
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        samples += Sample(back, b.uid, b.rxBytes, b.txBytes)
                    }
                }
                runCatching { stats.close() }
            }
        }
        return if (answered) accumulate(samples.asSequence()) else null
    }
}
