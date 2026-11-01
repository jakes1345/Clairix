//! The recovery file: this phone's Clairix identity, saved by its owner to a
//! place of their choosing, so that a lost, smashed or confiscated phone does
//! not take the number, the contacts and the groups with it.
//!
//! **What goes in.** The long-term identity only: the Olm account's Ed25519
//! and Curve25519 identity keys, the sealing key pair, the sealing prekeys
//! still held (so envelopes contacts seal to the prekey they cached can still
//! be opened), which Curve25519 key belongs to which Ed25519 key, and which
//! peers publish prekeys (so nothing is ever sealed to them the old way
//! again). Next to it the app puts its own record (`app_data`): the relay and
//! number, contacts with their pinned keys and verification state, groups and
//! rosters. The crate treats that as opaque bytes.
//!
//! **What never goes in.** Olm sessions (ratchet state), one-time keys and
//! the fallback key, and the list of pre-key messages already accepted. A
//! ratchet restored from a file is an old ratchet: it would reuse message keys
//! and accept messages it had already accepted. The one-time and fallback key
//! secrets are stripped from the account when the file is made, so they never
//! leave the phone; restoring makes a new fallback key and moves the key-id
//! counters far past anything the old phone could have handed out, so new
//! one-time keys cannot collide with ones contacts or the relay remember.
//! Every conversation starts a fresh session after a restore.
//!
//! **The format** (integers big-endian):
//!
//! ```text
//! 0   8  magic "CLXRCVRY"
//! 8   1  format version (1)
//! 9   1  key derivation (1 = Argon2id, version 0x13)
//! 10  4  Argon2 memory, KiB
//! 14  4  Argon2 passes
//! 18  4  Argon2 lanes
//! 22 16  salt
//! 38 12  nonce
//! 50  …  ChaCha20-Poly1305 ciphertext and tag
//! ```
//!
//! The key is Argon2id(recovery code, salt) and the whole 50-byte header is
//! the AEAD's associated data, so changing a parameter, the salt or the
//! version makes the file fail to open, exactly as a changed byte of the
//! ciphertext does.
//!
//! **The recovery code** is made here, not chosen by a person: 24 random
//! symbols from Crockford's base32 (120 bits), then 4 check symbols, written
//! in seven groups of four. A person-chosen passphrase is guessable at the
//! rate the KDF allows; 120 random bits are not guessable at any rate, and the
//! check symbols catch a code mistyped from paper before the KDF runs, so
//! "you typed it wrong" and "this is not the code for this file" are told
//! apart. Argon2id still stands between a stolen file and its contents in
//! case a code is ever written down carelessly in part.

use std::collections::{HashMap, HashSet};
use std::io::Write as _;
use std::sync::Arc;

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::value::RawValue;
use sha2::{Digest, Sha256};
use vodozemac::olm::{Account, AccountPickle};
use x25519_dalek::StaticSecret;
use zeroize::Zeroizing;

use crate::{
    now_ms, B64, ByteCounter, CryptoError, Identity, Inner2, PrekeyEntry, PrekeyRecord, Result, SecretBytes,
    MAX_PREKEYS,
};
use base64::Engine;
use std::collections::VecDeque;
use std::sync::Mutex;

/// Why a recovery file could not be opened.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RecoveryProblem {
    /// The file does not start like a Clairix recovery file.
    NotARecoveryFile,
    /// The file was made by a newer Clairix, in a format this one cannot read.
    NewerVersion,
    /// The code is not a recovery code: wrong length, a character no code
    /// has, or its check symbols do not match (a typo).
    CodeMistyped,
    /// A well-formed code that does not open this file, or a file that was
    /// changed after it was made. The two cannot be told apart, by design.
    WrongCodeOrDamaged,
    /// The file opened but what is inside it is not a usable identity.
    Unreadable,
}

/// What a recovery file gives back: the identity, ready to use (no sessions,
/// a new fallback key, no one-time keys until the app makes them), and the
/// app's own record, as it was handed to [`Identity::recovery_file`].
#[derive(uniffi::Record)]
pub struct RecoveredIdentity {
    pub identity: Arc<Identity>,
    pub app_data: Vec<u8>,
}

const MAGIC: &[u8; 8] = b"CLXRCVRY";
const FORMAT_VERSION: u8 = 1;
const KDF_ARGON2ID: u8 = 1;
const HEADER_LEN: usize = 8 + 1 + 1 + 4 + 4 + 4 + 16 + 12;
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;

/// Argon2id cost of every file this version makes: 64 MiB, three passes,
/// one lane. RFC 9106's second recommended setting is 64 MiB and three
/// passes; one lane because this crate computes lanes one after another, so
/// more lanes would cost a phone the same time without costing an attacker
/// more. 64 MiB fits comfortably in what Android gives an app on any phone
/// this app supports, and takes on the order of a second on a mid-range one.
pub(crate) const DEFAULT_KDF: KdfParams = KdfParams { memory_kib: 64 * 1024, passes: 3, lanes: 1 };

/// The most a file may ask for: an Argon2 run is memory the phone must find,
/// and a file can come from anyone.
const MAX_MEMORY_KIB: u32 = 1024 * 1024;
const MAX_PASSES: u32 = 16;
const MAX_LANES: u32 = 8;

/// Crockford's base32: no I, L, O or U, so a code copied by hand is not misread.
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_RANDOM: usize = 24;
const CODE_CHECK: usize = 4;
const CODE_LEN: usize = CODE_RANDOM + CODE_CHECK;
const CODE_GROUP: usize = 4;
const CODE_CHECK_CONTEXT: &[u8] = b"clairix-recovery-code-v1";

/// Format of the identity inside the file.
const STATE_VERSION: u8 = 1;

/// The new one-time and fallback key ids start at least this far past the
/// old counters, plus a random amount below it: the old phone would have had
/// to make a trillion keys after the file was saved to reach them, and two
/// restores of one file do not land on the same ids.
const KEY_ID_JUMP: u64 = 1 << 40;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct KdfParams {
    pub memory_kib: u32,
    pub passes: u32,
    pub lanes: u32,
}

/// The identity as the file holds it.
#[derive(Serialize, Deserialize)]
struct RecoveryState {
    v: u8,
    /// The Olm account with its one-time and fallback key secrets removed.
    account: AccountPickle,
    sealing_secret: SecretBytes,
    #[serde(default)]
    prekeys: Vec<PrekeyRecord>,
    #[serde(default)]
    peers: HashMap<String, String>,
    #[serde(default)]
    prekey_peers: HashSet<String>,
    /// The app's record, base64.
    app: String,
}

// ── Recovery codes ────────────────────────────────────────────────────────

/// A new recovery code, in groups of four: `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`.
/// Shown once; the app keeps no copy.
#[uniffi::export]
pub fn recovery_code_generate() -> String {
    let mut random = Zeroizing::new([0u8; CODE_RANDOM]);
    let mut bytes = Zeroizing::new([0u8; CODE_RANDOM]);
    rand::thread_rng().fill_bytes(bytes.as_mut());
    for (slot, b) in random.iter_mut().zip(bytes.iter()) {
        *slot = ALPHABET[(*b & 0x1F) as usize];
    }
    let mut code = Zeroizing::new(Vec::with_capacity(CODE_LEN));
    code.extend_from_slice(random.as_ref());
    code.extend_from_slice(&check_symbols(random.as_ref()));
    group(&code)
}

/// The code in its one canonical spelling (28 symbols, no separators), when
/// it is a recovery code; None when it is not (wrong length, a character no
/// code has, or the check symbols do not match). Lower case, spaces and
/// dashes are forgiven, and so are O for 0 and I or L for 1.
#[uniffi::export]
pub fn recovery_code_normalize(code: String) -> Option<String> {
    let canonical = canonical_code(&code)?;
    Some(String::from_utf8(canonical.to_vec()).expect("the alphabet is ASCII"))
}

/// The canonical code, as bytes that are wiped when dropped.
fn canonical_code(code: &str) -> Option<Zeroizing<Vec<u8>>> {
    let mut out = Zeroizing::new(Vec::with_capacity(CODE_LEN));
    for c in code.chars() {
        if c.is_whitespace() || c == '-' || c == '.' || c == '_' {
            continue;
        }
        let c = match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            other => other,
        };
        if !c.is_ascii() || !ALPHABET.contains(&(c as u8)) || out.len() == CODE_LEN {
            return None;
        }
        out.push(c as u8);
    }
    if out.len() != CODE_LEN {
        return None;
    }
    let (random, check) = out.split_at(CODE_RANDOM);
    if check_symbols(random) != check {
        return None;
    }
    Some(out)
}

/// The four check symbols of a code's 24 random ones: the first 20 bits of
/// SHA-256 over them, under a context string of their own.
fn check_symbols(random: &[u8]) -> [u8; CODE_CHECK] {
    let digest = Sha256::new().chain_update(CODE_CHECK_CONTEXT).chain_update(random).finalize();
    let bits = (u32::from(digest[0]) << 12) | (u32::from(digest[1]) << 4) | (u32::from(digest[2]) >> 4);
    let mut out = [0u8; CODE_CHECK];
    for (i, slot) in out.iter_mut().enumerate() {
        *slot = ALPHABET[((bits >> (5 * (CODE_CHECK - 1 - i))) & 0x1F) as usize];
    }
    out
}

fn group(code: &[u8]) -> String {
    code.chunks(CODE_GROUP)
        .map(|g| std::str::from_utf8(g).expect("the alphabet is ASCII"))
        .collect::<Vec<_>>()
        .join("-")
}

// ── Making and opening a file ─────────────────────────────────────────────

#[uniffi::export]
impl Identity {
    /// This identity's recovery file, with [`app_data`] (the app's own record)
    /// inside, encrypted under `code` (from [`recovery_code_generate`]). Runs
    /// Argon2id at 64 MiB: call it off the main thread.
    pub fn recovery_file(&self, app_data: Vec<u8>, code: String) -> Result<Vec<u8>> {
        self.recovery_file_with(&Zeroizing::new(app_data), &code, DEFAULT_KDF)
    }
}

impl Identity {
    pub(crate) fn recovery_file_with(&self, app_data: &[u8], code: &str, kdf: KdfParams) -> Result<Vec<u8>> {
        let canonical = canonical_code(code).ok_or(CryptoError::Recovery { problem: RecoveryProblem::CodeMistyped })?;
        // Taken under the lock, which is let go before the slow part.
        let state = {
            let inner = self.inner.lock().expect("identity mutex poisoned");
            RecoveryState {
                v: STATE_VERSION,
                account: account_without_one_time_keys(&inner.account, 0)?,
                sealing_secret: SecretBytes(inner.sealing_secret.to_bytes()),
                prekeys: inner
                    .prekeys
                    .iter()
                    .map(|p| PrekeyRecord { s: SecretBytes(p.secret.to_bytes()), c: p.created_ms, x: p.superseded_ms })
                    .collect(),
                peers: inner.peers.clone(),
                prekey_peers: inner.prekey_peers.clone(),
                app: B64.encode(app_data),
            }
        };
        let plain = to_json(&state)?;
        seal(&plain, &canonical, kdf)
    }
}

/// Opens a recovery file with its code. The identity comes back with no
/// sessions, no one-time keys and a new fallback key and sealing prekey: the
/// app makes one-time keys, publishes the bundle, and every conversation
/// starts afresh. Contacts see the same identity keys, so the same safety
/// numbers. Runs Argon2id: call it off the main thread.
#[uniffi::export]
pub fn recovery_open(file: Vec<u8>, code: String) -> Result<RecoveredIdentity> {
    let plain = open(&file, &code)?;
    let state: RecoveryState = serde_json::from_slice(&plain).map_err(|_| unreadable())?;
    if state.v != STATE_VERSION {
        return Err(CryptoError::Recovery { problem: RecoveryProblem::NewerVersion });
    }
    let RecoveryState { account, sealing_secret, prekeys, peers, prekey_peers, app, .. } = state;
    let app_data = B64.decode(app.as_bytes()).map_err(|_| unreadable())?;
    // Stripped again, whatever the file says, and the key-id counters moved on.
    let jump = KEY_ID_JUMP + (rand::thread_rng().next_u64() % KEY_ID_JUMP);
    let mut account = Account::from_pickle(account_without_one_time_keys(&Account::from_pickle(account), jump)?);
    account.generate_fallback_key();
    let now = now_ms();
    let mut prekeys: Vec<PrekeyEntry> = prekeys
        .into_iter()
        .map(|r| PrekeyEntry { secret: StaticSecret::from(r.s.0), created_ms: r.c, superseded_ms: r.x })
        .collect();
    // A new prekey goes out with the new bundle; the ones restored stay only
    // for envelopes already sealed to them, and go as replaced prekeys do.
    if let Some(current) = prekeys.first_mut() {
        current.superseded_ms.get_or_insert(now);
    }
    prekeys.insert(0, PrekeyEntry::new(now));
    prekeys.truncate(MAX_PREKEYS);
    let identity = Arc::new(Identity {
        inner: Mutex::new(Inner2 {
            account,
            sealing_secret: StaticSecret::from(sealing_secret.0),
            sessions: HashMap::new(),
            used_prekeys: VecDeque::new(),
            peers,
            prekeys,
            prekey_peers,
        }),
    });
    Ok(RecoveredIdentity { identity, app_data })
}

fn unreadable() -> CryptoError {
    CryptoError::Recovery { problem: RecoveryProblem::Unreadable }
}

/// Serialises `value` into a buffer sized first and wiped when dropped, so no
/// smaller copy of the secrets is freed unwiped as the buffer grows.
fn to_json<T: Serialize>(value: &T) -> Result<Zeroizing<Vec<u8>>> {
    let mut size = ByteCounter(0);
    serde_json::to_writer(&mut size, value).map_err(|_| unreadable())?;
    let mut out = Zeroizing::new(Vec::with_capacity(size.0));
    serde_json::to_writer(&mut *out, value).map_err(|_| unreadable())?;
    Ok(out)
}

/// The parts of a pickled account that are kept, borrowed from its JSON.
#[derive(Deserialize)]
struct AccountParts<'a> {
    #[serde(borrow)]
    signing_key: &'a RawValue,
    #[serde(borrow)]
    diffie_hellman_key: &'a RawValue,
    one_time_keys: OneTimeKeyCounter,
    fallback_keys: FallbackKeyCounter,
}

#[derive(Deserialize)]
struct OneTimeKeyCounter {
    #[serde(alias = "key_id")]
    next_key_id: u64,
}

#[derive(Deserialize)]
struct FallbackKeyCounter {
    key_id: u64,
}

/// `account` with no one-time keys and no fallback key, its two key-id
/// counters moved on by `jump`: the identity keys and nothing else.
fn account_without_one_time_keys(account: &Account, jump: u64) -> Result<AccountPickle> {
    let full = to_json(&account.pickle())?;
    let parts: AccountParts<'_> = serde_json::from_slice(&full).map_err(|_| unreadable())?;
    let signing = parts.signing_key.get().as_bytes();
    let dh = parts.diffie_hellman_key.get().as_bytes();
    let mut out = Zeroizing::new(Vec::with_capacity(signing.len() + dh.len() + 256));
    out.extend_from_slice(b"{\"signing_key\":");
    out.extend_from_slice(signing);
    out.extend_from_slice(b",\"diffie_hellman_key\":");
    out.extend_from_slice(dh);
    write!(
        &mut *out,
        ",\"one_time_keys\":{{\"next_key_id\":{},\"public_keys\":{{}},\"private_keys\":{{}}}},\
         \"fallback_keys\":{{\"key_id\":{},\"fallback_key\":null,\"previous_fallback_key\":null}}}}",
        parts.one_time_keys.next_key_id.wrapping_add(jump),
        parts.fallback_keys.key_id.wrapping_add(jump)
    )
    .map_err(|_| unreadable())?;
    serde_json::from_slice(&out).map_err(|_| unreadable())
}

// ── The envelope ──────────────────────────────────────────────────────────

fn derive_key(code: &[u8], salt: &[u8], kdf: KdfParams) -> Result<Zeroizing<[u8; 32]>> {
    let params = Params::new(kdf.memory_kib, kdf.passes, kdf.lanes, Some(32))
        .map_err(|_| CryptoError::Recovery { problem: RecoveryProblem::Unreadable })?;
    let mut key = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(code, salt, key.as_mut())
        .map_err(|_| CryptoError::Recovery { problem: RecoveryProblem::Unreadable })?;
    Ok(key)
}

fn header(kdf: KdfParams, salt: &[u8; SALT_LEN], nonce: &[u8; NONCE_LEN]) -> [u8; HEADER_LEN] {
    let mut h = [0u8; HEADER_LEN];
    h[..8].copy_from_slice(MAGIC);
    h[8] = FORMAT_VERSION;
    h[9] = KDF_ARGON2ID;
    h[10..14].copy_from_slice(&kdf.memory_kib.to_be_bytes());
    h[14..18].copy_from_slice(&kdf.passes.to_be_bytes());
    h[18..22].copy_from_slice(&kdf.lanes.to_be_bytes());
    h[22..38].copy_from_slice(salt);
    h[38..50].copy_from_slice(nonce);
    h
}

pub(crate) fn seal(plain: &[u8], code: &[u8], kdf: KdfParams) -> Result<Vec<u8>> {
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    rand::thread_rng().fill_bytes(&mut salt);
    rand::thread_rng().fill_bytes(&mut nonce);
    let head = header(kdf, &salt, &nonce);
    let key = derive_key(code, &salt, kdf)?;
    let ct = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()))
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad: &head })
        .map_err(|_| CryptoError::Encrypt { reason: "recovery file".into() })?;
    let mut out = Vec::with_capacity(HEADER_LEN + ct.len());
    out.extend_from_slice(&head);
    out.extend_from_slice(&ct);
    Ok(out)
}

/// The KDF parameters in a file's header, checked against what a phone will run.
fn header_params(file: &[u8]) -> Result<KdfParams> {
    if file.len() < 10 || &file[..8] != MAGIC {
        return Err(CryptoError::Recovery { problem: RecoveryProblem::NotARecoveryFile });
    }
    if file[8] != FORMAT_VERSION || file[9] != KDF_ARGON2ID {
        return Err(CryptoError::Recovery { problem: RecoveryProblem::NewerVersion });
    }
    if file.len() < HEADER_LEN + TAG_LEN {
        return Err(CryptoError::Recovery { problem: RecoveryProblem::WrongCodeOrDamaged });
    }
    let word = |at: usize| u32::from_be_bytes(file[at..at + 4].try_into().expect("four bytes"));
    let kdf = KdfParams { memory_kib: word(10), passes: word(14), lanes: word(18) };
    let sane = (1..=MAX_LANES).contains(&kdf.lanes)
        && (1..=MAX_PASSES).contains(&kdf.passes)
        && kdf.memory_kib >= 8 * kdf.lanes
        && kdf.memory_kib <= MAX_MEMORY_KIB;
    if !sane {
        return Err(CryptoError::Recovery { problem: RecoveryProblem::WrongCodeOrDamaged });
    }
    Ok(kdf)
}

pub(crate) fn open(file: &[u8], code: &str) -> Result<Zeroizing<Vec<u8>>> {
    let kdf = header_params(file)?;
    let canonical = canonical_code(code).ok_or(CryptoError::Recovery { problem: RecoveryProblem::CodeMistyped })?;
    let (head, ct) = file.split_at(HEADER_LEN);
    let key = derive_key(&canonical, &head[22..38], kdf)?;
    let plain = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()))
        .decrypt(Nonce::from_slice(&head[38..50]), Payload { msg: ct, aad: head })
        .map_err(|_| CryptoError::Recovery { problem: RecoveryProblem::WrongCodeOrDamaged })?;
    Ok(Zeroizing::new(plain))
}

// ── Tests ─────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{PeerKeys, SignedKey};

    /// Small enough for a debug build to run quickly; the format is the same.
    const FAST: KdfParams = KdfParams { memory_kib: 64, passes: 1, lanes: 1 };

    fn peer_keys(id: &Identity) -> PeerKeys {
        let b = id.public_bundle();
        PeerKeys { ed25519: b.ed25519, curve25519: b.curve25519, sealing: b.sealing, signature: b.signature, sealing_prekey: b.sealing_prekey }
    }

    fn file_for(id: &Identity, app: &[u8], code: &str) -> Vec<u8> {
        id.recovery_file_with(app, code, FAST).unwrap()
    }

    fn problem(r: Result<impl Sized>) -> RecoveryProblem {
        match r {
            Err(CryptoError::Recovery { problem }) => problem,
            Err(other) => panic!("expected a recovery problem, got {other:?}"),
            Ok(_) => panic!("expected a recovery problem, got success"),
        }
    }

    #[test]
    fn codes_are_grouped_checked_and_forgiving() {
        let code = recovery_code_generate();
        assert_eq!(code.len(), CODE_LEN + CODE_LEN / CODE_GROUP - 1);
        assert_eq!(code.split('-').count(), 7);
        assert!(code.split('-').all(|g| g.len() == 4));
        let canonical = recovery_code_normalize(code.clone()).unwrap();
        assert_eq!(canonical, code.replace('-', ""));
        // Lower case, spaces, and the letters Crockford reads as digits.
        let sloppy = code.to_lowercase().replace('-', " ").replace('0', "o").replace('1', "l");
        assert_eq!(recovery_code_normalize(sloppy).unwrap(), canonical);
        // One symbol changed: the check symbols catch it.
        let mut wrong = canonical.clone().into_bytes();
        wrong[5] = if wrong[5] == b'A' { b'B' } else { b'A' };
        assert!(recovery_code_normalize(String::from_utf8(wrong).unwrap()).is_none());
        assert!(recovery_code_normalize(canonical[..27].to_string()).is_none());
        assert!(recovery_code_normalize(format!("{canonical}A")).is_none());
        assert!(recovery_code_normalize(canonical.replacen(&canonical[..1], "U", 1)).is_none());
        assert_ne!(recovery_code_generate(), code);
    }

    #[test]
    fn the_default_cost_is_what_the_docs_say() {
        assert_eq!(DEFAULT_KDF, KdfParams { memory_kib: 65536, passes: 3, lanes: 1 });
        let head = header(DEFAULT_KDF, &[7; SALT_LEN], &[9; NONCE_LEN]);
        let mut file = head.to_vec();
        file.extend_from_slice(&[0; TAG_LEN]);
        assert_eq!(header_params(&file).unwrap(), DEFAULT_KDF);
    }

    #[test]
    fn round_trip_keeps_the_identity_and_the_app_record() {
        let alice = Identity::create();
        alice.generate_one_time_keys(5);
        let code = recovery_code_generate();
        let app = br#"{"number":"123456789","contacts":[]}"#;
        let file = file_for(&alice, app, &code);
        assert_eq!(&file[..8], MAGIC);
        assert_eq!(file[8], FORMAT_VERSION);

        let restored = recovery_open(file, code.to_lowercase()).unwrap();
        assert_eq!(restored.app_data, app.to_vec());
        let id = restored.identity;
        assert_eq!(id.ed25519(), alice.ed25519());
        assert_eq!(id.curve25519(), alice.curve25519());
        let (before, after) = (alice.public_bundle(), id.public_bundle());
        // The keys contacts pinned, and the signature binding them, are the same.
        assert_eq!(after.sealing, before.sealing);
        assert_eq!(after.signature, before.signature);
        // No one-time key came back, and the fallback key is a new one.
        assert!(after.one_time_keys.is_empty());
        assert_ne!(after.fallback.as_ref().unwrap().key, before.fallback.as_ref().unwrap().key);
        // A new sealing prekey, newer than the old one.
        assert!(after.sealing_prekey.as_ref().unwrap().created_ms >= before.sealing_prekey.as_ref().unwrap().created_ms);
        assert_ne!(after.sealing_prekey.unwrap().key, before.sealing_prekey.unwrap().key);
    }

    #[test]
    fn no_session_and_no_one_time_key_survives_and_contacts_see_the_same_safety_number() {
        let alice = Identity::create();
        let bob = Identity::create();
        let carol = Identity::create();
        // A session each way between Alice and Bob.
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.decrypt(alice.encrypt(peer_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        alice.decrypt(bob.encrypt(peer_keys(&alice), b"hello".to_vec()).unwrap()).unwrap();
        // Alice publishes one-time keys; Carol takes one but has not written yet.
        alice.generate_one_time_keys(3);
        let otk: SignedKey = alice.public_bundle().one_time_keys.into_iter().next().unwrap();
        alice.mark_keys_published();
        let old_ids: Vec<String> = vec![otk.id.clone()];
        carol.start_session(peer_keys(&alice), otk).unwrap();

        let code = recovery_code_generate();
        let restored = recovery_open(file_for(&alice, b"{}", &code), code).unwrap().identity;
        // Sessions were not restored.
        assert!(!restored.has_session(bob.curve25519()));
        // Bob's safety number for Alice is unchanged.
        assert_eq!(bob.safety_number(restored.ed25519()).unwrap(), bob.safety_number(alice.ed25519()).unwrap());

        // What Bob sends on his old session cannot be read, and says who to ask for a new one.
        let stale = bob.encrypt(peer_keys(&restored), b"on the old session".to_vec()).unwrap();
        assert!(matches!(restored.decrypt(stale), Err(CryptoError::NoSessionFor { .. })));
        // Carol's first message, built on a one-time key the file never held, cannot start a session.
        let on_old_key = carol.encrypt(peer_keys(&restored), b"first".to_vec()).unwrap();
        assert!(matches!(restored.decrypt(on_old_key), Err(CryptoError::DecryptFrom { .. })));

        // A fresh session from the restored phone is accepted by Bob as Alice's.
        restored.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let fresh = restored.encrypt(peer_keys(&bob), b"back".to_vec()).unwrap();
        let got = bob.decrypt(fresh).unwrap();
        assert_eq!(got.plaintext, b"back");
        assert_eq!(got.sender_ed25519, alice.ed25519());
        // And Bob answering on it reaches the restored phone.
        let reply = bob.encrypt(peer_keys(&restored), b"welcome back".to_vec()).unwrap();
        assert_eq!(restored.decrypt(reply).unwrap().plaintext, b"welcome back");

        // New one-time keys do not reuse an id the old phone handed out.
        restored.generate_one_time_keys(3);
        assert!(restored.public_bundle().one_time_keys.iter().all(|k| !old_ids.contains(&k.id)));
    }

    #[test]
    fn the_file_holds_no_one_time_key_secret() {
        let alice = Identity::create();
        alice.generate_one_time_keys(10);
        let code = recovery_code_generate();
        let plain = open(&file_for(&alice, b"", &code), &code).unwrap();
        let state: serde_json::Value = serde_json::from_slice(&plain).unwrap();
        let otks = &state["account"]["one_time_keys"];
        assert_eq!(otks["private_keys"].as_object().unwrap().len(), 0);
        assert_eq!(otks["public_keys"].as_object().unwrap().len(), 0);
        assert!(state["account"]["fallback_keys"]["fallback_key"].is_null());
        assert!(state["account"]["fallback_keys"]["previous_fallback_key"].is_null());
        assert!(state.get("sessions").is_none());
        assert!(state.get("used_prekeys").is_none());
    }

    #[test]
    fn a_wrong_code_does_not_open_it() {
        let alice = Identity::create();
        let file = file_for(&alice, b"x", &recovery_code_generate());
        assert_eq!(problem(recovery_open(file.clone(), recovery_code_generate())), RecoveryProblem::WrongCodeOrDamaged);
        assert_eq!(problem(recovery_open(file, "ABCD-EFGH".into())), RecoveryProblem::CodeMistyped);
    }

    #[test]
    fn a_changed_header_or_body_does_not_open_it() {
        let alice = Identity::create();
        let code = recovery_code_generate();
        let file = file_for(&alice, b"x", &code);
        // Every byte of the header that is not the magic or the version is
        // authenticated: passes, memory, lanes, salt, nonce.
        for at in [13usize, 17, 21, 22, 37, 38, 49] {
            let mut bad = file.clone();
            bad[at] ^= 0x01;
            let p = problem(recovery_open(bad, code.clone()));
            assert!(matches!(p, RecoveryProblem::WrongCodeOrDamaged), "byte {at}: {p:?}");
        }
        for at in [HEADER_LEN, file.len() / 2, file.len() - 1] {
            let mut bad = file.clone();
            bad[at] ^= 0x80;
            assert_eq!(problem(recovery_open(bad, code.clone())), RecoveryProblem::WrongCodeOrDamaged);
        }
        assert_eq!(problem(recovery_open(file[..file.len() - 1].to_vec(), code.clone())), RecoveryProblem::WrongCodeOrDamaged);
        assert_eq!(problem(recovery_open(file[..HEADER_LEN].to_vec(), code)), RecoveryProblem::WrongCodeOrDamaged);
    }

    #[test]
    fn unknown_versions_and_other_files_are_named() {
        let alice = Identity::create();
        let code = recovery_code_generate();
        let file = file_for(&alice, b"x", &code);
        let mut newer = file.clone();
        newer[8] = FORMAT_VERSION + 1;
        assert_eq!(problem(recovery_open(newer, code.clone())), RecoveryProblem::NewerVersion);
        let mut other_kdf = file.clone();
        other_kdf[9] = 2;
        assert_eq!(problem(recovery_open(other_kdf, code.clone())), RecoveryProblem::NewerVersion);
        let mut not_ours = file.clone();
        not_ours[0] = b'X';
        assert_eq!(problem(recovery_open(not_ours, code.clone())), RecoveryProblem::NotARecoveryFile);
        assert_eq!(problem(recovery_open(b"%PDF-1.7".to_vec(), code.clone())), RecoveryProblem::NotARecoveryFile);
        // A state version this build does not know, inside a file that opens.
        let plain = br#"{"v":9}"#;
        let canonical = recovery_code_normalize(code.clone()).unwrap();
        let sealed = seal(plain, canonical.as_bytes(), FAST).unwrap();
        assert!(matches!(recovery_open(sealed, code), Err(CryptoError::Recovery { .. })));
    }

    #[test]
    fn a_file_asking_for_too_much_memory_is_refused_before_the_kdf_runs() {
        let mut file = header(KdfParams { memory_kib: MAX_MEMORY_KIB + 1, passes: 1, lanes: 1 }, &[0; SALT_LEN], &[0; NONCE_LEN]).to_vec();
        file.extend_from_slice(&[0; 64]);
        assert_eq!(problem(recovery_open(file, recovery_code_generate())), RecoveryProblem::WrongCodeOrDamaged);
    }

    #[test]
    fn two_restores_of_one_file_hand_out_different_key_ids() {
        let alice = Identity::create();
        let code = recovery_code_generate();
        let file = file_for(&alice, b"", &code);
        let a = recovery_open(file.clone(), code.clone()).unwrap().identity;
        let b = recovery_open(file, code).unwrap().identity;
        a.generate_one_time_keys(1);
        b.generate_one_time_keys(1);
        assert_ne!(a.public_bundle().one_time_keys[0].id, b.public_bundle().one_time_keys[0].id);
        assert_ne!(a.public_bundle().fallback.unwrap().id, b.public_bundle().fallback.unwrap().id);
    }
}
