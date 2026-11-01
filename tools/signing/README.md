# Asset signing

Everything a phone downloads from this repository's releases is signed, and
the app refuses what it cannot verify: the road graphs and plate-reader
lists (`maps-<slug>` releases), the work-zone files (`workzones`), the
stalkerware list (`ioc`) and the relay list (`relays`). The release
checksums (`SHA256SUMS.txt`) are signed as well. A checksum fetched from the
same place as the file only proves the download was not corrupted; the
signature proves it was published by whoever holds the signing key, which a
compromised release, a hijacked Actions tag or a compelled host does not.
[`docs/KEYS.md`](../../docs/KEYS.md) records this key and the APK signing
certificate, with the commands that check them, and is published signed too
(`keys`).

The key is Ed25519. Its secret half lives only in the repository secret
`ASSET_SIGNING_KEY`; its public half is committed, so the app and anyone
checking a download can see it:

| File | What |
|---|---|
| `android/asset-signing.pub` | The public key, raw 32 bytes, base64 without padding: what the app is built with (`BuildConfig.ASSET_PUBKEY`) |
| `android/asset-signing.pem` | The same key as a PEM `SubjectPublicKeyInfo`, for `openssl` |

## One-time setup

```sh
python3 -m pip install --require-hashes -r tools/signing/requirements.txt
python3 tools/signing/keygen.py --gh-secret jakes1345/Clairix --gh-env release --gh-env assets
```

Run this yourself, in your own terminal, signed in to the GitHub CLI (`gh
auth login`) as an administrator of the repository. `keygen.py` writes the
two public files above (commit them) and hands the secret seed to `gh secret
set` on its standard input, so it is never printed, never on a command line,
never in a clipboard. With `--gh-env` it goes into the secret of each
environment named, the two that hold the signing jobs
([docs/PROCESS.md](../../docs/PROCESS.md), Signing keys); without, into the
repository's own secret. Without `--gh-secret` it prints the seed once
instead, for pasting into each environment's secrets by hand.

`requirements.txt` lists a hash for every file of every package it resolves
to, and every workflow installs it with `--require-hashes`, so pip refuses a
file PyPI serves that does not match. To change the version, edit
`requirements.in` and compile it again under Python 3.11 with the command at
the top of `requirements.txt`.

The seed must not pass through a browser extension, a chat, an AI session or
anything else that keeps a transcript: whatever sees it can sign releases and
map files as you, and a copy in a log is a copy you cannot take back. The
public files are safe anywhere; the seed is safe in the GitHub secret, which
Actions can use but nobody can read back, and in one offline copy of your
own if you want to survive losing the secret. A phone built with one public
key accepts nothing signed by another, so a lost key means a new key
(`--force`) and a new app release before maps can update again. Rotating it
is the same procedure, followed by an app release.

Who can get at the secret afterwards: a job that names the `release` or
`assets` environment and runs from a branch or tag that environment allows,
which is main for `assets`, and main, a `release/v*` branch or a `v*` tag for
`release`. A changed workflow reaches main only through a pull request under
the main ruleset, but anyone with write access can push a `release/v*` branch
or a `v*` tag, so keep the list of people with write access short and under
two-factor authentication. A copy left in the repository's own secrets is
readable by any workflow on any branch, so it is deleted once the
environments hold the key.

Without the secret, the Maps, Work zones, Stalkerware list, Relay list,
Signing keys and Release workflows fail rather than publish unsigned files. Without the public key
file, a release build of the app fails; a debug build skips verification and
says so in logcat.

## What is signed

| Published file | Signature | Over |
|---|---|---|
| `graph-<slug>.agr.gz.sha256` | `graph-<slug>.agr.gz.sha256.sig` | the exact bytes of the checksum file; the app then checks the graph against the signed checksum |
| `cameras-<slug>.json.gz` (the same `maps-<slug>` release) | `cameras-<slug>.json.gz.sig` | the gzip bytes of the region's plate-reader list |
| `relays.json` (the `relays` release, `.github/workflows/relays.yml`) | `relays.json.sig` | the exact bytes of the relay list; the app refuses one issued earlier than the list it has |
| `workzones-<slug>.json.gz` | `workzones-<slug>.json.gz.sig` | the gzip bytes |
| `workzones-index.json` | `workzones-index.json.sig` | the exact bytes of the index; the app reads the time of the aggregator's last run from it |
| `ioc.json.gz` (the `ioc` release, `.github/workflows/ioc.yml`) | `ioc.json.gz.sig` | the gzip bytes of the stalkerware list (`tools/ioc/convert.py`); the app refuses one issued earlier than the list it has |
| `SHA256SUMS.txt` | `SHA256SUMS.txt.sig` | the exact bytes of the checksum file |
| `KEYS.md` (the `keys` release, `.github/workflows/keys.yml`, started by hand from `main`) | `KEYS.md.sig` | the exact bytes of `docs/KEYS.md` |

A `.sig` is one line: the 64-byte Ed25519 signature, base64 without padding.

## Checking a release by hand

```sh
sed 's/$/==/' SHA256SUMS.txt.sig | base64 -d > sums.sig   # the .sig is unpadded; base64 -d wants the '=='
openssl pkeyutl -verify -pubin -inkey android/asset-signing.pem -rawin -in SHA256SUMS.txt -sigfile sums.sig
sha256sum -c --ignore-missing SHA256SUMS.txt
```
