#include "settings.h"

#include <Preferences.h>

#include "clx/crypto.h"

namespace {
constexpr const char* NS = "clx";
constexpr const char* KEY = "radio";
constexpr uint8_t VERSION = 1;
}  // namespace

bool loadRadioConfig(clx::RadioConfig& out) {
    Preferences p;
    if (!p.begin(NS, true)) return false;
    uint8_t buf[1 + 3 + 32];
    const size_t n = p.getBytes(KEY, buf, sizeof buf);
    p.end();
    if (n != sizeof buf || buf[0] != VERSION) return false;
    out.preset = buf[1];
    out.txPower = int8_t(buf[2]);
    out.hopLimit = buf[3];
    memcpy(out.networkKey, buf + 4, 32);
    out.nowMs = 0;
    clx::wipe(buf, sizeof buf);
    return true;
}

void saveRadioConfig(const clx::RadioConfig& c) {
    uint8_t buf[1 + 3 + 32];
    buf[0] = VERSION;
    buf[1] = c.preset;
    buf[2] = uint8_t(c.txPower);
    buf[3] = c.hopLimit;
    memcpy(buf + 4, c.networkKey, 32);
    Preferences p;
    if (p.begin(NS, false)) {
        p.putBytes(KEY, buf, sizeof buf);
        p.end();
    }
    clx::wipe(buf, sizeof buf);
}

void eraseRadioConfig() {
    Preferences p;
    if (p.begin(NS, false)) {
        p.clear();
        p.end();
    }
}
