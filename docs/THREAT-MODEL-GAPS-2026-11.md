# Threat-model gaps from the h4cker / OWASP checklists

h4cker's README names no Android, messaging or API section (only `cybersecurity-domains/`:
crypto, application security, cloud). The checks below come from the standard OWASP
MASVS/MASTG and API Top 10 lists it points at, compared against this repo on 2026-11-04.
Each item says what was checked. Candidates that turned out to be covered are at the bottom.

Status: **open** = a real gap in code or docs; **verify** = needs a test, not yet shown to be a bug.

| # | Check | Status | Evidence | Suggested action |
|---|---|---|---|---|
| 1 | Harvest-now-decrypt-later (post-quantum). Olm and X25519 sealing are not PQ-safe, and the adversary model includes a state actor who records traffic. | open (docs done 2026-11-07; build pending) | Now stated in README "What it cannot do"; design in `docs/PQ-SEALING-DESIGN.md`. Sealing uses X25519 + ChaCha20-Poly1305 (`comms-crypto/src/lib.rs`) | Decide the schedule and the ML-KEM crate, then build format 4 (hybrid seal). Calls (DTLS) and Tor stay classical and are listed in the design as separate work. |
| 2 | Invite page `/i` had no CSP or framing protection. | fixed in #65 | Was: only content-type, cache-control, referrer-policy, nosniff in `comms-worker/src/index.ts` | Done: CSP by hash, `frame-ancestors 'none'`, `X-Frame-Options: DENY`. |
| 3 | Deep-link input: `https://clairix.app/i` and `clairix://invite?c=…` are BROWSABLE. Is the payload parsed defensively and confirmed by the user before acting? | verify (partly read 2026-11-07) | `MainActivity.handleOpenThreadIntent` matches only `https://clairix.app/i` or `clairix://invite`, hands the text to `Registry.requestInvite`, clears the intent, and its comment says nothing is registered or added until the owner confirms. The confirm screen and the invite parser itself were not read or tested. | Fuzz the invite parser; add a test that no contact is added or registration made before the confirm tap. |
| 4 | Relay-chosen federation targets (SSRF). | checked, no bug | `relayOrigin()` requires https, port 443, a non-IP name, no `.local/.internal`; `forward()` and `fetchRelayKey` both use `redirect: "manual"`. A name resolving to a private IP is not reachable from a Cloudflare Worker. | Keep as is; retest if the relay is ever self-hosted on a network with private hosts (`selfhost/`). |
| 5 | Replay / reorder of relay requests. | checked, covered | Requests carry a ±5-minute timestamp, a single-use nonce, method, path and body hash (`auth.ts`); `relay.test.mjs` covers replay. | None. |
| 6 | Dependency pinning of Rust crates for audits: `cargo-audit` runs, but RustSec "unmaintained" advisories and `cargo vet` are not in the threat model. | open (process) | CI has `cargo-audit`, `npm-audit`, `dependency-review` | Add `cargo deny` / `cargo vet` or document why not. |
| 7 | Root / hooking detection (Frida, debugger). | open, low | One doc mention, two code hits; the report key's hardware attestation now ships in the evidence report | Out of scope per "no compromised OS"; say so explicitly in the threat model. |
| 8 | Memory hygiene for secrets on the Kotlin side (zeroisation). Rust uses `Zeroizing`; JVM strings holding secrets cannot be wiped. | open (docs) | 2 `Zeroizing` hits in code, none in docs | Document the limit; prefer `ByteArray` over `String` for key material where practical. |
| 9 | SIM-swap / phone-number takeover as a way to reach the account. | checked, no bug (read 2026-11-07) | `RelayClient.register` sends an invite or the enrollment secret with the identity keys, dated within five minutes; `comms-worker/src` has no SMS, Twilio or OTP code; the recovery file is keyed by a 24-character random code through Argon2id. A SIM swap gives an attacker neither. | None. A test that registration is refused without a secret or invite would pin it. |
| 10 | Notification content on lock screen and in system logs. | mostly covered (read 2026-11-07) | `NotificationPresentationTest` asserts tracker, cell, security and link notifications name nothing while locked or with previews hidden; message notifications are built by `unlockToRead` with `VISIBILITY_PRIVATE`. The incoming-call notification is `VISIBILITY_PUBLIC` (`CommsNotifications.incomingCall`), because it must be answerable. | Check whether the caller's name shows on a locked screen with previews hidden, and test it; there is a `lockedCall` variant that may already do this. |

## Checked and already covered (not gaps)

- Tapjacking / overlay attacks: `security/WindowShield.kt` (setHideOverlayWindows on API 31+, FLAG_SECURE).
- Cloud backup and device transfer: every data domain excluded.
- Cleartext traffic and CA trust: system-only CA, no cleartext, optional relay-key pinning.
- PendingIntent mutability: 10 `FLAG_IMMUTABLE` hits.
- Exported components: the manifest lines checked show `exported="true"` only on the launcher aliases (MainActivity's own line was not re-read); every other component seen is `exported="false"`.
- Clipboard: sensitive-marked copies, cleared on wipe.
- Reproducible builds: built twice in CI and compared.
