# Reproducible builds

Two builds of one commit should come out the same, so a published APK can be
checked against the source rather than trusted.

## What CI does

For every change to the app or the crate, `.github/workflows/android.yml`
builds the full edition's release APK twice, on two machines, with no keystore
in the environment (so Gradle leaves the signing config off and the APK comes
out unsigned), and fails unless the two files are identical byte for byte. The
unstripped crypto library is also checked for any path from the build machine.

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

You need JDK 17, the Android SDK (API 35), an NDK, `cargo-ndk`, and Rust
`1.97.0`, which rustup installs from `comms-crypto/rust-toolchain.toml` on the
first build. `android/README.md` says what is pinned and what is not.

## What is not pinned

The NDK (CI uses the runner's latest; `source.properties` under
`$ANDROID_HOME/ndk/<version>` says which one a machine has) and `cargo-ndk`
(the latest release at build time). A rebuild with a different NDK can differ
in the native library. No published release has yet been rebuilt and matched
this way; until one has, the comparison is a check, not a promise.
