# ACCESS_BACKGROUND_LOCATION: location permissions declaration

Declared in `android/app/src/suite/AndroidManifest.xml`. Never requested at
first launch or by the scanner itself: only when the owner turns on
**Resume after reboot** in Settings.

## The one feature (paste into the console)

> Tracker detection. Clairix's tracker scanner looks for Bluetooth trackers
> (AirTags, SmartTags, Tiles and others) that someone may have hidden on the
> user to follow them. A tracker is only a threat if it moves with the user, so
> the scanner compares where each Bluetooth device is heard with where the
> phone is, over time, while the user goes about their day with Clairix closed.
> The scanner runs as a location foreground service with an ongoing
> notification, started by the user (Scan > Trackers > Start scanning). Location
> access "all the time" is needed for one thing more: when the user turns on
> "Resume after reboot", Android only lets the scanner start again by itself
> after the phone restarts, or after Clairix is updated, if location is allowed
> all the time. Without it the scanner waits until the user next opens Clairix.
> The location history this builds stays on the phone, encrypted, and is never
> uploaded; it is deleted after the retention the user picks (30 days by
> default).

## What the code does

- `BootReceiver` (`android/app/src/main/java/app/clairix/BootReceiver.kt`): on
  `BOOT_COMPLETED` (only with Resume after reboot on) and `MY_PACKAGE_REPLACED`,
  starts `ScanService` if the scanner was on and `BLUETOOTH_SCAN`,
  `ACCESS_FINE_LOCATION` and `ACCESS_BACKGROUND_LOCATION` are all granted. Android
  refuses a location-typed foreground service started from those broadcasts
  without background location, so this is the only path that needs it.
- `ScanService` (`foregroundServiceType="location"`, suite manifest) is the
  scanner. It reads positions through `analysis/LocationClient.kt` and keeps the
  location log the following detection reads (`analysis/Geo.kt`,
  `analysis/Persistence.kt`).
- `SettingsScreen.kt`, "Resume after reboot": the prominent disclosure dialog,
  then the system request. The switch is stored on only once the permission is
  actually held.

## Prominent disclosure (in the app, before the request)

Shown when the owner turns on **Resume after reboot** and the permission is
not yet held (`SettingsScreen.kt`, `showBackgroundDialog`):

> **Allow location all the time?**
>
> Clairix uses your location when the app is closed to keep the tracker scan
> running and to tell whether a Bluetooth device is moving with you, including
> after the phone restarts. Your location history stays on this phone and is
> never uploaded.
>
> Android opens the location permission page for Clairix next: choose "Allow
> all the time" there, then come back.

Buttons: Continue (opens the system request), Cancel. Privacy policy section 5
says the same: "Background location is used only by the tracker scanner you
start, to tell whether a device is moving with you. Location history stays on
the device."

## Video script (30 seconds)

Record on a test phone with the Play build, screen recording on, no other app
in shot. Captions in brackets are added in editing.

| Time | On screen | Caption |
|---|---|---|
| 0:00-0:04 | Clairix open on Scan > Trackers. Tap **Start scanning**; the ongoing scanner notification appears. | [The tracker scanner, started by the user] |
| 0:04-0:08 | Open Settings, scroll to **Resume after reboot**, turn it on. | [The only place background location is asked for] |
| 0:08-0:15 | The "Allow location all the time?" dialog. Hold on it for the full text. Tap **Continue**. | [Prominent disclosure: use while closed, stays on the phone] |
| 0:15-0:20 | Android's location permission page for Clairix. Choose **Allow all the time**, go back. The switch is on. | [System permission] |
| 0:20-0:24 | Press Home: Clairix is closed, the scanner notification stays. Pull down the shade to show it. | [Scanning continues with the app closed] |
| 0:24-0:30 | Cut to the phone restarting, then the lock screen with the scanner notification back, Clairix not opened. | [After a restart the scan resumes by itself] |

## If the declaration is refused

The feature degrades without it and nothing else breaks: `BootReceiver`
returns early without the permission and the scanner restarts the next time
Clairix is opened (`MainActivity.onResume`). Removing the permission from the
Play build alone would mean moving the `<uses-permission>` line from
`src/suite` to `src/fullSuite`, and the Settings switch would have to say that
the Play edition cannot resume after a reboot. Decide that only after a
refusal, and change the switch's text with it.
