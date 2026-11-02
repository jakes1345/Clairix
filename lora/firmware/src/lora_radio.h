// The SX1262 under the router: receives, transmits after listening for
// activity on the channel, follows the router's channel hops, and sweeps all
// channels by channel activity detection while the radio has no clock.
#pragma once

#include <RadioLib.h>

#include "clx/bridge.h"

class LoraRadio {
public:
    LoraRadio();
    /** False when no radio answers on the SPI bus. */
    bool begin();
    /** Spreading factor, bandwidth, coding rate and power; resumes listening. */
    void configure(clx::PresetId preset, int8_t txPowerDbm);
    /** One step of the main loop. */
    void poll(clx::Bridge& bridge, uint64_t now);
    /** Bytes of RSSI noise, for the random pool. Only before the first poll. */
    void sampleNoise(uint8_t* out, size_t n);
    bool ok() const { return ok_; }
    /** The channel listened on, or -1 while sweeping. */
    int tunedChannel() const { return state_ == State::Sweep || state_ == State::SweepRx ? -1 : channel_; }

private:
    enum class State { Off, Rx, Tx, Sweep, SweepRx };

    void listen(int channel);
    void tune(int channel);
    void receive(clx::Bridge& bridge, uint64_t now);
    void finishTx(clx::Bridge& bridge, uint64_t now);
    void trySend(clx::Bridge& bridge, uint64_t now);
    bool channelBusy();

    SPIClass spi_;
    Module module_;
    SX1262 radio_;
    bool ok_ = false;
    State state_ = State::Off;
    int channel_ = -1;
    clx::PresetId preset_ = clx::PresetId::Balanced;
    uint16_t preambleSet_ = 0;
    uint8_t sweepNext_ = 0;
    uint64_t sweepUntil_ = 0;
    uint64_t txStarted_ = 0;
    uint32_t txAirUs_ = 0;
};
