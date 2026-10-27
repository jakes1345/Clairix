# Threat model

Clairix is an Android app for people who may be watched by a state: end-to-end
encrypted messages and calls through relays anyone can run, an offline mesh,
and tools that look for trackers, fake cell towers and spyware. This document
covers the messaging side and the protection of what is on the phone, which is
where the security claims are. The detection tools are best-effort by design
and are out of scope for an audit (see [SCOPE.md](SCOPE.md)).

## Adversaries

| | Who | Can |
|---|---|---|
| **R** | The relay's operator, or whoever compels them or the platform under them (Cloudflare, a hosting company) | Read and change everything the relay stores and every request it sees, including the network address each request comes from; refuse, delay, drop, reorder or replay; serve wrong keys; run modified relay code |
| **F** | Another relay, honest or not, and relays made up to attack (any https host can claim to be one) | Send federated requests to a relay; answer lookups and accept sends for its own numbers however it likes |
| **N** | A network observer up to a national scale: the carrier, an ISP, a state on the backbone | See which addresses talk, when and how much; block hosts and protocols; with a compelled or rogue certificate authority, impersonate a relay over TLS |
| **U** | Other people with Clairix accounts, on the same relay or another | Register as many accounts as a relay allows, look numbers up, send envelopes, invite, pay |
| **L** | Someone holding the phone while it is locked: border control, police, a thief | Copy the app's files and storage, try passcodes, keep the phone powered and connected, wait |
| **A** | Other apps on the same phone | Claim the invite link scheme, connect to local ports, read what accessibility services and the clipboard expose |
| **S** | The software supply chain: whoever can change a release, a map file, the relay list or a workflow | Ship a modified APK, map or relay list |
| **B** | Someone nearby with a Bluetooth radio, while the offline mesh is on | Join the mesh, carry and inspect bundles, flood it |

## What Clairix protects, and against whom

| Asset | Goal | Against | How (see [ARCHITECTURE.md](ARCHITECTURE.md)) |
|---|---|---|---|
| Message, voice note, photo, call content | Confidential and authentic; forward secrecy and post-compromise security | R, F, N, U, B | Olm double ratchet (vodozemac) per contact; calls DTLS-SRTP between the phones |
| Who sent an envelope | Not learnable by the relay, nor later from a seized phone | R, F, N, L | Sealed envelopes; no sender in the request; blind delivery tokens; sealing prekeys rotated weekly and deleted (format 3) |
| The sender claim inside an envelope | Convinces the recipient only; proves nothing to anyone else | R, L, anyone the recipient shows it to | HMAC keyed by X25519 between the sender's sealing key and the recipient's prekey (format 3) |
| Who looks up whom | Not in URLs or logs; lookups and one-time keys rate-limited | R, U | Lookups in signed bodies; per-account and per-relay limits |
| That a number exists | Not learnable by sending, paying or flooding, here or through other relays | U, F | A send to a number that does not exist is accepted and dropped; federated limits answer the same either way |
| A contact's keys | Never silently replaced; only an in-person scan verifies | R, F, U, A | Keys pinned at first contact; a change is flagged and what it sends is held until the owner looks; links and invites never verify or replace keys |
| Envelope sizes and Olm message types | Short messages, receipts and first messages look alike | R, N | Padding before Olm and inside the sealed box |
| The phone's network address | Hidden from relays and the network when the owner chooses | R, F, N | Tor (Arti) in the app, onion addresses for relays that have one; relay-only calls via TURN |
| Contacts, messages and keys at rest | Ciphertext to anyone who copies the files; unreadable while the phone is locked | L | SQLCipher under a Keystore-wrapped key; identity and database keys under an unlock-bound Keystore key; a locked inbox for what arrives meanwhile |
| The phone under duress | Everything gone fast, without signs | L | Wrong-passcode limit and duress passcode start a wipe that deletes Keystore keys first |
| Releases, map files, the relay list | Only the project's signed artifacts are accepted | S, N | Ed25519 signatures checked by the app; pinned workflow actions; reproducible builds |
| Being reachable | Messages keep moving when a relay is blocked | N, R | Alternate relay addresses; onion addresses; moving to another relay with contacts told; the offline mesh |

## Assumptions

- **The operating system is honest.** Android, its Keystore and the baseband
  are not working for the adversary. If they are, Clairix cannot help (the
  README says so plainly). Keys live in the Keystore; StrongBox is used for
  the passcode verifier when the phone has it.
- **vodozemac is correct.** Its Olm implementation has been audited; Clairix
  uses it as a library and does not change it.
- **The cryptographic primitives hold:** X25519, Ed25519, ChaCha20-Poly1305,
  HKDF-SHA256, HMAC-SHA256, AES-256-GCM, ristretto255 with SHA-512 (RFC 9497),
  SQLCipher's page encryption.
- **Clocks are roughly right.** Signed relay requests carry a time checked to
  within five minutes; sealing prekeys are refused more than 14 days old or
  more than a day in the future; blind-token epochs are checked against the
  phone's own clock.
- **Verification happens in person.** Without scanning a contact's code or
  comparing safety numbers, the first keys the relay hands out are trusted on
  first use, and a relay that lies at that moment sits in the middle until the
  owner verifies.
- **The relay is not trusted** for confidentiality or for who sent what. It is
  trusted to be available, which is why there are many relays, alternates,
  onion addresses, moves and the mesh.

## What Clairix does not claim

These are stated to users in the README and are not findings:

- Protection from a compromised operating system, baseband or system app.
- Protection of a phone seized while unlocked, beyond what the app lock gives.
- Hiding the phone's location from its carrier.
- Defeating traffic analysis by someone who sees a relay's traffic in and out:
  the relay mixes (it holds each envelope a random few seconds and releases in
  shuffled batches) and cover traffic is optional, but it is one relay, not a
  mix network, and it sees each envelope's recipient, size and time.
- Hiding the recipient of an envelope from the relay that holds their mailbox.
- Linking: both relays involved in a move see the same keys registered on
  each, which ties the old number to the new one for whoever can read both.
- That ClairixCoin is anything more than a relay-local ledger.
- That detection (trackers, cameras, IMSI catchers, spyware) is complete.

## Known open issues

See the "Still open" items in [`../SECURITY-FINDINGS.md`](../SECURITY-FINDINGS.md):
among them, delivery tokens are not bound to a recipient, so one account can
fill a mailbox (finding 23); new registrations can farm ClairixCoin (25); the
`clairix://invite` scheme can be claimed by another app (30); release signing
runs on the CI runner.
