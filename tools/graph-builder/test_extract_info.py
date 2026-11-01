import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extract_info  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "extract_info.py"
MD5 = "0123456789abcdef0123456789abcdef"


def write_pbf(path: Path, timestamp: str | None) -> None:
    import osmium

    header = osmium.io.Header()
    if timestamp is not None:
        header.set("osmosis_replication_timestamp", timestamp)
    writer = osmium.SimpleWriter(str(path), header=header)
    writer.add_node(osmium.osm.mutable.Node(id=1, location=(-83.0, 40.0)))
    writer.close()


def test_md5_is_read_from_geofabriks_file(tmp_path):
    f = tmp_path / "region.osm.pbf.md5"
    f.write_text(f"{MD5}  ohio-latest.osm.pbf\n")
    assert extract_info.read_md5(str(f)) == MD5


@pytest.mark.parametrize("content", ["", "not-an-md5  ohio-latest.osm.pbf\n", f"{MD5.upper()}  x\n"])
def test_anything_but_an_md5_is_refused(tmp_path, content):
    f = tmp_path / "bad.md5"
    f.write_text(content)
    with pytest.raises(ValueError):
        extract_info.read_md5(str(f))


def test_last_modified_is_the_final_responses(tmp_path):
    headers = tmp_path / "headers"
    headers.write_text(
        "HTTP/1.1 302 Found\r\nLast-Modified: Mon, 01 Jan 2024 00:00:00 GMT\r\nLocation: https://mirror/x\r\n\r\n"
        "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nlast-modified: Sat, 01 Nov 2026 01:23:45 GMT\r\n\r\n"
    )
    assert extract_info.last_modified(str(headers)) == "Sat, 01 Nov 2026 01:23:45 GMT"
    headers.write_text("HTTP/2 302\r\nlast-modified: Mon, 01 Jan 2024 00:00:00 GMT\r\n\r\nHTTP/2 200\r\n\r\n")
    assert extract_info.last_modified(str(headers)) == ""


def test_replication_timestamp_comes_from_the_extracts_header(tmp_path):
    pytest.importorskip("osmium")
    stamped, bare = tmp_path / "stamped.osm.pbf", tmp_path / "bare.osm.pbf"
    write_pbf(stamped, "2026-10-31T20:21:02Z")
    write_pbf(bare, None)
    assert extract_info.replication_timestamp(str(stamped)) == "2026-10-31T20:21:02Z"
    assert extract_info.replication_timestamp(str(bare)) == ""


def test_record_then_notes_through_the_command_line(tmp_path):
    pytest.importorskip("osmium")
    pbf = tmp_path / "region.osm.pbf"
    write_pbf(pbf, "2026-10-31T20:21:02Z")
    (tmp_path / "region.osm.pbf.md5").write_text(f"{MD5}  ohio-latest.osm.pbf\n")
    (tmp_path / "headers").write_text("HTTP/1.1 200 OK\r\nLast-Modified: Sat, 01 Nov 2026 01:23:45 GMT\r\n\r\n")
    out = tmp_path / "source-north-america-us-ohio.json"
    url = "https://download.geofabrik.de/north-america/us/ohio-latest.osm.pbf"
    subprocess.run([
        sys.executable, str(SCRIPT), "record", "--pbf", str(pbf), "--md5", str(tmp_path / "region.osm.pbf.md5"),
        "--headers", str(tmp_path / "headers"), "--url", url, "--region", "north-america/us/ohio", "--out", str(out),
    ], check=True, capture_output=True)
    assert json.loads(out.read_text()) == {
        "region": "north-america/us/ohio", "url": url, "md5": MD5,
        "last_modified": "Sat, 01 Nov 2026 01:23:45 GMT", "osm_timestamp": "2026-10-31T20:21:02Z",
    }
    text = subprocess.run([
        sys.executable, str(SCRIPT), "notes", str(out), "--slug", "north-america-us-ohio", "--run-id", "42", "--sha", "f" * 40,
    ], check=True, capture_output=True, text=True).stdout
    assert "Road graph (format v2) for `north-america/us/ohio`" in text
    assert f"by run 42 of {'f' * 40}" in text
    assert "cameras-north-america-us-ohio.json.gz" in text
    assert url in text
    assert "OpenStreetMap data as of 2026-10-31T20:21:02Z" in text
    assert "published by Geofabrik Sat, 01 Nov 2026 01:23:45 GMT" in text
    assert f"MD5 {MD5}" in text
    assert "they do not show that the extract is OpenStreetMap's" in text


def test_notes_say_when_a_date_is_missing():
    info = {"region": "europe/monaco", "url": "u", "md5": MD5, "last_modified": "", "osm_timestamp": ""}
    text = extract_info.notes(info, "europe-monaco", "1", "a" * 40)
    assert "OpenStreetMap data as of (not stated in the extract)" in text
    assert "published by Geofabrik (no date given by the server)" in text


def test_notes_need_only_the_standard_library():
    # The publishing job runs `notes` with the runner's own Python and installs nothing.
    probe = "import sys, extract_info; sys.exit(1 if 'osmium' in sys.modules else 0)"
    subprocess.run([sys.executable, "-c", probe], check=True, cwd=SCRIPT.parent)
