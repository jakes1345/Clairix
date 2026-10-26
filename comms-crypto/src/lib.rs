//! End-to-end encryption for Clairix comms.
//!
//! Two layers, both standard constructions:
//!
//! 1. **Olm double ratchet** (vodozemac, the audited Rust implementation used by
//!    Matrix), in its version-2 session format, which authenticates every
//!    message with a full-length HMAC rather than libolm's truncated one. Both
//!    ends are Clairix, so libolm interoperability is not needed. A long-term identity (Ed25519 signing key + Curve25519 identity
//!    key), signed one-time and fallback keys, and per-peer sessions that ratchet
//!    forward with every message. This gives forward secrecy and post-compromise
//!    security for message content.
//!
//! 2. **Sealed envelopes**: every Olm message is wrapped for the relay in an
//!    anonymous box to the recipient's sealing key (ephemeral X25519 → HKDF-SHA256
//!    → ChaCha20-Poly1305, the `crypto_box_seal` construction). The relay stores
//!    and forwards envelopes it cannot open, and never learns who sent one.
//!
//! Inside the box, the sender names itself (its Curve25519 and Ed25519 keys) and
//! signs that claim with its Ed25519 key, bound to this one envelope (its
//! ephemeral key, the recipient's sealing key and a hash of the Olm ciphertext).
//! The claim matters only when Olm cannot authenticate the message itself, which
//! is exactly when the app is asked to act on it: a message on a session this
//! device lost. An unsigned or forged claim is refused before it can make the
//! app start a session with, or ask anything of, the named contact.
//!
//! Every plaintext is padded to a fixed bucket before Olm sees it (multiples of
//! 160 bytes for small payloads, a few large buckets for media), so receipts,
//! call signals and short texts all look alike on the wire.
//!
//! Everything a device owns (account, sealing secret, sessions) is kept in one
//! [`Identity`], serialised by [`Identity::pickle`] under a 32-byte key the app
//! keeps in the Android Keystore. Secret key material is wiped from memory when
//! it is dropped. Nothing here touches the network or disk.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::{STANDARD as B64, URL_SAFE_NO_PAD as B64URL};
use base64::Engine;
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256, Sha512};
use vodozemac::olm::{
    Account, AccountPickle, DecryptionError, OlmMessage, Session, SessionConfig, SessionCreationError, SessionPickle,
};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};
use x25519_dalek::{PublicKey as XPublicKey, StaticSecret};
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

uniffi::setup_scaffolding!();

// ── Errors ─────────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CryptoError {
    #[error("bad key: {reason}")]
    BadKey { reason: String },
    /// A signature did not verify. For an envelope this means its sender claim
    /// is unsigned, signed by the wrong key, or bound to a different envelope;
    /// the claimed sender must not be acted on.
    #[error("bad signature")]
    BadSignature,
    #[error("no session with this peer")]
    NoSession,
    /// A normal (post-pre-key) message arrived, signed by `sender_ed25519` for
    /// `sender_curve25519`, but no session here can read it: the app should ask
    /// that peer to start a new one once it has checked the keys are a contact's.
    #[error("no session can read a message from {sender_curve25519}")]
    NoSessionFor { sender_curve25519: String, sender_ed25519: String, sent_at_ms: i64 },
    /// A message signed by `sender_ed25519` for `sender_curve25519` opened, but
    /// no session could decrypt it and it could not start a new one (for example,
    /// it was built on a one-time key this device no longer holds). The app
    /// should ask that peer for a fresh session rather than keep failing on
    /// every message after it.
    #[error("could not decrypt a message from {sender_curve25519}: {reason}")]
    DecryptFrom { sender_curve25519: String, sender_ed25519: String, sent_at_ms: i64, reason: String },
    /// A message this device has already decrypted (its key is spent): a
    /// redelivery or a replay. Nothing is wrong with the session; drop it.
    #[error("message already received")]
    Duplicate,
    /// This device already holds the most sessions it keeps with
    /// `peer_curve25519`, every one of them used in the last 14 days, so no
    /// new outbound session is started (none of those is given up for one
    /// this device was asked to start). The app tells the owner.
    #[error("too many sessions in recent use with {peer_curve25519}")]
    SessionLimit { peer_curve25519: String },
    /// `peer_curve25519` has published a sealing prekey, but the one given is
    /// missing or too old to seal to. Once a peer is seen with a prekey, an
    /// envelope is never sealed to them the old way again: that would be a
    /// downgrade anyone serving their keys could force. The app fetches the
    /// peer's keys again and retries.
    #[error("the sealing prekey of {peer_curve25519} is missing or out of date")]
    StalePrekey { peer_curve25519: String },
    #[error("could not decrypt: {reason}")]
    Decrypt { reason: String },
    #[error("could not encrypt: {reason}")]
    Encrypt { reason: String },
    #[error("bad pickle: {reason}")]
    BadPickle { reason: String },
    #[error("malformed envelope: {reason}")]
    Envelope { reason: String },
}

type Result<T> = std::result::Result<T, CryptoError>;

// ── Public records ─────────────────────────────────────────────────────────

/// One published one-time (or fallback) key: the Curve25519 key and the owner's
/// Ed25519 signature over it, so a relay cannot substitute its own.
#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct SignedKey {
    pub id: String,
    pub key: String,
    pub signature: String,
}

/// A signed sealing prekey: the X25519 key envelopes to a device are sealed to
/// (format 3). The owner replaces it every week and deletes its secret once
/// nothing can still arrive for it, after which envelopes logged at the relay
/// can no longer be opened, and who sent each one stays unknown.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct SealingPrekey {
    /// X25519 public key, base64.
    pub key: String,
    /// When the owner made it, ms since the epoch, by the owner's clock.
    pub created_ms: i64,
    /// The owner's Ed25519 signature over [`prekey_statement`], base64.
    pub signature: String,
}

/// Everything another device needs to start a session with this one.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PublicBundle {
    /// Long-term signing key, base64. This is the identity people verify.
    pub ed25519: String,
    /// Long-term Curve25519 identity key, base64 (Olm).
    pub curve25519: String,
    /// X25519 sealing key, base64: envelopes to this device are boxed to it.
    pub sealing: String,
    /// Signature over `curve25519 || sealing` by `ed25519`, base64.
    pub signature: String,
    /// The current sealing prekey; envelopes to this device are sealed to it.
    pub sealing_prekey: Option<SealingPrekey>,
    /// Fallback key, used when the relay has run out of one-time keys.
    pub fallback: Option<SignedKey>,
    /// One-time keys generated and not yet marked published.
    pub one_time_keys: Vec<SignedKey>,
}

/// The public keys of a peer, as fetched from the relay or read from a QR code.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PeerKeys {
    pub ed25519: String,
    pub curve25519: String,
    pub sealing: String,
    pub signature: String,
    /// Their current sealing prekey, when they publish one (apps from before
    /// envelope format 3 do not). Envelopes to them are sealed to it.
    #[uniffi(default = None)]
    pub sealing_prekey: Option<SealingPrekey>,
}

/// The result of opening an envelope.
#[derive(Clone, Debug, uniffi::Record)]
pub struct Decrypted {
    /// The sender's Curve25519 identity key, base64. The app maps it to a contact.
    pub sender_curve25519: String,
    /// The Ed25519 key that signed the envelope's sender claim, base64. The app
    /// checks it is the contact's identity key before trusting the content.
    pub sender_ed25519: String,
    /// When the sender says it sealed the envelope (ms since the epoch); signed.
    pub sent_at_ms: i64,
    /// The plaintext, padding removed.
    pub plaintext: Vec<u8>,
    /// True when this message created a new Olm session (a pre-key message).
    pub new_session: bool,
    /// True when, to keep that new session, the least recently used of the
    /// sender's sessions had to go even though it was used in the last 14
    /// days. Only the sender can cause this, by starting that many sessions;
    /// the app tells the owner.
    pub evicted_recent_session: bool,
    /// True when the envelope was sealed to one of this device's sealing
    /// prekeys (format 3): the sender's app knows them. When this device
    /// holds no prekey of the sender's, it should fetch their keys.
    pub sealed_to_prekey: bool,
}

// ── Padding ────────────────────────────────────────────────────────────────

/// Small payloads are padded to a multiple of this (Signal's choice).
pub const PAD_BLOCK: usize = 160;
/// Up to this many bytes (padding marker included) the 160-byte steps apply.
const PAD_SMALL_MAX: usize = PAD_BLOCK * 16;
/// Past that, the next of these: a media chunk (30 KiB of base64 in JSON) always
/// lands in the same one, as does every call offer of an ordinary size.
const PAD_LARGE_BUCKETS: [usize; 6] = [4096, 8192, 16_384, 32_768, 49_152, 65_536];
/// Anything bigger still: a multiple of this. Nothing the app sends is this big.
const PAD_HUGE_STEP: usize = 65_536;
/// The padding marker: the plaintext, then 0x80, then zeros up to the bucket.
const PAD_MARKER: u8 = 0x80;

/// The padded length of a plaintext of `len` bytes: the smallest bucket that
/// holds it and the one-byte marker.
pub fn padded_len(len: usize) -> usize {
    let need = len + 1;
    if need <= PAD_SMALL_MAX {
        need.div_ceil(PAD_BLOCK) * PAD_BLOCK
    } else if let Some(bucket) = PAD_LARGE_BUCKETS.iter().copied().find(|&b| b >= need) {
        bucket
    } else {
        need.div_ceil(PAD_HUGE_STEP) * PAD_HUGE_STEP
    }
}

/// ISO/IEC 7816-4 padding to the bucket [`padded_len`] gives.
fn pad(plaintext: &[u8]) -> Zeroizing<Vec<u8>> {
    let len = padded_len(plaintext.len());
    let mut out = Zeroizing::new(Vec::with_capacity(len));
    out.extend_from_slice(plaintext);
    out.push(PAD_MARKER);
    out.resize(len, 0);
    out
}

/// Removes [`pad`]'s padding. A length that is not a bucket, or padding that is
/// not a marker followed by zeros, is refused: the sender is not this code.
fn unpad(padded: &[u8]) -> Result<&[u8]> {
    if padded.is_empty() || padded_len(padded.len() - 1) != padded.len() {
        return Err(CryptoError::Decrypt { reason: format!("plaintext of {} bytes is not padded", padded.len()) });
    }
    let end = padded
        .iter()
        .rposition(|&b| b != 0)
        .ok_or_else(|| CryptoError::Decrypt { reason: "padding without a marker".into() })?;
    if padded[end] != PAD_MARKER {
        return Err(CryptoError::Decrypt { reason: "padding without a marker".into() });
    }
    Ok(&padded[..end])
}

// ── Sealed envelope ────────────────────────────────────────────────────────

/// Envelope format 2: sealed to the recipient's long-term sealing key, with a
/// sender claim signed by the sender's Ed25519 key. Still opened, so messages
/// from apps that predate format 3 arrive; sent only to peers that publish no
/// sealing prekey.
const ENVELOPE_V2: u8 = 2;
const SEAL_INFO_V2: &[u8] = b"clairix-comms-seal-v2";
/// Domain separation for the format-2 sender signature.
const SENDER_SIG_CONTEXT: &[u8] = b"clairix-comms-sender-v2";

/// Envelope format 3: sealed to the recipient's current sealing prekey, which
/// rotates, with a sender claim authenticated by a MAC both ends can compute.
///
/// * Rotating the key the box is sealed to gives the sender's identity forward
///   secrecy: once the recipient deletes a prekey's secret, envelopes logged at
///   the relay can no longer be opened, by anyone who later seizes the phone
///   included. In format 2 the one long-term key opened every envelope ever
///   sent to it.
/// * A signature proves to anyone who holds the envelope and the recipient's
///   key that the sender made it. The MAC is keyed from X25519 between the
///   sender's long-term sealing key and the recipient's prekey, which both of
///   them hold: it convinces the recipient, who knows they did not make it, and
///   proves nothing to anyone else, as Olm's own MACs do not.
/// * The inside is padded to a multiple of [`INNER_V3_STEP`], so a message that
///   starts a session (larger) and one that continues it look alike for
///   anything up to about 480 bytes of text, and seldom differ beyond.
const ENVELOPE_V3: u8 = 3;
const SEAL_INFO_V3: &[u8] = b"clairix-comms-seal-v3";
const CLAIM_KEY_INFO: &[u8] = b"clairix-comms-claim-v3";
const CLAIM_MAC_CONTEXT: &[u8] = b"clairix-comms-sender-v3";
/// What a sealing prekey's signature covers: this, the key and its time.
const PREKEY_CONTEXT: &str = "clairix-sealing-prekey-v1";

/// A device makes a new sealing prekey once its current one is this old.
const PREKEY_ROTATE_MS: u64 = 7 * 24 * 60 * 60 * 1000;
/// Senders do not seal to a prekey older than this (by their clock), so no
/// envelope is sealed to one long after its owner replaced it.
const PREKEY_MAX_AGE_MS: u64 = 14 * 24 * 60 * 60 * 1000;
/// How far in the future a prekey's time may be, for clocks that disagree.
const PREKEY_FUTURE_SKEW_MS: u64 = 24 * 60 * 60 * 1000;
/// After the last moment a sender may seal to a prekey, envelopes for it can
/// still be on their way this long: carried phone to phone (48 hours at most)
/// or held up by a sender's clock running behind.
const PREKEY_DRAIN_GRACE_MS: u64 = 3 * 24 * 60 * 60 * 1000;
/// A replaced prekey is deleted this long after it was replaced at the latest:
/// the relay keeps an envelope no longer than this.
const PREKEY_KEEP_MS: u64 = 30 * 24 * 60 * 60 * 1000;
/// The most prekeys kept; with the times above there are never more than six.
const MAX_PREKEYS: usize = 8;

/// The text a sealing prekey's signature covers.
fn prekey_statement(key_b64: &str, created_ms: i64) -> String {
    format!("{PREKEY_CONTEXT}|{key_b64}|{created_ms}")
}

/// What sits inside a format-2 sealed box, in this order:
///
/// | bytes    | field                                              |
/// |----------|----------------------------------------------------|
/// | 0        | Olm message type: 0 pre-key, 1 normal              |
/// | 1..33    | sender's Curve25519 identity key                   |
/// | 33..65   | sender's Ed25519 identity key                      |
/// | 65..73   | sent-at, ms since the epoch, big-endian            |
/// | 73..137  | Ed25519 signature over [`sender_claim`]            |
/// | 137..    | Olm ciphertext                                     |
const INNER_HEADER: usize = 1 + 32 + 32 + 8 + 64;

/// What sits inside a format-3 sealed box, in this order:
///
/// | bytes    | field                                                        |
/// |----------|--------------------------------------------------------------|
/// | 0        | Olm message type: 0 pre-key, 1 normal                        |
/// | 1..33    | sender's Curve25519 identity key                             |
/// | 33..65   | sender's Ed25519 identity key                                |
/// | 65..97   | sender's long-term sealing key                               |
/// | 97..161  | sender's signature binding the two keys above (its bundle's) |
/// | 161..169 | sent-at, ms since the epoch, big-endian                      |
/// | 169..201 | HMAC-SHA256 over [`claim_mac_input`]                         |
/// | 201..205 | Olm ciphertext length, big-endian                            |
/// | 205..    | Olm ciphertext, then zeros to a multiple of [`INNER_V3_STEP`] |
///
/// The binding signature is the one the sender publishes in its bundle, the
/// same in every envelope: it ties the sealing key to the identity, and says
/// nothing about this envelope.
const INNER_V3_HEADER: usize = 1 + 32 + 32 + 32 + 64 + 8 + 32 + 4;
const INNER_V3_STEP: usize = 1024;

/// The bytes the sender signs: its claim, bound to this one envelope by the
/// ephemeral key, the recipient's sealing key and a hash of the ciphertext.
/// A signed claim cannot be lifted onto another envelope, sent to another
/// recipient, or kept while the ciphertext is swapped.
fn sender_claim(
    eph_pub: &[u8; 32],
    recipient_sealing: &[u8; 32],
    message_type: u8,
    sender_curve: &[u8; 32],
    sent_at_ms: u64,
    ciphertext: &[u8],
) -> Vec<u8> {
    let mut out = Vec::with_capacity(SENDER_SIG_CONTEXT.len() + 32 + 32 + 1 + 32 + 8 + 32);
    out.extend_from_slice(SENDER_SIG_CONTEXT);
    out.extend_from_slice(eph_pub);
    out.extend_from_slice(recipient_sealing);
    out.push(message_type);
    out.extend_from_slice(sender_curve);
    out.extend_from_slice(&sent_at_ms.to_be_bytes());
    out.extend_from_slice(&Sha256::digest(ciphertext));
    out
}

/// The format-3 claim key: HKDF-SHA256 over X25519 between the sender's
/// long-term sealing key and the recipient's prekey, salted with this
/// envelope's ephemeral key and that prekey, so each envelope has its own.
fn claim_key(shared: &[u8], eph_pub: &[u8; 32], prekey: &[u8; 32]) -> Zeroizing<[u8; 32]> {
    let mut salt = Vec::with_capacity(64);
    salt.extend_from_slice(eph_pub);
    salt.extend_from_slice(prekey);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let mut key = Zeroizing::new([0u8; 32]);
    hk.expand(CLAIM_KEY_INFO, key.as_mut()).expect("32 bytes is a valid HKDF output length");
    key
}

/// What the format-3 claim MAC covers: everything the inside says about the
/// sender, and a hash of the ciphertext.
fn claim_mac_input(
    message_type: u8,
    sender_curve: &[u8; 32],
    sender_ed: &[u8; 32],
    sender_sealing: &[u8; 32],
    sent_at_ms: u64,
    ciphertext: &[u8],
) -> Vec<u8> {
    let mut out = Vec::with_capacity(CLAIM_MAC_CONTEXT.len() + 1 + 96 + 8 + 32);
    out.extend_from_slice(CLAIM_MAC_CONTEXT);
    out.push(message_type);
    out.extend_from_slice(sender_curve);
    out.extend_from_slice(sender_ed);
    out.extend_from_slice(sender_sealing);
    out.extend_from_slice(&sent_at_ms.to_be_bytes());
    out.extend_from_slice(&Sha256::digest(ciphertext));
    out
}

fn claim_mac(key: &[u8; 32]) -> Hmac<Sha256> {
    <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC takes a key of any length")
}

/// The parsed inner layout of an opened format-2 envelope; see [`INNER_HEADER`].
struct InnerView<'a> {
    message_type: u8,
    sender_curve: [u8; 32],
    sender_ed: [u8; 32],
    sent_at_ms: u64,
    signature: [u8; 64],
    ciphertext: &'a [u8],
}

fn parse_inner(bytes: &[u8]) -> Result<InnerView<'_>> {
    if bytes.len() <= INNER_HEADER {
        return Err(CryptoError::Envelope { reason: "inner message too short".into() });
    }
    let mut sender_curve = [0u8; 32];
    sender_curve.copy_from_slice(&bytes[1..33]);
    let mut sender_ed = [0u8; 32];
    sender_ed.copy_from_slice(&bytes[33..65]);
    let mut ts = [0u8; 8];
    ts.copy_from_slice(&bytes[65..73]);
    let mut signature = [0u8; 64];
    signature.copy_from_slice(&bytes[73..137]);
    Ok(InnerView {
        message_type: bytes[0],
        sender_curve,
        sender_ed,
        sent_at_ms: u64::from_be_bytes(ts),
        signature,
        ciphertext: &bytes[INNER_HEADER..],
    })
}

/// The parsed inner layout of an opened format-3 envelope; see [`INNER_V3_HEADER`].
struct InnerV3View<'a> {
    message_type: u8,
    sender_curve: [u8; 32],
    sender_ed: [u8; 32],
    sender_sealing: [u8; 32],
    binding: [u8; 64],
    sent_at_ms: u64,
    mac: [u8; 32],
    ciphertext: &'a [u8],
}

fn parse_inner_v3(bytes: &[u8]) -> Result<InnerV3View<'_>> {
    let bad = |reason: &str| CryptoError::Envelope { reason: reason.into() };
    if bytes.len() <= INNER_V3_HEADER || bytes.len() % INNER_V3_STEP != 0 {
        return Err(bad("inner message has the wrong length"));
    }
    let take = |from: usize| -> [u8; 32] {
        let mut out = [0u8; 32];
        out.copy_from_slice(&bytes[from..from + 32]);
        out
    };
    let mut binding = [0u8; 64];
    binding.copy_from_slice(&bytes[97..161]);
    let mut ts = [0u8; 8];
    ts.copy_from_slice(&bytes[161..169]);
    let mut len = [0u8; 4];
    len.copy_from_slice(&bytes[201..205]);
    let len = u32::from_be_bytes(len) as usize;
    let end = INNER_V3_HEADER.checked_add(len).filter(|&e| len > 0 && e <= bytes.len()).ok_or_else(|| bad("bad ciphertext length"))?;
    // Padding that is not all zeros was not made by this code.
    if bytes[end..].iter().any(|&b| b != 0) {
        return Err(bad("bad inner padding"));
    }
    Ok(InnerV3View {
        message_type: bytes[0],
        sender_curve: take(1),
        sender_ed: take(33),
        sender_sealing: take(65),
        binding,
        sent_at_ms: u64::from_be_bytes(ts),
        mac: take(169),
        ciphertext: &bytes[INNER_V3_HEADER..end],
    })
}

/// `crypto_box_seal`-style anonymous box: ephemeral X25519 with the recipient's
/// key, HKDF over the shared secret bound to both public keys, then
/// ChaCha20-Poly1305 with a zero nonce (the key is single-use). `inner` is
/// handed the ephemeral public key so the sender can bind its claim to it.
fn seal(
    version: u8,
    info: &[u8],
    recipient: &XPublicKey,
    inner: impl FnOnce(&[u8; 32]) -> Result<Zeroizing<Vec<u8>>>,
) -> Result<Vec<u8>> {
    let eph = StaticSecret::random_from_rng(&mut rand::thread_rng());
    let eph_pub = XPublicKey::from(&eph);
    let shared = eph.diffie_hellman(recipient);
    // A low-order recipient key makes the shared secret one anybody can compute
    // from public values alone, the relay included.
    if !shared.was_contributory() {
        return Err(CryptoError::BadKey { reason: "sealing key is of low order".into() });
    }
    let plaintext = inner(eph_pub.as_bytes())?;
    let key = seal_key(info, shared.as_bytes(), eph_pub.as_bytes(), recipient.as_bytes());
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
    let ct = cipher
        .encrypt(Nonce::from_slice(&[0u8; 12]), plaintext.as_slice())
        .expect("ChaCha20-Poly1305 encryption cannot fail on in-memory data");
    let mut out = Vec::with_capacity(1 + 32 + ct.len());
    out.push(version);
    out.extend_from_slice(eph_pub.as_bytes());
    out.extend_from_slice(&ct);
    Ok(out)
}

/// Opens a sealed box (its version byte already checked by the caller);
/// returns the ephemeral public key with the plaintext.
fn unseal(secret: &StaticSecret, info: &[u8], envelope: &[u8]) -> Result<([u8; 32], Zeroizing<Vec<u8>>)> {
    if envelope.len() < 1 + 32 + 16 {
        return Err(CryptoError::Envelope { reason: "too short".into() });
    }
    let mut eph_bytes = [0u8; 32];
    eph_bytes.copy_from_slice(&envelope[1..33]);
    let eph_pub = XPublicKey::from(eph_bytes);
    let me = XPublicKey::from(secret);
    let shared = secret.diffie_hellman(&eph_pub);
    // A low-order ephemeral key (a sender's, or a relay's forgery) gives a shared
    // secret anyone can compute: nothing sealed under it is private.
    if !shared.was_contributory() {
        return Err(CryptoError::Envelope { reason: "low-order ephemeral key".into() });
    }
    let key = seal_key(info, shared.as_bytes(), eph_pub.as_bytes(), me.as_bytes());
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
    let plain = cipher
        .decrypt(Nonce::from_slice(&[0u8; 12]), &envelope[33..])
        .map_err(|_| CryptoError::Envelope { reason: "not addressed to this device, or tampered".into() })?;
    Ok((eph_bytes, Zeroizing::new(plain)))
}

/// An opened envelope's claim, checked, and its Olm ciphertext.
struct Opened {
    message_type: u8,
    sender_identity: Curve25519PublicKey,
    sender_ed: Ed25519PublicKey,
    sent_at_ms: u64,
    ciphertext: Vec<u8>,
    sealed_to_prekey: bool,
}

/// Opens a format-2 envelope with the long-term sealing secret and checks its
/// signed claim.
fn open_v2(sealing_secret: &StaticSecret, envelope: &[u8]) -> Result<Opened> {
    let (eph_pub, inner_bytes) = unseal(sealing_secret, SEAL_INFO_V2, envelope)?;
    let view = parse_inner(&inner_bytes)?;
    let my_sealing = XPublicKey::from(sealing_secret);
    if !canonical_x25519(&view.sender_curve) {
        return Err(CryptoError::Envelope { reason: "sender key is not in canonical form".into() });
    }
    let sender_identity =
        Curve25519PublicKey::from_slice(&view.sender_curve).map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;
    let sender_ed = Ed25519PublicKey::from_slice(&view.sender_ed).map_err(|_| CryptoError::BadSignature)?;
    let signature = Ed25519Signature::from_slice(&view.signature).map_err(|_| CryptoError::BadSignature)?;
    let claim = sender_claim(&eph_pub, my_sealing.as_bytes(), view.message_type, &view.sender_curve, view.sent_at_ms, view.ciphertext);
    sender_ed.verify(&claim, &signature).map_err(|_| CryptoError::BadSignature)?;
    Ok(Opened {
        message_type: view.message_type,
        sender_identity,
        sender_ed,
        sent_at_ms: view.sent_at_ms,
        ciphertext: view.ciphertext.to_vec(),
        sealed_to_prekey: false,
    })
}

/// Opens a format-3 envelope with whichever of this device's prekeys it was
/// sealed to, and checks the claim's binding signature and MAC.
fn open_v3(prekeys: &[PrekeyEntry], envelope: &[u8]) -> Result<Opened> {
    let (prekey, eph_pub, inner_bytes) = prekeys
        .iter()
        .find_map(|p| unseal(&p.secret, SEAL_INFO_V3, envelope).ok().map(|(eph, inner)| (p, eph, inner)))
        .ok_or_else(|| CryptoError::Envelope { reason: "not addressed to this device, or tampered".into() })?;
    let view = parse_inner_v3(&inner_bytes)?;
    if !canonical_x25519(&view.sender_curve) || !canonical_x25519(&view.sender_sealing) {
        return Err(CryptoError::Envelope { reason: "sender key is not in canonical form".into() });
    }
    let sender_identity =
        Curve25519PublicKey::from_slice(&view.sender_curve).map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;
    let sender_ed = Ed25519PublicKey::from_slice(&view.sender_ed).map_err(|_| CryptoError::BadSignature)?;
    // The sealing key is the sender's: the same statement its bundle signs.
    let binding = Ed25519Signature::from_slice(&view.binding).map_err(|_| CryptoError::BadSignature)?;
    let statement = format!("{}|{}", sender_identity.to_base64(), B64.encode(view.sender_sealing));
    sender_ed.verify(statement.as_bytes(), &binding).map_err(|_| CryptoError::BadSignature)?;
    // And whoever holds it made this envelope (or this device did, which it knows it did not).
    let shared = prekey.secret.diffie_hellman(&XPublicKey::from(view.sender_sealing));
    if !shared.was_contributory() {
        return Err(CryptoError::BadSignature);
    }
    let my_prekey = XPublicKey::from(&prekey.secret);
    let key = claim_key(shared.as_bytes(), &eph_pub, my_prekey.as_bytes());
    let mut mac = claim_mac(&key);
    mac.update(&claim_mac_input(
        view.message_type,
        &view.sender_curve,
        &view.sender_ed,
        &view.sender_sealing,
        view.sent_at_ms,
        view.ciphertext,
    ));
    mac.verify_slice(&view.mac).map_err(|_| CryptoError::BadSignature)?;
    Ok(Opened {
        message_type: view.message_type,
        sender_identity,
        sender_ed,
        sent_at_ms: view.sent_at_ms,
        ciphertext: view.ciphertext.to_vec(),
        sealed_to_prekey: true,
    })
}

/// Checks a peer's sealing prekey: signed by their identity key, and neither
/// too old to seal to nor made too far in the future. Returns the key.
fn checked_prekey(peer_ed: &Ed25519PublicKey, peer_curve: &str, prekey: &SealingPrekey, now: u64) -> Result<XPublicKey> {
    let key = x25519_from(&prekey.key)?;
    verify_signature(peer_ed, prekey_statement(&prekey.key, prekey.created_ms).as_bytes(), &prekey.signature)?;
    let created = u64::try_from(prekey.created_ms).map_err(|_| CryptoError::BadKey { reason: "prekey time before 1970".into() })?;
    if created > now.saturating_add(PREKEY_FUTURE_SKEW_MS) || now.saturating_sub(created) > PREKEY_MAX_AGE_MS {
        return Err(CryptoError::StalePrekey { peer_curve25519: peer_curve.to_string() });
    }
    Ok(key)
}

/// A writer that only counts what it is given: the size of a serialisation,
/// without a copy of it.
struct ByteCounter(usize);

impl std::io::Write for ByteCounter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0 += buf.len();
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// HKDF info prefix for the mesh routing tag; the day follows it.
const MESH_TAG_INFO: &[u8] = b"clairix-mesh-tag-v2";
const MESH_TAG_LEN: usize = 16;

fn seal_key(info: &[u8], shared: &[u8], eph_pub: &[u8], recipient_pub: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut salt = Vec::with_capacity(64);
    salt.extend_from_slice(eph_pub);
    salt.extend_from_slice(recipient_pub);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let mut key = Zeroizing::new([0u8; 32]);
    hk.expand(info, key.as_mut()).expect("32 bytes is a valid HKDF output length");
    key
}

// ── Identity ───────────────────────────────────────────────────────────────

/// 32 secret bytes that are wiped when dropped (the sealing secret in a pickle).
#[derive(Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(transparent)]
struct SecretBytes([u8; 32]);

/// One Olm session as pickled, with when it last decrypted something.
#[derive(Serialize, Deserialize)]
struct SessionRecord {
    p: SessionPickle,
    /// Ms since the epoch of the last successful decryption; absent if never.
    #[serde(default)]
    used: Option<u64>,
}

/// One sealing prekey as pickled: its secret, when it was made, and when a
/// newer one replaced it.
#[derive(Serialize, Deserialize)]
struct PrekeyRecord {
    s: SecretBytes,
    c: u64,
    #[serde(default)]
    x: Option<u64>,
}

/// Serialised form of everything a device owns.
#[derive(Serialize, Deserialize)]
struct State {
    v: u8,
    account: AccountPickle,
    sealing_secret: SecretBytes,
    /// Peer Curve25519 key (base64) → sessions, newest first.
    sessions: HashMap<String, Vec<SessionRecord>>,
    /// Session ids of pre-key messages already accepted.
    #[serde(default)]
    used_prekeys: VecDeque<String>,
    /// Peer Curve25519 key (base64) → the Ed25519 key it is bound to.
    #[serde(default)]
    peers: HashMap<String, String>,
    /// Sealing prekeys, newest first.
    #[serde(default)]
    prekeys: Vec<PrekeyRecord>,
    /// Peers (Curve25519, base64) seen publishing a sealing prekey.
    #[serde(default)]
    prekey_peers: HashSet<String>,
}

/// Pickle format of [`State`]. Version 1 had no session ages or peer bindings,
/// version 2 no sealing prekeys; version 2 is still read.
const STATE_VERSION: u8 = 3;

/// A sealing prekey held in memory.
struct PrekeyEntry {
    secret: StaticSecret,
    created_ms: u64,
    superseded_ms: Option<u64>,
}

impl PrekeyEntry {
    fn new(now: u64) -> Self {
        Self { secret: StaticSecret::random_from_rng(&mut rand::thread_rng()), created_ms: now, superseded_ms: None }
    }
}

/// A live session and when it last decrypted something.
struct SessionEntry {
    session: Session,
    last_decrypt_ms: Option<u64>,
}

struct Inner2 {
    account: Account,
    sealing_secret: StaticSecret,
    sessions: HashMap<String, Vec<SessionEntry>>,
    used_prekeys: VecDeque<String>,
    /// Which Ed25519 key each peer Curve25519 key belongs to, learned only from
    /// signed peer keys ([`Identity::start_session`]) or from a pre-key message
    /// Olm authenticated. A sender claim that pairs a known Curve25519 key with
    /// another Ed25519 key is forged.
    peers: HashMap<String, String>,
    /// This device's sealing prekeys, newest (the published one) first.
    prekeys: Vec<PrekeyEntry>,
    /// Peers seen publishing a sealing prekey: never sealed to the old way again.
    prekey_peers: HashSet<String>,
}

/// How many accepted pre-key session ids are remembered to refuse replays.
const MAX_USED_PREKEYS: usize = 2000;

/// Sessions kept per peer before older, idle ones are evicted.
const MAX_SESSIONS_PER_PEER: usize = 5;
/// A session that decrypted something this recently is never evicted: the peer
/// may still be sending on it, whatever anyone asks this device to start.
const SESSION_PROTECT_MS: u64 = 14 * 24 * 60 * 60 * 1000;
/// The most sessions kept with one peer. With this many, all in recent use, a
/// new outbound one is refused ([`CryptoError::SessionLimit`]); a new inbound
/// one, which only the peer can start, replaces the least recently used.
const SESSION_HARD_LIMIT: usize = 16;

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn is_protected(entry: &SessionEntry, now: u64) -> bool {
    entry.last_decrypt_ms.is_some_and(|t| now.saturating_sub(t) < SESSION_PROTECT_MS)
}

/// A device's cryptographic identity: its Olm account, its sealing key pair and
/// its sessions with every peer. All methods are safe to call from any thread.
#[derive(uniffi::Object)]
pub struct Identity {
    inner: Mutex<Inner2>,
}

#[uniffi::export]
impl Identity {
    /// A brand-new identity with fresh random keys.
    #[uniffi::constructor]
    pub fn create() -> std::sync::Arc<Self> {
        let mut account = Account::new();
        account.generate_fallback_key();
        std::sync::Arc::new(Self {
            inner: Mutex::new(Inner2 {
                account,
                sealing_secret: StaticSecret::random_from_rng(&mut rand::thread_rng()),
                sessions: HashMap::new(),
                used_prekeys: VecDeque::new(),
                peers: HashMap::new(),
                prekeys: vec![PrekeyEntry::new(now_ms())],
                prekey_peers: HashSet::new(),
            }),
        })
    }

    /// Restores an identity from [`Identity::pickle`] output under the same key.
    #[uniffi::constructor]
    pub fn restore(pickle: String, key: Vec<u8>) -> Result<std::sync::Arc<Self>> {
        let key = key_from(Zeroizing::new(key))?;
        let blob = B64
            .decode(pickle.trim())
            .map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?;
        if blob.len() < 12 + 16 {
            return Err(CryptoError::BadPickle { reason: "too short".into() });
        }
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
        let plain = Zeroizing::new(
            cipher
                .decrypt(Nonce::from_slice(&blob[..12]), &blob[12..])
                .map_err(|_| CryptoError::BadPickle { reason: "wrong key or damaged".into() })?,
        );
        let state: State =
            serde_json::from_slice(&plain).map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?;
        if state.v != STATE_VERSION && state.v != 2 {
            return Err(CryptoError::BadPickle { reason: format!("unknown version {}", state.v) });
        }
        let State { account, sealing_secret, sessions, used_prekeys, peers, prekeys, prekey_peers, .. } = state;
        let prekeys = prekeys
            .into_iter()
            .map(|r| PrekeyEntry { secret: StaticSecret::from(r.s.0), created_ms: r.c, superseded_ms: r.x })
            .collect();
        let sessions = sessions
            .into_iter()
            .map(|(peer, records)| {
                let entries = records
                    .into_iter()
                    .map(|r| SessionEntry { session: Session::from_pickle(r.p), last_decrypt_ms: r.used })
                    .collect();
                (peer, entries)
            })
            .collect();
        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(Inner2 {
                account: Account::from_pickle(account),
                sealing_secret: StaticSecret::from(sealing_secret.0),
                sessions,
                used_prekeys,
                peers,
                prekeys,
                prekey_peers,
            }),
        }))
    }

    /// Serialises the whole identity, encrypted with ChaCha20-Poly1305 under a
    /// 32-byte key. The app keeps the key in the Keystore and the blob on disk.
    pub fn pickle(&self, key: Vec<u8>) -> Result<String> {
        let key = key_from(Zeroizing::new(key))?;
        let inner = self.inner.lock().expect("identity mutex poisoned");
        let state = State {
            v: STATE_VERSION,
            account: inner.account.pickle(),
            sealing_secret: SecretBytes(inner.sealing_secret.to_bytes()),
            sessions: inner
                .sessions
                .iter()
                .map(|(peer, entries)| {
                    let records = entries
                        .iter()
                        .map(|e| SessionRecord { p: e.session.pickle(), used: e.last_decrypt_ms })
                        .collect();
                    (peer.clone(), records)
                })
                .collect(),
            used_prekeys: inner.used_prekeys.clone(),
            peers: inner.peers.clone(),
            prekeys: inner
                .prekeys
                .iter()
                .map(|p| PrekeyRecord { s: SecretBytes(p.secret.to_bytes()), c: p.created_ms, x: p.superseded_ms })
                .collect(),
            prekey_peers: inner.prekey_peers.clone(),
        };
        // Sized first, then written once into a buffer that never grows: a
        // growing Vec frees each smaller copy of the plaintext state (identity
        // keys, chain keys) unwiped as it reallocates.
        let mut size = ByteCounter(0);
        serde_json::to_writer(&mut size, &state).map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?;
        let mut plain = Zeroizing::new(Vec::with_capacity(size.0));
        serde_json::to_writer(&mut *plain, &state).map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?;
        let mut nonce = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
        let ct = cipher
            .encrypt(Nonce::from_slice(&nonce), plain.as_slice())
            .map_err(|_| CryptoError::Encrypt { reason: "pickle".into() })?;
        let mut out = nonce.to_vec();
        out.extend_from_slice(&ct);
        Ok(B64.encode(out))
    }

    // ── Keys ──────────────────────────────────────────────────────────────

    pub fn ed25519(&self) -> String {
        self.inner.lock().expect("identity mutex poisoned").account.ed25519_key().to_base64()
    }

    pub fn curve25519(&self) -> String {
        self.inner.lock().expect("identity mutex poisoned").account.curve25519_key().to_base64()
    }

    /// The public bundle to publish: identity keys, the signed fallback key and
    /// the one-time keys not yet marked published.
    pub fn public_bundle(&self) -> PublicBundle {
        let inner = self.inner.lock().expect("identity mutex poisoned");
        let curve = inner.account.curve25519_key().to_base64();
        let sealing = B64.encode(XPublicKey::from(&inner.sealing_secret).as_bytes());
        let signature = inner.account.sign(format!("{curve}|{sealing}")).to_base64();
        let fallback = inner.account.fallback_key().into_iter().next().map(|(id, key)| SignedKey {
            id: id.to_base64(),
            key: key.to_base64(),
            signature: inner.account.sign(key.to_base64()).to_base64(),
        });
        let one_time_keys = inner
            .account
            .one_time_keys()
            .into_iter()
            .map(|(id, key)| SignedKey {
                id: id.to_base64(),
                key: key.to_base64(),
                signature: inner.account.sign(key.to_base64()).to_base64(),
            })
            .collect();
        let sealing_prekey = inner.prekeys.first().map(|p| {
            let key = B64.encode(XPublicKey::from(&p.secret).as_bytes());
            let created_ms = i64::try_from(p.created_ms).unwrap_or(i64::MAX);
            let signature = inner.account.sign(prekey_statement(&key, created_ms)).to_base64();
            SealingPrekey { key, created_ms, signature }
        });
        PublicBundle {
            ed25519: inner.account.ed25519_key().to_base64(),
            curve25519: curve,
            sealing,
            signature,
            sealing_prekey,
            fallback,
            one_time_keys,
        }
    }

    /// Generates `count` fresh one-time keys (kept until published).
    pub fn generate_one_time_keys(&self, count: u32) {
        self.inner.lock().expect("identity mutex poisoned").account.generate_one_time_keys(count as usize);
    }

    /// Rotates the fallback key. The previous one stays valid until the next rotation.
    pub fn rotate_fallback_key(&self) {
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        inner.account.generate_fallback_key();
    }

    /// Call after the relay has accepted the bundle's keys.
    pub fn mark_keys_published(&self) {
        self.inner.lock().expect("identity mutex poisoned").account.mark_keys_as_published();
    }

    /// Ed25519 signature over `message`, base64.
    pub fn sign(&self, message: Vec<u8>) -> String {
        self.inner.lock().expect("identity mutex poisoned").account.sign(message).to_base64()
    }

    // ── Sealing prekeys ───────────────────────────────────────────────────

    /// True when there is no sealing prekey yet (an identity from before
    /// them) or the current one is a week old: call
    /// [`Identity::rotate_sealing_prekey`] and publish the bundle.
    pub fn sealing_prekey_due(&self) -> bool {
        let inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        inner.prekeys.first().is_none_or(|p| now.saturating_sub(p.created_ms) >= PREKEY_ROTATE_MS || p.created_ms > now.saturating_add(PREKEY_FUTURE_SKEW_MS))
    }

    /// Makes a new sealing prekey, the one [`Identity::public_bundle`] offers
    /// from now on. The one it replaces is kept for envelopes already on their
    /// way to it, until [`Identity::prune_sealing_prekeys`] lets it go.
    pub fn rotate_sealing_prekey(&self) {
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        if let Some(current) = inner.prekeys.first_mut() {
            current.superseded_ms.get_or_insert(now);
        }
        inner.prekeys.insert(0, PrekeyEntry::new(now));
        inner.prekeys.truncate(MAX_PREKEYS);
    }

    /// Deletes the secrets of replaced sealing prekeys that nothing can still
    /// arrive for, and returns how many went. `drained_at_ms` is when this
    /// device last emptied its relay mailbox (0 when unknown). A replaced
    /// prekey goes once a sender can no longer seal to it (two weeks after it
    /// was made), envelopes carried phone to phone have had time to arrive,
    /// and the mailbox has been emptied since; or 30 days after it was
    /// replaced, the longest the relay keeps an envelope, whatever happened.
    /// After that, envelopes sealed to it can no longer be opened by anyone,
    /// whoever logged them and whatever happens to this phone.
    pub fn prune_sealing_prekeys(&self, drained_at_ms: i64) -> u32 {
        let drained = u64::try_from(drained_at_ms).unwrap_or(0);
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        let before = inner.prekeys.len();
        let mut first = true;
        inner.prekeys.retain(|p| {
            let keep_current = std::mem::take(&mut first);
            let Some(replaced) = p.superseded_ms else { return true };
            if keep_current {
                return true;
            }
            let last_use = p.created_ms.saturating_add(PREKEY_MAX_AGE_MS + PREKEY_DRAIN_GRACE_MS);
            now < replaced.saturating_add(PREKEY_KEEP_MS) && !(drained >= last_use && now >= last_use)
        });
        (before - inner.prekeys.len()) as u32
    }

    // ── Sessions ──────────────────────────────────────────────────────────

    /// True when at least one session with this peer exists.
    pub fn has_session(&self, peer_curve25519: String) -> bool {
        self.inner
            .lock()
            .expect("identity mutex poisoned")
            .sessions
            .get(&peer_curve25519)
            .is_some_and(|s| !s.is_empty())
    }

    /// Starts an outbound session with `peer` using one of their published keys
    /// (a one-time key or the fallback), after checking its signature and the
    /// signature binding their Curve25519 and sealing keys to their Ed25519 key.
    ///
    /// Sessions the peer used in the last 14 days are never evicted to make room;
    /// with [`SESSION_HARD_LIMIT`] of them already in use this fails with
    /// [`CryptoError::SessionLimit`] naming the peer.
    pub fn start_session(&self, peer: PeerKeys, signed_key: SignedKey) -> Result<()> {
        verify_peer(&peer)?;
        let ed = ed25519_from(&peer.ed25519)?;
        verify_signature(&ed, signed_key.key.as_bytes(), &signed_key.signature)?;
        let their_identity = curve_from(&peer.curve25519)?;
        let their_key = curve_from(&signed_key.key)?;
        // Kept under the key's one spelling: the one decrypt finds it under.
        let peer_curve = their_identity.to_base64();
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        if inner.sessions.get(&peer_curve).is_some_and(|list| at_hard_limit(list, now)) {
            return Err(CryptoError::SessionLimit { peer_curve25519: peer_curve });
        }
        // A Curve25519 key this device already knows as one identity's is not
        // rebound to another: whoever offers it with a different Ed25519 key is
        // not its owner (the relay refuses such a registration too), and
        // accepting would make the real owner's envelopes unreadable here.
        if inner.peers.get(&peer_curve).is_some_and(|known| *known != ed.to_base64()) {
            return Err(CryptoError::BadKey { reason: "this Curve25519 key already belongs to another identity".into() });
        }
        let session = inner
            .account
            .create_outbound_session(SessionConfig::version_2(), their_identity, their_key)
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        inner.peers.insert(peer_curve.clone(), ed.to_base64());
        push_session(&mut inner.sessions, peer_curve, SessionEntry { session, last_decrypt_ms: None }, now);
        Ok(())
    }

    /// Forgets every session with `peer` (they re-paired, or the app is resetting).
    pub fn drop_sessions(&self, peer_curve25519: String) {
        let name = curve_name(&peer_curve25519).unwrap_or(peer_curve25519);
        self.inner.lock().expect("identity mutex poisoned").sessions.remove(&name);
    }

    /// Drops the sessions (and key bindings) of every peer not in `keep` and
    /// returns how many peers' sessions were dropped. Any pre-key message to the
    /// fallback key creates a session before the app knows who sent it; sessions
    /// from senders that never became contacts would otherwise pile up in every
    /// saved state.
    pub fn prune_sessions(&self, keep: Vec<String>) -> u32 {
        let keep: HashSet<String> = keep.into_iter().map(|k| curve_name(&k).unwrap_or(k)).collect();
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let before = inner.sessions.len();
        inner.sessions.retain(|peer, _| keep.contains(peer));
        inner.peers.retain(|peer, _| keep.contains(peer));
        (before - inner.sessions.len()) as u32
    }

    /// Pads `plaintext`, encrypts it for `peer` and seals it for the relay,
    /// with this identity's sender claim inside. Requires a session.
    ///
    /// With the peer's sealing prekey (checked: signed by them, and fresh) the
    /// envelope is format 3, sealed to it. Without one, format 2, sealed to
    /// their long-term key, but only for a peer never seen with a prekey:
    /// otherwise [`CryptoError::StalePrekey`], so the app fetches their keys.
    pub fn encrypt(&self, peer: PeerKeys, plaintext: Vec<u8>) -> Result<Vec<u8>> {
        verify_peer(&peer)?;
        let sealing = x25519_from(&peer.sealing)?;
        let peer_curve = curve_name(&peer.curve25519)?;
        let peer_ed_key = ed25519_from(&peer.ed25519)?;
        let peer_ed = peer_ed_key.to_base64();
        let now = now_ms();
        let prekey = match &peer.sealing_prekey {
            Some(p) => Some(checked_prekey(&peer_ed_key, &peer_curve, p, now)?),
            None => None,
        };
        let plaintext = Zeroizing::new(plaintext);
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        // The session belongs to whoever this device learned owns that Curve25519
        // key; keys that pair it with another identity (and so another sealing
        // key) would seal what goes over it for someone else.
        if inner.peers.get(&peer_curve).is_some_and(|known| *known != peer_ed) {
            return Err(CryptoError::BadKey { reason: "this Curve25519 key belongs to another identity".into() });
        }
        match prekey {
            Some(_) => {
                inner.prekey_peers.insert(peer_curve.clone());
            }
            None if inner.prekey_peers.contains(&peer_curve) => {
                return Err(CryptoError::StalePrekey { peer_curve25519: peer_curve });
            }
            None => {}
        }
        let my_curve = inner.account.curve25519_key();
        let my_ed = inner.account.ed25519_key();
        let session = inner
            .sessions
            .get_mut(&peer_curve)
            .and_then(|s| s.first_mut())
            .ok_or(CryptoError::NoSession)?;
        let message = session
            .session
            .encrypt(pad(&plaintext).as_slice())
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        let (message_type, ciphertext) = message.to_parts();
        let message_type = message_type as u8;
        let sent_at = now;
        let account = &inner.account;
        let Some(prekey) = prekey else {
            return seal(ENVELOPE_V2, SEAL_INFO_V2, &sealing, |eph_pub| {
                let claim = sender_claim(eph_pub, sealing.as_bytes(), message_type, my_curve.as_bytes(), sent_at, &ciphertext);
                let signature = account.sign(&claim).to_bytes();
                let mut out = Zeroizing::new(Vec::with_capacity(INNER_HEADER + ciphertext.len()));
                out.push(message_type);
                out.extend_from_slice(my_curve.as_bytes());
                out.extend_from_slice(my_ed.as_bytes());
                out.extend_from_slice(&sent_at.to_be_bytes());
                out.extend_from_slice(&signature);
                out.extend_from_slice(&ciphertext);
                Ok(out)
            });
        };
        let my_sealing_secret = &inner.sealing_secret;
        let my_sealing = XPublicKey::from(my_sealing_secret);
        let binding = account
            .sign(format!("{}|{}", my_curve.to_base64(), B64.encode(my_sealing.as_bytes())))
            .to_bytes();
        let ct_len = u32::try_from(ciphertext.len()).map_err(|_| CryptoError::Encrypt { reason: "message too large".into() })?;
        seal(ENVELOPE_V3, SEAL_INFO_V3, &prekey, |eph_pub| {
            let shared = my_sealing_secret.diffie_hellman(&prekey);
            if !shared.was_contributory() {
                return Err(CryptoError::BadKey { reason: "sealing prekey is of low order".into() });
            }
            let key = claim_key(shared.as_bytes(), eph_pub, prekey.as_bytes());
            let mut mac = claim_mac(&key);
            mac.update(&claim_mac_input(
                message_type,
                my_curve.as_bytes(),
                my_ed.as_bytes(),
                my_sealing.as_bytes(),
                sent_at,
                &ciphertext,
            ));
            let tag = mac.finalize().into_bytes();
            let len = (INNER_V3_HEADER + ciphertext.len()).div_ceil(INNER_V3_STEP) * INNER_V3_STEP;
            let mut out = Zeroizing::new(Vec::with_capacity(len));
            out.push(message_type);
            out.extend_from_slice(my_curve.as_bytes());
            out.extend_from_slice(my_ed.as_bytes());
            out.extend_from_slice(my_sealing.as_bytes());
            out.extend_from_slice(&binding);
            out.extend_from_slice(&sent_at.to_be_bytes());
            out.extend_from_slice(&tag);
            out.extend_from_slice(&ct_len.to_be_bytes());
            out.extend_from_slice(&ciphertext);
            out.resize(len, 0);
            Ok(out)
        })
    }

    /// Opens an envelope addressed to this device. The sender claim inside is
    /// checked first: for format 3 its binding signature and MAC, for format 2
    /// its signature, either one bound to this envelope; and the Ed25519 key it
    /// names must be the one this device knows for the named Curve25519 key.
    /// Only then can a failure name the sender. A pre-key message from an
    /// unknown session creates one; the sender's identity keys are returned so
    /// the app can check them against a known contact before trusting the
    /// content.
    pub fn decrypt(&self, envelope: Vec<u8>) -> Result<Decrypted> {
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        let opened = match envelope.first() {
            Some(&ENVELOPE_V3) => open_v3(&inner.prekeys, &envelope)?,
            Some(&ENVELOPE_V2) => open_v2(&inner.sealing_secret, &envelope)?,
            Some(v) => return Err(CryptoError::Envelope { reason: format!("unknown version {v}") }),
            None => return Err(CryptoError::Envelope { reason: "too short".into() }),
        };
        let sealed_to_prekey = opened.sealed_to_prekey;
        let sender_identity = opened.sender_identity;
        let curve_b64 = sender_identity.to_base64();
        let ed_b64 = opened.sender_ed.to_base64();
        // A key pair this device already knows must not be re-paired by a claim.
        if inner.peers.get(&curve_b64).is_some_and(|known| *known != ed_b64) {
            return Err(CryptoError::BadSignature);
        }
        let sent_at_ms = i64::try_from(opened.sent_at_ms).unwrap_or(i64::MAX);

        let olm = OlmMessage::from_parts(opened.message_type as usize, &opened.ciphertext)
            .map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;

        // Existing sessions first, newest first. A session that knows the
        // message's chain but has already spent its key means this exact message
        // was decrypted before: a duplicate, not a broken session.
        //
        // The session that decrypts moves to the front, so this phone replies on
        // the session the peer is actually using. Without that, two phones that
        // each started a session at the same moment kept sending on different
        // ones for good, and the one the peer used could be pushed out of the
        // list by newer sessions, after which its messages no longer decrypted.
        let mut spent = false;
        if let Some(sessions) = inner.sessions.get_mut(&curve_b64) {
            for i in 0..sessions.len() {
                match sessions[i].session.decrypt(&olm) {
                    Ok(padded) => {
                        let padded = Zeroizing::new(padded);
                        let plaintext = unpad(&padded)?.to_vec();
                        sessions[i].last_decrypt_ms = Some(now);
                        if i != 0 {
                            let used = sessions.remove(i);
                            sessions.insert(0, used);
                        }
                        return Ok(Decrypted {
                            sender_curve25519: curve_b64,
                            sender_ed25519: ed_b64,
                            sent_at_ms,
                            plaintext,
                            new_session: false,
                            evicted_recent_session: false,
                            sealed_to_prekey,
                        });
                    }
                    Err(DecryptionError::MissingMessageKey(_)) => spent = true,
                    Err(_) => {}
                }
            }
        }
        if spent {
            return Err(CryptoError::Duplicate);
        }

        // A pre-key message we have not seen: create the inbound session.
        match olm {
            OlmMessage::PreKey(prekey) => {
                if prekey.identity_key() != sender_identity {
                    return Err(CryptoError::Decrypt { reason: "sender key does not match the pre-key message".into() });
                }
                // A pre-key message that already created one of our sessions is a
                // replay: with a fallback key it would otherwise succeed again,
                // duplicate the session and hand back stale plaintext.
                // The session ids already accepted are remembered even after a
                // session is dropped, so a relay cannot replay an old pre-key
                // message later to have it processed a second time.
                let session_id = prekey.session_id();
                let replayed = inner.used_prekeys.iter().any(|id| *id == session_id)
                    || inner
                        .sessions
                        .get(&curve_b64)
                        .is_some_and(|list| list.iter().any(|s| s.session.session_id() == session_id));
                if replayed {
                    return Err(CryptoError::Duplicate);
                }
                // The peer itself (Olm authenticates it below) started a new
                // session while every one this device keeps with it is in recent
                // use: its newest session wins, and the least recently used goes.
                let make_room = inner.sessions.get(&curve_b64).is_some_and(|list| at_hard_limit(list, now));
                // Only an unknown one-time key means the sender holds a session this
                // phone lost (a restored backup, a used-up key) and a new one should
                // be asked for. Any other failure is a message that was never valid
                // and must not make the app act on the named contact's behalf.
                let created = inner
                    .account
                    .create_inbound_session(SessionConfig::version_2(), sender_identity, &prekey)
                    .map_err(|e| match e {
                        SessionCreationError::MissingOneTimeKey(_) => CryptoError::DecryptFrom {
                            sender_curve25519: curve_b64.clone(),
                            sender_ed25519: ed_b64.clone(),
                            sent_at_ms,
                            reason: e.to_string(),
                        },
                        other => CryptoError::Decrypt { reason: other.to_string() },
                    })?;
                // Olm has now authenticated the Curve25519 key, and the claim's
                // signature the Ed25519 key: from here on they belong together,
                // unless the Curve25519 key is already another identity's.
                if inner.peers.get(&curve_b64).is_some_and(|known| *known != ed_b64) {
                    return Err(CryptoError::BadKey { reason: "this Curve25519 key already belongs to another identity".into() });
                }
                inner.peers.insert(curve_b64.clone(), ed_b64.clone());
                // The one-time key is spent whatever the plaintext turns out to
                // be, so the session is kept and the pre-key message remembered
                // before the padding is checked: a sender whose first message
                // was malformed can still be read afterwards, and the message
                // cannot be replayed.
                let padded = Zeroizing::new(created.plaintext);
                let plaintext = unpad(&padded).map(|p| p.to_vec());
                let evicted_recent_session = make_room
                    && inner.sessions.get_mut(&curve_b64).is_some_and(evict_least_recently_used);
                push_session(
                    &mut inner.sessions,
                    curve_b64.clone(),
                    SessionEntry { session: created.session, last_decrypt_ms: Some(now) },
                    now,
                );
                inner.used_prekeys.push_back(session_id);
                while inner.used_prekeys.len() > MAX_USED_PREKEYS {
                    inner.used_prekeys.pop_front();
                }
                let plaintext = plaintext?;
                Ok(Decrypted {
                    sender_curve25519: curve_b64,
                    sender_ed25519: ed_b64,
                    sent_at_ms,
                    plaintext,
                    new_session: true,
                    evicted_recent_session,
                    sealed_to_prekey,
                })
            }
            OlmMessage::Normal(_) => {
                Err(CryptoError::NoSessionFor { sender_curve25519: curve_b64, sender_ed25519: ed_b64, sent_at_ms })
            }
        }
    }

    /// A 60-digit safety number for this device and `peer`, the same on both
    /// phones regardless of who computes it: SHA-512 over the two Ed25519 keys
    /// in sorted order, rendered as twelve groups of five digits.
    pub fn safety_number(&self, peer_ed25519: String) -> Result<String> {
        let mine = self.ed25519();
        let peer = ed25519_from(&peer_ed25519)?.to_base64();
        Ok(safety_number_for(&mine, &peer))
    }

    /// The offline mesh's routing tag for bundles between this device and the
    /// owner of `peer_sealing` on `day` (days since the Unix epoch, UTC): 16
    /// bytes from HKDF-SHA256 over the X25519 secret the two sealing keys
    /// share, with both public keys in sorted order as salt and the day in
    /// the info. Both ends compute the same tag and nobody else can: not the
    /// relay, not a carrier, and not another contact who holds either public
    /// key. The secret never leaves this crate.
    pub fn mesh_tag(&self, peer_sealing: String, day: i64) -> Result<Vec<u8>> {
        let peer = x25519_from(&peer_sealing)?;
        let inner = self.inner.lock().expect("identity mutex poisoned");
        let mine = XPublicKey::from(&inner.sealing_secret);
        let shared = inner.sealing_secret.diffie_hellman(&peer);
        if !shared.was_contributory() {
            return Err(CryptoError::BadKey { reason: "low-order sealing key".into() });
        }
        let (a, b) = if mine.as_bytes() <= peer.as_bytes() { (mine, peer) } else { (peer, mine) };
        let mut salt = Vec::with_capacity(64);
        salt.extend_from_slice(a.as_bytes());
        salt.extend_from_slice(b.as_bytes());
        let hk = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
        let mut info = MESH_TAG_INFO.to_vec();
        info.extend_from_slice(&day.to_be_bytes());
        let mut tag = vec![0u8; MESH_TAG_LEN];
        hk.expand(&info, &mut tag).expect("16 bytes is a valid HKDF output length");
        Ok(tag)
    }
}

/// True when `list` already holds [`SESSION_HARD_LIMIT`] protected sessions:
/// adding one more would mean evicting a session in recent use.
fn at_hard_limit(list: &[SessionEntry], now: u64) -> bool {
    list.iter().filter(|e| is_protected(e, now)).count() >= SESSION_HARD_LIMIT
}

/// Removes the session that decrypted something longest ago (one that never
/// did counts as oldest). True when one was removed.
fn evict_least_recently_used(list: &mut Vec<SessionEntry>) -> bool {
    let oldest = list.iter().enumerate().min_by_key(|(_, e)| e.last_decrypt_ms.unwrap_or(0)).map(|(i, _)| i);
    match oldest {
        Some(i) => {
            list.remove(i);
            true
        }
        None => false,
    }
}

/// Puts `entry` first in the peer's list, then evicts the oldest sessions that
/// are not protected until at most [`MAX_SESSIONS_PER_PEER`] remain. Sessions
/// that decrypted something in the last [`SESSION_PROTECT_MS`] stay even past
/// that number (callers check [`at_hard_limit`] first and refuse or make room,
/// so the list never grows past [`SESSION_HARD_LIMIT`]).
fn push_session(sessions: &mut HashMap<String, Vec<SessionEntry>>, peer: String, entry: SessionEntry, now: u64) {
    let list = sessions.entry(peer).or_default();
    list.insert(0, entry);
    let mut i = list.len();
    while list.len() > MAX_SESSIONS_PER_PEER && i > 1 {
        i -= 1;
        if !is_protected(&list[i], now) {
            list.remove(i);
        }
    }
}

// ── Free functions ─────────────────────────────────────────────────────────

/// One blind token in the making: the random input the token will show when
/// it is spent, the blinded element the relay is sent, and the client state
/// (the blind and the blinded element) needed to unblind its answer. Kept in
/// memory between the request and its answer, never stored.
#[derive(Clone, uniffi::Record)]
pub struct BlindedToken {
    pub input: Vec<u8>,
    pub blinded: Vec<u8>,
    pub state: Vec<u8>,
}

/// Starts a blind send token (RFC 9497 VOPRF, ristretto255-SHA512): a fresh
/// 32-byte input, blinded. The relay sees only `blinded`.
#[uniffi::export]
pub fn token_blind() -> BlindedToken {
    let mut input = vec![0u8; 32];
    rand::thread_rng().fill_bytes(&mut input);
    let result = voprf::VoprfClient::<voprf::Ristretto255>::blind(&input, &mut rand::thread_rng())
        .expect("a 32-byte input is always accepted");
    BlindedToken {
        input,
        blinded: result.message.serialize().to_vec(),
        state: result.state.serialize().to_vec(),
    }
}

/// Unblinds the relay's answer to [`token_blind`] requests into token outputs.
/// `evaluated` answers the first `evaluated.len()` of `tokens` (a relay grants
/// fewer when the allowance runs low), and `proof` must show that all of them
/// were made with `public_key`, the key the relay publishes for the kind: a
/// relay that used another key, which could tell whose tokens they were, is
/// refused here with [`CryptoError::BadSignature`].
#[uniffi::export]
pub fn token_finalize(tokens: Vec<BlindedToken>, evaluated: Vec<Vec<u8>>, proof: Vec<u8>, public_key: Vec<u8>) -> Result<Vec<Vec<u8>>> {
    use voprf::{EvaluationElement, Group, Proof, Ristretto255, VoprfClient};
    if evaluated.is_empty() || evaluated.len() > tokens.len() {
        return Err(CryptoError::Decrypt { reason: "token answer does not match the request".into() });
    }
    let bad = |what: &str| CryptoError::BadKey { reason: format!("token {what}") };
    let pk = Ristretto255::deserialize_elem(&public_key).map_err(|_| bad("public key"))?;
    let proof = Proof::<Ristretto255>::deserialize(&proof).map_err(|_| bad("proof"))?;
    let used = &tokens[..evaluated.len()];
    let clients = used
        .iter()
        .map(|t| VoprfClient::<Ristretto255>::deserialize(&t.state).map_err(|_| bad("state")))
        .collect::<Result<Vec<_>>>()?;
    let messages = evaluated
        .iter()
        .map(|e| EvaluationElement::<Ristretto255>::deserialize(e).map_err(|_| bad("element")))
        .collect::<Result<Vec<_>>>()?;
    let inputs: Vec<&[u8]> = used.iter().map(|t| t.input.as_slice()).collect();
    let outputs = VoprfClient::batch_finalize(&inputs, &clients, &messages, &proof, pk).map_err(|_| CryptoError::BadSignature)?;
    outputs
        .map(|o| o.map(|o| o.to_vec()).map_err(|_| CryptoError::Decrypt { reason: "token output".into() }))
        .collect()
}

/// Verifies an Ed25519 signature (base64) over `message` with `ed25519` (base64).
#[uniffi::export]
pub fn verify(ed25519: String, message: Vec<u8>, signature: String) -> bool {
    match ed25519_from(&ed25519) {
        Ok(key) => verify_signature(&key, &message, &signature).is_ok(),
        Err(_) => false,
    }
}

/// The safety number for any two Ed25519 keys (base64), order-independent.
#[uniffi::export]
pub fn safety_number_for(a_ed25519: &str, b_ed25519: &str) -> String {
    let (first, second) = if a_ed25519 <= b_ed25519 { (a_ed25519, b_ed25519) } else { (b_ed25519, a_ed25519) };
    let mut hasher = Sha512::new();
    hasher.update(b"clairix-safety-number-v1");
    hasher.update(first.as_bytes());
    hasher.update(b"|");
    hasher.update(second.as_bytes());
    let digest = hasher.finalize();
    // Twelve groups of five digits, each group from 4 bytes → 0..99999.
    let mut groups = Vec::with_capacity(12);
    for chunk in digest.chunks(4).take(12) {
        let n = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) % 100_000;
        groups.push(format!("{n:05}"));
    }
    groups.join(" ")
}

/// A short, URL-safe fingerprint of an Ed25519 key for display and QR codes.
#[uniffi::export]
pub fn fingerprint(ed25519: String) -> String {
    let digest = Sha256::digest(ed25519.as_bytes());
    B64URL.encode(&digest[..12])
}

/// 32 random bytes, base64: the app uses this for the pickle key it wraps in the Keystore.
#[uniffi::export]
pub fn random_key() -> Vec<u8> {
    let mut key = vec![0u8; 32];
    rand::thread_rng().fill_bytes(&mut key);
    key
}

/// The padded length of a `len`-byte plaintext; see [`padded_len`].
#[uniffi::export]
pub fn padded_length(len: u32) -> u32 {
    u32::try_from(padded_len(len as usize)).unwrap_or(u32::MAX)
}

// ── Helpers ────────────────────────────────────────────────────────────────

fn key_from(key: Zeroizing<Vec<u8>>) -> Result<Zeroizing<[u8; 32]>> {
    let arr: [u8; 32] =
        key.as_slice().try_into().map_err(|_| CryptoError::BadKey { reason: "pickle key must be 32 bytes".into() })?;
    Ok(Zeroizing::new(arr))
}

/// Whether `bytes` is a Curve25519 public key in its one canonical form: the top
/// bit clear and the value below the field prime 2^255 - 19. X25519 ignores the
/// top bit and reduces the rest, so without this one key has several spellings,
/// and every map keyed by a key's text could hold it twice (a second spelling
/// of someone's key would not be recognised as theirs).
fn canonical_x25519(bytes: &[u8; 32]) -> bool {
    if bytes[31] & 0x80 != 0 {
        return false;
    }
    // p, little-endian: ed ff ff ... ff 7f. Canonical means bytes < p.
    let mut p = [0xffu8; 32];
    p[0] = 0xed;
    p[31] = 0x7f;
    for i in (0..32).rev() {
        if bytes[i] != p[i] {
            return bytes[i] < p[i];
        }
    }
    false
}

fn curve_from(b64: &str) -> Result<Curve25519PublicKey> {
    let key = Curve25519PublicKey::from_base64(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })?;
    if !canonical_x25519(key.as_bytes()) {
        return Err(CryptoError::BadKey { reason: "Curve25519 key is not in canonical form".into() });
    }
    Ok(key)
}

/// The one spelling of a Curve25519 key, under which sessions and bindings are kept.
fn curve_name(b64: &str) -> Result<String> {
    Ok(curve_from(b64)?.to_base64())
}

fn ed25519_from(b64: &str) -> Result<Ed25519PublicKey> {
    Ed25519PublicKey::from_base64(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })
}

fn x25519_from(b64: &str) -> Result<XPublicKey> {
    let bytes = B64.decode(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })?;
    let arr: [u8; 32] =
        bytes.try_into().map_err(|_| CryptoError::BadKey { reason: "sealing key must be 32 bytes".into() })?;
    if !canonical_x25519(&arr) {
        return Err(CryptoError::BadKey { reason: "sealing key is not in canonical form".into() });
    }
    Ok(XPublicKey::from(arr))
}

fn verify_signature(key: &Ed25519PublicKey, message: &[u8], signature_b64: &str) -> Result<()> {
    let signature =
        Ed25519Signature::from_base64(signature_b64).map_err(|_| CryptoError::BadSignature)?;
    key.verify(message, &signature).map_err(|_| CryptoError::BadSignature)
}

/// Checks that a peer's Curve25519 and sealing keys are signed by their Ed25519 key.
fn verify_peer(peer: &PeerKeys) -> Result<()> {
    let ed = ed25519_from(&peer.ed25519)?;
    verify_signature(&ed, format!("{}|{}", peer.curve25519, peer.sealing).as_bytes(), &peer.signature)
}

// ── Tests ──────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn peer_keys(id: &Identity) -> PeerKeys {
        let b = id.public_bundle();
        PeerKeys { ed25519: b.ed25519, curve25519: b.curve25519, sealing: b.sealing, signature: b.signature, sealing_prekey: b.sealing_prekey }
    }

    /// A peer's keys as an app from before sealing prekeys has them: envelopes
    /// to it are format 2.
    fn legacy_keys(id: &Identity) -> PeerKeys {
        PeerKeys { sealing_prekey: None, ..peer_keys(id) }
    }

    /// [`paired`], over format-2 envelopes.
    fn paired_v2() -> (std::sync::Arc<Identity>, std::sync::Arc<Identity>) {
        let alice = Identity::create();
        let bob = Identity::create();
        alice.start_session(legacy_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.decrypt(alice.encrypt(legacy_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        alice.decrypt(bob.encrypt(legacy_keys(&alice), b"hello".to_vec()).unwrap()).unwrap();
        (alice, bob)
    }

    /// Alice and Bob with a session each way, the way two paired phones end up.
    fn paired() -> (std::sync::Arc<Identity>, std::sync::Arc<Identity>) {
        let alice = Identity::create();
        let bob = Identity::create();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.decrypt(alice.encrypt(peer_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        alice.decrypt(bob.encrypt(peer_keys(&alice), b"hello".to_vec()).unwrap()).unwrap();
        (alice, bob)
    }

    /// Seals a hand-made inner message to `to`, so a test can say anything a
    /// forger could: any claimed sender, any signature, any ciphertext.
    fn forge(to: &Identity, message_type: u8, curve: [u8; 32], ed: [u8; 32], sign: impl FnOnce(&[u8]) -> [u8; 64], ciphertext: &[u8]) -> Vec<u8> {
        let sealing = x25519_from(&to.public_bundle().sealing).unwrap();
        let sent_at = now_ms();
        seal(ENVELOPE_V2, SEAL_INFO_V2, &sealing, |eph| {
            let claim = sender_claim(eph, sealing.as_bytes(), message_type, &curve, sent_at, ciphertext);
            let sig = sign(&claim);
            let mut out = Zeroizing::new(Vec::new());
            out.push(message_type);
            out.extend_from_slice(&curve);
            out.extend_from_slice(&ed);
            out.extend_from_slice(&sent_at.to_be_bytes());
            out.extend_from_slice(&sig);
            out.extend_from_slice(ciphertext);
            Ok(out)
        })
        .unwrap()
    }

    /// Seals a hand-made format-3 inner message to `to`'s current prekey. The
    /// MAC is keyed from `shared`, the X25519 value between the claimed
    /// sealing key and that prekey, however the forger came by it.
    #[allow(clippy::too_many_arguments)]
    fn forge_v3(
        to: &Identity,
        message_type: u8,
        curve: [u8; 32],
        ed: [u8; 32],
        sealing: [u8; 32],
        binding: [u8; 64],
        shared: [u8; 32],
        ciphertext: &[u8],
    ) -> Vec<u8> {
        let prekey = x25519_from(&to.public_bundle().sealing_prekey.unwrap().key).unwrap();
        let sent_at = now_ms();
        seal(ENVELOPE_V3, SEAL_INFO_V3, &prekey, |eph| {
            let key = claim_key(&shared, eph, prekey.as_bytes());
            let mut mac = claim_mac(&key);
            mac.update(&claim_mac_input(message_type, &curve, &ed, &sealing, sent_at, ciphertext));
            let mut out = Zeroizing::new(Vec::new());
            out.push(message_type);
            out.extend_from_slice(&curve);
            out.extend_from_slice(&ed);
            out.extend_from_slice(&sealing);
            out.extend_from_slice(&binding);
            out.extend_from_slice(&sent_at.to_be_bytes());
            out.extend_from_slice(&mac.finalize().into_bytes());
            out.extend_from_slice(&(ciphertext.len() as u32).to_be_bytes());
            out.extend_from_slice(ciphertext);
            let len = out.len().div_ceil(INNER_V3_STEP) * INNER_V3_STEP;
            out.resize(len, 0);
            Ok(out)
        })
        .unwrap()
    }

    fn sealing_bytes(id: &Identity) -> [u8; 32] {
        *x25519_from(&id.public_bundle().sealing).unwrap().as_bytes()
    }

    fn binding_bytes(id: &Identity) -> [u8; 64] {
        // vodozemac writes signatures in base64 without padding.
        base64::engine::general_purpose::STANDARD_NO_PAD.decode(id.public_bundle().signature).unwrap().try_into().unwrap()
    }

    /// The Olm ciphertext inside a format-3 envelope to `to`.
    fn olm_ciphertext_v3(to: &Identity, env: &[u8]) -> Vec<u8> {
        let me = to.inner.lock().unwrap();
        let (_, inner) = me.prekeys.iter().find_map(|p| unseal(&p.secret, SEAL_INFO_V3, env).ok()).unwrap();
        parse_inner_v3(&inner).unwrap().ciphertext.to_vec()
    }

    fn curve_bytes(id: &Identity) -> [u8; 32] {
        *curve_from(&id.curve25519()).unwrap().as_bytes()
    }

    fn ed_bytes(id: &Identity) -> [u8; 32] {
        *ed25519_from(&id.ed25519()).unwrap().as_bytes()
    }

    fn signer(id: &Identity) -> impl FnOnce(&[u8]) -> [u8; 64] + '_ {
        move |claim| id.inner.lock().unwrap().account.sign(claim).to_bytes()
    }

    /// The phone's half of the blind tokens against the relay's own module.
    #[test]
    fn blind_tokens_round_trip_with_the_relay_module() {
        use clairix_relay_voprf as relay;
        let secret = [3u8; 32];
        let info = b"clairix-token-v2|delivery|1500";
        let put = |parts: &[&[u8]]| {
            let buf = unsafe { std::slice::from_raw_parts_mut(relay::buffer(), relay::buffer_len()) };
            let mut at = 0;
            for p in parts {
                buf[at..at + p.len()].copy_from_slice(p);
                at += p.len();
            }
            buf
        };
        put(&[&secret, info]);
        assert_eq!(relay::public_key(info.len()), 0);
        let pk = put(&[])[..32].to_vec();

        let tokens: Vec<BlindedToken> = (0..4).map(|_| token_blind()).collect();
        let mut req = vec![];
        req.extend_from_slice(&secret);
        req.extend_from_slice(&[5u8; 32]);
        req.extend_from_slice(info);
        // The relay grants three of the four.
        for t in &tokens[..3] {
            req.extend_from_slice(&t.blinded);
        }
        put(&[&req]);
        assert_eq!(relay::evaluate_batch(info.len(), 3), 0);
        let out = put(&[]).to_vec();
        let evaluated: Vec<Vec<u8>> = (0..3).map(|i| out[i * 32..(i + 1) * 32].to_vec()).collect();
        let proof = out[96..160].to_vec();

        let outputs = token_finalize(tokens.clone(), evaluated.clone(), proof.clone(), pk.clone()).unwrap();
        assert_eq!(outputs.len(), 3);
        for (t, o) in tokens.iter().zip(&outputs) {
            put(&[&secret, info, &t.input, o]);
            assert_eq!(relay::verify(info.len(), 32), 0, "the relay accepts what the phone unblinded");
        }
        // Checked against a key the relay does not publish: refused.
        let mut other = pk.clone();
        put(&[&[4u8; 32], info]);
        assert_eq!(relay::public_key(info.len()), 0);
        other.copy_from_slice(&put(&[])[..32]);
        assert!(matches!(token_finalize(tokens.clone(), evaluated.clone(), proof.clone(), other), Err(CryptoError::BadSignature)));
        // An answer that does not fit the request.
        assert!(token_finalize(tokens[..2].to_vec(), evaluated, proof, pk).is_err());
    }

    #[test]
    fn mesh_tags_are_shared_by_the_two_ends_only() {
        let alice = Identity::create();
        let bob = Identity::create();
        let carol = Identity::create();
        let a = alice.public_bundle().sealing;
        let b = bob.public_bundle().sealing;
        let c = carol.public_bundle().sealing;
        let ab = alice.mesh_tag(b.clone(), 20_000).unwrap();
        assert_eq!(ab.len(), 16);
        assert_eq!(ab, bob.mesh_tag(a.clone(), 20_000).unwrap(), "both ends compute the same tag");
        assert_ne!(ab, alice.mesh_tag(b.clone(), 20_001).unwrap(), "the tag changes every day");
        assert_ne!(ab, carol.mesh_tag(b.clone(), 20_000).unwrap(), "another contact of Bob's gets another tag");
        assert_ne!(ab, carol.mesh_tag(a, 20_000).unwrap());
        assert_ne!(ab, bob.mesh_tag(c, 20_000).unwrap());
        assert!(alice.mesh_tag(B64.encode([0u8; 32]), 20_000).is_err(), "a low-order key is refused");
    }

    #[test]
    fn round_trip_between_two_identities() {
        let alice = Identity::create();
        let bob = Identity::create();
        bob.generate_one_time_keys(3);
        let bob_bundle = bob.public_bundle();
        assert_eq!(bob_bundle.one_time_keys.len(), 3);
        bob.mark_keys_published();
        assert!(bob.public_bundle().one_time_keys.is_empty());

        // Alice starts a session with one of Bob's one-time keys.
        alice.start_session(peer_keys(&bob), bob_bundle.one_time_keys[0].clone()).unwrap();
        assert!(alice.has_session(bob.curve25519()));

        // First message is a pre-key message; Bob has no session yet.
        let env1 = alice.encrypt(peer_keys(&bob), b"hello bob".to_vec()).unwrap();
        let d1 = bob.decrypt(env1).unwrap();
        assert_eq!(d1.plaintext, b"hello bob");
        assert!(d1.new_session);
        assert_eq!(d1.sender_curve25519, alice.curve25519());
        assert_eq!(d1.sender_ed25519, alice.ed25519());
        assert!((d1.sent_at_ms - now_ms() as i64).abs() < 60_000);
        assert!(!d1.evicted_recent_session);

        // Bob replies over the same session; Alice reads it with hers.
        let env2 = bob.encrypt(peer_keys(&alice), b"hi alice".to_vec()).unwrap();
        let d2 = alice.decrypt(env2).unwrap();
        assert_eq!(d2.plaintext, b"hi alice");
        assert!(!d2.new_session);

        // Many messages in a row ratchet fine.
        for i in 0..20 {
            let env = alice.encrypt(peer_keys(&bob), format!("msg {i}").into_bytes()).unwrap();
            assert_eq!(bob.decrypt(env).unwrap().plaintext, format!("msg {i}").into_bytes());
        }
    }

    #[test]
    fn fallback_key_works_when_one_time_keys_are_gone() {
        let alice = Identity::create();
        let bob = Identity::create();
        let fallback = bob.public_bundle().fallback.expect("fallback key present");
        alice.start_session(peer_keys(&bob), fallback).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"via fallback".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env).unwrap().plaintext, b"via fallback");
    }

    #[test]
    fn replayed_prekey_message_is_rejected() {
        let alice = Identity::create();
        let bob = Identity::create();
        // The fallback key stays on the account after use, so without the
        // replay check a second delivery would create a duplicate session.
        let fallback = bob.public_bundle().fallback.expect("fallback key present");
        alice.start_session(peer_keys(&bob), fallback).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"first".to_vec()).unwrap();
        let first = bob.decrypt(env.clone()).unwrap();
        assert!(first.new_session);
        let again = bob.decrypt(env);
        assert!(matches!(again, Err(CryptoError::Duplicate)), "replay accepted: {again:?}");
        assert_eq!(bob.inner.lock().unwrap().sessions[&alice.curve25519()].len(), 1);
        // The session itself keeps working after the rejected replay.
        let env2 = alice.encrypt(peer_keys(&bob), b"second".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env2).unwrap().plaintext, b"second");
    }

    #[test]
    fn prekey_on_a_lost_one_time_key_names_its_sender() {
        let alice = Identity::create();
        let bob = Identity::create();
        let key = random_key();
        // Bob's state before he generated the key Alice is about to use: the same
        // identity, but without that one-time key (a restore from an older pickle).
        let bob_before = Identity::restore(bob.pickle(key.clone()).unwrap(), key).unwrap();
        bob.generate_one_time_keys(1);
        let otk = bob.public_bundle().one_time_keys[0].clone();
        alice.start_session(peer_keys(&bob), otk).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"hello".to_vec()).unwrap();
        match bob_before.decrypt(env) {
            Err(CryptoError::DecryptFrom { sender_curve25519, sender_ed25519, .. }) => {
                assert_eq!(sender_curve25519, alice.curve25519());
                assert_eq!(sender_ed25519, alice.ed25519());
            }
            other => panic!("expected DecryptFrom, got {other:?}"),
        }
    }

    #[test]
    fn a_redelivered_normal_message_is_a_duplicate_not_a_broken_session() {
        let (alice, bob) = paired();
        // A normal (post-pre-key) message, delivered twice.
        let env = alice.encrypt(peer_keys(&bob), b"once".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env.clone()).unwrap().plaintext, b"once");
        assert!(matches!(bob.decrypt(env), Err(CryptoError::Duplicate)));
        // And the session is unharmed.
        let next = alice.encrypt(peer_keys(&bob), b"next".to_vec()).unwrap();
        assert_eq!(bob.decrypt(next).unwrap().plaintext, b"next");
    }

    #[test]
    fn a_replayed_prekey_is_refused_even_after_the_session_is_dropped() {
        let alice = Identity::create();
        let bob = Identity::create();
        let key = random_key();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"first".to_vec()).unwrap();
        bob.decrypt(env.clone()).unwrap();
        bob.drop_sessions(alice.curve25519());
        // Survives a pickle round trip too.
        let bob2 = Identity::restore(bob.pickle(key.clone()).unwrap(), key).unwrap();
        assert!(matches!(bob2.decrypt(env), Err(CryptoError::Duplicate)));
    }

    #[test]
    fn crossed_sessions_converge_and_survive_newer_sessions() {
        let alice = Identity::create();
        let bob = Identity::create();
        // Both start a session at the same moment, so each holds two.
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.start_session(peer_keys(&alice), alice.public_bundle().fallback.unwrap()).unwrap();
        let a1 = alice.encrypt(peer_keys(&bob), b"a1".to_vec()).unwrap();
        let b1 = bob.encrypt(peer_keys(&alice), b"b1".to_vec()).unwrap();
        bob.decrypt(a1).unwrap();
        alice.decrypt(b1).unwrap();
        // Alice now answers on Bob's session; Bob moves it to the front and
        // answers on it too, so from here on both use one session.
        let a2 = alice.encrypt(peer_keys(&bob), b"a2".to_vec()).unwrap();
        assert_eq!(bob.decrypt(a2).unwrap().plaintext, b"a2");
        let b2 = bob.encrypt(peer_keys(&alice), b"b2".to_vec()).unwrap();
        assert_eq!(alice.decrypt(b2).unwrap().plaintext, b"b2");
        // Newer sessions on Bob's side (resyncs, say) must not push out the one
        // Alice is still sending on.
        for _ in 0..(MAX_SESSIONS_PER_PEER - 1) {
            bob.start_session(peer_keys(&alice), alice.public_bundle().fallback.unwrap()).unwrap();
        }
        let a3 = alice.encrypt(peer_keys(&bob), b"a3".to_vec()).unwrap();
        assert_eq!(bob.decrypt(a3).unwrap().plaintext, b"a3");
    }

    #[test]
    fn a_session_in_recent_use_is_never_evicted() {
        let (alice, bob) = paired();
        // Someone keeps making Bob start new sessions with Alice (forged
        // resyncs, say): far more than the per-peer limit.
        for _ in 0..(MAX_SESSIONS_PER_PEER * 2) {
            bob.start_session(peer_keys(&alice), alice.public_bundle().fallback.unwrap()).unwrap();
        }
        {
            let inner = bob.inner.lock().unwrap();
            let list = &inner.sessions[&alice.curve25519()];
            // The idle new ones were trimmed; the one Alice uses was kept.
            assert_eq!(list.len(), MAX_SESSIONS_PER_PEER);
            assert_eq!(list.iter().filter(|e| e.last_decrypt_ms.is_some()).count(), 1);
        }
        // Alice is still sending on her session, and Bob still reads it.
        let env = alice.encrypt(peer_keys(&bob), b"still here".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env).unwrap().plaintext, b"still here");

        // A session last used more than 14 days ago is fair game again.
        {
            let mut inner = bob.inner.lock().unwrap();
            for e in inner.sessions.get_mut(&alice.curve25519()).unwrap() {
                if e.last_decrypt_ms.is_some() {
                    e.last_decrypt_ms = Some(now_ms() - SESSION_PROTECT_MS - 1);
                }
            }
        }
        for _ in 0..MAX_SESSIONS_PER_PEER {
            bob.start_session(peer_keys(&alice), alice.public_bundle().fallback.unwrap()).unwrap();
        }
        let inner = bob.inner.lock().unwrap();
        let list = &inner.sessions[&alice.curve25519()];
        assert_eq!(list.len(), MAX_SESSIONS_PER_PEER);
        assert!(list.iter().all(|e| e.last_decrypt_ms.is_none()), "the stale session should have been evicted");
    }

    #[test]
    fn at_the_hard_limit_a_new_inbound_session_evicts_the_least_recently_used() {
        let alice = Identity::create();
        let bob = Identity::create();
        for _ in 0..SESSION_HARD_LIMIT {
            alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
            bob.decrypt(alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap()).unwrap();
        }
        // Give every session a distinct age; the one used longest ago is known.
        let now = now_ms();
        let oldest_id = {
            let mut inner = bob.inner.lock().unwrap();
            let list = inner.sessions.get_mut(&alice.curve25519()).unwrap();
            for (i, e) in list.iter_mut().enumerate() {
                e.last_decrypt_ms = Some(now - 1000 - i as u64);
            }
            list.last().unwrap().session.session_id()
        };
        // Alice starts yet another session: it is kept, and the stalest goes.
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let d = bob.decrypt(alice.encrypt(peer_keys(&bob), b"newest".to_vec()).unwrap()).unwrap();
        assert_eq!(d.plaintext, b"newest");
        assert!(d.new_session);
        assert!(d.evicted_recent_session);
        let inner = bob.inner.lock().unwrap();
        let list = &inner.sessions[&alice.curve25519()];
        assert_eq!(list.len(), SESSION_HARD_LIMIT);
        assert!(list.iter().all(|e| e.session.session_id() != oldest_id), "the least recently used session survived");
    }

    #[test]
    fn protected_sessions_are_kept_past_the_limit_and_the_hard_limit_refuses_new_ones() {
        let alice = Identity::create();
        let bob = Identity::create();
        let now = now_ms();
        // Bob holds the most sessions with Alice it ever will, all recently used.
        for _ in 0..SESSION_HARD_LIMIT {
            alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
            bob.decrypt(alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap()).unwrap();
        }
        {
            let inner = bob.inner.lock().unwrap();
            let list = &inner.sessions[&alice.curve25519()];
            assert_eq!(list.len(), SESSION_HARD_LIMIT);
            assert!(list.iter().all(|e| is_protected(e, now)));
        }
        // Another outbound one is refused, naming the peer, rather than evicting any of them.
        let refused = bob.start_session(peer_keys(&alice), alice.public_bundle().fallback.unwrap());
        match refused {
            Err(CryptoError::SessionLimit { peer_curve25519 }) => assert_eq!(peer_curve25519, alice.curve25519()),
            other => panic!("expected SessionLimit, got {other:?}"),
        }
        assert_eq!(bob.inner.lock().unwrap().sessions[&alice.curve25519()].len(), SESSION_HARD_LIMIT);
    }

    #[test]
    fn session_ages_survive_a_pickle() {
        let (alice, bob) = paired();
        let key = random_key();
        let bob2 = Identity::restore(bob.pickle(key.clone()).unwrap(), key).unwrap();
        for _ in 0..(MAX_SESSIONS_PER_PEER * 2) {
            bob2.start_session(peer_keys(&alice), alice.public_bundle().fallback.unwrap()).unwrap();
        }
        let env = alice.encrypt(peer_keys(&bob2), b"after restore".to_vec()).unwrap();
        assert_eq!(bob2.decrypt(env).unwrap().plaintext, b"after restore");
    }

    #[test]
    fn padding_buckets() {
        // Small payloads: multiples of 160 bytes, marker included.
        assert_eq!(padded_len(0), 160);
        assert_eq!(padded_len(1), 160);
        assert_eq!(padded_len(159), 160);
        assert_eq!(padded_len(160), 320);
        assert_eq!(padded_len(2559), 2560);
        // Then the large buckets.
        assert_eq!(padded_len(2560), 4096);
        assert_eq!(padded_len(4095), 4096);
        assert_eq!(padded_len(4096), 8192);
        assert_eq!(padded_len(20_000), 32_768);
        // A media chunk: 30 KiB as base64 inside its JSON payload.
        assert_eq!(padded_len(30 * 1024 * 4 / 3 + 600), 49_152);
        assert_eq!(padded_len(65_535), 65_536);
        assert_eq!(padded_len(65_536), 131_072);
        // Every length lands in a bucket of its own size or above, and only ever
        // on a bucket boundary.
        for len in 0..70_000 {
            let p = padded_len(len);
            assert!(p > len);
            assert_eq!(padded_len(p - 1), p);
        }
        assert_eq!(padded_length(100), 160);
    }

    #[test]
    fn padding_round_trips_and_rejects_bad_padding() {
        for len in [0usize, 1, 2, 100, 159, 160, 161, 2559, 2560, 5000, 41_000, 65_535, 70_000] {
            let data: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
            let padded = pad(&data);
            assert_eq!(padded.len(), padded_len(len));
            assert_eq!(unpad(&padded).unwrap(), data.as_slice());
        }
        // Trailing zeros in the plaintext itself survive.
        assert_eq!(unpad(&pad(&[1, 0, 0])).unwrap(), &[1, 0, 0]);
        // Not a bucket length.
        assert!(unpad(&[0x80]).is_err());
        assert!(unpad(&[0u8; 161]).is_err());
        // A bucket of zeros has no marker; nor does one ending in another byte.
        assert!(unpad(&[0u8; 160]).is_err());
        let mut no_marker = vec![0u8; 160];
        no_marker[10] = 7;
        assert!(unpad(&no_marker).is_err());
        assert!(unpad(&[]).is_err());
    }

    #[test]
    fn short_payloads_are_indistinguishable_on_the_wire() {
        let (alice, bob) = paired();
        // A receipt, an ICE candidate and a short text all make envelopes of one size.
        let sizes: Vec<usize> = [&b"{\"t\":\"receipt\"}"[..], &[b'x'; 140][..], b"ok"]
            .iter()
            .map(|p| {
                let env = alice.encrypt(peer_keys(&bob), p.to_vec()).unwrap();
                let len = env.len();
                assert_eq!(bob.decrypt(env).unwrap().plaintext, p.to_vec());
                len
            })
            .collect();
        assert!(sizes.windows(2).all(|w| w[0] == w[1]), "envelope sizes differ: {sizes:?}");
        // Up to three blocks of text share that size; past it, one step up.
        let long = alice.encrypt(peer_keys(&bob), vec![b'y'; 400]).unwrap();
        assert_eq!(long.len(), sizes[0]);
        let longer = alice.encrypt(peer_keys(&bob), vec![b'y'; 900]).unwrap();
        assert_eq!(longer.len(), sizes[0] + INNER_V3_STEP);
        assert_eq!(bob.decrypt(long).unwrap().plaintext, vec![b'y'; 400]);
        assert_eq!(bob.decrypt(longer).unwrap().plaintext, vec![b'y'; 900]);
    }

    #[test]
    fn an_unsigned_sender_hint_is_rejected() {
        let (alice, bob) = paired_v2();
        // A normal message Bob cannot read, claiming to be from Alice with no valid signature.
        let real = alice.encrypt(legacy_keys(&bob), b"x".to_vec()).unwrap();
        let (_, inner) = {
            let me = bob.inner.lock().unwrap();
            unseal(&me.sealing_secret, SEAL_INFO_V2, &real).unwrap()
        };
        let ciphertext = parse_inner(&inner).unwrap().ciphertext.to_vec();
        bob.drop_sessions(alice.curve25519());
        let unsigned = forge(&bob, 1, curve_bytes(&alice), ed_bytes(&alice), |_| [0u8; 64], &ciphertext);
        let got = bob.decrypt(unsigned);
        assert!(matches!(got, Err(CryptoError::BadSignature)), "unsigned hint accepted: {got:?}");
        // The real envelope, correctly signed, still names Alice.
        match bob.decrypt(real) {
            Err(CryptoError::NoSessionFor { sender_curve25519, sender_ed25519, .. }) => {
                assert_eq!(sender_curve25519, alice.curve25519());
                assert_eq!(sender_ed25519, alice.ed25519());
            }
            other => panic!("expected NoSessionFor, got {other:?}"),
        }
    }

    #[test]
    fn a_forged_sender_hint_is_rejected() {
        let (alice, bob) = paired_v2();
        let mallory = Identity::create();
        let ciphertext = {
            // Any well-formed Olm normal message will do.
            mallory.start_session(legacy_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
            bob.decrypt(mallory.encrypt(legacy_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
            mallory.decrypt(bob.encrypt(legacy_keys(&mallory), b"hi".to_vec()).unwrap()).unwrap();
            let env = mallory.encrypt(legacy_keys(&bob), b"resync me".to_vec()).unwrap();
            let me = bob.inner.lock().unwrap();
            let (_, inner) = unseal(&me.sealing_secret, SEAL_INFO_V2, &env).unwrap();
            parse_inner(&inner).unwrap().ciphertext.to_vec()
        };
        bob.drop_sessions(alice.curve25519());

        // 1. Mallory signs, honestly, with her own key, but names Alice's Curve25519 key.
        let own_key = forge(&bob, 1, curve_bytes(&alice), ed_bytes(&mallory), signer(&mallory), &ciphertext);
        let got = bob.decrypt(own_key);
        assert!(matches!(got, Err(CryptoError::BadSignature)), "forged pairing accepted: {got:?}");

        // 2. Mallory names Alice's Ed25519 key too, but can only sign with her own.
        let wrong_signer = forge(&bob, 1, curve_bytes(&alice), ed_bytes(&alice), signer(&mallory), &ciphertext);
        let got = bob.decrypt(wrong_signer);
        assert!(matches!(got, Err(CryptoError::BadSignature)), "wrong signer accepted: {got:?}");

        // 3. A signature Alice really made, lifted onto another envelope, is refused.
        let lifted = {
            let real = alice.encrypt(legacy_keys(&bob), b"real".to_vec()).unwrap();
            let me = bob.inner.lock().unwrap();
            let (_, inner) = unseal(&me.sealing_secret, SEAL_INFO_V2, &real).unwrap();
            let sig: [u8; 64] = inner[73..137].try_into().unwrap();
            drop(me);
            forge(&bob, 1, curve_bytes(&alice), ed_bytes(&alice), move |_| sig, &ciphertext)
        };
        let got = bob.decrypt(lifted);
        assert!(matches!(got, Err(CryptoError::BadSignature)), "lifted signature accepted: {got:?}");
    }

    #[test]
    fn a_peer_key_binding_cannot_be_taken_over_by_a_new_identity() {
        // Bob has never seen Alice. Mallory, with her own identity, sends a
        // normal message claiming Alice's Curve25519 key: Bob does not know Alice,
        // so the claim is not refused here, but it names Mallory's key, which
        // the app compares with the contact's before doing anything.
        let alice = Identity::create();
        let bob = Identity::create();
        let mallory = Identity::create();
        mallory.start_session(legacy_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.decrypt(mallory.encrypt(legacy_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        mallory.decrypt(bob.encrypt(legacy_keys(&mallory), b"hi".to_vec()).unwrap()).unwrap();
        let env = mallory.encrypt(legacy_keys(&bob), b"x".to_vec()).unwrap();
        let ciphertext = {
            let me = bob.inner.lock().unwrap();
            let (_, inner) = unseal(&me.sealing_secret, SEAL_INFO_V2, &env).unwrap();
            parse_inner(&inner).unwrap().ciphertext.to_vec()
        };
        let forged = forge(&bob, 1, curve_bytes(&alice), ed_bytes(&mallory), signer(&mallory), &ciphertext);
        match bob.decrypt(forged) {
            Err(CryptoError::NoSessionFor { sender_ed25519, .. }) => assert_eq!(sender_ed25519, mallory.ed25519()),
            other => panic!("expected NoSessionFor naming Mallory's key, got {other:?}"),
        }
        // Nor did the claim teach Bob that Alice's Curve25519 key is Mallory's.
        assert!(!bob.inner.lock().unwrap().peers.contains_key(&alice.curve25519()));
    }

    #[test]
    fn a_curve_key_bound_to_one_identity_is_not_rebound_to_another() {
        // Bob knows Alice. Mallory offers Alice's Curve25519 key as her own,
        // with a binding she signed herself (which is all a relay checks).
        let (alice, bob) = paired();
        let mallory = Identity::create();
        let sealing = mallory.public_bundle().sealing;
        let signature = mallory.inner.lock().unwrap().account.sign(format!("{}|{}", alice.curve25519(), sealing).as_bytes()).to_base64();
        let offered = PeerKeys { ed25519: mallory.ed25519(), curve25519: alice.curve25519(), sealing, signature, sealing_prekey: None };
        match bob.start_session(offered, mallory.public_bundle().fallback.unwrap()) {
            Err(CryptoError::BadKey { .. }) => {}
            other => panic!("expected BadKey, got {other:?}"),
        }
        // Bob still knows the key as Alice's, and still reads her.
        assert_eq!(bob.inner.lock().unwrap().peers.get(&alice.curve25519()), Some(&alice.ed25519()));
        let got = bob.decrypt(alice.encrypt(peer_keys(&bob), b"still me".to_vec()).unwrap()).unwrap();
        assert_eq!(got.plaintext, b"still me");
    }

    #[test]
    fn a_first_message_with_bad_padding_still_leaves_a_usable_session() {
        // Alice's first message to Bob is built on one of his one-time keys but
        // its plaintext is not padded: the key is spent either way, so the
        // session must be kept, or every later message from Alice would ask
        // for a key Bob no longer has.
        let alice = Identity::create();
        let bob = Identity::create();
        bob.generate_one_time_keys(1);
        let key = bob.public_bundle().one_time_keys[0].clone();
        bob.mark_keys_published();
        alice.start_session(peer_keys(&bob), key).unwrap();
        let (message_type, ciphertext) = {
            let mut me = alice.inner.lock().unwrap();
            let session = &mut me.sessions.get_mut(&bob.curve25519()).unwrap()[0].session;
            let (t, c) = session.encrypt(b"not padded").unwrap().to_parts();
            (t as u8, c)
        };
        let malformed = forge(&bob, message_type, curve_bytes(&alice), ed_bytes(&alice), signer(&alice), &ciphertext);
        match bob.decrypt(malformed) {
            Err(CryptoError::Decrypt { .. }) => {}
            other => panic!("expected a decrypt error for bad padding, got {other:?}"),
        }
        assert!(bob.has_session(alice.curve25519()), "the session was not kept");
        let next = bob.decrypt(alice.encrypt(peer_keys(&bob), b"hello".to_vec()).unwrap()).unwrap();
        assert_eq!(next.plaintext, b"hello");
        assert!(!next.new_session);
    }

    #[test]
    fn pruning_keeps_only_the_listed_peers() {
        let alice = Identity::create();
        let bob = Identity::create();
        let mallory = Identity::create();
        for sender in [&alice, &mallory] {
            sender.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
            bob.decrypt(sender.encrypt(peer_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        }
        assert_eq!(bob.prune_sessions(vec![alice.curve25519()]), 1);
        assert!(bob.has_session(alice.curve25519()));
        assert!(!bob.has_session(mallory.curve25519()));
        let inner = bob.inner.lock().unwrap();
        assert!(inner.peers.contains_key(&alice.curve25519()));
        assert!(!inner.peers.contains_key(&mallory.curve25519()));
    }

    #[test]
    fn pickles_without_the_replay_list_still_restore() {
        // Decrypt a current pickle, take an optional field out and re-encrypt it.
        let alice = Identity::create();
        let key = random_key();
        let blob = B64.decode(alice.pickle(key.clone()).unwrap()).unwrap();
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
        let plain = cipher.decrypt(Nonce::from_slice(&blob[..12]), &blob[12..]).unwrap();
        let mut json: serde_json::Value = serde_json::from_slice(&plain).unwrap();
        assert!(json.as_object_mut().unwrap().remove("used_prekeys").is_some());
        let reduced = serde_json::to_vec(&json).unwrap();
        let nonce = [7u8; 12];
        let mut out = nonce.to_vec();
        out.extend(cipher.encrypt(Nonce::from_slice(&nonce), reduced.as_slice()).unwrap());
        let restored = Identity::restore(B64.encode(&out), key.clone()).unwrap();
        assert_eq!(restored.ed25519(), alice.ed25519());

        // A version-1 pickle is refused: there is no installed base to migrate.
        json.as_object_mut().unwrap().insert("v".into(), 1.into());
        let old = serde_json::to_vec(&json).unwrap();
        let mut out = nonce.to_vec();
        out.extend(cipher.encrypt(Nonce::from_slice(&nonce), old.as_slice()).unwrap());
        assert!(matches!(Identity::restore(B64.encode(out), key), Err(CryptoError::BadPickle { .. })));
    }

    #[test]
    fn envelope_is_opaque_to_third_parties() {
        let alice = Identity::create();
        let bob = Identity::create();
        let eve = Identity::create();
        let key = bob.public_bundle().fallback.unwrap();
        alice.start_session(peer_keys(&bob), key).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"secret".to_vec()).unwrap();
        // The sealed envelope carries nothing but a version byte and an ephemeral key in the clear.
        assert_eq!(env[0], ENVELOPE_V3);
        assert!(!env.windows(6).any(|w| w == b"secret"));
        let alice_curve = curve_bytes(&alice);
        let alice_ed = ed_bytes(&alice);
        let alice_sealing = sealing_bytes(&alice);
        assert!(!env.windows(32).any(|w| w == alice_curve || w == alice_ed || w == alice_sealing), "the sender's keys are visible");
        assert!(eve.decrypt(env.clone()).is_err());
        // Tampering is detected.
        let mut bad = env.clone();
        let last = bad.len() - 1;
        bad[last] ^= 1;
        assert!(bob.decrypt(bad).is_err());
    }

    #[test]
    fn an_envelope_of_the_old_format_is_refused() {
        let (alice, bob) = paired();
        let mut env = alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap();
        env[0] = 1;
        assert!(matches!(bob.decrypt(env), Err(CryptoError::Envelope { .. })));
    }

    #[test]
    fn forged_keys_are_rejected() {
        let alice = Identity::create();
        let bob = Identity::create();
        let mallory = Identity::create();
        let mut peer = peer_keys(&bob);
        // Swap in Mallory's sealing key: the binding signature no longer matches.
        peer.sealing = mallory.public_bundle().sealing;
        let key = bob.public_bundle().fallback.unwrap();
        assert!(matches!(alice.start_session(peer, key), Err(CryptoError::BadSignature)));
        // A one-time key signed by someone else is refused too.
        let mut key = bob.public_bundle().fallback.unwrap();
        key.signature = mallory.sign(key.key.as_bytes().to_vec());
        assert!(matches!(alice.start_session(peer_keys(&bob), key), Err(CryptoError::BadSignature)));
    }

    #[test]
    fn pickle_restores_sessions() {
        let alice = Identity::create();
        let bob = Identity::create();
        let key = random_key();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"before pickle".to_vec()).unwrap();
        bob.decrypt(env).unwrap();

        let alice2 = Identity::restore(alice.pickle(key.clone()).unwrap(), key.clone()).unwrap();
        let bob2 = Identity::restore(bob.pickle(key.clone()).unwrap(), key.clone()).unwrap();
        assert_eq!(alice2.ed25519(), alice.ed25519());
        assert!(alice2.has_session(bob.curve25519()));
        let env = alice2.encrypt(peer_keys(&bob2), b"after pickle".to_vec()).unwrap();
        assert_eq!(bob2.decrypt(env).unwrap().plaintext, b"after pickle");

        // Wrong key: refused, nothing leaks.
        let other = random_key();
        assert!(matches!(Identity::restore(alice.pickle(key).unwrap(), other), Err(CryptoError::BadPickle { .. })));
    }

    #[test]
    fn safety_number_is_symmetric_and_stable() {
        let alice = Identity::create();
        let bob = Identity::create();
        let a = alice.safety_number(bob.ed25519()).unwrap();
        let b = bob.safety_number(alice.ed25519()).unwrap();
        assert_eq!(a, b);
        assert_eq!(a.len(), 12 * 5 + 11);
        assert_eq!(safety_number_for(&alice.ed25519(), &bob.ed25519()), a);
        assert_ne!(a, Identity::create().safety_number(bob.ed25519()).unwrap());
    }

    #[test]
    fn verify_checks_signatures() {
        let alice = Identity::create();
        let sig = alice.sign(b"bundle".to_vec());
        assert!(verify(alice.ed25519(), b"bundle".to_vec(), sig.clone()));
        assert!(!verify(alice.ed25519(), b"other".to_vec(), sig));
        assert!(!verify("not a key".into(), b"bundle".to_vec(), "x".into()));
    }

    #[test]
    fn secret_material_is_wiped_on_drop() {
        // Compile-time: the types holding secret bytes zeroize when dropped.
        fn wipes<T: ZeroizeOnDrop>() {}
        wipes::<SecretBytes>();
        wipes::<Zeroizing<[u8; 32]>>();
        wipes::<Zeroizing<Vec<u8>>>();
        // And explicitly wiping one leaves nothing behind.
        let mut s = SecretBytes([0xAB; 32]);
        s.zeroize();
        assert_eq!(s.0, [0u8; 32]);
    }

    /// One Curve25519 key has one spelling here: X25519 ignores the top bit and
    /// base64 forgives padding, and either would let a key be bound twice.
    #[test]
    fn a_curve_key_has_one_spelling() {
        let (alice, bob) = paired();
        let mut keys = peer_keys(&alice);
        // Padded: the same key, under the same name.
        let mut raw = B64.decode(format!("{}=", keys.curve25519)).unwrap();
        assert_eq!(curve_name(&format!("{}=", keys.curve25519)).unwrap(), keys.curve25519);
        // The top bit set: refused outright.
        raw[31] |= 0x80;
        assert!(curve_from(&B64.encode(&raw)).is_err());
        assert!(curve_from(&B64.encode(&raw).trim_end_matches('=')).is_err());
        // The field prime and above: refused.
        let mut p = [0xffu8; 32];
        p[0] = 0xed;
        p[31] = 0x7f;
        assert!(!canonical_x25519(&p));
        p[0] = 0xec;
        assert!(canonical_x25519(&p));
        // Sessions are kept under the one spelling, so either spelling finds them.
        bob.drop_sessions(format!("{}=", keys.curve25519));
        keys.curve25519 = alice.curve25519();
        assert!(matches!(bob.encrypt(keys, b"gone".to_vec()), Err(CryptoError::NoSession)));
    }

    /// Mallory offering Alice's Curve25519 key with her own identity and sealing
    /// key gets nothing sealed for her over Bob's session with Alice.
    #[test]
    fn encrypt_keeps_a_curve_key_to_the_identity_it_belongs_to() {
        let (alice, bob) = paired();
        let mallory = Identity::create();
        let m = mallory.public_bundle();
        let a = alice.public_bundle();
        let signature = mallory.sign(format!("{}|{}", a.curve25519, m.sealing).into_bytes());
        let mixed = PeerKeys { ed25519: m.ed25519, curve25519: a.curve25519, sealing: m.sealing, signature, sealing_prekey: m.sealing_prekey };
        assert!(matches!(bob.encrypt(mixed, b"for alice".to_vec()), Err(CryptoError::BadKey { .. })));
    }

    /// A low-order sealing key, or a low-order ephemeral key in an envelope,
    /// would make the box's key one anyone can compute.
    #[test]
    fn low_order_points_are_refused_by_the_sealed_box() {
        let low = XPublicKey::from([0u8; 32]);
        assert!(seal(ENVELOPE_V3, SEAL_INFO_V3, &low, |_| Ok(Zeroizing::new(vec![1, 2, 3]))).is_err());
        let bob = Identity::create();
        for version in [ENVELOPE_V2, ENVELOPE_V3] {
            let mut envelope = vec![version];
            envelope.extend_from_slice(&[0u8; 32]);
            envelope.extend_from_slice(&[0u8; 64]);
            assert!(matches!(bob.decrypt(envelope), Err(CryptoError::Envelope { .. })));
        }
    }

    // ── Envelope format 3 ─────────────────────────────────────────────────

    #[test]
    fn envelopes_are_sealed_to_the_prekey_and_padded_to_whole_steps() {
        let (alice, bob) = paired();
        let env = alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap();
        assert_eq!(env[0], ENVELOPE_V3);
        assert_eq!((env.len() - 1 - 32 - 16) % INNER_V3_STEP, 0);
        let d = bob.decrypt(env).unwrap();
        assert!(d.sealed_to_prekey);
        assert_eq!(d.sender_curve25519, alice.curve25519());
        assert_eq!(d.sender_ed25519, alice.ed25519());
        // The long-term sealing key no longer opens it: only the prekey does.
        let env = alice.encrypt(peer_keys(&bob), b"y".to_vec()).unwrap();
        let me = bob.inner.lock().unwrap();
        assert!(unseal(&me.sealing_secret, SEAL_INFO_V3, &env).is_err());
    }

    #[test]
    fn a_message_that_starts_a_session_looks_like_one_that_continues_it() {
        let alice = Identity::create();
        let bob = Identity::create();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let first = alice.encrypt(peer_keys(&bob), b"hello".to_vec()).unwrap();
        assert!(bob.decrypt(first.clone()).unwrap().new_session);
        let reply = bob.encrypt(peer_keys(&alice), b"hello".to_vec()).unwrap();
        assert!(!alice.decrypt(reply.clone()).unwrap().new_session);
        assert_eq!(first.len(), reply.len(), "a pre-key message stands out by its length");
    }

    #[test]
    fn envelopes_of_format_2_still_open() {
        let (alice, bob) = paired_v2();
        let env = alice.encrypt(legacy_keys(&bob), b"from an older app".to_vec()).unwrap();
        assert_eq!(env[0], ENVELOPE_V2);
        let d = bob.decrypt(env).unwrap();
        assert_eq!(d.plaintext, b"from an older app");
        assert!(!d.sealed_to_prekey);
    }

    #[test]
    fn a_peer_seen_with_a_prekey_is_never_sealed_to_the_old_way() {
        let (alice, bob) = paired();
        // Keys served without the prekey (by a relay, say) do not make Alice
        // fall back to sealing to Bob's long-term key.
        match alice.encrypt(legacy_keys(&bob), b"x".to_vec()) {
            Err(CryptoError::StalePrekey { peer_curve25519 }) => assert_eq!(peer_curve25519, bob.curve25519()),
            other => panic!("expected StalePrekey, got {other:?}"),
        }
        // And that holds across a pickle.
        let key = random_key();
        let alice = Identity::restore(alice.pickle(key.clone()).unwrap(), key).unwrap();
        assert!(matches!(alice.encrypt(legacy_keys(&bob), b"x".to_vec()), Err(CryptoError::StalePrekey { .. })));
        assert!(alice.encrypt(peer_keys(&bob), b"x".to_vec()).is_ok());
    }

    #[test]
    fn a_stale_or_forged_prekey_is_refused() {
        let (alice, bob) = paired();
        let mallory = Identity::create();
        let signed = |by: &Identity, key: &str, created: i64| SealingPrekey {
            key: key.to_string(),
            created_ms: created,
            signature: by.sign(prekey_statement(key, created).into_bytes()),
        };
        let current = bob.public_bundle().sealing_prekey.unwrap();
        let now = now_ms() as i64;
        let day = 24 * 60 * 60 * 1000;
        let with = |p: SealingPrekey| PeerKeys { sealing_prekey: Some(p), ..peer_keys(&bob) };
        // Too old to seal to, or from too far in the future: fetch again.
        for created in [now - 15 * day, now + 2 * day] {
            let got = alice.encrypt(with(signed(&bob, &current.key, created)), b"x".to_vec());
            assert!(matches!(got, Err(CryptoError::StalePrekey { .. })), "{created}: {got:?}");
        }
        // Signed by someone else, a key swapped under Bob's signature, or a
        // time changed under it: refused outright.
        let mallory_key = mallory.public_bundle().sealing_prekey.unwrap().key;
        let forged = [
            signed(&mallory, &mallory_key, now),
            SealingPrekey { key: mallory_key.clone(), ..current.clone() },
            SealingPrekey { created_ms: current.created_ms + 1, ..current.clone() },
        ];
        for p in forged {
            assert!(matches!(alice.encrypt(with(p), b"x".to_vec()), Err(CryptoError::BadSignature)));
        }
        // A low-order key is no prekey.
        let low = B64.encode([0u8; 32]);
        assert!(alice.encrypt(with(signed(&bob, &low, now)), b"x".to_vec()).is_err());
    }

    #[test]
    fn a_replaced_prekey_still_opens_until_pruned_and_never_after() {
        let (alice, bob) = paired();
        let old = peer_keys(&bob);
        assert!(!bob.sealing_prekey_due());
        bob.rotate_sealing_prekey();
        assert_ne!(bob.public_bundle().sealing_prekey, old.sealing_prekey);
        // Alice still has the old prekey: what she seals to it opens.
        let on_the_way = alice.encrypt(old.clone(), b"sealed to the old one".to_vec()).unwrap();
        let logged = alice.encrypt(old, b"logged at the relay".to_vec()).unwrap();
        assert_eq!(bob.decrypt(on_the_way).unwrap().plaintext, b"sealed to the old one");
        assert_eq!(bob.prune_sealing_prekeys(now_ms() as i64), 0, "too soon: senders may still seal to it");

        // Two weeks and three days after it was made, with the mailbox emptied
        // since, nothing more can arrive for it, and it goes.
        let day = 24 * 60 * 60 * 1000u64;
        {
            let mut me = bob.inner.lock().unwrap();
            me.prekeys[1].created_ms = now_ms() - 18 * day;
            me.prekeys[1].superseded_ms = Some(now_ms() - 11 * day);
        }
        assert_eq!(bob.prune_sealing_prekeys(0), 0, "the mailbox has not been emptied since");
        assert_eq!(bob.prune_sealing_prekeys((now_ms() - 2 * day) as i64), 0, "emptied before the last envelope could arrive");
        assert_eq!(bob.prune_sealing_prekeys(now_ms() as i64), 1);
        assert_eq!(bob.inner.lock().unwrap().prekeys.len(), 1, "the current prekey is kept");
        // The envelope logged at the relay can no longer be opened, by anyone.
        assert!(matches!(bob.decrypt(logged), Err(CryptoError::Envelope { .. })));
        // New mail, sealed to the new prekey, is unaffected.
        let got = bob.decrypt(alice.encrypt(peer_keys(&bob), b"new".to_vec()).unwrap()).unwrap();
        assert_eq!(got.plaintext, b"new");

        // Without the mailbox emptied, a replaced prekey goes 30 days after it was replaced.
        bob.rotate_sealing_prekey();
        bob.inner.lock().unwrap().prekeys[1].superseded_ms = Some(now_ms() - 31 * day);
        assert_eq!(bob.prune_sealing_prekeys(0), 1);
    }

    #[test]
    fn a_week_old_prekey_is_due_for_rotation() {
        let bob = Identity::create();
        assert!(!bob.sealing_prekey_due());
        bob.inner.lock().unwrap().prekeys[0].created_ms = now_ms() - 8 * 24 * 60 * 60 * 1000;
        assert!(bob.sealing_prekey_due());
        bob.rotate_sealing_prekey();
        assert!(!bob.sealing_prekey_due());
        for _ in 0..20 {
            bob.rotate_sealing_prekey();
        }
        assert!(bob.inner.lock().unwrap().prekeys.len() <= MAX_PREKEYS);
    }

    /// The claim convinces the recipient and no one else: everything Bob can
    /// check, Bob could have made himself from his prekey and Alice's public
    /// keys. A signature, as in format 2, only Alice could have made.
    #[test]
    fn the_sender_claim_is_deniable() {
        let (alice, bob) = paired();
        let ciphertext = olm_ciphertext_v3(&bob, &alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap());
        let shared = {
            let me = bob.inner.lock().unwrap();
            *me.prekeys[0].secret.diffie_hellman(&XPublicKey::from(sealing_bytes(&alice))).as_bytes()
        };
        bob.drop_sessions(alice.curve25519());
        let made_by_bob =
            forge_v3(&bob, 1, curve_bytes(&alice), ed_bytes(&alice), sealing_bytes(&alice), binding_bytes(&alice), shared, &ciphertext);
        match bob.decrypt(made_by_bob) {
            // The claim passed every check and names Alice.
            Err(CryptoError::NoSessionFor { sender_curve25519, sender_ed25519, .. }) => {
                assert_eq!(sender_curve25519, alice.curve25519());
                assert_eq!(sender_ed25519, alice.ed25519());
            }
            other => panic!("expected the claim to check out, got {other:?}"),
        }
    }

    #[test]
    fn a_forged_format_3_claim_is_refused() {
        let (alice, bob) = paired();
        let mallory = Identity::create();
        let ciphertext = olm_ciphertext_v3(&bob, &alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap());
        bob.drop_sessions(alice.curve25519());
        let prekey = x25519_from(&bob.public_bundle().sealing_prekey.unwrap().key).unwrap();
        let mallory_shared = *mallory.inner.lock().unwrap().sealing_secret.diffie_hellman(&prekey).as_bytes();
        let refused = |env: Vec<u8>, what: &str| {
            let got = bob.decrypt(env);
            assert!(matches!(got, Err(CryptoError::BadSignature)), "{what}: {got:?}");
        };
        // 1. Alice's keys and binding, but a MAC from Mallory's sealing key.
        refused(
            forge_v3(&bob, 1, curve_bytes(&alice), ed_bytes(&alice), sealing_bytes(&alice), binding_bytes(&alice), mallory_shared, &ciphertext),
            "MAC from another key",
        );
        // 2. Mallory's sealing key under Alice's binding signature.
        refused(
            forge_v3(&bob, 1, curve_bytes(&alice), ed_bytes(&alice), sealing_bytes(&mallory), binding_bytes(&alice), mallory_shared, &ciphertext),
            "sealing key not Alice's",
        );
        // 3. Mallory's own binding, claiming Alice's Curve25519 key.
        let binding: [u8; 64] = base64::engine::general_purpose::STANDARD_NO_PAD
            .decode(mallory.sign(format!("{}|{}", alice.curve25519(), mallory.public_bundle().sealing).into_bytes()))
            .unwrap()
            .try_into()
            .unwrap();
        refused(
            forge_v3(&bob, 1, curve_bytes(&alice), ed_bytes(&mallory), sealing_bytes(&mallory), binding, mallory_shared, &ciphertext),
            "Alice's Curve25519 key re-paired",
        );
    }

    #[test]
    fn prekeys_survive_a_pickle_and_identities_from_before_them_restore() {
        let (alice, bob) = paired();
        let old = peer_keys(&bob);
        bob.rotate_sealing_prekey();
        let key = random_key();
        let bob = Identity::restore(bob.pickle(key.clone()).unwrap(), key.clone()).unwrap();
        assert_eq!(bob.inner.lock().unwrap().prekeys.len(), 2);
        assert_eq!(bob.decrypt(alice.encrypt(old, b"old".to_vec()).unwrap()).unwrap().plaintext, b"old");

        // A version-2 pickle: no prekeys. It restores, offers none, and is due one.
        let blob = B64.decode(bob.pickle(key.clone()).unwrap()).unwrap();
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
        let plain = cipher.decrypt(Nonce::from_slice(&blob[..12]), &blob[12..]).unwrap();
        let mut json: serde_json::Value = serde_json::from_slice(&plain).unwrap();
        let o = json.as_object_mut().unwrap();
        o.remove("prekeys").unwrap();
        o.remove("prekey_peers").unwrap();
        o.insert("v".into(), 2.into());
        let nonce = [9u8; 12];
        let mut out = nonce.to_vec();
        out.extend(cipher.encrypt(Nonce::from_slice(&nonce), serde_json::to_vec(&json).unwrap().as_slice()).unwrap());
        let restored = Identity::restore(B64.encode(out), key).unwrap();
        assert!(restored.public_bundle().sealing_prekey.is_none());
        assert!(restored.sealing_prekey_due());
        // Format-2 mail still reaches it, and once it rotates, format 3 does.
        let (carol, _) = paired_v2();
        carol.start_session(legacy_keys(&restored), restored.public_bundle().fallback.unwrap()).unwrap();
        assert!(!restored.decrypt(carol.encrypt(legacy_keys(&restored), b"v2".to_vec()).unwrap()).unwrap().sealed_to_prekey);
        restored.rotate_sealing_prekey();
        assert!(restored.decrypt(carol.encrypt(peer_keys(&restored), b"v3".to_vec()).unwrap()).unwrap().sealed_to_prekey);
    }

    #[test]
    fn a_format_3_inner_message_must_be_exact() {
        let mut inner = vec![0u8; INNER_V3_STEP];
        inner[201..205].copy_from_slice(&10u32.to_be_bytes());
        assert!(parse_inner_v3(&inner).is_ok());
        // Not a whole step, a length past the end or of zero, or padding that is not zeros.
        assert!(parse_inner_v3(&inner[..INNER_V3_STEP - 1]).is_err());
        let mut long = inner.clone();
        long[201..205].copy_from_slice(&(INNER_V3_STEP as u32).to_be_bytes());
        assert!(parse_inner_v3(&long).is_err());
        let mut zero = inner.clone();
        zero[201..205].copy_from_slice(&0u32.to_be_bytes());
        assert!(parse_inner_v3(&zero).is_err());
        let mut dirty = inner.clone();
        dirty[INNER_V3_STEP - 1] = 1;
        assert!(parse_inner_v3(&dirty).is_err());
    }
}
