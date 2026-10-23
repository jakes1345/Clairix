# Asset signing

Everything a phone downloads from this repository's releases is signed, and
the app refuses what it cannot verify: the road graphs (`maps-<slug>`
releases), the work-zone files (`workzones`) and the release checksums
(`SHA256SUMS.txt`). A checksum fetched from the same place as the file only
proves the download was not corrupted; the signature proves it was published
by whoever holds the signing key, which a compromised release, a hijacked
Actions tag or a compelled host does not.

The key is Ed25519. Its secret half lives only in the repository secret
`ASSET_SIGNING_KEY`; its public half is committed, so the app and anyone
checking a download can see it:

| File | What |
|---|---|
| `android/asset-signing.pub` | The public key, raw 32 bytes, base64 without padding: what the app is built with (`BuildConfig.ASSET_PUBKEY`) |
| `android/asset-signing.pem` | The same key as a PEM `SubjectPublicKeyInfo`, for `openssl` |

## One-time setup

```sh
python3 -m pip install -r tools/signing/requirements.txt
python3 tools/signing/keygen.py
```

`keygen.py` writes the two public files above (commit them) and prints the
secret seed once, base64. Put it in the repository secret `ASSET_SIGNING_KEY`
(Settings → Secrets and variables → Actions) and nowhere else. Keep a copy
somewhere safe: a phone built with one public key accepts nothing signed by
another, so losing the key means a new app release before maps can update
again. Rotating it is the same procedure, followed by an app release.

Without the secret, the Maps, Work zones and Release workflows fail rather
than publish unsigned files. Without the public key file, a release build of
the app fails; a debug build skips verification and says so in logcat.

## What is signed

| Published file | Signature | Over |
|---|---|---|
| `graph-<slug>.agr.gz.sha256` | `graph-<slug>.agr.gz.sha256.sig` | the exact bytes of the checksum file; the app then checks the graph against the signed checksum |
| `workzones-<slug>.json.gz` | `workzones-<slug>.json.gz.sig` | the gzip bytes |
| `SHA256SUMS.txt` | `SHA256SUMS.txt.sig` | the exact bytes of the checksum file |

A `.sig` is one line: the 64-byte Ed25519 signature, base64 without padding.

## Checking a release by hand

```sh
sed 's/$/==/' SHA256SUMS.txt.sig | base64 -d > sums.sig   # the .sig is unpadded; base64 -d wants the '=='
openssl pkeyutl -verify -pubin -inkey android/asset-signing.pem -rawin -in SHA256SUMS.txt -sigfile sums.sig
sha256sum -c --ignore-missing SHA256SUMS.txt
```
