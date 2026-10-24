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
python3 tools/signing/keygen.py --gh-secret jakes1345/Clairix
```

Run this yourself, in your own terminal, signed in to the GitHub CLI (`gh
auth login`) as an administrator of the repository. `keygen.py` writes the
two public files above (commit them) and hands the secret seed to `gh secret
set` on its standard input, so it is never printed, never on a command line,
never in a clipboard. Without `--gh-secret` it prints the seed once instead,
for pasting into Settings → Secrets and variables → Actions by hand.

The seed must not pass through a browser extension, a chat, an AI session or
anything else that keeps a transcript: whatever sees it can sign releases and
map files as you, and a copy in a log is a copy you cannot take back. The
public files are safe anywhere; the seed is safe in the GitHub secret, which
Actions can use but nobody can read back, and in one offline copy of your
own if you want to survive losing the secret. A phone built with one public
key accepts nothing signed by another, so a lost key means a new key
(`--force`) and a new app release before maps can update again. Rotating it
is the same procedure, followed by an app release.

Who can get at the secret afterwards: anyone who can change a workflow in
this repository, since a workflow runs with the secrets it is given. That is
the repository's administrators, so keep that list short and under two-factor
authentication.

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
