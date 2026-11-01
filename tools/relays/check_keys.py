#!/usr/bin/env python3
"""Checks relays/keys.json before it is published, and signatures against it.

    python3 tools/relays/check_keys.py relays/keys.json [--published PATH] [--verify-signed FILE...]

keys.json lists the keys the files phones download may be signed with: the
current one and the next, each with a revoked flag (relays/README.md). It is
signed with the root key, the one built into the app (android/asset-signing.pub),
and phones take it only with that signature; then they accept a relay list, a
version note, a map or any other published file signed by a listed key that is
not revoked. The app (android/.../nav/AssetKeys.kt) parses the same format and
refuses what this refuses; keep the two in step.

--published      the keys.json now on the `relays` release: the new one must be
                 issued later, since phones refuse an older one.
--verify-signed  check FILE.sig for each FILE against the keys this list
                 accepts, as a phone holding it would (needs `cryptography`).
"""
import argparse
import base64
import json
import re
import sys
import time

TYPE = "clairix-asset-keys"
VERSION = 1
MAX_KEYS = 8
ROLES = ("current", "next")
FIELDS = {"type", "version", "issued", "keys"}
KEY_FIELDS = {"key", "role", "revoked"}
# A raw 32-byte Ed25519 public key in standard base64, with or without its padding,
# as android/asset-signing.pub holds it.
KEY = re.compile(r"^[A-Za-z0-9+/]{43}=?$")


class Invalid(Exception):
    pass


def key_bytes(text):
    """The 32 bytes of a key as keys.json writes it, or Invalid."""
    if not isinstance(text, str) or not KEY.match(text):
        raise Invalid("key must be a 32-byte Ed25519 public key in base64, as android/asset-signing.pub holds it")
    raw = base64.b64decode(text.rstrip("=") + "=", validate=True)
    # Base64 forgives the spare bits of the last character; a key has one spelling.
    if base64.b64encode(raw).decode("ascii").rstrip("=") != text.rstrip("="):
        raise Invalid("key is not in its one base64 spelling")
    return raw


def check(doc, now=None):
    """Returns the list's entries; raises Invalid with the reason otherwise."""
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
    keys = doc.get("keys")
    if not isinstance(keys, list) or not 1 <= len(keys) <= MAX_KEYS:
        raise Invalid(f"keys must list 1-{MAX_KEYS} keys")
    seen = set()
    live = {role: 0 for role in ROLES}
    for i, entry in enumerate(keys):
        where = f"key {i + 1}"
        if not isinstance(entry, dict) or set(entry) != KEY_FIELDS:
            raise Invalid(f"{where}: must have exactly the fields {sorted(KEY_FIELDS)}")
        try:
            raw = key_bytes(entry["key"])
        except Invalid as e:
            raise Invalid(f"{where}: {e}")
        if entry["role"] not in ROLES:
            raise Invalid(f"{where}: role must be one of {list(ROLES)}")
        if not isinstance(entry["revoked"], bool):
            raise Invalid(f"{where}: revoked must be true or false")
        if raw in seen:
            raise Invalid(f"{where}: the same key is listed twice")
        seen.add(raw)
        if not entry["revoked"]:
            live[entry["role"]] += 1
    for role, count in live.items():
        if count > 1:
            raise Invalid(f"more than one {role} key that is not revoked")
    if sum(live.values()) == 0:
        raise Invalid("every key is revoked: phones holding this list would accept no published file")
    return keys


def accepted(doc):
    """The keys a phone holding [doc] accepts a signature from: every listed key not revoked."""
    return [entry["key"] for entry in doc["keys"] if not entry["revoked"]]


def verify_signed(doc, path):
    """Whether path.sig is a signature over path by a key [doc] accepts."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    with open(path, "rb") as f:
        data = f.read()
    with open(path + ".sig", "r", encoding="ascii") as f:
        text = f.read().strip()
    sig = base64.b64decode(text + "=" * (-len(text) % 4))
    for key in accepted(doc):
        try:
            Ed25519PublicKey.from_public_bytes(key_bytes(key)).verify(sig, data)
            return True
        except InvalidSignature:
            continue
    return False


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("keys")
    ap.add_argument("--published")
    ap.add_argument("--verify-signed", nargs="+", metavar="FILE")
    args = ap.parse_args(argv)
    try:
        with open(args.keys, "rb") as f:
            doc = json.loads(f.read().decode("utf-8"))
        check(doc)
        if args.published:
            with open(args.published, "rb") as f:
                old = json.loads(f.read().decode("utf-8"))
            if doc["issued"] <= old.get("issued", 0):
                raise Invalid(f"issued ({doc['issued']}) must be later than the published list's ({old.get('issued')}): phones refuse an older one")
    except (Invalid, ValueError) as e:
        print(f"{args.keys}: {e}", file=sys.stderr)
        return 1
    bad = [p for p in (args.verify_signed or []) if not verify_signed(doc, p)]
    for p in bad:
        print(f"{p}: not signed by any key {args.keys} accepts; phones holding it would refuse the file", file=sys.stderr)
    for p in args.verify_signed or []:
        if p not in bad:
            print(f"ok: {p}")
    if bad:
        return 1
    print(f"{args.keys}: {len(accepted(doc))} key(s) accepted of {len(doc['keys'])}, issued {doc['issued']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
