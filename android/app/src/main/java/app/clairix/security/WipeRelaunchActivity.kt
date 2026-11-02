package app.clairix.security

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import android.window.SplashScreen
import app.clairix.MainActivity

/**
 * The duress wipe's way back to the lock screen. When the wipe has finished,
 * everything the app showed is still in the memory of the process that showed
 * it (the timeline, the contacts, the conversations), and a stores-refuse-writes
 * flag cannot unsee it: so that process ends, and Clairix comes back in a fresh
 * one that reads only what the wipe left, the decoy and its lock screen.
 *
 * A process cannot reliably start its own successor and die, so this runs for a
 * moment in a process of its own (":relaunch" in the manifest, where
 * [app.clairix.ClairixApp] does nothing): it ends the old process, opens the
 * main screen afresh with no animation and no splash icon, and ends itself.
 * Seen from outside, the lock screen goes blank for a moment and is back,
 * still saying "Wrong passcode". Not exported: only the wipe starts it.
 */
class WipeRelaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val previous = intent.getIntExtra(EXTRA_PID, 0)
        if (previous > 0 && previous != Process.myPid()) Process.killProcess(previous)
        val next = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_NOTICE, true)
        // The plain background, not the launcher mark: this is the same screen coming back, not the app starting.
        val options = if (Build.VERSION.SDK_INT >= 33) {
            ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR).toBundle()
        } else {
            null
        }
        runCatching { startActivity(next, options) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "app.clairix.wipe.PID"

        /** On the main screen's intent: it opens on the lock screen as the last one ended ([AppLock.takeWipeNotice]). */
        const val EXTRA_NOTICE = "app.clairix.wipe.NOTICE"

        /**
         * Asks for the relaunch from the process that wiped. False when it could
         * not even be asked; Android also drops the request without a word when
         * Clairix is not on screen, which is why [Wiper] ends the process itself
         * if it is still alive moments later.
         */
        fun relaunch(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(context, WipeRelaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_PID, Process.myPid())
            )
            true
        }.getOrDefault(false)
    }
}
