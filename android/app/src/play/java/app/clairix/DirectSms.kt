package app.clairix

import android.content.Context
import app.clairix.security.SosExtras

/**
 * The Play edition does not send texts itself: Google Play allows the SEND_SMS
 * permission only to default SMS apps and approved exceptions, so this edition's
 * manifest does not declare it. The SOS sheet opens the messaging app with the text
 * written instead ([SosExtras.route] picks that whenever [available] is false), and
 * the owner presses send. This stands in for the full edition's sender (src/full) so
 * the shared code compiles unchanged; [send] is never reached in this build.
 */
internal object DirectSms {

    /** This build cannot send texts itself. */
    const val available: Boolean = false

    // The full edition's bridge answers from the permission; this edition never
    // holds SEND_SMS, so its answer is fixed. Same signature in both editions.
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun permitted(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun send(context: Context, number: String, body: String, withPosition: Boolean): SosExtras.TextOutcome =
        SosExtras.TextOutcome.Failed(number, "this edition cannot send texts itself")
}
