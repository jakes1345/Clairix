#pragma author Clairix contributors
#pragma description Clairix sealed envelope, inner message (decrypted) - format 2 or 3
#pragma endian big

// Apply to the plaintext you get after opening the outer ChaCha20-Poly1305 box.
// Set INNER_FORMAT to the envelope's version byte (2 or 3).
// Layouts are the tables on INNER_HEADER and INNER_V3_HEADER in comms-crypto/src/lib.rs.
// The Olm ciphertext is itself an Olm message; this pattern stops at it.

import std.mem;
import std.core;

const u8 INNER_FORMAT = 3;

enum OlmType : u8 {
    PreKey = 0,
    Normal = 1
};

// ms since the Unix epoch, big-endian, signed over / MACed by the sender.
using SentAtMs = be u64;

struct InnerV2 {
    OlmType message_type;
    u8 sender_curve25519[32];
    u8 sender_ed25519[32];
    SentAtMs sent_at_ms;
    u8 claim_signature_ed25519[64];
    u8 olm_ciphertext[std::mem::size() - 137];
};

struct InnerV3 {
    OlmType message_type;
    u8 sender_curve25519[32];
    u8 sender_ed25519[32];
    u8 sender_sealing_key[32];
    u8 binding_signature[64];      // same in every envelope from this sender (its bundle's)
    SentAtMs sent_at_ms;
    u8 claim_hmac_sha256[32];      // bound to this envelope by ephemeral key + ciphertext hash
    u32 olm_ciphertext_len;
    u8 olm_ciphertext[olm_ciphertext_len];
    // Zeros up to a multiple of 1024. Anything non-zero is rejected by the app.
    u8 padding[std::mem::size() - 205 - olm_ciphertext_len];
};

if (INNER_FORMAT == 2) {
    std::assert(std::mem::size() > 137, "too short for a format 2 inner message");
    InnerV2 inner @ 0x00;
} else {
    std::assert(std::mem::size() > 205 && std::mem::size() % 1024 == 0,
        "format 3 inner messages are a multiple of 1024 bytes");
    InnerV3 inner @ 0x00;
}
