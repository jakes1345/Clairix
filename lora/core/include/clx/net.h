// The link layer every Clairix LoRa frame goes through.
//
// A network is a 32-byte key. Everyone on one network can read its frames'
// headers and relay them; what most frames carry is sealed end to end
// besides (Clairix envelopes, or Lite messages), so the network key only keeps
// outsiders from reading the headers, from injecting frames and from waking
// every node with traffic not meant for this mesh. The public Clairix network's
// key is fixed and published below, so radios that were never set up still
// meet; a private network's key is random and passed on by hand (a network
// code: see code.h).
//
// On the air, at most 255 bytes:
//
//   hint(1) | nonce(8) | ChaCha20(encKey, 0^4 || nonce)(plain) | tag(8)
//   nonce = network time (u32 seconds since 2024-01-01) | clock quality (2 bits) | 30 random bits
//
// tag = HMAC-SHA512(macKey, hint || nonce || ciphertext), first 8 bytes.
// Encrypt-then-MAC: a frame is checked before anything in it is read.
//
// Nothing in the clear stays the same from one frame to the next. The hint is
// HMAC-SHA512(id, nonce)'s first byte: a node of this network recomputes it and
// skips other networks' frames with one hash and no decryption, while to anyone
// without the key it is a random byte. Relays seal each frame afresh, so a
// relayed copy shares no bytes with the frame it repeats.
//
// The network hops channels: every HOP_SLOT_S seconds of network time it moves
// to hopChannel(id, slot), a channel only its members can predict. The time in
// the nonce is authenticated by the tag, so a radio that has lost its clock
// learns it from the first frame it hears (see Router).
//
// Inside, the plain part starts with the packet type and the hop byte (high
// nibble: hops so far, low nibble: hops left), then the packet's body.
#pragma once

#include <cstddef>
#include <cstdint>

#include "clx/bytes.h"

namespace clx {

constexpr size_t FRAME_MAX = 255;
constexpr size_t NONCE_LEN = 8;
constexpr size_t TAG_LEN = 8;
constexpr size_t FRAME_OVERHEAD = 1 + NONCE_LEN + TAG_LEN;
/** The most a frame's plain part can hold: type, hops and body. */
constexpr size_t PLAIN_MAX = FRAME_MAX - FRAME_OVERHEAD;
/** The most a packet body can hold. */
constexpr size_t BODY_MAX = PLAIN_MAX - 2;

struct NetKeys {
    uint8_t enc[32];
    uint8_t mac[32];
    uint8_t id[8];
};

/** Network time counts seconds from 2024-01-01T00:00:00Z. */
constexpr uint64_t NET_EPOCH_UNIX_S = 1704067200ull;
/** Seconds a network stays on one channel. */
constexpr uint32_t HOP_SLOT_S = 600;
/** Seconds either side of a slot change in which nothing is sent, so clocks a little apart still meet. */
constexpr uint32_t HOP_GUARD_S = 20;

/** How a radio came by its clock; frames carry it so radios defer to better clocks. */
enum ClockQuality : uint8_t {
    CLOCK_NONE = 0,   // no idea: listens across all channels
    CLOCK_GUESS = 1,  // made one up to be able to send at all
    CLOCK_HEARD = 2,  // learned from a frame, or set by hand
    CLOCK_TRUE = 3,   // from a phone or GPS
};

/** A frame nonce for [netSeconds] and [quality], with 30 fresh random bits. */
void makeNonce(uint32_t netSeconds, uint8_t quality, uint8_t out[NONCE_LEN]);
uint32_t nonceTime(const uint8_t nonce[NONCE_LEN]);
uint8_t nonceQuality(const uint8_t nonce[NONCE_LEN]);
/** The frame's first byte: HMAC-SHA512(id, nonce)[0]. */
uint8_t frameHint(const NetKeys& k, const uint8_t nonce[NONCE_LEN]);
/** The channel (region.h) a network uses during [slot] = network seconds / HOP_SLOT_S. */
uint8_t hopChannel(const NetKeys& k, uint32_t slot);

/** The public Clairix network's key: SHA-512("clairix-lora public network v1"), first 32 bytes. */
void publicNetworkKey(uint8_t out[32]);
bool isPublicNetworkKey(const uint8_t key[32]);

/** Derives the frame keys and the network id from a network key (HKDF-SHA512, salt "clairix-lora-v1"). */
void deriveNet(const uint8_t key[32], NetKeys& out);

/** Seals [plain] (at most PLAIN_MAX bytes) into a frame; [nonce] must never repeat under one key. */
bool sealFrame(const NetKeys& k, const uint8_t* plain, size_t n, const uint8_t nonce[NONCE_LEN], Bytes& frame);
/** Opens a frame: false for another network's, a forged or a malformed one. */
bool openFrame(const NetKeys& k, const uint8_t* frame, size_t n, Bytes& plain);

enum class PacketType : uint8_t {
    /** A fragment of a Clairix bundle (a sealed envelope, as the Bluetooth mesh carries it). */
    Frag = 1,
    /** A Lite message: one frame, sealed end to end to one contact (lite.h). */
    Lite = 2,
    /** A request for a bundle's missing fragments, to the neighbours only. */
    Want = 3,
    /** A step of pairing two Lite identities (lite.h). */
    Pair = 4,
};

constexpr uint8_t MAX_HOP_LIMIT = 7;

struct Packet {
    PacketType type = PacketType::Frag;
    uint8_t hops = 0;      // hops travelled
    uint8_t hopLimit = 0;  // hops left
    Bytes body;
};

/** type | hops<<4|limit | body. */
Bytes encodePacket(const Packet& p);
bool decodePacket(const uint8_t* plain, size_t n, Packet& out);

/** A packet's identity for de-duplication: SHA-512(type || body), first 8 bytes. The hop byte, which relays change, is left out. */
uint64_t packetId(const Packet& p);

}  // namespace clx
