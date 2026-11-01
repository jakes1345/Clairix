#!/usr/bin/env python3
"""Checks relays/latest.json before it is signed and published.

    python3 tools/relays/check_latest.py relays/latest.json [--gradle android/app/build.gradle.kts]
                                         [--published PATH] [--print-version-name]

latest.json tells phones which Clairix version is the newest and which is the
oldest still safe to use (relays/README.md). The app (android/.../comms/AppUpdate.kt)
parses the same format and refuses what this refuses; keep the two in step.

--gradle              the app's build file: versionCode and versionName must be
                      the ones it builds, so the note and the release agree.
--published           the latest.json now on the `relays` release: the new one
                      must be issued later, since phones refuse an older one.
--print-version-name  print versionName once the file checks (for the workflow).
"""
import argparse
import json
import re
import sys
import time

TYPE = "clairix-latest"
VERSION = 1
MAX_TEXT = 200
MAX_VERSION_NAME = 64
# The largest versionCode Android accepts.
MAX_VERSION_CODE = 2_100_000_000
FIELDS = {"type", "version", "issued", "versionCode", "versionName", "minSafeVersionCode", "notes"}
VERSION_NAME = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$")


class Invalid(Exception):
    pass


def _whole(doc, field, low, high):
    value = doc.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
        raise Invalid(f"{field} must be a whole number from {low} to {high}")
    return value


def check(doc, now=None):
    """Returns the note; raises Invalid with the reason otherwise."""
    now = int(time.time()) if now is None else now
    if not isinstance(doc, dict) or doc.get("type") != TYPE:
        raise Invalid(f"type must be {TYPE!r}")
    if doc.get("version") != VERSION or isinstance(doc.get("version"), bool):
        raise Invalid(f"version must be {VERSION}")
    unknown = set(doc) - FIELDS
    if unknown:
        raise Invalid(f"unknown field(s) {sorted(unknown)}")
    issued = doc.get("issued")
    if not isinstance(issued, int) or isinstance(issued, bool) or issued <= 0:
        raise Invalid("issued must be a positive whole number of seconds since 1970")
    if issued > now + 3600:
        raise Invalid("issued is in the future")
    code = _whole(doc, "versionCode", 1, MAX_VERSION_CODE)
    _whole(doc, "minSafeVersionCode", 1, code)
    name = doc.get("versionName")
    if not isinstance(name, str) or len(name) > MAX_VERSION_NAME or not VERSION_NAME.match(name):
        raise Invalid("versionName must be a version like 3.0.0 or 3.0.0-beta.8")
    notes = doc.get("notes")
    if not isinstance(notes, str) or not notes.strip() or len(notes) > MAX_TEXT or any(c in notes for c in "\r\n\t"):
        raise Invalid(f"notes must be one line of text, 1-{MAX_TEXT} characters")
    return doc


def gradle_version(text):
    """The versionCode and versionName android/app/build.gradle.kts builds, or Invalid."""
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    if not code or not name:
        raise Invalid("could not find versionCode and versionName in the build file")
    return int(code.group(1)), name.group(1)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("latest")
    ap.add_argument("--gradle")
    ap.add_argument("--published")
    ap.add_argument("--print-version-name", action="store_true")
    args = ap.parse_args(argv)
    try:
        with open(args.latest, "rb") as f:
            doc = json.loads(f.read().decode("utf-8"))
        check(doc)
        if args.gradle:
            with open(args.gradle, encoding="utf-8") as f:
                code, name = gradle_version(f.read())
            if (doc["versionCode"], doc["versionName"]) != (code, name):
                raise Invalid(
                    f"says {doc['versionName']} ({doc['versionCode']}) but the app builds {name} ({code}): "
                    "change both together"
                )
        if args.published:
            with open(args.published, "rb") as f:
                old = json.loads(f.read().decode("utf-8"))
            if doc["issued"] <= old.get("issued", 0):
                raise Invalid(f"issued ({doc['issued']}) must be later than the published note's ({old.get('issued')}): phones refuse an older one")
    except (Invalid, ValueError) as e:
        print(f"{args.latest}: {e}", file=sys.stderr)
        return 1
    if args.print_version_name:
        print(doc["versionName"])
    else:
        print(f"{args.latest}: newest {doc['versionName']} ({doc['versionCode']}), oldest safe {doc['minSafeVersionCode']}, issued {doc['issued']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
