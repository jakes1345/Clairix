// Pins and parts of each board Clairix runs on. One of CLX_BOARD_* is set by
// the PlatformIO environment.
#pragma once

#include <Arduino.h>

#include "clx/link.h"

#if defined(CLX_BOARD_HELTEC_V3)
// Heltec WiFi LoRa 32 V3: SX1262 with a 1.8 V TCXO, DIO2 drives the RF switch.
#define CLX_HAS_OLED 1
#define CLX_HAS_PASSKEY_SCREEN 1
namespace board {
constexpr clx::BoardModel MODEL = clx::BoardModel::HeltecV3;
constexpr const char* NAME = "Heltec V3";
constexpr int LORA_NSS = 8, LORA_SCK = 9, LORA_MOSI = 10, LORA_MISO = 11;
constexpr int LORA_RST = 12, LORA_BUSY = 13, LORA_DIO1 = 14, LORA_RXEN = -1;
constexpr float LORA_TCXO_V = 1.8f;
constexpr int VEXT = 36;  // LOW powers the OLED
constexpr int OLED_SDA = 17, OLED_SCL = 18, OLED_RST = 21;
constexpr int BUTTON = 0;  // PRG
constexpr int LED = 35;
constexpr int BATTERY_ADC = 1, BATTERY_ADC_CTRL = 37;
constexpr float BATTERY_DIVIDER = 4.9f * 1.045f;
}  // namespace board

#elif defined(CLX_BOARD_XIAO_S3_WIO)
// Seeed XIAO ESP32S3 on the Wio-SX1262 board: DIO2 and RXEN switch the antenna.
// No screen: phones bond with "just works" pairing, and only while the
// pairing window is open (the BOOT button, or no phone bonded yet).
namespace board {
constexpr clx::BoardModel MODEL = clx::BoardModel::XiaoS3;
constexpr const char* NAME = "XIAO S3 + Wio-SX1262";
constexpr int LORA_NSS = 41, LORA_SCK = 7, LORA_MOSI = 9, LORA_MISO = 8;
constexpr int LORA_RST = 42, LORA_BUSY = 40, LORA_DIO1 = 39, LORA_RXEN = 38;
constexpr float LORA_TCXO_V = 1.8f;
constexpr int BUTTON = 0;  // BOOT
constexpr int LED = 21;    // the XIAO's user LED, lit when LOW
constexpr int BATTERY_ADC = -1;
constexpr float BATTERY_DIVIDER = 0;
}  // namespace board

#elif defined(CLX_BOARD_TDECK)
// LilyGO T-Deck and T-Deck Plus. The screen and the radio share one SPI bus.
#define CLX_HAS_PASSKEY_SCREEN 1
#define CLX_STANDALONE 1
namespace board {
constexpr clx::BoardModel MODEL = clx::BoardModel::TDeck;
constexpr const char* NAME = "T-Deck";
constexpr int POWER_ON = 10;  // HIGH powers the keyboard, radio and GPS
constexpr int LORA_NSS = 9, LORA_SCK = 40, LORA_MOSI = 41, LORA_MISO = 38;
constexpr int LORA_RST = 17, LORA_BUSY = 13, LORA_DIO1 = 45, LORA_RXEN = -1;
constexpr float LORA_TCXO_V = 1.8f;
constexpr int TFT_CS = 12, TFT_DC = 11, TFT_BL = 42;
constexpr int SD_CS = 39;
constexpr int I2C_SDA = 18, I2C_SCL = 8;
constexpr uint8_t KEYBOARD_ADDR = 0x55;
constexpr int BALL_UP = 3, BALL_DOWN = 15, BALL_LEFT = 1, BALL_RIGHT = 2, BALL_CLICK = 0;
constexpr int GPS_RX = 44, GPS_TX = 43;
constexpr int BUTTON = 0;  // the trackball's click is also GPIO 0
constexpr int LED = -1;
constexpr int BATTERY_ADC = 4;
constexpr float BATTERY_DIVIDER = 2.0f;
}  // namespace board

#else
#error "Set one of CLX_BOARD_HELTEC_V3, CLX_BOARD_XIAO_S3_WIO, CLX_BOARD_TDECK"
#endif

#ifndef CLX_FIRMWARE_VERSION
#define CLX_FIRMWARE_VERSION "dev"
#endif

/** Milliseconds since boot, not wrapping. */
inline uint64_t nowMs() { return uint64_t(esp_timer_get_time() / 1000); }
