# IzzyOnDroid inclusion request

## Read this first: the AI policy

IzzyOnDroid's inclusion policy
(https://izzyondroid.org/docs/general/AppInclusionPolicy/, "AI policy") says
vibe-coded apps will be rejected, that the code itself should be free of LLM
output (LLM-written docs are allowed), and that any lack of transparency they
discover can move a project to rejected.

Clairix's history (development branch, 1 November 2026): 220 of 330 commits
involve Claude; 208 are authored as "Claude" and 206 carry a `Co-Authored-By:
Claude` line. Recount before sending. Much of the code was written with an AI
coding assistant. By their policy, the
request is likely to be rejected.

Two honest options:

1. **Do not send it** for now, and rely on GitHub Releases and Obtainium.
2. **Send it with the disclosure below** and accept their decision. Do not
   leave the disclosure out, reword it to sound smaller, or rewrite history.
   They will see the commit log either way.

## Other things to settle before sending

- **The route.** Requests are believed to go as an issue at
  https://codeberg.org/IzzyOnDroid/repodata/issues (Codeberg account needed);
  confirm on the "New app inclusions" page linked from https://izzyondroid.org/docs/
  before sending.
  `docs/DISTRIBUTION.md` still names `gitlab.com/IzzyOnDroid/repo`, which is
  archived; correct that file.
- **Size.** The Full APK is 68.8 MB. The policy's rule of thumb is about 30 MB
  per app. The request asks for an exception and gives the reason.
- **Tags.** The policy wants tags that match `versionName`/`versionCode`. The
  current build is `versionName` `3.0.0-beta.9`, `versionCode` 29, tag
  `v3.0.0-beta.9`. Check the release exists and carries
  `clairix-v3.0.0-beta.9.apk` before sending.
- **Template.** If the repository offers an inclusion template, use it and
  fill in anything it asks that the text below does not answer.

## Text to paste

The body below is the request in `docs/DISTRIBUTION.md` with the AI
disclosure added, the "Reproducible" line reworded as our own rebuild check,
and the maintainer line filled from placeholders.

```text
Title: Request for inclusion: Clairix (app.clairix)

App: Clairix, a security app for stock Android phones: finds hidden cameras,
Bluetooth trackers, spyware, fake Wi-Fi and fake cell towers, and has
end-to-end encrypted messages and calls through relays anyone can run.

Source: https://github.com/jakes1345/Clairix
Licence: GPL-3.0-only
Package: app.clairix
APK: GitHub Releases, https://github.com/jakes1345/Clairix/releases
  Name pattern for the edition to list: clairix-v<version>.apk
  (each release also carries clairix-comms-v<version>.apk, a messaging-only
  build with the same package name, and a Play .aab; please ignore both).
Signing certificate SHA-256:
  471c09cd792fff0bf75724bbb8ee2d13cc674e3f51d1e1fde28a2500932a52cc
  (recorded in docs/KEYS.md, signed and published as the "keys" release)
Metadata: fastlane/metadata/android/en-US/ in the repository.
Rebuild check: our CI rebuilds each release from its tag on a separate runner
  and compares the APKs entry by entry, signatures aside (tools/reproducible,
  .github/workflows/verify-release.yml); v3.0.0-beta.8 passed. This is our own
  check, not independent verification.

Disclosure about AI-written code, so you can judge it against your AI policy:
  a large part of this app's code was written with an AI coding assistant
  (Claude) and reviewed by me. The git history shows it openly: 220 of the
  330 commits involve Claude (208 authored as "Claude", 206 with a
  Co-Authored-By: Claude line). I would rather you know this from me than find it.
  If that rules the app out, I understand.

Things you will want to know:
- Size: the current APK is 68.8 MB, because it carries native libraries for
  arm64-v8a, armeabi-v7a and x86_64: the Rust library (encryption, and Tor
  through Arti) and WebRTC for calls.
  If that is over your limit, we ask for an exception for that reason.
- It includes com.google.android.gms:play-services-location. It is used only
  where Google Play services is installed; elsewhere the app falls back to
  Android's LocationManager. No other proprietary library beyond the Play
  services parts it pulls in, and no analytics, ads or crash reporting.
- All releases so far are GitHub pre-releases (3.0.0 betas). Can you take them
  until 3.0.0 is released?
- The app talks to a relay for messaging (open source, in comms-worker/); the
  project relay runs on Cloudflare Workers, and anyone can run their own.

Maintainer: <your name>, the repository owner, reachable at <your email>
  (or support@clairix.app).
```
