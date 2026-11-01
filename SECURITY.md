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

Only the newest release, currently 3.0.0 Beta 8, and `main`. Fixes are not
backported to earlier betas.

## In scope

The code in this repository, tree by tree:

- `android/`: the app, in every edition and variant (Full, Clairix Comms and
  the Play edition);
- `comms-crypto/`: the end-to-end crypto crate, including the Tor client;
- `comms-worker/`: the relay, on Cloudflare, self-hosted (`selfhost/`) and as
  a container image (`container/`), and its blind-token module (`voprf/`);
- `relays/`: the signed relay list;
- `tools/`: asset signing (`signing/`), the stalkerware-list converter
  (`ioc/`), the road-graph and camera-list builder (`graph-builder/`), the
  work-zone aggregator (`workzones/`), the relay-list checker (`relays/`), the
  rebuild-and-compare checks (`reproducible/`) and the 16 KB page check
  (`android/`);
- `.github/`: the workflows that test, build, sign and publish, and their
  scripts;
- `website/`: clairix.app, including the invite page and the App Links file
  it serves (`public/.well-known/assetlinks.json`).

What Clairix promises, and what it does not, is set out in the README under
"Who it is built against". A way around something it says it protects is a
vulnerability; something it says it cannot do (a compromised operating system,
a phone seized while unlocked, the carrier knowing where a phone is) is not.

## Signing keys

[`docs/KEYS.md`](docs/KEYS.md) records the key that signs the app's downloads
and each release's checksums, and the certificate that signs the APKs, with
the commands that check a file or an APK against them.

## Audits

Clairix has not had an independent audit. vodozemac, the Olm implementation it
uses, has. Everything found by our own reviews, fixed or still open, is in
[`docs/SECURITY-FINDINGS.md`](docs/SECURITY-FINDINGS.md), and what is still
open is in one table in [`docs/OPEN-ISSUES.md`](docs/OPEN-ISSUES.md). Material
for auditors is in [`docs/audit/`](docs/audit/README.md).
