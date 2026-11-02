// The T-Deck as a handheld of its own: a PIN-locked vault, contacts paired
// face to face, Lite messages and SOS with the GPS position, and the radio's
// settings, all on its screen and keyboard. With the phone link on, it is a
// phone's radio as well.
#pragma once

#include <cstdint>

class RadioNode;

void tdeckSetup(RadioNode& node);
void tdeckLoop(RadioNode& node, uint64_t now);
