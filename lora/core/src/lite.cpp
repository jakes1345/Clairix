#include "clx/lite.h"

#include <cstring>

#include "clx/crypto.h"

namespace clx {

static const char INFO_SENDER[] = "clairix-lora-lite-v1 sender";
static const char INFO_MESSAGE[] = "clairix-lora-lite-v1 message";
static const uint8_t ZERO_NONCE[12] = {0};

LiteIdentity LiteIdentity::generate() {
    uint8_t s[32];
    randomBytes(s, 32);
    LiteIdentity id = fromSecret(s);
    clx::wipe(s, 32);
    return id;
}

LiteIdentity LiteIdentity::fromSecret(const uint8_t secret[32]) {
    LiteIdentity id;
    std::memcpy(id.secret, secret, 32);
    x25519Public(id.pub, id.secret);
    return id;
}

void LiteIdentity::wipe() { clx::wipe(secret, 32); }

static size_t paddedLen(size_t n) {
    static const size_t buckets[] = {32, 64, 96, 128, LITE_INNER_MAX};
    for (size_t b : buckets) if (n + 1 <= b) return b;
    return 0;
}

bool encodeLiteInner(const LiteMessage& m, Bytes& out) {
    const size_t raw = LITE_HEADER + m.payload.size();
    const size_t len = paddedLen(raw);
    if (len == 0) return false;
    out.assign(len, 0);
    out[0] = uint8_t(m.kind);
    put32(&out[1], m.id);
    put32(&out[5], m.sentMinutes);
    if (!m.payload.empty()) std::memcpy(&out[LITE_HEADER], m.payload.data(), m.payload.size());
    out[raw] = 0x80;
    return true;
}

bool decodeLiteInner(const uint8_t* p, size_t n, LiteMessage& out) {
    if (n < LITE_HEADER + 1 || paddedLen(n - 1) != n) return false;
    size_t end = n;
    while (end > 0 && p[end - 1] == 0) end--;
    if (end == 0 || p[end - 1] != 0x80 || end - 1 < LITE_HEADER) return false;
    uint8_t k = p[0];
    if (k < uint8_t(LiteKind::Text) || k > uint8_t(LiteKind::Sos)) return false;
    out.kind = LiteKind(k);
    out.id = get32(p + 1);
    out.sentMinutes = get32(p + 5);
    out.payload.assign(p + LITE_HEADER, p + end - 1);
    if (out.kind == LiteKind::Ack && out.payload.size() != 4) return false;
    return true;
}

Bytes sosPayload(bool hasPosition, int32_t lat7, int32_t lon7, const std::string& text) {
    Bytes out;
    out.push_back(hasPosition ? 1 : 0);
    if (hasPosition) { append32(out, uint32_t(lat7)); append32(out, uint32_t(lon7)); }
    append(out, reinterpret_cast<const uint8_t*>(text.data()), text.size());
    return out;
}

bool parseSos(const Bytes& payload, bool& hasPosition, int32_t& lat7, int32_t& lon7, std::string& text) {
    if (payload.empty()) return false;
    hasPosition = (payload[0] & 1) != 0;
    size_t at = 1;
    lat7 = lon7 = 0;
    if (hasPosition) {
        if (payload.size() < 9) return false;
        lat7 = int32_t(get32(&payload[1]));
        lon7 = int32_t(get32(&payload[5]));
        at = 9;
    }
    text.assign(reinterpret_cast<const char*>(payload.data() + at), payload.size() - at);
    return true;
}

static void kdf(uint8_t key[32], const uint8_t* ikm, size_t ikmLen, const uint8_t* salt, size_t saltLen, const char* info) {
    hkdfSha512(key, 32, ikm, ikmLen, salt, saltLen, reinterpret_cast<const uint8_t*>(info), std::strlen(info));
}

bool liteSeal(const LiteIdentity& me, const uint8_t peer[32], const Bytes& inner, Bytes& body, const uint8_t* ephSecret) {
    if (inner.empty() || inner.size() > LITE_INNER_MAX) return false;
    uint8_t e[32], E[32];
    if (ephSecret) std::memcpy(e, ephSecret, 32); else randomBytes(e, 32);
    x25519Public(E, e);

    uint8_t dh[64];
    bool ok = x25519(dh, e, peer) && x25519(dh + 32, me.secret, peer);
    wipe(e, 32);
    if (!ok) { wipe(dh, 64); return false; }

    uint8_t salt[96];
    std::memcpy(salt, E, 32);
    std::memcpy(salt + 32, peer, 32);
    std::memcpy(salt + 64, me.pub, 32);

    uint8_t k1[32], k2[32];
    kdf(k1, dh, 32, salt, 64, INFO_SENDER);
    kdf(k2, dh, 64, salt, 96, INFO_MESSAGE);
    wipe(dh, 64);

    body.assign(LITE_OVERHEAD + inner.size(), 0);
    body[0] = LITE_VERSION;
    std::memcpy(&body[1], E, 32);
    chacha20(&body[33], me.pub, 32, k1, ZERO_NONCE, 0);
    aeadSeal(&body[65], &body[65 + inner.size()], k2, ZERO_NONCE, body.data(), 65, inner.data(), inner.size());
    wipe(k1, 32);
    wipe(k2, 32);
    return true;
}

bool liteOpen(const LiteIdentity& me, const uint8_t* body, size_t n,
              const std::function<bool(const uint8_t pub[32])>& isContact,
              uint8_t sender[32], Bytes& inner) {
    if (n <= LITE_OVERHEAD || n > BODY_MAX || body[0] != LITE_VERSION) return false;
    const uint8_t* E = body + 1;
    uint8_t dh[64];
    if (!x25519(dh, me.secret, E)) { wipe(dh, 64); return false; }

    uint8_t salt[96];
    std::memcpy(salt, E, 32);
    std::memcpy(salt + 32, me.pub, 32);
    uint8_t k1[32];
    kdf(k1, dh, 32, salt, 64, INFO_SENDER);
    uint8_t S[32];
    chacha20(S, body + 33, 32, k1, ZERO_NONCE, 0);
    wipe(k1, 32);
    if (!isContact(S) || !x25519(dh + 32, me.secret, S)) { wipe(dh, 64); return false; }

    std::memcpy(salt + 64, S, 32);
    uint8_t k2[32];
    kdf(k2, dh, 64, salt, 96, INFO_MESSAGE);
    wipe(dh, 64);
    const size_t len = n - LITE_OVERHEAD;
    inner.assign(len, 0);
    bool ok = aeadOpen(inner.data(), body + 65 + len, k2, ZERO_NONCE, body, 65, body + 65, len);
    wipe(k2, 32);
    if (!ok) { inner.clear(); return false; }
    std::memcpy(sender, S, 32);
    return true;
}

uint64_t liteReplayKey(const uint8_t* body, size_t n) { return n >= 9 ? get64(body + 1) : 0; }

// ── Pairing ──────────────────────────────────────────────────────────────

static const char COMMIT_LABEL[] = "clairix-lora-pair-commit";
static const char SAS_LABEL[] = "clairix-lora-pair-sas";
static const uint8_t STEP_COMMIT = 1, STEP_ANSWER = 2, STEP_REVEAL = 3;
static const uint32_t RESEND_MS = 4000;

void pairCommitment(const uint8_t pub[32], const uint8_t nonce[16], uint8_t out[32]) {
    uint8_t m[sizeof COMMIT_LABEL - 1 + 48];
    std::memcpy(m, COMMIT_LABEL, sizeof COMMIT_LABEL - 1);
    std::memcpy(m + sizeof COMMIT_LABEL - 1, pub, 32);
    std::memcpy(m + sizeof COMMIT_LABEL - 1 + 32, nonce, 16);
    uint8_t h[64];
    sha512(h, m, sizeof m);
    std::memcpy(out, h, 32);
}

uint32_t pairDigits(const uint8_t a[32], const uint8_t b[32], const uint8_t nA[16], const uint8_t nB[16]) {
    uint8_t m[sizeof SAS_LABEL - 1 + 96];
    uint8_t* p = m;
    std::memcpy(p, SAS_LABEL, sizeof SAS_LABEL - 1); p += sizeof SAS_LABEL - 1;
    std::memcpy(p, a, 32); p += 32;
    std::memcpy(p, b, 32); p += 32;
    std::memcpy(p, nA, 16); p += 16;
    std::memcpy(p, nB, 16);
    uint8_t h[64];
    sha512(h, m, sizeof m);
    return get32(h) % 1000000u;
}

void Pairing::fixRandom(const uint8_t nonce[16], uint32_t session) {
    std::memcpy(nonce_, nonce, 16);
    session_ = session;
    fixed_ = true;
}

void Pairing::start(const LiteIdentity& me, const std::string& name, uint64_t now, uint32_t delayMs) {
    me_ = me;
    name_ = name.substr(0, PAIR_NAME_MAX);
    if (!fixed_) {
        randomBytes(nonce_, 16);
        uint8_t s[4];
        randomBytes(s, 4);
        session_ = get32(s);
    }
    pairCommitment(me_.pub, nonce_, myCommit_);
    initiator_ = false;
    digits_ = 0;
    peerName_.clear();
    state_ = State::Waiting;
    commitAt_ = now + delayMs;
    deadline_ = now + PAIR_TIMEOUT_MS;
}

void Pairing::cancel() {
    state_ = State::Idle;
    me_.wipe();
}

Bytes Pairing::named(uint8_t step, const uint8_t* key, const uint8_t* nonce) const {
    Bytes b;
    b.push_back(step);
    append32(b, session_);
    append(b, key, 32);
    append(b, nonce, 16);
    b.push_back(uint8_t(name_.size()));
    append(b, reinterpret_cast<const uint8_t*>(name_.data()), name_.size());
    return b;
}

void Pairing::commit() {
    Bytes b;
    b.push_back(STEP_COMMIT);
    append32(b, session_);
    append(b, myCommit_, 32);
    initiator_ = true;
    state_ = State::Committed;
    send_(b);
}

void Pairing::tick(uint64_t now) {
    if (state_ == State::Idle || state_ == State::Failed) return;
    if (state_ != State::Matched && now >= deadline_) { state_ = State::Failed; return; }
    if (state_ == State::Waiting && now >= commitAt_) { commit(); commitAt_ = now + RESEND_MS; return; }
    // Frames get lost; each side repeats its last step a few times. A repeat is
    // the same bytes, so radios that already heard it drop it unrelayed.
    if (now >= commitAt_ && now < deadline_) {
        commitAt_ = now + RESEND_MS;
        if (state_ == State::Committed) {
            Bytes b;
            b.push_back(STEP_COMMIT);
            append32(b, session_);
            append(b, myCommit_, 32);
            send_(b);
        } else if (state_ == State::Answered) {
            send_(named(STEP_ANSWER, me_.pub, nonce_));
        } else if (state_ == State::Matched && initiator_ && revealsLeft_ > 0) {
            revealsLeft_--;
            send_(named(STEP_REVEAL, me_.pub, nonce_));
        }
    }
}

void Pairing::finish(const uint8_t a[32], const uint8_t b[32], const uint8_t nA[16], const uint8_t nB[16]) {
    digits_ = pairDigits(a, b, nA, nB);
    state_ = State::Matched;
}

void Pairing::onBody(const uint8_t* p, size_t n, uint64_t now) {
    if (state_ == State::Idle || state_ == State::Failed) return;
    Reader r(p, n);
    uint8_t step = r.u8();
    uint32_t session = r.u32();
    if (!r.ok()) return;

    if (step == STEP_COMMIT) {
        uint8_t c[32];
        if (!r.take(c, 32) || r.remaining() != 0) return;
        if (equalCt(c, myCommit_, 32)) return;  // our own, heard back through a relay
        bool answer = state_ == State::Waiting ||
                      (state_ == State::Committed && std::memcmp(c, myCommit_, 32) < 0);
        if (!answer) return;
        std::memcpy(theirCommit_, c, 32);
        session_ = session;
        initiator_ = false;
        state_ = State::Answered;
        commitAt_ = now + RESEND_MS;
        send_(named(STEP_ANSWER, me_.pub, nonce_));
        return;
    }

    if (step != STEP_ANSWER && step != STEP_REVEAL) return;
    uint8_t key[32], nonce[16];
    r.take(key, 32);
    r.take(nonce, 16);
    uint8_t nameLen = r.u8();
    if (!r.ok() || nameLen > PAIR_NAME_MAX || r.remaining() != nameLen || session != session_) return;
    std::string name(reinterpret_cast<const char*>(r.here()), nameLen);
    if (equalCt(key, me_.pub, 32)) return;

    if (step == STEP_ANSWER && state_ == State::Committed && initiator_) {
        std::memcpy(peer_, key, 32);
        std::memcpy(peerNonce_, nonce, 16);
        peerName_ = name;
        send_(named(STEP_REVEAL, me_.pub, nonce_));
        commitAt_ = now + RESEND_MS;
        revealsLeft_ = 3;
        finish(me_.pub, peer_, nonce_, peerNonce_);
    } else if (step == STEP_REVEAL && state_ == State::Answered && !initiator_) {
        uint8_t c[32];
        pairCommitment(key, nonce, c);
        if (!equalCt(c, theirCommit_, 32)) { state_ = State::Failed; return; }
        std::memcpy(peer_, key, 32);
        std::memcpy(peerNonce_, nonce, 16);
        peerName_ = name;
        finish(peer_, me_.pub, peerNonce_, nonce_);
    }
}

}  // namespace clx
