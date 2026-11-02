import hashlib
import http.server
import json
import os
import threading
import urllib.parse

import pytest

import publish as pub

REPO = "jakes1345/Clairix"
TAG = "workzones"


class FakeGitHub:
    """Enough of api.github.com (a release by tag, its assets with pagination, uploads, deletes
    and asset downloads through a redirect) to publish against on 127.0.0.1, with knobs for
    rate limiting, assets recorded without a digest and the page size."""

    def __init__(self):
        self.releases: dict[int, dict] = {}
        self.assets: dict[int, dict] = {}
        self.next_id = 100
        self.page_size = 100
        self.digests = True
        self.fail_writes: list[tuple] = []
        self.fail_all_writes: tuple | None = None
        self.requests: list[tuple[str, str]] = []
        self.download_auth: list[bool] = []
        self.upload_types: dict[str, str] = {}
        srv = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, obj=None, headers=None, raw=None):
                body = raw if raw is not None else (json.dumps(obj).encode() if obj is not None else b"")
                self.send_response(status)
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                if obj is not None:
                    self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):  # noqa: N802
                srv.handle(self, "GET")

            def do_POST(self):  # noqa: N802
                srv.handle(self, "POST")

            def do_PATCH(self):  # noqa: N802
                srv.handle(self, "PATCH")

            def do_DELETE(self):  # noqa: N802
                srv.handle(self, "DELETE")

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    # -- state helpers --

    def _new_id(self) -> int:
        self.next_id += 1
        return self.next_id

    def release_json(self, rel: dict) -> dict:
        return dict(rel, upload_url=f"{self.base}/uploads/repos/{REPO}/releases/{rel['id']}/assets{{?name,label}}",
                    assets=[self.asset_json(a) for a in self.assets_of(rel["id"])])

    def assets_of(self, release_id: int) -> list[dict]:
        return sorted((a for a in self.assets.values() if a["release"] == release_id), key=lambda a: a["id"])

    def asset_json(self, a: dict) -> dict:
        digest = f"sha256:{hashlib.sha256(a['data']).hexdigest()}" if self.digests and a["state"] == "uploaded" else None
        return {"id": a["id"], "name": a["name"], "size": len(a["data"]), "state": a["state"], "digest": digest,
                "url": f"{self.base}/repos/{REPO}/releases/assets/{a['id']}",
                "browser_download_url": f"{self.base}/download/{a['id']}"}

    def asset_named(self, name: str) -> dict | None:
        return next((a for a in self.assets.values() if a["name"] == name), None)

    def add_asset(self, release_id: int, name: str, data: bytes, state: str = "uploaded") -> dict:
        a = {"id": self._new_id(), "release": release_id, "name": name, "data": data, "state": state}
        self.assets[a["id"]] = a
        return a

    # -- routing --

    def handle(self, h, method: str):
        u = urllib.parse.urlsplit(h.path)
        q = urllib.parse.parse_qs(u.query)
        parts = u.path.strip("/").split("/")
        self.requests.append((method, u.path))
        length = int(h.headers.get("Content-Length") or 0)
        body = h.rfile.read(length) if length else b""
        if method != "GET":
            canned = self.fail_all_writes if self.fail_all_writes is not None else (
                self.fail_writes.pop(0) if self.fail_writes else None)
            if canned is not None:
                status, headers, raw = canned
                return h.reply(status, headers=headers, raw=raw)

        if parts[:1] == ["uploads"] and method == "POST":
            release_id = int(parts[5])
            name = q.get("name", [""])[0]
            if any(a["name"] == name for a in self.assets_of(release_id)):
                return h.reply(422, {"message": "Validation Failed", "errors": [{"code": "already_exists"}]})
            self.upload_types[name] = h.headers.get("Content-Type", "")
            return h.reply(201, self.asset_json(self.add_asset(release_id, name, body)))

        if parts[:1] == ["download"] and method == "GET":
            self.download_auth.append(any(k.lower() == "authorization" for k in h.headers.keys()))
            a = self.assets.get(int(parts[1]))
            return h.reply(200, raw=a["data"]) if a else h.reply(404, raw=b"")

        if parts[:4] == ["repos", "jakes1345", "Clairix", "releases"]:
            rest = parts[4:]
            if rest[:1] == ["tags"] and method == "GET":
                rel = next((r for r in self.releases.values() if r["tag_name"] == rest[1]), None)
                return h.reply(200, self.release_json(rel)) if rel else h.reply(404, {"message": "Not Found"})
            if not rest and method == "POST":
                payload = json.loads(body)
                rel = {"id": self._new_id(), "tag_name": payload["tag_name"], "name": payload.get("name"),
                       "body": payload.get("body", ""), "prerelease": bool(payload.get("prerelease"))}
                self.releases[rel["id"]] = rel
                return h.reply(201, self.release_json(rel))
            if rest[:1] == ["assets"]:
                a = self.assets.get(int(rest[1]))
                if a is None:
                    return h.reply(404, {"message": "Not Found"})
                if method == "DELETE":
                    del self.assets[a["id"]]
                    return h.reply(204, raw=b"")
                if "application/octet-stream" in h.headers.get("Accept", ""):
                    return h.reply(302, raw=b"", headers={"Location": f"{self.base}/download/{a['id']}"})
                return h.reply(200, self.asset_json(a))
            rel = self.releases.get(int(rest[0]))
            if rel is None:
                return h.reply(404, {"message": "Not Found"})
            if len(rest) == 1 and method == "PATCH":
                rel.update({k: v for k, v in json.loads(body).items() if k in ("name", "body", "prerelease")})
                return h.reply(200, self.release_json(rel))
            if rest[1:] == ["assets"] and method == "GET":
                page = int(q.get("page", ["1"])[0])
                items = self.assets_of(rel["id"])
                chunk = items[(page - 1) * self.page_size: page * self.page_size]
                headers = {}
                if page * self.page_size < len(items):
                    headers["Link"] = (f'<{self.base}/repos/{REPO}/releases/{rel["id"]}/assets?per_page=100&page={page + 1}>; '
                                       f'rel="next", <{self.base}/x?page=1>; rel="first"')
                return h.reply(200, [self.asset_json(a) for a in chunk], headers)
        return h.reply(404, {"message": f"no route for {method} {u.path}"})

    def writes(self) -> list[tuple[str, str]]:
        return [(m, p) for m, p in self.requests if m != "GET"]


@pytest.fixture
def gh(monkeypatch):
    # The runner may set an HTTP proxy, which must not see requests to this local server.
    monkeypatch.setenv("no_proxy", "127.0.0.1,localhost")
    monkeypatch.setenv("NO_PROXY", "127.0.0.1,localhost")
    server = FakeGitHub()
    yield server
    server.close()


def client(server: FakeGitHub, max_attempts: int = 4, backoff_s: float = 0.0, rate_limit_wait_s: float = 0.0):
    sleeps: list[float] = []
    c = pub.GitHub("t0ken", server.base, max_attempts=max_attempts, backoff_s=backoff_s,
                   write_interval_s=0.0, sleep=sleeps.append, rate_limit_wait_s=rate_limit_wait_s)
    return c, sleeps


def write_files(tmp_path, **contents) -> list[str]:
    paths = []
    for name, data in contents.items():
        p = tmp_path / name.replace("__", "-").replace("_", ".")
        p.write_bytes(data)
        paths.append(str(p))
    return paths


def sample(tmp_path, feeds=b"feeds-1", ohio=b"ohio-1") -> list[str]:
    """Two state files with signatures, the feeds file and the index, as the workflow passes them."""
    return write_files(
        tmp_path,
        workzones__north__america__us__ohio_json_gz=ohio,
        workzones__north__america__us__ohio_json_gz_sig=b"sig-" + ohio,
        workzones__north__america__us__texas_json_gz=b"texas-1",
        workzones__north__america__us__texas_json_gz_sig=b"sig-texas-1",
        workzones__feeds_json_gz=feeds,
        workzones__feeds_json_gz_sig=b"sig-" + feeds,
        workzones__index_json=b'{"generated": 1}',
        workzones__index_json_sig=b"sig-index",
    )


def names(paths) -> set[str]:
    return {os.path.basename(p) for p in paths}


def test_first_run_creates_the_release_and_uploads_in_order(gh, tmp_path):
    c, sleeps = client(gh)
    paths = sample(tmp_path)
    out = pub.publish(c, REPO, TAG, "Work zones", "notes\n", True, paths)
    assert out["created"] and out["unchanged"] == [] and out["manifest_uploaded"]
    # State files, then the feeds file, then the index; each signature right after its file.
    assert out["uploaded"] == [
        "workzones-north-america-us-ohio.json.gz", "workzones-north-america-us-ohio.json.gz.sig",
        "workzones-north-america-us-texas.json.gz", "workzones-north-america-us-texas.json.gz.sig",
        "workzones-feeds.json.gz", "workzones-feeds.json.gz.sig",
        "workzones-index.json", "workzones-index.json.sig",
    ]
    rel = next(iter(gh.releases.values()))
    assert rel["tag_name"] == TAG and rel["name"] == "Work zones" and rel["body"] == "notes\n" and rel["prerelease"]
    assert {a["name"] for a in gh.assets_of(rel["id"])} == names(paths) | {"SHA256SUMS.txt"}
    # Served as the release action that this replaces served them.
    assert gh.upload_types["workzones-north-america-us-ohio.json.gz"] == "application/gzip"
    assert gh.upload_types["workzones-north-america-us-ohio.json.gz.sig"] == "application/octet-stream"
    assert gh.upload_types["workzones-index.json"] == "application/json"
    assert gh.upload_types["SHA256SUMS.txt"] == "text/plain"
    manifest = pub.parse_manifest(gh.asset_named("SHA256SUMS.txt")["data"].decode())
    assert manifest["workzones-north-america-us-ohio.json.gz"] == hashlib.sha256(b"ohio-1").hexdigest()
    assert set(manifest) == names(paths)
    assert out["writes"] == 1 + len(paths) + 1 and sleeps == []


def test_unchanged_files_cost_no_write_at_all(gh, tmp_path):
    c, _ = client(gh)
    paths = sample(tmp_path)
    pub.publish(c, REPO, TAG, "Work zones", "notes", True, paths)
    before = dict(gh.assets)
    gh.requests.clear()
    c2, _ = client(gh)
    out = pub.publish(c2, REPO, TAG, "Work zones", "notes\r\n", True, paths)
    assert out["uploaded"] == [] and not out["manifest_uploaded"] and not out["created"]
    assert sorted(out["unchanged"]) == sorted(names(paths))
    assert gh.writes() == [] and out["writes"] == 0
    assert gh.assets == before


def test_only_the_changed_file_and_the_manifest_are_replaced(gh, tmp_path):
    c, _ = client(gh)
    pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    old_ohio = gh.asset_named("workzones-north-america-us-ohio.json.gz")["id"]
    old_texas = gh.asset_named("workzones-north-america-us-texas.json.gz")["id"]
    gh.requests.clear()
    c2, _ = client(gh)
    out = pub.publish(c2, REPO, TAG, "Work zones", "", True, sample(tmp_path, ohio=b"ohio-2"))
    assert out["uploaded"] == ["workzones-north-america-us-ohio.json.gz", "workzones-north-america-us-ohio.json.gz.sig"]
    assert out["manifest_uploaded"] and out["writes"] == 6
    assert [m for m, _ in gh.writes()] == ["DELETE", "POST", "DELETE", "POST", "DELETE", "POST"]
    ohio = gh.asset_named("workzones-north-america-us-ohio.json.gz")
    assert ohio["id"] != old_ohio and ohio["data"] == b"ohio-2"
    assert gh.asset_named("workzones-north-america-us-texas.json.gz")["id"] == old_texas
    manifest = pub.parse_manifest(gh.asset_named("SHA256SUMS.txt")["data"].decode())
    assert manifest["workzones-north-america-us-ohio.json.gz"] == hashlib.sha256(b"ohio-2").hexdigest()


def test_assets_without_a_recorded_digest_are_compared_through_the_manifest(gh, tmp_path):
    gh.digests = False
    c, _ = client(gh)
    pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    gh.requests.clear()
    c2, _ = client(gh)
    out = pub.publish(c2, REPO, TAG, "Work zones", "", True, sample(tmp_path, ohio=b"ohio-2"))
    # The manifest was fetched through the redirect, and the token did not follow it.
    assert gh.download_auth == [False]
    assert out["uploaded"] == ["workzones-north-america-us-ohio.json.gz", "workzones-north-america-us-ohio.json.gz.sig"]
    assert out["manifest_uploaded"]
    # Without digests the manifest itself cannot be compared cheaply, so it is always replaced.
    gh.requests.clear()
    c3, _ = client(gh)
    out = pub.publish(c3, REPO, TAG, "Work zones", "", True, sample(tmp_path, ohio=b"ohio-2"))
    assert out["uploaded"] == [] and out["manifest_uploaded"] and out["writes"] == 2


def test_an_asset_with_neither_digest_nor_manifest_entry_is_replaced(gh, tmp_path):
    gh.digests = False
    c, _ = client(gh)
    paths = sample(tmp_path)
    pub.publish(c, REPO, TAG, "Work zones", "", True, paths)
    manifest = gh.asset_named("SHA256SUMS.txt")
    del gh.assets[manifest["id"]]
    c2, _ = client(gh)
    out = pub.publish(c2, REPO, TAG, "Work zones", "", True, paths)
    assert sorted(out["uploaded"]) == sorted(names(paths)) and out["manifest_uploaded"]


def test_an_unfinished_upload_is_replaced_whatever_its_name_says(gh, tmp_path):
    c, _ = client(gh)
    paths = sample(tmp_path)
    pub.publish(c, REPO, TAG, "Work zones", "", True, paths)
    broken = gh.asset_named("workzones-north-america-us-texas.json.gz")
    broken["state"] = "starter"
    c2, _ = client(gh)
    out = pub.publish(c2, REPO, TAG, "Work zones", "", True, paths)
    assert out["uploaded"] == ["workzones-north-america-us-texas.json.gz"]
    assert gh.asset_named("workzones-north-america-us-texas.json.gz")["state"] == "uploaded"


def test_pagination_follows_the_link_header(gh, tmp_path):
    gh.page_size = 3
    c, _ = client(gh)
    paths = sample(tmp_path)
    pub.publish(c, REPO, TAG, "Work zones", "", True, paths)
    assert len(gh.assets) == 9
    c2, _ = client(gh)
    out = pub.publish(c2, REPO, TAG, "Work zones", "", True, paths)
    assert out["uploaded"] == [] and not out["manifest_uploaded"]
    pages = [p for m, p in gh.requests if m == "GET" and p.endswith("/assets")]
    assert len(pages) >= 4  # 9 assets at 3 a page, listed on the second publish


def test_release_notes_are_updated_only_when_they_change(gh, tmp_path):
    c, _ = client(gh)
    paths = sample(tmp_path)
    pub.publish(c, REPO, TAG, "Work zones", "first\n", True, paths)
    gh.requests.clear()
    pub.publish(c, REPO, TAG, "Work zones", "first\n", True, paths)
    assert gh.writes() == []
    pub.publish(c, REPO, TAG, "Work zones", "second\n", True, paths)
    assert [m for m, _ in gh.writes()] == ["PATCH"]
    assert next(iter(gh.releases.values()))["body"] == "second\n"


def test_a_secondary_rate_limit_is_retried_after_the_wait_it_names(gh, tmp_path):
    limited = (403, {"Retry-After": "7"}, b'{"message":"You have exceeded a secondary rate limit. Please wait."}')
    gh.fail_writes = [limited, limited]
    c, sleeps = client(gh, max_attempts=4, backoff_s=0.0)
    out = pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [7.0, 7.0] and out["created"]
    assert len(gh.assets) == 9


def test_a_429_without_headers_backs_off_exponentially(gh, tmp_path):
    gh.fail_writes = [(429, {}, b"{}"), (429, {}, b"{}"), (429, {}, b"{}")]
    c, sleeps = client(gh, max_attempts=5, backoff_s=0.0, rate_limit_wait_s=2.0)
    pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [2.0, 4.0, 8.0]


def test_a_secondary_limit_without_headers_waits_a_minute_first_by_default(gh, tmp_path):
    gh.fail_writes = [(403, {}, b'{"message":"You have exceeded a secondary rate limit."}')]
    sleeps: list[float] = []
    c = pub.GitHub("t0ken", gh.base, write_interval_s=0.0, sleep=sleeps.append)
    pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [60.0]
    assert c.max_attempts == 4  # three retries: one, two and four minutes at most


def test_a_rate_limit_reset_is_honoured(gh, tmp_path, monkeypatch):
    now = 1_000.0
    c, sleeps = client(gh, max_attempts=3, backoff_s=1.0)
    c.clock = lambda: now
    gh.fail_writes = [(403, {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1042"}, b'{"message":"API rate limit exceeded"}')]
    pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [42.0]


def test_a_403_that_is_not_a_rate_limit_is_final(gh, tmp_path):
    gh.fail_all_writes = (403, {}, b'{"message":"Resource not accessible by integration"}')
    c, sleeps = client(gh, max_attempts=4, backoff_s=0.0)
    with pytest.raises(pub.PublishError, match="HTTP 403"):
        pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [] and len(gh.writes()) == 1


def test_a_limit_that_never_lifts_fails_the_run_after_the_last_attempt(gh, tmp_path):
    gh.fail_all_writes = (403, {"Retry-After": "1"}, b'{"message":"secondary rate limit"}')
    c, sleeps = client(gh, max_attempts=3, backoff_s=0.0)
    with pytest.raises(pub.PublishError, match="after 3 attempt"):
        pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [1.0, 1.0] and len(gh.writes()) == 3


def test_server_errors_are_retried_and_other_client_errors_are_not(gh, tmp_path):
    gh.fail_writes = [(502, {}, b"bad gateway")]
    c, sleeps = client(gh, max_attempts=3, backoff_s=3.0)
    pub.publish(c, REPO, TAG, "Work zones", "", True, sample(tmp_path))
    assert sleeps == [3.0]
    gh.fail_writes = [(422, {}, b'{"message":"Validation Failed"}')]
    c2, sleeps2 = client(gh, max_attempts=3, backoff_s=3.0)
    with pytest.raises(pub.PublishError, match="HTTP 422"):
        pub.publish(c2, REPO, TAG, "Work zones", "", True, sample(tmp_path, ohio=b"ohio-3"))
    assert sleeps2 == []


def test_writes_are_paced():
    clock = [0.0]
    sleeps: list[float] = []

    def sleep(s):
        sleeps.append(s)
        clock[0] += s
    c = pub.GitHub("t", "http://127.0.0.1:1", write_interval_s=1.0, sleep=sleep, clock=lambda: clock[0])
    c._pace()
    clock[0] += 0.25
    c._pace()
    c._pace()
    assert sleeps == [0.75, 1.0]


def test_duplicate_names_and_the_manifest_name_are_refused(gh, tmp_path):
    c, _ = client(gh)
    a = tmp_path / "a" / "workzones-index.json"
    b = tmp_path / "b" / "workzones-index.json"
    a.parent.mkdir()
    b.parent.mkdir()
    a.write_bytes(b"1")
    b.write_bytes(b"2")
    with pytest.raises(pub.PublishError, match="two files are named"):
        pub.publish(c, REPO, TAG, "x", "", True, [str(a), str(b)])
    m = tmp_path / "SHA256SUMS.txt"
    m.write_bytes(b"x")
    with pytest.raises(pub.PublishError, match="written by this script"):
        pub.publish(c, REPO, TAG, "x", "", True, [str(a), str(m)])
    with pytest.raises(pub.PublishError, match="not a file"):
        pub.publish(c, REPO, TAG, "x", "", True, [str(tmp_path / "missing")])
    assert gh.writes() == []


def test_manifest_round_trip_and_recorded_digest():
    text = pub.manifest_text({"b.gz": "b" * 64, "a.gz": "a" * 64})
    assert text == f"{'a' * 64}  a.gz\n{'b' * 64}  b.gz\n"
    assert pub.parse_manifest(text + f"{'c' * 64} *c.gz\njunk line\n") == {"a.gz": "a" * 64, "b.gz": "b" * 64, "c.gz": "c" * 64}
    assert pub.recorded_digest({"name": "a", "digest": "sha256:ABC"}, None) == "abc"
    assert pub.recorded_digest({"name": "a", "digest": None}, {"a": "def"}) == "def"
    assert pub.recorded_digest({"name": "a", "digest": None}, None) is None
    assert pub.recorded_digest({"name": "a", "digest": "sha256:abc", "state": "starter"}, {"a": "abc"}) is None
    assert pub.next_page({"link": '<https://x/next?page=2>; rel="next", <https://x/last>; rel="last"'}) == "https://x/next?page=2"
    assert pub.next_page({}) is None


def test_main_reports_usage_and_publishes_with_the_token_from_the_environment(gh, tmp_path, monkeypatch):
    paths = sample(tmp_path)
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    monkeypatch.delenv("GH_TOKEN", raising=False)
    assert pub.main(["--repo", REPO, "--tag", TAG, "--api", gh.base, *paths]) == 2
    monkeypatch.setenv("GITHUB_TOKEN", "t0ken")
    assert pub.main(["--tag", TAG, "--api", gh.base, *paths]) == 2  # no repository named
    notes = tmp_path / "notes.md"
    notes.write_text("Live work zones.\n")
    summary = tmp_path / "summary.md"
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(summary))
    rc = pub.main(["--repo", REPO, "--tag", TAG, "--name", "Work zones", "--body-file", str(notes), "--prerelease",
                   "--api", gh.base, "--write-interval", "0", "--backoff", "0", *paths])
    assert rc == 0
    rel = next(iter(gh.releases.values()))
    assert rel["body"] == "Live work zones.\n" and rel["prerelease"] and rel["name"] == "Work zones"
    assert "8 file(s) uploaded" in summary.read_text()
    gh.fail_all_writes = (403, {}, b'{"message":"Resource not accessible by integration"}')
    rc = pub.main(["--repo", REPO, "--tag", TAG, "--api", gh.base, "--write-interval", "0", "--backoff", "0",
                   *sample(tmp_path, ohio=b"ohio-9")])
    assert rc == 1
