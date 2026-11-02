// The parts every board runs: the protocol (Bridge), the SX1262 and the
// phone link, stepped from the main loop.
#pragma once

#include "clx/bridge.h"
#include "lora_radio.h"
#include "phone_link.h"

class RadioNode {
public:
    /** Starts the radio and Bluetooth. False when the radio does not answer. */
    bool begin(bool standalone);
    /** One pass of the main loop. */
    void step(uint64_t now);
    /** Retunes the radio to the bridge's preset and power. */
    void applyRadio();
    uint16_t battery() const { return batteryMv_; }

    clx::Bridge bridge;
    LoraRadio radio;
    PhoneLink phone;

private:
    uint64_t nextTick_ = 0;
    uint64_t nextBattery_ = 0;
    uint16_t batteryMv_ = 0;
};
