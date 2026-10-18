## Common problems

### "Location permission required to scan"

The tracker scanner is a location service. Android will not start it without location access.

1. Open Android **Settings > Apps > Clairix > Permissions > Location**.
2. Choose **Allow all the time**. "While using the app" is enough for the scan to run while Clairix is on screen, but confirming that a tracker is following you needs the scan to keep running in your pocket.
3. Make sure **Location** itself is turned on in the quick settings panel.

### "Nearby devices permission not granted" or "Bluetooth is off"

Tracker detection needs the **Nearby devices** permission and Bluetooth turned on. Grant the permission under **Settings > Apps > Clairix > Permissions**, and switch Bluetooth on. If you turn Bluetooth on after starting the scan, Clairix picks it up automatically.

### "Android blocked starting the scanner in the background"

Android 12 and newer refuse to start a foreground service from the background, for example after a reboot or from a stale notification. Open Clairix and tap **Start** on the Scan screen. If you want the scan to resume after reboots, turn on **Resume after reboot** in Settings and exempt Clairix from battery optimisation (below).

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

Confirmation needs movement. By default a device has to be heard with you for about ten minutes across about three hundred metres while you are actually travelling. Walk or drive somewhere with the tracker and the phone, with the scan running. If you never get a GPS fix (an indoor test, for example), Clairix will not make the stronger claim, because without position it cannot tell a tracker in your bag from one in the next room. You can lower both thresholds in Settings.

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

### The spyware check flags Clairix Full itself

Clairix Full is installed from outside a store, which is one of the signals the stalkerware scan looks for. The check is working as intended. The Play edition is not flagged.

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
