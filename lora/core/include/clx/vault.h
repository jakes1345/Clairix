// The handheld's storage on flash: everything it keeps (its Lite identity,
// contacts, messages and settings) in one blob sealed under a key derived
// from the owner's PIN with Argon2id. Without the PIN the file is noise; with
// a wrong one it does not open. The board has no secure element, so the PIN's
// strength and Argon2's cost are all that stand between someone holding the
// device and its contents: the firmware wipes the vault after too many wrong
// PINs (handheld.h) and the owner can wipe it at any time.
//
//   "CLXV" | version(1) | blocks(4) | passes(1) | salt(16) | nonce(24) | tag(16) | ciphertext
#pragma once

#include <cstdint>
#include <string>

#include "clx/bytes.h"

namespace clx {

constexpr uint8_t VAULT_VERSION = 1;

struct VaultCost {
    uint32_t blocks;  // KiB of memory
    uint32_t passes;
};

/** Derives the key, seals [plain]; [work] must hold cost.blocks KiB. */
bool vaultSeal(const std::string& pin, const Bytes& plain, const VaultCost& cost, void* work, Bytes& out);
/** False for a wrong PIN or a damaged file. [work] must hold the cost the file names (vaultCost). */
bool vaultOpen(const std::string& pin, const Bytes& sealed, void* work, Bytes& plain);
/**
 * A key derived from the PIN, kept in RAM while the handheld is unlocked so
 * that saving does not run Argon2 each time. Each save draws a fresh nonce;
 * the salt and cost stay those the key was derived with. Wipe it on lock.
 */
struct VaultKey {
    uint8_t key[32];
    uint8_t salt[16];
    VaultCost cost;
    void wipe();
};

/** A key for a new vault or a new PIN, with a fresh salt. */
bool vaultNewKey(const std::string& pin, const VaultCost& cost, void* work, VaultKey& out);
/** Opens [sealed] and keeps its key. False for a wrong PIN or a damaged file. */
bool vaultOpenKey(const std::string& pin, const Bytes& sealed, void* work, VaultKey& key, Bytes& plain);
void vaultSealKey(const VaultKey& key, const Bytes& plain, Bytes& out);

/** The cost a sealed vault was made with, to size the work area; false if it is not a vault. */
bool vaultCost(const Bytes& sealed, VaultCost& cost);

}  // namespace clx
