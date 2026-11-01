package app.clairix.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * What this phone's infrared self-test has shown: when its front camera last
 * showed a TV remote's flashing, and when a check last saw nothing. Until it has
 * passed once, an empty IR result says nothing about the room.
 */
data class IrCheck(val passedTs: Long = 0L, val failedTs: Long = 0L) {
    /** The front camera has shown infrared at least once. */
    val seen: Boolean get() = passedTs > 0L
}

/**
 * The hidden-camera sweep as it is kept on disk, in the app's encrypted store:
 * the findings, the checklist ticks, when any method last ran, the last network
 * sweep's result and the infrared self-test. A process that dies mid-sweep, or a
 * phone that restarts, comes back to the same screen; Clear and "Clear all data"
 * write the emptied record over it.
 *
 * Plain JSON in and out, so it can be tested.
 */
data class CameraSweepRecord(
    val findings: List<CameraFinding> = emptyList(),
    val done: Set<String> = emptySet(),
    val lastRunTs: Long = 0L,
    /** The last network sweep's result; only its result fields are kept, never a running state. */
    val network: NetworkScanProgress = NetworkScanProgress(),
    val ir: IrCheck = IrCheck()
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("findings", JSONArray().apply { for (f in findings) put(findingJson(f)) })
        put("done", JSONArray().apply { for (d in done.sorted()) put(d) })
        put("lastRun", lastRunTs)
        if (network.lastScanTs > 0L) put("network", JSONObject().apply {
            put("ts", network.lastScanTs)
            network.subnet?.let { put("subnet", it) }
            network.ssid?.let { put("ssid", it) }
            put("live", network.liveHosts)
            put("found", network.found)
            network.inconclusiveReason?.let { put("inconclusive", it) }
        })
        put("ir", JSONObject().apply {
            put("passed", ir.passedTs)
            put("failed", ir.failedTs)
        })
    }

    companion object {
        const val VERSION = 1

        /** Reads a record; anything missing or unreadable comes back empty rather than failing the whole sweep. */
        fun fromJson(o: JSONObject): CameraSweepRecord {
            val findings = ArrayList<CameraFinding>()
            o.optJSONArray("findings")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { findingFrom(it) }?.let { findings.add(it) }
            }
            val done = LinkedHashSet<String>()
            o.optJSONArray("done")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { done.add(it) }
            }
            val net = o.optJSONObject("network")
            val network = if (net == null) NetworkScanProgress() else NetworkScanProgress(
                lastScanTs = net.optLong("ts"),
                subnet = net.optStringOrNull("subnet"),
                ssid = net.optStringOrNull("ssid"),
                liveHosts = net.optInt("live"),
                found = net.optInt("found"),
                inconclusiveReason = net.optStringOrNull("inconclusive")
            )
            val ir = o.optJSONObject("ir")
            return CameraSweepRecord(
                findings = findings,
                done = done,
                lastRunTs = o.optLong("lastRun"),
                network = network,
                ir = IrCheck(passedTs = ir?.optLong("passed") ?: 0L, failedTs = ir?.optLong("failed") ?: 0L)
            )
        }

        private fun findingJson(f: CameraFinding) = JSONObject().apply {
            put("id", f.id)
            put("ts", f.ts)
            put("method", f.method.name)
            put("confidence", f.confidence.toDouble())
            put("location", f.location)
            put("detail", f.detail)
            if (f.lat != null) put("lat", f.lat)
            if (f.lon != null) put("lon", f.lon)
            if (f.needsConfirmation) put("confirm", true)
            if (f.photo != null) put("photo", f.photo)
        }

        private fun findingFrom(o: JSONObject): CameraFinding? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val method = runCatching { CameraFindMethod.valueOf(o.optString("method")) }.getOrNull() ?: return null
            return CameraFinding(
                id = id,
                ts = o.optLong("ts"),
                method = method,
                confidence = o.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                location = o.optString("location"),
                detail = o.optString("detail"),
                lat = if (o.has("lat")) o.optDouble("lat") else null,
                lon = if (o.has("lon")) o.optDouble("lon") else null,
                needsConfirmation = o.optBoolean("confirm", false),
                photo = o.optStringOrNull("photo")
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

        /**
         * Puts back findings from disk under those already made in this process: an
         * id found in both keeps the one in memory (it is newer). Newest first, at
         * most [cap], and anything from before [cutoff] (the history retention) dropped.
         */
        fun merge(current: List<CameraFinding>, saved: List<CameraFinding>, cutoff: Long?, cap: Int): List<CameraFinding> {
            val ids = current.mapTo(HashSet()) { it.id }
            return (current + saved.filter { it.id !in ids })
                .filter { cutoff == null || it.ts >= cutoff }
                .sortedByDescending { it.ts }
                .take(cap)
        }
    }
}
