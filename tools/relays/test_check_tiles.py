import copy
import json
import os

import pytest

import check_tiles

GOOD = {
    "type": "clairix-tile-source",
    "version": 1,
    "issued": 1_700_000_000,
    "name": "OpenStreetMap",
    "url": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    "minZoom": 0,
    "maxZoom": 19,
    "tileSize": 256,
    "attribution": "© OpenStreetMap contributors",
    "attributionUrl": "https://www.openstreetmap.org/copyright",
}
NOW = 1_800_000_000


def broken(**changes):
    doc = copy.deepcopy(GOOD)
    for field, value in changes.items():
        if value is None:
            del doc[field]
        else:
            doc[field] = value
    return doc


def test_a_good_setting_passes():
    assert check_tiles.check(GOOD, now=NOW)["url"] == GOOD["url"]


def test_a_commercial_server_with_a_key_in_the_query_passes():
    doc = broken(
        name="MapTiler",
        url="https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key=abc123",
        maxZoom=22,
        attribution="© MapTiler © OpenStreetMap contributors",
    )
    assert check_tiles.check(doc, now=NOW)["name"] == "MapTiler"


def test_the_repository_setting_passes():
    path = os.path.join(os.path.dirname(__file__), "..", "..", "relays", "tiles.json")
    with open(path, "rb") as f:
        check_tiles.check(json.loads(f.read().decode("utf-8")))


@pytest.mark.parametrize("doc", [
    broken(type="clairix-relay-list"),
    broken(version=2),
    broken(issued=NOW + 7200),
    broken(issued=True),
    broken(issued=0),
    broken(extra="field"),
    broken(attributionUrl=None),
    broken(name="two\nlines"),
    broken(name="x" * 201),
    broken(url="http://tile.openstreetmap.org/{z}/{x}/{y}.png"),
    broken(url="https://tile.openstreetmap.org/{z}/{x}.png"),
    broken(url="https://tile.openstreetmap.org/{z}/{x}/{y}/{y}.png"),
    broken(url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"),
    broken(url="https://tile.openstreetmap.org/{z}/{x}/{y}.png?style={style}"),
    broken(url="https://Tile.openstreetmap.org/{z}/{x}/{y}.png"),
    broken(url="https://user:pw@tile.openstreetmap.org/{z}/{x}/{y}.png"),
    broken(url="https://tile.openstreetmap.org/{z}/{x}/{y}.png#frag"),
    broken(url="https://tile.openstreetmap.org/ {z}/{x}/{y}.png"),
    broken(url="https://tile.example.org/" + "a" * 512 + "/{z}/{x}/{y}.png"),
    broken(maxZoom=23),
    broken(maxZoom=0),
    broken(minZoom=20),
    broken(minZoom=-1),
    broken(minZoom=1.5),
    broken(tileSize=300),
    broken(tileSize=True),
    broken(tileSize=256.0),
    broken(version=1.0),
    broken(version=True),
    broken(attribution="© Some tile company"),
    broken(attributionUrl="http://www.openstreetmap.org/copyright"),
])
def test_a_bad_setting_is_refused(doc):
    with pytest.raises(check_tiles.Invalid):
        check_tiles.check(doc, now=NOW)


def test_a_setting_not_newer_than_the_published_one_is_refused(tmp_path):
    new = tmp_path / "new.json"
    old = tmp_path / "old.json"
    new.write_text(json.dumps(GOOD))
    old.write_text(json.dumps(GOOD))
    assert check_tiles.main([str(new), "--published", str(old)]) == 1
    newer = dict(GOOD, issued=GOOD["issued"] + 1)
    new.write_text(json.dumps(newer))
    assert check_tiles.main([str(new), "--published", str(old)]) == 0
