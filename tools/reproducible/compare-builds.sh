#!/usr/bin/env bash
# Compares the APKs two machines built from one commit, directory against
# directory, and fails unless every one agrees:
#
#   *-unsigned.apk  must be the same file byte for byte: no key was in either
#                   environment, so nothing may differ.
#   any other .apk  is a debug build, which each machine signs with a debug key
#                   it made for itself, so the signature differs by design;
#                   every entry under it must match (compare-apk.sh).
#
#   tools/reproducible/compare-builds.sh builds/first builds/second
#
# Exit 0 when every APK agrees, 1 when any differs or is missing on one side,
# 2 on bad usage. Lines meant for a GitHub job summary go to $GITHUB_STEP_SUMMARY
# when it is set.
set -euo pipefail
shopt -s nullglob

if [ $# -ne 2 ] || [ ! -d "$1" ] || [ ! -d "$2" ]; then
  echo "usage: $0 DIR_A DIR_B   (each holding the APKs one machine built)" >&2
  exit 2
fi
here=$(cd "$(dirname "$0")" && pwd)
summary() { echo "$1"; if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "- $1" >> "$GITHUB_STEP_SUMMARY"; fi; }

count=0; bad=0
for a in "$1"/*.apk; do
  name=$(basename "$a"); b="$2/$name"
  count=$((count + 1))
  if [ ! -f "$b" ]; then
    echo "::error::$name was built on the first machine only" >&2; bad=1; continue
  fi
  sha256sum "$a" "$b"
  case "$name" in
    *-unsigned.apk)
      if cmp -s "$a" "$b"; then
        summary "$name: the same file on both machines ($(sha256sum "$a" | cut -c1-64))"
      else
        echo "::error::$name differs between the two machines" >&2
        "$here/compare-apk.sh" "$a" "$b" || true
        diff <(unzip -l "$a") <(unzip -l "$b") || true
        bad=1
      fi ;;
    *)
      if "$here/compare-apk.sh" "$a" "$b"; then
        if cmp -s "$a" "$b"; then
          summary "$name: the same file on both machines"
        else
          summary "$name: the same contents on both machines; the files differ only in their debug signatures"
        fi
      else
        echo "::error::$name: the contents differ between the two machines" >&2; bad=1
      fi ;;
  esac
done
for b in "$2"/*.apk; do
  name=$(basename "$b")
  if [ ! -f "$1/$name" ]; then echo "::error::$name was built on the second machine only" >&2; bad=1; fi
done
if [ "$count" = 0 ]; then
  echo "::error::No APK to compare in $1" >&2; exit 1
fi
exit $bad
