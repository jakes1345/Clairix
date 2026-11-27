#!/usr/bin/env bash
# Downloads a region's OpenStreetMap extract and the MD5 Geofabrik publishes beside it, and
# checks the one against the other. Used by the build job of the Maps workflow
# (.github/workflows/maps-build.yml).
#
#   fetch_extract.sh URL DIR
#
# writes DIR/region.osm.pbf, DIR/region.osm.pbf.md5 and DIR/region.headers (every response's
# headers, redirects included, for extract_info.py).
#
# Exit status:
#   0   downloaded and the MD5 matches
#   75  this machine could not reach the server at all: the connection was refused, reset,
#       timed out or its name did not resolve, on every try. download.geofabrik.de blocks
#       whole /24 networks that have made too many requests, and some GitHub runners share
#       a network with someone who has, so retrying on the same runner gets nowhere; the
#       workflow builds the region again on a new runner instead.
#   other: anything else (an HTTP error such as a 404, a bad MD5, a full disk), which a new
#       runner would not fix.
#
# FETCH_RETRIES and FETCH_RETRY_DELAY (seconds) change curl's retries; the tests set them low.
set -uo pipefail

if [ $# -ne 2 ]; then
  echo "usage: fetch_extract.sh URL DIR" >&2
  exit 2
fi
url=$1
dir=$2
retries=${FETCH_RETRIES:-5}
delay=${FETCH_RETRY_DELAY:-10}

# curl's exit codes for "could not get through to the server": 6 name not resolved, 7 could
# not connect, 28 timed out, 35 TLS handshake failed, 52 empty reply, 55 send failed,
# 56 receive failed (a reset).
unreachable=" 6 7 28 35 52 55 56 "

fetch() {
  # --connect-timeout: a dropped SYN otherwise holds each try for minutes.
  curl -fL --connect-timeout 30 --retry "$retries" --retry-delay "$delay" --retry-all-errors "$@"
}

mkdir -p "$dir"
fetch -D "$dir/region.headers" -o "$dir/region.osm.pbf" "$url"
rc=$?
if [ "$rc" = 0 ]; then
  fetch -o "$dir/region.osm.pbf.md5" "$url.md5"
  rc=$?
fi
if [ "$rc" != 0 ]; then
  case "$unreachable" in
    *" $rc "*)
      echo "Could not reach the server for $url (curl exit $rc on every try)" >&2
      exit 75
      ;;
  esac
  echo "Downloading $url failed (curl exit $rc)" >&2
  exit "$rc"
fi

# Geofabrik's file names the extract by its published name; check it against ours.
(cd "$dir" && sed 's#  .*#  region.osm.pbf#' region.osm.pbf.md5 | md5sum -c -) || exit 1
ls -l "$dir/region.osm.pbf"
