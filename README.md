# Clairix

**A security app for a stock Android phone.**

Clairix finds hidden cameras, trackers, fake cell towers and spyware, gives
you encrypted messages and calls that keep working when other things do not,
and navigates without the internet. It is built for the phone you already
have: no root, no custom ROM, no unlocked bootloader, because most people
cannot get those, and a locked phone from a carrier is still a phone that
needs protecting.

This README says what the app does, who it is built to stand up to, and,
just as plainly, what it cannot do.

---

## What it does

| What you want to do | How Clairix helps |
|---|---|
| Find a hidden camera | Point the phone around the room and it spots the lens glint every camera has |
| Know if someone is tracking you | Watches for AirTags, Tiles and similar trackers, and says FOLLOWING only once the same device has been heard at two places far apart |
| Catch a fake cell tower | Learns the towers that are normal for the places you go and warns when one breaks the rules |
| Check for spyware | Scans every app on the phone and flags anything that can read your screen, your passwords or your microphone, and checks the phone itself: installed certificates, proxies, USB debugging, patch level |
| Spot a fake Wi-Fi | Detects hotspots set up to impersonate the ones your phone already trusts |
| Talk without being listened to | Messages and calls are end-to-end encrypted on the phone and sent as sealed envelopes the relay cannot open and is not told the sender of |
| Navigate without internet | Turn-by-turn directions from signed offline map files, with anonymous crowd-sourced traffic and incident reports when you are online |
| Know where the plate readers are | Flock Safety and other licence-plate cameras mapped in OpenStreetMap are shown on the map, spoken before you reach one, counted per route and routed around by default; a Flock camera battery, Raven gunshot detector or Flock hotspot heard over radio is reported too, matched against the map or flagged as unmapped |
| Know if your GPS is lying | Checks that the satellite signals are from satellites: strengths too even or too strong, satellite time against network time, the GPS position against Wi-Fi and cell towers, impossible jumps, mock-location apps, and jamming; navigation warns on the map |
| Hear the inaudible | Listens for the ultrasonic beacons ad-tracking kits play from TVs, shops and web pages to tie your phone to them, after a self-test that proves the microphone can hear that band |
| Know where everything is | A guided tour of every screen and tool on first launch, from Settings any time after |
| Know what your Bluetooth gives away | Shows what a roadside or in-store sensor can log: the phone's name (renamed to the model in one tap if it is yours), whether it is discoverable, and which paired accessories keep one address for life and were connected on your drives |
| See your car's health | Connects to the car over Bluetooth OBD and shows live engine data and trip history |
| Track aircraft nearby | Plug in an SDR stick and see real aircraft on the map from their ADS-B broadcasts |

---

## Who it is built against

Clairix assumes the adversary is a state: one that can compel the companies
the app depends on (Cloudflare, which runs the relay; GitHub, which hosts the
releases and the map files; your carrier), watch the network, seize the phone,
and may hold an exploit for the operating system. Everything below is written
for that case. A jealous partner, an employer or a thief gets the same
protection with far less effort.

### What it protects

**What you say.** Every message, voice note, photo and call is encrypted on
your phone for one contact with the Olm double ratchet (the audited
[vodozemac](https://github.com/matrix-org/vodozemac) library), then sealed
in an anonymous envelope to the recipient's key. The relay stores and forwards
envelopes it cannot open. A compelled relay, or a compelled Cloudflare, hands
over ciphertext. Call audio is DTLS-SRTP between the two phones; a TURN server
in the path forwards packets it cannot decrypt.

**Who you talk to, from the relay's records.** Sending an envelope is not
signed and names no account: it spends a single-use token your phone fetched
earlier, in a batch, over its signed channel, and the relay keeps only each
token's hash in a store that never learns which account it went to. Looking
someone up names them in a signed body, never in a URL that a request log
could keep. Nothing a mailbox stores says when its owner was last here:
limits are kept to the hour, nonces to the minute, registrations to the day.
Traffic and incident reports are sent the same way, under a pseudonym the
phone makes up for each drive and replaces every ten minutes. An image of the
relay's storage shows public keys, queued envelopes with their recipient, size
and time, and nothing about senders, lookups or activity.

**What is on the phone when it is seized locked.** The location log, the
timeline, the cell-tower baseline, vehicle trips, the unlock ledger, the
whole Comms database (contacts, messages, groups), the Comms settings, keys
and saved cards are sealed on disk under keys in the phone's secure hardware, so a
copy of the app's files is ciphertext. The app lock wipes after a run of wrong
passcodes (three by default; you can choose the number, or none), a separate
duress passcode wipes at once and shows nothing unusual, and the "Wipe
Clairix" page wipes on demand. Locking collapses every notification into
"Unlock to read", stops read receipts, disarms a card armed for NFC readers,
and every screen is kept out of screenshots and the recents thumbnail. History
older than the retention you choose (30 days by default) is deleted, so there
is less to give up in the first place.

**What you install and download.** Releases, offline map graphs and work-zone
files are signed with the project's Ed25519 key, and the app refuses a map or
work-zone file whose signature does not verify. Every workflow action is pinned
to a commit. The app trusts only the system's certificate authorities, never a
certificate someone installed on the phone, and refuses cleartext; the
Certificates page can pin the relay's key so that a certificate from a
compelled authority is refused too.

**Where you go.** Routing runs from map files on the phone, so directions
keep working with no connection, and so does searching for a road: the map's
road names are indexed on the phone, and a dropped pin is named after the
nearest road from the same map, so a destination is never sent anywhere to be
found or named. Searching online for an address or a place is a separate tap
that says it sends the text to OpenStreetMap. The map's background tiles come
from OpenStreetMap's servers when you are online, which see your IP address and
the area you are looking at; the GPS track never leaves the phone, and a trip's
first 500 m and final approach are never reported to traffic.

### What it cannot do

Read this part twice.

- **A compromised operating system.** Clairix is an ordinary app. If the
  phone's OS, baseband or a system app is working for the adversary, it can
  read what Clairix reads and see what you type. Clairix can notice some
  signs (root, an unlocked bootloader, failed verified boot, an added
  certificate, a proxy, USB debugging, a microphone or camera in use, a
  screen recording) and tell you. It cannot stop or remove an implant below
  it, and it does not claim to remove viruses.
- **A phone seized while unlocked**, or taken from your hand with Clairix
  open. Whatever the app can show you, the person holding it can see. The
  wipe needs you to reach it, or the wrong-passcode limit to be hit.
- **Where you are, from the network.** Your carrier, and anyone who compels
  it, always knows roughly where a phone is from the towers it talks to, and
  the law in most countries lets it keep that. Clairix can detect a fake
  tower; it cannot hide you from a real one. Nothing an app can do changes
  this. Airplane mode, or leaving the phone behind, is the only answer.
- **Traffic analysis.** Anyone watching the network sees that your phone
  talks to the relay and when. Envelopes are padded to a few fixed sizes, and
  "Cover traffic" (Comms settings, off by default) sends envelopes nobody
  reads to random contacts at random intervals, so the real ones are not the
  only traffic there is. The relay holds each envelope a random few seconds
  (mean four, at most twenty) and releases what is due together in shuffled
  order, so traffic in cannot be paired with deliveries out by timing alone;
  call set-up, SOS and group membership changes skip the wait. That is a
  single mixing relay, not a mix network: the relay itself still sees every
  envelope arrive and leave, on a quiet relay there is little to mix with,
  and someone watching both ends for weeks can still correlate by volume. The relay's
  operator, or the platform under it, could capture live requests and see the
  address each one came from. None of this is written down, but it is
  possible while a request is in flight. From 3.0.0 Beta 6 the tokens a send
  spends are blind (RFC 9497, as Privacy Pass): the phone makes each one and
  the relay signs it without seeing it, so not even a relay that recorded
  every request can tell which account a send's token came from. If your IP address matters, use Tor or a VPN. A call hands your
  address to the contact's phone, answered or not, unless "Relay-only calls"
  is on.
- **Relays.** Anyone can run a relay (comms-worker), and a contact on
  another relay is added by scanning their QR code: everything for them goes
  to your relay, which forwards it to theirs signed with its own key, so
  their relay sees your relay's address and never your phone's. Relays do
  this out of the box (an operator can turn it off); the contact's page says
  which relay they are on. An order can shut a relay down or make it refuse you;
  it cannot make it read your messages, and people on other relays are not
  affected. The operator can publish other addresses the same relay answers
  at, and the app falls back to them when the first is blocked (unless you
  have pinned the relay's key, in which case only the pinned address is
  used). With no network at all, the offline mesh carries texts and SOS
  phone to phone over Bluetooth. ClairixCoin stays within one relay.
- **Your keyboard.** It sees everything typed in every app before any app can
  encrypt it. Clairix asks it not to learn from what is typed in Clairix, which
  Gboard and Samsung's keyboard honour, and Phone check says whether the
  keyboard in use can reach the internet. A keyboard is free to ignore the
  request; one with no internet access cannot send anything anywhere.
- **Your contacts' phones.** A delivered message is theirs. A contact whose
  phone is compromised or seized unlocked gives up every conversation with
  them.
- **Detection is best-effort.** No app finds every camera, tracker or piece
  of spyware. A finding is a reason to look, not proof.
- **The keys are not bound to the phone being unlocked.** The scanner has
  to write while the keyguard is up, so the keys that seal the records work
  while the phone is locked; code running as the app on an unlocked phone
  can read what the app can. The settings files (thresholds, the sleep
  window, the passcode's verifier, TLS pins) are not encrypted; they hold
  settings, not records.
- **No independent audit.** vodozemac has been audited. Clairix has not.
  `docs/SECURITY-FINDINGS.md` is the running list of what has been found,
  what is fixed and what is still open.

### What the relay keeps

Per Clairix number: the public keys; envelopes waiting for the phone, deleted
when collected or after 30 days; a UnifiedPush endpoint if you set one;
unused invite codes for 7 days; the hashes of unspent tokens for about 14
days; a note that each key belongs to that number, so nobody else can
register it. For traffic: anonymous speed samples for 2 hours, learned
road-segment positions for 30 days, incidents until they expire. Never a
name, a phone number, an email address, a contact list, a sender, a lookup or
a last-seen time. The full account is in [`comms-worker/README.md`](comms-worker/README.md).

---

## Emergency features

- **Wipe Clairix.** In the Comms menu: type WIPE, one button. It cancels every
  notification, clears the clipboard, unregisters push, gives up the
  device-admin role, disarms any card, asks the relay to delete the account
  (waiting at most four seconds), destroys every key in the secure hardware
  and deletes every file. The same wipe runs when the duress passcode is
  entered at the lock screen or the wrong-passcode limit is hit.
- **Duress phrase.** A sentence you can type into any chat. It is sent as
  written; underneath, an SOS with your last known position goes to every
  group you are in. In no group it sends nothing, and the Duress phrase page
  says so in red until you join one.
- **SOS.** Hold the red octagon until it fills and confirm: your position goes,
  end-to-end encrypted, to your groups.
  Location is read at that moment and at no other time.
- **Fake call.** Rings the phone with its real ringtone and a real-looking
  call screen, so you can walk away.
- **App lock.** A passcode for Clairix itself, with optional biometrics.
  Wrong passcodes wipe after a limit you choose, or lock you out for longer
  each time if you turn the wipe off. The passcode is never stored; only a
  verifier made with a hardware key is.

---

## Settings that change what the adversary gets

- **Hide names and previews in notifications** (Comms settings, on by
  default once a passcode is set): notifications name nobody and quote
  nothing.
- **Relay-only calls** (Comms settings): call audio always goes through the
  relay's TURN server, so a contact never learns your IP address from a
  call. Off by default; a call cannot connect while the relay has no call
  servers.
- **Cover traffic** (Comms settings): envelopes nobody reads, to random
  contacts at random intervals while the background connection is on. Off by
  default; costs a little data and battery.
- **Refuse the relay if its key changes** (Phone check → Certificates): pins
  the relay's TLS key. Off by default because the relay's key rotates with its
  certificate; turn it on if you would rather lose the relay than trust a
  new certificate without checking.
- **Wipe after N wrong passcodes** (Settings → App lock): 1, 3, 5, 10 or
  never.
- **Keep history for** (Settings, with Export and Clear): 7, 30 or 90 days,
  or forever. 30 by default. The location log, the timeline, vehicle trips
  and the unlock ledger older than that are deleted; the cell-tower baseline
  is kept.
- **Route around known plate readers** and **Say when one is ahead** (Drive,
  route options): both on by default. The list is what OpenStreetMap has,
  largely from the DeFlock project; a camera nobody has mapped is not on it,
  so a route the app calls clear may not be. Avoiding one is worth a detour
  of up to two and a half minutes to the router; the route card says how
  many each route still passes.
- **Alert on surveillance hardware** (Settings → Scanning): on by default.
  A notification when a Flock Safety camera battery pack, a Raven gunshot
  detector or a Flock setup hotspot is heard; off, the sighting still goes
  to the timeline. The signatures are the ones the DeFlock and flock-you
  projects published; Flock has been turning these radios down since 2025,
  so silence next to a camera is normal and the map list is the better guide.
- **Bluetooth exposure** (Scan → Trackers): what the phone's Bluetooth tells
  a sensor it passes. The phone's own advertising address rotates; a
  personal-looking name can be replaced with the model name from the page;
  Classic accessories (a car's hands-free, most earbuds) keep one address for
  life, and the page lists which were connected on your drives. Android lets
  no app switch Bluetooth off or unpair a device, so those stay in Settings.
- **GPS** (Scan → GPS): runs while the page is open, and in the background
  with the scanner whenever something else has the GPS on (navigation does).
  It catches the spoofers that are cheap to build; one that simulates a full
  sky at the right time passes the signal checks, and the Wi-Fi and cell
  comparison is the one it cannot pass while a tower or access point is in
  range.
- **2G** (Phone check → Health): Android keeps the "Allow 2G" switch's state
  from apps, so the row says whether the phone has the switch, marks it red if
  the phone is ever seen on a 2G tower, and opens the page where it is turned off.
- **VPN coverage** (Phone check → Health): with a VPN up, flags DNS still going
  to the router or carrier, IPv6 the VPN does not carry, Clairix itself left
  outside the VPN, "Block connections without VPN" off, and a proxy on the
  network.
- **Ultrasound** (Scan → Ultrasound): on demand only, never in the background.
  Nothing is recorded; the microphone feeds a spectrum and the spectrum is
  thrown away.
- **Resume scanning after reboot** needs "Allow all the time" location;
  without it, scanning waits until you open the app.

---

## How to build it

**You need:** [Android Studio](https://developer.android.com/studio) or the
Android SDK and NDK with JDK 17, and [Rust](https://rustup.rs) with
`cargo-ndk` for the crypto crate in `comms-crypto`.

```bash
git clone https://github.com/jakes1345/Clairix
cd Clairix/android
./gradlew assembleFullSuiteDebug
# app/build/outputs/apk/fullSuite/debug/app-full-suite-debug.apk
```

Every push also builds the debug APKs in CI (Actions → Android APK →
`clairix-debug-apk`), and builds the app twice on two machines, failing
unless the two builds agree. Editions, variants, release
signing and how to cut a release are in
[`android/README.md`](android/README.md), which also says how to check a
published release against the source; the relay is in
[`comms-worker/README.md`](comms-worker/README.md); the crypto crate in
[`comms-crypto`](comms-crypto).

**Before the first release** the asset signing key has to exist:
`python3 tools/signing/keygen.py` writes `android/asset-signing.pub` and
`android/asset-signing.pem` (commit both) and prints the secret for the
`ASSET_SIGNING_KEY` repository secret. Without them, release builds and the
maps and work-zones workflows fail on purpose.

## Requirements

- Android 12 or newer.
- Permissions for camera, location, microphone, Bluetooth and NFC, each asked
  for only when the feature that needs it is used.

## Reporting a vulnerability

Write to **security@clairix.app** first. Give us enough to reproduce it; a
proof of concept is welcome, other people's data is not. We will acknowledge
the report, keep you informed and credit you if you want to be credited.

---

*Map data © OpenStreetMap contributors, ODbL.*
