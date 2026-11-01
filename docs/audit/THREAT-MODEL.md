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
| **P** | An intimate partner or family member with household access: knows or has watched the phone's PIN, handles the phone unlocked, may have installed a monitoring app or placed a tracker, and may make the owner unlock it | Read the lock screen and the notification shade; open any app on the unlocked phone and see which apps are installed; read the call log, messages, browser history and clipboard; add a fingerprint or face; change the PIN; demand the phone and the app be unlocked in front of them; notice when a tracker or a monitoring app stops reporting |
| **Q** | The phone's location provider: Google Play services on most phones, or whoever compels Google | Learn where the phone is each time Clairix asks Android for a position: one worked out from Wi-Fi and cell towers sends it the identifiers of the networks and towers around the phone, with the phone's IP address |
| **W** | The push service behind a phone's UnifiedPush endpoint, when the owner set up push wake-ups: the server the phone's distributor app uses (for ntfy, ntfy.sh unless its owner chose another, such as one the relay's operator runs; for Conversations, up.conversations.im), and whoever compels it | Learn the relay's address and when each wake was sent, which is when something arrived for the phone while its live connection was down (one wake covers everything until the phone fetches, or 30 seconds); learn the phone's network address and when its distributor app is connected; withhold or delay wakes, or send false ones, which only make the phone fetch over its signed channel. Never what arrived or from whom: the relay posts only the word "wake" |
| **X** | Someone who gets a copy of the owner's recovery file: from the USB stick, computer or cloud folder it was saved to, or a household member who finds it | Try recovery codes offline, each at the cost of an Argon2id run (64 MiB, three passes) against a 120-bit generated code; with the code, everything the owner's identity can do from then on: restore it on another phone, take over the number while its mailbox exists, read what is sent to it afterwards and write as the owner. Not read past messages, which are not in the file |

## What Clairix protects, and against whom

| Asset | Goal | Against | How (see [ARCHITECTURE.md](ARCHITECTURE.md)) |
|---|---|---|---|
| Message, voice note, photo, call content | Confidential and authentic; forward secrecy and post-compromise security | R, F, N, U, B, W | Olm double ratchet (vodozemac) per contact; calls DTLS-SRTP between the phones; a push wake carries only the word "wake" |
| Who sent an envelope | Not learnable by the relay, nor later from a seized phone | R, F, N, L, W | Sealed envelopes; no sender in the request; blind delivery tokens; sealing prekeys rotated weekly and deleted (format 3); nothing about a sender reaches the push service |
| The sender claim inside an envelope | Convinces the recipient only; proves nothing to anyone else | R, L, anyone the recipient shows it to | HMAC keyed by X25519 between the sender's sealing key and the recipient's prekey (format 3) |
| Who looks up whom | Not in URLs or logs; lookups and one-time keys rate-limited | R, U | Lookups in signed bodies; per-account and per-relay limits |
| That a number exists | Not learnable by sending, paying or flooding, here or through other relays | U, F | A send to a number that does not exist is accepted and dropped; federated limits answer the same either way; a payment must name the fingerprint of the payee's identity key, as a lookup of an unlisted number must, and a number that does not exist and a fingerprint that does not match are refused alike before any coin moves |
| A contact's keys | Never silently replaced; only an in-person scan verifies | R, F, U, A | Keys pinned at first contact; a change is flagged and what it sends is held until the owner looks; links and invites never verify or replace keys |
| Envelope sizes and Olm message types | Short messages, receipts and first messages look alike | R, N | Padding before Olm and inside the sealed box |
| The phone's network address | Hidden from relays and the network when the owner chooses | R, F, N | Tor (Arti) in the app, onion addresses for relays that have one; relay-only calls via TURN |
| Contacts, messages and keys at rest | Ciphertext to anyone who copies the files; unreadable while the phone is locked | L | SQLCipher under a Keystore-wrapped key; identity and database keys under an unlock-bound Keystore key; a locked inbox for what arrives meanwhile |
| The phone under duress | Everything gone fast; under the duress passcode, a lock screen that stays and an empty app behind it rather than a first run. Not hidden: that Clairix is installed, and that it is empty to whoever opens it | L | Wrong-passcode limit and duress passcode start a wipe that deletes Keystore keys first; the duress passcode's wipe leaves a decoy (its own lock under a new key, the first run done) and brings the lock screen back in a fresh process |
| What a notification says, on the lock screen and in the shade | Nothing about a finding or a message on the lock screen; nothing about a finding in the shade unless the owner asked for it and Clairix is unlocked | P, L | Alert and connection channels secret on the lock screen; a message notification there shows only that something came; an alert reads "Unlock to read" unless "Show alert details" is on (off by default) and Clairix is unlocked; ongoing notifications say only that something runs; locking Clairix collapses alerts into "Unlock to see alerts" and messages into "Unlock to read" (security/NotificationPresentation.kt). An incoming call shows on the lock screen, so that it can be answered |
| What Clairix holds, on a phone someone else can unlock | Not readable without the app's own passcode, when one is set | P | App lock with its own passcode, separate from the phone's PIN, locking when the screen goes off or after the chosen time in the background; biometric unlock through a key Android destroys when a fingerprint or face is added; every screen kept out of screenshots and the Recents thumbnail |
| Knowing the phone was handled | The owner is told | P | Unlock ledger: unlocks in the owner's sleep window, wrong passcodes, PIN changes and the ledger's admin being switched off; biometric tripwire: a fingerprint or face added, or every one removed (removing one of several is not caught) |
| The owner's safety when acting on a finding | Not steered into removing a tracker or a monitoring app before saving the evidence and thinking about who will notice | P | A safety card on the tracker and travelling-with-you explainers, the stalkerware findings (Phone check's Device tab and Stalkerware page), unlock and enrolment alerts and the Wipe page: removing it can tell whoever placed it, with export of the evidence first and a way to Get help; before a flagged app's settings open, a sheet offers to save its evidence (installer, install date, access, reasons, signing-certificate SHA-256, time) to the timeline, where the retention does not delete it, to talk to someone first, or to open the settings anyway; a copy to the clipboard only on a separate tap, never for a keyboard (which can read the clipboard), marked sensitive and cleared when the sheet closes; the safety card's export asks for the passcode like every other export, and locking closes the explainer sheet that carries it |
| A way to help that fits the phone | Helplines for the owner's country, with a text or chat option where calling is not safe | P | Get help page (Settings and the Comms menu) from a bundled list, every entry checked against the service's own site or an official government page, with that page and the date stored; tap-to-call through the dialler, which needs no permission; the page says what a call, a text and a chat leave on the phone, and Clairix records nothing about it; the page and the sheet close when Clairix locks, so an unlock the owner is made to do does not open on them |
| The identity, contacts and groups, when the phone is lost, smashed or wiped | Recoverable by the owner, and by nobody without the owner's code; never an old ratchet brought back | L, X, R | Recovery file the owner saves through the system file picker (comms-crypto src/recovery.rs, comms/RecoveryFile.kt, comms/RecoveryRestorer.kt): ChaCha20-Poly1305 under Argon2id of a generated 120-bit code with 20 check bits, the whole versioned header authenticated; it holds the identity and sealing keys, the relay and number, contacts with pinned keys and verification, the blocked list, groups and rosters, and never a message, an Olm session, a one-time or fallback key. A restore makes a new fallback key and sealing prekey, moves the key-id counters past the old phone's, replaces the relay's one-time key pool (`PUT /v1/keys` with `replace`), a new transport key and no push endpoint; if the mailbox is gone, the same keys register again and contacts get the move notice |
| Releases, map files, the relay list | Only the project's signed artifacts are accepted | S, N | Ed25519 signatures checked by the app; pinned workflow actions; reproducible builds |
| Being reachable | Messages keep moving when a relay is blocked | N, R | Alternate relay addresses; onion addresses; moving to another relay with contacts told; the offline mesh |
| A mailbox's room for contacts | One sender cannot fill it against the people the owner writes to | U, F | Contacts' passes: envelopes without one share a small part of the queue and an hourly allowance |
| Where the phone is, from the positions Clairix asks for (scanner, navigation, SOS, the GPS check) | Not handed to a location provider when the owner chooses | Q | Satellite-only positioning (Settings, Location): fixes from the satellite receiver alone, no Wi-Fi/cell position asked for, and the GPS check's cross-check off with it; "Cross-check GPS against Wi-Fi/cell position" turns off that one request on its own. Otherwise Google Play services where it is installed, and Android's own location service where it is not or where it fails a request (analysis/LocationClient.kt) |
| An SOS position, and the follow verdict | Never an app's invented position | P, A | A fix Android marks as mock (an app chosen under Developer options, Select mock location app) never goes in an SOS, never places a sighting and never moves the odometer; the position reads as unknown and the Trackers screen says positions are being faked, so following is neither confirmed nor ruled out until the app is turned off (analysis/LocationSource.kt MockFixes, security/SosLocation.kt) |

## Assumptions

- **The operating system is honest.** Android, its Keystore and the baseband
  are not working for the adversary. If they are, Clairix cannot help (the
  README says so plainly). Keys live in the Keystore; StrongBox is used for
  the passcode verifier when the phone has it.
- **vodozemac is correct.** Its Olm implementation has been audited; Clairix
  uses it as a library and does not change it.
- **The cryptographic primitives hold:** X25519, Ed25519, ChaCha20-Poly1305,
  HKDF-SHA256, HMAC-SHA256, AES-256-GCM, ristretto255 with SHA-512 (RFC 9497),
  Argon2id (RFC 9106, for the recovery file), SQLCipher's page encryption.
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
- Hiding it from Google (Q) while Satellite-only positioning is off: on a
  phone with Google Play services every position fix Clairix asks for goes
  through it, and one from Wi-Fi and cell towers sends Google their
  identifiers.
- That the GPS check can hold the satellites against a Wi-Fi/cell position
  on every phone: with no Wi-Fi/cell location provider, with the cross-check
  off or with Satellite-only positioning on, that comparison does not run,
  and the GPS page says so.
- Defeating traffic analysis by someone who sees a relay's traffic in and out:
  the relay mixes (it holds each envelope a random few seconds and releases in
  shuffled batches) and cover traffic is optional, but it is one relay, not a
  mix network, and it sees each envelope's recipient, size and time.
- Hiding the recipient of an envelope from the relay that holds their mailbox.
- Hiding from the push service (W), once push wake-ups are set up, when
  something arrived for the phone while its live connection was down. The
  wake carries nothing else, and a relay's operator can trust only a push
  server they run themselves (`PUSH_HOSTS` with `PUSH_HOSTS_ONLY`, see the
  relay's README); which server a phone's distributor uses is its owner's
  choice in that app.
- Linking: both relays involved in a move see the same keys registered on
  each, which ties the old number to the new one for whoever can read both.
- That ClairixCoin is anything more than a relay-local ledger, on unless the
  relay's operator switches it off, whose coins only that operator creates (see
  "What ClairixCoin is" in the README).
- That detection (trackers, cameras, IMSI catchers, spyware) is complete.
- Hiding that Clairix is installed from someone who handles the phone
  unlocked (P): its name and icon are in the launcher and in Android's app
  list, its device admin, once enabled, is listed by name in Android's
  settings, and the scanner shows that it runs in the shade.
- Protecting what Clairix shows from someone who knows the app passcode, or
  who makes the owner unlock Clairix in front of them. The duress passcode
  wipes instead of opening, and a wipe can itself be noticed.
- That removing a tracker or a monitoring app is safe. Clairix cannot tell
  whether the person who placed it will notice; it says so before anything is
  removed and offers to save the evidence first.
- Protecting a recovery file once it leaves the phone (X): it is the owner's
  file, wherever they saved it, and Wipe Clairix and the duress passcode
  cannot reach it. Anyone with the file and its code can be the owner from
  then on. Restoring also does not take the keys back from the old phone: a
  locked old phone loses its transport key and push endpoint, but one that
  someone else can unlock still holds the same identity key.
- Bringing back what arrived between losing the phone and restoring: it was
  encrypted on sessions the file does not hold, and the restored phone asks
  each sender for a new session instead.
- That a call, text or chat to a helpline leaves no trace. The phone's call
  log, messaging app and browser keep their own records, and the Get help page
  says so.

## Card Vault and card emulation

A full-edition feature (sideload only; built with `CARD_VAULT` on). It is not part
of the messaging security model and is called out here because it stores card
credentials and can replay them.

- **Who it is for.** The owner's own contactless cards — an office or building
  badge, a transit card, a gym or hotel key — recorded so the phone can stand in
  for one the owner carries. It is not for cloning a card issued to someone else.
  The app asks the owner to acknowledge this once before the first save
  ("Only cards issued to you. Recording and replaying someone else's card or
  credential may be a crime."), stores that acknowledgement, and shows the same
  line on every tag row that offers a way into the vault.
- **What is stored.** For a saved card: its UID, its resolved type, a label the
  owner gives it, and — only for an ISO 14443-4 card recorded by an explicit
  "Record for vault" re-tap — the recorded APDU command/response pairs. An
  ordinary tap identifies a card (SELECT PPSE and the AID SELECTs that name a
  payment network) and records nothing; it never sends GET PROCESSING OPTIONS,
  READ RECORD or the access-control AID probes, and the scanned-tag list in
  memory holds no pairs. The capture runs only in the full edition and only on
  the deliberate re-tap. A recognised **payment card is never recorded or
  emulable**: no follow-up is sent to it, and it cannot be saved.
- **Where the key lives.** The stored cards are encrypted with AES-256-GCM under
  an Android Keystore key that never leaves secure hardware (StrongBox where the
  phone has it, the TEE otherwise). The key is authentication-bound: it decrypts
  only within 30 seconds of the owner confirming identity with a strong biometric
  or the device PIN/pattern/password, enforced by the Keystore, not by the screen.
  A phone with no secure lock screen cannot make the key, so the vault refuses.
- **What replay can and cannot do.** Emulation (`HostApduService`) replays the
  recorded exchange to a reader, matched exactly or on the CLA/INS/P1/P2 prefix.
  It works only for static exchanges a reader does not challenge. It **cannot**
  defeat challenge-response: DESFire EV2 and MIFARE Plus SL3 authenticate with
  keys the phone does not hold, EMV cryptograms are per-transaction, and a card's
  own protocol (MIFARE Classic/Ultralight, FeliCa, ISO 15693) is a different RF
  layer HCE cannot speak at all. Those cards are kept for identification only.
- **Why there is no PIV probe.** The access-control probe list deliberately does
  **not** include PIV (the U.S. federal / government-contractor badge, NIST
  SP 800-73, AIDs `A0000003080000100001` and `A0000000620001`). No Clairix feature
  needs a federal badge; PIV authenticates with on-card PKI that static replay
  cannot satisfy in any case; and probing government badge applications is a legal
  and policy liability with no product benefit. The probes that remain are
  ordinary access and transit application IDs a person may hold on a card of
  their own.
- **Disarming.** An armed card stops answering readers when the screen goes off,
  after five minutes in any case, and immediately when the app lock engages or a
  wipe runs — the loaded pairs are forgotten and the dynamically registered AIDs
  withdrawn.
- **Not in the Play edition.** The Play build is compiled with `CARD_VAULT` off:
  no vault, no capture, no `HostApduService`, its manifest entry or AID resource.
  The release workflow fails a Play bundle whose merged manifest declares card
  emulation.
- **What this does not defend.** A card the owner does not own but can tap can be
  recorded; the acknowledgement is a warning, not an enforced check, because the
  phone cannot tell whose card it is. The owner-handling adversary (**P**) with
  the device unlocked within the auth window could open the vault; the
  acknowledgement, the app lock and the 30-second window narrow but do not close
  this.

## Known open issues

[`../OPEN-ISSUES.md`](../OPEN-ISSUES.md) lists every finding that is not
closed, with what remains of each; [`../SECURITY-FINDINGS.md`](../SECURITY-FINDINGS.md)
tells each in full.
