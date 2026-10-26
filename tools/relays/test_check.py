import base64
import copy
import json
import os

import pytest

import check

KEY = base64.b64encode(bytes(range(32))).decode()
GOOD = {
    "type": "clairix-relay-list",
    "version": 1,
    "issued": 1_700_000_000,
    "relays": [
        {
            "url": "https://relay.example.org",
            "name": "Example",
            "country": "IS",
            "host": "1984 Hosting",
            "operator": "Someone",
            "key": KEY,
            "join": "Ask for an invite.",
            "onion": "http://" + "a" * 56 + ".onion",
        }
    ],
}
NOW = 1_800_000_000


def broken(**changes):
    doc = copy.deepcopy(GOOD)
    for path, value in changes.items():
        if path.startswith("relay_"):
            field = path[len("relay_"):]
            if value is None:
                del doc["relays"][0][field]
            else:
                doc["relays"][0][field] = value
        else:
            doc[path] = value
    return doc


def test_a_good_list_passes():
    assert check.check(GOOD, now=NOW)[0]["url"] == "https://relay.example.org"


def test_the_repository_list_passes():
    path = os.path.join(os.path.dirname(__file__), "..", "..", "relays", "relays.json")
    with open(path, "rb") as f:
        check.check(json.loads(f.read().decode("utf-8")))


@pytest.mark.parametrize("doc", [
    broken(type="clairix-something-else"),
    broken(version=2),
    broken(issued=NOW + 7200),
    broken(issued=True),
    broken(relays=[]),
    broken(relay_url="http://relay.example.org"),
    broken(relay_url="https://relay.example.org/path"),
    broken(relay_url="https://user@relay.example.org"),
    broken(relay_url="https://Relay.example.org"),
    broken(relay_key=base64.b64encode(bytes(31)).decode()),
    broken(relay_key="not base64!"),
    broken(relay_name="two\nlines"),
    broken(relay_name="x" * 201),
    broken(relay_onion="http://short.onion"),
    broken(relay_onion="https://" + "a" * 56 + ".onion"),
    broken(relay_extra="field"),
    broken(relay_operator=None),
])
def test_a_bad_list_is_refused(doc):
    with pytest.raises(check.Invalid):
        check.check(doc, now=NOW)


def test_a_relay_listed_twice_is_refused():
    doc = copy.deepcopy(GOOD)
    doc["relays"].append(copy.deepcopy(doc["relays"][0]))
    with pytest.raises(check.Invalid):
        check.check(doc, now=NOW)


def test_a_list_not_newer_than_the_published_one_is_refused(tmp_path):
    new = tmp_path / "new.json"
    old = tmp_path / "old.json"
    new.write_text(json.dumps(GOOD))
    old.write_text(json.dumps(GOOD))
    assert check.main([str(new), "--published", str(old)]) == 1
    newer = dict(GOOD, issued=GOOD["issued"] + 1)
    new.write_text(json.dumps(newer))
    assert check.main([str(new), "--published", str(old)]) == 0
