# Press

Tip lines want stories, not launches. The pitch below offers a story (what a
phone can and cannot tell you about being watched, built in the open by one
person) and is honest about what is not proven. Do not send a bare "we
launched" note.

Verified routes (see README.md for the full table):

- **404 Media:** Joseph Cox, joseph@404media.co, Signal `joseph.404` (covers
  surveillance and privacy). Their page asks you to use a non-work phone.
- **The Verge:** tips@theverge.com, or https://www.theverge.com/contact ("I
  want to pitch a story").
- **Ars Technica:** form at https://arstechnica.com/contact-us/, department
  "Tips, Suggestions, and Press Releases".
- **Wired:** not verified. Find the current contact on https://www.wired.com/
  (the footer's "Tips" or "Contact" link). Use no guessed address.

Send to one journalist at a time per outlet, personalise the first line to
something they wrote, and do not follow up more than once.

The website's press page (`website/src/content/docs/press.md`) says Clairix
has no named spokesperson and answers are attributed to "the Clairix team".
This pitch is signed by name. Make the two agree before sending.

## Pitch

```text
Subject: What can a phone actually tell you about being tracked? An open-source test case

Hi <journalist's name>,

<One line about a piece of theirs you read, and why this follows from it.>

I'm <your name>, and I've built Clairix, a free, GPLv3 Android app that
tries to answer one question for ordinary people: is something watching me?
It looks for Bluetooth trackers that move with you, stalkerware, fake cell
towers (2G downgrades, out-of-place cells), fake Wi-Fi, GPS spoofing and
hidden cameras, on a stock phone with no root, and has
end-to-end encrypted messaging through relays anyone can run.

The part I think is a story is the limits. The README has a long section on
what an app cannot do: it can't see past a compromised OS, can't hide you
from your carrier, and tells survivors that removing a tracker can alert
whoever placed it. It has not been independently audited, the camera sweep
hasn't been tested on real hardware, and much of the code was written with an
AI coding assistant, which the commit history shows.

Everything is checkable: source, threat model, our own security findings and
the open ones are public. A fact sheet is below. Happy to answer questions in
writing or on a call.

<your name>
<your email>
https://github.com/jakes1345/Clairix
```

Paste the fact sheet below the signature, or attach it.

## Fact sheet

| | |
|---|---|
| **What** | Clairix, a personal security app for Android: detection of trackers, stalkerware, fake cell towers, fake Wi-Fi, GPS spoofing, ultrasonic beacons and hidden cameras; end-to-end encrypted messages and calls; offline Bluetooth mesh; SOS, duress passcode and wipe |
| **Who made it** | <your name>, as one small independent project. No company, investors or partners |
| **Licence** | GNU GPL version 3, with an additional permission (section 7) to combine with Google Play services libraries |
| **Platform** | Android 12 and newer; no root, custom ROM or unlocked bootloader needed |
| **Version** | 3.0.0 Beta 9 (every release so far is a beta) |
| **Price** | Free; no ads |
| **Where to get it** | GitHub Releases (https://github.com/jakes1345/Clairix/releases) and https://clairix.app/download, which lists the APK's SHA-256. Works with Obtainium. **Not** on Google Play, F-Droid or IzzyOnDroid |
| **Users** | Unknown. The app has no analytics, so we do not know how many people use it |
| **Audit** | Not independently audited. The Olm library it uses (vodozemac) has been audited; Clairix has not. An audit pack for auditors is in `docs/audit/` |
| **AI use** | A large part of the code was written with an AI coding assistant (Claude) and reviewed by the maintainer; 220 of the 330 commits in the development history involve Claude (1 November 2026) |
| **Contact** | <your email> (the site's general address is support@clairix.app; security reports go to security@clairix.app) |

### How it works, briefly

- **Trackers:** recognises AirTag/Find My, Tile, Samsung SmartTag, Chipolo,
  Pebblebee, Google Find Hub tags and DULT-standard trackers; calls one
  "following" only when it has been heard at places far apart that the
  phone's own position shows the user travelled between (default about 10
  minutes and 300 metres).
- **Stalkerware:** scores every app on the access stalkerware depends on
  (accessibility, notification listener, device admin, VPN, keyboard, hidden
  icon, sideloading) and matches non-system apps against the Echap stalkerware
  indicators (CC BY 4.0) by package name and signing certificate; checks
  user-installed certificates, proxies, USB debugging, device owner and patch
  level.
- **Cell towers:** learns a baseline of normal towers for the places the user
  goes, and scores downgrades, wrong-area cells and other anomalies.
- **Messaging:** Olm double ratchet (vodozemac) inside sealed envelopes the
  relay cannot open or attribute to a sender; blind send tokens (RFC 9497).
  Relays are open source and anyone can run one.

### How to verify our claims

- Source code: https://github.com/jakes1345/Clairix
- Threat model and stated limits: README, "Who it is built against" and "What
  it cannot do"; `docs/audit/THREAT-MODEL.md`
- Our own security findings, including open ones: `docs/SECURITY-FINDINGS.md`,
  `docs/OPEN-ISSUES.md`
- What leaves the phone and what the relay keeps: https://clairix.app/security
- APK signing certificate SHA-256 and release checksums: `docs/KEYS.md`
- Each release is rebuilt from its tag on a separate CI runner and compared
  entry by entry (`tools/reproducible`); this is our own check, not
  independent verification
- Hidden-camera sweep validation status (no hardware run recorded yet):
  `docs/CAMERA-SWEEP-VALIDATION.md`

### Limitations

- Detection is best-effort. A finding is a reason to look, not proof. It
  cannot detect analogue wireless cameras, cameras that only record to a
  memory card, trackers without Bluetooth, or root-level spyware.
- It cannot see past a compromised operating system or baseband, and does not
  remove malware.
- It cannot hide a phone's location from the mobile carrier.
- Someone who handles the unlocked phone can see Clairix is installed.
- Every published edition includes Google's Play services location library; on phones
  with Google Play services, position fixes go through Google unless
  "Satellite-only positioning" is on.
- The project relay runs on Cloudflare; Cloudflare's own logging is under its
  policy, which we cannot check.
- Beta software. Not independently audited.
