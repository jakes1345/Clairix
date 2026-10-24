import gzip
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extract_cameras  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "extract_cameras.py"

# Node id -> (lat, lon, tags)
NODES = {
    # A Flock Falcon as the wiki recommends it.
    1: (40.0, -83.0, {"man_made": "surveillance", "surveillance": "public", "surveillance:type": "ALPR",
                      "surveillance:zone": "traffic", "camera:mount": "pole", "direction": "135",
                      "manufacturer": "Flock Safety", "model": "Falcon", "operator": "Example Police Department"}),
    # Common mistakes the wiki lists: camera:type=ALPR, lower case, brand instead of manufacturer, cardinal direction.
    2: (40.001, -83.001, {"man_made": "surveillance", "camera:type": "alpr", "brand": "Flock Safety",
                          "camera:direction": "NE"}),
    # ANPR spelling with a direction range.
    3: (40.002, -83.002, {"man_made": "surveillance", "surveillance:type": "ANPR", "direction": "90-180"}),
    # An ordinary camera: not a plate reader.
    4: (40.003, -83.003, {"man_made": "surveillance", "surveillance:type": "camera", "camera:type": "dome"}),
    # A removed reader, lifecycle-prefixed: not current.
    5: (40.004, -83.004, {"was:man_made": "surveillance", "was:surveillance:type": "ALPR"}),
    # A gunshot detector from the same maker: not a plate reader.
    6: (40.005, -83.005, {"man_made": "surveillance", "surveillance:type": "gunshot_detector", "manufacturer": "Flock Safety"}),
    # Unknown zone and mount values are dropped, text is trimmed and collapsed.
    7: (40.006, -83.006, {"man_made": "surveillance", "surveillance:type": "ALPR", "surveillance:zone": "moon",
                          "camera:mount": "unicycle", "operator": "  Some   Town  ", "direction": "garbage"}),
    8: (40.007, -83.007, {"highway": "speed_camera"}),
}


def write_osm(path: Path) -> None:
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="test">']
    for nid, (lat, lon, tags) in NODES.items():
        lines.append(f'  <node id="{nid}" version="1" lat="{lat:.7f}" lon="{lon:.7f}">')
        lines += [f'    <tag k="{k}" v="{v}"/>' for k, v in tags.items()]
        lines.append("  </node>")
    lines.append("</osm>")
    path.write_text("\n".join(lines), encoding="utf-8")


def test_extract_keeps_plate_readers_only(tmp_path):
    osm = tmp_path / "t.osm"
    write_osm(osm)
    recs = extract_cameras.extract(str(osm))
    assert [r["id"] for r in recs] == ["n1", "n2", "n3", "n7"]
    byid = {r["id"]: r for r in recs}
    assert byid["n1"] == {
        "id": "n1", "lat": 40.0, "lon": -83.0, "kind": "alpr", "zone": "traffic", "direction": 135,
        "mount": "pole", "manufacturer": "Flock Safety", "model": "Falcon", "operator": "Example Police Department",
    }
    assert byid["n2"]["manufacturer"] == "Flock Safety"
    assert byid["n2"]["direction"] == 45
    assert byid["n3"]["direction"] == 135
    assert "zone" not in byid["n7"] and "mount" not in byid["n7"] and "direction" not in byid["n7"]
    assert byid["n7"]["operator"] == "Some Town"


@pytest.mark.parametrize("value,expected", [
    ("0", 0), ("359.6", 0), ("90", 90), ("-90", 270), ("45°", 45), ("N", 0), ("south-west", None), ("sw", 225),
    ("270-90", 0), ("90-180", 135), ("", None), (None, None), ("north;south", 0),
])
def test_parse_direction(value, expected):
    assert extract_cameras.parse_direction(value) == expected


def test_cli_writes_deterministic_gzip(tmp_path, monkeypatch):
    osm = tmp_path / "t.osm"
    write_osm(osm)
    out1 = tmp_path / "cameras-a.json.gz"
    out2 = tmp_path / "cameras-b.json.gz"
    env = {"SOURCE_DATE_EPOCH": "1700000000", "PATH": "/usr/bin:/bin"}
    for out in (out1, out2):
        r = subprocess.run([sys.executable, str(SCRIPT), str(osm), str(out), "--region", "north-america-us-ohio"],
                           capture_output=True, text=True, env=env)
        assert r.returncode == 0, r.stderr
        assert "cameras=4" in r.stdout
    assert hashlib.sha256(out1.read_bytes()).hexdigest() == hashlib.sha256(out2.read_bytes()).hexdigest()
    doc = json.loads(gzip.decompress(out1.read_bytes()))
    assert doc["version"] == 1 and doc["slug"] == "north-america-us-ohio" and doc["built"] == 1700000000
    assert len(doc["cameras"]) == 4
    assert "OpenStreetMap" in doc["attribution"]


def test_cli_rejects_bad_slug(tmp_path):
    osm = tmp_path / "t.osm"
    write_osm(osm)
    r = subprocess.run([sys.executable, str(SCRIPT), str(osm), str(tmp_path / "x.json.gz"), "--region", "Ohio/USA"],
                       capture_output=True, text=True)
    assert r.returncode != 0
