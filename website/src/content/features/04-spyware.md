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

**Stalkerware scan.** Every installed app is scored on the signals a monitoring app needs: installed from outside a store, or by an installer pretending to be a store; hidden without a launcher icon; enabled as a keyboard (which can log every keystroke); running as an accessibility service (which can read every screen) or a notification listener (which sees every notification, including one-time codes); able to act as the VPN (which sees every byte of traffic); holding an active device-admin grant (which can block its own uninstall); or running a background service for a week without ever showing a screen. Each signal adds to a score, and the score decides whether an app is shown as high, medium or low; where an app came from never lists it on its own. An exemption from battery optimisation, which keeps Android from pausing an app in the background, is weighed the same way: it only adds to an app already listed that came from outside a store, hides its icon or holds access nobody vouches for, and never lists one on its own, since many ordinary apps have one. Apps from the system image and from genuine stores are recognised, with the exception that a store app itself installed from outside is not trusted to vouch for anything. Every app that is not part of the phone's system is also matched against a public list of known stalkerware, by its package name and by the certificate it is signed with, so a renamed copy is still found; a match is high on its own. The list is the stalkerware indicators the Echap non-profit publishes, converted and signed by Clairix, built into the app and updated from GitHub about once a day while you use Clairix; a copy whose signature does not check is refused. A device admin that is the phone's device owner or profile owner, as with Family Link supervision or a work enrolment, is reported as managing the phone whatever app it is, and work or school management apps are listed as medium until you confirm you enrolled the phone yourself. Clairix also lists every notification listener, accessibility service, VPN app, enabled keyboard and device admin on the phone, including the known-good ones, so you can see the whole picture, and you can mark one you set up yourself as yours; the mark is written on the timeline. The check runs at boot, after an update, once a day while Clairix is in use or scanning, when you open Clairix after an app was installed, updated or removed, and a few seconds after an app is installed, changed or removed while the scanner runs. An app that newly scores high, or a grant nobody vouches for, goes on the timeline and Phone check at once, with one notification per check. After an install, that notification waits until the screen has gone off and the phone has been unlocked again, so it does not go off in the hands of whoever installed the app.

**Certificate audit.** Your phone will accept a secure connection from any certificate authority in its trust store. A certificate someone installed by hand through Settings is the standard way to read a phone's encrypted traffic with physical access or a management profile. Clairix lists user-installed roots as high severity and compares system roots against the stock Android bundle, flagging anything a carrier or vendor added. If Android will not hand over the list, the Certificates page and Phone check say the trust store could not be read, and where in Settings to look yourself, instead of showing it as clean.

**Interception canary.** While Comms is set up or the scanner is on, Clairix opens a raw connection to the relay about every four hours, and again when the network changes while Clairix is running, and checks whether the certificate it is shown is the one it pinned the first time. The four-hourly check is an alarm the system keeps, so it runs even on a day you have not opened Clairix. A different key means something between you and the relay is substituting certificates. Clairix also checks whether a proxy is configured on your phone or network and, if so, tests the connection through the proxy too. A corporate proxy that passes the real key through is reported as worth knowing, not as an attack. Without Comms there is no relay to compare keys against, so only the proxy check runs. Phone check > Certificates shows the last result and when the next check is due.

**Unlock ledger.** With a one-time grant, Clairix records every lock-screen success, failure and credential change as it happens, and fills in history from the system's usage log. When you switch it on it asks when you usually sleep, and from then on it tells you when the phone was unlocked inside those hours; it judges nothing until you have answered. If someone revokes the grant, that is recorded and you are told.

**Biometric tripwire.** Someone with your PIN can add their own fingerprint in under a minute and never need the PIN again. Clairix arms a hardware key that Android destroys the moment the set of enrolled fingerprints or faces changes, and checks it on every launch.

**Screen recording.** On Android 15 and newer, Clairix is told when its own window is inside a screen recording or cast, and warns you. With the app lock on, Clairix also blanks itself out of any recording.

**Microphone, camera and audio routes.** Clairix shows when another app is recording audio or using a camera. While the scanner runs it also watches for a Bluetooth or USB microphone that attaches itself while nothing is playing and no call is up, which is how a paired earpiece left in a bag becomes a live microphone on your phone. Such a microphone is listed on Phone check and written to the timeline, and it sends a notification only if it becomes the phone's call audio while the screen is off. Whatever is already connected when you switch scanning on is listed, not reported; a headset you mark as yours is not reported again, and a paired accessory that was already connected on an earlier day, such as your car or your earbuds, is left alone.

**Phone health.** One screen with the facts that matter: root, bootloader state, verified boot, SELinux, screen lock, storage encryption, USB and wireless debugging, developer options, which apps may install other apps from outside a store ("Install unknown apps"), whether Android 16's Advanced Protection is on, security patch age, VPN and private DNS, certificates someone installed by hand, whether your phone's Bluetooth name is your real name, whether it is discoverable and whether the VPN leaks, whether 2G is allowed, whether your keyboard can reach the internet and how many other profiles the phone has that Clairix can see. Apps the stalkerware check scores high and special access nobody vouches for are listed there too, so the Phone check verdict covers them. A row you can put right in Settings has an Open settings button that takes you to the page.

**Who else can see this phone.** Most watching by someone you know goes through your accounts, not an app: a Google or Samsung account they also sign in to, location sharing in Google Maps, a family group, a recovery phone or passkey they added, or calls forwarded at the carrier. No app can see any of that, Clairix included, so Phone check > Accounts is a checklist instead. Each row says what to look for and opens the one place where it shows: the Google accounts signed in on the phone (Android's own account list), the devices signed in to your Google account, your recovery phone and email, 2-Step Verification and passkeys, Find My Device, Google Maps location sharing, your Google family group and Family Link, Play Protect, Quick Share visibility and, on a Samsung, Samsung Find and the devices on your Samsung account. Three buttons type the carrier codes \*#21#, \*#62# and \*#67# into the dialler, so that when you press call your carrier shows whether your calls are forwarded, and where. The pages are Google's and Samsung's own, opened in their app or your browser; where a setting has no page of its own, the row opens the official help that shows where it is. Tick each row once you have looked: the ticks are kept encrypted on the phone, and Phone check > Health says how many you have checked and when you last ticked one. The page says first that changing something can be noticed, and has a way to Get help.

**Your keyboard.** A keyboard sees everything you type in every app, before any app can encrypt it. Clairix asks the keyboard not to learn from anything typed in Clairix, which Gboard and Samsung's keyboard honour, and Phone check names the keyboard in use and warns if it can reach the internet.

</div>

<div class="limits cham">

### What it cannot do {#spyware-limits}

- It cannot see what root-level or system-level spyware hides. An app on stock Android can only read what Android lets apps read. If the phone is rooted, has an unlocked bootloader or fails verified boot, Clairix tells you, because at that point no app on the phone can be fully trusted, including this one.
- It cannot see your accounts. Who is signed in to your Google or Samsung account, who sees your location in Maps, who manages your family group and whether your calls are forwarded live with Google, Samsung and your carrier, not on the phone. The Accounts checklist takes you to each page and tells you what to look for; Clairix does not read those pages, and cannot tell you whether you checked them properly. Some carriers do not answer the forwarding codes.
- It cannot see into other profiles. Apps in a work profile, Samsung's Secure Folder, Android's Private space, a second space or a dual-apps profile are not scanned. Phone check says how many other profiles Android shows it; Private space is hidden from every app, Clairix included.
- The stalkerware list is not complete. Its maintainers say so, new stalkerware appears faster than anyone lists it, and an app sold as parental control can come from a store. Not being on the list proves nothing; the behaviour score is there for what the list misses.
- It cannot name the app that is using the microphone. Android tells third-party apps how many recordings are active, not which app owns them. Clairix shows the count and does not guess.
- It cannot remove spyware. It shows you the app and takes you to the system page where you can uninstall it or revoke its access.
- The interception canary checks the path to the Clairix relay. It does not inspect your other apps' traffic.
- The unlock ledger is only as complete as the grants you give it. Without the device-admin grant it relies on the usage log, which may lag.
- Screen-recording detection needs Android 15 or newer. On older versions Clairix cannot be told.
- Asking the keyboard not to learn is a request. A keyboard is free to ignore it, and it covers nothing typed in other apps. A keyboard with no internet access cannot send anything anywhere.

</div>
