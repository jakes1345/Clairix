// Reads the time and position out of a GPS receiver's NMEA output (the RMC
// sentence, from any constellation: GPRMC, GNRMC, ...), for the handheld's
// clock and its SOS position. Only a sentence whose checksum matches and that
// says the fix is valid ('A') is used: before a fix many receivers report a
// time from their own backup clock, which can be far off.
#pragma once

#include <cstddef>
#include <cstdint>

namespace clx {

struct GpsFix {
    uint64_t unixMs = 0;
    int32_t lat7 = 0;  // degrees x 1e7, north positive
    int32_t lon7 = 0;  // degrees x 1e7, east positive
};

class NmeaReader {
public:
    /** Feeds one character; true when it ended a valid RMC sentence with a fix, now in [out]. */
    bool feed(char c, GpsFix& out);

private:
    bool parse(GpsFix& out);
    char line_[100];
    size_t len_ = 0;
    bool in_ = false;
};

/** Days since 1970-01-01 of a proleptic Gregorian date. */
int64_t daysFromCivil(int y, unsigned m, unsigned d);

}  // namespace clx
