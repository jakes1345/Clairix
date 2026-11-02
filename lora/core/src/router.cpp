#include "clx/router.h"

#include <algorithm>

#include "clx/crypto.h"

namespace clx {

enum Priority : uint8_t { PRIO_CONTROL = 0, PRIO_OWN = 1, PRIO_RELAY = 2, PRIO_BULK = 3 };

Router::Router(Listener& listener) : listener_(listener) {
    uint8_t key[32];
    publicNetworkKey(key);
    setNetwork(key);
}

void Router::setNetwork(const uint8_t key[32]) {
    deriveNet(key, net_);
    // Another network's packets have nothing to do with this one's.
    queue_.clear();
    inFlightValid_ = false;
}

void Router::setClock(uint64_t unixMs, uint8_t quality, uint64_t now) {
    if (unixMs < NET_EPOCH_UNIX_S * 1000) return;
    clockNetMs_ = unixMs - NET_EPOCH_UNIX_S * 1000;
    clockMono_ = now;
    clockQ_ = quality;
}

uint32_t Router::netSeconds(uint64_t now) const {
    return clockQ_ == CLOCK_NONE ? 0 : uint32_t(netMs(now) / 1000);
}

int Router::channel(uint64_t now) const {
    if (clockQ_ == CLOCK_NONE) return -1;
    return hopChannel(net_, netSeconds(now) / HOP_SLOT_S);
}

bool Router::inGuard(uint64_t now) const {
    if (clockQ_ == CLOCK_NONE) return false;
    const uint32_t into = netSeconds(now) % HOP_SLOT_S;
    return into < HOP_GUARD_S || into >= HOP_SLOT_S - HOP_GUARD_S;
}

void Router::learnClock(const uint8_t nonce[NONCE_LEN], uint32_t airMs, uint64_t now) {
    const uint8_t theirs = nonceQuality(nonce);
    if (clockQ_ == CLOCK_TRUE || theirs == CLOCK_NONE) return;
    // The frame was stamped as it went on the air, airMs ago, to the second.
    const uint64_t theirMs = uint64_t(nonceTime(nonce)) * 1000 + 500 + airMs;
    const uint8_t learned = theirs == CLOCK_TRUE ? uint8_t(CLOCK_HEARD) : theirs;
    bool take;
    if (clockQ_ == CLOCK_NONE || theirs > clockQ_) {
        take = true;
    } else if (learned == clockQ_) {
        // Two clocks of the same standing: everyone settles on the later one, so they converge.
        take = theirMs > netMs(now) + uint64_t(HOP_GUARD_S) * 1000;
    } else {
        take = false;
    }
    if (!take) return;
    const uint64_t mine = netMs(now);
    // Re-hearing the same clock: leave it be unless it has drifted, so it does not jitter.
    if (clockQ_ != CLOCK_NONE && learned <= clockQ_ && (theirMs > mine ? theirMs - mine : mine - theirMs) < 2000) return;
    clockNetMs_ = theirMs;
    clockMono_ = now;
    if (learned > clockQ_ || clockQ_ == CLOCK_NONE) clockQ_ = learned;
    stats_.clockSets++;
}

uint32_t Router::rand32() {
    if (random_) return random_();
    uint8_t b[4];
    randomBytes(b, 4);
    return get32(b);
}

uint32_t Router::slotMs() const {
    // About what channel activity detection takes, and a margin: four symbols and 10 ms.
    const Preset& p = preset(config_.preset);
    return uint32_t((uint64_t(4) << p.sf) * 1000 / p.bwHz) + 10;
}

uint64_t Router::relayDelay(float snr) {
    // Heard faintly (far away): a small window, so likely first. Heard loud: a large one.
    float s = std::max(-20.0f, std::min(10.0f, snr));
    const uint32_t exponent = 3 + uint32_t((s + 20.0f) * 4.0f / 30.0f + 0.5f);
    return uint64_t(1 + rand32() % (1u << exponent)) * slotMs();
}

bool Router::markSeen(uint64_t pid, uint64_t now) {
    auto it = seen_.find(pid);
    if (it != seen_.end() && now - it->second < SEEN_TTL_MS) return false;
    seen_[pid] = now;
    seenOrder_.push_back(pid);
    while (seenOrder_.size() > SEEN_MAX) {
        uint64_t old = seenOrder_.front();
        seenOrder_.pop_front();
        auto o = seen_.find(old);
        // Only forget it if this is its latest entry in the order.
        if (o != seen_.end() && std::count(seenOrder_.begin(), seenOrder_.end(), old) == 0) seen_.erase(o);
    }
    return true;
}

bool Router::enqueue(Item item) {
    if (queue_.size() >= QUEUE_MAX) {
        // Full: a relay makes room for anything more important, never the other way.
        auto victim = std::find_if(queue_.rbegin(), queue_.rend(), [](const Item& i) { return i.relay; });
        if (victim == queue_.rend() || item.priority >= PRIO_RELAY) { stats_.dropped++; return false; }
        queue_.erase(std::next(victim).base());
        stats_.dropped++;
    }
    queue_.push_back(std::move(item));
    return true;
}

void Router::onFrame(const uint8_t* frame, size_t n, const RxInfo& rx, uint64_t now) {
    Bytes plain;
    Packet p;
    if (!openFrame(net_, frame, n, plain) || !decodePacket(plain.data(), plain.size(), p)) {
        stats_.foreign++;
        return;
    }
    stats_.heard++;
    learnClock(frame + 1, airtimeUs(preset(config_.preset), n, nonceQuality(frame + 1) <= CLOCK_GUESS ? SCAN_PREAMBLE_SYMBOLS : PREAMBLE_SYMBOLS) / 1000, now);
    const uint64_t pid = packetId(p);
    if (!markSeen(pid, now)) {
        stats_.duplicates++;
        for (auto it = queue_.begin(); it != queue_.end(); ++it) {
            if (!it->relay || it->pid != pid) continue;
            if (++it->echoes >= ECHOES_TO_SUPPRESS) { queue_.erase(it); stats_.suppressed++; }
            break;
        }
        return;
    }

    switch (p.type) {
        case PacketType::Frag: {
            Frag f;
            if (!parseFrag(p.body, f)) return;
            Bundle b;
            if (reasm_.add(f, now, b) == Reassembler::Result::Complete) {
                stats_.bundles++;
                listener_.onBundle(b, f.bid);
            }
            break;
        }
        case PacketType::Lite: listener_.onLite(p.body, rx); break;
        case PacketType::Pair: listener_.onPair(p.body, rx); break;
        case PacketType::Want: {
            uint64_t bid;
            uint16_t missing;
            if (parseWant(p.body, bid, missing)) answerWant(bid, missing, now);
            return;  // never relayed: it is for the neighbours
        }
    }

    if (p.hopLimit == 0) return;
    Item relay;
    relay.packet = p;
    relay.packet.hops = uint8_t(std::min(15, p.hops + 1));
    relay.packet.hopLimit = uint8_t(p.hopLimit - 1);
    relay.pid = pid;
    relay.relay = true;
    relay.priority = PRIO_RELAY;
    relay.due = now + relayDelay(rx.snr);
    enqueue(std::move(relay));
}

void Router::answerWant(uint64_t bid, uint16_t missing, uint64_t now) {
    const uint64_t key = bid ^ (uint64_t(missing) << 48);
    auto last = wantAnswered_.find(key);
    if (last != wantAnswered_.end() && now - last->second < WANT_ANSWER_GAP_MS) return;
    std::vector<Bytes> bodies;
    if (lookup_) {
        if (const Bundle* b = lookup_(bid)) {
            std::vector<Bytes> all = fragment(*b);
            for (size_t i = 0; i < all.size(); i++) if ((missing >> i) & 1) bodies.push_back(all[i]);
        }
    }
    if (bodies.empty()) bodies = reasm_.fragmentsFor(bid, missing);
    if (bodies.empty()) return;
    wantAnswered_[key] = now;
    if (wantAnswered_.size() > 64) wantAnswered_.erase(wantAnswered_.begin());
    for (Bytes& body : bodies) {
        Item it;
        it.packet.type = PacketType::Frag;
        // Radios that already hold this fragment drop it as a duplicate; one that lost it the
        // first time (and so never passed it on) takes it and carries it a little further.
        it.packet.hopLimit = WANT_ANSWER_HOPS;
        it.packet.body = std::move(body);
        it.pid = packetId(it.packet);
        it.priority = PRIO_RELAY;
        it.relay = true;
        it.due = now + relayDelay(0);
        enqueue(std::move(it));
    }
}

bool Router::sendBundle(const Bundle& b, uint64_t now) {
    std::vector<Bytes> bodies = fragment(b);
    if (bodies.empty() || queue_.size() + bodies.size() > QUEUE_MAX) return false;
    uint64_t due = now + rand32() % (slotMs() * 4);
    for (Bytes& body : bodies) {
        Item it;
        it.packet.type = PacketType::Frag;
        it.packet.hopLimit = std::min(config_.hopLimit, MAX_HOP_LIMIT);
        it.packet.body = std::move(body);
        it.pid = packetId(it.packet);
        markSeen(it.pid, now);
        it.priority = PRIO_BULK;
        it.due = due;
        enqueue(std::move(it));
    }
    return true;
}

bool Router::sendLite(const Bytes& body, uint8_t hopLimit, uint64_t now) {
    if (body.empty() || body.size() > BODY_MAX) return false;
    Item it;
    it.packet.type = PacketType::Lite;
    it.packet.hopLimit = std::min(hopLimit, MAX_HOP_LIMIT);
    it.packet.body = body;
    it.pid = packetId(it.packet);
    markSeen(it.pid, now);
    it.priority = PRIO_OWN;
    it.due = now + rand32() % slotMs();
    return enqueue(std::move(it));
}

bool Router::sendPair(const Bytes& body, uint64_t now) {
    if (body.empty() || body.size() > BODY_MAX) return false;
    Item it;
    it.packet.type = PacketType::Pair;
    // Pairing is for radios side by side: one hop is enough, and keeps it local.
    it.packet.hopLimit = 1;
    it.packet.body = body;
    it.pid = packetId(it.packet);
    markSeen(it.pid, now);
    it.priority = PRIO_CONTROL;
    it.due = now;
    return enqueue(std::move(it));
}

void Router::tick(uint64_t now) {
    reasm_.expire(now);
    for (auto& w : reasm_.dueWants(now)) {
        Item it;
        it.packet.type = PacketType::Want;
        it.packet.hopLimit = 0;
        it.packet.body = encodeWant(w.first, w.second);
        // A Want's id would repeat for the same gaps; it is never de-duplicated as relayed traffic is.
        it.pid = 0;
        it.priority = PRIO_CONTROL;
        // Neighbours stalled on the same bundle stall at about the same moment; spread their asks.
        it.due = now + rand32() % (slotMs() * 16);
        enqueue(std::move(it));
    }
    for (auto it = wantAnswered_.begin(); it != wantAnswered_.end();) {
        if (now - it->second > WANT_ANSWER_GAP_MS) it = wantAnswered_.erase(it); else ++it;
    }
}

void Router::pruneBudget(uint64_t now) {
    while (!spent_.empty() && now - spent_.front().first > config_.budgetWindowMs) spent_.pop_front();
}

uint64_t Router::airtimeInWindow(uint64_t now) {
    pruneBudget(now);
    uint64_t used = 0;
    for (auto& s : spent_) used += s.second;
    return used;
}

bool Router::popTx(uint64_t now, Bytes& frame) {
    if (inFlightValid_) return false;
    // Near a channel change, radios with slightly different clocks may be on different channels.
    if (inGuard(now)) return false;
    const uint64_t budget = uint64_t(config_.budgetWindowMs) * 1000 * config_.budgetPermille / 1000;
    const Preset& p = preset(config_.preset);
    while (true) {
        auto best = queue_.end();
        for (auto it = queue_.begin(); it != queue_.end(); ++it) {
            if (it->due > now) continue;
            if (best == queue_.end() || it->priority < best->priority ||
                (it->priority == best->priority && it->due < best->due)) best = it;
        }
        if (best == queue_.end()) return false;
        const Bytes plain = encodePacket(best->packet);
        const uint32_t air = airtimeUs(p, plain.size() + FRAME_OVERHEAD, preambleSymbols());
        if (airtimeInWindow(now) + air > budget) {
            if (best->relay) { queue_.erase(best); stats_.dropped++; continue; }
            // Our own wait for the oldest spend to leave the window.
            best->due = spent_.empty() ? now + 1000 : spent_.front().first + config_.budgetWindowMs + 1;
            return false;
        }
        if (best->relay && now - best->due > RELAY_MAX_WAIT_MS) { queue_.erase(best); stats_.dropped++; continue; }
        if (clockQ_ == CLOCK_NONE) {
            // Nobody heard yet to take the time from: make one up, mid-slot, so this radio can
            // send at all. Anyone who hears it takes it; a better clock heard later replaces it.
            clockNetMs_ = uint64_t(HOP_SLOT_S / 2) * 1000;
            clockMono_ = now;
            clockQ_ = CLOCK_GUESS;
        }
        uint8_t nonce[NONCE_LEN];
        makeNonce(netSeconds(now), clockQ_, nonce);
        if (!sealFrame(net_, plain.data(), plain.size(), nonce, frame)) { queue_.erase(best); continue; }
        inFlight_ = std::move(*best);
        inFlightValid_ = true;
        queue_.erase(best);
        return true;
    }
}

void Router::txDone(uint32_t airUs, uint64_t now) {
    if (inFlightValid_ && inFlight_.relay) stats_.relayed++;
    inFlightValid_ = false;
    stats_.sent++;
    stats_.airtimeUs += airUs;
    spent_.emplace_back(now, airUs);
}

void Router::busy(uint64_t now) {
    if (!inFlightValid_) return;
    inFlightValid_ = false;
    Item it = std::move(inFlight_);
    // A relay that cannot find a quiet moment has likely been covered by others already; our own
    // packets keep trying, backing off further each time the channel is taken.
    if (it.busyTries < 255) ++it.busyTries;
    if (it.relay && it.busyTries > MAX_BUSY_TRIES) { stats_.dropped++; return; }
    const uint32_t window = 8u << std::min<uint32_t>(it.busyTries, 4);
    it.due = now + uint64_t(1 + rand32() % window) * slotMs();
    queue_.push_front(std::move(it));
}

uint64_t Router::nextDue() const {
    uint64_t next = 0;
    for (const Item& it : queue_) if (next == 0 || it.due < next) next = it.due;
    return next;
}

}  // namespace clx
