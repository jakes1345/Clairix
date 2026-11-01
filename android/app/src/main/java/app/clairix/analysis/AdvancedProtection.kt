package app.clairix.analysis

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.clairix.HealthFact
import app.clairix.Severity
import java.lang.reflect.InvocationTargetException

/**
 * Android 16's Advanced Protection mode: one switch that turns on the platform's whole hardened
 * profile (no sideloading, no 2G, no insecure Wi-Fi, USB data off while locked, memory tagging,
 * theft protection), and Phone check's row for it.
 *
 * `AdvancedProtectionManager` is API 36 and this app compiles against 35, so it is reached by
 * reflection. Its `isAdvancedProtectionEnabled()` answers only an app holding
 * QUERY_ADVANCED_PROTECTION_MODE, a normal permission new in Android 16 that
 * src/suite/AndroidManifest.xml declares: granted at install there, and ignored by older releases,
 * which do not know it. Without it every Android 16 phone read "Unknown".
 */
internal object AdvancedProtection {

    /** Android 16, the first release with the mode. */
    const val SINCE_SDK = 36

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

    /** Asks the phone; [sdk] is the Android version, a parameter so the decision is tested without a phone. */
    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): State {
        if (sdk < SINCE_SDK) return State.NotOnThisRelease
        val cls = runCatching { Class.forName("android.security.advancedprotection.AdvancedProtectionManager") }.getOrNull()
            ?: return State.NotOnThisPhone
        // Context.ADVANCED_PROTECTION_SERVICE; the class lookup is the type-safe route when the name does not answer.
        val manager = runCatching { context.getSystemService("advanced_protection") }.getOrNull()
            ?: runCatching { context.getSystemService(cls) }.getOrNull()
            ?: return State.NotOnThisPhone
        return runCatching {
            when (val on = cls.getMethod("isAdvancedProtectionEnabled").invoke(manager)) {
                is Boolean -> State.Answered(on)
                else -> State.NoAnswer("it gave no answer")
            }
        }.getOrElse { e ->
            // A refusal arrives wrapped by the reflective call: name what the platform threw.
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            State.NoAnswer(cause.javaClass.simpleName)
        }
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
            detail = "Sideloading, 2G, insecure Wi-Fi and USB data while locked are all blocked by the platform, and memory tagging is on."
        ) else HealthFact(
            ID, CATEGORY, LABEL, "OFF", Severity.HIGH,
            "Android 16's one-switch hardened mode is off. Turning it on blocks sideloaded apps, 2G connections " +
                "(the IMSI-catcher downgrade path), insecure Wi-Fi, USB data while the phone is locked, and enables " +
                "memory tagging against exploits. $WHERE.",
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
}
