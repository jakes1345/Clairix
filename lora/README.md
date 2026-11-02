# Clairix LoRa

Clairix's own long-range radio: the protocol (`core/`, built and tested on the
host with CMake) and the firmware for the Heltec WiFi LoRa 32 V3, the Seeed
XIAO ESP32S3 with the Wio-SX1262, and the LilyGO T-Deck (`firmware/`,
PlatformIO).

    cmake -S lora/core -B build/lora && cmake --build build/lora && ctest --test-dir build/lora
    python3 lora/core/tests/crosscheck.py lora/core/tests/known_answers.h
    pip install --require-hashes -r lora/firmware/requirements.txt
    pio run -d lora/firmware                          # all three boards
    pio run -d lora/firmware -e tdeck -t upload       # flash one

What it does, the exact cryptography, what it does not hide, and what to test
on hardware: [docs/LORA.md](../docs/LORA.md).
