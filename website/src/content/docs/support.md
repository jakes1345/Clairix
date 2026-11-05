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
3. Turn on **Online** in the same settings. A quiet notification stays up while the connection is held.

A connection watchdog checks the link about every ten minutes and reconnects when the relay has gone quiet, but it can only do that if Android lets Clairix run. When Android refuses twice in a row to start the connection again, Clairix posts one notification, "Android stopped Clairix's connection" (just "Android stopped it in the background" while notification previews are hidden), which opens the setting that lets it run, and the Comms screen shows the steps for your phone, including the maker's own switch on Samsung and Xiaomi phones.

### Samsung phones

Samsung's One UI adds its own layer of battery management on top of Android's, and it is the most common reason Clairix is stopped in the background on Galaxy phones.

- After allowing unrestricted battery use as above, also open **Settings > Battery > Background usage limits** and make sure Clairix is not in **Sleeping apps** or **Deep sleeping apps**. Add it to **Never sleeping apps**.
- If **Adaptive battery** or **Put unused apps to sleep** is on, Samsung may still sleep Clairix after a few days without use. Opening the app resets this. Menu names vary by One UI version; look for anything that mentions sleeping or background usage.
- Clairix recognises Samsung's own system apps, Galaxy Store installs and Knox components in the spyware check, so a stock Galaxy should not show them as suspicious. If a preloaded Samsung app is flagged, send us the package name shown on the card and we will add it to the known list.
- Biometric unlock: on some phones, including some Samsung models, the system reports an invalidated biometric key in an unusual way. Clairix handles this, but if biometric unlock stops working after you add a fingerprint, that is by design: enrolling a new fingerprint or face invalidates the key. Enter your passcode and switch biometric unlock on again in Settings.
- The app-lock passcode key and the key that notices a new fingerprint or face use the phone's StrongBox security chip where there is one, which includes recent Galaxy flagships; Clairix's other keys live in the phone's trusted execution environment.

### The scan is running but nothing is listed

That is normal. Clairix lists only devices worth your attention: confirmed followers, devices that have stayed with you, recognised trackers heard a few times, and anything heard many times. Headphones, cars, phones and TVs that are recognised as benign are filtered out. The scanner's notification says only that it is scanning; what it is watching is on the Scan screen. The **Nearby** count at the top of the screen shows everything that is in range.

### The scanner's notification has no Stop button

With an app passcode set, the scanner's notification leaves out **Stop scanning**, so nobody holding your phone can switch the scanner off from the notification shade without the passcode. Stop it in the app, under **Scan > Trackers**. Without a passcode the button is there, and pressed on the lock screen it waits for you to unlock the phone.

### Clairix says a microphone connected while nothing was playing

While the scanner runs, a Bluetooth or USB microphone that connects with nothing playing and no call up is listed on **Phone check** and written to the timeline. If it is your own headset, earbuds or car, tap **This is my headset** there and it is not reported again; a paired accessory that was already connected on an earlier day is left alone anyway, and whatever was connected when you switched scanning on is only listed. You get a notification only if the microphone becomes the phone's call audio while the screen is off. Then find it under **Settings > Connected devices** and use **Forget** on anything you do not recognise.

### Which of Clairix's alerts can I silence?

Android lets you set each kind of notification separately under **Settings > Apps > Clairix > Notifications**. The phone's own alerts come on two: **Someone at your phone** (a new fingerprint or face, an unlock while you slept, a changed screen lock, unlock recording switched off, stalkerware, an intercepted connection) rings as an alarm, and **Phone settings to check** (Bluetooth left discoverable, a proxy, a screen recording, a scanner that cannot hear) arrives as an ordinary notification. If the reminders bother you, turn down the second and keep the first. Neither shows anything on the lock screen.

### "Following" never confirms, even for my own tracker

Confirmation needs movement. By default a device has to be heard with you for about ten minutes across about three hundred metres while you are actually travelling. Walk or drive somewhere with the tracker and the phone, with the scan running. If you never get a GPS fix (an indoor test, for example), Clairix will not make the stronger claim, because without position it cannot tell a tracker in your bag from one in the next room. You can lower both thresholds in Settings. The fixes also have to be fresh, and your own travelled distance has to account for the separation: a position that jumps or wanders while the phone sits still does not count, which is why a test indoors usually confirms nothing. While an app supplies mock locations (Developer options, Select mock location app), Clairix uses none of its positions and the Trackers screen says so: turn that app off.

### The self-test says it cannot hear its own signal

Some phones' Bluetooth controllers do not report their own advertisements to the scanner. On those phones the self-test proves that the transmitter works, and a second phone running Clairix is what proves the scanner works. The self-test screen says which case applies.

### The hidden-camera sweep shows "washed out" or "too bright"

- **Lens glint mode** needs a scene that is not saturated. Close the blinds, move away from mirrors and windows, and hold the phone at arm's length.
- **IR mode** needs a dark room. If the mean brightness is too high, Clairix will not report a dot, because it would mean nothing. Turn the lights off and wait a minute.
- A red circle that holds still is a candidate. Walk up to it and look. Most are screw heads or glossy edges that happened to hold still; the test is whether the pinpoint stays fixed as you change your angle.

### The network sweep found nothing

The sweep covers the Wi-Fi network your phone is joined to. Join the venue's network first. If the venue isolates guests from one another, the sweep cannot see other devices and says so: when only the router answers it ends as **Sweep inconclusive**, with the reason, instead of "No camera-like hosts found". It does the same when the network is wider than the block of 254 addresses around your phone that it checks, and when the Wi-Fi gives the phone no IPv4 address. Most consumer cameras (Wyze, Blink, Ring, Arlo, Nest and the like) talk only to their maker's cloud and are invisible to the sweep on any network; the optical, wireless and hands-on steps are what find them. A camera on its own hotspot shows up in the **Wireless** tab instead.

### The magnetic gauge says "unreliable"

The compass sensor is reporting that it needs calibration, so the gauge pauses and shows no verdict. Move the phone in a figure of eight for a few seconds; the gauge settles again by itself once the compass recovers. Keep it away from magnetic cases and metal furniture while the baseline settles.

### The cell tab says "RISK: NONE" but the baseline is young

The cell check learns what is normal over days. The screen shows the baseline's maturity and how many cells and visits it is built on. Until it is mature, a quiet result means "nothing stands out yet", not "all clear". Several rules also stay silent while you are moving, since travelling looks like a lot of cell switching.

### What Clairix looks like to another scanner, and what Settings shows

Clairix's own spyware check skips Clairix. Another app's stalkerware scanner may list it, because to do its checks Clairix holds some of the access a monitoring app wants:

- **Where it came from.** Clairix Full and the Comms edition are installed from an APK file, and Settings says so; a copy installed from Google Play, once that edition exists, will say Google Play.
- **Permissions.** Location, and "Allow all the time" if you let the scanner keep running in your pocket; the microphone, for calls, voice notes and the ultrasound listener; the camera, for the camera sweep and pairing codes; nearby devices; notifications. The scanner editions can also see every installed app, for the spyware check, and hold usage access and the alarms permission if you grant them.
- **Device admin.** Only once you switch on the unlock ledger. **Settings > Security > Device admin apps** then lists Clairix with two policies: watching lock-screen events and locking the screen.
- **Running in the background.** The scanner, and Comms when it stays online, run with a notification of their own. Clairix asks to be exempted from battery optimisation so they keep running; once you allow it, Android's battery settings list Clairix as unrestricted.
- **What it does not hold.** No accessibility service, notification access, keyboard, VPN or permission to draw over other apps.

Run against itself with every permission it asks for granted, Clairix's own scoring gives Clairix Full 6 (high starts at 5): 2 for coming from outside a store and 1 each for the microphone, background location, the camera and the device-admin receiver; 8 with the unlock ledger's admin on, or 9 if you have also let it run unrestricted by battery optimisation. A permission you have not granted counts for less. The Comms edition scores 4; a copy installed from Google Play, once that edition exists, will score 2, which is not listed. Other scanners weigh these things differently.

### I have no invite

Messaging, calls, SOS to your groups and the duress phrase need a Clairix number, and our relay gives one only to someone invited by a person already on it: they tap **Invite** in Comms, then send you the link or show you the QR code. The rest of Clairix works without a number: the tracker, camera, spyware and network checks, and the app lock.

If there is nobody you can ask:

- Look under **Relays you can join** in Comms setup. Each relay on the list says how to get on it. Today the list holds only our relay, which takes invites only, so this way in is not open yet. A relay whose operator has opened registration takes a limited number of new people a day with no invite and no secret: choose it, leave the enrollment secret empty and tap **Register**. If it says the day's numbers are gone, try again the next day.
- Run a relay of your own, on a server you rent or on a Cloudflare account, as the [relay's README](https://github.com/jakes1345/Clairix/tree/main/comms-worker) describes. You then make the invites yourself.

### Someone I do not know wrote to me, or someone will not leave me alone

A first message from a number that is not in your contacts waits under **Requests** at the top of Comms. Open it to read it; they are not told you did, and a call from them is noted as missed instead of ringing. **Accept** makes them a contact. **Decline** blocks the number and deletes what it sent. Someone who joined with an invite you made, or who writes in a group you are both in, is added as a contact instead; block them on their page if you need to.

To stop a contact reaching you, open their page (their name at the top of the conversation) and choose **Block** to keep the conversation as a record, or **Block and delete** to remove it too. Nothing they send reaches your phone afterwards, and they are not told. **Report** on the same page tells your relay's operator; do it before **Block and delete**, because a report names the last envelope they sent. Blocked numbers are listed in Comms settings, where you can unblock them.

A report in the app does not say who sent it, so we cannot answer it. To report a number on our relay and hear back, write to support@clairix.app with the number, what happened and when, and screenshots of the conversation that show the number; this is the one time we ask for screenshots. We reply within five business days, the same day for threats of violence, and we may suspend the number: it then cannot sign in on our relay, and nothing sent to it is delivered. A suspended number can appeal at the same address. How we weigh reports and appeals is our [abuse policy](https://github.com/jakes1345/Clairix/blob/main/docs/ABUSE.md).

While your phone is locked it cannot open anything, so a message from a blocked number or a request can still show as "New Clairix message" until you unlock it. Then a blocked number's is dropped, and a request's call is noted as missed. Neither can ring or show an SOS on the locked phone: your relay passes those alerts on only with envelopes from your contacts.

### I lost my phone, or wiped Clairix. Can you restore my Clairix number?

We cannot: your identity key is the only credential, and we never have a copy. You can, if you saved a recovery file before (Comms settings → Save a recovery file). On the new phone, open Comms and choose **Restore from file**, then type the recovery code you wrote down. You get the same number back while your relay still has your mailbox; after a wipe, or if the number was deleted, the same keys are registered again under a new number, and your contacts' phones are told it. Your contacts, groups and verified keys come back, and contacts see the same safety number; your messages do not come back. Without a recovery file, register again and re-pair with your contacts. There is still nothing on our side that could be stolen, subpoenaed or guessed.

### I forgot my app-lock passcode

There is no reset. The passcode is never stored, only a verifier that cannot be reversed. If wipe-on-failure is on, repeated wrong attempts will erase the app's data. Otherwise you can uninstall and reinstall, which also erases it.

### Exporting and checking an evidence report

**Export evidence** in Settings, or **Export evidence report** on Scan → Trackers, writes a text report; **Export as JSON** beside each writes the same report as JSON. With a passcode set, each asks for it first. Then Clairix asks for the period (From and To; everything it has kept, by default) and warns you that the report holds your own location history and device identifiers. Photos you kept in the hidden-camera sweep during that period go with the report as separate pictures. The report and the pictures are deleted from the phone a day later; the share goes wherever you send it.

Every report ends with a SHA-256 of its text and a signature (ECDSA P-256 with SHA-256) by a key Clairix makes in your phone's Android Keystore the first time you export. The public key and its fingerprint are at the top of the report, and the fingerprint is shown on the phone when you export. To check a text report on a computer with OpenSSL, save it as report.txt and run the commands printed at its end, which fill in the key and the signature for you:

```
sed '/^=== INTEGRITY ===$/,$d' report.txt > body.txt
openssl dgst -sha256 body.txt
echo '<public key from the report>' | base64 -d > key.der
openssl dgst -sha256 key.der
openssl pkey -pubin -inform DER -in key.der -out key.pem
echo '<signature from the report>' | base64 -d > sig.der
openssl dgst -sha256 -verify key.pem -signature sig.der body.txt
```

For a JSON report, saved as report.json, the first command is `head -n 1 report.json | sed -e 's/^{"report"://' -e 's/,$//' | tr -d '\n' > body.txt` and the rest are the same; the report lists them under `integrity`. The second command prints the hash at the end of the report, the fourth the key fingerprint, and the last "Verified OK" when not one character of the report was changed after the phone wrote it. Compare the fingerprint with the one the phone shows to know the key is that phone's. Wiping or reinstalling Clairix makes a new key. A check says nothing about who held the phone.

A key made by a Clairix newer than 3.0.0-beta.8, on a phone that supports key attestation, also comes with the certificates the Keystore keeps for it: the report gives them in base64 (`key_attestation_chain` in the JSON) and says whether the first is for the report's key and whether each is signed by the next. Clairix does not compare the last one with Google's published attestation roots or revocation list, which needs the internet; you can. A key made earlier has none, and the report says so.

The report also carries the timeline's chain head: every timeline entry stores the SHA-256 of the entry written before it, and the JSON report gives each entry's `seq`, `prev` and `hash` and how the hash is made (`timeline_chain`). Keep the head, and a later copy of the same entries can be checked for anything changed, removed or moved after this export. It does not prove when an entry was written, and someone in control of the phone could have rewritten the whole timeline before the export.

## Report a bug

The quickest way is **Report a problem** in Settings → About: it opens your mail app on a message to support@clairix.app with the Clairix version, phone model and Android version filled in, and nothing else. You write the rest and send it yourself. Or write to support@clairix.app with:

1. **What you did**, step by step.
2. **What you expected** and **what happened** instead.
3. **Phone model, Android version and Clairix version.**
4. **If Clairix crashed**, the crash record: after a crash, Settings → About shows "Clairix crashed last time" with **Copy** and **Share**. It holds the error's stack trace (code locations, never the error text), the app version, the phone model and the Android version: nothing from your conversations, no places, no identifiers. It stays on your phone until you send it, and **Delete** removes it.
5. For Comms problems, the **diagnostics log**: in Comms settings, open Diagnostics and tap **Copy**. The log records connections and delivery events. It never contains message text.
6. For scanner problems, an **evidence report export** is helpful if you are comfortable sharing it. It contains your own location history, device identifiers and positions, so send it only if you are, and pick a short period. You can delete lines before sending; that breaks its signature, which does not matter for a bug report.

Please do not send screenshots of conversations or anyone else's personal information. We reply by email within five business days. We have no telemetry, so your report is the only way we learn about a problem.

For security vulnerabilities, use security@clairix.app instead, and read the [disclosure policy](/security#disclosure).
