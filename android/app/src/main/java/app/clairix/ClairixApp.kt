package app.clairix

import android.app.Application
import app.clairix.security.Wiper

/**
 * The process's first code. Its one job: a wipe that was cut short (the power
 * pulled, the process killed) is finished before anything else runs, whatever
 * started the process: the main screen, the comms service after a reboot, a
 * push, a call. Waiting for the main screen would leave the data in place on a
 * phone whose owner never opens Clairix again, or that a forensic tool reads.
 */
class ClairixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Wiper.isPending(this)) Wiper.start(this)
    }
}
