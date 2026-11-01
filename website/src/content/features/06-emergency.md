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

**SOS.** Hold the red octagon until it fills, then confirm: Clairix sends your last known position, or reads a fresh one when the phone can get a fix within a few seconds, end-to-end encrypted, to every member of every group you are in, and then tells you how many people it reached. Location is read at that moment only. Anyone the relay could not take is tried again for about eight minutes while the app runs. The relay never holds an SOS back to mix it with other traffic. With the offline mesh on, an SOS that cannot reach the relay is carried phone to phone over Bluetooth instead.

**Duress phrase.** Set an ordinary-looking sentence. Type it into any Clairix conversation and the message goes out as written while the SOS fires underneath, with the same position. The phrase itself is stored encrypted and compared only in memory. Neither it nor the SOS it sends is written to a log, a notification or the timeline: nothing on this phone says help was called.

**Fake call.** Schedule an incoming call for 30 seconds, 2 minutes or 5 minutes ahead, from a contact of your choosing. Once notifications and full-screen calls are allowed for Clairix (the page schedules nothing until they are), the phone rings with its own ringtone and vibration for 45 seconds, honouring silent and vibrate mode, and a locked phone shows the call screen over the lock screen; with Clairix's device admin enabled, "Schedule and lock" locks the phone for you as it schedules. Allow exact alarms too, or the ring can come late. Answer it to hold a pretend call with a timer and an end button, or have it place a real encrypted call to the contact's Clairix number. The screen is excluded from your recent apps so nothing is left behind.

**App lock.** Clairix offers a passcode on its first run, for anyone who shares the phone; Settings has it after that. It gates the whole app, and Patterns, Unlocks, the tracker map and the evidence export ask for it again every time they are opened, not only when Clairix locks. The passcode is never stored; only a verifier made with a key in the phone's secure hardware is, so a copy of the app's files cannot be used to guess it offline. Every attempt is counted before it is checked, so killing the app mid-guess buys nothing. Wrong passcodes wipe nothing unless you say so: as soon as you set the passcode, Clairix asks what repeated ones should do, an escalating lockout (30 seconds after five in a row, doubling up to an hour) or a wipe after 1, 3, 5 or 10 in a row, and where there is evidence to lose it offers to export it first. A separate duress passcode wipes immediately. The lock screen never says how many attempts remain or that a limit exists; once you are past it, Clairix tells you when a wrong passcode was entered while you were away. Optional biometric unlock uses a hardware key that Android destroys when a new fingerprint or face is enrolled, so a newly added finger cannot open Clairix.

**Panic wipe.** Asks the relay to delete your account, then deletes every keystore key, every preferences file, every database and every file Clairix holds, including cached map tiles, and restarts the app to a blank slate. Once started it cannot be stopped. It wipes Clairix's own data, not your phone.

</div>

<div class="limits cham">

### What it cannot do {#emergency-limits}

- SOS and the duress phrase depend on Comms. If you are not registered, or have no group with anyone else in it, there is nobody to send to, and the app tells you so: the SOS button says nobody would receive it before you confirm, and the Duress phrase page says so in red.
- SOS sends your last known position, or a fresh fix when one can be read within a few seconds. Indoors or with location off, that may be old or missing. The alert says when no position was available, and a fix found afterwards follows as a second alert.
- An SOS the relay could not take is tried again for about eight minutes, only while the app runs; after that it is given up, and the app tells you how many people it reached either way.
- The fake call is a social tool. It does not contact emergency services.
- The fake call needs notifications and full-screen calls allowed for Clairix, and the page will not schedule one without them. On a phone that is unlocked and in another app, Android shows a call banner that carries Clairix's name rather than the call screen; lock the phone, or let the page lock it, before it rings.
- The app lock protects Clairix, not the phone. It cannot stop someone with root access from editing the app's files.
- The panic wipe cannot recall messages already delivered to other people's phones.

</div>
