// The T-Deck Plus's GPS receiver, read for the time (the radio's clock) and
// a position (SOS messages). The plain T-Deck has none; nothing arrives.
#pragma once

#include <cstdint>

#include "clx/nmea.h"

void gpsBegin();
/** Reads what arrived; true when it brought a new fix, in [fix]. */
bool gpsPoll(uint64_t now, clx::GpsFix& fix);
/** Any NMEA heard at all: there is a receiver. */
bool gpsPresent();
