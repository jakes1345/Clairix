#!/usr/bin/env python3
"""Extract the automatic licence plate readers (ALPR) mapped in an OSM extract.

Flock Safety, Motorola/Vigilant and other plate readers are mapped in
OpenStreetMap, largely by the DeFlock project, as nodes tagged
man_made=surveillance + surveillance:type=ALPR (see the OSM wiki page for
that tag). This reads the same extract the road graph is built from and
writes a small, signed-later file the phone shows on the map, warns about on
the road and can route around.

usage: extract_cameras.py <input.osm.pbf|.osm> <output.json.gz> --region <slug>

The output is gzip JSON:
    {"version": 1, "slug": ..., "built": <epoch>, "attribution": ...,
     "cameras": [{"id": "n123", "lat": .., "lon": .., "kind": "alpr",
                  "zone": "traffic", "direction": 90, "mount": "pole",
                  "manufacturer": "Flock Safety", "model": "Falcon",
                  "operator": "..."}, ...]}
Only keys with a value are written. Written deterministically (sorted by id,
gzip without a timestamp) so two builds of one extract are the same file.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import time

import osmium

FORMAT_VERSION = 1
ATTRIBUTION = "(c) OpenStreetMap contributors, ODbL 1.0"
MAX_TEXT = 120
# Values of surveillance:type (or its common misspellings on other keys) that mean a plate reader.
ALPR_VALUES = {"alpr", "anpr", "lpr", "license_plate_reader", "licence_plate_reader", "license_plate_scanner"}
ZONES = {"traffic", "parking", "entrance", "town", "building", "shop", "street", "public"}
MOUNTS = {"pole", "wall", "ceiling", "street_lamp", "traffic_signals", "building", "gantry", "bridge", "tree"}
CARDINAL = {
    "n": 0, "north": 0, "nne": 22, "ne": 45, "northeast": 45, "ene": 67, "e": 90, "east": 90, "ese": 112,
    "se": 135, "southeast": 135, "sse": 157, "s": 180, "south": 180, "ssw": 202, "sw": 225, "southwest": 225,
    "wsw": 247, "w": 270, "west": 270, "wnw": 292, "nw": 315, "northwest": 315, "nnw": 337,
}
_NUM_RE = re.compile(r"^\s*(-?[0-9]+(?:\.[0-9]+)?)\s*°?\s*$")
_RANGE_RE = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*-\s*([0-9]+(?:\.[0-9]+)?)\s*$")


def is_alpr(tags) -> bool:
    """True for a plate reader however it was tagged, false for every other camera."""
    if tags.get("man_made") != "surveillance":
        return False
    for key in ("surveillance:type", "camera:type", "surveillance"):
        v = tags.get(key)
        if v and v.strip().lower() in ALPR_VALUES:
            return True
    return False


def parse_direction(value: str | None) -> int | None:
    """Degrees clockwise from north, or None: numbers, a range's midpoint, or a compass point."""
    if not value:
        return None
    v = value.strip().lower()
    if not v:
        return None
    m = _NUM_RE.match(v)
    if m:
        return int(round(float(m.group(1)))) % 360
    m = _RANGE_RE.match(v)
    if m:
        a, b = float(m.group(1)), float(m.group(2))
        if b < a:
            b += 360.0
        return int(round((a + b) / 2.0)) % 360
    first = re.split(r"[;,/ ]", v)[0]
    return CARDINAL.get(first)


def text(tags, key: str) -> str | None:
    v = tags.get(key)
    if not v:
        return None
    v = " ".join(v.split())
    return v[:MAX_TEXT] if v else None


def camera_record(node) -> dict | None:
    """The record for one ALPR node, or None when it has no usable location."""
    loc = node.location
    if not loc.valid():
        return None
    tags = node.tags
    rec: dict = {
        "id": f"n{node.id}",
        "lat": round(loc.lat, 7),
        "lon": round(loc.lon, 7),
        "kind": "alpr",
    }
    zone = text(tags, "surveillance:zone")
    if zone and zone.lower() in ZONES:
        rec["zone"] = zone.lower()
    direction = parse_direction(tags.get("direction") or tags.get("camera:direction"))
    if direction is not None:
        rec["direction"] = direction
    mount = text(tags, "camera:mount")
    if mount and mount.lower() in MOUNTS:
        rec["mount"] = mount.lower()
    for key in ("manufacturer", "model", "operator"):
        v = text(tags, key)
        if v:
            rec[key] = v
    # The wiki lists brand=Flock Safety as a common mistake for the maker; keep the information.
    if "manufacturer" not in rec:
        brand = text(tags, "brand")
        if brand:
            rec["manufacturer"] = brand
    return rec


def extract(path: str) -> list[dict]:
    fp = osmium.FileProcessor(path, osmium.osm.NODE).with_filter(osmium.filter.KeyFilter("man_made"))
    out: list[dict] = []
    for node in fp:
        if not is_alpr(node.tags):
            continue
        rec = camera_record(node)
        if rec is not None:
            out.append(rec)
    out.sort(key=lambda r: int(r["id"][1:]))
    return out


def write(records: list[dict], slug: str, built_at: int, path: str) -> int:
    doc = {
        "version": FORMAT_VERSION,
        "slug": slug,
        "built": built_at,
        "attribution": ATTRIBUTION,
        "cameras": records,
    }
    data = json.dumps(doc, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    tmp = path + ".tmp"
    try:
        with open(tmp, "wb") as f:
            # No time and no file name in the gzip header, so the file depends on the data alone.
            with gzip.GzipFile(filename="", fileobj=f, mode="wb", compresslevel=9, mtime=0) as gz:
                gz.write(data)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise
    return len(data)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Extract the plate readers (ALPR) mapped in an OSM extract.")
    ap.add_argument("input", help=".osm.pbf or .osm file")
    ap.add_argument("output", help="output .json.gz path")
    ap.add_argument("--region", required=True, help="region slug, e.g. north-america-us-ohio")
    args = ap.parse_args(argv)
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", args.region):
        ap.error("--region must be a slug like north-america-us-ohio")
    t0 = time.time()
    records = extract(args.input)
    built_at = int(os.environ.get("SOURCE_DATE_EPOCH", time.time()))
    raw = write(records, args.region, built_at, args.output)
    print(f"cameras={len(records)} json_bytes={raw} gz_bytes={os.path.getsize(args.output)} in {time.time() - t0:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
