---
id: spyware
order: 4
title: "Check your phone for spyware"
kicker: "Spyware"
icon: spyware
variant: scan
visualLabel: "Stalkerware scan"
visualCaption: "Signals add up to a score"
---

<div class="f-does">

### What it does {#spyware-does}

Clairix inspects the apps on your phone for stalkerware, audits the certificates your phone trusts, watches for interception on the network, records unlocks, warns when your screen is recorded or a microphone attaches itself, and gives you a plain report of your phone's own security posture.

</div>

### How it works {#spyware-how}

<div class="methods">

**Stalkerware scan.** Every installed app is scored on the signals a monitoring app needs: installed from outside a store, or by an installer pretending to be a store; hidden without a launcher icon; enabled as a keyboard (which can log every keystroke); running as an accessibility service (which can read every screen) or a notification listener (which sees every notification, including one-time codes); able to act as the VPN (which sees every byte of traffic); holding an active device-admin grant (which can block its own uninstall); or running a background service for a week without ever showing a screen. Each signal adds to a score, and the score decides whether an app is shown as high, medium or low. Apps from the system image and from genuine stores are recognised, with the exception that a store app itself installed from outside is not trusted to vouch for anything. Clairix also lists every notification listener, accessibility service, VPN app, enabled keyboard and device admin on the phone, including the known-good ones, so you can see the whole picture.

**Certificate audit.** Your phone will accept a secure connection from any certificate authority in its trust store. A certificate someone installed by hand through Settings is the standard way to read a phone's encrypted traffic with physical access or a management profile. Clairix lists user-installed roots as high severity and compares system roots against the stock Android bundle, flagging anything a carrier or vendor added.

**Interception canary.** If you use Comms, Clairix opens a raw connection to the relay every four hours and whenever the network changes, and checks whether the certificate it is shown is the one it pinned the first time. A different key means something between you and the relay is substituting certificates. Clairix also checks whether a proxy is configured on your phone or network and, if so, tests the connection through the proxy too. A corporate proxy that passes the real key through is reported as worth knowing, not as an attack.

**Unlock ledger.** With a one-time grant, Clairix records every lock-screen success, failure and credential change as it happens, and fills in history from the system's usage log. It judges whether the phone was used while you slept and tells you in the morning. If someone revokes the grant, that is recorded too.

**Biometric tripwire.** Someone with your PIN can add their own fingerprint in under a minute and never need the PIN again. Clairix arms a hardware key that Android destroys the moment the set of enrolled fingerprints or faces changes, and checks it on every launch.

**Screen recording.** On Android 15 and newer, Clairix is told when its own window is inside a screen recording or cast, and warns you. With the app lock on, Clairix also blanks itself out of any recording.

**Microphone, camera and audio routes.** Clairix shows when another app is recording audio or using a camera. It also watches for a Bluetooth or USB microphone that attaches itself while nothing is playing and no call is up, which is how a paired earpiece left in a bag becomes a live microphone on your phone.

**Phone health.** One screen with the facts that matter: root, bootloader state, verified boot, SELinux, screen lock, storage encryption, USB and wireless debugging, developer options, installs from unknown sources, security patch age, VPN and private DNS, whether your phone's Bluetooth name is your real name, whether it is discoverable and whether the VPN leaks, whether 2G is allowed and whether your keyboard can reach the internet. A row that can be fixed takes you to the setting that fixes it.

**Your keyboard.** A keyboard sees everything you type in every app, before any app can encrypt it. Clairix asks the keyboard not to learn from anything typed in Clairix, which Gboard and Samsung's keyboard honour, and Phone check names the keyboard in use and warns if it can reach the internet.

</div>

<div class="limits cham">

### What it cannot do {#spyware-limits}

- It cannot see what root-level or system-level spyware hides. An app on stock Android can only read what Android lets apps read. If the phone is rooted, has an unlocked bootloader or fails verified boot, Clairix tells you, because at that point no app on the phone can be fully trusted, including this one.
- It cannot name the app that is using the microphone. Android tells third-party apps how many recordings are active, not which app owns them. Clairix shows the count and does not guess.
- It cannot remove spyware. It shows you the app and takes you to the system page where you can uninstall it or revoke its access.
- The interception canary checks the path to the Clairix relay. It does not inspect your other apps' traffic.
- The unlock ledger is only as complete as the grants you give it. Without the device-admin grant it relies on the usage log, which may lag.
- Screen-recording detection needs Android 15 or newer. On older versions Clairix cannot be told.
- Asking the keyboard not to learn is a request. A keyboard is free to ignore it, and it covers nothing typed in other apps. A keyboard with no internet access cannot send anything anywhere.

</div>
