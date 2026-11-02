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


# ── The Relay watch workflow (--watch) ────────────────────────────────────────

ENTRY = GOOD["relays"][0]
ANSWER = {"service": "clairix-comms", "ok": True, "relayKey": KEY, "onion": ENTRY["onion"]}


def test_a_relay_answering_as_listed_raises_nothing():
    assert check.watch_problem(ENTRY, lambda url: dict(ANSWER)) is None
    without_onion = {k: v for k, v in ENTRY.items() if k != "onion"}
    assert check.watch_problem(without_onion, lambda url: dict(ANSWER, onion=None)) is None


@pytest.mark.parametrize("answer, said", [
    (dict(ANSWER, relayKey=base64.b64encode(bytes(32)).decode()), "publishes key"),
    (dict(ANSWER, onion=None), "onion address"),
    (dict(ANSWER, service="something-else"), "not as a Clairix relay"),
    (dict(ANSWER, ok=False), "not as a Clairix relay"),
    (["not", "an", "object"], "not as a Clairix relay"),
])
def test_a_relay_answering_otherwise_is_a_problem(answer, said):
    assert said in check.watch_problem(ENTRY, lambda url: answer)


def test_a_relay_that_does_not_answer_is_asked_again_then_fails():
    asked, slept = [], []

    def down(url):
        asked.append(url)
        raise OSError("connection refused")

    problems = check.watch(GOOD["relays"], fetch=down, attempts=3, wait=5, sleep=slept.append)
    assert asked == [ENTRY["url"]] * 3 and slept == [5, 5]
    assert "did not answer" in problems[ENTRY["url"]]


def test_a_relay_that_answers_on_a_second_try_is_fine():
    answers = iter([OSError("timed out"), dict(ANSWER)])

    def flaky(url):
        answer = next(answers)
        if isinstance(answer, Exception):
            raise answer
        return answer

    assert check.watch(GOOD["relays"], fetch=flaky, attempts=3, wait=0, sleep=lambda s: None) == {}
