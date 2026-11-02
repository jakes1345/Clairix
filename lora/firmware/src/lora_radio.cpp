#include "lora_radio.h"

#include "board.h"
#include "clx/region.h"

namespace {
volatile bool dio1 = false;
void IRAM_ATTR onDio1() { dio1 = true; }
}  // namespace

LoraRadio::LoraRadio()
    : spi_(FSPI),
      module_(board::LORA_NSS, board::LORA_DIO1, board::LORA_RST, board::LORA_BUSY, spi_),
      radio_(&module_) {}

bool LoraRadio::begin() {
    spi_.begin(board::LORA_SCK, board::LORA_MISO, board::LORA_MOSI, board::LORA_NSS);
    const clx::Preset& p = clx::preset(preset_);
    int16_t r = radio_.begin(float(clx::channelMHz(0)), p.bwHz / 1000.0f, p.sf, p.crDenom, clx::SYNC_WORD,
                             clx::MAX_TX_DBM, clx::PREAMBLE_SYMBOLS, board::LORA_TCXO_V, false);
    if (r != RADIOLIB_ERR_NONE) {
        Serial.printf("[radio] SX1262 did not start (%d)\n", r);
        return false;
    }
    radio_.setDio2AsRfSwitch(true);
    if (board::LORA_RXEN >= 0) radio_.setRfSwitchPins(board::LORA_RXEN, RADIOLIB_NC);
    radio_.setCurrentLimit(140);
    radio_.setCRC(2);
    radio_.explicitHeader();
    radio_.setRxBoostedGainMode(true);
    radio_.setDio1Action(onDio1);
    preambleSet_ = clx::PREAMBLE_SYMBOLS;
    ok_ = true;
    return true;
}

void LoraRadio::sampleNoise(uint8_t* out, size_t n) {
    for (size_t i = 0; i < n; i++) out[i] = radio_.randomByte();
}

void LoraRadio::configure(clx::PresetId preset, int8_t txPowerDbm) {
    if (!ok_) return;
    preset_ = preset;
    const clx::Preset& p = clx::preset(preset);
    radio_.standby();
    radio_.setBandwidth(p.bwHz / 1000.0f);
    radio_.setSpreadingFactor(p.sf);
    radio_.setCodingRate(p.crDenom);
    radio_.setOutputPower(txPowerDbm);
    state_ = State::Off;  // the next poll listens again, on the right channel
}

void LoraRadio::tune(int channel) {
    if (channel != channel_) {
        radio_.setFrequency(float(clx::channelMHz(uint8_t(channel))));
        channel_ = channel;
    }
}

void LoraRadio::listen(int channel) {
    radio_.standby();
    if (preambleSet_ != clx::PREAMBLE_SYMBOLS) {
        // Receivers catch any preamble at least this long.
        radio_.setPreambleLength(clx::PREAMBLE_SYMBOLS);
        preambleSet_ = clx::PREAMBLE_SYMBOLS;
    }
    tune(channel);
    dio1 = false;
    radio_.startReceive();
    state_ = State::Rx;
}

bool LoraRadio::channelBusy() {
    // Channel activity detection: a few symbols of listening for a LoRa preamble.
    const int16_t r = radio_.scanChannel();
    dio1 = false;  // the CAD's own DIO1 interrupt is not a packet
    return r == RADIOLIB_LORA_DETECTED;
}

void LoraRadio::receive(clx::Bridge& bridge, uint64_t now) {
    uint8_t buf[clx::FRAME_MAX];
    const size_t len = radio_.getPacketLength();
    if (len > 0 && len <= sizeof buf) {
        const int16_t r = radio_.readData(buf, len);
        if (r == RADIOLIB_ERR_NONE) {
            clx::RxInfo rx;
            rx.rssi = int16_t(radio_.getRSSI());
            rx.snr = radio_.getSNR();
            bridge.onRadioFrame(buf, len, rx, now);
        }
    }
}

void LoraRadio::finishTx(clx::Bridge& bridge, uint64_t now) {
    radio_.finishTransmit();
    bridge.router().txDone(txAirUs_, now);
    state_ = State::Off;
}

void LoraRadio::trySend(clx::Bridge& bridge, uint64_t now) {
    clx::Router& router = bridge.router();
    clx::Bytes frame;
    if (!router.popTx(now, frame)) return;
    // popTx gives a clockless radio a guessed clock, so there is a channel now.
    const int ch = router.channel(now);
    radio_.standby();
    tune(ch < 0 ? 0 : ch);
    if (channelBusy()) {
        router.busy(now);
        listen(channel_);
        return;
    }
    const uint16_t preamble = router.preambleSymbols();
    if (preamble != preambleSet_) {
        radio_.setPreambleLength(preamble);
        preambleSet_ = preamble;
    }
    dio1 = false;
    if (radio_.startTransmit(frame.data(), frame.size()) != RADIOLIB_ERR_NONE) {
        router.busy(now);
        listen(channel_);
        return;
    }
    txAirUs_ = clx::airtimeUs(clx::preset(preset_), frame.size(), preamble);
    txStarted_ = now;
    state_ = State::Tx;
}

void LoraRadio::poll(clx::Bridge& bridge, uint64_t now) {
    if (!ok_) return;
    clx::Router& router = bridge.router();
    const int want = router.channel(now);

    switch (state_) {
        case State::Off:
            if (want < 0) {
                radio_.standby();
                state_ = State::Sweep;
            } else {
                listen(want);
            }
            return;

        case State::Tx:
            if (dio1) {
                dio1 = false;
                finishTx(bridge, now);
            } else if (now - txStarted_ > txAirUs_ / 1000 * 2 + 200) {
                // The done interrupt never came: stop and count the airtime anyway.
                finishTx(bridge, now);
            }
            return;

        case State::Rx:
            if (dio1) {
                dio1 = false;
                receive(bridge, now);
                listen(router.channel(now) < 0 ? channel_ : router.channel(now));
                return;
            }
            if (want < 0) {
                state_ = State::Off;
                return;
            }
            if (want != channel_) {
                listen(want);
                return;
            }
            if (router.nextDue() != 0 && router.nextDue() <= now) trySend(bridge, now);
            return;

        case State::Sweep: {
            if (want >= 0) {
                listen(want);
                return;
            }
            if (router.nextDue() != 0 && router.nextDue() <= now) {
                trySend(bridge, now);
                if (state_ == State::Tx) return;
            }
            // One channel per step: tune, look for a preamble, move on.
            tune(sweepNext_);
            sweepNext_ = uint8_t((sweepNext_ + 1) % clx::CHANNEL_COUNT);
            if (channelBusy()) {
                dio1 = false;
                radio_.startReceive();
                // Long enough for the longest frame after its preamble.
                sweepUntil_ = now + clx::airtimeUs(clx::preset(preset_), clx::FRAME_MAX, clx::SCAN_PREAMBLE_SYMBOLS) / 1000 + 50;
                state_ = State::SweepRx;
            } else {
                radio_.standby();
            }
            return;
        }

        case State::SweepRx:
            if (dio1) {
                dio1 = false;
                receive(bridge, now);
                state_ = State::Off;  // a clock may have come with it
            } else if (now > sweepUntil_) {
                radio_.standby();
                state_ = State::Sweep;
            }
            return;
    }
}
