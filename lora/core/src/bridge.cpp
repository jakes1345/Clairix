#include "clx/bridge.h"

#include <algorithm>
#include <cstring>

#include "clx/crypto.h"

namespace clx {

Bridge::Bridge() : router_(*this), store_(STORE_CAPACITY) {
    router_.setLookup([this](uint64_t bid) -> const Bundle* {
        const BundleStore::Entry* e = store_.get(bid);
        return e ? &e->bundle : nullptr;
    });
    RadioConfig c;
    publicNetworkKey(c.networkKey);
    apply(c);
}

void Bridge::describe(BoardModel model, const std::string& firmware, bool standalone, std::function<uint16_t()> battery) {
    model_ = model;
    firmware_ = firmware;
    standalone_ = standalone;
    battery_ = std::move(battery);
}

void Bridge::apply(const RadioConfig& in) {
    RadioConfig c = in;
    if (c.preset >= PRESET_COUNT) c.preset = uint8_t(PresetId::Balanced);
    c.txPower = std::max(MIN_TX_DBM, std::min(MAX_TX_DBM, c.txPower));
    c.hopLimit = std::max<uint8_t>(1, std::min<uint8_t>(MAX_HOP_LIMIT, c.hopLimit));
    c.nowMs = 0;
    const bool newNetwork = !networkSet_ || std::memcmp(c.networkKey, config_.networkKey, 32) != 0;
    config_ = c;
    if (newNetwork) router_.setNetwork(c.networkKey);
    networkSet_ = true;
    Router::Config rc = router_.config();
    rc.preset = presetFromByte(c.preset);
    rc.hopLimit = c.hopLimit;
    router_.setConfig(rc);
}

void Bridge::restore(const RadioConfig& c) { apply(c); }

void Bridge::setNetwork(const uint8_t key[32]) {
    RadioConfig c = config_;
    std::memcpy(c.networkKey, key, 32);
    apply(c);
}

void Bridge::setRadio(uint8_t preset, int8_t txPower, uint8_t hopLimit) {
    RadioConfig c = config_;
    c.preset = preset;
    c.txPower = txPower;
    c.hopLimit = hopLimit;
    apply(c);
}

void Bridge::phoneConnected() {
    connected_ = true;
    hello_ = false;
}

void Bridge::phoneDisconnected() {
    connected_ = false;
    hello_ = false;
}

RadioInfo Bridge::info(uint64_t now) {
    RadioInfo i;
    i.model = model_;
    i.firmware = firmware_;
    i.preset = config_.preset;
    i.txPower = config_.txPower;
    i.hopLimit = config_.hopLimit;
    const int ch = router_.channel(now);
    i.channel = ch < 0 ? CHANNEL_SCANNING : uint8_t(ch);
    std::memcpy(i.networkId, router_.net().id, 8);
    i.publicNetwork = isPublicNetworkKey(config_.networkKey);
    i.standalone = standalone_;
    i.batteryMv = battery_ ? battery_() : 0;
    i.stored = uint16_t(std::min<size_t>(store_.size(), 0xFFFF));
    const RouterStats& s = router_.stats();
    i.heard = s.heard;
    i.sent = s.sent;
    i.relayed = s.relayed;
    const Router::Config& rc = router_.config();
    const uint64_t budgetUs = uint64_t(rc.budgetWindowMs) * rc.budgetPermille;  // window in us, times permille / 1000
    i.airtimePermille = budgetUs ? uint16_t(std::min<uint64_t>(1000, router_.airtimeInWindow(now) * 1000 / budgetUs)) : 0;
    return i;
}

void Bridge::onPhoneFrame(const uint8_t* p, size_t n, uint64_t now) {
    now_ = now;
    if (!connected_ || n == 0) return;
    switch (LinkType(p[0])) {
        case LinkType::Hello: {
            uint8_t version = 0;
            if (!decodeHello(p, n, version)) return;
            hello_ = true;
            send(encodeInfo(info(now)));
            for (const BundleStore::Entry* e : store_.forPhone()) send(encodeBundleIn(e->bid, e->bundle));
            for (const Held& h : held_) send(encodeLiteIn(h.body, h.rx.rssi, h.rx.snr));
            held_.clear();
            return;
        }
        case LinkType::Config: {
            RadioConfig c;
            if (!decodeConfig(p, n, c)) return;
            const uint64_t clock = c.nowMs;
            apply(c);
            if (clock > 0) router_.setClock(clock, CLOCK_TRUE, now);
            wipe(c.networkKey, 32);
            if (onConfig) onConfig(config_);
            send(encodeInfo(info(now)));
            return;
        }
        case LinkType::BundleOut: {
            uint8_t flags = 0;
            Bundle b;
            if (!decodeBundleOut(p, n, flags, b)) return;
            if (store_.add(b, (flags & BUNDLE_OWN) != 0, now)) {
                store_.phoneHas(bundleId(b));
                router_.sendBundle(b, now);
            }
            return;
        }
        case LinkType::Got: {
            uint64_t bid = 0;
            if (decodeGot(p, n, bid)) store_.phoneHas(bid);
            return;
        }
        case LinkType::LiteOut: {
            uint8_t hops = 0;
            Bytes body;
            if (decodeLiteOut(p, n, hops, body)) router_.sendLite(body, std::min<uint8_t>(hops, config_.hopLimit), now);
            return;
        }
        case LinkType::PairOut: {
            Bytes body;
            if (decodePairOut(p, n, body)) router_.sendPair(body, now);
            return;
        }
        default:
            return;  // radio-to-phone frames, or something newer than this firmware
    }
}

void Bridge::onRadioFrame(const uint8_t* frame, size_t n, const RxInfo& rx, uint64_t now) {
    now_ = now;
    router_.onFrame(frame, n, rx, now);
}

void Bridge::tick(uint64_t now) {
    now_ = now;
    for (const BundleStore::Entry* e : store_.dueRetries(now)) router_.sendBundle(e->bundle, now);
    store_.expire(now);
    router_.tick(now);
}

void Bridge::onBundle(const Bundle& b, uint64_t bid) {
    if (!store_.add(b, false, now_)) return;
    if (hello_) send(encodeBundleIn(bid, b));
}

void Bridge::onLite(const Bytes& body, const RxInfo& rx) {
    if (liteTap) liteTap(body, rx);
    if (hello_) {
        send(encodeLiteIn(body, rx.rssi, rx.snr));
        return;
    }
    held_.push_back({body, rx});
    while (held_.size() > LITE_HELD) held_.pop_front();
}

void Bridge::onPair(const Bytes& body, const RxInfo& rx) {
    if (pairTap) pairTap(body, rx);
    if (hello_) send(encodePairIn(body, rx.rssi, rx.snr));
}

}  // namespace clx
