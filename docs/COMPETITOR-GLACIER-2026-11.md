# Glacier Security: what it is, what its code and whitepaper show, and how it differs from Clairix

Researched 2026-11-07 from glacier.chat, theglacierapp.com, the Glacier Crypto Whitepaper (rev 2024-03-10),
the two public repos under github.com/GlacierSecurityInc (read, not run), App Store listings, and press.
Marketing claims are marked **claim**; things read in code or the whitepaper are marked **seen**.
No independent audit of Glacier was found.

## What Glacier is

Glacier Security Inc. (Annapolis Junction, MD; founded 2015; founder-owned per its own legal page) sells
managed secure phones and an encrypted messaging, calling and VPN suite to executives, governments and
wealthy individuals. It is not a carrier. Co-founder and CTO Alex White is a former NSA engineer. A
consumer app (Security App LLC, launched June 2026, promoted by Shawn Ryan) sells a NextDNS-based tracker
blocker, a VPN and Twilio-backed burner numbers for $14.99/month plus add-ons.

| Product | What it is |
|---|---|
| Guardian (enterprise) | Per-customer XMPP servers on AWS, hardened iPhones or Pixels (or "altOS", an AOSP build), console, Security Hub |
| Pro / Guardian for Orgs | The same app at $40/user/month, 5-user minimum |
| QCORe | Per-organisation WireGuard VPN with rotating "obfuscation nodes" |
| Glacier App | Consumer DNS + VPN + burner numbers; no messaging |

Named customers: Honeywell Aerospace (testimonial). Reseller: UltiSat (2025). No federal contract found.

## What the code and whitepaper show

- **Messaging (seen).** XMPP with OMEMO (Signal's X3DH and Double Ratchet over X25519, Ed25519,
  AES-CBC, HMAC-SHA256). Android is a GPLv3 fork of Conversations; iOS is a ChatSecure descendant using
  SignalProtocol-ObjC. Group chat is pairwise sessions per device.
- **Calls (seen).** WebRTC with DTLS-SRTP, TURN relay optional. External calls and SMS go through
  Twilio (iOS bundles the Twilio Voice and Conversations SDKs). The whitepaper says external calls are
  "not encrypted for the non-Glacier party" once they reach the phone network.
- **VPN (seen).** WireGuard (ChaCha20-Poly1305, Curve25519). An "obfuscation node" swaps the client IP.
- **Accounts (seen).** AWS Cognito/Amplify with MFA; sign-in through console.glacier.chat. Not
  anonymous by default; the policy mentions an email address.
- **Storage (seen).** SQLCipher on iOS, Keystore-backed credentials on Android, `allowBackup="false"`,
  FLAG_SECURE as a setting, jailbreak checks on iOS.
- **Transport trust (seen).** Pinning is to Amazon Trust Services CAs, not to a Glacier key, plus
  trust-on-first-use for user-managed XMPP servers. The public repo's Android pin hashes are placeholders
  (the real config is withheld), so Android pinning could not be checked.
- **Push (seen).** Push metadata travels in a libsodium sealed box so APNs does not see JIDs. The
  whitepaper says Android sends an FCM token and a hash of the Glacier ID plus Android ID to the push
  server.
- **Trust policy (seen).** iOS marks new OMEMO devices `trustedTofu` "since we are in a closed network";
  Android has a blind-trust-before-verification setting. Users must verify fingerprints by hand.
- **Bug record (seen).** iOS 7.3.2 fixed a case where a group "could previously allow a group message
  to be sent in plaintext."
- **Retention.** Whitepaper: call and message records (username, tokens, keys, push tokens) kept 7 days.
  Consumer policy: burner-number call metadata 48 hours in carrier systems, up to 7 days on Glacier's.
- **Secrets.** No keys or tokens found in either repo.

## Claims that the evidence does not support

| Claim | Finding |
|---|---|
| "Untraceable calling"; "no call detail records" (blog) | The whitepaper keeps CDR-like records for 7 days; Twilio sees the phone-network leg |
| "Quantum-safe" keys | The whitepaper only adds Quantinuum Origin randomness to Curve25519. A better random source does not stop Shor's algorithm, and OMEMO itself uses X25519 |
| "Anonymous accounts, no phone number or email" | Accounts run through Cognito; true for some deployments at most |
| "Zero knowledge" | The operator or customer runs the XMPP servers, which hold encrypted queues and metadata |
| "Weak forward secrecy" is not claimed | The whitepaper itself says OMEMO gives only weak forward secrecy and no online deniability |
| HIPAA/NIST/CNSA/GDPR compliance | Alignment statements, no certificates or audit reports |

## How it differs from Clairix

| Topic | Glacier | Clairix |
|---|---|---|
| Phone | Managed, replaced OS or hardened device sold to you | Your own stock Android; no root or custom ROM |
| Core job | Secure comms and VPN for a managed fleet | Detect cameras, trackers, fake towers, spyware, plus comms and offline navigation |
| Who runs servers | Per-customer cloud (AWS) run by Glacier | A relay (Cloudflare) that gets sealed envelopes and no sender |
| Message crypto | OMEMO (X3DH/Double Ratchet), AES-CBC | Olm with X25519 + ChaCha20-Poly1305 sealing |
| Phone numbers | Twilio-backed virtual numbers, call metadata kept 2 to 7 days | Numbers are relay identifiers, not carrier lines |
| Post-quantum | Marketing claim, no PQ algorithm named | Listed as an accepted gap in `THREAT-MODEL-GAPS-2026-11.md` |
| Auditability | Apps are open source; servers, altOS and Android config are not | Open source |
| Price | $14.99/mo consumer, $40/user/mo Pro, quote-only Guardian | See `SUPPORTER.md` |

## What Clairix can learn or watch

1. **Honest wording on "anonymous" and "untraceable".** Glacier's own whitepaper contradicts its blog; do
   not repeat that pattern. Keep the README's plain "what it cannot do" section current.
2. **Retention windows stated per data type.** Glacier's consumer policy lists 48 hours and 7 days.
   `README.md` "What the relay keeps" already does this; keep it that precise.
3. **Plaintext-send regressions.** Glacier's 7.3.2 group bug is a reminder to test that no code path
   sends a group or reply unencrypted when keys are missing.
4. **Pinning scope.** Pinning to a public CA (Amazon) is weaker than pinning a service key. Clairix's
   optional relay-key pinning is the stronger design; consider making it the default.
5. **Push metadata.** The sealed-box push envelope is a good pattern if Clairix push ever carries routing data.
6. **Watch list.** Glacier's pricing, any audit it publishes, and whether its "quantum-safe" claim ever
   names a real PQ algorithm.

## Second pass (2026-11-07): more findings

**Company and people**
- **Trademark.** Glacier Security Inc. filed an intent-to-use application for GLACIER on 2026-01-09
  (USPTO serial 99585796, mobile software for cybersecurity and secure messaging). It is pending, not
  registered. Source: uspto.report.
- **Alex White** (CTO): per the consumer launch release, M.S. from Johns Hopkins and a B.A. from Davenport;
  decorations listed include the National Intelligence Meritorious Unit Citation. Not independently verified.
- **Shawn Ryan.** Crunchbase lists him as co-founder (May 2026). His own show episode discloses no stake and
  says only that he "partnered with some of the developers." Whether he has equity is unconfirmed. Security
  App LLC's state of formation and agent were not found, and its legal link to Glacier Security Inc. is not
  stated.
- **Team.** The site names no staff besides the founders; every post is bylined "Team Glacier." LinkedIn
  lists 11-50 employees. No CEO name was found (directories show initials only).

**Partnerships and demos**
- **RSA, 2023-04-26:** previewed a suite "powered by Quantinuum's quantum hardened keys" (booth N 5176).
  Quantinuum's product is a random-number source (Quantum Origin Onboard, June 2023). That does not make
  X25519 or WireGuard post-quantum. No Fornetix link appears outside the whitepaper's one mention.
- **Honeywell, AUSA 2023:** GoSecure Comms "with Glacier Mobility Suite," aimed at military logistics and
  drones. Honeywell's own press pages did not mention it.
- **T-REX 2024 (Camp Atterbury, Army/RDER, posted 2024-09-03):** the GoSecure Kit ran encrypted calls and
  messages over satellite and cellular, live tracking, and a compromised-device playbook (quarantine, alert,
  wipe). Result stated only as "resilient and reliable"; no metrics.
- **Security Hub (2022):** no MDM needed; checks compromise, OS update status, screen lock, biometrics and
  whether the VPN is on. In the iOS code the report carries a device id, device model, app and OS versions,
  a compromised flag with detail, lock settings and VPN state. That matches the "no messages or location"
  claim but is still device-identifying.
- **Desktop client** (Mac and Windows beta, 2026-01-14): chat and files, no name, phone or email required.

**Docs and policy**
- **Public FAQ** (Notion "Security and Trust", read in a browser): says Glacier "cannot read or decrypt any
  messages," uses OMEMO, and that external calls are TLS to Glacier then unencrypted to the phone network.
  It says per-message keys encrypt with AES-GCM, while the whitepaper lists AES-CBC for messages and GCM for
  files. The two documents disagree.
- **Domains.** The FAQ says Glacier avoids domain names tied to Glacier Security Inc. The public clients
  hard-code `console.glacier.chat` for login and password reset, so at least the account flow is
  identifiable.
- **Policy changelog.** The enterprise policy has one logged change (2025-01-14, VPN declaration for Google
  Play). The consumer policy is dated 2026-06-15 initial, 2026-10-27 latest. The viral claim that retention
  was quietly added after launch could not be checked: archive.org was unreachable from the tool. The current
  policy does disclose call metadata retention.
- **Entity and terms.** Enterprise terms: Maryland law, Anne Arundel County courts, liability capped at $100.

**More code observations** (public repos, read only)
- Android sends its JWT only to an allow-list of Glacier API hosts and fails closed if the host list is
  unknown. WireGuard runs through the Go backend VPN service. No WebView or JavaScript bridges. Most exported
  components are inherited from upstream Conversations (links, share, backup import).
- The iOS API-header file carries a "Created by OpenAI" header, so at least some code was AI-assisted. Both
  repos each show a single squashed commit by one developer, so there is no public history to review.

**Independent and competitor views**
- **Deniable** (a competing vendor, so biased) says Glacier's published docs are silent on duress PINs,
  hidden vaults and plausible deniability, and that a seizing inspector could see other profiles. It says
  altOS is a custom AOSP build on Pixels and that it is unclear whether altOS is published. Unverified.
- **User reviews of the consumer app:** about 4.2-4.3 stars from roughly 300 ratings. Complaints: sign-in
  loop, hard cancellation, VPN dropping after reboot, no SMS on burner numbers at launch. Android was "coming
  soon" at launch. Guardian on Android has about 1,700 installs by a third-party estimate.
- **No independent audit, pen-test report or security-researcher write-up of Glacier turned up.**

## Not checked

Server-side code, altOS internals, the Android release configuration, TLS behaviour of live Glacier
hosts, the CEO's identity, and Security App LLC's registration. `docs.glacier.chat` did not resolve and
three Notion help pages (app changelog, install guide, Guardian navigation) would not render. G2, ZoomInfo,
Crunchbase, USPTO and Dark Reading returned 403 to the fetch tool, and archive.org was blocked, so
historical policy versions were not compared. Funding (ZoomInfo: $6.1M) is unconfirmed. Criticism of the
consumer app from Leah Files and Candace Owens is partisan commentary, not testing.
