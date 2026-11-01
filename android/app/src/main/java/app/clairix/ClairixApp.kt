package app.clairix

import android.app.Application
import app.clairix.security.RelayFarewell
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
        // The duress wipe's relaunch (security/WipeRelaunchActivity) runs for a
        // moment in a process of its own; nothing below is for it.
        if (Application.getProcessName() != packageName) return
        // A crash from here on leaves a local record for Settings, About (never sent anywhere).
        CrashJournal.install(this)
        if (Wiper.isPending(this)) Wiper.start(this)
        // Before anything reads the comms vault (a push, the call service).
        app.clairix.comms.VaultBox.init(this)
        // Before any relay request: with "Connect through Tor" on, the first one
        // already goes through Tor (or fails), never out directly.
        app.clairix.comms.TorTransport.init(this)
        // A wipe's request to the relay to delete the account, still waiting
        // because the relay could not be reached: sent now, off this thread.
        RelayFarewell.resume(this)
        // The keys published files are checked against (read when first needed),
        // and the version note, so a version found unsafe stops sending at once.
        app.clairix.nav.AssetKeys.init(this)
        app.clairix.comms.AppUpdate.init(this)
    }
}
