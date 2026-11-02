## Before you suggest it {#before}

Clairix is a free, open-source Android app, in public beta. It runs on Android 12 and newer and on nothing else: there is no iPhone version. Today it is downloaded as an APK from [clairix.app/download](/download) or from GitHub Releases. It is not on Google Play yet (a Play edition is being prepared but has no store page), and it is not in F-Droid, IzzyOnDroid or any other app store, so installing it means allowing an install from outside a store.

It has not had an independent security audit. The encryption library it uses for messages, vodozemac, has been audited; Clairix as a whole has not, and the [security page](/security#where-the-limits-are) will say so until it has.

Its detection is best-effort. A finding is a reason to look, not proof, and an empty result is not an all-clear. The sections below say where each check stops, in the words the app uses. Please pass the limits on with the app.

## What it checks for {#checks}

Every check runs on the phone, and its results stay there. Each one is described in full, with how it works, on the [features page](/features).

- **Bluetooth trackers.** AirTag and other Find My accessories, Tile, Samsung SmartTag, Chipolo, Pebblebee, Google Find Hub tags, trackers that announce themselves under the DULT unwanted-tracking standard, and the generic Bluetooth modules in some hardwired GPS trackers. A device is called "following" only when it has been heard with the phone across real distance over real time (by default about ten minutes and three hundred metres). A compass locator helps find it.
- **Stalkerware and access to the phone.** Every installed app is scored on the behaviours monitoring apps rely on (sideloaded, hidden icon, accessibility service, notification access, keyboard, VPN, device admin) and matched against a public list of known stalkerware. Phone check also covers root and bootloader state, certificates someone installed by hand, whether the phone's own management has been handed to another app, USB debugging and the security patch's age. With the user's permission, an unlock ledger records unlocks, including "used while you slept", and a tripwire notices a newly added fingerprint or face.
- **Accounts.** Much monitoring by someone the person knows goes through shared accounts, not an app. No app can see those, so Phone check > Accounts is a checklist that opens each place to look: the Google accounts on the phone, devices signed in to the Google account, recovery phone and email, Google Maps location sharing, family groups, Samsung's equivalents, and the carrier codes that show whether calls are forwarded.
- **Hidden cameras.** A guided room sweep: lens glint with the torch, infrared lights with the front camera in a dark room, camera-like devices on the Wi-Fi the phone has joined, cameras in setup mode broadcasting a hotspot or a Bluetooth beacon, and a magnetic gauge for mounts.
- **Networks.** Signs of fake Wi-Fi (evil twins, the default names of interception kit, an "answer-everything" access point) and of fake cell towers or a downgrade to 2G, scored against what is normal for that place. Checks on the GPS position for cheap spoofers.
- **Ultrasound and NFC.** A listener for the ultrasonic beacons some ad-tracking kits play, while its page is open, and a reader that identifies NFC tags and cards.

Findings go into a timeline on the phone. An **evidence report** can be exported for a chosen period, as text or JSON, ending with a hash and a signature from a key Clairix makes in the phone's Android Keystore, so whoever receives it can check that it was not edited afterwards. The [support page](/support#exporting-and-checking-an-evidence-report) has the commands. The report holds the person's own location history and device identifiers, and Clairix warns them of that before it is shared.

## What it cannot detect {#limits}

These come from each feature's own list of limits. The full lists are on the [features page](/features).

**Trackers**

- A tracker that never uses Bluetooth, such as a GPS tracker that reports only over a mobile network, is invisible to a phone.
- Without location permission and movement, Clairix will list nearby devices but never call one a follower. Indoor tests usually confirm nothing.
- With the screen off, Android allows only a filtered scan, so an unknown, uncatalogued device is most likely to be found while the phone is in use.
- Two trackers of the same model can look identical. Distance estimates are rough.

**Stalkerware and accounts**

- It cannot see what root-level or system-level spyware hides. If the phone is rooted, has an unlocked bootloader or fails verified boot, Clairix says so, because then no app on the phone can be fully trusted, Clairix included.
- It cannot see into the person's accounts. Who else is signed in, who sees their location and whether calls are forwarded live with Google, Samsung and the carrier; the checklist only opens those pages.
- It does not scan other profiles: a work profile, Samsung's Secure Folder, Android's Private space or a second space.
- The stalkerware list is not complete, and an app sold as parental control can come from a store. Not being on the list proves nothing.
- It cannot remove spyware. It shows the app and opens the system page where it can be uninstalled.
- Screen-recording detection needs Android 15 or newer.

**Hidden cameras**

- The optical checks find only a lens the phone is pointed at. It cannot find a camera nobody looked at.
- It cannot detect analogue wireless cameras or cameras that only record to a memory card. A phone has no spectrum analyser.
- Most consumer cameras talk only to their maker's cloud and are invisible to the network check, even on the same Wi-Fi. "No-glow" infrared cameras are invisible to most phone cameras.
- None of the camera methods has yet been measured against real cameras on a test bench, so there is no detection rate to quote.

**Networks**

- No phone can prove a cell tower is fake. Clairix shows indicators and a score, and the baseline starts empty in a new place.

## Installing it when someone may be watching {#install}

If the person's phone may be monitored, or someone else handles it, installing anything can be noticed. Clairix cannot hide that it is installed from Android itself: its name stays in Settings > Apps, the battery and permission lists, the notification settings, and, once the unlock ledger is switched on, the device-admin list. Talk through whether the app's presence is itself the risk before it goes on the phone.

If you go ahead:

1. **Get it from the real source.** Only [clairix.app/download](/download) or the project's GitHub Releases. Check the SHA-256 on a computer as the download page describes; an advocate's computer works as well as the person's own.
2. **Allow the install once.** Android asks to allow installs from the browser or file manager. Allow it for this install, then turn it back off: Phone check itself lists apps that are allowed to install other apps.
3. **Set the app passcode on first run.** Clairix offers one on its first run. It is separate from the phone's PIN, so someone who knows the PIN still cannot open Clairix. Choose what repeated wrong passcodes should do: a growing lockout, or a wipe.
4. **Consider discreet mode** (Settings) before using the app around anyone else. Described below.
5. **Expect other scanners to flag it.** Clairix is installed from outside a store and holds some of the access a monitoring app wants (location, microphone, nearby devices, and device admin once the unlock ledger is on). Another app's stalkerware scanner may list it; the [support page](/support#what-clairix-looks-like-to-another-scanner-and-what-settings-shows) says exactly what it holds and what it does not.

Messaging, calls, SOS to a group and the duress phrase need a Clairix number, which the project's relay gives only to someone invited by a person already on it. Everything else, including every check above and the app lock, works without one.

## Acting on what it finds {#acting}

Removing a tracker or a monitoring app can tell whoever placed it that it was found, and Clairix cannot know whether they will notice. It says so before anything is removed: the tracker explainers, the stalkerware findings and the unlock alerts carry a safety card, and before Clairix opens a flagged app's settings it offers to save that app's details to the timeline first, or to talk to someone first. This is the point where an advocate's safety planning matters most.

**Get help**, in Settings and the Comms menu, lists helplines for abuse and stalking in the country of the SIM card or the phone's region, with a text or chat option where the service offers one, for when calling is not safe. The list is not exhaustive. A call, text or chat to a helpline still stays in the phone's call log, messages or browser history; the page says so, and Clairix records nothing about it.

## If they are found with it {#found}

What Clairix does about that, and where it stops:

- **App lock.** The passcode gates the whole app, and the timeline, unlock records, tracker map and evidence export ask for it again each time they are opened. Biometric unlock uses a key Android destroys when a new fingerprint or face is added, so a newly added finger cannot open Clairix.
- **Duress passcode.** A second passcode, typed on Clairix's lock screen, wipes Clairix at once. The screen shows "Wrong passcode", as for any wrong guess, and afterwards the duress passcode opens what looks like a fresh install with no history, no contacts and no Clairix number.
- **Discreet mode.** Alerts say only "New notification" and running services only "Running"; messages name no one; the lock screen loses the logo. In the editions from clairix.app, the home-screen icon can show as "Compass" or "Notes" instead of Clairix.
- **Quick exit.** Hold the mark at the top left, or press Back on the first screen, and Clairix goes to the background at once, and locks when a passcode is set.
- **Quiet lock screen.** By default, an alert on the lock screen says nothing about what was found.
- **Wipe Clairix.** In the Comms menu or Settings: type WIPE. It destroys Clairix's keys, deletes its data and asks the relay to delete the account. It wipes Clairix, not the phone.

And the limits, from the app's own documentation:

- **None of this hides that Clairix is installed.** Discreet mode changes what Clairix shows, not what Android records. After a wipe, Android still keeps the app, the access it was given and its battery history. When the app's presence is the risk, uninstall it after the wipe.
- **The duress decoy hides what was in Clairix, not that Clairix is there.** Whoever opens it finds an app with no history and no number, which can itself raise questions.
- **The app lock protects Clairix, not the phone.** Someone with root access can edit the app's files. A phone taken while Clairix is open shows whatever it would show its owner.
- **A wipe cannot recall messages already delivered** to other people's phones.

## Privacy {#privacy}

- **No personal details to sign up.** No name, email address, phone number or contact list is collected. Messaging uses a random nine-digit Clairix number from the relay, not the person's phone number.
- **No ads, analytics or trackers.** There is no advertising SDK, analytics SDK or crash reporter in the app. We cannot see how anyone uses it, and we do not know how many people do.
- **Scan results stay on the phone.** Bluetooth, Wi-Fi, cellular and NFC results, the timeline, location history, the app list and the unlock ledger are never uploaded, and Android backup is off for Clairix.
- **The exceptions are named.** Messages pass through a relay as sealed envelopes it cannot open. Map tiles come from OpenStreetMap's tile server unless a signed setting names another. On most phones, a position worked out from Wi-Fi and cell towers comes from Google Play services unless Satellite-only positioning is on. Every destination is on the [security page](/security#egress), and what the relay keeps is in the [privacy policy](/privacy).

## Checking the build {#verify}

Clairix is free software under the GNU GPL version 3, and its source is public on [GitHub](https://github.com/jakes1345/Clairix).

- **The file.** The download page publishes each APK's SHA-256, and every release from 3.0.0 Beta 3 on also carries a checksum list signed with the project's release key (the first two betas carry none). The [download page](/download#clairix-full) gives the commands.
- **The signer.** Every APK released so far is signed with one certificate, whose SHA-256 is `471c09cd792fff0bf75724bbb8ee2d13cc674e3f51d1e1fde28a2500932a52cc`. `apksigner verify --print-certs` on the APK prints it. Android refuses an update signed by anyone else.
- **The source.** From 3.0.0 Beta 7, each release is rebuilt from its tag on a separate machine and compared with what was published, entry by entry; earlier betas were built before the toolchain was pinned and a rebuild may differ. Anyone can do the same with the script and instructions in [tools/reproducible](https://github.com/jakes1345/Clairix/tree/main/tools/reproducible).

## Reaching the maintainer {#contact}

Clairix is an independent project with one maintainer and no named spokesperson. There is no partner programme or training offer behind this page; it is here so that people who help others can judge the app on facts.

- **Questions, corrections, or something this page gets wrong:** [support@clairix.app](mailto:support@clairix.app). We answer our own email, within five business days. Please do not send anyone's personal details or screenshots of their conversations.
- **A security vulnerability:** [security@clairix.app](mailto:security@clairix.app), under the [disclosure policy](/security#disclosure).
- **Someone on the Clairix relay harassing a person you support:** the [support page](/support#someone-i-do-not-know-wrote-to-me-or-someone-will-not-leave-me-alone) explains blocking and reporting in the app, and how to report a number to us by email.

If anyone is in immediate danger, contact local emergency services first.
