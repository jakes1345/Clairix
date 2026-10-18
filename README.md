# Clairix

**Your personal security app for Android.**
Clairix helps you find hidden cameras, catch spyware, and talk to people privately — all from your phone, no special skills needed.

---

## What can it do?

| What you want to do | How Clairix helps |
|---|---|
| 🎥 Find a hidden camera | Point your phone around the room — Clairix spots the tiny lens glint every camera has |
| 📍 Know if someone is tracking you | It watches for AirTags and Tile trackers following you and alerts you |
| 📡 Catch a fake cell tower | It learns your normal towers and warns you when a suspicious one shows up |
| 🦠 Check for spyware | It scans every app on your phone and flags anything that can read your passwords or listen to you |
| 📶 Spot a fake Wi-Fi | Detects hotspots set up by attackers trying to steal your passwords |
| 💬 Talk without being listened to | Calls and messages are fully encrypted — only you and the other person can read them |
| 🗺️ Navigate without internet | Full turn-by-turn directions using offline map data |
| 🚗 See your car's health | Connects to your car via Bluetooth and shows live engine data and trip history |
| ✈️ Track aircraft nearby | Plug in an SDR stick and see real aircraft on screen using ADS-B signals |

---

## Emergency features

These are there for when things get serious.

- 🔴 **Panic wipe** — one tap and everything in the app is gone, permanently
- 🤫 **Duress phrase** — type a secret word in any chat and Clairix quietly sends your location as an SOS
- 📞 **Fake call** — makes your phone ring so you can safely walk away from a bad situation
- 🔒 **App lock** — needs a passcode to open; too many wrong tries and it wipes itself

---

## How to build it

**You need:**
- [Android Studio](https://developer.android.com/studio)
- [Rust](https://rustup.rs) + `cargo-ndk` (for the encrypted calls library)
- Android NDK (Android Studio can install this for you)

**Then run:**
```bash
git clone https://github.com/jakes1345/Clairix
cd Clairix/android
./gradlew assembleDebug
```

Your APK will be here when it's done:
```
android/app/build/outputs/apk/full/debug/app-full-debug.apk
```

---

## Requirements

- Android 12 or newer
- Permissions for camera, location, microphone, and Bluetooth — Clairix asks for each one only when it actually needs it

---

*Map data © OpenStreetMap contributors, ODbL.*
