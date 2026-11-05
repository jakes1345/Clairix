#!/usr/bin/env bash
# Fails if Google Play services, Firebase or the Google Play Billing library is in the
# foss edition, the one built for F-Droid, whose main repository accepts no proprietary
# dependency. (Billing is the Play edition's alone: Supporter purchases, docs/SUPPORTER.md.)
# Two kinds of input:
#
#   tools/android/check-no-google-services.sh --deps FILE.txt ... --apk FILE.apk ...
#
# --deps  output of `./gradlew :app:dependencies --configuration <variant>RuntimeClasspath`:
#         no com.google.android.gms, com.google.firebase or com.android.billingclient
#         artifact may appear in it. It must
#         also list at least one artifact, so a misspelt configuration (which prints "No
#         dependencies") cannot pass.
# --apk   an APK: no class under com/google/android/gms, com/google/firebase or
#         com/android/billingclient in any classes*.dex, and no play-services-*,
#         firebase-* or billing file packaged. A file with no
#         classes*.dex fails: a check that examined nothing proves nothing.
#
# Exit 0 when everything is clean, 1 when something is not, 2 on bad usage.
set -euo pipefail

usage() {
  echo "usage: $0 [--deps FILE.txt ...] [--apk FILE.apk ...]" >&2
  exit 2
}
[ $# -eq 0 ] && usage
command -v unzip > /dev/null || { echo "::error::unzip is not installed" >&2; exit 2; }

ARTIFACTS='com\.google\.android\.gms:|com\.google\.firebase:|com\.android\.billingclient:'
CLASSES='Lcom/google/android/gms/|Lcom/google/firebase/|Lcom/android/billingclient/'
FILES='(^|/)(play-services-[^/]*|firebase-[^/]*|billing[^/]*)\.properties$|^com/google/android/gms/|^com/google/firebase/|^com/android/billingclient/'

mode=""
checked=0
failed=0
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

check_deps() {
  local file="$1"
  if ! grep -Eq -- '--- [A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+' "$file"; then
    echo "::error::$file lists no dependencies: is the configuration name right?"
    failed=1
    return
  fi
  local hits
  hits=$(grep -Eo "($ARTIFACTS)[A-Za-z0-9_.-]+" "$file" | sort -u || true)
  if [ -n "$hits" ]; then
    echo "::error::$file has Google Play services or Firebase on the runtime classpath:"
    echo "$hits" | sed 's/^/  /'
    failed=1
  else
    echo "$file: no Google Play services or Firebase artifact"
  fi
}

check_apk() {
  local apk="$1"
  local listing
  if ! listing=$(unzip -Z1 "$apk" 2> /dev/null); then
    echo "::error::$apk is not a readable zip"
    failed=1
    return
  fi
  local dexes
  dexes=$(echo "$listing" | grep -E '^classes[0-9]*\.dex$' || true)
  if [ -z "$dexes" ]; then
    echo "::error::$apk has no classes*.dex to check"
    failed=1
    return
  fi
  local bad=0
  local files
  files=$(echo "$listing" | grep -E "$FILES" || true)
  if [ -n "$files" ]; then
    echo "::error::$apk packages Google Play services or Firebase files:"
    echo "$files" | sed 's/^/  /'
    bad=1
  fi
  local dex
  for dex in $dexes; do
    unzip -p "$apk" "$dex" > "$tmp/classes.dex"
    local found
    found=$(grep -aEo "($CLASSES)[A-Za-z0-9_/\$]*;" "$tmp/classes.dex" | sort -u | head -5 || true)
    if [ -n "$found" ]; then
      echo "::error::$apk $dex has Google Play services or Firebase classes, for example:"
      echo "$found" | sed 's/^/  /'
      bad=1
    fi
  done
  if [ "$bad" -eq 0 ]; then
    echo "$apk: no Google Play services or Firebase class or file"
  else
    failed=1
  fi
}

for arg in "$@"; do
  case "$arg" in
    --deps|--apk) mode="$arg" ;;
    *)
      [ -z "$mode" ] && usage
      [ -f "$arg" ] || { echo "::error::$arg does not exist"; failed=1; continue; }
      checked=$((checked + 1))
      if [ "$mode" = "--deps" ]; then check_deps "$arg"; else check_apk "$arg"; fi
      ;;
  esac
done

if [ "$checked" -eq 0 ]; then
  echo "::error::nothing was checked" >&2
  exit 1
fi
exit "$failed"
