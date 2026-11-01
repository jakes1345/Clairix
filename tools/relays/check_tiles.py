#!/usr/bin/env python3
"""Checks relays/tiles.json, the map's tile server, before it is signed and published.

    python3 tools/relays/check_tiles.py relays/tiles.json [--published PATH]

The file names the server both maps draw their background from. The Relay
list workflow signs it with the asset key and publishes it on the `relays`
release next to relays.json, so the server can be changed without an app
update, as the OpenStreetMap tile usage policy asks. Phones fetch it once a
day, use it only if the signature checks, and fall back to OpenStreetMap's
own server while they hold none. The app (android/.../nav/TileConfig.kt)
parses the same format and refuses what this refuses; keep the two in step.

--published  the setting now on the `relays` release: the new one must be
             issued later, since phones refuse one older than they have.
"""
import argparse
import json
import sys
import time

TYPE = "clairix-tile-source"
VERSION = 1
MAX_TEXT = 200
MAX_URL = 512
MAX_ZOOM = 22
TILE_SIZES = (256, 512)
FIELDS = {"type", "version", "issued", "name", "url", "minZoom", "maxZoom", "tileSize", "attribution", "attributionUrl"}
PLACEHOLDERS = ("{z}", "{x}", "{y}")
# Every server the maps may draw from renders OpenStreetMap data, which the
# ODbL lets anyone use only with this credit on the map.
REQUIRED_CREDIT = "OpenStreetMap"


class Invalid(Exception):
    pass


def _text(doc, field):
    value = doc.get(field)
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_TEXT or any(c in value for c in "\r\n\t"):
        raise Invalid(f"{field!r} must be one line of text, 1-{MAX_TEXT} characters")
    return value


def _whole(doc, field, low, high):
    value = doc.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
        raise Invalid(f"{field!r} must be a whole number from {low} to {high}")
    return value


def _https(doc, field):
    """An https URL with a bare lowercase host, no credentials, no spaces, no fragment."""
    value = doc.get(field)
    if not isinstance(value, str) or not value or len(value) > MAX_URL:
        raise Invalid(f"{field!r} must be a URL of at most {MAX_URL} characters")
    if any(c.isspace() for c in value) or "#" in value:
        raise Invalid(f"{field!r} must have no spaces and no #fragment")
    if not value.startswith("https://"):
        raise Invalid(f"{field!r} must start with https://")
    rest = value[len("https://"):]
    host = rest.split("/", 1)[0].split("?", 1)[0]
    if not host or "@" in host or host != host.lower() or "{" in host or "}" in host:
        raise Invalid(f"{field!r} must name a lowercase host with no credentials or placeholders in it")
    if "/" not in rest:
        raise Invalid(f"{field!r} must have a path after the host")
    return value


def check(doc, now=None):
    """Returns the setting; raises Invalid with the reason otherwise."""
    now = int(time.time()) if now is None else now
    if not isinstance(doc, dict) or doc.get("type") != TYPE:
        raise Invalid(f"type must be {TYPE!r}")
    # type() rather than ==: 1.0 and True equal 1 in Python, and the app refuses both.
    if type(doc.get("version")) is not int or doc.get("version") != VERSION:
        raise Invalid(f"version must be {VERSION}")
    unknown = set(doc) - FIELDS
    if unknown:
        raise Invalid(f"unknown field(s) {sorted(unknown)}")
    missing = FIELDS - set(doc)
    if missing:
        raise Invalid(f"missing {sorted(missing)}")
    issued = doc.get("issued")
    if not isinstance(issued, int) or isinstance(issued, bool) or issued <= 0:
        raise Invalid("issued must be a positive whole number of seconds since 1970")
    if issued > now + 3600:
        raise Invalid("issued is in the future")
    _text(doc, "name")
    url = _https(doc, "url")
    for p in PLACEHOLDERS:
        if url.count(p) != 1:
            raise Invalid(f"url must contain {p} exactly once")
    stripped = url
    for p in PLACEHOLDERS:
        stripped = stripped.replace(p, "")
    if "{" in stripped or "}" in stripped:
        raise Invalid("url may hold no placeholder other than {z}, {x} and {y}")
    max_zoom = _whole(doc, "maxZoom", 1, MAX_ZOOM)
    _whole(doc, "minZoom", 0, max_zoom)
    if type(doc.get("tileSize")) is not int or doc.get("tileSize") not in TILE_SIZES:
        raise Invalid(f"tileSize must be one of {TILE_SIZES}")
    credit = _text(doc, "attribution")
    if REQUIRED_CREDIT not in credit:
        raise Invalid(f"attribution must credit {REQUIRED_CREDIT}: the map data is OpenStreetMap's, under the ODbL")
    _https(doc, "attributionUrl")
    return doc


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("setting")
    ap.add_argument("--published")
    args = ap.parse_args(argv)
    with open(args.setting, "rb") as f:
        doc = json.loads(f.read().decode("utf-8"))
    try:
        check(doc)
        if args.published:
            with open(args.published, "rb") as f:
                old = json.loads(f.read().decode("utf-8"))
            if doc["issued"] <= old.get("issued", 0):
                raise Invalid(f"issued ({doc['issued']}) must be later than the published setting's ({old.get('issued')}): phones refuse an older one")
    except Invalid as e:
        print(f"{args.setting}: {e}", file=sys.stderr)
        return 1
    print(f"{args.setting}: tiles from {doc['name']} ({doc['url']}), issued {doc['issued']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
