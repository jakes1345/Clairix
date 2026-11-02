#!/usr/bin/env python3
"""Publish the aggregator's files to one GitHub release, uploading only what changed.

    publish.py --tag workzones --name "Work zones" --body-file notes.md --prerelease dist/workzones-*

Every run of the work-zones workflow used to delete and re-upload all ~107 assets of the
`workzones` release: some 214 content-generating API requests every 15 minutes, more than
GitHub's secondary rate limit allows, so the runs failed and nothing was published. This
script compares the SHA-256 of each local file with the digest GitHub records for the asset
of the same name (and, for an asset recorded without one, with the SHA256SUMS.txt manifest
it keeps on the release), replaces only the assets that differ, paces its writes and backs
off when GitHub answers 403 or 429. The release is created on first use; its title and notes
are kept as given.

Needs GITHUB_TOKEN (or GH_TOKEN) with contents: write; the repository comes from --repo or
GITHUB_REPOSITORY. Exit 0 once every file is on the release in its current form, 1 when one
could not be published, 2 on bad usage.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

log = logging.getLogger("workzones.publish")

API_URL = "https://api.github.com"
API_VERSION = "2022-11-28"
USER_AGENT = "Clairix-workzones/1 (+https://github.com/jakes1345/Clairix)"
MANIFEST_NAME = "SHA256SUMS.txt"
# GitHub's secondary limit is 80 content-generating requests a minute and 500 an hour, and its
# guidance is one write at a time with a second between them, which keeps under the first. A
# 403 or 429 that is a rate limit is retried up to three times: after Retry-After or the
# rate-limit reset when GitHub gives one, else after one, two and four minutes. A limit that
# does not lift by then fails the run, which opens the alert issue, rather than being hammered.
# 5xx and dropped connections are retried after 5, 10 and 20 seconds.
MAX_ATTEMPTS = 4
BACKOFF_S = 5.0
RATE_LIMIT_WAIT_S = 60.0
MAX_WAIT_S = 300.0
WRITE_INTERVAL_S = 1.0
CONTENT_TYPES = {".gz": "application/gzip", ".json": "application/json", ".txt": "text/plain"}
TIMEOUT_S = 120
MAX_MANIFEST_BYTES = 1 << 20
WRITE_METHODS = ("POST", "PATCH", "PUT", "DELETE")
MANIFEST_LINE = re.compile(r"^([0-9a-f]{64}) [ *](\S.*)$")
NEXT_LINK = re.compile(r'<([^>]+)>;\s*rel="next"')


class PublishError(Exception):
    """A file could not be published; the caller fails the run so the failure is seen."""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Hands a 3xx back to the caller: an asset download redirects to object storage, which must
    not receive the API token (and refuses a request that carries it beside its own signature)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    """The few REST calls publishing needs, with retries and pacing."""

    def __init__(self, token: str | None, api: str = API_URL, max_attempts: int = MAX_ATTEMPTS,
                 backoff_s: float = BACKOFF_S, write_interval_s: float = WRITE_INTERVAL_S,
                 sleep=time.sleep, clock=time.time, timeout: float = TIMEOUT_S,
                 rate_limit_wait_s: float = RATE_LIMIT_WAIT_S):
        self.token = token
        self.api = api.rstrip("/")
        self.max_attempts = max(1, max_attempts)
        self.backoff_s = backoff_s
        self.rate_limit_wait_s = rate_limit_wait_s
        self.write_interval_s = write_interval_s
        self.sleep = sleep
        self.clock = clock
        self.timeout = timeout
        self.writes = 0
        self._last_write: float | None = None
        self._opener = urllib.request.build_opener(_NoRedirect)

    def request(self, method: str, url: str, data: bytes | None = None,
                headers: dict[str, str] | None = None, auth: bool = True) -> tuple[int, dict[str, str], bytes]:
        """(status, lower-cased headers, body). Rate limits (403/429), 5xx and transport errors
        are retried with backoff, honouring Retry-After and the rate-limit reset; any other 4xx
        and every 3xx are returned as they are."""
        if url.startswith("/"):
            url = self.api + url
        hdrs = {"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": API_VERSION}
        if auth and self.token:
            hdrs["Authorization"] = f"Bearer {self.token}"
        if headers:
            hdrs.update(headers)
        write = method in WRITE_METHODS
        last = ""
        wait = 0.0
        for attempt in range(1, self.max_attempts + 1):
            if write:
                self._pace()
            req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
            try:
                with self._opener.open(req, timeout=self.timeout) as resp:
                    body = resp.read()
                    if write:
                        self.writes += 1
                    return resp.status, _lower(resp.headers), body
            except urllib.error.HTTPError as e:
                body = e.read()
                hdr = _lower(e.headers)
                retry = self._wait_before_retry(e.code, hdr, body, attempt)
                if retry is None:
                    return e.code, hdr, body
                wait, last = retry, f"HTTP {e.code}"
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                wait, last = self._backoff(attempt), f"{type(e).__name__}: {getattr(e, 'reason', e)}"
            if attempt == self.max_attempts:
                break
            log.warning("%s %s: %s; retrying in %.0f s (%d of %d)", method, url, last, wait,
                        attempt, self.max_attempts - 1)
            self.sleep(wait)
        raise PublishError(f"{method} {url} failed after {self.max_attempts} attempt(s): {last}")

    def json(self, method: str, url: str, payload=None) -> tuple[int, object]:
        data = headers = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers = {"Content-Type": "application/json"}
        status, _, body = self.request(method, url, data, headers)
        return status, _parse_json(body)

    def _pace(self) -> None:
        if self._last_write is not None and self.write_interval_s > 0:
            due = self._last_write + self.write_interval_s - self.clock()
            if due > 0:
                self.sleep(due)
        self._last_write = self.clock()

    def _backoff(self, attempt: int) -> float:
        return min(self.backoff_s * (2 ** (attempt - 1)), MAX_WAIT_S)

    def _wait_before_retry(self, code: int, hdr: dict[str, str], body: bytes, attempt: int) -> float | None:
        """Seconds to wait before trying again, or None when the answer is final."""
        if code in (403, 429):
            if hdr.get("retry-after", "").strip().isdigit():
                wait = float(hdr["retry-after"])
            elif hdr.get("x-ratelimit-remaining") == "0" and hdr.get("x-ratelimit-reset", "").strip().isdigit():
                wait = float(hdr["x-ratelimit-reset"]) - self.clock()
            elif code == 429 or re.search(rb"rate limit|abuse", body, re.IGNORECASE):
                # Nothing to go by: GitHub asks for at least a minute, then exponentially longer.
                wait = self.rate_limit_wait_s * (2 ** (attempt - 1))
            else:
                return None  # a 403 that is not a rate limit (a token that cannot write) is final
            return min(max(wait, self._backoff(attempt)), MAX_WAIT_S)
        if code >= 500 or code == 408:
            return self._backoff(attempt)
        return None


def _lower(headers) -> dict[str, str]:
    return {k.lower(): v for k, v in headers.items()}


def _parse_json(body: bytes):
    if not body.strip():
        return None
    try:
        return json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def _message(obj) -> str:
    return str(obj.get("message", "")) if isinstance(obj, dict) else ""


def _norm(text) -> str:
    return str(text or "").replace("\r\n", "\n").strip()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_manifest(text: str) -> dict[str, str]:
    """name -> sha256 from sha256sum's format (two spaces, or space and asterisk, between them)."""
    out: dict[str, str] = {}
    for line in text.splitlines():
        m = MANIFEST_LINE.match(line.strip())
        if m:
            out[m.group(2)] = m.group(1)
    return out


def manifest_text(digests: dict[str, str]) -> str:
    return "".join(f"{digests[n]}  {n}\n" for n in sorted(digests))


def publish_order(name: str) -> tuple[int, str]:
    """State files first, the feeds file next, the index last: the index's "generated" says a run
    is published, so it goes up once the files it describes are there. A .sig sorts right after
    the file it signs."""
    if name.startswith("workzones-index"):
        rank = 2
    elif name.startswith("workzones-feeds"):
        rank = 1
    else:
        rank = 0
    return rank, name


def recorded_digest(asset: dict, manifest: dict[str, str] | None) -> str | None:
    """The SHA-256 GitHub records for an asset, else the manifest's entry for its name, else None.
    An asset whose upload never finished counts as unknown, so it is replaced."""
    if asset.get("state", "uploaded") != "uploaded":
        return None
    digest = asset.get("digest")
    if isinstance(digest, str) and digest.lower().startswith("sha256:"):
        return digest[7:].lower()
    if manifest:
        return manifest.get(str(asset.get("name", "")))
    return None


def next_page(headers: dict[str, str]) -> str | None:
    m = NEXT_LINK.search(headers.get("link", ""))
    return m.group(1) if m else None


def get_release(gh: GitHub, repo: str, tag: str) -> dict | None:
    status, obj = gh.json("GET", f"/repos/{repo}/releases/tags/{urllib.parse.quote(tag, safe='')}")
    if status == 404:
        return None
    if status != 200 or not isinstance(obj, dict):
        raise PublishError(f"could not read release {tag}: HTTP {status} {_message(obj)}")
    return obj


def create_release(gh: GitHub, repo: str, tag: str, name: str, body: str, prerelease: bool) -> dict:
    status, obj = gh.json("POST", f"/repos/{repo}/releases", {
        "tag_name": tag, "name": name, "body": body, "prerelease": prerelease, "make_latest": "false"})
    if status != 201 or not isinstance(obj, dict):
        raise PublishError(f"could not create release {tag}: HTTP {status} {_message(obj)}")
    return obj


def update_release(gh: GitHub, repo: str, release: dict, name: str, body: str, prerelease: bool) -> bool:
    """Brings the title, notes and pre-release flag in line: one write, and only when needed."""
    if (_norm(release.get("name")) == _norm(name) and _norm(release.get("body")) == _norm(body)
            and bool(release.get("prerelease")) == prerelease):
        return False
    status, obj = gh.json("PATCH", f"/repos/{repo}/releases/{release['id']}",
                          {"name": name, "body": body, "prerelease": prerelease})
    if status != 200:
        raise PublishError(f"could not update release {release.get('tag_name')}: HTTP {status} {_message(obj)}")
    return True


def list_assets(gh: GitHub, repo: str, release_id) -> dict[str, dict]:
    url: str | None = f"/repos/{repo}/releases/{release_id}/assets?per_page=100"
    out: dict[str, dict] = {}
    while url:
        status, hdr, body = gh.request("GET", url)
        page = _parse_json(body)
        if status != 200 or not isinstance(page, list):
            raise PublishError(f"could not list release assets: HTTP {status} {_message(page)}")
        for asset in page:
            if isinstance(asset, dict) and isinstance(asset.get("name"), str):
                out[asset["name"]] = asset
        url = next_page(hdr)
    return out


def download_asset(gh: GitHub, asset: dict, cap: int = MAX_MANIFEST_BYTES) -> bytes:
    """An asset's bytes. The API answers with a redirect to object storage, followed without the token."""
    status, hdr, body = gh.request("GET", asset["url"], headers={"Accept": "application/octet-stream"})
    if status in (301, 302, 303, 307, 308):
        location = hdr.get("location")
        if not location:
            raise PublishError(f"redirect without a location for {asset.get('name')}")
        status, hdr, body = gh.request("GET", location, headers={"Accept": "application/octet-stream"}, auth=False)
    if status != 200:
        raise PublishError(f"could not download {asset.get('name')}: HTTP {status}")
    if len(body) > cap:
        raise PublishError(f"{asset.get('name')} is larger than {cap} bytes")
    return body


def upload_url(release: dict) -> str:
    return str(release.get("upload_url", "")).split("{", 1)[0]


def content_type(name: str) -> str:
    """The type an asset is served with, by its last extension, as the release action that this
    replaces set it: a .json.gz is gzip, a .sig is opaque bytes."""
    return CONTENT_TYPES.get(os.path.splitext(name)[1].lower(), "application/octet-stream")


def upload_asset(gh: GitHub, release: dict, name: str, data: bytes) -> dict:
    url = upload_url(release) + "?" + urllib.parse.urlencode({"name": name})
    status, _, body = gh.request("POST", url, data, {"Content-Type": content_type(name),
                                                     "Content-Length": str(len(data))})
    obj = _parse_json(body)
    if status != 201 or not isinstance(obj, dict):
        raise PublishError(f"could not upload {name}: HTTP {status} {_message(obj)}")
    return obj


def delete_asset(gh: GitHub, repo: str, asset: dict) -> None:
    status, _, body = gh.request("DELETE", f"/repos/{repo}/releases/assets/{asset['id']}")
    if status not in (204, 404):
        raise PublishError(f"could not delete {asset.get('name')}: HTTP {status} {_message(_parse_json(body))}")


def publish(gh: GitHub, repo: str, tag: str, name: str, body: str, prerelease: bool,
            paths: list[str], manifest_name: str = MANIFEST_NAME) -> dict:
    """Puts every file on the release under its base name, replacing only the assets whose
    content differs, then the manifest. Returns what was done."""
    local: dict[str, str] = {}
    for p in paths:
        n = os.path.basename(p)
        if n in local:
            raise PublishError(f"two files are named {n}: {local[n]} and {p}")
        if n == manifest_name:
            raise PublishError(f"{manifest_name} is written by this script; do not pass it as a file")
        if not os.path.isfile(p):
            raise PublishError(f"{p}: not a file")
        local[n] = p
    if not local:
        raise PublishError("nothing to publish")

    release = get_release(gh, repo, tag)
    created = release is None
    if release is None:
        release = create_release(gh, repo, tag, name, body, prerelease)
        log.info("created release %s", tag)
    elif update_release(gh, repo, release, name, body, prerelease):
        log.info("updated the notes of release %s", tag)
    assets = list_assets(gh, repo, release["id"])
    digests = {n: sha256_file(p) for n, p in local.items()}

    # The manifest is only fetched when GitHub records no digest for an asset to be compared.
    manifest = None
    if any(recorded_digest(a, None) is None for n, a in assets.items() if n in digests):
        remote = assets.get(manifest_name)
        if remote is not None:
            manifest = parse_manifest(download_asset(gh, remote).decode("utf-8", "replace"))
            log.info("comparing assets without a recorded digest against %s (%d entries)",
                     manifest_name, len(manifest))

    uploaded: list[str] = []
    unchanged: list[str] = []
    for n in sorted(local, key=publish_order):
        existing = assets.get(n)
        if existing is not None and recorded_digest(existing, manifest) == digests[n]:
            unchanged.append(n)
            continue
        with open(local[n], "rb") as f:
            data = f.read()
        if existing is not None:
            delete_asset(gh, repo, existing)
        upload_asset(gh, release, n, data)
        uploaded.append(n)
        log.info("%s %s (%d bytes)", "replaced" if existing is not None else "uploaded", n, len(data))

    text = manifest_text(digests).encode("utf-8")
    existing = assets.get(manifest_name)
    manifest_uploaded = False
    if existing is None or recorded_digest(existing, None) != hashlib.sha256(text).hexdigest():
        if existing is not None:
            delete_asset(gh, repo, existing)
        upload_asset(gh, release, manifest_name, text)
        manifest_uploaded = True
    return {"release_id": release["id"], "created": created, "uploaded": uploaded,
            "unchanged": unchanged, "manifest_uploaded": manifest_uploaded, "writes": gh.writes}


def step_summary(tag: str, summary: dict) -> str:
    lines = [f"### Release `{tag}`", "",
             f"{'Created. ' if summary['created'] else ''}{len(summary['uploaded'])} file(s) uploaded, "
             f"{len(summary['unchanged'])} unchanged, {summary['writes']} write request(s)."]
    if summary["uploaded"]:
        lines += ["", "Uploaded: " + ", ".join(f"`{n}`" for n in summary["uploaded"])]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"),
                    help="owner/name (default: $GITHUB_REPOSITORY)")
    ap.add_argument("--tag", required=True, help="the release's tag; the release is created if it is missing")
    ap.add_argument("--name", help="release title (default: the tag)")
    ap.add_argument("--body-file", help="file holding the release notes (default: none)")
    ap.add_argument("--prerelease", action="store_true", help="mark the release as a pre-release")
    ap.add_argument("--manifest", default=MANIFEST_NAME,
                    help=f"name of the checksum manifest kept on the release (default {MANIFEST_NAME})")
    ap.add_argument("--api", default=os.environ.get("GITHUB_API_URL") or API_URL,
                    help="API base URL (default: $GITHUB_API_URL, else api.github.com)")
    ap.add_argument("--max-attempts", type=int, default=MAX_ATTEMPTS)
    ap.add_argument("--backoff", type=float, default=BACKOFF_S,
                    help="first retry wait in seconds, doubled each time (default %(default)s)")
    ap.add_argument("--write-interval", type=float, default=WRITE_INTERVAL_S,
                    help="seconds between content-generating requests (default %(default)s)")
    ap.add_argument("files", nargs="+")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stderr)

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        log.error("GITHUB_TOKEN (or GH_TOKEN) is not set; the release cannot be written")
        return 2
    if not args.repo or "/" not in args.repo:
        log.error("--repo owner/name (or GITHUB_REPOSITORY) is required")
        return 2
    body = ""
    if args.body_file:
        with open(args.body_file, "r", encoding="utf-8") as f:
            body = f.read()
    gh = GitHub(token, args.api, args.max_attempts, args.backoff, args.write_interval)
    try:
        summary = publish(gh, args.repo, args.tag, args.name or args.tag, body, args.prerelease,
                          args.files, args.manifest)
    except PublishError as e:
        log.error("%s", e)
        return 1
    log.info("release %s: %d uploaded, %d unchanged, %d write request(s)", args.tag,
             len(summary["uploaded"]), len(summary["unchanged"]), summary["writes"])
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(step_summary(args.tag, summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
