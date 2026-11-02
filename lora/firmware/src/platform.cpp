// The boards' randomBytes (clx/crypto.h).
//
// The ESP32-S3's random number generator is a true RNG while the radio
// subsystem (Bluetooth here, always on) is running. Its output is not used
// raw: each block is SHA-512 of a 64-byte pool seeded at boot with the RNG
// and with RSSI noise sampled by the SX1262 (addEntropy), a counter, and 64
// fresh hardware bytes. A flaw in either source alone does not make the keys
// guessable.
#include <esp_random.h>

#include <cstring>
#include <mutex>

#include "clx/crypto.h"
#include "platform.h"

namespace {
std::mutex lock;
uint8_t pool[64];
uint64_t counter = 0;
bool seeded = false;

void seed() {
    esp_fill_random(pool, sizeof pool);
    seeded = true;
}
}  // namespace

void addEntropy(const uint8_t* p, size_t n) {
    std::lock_guard<std::mutex> g(lock);
    if (!seeded) seed();
    uint8_t buf[64 + 64];
    std::memcpy(buf, pool, 64);
    const size_t take = n < 64 ? n : 64;
    std::memcpy(buf + 64, p, take);
    std::memset(buf + 64 + take, 0, 64 - take);
    clx::sha512(pool, buf, 64 + take);
    clx::wipe(buf, sizeof buf);
}

namespace clx {

void randomBytes(uint8_t* out, size_t n) {
    std::lock_guard<std::mutex> g(lock);
    if (!seeded) seed();
    uint8_t buf[64 + 8 + 64];
    uint8_t block[64];
    while (n > 0) {
        std::memcpy(buf, pool, 64);
        counter++;
        for (int i = 0; i < 8; i++) buf[64 + i] = uint8_t(counter >> (8 * i));
        esp_fill_random(buf + 72, 64);
        sha512(block, buf, sizeof buf);
        // The first half is output, the second the next pool: what was handed out cannot be traced back.
        const size_t take = n < 32 ? n : 32;
        std::memcpy(out, block, take);
        std::memcpy(pool, block + 32, 32);
        out += take;
        n -= take;
    }
    wipe(buf, sizeof buf);
    wipe(block, sizeof block);
}

}  // namespace clx
