# Security policy

## Reporting a vulnerability

Write to **security@clairix.app**. Please do not open a public issue for
anything that could put people at risk before it is fixed.

Tell us what you found and how to reproduce it: the version (Settings → About),
the edition (full or comms only), the Android version, and the steps or a proof
of concept. A proof of concept is welcome; other people's data is not. Do not
test against relays or phones you do not run or own.

### Sending it encrypted

There is no published key for security@clairix.app yet. To send a report
encrypted today, write first without the details and include your own OpenPGP
or age public key, or your Clairix number and relay. Within three business days
we answer encrypted to your key with a key of ours for your reply, or write to
you in Clairix, whose messages are end-to-end encrypted; send the details that
way. A published key, and a signed `/.well-known/security.txt` naming it, are
still to come.

### What we do, and by when

- **Acknowledge** your report within 3 business days.
- **Triage** it within 10 business days: whether we can reproduce it, how
  serious we think it is and what we plan to do, with any questions.
- **Fix it, or agree a plan with you,** within 90 days of the report. A plan
  says what will change and by when, if a fix takes longer.
- **Keep you informed** while we work on it, and tell you before the fix ships.

Business days are Monday to Friday, leaving out public holidays.

### Disclosure

We coordinate disclosure with you. We agree a date together, normally the day
the fixed version is published; after 90 days from the report you may publish
whether or not a fix is out, unless we have agreed a later date. When the fix
ships, we describe the problem in the release notes and in
[`docs/SECURITY-FINDINGS.md`](docs/SECURITY-FINDINGS.md). If a flaw is being
used against people, we may tell users sooner, and we tell you first.

### Safe harbour

We will not take or support legal action against anyone who, in good faith,
reports a vulnerability to us first and keeps to this policy: tests only phones,
accounts and relays they own or run (a relay of your own is easy to set up;
see `comms-worker/`), does not access, keep or share anyone else's data, does
not disrupt the project's relay or anyone's use of it, and gives us the agreed
time before publishing. If you are unsure whether something is covered, ask us
first.

### Credit

With your permission we credit you, by name or handle, in the release notes and
in `docs/SECURITY-FINDINGS.md`. There is no bug bounty.

### How fixes reach people

Once a day the app reads a signed note of the newest version and of the oldest
one still safe to use (`latest.json`, published with the relay list; see
[`relays/README.md`](relays/README.md)). Settings → About then offers the
update; below the oldest safe version the Scan and Comms screens say why the
version is unsafe, and the app stops sending messages, SOS alerts excepted,
until it is updated. Only versions after 3.0.0 Beta 8 read the note. For older
versions the project's relay ends what it kept only for them: from
**1 January 2027** it refuses the old kind of token (apps before Beta 6) and
envelopes in format 2 (apps before Beta 7) with a message to update Clairix.
Supported versions use blind tokens and send format 2 only to contacts on those
older versions; the date is two months after Beta 7 and Beta 8 were published.

## What is supported

Only the newest release, currently 3.0.0 Beta 9, and `main`. Fixes are not
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
