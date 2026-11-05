#pragma author Clairix contributors
#pragma description Clairix sealed envelope, outer layer (comms-crypto/src/lib.rs, seal())
#pragma endian big

// An envelope as the relay stores and forwards it: one version byte, the
// sender's ephemeral X25519 key, then a ChaCha20-Poly1305 box (zero nonce,
// single-use key) holding the inner message. The relay cannot open it.
//
// Version 2: sealed to the recipient's long-term sealing key.
// Version 3: sealed to the recipient's current sealing prekey (forward secrecy).
//
// To see the fields inside, decrypt the box with the recipient's sealing
// secret and open the result with clairix_envelope_inner.hexpat.

import std.mem;
import std.core;

enum EnvelopeVersion : u8 {
    V2 = 2,
    V3 = 3
};

struct Envelope {
    EnvelopeVersion version;
    u8 ephemeral_x25519[32];
    // Everything after the key is ciphertext followed by the 16-byte Poly1305 tag.
    // seal() always emits at least the tag; unseal() rejects anything under 1+32+16 bytes.
    u8 ciphertext[std::mem::size() - 33 - 16] [[sealed]];
    u8 poly1305_tag[16];
};

// The 1+32+16 minimum is enforced by the crate; shorter files do not parse.
std::assert(std::mem::size() >= 1 + 32 + 16, "shorter than the 49-byte minimum an envelope can have");

Envelope envelope @ 0x00;
