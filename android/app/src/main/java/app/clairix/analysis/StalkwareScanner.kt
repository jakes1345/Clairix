package app.clairix.analysis

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.clairix.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Finds apps on this phone that look like stalkerware: installed from outside a
 * store, hiding without a launcher icon, holding the permissions a monitoring
 * app needs (accessibility, overlays, SMS, call log), or running a foreground
 * service without ever showing a screen. Each signal adds to a score; the
 * score, not any one signal, decides whether an app is reported. Every
 * installed app that is not preloaded is also matched against the stalkerware
 * list ([StalkwareIoc]) by its package name and by every certificate it is
 * signed with; a match is HIGH on its own.
 *
 * Also lists the special-access grants that are not runtime permissions and so
 * escape a permission audit: notification listeners (every notification,
 * including one-time codes, before the owner sees it), enabled accessibility
 * services (every tap and every screen), apps that can act as the VPN (every
 * byte of traffic), enabled third-party keyboards (every keystroke, passwords
 * included) and active device admins (can block their own uninstall), and
 * says which device admin, if any, is the phone's device owner or this
 * profile's owner, which can do far more than any admin ([GrantKind]).
 * [grantStatus] is the one verdict on each grant, which Phone check
 * ([PhoneHealthMonitor]) reads too.
 *
 * Everything here is public API on stock Android. Two grants widen the view:
 * QUERY_ALL_PACKAGES (manifest; otherwise Android 11+ hides most apps) and
 * usage access (Settings → Usage access; otherwise the hidden-service signal
 * is unavailable and reported as such). Nothing widens it past the profile it
 * runs in: apps in a work profile, Secure Folder, Private space or a second
 * space are not seen, which [Result.otherProfiles] says.
 */
object StalkwareScanner {

    data class SuspiciousApp(
        val packageName: String,
        val appLabel: String,
        val score: Int,
        val reasons: List<String>,
        val installTs: Long,
        val lastUpdateTs: Long,
        val sideloaded: Boolean,
        val hasLauncherIcon: Boolean,
        val vpnCapable: Boolean,
        val notificationListener: Boolean,
        val accessibilityEnabled: Boolean,
        /** Enabled as a keyboard (input method). */
        val imeEnabled: Boolean,
        /** Holds an active device-admin grant. */
        val deviceAdminActive: Boolean,
        val installerPackage: String?,
        /** The stalkerware list's name for it, when its package name or a signing certificate is on the list. */
        val knownAs: String? = null,
        /** Android exempts it from battery optimisation ([AppFacts.batteryExempt]). */
        val batteryExempt: Boolean = false,
        /** The sensitive runtime permissions it holds granted, in plain words ([AppEvidence.grantedPermissions]). */
        val grantedPermissions: List<String> = emptyList(),
    ) {
        val severity: Severity
            get() = when {
                score >= HIGH_SCORE -> Severity.HIGH
                score >= MEDIUM_SCORE -> Severity.MEDIUM
                else -> Severity.LOW
            }
    }

    /** A device admin that is also the phone's device owner, or the owner of the profile Clairix runs in. */
    data class Owner(val packageName: String, val deviceOwner: Boolean) {
        val kind: GrantKind get() = if (deviceOwner) GrantKind.DEVICE_OWNER else GrantKind.PROFILE_OWNER
    }

    data class Result(
        val apps: List<SuspiciousApp>,
        /** Every app granted notification access, known-good ones included. */
        val notificationListeners: List<String>,
        /** Every enabled accessibility service, known-good ones included. */
        val accessibilityServices: List<String>,
        /** Every app that can act as the device VPN. */
        val vpnApps: List<String>,
        /**
         * Enabled keyboards not vouched for: not a known keyboard, or one using a known
         * keyboard's name without coming from a store or the system image, and not one
         * the owner marked as theirs.
         */
        val suspiciousImes: List<String>,
        /** Every app holding an active device-admin grant. */
        val activeDeviceAdmins: List<String>,
        /**
         * The members of [activeDeviceAdmins] vouched for: those whose grant ([adminStatus]) is
         * [GrantStatus.KNOWN] or [GrantStatus.YOURS] ([adminVouched]). Never a device or profile
         * owner or a management app the owner has not confirmed, whatever its name.
         */
        val vouchedAdmins: Set<String>,
        val usageAccessGranted: Boolean,
        val scannedAt: Long,
        /** Every enabled keyboard, known ones included. */
        val enabledImes: List<String> = emptyList(),
        /** [grantStatus] of every grant listed above, keyed by [grantKey]; this app's own are [GrantStatus.KNOWN]. */
        val grants: Map<String, GrantStatus> = emptyMap(),
        /** The device owner and this profile's owner, when either is an app. */
        val owners: List<Owner> = emptyList(),
        /** Apps among [vpnApps] that are part of the system image. */
        val preloadedVpns: Set<String> = emptySet(),
        /** Other profiles of this phone's user that Android lets Clairix see; their apps are not scanned. */
        val otherProfiles: Int = 0,
        /** Samsung's Secure Folder is on this phone; whatever is inside it is not scanned. */
        val secureFolder: Boolean = false,
        /** The profile Clairix itself runs in ([ownProfile]); null in the main user. */
        val ownProfile: String? = null,
        /** When the stalkerware list matched against was issued (Unix seconds), and how many apps it names; 0 without one. */
        val listIssued: Long = 0L,
        val listApps: Int = 0,
        /** When each app that is not preloaded was first installed (PackageInfo.firstInstallTime), this app aside. */
        val installs: Map<String, Long> = emptyMap(),
    ) {
        val high: List<SuspiciousApp> get() = apps.filter { it.severity == Severity.HIGH }
        val medium: List<SuspiciousApp> get() = apps.filter { it.severity == Severity.MEDIUM }

        fun status(kind: GrantKind, packageName: String): GrantStatus? = grants[grantKey(kind, packageName)]

        /** The verdict on [packageName]'s device admin: an owner's is its owner grant, which is the same grant. */
        fun adminStatus(packageName: String): GrantStatus? =
            status(owners.firstOrNull { it.packageName == packageName }?.kind ?: GrantKind.DEVICE_ADMIN, packageName)

        /**
         * Nothing needs a look: no app scores high or medium, and no grant (an owner included) is
         * unknown or waiting to be confirmed. Only then does the page say no suspicious apps were found.
         */
        val clean: Boolean
            get() = high.isEmpty() && medium.isEmpty() &&
                grants.values.none { it == GrantStatus.UNKNOWN || it == GrantStatus.CONFIRM }
    }

    private const val HIGH_SCORE = 5
    private const val MEDIUM_SCORE = 3
    private const val USAGE_WINDOW_MS = 7 * 24 * 3600_000L

    /** A special-access grant the scanner judges, with the key it is stored and acknowledged under. */
    enum class GrantKind(val key: String, val label: String) {
        ACCESSIBILITY("a11y", "accessibility service"),
        NOTIFICATION_LISTENER("listener", "notification access"),
        DEVICE_ADMIN("admin", "device admin"),
        KEYBOARD("ime", "keyboard"),
        DEVICE_OWNER("owner", "device owner"),
        PROFILE_OWNER("profile_owner", "profile owner"),
    }

    /** What the scanner makes of one grant; see [grantStatus]. */
    enum class GrantStatus {
        /** A known app from a store or the system image, or this app itself: listed as access granted. */
        KNOWN,
        /** The owner marked it as theirs: listed as access granted. */
        YOURS,
        /** In the system image but on no list (an OEM's own keyboard or listener): listed, not counted. */
        PRELOADED,
        /** A management app (an MDM agent's device admin, or any device or profile owner) the owner has not confirmed. */
        CONFIRM,
        /** None of the above: needs a look. */
        UNKNOWN,
    }

    /** How one grant is stored: in [Result.grants] and in the last check's record. */
    fun grantKey(kind: GrantKind, packageName: String): String = "${kind.key}:$packageName"

    private val _results = MutableStateFlow<Result?>(null)
    /** The last scan, for the Device tab. */
    val results: StateFlow<Result?> = _results.asStateFlow()

    /**
     * Vendor stores: they verify who publishes under a name, so a Google, Microsoft or Samsung
     * name from one of them is the real app.
     */
    private val VENDOR_STORES = setOf(
        "com.android.vending",                 // Google Play
        "com.sec.android.app.samsungapps",     // Galaxy Store
        "com.samsung.android.galaxystore",
        "com.huawei.appmarket",                // AppGallery
        "com.amazon.venezia"                   // Amazon Appstore
    )

    /**
     * Open stores: not a sideload, but they install any package name from repositories the
     * user adds, so they never vouch for a vendor's name ([VENDOR_PREFIXES]).
     */
    private val OPEN_STORES = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.aurora.store"
    )

    /** Installers that are stores, when genuine ([genuineStores]); anything else (or nothing) is a sideload. */
    private val STORES = VENDOR_STORES + OPEN_STORES

    /** Whether [packageName] is one of the app stores above: installing apps is what it is for ([UnknownSources]). */
    internal fun isAppStore(packageName: String): Boolean = packageName in STORES

    /** Package-name prefixes of the vendors whose names only a vendor store may vouch for. */
    private val VENDOR_PREFIXES = listOf(
        "com.google.", "com.android.", "com.microsoft.", "com.azure.", "com.touchtype.",
        "com.samsung.", "com.sec."
    )

    /**
     * Who installed a package, as the system recorded it. [initiating] is set by the system from
     * the calling app and cannot be chosen by it; it is null for an install over adb or by root,
     * and also once the app that ran the install has been uninstalled. [installing] is the
     * installer the app is attributed to, which the caller may name: `adb install -i
     * com.android.vending spy.apk` sets it to Play with no initiating app at all.
     */
    internal data class InstallSource(val initiating: String?, val installing: String?) {
        /** The installer named differs from the app that actually ran the install, or none ran it. */
        val spoofed: Boolean get() = installing != null && installing != initiating
    }

    /**
     * The members of [STORES] that are genuine: preloaded ([systemPackages]), or themselves
     * installed by a preloaded store as recorded in [sources]. A store app sideloaded under
     * Play's name is neither, so what it installs counts as sideloaded.
     */
    internal fun genuineStores(systemPackages: Set<String>, sources: Map<String, InstallSource?>): Set<String> {
        val preloaded = STORES.filterTo(HashSet()) { it in systemPackages }
        return STORES.filterTo(HashSet()) { store ->
            store in preloaded || sources[store]?.let { src -> !src.spoofed && src.initiating in preloaded } == true
        }
    }

    /** Notification listeners that belong on a stock Samsung or Pixel. */
    private val KNOWN_LISTENERS = setOf(
        "com.android.systemui",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.wearables.maestro.companion",
        "com.google.android.projection.gearhead",
        "com.google.android.wearable.app",
        "com.google.android.as",
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.wearable.watchmanager",
        "com.samsung.android.app.routines",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.app.reminder",
        "com.samsung.android.mdx",
        "com.samsung.android.oneconnect",
        "com.samsung.android.app.spage",
        "com.samsung.android.smartmirroring",
        "com.sec.android.app.shealth",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.lool",
        "com.samsung.accessibility",
        "com.sec.android.app.launcher",
        "com.samsung.android.forest",
        "com.android.car.notification",
        "org.thoughtcrime.securesms",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.discord",
        "com.slack",
        "com.microsoft.teams",
        "com.fitbit.FitbitMobile",
        "com.garmin.android.apps.connectmobile",
        "com.wunderkinder.wunderlistandroid",
        "com.google.android.apps.messaging",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.chromecast.app",
        "com.google.android.apps.nbu.files",
        "com.google.android.deskclock",
        "com.sonos.acr",
        "com.spotify.music",
        "com.plexapp.android",
        "com.gopro.smarty",
        "com.joaomgcd.autonotification",
        "net.dinglisch.android.taskerm",
        "com.oneplus.gamespace"
    )

    /** Accessibility services that belong on a stock Samsung or Pixel. */
    private val KNOWN_ACCESSIBILITY = setOf(
        "com.samsung.accessibility",
        "com.samsung.android.accessibility.talkback",
        "com.google.android.marvin.talkback",
        "com.google.android.accessibility.soundamplifier",
        "com.google.android.apps.accessibility.voiceaccess",
        "com.google.android.accessibility.switchaccess",
        "com.google.android.apps.accessibility.reveal",
        "com.samsung.android.app.talkback",
        "com.samsung.android.universalswitch",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.app.routines",
        "com.android.switchaccess",
        "com.samsung.android.smartmirroring",
        "com.samsung.android.app.smartcapture",
        "com.sec.android.app.launcher",
        "com.bitwarden",
        "com.x8bit.bitwarden",
        "com.lastpass.lpandroid",
        "com.agilebits.onepassword",
        "com.onepassword.android",
        "keepass2android.keepass2android",
        "org.mozilla.firefox",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "net.dinglisch.android.taskerm",
        "com.joaomgcd.autoinput",
        "com.greenify"
    )

    /**
     * Keyboards (input methods) preloaded by phone makers or widely installed from a
     * store. Every name is a real package (seen in the Play Store, F-Droid, a system-app
     * database or a debloat list). A name vouches only for an app from a store or the
     * system image ([vouched]); the same name on a sideloaded app is an impostor signal.
     */
    private val KNOWN_IMES = setOf(
        // Google
        "com.google.android.inputmethod.latin",            // Gboard
        "com.google.android.inputmethod.japanese",
        "com.google.android.inputmethod.korean",
        "com.google.android.inputmethod.pinyin",
        "com.google.android.apps.inputmethod.hindi",
        "com.google.android.apps.handwriting.ime",
        // AOSP
        "com.android.inputmethod.latin",
        "com.android.inputmethod.pinyin",
        // Samsung
        "com.samsung.android.honeyboard",                  // Samsung Keyboard
        "com.sec.android.inputmethod",                     // older Samsung Keyboard
        "com.sec.android.inputmethod.beta",
        "com.sec.android.inputmethod.iwnnime.japan",
        "com.samsung.android.svoiceime",                   // Samsung voice input
        // Huawei / Honor
        "com.huawei.ohos.inputmethod",                     // Celia Keyboard
        "com.huawei.secime",                               // secure password keyboard
        "com.baidu.input_huawei",
        "com.kikaoem.hw.qisiemoji.inputmethod",
        "com.nuance.swype.emui",
        // Xiaomi / Redmi / POCO
        "com.miui.securityinputmethod",                    // Mi Secure Keyboard
        "com.baidu.input_mi",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.iflytek.inputmethod.miui",
        "com.facemoji.lite.xiaomi",
        "com.facemoji.lite.xiaomi.gp",
        "com.preff.kb.xm",
        // OPPO / OnePlus / realme
        "com.oplus.securitykeyboard",
        "com.coloros.securitykeyboard",
        "com.preff.kb.op",
        // vivo
        "com.baidu.input_vivo",
        "com.vivo.secime.service",
        // Meizu, nubia and other Chinese ROMs
        "com.sohu.inputmethod.sogou.meizu",
        "com.sohu.inputmethod.sogou.nubia",
        "com.sohu.inputmethod.sogou.oem",
        "com.sohu.inputmethod.sogouoem",
        // Transsion (Tecno, Infinix, itel), Lenovo, TCL, LG, Sony, Onyx, iQQi
        "com.facemoji.lite.transsion",
        "com.transsion.sk",
        "com.zui.oneime",
        "com.tct.smartkeyboard",
        "com.lge.ime",
        "com.sonyericsson.textinput.chinese",
        "com.sonymobile.pobox",
        "com.onyx.latinime",
        "com.iqqi.imeservice",
        "jp.co.omronsoft.openwnn",
        "jp.co.omronsoft.iwnnime.ml",
        // Chinese store keyboards
        "com.sohu.inputmethod.sogou",                      // Sogou
        "com.baidu.input",                                 // Baidu
        "com.iflytek.inputmethod",                         // iFlytek
        // Popular store and open-source keyboards
        "com.touchtype.swiftkey",                          // Microsoft SwiftKey
        "com.touchtype.swiftkey.beta",
        "com.nuance.swype.input",
        "com.nuance.swype.dtc",                            // Swype (Nuance)
        "com.htc.sense.ime",                               // HTC Sense keyboard
        "com.minuum.keyboard",                             // Minuum
        "com.grammarly.android.keyboard",
        "com.syntellia.fleksy.keyboard",                   // Fleksy
        "ch.icoaching.typewise",
        "ru.yandex.androidkeyboard",
        "com.mint.keyboard",                               // Bobble
        "com.qisiemoji.inputmethod",                       // Kika
        "com.menny.android.anysoftkeyboard",
        "dev.patrickgold.florisboard",
        "dev.patrickgold.florisboard.beta",
        "helium314.keyboard",                              // HeliBoard
        "org.dslul.openboard.inputmethod.latin",           // OpenBoard
        "org.futo.inputmethod.latin",                      // FUTO Keyboard
        "org.futo.inputmethod.latin.playstore",
        "rkr.simplekeyboard.inputmethod",
        "org.pocketworkstation.pckeyboard"                 // Hacker's Keyboard
    )

    /**
     * Apps that hold a device-admin grant for a stated, legitimate purpose: finding a
     * lost phone, a work or school profile, or the policies an Exchange mail account
     * imposes. Listed, not scored, as for [KNOWN_LISTENERS], when from a store or the
     * system image ([vouched]); the management apps among them ([MDM_ADMINS]) are listed
     * as MEDIUM until the owner confirms the phone was enrolled by them.
     *
     * Clairix is here for its own unlock-ledger admin (watch-login and force-lock, nothing
     * else; res/xml/unlock_admin.xml): its own scan skips it, so this only decides what the
     * same model makes of a store install of it (StalkwareScannerTest, Clairix's own facts).
     * It holds no listener, accessibility service or keyboard, so it is on none of those lists.
     */
    private val KNOWN_ADMINS = setOf(
        "app.clairix",                                     // this app: the unlock ledger's admin
        "com.google.android.gms",                          // Play services: Find My Device
        "com.google.android.apps.adm",                     // Google Find My Device
        "com.google.android.apps.work.clouddpc",           // Android Device Policy
        "com.google.android.apps.enterprise.dmagent",      // Google Apps Device Policy
        "com.microsoft.windowsintune.companyportal",       // Intune Company Portal
        "com.airwatch.androidagent",                       // Workspace ONE Intelligent Hub
        "com.azure.authenticator",                         // Microsoft Authenticator (device registration)
        "com.mobileiron",                                  // Ivanti (MobileIron) Mobile@Work
        "com.mobileiron.anyware.android",                  // Ivanti Go
        "net.soti.mobicontrol.androidwork",                // SOTI MobiControl Agent
        "com.meraki.sm",                                   // Cisco Meraki Systems Manager
        "com.manageengine.mdm.android",                    // ManageEngine MDM
        "com.samsung.android.fmm",                         // Samsung Find My Mobile
        "com.samsung.android.knox.containercore",          // Knox work profile
        "com.sec.enterprise.knox.cloudmdm.smdms",          // Knox enrollment
        "com.google.android.gm",                           // Gmail (Exchange accounts)
        "com.microsoft.office.outlook",                    // Outlook (Exchange accounts)
        "com.samsung.android.email.provider"               // Samsung Email (Exchange accounts)
    )

    /**
     * The members of [KNOWN_ADMINS] that manage a phone for someone else: work and school
     * device management, which anyone can also install from a store and enrol a partner's
     * phone in, in minutes, with no sideload, no hidden icon and no accessibility service.
     * Real, so not an impostor; never vouched for silently either: MEDIUM until the owner
     * confirms they enrolled the phone themselves.
     */
    private val MDM_ADMINS = setOf(
        "com.google.android.apps.work.clouddpc",
        "com.google.android.apps.enterprise.dmagent",
        "com.microsoft.windowsintune.companyportal",
        "com.airwatch.androidagent",
        "com.azure.authenticator",
        "com.mobileiron",
        "com.mobileiron.anyware.android",
        "net.soti.mobicontrol.androidwork",
        "com.meraki.sm",
        "com.manageengine.mdm.android",
        "com.sec.enterprise.knox.cloudmdm.smdms",
    )

    /** Score for an enabled keyboard that is not in [KNOWN_IMES]: it sees every keystroke. */
    internal const val IME_ENABLED_SCORE = 4
    /** Score for an active device-admin grant held by an app not in [KNOWN_ADMINS]. */
    internal const val DEVICE_ADMIN_ACTIVE_SCORE = 3
    /** Score for a management app's active device admin ([MDM_ADMINS]) the owner has not confirmed: MEDIUM on its own. */
    internal const val MDM_ADMIN_SCORE = MEDIUM_SCORE
    /**
     * Score for an install from outside a genuine store. Below MEDIUM on its own: F-Droid
     * installed from its website, Obtainium and GitHub releases all read as this, and an app
     * that holds no access, hides nothing and asks for nothing a monitoring app needs is not
     * worth listing for where it came from. With any other signal it is.
     */
    internal const val SIDELOAD_SCORE = 2
    /** Score for a package name or signing certificate on the stalkerware list: HIGH on its own. */
    internal const val KNOWN_STALKERWARE_SCORE = HIGH_SCORE
    internal const val NO_LAUNCHER_SCORE = 2
    internal const val HIDDEN_SERVICE_SCORE = 2
    /**
     * Score for a sideloaded app using a known app's name. With any special access it holds
     * (keyboard 4, admin 3, accessibility 3, listener 3) it reaches HIGH on its own.
     */
    internal const val IMPOSTOR_SCORE = 2
    /**
     * Score for an exemption from battery optimisation ([batterySignal]). Weak, and never a
     * verdict: messengers, alarm clocks, fitness trackers, VPNs and home-automation apps ask for
     * it too. It only strengthens an app that is already listed for something else.
     */
    internal const val BATTERY_EXEMPT_SCORE = 1

    /**
     * A permission an app requests (`<uses-permission>`) that a monitoring app needs. [points] when
     * it is granted, or when that is not known; [askedPoints] when the app asks for it and Android
     * has not granted it, which says what the app wants but not what it can do. [runtime] is false
     * for a permission Android does not grant at run time (drawing over other apps is a special
     * access), whose grant flag says nothing. With [strangersOnly], the grant counts only for an
     * app from outside a genuine store or with no launcher icon: half the apps on a phone may use
     * the camera.
     */
    private class Suspect(
        val points: Int,
        val askedPoints: Int,
        val reason: String,
        val asked: String,
        val runtime: Boolean = true,
        val strangersOnly: Boolean = false,
    )

    /** Permissions an app *requests*, with their weight and reason, granted or only asked for. */
    private val SUSPECT_PERMISSIONS = linkedMapOf(
        Manifest.permission.READ_SMS to Suspect(2, 1, "can read SMS (including one-time codes)", "to read SMS"),
        Manifest.permission.RECEIVE_SMS to Suspect(1, 0, "receives SMS as they arrive", "to receive SMS"),
        Manifest.permission.READ_CALL_LOG to Suspect(2, 1, "can read the call log", "to read the call log"),
        Manifest.permission.PROCESS_OUTGOING_CALLS to Suspect(1, 0, "sees outgoing calls as they are placed", "to see outgoing calls"),
        Manifest.permission.SYSTEM_ALERT_WINDOW to Suspect(1, 1, "can draw over other apps", "to draw over other apps", runtime = false),
        Manifest.permission.RECORD_AUDIO to Suspect(1, 0, "can record audio", "for the microphone"),
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to Suspect(1, 0, "can read location in the background", "for location in the background"),
        Manifest.permission.CAMERA to Suspect(1, 0, "can use the camera", "for the camera", strangersOnly = true),
    )

    /** PackageInfo.REQUESTED_PERMISSION_GRANTED, spelled out so the JVM tests need no Android stub. */
    private const val REQUESTED_PERMISSION_GRANTED = 2

    /**
     * The members of [requested] that [flags] (PackageInfo.requestedPermissionsFlags) mark granted;
     * null when the flags were not read, which [AppFacts.granted] takes as "not known".
     */
    internal fun grantedOf(requested: Array<String>?, flags: IntArray?): Set<String>? {
        if (requested == null) return emptySet()
        if (flags == null) return null
        return requested.indices.filter { it < flags.size && (flags[it] and REQUESTED_PERMISSION_GRANTED) != 0 }
            .mapTo(HashSet()) { requested[it] }
    }

    /** The signal for [perm], which [f] requests: weighed by whether Android has granted it. */
    private fun permissionSignal(perm: String, s: Suspect, f: AppFacts, hidden: Boolean): Signal {
        val granted = f.granted
        val known = s.runtime && granted != null
        if (known && perm !in granted.orEmpty()) return Signal(s.askedPoints, "asks ${s.asked}, not granted")
        val points = if (s.strangersOnly && f.trustedOrigin && !hidden) 0 else s.points
        return Signal(points, if (known) "${s.reason} (granted)" else s.reason)
    }

    /**
     * What a keyboard requests to do its job: the microphone for voice typing and an
     * overlay for its suggestion strip and pop-ups. For an enabled keyboard these are the
     * keyboard signal itself, so they are not counted again.
     */
    private val KEYBOARD_PERMISSIONS = setOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.SYSTEM_ALERT_WINDOW
    )

    /**
     * Permissions an app *protects its own components with* (`android:permission` on
     * a `<service>` or `<receiver>`), with their weight and reason. These are how a
     * component declares itself an accessibility service, device admin, notification
     * listener, VPN or keyboard: the system alone holds the BIND_ permission, and an app
     * never requests it, so looking for them among requested permissions matched nothing.
     */
    private val SUSPECT_BINDINGS = linkedMapOf(
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE to (2 to "declares an accessibility service (reads every screen, every tap)"),
        Manifest.permission.BIND_DEVICE_ADMIN to (1 to "declares a device admin (can resist uninstall)"),
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE to (1 to "declares a notification listener"),
        Manifest.permission.BIND_VPN_SERVICE to (0 to "declares a VPN service"),
        Manifest.permission.BIND_INPUT_METHOD to (1 to "declares a keyboard (sees what is typed while it is in use)")
    )

    /** One scoring signal: the points it adds and the reason shown for it. */
    internal data class Signal(val points: Int, val reason: String)

    /** Everything [assess] needs to know about one installed package. */
    internal data class AppFacts(
        val name: String,
        val system: Boolean,
        /** The app that ran the install ([InstallSource.initiating]); null over adb or root, or once that app is uninstalled. */
        val installer: String?,
        val hasLauncher: Boolean,
        val requested: Set<String> = emptySet(),
        /** The BIND_ permissions its services and receivers are protected with. */
        val declared: Set<String> = emptySet(),
        val notificationListener: Boolean = false,
        val accessibilityEnabled: Boolean = false,
        val vpnCapable: Boolean = false,
        val imeEnabled: Boolean = false,
        val deviceAdminActive: Boolean = false,
        /** Started a foreground service this week without ever showing a screen (usage access only). */
        val hiddenService: Boolean = false,
        /**
         * The installer the app is attributed to ([InstallSource.installing]), which the caller may
         * name freely; null when none is named apart from [installer].
         */
        val declaredInstaller: String? = null,
        /** The stores on this phone that are genuine ([genuineStores]). */
        val genuineStores: Set<String> = STORES,
        /**
         * The app that ran the install ([installer]) is preloaded. A preloaded app with
         * INSTALL_PACKAGES may attribute an install to a store (a phone-transfer app restoring
         * apps under Play); forging that would take the firmware itself.
         */
        val installerPreloaded: Boolean = false,
        /** The grants the owner marked as theirs ([acknowledgedKinds]); never counted for an app on the stalkerware list. */
        val acknowledged: Set<GrantKind> = emptySet(),
        /** Its entry on the stalkerware list, when its package name or a signing certificate is there. */
        val knownStalkerware: IocMatch? = null,
        /**
         * First installed before the running version of Android was built (`Build.TIME`), so its
         * install record may have been carried over an OS upgrade, which keeps only the installer.
         */
        val installedBeforeSystemBuild: Boolean = false,
        /**
         * Android exempts it from battery optimisation (PowerManager.isIgnoringBatteryOptimizations):
         * Doze and App Standby never pause it, so it can keep reporting with the screen off for days.
         */
        val batteryExempt: Boolean = false,
        /**
         * The members of [requested] Android has granted, from PackageInfo.requestedPermissionsFlags
         * ([grantedOf]); null when not known, and then every requested permission is weighed as granted.
         */
        val granted: Set<String>? = null,
        /** The defaults it holds: SMS app, phone app, home screen ([DefaultRoles]). */
        val defaultRoles: Set<DefaultRoles.Role> = emptySet(),
    ) {
        /** The installer it claims is not the app that ran the install, and that app is not preloaded. */
        val spoofedInstaller: Boolean get() = InstallSource(installer, declaredInstaller).spoofed && !installerPreloaded

        /** Installed by a genuine store, as the system recorded it, or attributed to one by a preloaded app. */
        val fromStore: Boolean get() = !spoofedInstaller && (
            (installer != null && installer in genuineStores) ||
                (installerPreloaded && declaredInstaller != null && declaredInstaller in genuineStores)
            )

        /** Installed by something other than a genuine store; preloaded apps never are. */
        val sideloaded: Boolean get() = !system && !fromStore

        /** From the system image or a genuine store. */
        val trustedOrigin: Boolean get() = !sideloaded

        /**
         * Whether its name can be taken at its word: preloaded, from a vendor store, or from an
         * open store under a name that is not a vendor's.
         */
        val nameTrusted: Boolean get() = system || (fromStore && (installer in VENDOR_STORES || VENDOR_PREFIXES.none { name.startsWith(it) }))
    }

    /**
     * Names whose genuine app is also published outside every store: Clairix Full and Comms come
     * as APKs from GitHub, so a sideloaded copy under the name is no sign of an impostor.
     */
    private val PUBLISHED_OUTSIDE_STORES = setOf("app.clairix")

    /** Every name the scanner vouches for, so a sideloaded app wearing one stands out. */
    private val KNOWN_NAMES: Set<String> by lazy {
        KNOWN_IMES + KNOWN_ADMINS + KNOWN_ACCESSIBILITY + KNOWN_LISTENERS + STORES - PUBLISHED_OUTSIDE_STORES
    }

    /**
     * Whether [f]'s name in [known] vouches for it. A package name is chosen by whoever builds
     * the app, so stalkerware can ship as `com.google.android.gms` (free to take on phones
     * without Google services) or `com.touchtype.swiftkey`; only the app from the system image
     * or a store that enforces the name's owner ([AppFacts.nameTrusted]) is the real one.
     */
    private fun vouched(f: AppFacts, known: Set<String>): Boolean = f.nameTrusted && f.name in known

    /** The owner marked [f]'s [kind] grant as theirs, and [f] is not on the stalkerware list, which no mark can clear. */
    private fun yours(f: AppFacts, kind: GrantKind): Boolean = kind in f.acknowledged && f.knownStalkerware == null

    /**
     * The one verdict on a grant, which the stalkerware page, Phone check and the alerts all read:
     * - [GrantStatus.KNOWN]: a known app from a store or the system image ([vouched]).
     * - [GrantStatus.YOURS]: the owner marked it as theirs ("This is mine"), unless the app is on
     *   the stalkerware list.
     * - [GrantStatus.CONFIRM]: a device or profile owner, whatever its name (Family Link runs as
     *   Play services, which is a known name), or a management app's device admin
     *   ([MDM_ADMINS]), until the owner confirms they enrolled the phone themselves.
     * - [GrantStatus.PRELOADED]: a keyboard or notification listener in the system image that is
     *   on no list (an OEM's own; there are too many to list).
     * - [GrantStatus.UNKNOWN]: anything else.
     */
    internal fun grantStatus(kind: GrantKind, f: AppFacts): GrantStatus = when (kind) {
        GrantKind.DEVICE_OWNER, GrantKind.PROFILE_OWNER -> if (yours(f, kind)) GrantStatus.YOURS else GrantStatus.CONFIRM
        GrantKind.DEVICE_ADMIN -> when {
            vouched(f, KNOWN_ADMINS) && f.name in MDM_ADMINS -> if (yours(f, kind)) GrantStatus.YOURS else GrantStatus.CONFIRM
            vouched(f, KNOWN_ADMINS) -> GrantStatus.KNOWN
            yours(f, kind) -> GrantStatus.YOURS
            else -> GrantStatus.UNKNOWN
        }
        GrantKind.ACCESSIBILITY -> when {
            vouched(f, KNOWN_ACCESSIBILITY) -> GrantStatus.KNOWN
            yours(f, kind) -> GrantStatus.YOURS
            else -> GrantStatus.UNKNOWN
        }
        GrantKind.NOTIFICATION_LISTENER -> when {
            vouched(f, KNOWN_LISTENERS) -> GrantStatus.KNOWN
            yours(f, kind) -> GrantStatus.YOURS
            f.system -> GrantStatus.PRELOADED
            else -> GrantStatus.UNKNOWN
        }
        GrantKind.KEYBOARD -> when {
            vouched(f, KNOWN_IMES) -> GrantStatus.KNOWN
            yours(f, kind) -> GrantStatus.YOURS
            f.system -> GrantStatus.PRELOADED
            else -> GrantStatus.UNKNOWN
        }
    }

    /**
     * How loud a grant is in Phone check and the alerts: nothing for one that is known, yours or
     * preloaded; HIGH for an unconfirmed owner, which can do more than any other app; MEDIUM for an
     * unconfirmed management app's device admin and for an unknown keyboard (as the score has it,
     * a keyboard alone is MEDIUM); HIGH for any other unknown grant.
     */
    internal fun grantSeverity(kind: GrantKind, status: GrantStatus): Severity? = when (status) {
        GrantStatus.KNOWN, GrantStatus.YOURS, GrantStatus.PRELOADED -> null
        GrantStatus.CONFIRM -> if (kind == GrantKind.DEVICE_ADMIN) Severity.MEDIUM else Severity.HIGH
        GrantStatus.UNKNOWN -> if (kind == GrantKind.KEYBOARD) Severity.MEDIUM else Severity.HIGH
    }

    /**
     * How the owner's "This is mine" is stored: per grant, per app, and per signing certificate
     * ([signerKey]), so a different app installed later under the same name is asked about afresh.
     */
    internal fun ackKey(kind: GrantKind, packageName: String, signer: String): String = "${kind.key}|$packageName|$signer"

    /** The package an [ackKey] belongs to. */
    internal fun ackPackage(key: String): String? = key.split('|').getOrNull(1)

    /**
     * The grants of [packageName], signed as [signer], that [acknowledged] marks as the owner's.
     * Confirming that you enrolled the phone with an owner covers that app's device admin too.
     */
    internal fun acknowledgedKinds(packageName: String, signer: String, acknowledged: Set<String>): Set<GrantKind> {
        if (acknowledged.isEmpty()) return emptySet()
        val kinds = GrantKind.entries.filterTo(HashSet()) { ackKey(it, packageName, signer) in acknowledged }
        if (GrantKind.DEVICE_OWNER in kinds || GrantKind.PROFILE_OWNER in kinds) kinds += GrantKind.DEVICE_ADMIN
        return kinds
    }

    /** What the reasons list says about a match on the stalkerware list. */
    internal fun knownStalkerwareReason(m: IocMatch): String {
        val also = m.family.aliases.take(4)
        val sold = if (also.isEmpty()) "" else " (also sold as ${also.joinToString(", ")}${if (m.family.aliases.size > also.size) ", …" else ""})"
        val by = when {
            m.byCertificate && m.byPackage -> "its package name and signing certificate are"
            m.byCertificate -> "its signing certificate is"
            else -> "its package name is"
        }
        return "is ${m.family.name}$sold: $by on the Echap stalkerware indicators list"
    }

    /**
     * Scores one package. Each fact is counted once, by its strongest reading:
     * - An enabled keyboard is scored by the enabled-keyboard signal alone; its own
     *   BIND_INPUT_METHOD service, its microphone (voice typing) and its overlay
     *   (suggestion strip) are how a keyboard works, not further evidence.
     * - An active device admin is scored by the grant; the receiver it declared to hold
     *   it is not counted again.
     * - A preloaded app is not something an abuser installed: missing from the app
     *   drawer is normal for one, and an unknown preloaded keyboard (an OEM's own) is
     *   listed but scores only alongside some other, independent signal.
     * - Known apps declaring or holding the access they exist to provide are listed
     *   with "(a known app)" and score nothing, when they come from a store or the system
     *   image. A sideloaded app using a known name is scored as an impostor instead.
     */
    /** Why [f], installed from outside a store, reads as sideloaded: what its install record says. */
    private fun sideloadReason(f: AppFacts): String = when {
        // Android records no initiating app for an install over adb or by root, forgets it
        // once the app that ran the install is uninstalled, and never had it for a record
        // carried over an OS upgrade, so this is said as what it is, not as proof of a forgery.
        f.spoofedInstaller && f.installer == null && f.installedBeforeSystemBuild ->
            "installingPackageName says ${f.declaredInstaller}, but no app on the phone is recorded as having " +
                "installed it. It was installed before this version of Android was built, so its record may " +
                "simply predate an OS upgrade, or the app that installed it has since been uninstalled; it is " +
                "treated as not from a store"
        f.spoofedInstaller && f.installer == null ->
            "installingPackageName says ${f.declaredInstaller}, but initiatingPackageName is empty: no app on " +
                "the phone is recorded as having installed it, and it was installed after this version of " +
                "Android was built, so it is unlikely to be left over from an OS upgrade. That is how an install over adb " +
                "or by root naming ${f.declaredInstaller} looks, and also how any install looks once the app " +
                "that ran it has been uninstalled"
        f.spoofedInstaller ->
            "installingPackageName says ${f.declaredInstaller}, but initiatingPackageName is ${f.installer}: the installer is forged"
        f.installer in OPEN_STORES ->
            "installed by ${f.installer}, an open store that was itself installed from outside a store, so " +
                "Clairix cannot tell it from a copy made to look like it (usual for F-Droid)"
        f.installer in STORES ->
            "installed by ${f.installer}, which is not the genuine store (it was not preloaded or installed by one)"
        else -> when (f.installer) {
            null, "com.android.shell" -> "installed from a computer (adb) or an unknown source"
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller" -> "installed from an APK file, not a store"
            else -> "installed by ${f.installer}, not a store"
        }
    }

    internal fun assess(f: AppFacts): List<Signal> {
        val out = ArrayList<Signal>()
        val name = f.name

        f.knownStalkerware?.let { out += Signal(KNOWN_STALKERWARE_SCORE, knownStalkerwareReason(it)) }

        if (f.sideloaded) {
            out += Signal(SIDELOAD_SCORE, sideloadReason(f))
        }
        val impostor = !f.nameTrusted && name in KNOWN_NAMES
        if (impostor) {
            out += Signal(
                IMPOSTOR_SCORE,
                if (f.sideloaded) "uses the name of a known app but was not installed from a store or the system image"
                else "uses a vendor's app name but came from ${f.installer}, which cannot vouch for that vendor's names",
            )
        }
        val hidden = !f.system && !f.hasLauncher
        if (hidden) out += Signal(NO_LAUNCHER_SCORE, "has no icon in the app drawer")

        for ((perm, suspect) in SUSPECT_PERMISSIONS) {
            if (perm !in f.requested) continue
            // Waived only for a keyboard from a store or the system image, or one the owner marked
            // as theirs, that is not an impostor.
            if (f.imeEnabled && (f.trustedOrigin || yours(f, GrantKind.KEYBOARD)) && !impostor && perm in KEYBOARD_PERMISSIONS) continue
            out += permissionSignal(perm, suspect, f, hidden)
        }
        for ((perm, weight) in SUSPECT_BINDINGS) {
            if (perm !in f.declared) continue
            val skip = when (perm) {
                Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> vouched(f, KNOWN_ACCESSIBILITY) || yours(f, GrantKind.ACCESSIBILITY)
                Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE -> vouched(f, KNOWN_LISTENERS) || yours(f, GrantKind.NOTIFICATION_LISTENER)
                Manifest.permission.BIND_INPUT_METHOD -> vouched(f, KNOWN_IMES) || f.imeEnabled || yours(f, GrantKind.KEYBOARD)
                Manifest.permission.BIND_DEVICE_ADMIN -> vouched(f, KNOWN_ADMINS) || f.deviceAdminActive || yours(f, GrantKind.DEVICE_ADMIN)
                else -> false
            }
            if (!skip) out += Signal(weight.first, weight.second)
        }

        if (f.accessibilityEnabled) {
            out += when (grantStatus(GrantKind.ACCESSIBILITY, f)) {
                GrantStatus.KNOWN -> Signal(0, "accessibility service is on (a known app)")
                GrantStatus.YOURS -> Signal(0, "accessibility service is on (you marked it as yours)")
                else -> Signal(3, "accessibility service is switched ON")
            }
        }
        if (f.notificationListener) {
            out += when (grantStatus(GrantKind.NOTIFICATION_LISTENER, f)) {
                GrantStatus.KNOWN -> Signal(0, "notification access is on (a known app)")
                GrantStatus.YOURS -> Signal(0, "notification access is on (you marked it as yours)")
                else -> Signal(3, "notification access is switched ON (reads every notification)")
            }
        }
        if (f.vpnCapable) {
            out += Signal(if (f.sideloaded || hidden) 2 else 1, "can act as the VPN and see all traffic")
        }
        if (f.deviceAdminActive) {
            out += when (grantStatus(GrantKind.DEVICE_ADMIN, f)) {
                GrantStatus.KNOWN -> Signal(0, "device admin is active (a known app)")
                GrantStatus.YOURS ->
                    if (name in MDM_ADMINS) Signal(0, "device admin is active (a management app you said you enrolled this phone in)")
                    else Signal(0, "device admin is active (you marked it as yours)")
                GrantStatus.CONFIRM -> Signal(
                    MDM_ADMIN_SCORE,
                    "device admin is active for a work or school management app, which can see and control parts of " +
                        "the phone: listed until you confirm you enrolled this phone yourself",
                )
                else -> Signal(DEVICE_ADMIN_ACTIVE_SCORE, "device admin is ACTIVE (can block its own uninstall; may lock or wipe the phone)")
            }
        }
        if (f.hiddenService) {
            out += Signal(HIDDEN_SERVICE_SCORE, "ran a background service this week without ever showing a screen")
        }
        out += defaultRoleSignals(f)

        if (f.imeEnabled) {
            out += when {
                vouched(f, KNOWN_IMES) -> Signal(0, "is an enabled keyboard (a known app)")
                yours(f, GrantKind.KEYBOARD) -> Signal(0, "is an enabled keyboard (you marked it as yours)")
                // Trade-off: an unknown keyboard in the system image (an OEM's own, of which there
                // are too many to list) scores 0 on its own, so it is never reported as an app on
                // its own; it is still listed in the Stalkerware page's KEYBOARDS section for the
                // owner to judge. Spyware would have to be built into the firmware to benefit, and
                // any other signal (permissions, accessibility, admin, hidden service) scores it in
                // full. Checked last so "another signal" means everything above, none of it the
                // keyboard itself.
                f.system && out.none { it.points > 0 } -> Signal(0, "is an enabled keyboard (preloaded by the phone maker)")
                else -> Signal(IME_ENABLED_SCORE, "is an enabled keyboard (can log every keystroke, passwords included)")
            }
        }
        // Last, so "already listed" means every other signal above.
        batterySignal(f, out, hidden)?.let { out += it }
        return out
    }

    /**
     * The battery-exemption signal, or null when it does not count. Many legitimate apps are
     * exempt, so the exemption alone says nothing: it adds [BATTERY_EXEMPT_SCORE] only to an app
     * the other [signals] already list (MEDIUM or more) and only when one of them is the kind a
     * monitoring app shows: no icon in the app drawer ([hidden]), or a special-access grant nobody
     * vouches for ([unvouchedGrant]). It can lift a listed app to HIGH; it never lists one.
     *
     * An install from outside a store is not enough: a messenger downloaded from its maker's site
     * (Molly, Session, Threema's shop build) is installed that way, uses the microphone and the camera,
     * and asks for the exemption to stay connected without Google's push service. Counting it
     * would make each of them HIGH, "looks like stalkerware", with a notification.
     */
    internal fun batterySignal(f: AppFacts, signals: List<Signal>, hidden: Boolean): Signal? {
        if (!f.batteryExempt) return null
        if (signals.sumOf { it.points } < MEDIUM_SCORE) return null
        if (!hidden && !unvouchedGrant(f)) return null
        return Signal(BATTERY_EXEMPT_SCORE, "is exempt from battery optimisation, so Android never pauses it in the background")
    }

    /**
     * One signal for each default (SMS app, phone app, home screen) [f] holds, when it came from
     * outside a genuine store: being the default gives it every text, every call or every app
     * opened, with no permission to ask for. A preloaded or store app holding one is what the
     * phone is meant to have, and adds nothing.
     */
    internal fun defaultRoleSignals(f: AppFacts): List<Signal> =
        if (!f.sideloaded) emptyList()
        else f.defaultRoles.sortedBy { it.ordinal }.map { Signal(it.score, DefaultRoles.reason(it)) }

    /** Whether [f] holds accessibility, notification access, a device admin or a keyboard that is neither known nor marked as the owner's. */
    private fun unvouchedGrant(f: AppFacts): Boolean {
        val settled = setOf(GrantStatus.KNOWN, GrantStatus.YOURS, GrantStatus.PRELOADED)
        return (f.accessibilityEnabled && grantStatus(GrantKind.ACCESSIBILITY, f) !in settled) ||
            (f.notificationListener && grantStatus(GrantKind.NOTIFICATION_LISTENER, f) !in settled) ||
            (f.deviceAdminActive && grantStatus(GrantKind.DEVICE_ADMIN, f) !in settled) ||
            (f.imeEnabled && grantStatus(GrantKind.KEYBOARD, f) !in settled)
    }

    /**
     * Whether a preloaded app is worth scoring. It is not something an abuser
     * installed; it is only interesting when it holds a special-access grant
     * that is not part of the stock image.
     */
    internal fun systemAppWorthScoring(name: String, listener: Boolean, a11y: Boolean, imeEnabled: Boolean): Boolean =
        (listener && name !in KNOWN_LISTENERS) ||
            (a11y && name !in KNOWN_ACCESSIBILITY) ||
            (imeEnabled && name !in KNOWN_IMES)

    /** Enabled keyboards that are not in [vouchedImes], sorted. */
    internal fun suspiciousImes(enabledImes: Collection<String>, vouchedImes: Set<String>): List<String> =
        enabledImes.filter { it !in vouchedImes }.distinct().sorted()

    /** Whether [f] is a keyboard the scanner vouches for: a known name from a store or the system image. */
    internal fun isVouchedIme(f: AppFacts): Boolean = vouched(f, KNOWN_IMES)

    /** Whether [f] is a device admin the scanner vouches for: a known name from a store or the system image. */
    internal fun isVouchedAdmin(f: AppFacts): Boolean = vouched(f, KNOWN_ADMINS)

    /** Whether an app first installed at [firstInstallTs] predates a system built at [systemBuiltTs]; false when either is unknown. */
    internal fun installedBefore(firstInstallTs: Long, systemBuiltTs: Long): Boolean =
        firstInstallTs > 0L && systemBuiltTs > 0L && firstInstallTs < systemBuiltTs

    /** Whether a device admin whose grant (its owner grant, for an owner) is [status] counts as vouched for ([Result.vouchedAdmins]). */
    internal fun adminVouched(status: GrantStatus): Boolean = status == GrantStatus.KNOWN || status == GrantStatus.YOURS

    /** Whether [f] is an accessibility service the scanner vouches for: a known name from a store or the system image. */
    internal fun isVouchedAccessibility(f: AppFacts): Boolean = vouched(f, KNOWN_ACCESSIBILITY)

    /** Whether [f] is a notification listener the scanner vouches for: a known name from a store or the system image. */
    internal fun isVouchedListener(f: AppFacts): Boolean = vouched(f, KNOWN_LISTENERS)

    /**
     * Where apps on this phone came from, read one app at a time, so a grant can be judged the way
     * [scan] judges it without a scan of every package: Phone check does this on every pass. One
     * per pass; it keeps what it has read.
     */
    class Origins(context: Context) {
        private val pm = context.applicationContext.packageManager
        private val preloadedCache = HashMap<String, Boolean>()

        /** The stores on this phone that are genuine ([genuineStores]). */
        val stores: Set<String> by lazy {
            genuineStores(
                systemPackages = STORES.filterTo(HashSet()) { isSystemPackage(pm, it) },
                sources = STORES.associateWith { installSourceOf(pm, it) },
            )
        }

        /** Whether [pkg] is part of the system image. */
        fun preloaded(pkg: String?): Boolean = pkg != null && preloadedCache.getOrPut(pkg) { isSystemPackage(pm, pkg) }

        /**
         * What [grantStatus] needs to know about [packageName]: preloaded or not, who installed it,
         * and, when [acknowledged] holds a mark for it, which of its grants the owner marked as
         * theirs (read against its signing certificate) and whether [ioc] lists it, which no mark
         * can clear. Null when it is not installed or not visible.
         */
        internal fun facts(packageName: String, acknowledged: Set<String> = emptySet(), ioc: IocList? = null): AppFacts? {
            val system = runCatching { (applicationInfo(pm, packageName).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrNull() ?: return null
            val src = installSourceOf(pm, packageName)
            var acked = emptySet<GrantKind>()
            var match: IocMatch? = null
            if (acknowledged.any { ackPackage(it) == packageName }) {
                val pkg = runCatching { packageInfo(pm, packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
                if (pkg != null) {
                    acked = acknowledgedKinds(packageName, signerKey(pkg), acknowledged)
                    if (!system) match = ioc?.match(packageName, digests(signingCertificates(pkg)))
                }
            }
            return AppFacts(
                name = packageName, system = system, installer = src?.initiating, hasLauncher = true,
                declaredInstaller = src?.installing, genuineStores = stores, installerPreloaded = preloaded(src?.initiating),
                acknowledged = acked, knownStalkerware = match,
            )
        }

        /** [signerKey] of [packageName], or null when it is not installed or not visible. */
        fun signer(packageName: String): String? =
            runCatching { signerKey(packageInfo(pm, packageName, PackageManager.GET_SIGNING_CERTIFICATES)) }.getOrNull()
    }

    /** Whether the hidden-service signal is available: Settings → Usage access → Clairix. */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * Whether Android exempts [packageName] from battery optimisation. Any app may ask this of any
     * package, with no permission; false when the phone will not say.
     */
    fun batteryExempt(power: PowerManager?, packageName: String): Boolean =
        runCatching { power?.isIgnoringBatteryOptimizations(packageName) }.getOrNull() == true

    /** Opens the system's own details page for [packageName], where Uninstall lives. */
    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))

    /**
     * Enumerates every installed package and scores it. Some hundreds of
     * milliseconds on a phone with a few hundred apps; call off the main thread.
     * [acknowledged] holds the owner's "This is mine" marks ([ackKey]); [ioc] is the
     * stalkerware list every app that is not preloaded is matched against.
     */
    fun scan(context: Context, acknowledged: Set<String> = emptySet(), ioc: IocList? = StalkwareIoc.current(context)): Result {
        val app = context.applicationContext
        val pm = app.packageManager
        val self = app.packageName
        val origins = Origins(app)

        val listeners = enabledComponents(app, "enabled_notification_listeners")
        val accessibility = enabledComponents(app, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listenerPkgs = listeners.map { it.packageName }.toSet()
        val accessibilityPkgs = accessibility.map { it.packageName }.toSet()
        val vpnPkgs = vpnCapablePackages(pm)
        val launcherPkgs = launcherPackages(pm)
        val imePkgs = imePackages(app)
        val deviceAdminPkgs = activeDeviceAdminPackages(app)
        val owners = owners(app, deviceAdminPkgs - self)
        val ownerKinds = owners.associate { it.packageName to it.kind }

        val usageAccess = hasUsageAccess(app)
        val power = app.getSystemService(PowerManager::class.java)
        val rolesOf = HashMap<String, MutableSet<DefaultRoles.Role>>()
        runCatching { DefaultRoles.holderPackages(app) }.getOrNull()?.forEach { (role, pkg) -> rolesOf.getOrPut(pkg) { HashSet() } += role }
        val (fgsPkgs, resumedPkgs) = if (usageAccess) usageSignals(app) else emptySet<String>() to emptySet()

        val packages = installedPackages(pm)
        val stores = origins.stores

        val found = ArrayList<SuspiciousApp>()
        val vouchedImes = HashSet<String>()
        val vouchedAdmins = HashSet<String>()
        val grants = HashMap<String, GrantStatus>()
        val preloadedVpns = HashSet<String>()
        val installs = HashMap<String, Long>()
        for (pkg in packages) {
            val info = pkg.applicationInfo ?: continue
            val name = pkg.packageName
            val listener = name in listenerPkgs
            val a11y = name in accessibilityPkgs
            val vpn = name in vpnPkgs
            val ime = name in imePkgs
            val admin = name in deviceAdminPkgs
            if (name == self) {
                // This app: its own grant (the unlock ledger's device admin) is listed as what it
                // is, and it does not score itself. What the same model makes of it is pinned by
                // StalkwareScannerTest (Clairix's own facts), for the README's account of it.
                if (admin) { vouchedAdmins += name; grants[grantKey(GrantKind.DEVICE_ADMIN, name)] = GrantStatus.KNOWN }
                if (ime) { vouchedImes += name; grants[grantKey(GrantKind.KEYBOARD, name)] = GrantStatus.KNOWN }
                continue
            }
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (vpn && system) preloadedVpns += name
            if (!system) installs[name] = pkg.firstInstallTime
            val holdsGrant = listener || a11y || ime || admin
            // Every app that is not preloaded is matched against the list by its certificates
            // too, and the owner's marks are read against the certificate of the app holding a grant.
            val certs = if (!system || holdsGrant) signingCertificates(pkg) else emptyList()
            val match = if (system) null else ioc?.match(name, digests(certs))
            val acked = if (holdsGrant) acknowledgedKinds(name, signerKey(pkg), acknowledged) else emptySet()

            val source = installSourceOf(pm, name)
            val installer = source?.initiating
            // Only an app from a store or the system image may be vouched for by its name, so
            // the origin is read for every grant holder even when the app is not scored.
            val origin = AppFacts(
                name, system, installer, hasLauncher = true,
                declaredInstaller = source?.installing, genuineStores = stores,
                installerPreloaded = origins.preloaded(installer),
                acknowledged = acked, knownStalkerware = match,
            )
            if (holdsGrant) {
                fun judge(kind: GrantKind): GrantStatus = grantStatus(kind, origin).also { grants[grantKey(kind, name)] = it }
                if (a11y) judge(GrantKind.ACCESSIBILITY)
                if (listener) judge(GrantKind.NOTIFICATION_LISTENER)
                if (ime && judge(GrantKind.KEYBOARD).let { it == GrantStatus.KNOWN || it == GrantStatus.YOURS }) vouchedImes += name
                if (admin) {
                    // An owner is judged as an owner; its device admin is the same grant. A known
                    // name is not enough to vouch for it: an owner, or a management app nobody
                    // confirmed, needs a look whatever its name (Family Link runs as Play services).
                    val kind = ownerKinds[name] ?: GrantKind.DEVICE_ADMIN
                    if (adminVouched(judge(kind))) vouchedAdmins += name
                }
            }
            if (system && !systemAppWorthScoring(name, listener, a11y, ime)) continue

            val facts = origin.copy(
                hasLauncher = name in launcherPkgs,
                requested = pkg.requestedPermissions?.toSet() ?: emptySet(),
                granted = grantedOf(pkg.requestedPermissions, pkg.requestedPermissionsFlags),
                declared = declaredBindings(pkg),
                notificationListener = listener,
                accessibilityEnabled = a11y,
                vpnCapable = vpn,
                imeEnabled = ime,
                deviceAdminActive = admin,
                hiddenService = usageAccess && name in fgsPkgs && name !in resumedPkgs,
                installedBeforeSystemBuild = installedBefore(pkg.firstInstallTime, Build.TIME),
                batteryExempt = batteryExempt(power, name),
                defaultRoles = rolesOf[name].orEmpty(),
            )
            val signals = assess(facts)
            val score = signals.sumOf { it.points }
            val reasons = signals.map { it.reason }

            if (score < MEDIUM_SCORE) continue
            found += SuspiciousApp(
                packageName = name,
                appLabel = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(name),
                score = score,
                reasons = reasons,
                installTs = pkg.firstInstallTime,
                lastUpdateTs = pkg.lastUpdateTime,
                sideloaded = facts.sideloaded,
                hasLauncherIcon = facts.hasLauncher,
                vpnCapable = vpn,
                notificationListener = listener,
                accessibilityEnabled = a11y,
                imeEnabled = ime,
                deviceAdminActive = admin,
                installerPackage = installer ?: source?.installing,
                knownAs = match?.family?.name,
                batteryExempt = facts.batteryExempt,
                grantedPermissions = AppEvidence.grantedPermissions(pkg.requestedPermissions, pkg.requestedPermissionsFlags),
            )
        }

        val result = Result(
            apps = found.sortedWith(compareByDescending<SuspiciousApp> { it.score }.thenByDescending { it.installTs }),
            notificationListeners = listenerPkgs.sorted(),
            accessibilityServices = accessibilityPkgs.sorted(),
            vpnApps = vpnPkgs.sorted(),
            suspiciousImes = suspiciousImes(imePkgs, vouchedImes),
            activeDeviceAdmins = deviceAdminPkgs.sorted(),
            vouchedAdmins = vouchedAdmins,
            usageAccessGranted = usageAccess,
            scannedAt = System.currentTimeMillis(),
            enabledImes = imePkgs.sorted(),
            grants = grants,
            owners = owners,
            preloadedVpns = preloadedVpns,
            otherProfiles = otherProfiles(app),
            secureFolder = hasSecureFolder(app),
            ownProfile = ownProfile(app),
            listIssued = ioc?.issued ?: 0L,
            listApps = ioc?.families?.size ?: 0,
            installs = installs,
        )
        _results.value = result
        return result
    }

    /**
     * What [scan] needs of every package: requested permissions, services and
     * receivers — including components disabled in the manifest. Monitoring apps
     * commonly ship their accessibility service or admin receiver disabled and
     * enable it during setup; without MATCH_DISABLED_COMPONENTS those are omitted.
     * The signing certificates are for the stalkerware list and the owner's marks.
     */
    private const val PACKAGE_FLAGS =
        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_SIGNING_CERTIFICATES

    /**
     * Every certificate [pkg] is signed with, as DER: each signer of an APK with several, or the
     * signer and every earlier one it rotated from. Empty when the package info was read without
     * them.
     */
    private fun signingCertificates(pkg: PackageInfo): List<ByteArray> {
        val info = pkg.signingInfo ?: return emptyList()
        val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return signatures?.map { it.toByteArray() } ?: emptyList()
    }

    /**
     * Which signer [pkg] is, for the owner's marks ([ackKey]): the SHA-256 of its first
     * certificate, which survives key rotation, or of every signer of an APK signed by several.
     * Empty when unreadable, which matches no mark.
     */
    private fun signerKey(pkg: PackageInfo): String {
        val info = pkg.signingInfo ?: return ""
        if (info.hasMultipleSigners()) {
            return info.apkContentsSigners?.map { hex(sha256(it.toByteArray())) }?.sorted()?.joinToString(",") ?: ""
        }
        val first = info.signingCertificateHistory?.firstOrNull() ?: return ""
        return hex(sha256(first.toByteArray()))
    }

    /**
     * The SHA-1 and SHA-256 fingerprints of [certs], uppercase hex with no separators: the form
     * the stalkerware list keeps them in. The list's source gives SHA-1 fingerprints.
     */
    internal fun digests(certs: List<ByteArray>): Set<String> {
        val out = HashSet<String>(certs.size * 2)
        for (der in certs) {
            out += hex(MessageDigest.getInstance("SHA-1").digest(der))
            out += hex(sha256(der))
        }
        return out
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private val HEX = "0123456789ABCDEF".toCharArray()

    internal fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    /**
     * The members of [admins] that are the phone's device owner, or the owner of the profile
     * Clairix runs in. Both are admins nobody can switch off in Settings; an owner can install and
     * remove apps silently, hide apps, set location and other policies and install certificates.
     */
    private fun owners(context: Context, admins: Set<String>): List<Owner> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return emptyList()
        return admins.sorted().mapNotNull { pkg ->
            when {
                runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false) -> Owner(pkg, deviceOwner = true)
                runCatching { dpm.isProfileOwnerApp(pkg) }.getOrDefault(false) -> Owner(pkg, deviceOwner = false)
                else -> null
            }
        }
    }

    /** Whether [packageName] is the phone's device owner (true), this profile's owner (false), or neither (null). */
    fun ownerOf(context: Context, packageName: String): Boolean? = owners(context, setOf(packageName)).firstOrNull()?.deviceOwner

    /**
     * Other profiles of this phone's user that Android shows Clairix: a work profile, a clone or
     * dual-apps profile, some makers' second spaces. Their apps are not scanned. Android 15's
     * Private space is hidden from every app but the launcher, so it is never counted here.
     */
    fun otherProfiles(context: Context): Int = runCatching {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        (um.userProfiles.size - 1).coerceAtLeast(0)
    }.getOrDefault(0)

    /**
     * The kind of profile Clairix itself is installed in, or null in the phone's main user. In
     * one, it sees that profile's apps and none of the main ones. `UserManager.isPrivateProfile`
     * (Android 15) is not in the SDK this app compiles against, so it is reached by reflection.
     */
    fun ownProfile(context: Context): String? = runCatching {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        when {
            !um.isProfile -> null
            um.isManagedProfile -> "a work profile"
            Build.VERSION.SDK_INT >= 35 &&
                runCatching { UserManager::class.java.getMethod("isPrivateProfile").invoke(um) as? Boolean }.getOrNull() == true -> "Private space"
            else -> "another profile"
        }
    }.getOrNull()

    /** Samsung's Secure Folder app, present on every Galaxy that offers it, set up or not. */
    private const val SECURE_FOLDER = "com.samsung.knox.securefolder"

    /** Whether Samsung's Secure Folder is on this phone; what is inside it, if it is set up, is not visible. */
    fun hasSecureFolder(context: Context): Boolean = runCatching {
        applicationInfo(context.packageManager, SECURE_FOLDER)
        true
    }.getOrDefault(false)

    private fun applicationInfo(pm: PackageManager, packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }

    /**
     * Every installed package with its permissions and components. Asking for the
     * components of every package at once can exceed the binder transaction limit on
     * a phone with many large apps; then the list is taken with permissions only and
     * each package's components are read on their own.
     */
    private fun installedPackages(pm: PackageManager): List<PackageInfo> {
        runCatching { return installed(pm, PACKAGE_FLAGS) }
        val bare = installed(pm, PackageManager.GET_PERMISSIONS)
        return bare.map { pkg ->
            runCatching { packageInfo(pm, pkg.packageName, PACKAGE_FLAGS) }.getOrNull() ?: pkg
        }
    }

    private fun installed(pm: PackageManager, flags: Int): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

    private fun packageInfo(pm: PackageManager, name: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(name, flags)
        }

    /** The permissions [pkg]'s services and receivers are protected with. */
    private fun declaredBindings(pkg: PackageInfo): Set<String> {
        val out = HashSet<String>()
        pkg.services?.forEach { s -> s.permission?.let(out::add) }
        pkg.receivers?.forEach { r -> r.permission?.let(out::add) }
        return out
    }

    /** Who installed [packageName]; null when it is not installed or not visible. (minSdk 31: always API 30+.) */
    private fun installSourceOf(pm: PackageManager, packageName: String): InstallSource? = runCatching {
        val src = pm.getInstallSourceInfo(packageName)
        InstallSource(src.initiatingPackageName, src.installingPackageName)
    }.getOrNull()

    private fun isSystemPackage(pm: PackageManager, packageName: String): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    /** Packages with at least one activity in the launcher. */
    private fun launcherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return list.mapTo(HashSet()) { it.activityInfo.packageName }
    }

    /** Packages declaring a service that binds as android.net.VpnService. */
    private fun vpnCapablePackages(pm: PackageManager): Set<String> {
        val intent = Intent("android.net.VpnService")
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }
        return list.mapTo(HashSet()) { it.serviceInfo.packageName }
    }

    /** Packages of every enabled keyboard (input method), whether or not it is the current one. */
    private fun imePackages(context: Context): Set<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return emptySet()
        val list = runCatching { imm.enabledInputMethodList }.getOrNull() ?: return emptySet()
        return list.mapTo(HashSet()) { it.packageName }
    }

    /**
     * Packages holding an active device-admin grant. An active admin can stop its
     * own uninstall and, with the policies it asked for, lock or wipe the phone:
     * a common way for stalkerware to stay installed.
     */
    private fun activeDeviceAdminPackages(context: Context): Set<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return emptySet()
        val admins = runCatching { dpm.activeAdmins }.getOrNull() ?: return emptySet()
        return admins.mapTo(HashSet()) { it.packageName }
    }

    /** The label an app shows in the launcher and Settings, or [packageName] when it has none or is gone. */
    fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    /** Android's keyboard settings, where an enabled keyboard is switched off. */
    fun keyboardSettingsIntent(): Intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

    /** The ':'-separated component list under a Settings.Secure key, as components. */
    private fun enabledComponents(context: Context, key: String): List<ComponentName> {
        val raw = Settings.Secure.getString(context.contentResolver, key) ?: return emptyList()
        return raw.split(':').filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it) }
    }

    /**
     * Over the last week: packages that started a foreground service, and
     * packages that brought an activity to the front. A member of the first set
     * and not the second runs without ever being seen.
     */
    private fun usageSignals(context: Context): Pair<Set<String>, Set<String>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - USAGE_WINDOW_MS, now)
        val fgs = HashSet<String>()
        val resumed = HashSet<String>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> fgs += pkg
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed += pkg
            }
        }
        return fgs to resumed
    }
}
