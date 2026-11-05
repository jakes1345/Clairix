#pragma author Clairix contributors
#pragma description Clairix sealed on-disk store (timeline, location log, cell baseline, trips)
#pragma magic [ 43 4C 58 53 31 0A ] @ 0x00
#pragma endian big

// android/app/src/main/java/app/clairix/Store.kt + StoreCipher.kt:
//   "CLXS1\n" | IV (12) | AES-256-GCM ciphertext | tag (16)
// The key lives in the Android Keystore and never leaves the phone, so the body
// cannot be opened on a PC. This pattern shows the container and lets you check
// that a copy is whole. A file starting with '{' is an older plain-JSON store
// that the app reseals on first load.

import std.mem;
import std.core;

struct SealedStore {
    char magic[6];                 // "CLXS1\n"
    u8 gcm_iv[12];
    u8 ciphertext[std::mem::size() - 6 - 12 - 16] [[sealed]];
    u8 gcm_tag[16];
};

std::assert(std::mem::size() > 6 + 12 + 16, "shorter than a sealed store can be");

SealedStore store @ 0x00;
