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

Quiet tools for the moment a situation turns, in Clairix Full and Clairix Comms alike: SOS to your group, with an optional text to one number and a button for the emergency number, a duress phrase, a scheduled fake call, an app lock that can wipe, and Wipe Clairix.

</div>

### How it works {#emergency-how}

<div class="methods">

**SOS.** Hold the red octagon until it fills, then confirm: Clairix sends your last known position, or reads a fresh one when the phone can get a fix within a few seconds, end-to-end encrypted, to every member of every group you are in, and then tells you how many people it reached. Location is read at that moment only. Anyone the relay could not take right away (no signal, a busy relay) stays queued and is tried again for about thirty minutes, with the screen off too, and a notification says how many people it has reached each time a retry reaches someone more, then gives the final count. Members you have blocked are sent nothing. If your phone missed a change to a group, such as someone being removed, and has since heard a newer member list exists (every group message carries its number), it asks the group's admins for the current list, and members it cannot confirm get the SOS once that list arrives; anyone the list no longer names gets nothing. The relay never holds an SOS back to mix it with other traffic. With the offline mesh on, an SOS that cannot reach the relay is also carried phone to phone over Bluetooth.

**SOS text and emergency call.** For the weeks before you have a Clairix group, or as well as one, Settings has two extras under SOS, both off until you turn them on. "Also text this number" texts one number you choose when you send an SOS: your position as an OpenStreetMap link, read the same way as for the SOS and never a faked or old one, or a line saying the phone could not find where you are. Clairix Full and Clairix Comms send the text themselves, with the permission to send SMS that they ask for when you turn the option on; the Google Play edition may not hold that permission, so it opens your messaging app with the text written, and the text goes when you press send. "Emergency call button" puts a Call button on the SOS sheet with the emergency number for the country of the mobile network the phone is on (999 in the United Kingdom, 911 in the United States, 112 across the EU), or of the SIM card when there is no network; when Clairix cannot tell, or has no number listed for the country, the button offers 112, which mobile networks route to the local emergency service in most countries, and says why. It opens the phone app with the number entered; the call starts when you press call there, and Clairix needs no phone permission for it. Both work when there is nobody in Clairix to send to: the sheet that says so offers the text and the call instead. The sheet then says what happened: texted, or the messaging app opened, and whether the number was dialled.

**Duress phrase.** Set an ordinary-looking sentence. Type it into any Clairix conversation and the message goes out as written while the SOS fires underneath, with the same position. The phrase itself is stored encrypted and compared only in memory. Neither it nor the SOS it sends is written to a log, a notification or the timeline: nothing on this phone says help was called. Anyone the relay cannot take right away is tried again for about thirty minutes from memory only; if Android closes Clairix in that time, those tries stop.

**Fake call.** Schedule an incoming call for 30 seconds, 2 minutes or 5 minutes ahead, from a contact of your choosing. Once notifications and full-screen calls are allowed for Clairix (the page schedules nothing until they are), the phone rings with its own ringtone and vibration for 45 seconds, honouring silent and vibrate mode, and a locked phone shows the call screen over the lock screen; with Clairix's device admin enabled, "Schedule and lock" locks the phone for you as it schedules. Allow exact alarms too, or the ring can come late. Answer it to hold a pretend call with a timer and an end button, or have it place a real encrypted call to the contact's Clairix number. The screen is excluded from your recent apps so nothing is left behind.

**App lock.** Clairix offers a passcode on its first run, for anyone who shares the phone; Settings has it after that. It gates the whole app, and Patterns, Unlocks, the tracker map and the evidence export ask for it again every time they are opened, not only when Clairix locks. The passcode is never stored; only a verifier made with a key in the phone's secure hardware is, so a copy of the app's files cannot be used to guess it offline. Every attempt is counted before it is checked, so killing the app mid-guess buys nothing. Wrong passcodes wipe nothing unless you say so: as soon as you set the passcode, Clairix asks what repeated ones should do, an escalating lockout (30 seconds after five in a row, doubling up to an hour) or a wipe after 1, 3, 5 or 10 in a row, after which Clairix closes and opens next as a new install; where there is evidence to lose it offers to export it first. A separate duress passcode wipes immediately and leaves a decoy instead: the lock screen says "Wrong passcode", as it does for any wrong guess, goes blank for a moment while Clairix restarts behind it, and is back, still saying "Wrong passcode". From then on the duress passcode opens Clairix, and what it opens looks like a fresh install whose first-run pages are done, in your theme and with your scanner and lock settings, with no history, no contacts and no Clairix number. If the scanner was on, it starts again with its notification; Comms is no longer registered, so its notification does not come back. The lock screen never says how many attempts remain or that a limit exists; once you are past it, Clairix tells you when a wrong passcode was entered while you were away. Optional biometric unlock uses a hardware key that Android destroys when a new fingerprint or face is enrolled, so a newly added finger cannot open Clairix.

**Wipe Clairix.** In the Comms menu (⋮), or in Settings under Wipe and delete: type WIPE and tap the button. It destroys every key in the phone's secure hardware first, then deletes every preferences file, every database and every file Clairix holds, including cached map tiles, and every notification; then it asks the relay to delete your account, and Clairix closes. It opens next as a new install. If the relay cannot be reached, the request waits on the phone, in a file that names your Clairix number and nothing else, and is tried again about every ten minutes for up to a day; with Tor on, it goes only through Tor, while the wipe runs. Once started it cannot be stopped. It wipes Clairix's own data, not your phone.

</div>

<div class="limits cham">

### What it cannot do {#emergency-limits}

- The Clairix SOS and the duress phrase depend on Comms. If you are not registered, or have no group with anyone else in it, there is nobody to send to, and the app tells you so: the SOS button says nobody would receive it before you confirm, and the Duress phrase page says so in red. Outside Clairix, the SOS reaches only the one number you set under "Also text this number", and the emergency services only if you press call in the phone app; both are off until you turn them on.
- The SOS text is an ordinary SMS. It is not encrypted: your mobile carrier can read it, and it stays in your messaging app's sent texts, where anyone holding the phone can see it, until you delete it there. On the Google Play edition it goes only if you press send. The duress phrase never texts or calls, because a text would sit in the messaging app for whoever made you unlock.
- Clairix's list of emergency numbers is not complete, and a number from a SIM card is that SIM's home country, not necessarily where you are. Learn the local number when you travel.
- SOS sends your last known position, or a fresh fix when one can be read within a few seconds. Indoors or with location off, that may be old or missing. The alert says when no position was available, and a fix found afterwards follows as a second alert.
- An SOS the relay could not take is tried again for about thirty minutes. If Android closes Clairix in that time, the tries resume the next time it runs with the phone unlocked, until the thirty minutes are up. After that, anyone it has not reached is counted as not reached, and the notification says how many people it reached either way. The duress phrase's SOS is tried again from memory only, so its tries stop if Clairix is closed.
- The fake call is a social tool. It does not contact emergency services; the SOS sheet's Call button is the way to do that, and only once you press call.
- The fake call needs notifications and full-screen calls allowed for Clairix, and the page will not schedule one without them. On a phone that is unlocked and in another app, Android shows a call banner that carries Clairix's name rather than the call screen; lock the phone, or let the page lock it, before it rings.
- The app lock protects Clairix, not the phone. It cannot stop someone with root access from editing the app's files.
- Wipe Clairix cannot recall messages already delivered to other people's phones. It cannot hide that Clairix is installed either: Android keeps the app, the access you gave it and its battery history. When the app's presence is the risk, uninstall it after the wipe.
- The duress decoy hides what was in Clairix, not that Clairix is there: whoever opens it finds an app with no history and no Clairix number.
- Clairix Comms cannot lock the phone as it schedules a fake call; that needs the full app's device admin. Lock it with the power button.

</div>
