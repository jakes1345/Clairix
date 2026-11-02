#include "input.h"

#include <Arduino.h>
#include <Wire.h>

#include "../board.h"

namespace {
volatile uint16_t pulses[4] = {0, 0, 0, 0};  // up, down, left, right
void IRAM_ATTR up() { pulses[0]++; }
void IRAM_ATTR down() { pulses[1]++; }
void IRAM_ATTR left() { pulses[2]++; }
void IRAM_ATTR right() { pulses[3]++; }

uint64_t nextKeyboard = 0;
uint64_t lastBall = 0;
bool clickDown = false;
uint64_t clickAt = 0;
}  // namespace

void inputBegin() {
    pinMode(board::BALL_UP, INPUT_PULLUP);
    pinMode(board::BALL_DOWN, INPUT_PULLUP);
    pinMode(board::BALL_LEFT, INPUT_PULLUP);
    pinMode(board::BALL_RIGHT, INPUT_PULLUP);
    pinMode(board::BALL_CLICK, INPUT_PULLUP);
    attachInterrupt(board::BALL_UP, up, FALLING);
    attachInterrupt(board::BALL_DOWN, down, FALLING);
    attachInterrupt(board::BALL_LEFT, left, FALLING);
    attachInterrupt(board::BALL_RIGHT, right, FALLING);
}

InputEvent inputPoll(uint64_t now) {
    InputEvent e;
    // Trackball: one step per notch, at most one every 60 ms so a flick does not fly past.
    if (now - lastBall >= 60) {
        static const InputKind kinds[4] = {InputKind::Up, InputKind::Down, InputKind::Left, InputKind::Right};
        for (int i = 0; i < 4; i++) {
            if (pulses[i] > 0) {
                noInterrupts();
                for (int j = 0; j < 4; j++) pulses[j] = 0;
                interrupts();
                lastBall = now;
                e.kind = kinds[i];
                return e;
            }
        }
    }
    const bool down = digitalRead(board::BALL_CLICK) == LOW;
    if (down && !clickDown) {
        clickDown = true;
        clickAt = now;
    } else if (!down && clickDown) {
        clickDown = false;
        if (now - clickAt >= 20) {
            e.kind = InputKind::Click;
            return e;
        }
    }
    if (now >= nextKeyboard) {
        nextKeyboard = now + 15;
        if (Wire.requestFrom(board::KEYBOARD_ADDR, uint8_t(1)) == 1) {
            const int c = Wire.read();
            if (c == '\r' || c == '\n') e.kind = InputKind::Enter;
            else if (c == 0x08 || c == 0x7F) e.kind = InputKind::Backspace;
            else if (c >= 0x20 && c < 0x7F) { e.kind = InputKind::Char; e.ch = char(c); }
        }
    }
    return e;
}
