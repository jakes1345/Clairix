# Threat-model gaps from the h4cker / OWASP checklists

h4cker's README names no Android, messaging or API section (only `cybersecurity-domains/`:
crypto, application security, cloud). The checks below come from the standard OWASP
MASVS/MASTG and API Top 10 lists it points at, compared against this repo on 2026-11-04.
Each item says what was checked. Candidates that turned out to be covered are at the bottom.

Status: **open** = a real gap in code or docs; **verify** = needs a test, not yet shown to be a bug.

| # | Check | Status | Evidence | Suggested action |
|---|---|---|---|---|
| 1 | Harvest-now-decrypt-later (post-quantum). Olm and X25519 sealing are not PQ-safe, and the adversary model includes a state actor who records traffic. | open (docs) | No mention in `docs/*.md` or `SECURITY.md`; sealing uses X25519 + ChaCha20-Poly1305 (`comms-crypto/src/lib.rs`) | State it in the threat model as an accepted risk; track a hybrid (e.g. ML-KEM) sealing format as format 4. |
| 2 | Invite page `/i` had no CSP or framing protection. | fixed in #65 | Was: only content-type, cache-control, referrer-policy, nosniff in `comms-worker/src/index.ts` | Done: CSP by hash, `frame-ancestors 'none'`, `X-Frame-Options: DENY`. |
| 3 | Deep-link input: `https://clairix.app/i` and `clairix://invite?c=…` are BROWSABLE. Is the payload parsed defensively and confirmed by the user before acting? | verify | Manifest lines ~95-108 | Fuzz the invite parser; confirm no action happens without a tap. |
| 4 | Relay-chosen federation targets (SSRF). | checked, no bug | `relayOrigin()` requires https, port 443, a non-IP name, no `.local/.internal`; `forward()` and `fetchRelayKey` both use `redirect: "manual"`. A name resolving to a private IP is not reachable from a Cloudflare Worker. | Keep as is; retest if the relay is ever self-hosted on a network with private hosts (`selfhost/`). |
| 5 | Replay / reorder of relay requests. | checked, covered | Requests carry a ±5-minute timestamp, a single-use nonce, method, path and body hash (`auth.ts`); `relay.test.mjs` covers replay. | None. |
| 6 | Dependency pinning of Rust crates for audits: `cargo-audit` runs, but RustSec "unmaintained" advisories and `cargo vet` are not in the threat model. | open (process) | CI has `cargo-audit`, `npm-audit`, `dependency-review` | Add `cargo deny` / `cargo vet` or document why not. |
| 7 | Root / hooking detection (Frida, debugger). | open, low | One doc mention, two code hits; the report key's hardware attestation now ships in the evidence report | Out of scope per "no compromised OS"; say so explicitly in the threat model. |
| 8 | Memory hygiene for secrets on the Kotlin side (zeroisation). Rust uses `Zeroizing`; JVM strings holding secrets cannot be wiped. | open (docs) | 2 `Zeroizing` hits in code, none in docs | Document the limit; prefer `ByteArray` over `String` for key material where practical. |
| 9 | SIM-swap / phone-number takeover as a way to reach the account. | verify | Numbers are relay identifiers; recovery flow in `recovery.rs` | Confirm registration needs the enrollment secret, not the carrier. |
| 10 | Notification content on lock screen and in system logs. | verify | Redaction is implemented per `SECURITY-FINDINGS.md` | Add a test that asserts the redacted text when locked. |

## Checked and already covered (not gaps)

- Tapjacking / overlay attacks: `security/WindowShield.kt` (setHideOverlayWindows on API 31+, FLAG_SECURE).
- Cloud backup and device transfer: every data domain excluded.
- Cleartext traffic and CA trust: system-only CA, no cleartext, optional relay-key pinning.
- PendingIntent mutability: 10 `FLAG_IMMUTABLE` hits.
- Exported components: the manifest lines checked show `exported="true"` only on the launcher aliases (MainActivity's own line was not re-read); every other component seen is `exported="false"`.
- Clipboard: sensitive-marked copies, cleared on wipe.
- Reproducible builds: built twice in CI and compared.
