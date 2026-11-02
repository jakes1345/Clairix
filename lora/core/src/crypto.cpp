#include "clx/crypto.h"

#include "monocypher/monocypher-ed25519.h"
#include "monocypher/monocypher.h"

namespace clx {

void sha512(uint8_t out[64], const uint8_t* msg, size_t n) { crypto_sha512(out, msg, n); }

void hmacSha512(uint8_t out[64], const uint8_t* key, size_t keyLen, const uint8_t* msg, size_t n) {
    crypto_sha512_hmac(out, key, keyLen, msg, n);
}

void hkdfSha512(uint8_t* okm, size_t okmLen, const uint8_t* ikm, size_t ikmLen,
                const uint8_t* salt, size_t saltLen, const uint8_t* info, size_t infoLen) {
    crypto_sha512_hkdf(okm, okmLen, ikm, ikmLen, salt, saltLen, info, infoLen);
}

void x25519Public(uint8_t pub[32], const uint8_t secret[32]) { crypto_x25519_public_key(pub, secret); }

bool x25519(uint8_t shared[32], const uint8_t secret[32], const uint8_t theirPublic[32]) {
    crypto_x25519(shared, secret, theirPublic);
    uint8_t acc = 0;
    for (int i = 0; i < 32; i++) acc |= shared[i];
    return acc != 0;
}

void chacha20(uint8_t* out, const uint8_t* in, size_t n, const uint8_t key[32], const uint8_t nonce[12], uint32_t counter) {
    crypto_chacha20_ietf(out, in, n, key, nonce, counter);
}

void aeadSeal(uint8_t* out, uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n) {
    crypto_aead_ctx ctx;
    crypto_aead_init_ietf(&ctx, key, nonce);
    crypto_aead_write(&ctx, out, tag, ad, adLen, in, n);
    crypto_wipe(&ctx, sizeof ctx);
}

bool aeadOpen(uint8_t* out, const uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[12],
              const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n) {
    crypto_aead_ctx ctx;
    crypto_aead_init_ietf(&ctx, key, nonce);
    int r = crypto_aead_read(&ctx, out, tag, ad, adLen, in, n);
    crypto_wipe(&ctx, sizeof ctx);
    return r == 0;
}

void xaeadSeal(uint8_t* out, uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[24],
               const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n) {
    crypto_aead_lock(out, tag, key, nonce, ad, adLen, in, n);
}

bool xaeadOpen(uint8_t* out, const uint8_t tag[16], const uint8_t key[32], const uint8_t nonce[24],
               const uint8_t* ad, size_t adLen, const uint8_t* in, size_t n) {
    return crypto_aead_unlock(out, tag, key, nonce, ad, adLen, in, n) == 0;
}

void argon2id(uint8_t* out, size_t outLen, const uint8_t* pass, size_t passLen, const uint8_t salt[16],
              uint32_t blocks, uint32_t passes, void* work) {
    crypto_argon2_config config = {CRYPTO_ARGON2_ID, blocks, passes, 1};
    crypto_argon2_inputs inputs = {pass, salt, uint32_t(passLen), 16};
    crypto_argon2(out, uint32_t(outLen), work, config, inputs, crypto_argon2_no_extras);
}

bool equalCt(const uint8_t* a, const uint8_t* b, size_t n) {
    uint8_t acc = 0;
    for (size_t i = 0; i < n; i++) acc |= uint8_t(a[i] ^ b[i]);
    // Branch-free down to here; the one data-independent branch is on the result.
    return acc == 0;
}

void wipe(void* p, size_t n) { crypto_wipe(p, n); }

}  // namespace clx
