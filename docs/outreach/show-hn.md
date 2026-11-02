# Show HN

Submit at https://news.ycombinator.com/submit. Link the GitHub repository
(https://github.com/jakes1345/Clairix), which has the source, the releases and
the README with its limits section. Post only when you can stay and answer
questions for the rest of the day. Do not ask anyone to upvote or comment.

Expect questions about how much of the code an AI wrote. The first comment
says so up front; answer follow-ups the same way.

## Title

```text
Show HN: Clairix – GPLv3 Android app that looks for trackers and stalkerware
```

(76 characters.)

## First comment

```text
Hi HN, I'm <your name>. Clairix is an Android app (12+, no root needed) that
looks for the things someone might use to watch you, plus end-to-end encrypted
messaging. It's a one-person project, GPLv3, and still in beta.

What it checks, and how:

- Bluetooth trackers: AirTag/Find My, Tile, SmartTag, Chipolo, Pebblebee, Find
  Hub tags and anything using the DULT unwanted-tracking spec. It follows a tag
  across address rotations by payload and timing, and only says FOLLOWING when
  the same tag has been heard at places far apart that your own position shows
  you travelled between (default about 10 minutes and 300 m).
- Stalkerware: scores every app on what stalkerware needs (accessibility,
  notification listener, device admin, VPN, keyboard, hidden icon, sideload),
  matches non-system apps against the Echap stalkerware indicator list by
  package name and signing cert, and checks device/profile owner, user CA
  certs, proxies, USB debugging and patch age.
- Fake cell towers: learns a baseline of cells for the places you go and
  scores things like 2G/3G downgrades outside calls, wrong-area cells,
  area changes while stationary, cells with no neighbours. Android hides the
  "Allow 2G" toggle state from apps, so it can only point you at it.
- Fake Wi-Fi: evil-twin checks, and a Karma canary (a random SSID registered
  for the phone to probe; only an answer-everything box replies).
- GPS spoofing/jamming: C/N0 too even or too strong, satellite time vs network
  time, GPS vs Wi-Fi/cell position, impossible jumps, mock-location apps.
- Hidden cameras: torch + camera for lens glint, front camera for IR
  illuminators, ONVIF/mDNS/RTSP on the local network, setup-mode hotspots and
  BLE beacons, and a magnetometer gauge.
- Ultrasonic beacons, on demand, after a self-test that the mic hears the band.

Messaging uses vodozemac (Olm double ratchet) inside sealed envelopes to a
relay that can't read them and isn't told who sent them (it still sees your
IP address unless you use Tor or a VPN); send tokens are blind (RFC
9497). Relays are open source and anyone can run one. There's also an offline
Bluetooth mesh for texts and SOS.

What it does NOT do:

- It can't see past a compromised OS or baseband, and doesn't remove anything.
- Detection is best-effort. It won't find analogue or SD-card-only cameras,
  non-Bluetooth trackers, or root-level spyware.
- The camera sweep has no recorded run against real hardware yet; it's tested
  only by code, on synthetic input. I don't have detection rates.
- It can't hide you from your carrier.
- It has not been independently audited. vodozemac has; Clairix hasn't. Our
  own findings, open ones included, are in docs/SECURITY-FINDINGS.md.
- Every edition includes Google's play-services-location (used only if Play
  services is on the phone), so it isn't on F-Droid.

On AI: a large part of the code was written with Claude as a coding assistant,
and the git history says so on most commits. I've reviewed it, and CI drives
it on emulators for Android 12 to 15 (plus one without Google Play services),
including end-to-end messaging against two local copies of the relay, but you
should weigh that.

Builds: each release is rebuilt from its tag on a second runner and compared
entry by entry in CI (tools/reproducible). That's our own check, not
independent verification. The APK signing cert SHA-256 is in
docs/KEYS.md.

Install today: it's not on Google Play, F-Droid or IzzyOnDroid. Get the APK
from GitHub Releases (marked pre-release) or https://clairix.app/download,
which lists the SHA-256. With Obtainium, turn on "Include prereleases" and
filter APKs by clairix-v[^/]*\.apk$.

I'd really like feedback on false positives (especially trackers and cell
towers), on the threat model in the README, and from anyone who can test the
camera sweep against real cameras.
```
