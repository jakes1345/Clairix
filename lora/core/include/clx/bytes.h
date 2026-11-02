// Small byte helpers shared by the Clairix LoRa core. Big-endian on the wire,
// like the rest of Clairix (MeshProtocol uses java.nio's default order).
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace clx {

using Bytes = std::vector<uint8_t>;

inline void put16(uint8_t* p, uint16_t v) { p[0] = uint8_t(v >> 8); p[1] = uint8_t(v); }
inline void put32(uint8_t* p, uint32_t v) {
    p[0] = uint8_t(v >> 24); p[1] = uint8_t(v >> 16); p[2] = uint8_t(v >> 8); p[3] = uint8_t(v);
}
inline void put64(uint8_t* p, uint64_t v) { put32(p, uint32_t(v >> 32)); put32(p + 4, uint32_t(v)); }
inline uint16_t get16(const uint8_t* p) { return uint16_t((p[0] << 8) | p[1]); }
inline uint32_t get32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}
inline uint64_t get64(const uint8_t* p) { return (uint64_t(get32(p)) << 32) | get32(p + 4); }

inline void append(Bytes& out, const uint8_t* p, size_t n) { out.insert(out.end(), p, p + n); }
inline void append16(Bytes& out, uint16_t v) { uint8_t b[2]; put16(b, v); append(out, b, 2); }
inline void append32(Bytes& out, uint32_t v) { uint8_t b[4]; put32(b, v); append(out, b, 4); }
inline void append64(Bytes& out, uint64_t v) { uint8_t b[8]; put64(b, v); append(out, b, 8); }

/** Reads a byte string front to back; every read fails once the input runs short. */
class Reader {
public:
    Reader(const uint8_t* p, size_t n) : p_(p), n_(n) {}
    bool ok() const { return ok_; }
    size_t remaining() const { return ok_ ? n_ - at_ : 0; }
    const uint8_t* here() const { return p_ + at_; }
    uint8_t u8() { if (!need(1)) return 0; return p_[at_++]; }
    uint16_t u16() { if (!need(2)) return 0; uint16_t v = get16(p_ + at_); at_ += 2; return v; }
    uint32_t u32() { if (!need(4)) return 0; uint32_t v = get32(p_ + at_); at_ += 4; return v; }
    uint64_t u64() { if (!need(8)) return 0; uint64_t v = get64(p_ + at_); at_ += 8; return v; }
    bool take(uint8_t* out, size_t n) { if (!need(n)) return false; std::memcpy(out, p_ + at_, n); at_ += n; return true; }
    bool skip(size_t n) { if (!need(n)) return false; at_ += n; return true; }
    Bytes rest() { Bytes r(p_ + at_, p_ + n_); at_ = n_; return r; }
private:
    bool need(size_t n) { if (!ok_ || n_ - at_ < n) { ok_ = false; return false; } return true; }
    const uint8_t* p_;
    size_t n_;
    size_t at_ = 0;
    bool ok_ = true;
};

std::string hex(const uint8_t* p, size_t n);
inline std::string hex(const Bytes& b) { return hex(b.data(), b.size()); }
/** Parses lowercase or uppercase hex; false on anything else or an odd length. */
bool unhex(const std::string& s, Bytes& out);

}  // namespace clx
