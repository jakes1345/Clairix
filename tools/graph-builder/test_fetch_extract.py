"""fetch_extract.sh against a local server: a refused connection is the one failure that
sends a region to a new runner (exit 75); a bad MD5 or an HTTP error is not."""
import functools
import hashlib
import http.server
import os
import socket
import subprocess
import threading
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "fetch_extract.sh"
PBF = b"\x00\x00\x00\x0dnot a real pbf, only bytes to hash"


@pytest.fixture
def server(tmp_path):
    """An HTTP server for tmp_path/www, as Geofabrik lays out an extract and its MD5."""
    www = tmp_path / "www"
    www.mkdir()
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(www))
    handler.log_message = lambda *a, **k: None
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield www, f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


def closed_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def run(url: str, out: Path) -> subprocess.CompletedProcess:
    env = dict(os.environ, FETCH_RETRIES="1", FETCH_RETRY_DELAY="0")
    return subprocess.run(["bash", str(SCRIPT), url, str(out)], env=env, capture_output=True, text=True, timeout=120)


def publish(www: Path, md5: str | None = None) -> None:
    (www / "ohio-latest.osm.pbf").write_bytes(PBF)
    digest = md5 if md5 is not None else hashlib.md5(PBF).hexdigest()
    (www / "ohio-latest.osm.pbf.md5").write_text(f"{digest}  ohio-latest.osm.pbf\n")


def test_an_extract_whose_md5_matches_is_kept(server, tmp_path):
    www, base = server
    publish(www)
    out = tmp_path / "out"
    r = run(f"{base}/ohio-latest.osm.pbf", out)
    assert r.returncode == 0, r.stderr
    assert (out / "region.osm.pbf").read_bytes() == PBF
    assert (out / "region.osm.pbf.md5").read_text().startswith(hashlib.md5(PBF).hexdigest())
    assert "200" in (out / "region.headers").read_text().splitlines()[0]


def test_a_refused_connection_asks_for_a_new_runner(tmp_path):
    r = run(f"http://127.0.0.1:{closed_port()}/ohio-latest.osm.pbf", tmp_path / "out")
    assert r.returncode == 75, r.stderr
    assert "Could not reach" in r.stderr


def test_an_unresolvable_host_asks_for_a_new_runner(tmp_path):
    # .invalid never resolves (RFC 6761).
    r = run("http://download.geofabrik.invalid/ohio-latest.osm.pbf", tmp_path / "out")
    assert r.returncode == 75, r.stderr


def test_a_missing_extract_is_a_real_failure(server, tmp_path):
    _, base = server
    r = run(f"{base}/north-america/us/nowhere-latest.osm.pbf", tmp_path / "out")
    assert r.returncode == 22, r.stderr


def test_a_missing_md5_is_a_real_failure(server, tmp_path):
    www, base = server
    publish(www)
    (www / "ohio-latest.osm.pbf.md5").unlink()
    r = run(f"{base}/ohio-latest.osm.pbf", tmp_path / "out")
    assert r.returncode == 22, r.stderr


def test_a_bad_md5_is_a_real_failure(server, tmp_path):
    www, base = server
    publish(www, md5="0" * 32)
    r = run(f"{base}/ohio-latest.osm.pbf", tmp_path / "out")
    assert r.returncode == 1, r.stderr
    assert r.returncode != 75
