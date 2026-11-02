// Clairix LoRa radio firmware: the entry point for every board.
//
// Heltec V3 and XIAO: a radio for a phone. They relay for the network
// whether or not a phone is connected, hold what arrives for their own phone,
// and take their settings from it.
//
// T-Deck: a handheld (tdeck/), which can also serve a phone.
#include <Arduino.h>

#include "board.h"
#include "node.h"
#include "settings.h"

#if defined(CLX_HAS_OLED)
#include "oled/oled_ui.h"
#endif
#if defined(CLX_STANDALONE)
#include "tdeck/tdeck_app.h"
#endif

namespace {
RadioNode node;
bool radioOk = false;

#if !defined(CLX_STANDALONE)
constexpr uint32_t FORGET_HOLD_MS = 10000;
uint64_t pressedAt = 0;
bool pressed = false;
const char* notice = "";
uint64_t noticeUntil = 0;

void say(const char* text, uint64_t now) {
    notice = text;
    noticeUntil = now + 5000;
}

void led(bool on) {
    if (board::LED < 0) return;
#if defined(CLX_BOARD_XIAO_S3_WIO)
    digitalWrite(board::LED, on ? LOW : HIGH);
#else
    digitalWrite(board::LED, on ? HIGH : LOW);
#endif
}

/** Short press: open the pairing window (and wake the screen). Held 10 s: forget every phone. */
void button(uint64_t now) {
    const bool down = digitalRead(board::BUTTON) == LOW;
    if (down && !pressed) {
        pressed = true;
        pressedAt = now;
    } else if (!down && pressed) {
        pressed = false;
        const uint64_t held = now - pressedAt;
        if (held < 30) return;  // bounce
#if defined(CLX_HAS_OLED)
        oledWake(now);
#endif
        if (held >= FORGET_HOLD_MS) {
            node.phone.forgetPhones();
            say("Phones forgotten", now);
        } else {
            node.phone.openWindow(now);
            say("Pairing open 2 min", now);
        }
    }
}

/** The LED: off; slow blink while pairing is open; short blink each second with a phone connected. */
void blink(uint64_t now) {
    if (pressed && now - pressedAt >= FORGET_HOLD_MS) {
        led((now / 100) % 2);
    } else if (!radioOk) {
        led((now / 150) % 2);
    } else if (node.phone.windowOpen(now) && !node.phone.connected()) {
        led((now / 500) % 2);
    } else if (node.phone.connected()) {
        led(now % 1000 < 40);
    } else {
        led(false);
    }
}
#endif
}  // namespace

void setup() {
    Serial.begin(115200);
#if defined(CLX_STANDALONE)
    tdeckSetup(node);
#else
    pinMode(board::BUTTON, INPUT_PULLUP);
    if (board::LED >= 0) pinMode(board::LED, OUTPUT);
#if defined(CLX_HAS_OLED)
    oledBegin();
#endif
    clx::RadioConfig saved;
    if (loadRadioConfig(saved)) node.bridge.restore(saved);
    node.bridge.onConfig = [](const clx::RadioConfig& c) {
        saveRadioConfig(c);
        node.applyRadio();
    };
    radioOk = node.begin(false);
    Serial.printf("[clairix] %s %s, radio %s\n", board::NAME, CLX_FIRMWARE_VERSION, radioOk ? "ok" : "not found");
#endif
}

void loop() {
    const uint64_t now = nowMs();
#if defined(CLX_STANDALONE)
    tdeckLoop(node, now);
#else
    node.step(now);
    button(now);
    blink(now);
#if defined(CLX_HAS_OLED)
    if (now > noticeUntil) notice = "";
    oledShow(node, now, radioOk ? notice : "Radio not found");
#endif
    delay(1);
#endif
}
