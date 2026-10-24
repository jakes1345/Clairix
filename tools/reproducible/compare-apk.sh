#!/usr/bin/env bash
# Compares two APKs entry by entry (name, uncompressed size, CRC-32), leaving
# out the signature files, so a published, signed release can be checked
# against an unsigned rebuild of the same commit: signing adds
# META-INF/*.SF and *.RSA (or *.EC), rewrites META-INF/MANIFEST.MF, and
# puts the v2/v3 signing block outside the entries. Everything else must
# match. Exit 0 when it does, 1 when any entry differs, 2 on bad usage.
#
#   tools/reproducible/compare-apk.sh app-full-suite-release-unsigned.apk clairix-v3.1.0.apk
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 A.apk B.apk" >&2
  exit 2
fi
for f in "$1" "$2"; do
  if [ ! -f "$f" ]; then
    echo "$f: not a file" >&2
    exit 2
  fi
done

# unzip -vqq prints, per entry: length method size ratio date time crc name.
entries() {
  unzip -vqq "$1" | awk '{
    if ($8 ~ /^META-INF\/[^\/]*\.(SF|RSA|DSA|EC|MF)$/) next
    print $8, $1, $7
  }' | LC_ALL=C sort
}

if diff -u --label "$1" --label "$2" <(entries "$1") <(entries "$2"); then
  echo "Same contents: every entry of $1 and $2 matches, signature files aside."
else
  echo "Contents differ (name, size, CRC-32 per entry above)." >&2
  exit 1
fi
