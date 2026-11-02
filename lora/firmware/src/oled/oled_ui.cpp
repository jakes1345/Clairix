#include "oled_ui.h"

#include <U8g2lib.h>
#include <Wire.h>

#include "board.h"
#include "clx/code.h"
#include "clx/net.h"
#include "clx/region.h"
#include "node.h"

namespace {
U8G2_SSD1306_128X64_NONAME_F_HW_I2C oled(U8G2_R0, board::OLED_RST, board::OLED_SCL, board::OLED_SDA);
constexpr uint64_t AWAKE_MS = 60000;
uint64_t awakeUntil = 0;
bool lit = false;
uint64_t nextDraw = 0;
}  // namespace

void oledBegin() {
    pinMode(board::VEXT, OUTPUT);
    digitalWrite(board::VEXT, LOW);
    delay(50);
    oled.begin();
    oled.setPowerSave(0);
    lit = true;
    awakeUntil = nowMs() + AWAKE_MS;
}

void oledWake(uint64_t now) {
    awakeUntil = now + AWAKE_MS;
    nextDraw = 0;
}

void oledShow(RadioNode& node, uint64_t now, const char* notice) {
    const uint32_t passkey = node.phone.passkey();
    // A passkey is always shown, whatever the timeout says: the phone is waiting for it.
    const bool awake = now < awakeUntil || passkey != 0;
    if (!awake) {
        if (lit) {
            oled.setPowerSave(1);
            lit = false;
        }
        return;
    }
    if (!lit) {
        oled.setPowerSave(0);
        lit = true;
    }
    if (now < nextDraw) return;
    nextDraw = now + 500;

    char line[40];
    oled.clearBuffer();
    if (passkey != 0) {
        oled.setFont(u8g2_font_6x12_tf);
        oled.drawStr(0, 12, "Bluetooth passkey:");
        oled.setFont(u8g2_font_logisoso24_tn);
        snprintf(line, sizeof line, "%06lu", static_cast<unsigned long>(passkey));
        oled.drawStr(4, 48, line);
        oled.setFont(u8g2_font_6x12_tf);
        oled.drawStr(0, 63, "Type it on your phone");
        oled.sendBuffer();
        return;
    }

    clx::Bridge& b = node.bridge;
    clx::RadioInfo info = b.info(now);
    oled.setFont(u8g2_font_6x12_tf);
    oled.drawStr(0, 10, "Clairix radio");
    if (node.battery() > 0) {
        snprintf(line, sizeof line, "%.2fV", node.battery() / 1000.0);
        oled.drawStr(98, 10, line);
    }
    if (info.publicNetwork) {
        oled.drawStr(0, 22, "Net: public");
    } else {
        snprintf(line, sizeof line, "Net: %02X%02X-%02X%02X", info.networkId[0], info.networkId[1], info.networkId[2], info.networkId[3]);
        oled.drawStr(0, 22, line);
    }
    if (info.channel == clx::CHANNEL_SCANNING) {
        oled.drawStr(0, 34, "Ch: looking for clock");
    } else {
        static const char* const shortName[] = {"Fast", "Mid", "Long"};
        snprintf(line, sizeof line, "Ch %u %.1fMHz %s", info.channel, clx::channelMHz(info.channel),
                 shortName[uint8_t(clx::presetFromByte(info.preset))]);
        oled.drawStr(0, 34, line);
    }
    snprintf(line, sizeof line, "In %lu Out %lu Fwd %lu", static_cast<unsigned long>(info.heard),
             static_cast<unsigned long>(info.sent), static_cast<unsigned long>(info.relayed));
    oled.drawStr(0, 46, line);
    if (notice && *notice) {
        oled.drawStr(0, 58, notice);
    } else if (node.phone.connected()) {
        oled.drawStr(0, 58, "Phone connected");
    } else if (node.phone.windowOpen(now)) {
        oled.drawStr(0, 58, "Pairing open");
    } else {
        snprintf(line, sizeof line, "Phone away  %d bonded", node.phone.bondedPhones());
        oled.drawStr(0, 58, line);
    }
    oled.sendBuffer();
}
