# Clairix — Android

A real BLE tracker detector, not a port. The Node tool in this repo is a
terminal application and does not run on Android, so this is a native Kotlin
app that reuses the parts that actually matter: the tracker signature database,
the MAC-rotation fingerprinting, and the geo-correlation rule.

## The rule that makes it worth installing

Most tracker-detector apps flag anything they see repeatedly. That describes a
tracker in your bumper and it equally describes a neighbour's Tile through a
wall, so those apps mostly generate false alarms and get uninstalled.

This one only reports **FOLLOWING** when the same device has been heard from two
places at least **300 m apart** (adjustable in Settings). Until then it says
PERSISTENT and tells you which piece of evidence is missing — either you have
not travelled far enough yet, or there is no position fix to judge by.

## Editions and variants

Clairix is built in two editions from one codebase, along two flavour
dimensions (`edition` first, then `tier`, so a variant reads `<edition><Tier>`):

| Variant | Edition | What it is | Where it goes |
|---|---|---|---|
| `fullSuite` | Full | The whole app, including the Card Vault with NFC card emulation and ClairixCoin | `clairix-<tag>.apk` on GitHub Releases and clairix.app |
| `fullComms` | Full | Clairix Comms: encrypted messages, calls, groups and SOS, with the duress phrase, fake call and Wipe Clairix; no scanner | `clairix-comms-<tag>.apk` on GitHub Releases |
| `playSuite` | Play | The whole app **without** card emulation, the Card Vault or ClairixCoin | `clairix-play-<tag>.aab`, uploaded to Google Play |

There is no `playComms`: the comms-only build is never published on Play.

How the Play edition leaves those features out:

- **Card emulation.** `CardEmulationService` (the `HostApduService`), its
  `res/xml/hce_service.xml` and its description string live in `src/full`, and
  its manifest entry in `src/fullSuite/AndroidManifest.xml`, so none of them is
  in a Play build. `src/play` supplies a `CardEmulationBridge` with no service
  behind it. The release workflow fails if the Play manifest ever declares a
  HostApduService.
- **Card Vault and ClairixCoin.** `BuildConfig.CARD_VAULT` and
  `BuildConfig.CLAIRIX_COIN` are `false` in the Play edition. The NFC tab shows
  no vault, save or emulate controls; COMMS shows no balance and no Pay button;
  and the relay's `/v1/coin/*` endpoints are never called.

Everything else (scanner, tools, map, navigation, COMMS) is identical.

## Getting an APK without a build environment

Every push builds the debug variants in CI.

1. Open the **Actions** tab on GitHub
2. Pick the most recent **Android APK** run
3. Download the `clairix-debug-apk` artifact
4. Unzip it and install the `.apk` on your phone (`app-full-suite-debug.apk` is
   the whole app)

You will need to allow installation from unknown sources. These are debug
builds, signed with the standard debug key.

## Building locally instead

Requires JDK 17, the Android SDK, NDK `27.2.12479018` and Rust with `cargo-ndk`
`4.1.2` (for the COMMS crypto crate in `../comms-crypto`); the build stops on
any other NDK or cargo-ndk version.

```bash
cd android
gradle assembleFullSuiteDebug       # app/build/outputs/apk/fullSuite/debug/app-full-suite-debug.apk
gradle assemblePlaySuiteDebug       # the Play edition
gradle assembleDebug                # every debug variant
gradle test                         # unit tests for every variant
```

`./gradlew` works too; CI installs Gradle itself.

## Settings that matter for privacy

- **Phone check → Certificates → "Refuse the relay if its key changes"** pins
  the relay's TLS key on every handshake; off by default because a Workers
  relay's key rotates with its certificate.
- **Comms → "Hide names and previews in notifications"**, on by default once a
  passcode is set.
- **Comms → "Relay-only calls"**: audio only through the relay's TURN server,
  so no contact learns this phone's address from a call. On by default
  whenever the relay offers a TURN server; a choice made in Comms settings
  is kept.
- **Comms → "Cover traffic"**: sealed envelopes nobody reads, to random
  contacts at random intervals, so real messages are not the only traffic.
  Off by default.
- **Settings → App lock → wipe after N wrong passcodes** (1, 3, 5, 10 or never)
  and the duress passcode, whose wipe leaves an empty decoy behind the lock
  screen that the duress passcode then opens.
- **Comms menu → Wipe Clairix**, or **Settings → Wipe and delete → Wipe
  Clairix**, wipes on demand, in both editions; `security/Wiper.kt` lists what
  it takes with it, in order, and `security/RelayFarewell.kt` how the relay is
  told when it cannot be reached at once.
- **Settings → "Keep history for"**: 7, 30 or 90 days, or forever (30 by
  default). The location log, the timeline, vehicle trips, the unlock ledger
  and the map's tile and traffic caches older than that are deleted at each
  start, every quarter hour while scanning and once a day otherwise; the
  cell-tower baseline keeps which towers are usual, not when
  (`security/HistoryRetention.kt`).

## Reproducing a build

Two builds of one commit should come out the same, so a published APK can
be checked against the source. For every change, CI builds the full
edition twice, on two machines, and fails unless the two builds agree
(`reproducible-build` and `compare-builds` in
`.github/workflows/android.yml`): the debug APK entry for entry, since each
machine signs it with a debug key it makes for itself, and the release APK,
built unsigned, byte for byte. A release build refuses to run until
`asset-signing.pub` is committed, so until then the debug build alone is
compared. What makes the builds agree:

- The Rust compiler, `1.97.0`, named in `comms-crypto/rust-toolchain.toml`
  and in both workflows; bump both together. The absolute paths of the
  crate and of Cargo's registry are mapped to fixed names when the library
  is compiled (`RUSTFLAGS`, set by `cargoNdkBuild`), and CI checks the
  unstripped library for any path from the build machine.
- Gradle `8.14.3`, through the wrapper on a developer's machine and in CI
  alike: `gradle/wrapper/gradle-wrapper.properties` names the version and
  the distribution's SHA-256, and CI checks the committed wrapper jar
  against Gradle's published checksums before running it. The Android
  Gradle plugin, Kotlin and every library are at an exact version in the
  Gradle files; every GitHub action at a commit.
- No Google dependency-metadata block in the APK or bundle: it is encrypted
  for Google at signing time, so it can be neither reproduced nor read.
- Native libraries stripped. The Android Gradle plugin is pointed at the NDK
  cargo-ndk builds with; without it, it packaged every library unstripped,
  debug info and symbol tables included, and the first two-machine
  comparison showed that the symbol tables differ from one build to the next
  (LLVM numbers the functions it outlines for size in a varying order) while
  the code does not.

To check a published release, build the same tag with no keystore in the
environment, which leaves the APK unsigned, and compare the two entry by
entry with `tools/reproducible/compare-apk.sh`: signing adds the signature
files and the signing block and changes nothing else.
`tools/reproducible/README.md` has the steps.

The NDK (`27.2.12479018`) and `cargo-ndk` (`4.1.2`) are pinned from 3.0.0
Beta 6; the build checks both. Releases up to Beta 5 used the runner's latest
NDK, so a rebuild of one may differ in the native library. No published
release has yet been rebuilt and matched; until one has, the check is a
check, not a promise.

## Release signing

Release builds are signed by Gradle when these environment variables are set,
and come out unsigned otherwise:

| Variable | What it is |
|---|---|
| `ANDROID_KEYSTORE_FILE` | Path to the keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | The key's alias in the keystore |
| `ANDROID_KEY_PASSWORD` | The key's password (defaults to the keystore password) |

The release workflow reads them from these **repository secrets**
(Settings → Secrets and variables → Actions):

| Secret | What to put in it |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore, base64-encoded: `base64 -w0 release.keystore` (on a Mac: `base64 -i release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | The key's alias |
| `ANDROID_KEY_PASSWORD` | The key's password |

Keep the keystore and its passwords out of the repository, and keep a backup
somewhere safe: a phone only accepts an update signed with the same key as the
installed app, so losing it means every user has to uninstall and reinstall.
The same key signs the Play bundle, which makes it the Play upload key.

Without `ANDROID_KEYSTORE_BASE64` the workflow stops before it builds: a
release signed with any other key cannot be installed over an earlier one, and
a lost or mis-rotated secret must not publish one that looks tampered. To try
the build itself anyway, start the workflow by hand with `allow_throwaway_key`:
it signs with a throwaway key it makes on the spot, keeps the files as a
workflow artifact (`throwaway-signed-<tag>`, for seven days) and publishes no
GitHub release. Those APKs will not install over an earlier release, and the
bundle must not be uploaded to Play.

To build signed release files locally:

```bash
export ANDROID_KEYSTORE_FILE=/path/outside/the/repo/release.keystore
export ANDROID_KEYSTORE_PASSWORD=...  ANDROID_KEY_ALIAS=...  ANDROID_KEY_PASSWORD=...
cd android
gradle assembleFullSuiteRelease assembleFullCommsRelease bundlePlaySuiteRelease
# app/build/outputs/apk/fullSuite/release/app-full-suite-release.apk
# app/build/outputs/apk/fullComms/release/app-full-comms-release.apk
# app/build/outputs/bundle/playSuiteRelease/app-play-suite-release.aab
```

## Cutting a release

Before the first release, make the asset signing key (`tools/signing/README.md`):
`python3 tools/signing/keygen.py` writes `android/asset-signing.pub` and
`android/asset-signing.pem` (commit both) and prints the secret for the
`ASSET_SIGNING_KEY` repository secret. The maps, work zones and the release
checksums are signed with it, and the app refuses downloads it cannot verify;
a release build fails without the public key file.

1. Set `versionName` and raise `versionCode` in `app/build.gradle.kts`
   (3.0.0-beta.1 is versionCode 21). The workflow refuses a tag that does not
   match `versionName`.
2. Commit, then start `.github/workflows/release.yml` in one of three ways:
   push a tag `v3.0.0-beta.1`; push a branch `release/v3.0.0-beta.1` (the tag is
   created at its head); or Actions → Release → Run workflow with the tag.
3. The workflow tests the crypto crate, builds and signs `fullSuite` and
   `fullComms` APKs and the `playSuite` bundle, checks the Play manifest has no
   card emulation, checks that every 64-bit native library in them is built
   for 16 KB pages (`tools/android/check-16k-alignment.sh`: each LOAD segment
   0x4000-aligned, `zipalign -c -P 16 -v 4` on the APKs), and publishes a
   GitHub Release with the three files, `SHA256SUMS.txt` and
   `SHA256SUMS.txt.sig` (the checksums signed by the asset signing key). A tag
   ending in `-alpha.N`, `-beta.N` or `-rc.N` is marked as a pre-release. A run
   started by hand must start from `main`, a `release/v*` branch or a `v*` tag.
   A failed run, or a rebuild-and-compare check that does not match, opens an
   issue titled "Release workflow failing" (or adds the run to the open one).
4. Check the certificate digest the "Collect and verify artifacts" step prints:
   it must be the same as the previous release's, or phones will refuse the update.
5. Upload `clairix-play-<tag>.aab` in the Play Console (a beta goes to a testing
   track first).

## Permissions, and why each one is needed

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_SCAN`) | The scan itself |
| Precise location | Android requires it for BLE scanning and for reading your own modem's serving cell (the IMSI-catcher checks), and this app genuinely uses the fix — it is what proves a device travelled with you |
| Notifications | The one alert that matters, when something crosses into confirmed-following; and the fake call's incoming-call card, which opens its screen over the lock screen |
| Foreground service | Scanning has to continue while the screen is off, or it never accumulates enough evidence |
| Wi-Fi state | Reads the networks in range for the Wi-Fi checks, and asks Android for a fresh Wi-Fi scan: while the scanner runs, every two minutes with the screen on or the phone moving and every ten minutes once it has settled with the screen off; with the scanner off, once a minute from the Wi-Fi tab while it is open (with the scanner on, the tab shows the scanner's results and asks for none); and once each time you tap "Scan for setup hotspots" under Hidden cameras. Android caps how often an app may ask and hands out its last results otherwise |
| NFC | The passive HF tag sweep on the NFC tab |
| Background location | Optional, requested only from Settings → "Resume scanning after reboot". Android only lets a location service start at boot with "Allow all the time"; without it, scanning simply waits until you open the app |

Declining notifications costs you the alert and the fake call (its page says so, and
schedules nothing until notifications and full-screen calls are allowed), not the
detector — scanning still runs.

Detection data stays on the phone, sealed on disk under a key in the phone's
secure hardware. The app's network connections are: the Clairix relay (Comms,
and anonymous live traffic while navigating), OpenStreetMap's tile servers and
its Nominatim address search (the Map tab), GitHub Releases (signed offline map
graphs, work-zone files and the plate-reader list for each map) and Cloudflare
TURN (a call that cannot connect directly). What each one sees, and what the app can and cannot protect against,
is in the root [`README.md`](../README.md) and at clairix.app/security.

## Screen on versus screen off

Android suspends an *unfiltered* BLE scan once the screen goes off, and any filter
list is also a whitelist — so a scan built to survive a pocket can only ever find
devices someone already catalogued. That is the wrong trade for the case this app
exists for, which is the unbranded box wired into a wheel well.

So it runs both. Screen on, the scan is unfiltered and anything advertising at all
can be discovered, including hardware with no known signature. Screen off, it falls
back to filters covering the known tracker manufacturers and service UUIDs, which is
what keeps it running at all. A device first seen while the screen was on stays
tracked either way.

The practical consequence: leave the screen on for a minute or two when you first
start a sweep, so unknown hardware gets a chance to be discovered.

## What it detects

AirTag, Apple Find My items, Tile, Samsung SmartTag and SmartTag2, Chipolo,
Pebblebee, Orbit/KeySmart, Nut, Eddystone beacons, and standalone GPS trackers
that advertise a Bluetooth configuration interface.

AirPods, iBeacons, Apple Handoff and Microsoft devices are filtered out, because
otherwise every coffee shop sets off an alert.

## What it cannot detect

- A tracker with no Bluetooth radio — a hardwired GPS/LTE box only transmits on
  cellular uplink. Finding those needs an SDR; see the Node tool in this repo.
- Anything currently asleep. Physical search still finds things RF never will.
- Carrier-side location. Nothing near you transmits, so there is nothing to hear.
