package app.clairix.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.clairix.NeighborCell
import app.clairix.RadioInfo
import app.clairix.Rat
import app.clairix.ServingCell
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Reads the serving cell your baseband is actually camped on, natively.
 *
 * A fake tower does not announce itself in the spectrum in any way software can
 * tell from a real one — but it *does* change what your modem reports: which
 * cell serves you, on what technology, how loud, and with how many neighbours.
 * Android exposes exactly those four through TelephonyManager, no root and no
 * adb required, so watching them over time is how catchers stand out.
 *
 * Beyond the four the heuristics need, [sampleFull] also decodes what the Cell tab
 * shows a person: the band and frequency the cell transmits on, its physical cell
 * id, the LTE/NR quality figures, the platform's own bar count, and every
 * neighbouring cell the modem can hear.
 */
class CellMonitor(private val context: Context) {

    private val tm: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /** Read for the audio mode only: whether a call is up. Needs no permission. */
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    init {
        CallModeWatch.ensure(context)
    }

    /** The serving cell together with the neighbours heard at the same time. */
    data class Snapshot(val cell: ServingCell, val neighbors: List<NeighborCell>)

    fun unavailableReason(): String? {
        if (tm == null) return "No telephony service on this device"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return "Location permission needed to read cell identity"
        // The system-wide location switch gates cell identity just like the runtime
        // permission does: with it off, getAllCellInfo() comes back empty with no
        // explanation, and the tab used to blame the modem.
        if (!locationEnabled()) return "Location is turned off — enable it in Settings to read cell identity"
        // FEATURE_TELEPHONY, not getPhoneType(): LTE tablets and data-only handsets
        // report PHONE_TYPE_NONE while camped on a live cell with a working SIM.
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return "No cellular radio (Wi-Fi-only device)"
        }
        if (tm.simState == TelephonyManager.SIM_STATE_ABSENT) return "No SIM card — the modem is not registered on any network"
        return null
    }

    /** The system location master switch; false also when the service is missing. */
    private fun locationEnabled(): Boolean =
        runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }
            .getOrNull() ?: false

    /**
     * The manager to read cells from. On a dual-SIM device `getAllCellInfo()` returns
     * one registered cell per subscription in no guaranteed order, so a reading taken
     * from the default manager could alternate between SIMs from one poll to the next
     * and look like rapid cell switching. Scoping to the default data subscription
     * pins every poll to the same SIM, the one [radioInfo] describes.
     */
    private fun dataManager(base: TelephonyManager): TelephonyManager {
        val subId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base
        return runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
    }

    /** The serving cell right now, or null if it cannot be read. */
    suspend fun sample(): ServingCell? = sampleFull()?.cell

    /**
     * The serving cell and its neighbours right now, or null if it cannot be read.
     *
     * Since Android 10 `getAllCellInfo()` hands back whatever the platform last
     * cached, which on an idle handset can be minutes old or empty — the Cell tab
     * would sit on a stale tower and none of the timing-based heuristics would ever
     * see a change. So this asks the modem for a fresh reading and only falls back to
     * the cache when the request times out or the modem refuses.
     */
    suspend fun sampleFull(): Snapshot? {
        val base = tm ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        // With the location switch off the platform hands back an empty list; the
        // callers ask unavailableReason() first and show why, so nothing to read here.
        if (!locationEnabled()) return null

        val manager = dataManager(base)
        val fresh = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { requestFresh(manager) }
        val all = fresh?.takeIf { it.isNotEmpty() }
            ?: try { manager.allCellInfo } catch (_: SecurityException) { null }
            ?: return null
        if (all.isEmpty()) return null

        // requestCellInfoUpdate answers for the one subscription; the getAllCellInfo
        // fallback is documented to return the cells of every radio, so on a dual-SIM
        // phone it can hold two registered cells. Take the one on the data SIM's
        // network rather than whichever the platform listed first, which alternated
        // between SIMs from poll to poll and read as rapid cell switching.
        val registeredAll = all.filter { it.isRegistered }
        if (registeredAll.isEmpty()) return null
        val dataPlmn = runCatching { manager.networkOperator }.getOrNull()
        val registered = registeredAll[pickForPlmn(registeredAll.map { plmnOf(it) }, dataPlmn)]
        val others = all.filter { !it.isRegistered }
        val cell = build(registered, others.size)?.copy(inCall = inCall()) ?: return null
        val neighbors = others.mapNotNull { neighbor(it) }
            .sortedByDescending { it.signalDbm ?: Int.MIN_VALUE }
        return Snapshot(cell, neighbors)
    }

    /**
     * Whether a voice call is up or ringing now, or was within the last minute, from
     * the audio mode, which any app may read. A phone without VoLTE is parked on 3G or
     * 2G for the length of a call (circuit-switched fallback), and the catcher
     * heuristics must not read that as a forced downgrade. The minute covers a call
     * that started and ended between two readings, which the mode read at the reading
     * alone would miss; [CallModeWatch] saw it.
     */
    private fun inCall(): Boolean {
        val mode = runCatching { audio?.mode }.getOrNull()
        return callContext(mode, CallModeWatch.active, CallModeWatch.lastCallAt, SystemClock.elapsedRealtime())
    }

    /** MCC+MNC of the network a cell belongs to, as [TelephonyManager.getNetworkOperator] spells it. */
    private fun plmnOf(info: CellInfo): String? {
        val (mcc, mnc) = when (info) {
            is CellInfoLte -> info.cellIdentity.mccString to info.cellIdentity.mncString
            is CellInfoWcdma -> info.cellIdentity.mccString to info.cellIdentity.mncString
            is CellInfoTdscdma -> info.cellIdentity.mccString to info.cellIdentity.mncString
            is CellInfoGsm -> info.cellIdentity.mccString to info.cellIdentity.mncString
            is CellInfoNr -> (info.cellIdentity as? CellIdentityNr)?.let { it.mccString to it.mncString } ?: return null
            else -> return null
        }
        if (mcc == null || mnc == null) return null
        return mcc + mnc
    }

    /**
     * What the radio reports about the registration as a whole. None of it needs a
     * dangerous permission except the data network type, which is read when the
     * platform allows it and left null otherwise. Read from the data subscription's
     * manager, like the cells: on a dual-SIM phone the default manager describes
     * whichever subscription the platform treats as default for voice, which can be
     * the other SIM.
     */
    fun radioInfo(): RadioInfo {
        val manager = dataManager(tm ?: return RadioInfo())
        val networkOperator = manager.networkOperator?.takeIf { it.length >= 5 }
        val simOperator = manager.simOperator?.takeIf { it.length >= 5 }
        val dataType = try {
            networkTypeName(manager.dataNetworkType)
        } catch (_: SecurityException) { null }
        val dataState = try { manager.dataState } catch (_: SecurityException) { TelephonyManager.DATA_UNKNOWN }
        return RadioInfo(
            operatorName = manager.networkOperatorName?.takeIf { it.isNotBlank() },
            plmn = networkOperator?.let { "${it.substring(0, 3)}-${it.substring(3)}" },
            simOperatorName = manager.simOperatorName?.takeIf { it.isNotBlank() },
            simPlmn = simOperator?.let { "${it.substring(0, 3)}-${it.substring(3)}" },
            roaming = runCatching { manager.isNetworkRoaming }.getOrDefault(false),
            dataNetworkType = dataType,
            dataConnected = dataState == TelephonyManager.DATA_CONNECTED,
            simState = simStateName(manager.simState),
            activeSims = runCatching { manager.activeModemCount }.getOrDefault(0)
        )
    }

    private suspend fun requestFresh(manager: TelephonyManager): List<CellInfo>? =
        suspendCancellableCoroutine { cont ->
            try {
                manager.requestCellInfoUpdate(
                    executor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (_: IllegalStateException) {
                // Thrown when the modem is busy or the request is rate limited.
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * Timing advance is reported as [CellInfo.UNAVAILABLE] (Int.MAX_VALUE) by
     * modems that do not expose it, and the valid ranges are 0..219 on GSM and
     * 0..1282 on LTE. Anything outside that is treated as unknown rather than
     * fed into a heuristic as a real zero.
     */
    private fun validTa(ta: Int, max: Int): Int? = ta.takeIf { it in 0..max }

    private fun known(v: Int): Int? = v.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }
    private fun validDbm(dbm: Int): Int? = dbm.takeIf { it > -160 && it < 0 }
    private fun validLevel(s: CellSignalStrength): Int? = s.level.takeIf { it in 0..4 }
    /** Quality figures the modem does not know come back as Int.MAX_VALUE. */
    private fun validDb(v: Int): Int? = v.takeIf { it != CellInfo.UNAVAILABLE && it in -60..60 }

    private fun build(info: CellInfo, neighbors: Int): ServingCell? {
        val now = System.currentTimeMillis()
        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val ci = id.ci
                if (ci == CellInfo.UNAVAILABLE) return null
                val s = info.cellSignalStrength
                val earfcn = known(id.earfcn)
                val bands = id.bands.filter { it > 0 }
                val bandNo = bands.firstOrNull() ?: earfcn?.let { Bands.lteBandFor(it) }
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = known(id.tac)?.toString(),
                    cellId = ci.toString(), rat = Rat.LTE,
                    signalDbm = validDbm(s.dbm),
                    neighbors = neighbors, ts = now,
                    timingAdvance = validTa(s.timingAdvance, 1282),
                    bars = validLevel(s),
                    pci = known(id.pci),
                    arfcn = earfcn,
                    band = bandNo?.let { Bands.lteLabel(it) },
                    bandwidthKhz = known(id.bandwidth)?.takeIf { it > 0 },
                    rsrq = validDb(s.rsrq),
                    sinr = validDb(s.rssnr)
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val cid = id.cid
                if (cid == CellInfo.UNAVAILABLE) return null
                val s = info.cellSignalStrength
                val uarfcn = known(id.uarfcn)
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = known(id.lac)?.toString(),
                    cellId = cid.toString(), rat = Rat.UMTS,
                    signalDbm = validDbm(s.dbm),
                    neighbors = neighbors, ts = now,
                    bars = validLevel(s),
                    pci = known(id.psc),
                    arfcn = uarfcn,
                    band = uarfcn?.let { Bands.umtsLabel(it) }
                )
            }
            is CellInfoTdscdma -> {
                val id = info.cellIdentity
                val cid = id.cid
                if (cid == CellInfo.UNAVAILABLE) return null
                val s = info.cellSignalStrength
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = known(id.lac)?.toString(),
                    cellId = cid.toString(), rat = Rat.UMTS,
                    signalDbm = validDbm(s.dbm),
                    neighbors = neighbors, ts = now,
                    bars = validLevel(s),
                    pci = known(id.cpid),
                    arfcn = known(id.uarfcn),
                    band = "TD-SCDMA"
                )
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val cid = id.cid
                if (cid == CellInfo.UNAVAILABLE) return null
                val s = info.cellSignalStrength
                val arfcn = known(id.arfcn)
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = known(id.lac)?.toString(),
                    cellId = cid.toString(), rat = Rat.GSM,
                    signalDbm = validDbm(s.dbm),
                    neighbors = neighbors, ts = now,
                    timingAdvance = validTa(s.timingAdvance, 219),
                    bars = validLevel(s),
                    pci = known(id.bsic),
                    arfcn = arfcn,
                    band = arfcn?.let { Bands.gsmLabel(it, id.mccString) }
                )
            }
            else -> buildNr(info, neighbors, now)
        }
    }

    private fun buildNr(info: CellInfo, neighbors: Int, now: Long): ServingCell? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info !is CellInfoNr) return null
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val nci = id.nci
        if (nci == Long.MAX_VALUE) return null
        val s = info.cellSignalStrength as? CellSignalStrengthNr
        val nrarfcn = known(id.nrarfcn)
        val bands = id.bands.filter { it > 0 }
        return ServingCell(
            mcc = id.mccString, mnc = id.mncString,
            tac = known(id.tac)?.toString(),
            cellId = nci.toString(), rat = Rat.NR5G,
            signalDbm = s?.dbm?.let { validDbm(it) },
            neighbors = neighbors, ts = now,
            bars = s?.let { validLevel(it) },
            pci = known(id.pci),
            arfcn = nrarfcn,
            band = Bands.nrLabel(bands.firstOrNull(), nrarfcn),
            rsrq = s?.ssRsrq?.let { validDb(it) },
            sinr = s?.ssSinr?.let { validDb(it) }
        )
    }

    /** A neighbour is identified by whatever the modem gives; most give only a PCI. */
    private fun neighbor(info: CellInfo): NeighborCell? = when (info) {
        is CellInfoLte -> {
            val id = info.cellIdentity
            val label = known(id.pci)?.let { "PCI $it" } ?: known(id.ci)?.let { "CI $it" } ?: return null
            val extra = known(id.earfcn)?.let { " · EARFCN $it" } ?: ""
            NeighborCell(Rat.LTE, label + extra, validDbm(info.cellSignalStrength.dbm), validLevel(info.cellSignalStrength))
        }
        is CellInfoWcdma -> {
            val id = info.cellIdentity
            val label = known(id.psc)?.let { "PSC $it" } ?: known(id.cid)?.let { "CID $it" } ?: return null
            NeighborCell(Rat.UMTS, label, validDbm(info.cellSignalStrength.dbm), validLevel(info.cellSignalStrength))
        }
        is CellInfoGsm -> {
            val id = info.cellIdentity
            val label = known(id.cid)?.let { "CID $it" } ?: known(id.bsic)?.let { "BSIC $it" } ?: return null
            val extra = known(id.arfcn)?.let { " · ARFCN $it" } ?: ""
            NeighborCell(Rat.GSM, label + extra, validDbm(info.cellSignalStrength.dbm), validLevel(info.cellSignalStrength))
        }
        is CellInfoNr -> {
            val id = info.cellIdentity as? CellIdentityNr ?: return null
            val label = known(id.pci)?.let { "PCI $it" } ?: return null
            val extra = known(id.nrarfcn)?.let { " · ARFCN $it" } ?: ""
            val s = info.cellSignalStrength
            NeighborCell(Rat.NR5G, label + extra, validDbm(s.dbm), validLevel(s))
        }
        else -> null
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000L

        /**
         * Which of several registered cells to read, by the network each belongs to:
         * the one on the data subscription's registered network ([dataPlmn], MCC+MNC
         * as [TelephonyManager.getNetworkOperator] spells it), else the first. With one
         * registered cell there is nothing to choose.
         */
        internal fun pickForPlmn(plmns: List<String?>, dataPlmn: String?): Int {
            if (plmns.size <= 1) return 0
            val wanted = dataPlmn?.takeIf { it.length >= 5 } ?: return 0
            val i = plmns.indexOfFirst { it == wanted }
            return if (i >= 0) i else 0
        }

        /** How long after a call a reading still counts as taken in one; longer than the slowest poll. */
        internal const val RECENT_CALL_MS = 60_000L

        /** An audio mode that means a call is up or ringing, cellular or VoIP. */
        internal fun isCallMode(mode: Int): Boolean = when (mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT)
        }

        /**
         * A reading is taken in a call when the audio mode says so now ([modeNow], null
         * when unreadable), when the mode watcher has a call [active], or when the last
         * call ended less than [RECENT_CALL_MS] before [now] (both on the elapsed-time
         * clock).
         */
        internal fun callContext(modeNow: Int?, active: Boolean, lastCallAt: Long, now: Long): Boolean =
            (modeNow != null && isCallMode(modeNow)) || active ||
                (lastCallAt != 0L && now - lastCallAt in 0..RECENT_CALL_MS)

        /**
         * The platform delivers cell info on this executor. One shared single thread
         * is enough for a poll every fifteen seconds and keeps the callback off the
         * service's main thread.
         */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cell-info").apply { isDaemon = true }
        }

        fun networkTypeName(type: Int): String? = when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA (2G)"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT (2G)"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN (2G)"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO-A (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO-B (3G)"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD (3G)"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (Wi-Fi calling)"
            TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
            else -> null
        }

        fun simStateName(state: Int): String = when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
            TelephonyManager.SIM_STATE_READY -> "Ready"
            TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently disabled"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card I/O error"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Card restricted"
            else -> "Unknown"
        }
    }
}

/**
 * When a call was last up or ringing, as Android's audio mode reports it. Reading the
 * mode at each cell poll misses a call that starts and ends between two polls (15 to
 * 30 s apart) while the phone is still on 3G at the next one; a listener on the mode
 * sees every call. Needs no permission. Registered once per process on the
 * application context, which lives as long as the process, so it leaks nothing and
 * needs no stopping; it costs nothing until the mode changes. Times are on the
 * elapsed-time clock.
 */
private object CallModeWatch {
    @Volatile var active = false
        private set
    @Volatile var lastCallAt = 0L
        private set
    @Volatile private var registered = false

    fun ensure(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val app = context.applicationContext ?: return
            val am = app.getSystemService(AudioManager::class.java) ?: return
            val added = runCatching { am.addOnModeChangedListener(app.mainExecutor) { mode -> onMode(mode) } }.isSuccess
            if (!added) return
            registered = true
            // A call already up when the scanner starts has no change to report.
            runCatching { am.mode }.getOrNull()?.let { onMode(it) }
        }
    }

    private fun onMode(mode: Int) {
        val inCall = CellMonitor.isCallMode(mode)
        // Entering a call and leaving one are both moments a call was up.
        if (inCall || active) lastCallAt = SystemClock.elapsedRealtime()
        active = inCall
    }
}

/**
 * Band and frequency decoding. The modem gives channel numbers; people recognise
 * "Band 12 · 700 MHz". Coverage here is the bands in commercial use; an unlisted
 * one still shows its number.
 */
object Bands {

    private data class LteBand(val band: Int, val earfcnLow: Int, val earfcnHigh: Int, val mhz: String)

    /** 3GPP TS 36.101 downlink EARFCN ranges for the bands in service. */
    private val LTE = listOf(
        LteBand(1, 0, 599, "2100 MHz"), LteBand(2, 600, 1199, "1900 MHz"),
        LteBand(3, 1200, 1949, "1800 MHz"), LteBand(4, 1950, 2399, "1700/2100 MHz AWS"),
        LteBand(5, 2400, 2649, "850 MHz"), LteBand(7, 2750, 3449, "2600 MHz"),
        LteBand(8, 3450, 3799, "900 MHz"), LteBand(12, 5010, 5179, "700 MHz"),
        LteBand(13, 5180, 5279, "700 MHz"), LteBand(14, 5280, 5379, "700 MHz"),
        LteBand(17, 5730, 5849, "700 MHz"), LteBand(18, 5850, 5999, "850 MHz"),
        LteBand(19, 6000, 6149, "850 MHz"), LteBand(20, 6150, 6449, "800 MHz"),
        LteBand(25, 8040, 8689, "1900 MHz"), LteBand(26, 8690, 9039, "850 MHz"),
        LteBand(28, 9210, 9659, "700 MHz"), LteBand(29, 9660, 9769, "700 MHz"),
        LteBand(30, 9770, 9869, "2300 MHz"), LteBand(32, 9920, 10359, "1500 MHz"),
        LteBand(34, 36200, 36349, "2000 MHz TDD"), LteBand(38, 37750, 38249, "2600 MHz TDD"),
        LteBand(39, 38250, 38649, "1900 MHz TDD"), LteBand(40, 38650, 39649, "2300 MHz TDD"),
        LteBand(41, 39650, 41589, "2500 MHz TDD"), LteBand(42, 41590, 43589, "3500 MHz TDD"),
        LteBand(43, 43590, 45589, "3700 MHz TDD"), LteBand(46, 46790, 54539, "5 GHz LAA"),
        LteBand(48, 55240, 56739, "3500 MHz CBRS"), LteBand(66, 66436, 67335, "1700/2100 MHz AWS-3"),
        LteBand(71, 68586, 68935, "600 MHz")
    )

    private val NR_MHZ = mapOf(
        1 to "2100 MHz", 2 to "1900 MHz", 3 to "1800 MHz", 5 to "850 MHz", 7 to "2600 MHz",
        8 to "900 MHz", 12 to "700 MHz", 14 to "700 MHz", 20 to "800 MHz", 25 to "1900 MHz",
        26 to "850 MHz", 28 to "700 MHz", 29 to "700 MHz", 30 to "2300 MHz", 38 to "2600 MHz",
        40 to "2300 MHz", 41 to "2500 MHz", 48 to "3500 MHz CBRS", 66 to "1700/2100 MHz",
        70 to "2000 MHz", 71 to "600 MHz", 75 to "1500 MHz", 77 to "3700 MHz C-band",
        78 to "3500 MHz C-band", 79 to "4700 MHz", 257 to "28 GHz mmWave", 258 to "26 GHz mmWave",
        260 to "39 GHz mmWave", 261 to "28 GHz mmWave"
    )

    fun lteBandFor(earfcn: Int): Int? = LTE.firstOrNull { earfcn in it.earfcnLow..it.earfcnHigh }?.band

    fun lteLabel(band: Int): String {
        val mhz = LTE.firstOrNull { it.band == band }?.mhz
        return if (mhz != null) "B$band · $mhz" else "B$band"
    }

    /**
     * NR band from the platform when it reports one, otherwise the carrier frequency
     * computed from the NR-ARFCN (TS 38.104 §5.4.2.1), which is exact.
     */
    fun nrLabel(band: Int?, nrarfcn: Int?): String? {
        val mhz = nrarfcn?.let { nrArfcnToMhz(it) }
        return when {
            band != null && band > 0 -> {
                val known = NR_MHZ[band]
                when {
                    mhz != null -> "n$band · ${formatMhz(mhz)}"
                    known != null -> "n$band · $known"
                    else -> "n$band"
                }
            }
            mhz != null -> formatMhz(mhz)
            else -> null
        }
    }

    private fun nrArfcnToMhz(n: Int): Double? = when {
        n < 0 -> null
        n < 600_000 -> n * 0.005
        n < 2_016_667 -> 3000.0 + (n - 600_000) * 0.015
        n <= 3_279_165 -> 24_250.08 + (n - 2_016_667) * 0.06
        else -> null
    }

    private fun formatMhz(mhz: Double): String =
        if (mhz >= 10_000) "%.1f GHz".format(java.util.Locale.US, mhz / 1000.0)
        else "%.0f MHz".format(java.util.Locale.US, mhz)

    /** UTRA FDD downlink UARFCN ranges (TS 25.101). */
    fun umtsLabel(uarfcn: Int): String = when (uarfcn) {
        in 10562..10838 -> "Band 1 · 2100 MHz"
        in 9662..9938 -> "Band 2 · 1900 MHz"
        in 1162..1513 -> "Band 3 · 1800 MHz"
        in 1537..1738 -> "Band 4 · AWS 2100 MHz"
        in 4357..4458 -> "Band 5 · 850 MHz"
        in 4387..4413 -> "Band 6 · 800 MHz"
        in 2937..3088 -> "Band 8 · 900 MHz"
        in 712..763 -> "Band 19 · 800 MHz"
        else -> "UARFCN $uarfcn"
    }

    /**
     * GSM ARFCN → band. 512..810 is DCS 1800 in most of the world and PCS 1900 in
     * the Americas; the MCC decides (3xx is ITU region 3, the Americas).
     */
    fun gsmLabel(arfcn: Int, mcc: String?): String {
        val americas = mcc?.startsWith("3") == true
        return when (arfcn) {
            in 0..124 -> "GSM 900 · %.1f MHz".format(java.util.Locale.US, 935.0 + 0.2 * arfcn)
            in 975..1023 -> "E-GSM 900 · %.1f MHz".format(java.util.Locale.US, 935.0 + 0.2 * (arfcn - 1024))
            in 128..251 -> "GSM 850 · %.1f MHz".format(java.util.Locale.US, 869.2 + 0.2 * (arfcn - 128))
            in 512..810 -> if (americas)
                "PCS 1900 · %.1f MHz".format(java.util.Locale.US, 1930.2 + 0.2 * (arfcn - 512))
            else
                "DCS 1800 · %.1f MHz".format(java.util.Locale.US, 1805.2 + 0.2 * (arfcn - 512))
            in 811..885 -> "DCS 1800 · %.1f MHz".format(java.util.Locale.US, 1805.2 + 0.2 * (arfcn - 512))
            else -> "ARFCN $arfcn"
        }
    }
}
