# GrapheneOS Personal Hardening Guide

Practical setup for one person's own Pixel. Facts marked **[src]** were taken from
grapheneos.org pages fetched on 2026-11-08 ([/usage](https://grapheneos.org/usage),
[/features](https://grapheneos.org/features), [/faq](https://grapheneos.org/faq),
[/install/web](https://grapheneos.org/install/web)). Items marked **[advice]** are
judgment calls, not GrapheneOS claims. Items marked **[verify]** I could not confirm
from those pages; check them on the device or on the site before relying on them.
Menu paths can move between releases.

## Checklist

**Before install**
- [ ] Buy a Pixel 8 or newer, carrier-unlocked / carrier-agnostic model (see Hardware)
- [ ] Back up anything you need; the unlock and lock steps each wipe the device

**Install and verify**
- [ ] Install with the web installer, then relock the bootloader
- [ ] Leave OEM unlocking OFF after setup
- [ ] Compare the verified boot key fingerprint shown at boot with the official one
- [ ] Optional: attest the phone with the Auditor app from a second device

**First hour**
- [ ] Choose a long alphanumeric passphrase (not a short PIN)
- [ ] Turn on PIN scrambling if you use a PIN
- [ ] Set the duress PIN and password
- [ ] Decide on biometrics (default advice: off)
- [ ] Set Auto reboot to a short timeout (default is 18 h)
- [ ] Set USB-C port control to "Charging-only when locked except before first unlock", or stricter
- [ ] Set Wi-Fi and Bluetooth auto-off timeouts
- [ ] Leave "Wi-Fi and Bluetooth scanning" OFF (it is off by default)
- [ ] Set the default Network / Sensors toggles for newly installed apps

**Profiles and apps**
- [ ] Use the Owner profile as admin/setup only, a second profile for daily use (or Owner-only if simpler)
- [ ] Install Sandboxed Google Play only if an app really needs it, and only in a non-Owner profile
- [ ] Use Contact Scopes and Storage Scopes instead of broad permissions
- [ ] Deny Network permission to apps that don't need it, at install time
- [ ] Install a Signal-compatible messenger (Molly), Clairix, Orbot if needed

**Network and data**
- [ ] Per-connection MAC randomization (default); don't downgrade it
- [ ] Private DNS on, or a VPN, not both stacked carelessly
- [ ] VPN: Always-on + "Block connections without VPN", using the WireGuard or Mullvad app
- [ ] Enable Seedvault backup, with the recovery code written on paper
- [ ] Keep the phone updated; reboot at least daily

**Optional: build it yourself (section 11)**
- [ ] Only if you can patch monthly (kernel, Chromium, firmware); otherwise use the official build
- [ ] Build from a verified release tag, sign with your own keys, relock with your AVB key
- [ ] Keep your patch stack small; rebuild twice and compare hashes
- [ ] Run the per-release audit workflow (11.7) every release, on a spare device

---

## 1. Hardware

**[src]** Official support covers Pixel 6 through Pixel 10a. The FAQ recommends only
Pixel 8 and later. Pixel 8 and newer get at least 7 years of updates from launch.

| Model | Updates until (per FAQ) |
|---|---|
| Pixel 10a | March 2033 |
| Pixel 10 series | Aug to Oct 2032 |
| Pixel 9a | April 2032 |
| Pixel 9 series | August 2031 |
| Pixel 8a | May 2031 |
| Pixel 8 / 8 Pro | October 2030 |

**[advice]** Buy the newest you can afford, since support length is the main factor. A
Pixel 10a or Pixel 10 series gives the longest runway. Buy a carrier-agnostic unit;
**[src]** carrier variants may block bootloader unlocking. Buy new or from a seller you
trust, then verify the install yourself (below).

**[src]** Only Pixels are supported because hardware, firmware and drivers dominate
security. Requirements include hardware memory tagging, verified boot with rollback
protection and an isolated radio.

## 2. Install and verify

**[src]** Web installer, <https://grapheneos.org/install/web>:

1. Computer: bare metal (not a VM), 2 GB RAM and 32 GB free. Use the cable from the box
   or a good USB-C cable, plugged directly into the machine, not a hub.
2. Browser: a supported one, fully updated (Chromium, Vanadium, Chrome, Edge, or Brave
   with Shields off). Avoid Flatpak/Snap builds and private mode. On Linux install the
   udev rules package (`android-udev` on Arch, `android-sdk-platform-tools-common` on
   Debian/Ubuntu). If `fwupd` grabs the device: `sudo systemctl stop fwupd.service`.
3. On the phone: tap Build number until developer mode is on, then enable OEM unlocking
   (Settings > System > Developer options).
4. Boot to fastboot (hold volume down at boot). Don't press power to "Start".
5. In the installer: unlock bootloader (wipes), download images, flash, then **lock
   bootloader** (wipes again).
6. Boot. During setup leave OEM unlocking off.

Relocking enables full verified boot on every boot and blocks fastboot flashing.

**Verify the key fingerprint [src]:** boot into the bootloader and choose the option
that loads an alternate OS. A yellow notice shows the verified boot key ID. On Pixel 6
and later it is the full SHA-256; compare it with the official value for your model on
the install page. (Pixel 4/5 show only 32 bits, so this check is not possible there.)
Verified boot works without this manual check; the check is what detects a tampered
or pre-flashed unit.

**Auditor [src]:** the Auditor app on a *second* Android device verifies hardware,
firmware and OS through hardware attestation, with optional scheduled checks and alerts
(<https://attestation.app>). Pair it right after install while you know the phone is
clean.

**[advice]** Set your lock method only after verification, on a trusted network.

## 3. Lock screen and device unlock

- **Passphrase over PIN [advice]:** a 6-digit PIN is brute-forceable offline-ish if the
  secure element is ever bypassed; a 5+ random-word passphrase is not. Use a passphrase
  if you can tolerate typing it; otherwise a long random PIN.
- **PIN scrambling [src]:** a toggle that randomizes the keypad layout to resist
  shoulder-surfing and smudge/side-channel guessing. It applies to the lock screen and
  SIM PIN/PUK. Turn it on if you use a PIN.
- **Duress PIN/Password [src]:** Settings > Security & privacy > Device unlock >
  Duress Password (set from the Owner profile). Both a duress PIN and duress password
  must be set. Entering it **irreversibly wipes the device and installed eSIMs**,
  needs no reboot and cannot be interrupted. It also wipes if entered as the
  two-factor fingerprint PIN, but not as the SIM PIN. If it matches your real unlock
  method, the real one wins and nothing is wiped.
  - **[advice]** Choose one you will never type by accident (not a near-miss of your
    real PIN). Test the idea on a spare device first. Be aware that in some
    jurisdictions destroying data when ordered to unlock can itself be an offence; see
    Border tier below.
- **Biometrics [advice]:** fingerprint/face can be forced or taken from you while
  asleep or under arrest; a passphrase cannot be physically compelled the same way
  (though it can be legally). For anything above "casual", disable biometrics and use
  the passphrase only. If you keep a fingerprint for convenience, keep a strong
  passphrase as the second factor (GrapheneOS offers fingerprint + PIN two-factor
  **[src: features mentions the "two-factor fingerprint unlock PIN"]**).

## 4. Auto reboot, USB, radios

- **Auto reboot [src]:** Settings > System > System update > Automatic reboot (this
  is the path from /usage; /features documents the behavior). Default timeout is 18
  hours, range 10 minutes to 72 hours or off. The countdown starts on each lock;
  unlocking any profile cancels it. A reboot returns the phone to Before First
  Unlock (BFU), which is the state where forensic extraction is hardest.
  **[advice]** Set 4 to 8 hours for a daily-use phone; shorter for higher threat levels.
- **USB-C port control [src]:** Settings > Security & privacy > Exploit protection >
  USB-C port. Modes: Off, Charging-only, Charging-only when locked, Charging-only when
  locked except before first unlock, On. Default: Charging-only when locked. "Off"
  also disables charging, which removes the remaining USB controller attack surface.
  **[advice]** Use "Charging-only when locked except before first unlock" daily, "Off"
  or "Charging-only" when crossing a border or leaving the phone unattended; carry your
  own charger and cable, never a public charging kiosk.
- **Wi-Fi / Bluetooth auto-off [src, partial]:** /features states both can optionally
  be turned off after a timeout, but gives no exact setting names or values.
  **[verify]** Look under Settings > Network & internet and Connected devices for the
  timeout options on your build. **[advice]** Pick short timeouts.
- **Wi-Fi and Bluetooth scanning [src]:** Settings > Location > Location services.
  Off by default; keep it off. Enabling it lets scanning continue with radios off.
- **Airplane mode [src, FAQ]:** fully disables the cellular radio. Networks are treated
  as hostile.
- **Secure app spawning [src]:** Settings > Security & privacy > Exploit protection.
  Costs ~200 ms cold start on flagship devices. **[advice]** Leave it on.

## 5. Profiles, Google Play, permissions

**Owner-as-admin pattern.** **[src]** The simplest setup is Owner profile only. Apps
there are sandboxed normally and get no special access. **[advice]** For better
separation, keep Owner for setup and system settings only, with no daily apps, and use
a second user profile for everyday messaging and browsing. Profile data is encrypted
separately and returns to rest when you end the session **[src, FAQ: ending a secondary
session puts it back at rest]**. Practical wrinkles: the duress password is set from
Owner, and **[src]** unlocking *any* profile resets the auto-reboot countdown.
Switching profiles costs convenience (notifications only reach the running profile),
so many people stop at one daily profile plus a locked Owner.

**Sandboxed Google Play [src].** Install from the GrapheneOS App Store ("Google Play
services", which also installs the Play Store). It runs as ordinary unprivileged apps
inside one profile and only apps in that profile can use it. Configure at Settings >
Apps > Sandboxed Google Play. Location requests are rerouted to the OS implementation
by default. Play services needs a battery-optimisation exception for push. RCS in
Google Messages is currently known to work only in the Owner profile. **[advice]**
Skip it entirely unless a banking or work app needs it; if you need it, put it in its
own profile and keep your messaging apps outside it. Aurora Store works as a frontend
but still needs an account **[src]**; prefer the Play Store app itself for Play apps,
which the page calls the most secure way to install and update them.

**Network permission [src].** Per-app toggle, on by default for compatibility. It
blocks direct and indirect access including localhost, which stops apps in different
profiles from talking via loopback. The install confirmation dialog shows it, so turn
it off *at install* for offline apps (notes, calculators, cameras, offline readers).
**Sensors permission [src].** Blocks accelerometer, gyroscope, compass, barometer
and similar; blocked apps get zeroed data. Default is on for compatibility; the
default for user-installed apps can be changed in Settings > Security & privacy >
More security & privacy. **[advice]** Flip the default to "off" so each app must earn it.

**Contact Scopes [src].** Apps see an empty contact list by default. You can grant
per contact data type, per single contact, or per group; account type and name are
hidden; write access is blocked. Use this for messengers that only need a few
contacts.

**Storage Scopes [src].** For apps with no storage permission: they behave as if they
have it but only see their own files, plus any files/directories you add through the
picker. Reinstalling an app loses access to files it created unless you re-add them.

## 6. App choices

- **Sources [advice]:** GrapheneOS App Store for its own apps and Android Auto;
  Accrescent for reproducibly-built apps that offer it; F-Droid (with the usual caveat
  that its repo signing and update latency differ from upstream); Play Store only inside
  the sandbox profile. Prefer Accrescent or the developer's own signed APK (verify
  signing key / hash) over third-party mirrors. The FAQ page I fetched does not cover
  F-Droid or Accrescent, so treat this as my opinion.
- **Messaging [advice]:** Molly (Signal fork with hardened local storage and optional
  database passphrase) is a reasonable pick; Signal itself works well on GrapheneOS
  (**[src]** FAQ cites it as an example of correct background notifications via a
  foreground service). Clairix for your own trusted circle (see `docs/THREAT-MODEL-GAPS-2026-11.md`
  for its known gaps; do not assume it is better than Signal until reviewed).
- **Tor [advice]:** Orbot for per-app Tor routing; Tor Browser (or Brave/Vanadium
  with care) for browsing. **[src]** FAQ says Private DNS is not an alternative to
  Tor or a VPN and gives no detailed Tor guidance.
- **Browser [src]:** Vanadium is the built-in hardened Chromium. The usage page says to
  avoid Gecko-based browsers such as Firefox.
- **VPN apps [src]:** use the official WireGuard or Mullvad app rather than built-in
  IPsec. Mullvad's app is tested with hardware memory tagging.
- **Fewer apps [advice]:** every app is attack surface and metadata. Use the web
  version in Vanadium for things you open twice a year.

## 7. Network hygiene

- **MAC randomization [src]:** Wi-Fi scanning always randomizes. Associated
  connections default to a new random MAC per connection (more private than stock
  Android's per-network random MAC). DHCP sends no hostname and flushes state before
  reconnect. Per-network override: Settings > Network & internet > Internet > (network)
  > Privacy. Leave on the default.
- **Private DNS [src]:** encrypts DNS queries but does not hide the IPs you connect to,
  and TLS SNI still reveals domains. **[src]** Disable Private DNS when using a VPN,
  especially in secondary profiles. **[advice]** Use Private DNS (a DoT provider you
  trust) when *not* on a VPN.
- **VPN kill switch [advice, standard Android setting]:** Settings > Network &
  internet > VPN > (gear) > Always-on VPN + Block connections without VPN. RethinkDNS
  **[src]** is suggested for VPN-based filtering. A VPN moves trust from your ISP to
  the VPN provider; it does not give anonymity.
- **Cellular [src, FAQ]:** treat the carrier network as hostile. Silent SMS is not a
  meaningful indicator of targeting. The baseband is isolated via the IOMMU.
  **[advice]** On Wi-Fi at home consider airplane mode + Wi-Fi when you do not need
  calls.

## 8. Backups

**[src]** Seedvault is the OS backup service; it must be explicitly enabled and can
restore app data on reinstall.
**[advice]**
- Back up to a local drive or your own Nextcloud, not a third-party cloud.
- Write the 12-word recovery code on paper and keep it away from the phone.
- Test a restore on a spare device or after the first month.
- Messenger databases (Molly, Clairix) may need their own export; Seedvault will not
  help if the app opts out of backup.
- Remember backups are an attack surface: a weak backup passphrase undoes a strong
  lock screen.

## 9. Threat-model tiers

| | Casual (stalkers, thieves, data brokers) | Targeted (motivated attacker, local police, ex-partner with access) | Border / seizure |
|---|---|---|---|
| Lock | 8+ char passphrase or 6+ digit scrambled PIN | Passphrase, no biometrics | Passphrase, no biometrics |
| Auto reboot | 8 h | 1 to 4 h | 10 min to 1 h |
| USB-C | Charging-only when locked | Charging-only when locked except BFU | Off |
| Duress | Optional | Set | Think carefully (legal risk) |
| Profiles | Owner only is fine | Owner-admin + daily profile | Minimal profile, few apps |
| Play | Sandboxed if needed | Avoid | None |
| Network | Private DNS | Always-on VPN or Tor for sensitive apps | Airplane mode when crossing; power off before arrival (BFU) |
| Data | Seedvault local | Seedvault local, encrypted | Travel with a clean/minimal device; carry little data |

**Border [advice].** Powering the phone off before the crossing returns it to BFU,
which is the strongest at-rest state. Leave sensitive data off the device. Know local
law: some countries can compel an unlock or penalise a wipe. If your risk is a
legal one rather than a technical one, a wipe PIN may be worse than a refused
unlock. Get legal advice for your jurisdiction. This guide cannot give it.

## 10. What hardening cannot fix

- **The people and the metadata.** Who you talk to, when, and from where is still
  visible to your carrier, your messenger's servers, and the network. **[src]**
  Private DNS and TLS do not hide IP addresses or SNI.
- **A compromised or malicious app you grant access to.** The sandbox limits
  damage; it does not stop an app abusing the permissions you gave it. **[src]** Apps
  can still detect the device model and that they are running on GrapheneOS.
- **After first unlock, a live-unlocked phone is exposed.** Disk keys are in memory.
  Auto reboot and a short lock timeout shrink that window; they don't close it. A
  phone seized *while unlocked* is lost.
- **Skilled, well-funded attackers.** Zero-days against the kernel, firmware, secure
  element or baseband exist; GrapheneOS raises cost and reduces attack surface (hardened
  allocator, hardware memory tagging **[src]**, exploit protection) but cannot
  promise immunity.
- **Rubber-hose and legal compulsion.** A passphrase can be demanded. A duress wipe
  can be a crime.
- **Supply chain.** A tampered new unit is only caught if you do the fingerprint check
  and ideally the Auditor attestation. Google Pixel firmware and the secure element
  remain proprietary.
- **Phone number identity.** A SIM ties you to your real name in many countries.
  eSIM, prepaid SIM, or avoiding the number is a separate problem.
- **Your own habits.** Reusing passwords, photographing documents, posting
  location-tagged images, backing up to a weak cloud.
- **Social engineering and account recovery.** Email/phone account takeovers happen
  outside the phone entirely.
- **Support lifetime.** Once the model passes its end date (table above), stop
  trusting it.

## 11. Build it yourself

Goal: your own signed build, so you control the keys and can read what changed. Same
tags: **[src]** from <https://grapheneos.org/build> and the FAQ (fetched 2026-11-08);
**[advice]** is my opinion; **[verify]** was not on those pages.

### 11.1 Is it worth it?

Building from source gives you control of signing keys and update server. It does not
by itself give you more security than the official build, and it costs you the
project's release engineering. Read 11.6 before committing.

### 11.2 Build GrapheneOS

**[src] Host:** x86_64 Linux (Arch, Debian bookworm, Ubuntu 24.04 LTS or 24.10),
32 GiB+ RAM, about 136 GiB disk for a full sync (90 GiB lightweight) plus 100 GiB+ for a
multiarch build. Debian needs `repo yarnpkg zip rsync`; adevtool needs Node.js 24 LTS.

```bash
mkdir grapheneos && cd grapheneos
repo init -u https://github.com/GrapheneOS/platform_manifest.git -b refs/tags/TAG_NAME
# verify the manifest tag signature before syncing
curl -O https://grapheneos.org/allowed_signers
git -C .repo/manifests config gpg.ssh.allowedSignersFile "$PWD/allowed_signers"
git -C .repo/manifests verify-tag "$(git -C .repo/manifests describe)"
repo sync -j8
yarn --cwd vendor/adevtool/ install
adevtool generate-all -d CODENAME      # pulls Pixel vendor files
source build/envsetup.sh && lunch CODENAME-cur-user
m vendorbootimage vendorkernelbootimage target-files-package   # Pixel 7 to 9 Pro Fold
```

- **[src]** Use a release **tag** for stable, not branch `17` (development). The correct
  tag differs per device; use the one in the release notes. Stable manifests pin commit
  hashes, so only the manifest tag needs signature verification.
- **[src]** Use `user` builds for daily use; `userdebug` is for development. Plain `m`
  produces test-key-signed images that you must never ship to a daily phone.
- **[src]** Pixel 10 kernels, Vanadium (the browser/WebView) and the Updater have their
  own steps; the kernel is built separately and copied into `device/google/*-kernels/`.
  Vanadium is built with `gclient`, patches applied with `git am`, and signed with its own
  keystore. Skipping Vanadium means you ship someone else's prebuilt binary.

### 11.3 Sign with your own keys and lock the bootloader

**[src]** Per-device keys: `releasekey, platform, shared, media, networkstack,
bluetooth, sdk_sandbox, gmscompat_lib, nfc`, plus an AVB key (4096-bit RSA, `avbtool.py
extract_public_key` produces `avb_pkmd.bin`) and an ed25519 SSH key for factory-image
signing. Rules from the page:

- Use one passphrase per device and encrypt the key dir with `script/encrypt-keys`.
- Swap weakens key-at-rest protection; use encrypted/ephemeral swap or none while signing.
- Keys cannot change later without reflashing factory images (a factory reset).
- Sign with `m otatools-package`, `script/finalize.sh`, `script/generate-release.sh DEVICE BUILD`.
- Set `OFFICIAL_BUILD=true` only if you also change the Updater URL in
  `packages/apps/Updater/res/values/config.xml` to your own server. **[src]** The official
  server will not accept a build signed with other keys; disable the Updater if you keep it.

**Custom AVB key and relock [verify].** The build page says `avb_pkmd.bin` sets the
public key the device uses for verified boot but does **not** describe the bootloader
lock procedure. The generic Pixel method is to unlock, flash `avb_pkmd.bin` as the
custom AVB key partition (`fastboot flash avb_custom_key`), flash your images, then
`fastboot flashing lock`. Check the exact steps for your model before you wipe a
phone. After relock, boot shows a yellow warning with your own key's ID; note it and
compare it on every boot, the same check as section 2.

**[advice]** Keep the signing machine offline and the keys on encrypted media with a
second offline copy. Losing the keys means reflashing and wiping; leaking them means
anyone can ship you an "update".

### 11.4 Track releases and keep your own patches small

- **[src]** Update by re-running `repo init` on the new tag, then `repo sync -j8`
  (`--force-sync` if a repo's source changed). Retry `repo sync` on network failure; don't
  start over.
- **[src]** Ported changes are kept as commit series so reviewers can use `git range-diff`
  to see how they evolved between releases (FAQ). Use that on your own patches too.
- **[src]** Forks are not GrapheneOS and should not be presented as such; a build with
  only the update URL changed is an "unofficial build", anything more is a distinct OS
  (FAQ). Rebrand if you ever share it.
- **[advice] Keep a patch stack, not a tree.** Keep your changes as a small series on
  top of a specific tag (a few dozen commits at most), each commit one purpose and
  independently revertable. Rebase it onto each new tag. Every extra line you add is a
  line that must be re-reviewed every release and can conflict with upstream.
- **[advice]** Never "fix" security-sensitive upstream code in place; send it upstream
  or carry a minimal, documented patch.

### 11.5 Reproducibility

**[src]** Reproduce an official build by exporting `BUILD_DATETIME` and `BUILD_NUMBER`
from the original build's `out/build_date.txt` and `out/build_number.txt` (and check
`ro.build.date.utc` / `ro.build.version.incremental` in OTA metadata), plus
`OFFICIAL_BUILD=true`. Signed output reproduces only with the same private keys; to
compare against official production builds signed with other keys, ignore signatures.

**[advice]** Do this in practice: build the release tag twice on two machines (or a clean
VM), compare hashes of the unsigned `target-files` contents, and then compare against the
official factory image with signatures excluded. A mismatch you cannot explain is a
finding, either in your environment or in the supply chain. Pin host packages and
toolchain versions so you can tell the two apart.

### 11.6 Why a solo fork falls behind

- **Cadence.** Android and Pixel security patches, the Linux kernel stable series and
  Chromium (Vanadium) ship on a roughly monthly or faster rhythm; Chromium is the most
  urgent because it parses untrusted web content. Each requires merging upstream,
  rebuilding, signing and testing on real devices, then staying that way forever.
- **Vendor inputs.** The Pixel firmware, kernel sources and vendor blobs for a new
  patch level come from Google on Google's schedule. You cannot build them early.
- **Hardening is a patch pile.** GrapheneOS carries many invasive changes (hardened
  allocator, memory tagging, exploit protections). Rebasing those onto each AOSP release
  is the full-time job of an experienced team. A single developer typically
  falls days to weeks behind, and a phone running a known-exploitable, publicly-patched
  browser or kernel is *less* safe than an up-to-date official build, even with a "clean"
  fork. **[advice]** This is the main risk of going solo.
- **Only for the window of risk.** Patch-gap exposure matters most for the Targeted tier
  (section 9); for the Casual tier, being a few days behind is usually a smaller risk than
  a backdoor you worry about.

### 11.7 What AI-assisted auditing can and can't do

Use a strong model as a tireless reviewer, not as a verifier. A concrete, maximal
workflow follows, then the limits right beside it. Tools named are suggestions
**[advice]**; none of this is on the GrapheneOS pages.

**Per release (run all of it, every time):**

1. **Diff review.** Diff the new tag against the previous release, and GrapheneOS
   against the matching AOSP tag, repo by repo (`repo forall -c 'git diff OLD..NEW'`,
   `git range-diff`). Have the model summarise each repo's change, flag anything that
   does not match the release notes, and list files changed with no explanation.
2. **Targeted scans** over the full tree and over each diff:
   - New network endpoints, hardcoded URLs/IPs/keys/tokens, new `INTERNET` users
     and new DNS/HTTP clients; compare against the last release's list.
   - Added permissions, privileged apps (`priv-app`), signature-permission grants,
     default-granted runtime permissions, and `AndroidManifest.xml` changes.
   - SELinux policy: added `allow` rules, `permissive` domains, new `neverallow`
     exceptions, `unconfined`-like domains (`sepolicy` diff).
   - `init.rc` / service definitions: new services, `seclabel`, `user root`, capabilities.
   - New setuid/setgid binaries and file capabilities in the built image
     (`find` over the extracted system image, compared to last release).
3. **Reproducibility.** Rebuild as in 11.5 and compare hashes (11.5). Anything
   non-reproducible gets investigated, not ignored.
4. **Vendor blobs and firmware.** Extract the Pixel vendor/firmware images from the
   factory zip, disassemble the interesting ones with Ghidra (or similar), and diff
   function lists, strings and imported symbols between releases. Ask the model to
   explain new functions and new network/IPC strings. Note that for most blobs this is
   behavioural triage, not full understanding.
5. **Signing and verified-boot chain.** Check that `avb_pkmd.bin` is the key you
   meant, that every partition is covered by AVB descriptors (`avbtool info_image`),
   that `dm-verity` is enabled with the expected hash trees, that rollback indexes are
   as expected, and that nothing is signed with test keys.
6. **Runtime check on a spare test device** (never your main phone): after install,
   capture all traffic at a router/hotspot you control (`tcpdump`/Wireshark), with Google
   Play absent and no apps; list every destination and compare against the previous
   release. On-device, list listening ports and running services (`ss -tulpn`,
   `ps -A -Z`, `dumpsys`) and diff them too. Repeat with Wi-Fi only, airplane mode, and
   a SIM.
7. **Repeat for every release**, in a script, with results stored so you can diff
   *findings* over time. A one-time audit of one release proves nothing about the next.

**What it can't do (read this next to the workflow above):**

- **No proof of absence.** A model can find what it recognises; a subtle backdoor looks
  like an ordinary bug (a missing bounds check, a weak RNG, a logic flaw). A clean report
  means "nothing found", not "none present". Expect both false negatives and false
  positives, and a human to triage the latter.
- **AOSP and the kernel are far too large to read in full.** The diff-first approach
  reviews what *changed*, which trusts every release before your first audit. Your
  baseline is unaudited unless you audited it.
- **It can't see hardware.** Silicon, the modem/baseband, the bootloader and secure
  element firmware (Titan M2) are not in the source you build. You can fingerprint
  those images and diff them; you cannot verify what the silicon does with them.
- **Unrebuildable firmware stays unverified.** Closed vendor blobs, baseband and
  secure-element firmware can be analysed and diffed but cannot be rebuilt from source
  by you, so they cannot be made trustworthy by analysis alone. Binary analysis of
  stripped, obfuscated or encrypted firmware is incomplete by nature.
- **Runtime capture is a sample.** It shows what the phone did while you watched, not
  what it might do later, on a trigger, or over a channel you did not capture (radio-level,
  not IP).
- **It does not replace patching.** Auditing a release does not produce the next
  kernel, Chromium or firmware patch. Section 11.6 still applies.
- **Prompt injection.** The code you feed it is untrusted input; a comment or string can
  try to steer the model's conclusion. Keep a human in the loop for conclusions that
  matter and give the model no tool access beyond read-only.

### 11.8 Honest bottom line: what you can and cannot verify

| Layer | Verifiable by you? |
|---|---|
| GrapheneOS and AOSP source you build | Partially: diffable and rebuildable, but too big to read in full |
| Your signing keys and AVB chain | Yes |
| Build reproducibility | Yes, for the parts built from source |
| Linux kernel | Source yes (with the above caveats), vendor drivers partly |
| Vendor blobs (HALs, camera, GPU etc.) | Analyse and diff only |
| Baseband / modem firmware | No |
| Bootloader and Titan M2 / secure-element firmware | No |
| The silicon itself | No |

So "no backdoors" cannot be fully established on any phone. Building your own narrows
the gap to what the hardware vendor ships; it does not close it. A Pixel on the official
build, updated promptly, with the key fingerprint and Auditor checks from section 2,
is a stronger position for most people than a self-built fork that is a month behind.

## 12. Physical surveillance layer

GrapheneOS hardens what happens inside the phone. The physical layer around it is a
separate problem.

### 12.1 ALPR / license plate readers (Flock, DRN, Rekor)

Automated License Plate Readers (ALPRs) run continuously at fixed locations and in
police vehicles. **Flock Safety** is the dominant municipal fixed-camera vendor;
**DRN (Digital Recognition Network, now Motorola Solutions)** runs the commercial
repo/insurance network; **Rekor** sells both. Each read is timestamped and geolocated
and can be shared across agencies and vendors without a warrant in most jurisdictions.

**What to know:**
- The EFF's **Atlas of Surveillance** (<https://atlasofsurveillance.org>) is the
  best public map of ALPR deployments, sourced from public records and community
  reports. Use it to identify dense corridors in your area.
- Flock and DRN deployment maps are FOIA-able from city/county police departments
  and traffic agencies; many have been published by journalists and local activists.
  Search `"Flock Safety" site:your-county.gov` or file a public records request.
- **[advice]** For route planning: identify corridors heavy in fixed cameras and
  choose alternates for sensitive trips. This is legal and practical.
- **Plate covers, sprays, IR emitters, and reflective films** are illegal in most
  U.S. states and many countries, legally gray elsewhere, and physically detectable
  by modern readers. Don't use them.
- Car rental, tolling, and parking-garage systems also run ALPR and share data
  commercially. A rental car under your real name is an ALPR trail even without
  police cameras on the route.

### 12.2 Cell-site simulators (IMSI catchers, "Stingrays")

An IMSI catcher impersonates a cell tower to force nearby phones to connect to it,
logging device identifiers (IMEI, IMSI) and sometimes intercepting calls/SMS. Law
enforcement use is widespread; devices are also available commercially.

**Indicators of an active IMSI catcher nearby (observable on device):**
- Sudden unexplained drop to 2G/EDGE (many catchers only support 2G)
- Rapid tower-switching while stationary
- Increased battery drain with radio active but no data transfer
- SnoopSnitch (Android root app, F-Droid) monitors baseband signalling for some of
  these; it is not comprehensive and cannot detect modern catchers that stay on 4G/5G

**What hardening does and doesn't help:**
- GrapheneOS isolates the baseband via IOMMU, so a catcher that only captures
  metadata cannot read app data
- Voice calls and SMS through the carrier are exposed to a catcher regardless of
  phone hardening; use Signal/Molly/Clairix for content you care about
- **[advice]** In high-risk situations: airplane mode completely disables the radio;
  it also makes you invisible to ALPR-linked phone tracking (some agencies correlate
  ALPR reads with pings from nearby phones to the carrier)

### 12.3 Body cameras, doorbell cameras, Wi-Fi probe capture

- Modern body cameras (Axon/Taser, Motorola) timestamp and geotag footage; footage
  is often uploaded automatically to cloud evidence systems
- Doorbell cameras (Ring, Nest) have bulk data-sharing agreements with law
  enforcement in many U.S. cities; footage from your neighbor's doorbell can place
  you at a location
- **Wi-Fi probe requests:** your phone broadcasts probe frames for saved SSIDs when
  Wi-Fi is on but not connected. GrapheneOS randomizes the MAC in probes **[src]**
  and suppresses SSID probing in newer versions; leave per-connection MAC randomization
  at default (randomized) and avoid networks with unusual probe suppression requirements

### 12.4 Physical tail and counter-surveillance basics

**[advice]** This is outside what any OS can help with. Brief notes:
- A phone in your pocket with radios on can be correlated to ALPR reads, carrier
  cell-site logs, and Wi-Fi probe captures simultaneously. Airplane mode breaks all
  three at once.
- Physical separation: if you need a device associated with a different identity,
  that device must be bought with cash, never co-located with your real phone while
  both are powered, and never connected to accounts tied to your real identity.
- Choke points: cameras concentrate at chokepoints (bridges, freeway on-ramps,
  transit stations). The Atlas of Surveillance and Flock FOIA data show where.

---

## 13. SIM and phone number strategy

Section 10 noted that a SIM ties you to your real name. This section addresses it.

**The problem:** in most countries a SIM registration requirement means your carrier
knows who you are, and your phone number is used as an identity anchor for account
recovery, 2FA, and commercial tracking. Even a prepaid SIM bought with cash is
linked to your IMEI once inserted (the carrier logs IMEI↔IMSI).

**Options, roughly ordered by isolation:**

| Option | Isolation | Friction |
|---|---|---|
| eSIM on a prepaid virtual carrier (e.g., Mint, Visible) paid with a privacy card | Moderate | Low |
| Prepaid physical SIM, cash purchase, never inserted in your real-name device | Good | Medium |
| VoIP number (Google Voice, MySudo, JMP.chat) used as the public number | Good for content, not for SS7 | Low |
| No carrier SIM; Wi-Fi + VPN only for data; VoIP for calls/SMS | Strong | High |
| Separate device per identity, no overlap in location or account | Strongest | Very high |

**[advice]**
- For daily use, the minimum-friction improvement is a VoIP number (MySudo or
  JMP.chat) as your public-facing number; your real SIM is for carrier infrastructure
  only (2FA you can't avoid, banking) and is never shared publicly.
- JMP.chat provides a full XMPP+PSTN number that works over Tor and does not require
  identity verification beyond payment.
- Buying a SIM with cash still leaves your IMEI in the carrier's logs the moment
  you insert it. If the IMEI is known from your real-name device history, it links
  the "anonymous" SIM back to you. Use a separate device for a truly separate number.
- **[verify]** GrapheneOS eSIM support: the web installer page and FAQ do not detail
  which eSIM features are or are not supported; check the GrapheneOS issue tracker
  and forum for your model.

---

## 14. Metadata and OPSEC habits

The best hardened phone loses its value if the habits around it are loose.

### 14.1 Image metadata

EXIF in photos includes GPS coordinates, timestamp, device model, and sometimes lens
serial number. GrapheneOS's camera app strips EXIF on share by default **[verify]**;
many third-party cameras do not.

**[advice]**
- Use the built-in camera or Secure Camera (GrapheneOS App Store) for anything
  posted publicly or sent to untrusted parties.
- For images received from others: ExifTool (Linux) or Scrambled Exif (F-Droid)
  strip metadata before forwarding; check before you forward a screenshot someone
  sent you.
- Screenshots on Android include no GPS but do include device dimensions and Android
  version in some apps; strip or crop before sharing if that matters.

### 14.2 Posting habits

- Location-tagged posts anchor your real-world pattern of life. Turn off location
  tagging in every social app; most default it on.
- Posting photos from home reveals your home. Metadata aside, backgrounds (street
  signs, distinctive architecture, reflection in glasses) are locatable with OSINT
  tools and AI image search.
- Timing of posts (always online 9am–5pm Monday–Friday) reveals timezone and work
  schedule. This matters more for high-threat models.

### 14.3 Password and account hygiene

- Use a password manager (Bitwarden, KeePassXC) with a strong master passphrase.
  GrapheneOS's storage sandbox means a password manager in one profile does not reach
  another profile's apps; install it in every profile that needs it.
- Hardware security keys (YubiKey, Google Titan) for accounts that allow FIDO2;
  prefer hardware keys over TOTP, prefer TOTP over SMS 2FA.
- SMS 2FA is SIM-swappable. If you use it, it is your weakest link regardless of
  phone hardening.
- **Email:** a compromised email account undoes almost all account security. Use a
  dedicated provider (Proton, Tutanota/Tuta) for accounts you care about; keep it
  out of profiles that have untrusted apps.

### 14.4 Social engineering

No hardening survives a convincing caller or a fake login page. The most common
vectors:
- SIM swap: attacker calls your carrier, convinces them to port your number. Mitigation:
  port-freeze / account PIN at your carrier, plus reduce SMS 2FA.
- Account recovery: attacker uses your email or phone to reset passwords. Mitigation:
  hardware key + recovery email that is not your main address.
- Phishing: a link that looks like your bank/Signal/Clairix login. Mitigation:
  always check the domain; a hardware key's origin binding means it won't auth on
  a fake domain even if you enter your credentials.

---

## 15. Incident response: device compromise suspected

If you think your device has been compromised (Pegasus-style spyware, stalkerware,
or a physical access incident while unlocked):

**Immediate:**
1. Do not turn the phone off yet — a live device is better for forensics if you
   pursue it. But if you are in an active confrontation or crossing a border, power
   off immediately (BFU state is safest).
2. Stop using it for anything sensitive. Move to a secondary device or a clean laptop
   with Tor Browser.
3. Do not connect it to any network you use for other devices.

**Forensic options:**
- **MVT (Mobile Verification Toolkit)** by Amnesty Tech:
  <https://github.com/mvt-project/mvt> — analyzes a full Android backup or a
  connected device (with ADB) for known Pegasus/commercial spyware IoCs and
  suspicious artifacts. Run from a trusted Linux machine. It does not detect
  everything; a clean result is not a clean bill of health.
- **ADB backup + analysis:** `adb backup -all -shared -f backup.ab` while unlocked,
  then analyze with MVT or manually.
- **Network capture:** configure the phone to route through a hotspot running
  `tcpdump`; look for unexpected outbound connections, especially at odd hours or
  to commercial spyware infrastructure (Intellexa, NSO, Candiru C2 ranges are
  partially public via threat intelligence feeds).

**After assessment:**
- If you believe compromise is real: factory reset is the only reliable remediation
  for user-space malware. For firmware-level compromise (rare, nation-state),
  no reset helps; get a new device.
- Rotate every credential that was accessible on the device (passwords, 2FA seeds,
  API keys).
- Rotate the Clairix relay's authentication if you used it from the compromised device.
- Change accounts associated with any app that had network access.
- Consider the Auditor check on the new/reset device as a baseline going forward.

**Documentation:**
- If you plan to report to a journalist, attorney, or digital rights org, preserve
  the device in its current state before any reset; the MVT output and a full ADB
  backup are evidence.
- Access Now Digital Security Helpline (<https://www.accessnow.org/help>) provides
  free assistance for journalists, activists, and civil society.

---

## 16. Gaps in this guide

Unconfirmed from the GrapheneOS source pages (fetched 2026-11-08):
- Exact Wi-Fi/Bluetooth auto-off timeout settings and UI location on current builds
- Seedvault setup steps and supported backup targets
- Always-on VPN menu text on current builds
- GrapheneOS opinion on F-Droid/Accrescent
- eSIM support specifics by device model
- Whether the camera app strips EXIF on share by default

The final ~6.5k characters of the FAQ were not read. Check these on the device and
at <https://grapheneos.org/usage> before treating them as settled.

Physical surveillance data (ALPR coverage, Flock deployments) changes frequently;
the Atlas of Surveillance is crowd-maintained and may lag real-world deployments.
File a FOIA request for current data in your jurisdiction.
