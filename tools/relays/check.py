#!/usr/bin/env python3
"""Checks relays/relays.json before it is signed and published, and watches it.

    python3 tools/relays/check.py relays/relays.json [--published PATH] [--live | --watch]

The app (android/.../comms/RelayDirectory.kt) parses the same format and
refuses what this refuses; keep the two in step.

--published  the list now on the `relays` release: the new one must be issued
             later, since phones refuse a list older than the one they have.
--live       ask every listed relay for its key (GET /) and fail when one
             answers with a different key. A relay that does not answer is
             only reported: it may be down for an hour.
--watch      what the Relay watch workflow runs on a schedule: every listed
             relay must answer GET / as a Clairix relay, with the key (and the
             onion address, when one is listed) the list gives it. A relay that
             does not answer, or not like that, is asked twice more a little
             later and then fails the check.
"""
import argparse
import base64
import json
import sys
import time
import urllib.request

TYPE = "clairix-relay-list"
VERSION = 1
MAX_RELAYS = 64
MAX_TEXT = 200
FIELDS = {"url", "name", "country", "host", "operator", "key", "join", "onion"}
REQUIRED = {"url", "name", "country", "host", "operator", "key"}


class Invalid(Exception):
    pass


def _text(entry, field, required=True):
    value = entry.get(field)
    if value is None and not required:
        return None
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_TEXT or any(c in value for c in "\r\n\t"):
        raise Invalid(f"{field!r} must be one line of text, 1-{MAX_TEXT} characters")
    return value


def _origin(url, scheme):
    prefix = scheme + "://"
    if not url.startswith(prefix):
        raise Invalid(f"{url!r} must start with {prefix}")
    host = url[len(prefix):]
    if not host or "/" in host or "?" in host or "#" in host or "@" in host or host != host.lower():
        raise Invalid(f"{url!r} must be a bare lowercase origin, like {prefix}relay.example.org")
    return host


def check(doc, now=None):
    """Returns the list's relays; raises Invalid with the reason otherwise."""
    now = int(time.time()) if now is None else now
    if not isinstance(doc, dict) or doc.get("type") != TYPE:
        raise Invalid(f"type must be {TYPE!r}")
    if doc.get("version") != VERSION:
        raise Invalid(f"version must be {VERSION}")
    issued = doc.get("issued")
    if not isinstance(issued, int) or isinstance(issued, bool) or issued <= 0:
        raise Invalid("issued must be a positive whole number of seconds since 1970")
    if issued > now + 3600:
        raise Invalid("issued is in the future")
    relays = doc.get("relays")
    if not isinstance(relays, list) or not 1 <= len(relays) <= MAX_RELAYS:
        raise Invalid(f"relays must list 1-{MAX_RELAYS} relays")
    seen = set()
    for i, entry in enumerate(relays):
        where = f"relay {i + 1}"
        if not isinstance(entry, dict):
            raise Invalid(f"{where}: not an object")
        unknown = set(entry) - FIELDS
        if unknown:
            raise Invalid(f"{where}: unknown field(s) {sorted(unknown)}")
        missing = REQUIRED - set(entry)
        if missing:
            raise Invalid(f"{where}: missing {sorted(missing)}")
        try:
            for field in ("name", "country", "host", "operator"):
                _text(entry, field)
            _text(entry, "join", required=False)
            url = _text(entry, "url")
            _origin(url, "https")
            onion = _text(entry, "onion", required=False)
            if onion is not None:
                host = _origin(onion, "http")
                label = host[: -len(".onion")] if host.endswith(".onion") else ""
                if len(label) != 56 or any(c not in "abcdefghijklmnopqrstuvwxyz234567" for c in label):
                    raise Invalid(f"{onion!r} is not a v3 onion address")
            key = _text(entry, "key")
            try:
                raw = base64.b64decode(key, validate=True)
            except ValueError:
                raise Invalid("key is not base64")
            if len(raw) != 32:
                raise Invalid("key must be a 32-byte Ed25519 public key, as the relay's GET / publishes it")
        except Invalid as e:
            raise Invalid(f"{where}: {e}")
        if url in seen:
            raise Invalid(f"{where}: {url} is listed twice")
        seen.add(url)
    return relays


def live_info(url, timeout=20):
    """What the relay at [url] says about itself at GET /, as parsed JSON."""
    req = urllib.request.Request(url + "/", headers={"Accept": "application/json", "User-Agent": "clairix-relay-list-check"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def live_key(url, timeout=20):
    return live_info(url, timeout).get("relayKey")


def watch_problem(entry, fetch=live_info):
    """Why the listed relay [entry] is not answering as listed, or None when it is."""
    try:
        info = fetch(entry["url"])
    except Exception as e:  # noqa: BLE001 - any failure to reach it is the finding
        return f"did not answer ({e})"
    if not isinstance(info, dict) or info.get("service") != "clairix-comms" or info.get("ok") is not True:
        return "answered, but not as a Clairix relay"
    if info.get("relayKey") != entry["key"]:
        return f"publishes key {info.get('relayKey')}, the list says {entry['key']}"
    if "onion" in entry and info.get("onion") != entry["onion"]:
        return f"publishes onion address {info.get('onion')}, the list says {entry['onion']}"
    return None


def watch(relays, fetch=live_info, attempts=3, wait=15.0, sleep=time.sleep):
    """Each listed relay that does not answer as listed after [attempts] tries [wait] seconds apart, with why."""
    problems = {}
    for entry in relays:
        problem = None
        for attempt in range(attempts):
            problem = watch_problem(entry, fetch)
            if problem is None:
                break
            if attempt + 1 < attempts:
                sleep(wait)
        if problem is not None:
            problems[entry["url"]] = problem
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("list")
    ap.add_argument("--published")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--live", action="store_true")
    mode.add_argument("--watch", action="store_true")
    args = ap.parse_args(argv)
    with open(args.list, "rb") as f:
        doc = json.loads(f.read().decode("utf-8"))
    try:
        relays = check(doc)
        if args.published:
            with open(args.published, "rb") as f:
                old = json.loads(f.read().decode("utf-8"))
            if doc["issued"] <= old.get("issued", 0):
                raise Invalid(f"issued ({doc['issued']}) must be later than the published list's ({old.get('issued')}): phones refuse an older list")
    except Invalid as e:
        print(f"{args.list}: {e}", file=sys.stderr)
        return 1
    if args.watch:
        problems = watch(relays, fetch=lambda url: live_info(url, timeout=15))
        for entry in relays:
            problem = problems.get(entry["url"])
            if problem is None:
                print(f"{entry['url']}: answers with the listed key")
            else:
                print(f"{entry['url']}: {problem}", file=sys.stderr)
        return 1 if problems else 0
    failed = False
    if args.live:
        for entry in relays:
            try:
                key = live_key(entry["url"])
            except Exception as e:  # noqa: BLE001 - any failure to reach it is only reported
                print(f"warning: {entry['url']} did not answer ({e})")
                continue
            if key != entry["key"]:
                print(f"{entry['url']} publishes key {key}, the list says {entry['key']}", file=sys.stderr)
                failed = True
            else:
                print(f"{entry['url']}: key matches")
    if failed:
        return 1
    print(f"{args.list}: {len(relays)} relay(s), issued {doc['issued']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
