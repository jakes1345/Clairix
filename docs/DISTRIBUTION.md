# Distribution

Where Clairix can be installed from, what each channel needs, and where each
one stands. Written 1 November 2026, F-Droid section updated 5 November 2026; correct this file whenever a channel
changes. It claims no listing that does not exist.

## Where it stands

| Channel | State on 1 November 2026 (the GitHub and F-Droid rows on 5 November) |
|---|---|
| GitHub Releases | **Live.** The only place an APK is published. Every release carries `clairix-<tag>.apk` (Clairix Full), `clairix-comms-<tag>.apk` (Clairix Comms) and the Play bundle (`.github/workflows/release.yml`); from 3.0.0 Beta 9 also `clairix-foss-<tag>.apk`, the foss edition. Every release so far is a beta and is marked as a pre-release. |
| clairix.app/download | **Live.** Links to the Full APK on GitHub Releases with its SHA-256 (`website/src/config/release.ts`). |
| Obtainium | **Works now, needs no listing.** Obtainium reads GitHub Releases directly; /download has the link and the two settings it needs. |
| IzzyOnDroid | **Not listed.** The inclusion request below has not been sent yet. |
| F-Droid (main repository) | **Not submitted.** A `foss` flavour without Google Play services or Firebase builds, CI checks it stays that way, and from 3.0.0 Beta 9 every release publishes it signed and rebuilds it from its tag. The metadata draft, listing text and changelog are ready; what is left is the maintainer's, under "F-Droid" below. |
| Google Play | **Not public.** The Play edition is built with every release (`clairix-play-<tag>.aab`), but `PLAY_LIVE` is `false` in `website/src/config/site.ts` and no store page exists. |

GitHub is the single host of every APK. The README's adversary can compel
GitHub, so a second channel that keeps its own copy (IzzyOnDroid) is worth
having for that reason alone, besides update notifications.

## What every channel reads from this repository

`fastlane/metadata/android/en-US/` holds the listing in the layout F-Droid,
IzzyOnDroid and fastlane read:

- `title.txt`, `short_description.txt` (80 characters at most),
  `full_description.txt` (4000 at most);
- `changelogs/<versionCode>.txt`, 500 characters at most, one per release;
- `images/icon.png` and `images/featureGraphic.png`, copies of
  `brand/assets/play-store-icon-512.png` and `brand/assets/play-feature-graphic.png`;
- `images/phoneScreenshots/1.png` to `8.png`, copies of the Paparazzi renders in
  `android/app/src/test/snapshots/images` (the scan hub, trackers, phone check,
  Wi-Fi, cell towers, Comms setup, the duress phrase and the lock screen). They
  are the app's own screens drawn from test data, not photos of a phone.

**With every release:** add `changelogs/<versionCode>.txt` for the new
`versionCode` in `android/app/build.gradle.kts` (29 is 3.0.0 Beta 9), and copy
the screenshots again when the Paparazzi images are re-recorded. A channel shows
no "what's new" for a version without its changelog file.

The description describes Clairix Full, the APK a channel would list: Full and
Comms share the application id `app.clairix`, so a store can list only one of
them, and the Play edition is not an APK. F-Droid lists the foss edition
instead and takes its description from
`src/fossSuite/fastlane/metadata/android/en-US/full_description.txt` at the
repository root, which overrides the root one for that build alone; the title,
short description, changelogs and images are shared (see "F-Droid" below). A
change to the description goes into both files.

## Obtainium

[Obtainium](https://obtainium.imranr.dev) installs and updates apps straight
from their GitHub releases. Nothing has to be submitted. The link on /download
is `obtainium://add/https://github.com/jakes1345/Clairix`; before adding, the
user turns on **Include prereleases** (every release is a beta) and sets
**Filter links by regex** to `clairix-v[^/]*\.apk$`, which matches
`clairix-v3.0.0-beta.8.apk` and not `clairix-comms-v3.0.0-beta.8.apk`,
`clairix-foss-v<version>.apk` (from Beta 9) or the `.aab`. The repository's other releases (`keys`, `relays`, `ioc`, `workzones`,
`maps-<region>`) carry no APK; Obtainium's "Fallback to older releases", on by
default, skips them. Someone who wants Clairix Comms instead uses
`clairix-comms-v[^/]*\.apk$`, and the foss edition `clairix-foss-v[^/]*\.apk$`.

If release file names change in `release.yml`, change the filter on /download
(`OBTAINIUM_APK_FILTER` in `website/src/pages/download.astro`) and here.

## IzzyOnDroid

IzzyOnDroid is an F-Droid-style repository of developers' own APKs, taken from
their GitHub releases and signed by the developer; its updater checks the
releases on a schedule. Requests are made as an issue in
https://codeberg.org/IzzyOnDroid/repodata/issues (the GitLab repository named
here before is archived and read-only); the inclusion policy is at
https://izzyondroid.org/docs/general/AppInclusionPolicy/. Read it before
sending, including its AI policy, which rejects apps whose code is LLM output:
much of Clairix's code was written with an AI assistant, and
docs/outreach/izzyondroid-request.md says so. What follows is what this
repository can show against the policy, not a promise of acceptance.

What is ready:

- Licence: GPL-3.0-only (`LICENSE`).
- APKs attached to tagged GitHub releases, one name pattern per edition, signed
  with one certificate (`docs/KEYS.md`: SHA-256
  `471c09cd792fff0bf75724bbb8ee2d13cc674e3f51d1e1fde28a2500932a52cc`).
- Listing metadata in `fastlane/metadata/android/en-US/`.
- Releases rebuilt from their tags and compared (`tools/reproducible`,
  `.github/workflows/verify-release.yml`).
- No analytics, advertising or crash-reporting library.

What the request has to raise:

- **Size.** `clairix-v3.0.0-beta.8.apk` is 68.8 MB (68,818,347 bytes): three
  ABIs of the Rust library (encryption and Tor, through Arti) and of WebRTC for
  calls. IzzyOnDroid limits the size per
  app; if 68.8 MB is over the current limit, the request asks for an exception
  and gives those reasons.
- **Google's location library.** `play-services-location` is in every published
  edition (the foss flavour, not published, is the only one without it).
  It is used only when Google Play services is on the phone; without it the app
  uses Android's `LocationManager` (`analysis/LocationClient.kt`,
  `security/SosLocation.kt`). Expect IzzyOnDroid to mark it with an
  anti-feature; that is their call.
- **Pre-releases.** Every release so far is a GitHub pre-release; the request
  asks whether they will take them until 3.0.0 is out.
- **Several APKs per release.** Their updater needs the Full edition's name
  pattern; from 3.0.0 Beta 9 a release carries three APKs.

The request the maintainer sends (as a new issue, with the "request for
inclusion" template if the repository offers one; fill in anything the template
asks that this does not answer):

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
  build with the same package name, clairix-foss-v<version>.apk, the same app
  without Google Play services, meant for F-Droid, and a Play .aab; please
  ignore those three).
Signing certificate SHA-256:
  471c09cd792fff0bf75724bbb8ee2d13cc674e3f51d1e1fde28a2500932a52cc
  (recorded in docs/KEYS.md, signed and published as the "keys" release)
Metadata: fastlane/metadata/android/en-US/ in the repository.
Reproducible: every release is rebuilt from its tag on a separate runner and
  compared entry by entry (tools/reproducible, .github/workflows/verify-release.yml).

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

Maintainer: the repository owner, reachable at support@clairix.app.
```

## F-Droid

F-Droid's main repository builds every app from source on its own servers and
accepts no proprietary dependency. The published editions (Full, Comms, Play)
all declare `com.google.android.gms:play-services-location`, so none of them
can go there.

**What exists now (5 November 2026).** A third edition flavour, `foss`, next to
`full` and `play` in `android/app/build.gradle.kts`, gives two variants:
`fossSuite` (the whole app) and `fossComms` (messaging only). It is the full
edition without Google Play services or Firebase:

- `play-services-location` is a dependency of the `full` and `play` flavours
  only (`fullImplementation`, `playImplementation`). The foss runtime
  classpath holds no `com.google.android.gms` or `com.google.firebase`
  artifact.
- The one piece of code that used Google's fused location client is behind an
  interface, `analysis/FusedLocation.kt`. Its only implementation is in
  `src/gms` (a source directory of `full` and `play`); the foss edition's
  `src/foss/.../EditionLocation.kt` supplies none, so every position (scanner,
  Drive map, navigation, SOS) comes from Android's own `LocationManager`: its
  fused provider where the phone has one, otherwise the satellite and
  Wi-Fi/cell providers, the satellite receiver alone with Satellite-only
  positioning on. That is the same path the other editions already take on a
  phone without Google Play services. Settings says so in this edition.
- Everything else is the full edition's: the direct SOS text (`src/full`),
  the card vault with card emulation (`src/fullSuite`), ClairixCoin and the
  disguised launcher entries.
- `.github/workflows/android.yml` (the "Check the foss edition has no Google
  Play services or Firebase" step) fails the build if a `com.google.android.gms`
  or `com.google.firebase` artifact reaches `fossSuiteReleaseRuntimeClasspath`
  or `fossCommsReleaseRuntimeClasspath`, or if such a class or file is in the
  foss debug APKs (`tools/android/check-no-google-services.sh`, with its own
  tests). The foss unit tests (`src/testFoss`) check that no Play services
  class can be loaded.

**Done for the submission, on the way to 3.0.0 Beta 9:**

- **Version.** `versionCode` 29, `versionName` 3.0.0-beta.9: the first release
  whose tag will carry the foss flavour.
- **The foss APK is published and checked.** `release.yml` builds
  `fossSuiteRelease` in the same Gradle run as the other release builds, so the
  same signing config signs it with the release key, and publishes it as
  `clairix-foss-<tag>.apk`, listed in `SHA256SUMS.txt` (covered by the asset
  key's signature) and in the 16 KB alignment and one-certificate checks.
  `verify-release.yml` rebuilds it from the tag and compares it entry by entry
  whenever a release carries it (none before Beta 9 does).
- **Listing text.** The foss build's description is
  `src/fossSuite/fastlane/metadata/android/en-US/full_description.txt` at the
  repository root. fdroidserver (`update.py`, `insert_localized_app_metadata`,
  read on its master branch on 5 November 2026) and F-Droid's "All About
  Descriptions, Graphics and Screenshots" page say it reads
  `src/<buildFlavor>/fastlane/metadata/android/<locale>/` from the repository
  root, not from the Gradle module (`android/app/src/<flavour>/...` is not
  read), for each combination of the build's `gradle:` entries (`fossSuite`
  here), and that a directory later in sorted order overrides the root
  `fastlane/`. The title, short description, changelogs and images stay
  shared. This has not been run through `fdroid update` yet.
- **Changelog** `changelogs/29.txt`.
- **Brand licence.** `brand/LICENSE.md` proposes CC BY-SA 4.0 for the brand
  files and the store images (`licenses/CC-BY-SA-4.0.txt`); the maintainer
  confirms it.
- **Metadata draft** `docs/fdroid/app.clairix.yml`: `commit: v3.0.0-beta.9`,
  the new version and `CurrentVersion`, `Binaries:` pointing at
  `clairix-foss-v%v.apk`, a placeholder `AllowedAPKSigningKeys:`, a proposed
  NonFreeNet anti-feature with its reasons, and `ioc.json.gz` as a commented
  `scanignore` candidate.

**What a submission still needs**, all of it the maintainer's:

1. **Tag and publish 3.0.0 Beta 9**, then check the release carries
   `clairix-foss-v3.0.0-beta.9.apk` and that `verify-release.yml` matched it.
2. **The release certificate in the draft.** Replace the placeholder
   `AllowedAPKSigningKeys:` with the release certificate's SHA-256 as
   `docs/KEYS.md` records it (64 lower-case hex characters, no colons).
   Without it F-Droid signs the app with its own key: an F-Droid install then
   cannot be updated from GitHub or the other way round, and the app's update
   notice sends a foss install to F-Droid for that reason.
3. **Decide whether the foss APK stays on GitHub Releases.** It is there so
   F-Droid can reproduce it and ship the developer's signature. Anyone can
   install it from GitHub too, and it installs over the Full edition and the
   Full edition over it (same package, same key). Its update notice points to
   F-Droid's page for Clairix, which does not exist until a listing does; the
   release notes say so. Keeping it off GitHub means F-Droid signs with its
   own key (item 2).
4. **Decide how the "oldest safe version" brake treats F-Droid.** Below
   `minSafeVersionCode` in `relays/latest.json` the app stops sending
   messages, SOS alerts excepted, until it is updated (SECURITY.md, "How fixes
   reach people"). F-Droid builds and publishes some time after a tag (often days
   later), and the app sends a foss install to F-Droid for its update. Raising the
   minimum on the day of a release would stop F-Droid users from sending until
   F-Droid catches up. Either raise it only once the fixed version is on
   F-Droid as well (relays/README.md already asks for "everywhere people get
   Clairix"), or change the app so a foss build is not stopped, or is stopped
   later. Beta 9 leaves it at 27. `AutoUpdateMode: Version` in the draft, once
   a build has passed review, shortens the lag.
5. **Confirm the brand licence** (`brand/LICENSE.md`), or replace it.
6. **A build on F-Droid's own tooling.** Run `fdroid lint`, `fdroid scanner`,
   `fdroid build app.clairix` and `fdroid update` with the draft in a local
   fdroiddata checkout (or let the merge request's CI do it). The recipe has
   to install Rust (`comms-crypto/rust-toolchain.toml` pins 1.97.0 and the
   three Android targets), cargo-ndk 4.1.2 and NDK r27c (27.2.12479018); the
   Gradle build refuses any other cargo-ndk or NDK (`tools/reproducible`).
   Whether the draft's `prebuild`/`build` lines get `cargo` onto the Gradle
   task's `PATH` on F-Droid's builder, and whether its build fetching crates
   from crates.io is accepted, has not been tried. `fdroid update` shows
   whether the foss description is picked up.
7. **Prebuilt native code in Maven libraries.** Three FOSS dependencies ship
   compiled `.so` files in their AARs: `io.getstream:stream-webrtc-android`
   (libwebrtc, for calls), `net.zetetic:sqlcipher-android` (SQLCipher) and
   `net.java.dev.jna:jna` (libjnidispatch). F-Droid's reviewers may ask for
   them to be built from source; that is theirs to decide and has not been
   asked.
8. **Anti-features.** The draft proposes NonFreeNet: the project relay runs
   on Cloudflare Workers (its code, `comms-worker/`, is free; anyone can run
   a relay), calls may use Cloudflare's TURN service, and with Share traffic
   data on, the box around a route goes through the relay to HERE and TomTom
   for incidents. UpstreamNonFree (the other editions include Play services)
   is left for the merge request. F-Droid decides.
9. **One build per listing.** `fossSuite` and `fossComms` share the package
   name `app.clairix` and the versionCode, so a listing carries one of them;
   the draft lists `fossSuite`.
10. **Open the merge request** to fdroiddata, raising items 7 and 8 in its
    description, after reading F-Droid's current inclusion policy.

Until a listing exists, Obtainium (and IzzyOnDroid, once it lists Clairix) is
the route for people who use F-Droid-style clients; both take the full edition
from GitHub Releases, with Google's location library.
