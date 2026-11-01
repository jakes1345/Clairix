## Common problems

### "Location permission required to scan"

The tracker scanner is a location service. Android will not start it without location access.

1. Open Android **Settings > Apps > Clairix > Permissions > Location**.
2. Choose **Allow all the time**. "While using the app" is enough for the scan to run while Clairix is on screen, but confirming that a tracker is following you needs the scan to keep running in your pocket.
3. Make sure **Location** itself is turned on in the quick settings panel.

### "Nearby devices permission not granted" or "Bluetooth is off"

Tracker detection needs the **Nearby devices** permission and Bluetooth turned on. Grant the permission under **Settings > Apps > Clairix > Permissions**, and switch Bluetooth on. If you turn Bluetooth on after starting the scan, Clairix picks it up automatically.

### "Android blocked starting the scanner in the background"

Android 12 and newer refuse to start a foreground service from the background, for example after a reboot or from a stale notification. Open Clairix: if you had left the scanner on, it starts again by itself. If it does not, tap **Start scanning** in **Scan > Trackers**. If you want the scan to resume after reboots, turn on **Resume after reboot** in Settings and exempt Clairix from battery optimisation (below).

### "Scanner cannot hear" or "Scanner was stopped by the system"

Android hands Bluetooth scan results to an app only while the system **Location** switch is on, so with it off the scanner runs but hears nothing. Clairix says so on the Scan screen and in a notification instead of showing an empty list as all clear; turn Location on in quick settings and the scan picks up again. Clairix shows the same warning if the scan has heard nothing at all for five minutes while the screen is on.

"Stopped by the system at HH:MM" means the scanner was ended by an update, a low-memory kill, a restart or the phone's battery manager, not from Clairix. The time is when it was last known to be running, and the gap is written to the timeline. After an update the scanner comes back by itself when Clairix's location access is set to **Allow all the time**; otherwise opening Clairix starts it again. If this keeps happening, let Clairix run unrestricted from the card on the Scan screen, and on Samsung and Xiaomi phones also set the maker's own switch the card names.

### Calls do not ring, or messages arrive late

Android's battery optimisation can cut Clairix's connection to the relay while the phone sleeps. Then calls do not ring and messages wait until you open the app.

1. In Clairix, open **Comms > Settings** and look for the battery row. If it says "optimised", tap **Allow**.
2. Android shows a one-tap dialog. Choose **Allow**. On phones that do not offer the dialog, Clairix opens the battery optimisation list instead; find Clairix and set it to **Don't optimise** or **Unrestricted**.
3. Turn on **Keep Comms online in the background** in the same settings. A quiet notification stays up while the connection is held.

A connection watchdog checks the link every few minutes and reconnects when the relay has gone quiet, but it can only do that if Android lets Clairix run.

### Samsung phones

Samsung's One UI adds its own layer of battery management on top of Android's, and it is the most common reason Clairix is stopped in the background on Galaxy phones.

- After allowing unrestricted battery use as above, also open **Settings > Battery > Background usage limits** and make sure Clairix is not in **Sleeping apps** or **Deep sleeping apps**. Add it to **Never sleeping apps**.
- If **Adaptive battery** or **Put unused apps to sleep** is on, Samsung may still sleep Clairix after a few days without use. Opening the app resets this. Menu names vary by One UI version; look for anything that mentions sleeping or background usage.
- Clairix recognises Samsung's own system apps, Galaxy Store installs and Knox components in the spyware check, so a stock Galaxy should not show them as suspicious. If a preloaded Samsung app is flagged, send us the package name shown on the card and we will add it to the known list.
- Biometric unlock: on some phones, including some Samsung models, the system reports an invalidated biometric key in an unusual way. Clairix handles this, but if biometric unlock stops working after you add a fingerprint, that is by design: enrolling a new fingerprint or face invalidates the key. Enter your passcode and switch biometric unlock on again in Settings.
- Clairix uses the phone's StrongBox security chip for keys where one is present, which includes recent Galaxy flagships.

### The scan is running but nothing is listed

That is normal. Clairix lists only devices worth your attention: confirmed followers, devices that have stayed with you, recognised trackers heard a few times, and anything heard many times. Headphones, cars, phones and TVs that are recognised as benign are filtered out. The notification tells you how many devices are being watched. The **Nearby** count at the top of the screen shows everything that is in range.

### "Following" never confirms, even for my own tracker

Confirmation needs movement. By default a device has to be heard with you for about ten minutes across about three hundred metres while you are actually travelling. Walk or drive somewhere with the tracker and the phone, with the scan running. If you never get a GPS fix (an indoor test, for example), Clairix will not make the stronger claim, because without position it cannot tell a tracker in your bag from one in the next room. You can lower both thresholds in Settings. The fixes also have to be fresh, and your own travelled distance has to account for the separation: a position that jumps or wanders while the phone sits still does not count, which is why a test indoors usually confirms nothing.

### The self-test says it cannot hear its own signal

Some phones' Bluetooth controllers do not report their own advertisements to the scanner. On those phones the self-test proves that the transmitter works, and a second phone running Clairix is what proves the scanner works. The self-test screen says which case applies.

### The hidden-camera sweep shows "washed out" or "too bright"

- **Lens glint mode** needs a scene that is not saturated. Close the blinds, move away from mirrors and windows, and hold the phone at arm's length.
- **IR mode** needs a dark room. If the mean brightness is too high, Clairix will not report a dot, because it would mean nothing. Turn the lights off and wait a minute.
- A red circle that holds still is a candidate. Walk up to it and look. Most are screw heads or glossy edges that happened to hold still; the test is whether the pinpoint stays fixed as you change your angle.

### The network sweep found nothing

The sweep covers the Wi-Fi network your phone is joined to. Join the venue's network first. If the venue isolates guests from one another, the sweep cannot see other devices and will say so. A camera on its own hotspot shows up in the **Wireless** tab instead.

### The magnetic gauge says "unreliable"

The compass sensor is reporting that it needs calibration. Move the phone in a figure of eight for a few seconds, then try again. Keep it away from magnetic cases and metal furniture while the baseline settles.

### The cell tab says "RISK: NONE" but the baseline is young

The cell check learns what is normal over days. The screen shows the baseline's maturity and how many cells and visits it is built on. Until it is mature, a quiet result means "nothing stands out yet", not "all clear". Several rules also stay silent while you are moving, since travelling looks like a lot of cell switching.

### What Clairix looks like to another scanner, and what Settings shows

Clairix's own spyware check skips Clairix. Another app's stalkerware scanner may list it, because to do its checks Clairix holds some of the access a monitoring app wants:

- **Where it came from.** Clairix Full and the Comms edition are installed from an APK file, and Settings says so; the Play edition says Google Play.
- **Permissions.** Location, and "Allow all the time" if you let the scanner keep running in your pocket; the microphone, for calls, voice notes and the ultrasound listener; the camera, for the camera sweep and pairing codes; nearby devices; notifications. The scanner editions can also see every installed app, for the spyware check, and hold usage access and the alarms permission if you grant them.
- **Device admin.** Only once you switch on the unlock ledger. **Settings > Security > Device admin apps** then lists Clairix with two policies: watching lock-screen events and locking the screen.
- **Running in the background.** The scanner, and Comms when it stays online, run with a notification of their own.
- **What it does not hold.** No accessibility service, notification access, keyboard, VPN or permission to draw over other apps.

Run against itself, Clairix's own scoring gives Clairix Full 5 (high starts at 5): 2 for coming from outside a store and 1 each for the microphone, background location and the device-admin receiver; 7 with the unlock ledger's admin on. The Comms edition scores 3 and the Play edition 2, which is not listed. Other scanners weigh these things differently.

### I have no invite

Messaging, calls, SOS and the duress phrase need a Clairix number, and our relay gives one only to someone invited by a person already on it: they tap **Invite** in Comms, then send you the link or show you the QR code. The rest of Clairix works without a number: the tracker, camera, spyware and network checks, and the app lock.

If there is nobody you can ask:

- Look under **Relays you can join** in Comms setup. Each relay on the list says how to get on it. A relay whose operator has opened registration takes a limited number of new people a day with no invite and no secret: choose it, leave the enrollment secret empty and tap **Register**. If it says the day's numbers are gone, try again the next day.
- Run a relay of your own, on a server you rent or on a Cloudflare account, as the [relay's README](https://github.com/jakes1345/Clairix/tree/main/comms-worker) describes. You then make the invites yourself.

### Someone I do not know wrote to me, or someone will not leave me alone

A first message from a number that is not in your contacts waits under **Requests** at the top of Comms. Open it to read it; they are not told you did, and a call from them is noted as missed instead of ringing. **Accept** makes them a contact. **Decline** blocks the number and deletes what it sent. Someone who joined with an invite you made, or who writes in a group you are both in, is added as a contact instead; block them on their page if you need to.

To stop a contact reaching you, open their page (their name at the top of the conversation) and choose **Block** to keep the conversation as a record, or **Block and delete** to remove it too. Nothing they send reaches your phone afterwards, and they are not told. **Report** on the same page tells your relay's operator; do it before **Block and delete**, because a report names the last envelope they sent. Blocked numbers are listed in Comms settings, where you can unblock them.

While your phone is locked it cannot open anything, so a message from a blocked number or a request can still show as "New Clairix message", and a call as "Clairix call", until you unlock it. Then a blocked number's is dropped, and a request's call is noted as missed.

### I lost my phone, or wiped Clairix. Can you restore my Clairix number?

No. Your identity key is the only credential, and it existed only on that phone. Register again and re-pair with your contacts. This is deliberate: there is nothing on our side that could be stolen, subpoenaed or guessed.

### I forgot my app-lock passcode

There is no reset. The passcode is never stored, only a verifier that cannot be reversed. If wipe-on-failure is on, repeated wrong attempts will erase the app's data. Otherwise you can uninstall and reinstall, which also erases it.

## Report a bug

Write to support@clairix.app with:

1. **What you did**, step by step.
2. **What you expected** and **what happened** instead.
3. **Phone model, Android version and Clairix version.**
4. For Comms problems, the **diagnostics log**: in Comms settings, open Diagnostics and tap **Copy**. The log records connections and delivery events. It never contains message text.
5. For scanner problems, an **evidence report export** is helpful if you are comfortable sharing it. It contains device identifiers and positions, so send it only if you are. You can delete lines before sending.

Please do not send screenshots of conversations or anyone else's personal information. We will reply by email. We have no telemetry, so your report is the only way we learn about a problem.

For security vulnerabilities, use security@clairix.app instead, and read the [disclosure policy](/security#disclosure).
