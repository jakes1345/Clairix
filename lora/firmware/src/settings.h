// The radio settings a phone set, kept in flash (NVS) across power cycles,
// for the boards without a vault. The network key sits there in the clear:
// whoever holds the board can read it out. Messages stay end-to-end
// encrypted between phones regardless; the key only lets them hear and join
// the network's link layer.
#pragma once

#include "clx/link.h"

bool loadRadioConfig(clx::RadioConfig& out);
void saveRadioConfig(const clx::RadioConfig& c);
void eraseRadioConfig();
