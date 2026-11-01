package app.clairix

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import app.clairix.security.SosExtras
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The full edition's SOS text: sent by Clairix itself through SmsManager, with the
 * SEND_SMS permission that only this edition's manifest (src/full) declares. The Play
 * edition has its own object (src/play) that cannot send, and the SOS sheet opens the
 * messaging app there instead ([SosExtras.route]).
 */
internal object DirectSms {

    /** This build may send texts itself. */
    const val available: Boolean = true

    /** How long the network's answer is waited for before the sheet says "not confirmed yet". */
    private const val SENT_WAIT_MS = 30_000L

    /** Whether the owner has granted Clairix the SEND_SMS permission. */
    fun permitted(context: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Sends [body] to [number] and waits up to [SENT_WAIT_MS] for the network to take
     * every part of it. [withPosition] only colours what the outcome says. Call off the
     * main thread.
     */
    suspend fun send(context: Context, number: String, body: String, withPosition: Boolean): SosExtras.TextOutcome {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SosExtras.TextOutcome.Failed(number, "Clairix may not send texts (the SMS permission is off)")
        }
        val sms = app.getSystemService(SmsManager::class.java)
            ?: return SosExtras.TextOutcome.Failed(number, "this phone has no SMS service")
        val parts = sms.divideMessage(body)
        // One action per text, so an answer to an earlier SOS's text cannot count for this one.
        val action = "app.clairix.SOS_SMS_SENT." + UUID.randomUUID()
        val codes = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                codes.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(action).setPackage(app.packageName)
                sent += PendingIntent.getBroadcast(app, i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            }
            try {
                if (parts.size == 1) {
                    sms.sendTextMessage(number, null, body, sent[0], null)
                } else {
                    sms.sendMultipartTextMessage(number, null, parts, sent, null)
                }
            } catch (e: IllegalArgumentException) {
                return SosExtras.TextOutcome.Failed(number, "the phone refused the number or the message (${e.message ?: "no reason given"})")
            } catch (e: SecurityException) {
                return SosExtras.TextOutcome.Failed(number, "Android refused to let Clairix send it (${e.message ?: "no reason given"})")
            }
            val answers = withTimeoutOrNull(SENT_WAIT_MS) { List(parts.size) { codes.receive() } }
            return SosExtras.directOutcome(number, withPosition, answers)
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
