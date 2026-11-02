// Clairix Lite: short messages sealed end to end in one LoRa frame, for the
// handheld that has no phone (and for phones writing to one).
//
// A full Clairix message is an Olm-encrypted sealed envelope of about 1.1 KB,
// five frames on LoRa, and starting its session needs the relay. A Lite
// message is one frame and needs nothing but the two people having paired.
//
// Identity: an X25519 key pair. Sealing a message m from S (secret s, public
// S) to R (public R), with a fresh ephemeral key pair (e, E):
//
//   dh1 = X25519(e, R)
//   k1  = HKDF-SHA512(dh1, salt E||R, "clairix-lora-lite-v1 sender")
//   c1  = ChaCha20(k1, nonce 0)(S)                     the sender, hidden
//   dh2 = X25519(s, R)
//   k2  = HKDF-SHA512(dh1||dh2, salt E||R||S, "clairix-lora-lite-v1 message")
//   c2  = ChaCha20-Poly1305(k2, nonce 0, ad 0x01||E||c1)(padded m)
//   body = 0x01 || E || c1 || c2 || tag                 81 bytes on top of m
//
// Only R can find the sender, and only a message from a paired contact is
// opened: the recipient decrypts c1, looks the key up among its contacts and
// stops there if it is not one (one X25519 per frame heard, not two). The tag
// covers E and c1, and k2 needs both static keys, so a message opens only for R
// and only as coming from S. Every message has its own k1 and k2, so the zero
// nonces never repeat under one key.
//
// What it does not have, and Olm does: forward secrecy. Whoever later takes R's
// secret key, or S's, can open every Lite message between them that they
// recorded off the air. The handheld keeps its key in a vault under a PIN
// (vault.h) and wipes it on demand. Static-static DH also means anyone holding
// R's secret key could write to R as any of R's contacts.
//
// Inside: kind(1) | message id(4) | sent, minutes since 1970 (4, 0 when the
// sender has no clock) | payload, then 0x80 and zeros to 32, 64, 96, 128 or
// LITE_INNER_MAX bytes, so most messages look the same size.
#pragma once

#include <cstdint>
#include <functional>
#include <string>

#include "clx/bytes.h"
#include "clx/net.h"

namespace clx {

constexpr uint8_t LITE_VERSION = 1;
constexpr size_t LITE_OVERHEAD = 1 + 32 + 32 + 16;
constexpr size_t LITE_INNER_MAX = BODY_MAX - LITE_OVERHEAD;  // 155
constexpr size_t LITE_HEADER = 1 + 4 + 4;
/** The most text one message holds, in bytes of UTF-8. */
constexpr size_t LITE_TEXT_MAX = LITE_INNER_MAX - LITE_HEADER - 1;

struct LiteIdentity {
    uint8_t secret[32];
    uint8_t pub[32];
    /** A new random identity. */
    static LiteIdentity generate();
    static LiteIdentity fromSecret(const uint8_t secret[32]);
    void wipe();
};

enum class LiteKind : uint8_t { Text = 1, Ack = 2, Sos = 3 };

struct LiteMessage {
    LiteKind kind = LiteKind::Text;
    uint32_t id = 0;
    uint32_t sentMinutes = 0;
    /** Text: the UTF-8 text. Ack: the acknowledged id, 4 bytes. Sos: see sosPayload. */
    Bytes payload;
};

/** Pads and encodes; false when the payload does not fit in one frame. */
bool encodeLiteInner(const LiteMessage& m, Bytes& out);
bool decodeLiteInner(const uint8_t* p, size_t n, LiteMessage& out);

/** SOS payload: flags (bit 0: has a position) | latitude, longitude in 1e-7 degrees (if flagged) | text. */
Bytes sosPayload(bool hasPosition, int32_t lat7, int32_t lon7, const std::string& text);
bool parseSos(const Bytes& payload, bool& hasPosition, int32_t& lat7, int32_t& lon7, std::string& text);

/**
 * Seals [inner] (already encoded) from [me] to [peer]. [ephSecret], for the
 * known-answer tests only, fixes the ephemeral key; null draws a fresh one.
 */
bool liteSeal(const LiteIdentity& me, const uint8_t peer[32], const Bytes& inner, Bytes& body,
              const uint8_t* ephSecret = nullptr);

/**
 * Opens a Lite body addressed to [me] from someone [isContact] accepts. On
 * success [sender] holds their public key and [inner] the encoded message.
 */
bool liteOpen(const LiteIdentity& me, const uint8_t* body, size_t n,
              const std::function<bool(const uint8_t pub[32])>& isContact,
              uint8_t sender[32], Bytes& inner);

/** The first 8 bytes of a Lite body's ephemeral key: what a recipient remembers to drop replays. */
uint64_t liteReplayKey(const uint8_t* body, size_t n);

// ── Pairing ──────────────────────────────────────────────────────────────
//
// Two people put their radios in pairing mode next to each other. One sends a
// commitment to its key and a random nonce; the other answers with its key and
// nonce; the first reveals. Both screens then show the same six digits,
// derived from both keys and both nonces, and each person accepts only when
// the digits match. Whoever sits between the radios has to commit before
// seeing the second nonce, so a forged pairing shows matching digits one time
// in a million. Pairing frames go one hop, to radios in direct range.
//
//   commit: 1 | session(4) | SHA-512("clairix-lora-pair-commit" || A || nA)[0..32]
//   answer: 2 | session(4) | B(32) | nB(16) | name length(1) | name
//   reveal: 3 | session(4) | A(32) | nA(16) | name length(1) | name
//   digits = u32(SHA-512("clairix-lora-pair-sas" || A || B || nA || nB)[0..4]) mod 1 000 000

constexpr size_t PAIR_NAME_MAX = 24;
constexpr uint32_t PAIR_TIMEOUT_MS = 120000;

void pairCommitment(const uint8_t pub[32], const uint8_t nonce[16], uint8_t out[32]);
uint32_t pairDigits(const uint8_t a[32], const uint8_t b[32], const uint8_t nA[16], const uint8_t nB[16]);

class Pairing {
public:
    enum class State { Idle, Waiting, Committed, Answered, Matched, Failed };

    /** [send] gets each Pair body to put on the air. */
    explicit Pairing(std::function<void(const Bytes&)> send) : send_(std::move(send)) {}

    /**
     * Starts listening and, unless a commitment is heard first, commits after
     * [delayMs] (draw it at random, 0-3 s, so two radios started together do
     * not both commit at once; when they do, the lower commitment answers).
     */
    void start(const LiteIdentity& me, const std::string& name, uint64_t now, uint32_t delayMs);
    void cancel();
    void tick(uint64_t now);
    void onBody(const uint8_t* p, size_t n, uint64_t now);

    State state() const { return state_; }
    /** Once Matched: the six digits, the other side's key and name. */
    uint32_t digits() const { return digits_; }
    const uint8_t* peer() const { return peer_; }
    const std::string& peerName() const { return peerName_; }

    /** For the tests: fixes the nonce and session instead of drawing them. */
    void fixRandom(const uint8_t nonce[16], uint32_t session);

private:
    void commit();
    void finish(const uint8_t a[32], const uint8_t b[32], const uint8_t nA[16], const uint8_t nB[16]);
    Bytes named(uint8_t step, const uint8_t* key, const uint8_t* nonce) const;

    std::function<void(const Bytes&)> send_;
    State state_ = State::Idle;
    bool initiator_ = false;
    LiteIdentity me_{};
    std::string name_;
    uint8_t nonce_[16]{};
    uint8_t myCommit_[32]{};
    uint8_t theirCommit_[32]{};
    uint32_t session_ = 0;
    bool fixed_ = false;
    uint64_t commitAt_ = 0;
    uint64_t deadline_ = 0;
    uint8_t peer_[32]{};
    uint8_t peerNonce_[16]{};
    std::string peerName_;
    uint32_t digits_ = 0;
    uint8_t revealsLeft_ = 0;
};

}  // namespace clx
