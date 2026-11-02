// What every Clairix radio board runs between the air and its phone: the
// router, the bundles it holds, and the phone link's frames (link.h). The
// board supplies the radio, the Bluetooth link and the clock; this class
// decides what goes where, so the host tests can run it with no hardware.
//
// While no phone is connected the radio keeps relaying for everyone else,
// holds whole bundles for its phone (BundleStore) and the last LITE_HELD Lite
// bodies it heard, and hands them over after the phone's next Hello.
#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <string>

#include "clx/bundle.h"
#include "clx/link.h"
#include "clx/router.h"

namespace clx {

class Bridge : public Router::Listener {
public:
    static constexpr size_t LITE_HELD = 32;
    static constexpr size_t STORE_CAPACITY = 48;

    Bridge();

    Router& router() { return router_; }
    BundleStore& store() { return store_; }
    const RadioConfig& config() const { return config_; }

    /** What the board is, for Info. [battery] returns millivolts, 0 when unknown. */
    void describe(BoardModel model, const std::string& firmware, bool standalone, std::function<uint16_t()> battery);

    /** The settings saved on the board, at power-on. They carry no clock. */
    void restore(const RadioConfig& c);
    /** Sets the network key alone (the handheld's settings), keeping the rest. */
    void setNetwork(const uint8_t key[32]);
    /** Sets preset, power and hop limit, keeping the network (the handheld's settings). */
    void setRadio(uint8_t preset, int8_t txPower, uint8_t hopLimit);

    void phoneConnected();
    void phoneDisconnected();
    bool phoneReady() const { return hello_; }
    /** A whole frame from the phone, after the Dechunker. */
    void onPhoneFrame(const uint8_t* p, size_t n, uint64_t now);

    /** A frame off the air (the board's radio hands every packet here). */
    void onRadioFrame(const uint8_t* frame, size_t n, const RxInfo& rx, uint64_t now);

    /** Housekeeping, once a second: resends, expiry, the router's own. */
    void tick(uint64_t now);

    RadioInfo info(uint64_t now);

    /** Frames for the phone, whole; the board cuts them into chunks. */
    std::function<void(const Bytes&)> toPhone;
    /** Called when the phone changed the settings: the board saves them and retunes. */
    std::function<void(const RadioConfig&)> onConfig;
    /** Every Lite body heard, for a handheld's own messenger. */
    std::function<void(const Bytes&, const RxInfo&)> liteTap;
    /** Every Pair body heard, for a handheld's own pairing screen. */
    std::function<void(const Bytes&, const RxInfo&)> pairTap;

    void onBundle(const Bundle& b, uint64_t bid) override;
    void onLite(const Bytes& body, const RxInfo& rx) override;
    void onPair(const Bytes& body, const RxInfo& rx) override;

private:
    struct Held { Bytes body; RxInfo rx; };
    void apply(const RadioConfig& c);
    void send(const Bytes& frame) { if (toPhone) toPhone(frame); }

    Router router_;
    BundleStore store_;
    RadioConfig config_;
    std::deque<Held> held_;
    bool networkSet_ = false;
    bool connected_ = false;
    bool hello_ = false;
    uint64_t now_ = 0;
    BoardModel model_ = BoardModel::Unknown;
    std::string firmware_;
    bool standalone_ = false;
    std::function<uint16_t()> battery_;
};

}  // namespace clx
