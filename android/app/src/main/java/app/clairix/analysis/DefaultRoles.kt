package app.clairix.analysis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import app.clairix.HealthFact
import app.clairix.Severity

/**
 * The three default apps that see the most of a phone: the SMS app, which receives every text
 * message first (one-time codes included) and may read, send and delete them; the phone app,
 * which places and answers every call; and the home screen, which sees every app that is opened
 * and decides which ones show. Stalkerware sold as "parental control" can ask to be made one of
 * them, and Android then gives it all of that without any further permission. A default held by
 * an app that is neither part of the phone's system nor installed by a genuine store
 * ([StalkwareScanner.AppFacts.sideloaded]) is flagged in Phone check ([fact]) and adds to the app's
 * stalkerware score ([StalkwareScanner.defaultRoleSignals]).
 *
 * Who holds each default, read with no permission on Android 12 to 16 (API 31 to 36):
 * - SMS: Telephony.Sms.getDefaultSmsPackage, public since API 19 and permission-free; null on a
 *   phone without telephony, and when no SMS app is set.
 * - Phone: TelecomManager.getDefaultDialerPackage, permission-free since API 29 (before that it
 *   wanted READ_PHONE_STATE, which minSdk 31 no longer has to deal with); null when there is no
 *   telecom service (a Wi-Fi tablet).
 * - Home: the activity PackageManager.resolveActivity picks for ACTION_MAIN / CATEGORY_HOME with
 *   MATCH_DEFAULT_ONLY. With no default chosen Android answers with its own chooser (package
 *   "android"), which is no holder. The answer is filtered by package visibility; the suite's
 *   QUERY_ALL_PACKAGES makes every home app visible. RoleManager.getRoleHolders would say the same
 *   for all three but needs MANAGE_ROLE_HOLDERS, which only the system holds.
 *
 * [judge] and [fact] are plain Kotlin, tested on the JVM; [read] asks the phone.
 */
internal object DefaultRoles {

    enum class Role(val key: String, val label: String, val what: String, val score: Int) {
        SMS("sms", "default SMS app", "receives every text message first, one-time codes included, and can read, send and delete them", 2),
        DIALER("dialer", "default phone app", "places and answers every call and keeps the call log", 2),
        HOME("home", "home screen", "sees every app that is opened and decides which ones show", 1),
    }

    /**
     * One default and the app holding it. [system]: part of the phone's system image; [fromStore]:
     * installed by a genuine store. Both false when Android would not say where it came from
     * ([checked] false), which is then said rather than judged.
     */
    data class Holder(
        val role: Role,
        val packageName: String,
        val label: String,
        val system: Boolean,
        val fromStore: Boolean,
        val checked: Boolean = true,
    ) {
        /** Neither preloaded nor from a genuine store: worth a look. */
        val outsideStore: Boolean get() = checked && !system && !fromStore
    }

    /** The package Android's own chooser is in: a home "default" from it means none was chosen. */
    private const val CHOOSER = "android"

    /** The holder package of each role, as [read] found them, without the judgement. */
    fun holderPackages(context: Context): Map<Role, String> {
        val app = context.applicationContext
        val out = LinkedHashMap<Role, String>()
        for (role in Role.entries) {
            val pkg = runCatching { holderOf(app, role) }.getOrNull()?.takeIf { it.isNotBlank() && it != CHOOSER } ?: continue
            out[role] = pkg
        }
        return out
    }

    /** Every role's holder with where it came from, as [StalkwareScanner.Origins] judges it. */
    fun read(context: Context): List<Holder> {
        val app = context.applicationContext
        val origins = StalkwareScanner.Origins(app)
        return holderPackages(app).map { (role, pkg) ->
            val facts = origins.facts(pkg)
            Holder(
                role = role,
                packageName = pkg,
                label = StalkwareScanner.appLabel(app, pkg),
                system = facts?.system == true,
                fromStore = facts?.fromStore == true,
                checked = facts != null,
            )
        }
    }

    private fun holderOf(context: Context, role: Role): String? = when (role) {
        Role.SMS -> Telephony.Sms.getDefaultSmsPackage(context)
        Role.DIALER -> context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        Role.HOME -> homeActivity(context.packageManager)?.activityInfo?.packageName
    }

    private fun homeActivity(pm: PackageManager): ResolveInfo? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    /** The defaults among [holders] held by an app from outside a store, in role order. */
    fun judge(holders: List<Holder>): List<Holder> = holders.filter { it.outsideStore }.sortedBy { it.role.ordinal }

    /** The reason the stalkerware score gives for an app from outside a store that holds [role] ([StalkwareScanner.defaultRoleSignals]). */
    fun reason(role: Role): String = "is the ${role.label}, which ${role.what}"

    /**
     * Phone check's row. Every default held by a preloaded or store app is in order and named;
     * any held by an app from outside a store is MEDIUM and says what that app can do and where the
     * default is changed. A holder whose origin Android would not give is named as unchecked.
     */
    fun fact(holders: List<Holder>): HealthFact {
        if (holders.isEmpty()) return HealthFact(
            ID, CATEGORY, LABEL, "Not readable",
            detail = "Android did not say which apps are the default SMS app, phone app and home screen. Look yourself: $WHERE."
        )
        val flagged = judge(holders)
        val unchecked = holders.filter { !it.checked }
        val uncheckedText = if (unchecked.isEmpty()) "" else
            " Android did not say where ${unchecked.joinToString(", ") { it.label }} came from; $WHERE shows ${if (unchecked.size == 1) "it" else "them"}."
        if (flagged.isEmpty()) return HealthFact(
            ID, CATEGORY, LABEL,
            holders.sortedBy { it.role.ordinal }.joinToString(" · ") { "${short(it.role)}: ${it.label}" },
            detail = "Each is part of the phone's system or came from a genuine app store.$uncheckedText"
        )
        val first = flagged.first()
        val value = if (flagged.size == 1) "${first.label} is the ${first.role.label}" else
            "${flagged.size} defaults held by apps from outside a store"
        val detail = flagged.joinToString(" ") { h ->
            "${h.label} (${h.packageName}) is the ${h.role.label}, which ${h.role.what}, and it was not installed by a " +
                "genuine app store or with the phone."
        } + " If you did not choose ${if (flagged.size == 1) "it" else "them"} yourself, change the default under $WHERE, " +
            "then look at the app in Phone check → Stalkerware. Changing a default can be noticed by whoever set it." + uncheckedText
        return HealthFact(ID, CATEGORY, LABEL, value, Severity.MEDIUM, detail, fixAction = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    private fun short(role: Role): String = when (role) {
        Role.SMS -> "SMS"
        Role.DIALER -> "Phone"
        Role.HOME -> "Home"
    }

    const val ID = "default_apps"
    private const val CATEGORY = "Apps"
    private const val LABEL = "Default SMS, phone and home apps"
    private const val WHERE = "Settings → Apps → Default apps"
}
