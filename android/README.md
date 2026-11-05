# Clairix for Android

The Clairix app, written in Kotlin, with the end-to-end crypto in Rust
(`../comms-crypto`). This page covers the editions, how to build, sign and
release them, the permissions the app asks for, and how the Bluetooth tracker
detector works. What the app does for the person holding the phone, and what
it cannot protect against, is in the root [`README.md`](../README.md).

The tracker signatures are in `detect/Signatures.kt`. A device that changes
its Bluetooth address is followed across the change by its advertisement
content (`analysis/Identity.kt`) and by the rhythm of its radio
(`detect/CadenceFingerprinter.kt`). The rule below is in `analysis/Geo.kt` and
`analysis/Persistence.kt`.

## The rule behind FOLLOWING

Most tracker-detector apps flag anything they see repeatedly. That describes a
tracker in your bumper and it equally describes a neighbour's Tile through a
wall, so those apps mostly generate false alarms and get uninstalled.

This one only reports **FOLLOWING** when the same device has been heard from two
places at least **300 m apart** (adjustable in Settings). Until then it says
PERSISTENT and tells you which piece of evidence is missing: either you have
not travelled far enough yet, or there is no position fix to judge by.

## Editions and variants

Clairix is built in three editions from one codebase, along two flavour
dimensions (`edition` first, then `tier`, so a variant reads `<edition><Tier>`):

| Variant | Edition | What it is | Where it goes |
|---|---|---|---|
| `fullSuite` | Full | The whole app, including the Card Vault with NFC card emulation and ClairixCoin | `clairix-<tag>.apk` on GitHub Releases and clairix.app |
| `fullComms` | Full | Clairix Comms: encrypted messages, calls, groups and SOS, with the duress phrase, fake call and Wipe Clairix; no scanner | `clairix-comms-<tag>.apk` on GitHub Releases |
| `playSuite` | Play | The whole app **without** card emulation, the Card Vault or ClairixCoin | `clairix-play-<tag>.aab`, for the Play Console (the listing is not public yet) |
| `fossSuite` | Foss | The full edition's whole app **without** Google Play services or Firebase | `clairix-foss-<tag>.apk` on GitHub Releases from 3.0.0 Beta 9, so F-Droid can check its build against it; meant for F-Droid, not submitted (`docs/DISTRIBUTION.md`, "F-Droid") |
| `fossComms` | Foss | Clairix Comms, the same without Google Play services | Nowhere yet |

There is no `playComms`: the comms-only build is never published on Play.

How the Play edition leaves those features out:

- **Card emulation.** `CardEmulationService` (the `HostApduService`), the
  `CardEmulationBridge` that talks to it, its `res/xml/hce_service.xml`, its
  description string and its manifest entry all live in `src/fullSuite`, so
  none of them is in a Play build (or in the comms-only build). `src/play`
  supplies a `CardEmulationBridge` with no service behind it. The release
  workflow fails if the Play manifest ever declares a HostApduService.
- **Card Vault and ClairixCoin.** `BuildConfig.CARD_VAULT` and
  `BuildConfig.CLAIRIX_COIN` are `false` in the Play edition. The NFC tab shows
  no vault, save or emulate controls; COMMS shows no balance and no Pay button;
  and the relay's `/v1/coin/*` endpoints are never called. In the Full edition
  the balance and the Pay button appear unless the relay's `GET /` says
  `"coin": false` (its operator set `COIN=off`) or a coin request answers 404.

Everything else (scanner, tools, map, navigation, COMMS) is identical.

How the foss edition leaves Google Play services out: `play-services-location`
is a dependency of the `full` and `play` flavours only, and the code that uses
it (`EditionLocation`, Google's implementation of `analysis/FusedLocation.kt`)
is in `src/gms`, a source directory of those two. The foss edition's own
`src/foss` supplies no fused client, so `LocationClient` and `SosLocation` use
Android's `LocationManager` alone. Otherwise it is the full edition, built from
the same `src/full` and `src/fullSuite` files (`sourceSets` in
`app/build.gradle.kts`). `.github/workflows/android.yml` fails if a
`com.google.android.gms` or `com.google.firebase` artifact, class or file
reaches it.

How the comms-only build leaves the toolkit out: its manifest (`src/main`)
declares none of the scanner's components or permissions, which
`src/suite/AndroidManifest.xml` adds. Drive mode (the RTL-SDR driver, radio
scanner and its recordings, ADS-B, OBD-II), the NFC tab, the card vault and the
card-emulation guard are in `src/suite`, not `src/main`. The shared code reaches
them only through `TierShell` (`src/main/java/app/clairix/TierShell.kt`), which
`src/suite/java/app/clairix/Tier.kt` implements with them and
`src/comms/java/app/clairix/Tier.kt` without them, so `fullComms` is compiled
without that code at all. The release workflow fails if R8's mapping for the
Comms APK lists any of those classes. Their unit tests are in `src/testSuite`.

## Getting an APK without a build environment

Every push that changes the app, the crypto crate or the relay list builds
the debug variants in CI (the path filters are in
`.github/workflows/android.yml`).

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
./gradlew assembleFullSuiteDebug    # app/build/outputs/apk/fullSuite/debug/app-full-suite-debug.apk
./gradlew assemblePlaySuiteDebug    # the Play edition
./gradlew assembleDebug             # every debug variant
./gradlew test                      # unit tests for every variant
```

CI runs the same wrapper, so a local build and CI both use Gradle 8.14.3.

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
  default). The location log, the timeline, vehicle trips, the unlock ledger,
  the radio scanner's recordings and the map's tile and traffic caches older
  than that are deleted at each start, every hour while scanning, and once a
  day on a system alarm even with Clairix closed. The cell-tower baseline
  keeps which towers and areas are usual and the last day each was seen, the
  exact time for two days (`security/HistoryRetention.kt`).

## Reproducing a build

Two builds of one commit should come out the same, so a published APK can
be checked against the source. For every change, CI builds the full
edition twice, on two machines, and fails unless the two builds agree
(`reproducible-build` and `compare-builds` in
`.github/workflows/android.yml`): the debug APK entry for entry, since each
machine signs it with a debug key it makes for itself, and the release APK,
built unsigned, byte for byte. (A release build refuses to run without
`asset-signing.pub`; that file has been committed since 0ff171b5, see
`docs/KEYS.md`, so both are compared.) What makes the builds agree:

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
NDK, so a rebuild of one may differ in the native library. 3.0.0 Beta 7 is
the first published release rebuilt from its tag and matched, and
`.github/workflows/verify-release.yml` now rebuilds and compares every release
as it is published (`tools/reproducible/README.md`).

## Release signing

Release builds are signed by Gradle when these environment variables are set,
and come out unsigned otherwise:

| Variable | What it is |
|---|---|
| `ANDROID_KEYSTORE_FILE` | Path to the keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | The key's alias in the keystore |
| `ANDROID_KEY_PASSWORD` | The key's password (defaults to the keystore password) |

The release workflow's `release` job reads them from these secrets of the
**`release` environment** (Settings → Environments → release), which only that
job can read, and only from main, a `release/v*` branch or a `v*` tag
([`docs/PROCESS.md`](../docs/PROCESS.md), Signing keys):

| Secret | What to put in it |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore, base64-encoded: `base64 -w0 release.keystore` (on a Mac: `base64 -i release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | The key's alias |
| `ANDROID_KEY_PASSWORD` | The key's password |

Keep the keystore and its passwords out of the repository, and keep a backup
somewhere safe: a phone only accepts an update signed with the same key as the
installed app, so losing it means every user has to uninstall and reinstall.
The same key signs the Play bundle, so it is the key Play checks each upload
against (the upload key). That does not make it the key Play signs the APKs it
delivers with (the app-signing key): Play App Signing fixes that key once, at
the first upload, and unless the release key is enrolled there (step 5a under
"Cutting a release"), Google generates its own, and Play installs and these
APKs can never install over each other.

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
./gradlew assembleFullSuiteRelease assembleFullCommsRelease bundlePlaySuiteRelease
# app/build/outputs/apk/fullSuite/release/app-full-suite-release.apk
# app/build/outputs/apk/fullComms/release/app-full-comms-release.apk
# app/build/outputs/bundle/playSuiteRelease/app-play-suite-release.aab
```

## Cutting a release

The asset signing key exists: its public half is committed as
`android/asset-signing.pub` and `android/asset-signing.pem`, its secret half is
the `ASSET_SIGNING_KEY` secret, and `docs/KEYS.md` records it. The jobs that
sign name the `release` and `assets` environments, and `docs/PROCESS.md` says
how to move the secret into them. The maps, the work zones, the relay and
stalkerware lists and the release checksums are signed with it, and the app
refuses downloads it cannot verify; a release build fails without the public
key file. Replacing it takes `tools/signing/keygen.py --force` and a new app
release (`tools/signing/README.md`), and `docs/KEYS.md` changes with it.

1. Set `versionName` and raise `versionCode` in `app/build.gradle.kts`
   (3.0.0-beta.1 is versionCode 21). The workflow refuses a tag that does not
   match `versionName`. In the same commit, change the supported version in
   `SECURITY.md` ("currently 3.0.0 Beta 9" for `3.0.0-beta.9`), update the
   version note in `relays/latest.json` (the Relay list workflow fails while
   the two disagree; see [relays/README.md](../relays/README.md)), and bring
   `docs/OPEN-ISSUES.md` up to date.
2. Commit, then start `.github/workflows/release.yml` in one of three ways:
   push a tag `v3.0.0-beta.1`; push a branch `release/v3.0.0-beta.1` (the tag is
   created at its head); or Actions → Release → Run workflow with the tag.
3. The workflow first runs the Android APK workflow on the commit (unit tests,
   screenshots, lint, detekt, the build on two machines), requires an
   Emulator smoke test run that passed on the same tree (it waits for one
   still going, and fails if there is none: start one by hand on the same ref
   and re-run), and makes CycloneDX bills of materials of the app's libraries
   and the crypto crate. Then it tests the crypto crate, builds and signs the
   `fullSuite`, `fullComms` and `fossSuite` APKs and the `playSuite` bundle,
   checks the Play manifest has no card emulation, checks that every 64-bit native library in
   them is built for 16 KB pages (`tools/android/check-16k-alignment.sh`: each
   LOAD segment 0x4000-aligned, `zipalign -c -P 16 -v 4` on the APKs), and
   publishes a GitHub Release with the four files, the two bills of
   materials, `SHA256SUMS.txt` and `SHA256SUMS.txt.sig` (the checksums signed
   by the asset signing key). A tag
   ending in `-alpha.N`, `-beta.N` or `-rc.N` is marked as a pre-release. A run
   started by hand must start from `main`, a `release/v*` branch or a `v*` tag.
   A failed run, or a rebuild-and-compare check that does not match, opens an
   issue titled "Release workflow failing" (or adds the run to the open one).
4. Check the certificate digest the "Collect and verify artifacts" step prints:
   it must be the same as the previous release's, or phones will refuse the update.
   The "Check the bundle and the APKs share one signing certificate" step
   (`tools/android/check-bundle-signer.sh`) fails the release if the Play
   bundle is signed by any other certificate than the APKs, and prints the
   digest as `Release certificate SHA-256: …`; it is the `apksigner` digest in
   `docs/KEYS.md`.
5. Upload `clairix-play-<tag>.aab` in the Play Console (a beta goes to a testing
   track first). The declarations Play reviews are prepared in
   [docs/play/](../docs/play/README.md); before sending a release for review:
   - Permissions declaration for `QUERY_ALL_PACKAGES`: paste the core-use text
     from `docs/play/query-all-packages.md`.
   - Location permissions for `ACCESS_BACKGROUND_LOCATION`: the feature
     statement from `docs/play/background-location.md`, and the video recorded
     from its script (unlisted YouTube link).
   - Foreground service permissions: one entry and one video per type
     (`specialUse`, `microphone`, `location`, `connectedDevice`) from
     `docs/play/foreground-services.md`.
   - Full-screen intent: the calling use case from
     `docs/play/full-screen-intent.md`.
   - Data safety: the answers in `docs/play/data-safety.md`, after its
     "Mismatches" list is empty or each item has a recorded reason.
   - Store listing: a privacy policy URL of https://clairix.app/privacy; the
     content rating questionnaire answered for an app for adults (Terms,
     section 2: 18 or older); target audience 18 and over.
   - Re-read each file against the manifests of the build being uploaded: a
     permission or service type added since the last review needs its own entry.
   - The first time only, before any of this, step 5a.

   5a. **The first Play upload: enrol the release key as the app-signing key.**
   Play App Signing is mandatory for bundles, and the app-signing key is chosen
   once, when the first bundle is uploaded; it cannot be changed to the release
   key afterwards. In the Play Console, under the app's signing setup, choose
   "Use an existing key" and the option to export and upload a key from a Java
   keystore. The console offers Google's `pepk` tool and an encryption public
   key; on a trusted machine holding the release keystore, run

   ```bash
   java -jar pepk.jar --keystore=release.keystore --alias="$ANDROID_KEY_ALIAS" \
     --output=clairix-app-signing-key.zip --include-cert --rsa-aes-encryption \
     --encryption-key-path=encryption_public_key.pem
   ```

   and upload the zip it writes (it holds the key encrypted to Google; delete
   it afterwards). Do not register a separate upload key: the release workflow
   signs the bundle with the release key, and the step in point 4 checks that.
   Then upload the first bundle.

   **Verify it before the listing goes live.** On the Play Console's app
   signing page, the app-signing certificate's SHA-256 must equal the one in
   `docs/KEYS.md` and in `website/public/.well-known/assetlinks.json`. Then
   install the app from the internal testing track on a phone and check the
   APK Play delivered:

   ```bash
   adb shell pm path app.clairix          # package:/data/app/…/base.apk
   adb pull /data/app/…/base.apk play-base.apk
   apksigner verify --print-certs play-base.apk | grep 'SHA-256 digest'
   ```

   The digest must equal the `Release certificate SHA-256` the release
   workflow printed for that tag. When it does, record the check (date, tag,
   digest) under "The APK signing certificate" in `docs/KEYS.md` and set
   `PLAY_SAME_SIGNING_KEY = true` in `website/src/config/site.ts`: only then
   does the download page say that one edition installs over the other. If it
   does not (Google generated the key), the editions cannot replace each other
   without uninstalling, which deletes the Comms identity: leave
   `PLAY_SAME_SIGNING_KEY` false (the download page then says so), add the
   app-signing certificate's SHA-256 to `assetlinks.json` before the listing
   goes live (or `https://clairix.app/i` links stop opening in Play installs),
   and record both digests in `docs/KEYS.md`.

## Permissions, and why each one is needed

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_SCAN`) | The scan itself |
| Precise location | Android requires it for BLE scanning and for reading your own modem's serving cell (the IMSI-catcher checks). The app also uses the fix itself: it is what proves a device travelled with you |
| Notifications | The one alert that matters, when something crosses into confirmed-following; and the fake call's incoming-call card, which opens its screen over the lock screen |
| Foreground service | Scanning has to continue while the screen is off, or it never accumulates enough evidence |
| Wi-Fi state | Reads the networks in range for the Wi-Fi checks, and asks Android for a fresh Wi-Fi scan: while the scanner runs, every two minutes with the screen on or the phone moving and every ten minutes once it has settled with the screen off; with the scanner off, once a minute from the Wi-Fi tab while it is open (with the scanner on, the tab shows the scanner's results and asks for none); and once each time you tap "Scan for setup hotspots" under Hidden cameras. Android caps how often an app may ask and hands out its last results otherwise |
| NFC | The passive HF tag sweep on the NFC tab |
| Background location | Optional, requested only from Settings → "Resume after reboot". Android only lets a location service start at boot with "Allow all the time"; without it, scanning waits until you open the app |
| Nearby devices (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`) | Connecting to an OBD-II adapter, asking a tracker for its identifier and naming paired accessories; advertising only for the detector self-test and the offline mesh while it is on |
| Camera | The hidden-camera sweep and scanning a contact's pairing code. No frame is saved unless the owner keeps a photo of a finding |
| Microphone | Used during a call you place or answer, while you hold the record button for a voice note, and while the Ultrasound tool is open, when it listens for 17.5-21.5 kHz beacons. Only spectrum numbers are kept; no audio is stored or sent |
| Installed apps (`QUERY_ALL_PACKAGES`) | The stalkerware check matches every installed app against its signed list, on the phone; Android 11+ hides most apps without it |
| Usage access (`PACKAGE_USAGE_STATS`, special access) | Fills the unlock ledger in from before its device admin was enabled, and shows the spyware check which apps ran in the background without ever being opened |
| Device admin | The unlock ledger's lock-screen successes and failures, and "Schedule and lock" on the fake call page; nothing else |
| Basic phone state (`READ_BASIC_PHONE_STATE`) | The data network type for the Cell tab; granted at install on Android 13+ |
| Exact alarms | The fake call rings at the minute the owner picked, even in Doze |
| Screen-recording detection, Advanced Protection status | Granted at install: whether Clairix's own screen is being recorded (Android 15+), and whether Advanced Protection is on (Android 16+) |

Declining notifications costs you the alert and the fake call (its page says so, and
schedules nothing until notifications and full-screen calls are allowed), not the
detector: scanning still runs.

Detection data stays on the phone, sealed on disk under a key in the phone's
secure hardware. The app's network connections are: the Clairix relay (Comms,
and anonymous live traffic while navigating), OpenStreetMap's tile servers and
its Nominatim address search (the Map tab), GitHub Releases (signed offline map
graphs, work-zone files and the plate-reader list for each map) and Cloudflare
TURN (a call that cannot connect directly). What each one sees, and what the
app can and cannot protect against, is in the root [`README.md`](../README.md)
and at clairix.app/security.

## Screen on versus screen off

Android suspends an *unfiltered* BLE scan once the screen goes off, and any filter
list is also an allow list, so a scan built to survive a pocket can only ever find
devices someone already catalogued. That is the wrong trade for the case this app
exists for, which is the unbranded box wired into a wheel well.

So it runs both. Screen on, the scan is unfiltered and anything advertising at all
can be discovered, including hardware with no known signature. Screen off, it falls
back to filters covering the known tracker manufacturers, Find My and iBeacon frames
and service UUIDs, which is what keeps it running at all, plus an address filter for
each device already under watch that those would miss (up to sixteen: confirmed
following, keeping a persistence pattern, or a GPS module recognised by its name),
re-registered at most once a minute as that set changes. A device under watch keeps
a filter of its own with the screen off for as long as it keeps its address.

The practical consequence: leave the screen on for a minute or two when you first
start a sweep, so unknown hardware gets a chance to be discovered.

## What it detects

AirTag, Apple Find My items, Tile, Samsung SmartTag and SmartTag2, Chipolo,
Pebblebee, Google Find Hub tags, trackers announcing themselves under DULT,
Orbit/KeySmart, Nut, Invoxia, Eddystone beacons, tags in Apple's iBeacon format,
and standalone GPS trackers that advertise a Bluetooth configuration interface.

AirPods, Apple Handoff and Microsoft devices are filtered out, because otherwise
every coffee shop sets off an alert. iBeacons are not: a shop's beacon stays on its
wall and the follow check leaves it alone, while an anti-lost tag in the same format
never changes its address and is exactly what the follow check catches.

## What it cannot detect

- A tracker with no Bluetooth radio. A hardwired GPS/LTE box only transmits on
  the cellular uplink, and Clairix does not listen for that. Drive mode's SDR
  support (an RTL-SDR dongle on USB, `SdrUsbDriver.kt`) decodes aircraft
  transponders (`AdsbDecoder.kt`) and plays the voice channels you set; it does
  not look for a modem's uplink. A physical search is the way to find one.
- Anything currently asleep. Physical search still finds things RF never will.
- Carrier-side location. Nothing near you transmits, so there is nothing to hear.
