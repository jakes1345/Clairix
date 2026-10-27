# Audit pack

Material for an independent security audit of Clairix: what it is meant to
protect, how it is built, what to look at first, and how to build and test it.

| Document | What it covers |
|---|---|
| [THREAT-MODEL.md](THREAT-MODEL.md) | Who Clairix defends against, what it protects, what it assumes, and what it does not claim |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The components and how a message, a token, a lookup and a move travel between them |
| [SCOPE.md](SCOPE.md) | What to audit, in order, with file paths and sizes; the questions we most want answered; what is out of scope |
| [BUILD-AND-TEST.md](BUILD-AND-TEST.md) | Building every part, running every test suite, standing up two relays locally, and checking a release against the source |
| [OTF-SECURITY-LAB-APPLICATION.md](OTF-SECURITY-LAB-APPLICATION.md) | A draft application to the Open Technology Fund's Security Lab |

Also read:

- [`../SECURITY-FINDINGS.md`](../SECURITY-FINDINGS.md): every finding from our
  own reviews, what was done about it, and what is still open. Please do not
  report those again unless the fix is wrong.
- [`../../README.md`](../../README.md), "Who it is built against": the claims
  as users read them.
- [`../../comms-worker/README.md`](../../comms-worker/README.md): what the
  relay stores, its limits, federation and its API.
- [`../RELAY-HOSTING-PLAN.md`](../RELAY-HOSTING-PLAN.md): relays, the relay
  list, Tor and moving between relays.

## The code at a glance

| Part | Language | Where | Size |
|---|---|---|---|
| End-to-end crypto: identity, sessions, sealed envelopes, blind tokens, mesh tags | Rust (vodozemac, UniFFI) | `comms-crypto/src/lib.rs` | 2,725 lines |
| Tor client and local SOCKS proxy | Rust (Arti) | `comms-crypto/src/tor.rs` | 455 lines |
| Relay: accounts, mailboxes, tokens, federation | TypeScript (Cloudflare Workers, Durable Objects) | `comms-worker/src/` | 5,930 lines (3,740 in the messaging files: `index.ts`, `mailbox.ts`, `federation.ts`, `comms-logic.ts`, `tokens.ts`, `auth.ts`) |
| App messaging: protocol, storage, transport | Kotlin | `android/app/src/main/java/app/clairix/comms/` | 13,521 lines |
| App on-device protection: lock, wipe, duress | Kotlin | `android/app/src/main/java/app/clairix/security/` | 3,188 lines |

## Who to talk to

security@clairix.app. The maintainers can walk an auditor through the code,
answer questions during the engagement, and turn findings around quickly; the
repository's CI builds and tests every change on four Android versions against
two real relays, so a fix can be shown working the same day.

## Licence

GPLv3 (see [`../../LICENSE`](../../LICENSE)). Audit reports will be published
in this directory, unedited, once the findings are fixed or the disclosure
period agreed with the auditor has passed.
