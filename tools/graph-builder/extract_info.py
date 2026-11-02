#!/usr/bin/env python3
"""What a road graph was built from, for its release notes.

usage:
  extract_info.py record --pbf FILE --md5 FILE --headers FILE --url URL --region PATH --out JSON
      Writes, as JSON, the Geofabrik extract's URL, the MD5 Geofabrik publishes beside it (the
      Maps workflow has already checked the download against it), the download's Last-Modified
      time and the OpenStreetMap replication timestamp from the extract's own header.
  extract_info.py notes JSON --slug SLUG --run-id ID --sha SHA
      Prints the maps-<slug> release notes with that record in them, so a published graph can be
      matched to the extract and the commit it was built from.

`notes` uses the standard library alone (the publishing job installs nothing); `record` needs
osmium, from tools/graph-builder/requirements.txt.
"""
from __future__ import annotations

import argparse
import json
import re
import sys


def read_md5(path: str) -> str:
    """The checksum from a Geofabrik .md5 file ("<md5>  <name>")."""
    with open(path, encoding="ascii") as f:
        fields = f.read().split()
    if not fields or not re.fullmatch(r"[0-9a-f]{32}", fields[0]):
        raise ValueError(f"{path} does not start with an MD5")
    return fields[0]


def last_modified(headers_path: str) -> str:
    """The Last-Modified header of the final response, or "". curl -D writes the headers of every
    response it followed, redirects included, each block starting with its status line."""
    value = ""
    with open(headers_path, encoding="latin-1") as f:
        for line in f:
            if line.startswith("HTTP/"):
                value = ""
                continue
            name, sep, rest = line.partition(":")
            if sep and name.strip().lower() == "last-modified":
                value = rest.strip()
    return value


def replication_timestamp(pbf_path: str) -> str:
    """The extract's osmosis_replication_timestamp: when its OpenStreetMap data was cut. "" if the
    file does not say."""
    import osmium

    reader = osmium.io.Reader(pbf_path, osmium.osm.osm_entity_bits.NOTHING)
    try:
        return reader.header().get("osmosis_replication_timestamp")
    finally:
        reader.close()


def record(pbf: str, md5: str, headers: str, url: str, region: str) -> dict:
    return {
        "region": region,
        "url": url,
        "md5": read_md5(md5),
        "last_modified": last_modified(headers),
        "osm_timestamp": replication_timestamp(pbf),
    }


def notes(info: dict, slug: str, run_id: str, sha: str) -> str:
    return "\n".join([
        f"Road graph (format v2) for `{info['region']}`, built from the Geofabrik extract",
        f"by run {run_id} of {sha}, with the plate readers (ALPR) mapped in the same",
        f"extract as cameras-{slug}.json.gz. Rebuilt daily. Map data (c) OpenStreetMap contributors, ODbL.",
        "",
        "The extract it was built from:",
        "",
        f"- {info['url']}",
        f"- OpenStreetMap data as of {info['osm_timestamp'] or '(not stated in the extract)'}",
        f"- published by Geofabrik {info['last_modified'] or '(no date given by the server)'}",
        f"- MD5 {info['md5']}, as Geofabrik publishes it beside the file; the download matched it",
        "",
        f"graph-{slug}.agr.gz.sha256.sig and cameras-{slug}.json.gz.sig are Ed25519 signatures by the asset key",
        "(android/asset-signing.pub). They show that this repository's Maps workflow published these files;",
        "they do not show that the extract is OpenStreetMap's, since its MD5 comes from the same server as the file.",
    ]) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Record what a road graph was built from, and write its release notes.")
    sub = ap.add_subparsers(dest="command", required=True)
    rec = sub.add_parser("record")
    rec.add_argument("--pbf", required=True)
    rec.add_argument("--md5", required=True)
    rec.add_argument("--headers", required=True)
    rec.add_argument("--url", required=True)
    rec.add_argument("--region", required=True)
    rec.add_argument("--out", required=True)
    nt = sub.add_parser("notes")
    nt.add_argument("info")
    nt.add_argument("--slug", required=True)
    nt.add_argument("--run-id", required=True)
    nt.add_argument("--sha", required=True)
    args = ap.parse_args(argv)
    if args.command == "record":
        info = record(args.pbf, args.md5, args.headers, args.url, args.region)
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(info, f, indent=1, sort_keys=True)
            f.write("\n")
        print(json.dumps(info, sort_keys=True))
        return 0
    with open(args.info, encoding="utf-8") as f:
        info = json.load(f)
    sys.stdout.write(notes(info, args.slug, args.run_id, args.sha))
    return 0


if __name__ == "__main__":
    sys.exit(main())
