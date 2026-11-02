#include "tdeck_app.h"

#include <Arduino.h>
#include <Preferences.h>
#include <Wire.h>
#include <esp_random.h>

#include <algorithm>
#include <deque>
#include <memory>
#include <string>
#include <vector>

#include "../board.h"
#include "../node.h"
#include "clx/code.h"
#include "clx/crypto.h"
#include "clx/handheld.h"
#include "clx/lite.h"
#include "clx/region.h"
#include "display.h"
#include "gps.h"
#include "input.h"
#include "storage.h"

namespace {

// ── Screen ───────────────────────────────────────────────────────────────

TDeckDisplay lcd;
LGFX_Sprite canvas(&lcd);

constexpr int W = 320, H = 240, HEADER = 20;
constexpr uint16_t BG = 0x0000, FG = 0xFFFF, DIM = 0x8410, ACCENT = 0x05FF, WARN = 0xFD20, BAD = 0xF800, GOOD = 0x07E0;
constexpr uint16_t SELECT_BG = 0x2124;

const lgfx::IFont* const BODY = &lgfx::fonts::FreeSans9pt7b;
const lgfx::IFont* const SMALL = &lgfx::fonts::Font2;
const lgfx::IFont* const BIG = &lgfx::fonts::FreeSansBold18pt7b;

enum class Screen { Setup, Lock, Home, Chat, ChatMenu, ConfirmRemove, ContactInfo, Pair, Settings, Network,
                    ShowCode, EnterCode, EditName, ChangePin, Wipe };

RadioNode* node = nullptr;

enum Align { TL, TR, ML, MR, MC, BL };

/** Draws [text] with its [a] corner or edge at (x, y), in the current font. */
void put(const std::string& text, int x, int y, Align a) {
    const int w = canvas.textWidth(text.c_str());
    const int h = canvas.fontHeight();
    const int dx = a == TR || a == MR ? -w : (a == MC ? -w / 2 : 0);
    const int dy = a == ML || a == MR || a == MC ? -h / 2 : (a == BL ? -h : 0);
    canvas.drawString(text.c_str(), x + dx, y + dy);
}

struct State {
    Screen screen = Screen::Lock;
    bool unlocked = false;
    bool radioStarted = false;
    clx::HandheldState hh;
    clx::VaultKey key{};
    std::unique_ptr<clx::Messenger> messenger;
    std::unique_ptr<clx::Pairing> pairing;
    std::deque<clx::Bytes> heardLocked;  // Lite bodies that arrived while locked, opened on unlock
    bool phoneConfigLocked = false;

    int sel = 0;
    int scroll = 0;
    size_t chat = 0;
    int step = 0;
    std::string input, first;
    std::string toast;
    uint64_t toastUntil = 0;
    bool redraw = true;
    uint64_t nextFrame = 0;
    bool saveWanted = false;
    uint64_t saveAt = 0;

    bool hasFix = false;
    clx::GpsFix fix;
    uint64_t fixAt = 0;
    int tzMinutes = 0;
} app;

void say(const std::string& text, uint64_t now) {
    app.toast = text;
    app.toastUntil = now + 3500;
    app.redraw = true;
}

void go(Screen s) {
    app.screen = s;
    app.sel = 0;
    app.scroll = 0;
    app.step = 0;
    clx::wipe(&app.input[0], app.input.size());
    clx::wipe(&app.first[0], app.first.size());
    app.input.clear();
    app.first.clear();
    app.redraw = true;
}

void requestSave(uint64_t now) {
    app.saveWanted = true;
    app.saveAt = now + 1500;
}

// ── Time ─────────────────────────────────────────────────────────────────

int loadTz() {
    Preferences p;
    p.begin("clxlock", true);
    const int tz = p.getShort("tz", 0);
    p.end();
    return tz;
}

void saveTz(int tz) {
    Preferences p;
    p.begin("clxlock", false);
    p.putShort("tz", int16_t(tz));
    p.end();
}

/** Minutes since 1970 by a clock someone vouches for (phone, GPS or heard from one), else 0. */
uint32_t wallMinutes(uint64_t now) {
    clx::Router& r = node->bridge.router();
    if (r.clockQuality() < clx::CLOCK_HEARD) return 0;
    return uint32_t((uint64_t(r.netSeconds(now)) + clx::NET_EPOCH_UNIX_S) / 60);
}

std::string clockText(uint32_t minutes) {
    if (minutes == 0) return "--:--";
    const int64_t local = int64_t(minutes) + app.tzMinutes;
    char b[8];
    snprintf(b, sizeof b, "%02d:%02d", int((local / 60) % 24), int(local % 60));
    return b;
}

std::string tzText(int tz) {
    char b[16];
    snprintf(b, sizeof b, "UTC%c%d:%02d", tz < 0 ? '-' : '+', abs(tz) / 60, abs(tz) % 60);
    return b;
}

// ── Drawing helpers ──────────────────────────────────────────────────────

std::vector<std::string> wrap(const std::string& text, int width) {
    std::vector<std::string> lines;
    std::string line;
    size_t i = 0;
    while (i < text.size()) {
        size_t j = text.find(' ', i);
        if (j == std::string::npos) j = text.size();
        std::string word = text.substr(i, j - i);
        std::string trial = line.empty() ? word : line + " " + word;
        if (canvas.textWidth(trial.c_str()) <= width) {
            line = trial;
        } else {
            if (!line.empty()) lines.push_back(line);
            // A word wider than the line is cut where it no longer fits.
            while (canvas.textWidth(word.c_str()) > width && word.size() > 1) {
                size_t k = word.size();
                while (k > 1 && canvas.textWidth(word.substr(0, k).c_str()) > width) k--;
                lines.push_back(word.substr(0, k));
                word = word.substr(k);
            }
            line = word;
        }
        i = j + 1;
    }
    if (!line.empty() || lines.empty()) lines.push_back(line);
    return lines;
}

void header(uint64_t now) {
    canvas.fillRect(0, 0, W, HEADER, 0x10A2);
    canvas.setFont(SMALL);
    canvas.setTextColor(FG);
    put(app.unlocked ? app.hh.name.c_str() : "Clairix", 4, HEADER / 2, ML);

    std::string right;
    if (node->phone.enabled() && app.radioStarted) right += node->phone.connected() ? "Phone  " : "";
    if (app.hasFix && now - app.fixAt < 120000) right += "GPS  ";
    if (app.radioStarted) {
        const int ch = node->bridge.router().channel(now);
        right += ch < 0 ? "Scan  " : "Ch " + std::to_string(ch) + "  ";
    }
    const uint16_t mv = node->battery();
    if (mv > 0) {
        const int pct = std::max(0, std::min(100, int((mv - 3300) * 100 / (4150 - 3300))));
        right += std::to_string(pct) + "%  ";
    }
    right += clockText(app.radioStarted ? wallMinutes(now) : 0);
    put(right.c_str(), W - 4, HEADER / 2, MR);
}

void footer(const char* hint) {
    canvas.setFont(SMALL);
    canvas.setTextColor(DIM);
    put(hint, 4, H - 2, BL);
}

void title(const char* t) {
    canvas.setFont(BODY);
    canvas.setTextColor(ACCENT);
    canvas.drawString(t, 6, HEADER + 6);
}

/** A list of rows with an optional value on the right; [sel] highlighted, scrolled into view. */
void list(const std::vector<std::pair<std::string, std::string>>& rows, int top) {
    canvas.setFont(BODY);
    const int rowH = 24;
    const int fit = (H - 18 - top) / rowH;
    int first = std::max(0, app.sel - fit + 1);
    for (int i = first; i < int(rows.size()) && i < first + fit; i++) {
        const int y = top + (i - first) * rowH;
        if (i == app.sel) canvas.fillRoundRect(2, y, W - 4, rowH - 2, 4, SELECT_BG);
        canvas.setTextColor(FG);
        put(rows[size_t(i)].first.c_str(), 10, y + rowH / 2 - 1, ML);
        if (!rows[size_t(i)].second.empty()) {
            canvas.setTextColor(DIM);
            put(rows[size_t(i)].second.c_str(), W - 10, y + rowH / 2 - 1, MR);
        }
    }
}

void textBox(int y, const std::string& text, bool masked) {
    canvas.drawRoundRect(6, y, W - 12, 28, 4, ACCENT);
    canvas.setFont(BODY);
    canvas.setTextColor(FG);
    std::string shown = masked ? std::string(text.size(), '*') : text;
    // Keep the end of the text, where the cursor is, in view.
    while (canvas.textWidth((shown + "_").c_str()) > W - 24 && !shown.empty()) shown.erase(0, 1);
    put((shown + "_").c_str(), 12, y + 14, ML);
}

void paragraph(const std::string& text, int y, uint16_t color = FG) {
    canvas.setFont(BODY);
    canvas.setTextColor(color);
    for (const std::string& line : wrap(text, W - 16)) {
        canvas.drawString(line.c_str(), 8, y);
        y += 20;
    }
}

/** Draws now, for the moments before a blocking step (Argon2, wiping). */
void busy(const char* text) {
    canvas.fillScreen(BG);
    canvas.setFont(BODY);
    canvas.setTextColor(FG);
    put(text, W / 2, H / 2, MC);
    canvas.pushSprite(0, 0);
}

// ── Typing ───────────────────────────────────────────────────────────────

/** Applies a key to app.input; true when the input changed. */
bool type(const InputEvent& e, size_t max) {
    if (e.kind == InputKind::Char && app.input.size() < max) {
        app.input.push_back(e.ch);
        return true;
    }
    if (e.kind == InputKind::Backspace && !app.input.empty()) {
        app.input.pop_back();
        return true;
    }
    return false;
}

// ── Radio, vault and messenger ───────────────────────────────────────────

void applySettings() {
    clx::HandheldSettings& s = app.hh.settings;
    node->bridge.setNetwork(s.networkKey);
    node->bridge.setRadio(s.preset, s.txPower, s.hopLimit);
    node->applyRadio();
    // The bridge may have clamped what it was given.
    s.preset = node->bridge.config().preset;
    s.txPower = node->bridge.config().txPower;
    s.hopLimit = node->bridge.config().hopLimit;
}

void startRadio() {
    if (app.radioStarted) return;
    if (!node->begin(true)) Serial.println("[clairix] radio not found");
    app.radioStarted = true;
    node->bridge.liteTap = [](const clx::Bytes& body, const clx::RxInfo&) {
        const uint64_t now = nowMs();
        if (app.unlocked && app.messenger) {
            app.messenger->onLite(body, now, wallMinutes(now));
        } else {
            app.heardLocked.push_back(body);
            while (app.heardLocked.size() > 64) app.heardLocked.pop_front();
        }
    };
    node->bridge.pairTap = [](const clx::Bytes& body, const clx::RxInfo&) {
        if (app.pairing) app.pairing->onBody(body.data(), body.size(), nowMs());
    };
    node->bridge.onConfig = [](const clx::RadioConfig& c) {
        node->applyRadio();
        if (!app.unlocked) {
            app.phoneConfigLocked = true;
            return;
        }
        clx::HandheldSettings& s = app.hh.settings;
        s.preset = c.preset;
        s.txPower = c.txPower;
        s.hopLimit = c.hopLimit;
        memcpy(s.networkKey, c.networkKey, 32);
        requestSave(nowMs());
    };
}

void afterUnlock(uint64_t now) {
    app.unlocked = true;
    // Messages that were on their way when the handheld was locked or turned off have no
    // retries left running: they show as failed, to send again.
    for (clx::StoredMessage& m : app.hh.messages) {
        if (m.outgoing && (m.state == clx::MsgState::Sending || m.state == clx::MsgState::Sent)) m.state = clx::MsgState::Failed;
    }
    app.messenger.reset(new clx::Messenger(app.hh, [](const clx::Bytes& body, uint8_t hops) {
        return node->bridge.router().sendLite(body, hops, nowMs());
    }));
    app.messenger->onIncoming = [](size_t index) {
        const clx::StoredMessage& m = app.hh.messages[index];
        if (app.screen == Screen::Chat && app.chat == m.contact) {
            app.messenger->markRead(m.contact);
        } else {
            const std::string& from = app.hh.contacts[m.contact].name;
            say(m.kind == clx::LiteKind::Sos ? "SOS from " + from : "Message from " + from, nowMs());
        }
        app.redraw = true;
    };
    startRadio();
    if (app.phoneConfigLocked) {
        // A phone changed the radio while locked: keep what it set.
        const clx::RadioConfig& c = node->bridge.config();
        app.hh.settings.preset = c.preset;
        app.hh.settings.txPower = c.txPower;
        app.hh.settings.hopLimit = c.hopLimit;
        memcpy(app.hh.settings.networkKey, c.networkKey, 32);
        app.phoneConfigLocked = false;
    }
    applySettings();
    node->phone.setEnabled(app.hh.settings.phoneLink);
    setWipeOnTries(app.hh.settings.wipeAfterTries);
    while (!app.heardLocked.empty()) {
        app.messenger->onLite(app.heardLocked.front(), now, wallMinutes(now));
        app.heardLocked.pop_front();
    }
    requestSave(now);
    go(Screen::Home);
}

void saveNow() {
    if (!app.unlocked) return;
    if (!vaultSave(app.key, app.hh)) Serial.println("[clairix] saving the vault failed");
    app.saveWanted = false;
    if (app.messenger) app.messenger->dirty = false;
}

void lock() {
    saveNow();
    app.pairing.reset();
    app.messenger.reset();
    app.key.wipe();
    app.hh.id.wipe();
    app.hh = clx::HandheldState{};
    app.unlocked = false;
    go(Screen::Lock);
}

// ── Screens: drawing ─────────────────────────────────────────────────────

const char* presetName(uint8_t p) { return clx::preset(clx::presetFromByte(p)).name; }

std::string networkText() {
    if (clx::isPublicNetworkKey(app.hh.settings.networkKey)) return "Public";
    const uint8_t* id = node->bridge.router().net().id;
    char b[24];
    snprintf(b, sizeof b, "Private %02X%02X", id[0], id[1]);
    return b;
}

std::vector<std::pair<std::string, std::string>> homeRows() {
    std::vector<std::pair<std::string, std::string>> rows;
    for (const clx::Contact& c : app.hh.contacts) rows.push_back({c.name, c.unread ? std::to_string(c.unread) + " new" : ""});
    rows.push_back({"+ Pair with someone", ""});
    rows.push_back({"Settings", ""});
    rows.push_back({"Lock", ""});
    return rows;
}

std::vector<std::pair<std::string, std::string>> settingsRows() {
    const clx::HandheldSettings& s = app.hh.settings;
    return {
        {"Name", app.hh.name},
        {"Speed and range", presetName(s.preset)},
        {"Power", std::to_string(s.txPower) + " dBm"},
        {"Hops", std::to_string(s.hopLimit)},
        {"Network", networkText()},
        {"Phone link", s.phoneLink ? "On" : "Off"},
        {"Forget bonded phones", ""},
        {"Time zone", tzText(app.tzMinutes)},
        {"Wipe after 10 wrong PINs", s.wipeAfterTries ? "On" : "Off"},
        {"Change PIN", ""},
        {"My fingerprint", clx::fingerprint(app.hh.id.pub)},
        {"Wipe everything", ""},
        {"Back", ""},
    };
}

void drawChat(uint64_t now) {
    const clx::Contact& c = app.hh.contacts[app.chat];
    canvas.setFont(BODY);
    canvas.setTextColor(ACCENT);
    canvas.drawString(c.name.c_str(), 6, HEADER + 3);
    canvas.setFont(SMALL);
    canvas.setTextColor(DIM);
    put(clx::fingerprint(c.pub).c_str(), W - 6, HEADER + 6, TR);

    // Lines from the newest message up, then shown bottom-first above the compose box.
    struct Line { std::string text; uint16_t color; bool right; const lgfx::IFont* font; };
    std::vector<Line> lines;
    const int width = W - 60;
    for (const clx::StoredMessage& m : app.hh.messages) {
        if (m.contact != app.chat) continue;
        canvas.setFont(BODY);
        std::string text = m.text;
        if (m.kind == clx::LiteKind::Sos) {
            char pos[48] = "no position";
            if (m.hasPosition) snprintf(pos, sizeof pos, "%.5f, %.5f", m.lat7 / 1e7, m.lon7 / 1e7);
            text = "SOS: " + (text.empty() ? std::string() : text + " ") + "(" + pos + ")";
        }
        const uint16_t color = m.kind == clx::LiteKind::Sos ? BAD : (m.outgoing ? ACCENT : FG);
        for (const std::string& l : wrap(text, width)) lines.push_back({l, color, m.outgoing, BODY});
        std::string meta = clockText(m.minutes);
        if (m.outgoing) {
            switch (m.state) {
                case clx::MsgState::Sending: meta += "  waiting for the radio"; break;
                case clx::MsgState::Sent: meta += "  sent"; break;
                case clx::MsgState::Delivered: meta += "  delivered"; break;
                case clx::MsgState::Failed: meta += "  not delivered"; break;
                default: break;
            }
        }
        lines.push_back({meta, m.state == clx::MsgState::Failed ? WARN : DIM, m.outgoing, SMALL});
    }
    const int top = HEADER + 24, bottom = H - 34;
    int y = bottom;
    const int maxScroll = std::max(0, int(lines.size()) - 4);
    app.scroll = std::min(app.scroll, maxScroll);
    for (int i = int(lines.size()) - 1 - app.scroll; i >= 0; i--) {
        const Line& l = lines[size_t(i)];
        const int h = l.font == SMALL ? 16 : 19;
        y -= h;
        if (y < top) break;
        canvas.setFont(l.font);
        canvas.setTextColor(l.color);
        put(l.text.c_str(), l.right ? W - 6 : 6, y, (l.right ? TR : TL));
    }
    if (lines.empty()) paragraph("No messages yet. Type below and press Enter.", top + 10, DIM);
    textBox(H - 31, app.input, false);
    (void)now;
}

void drawPair() {
    title("Pair with someone");
    if (!app.pairing) return;
    using S = clx::Pairing::State;
    switch (app.pairing->state()) {
        case S::Idle:
        case S::Waiting:
        case S::Committed:
        case S::Answered:
            paragraph("Hold your radios close together, and have the other person open Pair with someone too.", HEADER + 34);
            paragraph("Looking...", HEADER + 120, DIM);
            footer("Left: cancel");
            break;
        case S::Matched: {
            paragraph("Check the other screen shows the same six digits:", HEADER + 30);
            char d[8];
            snprintf(d, sizeof d, "%06lu", static_cast<unsigned long>(app.pairing->digits()));
            canvas.setFont(BIG);
            canvas.setTextColor(GOOD);
            put(d, W / 2, 130, MC);
            paragraph("Their name: " + app.pairing->peerName(), 162);
            footer("Y: the same, add them    N: not the same, cancel");
            break;
        }
        case S::Failed:
            paragraph("Pairing did not finish. Check the other radio is close and in pairing too.", HEADER + 34, WARN);
            footer("Click: try again    Left: back");
            break;
    }
}

void draw(uint64_t now) {
    canvas.fillScreen(BG);
    header(now);
    switch (app.screen) {
        case Screen::Setup: {
            static const char* const prompts[] = {
                "Name this handheld (others see it when you pair):",
                "Choose a PIN of at least 6 characters. It locks everything on this handheld.",
                "Type the PIN again:"};
            title("Welcome to Clairix");
            paragraph(prompts[std::min(app.step, 2)], HEADER + 32);
            textBox(150, app.input, app.step > 0);
            footer("Enter: next");
            break;
        }
        case Screen::Lock: {
            title("Locked");
            paragraph("Type your PIN and press Enter.", HEADER + 32);
            textBox(110, app.input, true);
            const uint8_t tries = wrongPins();
            if (tries > 0 && wipeOnTries()) {
                paragraph(std::to_string(clx::WIPE_AFTER - std::min<int>(tries, clx::WIPE_AFTER)) + " tries left before this handheld wipes itself.", 150, WARN);
            }
            break;
        }
        case Screen::Home:
            list(homeRows(), HEADER + 4);
            footer("Click: open    Up, down: move");
            break;
        case Screen::Chat:
            drawChat(now);
            break;
        case Screen::ChatMenu:
            title(app.hh.contacts[app.chat].name.c_str());
            list({{app.hasFix ? "Send SOS with my position" : "Send SOS (no GPS position)", ""},
                  {"Send failed messages again", ""},
                  {"Their details", ""},
                  {"Remove contact", ""},
                  {"Back", ""}}, HEADER + 30);
            break;
        case Screen::ConfirmRemove:
            title("Remove contact");
            paragraph("Remove " + app.hh.contacts[app.chat].name + " and every message with them? You would have to pair again.", HEADER + 34);
            footer("Y: remove    N: keep");
            break;
        case Screen::ContactInfo: {
            const clx::Contact& c = app.hh.contacts[app.chat];
            title(c.name.c_str());
            paragraph("Fingerprint: " + clx::fingerprint(c.pub), HEADER + 34);
            paragraph("Compare it with the one on their Settings screen to be sure who you paired with.", HEADER + 60, DIM);
            footer("Left: back");
            break;
        }
        case Screen::Pair:
            drawPair();
            break;
        case Screen::Settings:
            list(settingsRows(), HEADER + 4);
            footer(app.sel == 7 ? "Left, right: change the time zone" : "Click: change");
            break;
        case Screen::Network:
            title(("Network: " + networkText()).c_str());
            list({{"Show this network's code", ""},
                  {"Join with a code", ""},
                  {"Start a new private network", ""},
                  {"Use the public network", ""},
                  {"Back", ""}}, HEADER + 30);
            break;
        case Screen::ShowCode: {
            title("Network code");
            const std::string code = clx::networkCode(app.hh.settings.networkKey);
            canvas.setFont(&lgfx::fonts::FreeMono9pt7b);
            canvas.setTextColor(FG);
            // Eleven groups of five: four groups a line.
            int y = HEADER + 34;
            for (size_t at = 0; at < code.size(); at += 24) {
                canvas.drawString(code.substr(at, 23).c_str(), 10, y);
                y += 22;
            }
            paragraph("Anyone with this code can join this network. Share it face to face, never over an app you do not trust.", y + 6, DIM);
            footer("Left: back");
            break;
        }
        case Screen::EnterCode:
            title("Join with a code");
            paragraph("Type the 55-character code. Spaces do not matter.", HEADER + 32);
            textBox(110, app.input, false);
            footer("Enter: join    Left: back");
            break;
        case Screen::EditName:
            title("Name");
            textBox(70, app.input, false);
            footer("Enter: save    Left: back");
            break;
        case Screen::ChangePin: {
            static const char* const prompts[] = {"Your current PIN:", "The new PIN, at least 6 characters:", "The new PIN again:"};
            title("Change PIN");
            paragraph(prompts[std::min(app.step, 2)], HEADER + 32);
            textBox(110, app.input, true);
            footer("Enter: next    Left: back");
            break;
        }
        case Screen::Wipe:
            title("Wipe everything");
            paragraph("This erases your key, contacts, messages, settings and bonded phones for good. Type WIPE and press Enter.", HEADER + 32, WARN);
            textBox(150, app.input, false);
            footer("Left: back");
            break;
    }
    if (now < app.toastUntil) {
        canvas.setFont(BODY);
        const int w = std::min(W - 20, int(canvas.textWidth(app.toast.c_str())) + 20);
        canvas.fillRoundRect((W - w) / 2, H - 58, w, 26, 6, 0x31A6);
        canvas.setTextColor(FG);
        put(app.toast.c_str(), W / 2, H - 45, MC);
    }
    const uint32_t passkey = app.radioStarted ? node->phone.passkey() : 0;
    if (passkey != 0) {
        canvas.fillRoundRect(30, 50, W - 60, 130, 8, 0x18E3);
        canvas.drawRoundRect(30, 50, W - 60, 130, 8, ACCENT);
        canvas.setFont(BODY);
        canvas.setTextColor(FG);
        put("Bluetooth passkey", W / 2, 72, MC);
        char d[8];
        snprintf(d, sizeof d, "%06lu", static_cast<unsigned long>(passkey));
        canvas.setFont(BIG);
        put(d, W / 2, 112, MC);
        canvas.setFont(SMALL);
        put("Type it on your phone", W / 2, 155, MC);
    }
    canvas.pushSprite(0, 0);
}

// ── Screens: input ───────────────────────────────────────────────────────

void moveSel(const InputEvent& e, int count) {
    if (count <= 0) return;
    if (e.kind == InputKind::Up) app.sel = (app.sel + count - 1) % count;
    if (e.kind == InputKind::Down) app.sel = (app.sel + 1) % count;
}

void setNetwork(const uint8_t key[32], uint64_t now) {
    memcpy(app.hh.settings.networkKey, key, 32);
    applySettings();
    requestSave(now);
}

void handle(const InputEvent& e, uint64_t now) {
    app.redraw = true;
    switch (app.screen) {
        case Screen::Setup:
            if (e.kind != InputKind::Enter) {
                type(e, app.step == 0 ? clx::NAME_MAX : 64);
                return;
            }
            if (app.step == 0) {
                if (app.input.empty()) return;
                app.first = app.input;  // the name, for now
                app.hh.name = app.input;
                app.input.clear();
                app.step = 1;
            } else if (app.step == 1) {
                if (app.input.size() < 6) { say("At least 6 characters", now); return; }
                app.first = app.input;
                app.input.clear();
                app.step = 2;
            } else {
                if (app.input != app.first) {
                    say("The two PINs differ. Choose again.", now);
                    app.input.clear();
                    app.step = 1;
                    return;
                }
                busy("Securing your vault...");
                const std::string name = app.hh.name;
                if (!vaultKeyFor(app.input, app.key)) { say("Could not make the vault", now); return; }
                app.hh = clx::freshState(name);
                if (!vaultSave(app.key, app.hh)) { say("Could not write the vault", now); return; }
                clearTries();
                afterUnlock(now);
            }
            return;

        case Screen::Lock:
            if (e.kind != InputKind::Enter) {
                type(e, 64);
                return;
            }
            if (app.input.empty()) return;
            busy("Unlocking...");
            countTry();
            if (vaultUnlock(app.input, app.key, app.hh)) {
                clearTries();
                afterUnlock(now);
                return;
            }
            app.input.clear();
            if (wipeOnTries() && wrongPins() >= clx::WIPE_AFTER) {
                busy("Too many wrong PINs. Wiping...");
                wipeEverything();
            }
            say("Wrong PIN", now);
            return;

        case Screen::Home: {
            const int n = int(app.hh.contacts.size());
            moveSel(e, n + 3);
            if (e.kind != InputKind::Click && e.kind != InputKind::Enter) return;
            if (app.sel < n) {
                app.chat = size_t(app.sel);
                go(Screen::Chat);
                app.messenger->markRead(app.chat);
            } else if (app.sel == n) {
                go(Screen::Pair);
                app.pairing.reset(new clx::Pairing([](const clx::Bytes& body) { node->bridge.router().sendPair(body, nowMs()); }));
                app.pairing->start(app.hh.id, app.hh.name, now, esp_random() % 3000);
            } else if (app.sel == n + 1) {
                go(Screen::Settings);
            } else {
                lock();
            }
            return;
        }

        case Screen::Chat:
            if (e.kind == InputKind::Left) { go(Screen::Home); return; }
            if (e.kind == InputKind::Up) { app.scroll++; return; }
            if (e.kind == InputKind::Down) { app.scroll = std::max(0, app.scroll - 1); return; }
            if (e.kind == InputKind::Click) {
                const std::string draft = app.input;
                go(Screen::ChatMenu);
                app.input = draft;
                return;
            }
            if (e.kind == InputKind::Enter) {
                if (app.input.empty()) return;
                if (app.messenger->sendText(app.chat, app.input, now, wallMinutes(now)) < 0) {
                    say("Could not send", now);
                    return;
                }
                app.input.clear();
                app.scroll = 0;
                return;
            }
            type(e, clx::LITE_TEXT_MAX);
            return;

        case Screen::ChatMenu: {
            moveSel(e, 5);
            if (e.kind == InputKind::Left) { const std::string d = app.input; go(Screen::Chat); app.input = d; return; }
            if (e.kind != InputKind::Click && e.kind != InputKind::Enter) return;
            const std::string draft = app.input;
            switch (app.sel) {
                case 0: {
                    const bool fresh = app.hasFix && now - app.fixAt < 10 * 60000;
                    app.messenger->sendSos(app.chat, fresh, app.fix.lat7, app.fix.lon7, draft.empty() ? "Need help" : draft, now, wallMinutes(now));
                    go(Screen::Chat);
                    say("SOS sent", now);
                    return;
                }
                case 1: {
                    std::vector<uint32_t> ids;
                    for (const clx::StoredMessage& m : app.hh.messages)
                        if (m.contact == app.chat && m.outgoing && m.state == clx::MsgState::Failed) ids.push_back(m.id);
                    for (uint32_t id : ids) {
                        for (size_t i = 0; i < app.hh.messages.size(); i++) {
                            clx::StoredMessage& m = app.hh.messages[i];
                            if (m.id != id || m.contact != app.chat || !m.outgoing) continue;
                            app.messenger->resend(i, now, wallMinutes(now));
                            break;
                        }
                    }
                    go(Screen::Chat);
                    app.input = draft;
                    say(ids.empty() ? "Nothing to send again" : "Sending again", now);
                    return;
                }
                case 2: go(Screen::ContactInfo); return;
                case 3: go(Screen::ConfirmRemove); return;
                default: go(Screen::Chat); app.input = draft; return;
            }
        }

        case Screen::ConfirmRemove:
            if (e.kind == InputKind::Char && (e.ch == 'y' || e.ch == 'Y')) {
                app.messenger->removeContact(app.chat);
                requestSave(now);
                go(Screen::Home);
                say("Removed", now);
            } else if ((e.kind == InputKind::Char && (e.ch == 'n' || e.ch == 'N')) || e.kind == InputKind::Left) {
                go(Screen::Chat);
            }
            return;

        case Screen::ContactInfo:
            if (e.kind == InputKind::Left || e.kind == InputKind::Click) go(Screen::Chat);
            return;

        case Screen::Pair: {
            using S = clx::Pairing::State;
            const S st = app.pairing ? app.pairing->state() : S::Failed;
            if (e.kind == InputKind::Left || (st == S::Matched && e.kind == InputKind::Char && (e.ch == 'n' || e.ch == 'N'))) {
                if (app.pairing) app.pairing->cancel();
                app.pairing.reset();
                go(Screen::Home);
                return;
            }
            if (st == S::Matched && e.kind == InputKind::Char && (e.ch == 'y' || e.ch == 'Y')) {
                const std::string name = app.pairing->peerName().empty() ? "Contact" : app.pairing->peerName();
                const bool ok = app.messenger->addContact(app.pairing->peer(), name, wallMinutes(now));
                app.pairing.reset();
                requestSave(now);
                go(Screen::Home);
                say(ok ? "Added " + name : "Contact list is full", now);
                return;
            }
            if (st == S::Failed && e.kind == InputKind::Click) app.pairing->start(app.hh.id, app.hh.name, now, esp_random() % 3000);
            return;
        }

        case Screen::Settings: {
            clx::HandheldSettings& s = app.hh.settings;
            if (app.sel == 7 && (e.kind == InputKind::Left || e.kind == InputKind::Right)) {
                app.tzMinutes = std::max(-12 * 60, std::min(14 * 60, app.tzMinutes + (e.kind == InputKind::Right ? 15 : -15)));
                saveTz(app.tzMinutes);
                return;
            }
            if (e.kind == InputKind::Left) { go(Screen::Home); return; }
            moveSel(e, 13);
            if (e.kind != InputKind::Click && e.kind != InputKind::Enter) return;
            switch (app.sel) {
                case 0: { go(Screen::EditName); app.input = app.hh.name; app.sel = 0; return; }
                case 1: s.preset = uint8_t((s.preset + 1) % clx::PRESET_COUNT); break;
                case 2: {
                    static const int8_t levels[] = {22, 20, 17, 14, 10, 6, 2};
                    size_t i = 0;
                    while (i < sizeof levels && levels[i] > s.txPower) i++;
                    s.txPower = levels[(i + 1) % sizeof levels];
                    break;
                }
                case 3: s.hopLimit = uint8_t(s.hopLimit % clx::MAX_HOP_LIMIT + 1); break;
                case 4: go(Screen::Network); return;
                case 5:
                    s.phoneLink = !s.phoneLink;
                    node->phone.setEnabled(s.phoneLink);
                    if (s.phoneLink) {
                        node->phone.openWindow(now);
                        say("Pair from Clairix on your phone", now);
                    }
                    requestSave(now);
                    return;
                case 6: node->phone.forgetPhones(); say("Phones forgotten", now); return;
                case 7: app.tzMinutes = app.tzMinutes + 60 > 14 * 60 ? -12 * 60 : app.tzMinutes + 60; saveTz(app.tzMinutes); return;
                case 8:
                    s.wipeAfterTries = !s.wipeAfterTries;
                    setWipeOnTries(s.wipeAfterTries);
                    requestSave(now);
                    return;
                case 9: go(Screen::ChangePin); return;
                case 10: return;
                case 11: go(Screen::Wipe); return;
                default: go(Screen::Home); return;
            }
            const int keep = app.sel;
            applySettings();
            requestSave(now);
            app.sel = keep;
            return;
        }

        case Screen::Network:
            if (e.kind == InputKind::Left) { go(Screen::Settings); app.sel = 4; return; }
            moveSel(e, 5);
            if (e.kind != InputKind::Click && e.kind != InputKind::Enter) return;
            switch (app.sel) {
                case 0: go(Screen::ShowCode); return;
                case 1: go(Screen::EnterCode); return;
                case 2: {
                    uint8_t key[32];
                    clx::randomBytes(key, 32);
                    setNetwork(key, now);
                    clx::wipe(key, 32);
                    go(Screen::ShowCode);
                    say("New private network", now);
                    return;
                }
                case 3: {
                    uint8_t key[32];
                    clx::publicNetworkKey(key);
                    setNetwork(key, now);
                    say("On the public network", now);
                    return;
                }
                default: go(Screen::Settings); app.sel = 4; return;
            }

        case Screen::ShowCode:
            if (e.kind == InputKind::Left || e.kind == InputKind::Click) go(Screen::Network);
            return;

        case Screen::EnterCode:
            if (e.kind == InputKind::Left) { go(Screen::Network); return; }
            if (e.kind == InputKind::Enter) {
                uint8_t key[32];
                if (!clx::parseNetworkCode(app.input, key)) {
                    say("That code has a typo", now);
                    return;
                }
                setNetwork(key, now);
                clx::wipe(key, 32);
                go(Screen::Network);
                say("Joined", now);
                return;
            }
            type(e, 80);
            return;

        case Screen::EditName:
            if (e.kind == InputKind::Left) { go(Screen::Settings); return; }
            if (e.kind == InputKind::Enter && !app.input.empty()) {
                app.hh.name = app.input;
                requestSave(now);
                go(Screen::Settings);
                return;
            }
            type(e, clx::NAME_MAX);
            return;

        case Screen::ChangePin:
            if (e.kind == InputKind::Left) { go(Screen::Settings); app.sel = 9; return; }
            if (e.kind != InputKind::Enter) { type(e, 64); return; }
            if (app.step == 0) {
                busy("Checking...");
                clx::VaultKey check{};
                clx::HandheldState scratch;
                const bool ok = vaultUnlock(app.input, check, scratch);
                check.wipe();
                scratch.id.wipe();
                app.input.clear();
                if (!ok) { say("Wrong PIN", now); return; }
                app.step = 1;
            } else if (app.step == 1) {
                if (app.input.size() < 6) { say("At least 6 characters", now); return; }
                app.first = app.input;
                app.input.clear();
                app.step = 2;
            } else {
                if (app.input != app.first) { say("The two PINs differ", now); app.input.clear(); app.step = 1; return; }
                busy("Securing your vault...");
                clx::VaultKey fresh{};
                if (!vaultKeyFor(app.input, fresh)) { say("Could not change the PIN", now); return; }
                app.key.wipe();
                app.key = fresh;
                fresh.wipe();
                saveNow();
                go(Screen::Settings);
                say("PIN changed", now);
            }
            return;

        case Screen::Wipe:
            if (e.kind == InputKind::Left) { go(Screen::Settings); return; }
            if (e.kind == InputKind::Enter) {
                if (app.input == "WIPE" || app.input == "wipe") {
                    busy("Wiping...");
                    wipeEverything();
                }
                say("Type WIPE to wipe", now);
                return;
            }
            type(e, 8);
            return;
    }
}

}  // namespace

void tdeckSetup(RadioNode& n) {
    node = &n;
    // Everything on the shared SPI bus deselected before anyone talks on it.
    pinMode(board::POWER_ON, OUTPUT);
    digitalWrite(board::POWER_ON, HIGH);
    for (int cs : {board::TFT_CS, board::LORA_NSS, board::SD_CS}) {
        pinMode(cs, OUTPUT);
        digitalWrite(cs, HIGH);
    }
    delay(200);  // the keyboard's own chip starts after power-on
    Wire.begin(board::I2C_SDA, board::I2C_SCL);
    lcd.init();
    lcd.setRotation(1);
    lcd.setBrightness(160);
    canvas.setPsram(true);
    canvas.setColorDepth(16);
    if (!canvas.createSprite(W, H)) {
        canvas.setPsram(false);
        canvas.setColorDepth(8);
        canvas.createSprite(W, H);
    }
    inputBegin();
    gpsBegin();
    app.tzMinutes = loadTz();
    if (!storageBegin()) Serial.println("[clairix] flash file system did not mount");
    go(vaultExists() ? Screen::Lock : Screen::Setup);
    Serial.printf("[clairix] %s %s\n", board::NAME, CLX_FIRMWARE_VERSION);
}

void tdeckLoop(RadioNode& n, uint64_t now) {
    if (app.radioStarted) n.step(now);

    clx::GpsFix fix;
    if (gpsPoll(now, fix)) {
        const bool first = !app.hasFix;
        app.fix = fix;
        app.hasFix = true;
        app.fixAt = now;
        if (app.radioStarted) n.bridge.router().setClock(fix.unixMs, clx::CLOCK_TRUE, now);
        if (first) app.redraw = true;
    }

    if (app.unlocked) {
        if (app.messenger) {
            app.messenger->tick(now);
            if (app.messenger->dirty) {
                app.messenger->dirty = false;
                requestSave(now);
                app.redraw = true;
            }
        }
        if (app.pairing) {
            const auto before = app.pairing->state();
            app.pairing->tick(now);
            if (app.pairing->state() != before) app.redraw = true;
        }
        if (app.saveWanted && now >= app.saveAt) saveNow();
    }

    for (int i = 0; i < 4; i++) {
        const InputEvent e = inputPoll(now);
        if (e.kind == InputKind::None) break;
        handle(e, now);
    }

    // The passkey and the header change on their own: a frame a second, more when something happened.
    static uint32_t shownPasskey = 0;
    const uint32_t pk = app.radioStarted ? n.phone.passkey() : 0;
    if (pk != shownPasskey) {
        shownPasskey = pk;
        app.redraw = true;
    }
    if (app.redraw || now >= app.nextFrame) {
        app.redraw = false;
        app.nextFrame = now + 1000;
        draw(now);
    }
    delay(1);
}
