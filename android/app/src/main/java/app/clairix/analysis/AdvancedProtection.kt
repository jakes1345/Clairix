package app.clairix.analysis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import app.clairix.HealthFact
import app.clairix.Severity

/**
 * Android 16's Advanced Protection mode: one switch that turns on the platform's whole hardened
 * profile (no sideloading, no 2G, no insecure Wi-Fi, USB data off while locked, memory tagging,
 * theft protection), and Phone check's row for it.
 *
 * `AdvancedProtectionManager` (API 36, the SDK this app compiles against) answers
 * `isAdvancedProtectionEnabled()` only to an app holding QUERY_ADVANCED_PROTECTION_MODE, a normal
 * permission new in Android 16 that src/suite/AndroidManifest.xml declares: granted at install
 * there, and ignored by older releases, which do not know it. Without it every Android 16 phone
 * read "Unknown".
 */
internal object AdvancedProtection {

    /** Android 16, the first release with the mode. */
    const val SINCE_SDK = Build.VERSION_CODES.BAKLAVA

    /** What the phone said about the mode. */
    sealed interface State {
        /** Below Android 16: the mode does not exist on this release. */
        data object NotOnThisRelease : State

        /** Android 16 or later without the Advanced Protection service: the phone's maker left it out. */
        data object NotOnThisPhone : State

        /** The phone answered. */
        data class Answered(val on: Boolean) : State

        /** The service is there and would not say; [reason] is why, in a few words. */
        data class NoAnswer(val reason: String) : State
    }

    /**
     * Asks the phone; [sdk] is the Android version, a parameter so the decision is tested without
     * a phone. Lint cannot see that [sdk] is Build.VERSION.SDK_INT everywhere but the JVM tests,
     * hence NewApi: below 36 the manager is never touched.
     */
    @SuppressLint("NewApi")
    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): State {
        if (sdk < SINCE_SDK) return State.NotOnThisRelease
        return ask(context)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    private fun ask(context: Context): State {
        val manager = runCatching { context.getSystemService(AdvancedProtectionManager::class.java) }.getOrNull()
            ?: return State.NotOnThisPhone
        // A refusal (SecurityException without the permission) is named rather than read as off.
        return runCatching { State.Answered(manager.isAdvancedProtectionEnabled) }
            .getOrElse { State.NoAnswer(it.javaClass.simpleName) }
    }

    /** Whether the mode is on, for the rows that depend on it (2G); null when the phone did not say. */
    fun enabled(state: State): Boolean? = (state as? State.Answered)?.on

    /** Phone check's row: On or OFF on Android 16, and what is true of the phone otherwise, never a bare "Unknown". */
    fun fact(state: State): HealthFact = when (state) {
        State.NotOnThisRelease -> HealthFact(
            ID, CATEGORY, LABEL, "Not available on this Android version",
            detail = "Google's Advanced Protection mode arrived with Android 16. On this release the other rows are the " +
                "individual checks it would enforce."
        )
        State.NotOnThisPhone -> HealthFact(
            ID, CATEGORY, LABEL, "Not available on this phone",
            detail = "This phone runs Android 16 or later but does not offer Advanced Protection. The other rows are the " +
                "individual checks it would enforce."
        )
        is State.Answered -> if (state.on) HealthFact(
            ID, CATEGORY, LABEL, "On",
            detail = "The platform blocks apps installed from outside an app store and 2G connections, and turns on the " +
                "rest of its hardened profile as far as this phone supports it (insecure Wi-Fi, USB data while " +
                "locked, memory tagging). $SIDELOAD"
        ) else HealthFact(
            ID, CATEGORY, LABEL, "OFF", Severity.HIGH,
            "Android 16's one-switch hardened mode is off. Turning it on blocks sideloaded apps, 2G connections " +
                "(the IMSI-catcher downgrade path), insecure Wi-Fi and, on phones that support them, USB data while " +
                "the phone is locked and memory tagging against exploits. $SIDELOAD $WHERE.",
            fixAction = Settings.ACTION_SECURITY_SETTINGS
        )
        is State.NoAnswer -> HealthFact(
            ID, CATEGORY, LABEL, "Could not be read",
            detail = "The phone would not say whether Advanced Protection is on (${state.reason}). Look yourself: $WHERE."
        )
    }

    private const val ID = "adv_protection"
    private const val CATEGORY = "Protection"
    private const val LABEL = "Advanced Protection"
    private const val WHERE = "Settings → Security & privacy → Advanced Protection"

    /**
     * What the mode does to an app installed from a file, said wherever it is suggested: Clairix Full
     * is such an app, and the owner should know before turning the mode on that its next update waits
     * until the mode is off again.
     */
    internal const val SIDELOAD = "While it is on, an app installed from a file rather than an app store " +
        "(Clairix too, unless it came from Google Play) cannot be installed or updated: turn it off for the update."
}
