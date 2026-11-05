# ImHex patterns for Clairix formats

Copy these into ImHex's patterns folder (File > Open Pattern, or put them in
`~/.local/share/imhex/patterns/`) and apply with the file open.

| File | Format | Source of truth |
|---|---|---|
| `clairix_envelope.hexpat` | sealed envelope as stored on the relay (v2/v3) | `comms-crypto/src/lib.rs` |
| `clairix_envelope_inner.hexpat` | decrypted inner message; set `INNER_FORMAT` at the top | same |
| `clairix_sealed_store.hexpat` | sealed timeline / location / trips store | `Store.kt`, `StoreCipher.kt` |

Evidence exports and the timeline inside them are JSON/text, not binary, so
there is nothing for a pattern to parse. Check a report with the SHA-256 and
ECDSA signature printed in its integrity block (`openssl dgst`, see `ReportSigning.kt`).
The hash-chained timeline (`TimelineChain.kt`) is JSON inside the sealed store; its head and the report key's attestation are carried in the evidence report.

Not yet run in ImHex: ImHex is not installed on this machine. Offsets come from the
documented tables in the Rust source.
