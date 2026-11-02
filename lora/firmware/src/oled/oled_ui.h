// The Heltec V3's 128x64 OLED: what network the radio is on, its channel,
// the phone link, and the passkey while a phone bonds. It goes dark after a
// minute; the button wakes it.
#pragma once

#include <cstdint>

class RadioNode;

void oledBegin();
void oledWake(uint64_t now);
void oledShow(RadioNode& node, uint64_t now, const char* notice);
