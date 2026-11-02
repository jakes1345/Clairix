#include "clx/link.h"

#include <algorithm>

namespace clx {

Bytes encodeInfo(const RadioInfo& i) {
    Bytes b;
    b.push_back(uint8_t(LinkType::Info));
    b.push_back(i.version);
    b.push_back(uint8_t(i.model));
    const std::string fw = i.firmware.substr(0, 32);
    b.push_back(uint8_t(fw.size()));
    append(b, reinterpret_cast<const uint8_t*>(fw.data()), fw.size());
    b.push_back(i.preset);
    b.push_back(uint8_t(i.txPower));
    b.push_back(i.hopLimit);
    b.push_back(i.channel);
    append(b, i.networkId, 8);
    b.push_back(uint8_t((i.publicNetwork ? 1 : 0) | (i.standalone ? 2 : 0)));
    append16(b, i.batteryMv);
    append16(b, i.stored);
    append32(b, i.heard);
    append32(b, i.sent);
    append32(b, i.relayed);
    append16(b, i.airtimePermille);
    return b;
}

bool decodeInfo(const uint8_t* p, size_t n, RadioInfo& out) {
    Reader r(p, n);
    if (r.u8() != uint8_t(LinkType::Info)) return false;
    out.version = r.u8();
    out.model = BoardModel(r.u8());
    uint8_t fwLen = r.u8();
    if (!r.ok() || r.remaining() < fwLen) return false;
    out.firmware.assign(reinterpret_cast<const char*>(r.here()), fwLen);
    r.skip(fwLen);
    out.preset = r.u8();
    out.txPower = int8_t(r.u8());
    out.hopLimit = r.u8();
    out.channel = r.u8();
    r.take(out.networkId, 8);
    uint8_t flags = r.u8();
    out.publicNetwork = flags & 1;
    out.standalone = flags & 2;
    out.batteryMv = r.u16();
    out.stored = r.u16();
    out.heard = r.u32();
    out.sent = r.u32();
    out.relayed = r.u32();
    out.airtimePermille = r.u16();
    return r.ok();
}

Bytes encodeConfig(const RadioConfig& c) {
    Bytes b;
    b.push_back(uint8_t(LinkType::Config));
    b.push_back(c.preset);
    b.push_back(uint8_t(c.txPower));
    b.push_back(c.hopLimit);
    append(b, c.networkKey, 32);
    append64(b, c.nowMs);
    return b;
}

bool decodeConfig(const uint8_t* p, size_t n, RadioConfig& out) {
    Reader r(p, n);
    if (r.u8() != uint8_t(LinkType::Config)) return false;
    out.preset = r.u8();
    out.txPower = int8_t(r.u8());
    out.hopLimit = r.u8();
    r.take(out.networkKey, 32);
    out.nowMs = r.u64();
    return r.ok() && r.remaining() == 0;
}

void writeLinkBundle(Bytes& out, const Bundle& b) {
    append(out, b.tag, BUNDLE_TAG_LEN);
    out.push_back(b.hops);
    append64(out, b.createdMs);
    append16(out, uint16_t(b.envelope.size()));
    append(out, b.envelope.data(), b.envelope.size());
}

bool readLinkBundle(Reader& r, Bundle& out) {
    r.take(out.tag, BUNDLE_TAG_LEN);
    out.hops = r.u8();
    out.createdMs = r.u64();
    uint16_t len = r.u16();
    if (!r.ok() || len == 0 || len > MAX_LORA_ENVELOPE || r.remaining() != len) return false;
    out.envelope.assign(r.here(), r.here() + len);
    r.skip(len);
    return true;
}

Bytes encodeBundleOut(const Bundle& b, uint8_t flags) {
    Bytes out;
    out.push_back(uint8_t(LinkType::BundleOut));
    out.push_back(flags);
    writeLinkBundle(out, b);
    return out;
}

Bytes encodeBundleIn(uint64_t bid, const Bundle& b) {
    Bytes out;
    out.push_back(uint8_t(LinkType::BundleIn));
    append64(out, bid);
    writeLinkBundle(out, b);
    return out;
}

Bytes encodeGot(uint64_t bid) {
    Bytes out;
    out.push_back(uint8_t(LinkType::Got));
    append64(out, bid);
    return out;
}

static Bytes heardFrame(LinkType t, const Bytes& body, int16_t rssi, float snr) {
    Bytes out;
    out.push_back(uint8_t(t));
    append16(out, uint16_t(rssi));
    int s = int(snr * 4.0f + (snr >= 0 ? 0.5f : -0.5f));
    out.push_back(uint8_t(int8_t(std::max(-128, std::min(127, s)))));
    append(out, body.data(), body.size());
    return out;
}

Bytes encodeLiteIn(const Bytes& body, int16_t rssi, float snr) { return heardFrame(LinkType::LiteIn, body, rssi, snr); }
Bytes encodePairIn(const Bytes& body, int16_t rssi, float snr) { return heardFrame(LinkType::PairIn, body, rssi, snr); }

Bytes encodeLiteOut(const Bytes& body, uint8_t hopLimit) {
    Bytes out;
    out.push_back(uint8_t(LinkType::LiteOut));
    out.push_back(hopLimit);
    append(out, body.data(), body.size());
    return out;
}

Bytes encodePairOut(const Bytes& body) {
    Bytes out;
    out.push_back(uint8_t(LinkType::PairOut));
    append(out, body.data(), body.size());
    return out;
}

Bytes encodeHello() { return Bytes{uint8_t(LinkType::Hello), LINK_VERSION}; }

bool decodeBundleOut(const uint8_t* p, size_t n, uint8_t& flags, Bundle& out) {
    Reader r(p, n);
    if (r.u8() != uint8_t(LinkType::BundleOut)) return false;
    flags = r.u8();
    return r.ok() && readLinkBundle(r, out);
}

bool decodeBundleIn(const uint8_t* p, size_t n, uint64_t& bid, Bundle& out) {
    Reader r(p, n);
    if (r.u8() != uint8_t(LinkType::BundleIn)) return false;
    bid = r.u64();
    return r.ok() && readLinkBundle(r, out);
}

bool decodeGot(const uint8_t* p, size_t n, uint64_t& bid) {
    Reader r(p, n);
    if (r.u8() != uint8_t(LinkType::Got)) return false;
    bid = r.u64();
    return r.ok() && r.remaining() == 0;
}

bool decodeLiteOut(const uint8_t* p, size_t n, uint8_t& hopLimit, Bytes& body) {
    if (n < 3 || p[0] != uint8_t(LinkType::LiteOut) || n - 2 > BODY_MAX) return false;
    hopLimit = p[1];
    body.assign(p + 2, p + n);
    return true;
}

bool decodePairOut(const uint8_t* p, size_t n, Bytes& body) {
    if (n < 2 || p[0] != uint8_t(LinkType::PairOut) || n - 1 > BODY_MAX) return false;
    body.assign(p + 1, p + n);
    return true;
}

bool decodeHello(const uint8_t* p, size_t n, uint8_t& version) {
    if (n != 2 || p[0] != uint8_t(LinkType::Hello)) return false;
    version = p[1];
    return true;
}

bool decodeHeard(LinkType type, const uint8_t* p, size_t n, Bytes& body, int16_t& rssi, float& snr) {
    if (n < 5 || p[0] != uint8_t(type) || (type != LinkType::LiteIn && type != LinkType::PairIn)) return false;
    rssi = int16_t(get16(p + 1));
    snr = float(int8_t(p[3])) / 4.0f;
    body.assign(p + 4, p + n);
    return true;
}

std::vector<Bytes> chunk(const Bytes& frame, size_t payload) {
    std::vector<Bytes> out;
    if (payload < 8 || frame.empty()) return out;
    const size_t first = std::min(frame.size(), payload - 5);
    Bytes c;
    c.push_back(1);
    append32(c, uint32_t(frame.size()));
    append(c, frame.data(), first);
    out.push_back(std::move(c));
    size_t at = first;
    while (at < frame.size()) {
        const size_t n = std::min(frame.size() - at, payload - 1);
        Bytes k;
        k.push_back(0);
        append(k, frame.data() + at, n);
        out.push_back(std::move(k));
        at += n;
    }
    return out;
}

Dechunker::Result Dechunker::add(const uint8_t* p, size_t n, Bytes& frame) {
    if (n == 0) return Result::Bad;
    if (p[0] == 1) {
        if (n < 5) { reset(); return Result::Bad; }
        want_ = get32(p + 1);
        if (want_ == 0 || want_ > max_) { reset(); return Result::Bad; }
        buf_.assign(p + 5, p + n);
    } else if (p[0] == 0 && want_ > 0) {
        append(buf_, p + 1, n - 1);
    } else {
        reset();
        return Result::Bad;
    }
    if (buf_.size() > want_) { reset(); return Result::Bad; }
    if (buf_.size() < want_) return Result::More;
    frame = std::move(buf_);
    reset();
    return Result::Frame;
}

}  // namespace clx
