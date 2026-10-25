# Reproducible builds

Two builds of one commit should come out the same, so a published APK can be
checked against the source rather than trusted.

## What CI does

For every change to the app or the crate, `.github/workflows/android.yml`
builds the full edition twice, on two machines, with no keystore in the
environment, and `compare-builds.sh` fails unless the two builds agree: the
debug APK entry for entry (each machine signs it with a debug key it makes for
itself, so the signature differs by design), and the release APK, which Gradle
writes unsigned without a keystore, byte for byte. A release build refuses to
run until `android/asset-signing.pub` is committed, so until then the debug
build alone is compared. The unstripped crypto library is also checked for any
path from the build machine.

## Checking a published release

Build the same tag yourself, which leaves the APK unsigned, and compare it to
the release entry by entry. Signing adds `META-INF/*.SF` and `*.RSA`, rewrites
`META-INF/MANIFEST.MF` and puts the signing block outside the entries; the
script leaves those out and requires everything else to match.

```sh
git checkout v3.1.0
(cd android && ./gradlew assembleFullSuiteRelease)
tools/reproducible/compare-apk.sh \
    android/app/build/outputs/apk/fullSuite/release/app-full-suite-release-unsigned.apk \
    clairix-v3.1.0.apk
```

You need JDK 17, the Android SDK (API 35), NDK `27.2.12479018`, `cargo-ndk`
`4.1.2` and Rust `1.97.0`, which rustup installs from
`comms-crypto/rust-toolchain.toml` on the first build:

```
sdkmanager "ndk;27.2.12479018"
cargo install cargo-ndk --version 4.1.2 --locked
```

From 3.0.0 Beta 6 the build checks both versions and stops on any other, so a
rebuild cannot quietly use a different NDK. Releases up to Beta 5 were built
with whichever NDK the CI runner had installed as its latest, so a rebuild of
one of those may differ in the native library. No published release has yet
been rebuilt and matched this way; until one has, the comparison is a check,
not a promise.
