package app.clairix.security

import android.app.Activity
import android.content.Context
import android.telephony.SmsManager
import app.clairix.AppSettings
import app.clairix.LatLon
import java.util.Locale

/**
 * The SOS sheet's two optional extras, for the weeks before a Clairix group exists or as
 * well as one: a text to one trusted number, and a button that opens the phone app with
 * the local emergency number ([EmergencyNumbers]). Both are off until the owner turns
 * them on in Settings, SOS.
 *
 * The text goes by SMS: through the carrier in the clear, and kept in the messaging
 * app's history. The full edition sends it itself (SmsManager, with the SEND_SMS
 * permission asked for when the option is turned on); the Play edition may not hold
 * that permission (Google Play allows it to default SMS apps and approved exceptions
 * only), so it opens the messaging app with the text written and the owner presses
 * send. The duress phrase never texts: a text would sit in the messaging app for
 * whoever forced the unlock to read.
 *
 * Pure apart from [config] and the setters: the wording, the route and the message are
 * here so they can be tested without a phone.
 */
object SosExtras {

    private const val KEY_TEXT = "sos_text_enabled"
    private const val KEY_NUMBER = "sos_text_number"
    private const val KEY_CALL = "sos_call_button"

    /** Fewest and most digits a number to text may have (short codes to E.164's fifteen). */
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 15

    /** What the owner set in Settings, SOS. */
    data class Config(val textOn: Boolean, val number: String, val callOn: Boolean) {
        /** The number a text goes to: null while texting is off or what was typed is not a number. */
        val textTo: String? get() = if (textOn) normalizeNumber(number) else null

        /** Whether the SOS sheet has anything extra to show. */
        val any: Boolean get() = textTo != null || callOn

        companion object {
            val OFF = Config(textOn = false, number = "", callOn = false)
        }
    }

    /** The settings, read from the app's preferences (wiped with them by Wipe Clairix and a duress wipe). */
    fun config(context: Context): Config {
        val p = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        return Config(
            textOn = p.getBoolean(KEY_TEXT, false),
            number = p.getString(KEY_NUMBER, null) ?: "",
            callOn = p.getBoolean(KEY_CALL, false)
        )
    }

    fun setText(context: Context, on: Boolean) =
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEXT, on).apply()

    fun setNumber(context: Context, number: String) =
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_NUMBER, number.trim()).apply()

    fun setCall(context: Context, on: Boolean) =
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL, on).apply()

    /**
     * A typed phone number as a text is addressed to: digits, with a leading + kept, and
     * spaces, dashes, dots and brackets dropped. Null when anything else is in it, or it
     * has fewer than three or more than fifteen digits.
     */
    fun normalizeNumber(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val plus = s.startsWith("+")
        val rest = if (plus) s.substring(1) else s
        if (!rest.all { it.isDigit() || it in " -.()" }) return null
        val digits = rest.filter { it.isDigit() }
        if (digits.length !in MIN_DIGITS..MAX_DIGITS) return null
        return (if (plus) "+" else "") + digits
    }

    /** An OpenStreetMap link that opens on [at], with a marker there. */
    fun osmLink(at: LatLon): String {
        val lat = String.format(Locale.ROOT, "%.5f", at.lat)
        val lon = String.format(Locale.ROOT, "%.5f", at.lon)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
    }

    /**
     * The text. [at] is the position [SosLocation] found (never a mock position, never
     * a stale one); without one the text says that it carries none, so nobody reads its
     * absence as "no need to come". It does not name Clairix.
     */
    fun smsBody(at: LatLon?): String =
        if (at != null) "SOS: I need help. Where I am: ${osmLink(at)}"
        else "SOS: I need help. My phone could not find where I am, so this text has no position."

    /** How the text goes. */
    enum class TextRoute {
        /** Clairix sends it itself through SmsManager. */
        DIRECT,
        /** The messaging app opens with the text written; the owner presses send. */
        COMPOSER
    }

    /**
     * [TextRoute.DIRECT] only in a build that may send texts ([canSendDirectly]: the full
     * edition, whose manifest alone declares SEND_SMS) and with that permission granted;
     * the composer otherwise, so a refused or revoked permission still leaves a way.
     */
    fun route(canSendDirectly: Boolean, permitted: Boolean): TextRoute =
        if (canSendDirectly && permitted) TextRoute.DIRECT else TextRoute.COMPOSER

    /** What became of the text. */
    sealed interface TextOutcome {
        val number: String

        /** The position is being looked for; nothing has gone yet. */
        data class Finding(override val number: String) : TextOutcome
        /** The network took every part of it. */
        data class Sent(override val number: String, val withPosition: Boolean) : TextOutcome
        /** Handed to the phone's SMS service, with no word from the network in the time waited. */
        data class Unconfirmed(override val number: String, val withPosition: Boolean) : TextOutcome
        /** Not sent, and why. */
        data class Failed(override val number: String, val reason: String) : TextOutcome
        /** The messaging app is open with the text written; it goes only when the owner presses send. */
        data class ComposerOpened(override val number: String, val withPosition: Boolean, val permissionOff: Boolean) : TextOutcome
    }

    /** What became of the emergency call button. */
    sealed interface CallOutcome {
        val number: String

        /** The phone app is open with the number entered. */
        data class DiallerOpened(override val number: String) : CallOutcome
        /** No app on the phone takes a number to call. */
        data class NoDialler(override val number: String) : CallOutcome
        /** The button was not pressed. */
        data class NotDialled(override val number: String) : CallOutcome
    }

    /** What the SOS sheet says will happen to the text when the SOS is sent. */
    fun textPlan(number: String, route: TextRoute, canSendDirectly: Boolean): String = when (route) {
        TextRoute.DIRECT -> "Also texts $number by SMS, with your position if the phone finds one."
        TextRoute.COMPOSER -> "Then opens your messaging app with a text to $number written, with your position if the phone " +
            "finds one. It goes when you press send there." +
            if (canSendDirectly) " (Clairix cannot send it itself: the SMS permission is off.)" else ""
    }

    /** How this edition sends the text, as Settings says it under the number. */
    fun editionLine(canSendDirectly: Boolean, permitted: Boolean): String = when {
        !canSendDirectly ->
            "This edition, from Google Play, cannot send texts itself (Google Play allows that to default messaging " +
                "apps and a few approved exceptions): the SOS opens your messaging app with the text written, and it " +
                "goes when you press send."
        permitted -> "Clairix sends the text itself when you send an SOS."
        else -> "Clairix is not allowed to send texts, so the SOS opens your messaging app with the text written, and " +
            "it goes when you press send. Allow SMS for Clairix in Android's settings to have it sent for you."
    }

    /** The SOS sheet's line for the text. */
    fun textLine(o: TextOutcome): String {
        val position = { has: Boolean -> if (has) "with your position" else "without a position (none was found)" }
        return when (o) {
            is TextOutcome.Finding -> "Finding your position for the text to ${o.number}…"
            is TextOutcome.Sent -> "Texted ${o.number}, ${position(o.withPosition)}."
            is TextOutcome.Unconfirmed -> "Text to ${o.number} handed to the phone, ${position(o.withPosition)}; the network " +
                "has not confirmed it yet. Your messaging app shows whether it went."
            is TextOutcome.Failed -> "Text to ${o.number} not sent: ${o.reason}."
            is TextOutcome.ComposerOpened -> "Your messaging app is open with the text to ${o.number} written, " +
                "${position(o.withPosition)}. It is not sent until you press send there." +
                if (o.permissionOff) " Clairix could not send it itself: the SMS permission is off." else ""
        }
    }

    /** The SOS sheet's line for the emergency call. */
    fun callLine(o: CallOutcome): String = when (o) {
        is CallOutcome.DiallerOpened -> "The phone app is open with ${o.number} entered. The call starts when you press call " +
            "there; Clairix places no call itself."
        is CallOutcome.NoDialler -> "${o.number} not dialled: no app on this phone can make calls."
        is CallOutcome.NotDialled -> "${o.number} not dialled."
    }

    /**
     * What the sheet says after an SOS: one line for the text when one was set up, one for
     * the emergency call when the button is shown, in that order. [call] null with
     * [callNumber] set reads as not dialled.
     */
    fun resultLines(text: TextOutcome?, call: CallOutcome?, callNumber: String?): List<String> = buildList {
        text?.let { add(textLine(it)) }
        val c = call ?: callNumber?.let { CallOutcome.NotDialled(it) }
        c?.let { add(callLine(it)) }
    }

    /**
     * What the full edition's sender makes of SmsManager's answers: [codes] holds one
     * result code per part of the text ([OK] for each part the network took), or is null
     * when not every part was answered in the time waited.
     */
    fun directOutcome(number: String, withPosition: Boolean, codes: List<Int>?): TextOutcome = when {
        codes == null -> TextOutcome.Unconfirmed(number, withPosition)
        codes.all { it == OK } -> TextOutcome.Sent(number, withPosition)
        else -> TextOutcome.Failed(number, smsError(codes.first { it != OK }))
    }

    /** The code SmsManager reports for a part the network took. */
    const val OK = Activity.RESULT_OK

    /** SmsManager's error codes, as a reason a person can act on. */
    internal fun smsError(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the phone or the carrier refused it"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "the mobile radio is off (flight mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "the phone could not build the message"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "there is no mobile service here"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "the phone has sent too many texts in a short time"
        else -> "the phone's SMS service reported error $code"
    }
}
