# Audit scope

In order of priority. If the budget covers only part of this, stop at the end
of a tier rather than skimming all of them. Line counts (`wc -l`) are for the
tree on 3 November 2026, after the newest release tag, `v3.0.0-beta.8`; several
of these files have grown a good deal since Beta 7 (`CommsRepository.kt` from
3,542 lines to 5,210, `mailbox.ts` from 1,280 to 1,699). Audit the commit
agreed with us, and count again there.

## Tier 1: the cryptography

**Files:**
- `comms-crypto/src/lib.rs` (2,733 lines, Rust): identity, sessions, padding,
  sealed envelopes formats 2 and 3, sealing prekeys, blind tokens on the
  client side, mesh tags, transport key, pickling.
- `comms-crypto/src/recovery.rs` (669 lines; added after Beta 8, so in no release yet): the recovery
  file, the identity saved by its owner under a generated 120-bit code
  (Argon2id, then ChaCha20-Poly1305 with the header as associated data).
- `comms-worker/src/tokens.ts` (180 lines) and `comms-worker/voprf/`: blind
  tokens on the relay side.

**Questions:**
1. **Format 3 envelopes.** Is the sealed box (ephemeral X25519 → HKDF-SHA256
   → ChaCha20-Poly1305 to the prekey) used correctly? Domain separation and
   nonce use are the main concerns.
2. **The HMAC sender claim** (key from X25519 between the sender's long-term
   sealing key and the recipient's prekey):
   - Does it authenticate the sender to the recipient, bound to this one
     envelope?
   - Is it deniable as claimed?
   - Can anyone but the sender produce one the recipient accepts?
3. **Downgrades.** Can a relay or a network attacker force format 2 or an old
   prekey? Once a peer has a prekey, the code refuses format 2 to them.
   Can a stale or future-dated prekey get through? See the `PREKEY_*`
   constants and `StalePrekey`, and on the app's side `LegacyEnvelope.kt`,
   which says when the app still sends format 2.
4. **Deleting prekeys.** Is the deletion schedule (`prune_sealing_prekeys`,
   `PREKEY_DRAIN_GRACE_MS`, `PREKEY_KEEP_MS`) sound? Can an envelope be lost
   before it can be read, or a key be kept longer than claimed?
5. **Olm integration.** Check:
   - session creation from one-time and fallback keys;
   - the session list per peer (`MAX_SESSIONS_PER_PEER`, the protected window,
     `SESSION_HARD_LIMIT`);
   - duplicate and replay handling;
   - recovery from lost sessions (`NoSessionFor`, `DecryptFrom`): can a
     sender force a victim to burn one-time keys or sessions?
6. **Padding** (`pad`, `unpad`, the v3 inner padding). What still leaks
   through sizes, and can malformed padding cause harm?
7. **Pickling and zeroisation.** Is secret material wiped as claimed? Can a
   wrong key or a damaged pickle be mistaken for a good one?
8. **Blind tokens** (RFC 9497 VOPRF, ristretto255-SHA512):
   - the blinding, finalisation and DLEQ proof check on both sides;
   - whether the phone's key pinning (one epoch ahead, the epoch checked
     against its own clock, never fetching keys right after a token request)
     stops a relay from tagging tokens per account;
   - double-spend protection.
9. **Mesh tags** (`mesh_tag`). Can anyone but the two contacts recognise or
   link a pair's bundles?
10. **The recovery file** (`recovery.rs`). Can a file that someone else made,
    or that was changed, be opened or partly restored? Does a restore really
    leave out sessions, one-time and fallback key secrets, and keep new key
    ids clear of ones the old phone handed out? Are the Argon2id parameters
    read from the file bounded, so a crafted file cannot exhaust the phone?

## Tier 2: the relay

**Files:**
- `comms-worker/src/index.ts` (1,856 lines)
- `mailbox.ts` (1,699)
- `federation.ts` (369)
- `comms-logic.ts` (592)
- `auth.ts` (60)
- `env.ts` (219)
- `invite-page.ts` (the page an invite link opens)

**Questions:**
1. **Request authentication** (`auth.ts`). Replay, canonicalisation of the
   signed string, nonce storage, clock skew. Check that the transport key
   reaches only `GET /v1/inbox`, `POST /v1/ack` and the socket.
2. **Registration.** One key under two spellings, keys that don't belong
   together (curve and sealing keys must be bound by the Ed25519 signature),
   abuse of invites and the enrollment secret.
3. **What the relay can learn, and what an outsider can learn from it:**
   - Can a lookup, send, payment, invite or federated request reveal that a
     number exists, who looked whom up, or who sent what?
   - The `alert` and `mix` fields are visible by design (a locked phone has to
     ring). Is anything else visible that shouldn't be?
4. **Federation.**
   - Check the signatures between relays and how the key is fetched from
     `GET /`.
   - Can a malicious relay impersonate another, replay across relays, or flood
     a mailbox past the known and stranger allowances?
   - Is the known-relay record (the day someone last wrote to each relay)
     safe to keep?
5. **Limits and denial of service.** Allowances per account, per mailbox, per
   relay and per recipient.
   - Finding 23 (one account could fill a mailbox) is answered by contacts'
     passes (`Mailbox.enqueue`, `passValid`; `PUT /v1/pass`). Can a stranger
     still crowd out contacts, forge or replay a pass, or learn from the
     strangers' limit that a number exists?
   - Is anything else cheap for an attacker and expensive for the relay?
6. **Storage.** Does the relay's storage hold anything the README's "What the
   relay keeps" does not list? Check timestamps, senders, lookups and
   last-seen.

## Tier 3: the app's protocol and storage

**Files:**
- `android/app/src/main/java/app/clairix/comms/CommsRepository.kt` (5,210 lines)
- `RelayClient.kt` (1,285)
- `CommsStore.kt` (1,888)
- `CommsWire.kt` (458)
- `KeyChangeGuard.kt` (89)
- `ContactGate.kt` (234): blocking and message requests
- `RecoveryFile.kt` (360) and `RecoveryRestorer.kt` (224): the app's half of
  the recovery file, and putting a restored identity back
- `IdentityStore.kt`, `Vault.kt`, `VaultBox.kt`, `KeystoreBox.kt`,
  `TransportKeyStore.kt`, `LockedInbox.kt`, `CommsDb.kt`, `SealedPrefs.kt`
- `TorTransport.kt` and `comms-crypto/src/tor.rs` (455)

**Questions:**
1. **Trust in contact keys.**
   - Only an in-person scan verifies a contact, and only a scan on the
     contact's own page replaces their keys. Can a link, an invite, a lookup or
     a message get around that?
   - Is `KeyChangeGuard` applied on every path that acts on a payload?
2. **Resolving the sender** (`resolveSender`). Can a payload make the app act
   on behalf of, or start a session with, someone it did not come from?
3. **Moves between relays** (`moveTo`, `applyMove`, `CommsStore.renumberContact`).
   - Can anyone but the contact move them?
   - Can an old notice, a notice naming another contact's number, or a notice
     from changed keys do harm?
   - Does the old-mailbox collection open anything up?
4. **The locked phone.**
   - Is anything that reads conversations reachable while the phone is locked?
   - Look at the transport key's reach, what the locked inbox keeps, and the
     re-wrapping of the data key as the lock screen changes.
5. **Storage at rest.** SQLCipher keying, the Keystore key properties, what
   is left in the clear (settings, token pools), and what survives an unpair
   or a wipe.
6. **Tor.**
   - Can another app on the phone use or watch the proxy?
   - Does anything bypass it while Tor is on? The design claims everything
     fails closed while Tor connects.
   - Is the onion fallback safe?
7. **Transport.** TLS configuration, the optional certificate pin, alternate
   addresses, and the anonymous send's separate connection and TLS session.
8. **Restoring a recovery file** (`RecoveryRestorer.kt`). Can a restore bring
   back trust it should not, such as a contact marked verified under keys the
   file did not pin, or leave the old phone's pass key in use?

## Tier 4: the rest, if there is time

- **On-device protection** (`android/app/src/main/java/app/clairix/security/`):
  - `AppLock.kt`: the verifier, attempt counting across process death, the
    wipe threshold.
  - `Wiper.kt`: completeness and order.
  - `DuressPhrase.kt`
  - `WipeLimit.kt` (the wipe threshold is applied only once the owner chose
    it) and `RelayFarewell.kt` (the relay account deletion a wipe asks for,
    signed before the identity key is destroyed).
  - What accessibility services can see behind the lock.
- **Card Vault and card emulation** (`src/suite`: `analysis/CardVault.kt`,
  `analysis/NfcScanner.kt`'s `capture`/`runIsoDep` path, `NfcVaultHost.kt`,
  `EmulationGuard.kt`; `src/fullSuite`: `CardEmulationService.kt`; in the full and foss editions, not the Play edition or the comms tier): the auth-bound Keystore key and what is stored at rest; that
  an ordinary scan records no APDU pairs and only the explicit re-tap does; that a
  payment card is never recorded or emulable; and that emulation disarms on lock,
  wipe, screen-off and timeout. See the "Card Vault and card emulation" section of
  [THREAT-MODEL.md](THREAT-MODEL.md) for what it is and is not.
- **The offline mesh** (`comms/mesh/`): what a nearby radio learns, flooding,
  and resource exhaustion.
- **Mixing in the relay** (`mailbox.ts`): what timing still reveals.
- **Groups**: who can change a roster, matched by key rather than number.
- **The supply chain** (`.github/workflows/release.yml`, `tools/signing/`,
  `tools/reproducible/`, `relays/`): asset signing, the signed relay list, and
  whether the reproducible-build check would catch a modified release.

## Out of scope

- The detection tools: Bluetooth and Wi-Fi tracker detection, IMSI-catcher
  heuristics, the camera sweep, spyware and stalkerware checks, GPS spoofing
  checks, and plate-reader maps. They are best-effort by design.
- Navigation, maps, traffic and incidents (`nav/`, `comms-worker/src/traffic*`,
  `incidents*`), except where they share the relay's authentication and tokens.
- The website (`website/`) and the older desktop tool (`src/` in the tagged
  releases; removed from the repository after 3.0.0 Beta 8).
- ClairixCoin's economics. The payment protocol's idempotency and refunds are
  in scope as part of the relay; finding 25 (coin farming) is known.
- vodozemac itself, Arti, SQLCipher and Android. Report misuse of them, not
  their internals.
- Anything listed under "What Clairix does not claim" in
  [THREAT-MODEL.md](THREAT-MODEL.md).

## Already known

Everything in [`../SECURITY-FINDINGS.md`](../SECURITY-FINDINGS.md), fixed or
open. A finding that one of those fixes is wrong or incomplete is very welcome.
