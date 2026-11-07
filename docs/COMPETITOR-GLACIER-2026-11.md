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

## Not checked

Server-side code, altOS internals, the Android release configuration, TLS behaviour of live Glacier
hosts, and the CEO's identity. `docs.glacier.chat` did not resolve. Funding figures (ZoomInfo: $6.1M)
are unconfirmed. Some criticism of the consumer app (Leah Files, Candace Owens) is partisan commentary,
not testing.
