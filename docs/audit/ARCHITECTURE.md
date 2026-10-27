# Architecture

How the parts fit, and how each thing that matters travels between them. File
references are to this repository; [SCOPE.md](SCOPE.md) has sizes and the
questions to ask of each.

```
 phone A (app)                    relay A ─────── federation ─────── relay B                    phone B (app)
 ┌─────────────────────┐   https/wss   ┌──────────┐  signed relay-to-relay  ┌──────────┐   https/wss   ┌─────────────────────┐
 │ Kotlin: CommsRepository ├──(Tor)──────►│ Worker   │◄──────────────────────►│ Worker   │◄──────(Tor)──┤ CommsRepository     │
 │ Rust:   comms-crypto    │              │ Mailbox  │                         │ Mailbox  │              │ comms-crypto        │
 │ SQLCipher, Keystore     │              │ (DO)     │                         │ (DO)     │              │                     │
 └─────────┬───────────────┘              └──────────┘                         └──────────┘              └─────────┬───────────┘
           └──────────────────────── offline mesh: the same sealed envelopes over Bluetooth ───────────────────────────┘
```

## The parts

**comms-crypto** (`comms-crypto/src/lib.rs`), Rust, called from Kotlin through
UniFFI. It owns the identity and does all the cryptography. It touches neither
the network nor the disk.

- The identity is an Olm account (vodozemac): Ed25519 signing key, Curve25519
  identity key, signed one-time and fallback keys. It also holds a long-term
  X25519 sealing key, rotating sealing prekeys, and up to 5 sessions per peer
  (16 at most, the recent ones protected).
- `encrypt` / `decrypt` cover Olm (session format 2, full-length MACs), padding,
  and the sealed envelope (format 3, with format 2 kept for older peers).
- Blind delivery tokens use RFC 9497 VOPRF over ristretto255-SHA512, with the
  relay's DLEQ proof checked.
- It also makes safety numbers, fingerprints, mesh tags and the transport key.
- `tor.rs`: Arti, the Tor Project's Rust client, behind a SOCKS5 proxy on
  127.0.0.1. The proxy takes a username and password made fresh at each start,
  serves CONNECT only, resolves names at the exit, and gives every connection
  its own circuit.

**The relay** (`comms-worker/src/`), TypeScript on Cloudflare Workers with a
Durable Object per mailbox. The same code runs self-hosted under workerd
(`comms-worker/selfhost/`).

- `index.ts`: routing, registration, lookups, sending, federation endpoints.
- `mailbox.ts`: one Durable Object per number. It holds keys, the queue,
  mixing, allowances, one-time keys, sealing prekeys and the transport key.
- `auth.ts`: signed requests.
- `tokens.ts` + `voprf/`: blind tokens.
- `federation.ts`: relay-to-relay signing and limits.
- `comms-logic.ts`: pure rules such as token allowances and validation.

**The app** (`android/app/src/main/java/app/clairix/comms/`), Kotlin.

- `CommsRepository.kt`: the protocol. Registration, contacts, sending,
  receiving, groups, calls, the locked inbox and moves between relays.
- `RelayClient.kt`: HTTP and WebSocket to the relay, request signing, tokens,
  alternates and onion addresses.
- `CommsStore.kt`: SQLCipher database.
- `CommsWire.kt`: the JSON payloads inside Olm.
- `KeyChangeGuard.kt`: what happens to payloads from changed keys.
- `IdentityStore`, `Vault`, `VaultBox`, `KeystoreBox`, `TransportKeyStore`,
  `LockedInbox`, `CommsDb`: at-rest protection.
- `TorTransport.kt`: the switch and the proxy selector.
- `mesh/`: the offline mesh.

**On-device protection** (`android/app/src/main/java/app/clairix/security/`):

- `AppLock.kt`: passcode verifier, attempt counting, wipe threshold.
- `Wiper.kt`: the wipe.
- `DuressPhrase.kt`: the duress phrase, which sends a silent SOS.
- Lock-screen, recording and biometric tripwires.

## Registration

1. The phone makes an identity and a transport key (`Identity.create`,
   `TransportKey.generate`) and a batch of one-time keys.
2. It posts `POST /v1/register` with its public keys, one-time keys, fallback
   key, current sealing prekey, transport key, `listed`, and either the
   relay's enrollment secret or an invite code. The body is signed by the
   Ed25519 key it registers and dated (accepted within five minutes).
3. The relay checks every signature, refuses a key already registered there,
   and gives a random nine-digit number. Nothing else identifies the account.
4. For a relay on the signed relay list (`relays/relays.json`), the phone
   first checks that the relay answers `GET /` with the key the list gives it
   (`CommsRepository.listedKeyProblem`).

## Requests about one's own mailbox

These are fetching the inbox, acknowledging, keys, invites, push and lookups.

- They are signed with the identity key over number, time (±5 min), a
  single-use nonce, method, path and a hash of the body (`auth.ts`,
  `RelayClient.signed`).
- Fetching, acknowledging and the live socket may instead be signed with the
  transport key (header `X-Clairix-Key: transport`). That key can do nothing
  else, so a locked phone can collect what arrives.

## Delivery tokens

1. The phone fetches tokens in batches, over its signed channel, as blinded
   VOPRF elements.
2. The relay evaluates them with its key for that kind (delivery, traffic,
   report) and epoch, and returns a DLEQ proof.
3. The phone unblinds the results and checks the proof against the key `GET /`
   publishes. It checks the epoch against its own clock and pins keys one
   epoch ahead. It never fetches keys straight after a token request, so the
   relay cannot pair the two requests.
4. Spending a token shows its input and output. The relay checks it made that
   output, and keeps a hash of each spent token until that key is retired
   (keys live one 14-day epoch). It cannot tell which account a token went to.
5. Allowance: 1,000 delivery tokens an hour per account by default; an
   operator can change it with `DELIVERY_TOKENS_PER_HOUR`.

## Adding a contact

| How | Verified? | Can replace pinned keys? |
|---|---|---|
| Scanning their QR code in person | Yes | Only scanned on that contact's own page |
| Looking up a number (`identity` / `bundle`, pinned by the Ed25519 fingerprint the phone expects) | No | No |
| An invite or contact link | No | No |
| Their first message, when they added you | No | No |

A contact on another relay carries that relay's origin (`Contact.relay`).

## Sending a message

1. The phone builds a JSON payload (`CommsWire`): type, fields, and the
   sender's number, name, keys and relay origin.
2. The crate pads it to a bucket, which is a multiple of 160 bytes for small
   payloads and a few fixed sizes for media. It then Olm-encrypts the result,
   starting a session from a fetched bundle if there is none (the bundle's
   claim ticket accounts for the one-time key spent).
3. It seals the result: format 3 to the recipient's current sealing prekey,
   with an HMAC sender claim, padded inside to 1 KiB steps. Format 2 goes to
   the long-term sealing key with a signed claim, and only for a peer never
   seen with a prekey. Once a peer has published a prekey, format 2 is refused
   to them (`StalePrekey`).
4. It posts `POST /v1/send` with `{to, envelope, claim?, mix, relay?, alert?}`
   and a delivery token. The request is not signed and goes on a connection
   and TLS session of its own, through Tor when Tor is on.
   - `relay` names the recipient's relay when it is not this one: the home
     relay forwards the request there.
   - `alert` (`call` or `sos`) is visible to the relay. It lets a locked phone
     ring or sound an SOS before it can open the envelope.
   - `mix: false` (call set-up, SOS, group changes) skips the mixing delay.
5. The mailbox queues the envelope with its recipient, size and time. A send
   to a number that does not exist is answered the same and dropped. Mixing
   holds envelopes a random few seconds and releases them in shuffled batches.
6. The recipient collects envelopes over the live WebSocket or `GET
   /v1/inbox`, and acknowledges them. An acknowledged envelope is deleted;
   others go after 30 days.

## Receiving

All of this is in `CommsRepository.handleLocked` and `processStagedLocked`.

1. The phone deduplicates by a hash of the envelope's bytes, then decrypts.
   The crate refuses a forged or misbound claim (`BadSignature`). A message on
   a session the phone lacks raises `NoSessionFor` or `DecryptFrom`. That
   starts a rate-limited resync (`ResyncLimiter`), only for a known contact
   whose keys match the claim.
2. The plaintext is staged in the database in the same transaction that marks
   the envelope seen, before anything that can fail acts on it.
3. The sender is resolved:
   - A known Curve25519 key gives the contact, provided the Ed25519 key matches.
   - Otherwise the claimed keys must be signed, and the claimed relay must
     confirm that number holds them.
   - A known number with new keys is flagged as changed.
4. `KeyChangeGuard` decides what happens to content from changed keys: it is
   held for review, group control is dropped, and calls are shown as missed.
   Everything else is processed.

## Federation

- A phone talks only to its home relay. For a contact elsewhere, the home
  relay forwards the anonymous send or the lookup to the other relay.
- The forwarded request is signed with the home relay's own Ed25519 key, over
  origin, target origin, time, nonce, method, path and body hash.
  - The target checks it against the key the origin publishes at `GET /`.
  - A request made for one relay is worthless at another.
- Limits between relays:
  - for all of one relay's phones together, 600 envelopes a minute, 120
    lookups a minute and 600 one-time keys an hour;
  - 30 sends a minute to one recipient from one relay;
  - per mailbox, 6,000 an hour from known relays;
  - a smaller allowance (10 at once, 30 an hour) from relays nobody on this
    one has written to in 90 days.

## The locked phone

1. When the screen goes off on a phone with a secure lock screen, `Vault`
   seals. The repository drops the identity, closes the database and puts the
   comms log away.
2. Their keys are under `VaultBox`: one data key, wrapped by an unlock-bound
   Keystore key (`setUnlockedDeviceRequired`). It is rewrapped whenever the
   lock screen changes, because Android 14 deletes unlock-bound keys when the
   lock screen is removed.
3. While sealed, envelopes are collected with the transport key. They are
   wrapped once more under `KeystoreBox`, which works while the phone is
   locked, into `LockedInbox`, and read in order after unlock.
4. An envelope marked `alert: call` rings as "Clairix call", naming no one.
   An SOS shows as "SOS from a group member". Anything else shows as "New
   Clairix message".

## Storage on the phone

| What | Encrypted with | Usable while locked |
|---|---|---|
| Identity pickle (account, sessions, sealing keys) | Random 32-byte key, wrapped by `VaultBox` | No |
| Message database (SQLCipher, every page) | Random key, wrapped by `VaultBox` | No |
| Stored media, duress phrase, comms log | `VaultBox` | No |
| Transport key seed, locked inbox, delivery tokens, claim tickets | `KeystoreBox` (Keystore AES-GCM, no unlock condition) | Yes |
| Comms settings (relay, number, push endpoint) | `SealedPrefs` (`KeystoreBox`) | Yes |
| App passcode | Not stored: HMAC verifier under a Keystore key (StrongBox when present) | n/a |

## The wipe

`Wiper.start`, from the duress passcode, the wrong-passcode limit or the Wipe
page, runs in this order:

1. Sign the relay account deletion in memory, for each relay the phone has an
   account on.
2. Delete every Keystore key.
3. Stop the services.
4. Clear notifications, the clipboard and push.
5. Delete the preferences, databases and files.
6. Send the signed deletions.
7. Kill the process.

A marker file makes the next start finish an interrupted wipe.

## Moving to another relay

`CommsRepository.moveTo` handles moves; `applyMove` handles a contact's.

1. The phone registers the identity it has on the new relay, with fresh
   one-time keys and its existing transport key, and gets a new number. Moving
   back to the relay it just left takes up the old account again (`GET /v1/me`
   answers).
2. In one transaction, contacts are re-pointed to their relays relative to the
   new one, and the phone's own group entries take the new number.
3. Every contact is owed a `moved` payload (`move_notices` table). It is sent
   over the existing session, and its sender fields carry the new number and
   relay.
4. A contact's phone renumbers the contact and everything under that number.
   It ignores a notice dated no later than the last one applied. A notice from
   changed keys is held, like any content.
5. The old mailbox is collected for 30 days whenever it answers. The phone
   backs off 15 minutes after a failure, and deletes the old account at the
   end. Replaced sealing prekeys are not deleted until that mailbox has been
   emptied too.

## The offline mesh

- Off by default. Phones swap envelope ids over Bluetooth and pass each other
  what they lack (`mesh/`).
- Bundles are the same sealed envelopes, addressed by a tag only the two
  people can compute (`Identity.mesh_tag`: a secret their sealing keys share,
  and the day).
- Limits: 8 hops, 48 hours, texts and SOS only.

## Tor

- With "Connect through Tor" on, every relay request and the relay list go
  through the in-app Arti proxy. Requests fail closed while Tor connects.
- A relay's onion address (from the signed list, or its `GET /`) is used
  first, so traffic never leaves Tor. If the onion does not answer, the phone
  falls back to the registered address for 10 minutes.
- Calls (UDP) and the push distributor's own connection do not use Tor.

## Supply chain

- Releases, offline map graphs, work-zone files and the relay list are signed
  with the project's Ed25519 asset key, and the app verifies them.
- Every workflow action is pinned to a commit.
- CI builds the app twice on two machines and fails unless the builds match.
  The NDK, cargo-ndk and Rust are pinned (`tools/reproducible/`).
- Release signing happens in the Release workflow from a repository secret.
