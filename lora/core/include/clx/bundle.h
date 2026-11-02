// Clairix bundles on LoRa: the sealed envelopes the Bluetooth mesh carries
// (android/.../comms/mesh/MeshProtocol.kt), cut into frames.
//
// A bundle is its routing tag (16 bytes, only its sender and recipient can
// compute it), the Bluetooth mesh's hop count, the hour it was made and the
// envelope. On LoRa it is written as
//
//   tag(16) | hops(1) | created, hours since 1970 (4) | envelope
//
// and cut into at most MAX_FRAGS fragments of FRAG_DATA bytes (the last one
// shorter), each one packet:
//
//   Frag body: bundle id(8) | index(1) | count(1) | data
//
// The bundle id is SHA-512(tag || created || envelope), first 8 bytes: the
// hops, which every carrier changes, are left out. A reassembled bundle whose
// bytes do not hash to its id is thrown away, so a stray or forged fragment
// cannot corrupt someone's message, only delay it.
#pragma once

#include <cstdint>
#include <deque>
#include <map>
#include <vector>

#include "clx/bytes.h"
#include "clx/net.h"

namespace clx {

constexpr size_t BUNDLE_TAG_LEN = 16;
constexpr size_t BUNDLE_HEADER = BUNDLE_TAG_LEN + 1 + 4;
constexpr size_t FRAG_HEADER = 8 + 1 + 1;
constexpr size_t FRAG_DATA = BODY_MAX - FRAG_HEADER;  // 226
constexpr size_t MAX_FRAGS = 10;
/** The largest envelope LoRa carries: a text or an SOS (about 1.1 KB) with room to spare; media stays for the relay. */
constexpr size_t MAX_LORA_ENVELOPE = MAX_FRAGS * FRAG_DATA - BUNDLE_HEADER;
/** As MeshProtocol.MAX_HOPS: a bundle that has been through this many phones is not passed on. */
constexpr uint8_t BUNDLE_MAX_HOPS = 8;
/** As MeshProtocol.TTL_MS. */
constexpr uint64_t BUNDLE_TTL_MS = 48ull * 3600 * 1000;

struct Bundle {
    uint8_t tag[BUNDLE_TAG_LEN]{};
    uint8_t hops = 0;
    uint64_t createdMs = 0;  // whole hours, as the phone dates it
    Bytes envelope;
};

uint64_t bundleId(const Bundle& b);

/** The Frag packet bodies for [b]; empty when it is too large for LoRa. */
std::vector<Bytes> fragment(const Bundle& b);

/** One fragment, parsed. */
struct Frag {
    uint64_t bid = 0;
    uint8_t index = 0;
    uint8_t count = 0;
    Bytes data;
};
bool parseFrag(const Bytes& body, Frag& out);

/** Want body: bundle id(8) | missing fragments, bit i for index i (2) | random(4), so a second ask is not taken for a repeat. */
Bytes encodeWant(uint64_t bid, uint16_t missing);
bool parseWant(const Bytes& body, uint64_t& bid, uint16_t& missing);

/**
 * Puts bundles back together from fragments heard in any order. Keeps at
 * most [maxPartial] bundles in progress; the one heard from longest ago goes
 * first. A part that has heard nothing for PARTIAL_TTL_MS is dropped.
 */
class Reassembler {
public:
    static constexpr uint64_t PARTIAL_TTL_MS = 10 * 60 * 1000;
    /** How long a partial bundle sits quiet before its missing fragments are asked for. */
    static constexpr uint64_t WANT_AFTER_MS = 20 * 1000;
    static constexpr uint8_t MAX_WANTS = 4;  // after 20, 40, 60 and 80 s more of silence

    explicit Reassembler(size_t maxPartial = 8) : maxPartial_(maxPartial) {}

    enum class Result { Partial, Complete, Duplicate, Bad };
    /** Adds a fragment; on Complete, [out] holds the bundle. */
    Result add(const Frag& f, uint64_t now, Bundle& out);

    /** Partial bundles due a Want: their ids and missing bits. Marks them asked. */
    std::vector<std::pair<uint64_t, uint16_t>> dueWants(uint64_t now);
    /** The fragments of a partial bundle that [missing] asks for and this node has. */
    std::vector<Bytes> fragmentsFor(uint64_t bid, uint16_t missing) const;
    void expire(uint64_t now);
    size_t partials() const { return parts_.size(); }

private:
    struct Part {
        uint8_t count = 0;
        uint16_t have = 0;
        Bytes data[MAX_FRAGS];
        uint64_t lastHeard = 0;
        uint8_t wants = 0;
    };
    std::map<uint64_t, Part> parts_;
    size_t maxPartial_;
};

/**
 * The bundles a board holds: those it reassembled off the air, kept for the
 * phone until the phone says it has them, and those the phone handed it,
 * sent on the air again a few times (RETRY_AFTER_MS) so radios that were off
 * or out of range the first time still hear them. Oldest first out when full.
 */
class BundleStore {
public:
    static constexpr uint64_t RETRY_AFTER_MS[] = {2ull * 60000, 15ull * 60000, 60ull * 60000, 4ull * 3600000};
    static constexpr size_t RETRIES = sizeof RETRY_AFTER_MS / sizeof RETRY_AFTER_MS[0];

    struct Entry {
        uint64_t bid = 0;
        Bundle bundle;
        bool fromPhone = false;
        bool phoneHas = false;
        uint8_t retries = 0;
        uint64_t nextRetry = 0;
        uint64_t addedAt = 0;
    };

    explicit BundleStore(size_t capacity) : capacity_(capacity) {}

    /** Adds a bundle; false when it is already here (or was lately). */
    bool add(const Bundle& b, bool fromPhone, uint64_t now);
    bool has(uint64_t bid) const;
    const Entry* get(uint64_t bid) const;
    /** The phone has [bid]: it is not offered to it again. */
    void phoneHas(uint64_t bid);
    /** Bundles the phone has not acknowledged, oldest first. */
    std::vector<const Entry*> forPhone() const;
    /** The phone's bundles due another time on the air; their retry is counted. */
    std::vector<const Entry*> dueRetries(uint64_t now);
    void expire(uint64_t now);
    size_t size() const { return entries_.size(); }

private:
    std::deque<Entry> entries_;
    /** Ids seen lately, longer than the entries themselves stay. */
    std::deque<uint64_t> recent_;
    size_t capacity_;
};

}  // namespace clx
