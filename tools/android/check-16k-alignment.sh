#!/usr/bin/env bash
# Fails unless every 64-bit native library packaged in the given APKs and bundles is built
# for 16 KB pages, which Android 15+ devices with 16 KB pages need to load it and Google
# Play requires of new uploads: each LOAD segment of lib/arm64-v8a/*.so and lib/x86_64/*.so
# (base/lib/... in a bundle) must be aligned to 0x4000, and an APK's uncompressed libraries
# must sit on 16 KiB boundaries in the zip (zipalign -c -P 16 -v 4; a bundle is aligned by
# bundletool when it makes APKs, so only its libraries are read). Finding no 64-bit library
# is a failure too: a check that examined nothing proves nothing.
#
#   tools/android/check-16k-alignment.sh [--zipalign PATH] FILE.apk|FILE.aab ...
#
# zipalign is taken from the newest build-tools under $ANDROID_HOME unless --zipalign names
# it. Exit 0 when everything is aligned, 1 when something is not, 2 on bad usage.
set -euo pipefail

PAGE=16384
zipalign=""
if [ "${1:-}" = "--zipalign" ]; then
  zipalign="${2:?--zipalign needs a path}"
  shift 2
fi
if [ $# -eq 0 ]; then
  echo "usage: $0 [--zipalign PATH] FILE.apk|FILE.aab ..." >&2
  exit 2
fi
if ! command -v readelf > /dev/null; then
  echo "::error::readelf (binutils) is not installed" >&2
  exit 2
fi
if [ -z "$zipalign" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  newest=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
  [ -n "$newest" ] && zipalign="$ANDROID_HOME/build-tools/$newest/zipalign"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

failed=0
checked=0
n=0
for f in "$@"; do
  n=$((n + 1))
  if [ ! -f "$f" ]; then
    echo "::error::$f: not a file" >&2
    exit 2
  fi
  case "$f" in
    *.apk) kind=apk; prefix="lib" ;;
    *.aab) kind=aab; prefix="base/lib" ;;
    *) echo "::error::$f: not an .apk or .aab" >&2; exit 2 ;;
  esac
  dir="$tmp/$n"
  mkdir -p "$dir"
  # unzip exits 11 when a pattern matches nothing: an APK with one 64-bit ABI is still fine.
  unzip -o -q "$f" "$prefix/arm64-v8a/*.so" "$prefix/x86_64/*.so" -d "$dir" > /dev/null 2>&1 || true
  libs=()
  while IFS= read -r -d '' so; do libs+=("$so"); done < <(find "$dir" -type f -name '*.so' -print0 | sort -z)
  if [ ${#libs[@]} -eq 0 ]; then
    echo "::error file=$f::no 64-bit native library under $prefix/arm64-v8a or $prefix/x86_64; the check proved nothing"
    failed=1
    continue
  fi
  for so in "${libs[@]}"; do
    rel="${so#"$dir/"}"
    checked=$((checked + 1))
    loads=0
    while read -r align; do
      [ -n "$align" ] || continue
      loads=$((loads + 1))
      # readelf prints the alignment in hex; bash arithmetic reads 0x.
      if [ $((align)) -lt $PAGE ] || [ $((align % PAGE)) -ne 0 ]; then
        echo "::error file=$f::$rel has a LOAD segment aligned to $align, not 0x4000 (16 KB)"
        failed=1
      fi
    done < <(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }')
    if [ "$loads" -eq 0 ]; then
      echo "::error file=$f::$rel has no LOAD segment; is it an ELF shared object?"
      failed=1
    fi
  done
  if [ "$kind" = apk ]; then
    if [ -z "$zipalign" ] || [ ! -x "$zipalign" ]; then
      echo "::error::zipalign not found: set ANDROID_HOME to an SDK with build-tools, or pass --zipalign PATH" >&2
      exit 2
    fi
    if ! "$zipalign" -c -P 16 -v 4 "$f" > "$tmp/zipalign.log" 2>&1; then
      grep -E 'BAD|FAILED' "$tmp/zipalign.log" | head -20 || true
      echo "::error file=$f::uncompressed native libraries are not on 16 KiB boundaries in the zip (zipalign -c -P 16 -v 4 failed)"
      failed=1
    fi
  fi
  echo "$f: ${#libs[@]} 64-bit librar$([ ${#libs[@]} -eq 1 ] && echo y || echo ies) read"
done

if [ "$failed" -eq 0 ]; then
  echo "All $checked 64-bit libraries in $n file(s) are built for 16 KB pages."
fi
exit $failed
