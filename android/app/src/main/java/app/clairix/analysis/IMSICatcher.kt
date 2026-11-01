package app.clairix.analysis

import app.clairix.CatcherFinding
import app.clairix.Rat
import app.clairix.Severity
import app.clairix.ServingCell
import app.clairix.Store
import app.clairix.Threat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches the serving cell over time and scores each observation against a
 * locally-built baseline. No internet connection, no account, no tower
 * database — just your own history of what "normal" looks like here.
 *
 * Eight heuristics, each with a score weight. The scores accumulate until
 * they cross a Threat threshold. A single indicator is noise; three at once
 * is a problem.
 *
 * Several heuristics describe what travelling normally looks like and only mean
 * something while the phone stands still: RAT downgrade and oscillation, area
 * change, cell flapping, signal spike, and the two ephemeral-cell rules. They run
 * only when the phone is known to be stationary — a fresh fix reporting a low
 * speed, or two fresh fixes close enough in time whose implied speed is low.
 * Without a usable fix (none, a stale one, a first fix with no speed, or a
 * previous fix too old to compare) motion is unknown, unknown is treated as
 * moving, and those rules stay quiet.
 *
 * The rules a call can trip also know about voice calls. A phone without VoLTE is
 * parked on 3G or 2G for every call (circuit-switched fallback) and can take minutes
 * to come back, and a 5G phone without voice over NR is moved to LTE; so a reading
 * taken in a call ([ServingCell.inCall]), or within [CALL_GRACE_MS] of one, is never
 * a downgrade, an oscillation or an area change, and a cell that served for a call is
 * never a "brief" or "ephemeral" tower.
 *
 * Two rules keep per-cell memory in the baseline rather than judging each poll on
 * its own: the tracking area a cell was first seen in is relearned when a carrier
 * re-plans it, and a cell that has read timing advance 0 from where you sit over a
 * whole day stops being news.
 */
class IMSICatcher(
    private val store: Store,
    /** Wall clock; a parameter so the JVM tests can drive time. */
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** [inCall] is the call context of the reading: in a call, or within [CALL_GRACE_MS] of one. */
    private data class HistEntry(val cell: ServingCell, val ts: Long, val inCall: Boolean = false)
    private data class Ephemeral(
        val cell: ServingCell,
        val registeredAt: Long,
        /**
         * When the cell stopped being the serving cell, or null while it still is.
         * The ephemeral-tower heuristics measure a cell's lifetime to here, not to
         * "now": [gone] is only confirmed five minutes after the cell was last seen,
         * so a lifetime measured at that point always read as five minutes or more
         * and neither heuristic could ever fire.
         */
        var vanishedAt: Long? = null,
        var gone: Boolean = false,
        var reported: Boolean = false,
        var reportedLoose: Boolean = false,
        /**
         * Set when the phone was moving, or its motion was unknown, at any reading
         * while this cell was serving or at the reading where it stopped. A cell that
         * serves briefly and is gone is exactly what an ordinary handoff looks like
         * from a moving car, so neither ephemeral-cell heuristic applies to it.
         */
        var movedDuring: Boolean = false,
        /**
         * Set when a reading while this cell was serving, or the reading where it
         * stopped, was taken in a voice call or just after one. Without VoLTE a call
         * parks the phone on a 3G or 2G cell for exactly the length of the call, and a
         * 45-second call then looked like a "very brief serving cell"; a cell the call
         * moved the phone off is explained by the call too.
         */
        var callDuring: Boolean = false
    )

    private val recent = ArrayDeque<HistEntry>()
    private val ephemeral = LinkedHashMap<String, Ephemeral>()

    /**
     * Radio technology ranks seen over the last [RAT_WINDOW_MS] without moving.
     * Emptied whenever the user is found to be travelling: LTE on the motorway says
     * nothing about the 3G in the car park you have just driven into.
     */
    private val ratRanks = ArrayDeque<Pair<Long, Int>>()

    private var prev: ServingCell? = null
    /** The newest distinct fix seen (by fix time). */
    private var prevFix: Fix? = null
    /** The distinct fix before [prevFix], for when a reading repeats the newest one. */
    private var olderFix: Fix? = null
    /** When a reading last found a voice call up, or 0; see [CALL_GRACE_MS]. */
    private var lastCallAt = 0L

    init {
        val data = store.load {
            JSONObject()
                .put("cells", JSONObject())
                .put("tacs", JSONObject())
                .put("cellTacs", JSONObject())
        }
        migrateCellTacs(data)
    }

    /**
     * Earlier versions keyed the cell→area table on mcc-mnc-cellId, with no radio
     * technology, and held the area as a bare string written once and never updated.
     * Under the new key ([ctKey]) those entries can never be read again, so they are
     * dropped rather than left in the baseline forever; the table relearns each cell
     * from its next reading.
     */
    private fun migrateCellTacs(data: JSONObject) {
        val cellTacs = data.optJSONObject("cellTacs") ?: return
        val legacy = ArrayList<String>()
        val keys = cellTacs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (cellTacs.optJSONObject(k) == null) legacy += k
        }
        if (legacy.isEmpty()) return
        for (k in legacy) cellTacs.remove(k)
        store.markDirty()
    }

    private fun tacKey(c: ServingCell) = "${c.mcc ?: "?"}-${c.mnc ?: "?"}-${c.tac ?: "?"}"

    /**
     * The cell→area key carries the radio technology: the `tac` field holds an LTE/NR
     * tracking area code but a 2G/3G location area code, a different number space,
     * and cell numbers are reused across technologies within one network.
     */
    private fun ctKey(c: ServingCell) = "${c.mcc ?: "?"}-${c.mnc ?: "?"}-${c.rat.name}-${c.cellId}"

    /**
     * [settled] is the scanner's word that the phone has gone nowhere for minutes, with
     * a recent fix to show it ([MotionPolicy.confirmedStill]). A settled phone gets a
     * fix every few minutes, not every fifteen seconds, so on its own the newest fix
     * would usually be too old to judge by, and every rule meant for standing still
     * would stay off exactly when the phone is standing still.
     */
    @Synchronized
    fun recordAndAnalyze(cell: ServingCell, fix: Fix?, settled: Boolean = false): List<CatcherFinding> {
        val now = clock()
        pruneRecent(now)

        // Several heuristics only mean anything while you are standing still: crossing
        // cells, swapping radio technology and signal jumps are all what travelling
        // normally looks like. Speed is authoritative when the fix reports it;
        // otherwise fall back to the speed implied by the last two distinct fixes.
        val stationary = settled || isStationary(fix, now)
        if (!stationary) ratRanks.clear()

        // A reading taken in a voice call, or in the minutes after one, says nothing
        // about forced downgrades: without VoLTE the phone drops to 3G or 2G for
        // every call and can take a while to find its way back to LTE.
        if (cell.inCall) lastCallAt = now
        val callContext = cell.inCall || (lastCallAt != 0L && now - lastCallAt < CALL_GRACE_MS)

        updateEphemeral(cell, now, stationary, callContext)
        recent.addLast(HistEntry(cell, now, callContext))

        val findings = runChecks(cell, fix, now, stationary, callContext)
        // Recorded after the checks so the downgrade test compares against what was
        // seen before this reading, not the reading itself.
        ratRanks.addLast(now to cell.rat.rank)
        updateBaseline(cell, now)

        prev = cell
        rememberFix(fix)
        return findings
    }

    /** Keeps the last two *distinct* fixes; a poll that sees the same fix again changes nothing. */
    private fun rememberFix(fix: Fix?) {
        if (fix == null) return
        val newest = prevFix
        if (newest == null || fix.time > newest.time) {
            olderFix = newest
            prevFix = fix
        }
    }

    private fun isStationary(fix: Fix?, now: Long): Boolean {
        // LocationTrack.current is never expired, so when GPS stops (subway, indoors)
        // the last fix lingers with zero speed and read as "standing still", arming
        // every motion-gated heuristic while the user was in fact travelling. No fix,
        // or one older than FIX_STALE_MS, means movement is unknown — and unknown is
        // not stationary.
        if (fix == null || now - fix.time > FIX_STALE_MS) return false
        val speed = fix.speed
        if (speed != null) return speed < MOVING_SPEED_MS
        // No reported speed: derive one from the previous distinct fix, the way
        // LocationTrack.isMoving() does. This used to call anything under 200 m
        // between fixes "stationary" — with fixes about 15 s apart that is 48 km/h,
        // so a car in town read as standing still and armed every motion-gated rule.
        // When this reading repeats the newest fix, compare against the one before.
        val newest = prevFix
        val previousFix = if (newest != null && fix.time <= newest.time) olderFix else newest
        // No previous fix, or one from long ago that averages a drive and a stop
        // together: the speed is unknown, and unknown is not stationary.
        if (previousFix == null || fix.time - previousFix.time > FIX_STALE_MS) return false
        val dtSeconds = (fix.time - previousFix.time) / 1000.0
        if (dtSeconds <= 0) return false
        return haversine(previousFix, fix) / dtSeconds < MOVING_SPEED_MS
    }

    private fun pruneRecent(now: Long) {
        val cutoff = now - 5 * 60_000L
        while (recent.isNotEmpty() && recent.first().ts < cutoff) recent.removeFirst()
        val ratCutoff = now - RAT_WINDOW_MS
        while (ratRanks.isNotEmpty() && ratRanks.first().first < ratCutoff) ratRanks.removeFirst()
    }

    private fun updateEphemeral(current: ServingCell, now: Long, stationary: Boolean, inCall: Boolean) {
        val activeKeys = recent.map { it.cell.key }.toSet()
        for (e in ephemeral.values) {
            if (e.cell.key == current.key) continue
            // Not serving any more: this is where its lifetime ends. `gone` below is
            // the later confirmation that it stayed away, not the end of the stint.
            // Moving at the reading that ended the stint is a handoff on the move;
            // a call at that reading is the call moving the phone off the cell.
            if (e.vanishedAt == null) {
                e.vanishedAt = now
                if (!stationary) e.movedDuring = true
                if (inCall) e.callDuring = true
            }
            if (!e.gone && !activeKeys.contains(e.cell.key) && now - e.registeredAt > 60_000L) {
                e.gone = true
            }
        }
        // Serving again (or for the first time): the stint is open, so no vanish time.
        val serving = ephemeral.getOrPut(current.key) { Ephemeral(current, now) }
        serving.vanishedAt = null
        if (!stationary) serving.movedDuring = true
        if (inCall) serving.callDuring = true
        if (ephemeral.size > 200) {
            val iter = ephemeral.iterator()
            var n = 0
            while (iter.hasNext() && n < 50) {
                if (iter.next().value.gone) { iter.remove(); n++ }
            }
        }
    }

    private fun runChecks(cell: ServingCell, fix: Fix?, now: Long, stationary: Boolean, inCall: Boolean): List<CatcherFinding> {
        val findings = mutableListOf<CatcherFinding>()
        val data = store.json
        val cells = data.optJSONObject("cells") ?: JSONObject()
        val tacs = data.optJSONObject("tacs") ?: JSONObject()
        val cellTacs = data.optJSONObject("cellTacs") ?: JSONObject()

        val tKey = tacKey(cell)
        val tacRec = tacs.optJSONObject(tKey)
        // Separate visits to this area, not polls. The old "maturity" key counted
        // every 15-second poll, so "5 visits" arrived after 75 s and "20 visits" after
        // five minutes; it is ignored rather than trusted, and areas re-learn.
        val tacVisits = tacRec?.optInt("visits", 0) ?: 0

        // 1 — RAT downgrade: 2G/3G now, LTE or better within the last half hour, no
        // movement in between and no voice call. That is what interception equipment
        // forces. A 5G to LTE fallback is routine — NR coverage is patchy indoors and
        // at the edge of every cell — and used to raise this on its own; so did every
        // circuit-switched call on a SIM without VoLTE, which parks the phone on 3G
        // or 2G for the length of the call.
        //
        // Judged from the in-memory history, not the area record: the stored record
        // is keyed on the LTE tracking area code, but on 2G/3G the modem reports the
        // location area code in that field — a different number — so a 3G cell's
        // record never held any LTE history and this could not fire.
        val recentMaxRank = ratRanks.maxOfOrNull { it.second } ?: 0
        if (stationary && !inCall && cell.rat.rank in 1..Rat.UMTS.rank && recentMaxRank >= Rat.LTE.rank) {
            val was = Rat.entries.firstOrNull { it.rank == recentMaxRank }?.label ?: "rank-$recentMaxRank"
            findings += CatcherFinding(
                id = "rat_downgrade", severity = Severity.HIGH,
                title = "Radio downgrade detected",
                detail = "Currently ${cell.rat.label}, but $was was serving you here within the last " +
                    "${RAT_WINDOW_MS / 60_000L} minutes, you have not moved and no voice call was in progress, " +
                    "so this is not a call falling back to the older network. Forced downgrade is the " +
                    "primary method used to intercept modern devices."
            )
        }

        // 2 — Cell ID / TAC mismatch.
        // Keyed on the radio technology as well as the cell number ([ctKey]), so a 3G
        // cell's location area code is never held against an LTE tracking area code
        // when the two share a number. Only compare real tracking areas against real
        // tracking areas: a modem that reported no TAC on the first sighting used to
        // record "?" and then flag every later reading as a mismatch, permanently, on
        // the strength of its own gap. And a carrier re-plan is accepted: once the
        // cell has reported the new area consistently (see [updateBaseline]) the new
        // area becomes the baseline instead of a permanent alarm.
        val ctRec = cellTacs.optJSONObject(ctKey(cell))
        val knownTac = ctRec?.optString("tac", "")?.takeIf { it.isNotEmpty() && it != "?" }
        if (knownTac != null && cell.tac != null && knownTac != cell.tac) {
            findings += CatcherFinding(
                id = "cellid_tac_mismatch", severity = Severity.HIGH,
                title = "Cell ID in wrong area",
                detail = "Cell ${cell.cellId} (${cell.rat.label}) was previously in area $knownTac; it now claims area ${cell.tac}. " +
                    "Real base stations do not change their tracking area code; a carrier re-plan does, once, and Clairix " +
                    "accepts the new area once the cell has reported it $TAC_RELEARN_POLLS times over an hour or more " +
                    "with no reading of the old one in between."
            )
        }

        // 3 — TAC change while stationary.
        // Only when both readings use the same number space: the `tac` field holds
        // the LTE/NR tracking area code, but on 2G/3G the modem reports the location
        // area code there instead — a different number — so an ordinary LTE → 3G
        // reselection while sitting still read as an "area change" and stacked a
        // MEDIUM on top of the downgrade finding. Same RAT, or both LTE-or-better
        // (LTE and NR share the TAC space), is the comparison that means something.
        // Gated on [stationary] rather than on a distance between the last two fixes,
        // which two copies of one stale fix put at zero. Not in a call either: a 5G
        // phone without voice over NR is moved to LTE for every call, and the LTE
        // tracking area need not carry the 5G one's number.
        val p = prev
        if (p != null && p.tac != null && cell.tac != null && p.tac != cell.tac) {
            val sameTacSpace = p.rat == cell.rat ||
                (p.rat.rank >= Rat.LTE.rank && cell.rat.rank >= Rat.LTE.rank)
            if (stationary && sameTacSpace && !inCall) {
                findings += CatcherFinding(
                    id = "tac_change_stationary", severity = Severity.MEDIUM,
                    title = "Area change while stationary",
                    detail = "Tracking area changed from ${p.tac} to ${cell.tac} without movement. You cross cell area boundaries at speed; this happened while you were still."
                )
            }
        }

        // 4 — Signal outlier
        val maxDbm = tacRec?.optInt("maxDbm", -160) ?: -160
        val dbm = cell.signalDbm
        if (dbm != null && tacVisits >= 5 && dbm > maxDbm + 15) {
            findings += CatcherFinding(
                id = "signal_outlier", severity = Severity.MEDIUM,
                title = "Unusually strong signal",
                detail = "Signal is $dbm dBm — ${dbm - maxDbm} dB above the previous maximum for this area ($maxDbm dBm). A transmitter close beside you looks exactly like this."
            )
        }

        // 5 — Unknown cell at familiar area
        if (tacVisits >= 20) {
            val known = tacRec?.optJSONArray("cells") ?: JSONArray()
            var found = false
            for (i in 0 until known.length()) if (known.optString(i) == cell.cellId) { found = true; break }
            if (!found) {
                findings += CatcherFinding(
                    id = "unknown_cell", severity = Severity.MEDIUM,
                    title = "Unknown cell at familiar location",
                    detail = "Cell ${cell.cellId} has never appeared in area ${cell.tac}, but you have visited this area $tacVisits times. New permanent towers are rare; portable ones are not."
                )
            }
        }

        // 6 — Ephemeral cell.
        // The id carries the cell so two vanished towers stay two findings; the UI
        // lists findings by id and duplicates used to take the Cell tab down.
        // A cell that served while the phone was moving is a handoff, not a tower
        // being driven away, so it is skipped (see [Ephemeral.movedDuring]); so is one
        // that served for a voice call (see [Ephemeral.callDuring]).
        for ((key, e) in ephemeral) {
            if (e.gone && !e.reportedLoose && !e.movedDuring && !e.callDuring && key != cell.key) {
                val vanishedAt = e.vanishedAt ?: continue
                val lifetime = vanishedAt - e.registeredAt
                if (lifetime < 5 * 60_000L) {
                    findings += CatcherFinding(
                        id = "ephemeral_cell:${e.cell.cellId}", severity = Severity.MEDIUM,
                        title = "Ephemeral tower",
                        detail = "Cell ${e.cell.cellId} (${e.cell.rat.label}) appeared as serving cell then vanished within ${lifetime / 1000}s. Portable equipment is driven in and away."
                    )
                    e.reportedLoose = true
                }
            }
        }

        // 7 — Cell flapping while stationary
        if (stationary) {
            val uniqueRecent = recent.map { it.cell.key }.toSet().size
            if (uniqueRecent >= 4) {
                findings += CatcherFinding(
                    id = "cell_flapping", severity = Severity.MEDIUM,
                    title = "Rapid cell switching",
                    detail = "Registered on $uniqueRecent different cells in 5 min while stationary. Normal network handover doesn't look like this; active redirection does."
                )
            }
        }

        // 8 — No neighbours.
        // Plenty of modems never populate neighbouring cells at all, and on those the
        // count is zero forever. Treating that as an indicator pinned a permanent
        // finding on the device, so it only counts once this phone has proved it can
        // report neighbours at least once.
        if (cell.neighbors != null && cell.neighbors == 0 && tacVisits >= 5 &&
            data.optBoolean("neighborsEverSeen", false)
        ) {
            findings += CatcherFinding(
                id = "no_neighbors", severity = Severity.LOW,
                title = "No visible neighbours",
                detail = "Serving cell reports zero neighbours. Real base stations always have overlapping coverage; a lone portable transmitter does not."
            )
        }

        // 9 — Timing advance zero on LTE (within ~78 m of the transmitter).
        // Rooftop sites, in-building antennas and small cells legitimately sit this
        // close, which is why the rule weighs little on its own (28, below the MEDIUM
        // line, and the card says LOW to match) and why it has memory: once a cell has
        // read TA 0 on TA_ZERO_NORMAL_POLLS readings spread over TA_ZERO_NORMAL_SPAN_MS,
        // that is what the cell looks like from here and the rule stays quiet for it
        // instead of re-alarming on every poll at home. The span is what keeps a
        // transmitter parked outside for an afternoon from becoming "normal". The
        // device-wide caveat stands too: a modem that always reports 0 is not
        // reporting at all, so nothing fires until a real non-zero advance has been
        // seen on this phone.
        val ta = cell.timingAdvance
        val cellRec = cells.optJSONObject(cell.key)
        if (ta != null && ta == 0 && cell.rat == Rat.LTE && tacVisits >= 5 &&
            data.optBoolean("taEverNonZero", false) && !taZeroIsNormal(cellRec, now)
        ) {
            val taMax = cellRec?.optInt("taMax", 0) ?: 0
            findings += CatcherFinding(
                id = "timing_advance_zero", severity = Severity.LOW,
                title = "Transmitter within about 78 m (LTE timing advance 0)",
                detail = "The LTE timing advance is 0, so the transmitter serving you is within about 78 m. " +
                    (if (taMax > 0) "This cell has reported an advance of up to $taMax (about ${taMax * 78} m) before. " else "") +
                    "A rooftop site, an in-building antenna or a small cell is this close legitimately, so on its own " +
                    "this weighs little; it matters alongside other indicators. Once this cell has read 0 on " +
                    "$TA_ZERO_NORMAL_POLLS readings spread over a day, it is treated as normal here and no longer listed."
            )
        }

        // 10 — Signal spike between consecutive readings, while stationary.
        // Walking out of a building swings the signal by more than this, so a spike
        // only says something when you have not moved.
        val prevDbm = prev?.signalDbm
        val curDbm = cell.signalDbm
        if (stationary && prevDbm != null && curDbm != null && prev?.key == cell.key) {
            val spike = curDbm - prevDbm
            if (spike >= 20) {
                findings += CatcherFinding(
                    id = "signal_spike", severity = Severity.MEDIUM,
                    title = "Sudden signal spike on same cell",
                    detail = "Signal on ${cell.cellId} jumped by ${spike}dB in one reading cycle. Stationary macro cells have stable signal; a vehicle-borne transmitter moving closer produces sudden spikes."
                )
            }
        }

        // 11 — Rapid RAT oscillation (forced downgrade/upgrade cycle indicates IMSI paging).
        // Only while stationary: a drive across town legitimately walks 5G → LTE → 3G
        // and back, which is not evidence of anything. Readings taken in a voice call,
        // or just after one, are left out: a 5G phone's circuit-switched call walks
        // NR → 3G → LTE inside a minute, which is three technologies and no catcher.
        val recentRats = recent.takeLast(8).filter { !it.inCall }.map { it.cell.rat }.distinct()
        if (stationary && !inCall && recentRats.size >= 3) {
            findings += CatcherFinding(
                id = "rat_oscillation", severity = Severity.HIGH,
                title = "Rapid radio technology switching",
                detail = "Device switched between ${recentRats.joinToString { it.label }} technologies in the last 2 min while stationary. IMSI catchers force devices through technology cycles to capture authentication events."
            )
        }

        // 12 — Cell active for < 90s then replaced (ephemeral, stricter threshold).
        // Same motion and call gates as 6: from a car, ninety seconds on one cell is
        // normal, and so is a 3G cell that carried a 45-second call.
        for ((key, e) in ephemeral) {
            if (e.gone && key != cell.key && !e.reported && !e.movedDuring && !e.callDuring) {
                val vanishedAt = e.vanishedAt ?: continue
                val lifetime = vanishedAt - e.registeredAt
                if (lifetime in 30_000L..90_000L) {
                    findings += CatcherFinding(
                        id = "ephemeral_cell_strict:${e.cell.cellId}", severity = Severity.HIGH,
                        title = "Very brief serving cell (${lifetime / 1000}s)",
                        detail = "Cell ${e.cell.cellId} (${e.cell.rat.label}) served as primary cell for only ${lifetime / 1000}s. Real base stations serve continuously for hours; portable catchers connect briefly then move on."
                    )
                    e.reported = true
                }
            }
        }

        // The Cell tab keys its list on the finding id, so a duplicate id is a crash
        // rather than a cosmetic problem. Guarantee uniqueness here.
        return findings.distinctBy { it.id }
    }

    private fun updateBaseline(cell: ServingCell, now: Long) {
        val data = store.json
        val cells = data.optJSONObject("cells") ?: JSONObject().also { data.put("cells", it) }
        val tacs = data.optJSONObject("tacs") ?: JSONObject().also { data.put("tacs", it) }
        val cellTacs = data.optJSONObject("cellTacs") ?: JSONObject().also { data.put("cellTacs", it) }

        val tKey = tacKey(cell)
        val ctKey = ctKey(cell)

        val cellRec = cells.optJSONObject(cell.key) ?: JSONObject().also { cells.put(cell.key, it) }
        // The day, not the moment: what retention needs to forget a tower nobody
        // passes any more, and no record of when the phone was near it.
        cellRec.put("lastSeen", dayFloor(now))
        cellRec.put("count", cellRec.optInt("count", 0) + 1)
        cellRec.put("rat", cell.rat.name)

        val tacRec = tacs.optJSONObject(tKey) ?: JSONObject().also { tacs.put(tKey, it) }
        // A visit is a first sighting, or a return after more than VISIT_GAP_MS away
        // — and "away" means seen in some *other* area in between. Polls only move
        // lastSeenTs forward, so a long stay is one visit; and a gap with no other
        // area seen is the scanner having been off, not a trip, so stopping and
        // restarting at home no longer counts as coming home again.
        val lastSeenTs = tacRec.optLong("lastSeenTs", 0L)
        val previousTac = data.optString(LAST_TAC_KEY, "")
        if (lastSeenTs == 0L || (previousTac != tKey && now - lastSeenTs > VISIT_GAP_MS)) {
            tacRec.put("visits", tacRec.optInt("visits", 0) + 1)
        }
        tacRec.put("lastSeenTs", now)
        data.put(LAST_TAC_KEY, tKey)
        // Written by earlier versions as a per-poll count; meaningless as a visit count.
        tacRec.remove("maturity")
        if (cell.rat.rank > tacRec.optInt("maxRatRank", 0)) tacRec.put("maxRatRank", cell.rat.rank)
        val dbm = cell.signalDbm
        if (dbm != null && dbm > tacRec.optInt("maxDbm", -160)) tacRec.put("maxDbm", dbm)

        val knownCells = tacRec.optJSONArray("cells") ?: JSONArray().also { tacRec.put("cells", it) }
        var alreadyKnown = false
        for (i in 0 until knownCells.length()) if (knownCells.optString(i) == cell.cellId) { alreadyKnown = true; break }
        if (!alreadyKnown) knownCells.put(cell.cellId)

        // Only remember a tracking area we actually read. Recording "?" for a modem
        // that withheld it turns every later reading into a false mismatch. A
        // different area for a known cell is counted rather than written over: a
        // carrier re-plan reports the new area on every reading from then on, and
        // after TAC_RELEARN_POLLS readings spread over at least TAC_RELEARN_MS it
        // becomes the baseline. A reading with the old area in between starts the
        // count again, which is what a transmitter borrowing the cell's number looks
        // like, and the mismatch finding keeps firing until the relearn lands.
        val tac = cell.tac
        if (tac != null) {
            val rec = cellTacs.optJSONObject(ctKey)
            when {
                rec == null -> cellTacs.put(ctKey, JSONObject().put("tac", tac))
                rec.optString("tac", "") == tac -> {
                    rec.remove("pendingTac"); rec.remove("pendingPolls"); rec.remove("pendingSince")
                }
                else -> {
                    if (rec.optString("pendingTac", "") != tac) {
                        rec.put("pendingTac", tac).put("pendingPolls", 0).put("pendingSince", now)
                    }
                    val polls = rec.optInt("pendingPolls", 0) + 1
                    rec.put("pendingPolls", polls)
                    if (polls >= TAC_RELEARN_POLLS && now - rec.optLong("pendingSince", now) >= TAC_RELEARN_MS) {
                        rec.put("tac", tac)
                        rec.remove("pendingTac"); rec.remove("pendingPolls"); rec.remove("pendingSince")
                    }
                }
            }
        }

        // Per-cell timing-advance memory for rule 9: how often this cell has read
        // TA 0 from here and since when, and the largest advance it has reported.
        val ta = cell.timingAdvance
        if (ta != null && cell.rat == Rat.LTE) {
            if (ta == 0) {
                cellRec.put("taZeroPolls", cellRec.optInt("taZeroPolls", 0) + 1)
                if (!cellRec.has("taZeroFirst")) cellRec.put("taZeroFirst", now)
            }
            if (ta > cellRec.optInt("taMax", 0)) cellRec.put("taMax", ta)
        }

        // Remember whether this handset reports neighbour counts and timing advance at
        // all. The heuristics that key off "zero" are meaningless on a modem that
        // never reports anything else, and have to stay quiet there.
        if ((cell.neighbors ?: 0) > 0) data.put("neighborsEverSeen", true)
        if ((cell.timingAdvance ?: 0) > 0) data.put("taEverNonZero", true)

        // Encrypted and written at most every few minutes rather than on every poll:
        // the scanner flushes it when it stops, so only a killed process loses the
        // last few minutes of counts.
        store.touch(BASELINE_WRITE_MS)
    }

    /** Rule 9's memory: this cell has read TA 0 often enough, over long enough, to be normal here. */
    private fun taZeroIsNormal(cellRec: JSONObject?, now: Long): Boolean {
        if (cellRec == null) return false
        val first = cellRec.optLong("taZeroFirst", 0L)
        return cellRec.optInt("taZeroPolls", 0) >= TA_ZERO_NORMAL_POLLS &&
            first != 0L && now - first >= TA_ZERO_NORMAL_SPAN_MS
    }

    fun scoreAndLevel(findings: List<CatcherFinding>): Pair<Int, Threat> {
        // Ephemeral findings carry the cell in their id to stay unique; score on the
        // heuristic name in front of the colon.
        val score = findings.sumOf {
            when (it.id.substringBefore(':')) {
                "rat_downgrade" -> 35; "cellid_tac_mismatch" -> 30
                "tac_change_stationary" -> 25; "signal_outlier" -> 22
                "unknown_cell" -> 20; "ephemeral_cell" -> 20
                "cell_flapping" -> 18; "no_neighbors" -> 15
                "timing_advance_zero" -> 28; "signal_spike" -> 18
                "rat_oscillation" -> 30; "ephemeral_cell_strict" -> 32
                else -> 10
            }.toInt()
        }.coerceIn(0, 100)
        val level = when {
            score >= 80 -> Threat.CRITICAL; score >= 50 -> Threat.HIGH
            score >= 30 -> Threat.MEDIUM; score > 0 -> Threat.LOW; else -> Threat.NONE
        }
        return score to level
    }

    /**
     * Writes the baseline now. Synchronized on this object, the lock every mutation
     * of the store's JSON happens under, so the snapshot cannot race one; see [Store].
     */
    @Synchronized
    fun flush() {
        store.flush()
    }

    /**
     * The owner's history retention, applied to the baseline ([BaselineRetention]):
     * it keeps which towers and areas are usual here, not when the phone was near
     * them. Returns how many values changed; writes the baseline when any did.
     * Synchronized on this object, the lock every mutation of the store's JSON
     * happens under; see [Store].
     */
    @Synchronized
    fun applyRetention(cutoff: Long?, now: Long = clock()): Int {
        val changed = BaselineRetention.apply(store.json, cutoff, now)
        if (changed > 0) {
            store.markDirty()
            store.flush()
        }
        return changed
    }

    /** Wipes the learned baseline and the in-memory history behind it. */
    @Synchronized
    fun resetBaseline() {
        recent.clear()
        ephemeral.clear()
        ratRanks.clear()
        prev = null
        prevFix = null
        olderFix = null
        lastCallAt = 0L
        // The owner's clear: the file goes even if this run could not read it.
        store.discardUnread()
        store.replace(
            JSONObject()
                .put("cells", JSONObject())
                .put("tacs", JSONObject())
                .put("cellTacs", JSONObject())
        )
    }

    private companion object {
        /** The baseline is written at most this often while scanning; see [updateBaseline]. */
        const val BASELINE_WRITE_MS = 5 * 60_000L
        /** Time away from an area after which being back there counts as a new visit. */
        const val VISIT_GAP_MS = 30 * 60_000L
        /** How long a better radio technology counts as "available here" while still. */
        const val RAT_WINDOW_MS = 30 * 60_000L
        /** A GPS fix older than this says nothing about whether the user is moving now. */
        const val FIX_STALE_MS = 90_000L
        /** Store key for the last tracking area observed, so "away" survives restarts. */
        const val LAST_TAC_KEY = "lastTac"
        /** Visits across all areas at which the baseline bar reads full. */
        const val MATURE_VISITS = 10
        /**
         * Readings this long after a voice call still count as call context: after a
         * circuit-switched call some networks leave the phone on 3G until the next
         * idle reselection, which can take minutes.
         */
        const val CALL_GRACE_MS = 5 * 60_000L
        /**
         * Readings of a different tracking area for a known cell, spread over at least
         * [TAC_RELEARN_MS], after which it is accepted as the cell's area (a carrier
         * re-plan). A transmitter borrowing a cell's number while the real cell is still
         * heard never gets there; a re-plan is permanent and gets there after about an
         * hour of readings.
         */
        const val TAC_RELEARN_POLLS = 40
        const val TAC_RELEARN_MS = 60 * 60_000L
        /**
         * Readings of timing advance 0 at one cell, spread over at least
         * [TA_ZERO_NORMAL_SPAN_MS], after which TA 0 is normal there.
         */
        const val TA_ZERO_NORMAL_POLLS = 20
        const val TA_ZERO_NORMAL_SPAN_MS = 24 * 60 * 60_000L
    }

    /**
     * (known cells, visits across all areas, maturity 0..1). Maturity used to be
     * derived from per-cell poll counts — 50 polls, about twelve minutes — which
     * read "Mature" long before any visit-gated heuristic could fire. It is now
     * visits, the unit those heuristics are gated on.
     */
    @Synchronized
    fun stats(): Triple<Int, Int, Float> {
        val data = store.json
        val uniqueCells = data.optJSONObject("cells")?.length() ?: 0
        var totalVisits = 0
        data.optJSONObject("tacs")?.let { tacs ->
            val keys = tacs.keys()
            while (keys.hasNext()) totalVisits += tacs.optJSONObject(keys.next())?.optInt("visits", 0) ?: 0
        }
        val maturity = (totalVisits.coerceIn(0, MATURE_VISITS) / MATURE_VISITS.toFloat())
        return Triple(uniqueCells, totalVisits, maturity)
    }
}

/** Midnight UTC at or before [ts]. */
internal fun dayFloor(ts: Long): Long = ts - Math.floorMod(ts, BaselineRetention.DAY_MS)

/**
 * What the owner's history retention means for the cell-tower baseline. The
 * rules need which towers and areas are usual and how often each was visited,
 * not when: the baseline is what "normal" is measured against, so it is not
 * deleted with the history, but it must not be a dated log of every area the
 * phone has been in either.
 *
 *  - A tower's last sighting is kept to the day (see [IMSICatcher.updateBaseline]).
 *  - The exact times the rules still measure against (the 30-minute visit gap,
 *    the hour a carrier re-plan takes to be accepted, the day a tower must read
 *    timing advance 0 to count as normal) are kept only while one can matter,
 *    [EXACT_KEEP_MS], then rounded down to the day, which changes no rule's
 *    answer: every threshold is a day or less.
 *  - A tower or area not seen for [STALE_PERIODS] retention periods is no
 *    longer "normal" here and is forgotten, with the tower's place in its
 *    area's list and its area mapping. Kept for ever only when history is.
 *  - What earlier versions wrote and nothing reads (when each tower was first
 *    seen, when each tower's area was learned) is dropped.
 */
internal object BaselineRetention {

    const val DAY_MS = 24 * 60 * 60_000L
    const val EXACT_KEEP_MS = 2 * DAY_MS
    const val STALE_PERIODS = 3

    /**
     * Applies the retention to the baseline document [data] in place, with
     * [cutoff] the oldest moment history keeps (null: kept for ever). Returns
     * how many values changed.
     */
    fun apply(data: JSONObject, cutoff: Long?, now: Long): Int {
        var changed = 0
        val exactBefore = now - EXACT_KEEP_MS
        val forgetBefore = cutoff?.let { now - STALE_PERIODS * (now - it) }
        fun toDay(rec: JSONObject, field: String) {
            if (!rec.has(field)) return
            val t = rec.optLong(field, 0L)
            val day = dayFloor(t)
            if (t != 0L && day != t) {
                rec.put(field, day)
                changed++
            }
        }
        fun stale(t: Long) = forgetBefore != null && t != 0L && t < forgetBefore

        val forgotten = HashSet<String>()
        data.optJSONObject("cells")?.let { cells ->
            for (key in cells.keys().asSequence().toList()) {
                val rec = cells.optJSONObject(key) ?: continue
                if (rec.remove("firstSeen") != null) changed++
                toDay(rec, "lastSeen")
                if (rec.optLong("taZeroFirst", 0L) in 1L until exactBefore) toDay(rec, "taZeroFirst")
                if (stale(rec.optLong("lastSeen", 0L))) {
                    cells.remove(key)
                    forgotten += key
                    changed++
                }
            }
        }
        data.optJSONObject("tacs")?.let { tacs ->
            for (key in tacs.keys().asSequence().toList()) {
                val rec = tacs.optJSONObject(key) ?: continue
                if (rec.optLong("lastSeenTs", 0L) in 1L until exactBefore) toDay(rec, "lastSeenTs")
                if (stale(rec.optLong("lastSeenTs", 0L))) {
                    tacs.remove(key)
                    changed++
                }
            }
            // A forgotten tower (mcc-mnc-area-cell) leaves its area's list of towers.
            for (cellKey in forgotten) {
                val parts = cellKey.split("-", limit = 4)
                if (parts.size != 4) continue
                val area = tacs.optJSONObject("${parts[0]}-${parts[1]}-${parts[2]}") ?: continue
                val list = area.optJSONArray("cells") ?: continue
                val kept = JSONArray()
                for (i in 0 until list.length()) list.optString(i).let { if (it != parts[3]) kept.put(it) }
                if (kept.length() != list.length()) {
                    area.put("cells", kept)
                    changed++
                }
            }
        }
        data.optJSONObject("cellTacs")?.let { cellTacs ->
            for (key in cellTacs.keys().asSequence().toList()) {
                val rec = cellTacs.optJSONObject(key) ?: continue
                if (rec.remove("since") != null) changed++
                if (rec.optLong("pendingSince", 0L) in 1L until exactBefore) toDay(rec, "pendingSince")
                // Keyed mcc-mnc-technology-cell; the tower it maps is mcc-mnc-<its area>-cell.
                val parts = key.split("-", limit = 4)
                if (parts.size == 4 && "${parts[0]}-${parts[1]}-${rec.optString("tac")}-${parts[3]}" in forgotten) {
                    cellTacs.remove(key)
                    changed++
                }
            }
        }
        return changed
    }
}
