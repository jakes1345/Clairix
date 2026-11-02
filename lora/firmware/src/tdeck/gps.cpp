#include "gps.h"

#include <Arduino.h>

#include "../board.h"

namespace {
clx::NmeaReader reader;
// T-Deck Plus units have shipped with receivers at either rate.
const uint32_t BAUDS[] = {38400, 9600};
size_t baud = 0;
uint64_t triedAt = 0;
bool heard = false;
}  // namespace

void gpsBegin() {
    Serial1.begin(BAUDS[baud], SERIAL_8N1, board::GPS_RX, board::GPS_TX);
    triedAt = 0;
}

bool gpsPresent() { return heard; }

bool gpsPoll(uint64_t now, clx::GpsFix& fix) {
    if (triedAt == 0) triedAt = now;
    bool got = false;
    int budget = 256;
    while (Serial1.available() > 0 && budget-- > 0) {
        const char c = char(Serial1.read());
        if (c == '$') heard = true;
        clx::GpsFix f;
        if (reader.feed(c, f)) {
            fix = f;
            got = true;
        }
    }
    if (!heard && now - triedAt > 4000) {
        // Nothing that looks like NMEA: try the other rate.
        baud = (baud + 1) % (sizeof BAUDS / sizeof BAUDS[0]);
        Serial1.updateBaudRate(BAUDS[baud]);
        triedAt = now;
    }
    return got;
}
