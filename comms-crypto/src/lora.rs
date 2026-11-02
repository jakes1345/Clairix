//! The phone's side of Clairix LoRa (`lora/` in the repository; `docs/LORA.md`).
//!
//! The radio board does the link layer (network keys, frames, routing); what
//! needs the phone's own keys or must match the radio byte for byte is here:
//!
//! - **Lite messages**, one-frame messages between a phone and a standalone
//!   handheld (or two phones) that carry no Olm session: an anonymous box to the
//!   recipient's X25519 key, the sender's key inside it, authenticated by a
//!   second Diffie-Hellman with the sender's static key. The phone's Lite key is
//!   its sealing key, so a contact's Lite key is already in their bundle; a
//!   handheld's is learned by pairing. Lite has no forward secrecy (a stolen
//!   static key opens old Lite messages that were recorded), which is why
//!   phone-to-phone traffic goes as full Clairix envelopes whenever it fits.
//! - **Pairing** with a handheld: commit, answer, reveal, then both screens show
//!   six digits that match only if nobody sat in the middle.
//! - **Network codes** and **fingerprints**, as the handheld writes them.
//!
//! Every construction here is checked against the radio's C++ (`lora/core`)
//! by the known answers in the tests, and both against an independent Python
//! model (`lora/core/tests/crosscheck.py`).

use std::sync::{Arc, Mutex};

use chacha20::cipher::{KeyIvInit, StreamCipher};
use chacha20::ChaCha20;
use chacha20poly1305::aead::{AeadInPlace, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce, Tag};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::{Digest, Sha512};
use x25519_dalek::{PublicKey as XPublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::{canonical_x25519, CryptoError, Identity, Result};

// ── Sizes: these follow from the radio's 255-byte frame ───────────────────

/// The most a packet body can hold on the air (frame 255, less 17 of frame overhead and 2 of header).
pub const LORA_BODY_MAX: usize = 236;
pub const LITE_VERSION: u8 = 1;
/// Version, ephemeral key, sealed sender key, Poly1305 tag.
pub const LITE_OVERHEAD: usize = 1 + 32 + 32 + 16;
pub const LITE_INNER_MAX: usize = LORA_BODY_MAX - LITE_OVERHEAD;
const LITE_HEADER: usize = 1 + 4 + 4;
/// The longest Lite text, in bytes of UTF-8.
pub const LITE_TEXT_MAX: usize = LITE_INNER_MAX - LITE_HEADER - 1;
const LITE_BUCKETS: [usize; 5] = [32, 64, 96, 128, LITE_INNER_MAX];

pub const LITE_KIND_TEXT: u8 = 1;
pub const LITE_KIND_ACK: u8 = 2;
pub const LITE_KIND_SOS: u8 = 3;

const INFO_SENDER: &[u8] = b"clairix-lora-lite-v1 sender";
const INFO_MESSAGE: &[u8] = b"clairix-lora-lite-v1 message";
const PUBLIC_LABEL: &[u8] = b"clairix-lora public network v1";
const NETCODE_LABEL: &[u8] = b"clairix-lora-netcode";
const ID_LABEL: &[u8] = b"clairix-lora-id";
const COMMIT_LABEL: &[u8] = b"clairix-lora-pair-commit";
const SAS_LABEL: &[u8] = b"clairix-lora-pair-sas";
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

fn sha512(parts: &[&[u8]]) -> [u8; 64] {
    let mut h = Sha512::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

fn key32(bytes: &[u8], what: &str) -> Result<[u8; 32]> {
    bytes.try_into().map_err(|_| CryptoError::BadKey { reason: format!("{what} must be 32 bytes") })
}

fn lora_public_key(bytes: &[u8]) -> Result<XPublicKey> {
    let arr = key32(bytes, "a LoRa key")?;
    if !canonical_x25519(&arr) {
        return Err(CryptoError::BadKey { reason: "LoRa key is not in canonical form".into() });
    }
    Ok(XPublicKey::from(arr))
}

fn kdf(ikm: &[u8], salt: &[u8], info: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut out = Zeroizing::new([0u8; 32]);
    Hkdf::<Sha512>::new(Some(salt), ikm).expand(info, out.as_mut()).expect("32 bytes is a valid HKDF output length");
    out
}

// ── Networks, codes and fingerprints ──────────────────────────────────────

/// The public Clairix network's key, which every radio knows: SHA-512 of its label, first 32 bytes.
#[uniffi::export]
pub fn lora_public_network_key() -> Vec<u8> {
    sha512(&[PUBLIC_LABEL])[..32].to_vec()
}

/// A new private network's key.
#[uniffi::export]
pub fn lora_new_network_key() -> Vec<u8> {
    let mut k = vec![0u8; 32];
    rand::thread_rng().fill_bytes(&mut k);
    k
}

fn base32(bytes: &[u8]) -> String {
    let mut out = String::new();
    let (mut buf, mut bits) = (0u32, 0u32);
    for &b in bytes {
        buf = (buf << 8) | u32::from(b);
        bits += 8;
        while bits >= 5 {
            out.push(ALPHABET[((buf >> (bits - 5)) & 31) as usize] as char);
            bits -= 5;
        }
        buf &= (1 << bits) - 1;
    }
    if bits > 0 {
        out.push(ALPHABET[((buf << (5 - bits)) & 31) as usize] as char);
    }
    out
}

fn unbase32(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let (mut buf, mut bits) = (0u32, 0u32);
    for c in text.chars() {
        if c == ' ' || c == '-' {
            continue;
        }
        let c = match c.to_ascii_uppercase() {
            'I' | 'L' => '1',
            'O' => '0',
            c => c,
        };
        let v = ALPHABET.iter().position(|&a| a as char == c)? as u32;
        buf = (buf << 5) | v;
        bits += 5;
        if bits >= 8 {
            out.push((buf >> (bits - 8)) as u8);
            bits -= 8;
        }
        buf &= (1 << bits) - 1;
    }
    // Leftover bits are padding and must be zero, so each code has one spelling.
    if bits >= 5 || buf != 0 {
        return None;
    }
    Some(out)
}

fn grouped(s: &str, every: usize, sep: char) -> String {
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && i % every == 0 {
            out.push(sep);
        }
        out.push(c);
    }
    out
}

/// A network key as people pass it on: 55 characters of Crockford base32 in groups of five, the
/// last two bytes a check that catches typos.
#[uniffi::export]
pub fn lora_network_code(key: Vec<u8>) -> Result<String> {
    let key = key32(&key, "a network key")?;
    let mut all = key.to_vec();
    all.extend_from_slice(&sha512(&[NETCODE_LABEL, &key])[..2]);
    Ok(grouped(&base32(&all), 5, ' '))
}

/// Reads a network code back, forgiving case, spaces, dashes and the letters people mistake for digits.
#[uniffi::export]
pub fn lora_parse_network_code(code: String) -> Result<Vec<u8>> {
    let bad = || CryptoError::BadKey { reason: "not a network code".into() };
    let all = unbase32(&code).ok_or_else(bad)?;
    if all.len() != 34 || sha512(&[NETCODE_LABEL, &all[..32]])[..2] != all[32..] {
        return Err(bad());
    }
    Ok(all[..32].to_vec())
}

/// A Lite key's fingerprint as the handheld shows it, like "7THP-MJ6W".
#[uniffi::export]
pub fn lora_fingerprint(public: Vec<u8>) -> Result<String> {
    let key = key32(&public, "a LoRa key")?;
    Ok(grouped(&base32(&sha512(&[ID_LABEL, &key])[..5]), 4, '-'))
}

// ── Lite messages ─────────────────────────────────────────────────────────

/// What a Lite message says. `kind`: 1 text, 2 delivery receipt (payload: the
/// acknowledged id, 4 bytes), 3 SOS (see [`lora_sos_payload`]). `sent_minutes`
/// counts minutes since 1970.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct LoraLiteMessage {
    pub kind: u8,
    pub id: u32,
    pub sent_minutes: u32,
    pub payload: Vec<u8>,
}

/// A Lite message opened: who sent it (their 32-byte key) and its padded inner part.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LoraLiteOpened {
    pub sender: Vec<u8>,
    pub inner: Vec<u8>,
}

/// The inner part, padded to one of a few sizes so lengths say little: kind,
/// id, time, payload, then 0x80 and zeros.
#[uniffi::export]
pub fn lora_lite_encode(message: LoraLiteMessage) -> Result<Vec<u8>> {
    if !(LITE_KIND_TEXT..=LITE_KIND_SOS).contains(&message.kind) {
        return Err(CryptoError::Encrypt { reason: "unknown Lite kind".into() });
    }
    let raw = LITE_HEADER + message.payload.len();
    let len = LITE_BUCKETS
        .iter()
        .copied()
        .find(|&b| raw < b)
        .ok_or_else(|| CryptoError::Encrypt { reason: "too long for one LoRa frame".into() })?;
    let mut out = vec![0u8; len];
    out[0] = message.kind;
    out[1..5].copy_from_slice(&message.id.to_be_bytes());
    out[5..9].copy_from_slice(&message.sent_minutes.to_be_bytes());
    out[LITE_HEADER..raw].copy_from_slice(&message.payload);
    out[raw] = 0x80;
    Ok(out)
}

#[uniffi::export]
pub fn lora_lite_decode(inner: Vec<u8>) -> Result<LoraLiteMessage> {
    let bad = || CryptoError::Decrypt { reason: "malformed Lite message".into() };
    if inner.len() <= LITE_HEADER || !LITE_BUCKETS.contains(&inner.len()) {
        return Err(bad());
    }
    let end = inner.iter().rposition(|&b| b != 0).ok_or_else(bad)?;
    if inner[end] != 0x80 || end < LITE_HEADER {
        return Err(bad());
    }
    let kind = inner[0];
    if !(LITE_KIND_TEXT..=LITE_KIND_SOS).contains(&kind) {
        return Err(bad());
    }
    let payload = inner[LITE_HEADER..end].to_vec();
    if kind == LITE_KIND_ACK && payload.len() != 4 {
        return Err(bad());
    }
    Ok(LoraLiteMessage {
        kind,
        id: u32::from_be_bytes(inner[1..5].try_into().unwrap()),
        sent_minutes: u32::from_be_bytes(inner[5..9].try_into().unwrap()),
        payload,
    })
}

/// An SOS: where the sender is, if known (degrees × 10⁷), and a few words.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct LoraSos {
    pub has_position: bool,
    pub lat7: i32,
    pub lon7: i32,
    pub text: String,
}

#[uniffi::export]
pub fn lora_sos_payload(sos: LoraSos) -> Vec<u8> {
    let mut out = vec![u8::from(sos.has_position)];
    if sos.has_position {
        out.extend_from_slice(&sos.lat7.to_be_bytes());
        out.extend_from_slice(&sos.lon7.to_be_bytes());
    }
    out.extend_from_slice(sos.text.as_bytes());
    out
}

#[uniffi::export]
pub fn lora_parse_sos(payload: Vec<u8>) -> Result<LoraSos> {
    let bad = || CryptoError::Decrypt { reason: "malformed SOS".into() };
    let first = *payload.first().ok_or_else(bad)?;
    let has_position = first & 1 != 0;
    let (lat7, lon7, at) = if has_position {
        if payload.len() < 9 {
            return Err(bad());
        }
        (
            i32::from_be_bytes(payload[1..5].try_into().unwrap()),
            i32::from_be_bytes(payload[5..9].try_into().unwrap()),
            9,
        )
    } else {
        (0, 0, 1)
    };
    Ok(LoraSos { has_position, lat7, lon7, text: String::from_utf8_lossy(&payload[at..]).into_owned() })
}

fn chacha20_xor(key: &[u8; 32], data: &mut [u8]) {
    ChaCha20::new(key.into(), &[0u8; 12].into()).apply_keystream(data);
}

/// Seals [inner] from [me] to [peer]. [ephemeral] is for the known-answer tests only.
pub(crate) fn lite_seal(me: &StaticSecret, peer: &XPublicKey, inner: &[u8], ephemeral: Option<[u8; 32]>) -> Result<Vec<u8>> {
    if inner.is_empty() || inner.len() > LITE_INNER_MAX {
        return Err(CryptoError::Encrypt { reason: "Lite inner part has the wrong size".into() });
    }
    let e = match ephemeral {
        Some(b) => StaticSecret::from(b),
        None => StaticSecret::random_from_rng(rand::thread_rng()),
    };
    let e_pub = XPublicKey::from(&e);
    let my_pub = XPublicKey::from(me);
    let dh1 = e.diffie_hellman(peer);
    let dh2 = me.diffie_hellman(peer);
    if !dh1.was_contributory() || !dh2.was_contributory() {
        return Err(CryptoError::BadKey { reason: "low-order LoRa key".into() });
    }
    let mut salt = Vec::with_capacity(96);
    salt.extend_from_slice(e_pub.as_bytes());
    salt.extend_from_slice(peer.as_bytes());
    let k1 = kdf(dh1.as_bytes(), &salt, INFO_SENDER);
    salt.extend_from_slice(my_pub.as_bytes());
    let mut both = Zeroizing::new([0u8; 64]);
    both[..32].copy_from_slice(dh1.as_bytes());
    both[32..].copy_from_slice(dh2.as_bytes());
    let k2 = kdf(both.as_ref(), &salt, INFO_MESSAGE);

    let mut body = Vec::with_capacity(LITE_OVERHEAD + inner.len());
    body.push(LITE_VERSION);
    body.extend_from_slice(e_pub.as_bytes());
    let mut sealed_sender = *my_pub.as_bytes();
    chacha20_xor(&k1, &mut sealed_sender);
    body.extend_from_slice(&sealed_sender);
    let mut ct = inner.to_vec();
    let tag = ChaCha20Poly1305::new(Key::from_slice(k2.as_ref()))
        .encrypt_in_place_detached(Nonce::from_slice(&[0u8; 12]), &body, &mut ct)
        .map_err(|_| CryptoError::Encrypt { reason: "Lite seal failed".into() })?;
    body.extend_from_slice(&ct);
    body.extend_from_slice(&tag);
    Ok(body)
}

pub(crate) fn lite_open(me: &StaticSecret, body: &[u8], is_contact: impl Fn(&[u8; 32]) -> bool) -> Result<LoraLiteOpened> {
    let bad = || CryptoError::Decrypt { reason: "not a Lite message for this key from a contact".into() };
    if body.len() <= LITE_OVERHEAD || body.len() > LORA_BODY_MAX || body[0] != LITE_VERSION {
        return Err(bad());
    }
    let e_pub = XPublicKey::from(key32(&body[1..33], "ephemeral key")?);
    let my_pub = XPublicKey::from(me);
    let dh1 = me.diffie_hellman(&e_pub);
    if !dh1.was_contributory() {
        return Err(bad());
    }
    let mut salt = Vec::with_capacity(96);
    salt.extend_from_slice(e_pub.as_bytes());
    salt.extend_from_slice(my_pub.as_bytes());
    let k1 = kdf(dh1.as_bytes(), &salt, INFO_SENDER);
    let mut sender = key32(&body[33..65], "sender key")?;
    chacha20_xor(&k1, &mut sender);
    // Only contacts: a stranger's message costs no second key agreement and is never shown.
    if !is_contact(&sender) || !canonical_x25519(&sender) {
        return Err(bad());
    }
    let sender_pub = XPublicKey::from(sender);
    let dh2 = me.diffie_hellman(&sender_pub);
    if !dh2.was_contributory() {
        return Err(bad());
    }
    salt.extend_from_slice(&sender);
    let mut both = Zeroizing::new([0u8; 64]);
    both[..32].copy_from_slice(dh1.as_bytes());
    both[32..].copy_from_slice(dh2.as_bytes());
    let k2 = kdf(both.as_ref(), &salt, INFO_MESSAGE);
    let len = body.len() - LITE_OVERHEAD;
    let mut inner = body[65..65 + len].to_vec();
    ChaCha20Poly1305::new(Key::from_slice(k2.as_ref()))
        .decrypt_in_place_detached(Nonce::from_slice(&[0u8; 12]), &body[..65], &mut inner, Tag::from_slice(&body[65 + len..]))
        .map_err(|_| bad())?;
    Ok(LoraLiteOpened { sender: sender.to_vec(), inner })
}

#[uniffi::export]
impl Identity {
    /// This device's Lite key on LoRa: its sealing key, raw 32 bytes.
    pub fn lora_public(&self) -> Vec<u8> {
        let inner = self.inner.lock().expect("identity mutex poisoned");
        XPublicKey::from(&inner.sealing_secret).as_bytes().to_vec()
    }

    /// Seals a Lite inner part (see [`lora_lite_encode`]) to [peer]'s Lite key, for one LoRa frame.
    pub fn lora_lite_seal(&self, peer: Vec<u8>, inner: Vec<u8>) -> Result<Vec<u8>> {
        let peer = lora_public_key(&peer)?;
        let guard = self.inner.lock().expect("identity mutex poisoned");
        lite_seal(&guard.sealing_secret, &peer, &inner, None)
    }

    /// Opens a Lite body heard on LoRa, if it is for this device and from one of [contacts] (raw Lite keys).
    pub fn lora_lite_open(&self, body: Vec<u8>, contacts: Vec<Vec<u8>>) -> Result<LoraLiteOpened> {
        let guard = self.inner.lock().expect("identity mutex poisoned");
        lite_open(&guard.sealing_secret, &body, |k| contacts.iter().any(|c| c.as_slice() == k))
    }
}

// ── Pairing ───────────────────────────────────────────────────────────────

const STEP_COMMIT: u8 = 1;
const STEP_ANSWER: u8 = 2;
const STEP_REVEAL: u8 = 3;
const PAIR_RESEND_MS: u64 = 4000;
pub const PAIR_TIMEOUT_MS: u64 = 120_000;
pub const PAIR_NAME_MAX: usize = 24;

pub fn pair_commitment(public: &[u8; 32], nonce: &[u8; 16]) -> [u8; 32] {
    sha512(&[COMMIT_LABEL, public, nonce])[..32].try_into().unwrap()
}

/// The six digits both screens show: they match only when each side holds the other's real key.
pub fn pair_digits(a: &[u8; 32], b: &[u8; 32], na: &[u8; 16], nb: &[u8; 16]) -> u32 {
    let h = sha512(&[SAS_LABEL, a, b, na, nb]);
    u32::from_be_bytes(h[..4].try_into().unwrap()) % 1_000_000
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum LoraPairState {
    Idle,
    Waiting,
    Committed,
    Answered,
    Matched,
    Failed,
}

struct PairInner {
    state: LoraPairState,
    initiator: bool,
    public: [u8; 32],
    name: String,
    nonce: [u8; 16],
    my_commit: [u8; 32],
    their_commit: [u8; 32],
    session: u32,
    next_at: u64,
    deadline: u64,
    peer: [u8; 32],
    peer_nonce: [u8; 16],
    peer_name: String,
    digits: u32,
    reveals_left: u8,
}

/// Pairing with a handheld (or another phone) over LoRa, the same steps as the
/// radio's `clx::Pairing`. Every call returns the Pair bodies to send now, in order.
#[derive(uniffi::Object)]
pub struct LoraPairing {
    inner: Mutex<PairInner>,
}

impl PairInner {
    fn commit_body(&self) -> Vec<u8> {
        let mut b = vec![STEP_COMMIT];
        b.extend_from_slice(&self.session.to_be_bytes());
        b.extend_from_slice(&self.my_commit);
        b
    }

    fn named(&self, step: u8) -> Vec<u8> {
        let mut b = vec![step];
        b.extend_from_slice(&self.session.to_be_bytes());
        b.extend_from_slice(&self.public);
        b.extend_from_slice(&self.nonce);
        b.push(self.name.len() as u8);
        b.extend_from_slice(self.name.as_bytes());
        b
    }

    fn start(&mut self, nonce: [u8; 16], session: u32, now: u64, delay_ms: u32) {
        self.nonce = nonce;
        self.session = session;
        self.my_commit = pair_commitment(&self.public, &self.nonce);
        self.initiator = false;
        self.digits = 0;
        self.peer_name.clear();
        self.state = LoraPairState::Waiting;
        self.next_at = now + u64::from(delay_ms);
        self.deadline = now + PAIR_TIMEOUT_MS;
    }

    fn tick(&mut self, now: u64) -> Vec<Vec<u8>> {
        use LoraPairState::*;
        let mut out = Vec::new();
        if matches!(self.state, Idle | Failed) {
            return out;
        }
        if self.state != Matched && now >= self.deadline {
            self.state = Failed;
            return out;
        }
        if self.state == Waiting && now >= self.next_at {
            self.initiator = true;
            self.state = Committed;
            self.next_at = now + PAIR_RESEND_MS;
            out.push(self.commit_body());
            return out;
        }
        if now >= self.next_at && now < self.deadline {
            self.next_at = now + PAIR_RESEND_MS;
            match self.state {
                Committed => out.push(self.commit_body()),
                Answered => out.push(self.named(STEP_ANSWER)),
                Matched if self.initiator && self.reveals_left > 0 => {
                    self.reveals_left -= 1;
                    out.push(self.named(STEP_REVEAL));
                }
                _ => {}
            }
        }
        out
    }

    fn on_body(&mut self, p: &[u8], now: u64) -> Vec<Vec<u8>> {
        use LoraPairState::*;
        let mut out = Vec::new();
        if matches!(self.state, Idle | Failed) || p.len() < 5 {
            return out;
        }
        let step = p[0];
        let session = u32::from_be_bytes(p[1..5].try_into().unwrap());
        let rest = &p[5..];
        if step == STEP_COMMIT {
            if rest.len() != 32 {
                return out;
            }
            let c: [u8; 32] = rest.try_into().unwrap();
            if c == self.my_commit {
                return out; // our own, heard back through a relay
            }
            let answer = self.state == Waiting || (self.state == Committed && c < self.my_commit);
            if !answer {
                return out;
            }
            self.their_commit = c;
            self.session = session;
            self.initiator = false;
            self.state = Answered;
            self.next_at = now + PAIR_RESEND_MS;
            out.push(self.named(STEP_ANSWER));
            return out;
        }
        if (step != STEP_ANSWER && step != STEP_REVEAL) || rest.len() < 49 {
            return out;
        }
        let key: [u8; 32] = rest[..32].try_into().unwrap();
        let nonce: [u8; 16] = rest[32..48].try_into().unwrap();
        let name_len = rest[48] as usize;
        if name_len > PAIR_NAME_MAX || rest.len() != 49 + name_len || session != self.session || key == self.public {
            return out;
        }
        let name = String::from_utf8_lossy(&rest[49..]).into_owned();
        if step == STEP_ANSWER && self.state == Committed && self.initiator {
            self.peer = key;
            self.peer_nonce = nonce;
            self.peer_name = name;
            out.push(self.named(STEP_REVEAL));
            self.next_at = now + PAIR_RESEND_MS;
            self.reveals_left = 3;
            self.digits = pair_digits(&self.public, &self.peer, &self.nonce, &self.peer_nonce);
            self.state = Matched;
        } else if step == STEP_REVEAL && self.state == Answered && !self.initiator {
            if pair_commitment(&key, &nonce) != self.their_commit {
                self.state = Failed;
                return out;
            }
            self.peer = key;
            self.peer_nonce = nonce;
            self.peer_name = name;
            self.digits = pair_digits(&self.peer, &self.public, &self.peer_nonce, &self.nonce);
            self.state = Matched;
        }
        out
    }
}

#[uniffi::export]
impl LoraPairing {
    /// Pairing for this device's Lite key ([`Identity::lora_public`]) under [name], cut to 24 bytes.
    #[uniffi::constructor]
    pub fn new(public: Vec<u8>, name: String) -> Result<Arc<Self>> {
        let public = key32(&public, "a LoRa key")?;
        let mut name = name;
        while name.len() > PAIR_NAME_MAX {
            name.pop();
        }
        Ok(Arc::new(Self {
            inner: Mutex::new(PairInner {
                state: LoraPairState::Idle,
                initiator: false,
                public,
                name,
                nonce: [0; 16],
                my_commit: [0; 32],
                their_commit: [0; 32],
                session: 0,
                next_at: 0,
                deadline: 0,
                peer: [0; 32],
                peer_nonce: [0; 16],
                peer_name: String::new(),
                digits: 0,
                reveals_left: 0,
            }),
        }))
    }

    /// Starts listening; unless the other side commits first, commits after [delay_ms] (draw it at random, 0 to 3000).
    pub fn start(&self, now_ms: u64, delay_ms: u32) {
        let mut nonce = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut nonce);
        let session = rand::thread_rng().next_u32();
        self.inner.lock().expect("pairing mutex poisoned").start(nonce, session, now_ms, delay_ms);
    }

    pub fn cancel(&self) {
        self.inner.lock().expect("pairing mutex poisoned").state = LoraPairState::Idle;
    }

    /// Call about once a second: returns what to resend.
    pub fn tick(&self, now_ms: u64) -> Vec<Vec<u8>> {
        self.inner.lock().expect("pairing mutex poisoned").tick(now_ms)
    }

    /// A Pair body heard on LoRa.
    pub fn on_body(&self, body: Vec<u8>, now_ms: u64) -> Vec<Vec<u8>> {
        self.inner.lock().expect("pairing mutex poisoned").on_body(&body, now_ms)
    }

    pub fn state(&self) -> LoraPairState {
        self.inner.lock().expect("pairing mutex poisoned").state
    }

    /// Once matched: the six digits to compare with the other screen.
    pub fn digits(&self) -> u32 {
        self.inner.lock().expect("pairing mutex poisoned").digits
    }

    /// Once matched: the other side's Lite key.
    pub fn peer(&self) -> Option<Vec<u8>> {
        let inner = self.inner.lock().expect("pairing mutex poisoned");
        (inner.state == LoraPairState::Matched).then(|| inner.peer.to_vec())
    }

    pub fn peer_name(&self) -> String {
        self.inner.lock().expect("pairing mutex poisoned").peer_name.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Known answers from lora/core/tests/known_answers.h: the radio's C++ made them, and an
    // independent Python model (lora/core/tests/crosscheck.py) agrees.
    const KA_PUBLIC_KEY: &str = "f12717ac8cac9a77da2318c3335a9968fb6687e457b83bf221008724ffe5506e";
    const KA_LITE_BODY: &str = "014c29f4996985e80d247e80b70303824592ff9fbad74904ea7c3d2bda24e51c12343e456b5273870d8193ac68377b429e91290740513bb98060187e3a1d13ad3b4c4cca3f7983d4c416e6d1bc76473d34fce7819274e844fa16aa1eb4cb51fcec6914397d7069534e8d3ae113df1997a1";
    const KA_FINGERPRINT: &str = "7THP-MJ6W";
    const KA_PAIR_DIGITS: u32 = 265565;
    const KA_PAIR_COMMIT: &str = "2e4eb43021561076756362030d1954323b152567f3f27a8fda797fb8951e91df";
    const KA_PUBLIC_CODE: &str = "Y4KHF B4CNJ D7FPH 3331K 6PMSD 3XPD1 Z4AYW 3QWH1 023J9 ZZ5A1 QA1N0";

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }
    fn seq<const N: usize>(start: u8) -> [u8; N] {
        std::array::from_fn(|i| start.wrapping_add(i as u8))
    }

    #[test]
    fn matches_the_radio_byte_for_byte() {
        assert_eq!(hex(&lora_public_network_key()), KA_PUBLIC_KEY);
        assert_eq!(lora_network_code(lora_public_network_key()).unwrap(), KA_PUBLIC_CODE);
        let a = StaticSecret::from(seq::<32>(1));
        let b = StaticSecret::from(seq::<32>(101));
        let a_pub = XPublicKey::from(&a);
        let b_pub = XPublicKey::from(&b);
        let inner = lora_lite_encode(LoraLiteMessage {
            kind: LITE_KIND_TEXT,
            id: 0x11223344,
            sent_minutes: 29_883_000,
            payload: b"Clairix Lite".to_vec(),
        })
        .unwrap();
        let body = lite_seal(&a, &b_pub, &inner, Some(seq::<32>(201))).unwrap();
        assert_eq!(hex(&body), KA_LITE_BODY);
        assert_eq!(lora_fingerprint(a_pub.as_bytes().to_vec()).unwrap(), KA_FINGERPRINT);
        let (na, nb) = (seq::<16>(50), seq::<16>(70));
        assert_eq!(pair_digits(a_pub.as_bytes(), b_pub.as_bytes(), &na, &nb), KA_PAIR_DIGITS);
        assert_eq!(hex(&pair_commitment(a_pub.as_bytes(), &na)), KA_PAIR_COMMIT);
        // And the radio's message opens here.
        let opened = lite_open(&b, &body, |k| k == a_pub.as_bytes()).unwrap();
        assert_eq!(opened.sender, a_pub.as_bytes().to_vec());
        assert_eq!(opened.inner, inner);
    }

    #[test]
    fn lite_between_two_identities_and_only_from_contacts() {
        let alice = Identity::create();
        let bob = Identity::create();
        let carol = Identity::create();
        let inner = lora_lite_encode(LoraLiteMessage { kind: LITE_KIND_TEXT, id: 7, sent_minutes: 1, payload: b"hi".to_vec() }).unwrap();
        let body = alice.lora_lite_seal(bob.lora_public(), inner.clone()).unwrap();
        assert!(body.len() <= LORA_BODY_MAX);
        let opened = bob.lora_lite_open(body.clone(), vec![alice.lora_public()]).unwrap();
        assert_eq!(opened.sender, alice.lora_public());
        assert_eq!(lora_lite_decode(opened.inner).unwrap().payload, b"hi");
        assert!(bob.lora_lite_open(body.clone(), vec![carol.lora_public()]).is_err(), "not from a contact");
        assert!(carol.lora_lite_open(body.clone(), vec![alice.lora_public()]).is_err(), "not for carol");
        for i in 0..body.len() {
            let mut bad = body.clone();
            bad[i] ^= 4;
            assert!(bob.lora_lite_open(bad, vec![alice.lora_public()]).is_err(), "byte {i}");
        }
        assert!(alice.lora_lite_seal(vec![0; 32], inner).is_err(), "low-order key");
    }

    #[test]
    fn lite_inner_sizes() {
        let full = LoraLiteMessage { kind: LITE_KIND_TEXT, id: 1, sent_minutes: 2, payload: vec![b'x'; LITE_TEXT_MAX] };
        assert_eq!(lora_lite_encode(full.clone()).unwrap().len(), LITE_INNER_MAX);
        let mut over = full;
        over.payload.push(b'x');
        assert!(lora_lite_encode(over).is_err());
        let ack = LoraLiteMessage { kind: LITE_KIND_ACK, id: 3, sent_minutes: 4, payload: vec![0, 0, 1, 0] };
        assert_eq!(lora_lite_decode(lora_lite_encode(ack.clone()).unwrap()).unwrap(), ack);
        let sos = LoraSos { has_position: true, lat7: 418_781_000, lon7: -876_298_000, text: "hurt".into() };
        let msg = LoraLiteMessage { kind: LITE_KIND_SOS, id: 5, sent_minutes: 6, payload: lora_sos_payload(sos.clone()) };
        let back = lora_lite_decode(lora_lite_encode(msg).unwrap()).unwrap();
        assert_eq!(lora_parse_sos(back.payload).unwrap(), sos);
    }

    #[test]
    fn network_codes() {
        let key = lora_new_network_key();
        let code = lora_network_code(key.clone()).unwrap();
        assert_eq!(lora_parse_network_code(code.to_lowercase().replace(' ', "-")).unwrap(), key);
        let mut typo: Vec<char> = code.chars().collect();
        typo[3] = if typo[3] == 'A' { 'B' } else { 'A' };
        assert!(lora_parse_network_code(typo.into_iter().collect()).is_err());
        assert!(lora_parse_network_code("hello".into()).is_err());
    }

    fn run(a: &mut PairInner, b: &mut PairInner, until: u64) {
        let (mut to_a, mut to_b): (Vec<Vec<u8>>, Vec<Vec<u8>>) = (vec![], vec![]);
        let mut t = 0;
        while t <= until {
            to_b.extend(a.tick(t));
            to_a.extend(b.tick(t));
            for m in std::mem::take(&mut to_a) {
                to_b.extend(a.on_body(&m, t));
            }
            for m in std::mem::take(&mut to_b) {
                to_a.extend(b.on_body(&m, t));
            }
            t += 100;
        }
    }

    fn party(public: [u8; 32], name: &str) -> PairInner {
        let p = LoraPairing::new(public.to_vec(), name.into()).unwrap();
        Arc::try_unwrap(p).ok().unwrap().inner.into_inner().unwrap()
    }

    #[test]
    fn pairing_matches_digits_and_catches_a_switched_key() {
        let (ka, kb) = (seq::<32>(9), seq::<32>(99));
        for (da, db) in [(500, 2500), (0, 0)] {
            let mut a = party(ka, "Ana");
            let mut b = party(kb, "Bo");
            a.start(seq::<16>(1), 77, 0, da);
            b.start(seq::<16>(2), 88, 0, db);
            run(&mut a, &mut b, 10_000);
            assert_eq!(a.state, LoraPairState::Matched);
            assert_eq!(b.state, LoraPairState::Matched);
            assert_eq!(a.digits, b.digits);
            assert_eq!((a.peer, b.peer), (kb, ka));
            assert_eq!((a.peer_name.as_str(), b.peer_name.as_str()), ("Bo", "Ana"));
        }
        // Someone answers a commitment and then reveals a different key than it committed to.
        let mut b = party(kb, "Bo");
        b.start(seq::<16>(2), 88, 0, 60_000);
        let mut commit = vec![STEP_COMMIT];
        commit.extend_from_slice(&5u32.to_be_bytes());
        commit.extend_from_slice(&pair_commitment(&ka, &seq::<16>(1)));
        b.on_body(&commit, 10);
        assert_eq!(b.state, LoraPairState::Answered);
        let mut reveal = vec![STEP_REVEAL];
        reveal.extend_from_slice(&5u32.to_be_bytes());
        reveal.extend_from_slice(&seq::<32>(200));
        reveal.extend_from_slice(&seq::<16>(1));
        reveal.push(0);
        b.on_body(&reveal, 20);
        assert_eq!(b.state, LoraPairState::Failed);
    }
}
