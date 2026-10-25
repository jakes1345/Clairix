//! The relay's half of Clairix's blind send tokens, compiled to WebAssembly for
//! the Worker (src/tokens.ts loads it).
//!
//! RFC 9497 VOPRF over ristretto255 with SHA-512, the construction Privacy Pass
//! uses for privately verifiable tokens. A phone picks a random input, blinds
//! it and sends only the blinded element; the relay multiplies it by its secret
//! key and proves, with a DLEQ proof, that it used the key it publishes; the
//! phone unblinds the answer into the token. Later the phone spends the token
//! by showing the input and the output. The relay can check that it made the
//! output, but it never saw the input before, so it cannot tell which account
//! the token was issued to.
//!
//! The phone runs the same crate (comms-crypto), so both ends are one
//! implementation. Every key is derived from a 32-byte relay secret and an info
//! string naming the token kind and the key's epoch (DeriveKeyPair, RFC 9497
//! section 3.2.1), so nothing but the secret has to be stored.
//!
//! The interface is a fixed buffer, read and written by the caller, and four
//! functions; there are no imports. Layouts are given on each function. Every
//! function returns 0 on success and a positive code on failure.

use core::cell::UnsafeCell;

use rand_chacha::ChaCha20Rng;
use rand_core::SeedableRng;
use subtle::ConstantTimeEq;
use voprf::{BlindedElement, Group, Ristretto255, VoprfServer};

type Suite = Ristretto255;

/// The most blinded elements one evaluation takes.
pub const MAX_BATCH: usize = 100;
/// The longest info string (kind and epoch) a key is derived with.
pub const MAX_INFO: usize = 64;
/// The longest token input.
pub const MAX_INPUT: usize = 64;
pub const ELEMENT_LEN: usize = 32;
pub const PROOF_LEN: usize = 64;
pub const OUTPUT_LEN: usize = 64;
const SEED_LEN: usize = 32;
const BUF_LEN: usize = 2 * SEED_LEN + MAX_INFO + MAX_BATCH * ELEMENT_LEN + PROOF_LEN;

pub const OK: i32 = 0;
/// A length out of range.
pub const ERR_LENGTH: i32 = 1;
/// A blinded element that is not a valid ristretto255 point, or is the identity.
pub const ERR_ELEMENT: i32 = 2;
/// Key derivation or evaluation failed.
pub const ERR_CRYPTO: i32 = 3;
/// The output does not match: not a token this key made.
pub const ERR_MISMATCH: i32 = 4;

struct Buffer(UnsafeCell<[u8; BUF_LEN]>);
// SAFETY: WebAssembly here is single-threaded; the Worker calls one function at
// a time and reads the buffer before the next call.
unsafe impl Sync for Buffer {}
static BUFFER: Buffer = Buffer(UnsafeCell::new([0; BUF_LEN]));

fn buf() -> &'static mut [u8; BUF_LEN] {
    // SAFETY: see Buffer; no reference outlives the exported call that took it.
    unsafe { &mut *BUFFER.0.get() }
}

/// Where the caller reads and writes, and how long it is.
#[no_mangle]
pub extern "C" fn buffer() -> *mut u8 {
    BUFFER.0.get() as *mut u8
}

#[no_mangle]
pub extern "C" fn buffer_len() -> usize {
    BUF_LEN
}

fn server(seed: &[u8], info: &[u8]) -> Result<VoprfServer<Suite>, i32> {
    VoprfServer::<Suite>::new_from_seed(seed, info).map_err(|_| ERR_CRYPTO)
}

/// The public key for a relay secret and an info string.
/// In: secret (32) | info (`info_len`). Out: public key (32).
#[no_mangle]
pub extern "C" fn public_key(info_len: usize) -> i32 {
    match public_key_inner(info_len) {
        Ok(()) => OK,
        Err(e) => e,
    }
}

fn public_key_inner(info_len: usize) -> Result<(), i32> {
    if info_len > MAX_INFO {
        return Err(ERR_LENGTH);
    }
    let b = buf();
    let s = server(&b[..SEED_LEN], &b[SEED_LEN..SEED_LEN + info_len])?;
    let pk = Suite::serialize_elem(s.get_public_key());
    b[..ELEMENT_LEN].copy_from_slice(&pk);
    Ok(())
}

/// Evaluates `n` blinded elements with one DLEQ proof over all of them.
/// In: secret (32) | randomness for the proof (32) | info (`info_len`) | n blinded elements (32 each).
/// Out: n evaluated elements (32 each) | proof (64).
#[no_mangle]
pub extern "C" fn evaluate_batch(info_len: usize, n: usize) -> i32 {
    match evaluate_batch_inner(info_len, n) {
        Ok(()) => OK,
        Err(e) => e,
    }
}

fn evaluate_batch_inner(info_len: usize, n: usize) -> Result<(), i32> {
    if info_len > MAX_INFO || n == 0 || n > MAX_BATCH {
        return Err(ERR_LENGTH);
    }
    let b = buf();
    let s = server(&b[..SEED_LEN], &b[2 * SEED_LEN..2 * SEED_LEN + info_len])?;
    let mut rng_seed = [0u8; SEED_LEN];
    rng_seed.copy_from_slice(&b[SEED_LEN..2 * SEED_LEN]);
    let mut rng = ChaCha20Rng::from_seed(rng_seed);
    let at = 2 * SEED_LEN + info_len;
    let mut blinded = Vec::with_capacity(n);
    for i in 0..n {
        let bytes = &b[at + i * ELEMENT_LEN..at + (i + 1) * ELEMENT_LEN];
        let element = BlindedElement::<Suite>::deserialize(bytes).map_err(|_| ERR_ELEMENT)?;
        blinded.push(element);
    }
    let result = s.batch_blind_evaluate(&mut rng, &blinded).map_err(|_| ERR_CRYPTO)?;
    for (i, message) in result.messages.iter().enumerate() {
        b[i * ELEMENT_LEN..(i + 1) * ELEMENT_LEN].copy_from_slice(&message.serialize());
    }
    b[n * ELEMENT_LEN..n * ELEMENT_LEN + PROOF_LEN].copy_from_slice(&result.proof.serialize());
    Ok(())
}

/// Checks a token: whether `output` is what this key makes of `input`, compared
/// in constant time. 0 when it is, [`ERR_MISMATCH`] when it is not.
/// In: secret (32) | info (`info_len`) | input (`input_len`) | output (64).
#[no_mangle]
pub extern "C" fn verify(info_len: usize, input_len: usize) -> i32 {
    match verify_inner(info_len, input_len) {
        Ok(()) => OK,
        Err(e) => e,
    }
}

fn verify_inner(info_len: usize, input_len: usize) -> Result<(), i32> {
    if info_len > MAX_INFO || input_len == 0 || input_len > MAX_INPUT {
        return Err(ERR_LENGTH);
    }
    let b = buf();
    let s = server(&b[..SEED_LEN], &b[SEED_LEN..SEED_LEN + info_len])?;
    let at = SEED_LEN + info_len;
    let input = &b[at..at + input_len];
    let output = &b[at + input_len..at + input_len + OUTPUT_LEN];
    let expected = s.evaluate(input).map_err(|_| ERR_CRYPTO)?;
    if bool::from(expected.as_slice().ct_eq(output)) {
        Ok(())
    } else {
        Err(ERR_MISMATCH)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use voprf::{EvaluationElement, Proof, VoprfClient};

    /// The tests share the one buffer; they take turns.
    static TURN: Mutex<()> = Mutex::new(());

    fn put(parts: &[&[u8]]) {
        let b = buf();
        let mut at = 0;
        for p in parts {
            b[at..at + p.len()].copy_from_slice(p);
            at += p.len();
        }
    }

    #[test]
    fn a_token_issued_blind_is_accepted_once_unblinded_and_nothing_else_is() {
        let _turn = TURN.lock().unwrap();
        let secret = [7u8; 32];
        let info = b"clairix-token-v2|delivery|1500";
        put(&[&secret, info]);
        assert_eq!(public_key(info.len()), OK);
        let pk = Suite::deserialize_elem(&buf()[..32]).unwrap();

        let mut rng = ChaCha20Rng::from_seed([1; 32]);
        let inputs: Vec<[u8; 32]> = (0..5u8).map(|i| [i + 10; 32]).collect();
        let blinds: Vec<_> = inputs.iter().map(|i| VoprfClient::<Suite>::blind(i, &mut rng).unwrap()).collect();
        let mut req: Vec<u8> = Vec::new();
        req.extend_from_slice(&secret);
        req.extend_from_slice(&[9u8; 32]);
        req.extend_from_slice(info);
        for bl in &blinds {
            req.extend_from_slice(&bl.message.serialize());
        }
        put(&[&req]);
        assert_eq!(evaluate_batch(info.len(), blinds.len()), OK);
        let b = buf();
        let evaluated: Vec<_> =
            (0..5).map(|i| EvaluationElement::<Suite>::deserialize(&b[i * 32..(i + 1) * 32]).unwrap()).collect();
        let proof = Proof::<Suite>::deserialize(&b[160..224]).unwrap();
        let clients: Vec<_> = blinds.iter().map(|bl| bl.state.clone()).collect();
        let outputs: Vec<_> = VoprfClient::batch_finalize(&inputs, &clients, &evaluated, &proof, pk)
            .unwrap()
            .map(|o| o.unwrap())
            .collect();

        for (input, output) in inputs.iter().zip(&outputs) {
            put(&[&secret, info, input, output]);
            assert_eq!(verify(info.len(), 32), OK);
        }
        // Another kind or epoch, another key: the token is not accepted there.
        let other = b"clairix-token-v2|traffic|1500";
        put(&[&secret, other, &inputs[0], &outputs[0]]);
        assert_eq!(verify(other.len(), 32), ERR_MISMATCH);
        // A made-up output, or another token's.
        put(&[&secret, info, &inputs[0], &outputs[1]]);
        assert_eq!(verify(info.len(), 32), ERR_MISMATCH);
        put(&[&secret, info, &inputs[0], &[0u8; 64]]);
        assert_eq!(verify(info.len(), 32), ERR_MISMATCH);
        // A proof checked against another key fails: the phone notices a relay
        // that evaluates with a key it does not publish.
        put(&[&[8u8; 32], info]);
        assert_eq!(public_key(info.len()), OK);
        let wrong = Suite::deserialize_elem(&buf()[..32]).unwrap();
        assert!(VoprfClient::batch_finalize(&inputs, &clients, &evaluated, &proof, wrong).is_err());
    }

    #[test]
    fn bad_elements_and_lengths_are_refused() {
        let _turn = TURN.lock().unwrap();
        put(&[&[1u8; 32], &[2u8; 32], b"i", &[0xffu8; 32]]);
        assert_eq!(evaluate_batch(1, 1), ERR_ELEMENT);
        put(&[&[1u8; 32], &[2u8; 32], b"i", &[0u8; 32]]);
        assert_eq!(evaluate_batch(1, 1), ERR_ELEMENT, "the identity is refused");
        assert_eq!(evaluate_batch(1, 0), ERR_LENGTH);
        assert_eq!(evaluate_batch(1, MAX_BATCH + 1), ERR_LENGTH);
        assert_eq!(evaluate_batch(MAX_INFO + 1, 1), ERR_LENGTH);
        assert_eq!(verify(1, 0), ERR_LENGTH);
        assert_eq!(verify(1, MAX_INPUT + 1), ERR_LENGTH);
    }
}
