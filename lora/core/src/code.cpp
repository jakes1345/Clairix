#include "clx/code.h"

#include <cstring>

#include "clx/crypto.h"

namespace clx {

static const char ALPHABET[] = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

std::string base32(const uint8_t* p, size_t n) {
    std::string out;
    uint32_t buf = 0;
    int bits = 0;
    for (size_t i = 0; i < n; i++) {
        buf = (buf << 8) | p[i];
        bits += 8;
        while (bits >= 5) {
            out += ALPHABET[(buf >> (bits - 5)) & 31];
            bits -= 5;
        }
    }
    if (bits > 0) out += ALPHABET[(buf << (5 - bits)) & 31];
    return out;
}

static int value(char c) {
    if (c >= 'a' && c <= 'z') c = char(c - 'a' + 'A');
    if (c == 'I' || c == 'L') c = '1';
    if (c == 'O') c = '0';
    for (int i = 0; i < 32; i++) if (ALPHABET[i] == c) return i;
    return -1;
}

bool unbase32(const std::string& s, Bytes& out) {
    out.clear();
    uint32_t buf = 0;
    int bits = 0;
    for (char c : s) {
        if (c == ' ' || c == '-') continue;
        int v = value(c);
        if (v < 0) return false;
        buf = (buf << 5) | uint32_t(v);
        bits += 5;
        if (bits >= 8) {
            out.push_back(uint8_t(buf >> (bits - 8)));
            bits -= 8;
        }
    }
    // Leftover bits are the zero padding base32() wrote; anything set there is a typo.
    return bits < 5 && (buf & ((1u << bits) - 1)) == 0;
}

std::string grouped(const std::string& s, size_t n, char sep) {
    std::string out;
    for (size_t i = 0; i < s.size(); i++) {
        if (i && i % n == 0) out += sep;
        out += s[i];
    }
    return out;
}

static void check(const uint8_t key[32], uint8_t out[2]) {
    static const char LABEL[] = "clairix-lora-netcode";
    uint8_t m[sizeof LABEL - 1 + 32];
    std::memcpy(m, LABEL, sizeof LABEL - 1);
    std::memcpy(m + sizeof LABEL - 1, key, 32);
    uint8_t h[64];
    sha512(h, m, sizeof m);
    out[0] = h[0];
    out[1] = h[1];
}

std::string networkCode(const uint8_t key[32]) {
    uint8_t all[34];
    std::memcpy(all, key, 32);
    check(key, all + 32);
    return grouped(base32(all, 34), 5, ' ');
}

bool parseNetworkCode(const std::string& code, uint8_t key[32]) {
    Bytes b;
    if (!unbase32(code, b) || b.size() != 34) return false;
    uint8_t c[2];
    check(b.data(), c);
    if (c[0] != b[32] || c[1] != b[33]) return false;
    std::memcpy(key, b.data(), 32);
    return true;
}

std::string fingerprint(const uint8_t pub[32]) {
    static const char LABEL[] = "clairix-lora-id";
    uint8_t m[sizeof LABEL - 1 + 32];
    std::memcpy(m, LABEL, sizeof LABEL - 1);
    std::memcpy(m + sizeof LABEL - 1, pub, 32);
    uint8_t h[64];
    sha512(h, m, sizeof m);
    return grouped(base32(h, 5), 4, '-');
}

}  // namespace clx
