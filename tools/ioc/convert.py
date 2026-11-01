#!/usr/bin/env python3
"""Converts the Echap stalkerware indicators into the list Clairix matches installed apps against.

    convert.py --url URL --out ioc.json.gz          fetch ioc.yaml from URL and convert it
    convert.py --yaml FILE --out ioc.json.gz        convert a local copy of ioc.yaml
    convert.py --check FILE                         validate a converted list; standard library only
    convert.py --same A B                           exit 0 when A and B list the same indicators, 1 when not

The source is ioc.yaml from https://github.com/AssoEchap/stalkerware-indicators, maintained by
Julien Voisin and Tek for the Echap non-profit and published under CC BY 4.0 (see
licenses/CC-BY-4.0-stalkerware-indicators.txt). The converted list keeps three things from it and
nothing else: each app's names, its Android package names, and the fingerprints of the
certificates its APKs are signed with. The source gives SHA-1 fingerprints (40 hex digits); a
64-digit SHA-256 one would be kept as well. Websites and command-and-control addresses are left
out, since the app matches installed apps, not traffic, and so are apps with neither a package
name nor a certificate.

The format, gzip of UTF-8 JSON with sorted keys and no whitespace, is read by
android/app/src/main/java/app/clairix/analysis/StalkwareIoc.kt, which refuses what --check
refuses (keep the two in step):

    {"type": "clairix-stalkerware-ioc", "version": 1, "issued": <unix seconds>,
     "source": "<where ioc.yaml came from>", "licence": "CC BY 4.0",
     "names": [["TheTruthSpy", "Copy9", ...], ...],   one entry per app, its main name first
     "packages": {"com.example.spy": 0, ...},           package name -> index into names
     "certs": {"31A6...1C": 0, ...}}                    uppercase hex fingerprint -> index into names

A package or certificate the source lists under two apps goes to the first, and the second app's
name is added to the first one's names, so it is still shown. The gzip header
carries no file name and no time, so the same indicators converted at the same "issued" give the
same bytes; --same compares the indicators alone, so the workflow publishes only when they change.
The copy built into the app is android/app/src/suite/assets/ioc.json.gz, made with --out.
"""
import argparse
import gzip
import io
import json
import re
import sys
import time
import urllib.request

TYPE = "clairix-stalkerware-ioc"
VERSION = 1
LICENCE = "CC BY 4.0"
DEFAULT_URL = "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"

# The app refuses anything larger; see StalkwareIoc.kt.
MAX_FAMILIES = 2000
MAX_NAMES = 50
MAX_TEXT = 100
MAX_ENTRIES = 50000
MAX_PACKAGE = 255
MAX_SOURCE_BYTES = 16 * 1024 * 1024
MAX_JSON_BYTES = 8 * 1024 * 1024

# Below these the source has most likely moved or broken, not shrunk: the list on 2026-11-01 had
# 131 apps with a package or certificate, 615 package names and 473 certificates.
MIN_FAMILIES = 80
MIN_PACKAGES = 350
MIN_CERTS = 250

PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
DIGEST = re.compile(r"^(?:[0-9A-F]{40}|[0-9A-F]{64})$")
FIELDS = {"type", "version", "issued", "source", "licence", "names", "packages", "certs"}


class Invalid(Exception):
    pass


def _name(value):
    if not isinstance(value, str):
        return None
    value = value.strip()
    if not value or len(value) > MAX_TEXT or any(c in value for c in "\r\n\t"):
        return None
    return value


def convert(entries, issued, source, warn=lambda message: None, floors=True):
    """The converted list for [entries], ioc.yaml as parsed: a list of one mapping per app."""
    if not isinstance(entries, list):
        raise Invalid("ioc.yaml must be a list of apps")
    names = []
    packages = {}
    certs = {}

    def also_known_as(index, name):
        if name not in names[index] and len(names[index]) < MAX_NAMES:
            names[index].append(name)

    for number, entry in enumerate(entries, 1):
        if not isinstance(entry, dict):
            raise Invalid(f"entry {number} is not a mapping")
        main = _name(entry.get("name"))
        if main is None:
            raise Invalid(f"entry {number} has no usable name")
        if entry.get("type", "stalkerware") != "stalkerware":
            warn(f"{main}: type {entry.get('type')!r} is not stalkerware, left out")
            continue
        aliases = []
        for alias in entry.get("names") or []:
            alias = _name(alias)
            if alias is not None and alias != main and alias not in aliases:
                aliases.append(alias)
        own_packages = []
        for package in entry.get("packages") or []:
            if not isinstance(package, str) or len(package) > MAX_PACKAGE or not PACKAGE.match(package):
                warn(f"{main}: package {package!r} is not an Android package name, left out")
                continue
            if package in packages:
                warn(f"{main}: package {package} is already listed under {names[packages[package]][0]}")
                also_known_as(packages[package], main)
                continue
            if package not in own_packages:
                own_packages.append(package)
        own_certs = []
        for cert in entry.get("certificates") or []:
            digest = cert.strip().replace(":", "").upper() if isinstance(cert, str) else None
            if digest is None or not DIGEST.match(digest):
                warn(f"{main}: certificate {cert!r} is not a SHA-1 or SHA-256 fingerprint, left out")
                continue
            if digest in certs:
                warn(f"{main}: certificate {digest} is already listed under {names[certs[digest]][0]}")
                also_known_as(certs[digest], main)
                continue
            if digest not in own_certs:
                own_certs.append(digest)
        if not own_packages and not own_certs:
            continue
        index = len(names)
        names.append([main] + aliases[: MAX_NAMES - 1])
        for package in own_packages:
            packages[package] = index
        for digest in own_certs:
            certs[digest] = index
    doc = {
        "type": TYPE,
        "version": VERSION,
        "issued": int(issued),
        "source": source,
        "licence": LICENCE,
        "names": names,
        "packages": packages,
        "certs": certs,
    }
    validate(doc, floors=floors)
    return doc


def validate(doc, floors=True):
    """Raises Invalid unless [doc] is a list the app accepts; with [floors], also unless it is plausibly whole."""
    if not isinstance(doc, dict):
        raise Invalid("not a JSON object")
    unknown = set(doc) - FIELDS
    if unknown:
        raise Invalid(f"unknown field(s) {sorted(unknown)}")
    if doc.get("type") != TYPE:
        raise Invalid("not a Clairix stalkerware list")
    if doc.get("version") != VERSION or isinstance(doc.get("version"), bool):
        raise Invalid(f"version {doc.get('version')!r} is not {VERSION}")
    issued = doc.get("issued")
    if not isinstance(issued, int) or isinstance(issued, bool) or issued <= 0:
        raise Invalid("issued must be a positive whole number")
    for field in ("source", "licence"):
        if not isinstance(doc.get(field), str) or len(doc[field]) > 500:
            raise Invalid(f"{field} must be text")
    names = doc.get("names")
    if not isinstance(names, list) or not 1 <= len(names) <= MAX_FAMILIES:
        raise Invalid(f"names must list 1-{MAX_FAMILIES} apps")
    for i, family in enumerate(names):
        if not isinstance(family, list) or not 1 <= len(family) <= MAX_NAMES:
            raise Invalid(f"names[{i}] must list 1-{MAX_NAMES} names")
        for value in family:
            if _name(value) != value:
                raise Invalid(f"names[{i}] holds {value!r}, which is not one line of 1-{MAX_TEXT} characters")
    for field, pattern, what in (("packages", PACKAGE, "an Android package name"), ("certs", DIGEST, "an uppercase hex fingerprint")):
        table = doc.get(field)
        if not isinstance(table, dict) or len(table) > MAX_ENTRIES:
            raise Invalid(f"{field} must map at most {MAX_ENTRIES} keys to apps")
        for key, index in table.items():
            if len(key) > MAX_PACKAGE or not pattern.match(key):
                raise Invalid(f"{field}: {key!r} is not {what}")
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(names):
                raise Invalid(f"{field}: {key} points at no app")
    if not doc["packages"]:
        raise Invalid("packages is empty")
    if floors:
        counts = (len(names), len(doc["packages"]), len(doc["certs"]))
        if counts[0] < MIN_FAMILIES or counts[1] < MIN_PACKAGES or counts[2] < MIN_CERTS:
            raise Invalid(
                f"only {counts[0]} apps, {counts[1]} packages and {counts[2]} certificates "
                f"(at least {MIN_FAMILIES}, {MIN_PACKAGES} and {MIN_CERTS} expected): the source has probably moved or broken"
            )
    return doc


def encode(doc):
    """The gzip bytes of [doc]: sorted keys, no whitespace, no name or time in the gzip header."""
    raw = json.dumps(doc, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    out = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=out, mtime=0, compresslevel=9) as gz:
        gz.write(raw)
    return out.getvalue()


def decode(data):
    """The list in gzip [data], read the way the app reads it: capped, then validated without floors."""
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(data), mode="rb") as gz:
            raw = gz.read(MAX_JSON_BYTES + 1)
    except (OSError, EOFError) as e:
        raise Invalid(f"not gzip: {e}")
    if len(raw) > MAX_JSON_BYTES:
        raise Invalid(f"larger than {MAX_JSON_BYTES} bytes")
    try:
        doc = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as e:
        raise Invalid(f"not JSON: {e}")
    return validate(doc, floors=False)


def indicators(doc):
    """What [doc] says, apart from when and where: each package and certificate with the names it maps to."""
    names = doc["names"]
    return (
        {key: tuple(names[i]) for key, i in doc["packages"].items()},
        {key: tuple(names[i]) for key, i in doc["certs"].items()},
    )


def fetch(url):
    if not url.startswith("https://"):
        raise Invalid(f"{url} is not an https URL")
    request = urllib.request.Request(url, headers={"User-Agent": "clairix-ioc-convert (+https://github.com/jakes1345/Clairix)"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(MAX_SOURCE_BYTES + 1)
    if len(data) > MAX_SOURCE_BYTES:
        raise Invalid(f"{url} is larger than {MAX_SOURCE_BYTES} bytes")
    return data


def parse_yaml(data):
    import yaml  # Only converting needs PyYAML (tools/ioc/requirements.txt); --check and --same do not.

    try:
        return yaml.safe_load(data.decode("utf-8"))
    except (UnicodeDecodeError, yaml.YAMLError) as e:
        raise Invalid(f"ioc.yaml does not parse: {e}")


def read(path):
    with open(path, "rb") as f:
        return f.read()


def main(argv=None):
    ap = argparse.ArgumentParser(description="Convert the Echap stalkerware indicators for Clairix.")
    group = ap.add_mutually_exclusive_group(required=True)
    group.add_argument("--url", help=f"fetch ioc.yaml from here (the source is {DEFAULT_URL})")
    group.add_argument("--yaml", metavar="FILE", help="convert a local copy of ioc.yaml")
    group.add_argument("--check", metavar="FILE", help="validate a converted list")
    group.add_argument("--same", nargs=2, metavar=("A", "B"), help="exit 0 when A and B list the same indicators, 1 when not")
    ap.add_argument("--out", metavar="FILE", help="where to write the converted list (with --url or --yaml)")
    ap.add_argument("--issued", type=int, help="unix seconds to issue the list at (default: now)")
    ap.add_argument("--source", help="the source recorded in the list (default: the URL or file given)")
    args = ap.parse_args(argv)
    try:
        if args.check:
            doc = validate(decode(read(args.check)))
            print(f"ok: {args.check}: {len(doc['names'])} apps, {len(doc['packages'])} packages, {len(doc['certs'])} certificates, issued {doc['issued']}")
            return 0
        if args.same:
            same = indicators(decode(read(args.same[0]))) == indicators(decode(read(args.same[1])))
            print("same indicators" if same else "the indicators differ")
            return 0 if same else 1
        if not args.out:
            ap.error("--out is required with --url or --yaml")
        data = fetch(args.url) if args.url else read(args.yaml)
        source = args.source or (args.url if args.url else DEFAULT_URL)
        issued = args.issued if args.issued is not None else int(time.time())
        doc = convert(parse_yaml(data), issued, source, warn=lambda message: print(f"warning: {message}", file=sys.stderr))
        encoded = encode(doc)
        decode(encoded)
        with open(args.out, "wb") as f:
            f.write(encoded)
        print(f"wrote {args.out}: {len(doc['names'])} apps, {len(doc['packages'])} packages, {len(doc['certs'])} certificates, issued {doc['issued']}")
        return 0
    except Invalid as e:
        print(f"refused: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
