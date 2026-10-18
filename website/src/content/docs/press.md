## Boilerplate

### 50 words

Clairix is a personal security app for Android. It finds hidden cameras, Bluetooth trackers and spyware using the phone's own camera and radios, and offers end-to-end encrypted messaging and calls. Detection runs on the device, with no ads, analytics or tracking. Clairix is free.

### 100 words

Clairix is a personal security app for Android built for everyday situations: a rental that might have a camera, a bag that might have an AirTag in it, a phone that an ex might have put spyware on. It sweeps a room for lens reflections and infrared, confirms a tracker as following only when it has moved with you, scores apps for stalkerware behaviour, and watches Wi-Fi and cell towers for the signs of interception. End-to-end encrypted messages and calls use the Olm double ratchet with sealed envelopes. Detection data never leaves the phone. Every feature is free.

### 250 words

Clairix is a personal security app for Android that puts counter-surveillance tools in the hands of people who have never needed them before: someone checking a holiday rental for a hidden camera, someone who suspects a tracker was slipped into their bag, someone whose former partner had access to their phone.

The hidden-camera sweep uses the phone's torch and camera to find the one thing a lens cannot hide, its reflection, and the front camera to spot the infrared lights night-vision cameras use. It then checks the venue's Wi-Fi for devices that stream video, listens for cameras still in setup mode, and offers a magnetic gauge for finding hidden mounts. A guided ten-step checklist ties it together.

Tracker detection recognises AirTags, Tile, Samsung SmartTag, Chipolo and other Bluetooth trackers, follows them through their address rotations, and raises an alert only when the same device has been heard with the user across real distance over real time. A locator, a trip memory for cars and the industry-standard tracker identification protocol help the user find and name the device.

The spyware check scores every installed app on the behaviours stalkerware depends on, audits trusted certificates, records unlock attempts, and warns when the screen is recorded or a microphone attaches itself. Wi-Fi and cell-tower monitoring look for the behaviour of interception equipment rather than for names on a list.

Encrypted messaging and calls use the Olm double ratchet (vodozemac) with sealed envelopes, so the relay sees neither content nor sender. There are no ads, no analytics and no trackers in the app, and detection data never leaves the phone.

Clairix is free to use and requires Android 12 or newer.

## Fact sheet

| | |
|---|---|
| **Name** | Clairix |
| **Website** | https://clairix.app |
| **Platform** | Android 12 and newer |
| **Distribution** | Google Play (package `app.clairix`); Clairix Full edition as a direct APK download from clairix.app |
| **Current version** | 2.7.0 |
| **Price** | Free. Every feature. |
| **Developer** | Clairix, an independent project |
| **Category** | Tools / personal security |
| **Languages** | English |

### What it detects

- **Hidden cameras:** lens glint (torch and rear camera), infrared illuminators (front camera, dark room), cameras streaming on the local Wi-Fi (RTSP, ONVIF, mDNS), cameras in setup mode (Wi-Fi hotspots and Bluetooth beacons), magnetic mounts (magnetometer). Guided ten-step room sweep.
- **Bluetooth trackers:** AirTag and Find My accessories, Tile, Samsung SmartTag and SmartTag 2, Chipolo and Chipolo Spot, Pebblebee, Orbit, Nut, Motorola tags, Invoxia, Eddystone beacons, and generic Bluetooth modules used in hardwired GPS trackers. Following confirmed by displacement over time (default about 10 minutes and 300 metres). Address-rotation tracking by payload fingerprint and radio cadence. Compass locator with distance estimate. DULT identification and sound. Vehicle trip memory. Self-test. Pattern-of-life report.
- **Spyware and phone integrity:** score-based stalkerware scan (sideload, hidden icon, keyboard, accessibility, notification listener, VPN, device admin, hidden background service); user-installed certificate audit; TLS interception canary; unlock ledger with "used while you slept"; biometric enrolment tripwire; screen-recording alert (Android 15+); microphone and camera use; wireless microphone attachment; root, bootloader, verified boot, SELinux, debugging and patch-age checks.
- **Networks and towers:** Karma-style rogue access point canary (zero false positives by construction); open carrier-named and evil-twin networks; known interception tool names; cell-tower baseline with scored indicators (technology downgrade, wrong-area cell, area change while stationary, signal outlier, unknown cell at a familiar place, ephemeral cell, rapid switching, no neighbours, near-zero timing advance).

### Private communication

- Random nine-digit Clairix numbers, not phone numbers; listed or unlisted.
- Pairing by QR code in person or by number plus safety-number comparison.
- Olm double ratchet (vodozemac, version-2 sessions) with forward secrecy and post-compromise security.
- Sealed envelopes (X25519, HKDF-SHA256, ChaCha20-Poly1305): the relay cannot read content or see the sender.
- Ed25519 request signing; no passwords.
- WebRTC voice calls with DTLS-SRTP; STUN or TURN that cannot decrypt.
- Voice notes, photos, video, groups, SOS.
- UnifiedPush wake-ups; no Google push service in the path.

### Emergency tools

SOS to groups with end-to-end encrypted location; duress phrase; scheduled fake call; app lock with hardware-backed verifier, wipe-on-failure and duress passcode; panic wipe.

### Privacy

- No ads, analytics SDK, advertising SDK or crash reporter.
- Scan results, timeline, location history, app list and unlock ledger stay on the device. Android backup disabled.
- Relay holds only the Clairix number, public keys, queued envelopes (30 days), push endpoint and unused invites (7 days).
- Full list of network destinations published at https://clairix.app/security.

### Technology

Kotlin and Jetpack Compose. Encryption in Rust (vodozemac) exposed through UniFFI. Relay as a Cloudflare Worker with Durable Objects. OpenStreetMap map data.

## Boundaries for coverage

We ask reporters to keep two things in mind:

- **Clairix does not claim to find everything.** The app states its limits in each feature, and we would rather be quoted on the limits than on a promise we did not make. Analogue wireless cameras, memory-card-only cameras, trackers without Bluetooth and root-level spyware are examples of what it cannot detect.
- **We publish no user numbers, testimonials or awards** unless they are real and verifiable. We have no analytics in the app, so we do not know how many people use it, and we will say so if asked.

Clairix is published without a named spokesperson. Written answers to questions are available by email and may be attributed to "the Clairix team".
