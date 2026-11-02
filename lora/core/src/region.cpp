#include "clx/region.h"

#include <cmath>

namespace clx {

static const Preset PRESETS[PRESET_COUNT] = {
    {"Fast", 8, 500000, 5},
    {"Balanced", 10, 500000, 5},
    {"Long", 12, 500000, 5},
};

const Preset& preset(PresetId id) {
    uint8_t i = uint8_t(id);
    return PRESETS[i < PRESET_COUNT ? i : uint8_t(PresetId::Balanced)];
}

PresetId presetFromByte(uint8_t b) { return b < PRESET_COUNT ? PresetId(b) : PresetId::Balanced; }

double channelMHz(uint8_t ch) { return 915.5 + 0.5 * double(ch % CHANNEL_COUNT); }

uint32_t airtimeUs(const Preset& p, size_t payloadLen, uint16_t preamble) {
    // Semtech's formula (SX1262 datasheet 6.1.4, SF7 and up): explicit header, CRC on.
    const double tsym = double(1u << p.sf) * 1e6 / double(p.bwHz);
    const int de = tsym > 16000.0 ? 1 : 0;  // low data rate optimisation
    const double num = 8.0 * double(payloadLen) - 4.0 * p.sf + 28.0 + 16.0;
    const double den = 4.0 * (p.sf - 2 * de);
    double n = std::ceil(num / den) * p.crDenom;
    if (n < 0) n = 0;
    const double symbols = (preamble + 4.25) + 8.0 + n;
    return uint32_t(std::lround(symbols * tsym));
}

uint32_t bitRate(const Preset& p) {
    return uint32_t(double(p.sf) * double(p.bwHz) / double(1u << p.sf) * 4.0 / double(p.crDenom));
}

}  // namespace clx
