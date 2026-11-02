#!/usr/bin/env bash
# Fails unless the Play bundle and every APK are signed by one and the same certificate,
# and prints that certificate's SHA-256 digest.
#
# Google Play re-signs what it delivers with the app-signing key enrolled at the first
# upload. Clairix enrols its own release key there ("Use an existing key", android/README.md,
# "Cutting a release" step 5a), so a Play-delivered APK carries the same certificate as the
# APKs on GitHub Releases, and one installs over the other. That only holds while the bundle
# is signed with the release key too: Play then checks it as the upload key. This check stops
# a release whose bundle and APKs disagree, and the digest it prints is the one a
# Play-delivered APK's `apksigner verify --print-certs` must show.
#
#   tools/android/check-bundle-signer.sh [--apksigner PATH] [--keytool PATH] BUNDLE.aab APK...
#
# apksigner is taken from the newest build-tools under $ANDROID_HOME and keytool from PATH
# unless named. Exit 0 when every file has the same single signer, 1 when not, 2 on bad usage.
set -euo pipefail

apksigner=""
keytool="keytool"
while [ $# -gt 0 ]; do
  case "$1" in
    --apksigner) apksigner="${2:?--apksigner needs a path}"; shift 2 ;;
    --keytool) keytool="${2:?--keytool needs a path}"; shift 2 ;;
    *) break ;;
  esac
done
if [ $# -lt 2 ]; then
  echo "usage: $0 [--apksigner PATH] [--keytool PATH] BUNDLE.aab APK..." >&2
  exit 2
fi
bundle="$1"
shift
case "$bundle" in
  *.aab) ;;
  *) echo "::error::$bundle: the first file must be the .aab" >&2; exit 2 ;;
esac
if [ -z "$apksigner" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  newest=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
  [ -n "$newest" ] && apksigner="$ANDROID_HOME/build-tools/$newest/apksigner"
fi
if [ -z "$apksigner" ] || [ ! -x "$apksigner" ]; then
  echo "::error::apksigner not found: set ANDROID_HOME to an SDK with build-tools, or pass --apksigner PATH" >&2
  exit 2
fi
for f in "$bundle" "$@"; do
  if [ ! -f "$f" ]; then
    echo "::error::$f: not a file" >&2
    exit 2
  fi
done

# The bundle is jar-signed: keytool lists each signer's certificate chain. Only the first
# certificate of each chain (the signer itself) counts; a release key is self-signed, so
# there is one per signer.
bundle_digests=$("$keytool" -printcert -jarfile "$bundle" 2>/dev/null \
  | awk '/^Signer #/ { want = 1 } want && $1 == "SHA256:" { print $2; want = 0 }' \
  | tr -d ':' | tr 'A-F' 'a-f' | sort -u) || true
count=$(printf '%s' "$bundle_digests" | grep -c . || true)
if [ "$count" -eq 0 ]; then
  echo "::error file=$bundle::the bundle is not signed (keytool -printcert -jarfile found no signer)"
  exit 1
fi
if [ "$count" -gt 1 ]; then
  echo "::error file=$bundle::the bundle has $count signers; Play App Signing expects exactly one"
  exit 1
fi
expected="$bundle_digests"
echo "$bundle: signer certificate SHA-256 $expected"

failed=0
for apk in "$@"; do
  if ! out=$("$apksigner" verify --print-certs "$apk" 2>&1); then
    echo "::error file=$apk::apksigner could not verify the APK"
    printf '%s\n' "$out" | tail -5
    failed=1
    continue
  fi
  # "Signer #1 certificate SHA-256 digest: …" from older build-tools; newer ones name
  # the scheme ("V3.0 Signer: certificate …", what CI's build-tools print) or the
  # platform versions a signer covers ("Signer (minSdkVersion=31, …) certificate …"),
  # the same certificate on more than one line. Every form is read; one certificate
  # is one signer however often it is named.
  digests=$(printf '%s\n' "$out" | sed -nE 's/^(V[0-9.]+ )?Signer[^:]*:? *certificate SHA-256 digest: *([0-9A-Fa-f]{64}).*$/\2/p' | tr 'A-F' 'a-f' | sort -u)
  n=$(printf '%s' "$digests" | grep -c . || true)
  if [ "$n" -ne 1 ]; then
    echo "::error file=$apk::expected exactly one signer, found $n"
    printf '%s\n' "$out" | grep -i 'signer\|verif' | head -20
    failed=1
    continue
  fi
  if [ "$digests" != "$expected" ]; then
    echo "::error file=$apk::signed by $digests, but the bundle by $expected; Play-delivered and GitHub APKs would not install over each other"
    failed=1
    continue
  fi
  echo "$apk: signer certificate SHA-256 $digests"
done

if [ "$failed" -eq 0 ]; then
  echo "Release certificate SHA-256: $expected (the bundle and $# APK(s) agree)"
fi
exit $failed
