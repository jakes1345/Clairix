# Signing keys

Clairix signs what it publishes with two keys: the asset signing key, and the
release key whose certificate signs the APKs. This file records each from
facts anyone can check, gives the commands that check them, and says what it
does not record. It is published with its signature by the asset key as the
[`keys` release](https://github.com/jakes1345/Clairix/releases/tag/keys)
(`KEYS.md` and `KEYS.md.sig`, by `.github/workflows/keys.yml`).

## The asset signing key

An Ed25519 key. The app is built with its public half and refuses a
downloaded file whose signature does not verify against it.

| | |
|---|---|
| Public key, raw 32 bytes, base64 without padding (`android/asset-signing.pub`) | `uUUqtheaiRkKRdzuhW2Nf1MmlH3ucHTT81DI038pX7U` |
| SHA-256 of the raw 32 bytes | `9f0327f3b9d6e3014d729ec59846787bc4bdc7574a7ceef59439570a8fbaa72e` |
| The same key as a PEM `SubjectPublicKeyInfo` (`android/asset-signing.pem`) | `MCowBQYDK2VwAyEAuUUqtheaiRkKRdzuhW2Nf1MmlH3ucHTT81DI038pX7U=` |
| SHA-256 of that `SubjectPublicKeyInfo` (DER) | `e36b3d594d3ef346df61a766636b1633ffb123ec301a2f1a4b6f2898bf665aa1` |
| SHA-256 of `android/asset-signing.pub` as committed (with its newline) | `9e8a40521d13ceaa310033352ba779a93930295d5ceb286d8f0919b0f861c2f9` |
| SHA-256 of `android/asset-signing.pem` as committed | `9d26b38b1d4ea96576b7e7569712894c376dae421471465764d09d969e27dc4d` |

**The commit that added it.** Both files were added by commit
`0ff171b5c6c973310726d331432ee0a84736faac`, "Asset signing public key", whose
author and committer, as git records them, are
`CoinsCommunity <Communitycoins@proton.me>`, on 24 October 2026 at 14:42:42
−0500 (19:42:42 UTC). The commit is not signed. It is the only commit by that
identity on any branch, and neither file has changed since.

**Where the app holds it.** `android/app/build.gradle.kts` reads
`android/asset-signing.pub` when the app is built and bakes it in as
`BuildConfig.ASSET_PUBKEY`; `nav/AssetSignature.kt` checks signatures against
it; a release build stops if the file is missing. The key is visible inside a
release APK (see "How to check" below).

**What it signs.** Its secret half is the repository secret
`ASSET_SIGNING_KEY`. These workflows sign with it and check each signature
against `android/asset-signing.pub` before they publish:

| Published file | Release | Workflow | Checked by |
|---|---|---|---|
| `relays.json`, the relay list | `relays` | `relays.yml` | the app, before it uses a downloaded list (`comms/RelayDirectory.kt`) |
| `graph-<region>.agr.gz.sha256`, each road graph's checksum | `maps-<region>` | `maps.yml` | the app, which then holds the graph to the checksum before installing it (`nav/GraphStore.kt`) |
| `cameras-<region>.json.gz`, each region's plate-reader list | `maps-<region>` | `maps.yml` | the app, before it uses a downloaded list (`nav/CameraFeed.kt`) |
| `workzones-*.json.gz` and `workzones-index.json`, the work zones | `workzones` | `workzones.yml` | the app, before it uses a downloaded file (`nav/WorkZoneFeed.kt`) |
| `ioc.json.gz`, the stalkerware list | `ioc` | `ioc.yml` | the app, before it uses a downloaded list (`analysis/StalkwareIoc.kt`) |
| `SHA256SUMS.txt`, the checksums of each release's files | every `v*` release | `release.yml` | you, by hand; the app does not check it |
| `KEYS.md`, this file | `keys` | `keys.yml` | you, by hand |
| `legal-process.md`, the text of [clairix.app/legal-process](https://clairix.app/legal-process) with its dated transparency statement | `keys` | `keys.yml` | you, by hand |

A `.sig` is one line: the 64-byte Ed25519 signature over the file's exact
bytes, base64 without padding (`tools/signing/README.md`).

Checked on 1 November 2026 against the published files: the
`SHA256SUMS.txt.sig` of every release from `v3.0.0-beta.3` to `v3.0.0-beta.8`
verifies against this key (`v3.0.0-beta.1` and `v3.0.0-beta.2` were published
before it was added and carry none), and so do the published `relays.json.sig`
and, as samples, the Vermont region's graph checksum, plate-reader list and
work-zone file.

## The APK signing certificate

Android installs an update only when it is signed with the certificate of the
app already installed. Every APK this repository has released is signed with
this one:

| | |
|---|---|
| SHA-256 of the certificate | `47:1C:09:CD:79:2F:FF:0B:F7:57:24:BB:B8:EE:2D:13:CC:67:4E:3F:51:D1:E1:FD:E2:8A:25:00:93:2A:52:CC` |
| The same, as `apksigner` prints it | `471c09cd792fff0bf75724bbb8ee2d13cc674e3f51d1e1fde28a2500932a52cc` |
| Subject and issuer (self-signed) | `CN=Clairix, O=Clairix, C=US` |
| Key | RSA, 4096 bits |
| Serial number | `72cae97962eed481` |
| Validity written in the certificate | 18 October 2026 22:33:00 UTC to 5 March 2054 22:33:00 UTC |

Checked on 1 November 2026: all sixteen APKs published as `v3.0.0-beta.1` to
`v3.0.0-beta.8` (`clairix-<tag>.apk` and `clairix-comms-<tag>.apk`) carry this
certificate, with APK Signature Scheme v2, and the Beta 8 Play bundle
(`clairix-play-v3.0.0-beta.8.aab`) is jar-signed with it.
`website/public/.well-known/assetlinks.json`, served at
https://clairix.app/.well-known/assetlinks.json, names it, so Android opens
`https://clairix.app/i` invite links straight in an app signed with it and in
no other. The validity dates are whatever the tool that made the certificate
wrote; they are not evidence of when or where the key was made.

**Where it is used.** For each release, `release.yml` decodes the repository
secret `ANDROID_KEYSTORE_BASE64` onto the GitHub runner and Gradle signs the
APKs and the Play bundle with it (`android/README.md`, "Release signing").
Without the secret the workflow publishes nothing. Signing on the CI runner is
an open finding (18 in [OPEN-ISSUES.md](OPEN-ISSUES.md)).

## Commit signatures

No key in this file signs commits, and no commit in this repository is signed
with a key the maintainer has published. Merges made in GitHub's web
interface carry GitHub's own signature; the other commits are unsigned or
carry SSH signatures that GitHub does not tie to the maintainer's account.
So a commit's signature says nothing here about who made it; the two keys
above are what to check.

## How to check

On a Mac, use `shasum -a 256` where these say `sha256sum`.

**This file.** Download `KEYS.md` and `KEYS.md.sig` from the `keys` release,
and `android/asset-signing.pem` from the repository:

```sh
sed 's/$/==/' KEYS.md.sig | base64 -d > keys.sig   # the .sig is unpadded; base64 -d wants the '=='
openssl pkeyutl -verify -pubin -inkey asset-signing.pem -rawin -in KEYS.md -sigfile keys.sig
```

`Signature Verified Successfully` means the file was published by whoever
holds the asset key's secret half and has not changed since. It cannot show
that the key is the right one, since the file vouches for the key that signs
it: compare the values above with a copy you got another way, such as the
key inside the APK you installed or a clone of the repository you made
earlier.

**The key files.** In a clone of the repository:

```sh
sha256sum android/asset-signing.pub android/asset-signing.pem             # the last two rows of the first table
sed 's/$/=/' android/asset-signing.pub | base64 -d | sha256sum            # 9f0327f3...
openssl pkey -pubin -in android/asset-signing.pem -outform DER | sha256sum  # e36b3d59...
git log --format='%H %an <%ae> %ad %G?' -- android/asset-signing.pub android/asset-signing.pem
```

**The key inside an APK.**

```sh
unzip -p clairix-v3.0.0-beta.8.apk classes.dex | grep -a -c uUUqtheaiRkKRdzuhW2Nf1MmlH3ucHTT81DI038pX7U   # 1
```

**A release.** With `SHA256SUMS.txt`, `SHA256SUMS.txt.sig` and the files you
downloaded in one folder:

```sh
sed 's/$/==/' SHA256SUMS.txt.sig | base64 -d > sums.sig
openssl pkeyutl -verify -pubin -inkey asset-signing.pem -rawin -in SHA256SUMS.txt -sigfile sums.sig
sha256sum -c --ignore-missing SHA256SUMS.txt
```

**An APK's certificate.** `apksigner` is in the Android SDK's build tools:

```sh
apksigner verify --print-certs clairix-v3.0.0-beta.8.apk
# Signer #1 certificate SHA-256 digest: 471c09cd792fff0bf75724bbb8ee2d13cc674e3f51d1e1fde28a2500932a52cc
```

`keytool -printcert -jarfile` cannot read the APKs, which carry only an APK
Signature Scheme v2 signature; it does read the Play bundle.

`python3 tools/signing/sign.py --verify android/asset-signing.pub FILE...`
checks `FILE.sig` beside each file, for any file in the first table.

## What the maintainer signs for

Every fact above comes with a way to check it that does not depend on trusting
anyone. Publishing this file through `keys.yml`, which is started by hand,
from `main` only, adds one statement that only the maintainer can make: these
two keys, and no others, are the ones Clairix signs its downloads, its release
checksums and its APKs with, so anything signed with another key did not come
from the project. The same run signs and publishes the legal-process page, so
its transparency section, which says how many requests for users' data have
arrived as of the date it gives, is a statement by the same key.

On every pull request that touches this file or the key files, and before
every signing, `keys.yml` checks that this file names the key in
`android/asset-signing.pub` and `android/asset-signing.pem`, and that the two
files hold the same key; a key that changes while this file stays behind
fails that check.

## Not recorded here

This file does not say who generated either key, on what machine or when,
whether an offline copy of either secret exists or where, or whose identity
`CoinsCommunity <Communitycoins@proton.me>` is. Nothing in the repository or
its releases shows those things, and this file states only what can be shown.
