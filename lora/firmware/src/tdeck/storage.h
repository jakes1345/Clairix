// The handheld's vault on flash (LittleFS) and the wrong-PIN counter (NVS).
//
// The counter lives outside the vault so it survives a power cut between
// tries: it is counted before each attempt and cleared after a good one.
// Wiping erases the vault and all of NVS (bonded phones, radio settings)
// and restarts.
#pragma once

#include <string>

#include "clx/handheld.h"
#include "clx/vault.h"

bool storageBegin();
bool vaultExists();

/** Opens the vault; on success [key] is kept for later saves. */
bool vaultUnlock(const std::string& pin, clx::VaultKey& key, clx::HandheldState& state);
/** Makes a key for a new vault or a new PIN (Argon2, several seconds). */
bool vaultKeyFor(const std::string& pin, clx::VaultKey& key);
bool vaultSave(const clx::VaultKey& key, const clx::HandheldState& state);

uint8_t wrongPins();
void countTry();
void clearTries();
/** Whether to wipe after WIPE_AFTER wrong PINs; kept outside the vault, as it must be known while locked. */
bool wipeOnTries();
void setWipeOnTries(bool on);

[[noreturn]] void wipeEverything();
