# Hidden-camera sweep: bench validation

How the six methods of the hidden-camera sweep (Scan → Hidden cameras) are to be
checked against real hardware, and the table the results go in.

**No hardware run is recorded yet.** Every row of the results table below is
empty. Nothing in this repository shows any of the six methods run against a
real camera, illuminator or LED, and no detection or false-alarm rate for the
sweep has been measured. Until rows are filled in from real runs, the sweep is
tested only by code (listed at the end), and what the app says about it must not
claim more.

## What is tested by code today

These run on every change, with no camera:

| Method (in-app label) | What the code tests check | Where |
|---|---|---|
| Lens glint | The detector on synthetic frames: a reflection confirmed by switching the torch off, a light that stays lit, slow torches, hot pixels, washed-out frames | `android/app/src/test/.../analysis/GlintTrackerTest.kt` |
| Lens glint, IR illuminator | The analyzer's whole frame path on a phone or emulator, fed synthetic YUV_420_888 luma planes with row padding: a pinhole reported where it is on screen, a standby LED never reported, a still infrared dot on the mirrored front camera | `android/app/src/androidTest/.../analysis/LensGlintAnalyzerDeviceTest.kt` |
| IR illuminator | The IR view's self-test (can this front camera see a TV remote's infrared) | `IrSelfTestTest.kt` |
| Camera on the Wi-Fi | ONVIF WS-Discovery `ProbeMatch` parsing (camera, printer, another phone's probe), mDNS service types and HomeKit categories, web-page fingerprints, the verdict on a host (the router needs hard evidence), sweep coverage | `CameraEvidenceTest.kt`, `CameraSignatureTest.kt`, `NetworkSweepCoverageTest.kt` |
| Wi-Fi setup hotspot | Setup-mode SSIDs, including names that only look like camera prefixes | `CameraSignatureTest.kt` |
| Bluetooth setup beacon | Advertised names and provisioning services (a Tuya plug against a Tuya camera, headphones, GoPro) | `CameraEvidenceTest.kt` |
| Magnetic anomaly | The baseline: a magnet slid past, a step that stays, a change in the compass's accuracy | `MagneticBaselineTest.kt` |

Synthetic input proves the code does what it was written to do. It does not
prove that a real lens, at a real distance, under a real torch, produces the
input the code expects. That is what the bench run is for.

## Bench protocol

### Equipment

At minimum, one of each:

| Device under test | Why |
|---|---|
| A9 or SQ11-class mini camera (a no-brand clone) | The covert camera most often found in rentals; Wi-Fi setup hotspot, tiny lens |
| Wyze Cam v3 | Common consumer camera; 850 nm night vision; Bluetooth pairing |
| TP-Link Tapo C200 | Common consumer camera; ONVIF and RTSP once enabled |
| A Hikvision OEM mini camera (sold under another brand) | ONVIF on by default, web UI fingerprint, RTSP |
| Blink Mini | Cloud-only camera: should answer no network probe |
| An 850 nm IR illuminator | Faint red glow visible to the eye; what most night-vision cameras use |
| A 940 nm IR illuminator | Invisible to the eye; many phone front cameras filter it out |
| A smoke detector with a red status LED | The commonest false alarm for the optical passes |

Phones: at least two models with different camera hardware, one of them a
Pixel. Record the build of Clairix (version and commit) used for each run.

### Room

A furnished room of about 15 m², lights off and blinds closed for the optical
passes, on its own Wi-Fi network with nothing else joined but the phone and the
device under test (so a network finding can only be that device). A second
network, the venue's ordinary one with its routers, printers and televisions, is
used for the false-alarm sweep.

### Each method, each device

For each device under test, set up as a person hiding it would (powered,
running, in a shelf, a clock, a smoke-detector housing, behind a vent):

1. **Lens glint.** Back camera, torch on. Sweep the room slowly at 1 m, 2 m and
   3 m from the device. Record whether it is reported, at which distance first,
   and how long the phone was held on it.
2. **IR illuminator.** Front camera, lights off. Run the IR view's self-test
   (a TV remote pointed at the front camera) first and record its result. Sweep at 1 m and 3 m with the device's night
   vision on.
3. **Camera on the Wi-Fi.** Device joined to the test network. Run Scan the
   venue's Wi-Fi. Record whether it is reported, the confidence shown, and what
   the finding says (ONVIF, RTSP, web page, mDNS, ports).
4. **Wi-Fi setup hotspot.** Device reset to setup mode. Run Listen for setup
   beacons. Record the SSID it broadcast and whether it was reported.
5. **Bluetooth setup beacon.** Device in Bluetooth pairing mode where it has
   one. Same as above, with the advertised name.
6. **Magnetic anomaly.** Let the compass settle (the app says "Paused:
   recalibrate" until it has), then pass the phone along the surface the device
   is behind, 2 cm away. Record whether the anomaly showed.

### False alarms

With no device under test in the room, run every method for ten minutes on the
venue's ordinary network and in the furnished room with its ordinary lights,
chargers, routers and appliances. Count every finding at or above the alert
level (the app alerts at 70%) and every finding below it, separately.

### What a row records

One row per device, method, distance and phone. A result is a run that
happened; a method that could not be tried on a device (a Blink Mini has no
setup hotspot to catch once set up) is a row with "not applicable" and the
reason, not an empty cell.

## Results

**No hardware run is recorded yet.** Rows are added here as runs are made, with
the date of the run.

| Date | Device under test | Firmware | Method | Distance | Detected (y/n) | Confidence shown | False alarms in a 10-minute sweep (at alert / below) | Phone model and Android version | Clairix build | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | |
