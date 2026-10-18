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

/// Envelope format 2: signed sender claim, binary inner layout, padded plaintext.
const ENVELOPE_VERSION: u8 = 2;
const SEAL_INFO: &[u8] = b"clairix-comms-seal-v2";
/// Domain separation for the sender's signature over its claim.
const SENDER_SIG_CONTEXT: &[u8] = b"clairix-comms-sender-v2";

/// What sits inside the sealed box, in this order:
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

/// The parsed inner layout of an opened envelope; see [`INNER_HEADER`].
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

/// `crypto_box_seal`-style anonymous box: ephemeral X25519 with the recipient's
/// static key, HKDF over the shared secret bound to both public keys, then
/// ChaCha20-Poly1305 with a zero nonce (the key is single-use). `inner` is
/// handed the ephemeral public key so the sender can bind its claim to it.
fn seal(recipient: &XPublicKey, inner: impl FnOnce(&[u8; 32]) -> Zeroizing<Vec<u8>>) -> Vec<u8> {
    let eph = StaticSecret::random_from_rng(&mut rand::thread_rng());
    let eph_pub = XPublicKey::from(&eph);
    let plaintext = inner(eph_pub.as_bytes());
    let shared = eph.diffie_hellman(recipient);
    let key = seal_key(shared.as_bytes(), eph_pub.as_bytes(), recipient.as_bytes());
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
    let ct = cipher
        .encrypt(Nonce::from_slice(&[0u8; 12]), plaintext.as_slice())
        .expect("ChaCha20-Poly1305 encryption cannot fail on in-memory data");
    let mut out = Vec::with_capacity(1 + 32 + ct.len());
    out.push(ENVELOPE_VERSION);
    out.extend_from_slice(eph_pub.as_bytes());
    out.extend_from_slice(&ct);
    out
}

/// Opens a sealed box; returns the ephemeral public key with the plaintext.
fn unseal(secret: &StaticSecret, envelope: &[u8]) -> Result<([u8; 32], Zeroizing<Vec<u8>>)> {
    if envelope.len() < 1 + 32 + 16 {
        return Err(CryptoError::Envelope { reason: "too short".into() });
    }
    if envelope[0] != ENVELOPE_VERSION {
        return Err(CryptoError::Envelope { reason: format!("unknown version {}", envelope[0]) });
    }
    let mut eph_bytes = [0u8; 32];
    eph_bytes.copy_from_slice(&envelope[1..33]);
    let eph_pub = XPublicKey::from(eph_bytes);
    let me = XPublicKey::from(secret);
    let shared = secret.diffie_hellman(&eph_pub);
    let key = seal_key(shared.as_bytes(), eph_pub.as_bytes(), me.as_bytes());
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
    let plain = cipher
        .decrypt(Nonce::from_slice(&[0u8; 12]), &envelope[33..])
        .map_err(|_| CryptoError::Envelope { reason: "not addressed to this device, or tampered".into() })?;
    Ok((eph_bytes, Zeroizing::new(plain)))
}

fn seal_key(shared: &[u8], eph_pub: &[u8], recipient_pub: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut salt = Vec::with_capacity(64);
    salt.extend_from_slice(eph_pub);
    salt.extend_from_slice(recipient_pub);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let mut key = Zeroizing::new([0u8; 32]);
    hk.expand(SEAL_INFO, key.as_mut()).expect("32 bytes is a valid HKDF output length");
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
}

/// Pickle format of [`State`]. Version 1 had no session ages or peer bindings.
const STATE_VERSION: u8 = 2;

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
        if state.v != STATE_VERSION {
            return Err(CryptoError::BadPickle { reason: format!("unknown version {}", state.v) });
        }
        let State { account, sealing_secret, sessions, used_prekeys, peers, .. } = state;
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
        };
        let plain = Zeroizing::new(
            serde_json::to_vec(&state).map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?,
        );
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
        PublicBundle {
            ed25519: inner.account.ed25519_key().to_base64(),
            curve25519: curve,
            sealing,
            signature,
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
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        if inner.sessions.get(&peer.curve25519).is_some_and(|list| at_hard_limit(list, now)) {
            return Err(CryptoError::SessionLimit { peer_curve25519: peer.curve25519 });
        }
        let session = inner
            .account
            .create_outbound_session(SessionConfig::version_2(), their_identity, their_key)
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        inner.peers.insert(peer.curve25519.clone(), ed.to_base64());
        push_session(&mut inner.sessions, peer.curve25519, SessionEntry { session, last_decrypt_ms: None }, now);
        Ok(())
    }

    /// Forgets every session with `peer` (they re-paired, or the app is resetting).
    pub fn drop_sessions(&self, peer_curve25519: String) {
        self.inner.lock().expect("identity mutex poisoned").sessions.remove(&peer_curve25519);
    }

    /// Drops the sessions (and key bindings) of every peer not in `keep` and
    /// returns how many peers' sessions were dropped. Any pre-key message to the
    /// fallback key creates a session before the app knows who sent it; sessions
    /// from senders that never became contacts would otherwise pile up in every
    /// saved state.
    pub fn prune_sessions(&self, keep: Vec<String>) -> u32 {
        let keep: HashSet<String> = keep.into_iter().collect();
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let before = inner.sessions.len();
        inner.sessions.retain(|peer, _| keep.contains(peer));
        inner.peers.retain(|peer, _| keep.contains(peer));
        (before - inner.sessions.len()) as u32
    }

    /// Pads `plaintext`, encrypts it for `peer` and seals it for the relay,
    /// with this identity's signed sender claim inside. Requires a session.
    pub fn encrypt(&self, peer: PeerKeys, plaintext: Vec<u8>) -> Result<Vec<u8>> {
        verify_peer(&peer)?;
        let sealing = x25519_from(&peer.sealing)?;
        let plaintext = Zeroizing::new(plaintext);
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let my_curve = inner.account.curve25519_key();
        let my_ed = inner.account.ed25519_key();
        let session = inner
            .sessions
            .get_mut(&peer.curve25519)
            .and_then(|s| s.first_mut())
            .ok_or(CryptoError::NoSession)?;
        let message = session
            .session
            .encrypt(pad(&plaintext).as_slice())
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        let (message_type, ciphertext) = message.to_parts();
        let message_type = message_type as u8;
        let sent_at = now_ms();
        let account = &inner.account;
        Ok(seal(&sealing, |eph_pub| {
            let claim = sender_claim(eph_pub, sealing.as_bytes(), message_type, my_curve.as_bytes(), sent_at, &ciphertext);
            let signature = account.sign(&claim).to_bytes();
            let mut out = Zeroizing::new(Vec::with_capacity(INNER_HEADER + ciphertext.len()));
            out.push(message_type);
            out.extend_from_slice(my_curve.as_bytes());
            out.extend_from_slice(my_ed.as_bytes());
            out.extend_from_slice(&sent_at.to_be_bytes());
            out.extend_from_slice(&signature);
            out.extend_from_slice(&ciphertext);
            out
        }))
    }

    /// Opens an envelope addressed to this device. The sender claim inside is
    /// checked first: it must be signed by the Ed25519 key it names, for this
    /// envelope, and that key must be the one this device knows for the named
    /// Curve25519 key. Only then can a failure name the sender. A pre-key
    /// message from an unknown session creates one; the sender's identity keys
    /// are returned so the app can check them against a known contact before
    /// trusting the content.
    pub fn decrypt(&self, envelope: Vec<u8>) -> Result<Decrypted> {
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let now = now_ms();
        let (eph_pub, inner_bytes) = unseal(&inner.sealing_secret, &envelope)?;
        let view = parse_inner(&inner_bytes)?;
        let my_sealing = XPublicKey::from(&inner.sealing_secret);

        // The claim: who says they sent this, and their proof.
        let sender_identity =
            Curve25519PublicKey::from_slice(&view.sender_curve).map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;
        let sender_ed = Ed25519PublicKey::from_slice(&view.sender_ed).map_err(|_| CryptoError::BadSignature)?;
        let signature = Ed25519Signature::from_slice(&view.signature).map_err(|_| CryptoError::BadSignature)?;
        let claim = sender_claim(
            &eph_pub,
            my_sealing.as_bytes(),
            view.message_type,
            &view.sender_curve,
            view.sent_at_ms,
            view.ciphertext,
        );
        sender_ed.verify(&claim, &signature).map_err(|_| CryptoError::BadSignature)?;
        let curve_b64 = sender_identity.to_base64();
        let ed_b64 = sender_ed.to_base64();
        // A key pair this device already knows must not be re-paired by a claim.
        if inner.peers.get(&curve_b64).is_some_and(|known| *known != ed_b64) {
            return Err(CryptoError::BadSignature);
        }
        let sent_at_ms = i64::try_from(view.sent_at_ms).unwrap_or(i64::MAX);

        let olm = OlmMessage::from_parts(view.message_type as usize, view.ciphertext)
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
                let padded = Zeroizing::new(created.plaintext);
                let plaintext = unpad(&padded)?.to_vec();
                // Olm has now authenticated the Curve25519 key, and the claim's
                // signature the Ed25519 key: from here on they belong together.
                inner.peers.insert(curve_b64.clone(), ed_b64.clone());
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
                Ok(Decrypted {
                    sender_curve25519: curve_b64,
                    sender_ed25519: ed_b64,
                    sent_at_ms,
                    plaintext,
                    new_session: true,
                    evicted_recent_session,
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

fn curve_from(b64: &str) -> Result<Curve25519PublicKey> {
    Curve25519PublicKey::from_base64(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })
}

fn ed25519_from(b64: &str) -> Result<Ed25519PublicKey> {
    Ed25519PublicKey::from_base64(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })
}

fn x25519_from(b64: &str) -> Result<XPublicKey> {
    let bytes = B64.decode(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })?;
    let arr: [u8; 32] =
        bytes.try_into().map_err(|_| CryptoError::BadKey { reason: "sealing key must be 32 bytes".into() })?;
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
        PeerKeys { ed25519: b.ed25519, curve25519: b.curve25519, sealing: b.sealing, signature: b.signature }
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
        seal(&sealing, |eph| {
            let claim = sender_claim(eph, sealing.as_bytes(), message_type, &curve, sent_at, ciphertext);
            let sig = sign(&claim);
            let mut out = Zeroizing::new(Vec::new());
            out.push(message_type);
            out.extend_from_slice(&curve);
            out.extend_from_slice(&ed);
            out.extend_from_slice(&sent_at.to_be_bytes());
            out.extend_from_slice(&sig);
            out.extend_from_slice(ciphertext);
            out
        })
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
        // A longer text moves up exactly one bucket.
        let long = alice.encrypt(peer_keys(&bob), vec![b'y'; 200]).unwrap();
        assert_eq!(long.len(), sizes[0] + PAD_BLOCK);
    }

    #[test]
    fn an_unsigned_sender_hint_is_rejected() {
        let (alice, bob) = paired();
        // A normal message Bob cannot read, claiming to be from Alice with no valid signature.
        let real = alice.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap();
        let (_, inner) = {
            let me = bob.inner.lock().unwrap();
            unseal(&me.sealing_secret, &real).unwrap()
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
        let (alice, bob) = paired();
        let mallory = Identity::create();
        let ciphertext = {
            // Any well-formed Olm normal message will do.
            mallory.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
            bob.decrypt(mallory.encrypt(peer_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
            mallory.decrypt(bob.encrypt(peer_keys(&mallory), b"hi".to_vec()).unwrap()).unwrap();
            let env = mallory.encrypt(peer_keys(&bob), b"resync me".to_vec()).unwrap();
            let me = bob.inner.lock().unwrap();
            let (_, inner) = unseal(&me.sealing_secret, &env).unwrap();
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
            let real = alice.encrypt(peer_keys(&bob), b"real".to_vec()).unwrap();
            let me = bob.inner.lock().unwrap();
            let (_, inner) = unseal(&me.sealing_secret, &real).unwrap();
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
        mallory.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.decrypt(mallory.encrypt(peer_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        mallory.decrypt(bob.encrypt(peer_keys(&mallory), b"hi".to_vec()).unwrap()).unwrap();
        let env = mallory.encrypt(peer_keys(&bob), b"x".to_vec()).unwrap();
        let ciphertext = {
            let me = bob.inner.lock().unwrap();
            let (_, inner) = unseal(&me.sealing_secret, &env).unwrap();
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
        assert_eq!(env[0], ENVELOPE_VERSION);
        assert!(!env.windows(6).any(|w| w == b"secret"));
        let alice_curve = curve_bytes(&alice);
        let alice_ed = ed_bytes(&alice);
        assert!(!env.windows(32).any(|w| w == alice_curve || w == alice_ed), "the sender's keys are visible");
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
}
