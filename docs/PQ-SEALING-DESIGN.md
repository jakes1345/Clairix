# Post-quantum sealing: a proposal for envelope format 4

**Status: proposal. Nothing here is implemented.** It answers row 1 of
`THREAT-MODEL-GAPS-2026-11.md` (harvest now, decrypt later). Sizes below are
from FIPS 203; nothing has been built or benchmarked on a phone yet.

## The threat

The adversary in the README is a state that can compel the relay's host and
watch the network. It does not need to read anything today. It can keep every
sealed envelope the relay holds or the wire carries, and open them years later
if it ever has a computer that breaks elliptic-curve Diffie-Hellman (Shor's
algorithm). Everything Clairix uses for key agreement is X25519 or Curve25519:
the seal, the Olm session inside it, the call's DTLS keys and Tor's onion
handshake. Longer random keys do not help: the weakness is the algorithm, not
the randomness.

## What each layer gives a recorder

| Layer | Key agreement | Recorded today, readable by a future quantum computer? |
|---|---|---|
| Relay TLS | Whatever the phone's TLS stack negotiates (X25519 unless it offers a hybrid group) | Possibly; the TLS layer holds only sealed envelopes, so the seal below still stands |
| Seal (format 3) | Ephemeral X25519 to the recipient's prekey | **Yes.** This is the gap |
| Olm session inside the seal (vodozemac) | Curve25519 double ratchet | Yes, but only reachable after the seal is opened |
| Call media (WebRTC DTLS-SRTP) | Classical | Yes, if the media path was recorded; the signalling is inside envelopes |
| Tor to the relay | Classical | Yes (Tor is not post-quantum today) |
| Recovery file | Argon2id then ChaCha20-Poly1305, symmetric | No: a 256-bit key keeps a safe margin against Grover's search |
| Identity signatures (Ed25519) | n/a | Not a recording problem; a quantum computer could forge them later, not read old traffic |

## Why one hybrid seal is enough for recorded envelopes

An envelope travels only as a sealed box: the relay, the Bluetooth mesh and
anyone on the wire see nothing else. The Olm ciphertext, the sender's identity
keys and the sent-at time are all inside the seal. If the seal needs **both**
X25519 and ML-KEM-768 to open, a recorder with only a quantum computer cannot
open it, so it never reaches the Olm layer. vodozemac has no post-quantum
variant, and this avoids replacing it.

This protects recorded envelopes. It does not protect call media or Tor
traffic (below), and it does not protect an Olm ciphertext that leaks some
other way (an unsealed copy on a seized phone is already plaintext).

## Construction

Format 4 replaces the single X25519 in format 3 with a hybrid KEM, still sealed
the way `seal` works now (a fresh ephemeral per envelope, a zero nonce because
the key is single-use).

1. The recipient publishes, inside the signed sealing prekey, an X25519 public
   key **and** an ML-KEM-768 encapsulation key (1184 bytes).
2. The sender picks an ephemeral X25519 key, encapsulates to the ML-KEM key
   (1088-byte ciphertext, 32-byte secret), and derives the box key from both
   shared secrets plus every public value in the exchange, so neither half can
   be swapped:
   `key = HKDF-SHA256(salt = eph_x25519_pub || mlkem_ct || recipient_x25519_pub || SHA256(recipient_mlkem_ek), ikm = x25519_ss || mlkem_ss, info = "clairix-comms-seal-v4")`.
3. Encrypt the padded inner with ChaCha20-Poly1305 as in format 3.

Prefer a published combiner to this hand-rolled one. **X-Wing** (X25519 plus
ML-KEM-768, an IETF CFRG draft) is the closest fit; check its current status
before adopting, and test against its published vectors if used.

## What it costs

- Envelope: +1088 bytes, constant, so short messages stay in one size bucket
  (`short_payloads_are_indistinguishable_on_the_wire` must be updated to the new
  size). The relay's cap is 72 KiB, so there is room.
- Prekey: +1184 bytes in the bundle, republished weekly. The relay's key
  endpoints (`comms-worker/src`) must accept and size-check the new field, and
  the signature input must cover it with a new domain-separation string.
- Cost: ML-KEM operations are cheap next to the Argon2id and Tor work the app
  already does. Measure on a low-end phone before shipping.
- Dependencies: RustCrypto `ml-kem` (0.3.x on crates.io) or Cryspen's
  formally verified `libcrux-ml-kem`. Check each against the repo's rules:
  `cargo-audit`, reproducible builds (the app is built twice and compared) and
  `THIRD-PARTY-NOTICES.md`.

## Downgrade and rollout

- **A relay must not be able to strip the PQ key.** It sits inside the signed
  prekey, so a changed prekey fails the signature. Also add a signed
  "has published a PQ prekey" flag to the identity bundle; once a client has
  seen it for a contact, it never seals that contact in format 3.
- **Mixed fleets.** Seal in format 4 only to peers who publish a PQ prekey. The
  sizes differ, so for a while the relay can tell format 3 from 4 apart; the
  existing cutoff mechanism (format 2 is refused past a date) is the way to end
  that window.
- **Order of work:** crypto crate with known-answer tests, then the relay's
  key endpoints, then the Kotlin types (UniFFI `SealingPrekey` and `PeerKeys`),
  then a release that publishes PQ prekeys but still accepts format 3, then a
  cutoff.

## Tests to write with it

- NIST known-answer vectors for ML-KEM, and the combiner's published vectors.
- A downgrade test: a bundle whose PQ key was stripped is refused for a contact
  who had one.
- The existing no-plaintext test, run on format 4 envelopes too.
- Size uniformity across message lengths.

## Not covered, and separate work

- **Calls.** DTLS-SRTP key agreement is classical. A recorded call's media
  stays readable to a future quantum computer unless call keys are derived from
  something sent inside a (now PQ) envelope instead.
- **Tor.** Not post-quantum today; nothing to do here except track it.
- **Blind send tokens (VOPRF, ristretto255)** and **Ed25519 signatures:** a
  quantum computer could forge these later. That harms the future, not recorded
  traffic. ML-DSA is the follow-up when it matters.
- **Glacier comparison.** `COMPETITOR-GLACIER-2026-11.md` notes that a
  competitor calls its VPN "quantum-safe" because it feeds quantum-generated
  randomness into Curve25519. That does not change the algorithm. A hybrid KEM
  does.

## Decision needed

Whether to build this before or after the next release, and which ML-KEM
implementation to depend on.
