#include "clx/bundle.h"

#include <algorithm>

#include "clx/crypto.h"

namespace clx {

constexpr uint64_t BundleStore::RETRY_AFTER_MS[];

static constexpr uint64_t HOUR_MS = 3600ull * 1000;

static Bytes serialize(const Bundle& b) {
    Bytes out;
    out.reserve(BUNDLE_HEADER + b.envelope.size());
    append(out, b.tag, BUNDLE_TAG_LEN);
    out.push_back(b.hops);
    append32(out, uint32_t(b.createdMs / HOUR_MS));
    append(out, b.envelope.data(), b.envelope.size());
    return out;
}

uint64_t bundleId(const Bundle& b) {
    Bytes m;
    m.reserve(BUNDLE_TAG_LEN + 4 + b.envelope.size());
    append(m, b.tag, BUNDLE_TAG_LEN);
    append32(m, uint32_t(b.createdMs / HOUR_MS));
    append(m, b.envelope.data(), b.envelope.size());
    uint8_t h[64];
    sha512(h, m.data(), m.size());
    return get64(h);
}

std::vector<Bytes> fragment(const Bundle& b) {
    std::vector<Bytes> out;
    if (b.envelope.empty() || b.envelope.size() > MAX_LORA_ENVELOPE) return out;
    const Bytes all = serialize(b);
    const uint64_t bid = bundleId(b);
    const size_t count = (all.size() + FRAG_DATA - 1) / FRAG_DATA;
    for (size_t i = 0; i < count; i++) {
        const size_t at = i * FRAG_DATA;
        const size_t n = std::min(FRAG_DATA, all.size() - at);
        Bytes f;
        f.reserve(FRAG_HEADER + n);
        append64(f, bid);
        f.push_back(uint8_t(i));
        f.push_back(uint8_t(count));
        append(f, all.data() + at, n);
        out.push_back(std::move(f));
    }
    return out;
}

bool parseFrag(const Bytes& body, Frag& out) {
    if (body.size() <= FRAG_HEADER) return false;
    out.bid = get64(body.data());
    out.index = body[8];
    out.count = body[9];
    if (out.count == 0 || out.count > MAX_FRAGS || out.index >= out.count) return false;
    const size_t n = body.size() - FRAG_HEADER;
    // Every fragment but the last is full; the first holds at least the bundle header.
    if (out.index + 1 < out.count && n != FRAG_DATA) return false;
    if (out.index == 0 && n < BUNDLE_HEADER + (out.count == 1 ? 1 : 0)) return false;
    out.data.assign(body.begin() + FRAG_HEADER, body.end());
    return true;
}

Bytes encodeWant(uint64_t bid, uint16_t missing) {
    Bytes b;
    append64(b, bid);
    append16(b, missing);
    // A second Want for the same gaps must not look like a repeat of the first.
    uint8_t salt[4];
    randomBytes(salt, 4);
    append(b, salt, 4);
    return b;
}

bool parseWant(const Bytes& body, uint64_t& bid, uint16_t& missing) {
    if (body.size() != 14) return false;
    bid = get64(body.data());
    missing = get16(body.data() + 8);
    return missing != 0;
}

static uint16_t allBits(uint8_t count) { return uint16_t((1u << count) - 1); }

Reassembler::Result Reassembler::add(const Frag& f, uint64_t now, Bundle& out) {
    auto it = parts_.find(f.bid);
    if (it == parts_.end()) {
        if (parts_.size() >= maxPartial_) {
            auto oldest = std::min_element(parts_.begin(), parts_.end(),
                [](const auto& a, const auto& b) { return a.second.lastHeard < b.second.lastHeard; });
            parts_.erase(oldest);
        }
        it = parts_.emplace(f.bid, Part{}).first;
        it->second.count = f.count;
    }
    Part& p = it->second;
    if (p.count != f.count) return Result::Bad;
    const uint16_t bit = uint16_t(1u << f.index);
    if (p.have & bit) return Result::Duplicate;
    p.have |= bit;
    p.data[f.index] = f.data;
    p.lastHeard = now;
    if (p.have != allBits(p.count)) return Result::Partial;

    Bytes all;
    for (uint8_t i = 0; i < p.count; i++) append(all, p.data[i].data(), p.data[i].size());
    parts_.erase(it);
    if (all.size() <= BUNDLE_HEADER) return Result::Bad;
    Bundle b;
    std::copy(all.begin(), all.begin() + BUNDLE_TAG_LEN, b.tag);
    b.hops = all[BUNDLE_TAG_LEN];
    b.createdMs = uint64_t(get32(&all[BUNDLE_TAG_LEN + 1])) * HOUR_MS;
    b.envelope.assign(all.begin() + BUNDLE_HEADER, all.end());
    if (bundleId(b) != f.bid) return Result::Bad;
    out = std::move(b);
    return Result::Complete;
}

std::vector<std::pair<uint64_t, uint16_t>> Reassembler::dueWants(uint64_t now) {
    std::vector<std::pair<uint64_t, uint16_t>> out;
    for (auto& kv : parts_) {
        Part& p = kv.second;
        if (p.wants >= MAX_WANTS || now - p.lastHeard < WANT_AFTER_MS * (p.wants + 1)) continue;
        p.wants++;
        out.emplace_back(kv.first, uint16_t(allBits(p.count) & ~p.have));
    }
    return out;
}

std::vector<Bytes> Reassembler::fragmentsFor(uint64_t bid, uint16_t missing) const {
    std::vector<Bytes> out;
    auto it = parts_.find(bid);
    if (it == parts_.end()) return out;
    const Part& p = it->second;
    for (uint8_t i = 0; i < p.count; i++) {
        if (!((missing >> i) & 1) || !((p.have >> i) & 1)) continue;
        Bytes f;
        append64(f, bid);
        f.push_back(i);
        f.push_back(p.count);
        append(f, p.data[i].data(), p.data[i].size());
        out.push_back(std::move(f));
    }
    return out;
}

void Reassembler::expire(uint64_t now) {
    for (auto it = parts_.begin(); it != parts_.end();) {
        if (now - it->second.lastHeard > PARTIAL_TTL_MS) it = parts_.erase(it); else ++it;
    }
}

// ── Store ────────────────────────────────────────────────────────────────

static constexpr size_t RECENT_MAX = 512;

bool BundleStore::add(const Bundle& b, bool fromPhone, uint64_t now) {
    const uint64_t bid = bundleId(b);
    if (has(bid) || std::find(recent_.begin(), recent_.end(), bid) != recent_.end()) return false;
    if (b.envelope.empty() || b.envelope.size() > MAX_LORA_ENVELOPE) return false;
    Entry e;
    e.bid = bid;
    e.bundle = b;
    e.fromPhone = fromPhone;
    e.phoneHas = fromPhone;
    e.addedAt = now;
    e.nextRetry = fromPhone ? now + RETRY_AFTER_MS[0] : 0;
    entries_.push_back(std::move(e));
    recent_.push_back(bid);
    while (recent_.size() > RECENT_MAX) recent_.pop_front();
    while (entries_.size() > capacity_) entries_.pop_front();
    return true;
}

bool BundleStore::has(uint64_t bid) const { return get(bid) != nullptr; }

const BundleStore::Entry* BundleStore::get(uint64_t bid) const {
    for (const Entry& e : entries_) if (e.bid == bid) return &e;
    return nullptr;
}

void BundleStore::phoneHas(uint64_t bid) {
    for (Entry& e : entries_) if (e.bid == bid) e.phoneHas = true;
}

std::vector<const BundleStore::Entry*> BundleStore::forPhone() const {
    std::vector<const Entry*> out;
    for (const Entry& e : entries_) if (!e.phoneHas) out.push_back(&e);
    return out;
}

std::vector<const BundleStore::Entry*> BundleStore::dueRetries(uint64_t now) {
    std::vector<const Entry*> out;
    for (Entry& e : entries_) {
        if (!e.fromPhone || e.retries >= RETRIES || now < e.nextRetry) continue;
        e.retries++;
        e.nextRetry = e.retries < RETRIES ? e.addedAt + RETRY_AFTER_MS[e.retries] : 0;
        out.push_back(&e);
    }
    return out;
}

void BundleStore::expire(uint64_t now) {
    entries_.erase(std::remove_if(entries_.begin(), entries_.end(), [now](const Entry& e) {
        // [now] is the board's own clock, time since it started: a board has no
        // calendar, so a bundle's age counts from when it got here. The phone
        // checks the date it was made against its own clock.
        const bool old = now > e.addedAt + BUNDLE_TTL_MS;
        // Done with: the phone has it and there is nothing left to send again.
        const bool done = e.phoneHas && (!e.fromPhone || e.retries >= RETRIES);
        return old || done;
    }), entries_.end());
}

}  // namespace clx
