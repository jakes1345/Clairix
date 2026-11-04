package app.clairix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Disarms an emulated card when the screen goes off, and after [ARM_TIMEOUT_MS]
 * in any case.
 *
 * An armed card answers any reader that asks while the phone is unlocked and
 * the screen is on. The owner arms it to tap a reader in the next minute or
 * two; a phone that has since gone dark in a pocket, or into other hands,
 * should not still be presenting a credential when it next lights up. The app
 * lock and the wipe disarm too ([app.clairix.security.AppLock.lock]); this
 * covers the phone that Clairix is not locking at all.
 */
object EmulationGuard {

    /** How long a card stays armed at most, screen on or off. */
    const val ARM_TIMEOUT_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null
    private val timeout = Runnable { appContext?.let { if (Registry.emulating.value != null) disarm(it) } }

    /** Starts watching; called once a card has been armed. */
    @Synchronized
    fun watch(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) disarm(app)
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, ARM_TIMEOUT_MS)
    }

    /** Stops watching without touching the card; for the owner disarming it by hand. */
    @Synchronized
    fun unwatch(context: Context) {
        main.removeCallbacks(timeout)
        val r = receiver ?: return
        receiver = null
        runCatching { context.applicationContext.unregisterReceiver(r) }
    }

    /** Disarms the card wherever the app is: pairs forgotten, AIDs withdrawn, banner cleared. */
    fun disarm(context: Context) {
        val app = context.applicationContext
        unwatch(app)
        runCatching { CardEmulationBridge.disarm(app) }
        Registry.setEmulating(null)
    }
}
