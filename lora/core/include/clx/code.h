// Text forms of keys that people read out, type in or compare: Crockford
// base32 (no I, L, O or U; reading I and L as 1 and O as 0), grouped in fives.
#pragma once

#include <cstdint>
#include <string>

#include "clx/bytes.h"

namespace clx {

std::string base32(const uint8_t* p, size_t n);
/** Ignores spaces and dashes, any case; false for any other character. */
bool unbase32(const std::string& s, Bytes& out);

/**
 * A network code: the 32-byte network key and a 2-byte check
 * (SHA-512("clairix-lora-netcode" || key), first 2 bytes), in base32,
 * 55 characters in groups of five. A typing mistake fails the check.
 */
std::string networkCode(const uint8_t key[32]);
bool parseNetworkCode(const std::string& code, uint8_t key[32]);

/** A Lite identity's short fingerprint, "XXXX-XXXX": SHA-512("clairix-lora-id" || public key), first 5 bytes. */
std::string fingerprint(const uint8_t pub[32]);

/** Splits [s] into groups of [n] with [sep]. */
std::string grouped(const std::string& s, size_t n, char sep);

}  // namespace clx
