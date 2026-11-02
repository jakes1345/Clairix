#include "storage.h"

#include <Arduino.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <esp_heap_caps.h>
#include <esp_partition.h>
#include <nvs_flash.h>

#include <algorithm>

#include "clx/crypto.h"

namespace {
constexpr const char* VAULT = "/vault.bin";
constexpr const char* VAULT_NEW = "/vault.new";
constexpr const char* NS = "clxlock";

// Argon2id: 4 MiB in PSRAM, 3 passes. Without PSRAM, 256 KiB in RAM.
constexpr clx::VaultCost COST_PSRAM{4096, 3};
constexpr clx::VaultCost COST_RAM{256, 3};

void* argonWork(uint32_t blocks) {
    void* p = heap_caps_malloc(size_t(blocks) * 1024, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!p) p = heap_caps_malloc(size_t(blocks) * 1024, MALLOC_CAP_8BIT);
    return p;
}

void freeWork(void* p, uint32_t blocks) {
    if (!p) return;
    clx::wipe(p, size_t(blocks) * 1024);
    heap_caps_free(p);
}

bool readFile(const char* path, clx::Bytes& out) {
    File f = LittleFS.open(path, "r");
    if (!f) return false;
    out.resize(f.size());
    const size_t n = f.read(out.data(), out.size());
    f.close();
    return n == out.size();
}
}  // namespace

bool storageBegin() { return LittleFS.begin(true); }

bool vaultExists() { return LittleFS.exists(VAULT); }

bool vaultUnlock(const std::string& pin, clx::VaultKey& key, clx::HandheldState& state) {
    clx::Bytes sealed, plain;
    clx::VaultCost cost;
    if (!readFile(VAULT, sealed) || !clx::vaultCost(sealed, cost)) return false;
    void* work = argonWork(cost.blocks);
    if (!work) return false;
    const bool ok = clx::vaultOpenKey(pin, sealed, work, key, plain);
    freeWork(work, cost.blocks);
    if (!ok) return false;
    const bool parsed = clx::deserializeState(plain, state);
    clx::wipe(plain.data(), plain.size());
    if (!parsed) key.wipe();
    return parsed;
}

bool vaultKeyFor(const std::string& pin, clx::VaultKey& key) {
    clx::VaultCost cost = COST_PSRAM;
    void* work = heap_caps_malloc(size_t(cost.blocks) * 1024, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!work) {
        cost = COST_RAM;
        work = argonWork(cost.blocks);
    }
    if (!work) return false;
    const bool ok = clx::vaultNewKey(pin, cost, work, key);
    freeWork(work, cost.blocks);
    return ok;
}

bool vaultSave(const clx::VaultKey& key, const clx::HandheldState& state) {
    clx::Bytes plain = clx::serializeState(state);
    clx::Bytes sealed;
    clx::vaultSealKey(key, plain, sealed);
    clx::wipe(plain.data(), plain.size());
    File f = LittleFS.open(VAULT_NEW, "w");
    if (!f) return false;
    const size_t n = f.write(sealed.data(), sealed.size());
    f.close();
    if (n != sealed.size()) {
        LittleFS.remove(VAULT_NEW);
        return false;
    }
    // LittleFS renames atomically, over the old file: a power cut leaves one whole vault or the other.
    return LittleFS.rename(VAULT_NEW, VAULT);
}

uint8_t wrongPins() {
    Preferences p;
    p.begin(NS, true);
    const uint8_t n = p.getUChar("tries", 0);
    p.end();
    return n;
}

void countTry() {
    Preferences p;
    p.begin(NS, false);
    p.putUChar("tries", uint8_t(std::min<int>(255, p.getUChar("tries", 0) + 1)));
    p.end();
}

void clearTries() {
    Preferences p;
    p.begin(NS, false);
    p.putUChar("tries", 0);
    p.end();
}

bool wipeOnTries() {
    Preferences p;
    p.begin(NS, true);
    const bool on = p.getBool("wipe", true);
    p.end();
    return on;
}

void setWipeOnTries(bool on) {
    Preferences p;
    p.begin(NS, false);
    p.putBool("wipe", on);
    p.end();
}

void wipeEverything() {
    // LittleFS writes copy-on-write, so old versions of the vault sit in blocks it has not
    // reused yet. Erase the whole partition, not just the file.
    LittleFS.end();
    const esp_partition_t* fs = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_DATA_SPIFFS, nullptr);
    if (fs) esp_partition_erase_range(fs, 0, fs->size);
    nvs_flash_erase();
    nvs_flash_init();
    delay(200);
    ESP.restart();
    while (true) {}
}
