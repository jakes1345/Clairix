// Where and how Clairix LoRa transmits.
//
// United States, 902-928 MHz, under FCC Part 15.247(a)(2) "digital modulation":
// a transmitter whose 6 dB bandwidth is at least 500 kHz needs no frequency
// hopping and has no dwell-time or duty-cycle limit, up to 1 W conducted, 4 W
// EIRP (a 6 dBi antenna), at most 8 dBm in any 3 kHz. LoRa at 500 kHz meets
// the bandwidth rule (LoRaWAN's US 500 kHz channels rely on the same one), so
// every Clairix preset uses 500 kHz and never the narrower bandwidths, which
// are only legal here when they hop over 50 channels. At the SX1262's 22 dBm
// the power spectral density is about -0.2 dBm per 3 kHz, well inside the
// limit. Nothing here touches an amateur band: amateur rules forbid
// encryption meant to hide a message's meaning.
//
// The 15 channels sit between LoRaWAN's US uplink channels (up to 914.9 MHz)
// and its downlinks (from 923.3 MHz), so Clairix stays out of the busiest
// parts of the band. A network's key picks one of them (net.h).
#pragma once

#include <cstddef>
#include <cstdint>

namespace clx {

struct Preset {
    const char* name;
    uint8_t sf;         // spreading factor
    uint32_t bwHz;      // bandwidth
    uint8_t crDenom;    // coding rate 4/crDenom, 5..8
};

enum class PresetId : uint8_t { Fast = 0, Balanced = 1, Long = 2 };
constexpr uint8_t PRESET_COUNT = 3;

const Preset& preset(PresetId id);
/** The preset for a stored or received byte; Balanced for anything unknown. */
PresetId presetFromByte(uint8_t b);

/** Symbols of preamble: long enough for a receiver that wakes on channel activity to catch the start. */
constexpr uint16_t PREAMBLE_SYMBOLS = 16;
/**
 * Preamble for radios whose clock nobody vouches for (no phone, no GPS): long
 * enough that a radio sweeping all 15 channels for its network always catches
 * it. Receivers take any preamble at least as long as their own setting.
 */
constexpr uint16_t SCAN_PREAMBLE_SYMBOLS = 128;
/** The SX126x "private network" sync word (0x1424): not LoRaWAN's, not Meshtastic's. */
constexpr uint8_t SYNC_WORD = 0x12;
/** The most the SX1262 puts out, and the most a board is set to; FCC 15.247 allows 30 dBm. */
constexpr int8_t MAX_TX_DBM = 22;
constexpr int8_t MIN_TX_DBM = 2;

constexpr uint8_t CHANNEL_COUNT = 15;
/** Channel [ch]'s centre in MHz: 915.5, 916.0, ... 922.5. */
double channelMHz(uint8_t ch);

/** Time on air in microseconds of an explicit-header, CRC-on packet of [payloadLen] bytes. */
uint32_t airtimeUs(const Preset& p, size_t payloadLen, uint16_t preamble = PREAMBLE_SYMBOLS);

/** Bits per second the preset carries, before headers. */
uint32_t bitRate(const Preset& p);

}  // namespace clx
