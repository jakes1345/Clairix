package app.clairix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clairix.analysis.DebugPace

/**
 * Debug builds only: `adb shell am broadcast -a app.clairix.debug.FORCE_STILL -p app.clairix`
 * settles the running scanner at once ([DebugPace]), so the emulator workflow can check
 * that a settled scanner holds no satellite measurement request. Declared in
 * src/debug/AndroidManifest.xml behind the DUMP permission, which only the shell and
 * the system hold.
 */
class DebugPaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DebugPace.ACTION_FORCE_STILL) DebugPace.forceStill()
    }
}
