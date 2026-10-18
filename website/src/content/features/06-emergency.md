---
id: emergency
order: 6
title: "Be ready for an emergency"
kicker: "Emergency"
icon: sos
variant: sos
visualLabel: "SOS"
visualCaption: "End-to-end encrypted location"
---

<div class="f-does">

### What it does {#emergency-does}

Quiet tools for the moment a situation turns: SOS to your group, a duress phrase, a scheduled fake call, an app lock that can wipe, and a panic wipe.

</div>

### How it works {#emergency-how}

<div class="methods">

**SOS.** One tap reads your position and sends it, end-to-end encrypted, to every member of every group you are in. Location is read at that moment only.

**Duress phrase.** Set an ordinary-looking sentence. Type it into any Clairix conversation and the message goes out as written while the SOS fires underneath. The phrase itself is stored encrypted and compared only in memory. It is never written to a log, a notification or the timeline.

**Fake call.** Schedule an incoming call for 30 seconds, 2 minutes or 5 minutes ahead, from a contact of your choosing. The phone rings with its real ringtone and vibration, over the lock screen, honouring silent mode. Answer it to hold a pretend call with a timer and an end button, or have it place a real encrypted call to the contact's Clairix number. The screen is excluded from your recent apps so nothing is left behind.

**App lock.** A passcode gates the whole app. The passcode is never stored; only a verifier made with a key in the phone's secure hardware is, so a copy of the app's files cannot be used to guess it offline. Every attempt is counted before it is checked, so killing the app mid-guess buys nothing. You choose what repeated failures do: an escalating lockout, or a wipe after a number of attempts you set. A separate duress passcode wipes immediately. The lock screen never says how many attempts remain or that a limit exists. Optional biometric unlock uses a hardware key that Android destroys when a new fingerprint or face is enrolled, so a newly added finger cannot open Clairix.

**Panic wipe.** Asks the relay to delete your account, then deletes every keystore key, every preferences file, every database and every file Clairix holds, including cached map tiles, and restarts the app to a blank slate. Once started it cannot be stopped. It wipes Clairix's own data, not your phone.

</div>

<div class="limits cham">

### What it cannot do {#emergency-limits}

- SOS and the duress phrase depend on Comms. If you are not registered, or have no group, there is nobody to send to, and the app tells you so.
- SOS sends your last known position. Indoors or with location off, that may be old or missing. The alert says when no position was available.
- The fake call is a social tool. It does not contact emergency services.
- The app lock protects Clairix, not the phone. It cannot stop someone with root access from editing the app's files.
- The panic wipe cannot recall messages already delivered to other people's phones.

</div>
