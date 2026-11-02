// The T-Deck's 2.8" 320x240 ST7789, through LovyanGFX. It shares its SPI bus
// with the SX1262; all SPI work happens on the main loop, one user at a time.
#pragma once

#define LGFX_USE_V1
#include <LovyanGFX.hpp>

#include "../board.h"

class TDeckDisplay : public lgfx::LGFX_Device {
public:
    TDeckDisplay() {
        {
            auto cfg = bus_.config();
            cfg.spi_host = SPI2_HOST;
            cfg.spi_mode = 0;
            cfg.freq_write = 40000000;
            cfg.freq_read = 16000000;
            cfg.spi_3wire = false;
            cfg.use_lock = true;
            cfg.dma_channel = SPI_DMA_CH_AUTO;
            cfg.pin_sclk = board::LORA_SCK;
            cfg.pin_mosi = board::LORA_MOSI;
            cfg.pin_miso = board::LORA_MISO;
            cfg.pin_dc = board::TFT_DC;
            bus_.config(cfg);
            panel_.setBus(&bus_);
        }
        {
            auto cfg = panel_.config();
            cfg.pin_cs = board::TFT_CS;
            cfg.pin_rst = -1;
            cfg.pin_busy = -1;
            cfg.panel_width = 240;
            cfg.panel_height = 320;
            cfg.offset_x = 0;
            cfg.offset_y = 0;
            cfg.offset_rotation = 0;
            cfg.readable = false;
            cfg.invert = true;
            cfg.rgb_order = false;
            cfg.dlen_16bit = false;
            cfg.bus_shared = true;
            panel_.config(cfg);
        }
        {
            auto cfg = light_.config();
            cfg.pin_bl = board::TFT_BL;
            cfg.invert = false;
            cfg.freq = 44100;
            cfg.pwm_channel = 7;
            light_.config(cfg);
            panel_.setLight(&light_);
        }
        setPanel(&panel_);
    }

private:
    lgfx::Panel_ST7789 panel_;
    lgfx::Bus_SPI bus_;
    lgfx::Light_PWM light_;
};
