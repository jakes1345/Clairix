// The link between a phone and its radio, over Bluetooth Low Energy.
//
// The radio is a GATT server with one service and one characteristic, written
// by the phone and notified by the radio, as the phone-to-phone mesh does
// (MeshProtocol.kt): frames are cut into chunks the same way, the first chunk
// marked 0x01 and carrying the frame's length, the rest marked 0x00. The radio
// only lets a bonded phone in; bonding asks for the six-digit passkey the radio
// shows on its screen.
//
// Frames, by their first byte:
//
//   0x40 Info       radio -> phone   what the radio is and does (RadioInfo)
//   0x41 Config     phone -> radio   preset, power, hop limit, network key, the time
//   0x42 BundleOut  phone -> radio   flags(1) | bundle: put it on the air
//   0x43 BundleIn   radio -> phone   bundle id(8) | bundle: heard off the air
//   0x44 Got        phone -> radio   bundle id(8): the phone has it
//   0x45 LiteOut    phone -> radio   hop limit(1) | Lite body
//   0x46 LiteIn     radio -> phone   rssi(2) | snr x4 (1) | Lite body
//   0x47 PairOut    phone -> radio   Pair body
//   0x48 PairIn     radio -> phone   rssi(2) | snr x4 (1) | Pair body
//   0x49 Hello      phone -> radio   version(1): send me Info and what is waiting
//
// Info's channel is 0xFF while the radio has no clock and sweeps all channels.
// The radio answers Hello and Config with Info, then (after Hello) sends every
// bundle the phone has not acknowledged with Got and the Lite bodies it heard
// while no phone was connected.
//
// A bundle on the link: tag(16) | hops(1) | created, ms since 1970 (8) | length(2) | envelope.
#pragma once

#include <cstdint>
#include <string>

#include "clx/bundle.h"
#include "clx/bytes.h"

namespace clx {

constexpr uint8_t LINK_VERSION = 1;

/** The radio's GATT service, and the one characteristic both directions use. */
constexpr const char* LINK_SERVICE_UUID = "5c1a7e0d-3f1b-4d8e-9a26-c1a1e0f7a4c0";
constexpr const char* LINK_FRAMES_UUID = "5c1a7e0d-3f1b-4d8e-9a26-c1a1e0f7a4c1";

enum class LinkType : uint8_t {
    Info = 0x40, Config = 0x41, BundleOut = 0x42, BundleIn = 0x43, Got = 0x44,
    LiteOut = 0x45, LiteIn = 0x46, PairOut = 0x47, PairIn = 0x48, Hello = 0x49,
};

/** BundleOut flag: the phone's owner wrote it, so the radio sends it again later (BundleStore). */
constexpr uint8_t BUNDLE_OWN = 0x01;

enum class BoardModel : uint8_t { Unknown = 0, HeltecV3 = 1, TDeck = 2, XiaoS3 = 3 };

/** Info's channel while the radio has no clock and sweeps every channel. */
constexpr uint8_t CHANNEL_SCANNING = 0xFF;

struct RadioInfo {
    uint8_t version = LINK_VERSION;
    BoardModel model = BoardModel::Unknown;
    std::string firmware;
    uint8_t preset = 1;
    int8_t txPower = 0;
    uint8_t hopLimit = 5;
    uint8_t channel = 0;
    uint8_t networkId[8]{};
    bool publicNetwork = true;
    uint16_t batteryMv = 0;  // 0: unknown or on USB power
    uint16_t stored = 0;
    uint32_t heard = 0;
    uint32_t sent = 0;
    uint32_t relayed = 0;
    uint16_t airtimePermille = 0;  // share of the budget window spent
    bool standalone = false;       // a handheld also used on its own
};

struct RadioConfig {
    uint8_t preset = 1;
    int8_t txPower = 22;
    uint8_t hopLimit = 5;
    uint8_t networkKey[32]{};
    uint64_t nowMs = 0;  // the phone's clock, so the radio can date what it writes
};

Bytes encodeInfo(const RadioInfo& i);
bool decodeInfo(const uint8_t* p, size_t n, RadioInfo& out);
Bytes encodeConfig(const RadioConfig& c);
bool decodeConfig(const uint8_t* p, size_t n, RadioConfig& out);

void writeLinkBundle(Bytes& out, const Bundle& b);
bool readLinkBundle(Reader& r, Bundle& out);

Bytes encodeBundleOut(const Bundle& b, uint8_t flags);
Bytes encodeBundleIn(uint64_t bid, const Bundle& b);
Bytes encodeGot(uint64_t bid);
Bytes encodeLiteIn(const Bytes& body, int16_t rssi, float snr);
Bytes encodePairIn(const Bytes& body, int16_t rssi, float snr);
Bytes encodeLiteOut(const Bytes& body, uint8_t hopLimit);
Bytes encodePairOut(const Bytes& body);
Bytes encodeHello();

bool decodeBundleOut(const uint8_t* p, size_t n, uint8_t& flags, Bundle& out);
bool decodeBundleIn(const uint8_t* p, size_t n, uint64_t& bid, Bundle& out);
bool decodeGot(const uint8_t* p, size_t n, uint64_t& bid);
bool decodeLiteOut(const uint8_t* p, size_t n, uint8_t& hopLimit, Bytes& body);
bool decodePairOut(const uint8_t* p, size_t n, Bytes& body);
bool decodeHello(const uint8_t* p, size_t n, uint8_t& version);
/** LiteIn or PairIn ([type] says which). */
bool decodeHeard(LinkType type, const uint8_t* p, size_t n, Bytes& body, int16_t& rssi, float& snr);

/** Cuts a frame into chunks of at most [payload] bytes. */
std::vector<Bytes> chunk(const Bytes& frame, size_t payload);

/** Puts frames back together from chunks; one per connection. */
class Dechunker {
public:
    explicit Dechunker(size_t maxFrame = 4096) : max_(maxFrame) {}
    enum class Result { More, Frame, Bad };
    Result add(const uint8_t* p, size_t n, Bytes& frame);
    void reset() { want_ = 0; buf_.clear(); }
private:
    size_t max_;
    size_t want_ = 0;
    Bytes buf_;
};

}  // namespace clx
