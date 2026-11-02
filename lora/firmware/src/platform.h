#pragma once

#include <cstddef>
#include <cstdint>

/** Mixes [p] into the random pool (the radio's RSSI noise at boot). */
void addEntropy(const uint8_t* p, size_t n);
