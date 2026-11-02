// The primitives the Clairix LoRa core uses, all from Monocypher (vendored in
// src/monocypher), so the boards and the host tests run one implementation.
// The Android side computes the same things with RustCrypto in comms-crypto
// (src/lora.rs); the known-answer tests in both pin them to each other.
#pragma once

#include <cstddef>
#include <cstdint>

namespace clx {

/**
 * Fills [out] with cryptographically secure random bytes. Each platform
 * provides it: the ESP32 hardware RNG on the boards (esp_fill_random, with the
 * radio on so it is seeded from RF noise), /dev/urandom on the host.
 */
void randomBytes(uint8_t* out, size_t n);

void sha512(uint8_t out[64], const uint8_t* msg, size_t n);
void hmacSha512(uint8_t out[64], const uint8_t* key, size_t keyLen, const uint8_t* msg, size_t n);
/** RFC 5869 HKDF with SHA-512. */
void hkdfSha512(uint8_t* okm, size_t okmLen, const uint8_t* ikm, size_t ikmLen,
                const uint8_t* salt, size_t saltLen, const uint8_t* info, size_t infoLen);

void x25519Public(uint8_t pub[32], const uint8_t secret[32]);
/** Returns false when the shared secret is all zeros (a low-order public key). */
bool x25519(uint8_t shared[32], const uint8_t secret[32], const uint8_t theirPublic[32]);

/** RFC 8439 ChaCha20 keystream XOR, 96-bit nonce, starting at block [counter]. */
void chacha20(uint8_t* out, const uint8_t* in, size_t n, const uint8_t key[32], const uint8_t nonce[12], uint32_t counter);

/** RFC 8439 ChaCha20-Poly1305: [out] gets the ciphertext, [tag] the 16-byte tag. */
void aeadSeal(uint8_t* out, uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n);
/** Returns false (and writes nothing useful) when the tag does not verify. */
bool aeadOpen(uint8_t* out, const uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n);

/** XChaCha20-Poly1305 (24-byte nonce), for the handheld's vault on flash. */
void xaeadSeal(uint8_t* out, uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[24],
               const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n);
bool xaeadOpen(uint8_t* out, const uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[24],
               const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n);

/**
 * Argon2id over [pass] and a 16-byte [salt] into [out]. [blocks] KiB of
 * [work] (blocks * 1024 bytes) is the memory cost.
 */
void argon2id(uint8_t* out, size_t outLen, const uint8_t* pass, size_t passLen, const uint8_t salt[16],
              uint32_t blocks, uint32_t passes, void* work);

/** Constant-time comparison of [n] bytes. */
bool equalCt(const uint8_t* a, const uint8_t* b, size_t n);

void wipe(void* p, size_t n);

}  // namespace clx
