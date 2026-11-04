package app.clairix.analysis

import android.content.Context
import android.provider.Settings
import app.clairix.Store
import org.json.JSONObject

/**
 * Phone check → Accounts: "Who else can see this phone". The surveillance that
 * casework finds most often is not an app on the phone at all but an account:
 * a Google or Samsung account someone else also signs in to, location sharing
 * in Google Maps, Family Link supervision, a recovery phone or passkey someone
 * added, call forwarding at the carrier, Find My Device on someone else's
 * screen. None of it is visible to an app, so Clairix cannot check it; what it
 * can do is send her to the one page where each is shown, say what to look for
 * there, and remember which she has looked at.
 *
 * Every action opens only what is known to exist: a [Settings] action from the
 * SDK, an official https page (Google Account, Google and Samsung help) opened
 * in the browser or the account's app, an installed app's own launch intent, the
 * dialler with a carrier code, or Android's account chooser. No row invents an
 * activity name; where a channel has no reliable deep link the row opens the
 * official help page that walks through it.
 *
 * The rows and the record are plain Kotlin, so they can be tested; the page is
 * in AccountChecklistPage.kt.
 */
object AccountChecklist {

    internal const val FILE = "account_checklist.json"

    // Official pages, each checked to exist (a Google Account page answers a
    // signed-out visit by sending it to sign in; a help page answers with itself).
    const val URL_DEVICES = "https://myaccount.google.com/device-activity"
    const val URL_RECOVERY_PHONE = "https://myaccount.google.com/signinoptions/rescuephone"
    const val URL_RECOVERY_EMAIL = "https://myaccount.google.com/recovery/email"
    const val URL_TWO_STEP = "https://myaccount.google.com/signinoptions/two-step-verification"
    const val URL_PASSKEYS = "https://myaccount.google.com/signinoptions/passkeys"
    const val URL_FIND_DEVICE_WEB = "https://www.google.com/android/find/"
    const val URL_LOCATION_SHARING = "https://myaccount.google.com/locationsharing"
    const val URL_LOCATION_SHARING_HELP = "https://support.google.com/accounts/answer/9363497?co=GENIE.Platform%3DAndroid"
    const val URL_FAMILY = "https://myaccount.google.com/family/details"
    const val URL_FAMILY_LINK_HELP = "https://support.google.com/families/answer/7101025"
    const val URL_SAMSUNG_FIND = "https://smartthingsfind.samsung.com/"
    const val URL_SAMSUNG_ACCOUNT = "https://account.samsung.com/"
    const val URL_PLAY_PROTECT_HELP = "https://support.google.com/googleplay/answer/2812853"
    const val URL_QUICK_SHARE_HELP = "https://support.google.com/android/answer/9286773"

    // Apps opened by their own launch intent, only when installed.
    const val PKG_FIND_MY_DEVICE = "com.google.android.apps.adm"
    const val PKG_MAPS = "com.google.android.apps.maps"
    const val PKG_PLAY_STORE = "com.android.vending"

    /** The account type of a Google account on Android. */
    const val GOOGLE_ACCOUNT_TYPE = "com.google"

    // Row ids, as the record keeps them.
    const val ROW_GOOGLE_ACCOUNTS = "google_accounts"
    const val ROW_GOOGLE_DEVICES = "google_devices"
    const val ROW_RECOVERY = "recovery"
    const val ROW_SIGN_IN = "sign_in"
    const val ROW_FIND_MY_DEVICE = "find_my_device"
    const val ROW_LOCATION_SHARING = "location_sharing"
    const val ROW_FAMILY_LINK = "family_link"
    const val ROW_SAMSUNG_FIND = "samsung_find"
    const val ROW_SAMSUNG_ACCOUNT = "samsung_account"
    const val ROW_PLAY_PROTECT = "play_protect"
    const val ROW_QUICK_SHARE = "quick_share"
    const val ROW_FORWARDING = "forwarding"

    /** What a row's button does. The page turns each into an Intent; nothing else is opened. */
    sealed interface Action {
        val label: String

        /** An official https page, opened with ACTION_VIEW (the browser, or the account's own app). */
        data class Web(override val label: String, val url: String) : Action

        /** An installed app's own launch intent; [fallbackUrl] when it is not installed, or nothing. */
        data class App(override val label: String, val pkg: String, val fallbackUrl: String?) : Action

        /** A page of Android's own settings, by its [Settings] action constant. */
        data class SystemSettings(override val label: String, val action: String) : Action

        /** The dialler with [code] typed in (ACTION_DIAL): she presses call, and the carrier answers on screen. */
        data class Dial(override val label: String, val code: String, val shows: String) : Action

        /** Android's account chooser listing every account of [accountType]. */
        data class ChooseAccount(override val label: String, val accountType: String) : Action
    }

    data class Row(val id: String, val title: String, val lookFor: String, val actions: List<Action>)

    /** The carrier codes that show call forwarding, and what each one's answer means. */
    val FORWARDING_CODES: List<Action.Dial> = listOf(
        Action.Dial(
            "Check *#21#", "*#21#",
            "Whether every call is forwarded. It should say forwarding is off, or not active."
        ),
        Action.Dial(
            "Check *#62#", "*#62#",
            "Where calls go when the phone is off or has no signal. Your voicemail number is normal; any other number is not."
        ),
        Action.Dial(
            "Check *#67#", "*#67#",
            "Where calls go when you are busy or decline. Your voicemail number is normal; any other number is not."
        ),
    )

    /** Samsung phones get the Samsung rows; the name is what [android.os.Build.MANUFACTURER] reports. */
    fun isSamsung(manufacturer: String?): Boolean = manufacturer?.trim()?.equals("samsung", ignoreCase = true) == true

    /** The checklist for a phone made by [manufacturer], in the order she should work through it. */
    fun rows(manufacturer: String?): List<Row> =
        googleRows() + (if (isSamsung(manufacturer)) samsungRows() else emptyList()) + phoneRows()

    /** The Google account: who is signed in, how it is recovered, who can sign in, and what it shares. */
    private fun googleRows(): List<Row> {
        val out = ArrayList<Row>()
        out += Row(
            ROW_GOOGLE_ACCOUNTS, "Google accounts on this phone",
            "Every Google account signed in here. One you do not use, or do not recognise, can be someone else's way " +
                "into your mail, photos, location and backups. Read the list, then press Back; if there is no Google " +
                "account, Android offers to add one instead, and Back leaves that too. Remove an account in Settings, Accounts.",
            listOf(
                Action.ChooseAccount("Show the accounts", GOOGLE_ACCOUNT_TYPE),
                Action.SystemSettings("Open Accounts", Settings.ACTION_SYNC_SETTINGS),
            )
        )
        out += Row(
            ROW_GOOGLE_DEVICES, "Devices signed in to your Google account",
            "Every phone, tablet and computer signed in to your account, and when each was last used. A device you do " +
                "not know, or one that is not yours, can read your account: sign it out there, then change your password.",
            listOf(Action.Web("Open your devices", URL_DEVICES))
        )
        out += Row(
            ROW_RECOVERY, "Recovery phone and email",
            "The phone number and email address that can reset your password. If either is someone else's, they can " +
                "take the account back whenever they like: change it to one only you hold.",
            listOf(
                Action.Web("Recovery phone", URL_RECOVERY_PHONE),
                Action.Web("Recovery email", URL_RECOVERY_EMAIL),
            )
        )
        out += Row(
            ROW_SIGN_IN, "2-Step Verification and passkeys",
            "The phones, security keys, authenticator apps and passkeys that can sign in as you. Any you did not add, " +
                "such as another person's phone receiving your sign-in prompts or a passkey on their device, lets them in " +
                "without your password: remove it.",
            listOf(
                Action.Web("2-Step Verification", URL_TWO_STEP),
                Action.Web("Passkeys", URL_PASSKEYS),
            )
        )
        out += Row(
            ROW_FIND_MY_DEVICE, "Find My Device",
            "Anyone signed in to your Google account can see where this phone is, ring it, lock it or erase it from " +
                "Find My Device. Check which devices it lists, and the devices row above for who is signed in.",
            listOf(Action.App("Open Find My Device", PKG_FIND_MY_DEVICE, URL_FIND_DEVICE_WEB))
        )
        out += Row(
            ROW_LOCATION_SHARING, "Location sharing in Google Maps",
            "Everyone you share your real-time location with. A name you did not add, or one you no longer want, sees " +
                "where you are: stop sharing with them. Google Maps, your picture, Location sharing shows the same list.",
            listOf(
                Action.Web("Open location sharing", URL_LOCATION_SHARING),
                Action.App("Open Google Maps", PKG_MAPS, null),
                Action.Web("How it works", URL_LOCATION_SHARING_HELP),
            )
        )
        out += Row(
            ROW_FAMILY_LINK, "Family group and Family Link",
            "Who is in your Google family group, and whether your account is supervised. A family manager or parent " +
                "account can see this phone's location and apps and set limits on it.",
            listOf(
                Action.Web("Open your family group", URL_FAMILY),
                Action.Web("About Family Link", URL_FAMILY_LINK_HELP),
            )
        )
        return out
    }

    /** A Samsung phone's own account and finder, which a Google check does not cover. */
    private fun samsungRows(): List<Row> = listOf(
        Row(
            ROW_SAMSUNG_FIND, "Samsung Find",
            "Anyone signed in to your Samsung account can locate, ring, lock or erase this phone from Samsung Find. " +
                "Check the devices listed there.",
            listOf(Action.Web("Open Samsung Find", URL_SAMSUNG_FIND))
        ),
        Row(
            ROW_SAMSUNG_ACCOUNT, "Samsung account devices",
            "The devices signed in to your Samsung account, and the account's password and recovery details. A " +
                "device that is not yours can reach your Samsung Cloud, backups and Find: sign it out.",
            listOf(
                Action.Web("Open Samsung account", URL_SAMSUNG_ACCOUNT),
                Action.SystemSettings("Open Accounts", Settings.ACTION_SYNC_SETTINGS),
            )
        ),
    )

    /** The phone's own settings and the carrier: Play Protect, Quick Share and call forwarding. */
    private fun phoneRows(): List<Row> {
        val out = ArrayList<Row>()
        out += Row(
            ROW_PLAY_PROTECT, "Play Protect",
            "Play Protect checks apps for harm. Someone installing a monitoring app often turns it off first: in the " +
                "Play Store, your picture, Play Protect, it should say scanning is on.",
            listOf(
                Action.App("Open the Play Store", PKG_PLAY_STORE, null),
                Action.Web("How to check", URL_PLAY_PROTECT_HELP),
            )
        )
        out += Row(
            ROW_QUICK_SHARE, "Quick Share visibility",
            "Who can send files to this phone. \"Everyone\" lets anyone nearby offer files; \"Your devices\" or " +
                "\"Contacts\" is safer. The help page shows where the setting is on your phone.",
            listOf(Action.Web("How to check", URL_QUICK_SHARE_HELP))
        )
        out += Row(
            ROW_FORWARDING, "Call forwarding",
            "Calls can be forwarded at your carrier to someone else's number, so they get your calls, and your " +
                "verification codes by voice, without touching the phone. Each button types a code into the dialler: " +
                "press call and your carrier shows the answer. Some carriers do not answer these codes; then ask the carrier.",
            FORWARDING_CODES
        )
        return out
    }

    /** What the Health tab says about the checklist: how far she got, and when she last ticked a row. */
    data class Progress(val checked: Int, val total: Int, val lastTickAt: Long?) {
        val done: Boolean get() = total > 0 && checked >= total
        val started: Boolean get() = checked > 0
    }

    /** [ticks] (row id → when it was ticked) counted against [rows]; ticks on rows this phone does not show are left out. */
    fun progress(rows: List<Row>, ticks: Map<String, Long>): Progress {
        val ids = rows.mapTo(HashSet()) { it.id }
        val mine = ticks.filterKeys { it in ids }
        return Progress(checked = mine.size, total = rows.size, lastTickAt = mine.values.maxOrNull())
    }

    // ── The record ───────────────────────────────────────────────────────

    @Volatile private var record: AccountCheckRecord? = null

    private fun record(context: Context): AccountCheckRecord =
        record ?: synchronized(this) {
            record ?: AccountCheckRecord(Store(context.applicationContext, FILE)).also { record = it }
        }

    /** Row id → when it was ticked. File I/O. */
    fun ticks(context: Context): Map<String, Long> = record(context).ticks()

    /** Ticks or unticks a row. File I/O. */
    fun setChecked(context: Context, id: String, checked: Boolean, now: Long = System.currentTimeMillis()) =
        record(context).setChecked(id, checked, now)

    /** Starts the checklist again: every tick goes. File I/O. */
    fun clear(context: Context) = record(context).clear()
}

/**
 * The ticks, sealed in the app's encrypted store like the scanner's other
 * records and cleared with them: which rows she has looked at and when, and
 * nothing she saw there.
 */
internal class AccountCheckRecord(private val store: Store) {

    private val lock = Any()

    fun ticks(): Map<String, Long> = synchronized(lock) {
        val o = store.load { JSONObject() }.optJSONObject(TICKS) ?: return emptyMap()
        o.keys().asSequence().mapNotNull { k -> o.optLong(k, 0L).takeIf { it > 0L }?.let { k to it } }.toMap()
    }

    fun setChecked(id: String, checked: Boolean, now: Long) {
        synchronized(lock) {
            val root = store.load { JSONObject() }
            val ticks = root.optJSONObject(TICKS) ?: JSONObject().also { root.put(TICKS, it) }
            if (checked) ticks.put(id, now) else ticks.remove(id)
            store.markDirty()
            store.flush()
        }
    }

    fun clear() {
        synchronized(lock) {
            // The owner's clear: the file goes even if this run could not read it.
            store.discardUnread()
            store.replace(JSONObject())
        }
    }

    private companion object {
        const val TICKS = "ticks"
    }
}
