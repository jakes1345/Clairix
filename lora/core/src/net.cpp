#include "clx/net.h"

#include <cstring>

#include "clx/crypto.h"
#include "clx/region.h"

namespace clx {

static const char PUBLIC_LABEL[] = "clairix-lora public network v1";
static const char SALT[] = "clairix-lora-v1";

void publicNetworkKey(uint8_t out[32]) {
    uint8_t h[64];
    sha512(h, reinterpret_cast<const uint8_t*>(PUBLIC_LABEL), sizeof PUBLIC_LABEL - 1);
    std::memcpy(out, h, 32);
}

bool isPublicNetworkKey(const uint8_t key[32]) {
    uint8_t pub[32];
    publicNetworkKey(pub);
    return equalCt(pub, key, 32);
}

static void expand(uint8_t* out, size_t n, const uint8_t key[32], const char* info) {
    hkdfSha512(out, n, key, 32, reinterpret_cast<const uint8_t*>(SALT), sizeof SALT - 1,
               reinterpret_cast<const uint8_t*>(info), std::strlen(info));
}

void deriveNet(const uint8_t key[32], NetKeys& out) {
    expand(out.enc, 32, key, "enc");
    expand(out.mac, 32, key, "mac");
    expand(out.id, 8, key, "id");
}

static void frameTag(const NetKeys& k, const uint8_t* headerAndCipher, size_t n, uint8_t tag[TAG_LEN]) {
    uint8_t full[64];
    hmacSha512(full, k.mac, 32, headerAndCipher, n);
    std::memcpy(tag, full, TAG_LEN);
}

void makeNonce(uint32_t netSeconds, uint8_t quality, uint8_t out[NONCE_LEN]) {
    put32(out, netSeconds);
    randomBytes(out + 4, 4);
    out[4] = uint8_t((out[4] & 0x3F) | ((quality & 3) << 6));
}

uint32_t nonceTime(const uint8_t nonce[NONCE_LEN]) { return get32(nonce); }
uint8_t nonceQuality(const uint8_t nonce[NONCE_LEN]) { return nonce[4] >> 6; }

uint8_t hopChannel(const NetKeys& k, uint32_t slot) {
    uint8_t m[7] = {'h', 'o', 'p'};
    put32(m + 3, slot);
    uint8_t h[64];
    hmacSha512(h, k.id, sizeof k.id, m, sizeof m);
    return uint8_t(((uint32_t(h[0]) << 8) | h[1]) % CHANNEL_COUNT);
}

uint8_t frameHint(const NetKeys& k, const uint8_t nonce[NONCE_LEN]) {
    uint8_t h[64];
    hmacSha512(h, k.id, sizeof k.id, nonce, NONCE_LEN);
    return h[0];
}

static void nonce12(const uint8_t nonce[NONCE_LEN], uint8_t out[12]) {
    std::memset(out, 0, 4);
    std::memcpy(out + 4, nonce, NONCE_LEN);
}

bool sealFrame(const NetKeys& k, const uint8_t* plain, size_t n, const uint8_t nonce[NONCE_LEN], Bytes& frame) {
    if (n == 0 || n > PLAIN_MAX) return false;
    frame.assign(1 + NONCE_LEN + n + TAG_LEN, 0);
    frame[0] = frameHint(k, nonce);
    std::memcpy(&frame[1], nonce, NONCE_LEN);
    uint8_t iv[12];
    nonce12(nonce, iv);
    chacha20(&frame[1 + NONCE_LEN], plain, n, k.enc, iv, 0);
    frameTag(k, frame.data(), 1 + NONCE_LEN + n, &frame[1 + NONCE_LEN + n]);
    return true;
}

bool openFrame(const NetKeys& k, const uint8_t* frame, size_t n, Bytes& plain) {
    if (n <= FRAME_OVERHEAD || n > FRAME_MAX || frame[0] != frameHint(k, frame + 1)) return false;
    const size_t body = n - FRAME_OVERHEAD;
    uint8_t tag[TAG_LEN];
    frameTag(k, frame, 1 + NONCE_LEN + body, tag);
    if (!equalCt(tag, frame + 1 + NONCE_LEN + body, TAG_LEN)) return false;
    uint8_t iv[12];
    nonce12(frame + 1, iv);
    plain.assign(body, 0);
    chacha20(plain.data(), frame + 1 + NONCE_LEN, body, k.enc, iv, 0);
    return true;
}

Bytes encodePacket(const Packet& p) {
    Bytes out;
    out.reserve(2 + p.body.size());
    out.push_back(uint8_t(p.type));
    out.push_back(uint8_t(((p.hops & 0x0F) << 4) | (p.hopLimit & 0x0F)));
    append(out, p.body.data(), p.body.size());
    return out;
}

bool decodePacket(const uint8_t* plain, size_t n, Packet& out) {
    if (n < 2) return false;
    uint8_t t = plain[0];
    if (t < uint8_t(PacketType::Frag) || t > uint8_t(PacketType::Pair)) return false;
    out.type = PacketType(t);
    out.hops = plain[1] >> 4;
    out.hopLimit = plain[1] & 0x0F;
    if (out.hopLimit > MAX_HOP_LIMIT || out.hops > 15) return false;
    out.body.assign(plain + 2, plain + n);
    return true;
}

uint64_t packetId(const Packet& p) {
    Bytes m;
    m.reserve(1 + p.body.size());
    m.push_back(uint8_t(p.type));
    append(m, p.body.data(), p.body.size());
    uint8_t h[64];
    sha512(h, m.data(), m.size());
    return get64(h);
}

}  // namespace clx
