#pragma once

#include <cstdint>

void batteryBegin();
/** Battery voltage in millivolts; 0 when the board cannot measure it. */
uint16_t batteryMv();
