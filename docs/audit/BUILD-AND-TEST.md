# Building and testing

Everything below runs on Linux. The same commands run in CI on every change
(`.github/workflows/`); results quoted are from 3.0.0 Beta 7.

## Tools

- **JDK 17**, and the **Android SDK** with the API 36 platform and NDK `27.2.12479018`
  (`sdkmanager "ndk;27.2.12479018"`).
- **Rust** `1.97.0`: rustup installs it from `comms-crypto/rust-toolchain.toml`,
  with the three Android targets.
- **cargo-ndk** `4.1.2`: `cargo install cargo-ndk --version 4.1.2 --locked`.
  The Gradle build refuses any other version, so builds stay reproducible.
- **Node.js 22** and npm, for the relay.

## The crypto crate

```sh
cd comms-crypto
cargo test --release                                  # 49 tests
cargo test --release --lib -- --ignored tor_          # 1 test over the real Tor network (needs internet)
```

## The relay

```sh
cd comms-worker
npm ci
npm run typecheck
npm test                                              # 148 tests, against the Worker's own code
(cd voprf && cargo test --locked --release)           # the VOPRF the relay runs, compiled to src/voprf.wasm
```

`npm test` runs the real Worker modules (Durable Objects included) in Node
through a small harness in `test/harness/`; nothing is mocked except the
platform bindings.

## The app

```sh
cd android
./gradlew assembleFullSuiteDebug                      # app/build/outputs/apk/fullSuite/debug/
./gradlew testFullSuiteDebugUnitTest                  # 337 JVM tests; 24 more need relays (below)
```

The build compiles `comms-crypto` for every Android ABI through cargo-ndk.

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

- **Editions:** `fullSuite` (everything), `fullComms` (messaging only) and
  `playSuite` (the Play Store edition).
- **Variants:** see [`../../android/README.md`](../../android/README.md).

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
crypto crate, against real relays.

```sh
cd android
CLAIRIX_RELAY_URL=http://127.0.0.1:8787 CLAIRIX_RELAY_B_URL=http://127.0.0.1:8788 \
CLAIRIX_ENROLL_SECRET=e2e-enrollment-secret-0123456789 \
  ./gradlew testFullSuiteDebugUnitTest --tests 'app.clairix.comms.RelayRoundTripTest'
```

They cover:
- registration, sessions and receipts;
- blind tokens, and that a claimed one-time key is never offered again;
- envelope sizes;
- a contact on another relay;
- an identity moving to the other relay.

### The app on an emulator against them

The instrumentation suites drive the real app. They run in CI on Android 12,
13, 14 and 15 (`.github/workflows/emulator.yml`).

```sh
adb reverse tcp:8787 tcp:8787 && adb reverse tcp:8788 tcp:8788
adb shell am instrument -w -r -e class app.clairix.comms.EndToEndTest \
  -e relayA http://127.0.0.1:8787 -e relayB http://127.0.0.1:8788 \
  -e secret e2e-enrollment-secret-0123456789 \
  app.clairix.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | What it does |
|---|---|
| `SmokeTest` (33) | Every screen, through UI Automator, as a person would |
| `EndToEndTest` (11) | Covers: <ul><li>texts with receipts, a voice note, a photo in chunks, a group;</li><li>disappearing messages;</li><li>a call that rings, including on a locked phone;</li><li>a contact on the other relay;</li><li>contacts holding the phone's pass, and the pass replaced when a contact is deleted.</li></ul> |
| `SoakTest` (1, `-e soakMinutes 8`) | An eight-minute conversation with a contact on each relay. Photos, the vault sealing and opening, the socket dropped; nothing lost, nothing twice |
| `RelayMoveTest` (4) | The app moves and both contacts follow; mail to the old number is collected; a contact moves; the app moves back |
| `TorDeviceTest` (4, `-e tor true`) | Requests leave through Tor; a listed relay answers through Tor; a relay is reached at its onion address; nothing goes out directly when Tor is down |
| `VaultLockScreenTest` (2, `-e lockScreen true`) | The vault across a lock screen being set and removed |
| `VaultDeviceTest`, `CommsStoreUpgradeTest`, `DisappearingMessagesTest` | The vault keys, database upgrades and timer deletion on a device |
| `CardVaultLockTest` (`-e lockScreen true`) | A synthetic ISO-DEP card saved through the card vault under a set lock screen, and the armed card disarming when the app locks |

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
(`.github/workflows/verify-release.yml`); Beta 7 matches. To check a
published APK yourself:

```sh
git checkout v3.0.0-beta.7
(cd android && ./gradlew assembleFullSuiteRelease)
tools/reproducible/compare-apk.sh \
  android/app/build/outputs/apk/fullSuite/release/app-full-suite-release-unsigned.apk \
  clairix-v3.0.0-beta.7.apk
```

The script ignores only what signing adds. Everything else must match
[`../../tools/reproducible/README.md`](../../tools/reproducible/README.md).

## Useful starting points in the code

| To see | Start at |
|---|---|
| Everything one envelope goes through on the way in | `CommsRepository.handleLocked` → `processStagedLocked` → `resolveSender` → `KeyChangeGuard.inbound` → `processPayload` |
| The way out | `CommsRepository.deliver` → `prepareLocked` → `Identity::encrypt` (`lib.rs`) → `RelayClient.send` → `index.ts` `/v1/send` → `Mailbox.enqueue` |
| Tokens | `RelayClient.topUpTokens` → `token_blind` / `token_finalize` (`lib.rs`) ↔ `tokens.ts` |
| A relay-to-relay request | `federation.ts` (`federationPayload`, `forward`, `relayKey`) and the `/v1/federate/*` handlers in `index.ts` |
| The locked phone | `Vault.kt`, `VaultBox.kt`, `CommsRepository.keepForUnlock`, `LockedInbox.kt` |
| A move | `CommsRepository.moveTo`, `applyMove`, `drainPreviousRelay`; `CommsStore.renumberContact`, `moveSelf` |
