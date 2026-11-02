#include "clx/vault.h"

#include <cstring>

#include "clx/crypto.h"

namespace clx {

static const uint8_t MAGIC[4] = {'C', 'L', 'X', 'V'};
static constexpr size_t HEADER = 4 + 1 + 4 + 1 + 16 + 24 + 16;
static constexpr uint32_t MAX_BLOCKS = 65536;  // 64 MiB: anything more is not a file this firmware wrote

bool vaultCost(const Bytes& sealed, VaultCost& cost) {
    if (sealed.size() < HEADER || std::memcmp(sealed.data(), MAGIC, 4) != 0 || sealed[4] != VAULT_VERSION) return false;
    cost.blocks = get32(&sealed[5]);
    cost.passes = sealed[9];
    return cost.blocks >= 8 && cost.blocks <= MAX_BLOCKS && cost.passes >= 1;
}

static void deriveKey(const std::string& pin, const uint8_t salt[16], const VaultCost& cost, void* work, uint8_t key[32]) {
    argon2id(key, 32, reinterpret_cast<const uint8_t*>(pin.data()), pin.size(), salt, cost.blocks, cost.passes, work);
}

void VaultKey::wipe() { clx::wipe(this, sizeof *this); }

bool vaultNewKey(const std::string& pin, const VaultCost& cost, void* work, VaultKey& out) {
    if (pin.empty() || cost.blocks < 8 || cost.blocks > MAX_BLOCKS || cost.passes < 1 || cost.passes > 255) return false;
    out.cost = cost;
    randomBytes(out.salt, 16);
    deriveKey(pin, out.salt, cost, work, out.key);
    return true;
}

void vaultSealKey(const VaultKey& k, const Bytes& plain, Bytes& out) {
    out.assign(HEADER + plain.size(), 0);
    std::memcpy(&out[0], MAGIC, 4);
    out[4] = VAULT_VERSION;
    put32(&out[5], k.cost.blocks);
    out[9] = uint8_t(k.cost.passes);
    std::memcpy(&out[10], k.salt, 16);
    uint8_t* nonce = &out[26];
    uint8_t* tag = &out[50];
    randomBytes(nonce, 24);
    // The header is the associated data: its cost and salt cannot be swapped.
    xaeadSeal(&out[HEADER], tag, k.key, nonce, out.data(), 50, plain.data(), plain.size());
}

bool vaultSeal(const std::string& pin, const Bytes& plain, const VaultCost& cost, void* work, Bytes& out) {
    VaultKey k;
    if (!vaultNewKey(pin, cost, work, k)) return false;
    vaultSealKey(k, plain, out);
    k.wipe();
    return true;
}

bool vaultOpenKey(const std::string& pin, const Bytes& sealed, void* work, VaultKey& k, Bytes& plain) {
    if (!vaultCost(sealed, k.cost)) return false;
    std::memcpy(k.salt, &sealed[10], 16);
    deriveKey(pin, k.salt, k.cost, work, k.key);
    plain.assign(sealed.size() - HEADER, 0);
    bool ok = xaeadOpen(plain.data(), &sealed[50], k.key, &sealed[26], sealed.data(), 50, &sealed[HEADER], plain.size());
    if (!ok) { wipe(plain.data(), plain.size()); plain.clear(); k.wipe(); }
    return ok;
}

bool vaultOpen(const std::string& pin, const Bytes& sealed, void* work, Bytes& plain) {
    VaultKey k;
    bool ok = vaultOpenKey(pin, sealed, work, k, plain);
    k.wipe();
    return ok;
}

}  // namespace clx
