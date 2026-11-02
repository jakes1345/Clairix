// Managed flooding over LoRa, the same for every Clairix radio.
//
// A node relays each packet it has not heard before once, with one hop more
// and one hop left fewer, after a random wait. The wait is drawn from a window
// that grows with how well the packet was heard: a node that heard it faintly
// is likely far from the sender and covers more new ground, so it tends to go
// first. A node that hears its packet relayed twice by others while it waits
// drops its own copy: the neighbourhood is covered. Before every
// transmission the radio listens for activity on the channel (the driver does
// it) and the packet waits a few slots when the channel is busy.
//
// Packets are de-duplicated by their id (net.h), which leaves out the hop
// byte, for SEEN_TTL_MS. A Clairix bundle travels as fragments, each flooded
// on its own; a node missing some of a bundle's fragments asks its neighbours
// for them (Want), and a neighbour with the bundle or those fragments sends
// them one hop.
//
// The 500 kHz channels have no duty-cycle limit in the US, but the channel is
// shared: a node keeps its own time on the air within budgetPermille of every
// budgetWindowMs. Its own packets wait for room; relays that would have to wait
// long are dropped, as other nodes will likely have relayed them.
//
// The router owns no radio and no clock: the board feeds it frames and the
// time, and asks it what to send. That is what lets it run in the host tests.
#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <unordered_map>
#include <vector>

#include "clx/bundle.h"
#include "clx/net.h"
#include "clx/region.h"

namespace clx {

struct RxInfo {
    int16_t rssi = 0;
    float snr = 0;
};

struct RouterStats {
    uint32_t heard = 0;       // frames that opened under this network's key
    uint32_t foreign = 0;     // frames that did not (another network, noise, forgeries)
    uint32_t duplicates = 0;
    uint32_t relayed = 0;
    uint32_t suppressed = 0;  // relays dropped because others covered them
    uint32_t sent = 0;        // frames put on the air
    uint32_t dropped = 0;     // relays dropped for want of airtime or a free channel
    uint32_t bundles = 0;     // bundles reassembled
    uint32_t clockSets = 0;   // times the clock was taken from a frame
    uint64_t airtimeUs = 0;
};

class Router {
public:
    static constexpr uint64_t SEEN_TTL_MS = 6ull * 3600 * 1000;
    static constexpr size_t SEEN_MAX = 2048;
    static constexpr uint8_t ECHOES_TO_SUPPRESS = 2;
    static constexpr uint8_t MAX_BUSY_TRIES = 10;
    static constexpr uint8_t WANT_ANSWER_HOPS = 2;
    static constexpr uint64_t RELAY_MAX_WAIT_MS = 30000;
    static constexpr uint64_t WANT_ANSWER_GAP_MS = 10000;  // per bundle and gap set: one answer covers neighbours asking at once
    static constexpr size_t QUEUE_MAX = 64;

    struct Config {
        PresetId preset = PresetId::Balanced;
        uint8_t hopLimit = 5;
        uint16_t budgetPermille = 100;
        uint32_t budgetWindowMs = 600000;
    };

    class Listener {
    public:
        virtual ~Listener() = default;
        /** A whole bundle heard off the air, new to this node. */
        virtual void onBundle(const Bundle& b, uint64_t bid) = 0;
        /** A Lite body, new to this node: for whoever here can open it. */
        virtual void onLite(const Bytes& body, const RxInfo& rx) = 0;
        virtual void onPair(const Bytes& body, const RxInfo& rx) = 0;
    };

    explicit Router(Listener& listener);

    void setNetwork(const uint8_t key[32]);
    const NetKeys& net() const { return net_; }
    void setConfig(const Config& c) { config_ = c; }
    const Config& config() const { return config_; }
    /** Where the router finds whole bundles this node holds, to answer a Want. */
    void setLookup(std::function<const Bundle*(uint64_t)> lookup) { lookup_ = std::move(lookup); }

    /**
     * The clock. Network time drives channel hopping; until the radio has one
     * it listens across all channels (channel() is -1) and takes the time from
     * the first frame it hears. A phone or GPS sets it with CLOCK_TRUE.
     */
    void setClock(uint64_t unixMs, uint8_t quality, uint64_t now);
    uint8_t clockQuality() const { return clockQ_; }
    /** Network seconds (since NET_EPOCH_UNIX_S); 0 without a clock. */
    uint32_t netSeconds(uint64_t now) const;
    /** The channel to listen and send on now, or -1: no clock yet, so scan. */
    int channel(uint64_t now) const;
    /** Preamble to send with: long while the clock is only a guess, so radios still scanning catch it. */
    uint16_t preambleSymbols() const { return clockQ_ <= CLOCK_GUESS ? SCAN_PREAMBLE_SYMBOLS : PREAMBLE_SYMBOLS; }
    /** True within HOP_GUARD_S of a channel change, when nothing is sent. */
    bool inGuard(uint64_t now) const;

    /** A frame off the air. */
    void onFrame(const uint8_t* frame, size_t n, const RxInfo& rx, uint64_t now);

    /** Puts a bundle this node was handed on the air (all its fragments). False when it does not fit or the queue is full. */
    bool sendBundle(const Bundle& b, uint64_t now);
    bool sendLite(const Bytes& body, uint8_t hopLimit, uint64_t now);
    bool sendPair(const Bytes& body, uint64_t now);

    /** Housekeeping: Wants for stalled bundles, expiry. Call every second or so. */
    void tick(uint64_t now);

    /**
     * The next frame to transmit, sealed, if one is due and the budget has room.
     * After transmitting, call txDone; if the channel was busy, call busy instead.
     */
    bool popTx(uint64_t now, Bytes& frame);
    void txDone(uint32_t airUs, uint64_t now);
    void busy(uint64_t now);
    /** When the next queued packet is due (0: nothing queued). */
    uint64_t nextDue() const;
    size_t queued() const { return queue_.size(); }

    /** Airtime used in the current window, in microseconds. */
    uint64_t airtimeInWindow(uint64_t now);
    const RouterStats& stats() const { return stats_; }
    size_t partialBundles() const { return reasm_.partials(); }

    /** For the tests: draws for delays come from here instead of the RNG. */
    void setRandom(std::function<uint32_t()> r) { random_ = std::move(r); }

private:
    struct Item {
        Packet packet;
        uint64_t pid = 0;
        uint64_t due = 0;
        uint8_t priority = 0;  // 0 first
        bool relay = false;
        uint8_t echoes = 0;
        uint8_t busyTries = 0;
    };

    bool enqueue(Item item);
    void learnClock(const uint8_t nonce[NONCE_LEN], uint32_t airMs, uint64_t now);
    uint64_t netMs(uint64_t now) const { return clockNetMs_ + (now - clockMono_); }
    bool markSeen(uint64_t pid, uint64_t now);  // false when already seen
    uint32_t slotMs() const;
    uint64_t relayDelay(float snr);
    uint32_t rand32();
    void answerWant(uint64_t bid, uint16_t missing, uint64_t now);
    void pruneBudget(uint64_t now);

    Listener& listener_;
    NetKeys net_{};
    uint8_t clockQ_ = CLOCK_NONE;
    uint64_t clockNetMs_ = 0;  // network time in ms at clockMono_
    uint64_t clockMono_ = 0;
    Config config_;
    std::function<const Bundle*(uint64_t)> lookup_;
    std::function<uint32_t()> random_;
    Reassembler reasm_;
    std::deque<Item> queue_;
    bool inFlightValid_ = false;
    Item inFlight_;
    std::unordered_map<uint64_t, uint64_t> seen_;
    std::deque<uint64_t> seenOrder_;
    std::map<uint64_t, uint64_t> wantAnswered_;
    std::deque<std::pair<uint64_t, uint32_t>> spent_;
    RouterStats stats_;
};

}  // namespace clx
