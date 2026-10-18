# Clairix

Clairix is a personal security app for Android. It helps you find hidden cameras, detect spyware, and talk privately — all from your phone.

---

## What does it do?

### Find hidden cameras
Point your phone around a room. Clairix uses your camera to look for the tiny reflective lens that all cameras have, even tiny hidden ones. If it finds one, it draws a circle around it on screen.

### Find trackers following you
AirTags, Tile trackers, and other Bluetooth tracking devices broadcast a signal every few seconds. Clairix watches for those signals and tells you if the same device keeps showing up wherever you go.

### Detect fake cell towers
Some surveillance equipment pretends to be a cell tower to intercept calls. Clairix learns your normal towers and alerts you when a suspicious one appears.

### Scan for spyware
Clairix checks every app on your phone for signs of spyware — apps that can read your keystrokes, apps that quietly use your microphone, and apps that have special "device admin" powers that make them hard to delete.

### Check your network
Clairix can detect fake Wi-Fi hotspots (used by attackers to steal your passwords) and check whether anyone is intercepting your encrypted connections.

### Talk privately
Clairix has built-in encrypted calls and messages. Nothing goes through regular phone lines. Everything is end-to-end encrypted, meaning only you and the person you're talking to can read or hear it.

### Navigate offline
Full turn-by-turn navigation that works without the internet, using open map data.

### Read your car's data
Connect a Bluetooth OBD-II adapter and Clairix shows live engine data, trip history, and alerts you if your car moves without you.

### Listen to aircraft and radio
Plug in an RTL-SDR USB stick and Clairix can decode aircraft transponder signals (ADS-B) and receive FM radio.

---

## Emergency features

- **Panic wipe** — one tap deletes everything in the app, unregisters from the relay server, and closes itself
- **Duress phrase** — type a secret sentence into any Clairix chat and it silently sends your GPS location as an SOS
- **Fake call** — pretend your phone is ringing to safely exit a situation
- **App lock** — requires a passcode to open Clairix; wrong passcode too many times wipes it

---

## How do I build it?

You need:
- Android Studio
- Rust + cargo-ndk (for the encrypted comms library)
- An Android NDK

```bash
git clone https://github.com/jakes1345/Clairix
cd Clairix/android
./gradlew assembleDebug
```

The APK will be at `android/app/build/outputs/apk/full/debug/app-full-debug.apk`.

---

## Requirements

- Android 12 or newer
- The camera, location, microphone, and Bluetooth permissions (asked when you need them)

---

Map data © OpenStreetMap contributors, ODbL.
