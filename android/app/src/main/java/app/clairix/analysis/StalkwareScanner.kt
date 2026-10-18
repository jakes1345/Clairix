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
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.clairix.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Finds apps on this phone that look like stalkerware: installed from outside a
 * store, hiding without a launcher icon, holding the permissions a monitoring
 * app needs (accessibility, overlays, SMS, call log), or running a foreground
 * service without ever showing a screen. Each signal adds to a score; the
 * score, not any one signal, decides whether an app is reported.
 *
 * Also lists the special-access grants that are not runtime permissions and so
 * escape a permission audit: notification listeners (every notification,
 * including one-time codes, before the owner sees it), enabled accessibility
 * services (every tap and every screen), apps that can act as the VPN (every
 * byte of traffic), enabled third-party keyboards (every keystroke, passwords
 * included) and active device admins (can block their own uninstall).
 *
 * Everything here is public API on stock Android. Two grants widen the view:
 * QUERY_ALL_PACKAGES (manifest; otherwise Android 11+ hides most apps) and
 * usage access (Settings → Usage access; otherwise the hidden-service signal
 * is unavailable and reported as such).
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
        val installerPackage: String?
    ) {
        val severity: Severity
            get() = when {
                score >= HIGH_SCORE -> Severity.HIGH
                score >= MEDIUM_SCORE -> Severity.MEDIUM
                else -> Severity.LOW
            }
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
         * keyboard's name without coming from a store or the system image.
         */
        val suspiciousImes: List<String>,
        /** Every app holding an active device-admin grant. */
        val activeDeviceAdmins: List<String>,
        /** The members of [activeDeviceAdmins] vouched for: a known admin app from a store or the system image. */
        val vouchedAdmins: Set<String>,
        val usageAccessGranted: Boolean,
        val scannedAt: Long
    ) {
        val high: List<SuspiciousApp> get() = apps.filter { it.severity == Severity.HIGH }
        val medium: List<SuspiciousApp> get() = apps.filter { it.severity == Severity.MEDIUM }
    }

    private const val HIGH_SCORE = 5
    private const val MEDIUM_SCORE = 3
    private const val USAGE_WINDOW_MS = 7 * 24 * 3600_000L

    private val _results = MutableStateFlow<Result?>(null)
    /** The last scan, for the Device tab. */
    val results: StateFlow<Result?> = _results.asStateFlow()

    /** Installers that are stores; anything else (or nothing) is a sideload. */
    private val STORES = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.samsung.android.galaxystore",
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.aurora.store",
        "com.amazon.venezia",
        "com.huawei.appmarket"
    )

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
        "com.oneplus.gamespace",
        "app.clairix"
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
        "com.greenify",
        "app.clairix"
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
        "org.pocketworkstation.pckeyboard",                // Hacker's Keyboard
        "app.clairix"
    )

    /**
     * Apps that hold a device-admin grant for a stated, legitimate purpose: finding a
     * lost phone, a work or school profile, or the policies an Exchange mail account
     * imposes. Listed, not scored, as for [KNOWN_LISTENERS], when from a store or the
     * system image ([vouched]).
     */
    private val KNOWN_ADMINS = setOf(
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

    /** Score for an enabled keyboard that is not in [KNOWN_IMES]: it sees every keystroke. */
    internal const val IME_ENABLED_SCORE = 4
    /** Score for an active device-admin grant held by an app not in [KNOWN_ADMINS]. */
    internal const val DEVICE_ADMIN_ACTIVE_SCORE = 3
    internal const val SIDELOAD_SCORE = 3
    internal const val NO_LAUNCHER_SCORE = 2
    internal const val HIDDEN_SERVICE_SCORE = 2
    /**
     * Score for a sideloaded app using a known app's name. With any special access it holds
     * (keyboard 4, admin 3, accessibility 3, listener 3) it reaches HIGH on its own.
     */
    internal const val IMPOSTOR_SCORE = 2

    /** Permissions an app *requests* (`<uses-permission>`), with their weight and reason. */
    private val SUSPECT_PERMISSIONS = linkedMapOf(
        Manifest.permission.READ_SMS to (2 to "can read SMS (including one-time codes)"),
        Manifest.permission.RECEIVE_SMS to (1 to "receives SMS as they arrive"),
        Manifest.permission.READ_CALL_LOG to (2 to "can read the call log"),
        Manifest.permission.PROCESS_OUTGOING_CALLS to (1 to "sees outgoing calls as they are placed"),
        Manifest.permission.SYSTEM_ALERT_WINDOW to (1 to "can draw over other apps"),
        Manifest.permission.RECORD_AUDIO to (1 to "can record audio"),
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to (1 to "can read location in the background")
    )

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
    ) {
        /** Installed by something other than a store; preloaded apps never are. */
        val sideloaded: Boolean get() = !system && (installer == null || installer !in STORES)

        /** From the system image or a store: only then can a known name vouch for it. */
        val trustedOrigin: Boolean get() = !sideloaded
    }

    /** Every name the scanner vouches for, so a sideloaded app wearing one stands out. */
    private val KNOWN_NAMES: Set<String> by lazy { KNOWN_IMES + KNOWN_ADMINS + KNOWN_ACCESSIBILITY + KNOWN_LISTENERS + STORES }

    /**
     * Whether [f]'s name in [known] vouches for it. A package name is chosen by whoever builds
     * the app, so stalkerware can ship as `com.google.android.gms` (free to take on phones
     * without Google services) or `com.touchtype.swiftkey`; only the app from the system image
     * or a store, which enforce a name's owner, is the real one.
     */
    private fun vouched(f: AppFacts, known: Set<String>): Boolean = f.trustedOrigin && f.name in known

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
    internal fun assess(f: AppFacts): List<Signal> {
        val out = ArrayList<Signal>()
        val name = f.name

        if (f.sideloaded) {
            out += Signal(SIDELOAD_SCORE, when (f.installer) {
                null, "com.android.shell" -> "installed from a computer (adb) or an unknown source"
                "com.android.packageinstaller", "com.google.android.packageinstaller",
                "com.samsung.android.packageinstaller" -> "installed from an APK file, not a store"
                else -> "installed by ${f.installer}, not a store"
            })
        }
        if (!f.trustedOrigin && name in KNOWN_NAMES) {
            out += Signal(IMPOSTOR_SCORE, "uses the name of a known app but was not installed from a store or the system image")
        }
        val hidden = !f.system && !f.hasLauncher
        if (hidden) out += Signal(NO_LAUNCHER_SCORE, "has no icon in the app drawer")

        for ((perm, weight) in SUSPECT_PERMISSIONS) {
            if (perm !in f.requested) continue
            // Waived only for a keyboard from a store or the system image: a sideloaded one gets no benefit of the doubt.
            if (f.imeEnabled && f.trustedOrigin && perm in KEYBOARD_PERMISSIONS) continue
            out += Signal(weight.first, weight.second)
        }
        for ((perm, weight) in SUSPECT_BINDINGS) {
            if (perm !in f.declared) continue
            val skip = when (perm) {
                Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> vouched(f, KNOWN_ACCESSIBILITY)
                Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE -> vouched(f, KNOWN_LISTENERS)
                Manifest.permission.BIND_INPUT_METHOD -> vouched(f, KNOWN_IMES) || f.imeEnabled
                Manifest.permission.BIND_DEVICE_ADMIN -> vouched(f, KNOWN_ADMINS) || f.deviceAdminActive
                else -> false
            }
            if (!skip) out += Signal(weight.first, weight.second)
        }

        if (f.accessibilityEnabled) {
            out += if (!vouched(f, KNOWN_ACCESSIBILITY)) Signal(3, "accessibility service is switched ON")
            else Signal(0, "accessibility service is on (a known app)")
        }
        if (f.notificationListener) {
            out += if (!vouched(f, KNOWN_LISTENERS)) Signal(3, "notification access is switched ON (reads every notification)")
            else Signal(0, "notification access is on (a known app)")
        }
        if (f.vpnCapable) {
            out += Signal(if (f.sideloaded || hidden) 2 else 1, "can act as the VPN and see all traffic")
        }
        if (f.deviceAdminActive) {
            out += if (!vouched(f, KNOWN_ADMINS)) {
                Signal(DEVICE_ADMIN_ACTIVE_SCORE, "device admin is ACTIVE (can block its own uninstall; may lock or wipe the phone)")
            } else {
                Signal(0, "device admin is active (a known app)")
            }
        }
        if (f.hiddenService) {
            out += Signal(HIDDEN_SERVICE_SCORE, "ran a background service this week without ever showing a screen")
        }

        if (f.imeEnabled) {
            out += when {
                vouched(f, KNOWN_IMES) -> Signal(0, "is an enabled keyboard (a known app)")
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
        return out
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

    /** Whether the hidden-service signal is available: Settings → Usage access → Clairix. */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Opens the system's own details page for [packageName], where Uninstall lives. */
    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))

    /** Runs the scan as a flow; the single emission is the ranked list. */
    fun scanFlow(context: Context): Flow<List<SuspiciousApp>> = flow { emit(scan(context).apps) }

    /**
     * Enumerates every installed package and scores it. Some hundreds of
     * milliseconds on a phone with a few hundred apps; call off the main thread.
     */
    fun scan(context: Context): Result {
        val app = context.applicationContext
        val pm = app.packageManager
        val self = app.packageName

        val listeners = enabledComponents(app, "enabled_notification_listeners")
        val accessibility = enabledComponents(app, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listenerPkgs = listeners.map { it.packageName }.toSet()
        val accessibilityPkgs = accessibility.map { it.packageName }.toSet()
        val vpnPkgs = vpnCapablePackages(pm)
        val launcherPkgs = launcherPackages(pm)
        val imePkgs = imePackages(app)
        val deviceAdminPkgs = activeDeviceAdminPackages(app)

        val usageAccess = hasUsageAccess(app)
        val (fgsPkgs, resumedPkgs) = if (usageAccess) usageSignals(app) else emptySet<String>() to emptySet()

        val packages = installedPackages(pm)

        val found = ArrayList<SuspiciousApp>()
        val vouchedImes = HashSet<String>()
        val vouchedAdmins = HashSet<String>()
        for (pkg in packages) {
            val info = pkg.applicationInfo ?: continue
            val name = pkg.packageName
            if (name == self) continue
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val listener = name in listenerPkgs
            val a11y = name in accessibilityPkgs
            val vpn = name in vpnPkgs
            val ime = name in imePkgs
            val admin = name in deviceAdminPkgs
            // Only an app from a store or the system image may be vouched for by its name, so
            // the origin is read for keyboards and admins even when the app is not scored.
            if (ime || admin) {
                val origin = AppFacts(name, system, installerOf(pm, name), hasLauncher = true)
                if (ime && isVouchedIme(origin)) vouchedImes += name
                if (admin && isVouchedAdmin(origin)) vouchedAdmins += name
            }
            if (system && !systemAppWorthScoring(name, listener, a11y, ime)) continue

            val installer = installerOf(pm, name)

            val facts = AppFacts(
                name = name,
                system = system,
                installer = installer,
                hasLauncher = name in launcherPkgs,
                requested = pkg.requestedPermissions?.toSet() ?: emptySet(),
                declared = declaredBindings(pkg),
                notificationListener = listener,
                accessibilityEnabled = a11y,
                vpnCapable = vpn,
                imeEnabled = ime,
                deviceAdminActive = admin,
                hiddenService = usageAccess && name in fgsPkgs && name !in resumedPkgs,
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
                installerPackage = installer
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
            scannedAt = System.currentTimeMillis()
        )
        _results.value = result
        return result
    }

    /**
     * What [scan] needs of every package: requested permissions, services and
     * receivers — including components disabled in the manifest. Monitoring apps
     * commonly ship their accessibility service or admin receiver disabled and
     * enable it during setup; without MATCH_DISABLED_COMPONENTS those are omitted.
     */
    private const val PACKAGE_FLAGS =
        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.MATCH_DISABLED_COMPONENTS

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

    private fun installerOf(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val src = pm.getInstallSourceInfo(packageName)
            src.installingPackageName ?: src.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

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
