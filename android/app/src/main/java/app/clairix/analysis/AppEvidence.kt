package app.clairix.analysis

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.TimelineEvent
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What "Save evidence now" records about an app before its settings page is
 * opened, where it can be switched off or uninstalled: who installed it and
 * when, the access it holds at this moment, the reasons the stalkerware scan
 * flagged it, the SHA-256 of the certificate it is signed with (which names
 * the build even if the app is renamed or reinstalled) and when this was saved.
 * The record goes on the timeline, and so into the evidence report, and onto
 * the clipboard.
 *
 * [collect] reads the phone; everything else is plain formatting, tested on the JVM.
 */
data class AppEvidence(
    val packageName: String,
    val label: String,
    /** The stalkerware scan's verdict, when it flagged the app. */
    val severity: Severity?,
    val score: Int?,
    /** The app that ran the install, as the system recorded it; null over adb or root. */
    val installer: String?,
    /** The installer the app is attributed to, when it differs from [installer]. */
    val namedInstaller: String?,
    val installedAt: Long?,
    val updatedAt: Long?,
    /** Special access held when this was saved: accessibility, notification access, device admin and the like. */
    val access: List<String>,
    /** Runtime permissions granted when this was saved, in plain words. */
    val permissions: List<String>,
    val reasons: List<String>,
    /** SHA-256 of each certificate the app is signed with now, as colon-separated hex. */
    val signers: List<String>,
    val savedAt: Long,
    /**
     * Whether Android exempted it from battery optimisation when this was saved, so nothing paused
     * it in the background; null when the phone would not say. Recorded on its own, never as a
     * verdict: many ordinary apps are exempt.
     */
    val batteryExempt: Boolean? = null,
    /**
     * Sensitive runtime permissions the app asks for that were not granted when this was saved:
     * what it wants, apart from what it can do ([permissions]).
     */
    val requestedOnly: List<String> = emptyList()
) {

    /** The timeline entry: one line of detail, which is how the evidence report prints it. */
    fun timelineEvent(zone: TimeZone = TimeZone.getDefault()): TimelineEvent = TimelineEvent(
        id = "$ID_PREFIX$packageName@$savedAt",
        kind = EventKind.EVIDENCE,
        ts = savedAt,
        title = "Evidence saved: $label",
        detail = fields(zone).joinToString("; ") { (k, v) -> "$k: $v" },
        severity = severity ?: Severity.LOW
    )

    /** The clipboard copy: one field to a line. */
    fun text(zone: TimeZone = TimeZone.getDefault()): String =
        "Clairix evidence: an app on this phone\n" + fields(zone).joinToString("\n") { (k, v) -> "$k: $v" }

    private fun fields(zone: TimeZone): List<Pair<String, String>> = buildList {
        add("App" to "$label ($packageName)")
        if (severity != null) add("Flagged" to (severity.name + (score?.let { ", score $it" } ?: "")))
        add("Installed" to (installedAt?.let { stamp(it, zone) } ?: "unknown"))
        if (updatedAt != null && updatedAt != installedAt) add("Last updated" to stamp(updatedAt, zone))
        add("Installed by" to (installer ?: "none recorded (adb, root or an unknown source)"))
        if (namedInstaller != null && namedInstaller != installer) add("Installer it names" to namedInstaller)
        add("Access" to access.ifEmpty { listOf("none of the special access Clairix checks") }.joinToString(", "))
        add("Permissions granted" to permissions.ifEmpty { listOf("none of the sensitive ones Clairix checks") }.joinToString(", "))
        if (requestedOnly.isNotEmpty()) add("Asked for, not granted" to requestedOnly.joinToString(", "))
        add("Battery optimisation" to batteryText(batteryExempt))
        if (reasons.isNotEmpty()) add("Reasons" to reasons.joinToString("; "))
        add("Signing certificate SHA-256" to signers.ifEmpty { listOf("not readable") }.joinToString(", "))
        add("Saved" to stamp(savedAt, zone))
    }

    companion object {

        /** Every timeline id this writes starts with this. */
        const val ID_PREFIX = "evidence_app:"

        /**
         * The runtime permissions worth naming in evidence, in plain words: the
         * ones a monitoring app needs to read messages, calls, contacts and
         * files, and to locate, listen or watch.
         */
        internal val PERMISSION_NAMES: Map<String, String> = linkedMapOf(
            "android.permission.ACCESS_FINE_LOCATION" to "precise location",
            "android.permission.ACCESS_COARSE_LOCATION" to "approximate location",
            "android.permission.ACCESS_BACKGROUND_LOCATION" to "location all the time",
            "android.permission.RECORD_AUDIO" to "microphone",
            "android.permission.CAMERA" to "camera",
            "android.permission.READ_SMS" to "read text messages",
            "android.permission.RECEIVE_SMS" to "receive text messages",
            "android.permission.SEND_SMS" to "send text messages",
            "android.permission.RECEIVE_MMS" to "receive picture messages",
            "android.permission.READ_CALL_LOG" to "read the call log",
            "android.permission.WRITE_CALL_LOG" to "change the call log",
            "android.permission.PROCESS_OUTGOING_CALLS" to "see outgoing calls",
            "android.permission.READ_PHONE_STATE" to "phone status and identity",
            "android.permission.READ_PHONE_NUMBERS" to "phone numbers",
            "android.permission.CALL_PHONE" to "make calls",
            "android.permission.ANSWER_PHONE_CALLS" to "answer calls",
            "android.permission.READ_CONTACTS" to "read contacts",
            "android.permission.GET_ACCOUNTS" to "see accounts",
            "android.permission.READ_CALENDAR" to "read the calendar",
            "android.permission.READ_EXTERNAL_STORAGE" to "read files",
            "android.permission.READ_MEDIA_IMAGES" to "photos",
            "android.permission.READ_MEDIA_VIDEO" to "videos",
            "android.permission.READ_MEDIA_AUDIO" to "audio files",
            "android.permission.BODY_SENSORS" to "body sensors",
            "android.permission.ACTIVITY_RECOGNITION" to "physical activity"
        )

        /** What the evidence says about the battery exemption. */
        internal fun batteryText(exempt: Boolean?): String = when (exempt) {
            true -> "exempt (Android never pauses it in the background)"
            false -> "not exempt"
            null -> "not readable"
        }

        /** PackageInfo.REQUESTED_PERMISSION_GRANTED, spelled out so the JVM tests need no Android stub. */
        private const val GRANTED = 2

        /**
         * The members of [PERMISSION_NAMES] that [requested] holds granted, by
         * [flags] (PackageInfo.requestedPermissionsFlags), in the table's order.
         */
        internal fun grantedPermissions(requested: Array<String>?, flags: IntArray?): List<String> {
            if (requested == null || flags == null) return emptyList()
            val granted = requested.indices
                .filter { it < flags.size && (flags[it] and GRANTED) != 0 }
                .mapTo(HashSet()) { requested[it] }
            return PERMISSION_NAMES.filterKeys { it in granted }.values.toList()
        }

        /**
         * The members of [PERMISSION_NAMES] that [requested] holds but [flags] do not mark granted,
         * in the table's order; empty when the flags were not read.
         */
        internal fun requestedOnlyPermissions(requested: Array<String>?, flags: IntArray?): List<String> {
            if (requested == null || flags == null) return emptyList()
            val asked = requested.indices
                .filter { it >= flags.size || (flags[it] and GRANTED) == 0 }
                .mapTo(HashSet()) { requested[it] }
            return PERMISSION_NAMES.filterKeys { it in asked }.values.toList()
        }

        /** SHA-256 of [bytes] as upper-case hex pairs joined by colons, the way certificate fingerprints are printed. */
        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

        /** "2026-11-01 14:02:11 UTC (15:02:11 CET on this phone)": UTC first, so reports from anywhere line up. */
        internal fun stamp(ts: Long, zone: TimeZone): String {
            val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).apply { timeZone = zone }
            return "${utc.format(Date(ts))} (${local.format(Date(ts))} on this phone)"
        }

        /**
         * Reads what the phone says about [packageName] now, adding the scan's
         * verdict and reasons from [flagged]. Each part is read on its own, so
         * an app uninstalled meanwhile still yields its name, the reasons and the
         * time. PackageManager and Settings reads; call off the main thread.
         */
        fun collect(
            context: Context,
            packageName: String,
            flagged: StalkwareScanner.SuspiciousApp?,
            now: Long = System.currentTimeMillis()
        ): AppEvidence {
            val app = context.applicationContext
            val pm = app.packageManager
            val info = runCatching { packageInfo(pm, packageName) }.getOrNull()
            val source = runCatching { pm.getInstallSourceInfo(packageName) }.getOrNull()
            val installer = source?.initiatingPackageName
            return AppEvidence(
                packageName = packageName,
                label = flagged?.appLabel ?: StalkwareScanner.appLabel(app, packageName),
                severity = flagged?.severity,
                score = flagged?.score,
                installer = installer,
                namedInstaller = source?.installingPackageName?.takeIf { it != installer },
                installedAt = info?.firstInstallTime ?: flagged?.installTs,
                updatedAt = info?.lastUpdateTime ?: flagged?.lastUpdateTs,
                access = runCatching { access(app, packageName, info, flagged) }.getOrDefault(emptyList()),
                permissions = grantedPermissions(info?.requestedPermissions, info?.requestedPermissionsFlags),
                requestedOnly = requestedOnlyPermissions(info?.requestedPermissions, info?.requestedPermissionsFlags),
                reasons = flagged?.reasons.orEmpty(),
                signers = info?.let { runCatching { signers(it) }.getOrNull() }.orEmpty(),
                savedAt = now,
                batteryExempt = runCatching {
                    app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)
                }.getOrNull()
            )
        }

        private fun packageInfo(pm: PackageManager, name: String): PackageInfo {
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(name, flags)
            }
        }

        /**
         * The certificates the app is signed with now. With one signer, the last
         * entry of the rotation history is the current key; with several, every
         * one of them signed the APK.
         */
        private fun signers(info: PackageInfo): List<String> {
            val si = info.signingInfo ?: return emptyList()
            val certs = if (si.hasMultipleSigners()) {
                si.apkContentsSigners?.toList().orEmpty()
            } else {
                listOfNotNull(si.signingCertificateHistory?.lastOrNull())
            }
            return certs.map { sha256(it.toByteArray()) }
        }

        /** The special access [packageName] holds at this moment, read fresh rather than from the last scan. */
        private fun access(context: Context, packageName: String, info: PackageInfo?, flagged: StalkwareScanner.SuspiciousApp?): List<String> {
            val out = ArrayList<String>()
            if ((enabledIn(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, packageName) ?: flagged?.accessibilityEnabled) == true) {
                out += "accessibility service on (reads the screen)"
            }
            if ((enabledIn(context, "enabled_notification_listeners", packageName) ?: flagged?.notificationListener) == true) {
                out += "notification access on (reads every notification)"
            }
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = runCatching { dpm?.activeAdmins?.any { it.packageName == packageName } }.getOrNull()
            if ((admin ?: flagged?.deviceAdminActive) == true) out += "device admin active (can block its own uninstall)"
            val imm = context.getSystemService(InputMethodManager::class.java)
            val keyboard = runCatching { imm?.enabledInputMethodList?.any { it.packageName == packageName } }.getOrNull()
            if ((keyboard ?: flagged?.imeEnabled) == true) out += "enabled as a keyboard (sees what is typed)"
            val uid = info?.applicationInfo?.uid
            if (uid != null) {
                val ops = context.getSystemService(AppOpsManager::class.java)
                fun allowed(op: String): Boolean = runCatching {
                    ops?.unsafeCheckOpNoThrow(op, uid, packageName) == AppOpsManager.MODE_ALLOWED
                }.getOrDefault(false)
                if (allowed(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)) out += "can draw over other apps"
                if (allowed(AppOpsManager.OPSTR_GET_USAGE_STATS)) out += "usage access (sees which apps are used)"
            }
            if (flagged?.vpnCapable == true) out += "can act as the VPN"
            if (flagged != null && !flagged.hasLauncherIcon) out += "no icon in the app drawer"
            return out
        }

        /** Whether [packageName] has a component in the ':'-separated list under [key]; null when the list cannot be read. */
        private fun enabledIn(context: Context, key: String, packageName: String): Boolean? = runCatching {
            val raw = Settings.Secure.getString(context.contentResolver, key).orEmpty()
            raw.split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }.any { it.packageName == packageName }
        }.getOrNull()
    }
}
