#include "node.h"

#include "battery.h"
#include "board.h"
#include "platform.h"

bool RadioNode::begin(bool standalone) {
    batteryBegin();
    batteryMv_ = batteryMv();
    bridge.describe(board::MODEL, CLX_FIRMWARE_VERSION, standalone, [this] { return batteryMv_; });
    bridge.toPhone = [this](const clx::Bytes& frame) { phone.send(frame); };
    const bool ok = radio.begin();
    if (ok) {
        uint8_t noise[32];
        radio.sampleNoise(noise, sizeof noise);
        addEntropy(noise, sizeof noise);
    }
#if defined(CLX_HAS_PASSKEY_SCREEN)
    phone.begin(true);
#else
    phone.begin(false);
#endif
    applyRadio();
    return ok;
}

void RadioNode::applyRadio() {
    const clx::RadioConfig& c = bridge.config();
    radio.configure(clx::presetFromByte(c.preset), c.txPower);
}

void RadioNode::step(uint64_t now) {
    phone.poll(bridge, now);
    radio.poll(bridge, now);
    if (now >= nextTick_) {
        nextTick_ = now + 1000;
        bridge.tick(now);
    }
    if (now >= nextBattery_) {
        nextBattery_ = now + 30000;
        batteryMv_ = batteryMv();
    }
}
