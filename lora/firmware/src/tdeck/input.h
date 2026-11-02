// The T-Deck's keyboard (an ESP32-C3 on I2C that hands over one ASCII
// character per read) and its trackball (four Hall sensors that pulse a pin
// per notch, and a click on GPIO 0).
#pragma once

#include <cstdint>

enum class InputKind : uint8_t { None, Char, Enter, Backspace, Up, Down, Left, Right, Click };

struct InputEvent {
    InputKind kind = InputKind::None;
    char ch = 0;
};

void inputBegin();
/** The next input, or None. Call often. */
InputEvent inputPoll(uint64_t now);
