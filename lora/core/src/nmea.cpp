#include "clx/nmea.h"

#include <cstring>

namespace clx {

int64_t daysFromCivil(int y, unsigned m, unsigned d) {
    // Howard Hinnant's algorithm.
    y -= m <= 2;
    const int64_t era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = unsigned(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + int64_t(doe) - 719468;
}

static int hexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static bool digits(const char* p, size_t n, int& out) {
    out = 0;
    for (size_t i = 0; i < n; i++) {
        if (p[i] < '0' || p[i] > '9') return false;
        out = out * 10 + (p[i] - '0');
    }
    return true;
}

/** "ddmm.mmmm" (or "dddmm.mmmm") to degrees x 1e7. */
static bool coordinate(const char* f, size_t degDigits, char hemi, int32_t& out) {
    int deg = 0;
    if (std::strlen(f) < degDigits + 2 || !digits(f, degDigits, deg)) return false;
    // Minutes as an integer in 1e-6 minutes, so nothing goes through floating point.
    const char* m = f + degDigits;
    int64_t micro = 0;
    int whole = 0;
    if (!digits(m, 2, whole)) return false;
    micro = int64_t(whole) * 1000000;
    if (m[2] == '.') {
        int64_t scale = 100000;
        for (const char* q = m + 3; *q && scale > 0; q++, scale /= 10) {
            if (*q < '0' || *q > '9') return false;
            micro += int64_t(*q - '0') * scale;
        }
    } else if (m[2] != '\0') {
        return false;
    }
    if (whole >= 60) return false;
    // degrees*1e7 + minutes/60*1e7 = deg*1e7 + micro/6 (rounded)
    int64_t v = int64_t(deg) * 10000000 + (micro + 3) / 6;
    if (hemi == 'S' || hemi == 'W') v = -v;
    else if (hemi != 'N' && hemi != 'E') return false;
    out = int32_t(v);
    return true;
}

bool NmeaReader::feed(char c, GpsFix& out) {
    if (c == '$') {
        in_ = true;
        len_ = 0;
        return false;
    }
    if (!in_) return false;
    if (c == '\r' || c == '\n') {
        in_ = false;
        line_[len_] = '\0';
        return parse(out);
    }
    if (len_ + 1 >= sizeof line_) {
        in_ = false;
        return false;
    }
    line_[len_++] = c;
    return false;
}

bool NmeaReader::parse(GpsFix& out) {
    char* star = std::strchr(line_, '*');
    if (!star || hexDigit(star[1]) < 0 || hexDigit(star[2]) < 0) return false;
    uint8_t sum = 0;
    for (char* p = line_; p < star; p++) sum ^= uint8_t(*p);
    if (sum != uint8_t(hexDigit(star[1]) * 16 + hexDigit(star[2]))) return false;
    *star = '\0';
    // Talker (2 letters) then "RMC".
    if (std::strlen(line_) < 6 || std::strncmp(line_ + 2, "RMC,", 4) != 0) return false;

    const char* field[13] = {};
    size_t count = 0;
    for (char* p = line_; count < 13;) {
        field[count++] = p;
        char* comma = std::strchr(p, ',');
        if (!comma) break;
        *comma = '\0';
        p = comma + 1;
    }
    if (count < 10) return false;
    const char* time = field[1];
    const char* status = field[2];
    const char* date = field[9];
    if (status[0] != 'A' || std::strlen(time) < 6 || std::strlen(date) != 6) return false;

    int hh, mm, ss, dd, mo, yy;
    if (!digits(time, 2, hh) || !digits(time + 2, 2, mm) || !digits(time + 4, 2, ss)) return false;
    if (!digits(date, 2, dd) || !digits(date + 2, 2, mo) || !digits(date + 4, 2, yy)) return false;
    if (hh > 23 || mm > 59 || ss > 60 || dd < 1 || dd > 31 || mo < 1 || mo > 12) return false;
    int ms = 0;
    if (time[6] == '.') {
        int scale = 100;
        for (const char* q = time + 7; *q && scale > 0; q++, scale /= 10) {
            if (*q < '0' || *q > '9') return false;
            ms += (*q - '0') * scale;
        }
    }
    const int year = yy < 80 ? 2000 + yy : 1900 + yy;
    const int64_t days = daysFromCivil(year, unsigned(mo), unsigned(dd));
    GpsFix f;
    f.unixMs = uint64_t(((days * 24 + hh) * 60 + mm) * 60 + ss) * 1000 + uint64_t(ms);
    if (!coordinate(field[3], 2, field[4][0], f.lat7) || !coordinate(field[5], 3, field[6][0], f.lon7)) return false;
    out = f;
    return true;
}

}  // namespace clx
