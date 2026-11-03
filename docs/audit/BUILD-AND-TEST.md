# Building and testing

Everything below runs on Linux. The same commands run in CI on every change
(`.github/workflows/`). The counts are from the tree on 3 November 2026
(`versionName` 3.0.0-beta.8 in `android/app/build.gradle.kts`, with the
commits after it), each one taken with the command next to it; "Counting
again" at the end lists those commands in one place.

## Tools

- **JDK 17**, and the **Android SDK** with the API 36 platform and NDK `27.2.12479018`
  (`sdkmanager "ndk;27.2.12479018" "platforms;android-36"`).
- **Rust** `1.97.0`: rustup installs it from `comms-crypto/rust-toolchain.toml`,
  with the three Android targets.
- **cargo-ndk** `4.1.2`: `cargo install cargo-ndk --version 4.1.2 --locked`.
  The Gradle build refuses any other version, so builds stay reproducible.
- **Node.js 22** and npm, for the relay.

## The crypto crate

```sh
cd comms-crypto
cargo test --release                                  # 59 tests pass, 1 ignored (the Tor one)
cargo test --release --lib -- --ignored tor_          # that 1 test, over the real Tor network (needs internet)
```

## The relay

```sh
cd comms-worker
npm ci
npm run typecheck
npm test                                              # 212 tests in 10 files, against the Worker's own code
(cd voprf && cargo test --locked --release)           # 2 tests of the VOPRF the relay runs, compiled to src/voprf.wasm
```

`npm test` runs the real Worker modules (Durable Objects included) in Node
through a small harness in `test/harness/`; nothing is mocked except the
platform bindings.

## The app

```sh
cd android
./gradlew assembleFullSuiteDebug                      # app/build/outputs/apk/fullSuite/debug/
./gradlew testFullSuiteDebugUnitTest                  # 1,340 JVM tests in 151 classes: 1,315 run, the 25 in RelayRoundTripTest need relays (below)
```

The build compiles `comms-crypto` for every Android ABI through cargo-ndk.
The fullSuite unit tests are `src/test` (143 test files, 1,262 tests) and
`src/testSuite` (8 files, 78 tests: the scanner tier's own code, among them the
SDR and OBD decoders). The foss edition has one more file of its own:

```sh
./gradlew testFossSuiteDebugUnitTest --tests 'app.clairix.analysis.FossEditionTest'   # 3 tests, src/testFoss
```

```sh
./gradlew testFullSuiteDebugUnitTest --tests 'app.clairix.screenshots.*' verifyPaparazziFullSuiteDebug
./gradlew lintFullSuiteDebug detekt                   # every warning an error, past the baselines
```

A plain test run draws the screenshot tests' screens and compares nothing;
`verifyPaparazziFullSuiteDebug` compares them with the PNGs in
`app/src/test/snapshots/images/` and leaves any difference in
`app/build/paparazzi/failures/`. `recordPaparazziFullSuiteDebug` writes new
PNGs; CI records them on its own runner when the Android APK workflow is run
by hand with "record_screenshots", since rendering depends on the JDK and its
fonts. Lint and detekt fail on anything not in `app/lint-baseline.xml` and
`app/detekt-baseline.xml` (`updateLintBaselineFullSuiteDebug`,
`detektBaseline`). The same steps run in `.github/workflows/android.yml`
([`../PROCESS.md`](../PROCESS.md)).

- **Editions** (flavour dimension `edition`): `full` (sideloaded: everything),
  `play` (the Play Store edition, without the card vault, ClairixCoin or the
  disguised launcher entries) and `foss` (the full edition without Google Play
  services or Firebase, for F-Droid; not published). **Tiers** (dimension
  `tier`): `suite` (the whole app) and `comms` (messaging only).
- **Variants built:** `fullSuite`, `fullComms`, `playSuite`, `fossSuite` and
  `fossComms`; `playComms` is switched off. `release.yml` builds `fullSuite`,
  `fullComms` and the `playSuite` bundle; nothing publishes the foss variants.
  Every variant compiles against and targets API 36 (`compileSdk`,
  `targetSdk`); the oldest Android it installs on is 12 (`minSdk` 31). More in
  [`../../android/README.md`](../../android/README.md).

## Two relays on your machine

This is how CI runs them, and how to test federation and moves between
relays. Relay A runs under `wrangler dev`; relay B is the self-hosted build
under plain `workerd`. Both run on loopback, where relays admit each other
over plain HTTP; a deployed relay never does.

```sh
cd comms-worker
npm ci
npx wrangler dev --ip 127.0.0.1 --port 8787 --persist-to /tmp/relay-a \
  --var ENROLL_SECRET:e2e-enrollment-secret-0123456789 --var MIX_MAX_MS:2000 \
  --var DELIVERY_TOKENS_PER_HOUR:20000 &
node selfhost/build.mjs --out /tmp/relay-b-build --state /tmp/relay-b \
  --listen 127.0.0.1:8788 --cron-listen 127.0.0.1:8790 --allow-loopback
ENROLL_SECRET=e2e-enrollment-secret-0123456789 MIX_MAX_MS=2000 DELIVERY_TOKENS_PER_HOUR=20000 \
  node_modules/.bin/workerd serve --experimental /tmp/relay-b-build/relay.capnp &
```

### The app's relay client against them

These tests run on the JVM. They use the app's own `RelayClient` and the
crypto crate, against real relays. Without the variables below they are
skipped, which is how every CI run takes them: no workflow sets
`CLAIRIX_RELAY_URL`, so these 25 run only on a machine where someone starts
the two relays.

```sh
cd android
CLAIRIX_RELAY_URL=http://127.0.0.1:8787 CLAIRIX_RELAY_B_URL=http://127.0.0.1:8788 \
CLAIRIX_ENROLL_SECRET=e2e-enrollment-secret-0123456789 \
  ./gradlew testFullSuiteDebugUnitTest --tests 'app.clairix.comms.RelayRoundTripTest'
```

They cover:
- registration (an invite registers exactly one phone), sessions, resync after
  a lost session, duplicates and receipts;
- blind tokens, their key, and that a claimed one-time key is never offered
  again;
- the sealing prekey, envelope sizes, and that a send carries no identity;
- contacts' passes against a stranger filling the mailbox;
- the socket (a second socket, pings, heartbeats) and the relay's clock;
- a contact on another relay, and an identity moving to the other relay.

### The app on an emulator against them

The instrumentation suites drive the real app. They run in CI on Android 12,
13, 14, 15 and 16 (API 31, 33, 34, 35 and 36, Google APIs images), and once
more on API 35's AOSP image, a phone without Google Play services
(`.github/workflows/emulator.yml`). There are 16 test classes with 52 tests:
15 in `src/androidTest` (whose other two files are helpers) and
`CardVaultLockTest` in `src/androidTestFullSuite`. The smoke step runs the whole package with no
arguments; the suites that need relays, Tor, a lock screen or refused
permissions skip themselves there and run in their own steps, with the
arguments shown.

```sh
adb reverse tcp:8787 tcp:8787 && adb reverse tcp:8788 tcp:8788
adb shell am instrument -w -r -e class app.clairix.comms.EndToEndTest \
  -e relayA http://127.0.0.1:8787 -e relayB http://127.0.0.1:8788 \
  -e secret e2e-enrollment-secret-0123456789 \
  app.clairix.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | What it does |
|---|---|
| `SmokeTest` (1) | One walk through every screen, through UI Automator, as a person would, with a screenshot at each stop |
| `EndToEndTest` (12) | Covers: <ul><li>registration and blind tokens;</li><li>texts with receipts, a voice note, a photo in chunks, a group;</li><li>disappearing messages;</li><li>a call that rings, including on a locked phone, and what arrives while locked;</li><li>a contact on the other relay;</li><li>contacts holding the phone's pass, and the pass replaced when a contact is deleted;</li><li>an SOS sent while the relay is down going once it is back;</li><li>unpairing.</li></ul> |
| `SoakTest` (1, `-e soakMinutes 8`) | An eight-minute conversation with a contact on each relay. Photos, the vault sealing and opening, the socket dropped; nothing lost, nothing twice |
| `RelayMoveTest` (4) | The app moves and both contacts follow; mail to the old number is collected; a contact moves; the app moves back |
| `TorDeviceTest` (4, `-e tor true`) | Requests leave through Tor; a listed relay answers through Tor; a relay is reached at its onion address; nothing goes out directly when Tor is down |
| `VaultLockScreenTest` (2, `-e lockScreen true`) | The vault across a lock screen being set and removed |
| `VaultDeviceTest` (3), `CommsStoreUpgradeTest` (4), `DisappearingMessagesTest` (6), `BlockedListMoveTest` (1) | The vault keys and locked inbox, database upgrades from versions 6, 10, 11 and 12, timer deletion, and the blocked list across a move, on a device |
| `AppLockDeviceTest` (3) | What the app lock shows accessibility services and the notification shade, and what its settings file gives away |
| `FakeCallDeviceTest` (1) | A scheduled fake call rings aloud with another app in front |
| `LensGlintAnalyzerDeviceTest` (5) | The camera sweep's glint analyzer fed synthetic camera frames (the emulator has no camera) |
| `CardVaultLockTest` (2, `-e lockScreen true`, fullSuite only) | A synthetic ISO-DEP card saved through the card vault under a set lock screen, and the armed card disarming when the app locks |
| `DuressFlowDeviceTest` (2, `-e duress prepare` / `decoy`) | The duress passcode typed on the lock screen from outside the app (`.github/scripts/duress-ui.py` between the two runs), and the decoy left after the wipe |
| `PermissionsDeniedDeviceTest` (1, `-e permissions denied`) | First run and every Scan category with each runtime permission refused, revoked by the workflow before the run |

## Hardware: SDR, scanner, ADS-B, OBD

The Drive tab's radio and vehicle features need a USB SDR dongle and a Bluetooth
OBD-II adapter, which the emulator cannot attach. Only their offline decode logic
is covered by unit tests (`AdsbDecoderTest`, `FmDemodulatorTest`, `ObdParserTest`,
`UsbDeviceFilterTest`); no run against real hardware is recorded in this
repository. See [HARDWARE-RUNS.md](HARDWARE-RUNS.md) for exactly what is and is
not validated, and the device run to add.

## Checking a release against the source

CI builds every change twice, on two machines, and fails unless the builds
match (`tools/reproducible/compare-builds.sh`). Every published release is
also rebuilt from its tag and compared with its APKs
(`.github/workflows/verify-release.yml`): Beta 7 was rebuilt and matched by
hand, Beta 8 by that workflow as it was published. To check a published APK
yourself (Beta 7 here; any release tag works the same way):

```sh
git checkout v3.0.0-beta.7
(cd android && ./gradlew assembleFullSuiteRelease)
tools/reproducible/compare-apk.sh \
  android/app/build/outputs/apk/fullSuite/release/app-full-suite-release-unsigned.apk \
  clairix-v3.0.0-beta.7.apk
```

The script ignores only what signing adds. Everything else must match
[`../../tools/reproducible/README.md`](../../tools/reproducible/README.md).

## Counting again

Every number on this page, from the repository root:

| Count | Command |
|---|---|
| Rust tests | `(cd comms-crypto && cargo test --release)`: the `test result` line |
| Relay tests | `(cd comms-worker && npm test)`: `# tests`; `ls comms-worker/test/*.test.mjs \| wc -l` for the files |
| VOPRF tests | `(cd comms-worker/voprf && cargo test --locked --release)` |
| JVM tests | after `testFullSuiteDebugUnitTest`, the `tests=` and `skipped=` attributes summed over `android/app/build/test-results/testFullSuiteDebugUnitTest/*.xml` (151 files); per source set, `grep -rhE '^\s*@Test(\s\|$\|\()' android/app/src/test \| wc -l` (1,262), the same over `src/testSuite` (78) and `src/testFoss` (3); test files, `find android/app/src/test -name '*Test.kt' \| wc -l` (143) |
| Device tests | `grep -c '@Test'` over each file in `android/app/src/androidTest*` |
| Python tool tests | `python3 -m pytest -q --collect-only <dir>`: `.github/scripts` 61, `tools/android` 22, `tools/graph-builder` 51, `tools/ioc` 26, `tools/relays` 115, `tools/signing` 6, `tools/workzones` 122. CI runs them in the Gate, Android APK, Stalkerware list, Maps and Relay list workflows |
| Kotlin files | `find android/app/src/<set> -name '*.kt' \| wc -l`: app code 272 (`main` 246, `suite` 17, `fullSuite` 2, `play` 2, and 1 each in `full`, `foss`, `gms`, `comms`, `debug`); tests 157 (`test` 148, `testSuite` 8, `testFoss` 1); device tests 18 (`androidTest` 17, `androidTestFullSuite` 1) |
| Workflows | `ls .github/workflows`: 15 files. Android APK (`android.yml`), DCO, Emulator smoke test (`emulator.yml`), Gate, Stalkerware list (`ioc.yml`), Signing keys (`keys.yml`), Maps, Relay container, Relay watch, Relay (`relay.yml`), Relay list (`relays.yml`), Release, Supply chain, Verify a release, Work zones |

## Useful starting points in the code

| To see | Start at |
|---|---|
| Everything one envelope goes through on the way in | `CommsRepository.handleLocked` → `processStagedLocked` → `resolveSender` → `KeyChangeGuard.inbound` → `processPayload` |
| The way out | `CommsRepository.deliver` → `prepareLocked` → `Identity::encrypt` (`lib.rs`) → `RelayClient.send` → `index.ts` `/v1/send` → `Mailbox.enqueue` |
| Tokens | `RelayClient.topUpTokens` → `token_blind` / `token_finalize` (`lib.rs`) ↔ `tokens.ts` |
| A relay-to-relay request | `federation.ts` (`federationPayload`, `forward`, `relayKey`) and the `/v1/federate/*` handlers in `index.ts` |
| The locked phone | `Vault.kt`, `VaultBox.kt`, `CommsRepository.keepForUnlock`, `LockedInbox.kt` |
| A move | `CommsRepository.moveTo`, `applyMove`, `drainPreviousRelay`; `CommsStore.renumberContact`, `moveSelf` |
