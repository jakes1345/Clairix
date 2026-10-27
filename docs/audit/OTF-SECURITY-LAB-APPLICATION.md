# Open Technology Fund Security Lab: draft application

Draft answers for the Security Lab application at
https://apply.opentech.fund/security-lab/. The Security Lab pays established
security firms to audit internet-freedom tools. It also takes projects OTF does
not otherwise fund, and its findings are published after a disclosure period.

**Only the applicant can supply these, so fill them in before sending:**
- your name and the country you are in;
- whether you apply as an individual or for an organisation;
- a contact email if not security@clairix.app;
- how many people use Clairix today, if you can say.

Everything else below is drawn from the repository and accurate as of 3.0.0
Beta 7.

---

## Project name

Clairix

## Is this audit for your own project?

Yes. The applicant maintains Clairix and can coordinate the engagement
directly with the auditing firm.

## Project website and source code

- Source: https://github.com/jakes1345/Clairix
- Licence: GNU General Public License, version 3 (`LICENSE`), with an
  additional permission under section 7 to combine it with Google Play
  services. All work produced under this engagement will be openly licensed.

## Short description

Clairix is an Android app for people who may be watched by a state. It
combines three things:
- end-to-end encrypted messaging and calls through relays anyone can run;
- an offline Bluetooth mesh for when the network is down;
- tools that look for trackers, fake cell towers and spyware.

Messages use the Olm double ratchet (vodozemac) inside sealed envelopes. The
relay cannot open them, and cannot tell who sent them. Every part, the relay
included, is free software.

## How does it advance internet freedom?

It is built for people whose network and service providers can be compelled:
journalists, activists, human rights defenders, and people at risk from an
abusive partner or employer.

- **A relay operator or hosting company learns as little as possible.**
  - Delivery tokens are blind (RFC 9497, as in Privacy Pass), so a relay
    cannot tell whose message a token paid for.
  - Envelopes name no sender.
  - The key envelopes are sealed to rotates weekly. Someone who logs a relay
    and later seizes a phone still cannot learn who sent what.
- **Blocking one relay does not cut people off.**
  - Anyone can run a relay with one script (behind HTTPS and an onion
    address), and relays reach each other.
  - The app is published with a signed list of relays.
  - A phone whose relay is blocked can move to another, and its contacts
    follow it automatically.
- **Network observers learn less.** Tor is built into the app, and relays
  with onion addresses are reached without leaving Tor.
- **A seized phone gives little away.**
  - While locked, it cannot read its own conversations: messages that arrive
    wait sealed until it is unlocked.
  - A duress passcode wipes it at once, deleting keys first.
- **It keeps working without a network.** Texts and SOS can travel phone to
  phone over Bluetooth.

## What should be audited?

In priority order (full detail in `docs/audit/SCOPE.md`):

1. **The cryptography** (Rust, about 2,700 lines):
   - sealed envelopes with rotating prekeys and a deniable sender claim;
   - padding;
   - the use of vodozemac (Olm);
   - blind tokens (RFC 9497 VOPRF);
   - mesh routing tags.
2. **The relay** (TypeScript on Cloudflare Workers or self-hosted workerd,
   about 3,700 lines for messaging):
   - request authentication;
   - registration;
   - federation between relays;
   - limits, and what an outsider or the operator can learn.
3. **The app's messaging protocol and storage** (Kotlin):
   - trust in contact keys, and how the sender of a message is resolved;
   - moving between relays;
   - protection while the phone is locked;
   - encryption at rest;
   - the Tor integration.
4. **If time allows:**
   - the app lock and wipe;
   - the offline mesh;
   - mixing in the relay;
   - the release and signing pipeline.

The detection tools (tracker, IMSI-catcher and spyware checks) are out of
scope.

## What kind of audit?

A source-code review of the cryptography and protocols (tiers 1–3), with
penetration testing of a relay we stand up for the auditors. That relay can be
self-hosted or on Cloudflare, with a second relay for federation.

We also ask for a re-test of the fixes, so the published report says which
findings are resolved.

## Previous audits

- None of Clairix itself; this would be its first independent audit.
- vodozemac, the Olm library Clairix uses, was audited by Least Authority in
  2022 ([announcement](https://matrix.org/blog/2022/05/16/independent-public-audit-of-vodozemac-a-native-rust-reference-implementation-of-matrix-end-to-end-encryption/)).
- Clairix has had two internal security reviews. Every finding, fixed or open,
  is published in `docs/SECURITY-FINDINGS.md`. The open ones include:
  - delivery tokens not tied to a recipient (mailbox flooding);
  - coin farming;
  - the custom invite link scheme;
  - release signing on the CI runner.

## Project maturity

- **Release:** public beta, 3.0.0 Beta 7. Releases are signed and published
  on GitHub, and the CI check that two builds match is described below.
- **Testing on every change:**
  - 49 crypto tests;
  - 148 relay tests;
  - 337 app unit tests;
  - instrumented tests on Android 12, 13, 14 and 15 emulators against two
    real relays. These cover messaging, calls, groups, disappearing messages,
    an eight-minute soak, Tor, moving relays, and the lock screen.
- **Reproducible builds:** CI builds every change twice on two machines and
  fails unless the builds match.

## Materials for the auditors

`docs/audit/` contains:
- a threat model;
- an architecture description;
- a prioritised scope with file paths and questions;
- build and test instructions, including running two relays locally.

The maintainers are available throughout the engagement and can turn fixes
around quickly.

## Timing

We can start whenever the Security Lab and the firm are ready. We will
publish the final report in the repository, unedited, after the agreed
disclosure period.
