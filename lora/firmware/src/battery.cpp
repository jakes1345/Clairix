#include "battery.h"

#include "board.h"

void batteryBegin() {
    if (board::BATTERY_ADC < 0) return;
#if defined(CLX_BOARD_HELTEC_V3)
    // ADC_CTRL switches the divider on only while measuring, so it does not drain the cell.
    pinMode(board::BATTERY_ADC_CTRL, OUTPUT);
    digitalWrite(board::BATTERY_ADC_CTRL, HIGH);
    analogSetPinAttenuation(board::BATTERY_ADC, ADC_2_5db);
#else
    analogSetPinAttenuation(board::BATTERY_ADC, ADC_11db);
#endif
}

uint16_t batteryMv() {
    if (board::BATTERY_ADC < 0) return 0;
#if defined(CLX_BOARD_HELTEC_V3)
    digitalWrite(board::BATTERY_ADC_CTRL, LOW);
    delay(2);
#endif
    uint32_t sum = 0;
    for (int i = 0; i < 8; i++) sum += analogReadMilliVolts(board::BATTERY_ADC);
#if defined(CLX_BOARD_HELTEC_V3)
    digitalWrite(board::BATTERY_ADC_CTRL, HIGH);
#endif
    const float mv = float(sum) / 8.0f * board::BATTERY_DIVIDER;
    // Under about 2.5 V there is no cell, only USB power leaking through.
    return mv < 2500 || mv > 4500 ? 0 : uint16_t(mv);
}
