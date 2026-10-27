# Security policy

## Reporting a vulnerability

Write to **security@clairix.app**. Please do not open a public issue for
anything that could put people at risk before it is fixed.

Tell us what you found and how to reproduce it: the version (Settings → About),
the edition (full or comms only), the Android version, and the steps or a proof
of concept. A proof of concept is welcome; other people's data is not. Do not
test against relays or phones you do not run or own.

We will acknowledge the report, keep you informed while we fix it, agree a
disclosure date with you, and credit you in the release notes and in
[`docs/SECURITY-FINDINGS.md`](docs/SECURITY-FINDINGS.md) if you want to be
credited.

## What is supported

Only the newest release, currently 3.0.0 Beta 7, and `main`. Fixes are not
backported to earlier betas.

## In scope

Everything in this repository:

- the Android app (`android/`), both editions;
- the end-to-end crypto crate (`comms-crypto/`), including the Tor client;
- the relay (`comms-worker/`), on Cloudflare and self-hosted
  (`comms-worker/selfhost/`);
- the signed relay list (`relays/`) and the asset-signing tools (`tools/signing/`);
- the release and asset workflows (`.github/workflows/`).

What Clairix promises, and what it does not, is set out in the README under
"Who it is built against". A way around something it says it protects is a
vulnerability; something it says it cannot do (a compromised operating system,
a phone seized while unlocked, the carrier knowing where a phone is) is not.

## Audits

Clairix has not had an independent audit. vodozemac, the Olm implementation it
uses, has. Everything found by our own reviews, fixed or still open, is in
[`docs/SECURITY-FINDINGS.md`](docs/SECURITY-FINDINGS.md). Material for
auditors is in [`docs/audit/`](docs/audit/README.md).
