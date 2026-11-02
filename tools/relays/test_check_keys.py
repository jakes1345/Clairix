import base64
import copy
import json
import os

import pytest

import check_keys

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
CURRENT = base64.b64encode(bytes(range(32))).decode().rstrip("=")
NEXT = base64.b64encode(bytes(range(32, 64))).decode()
GOOD = {
    "type": "clairix-asset-keys",
    "version": 1,
    "issued": 1_700_000_000,
    "keys": [
        {"key": CURRENT, "role": "current", "revoked": False},
        {"key": NEXT, "role": "next", "revoked": False},
    ],
}
NOW = 1_800_000_000


def broken(**changes):
    doc = copy.deepcopy(GOOD)
    for path, value in changes.items():
        if path.startswith("key_"):
            field = path[len("key_"):]
            if value is None:
                del doc["keys"][0][field]
            else:
                doc["keys"][0][field] = value
        elif value is None:
            del doc[path]
        else:
            doc[path] = value
    return doc


def test_a_good_list_passes_and_accepts_the_current_and_the_next_key():
    check_keys.check(GOOD, now=NOW)
    assert check_keys.accepted(GOOD) == [CURRENT, NEXT]


def test_the_repository_list_passes_and_accepts_the_key_the_workflows_sign_with():
    with open(os.path.join(ROOT, "relays", "keys.json"), "rb") as f:
        doc = json.loads(f.read().decode("utf-8"))
    check_keys.check(doc)
    # Until the asset key is rotated (docs/KEYS.md), the workflows sign with the
    # key built into the app; a list that did not accept it would make every
    # phone holding it refuse the next relay list. A rotation changes this test.
    with open(os.path.join(ROOT, "android", "asset-signing.pub"), encoding="ascii") as f:
        built_in = check_keys.key_bytes(f.read().strip())
    assert built_in in [check_keys.key_bytes(k) for k in check_keys.accepted(doc)]


@pytest.mark.parametrize("doc", [
    broken(type="clairix-relay-list"),
    broken(version=2),
    broken(version=True),
    broken(issued=NOW + 7200),
    broken(issued=True),
    broken(issued=None),
    broken(keys=[]),
    broken(keys=[{"key": base64.b64encode(bytes([i]) * 32).decode(), "role": "next", "revoked": True} for i in range(9)]),
    broken(key_key="not base64!"),
    broken(key_key=base64.b64encode(bytes(31)).decode()),
    broken(key_key=CURRENT[:-1] + "B"),  # the spare bits of the last character set
    broken(key_role="old"),
    broken(key_revoked="no"),
    broken(key_revoked=None),
    broken(key_extra="field"),
    broken(extra="field"),
])
def test_a_bad_list_is_refused(doc):
    with pytest.raises(check_keys.Invalid):
        check_keys.check(doc, now=NOW)


def test_a_key_listed_twice_is_refused_however_it_is_spelled():
    doc = copy.deepcopy(GOOD)
    doc["keys"].append({"key": CURRENT + "=", "role": "next", "revoked": True})
    with pytest.raises(check_keys.Invalid):
        check_keys.check(doc, now=NOW)


def test_two_live_keys_in_one_role_are_refused_but_revoked_ones_may_pile_up():
    doc = copy.deepcopy(GOOD)
    doc["keys"].append({"key": base64.b64encode(bytes([7]) * 32).decode(), "role": "current", "revoked": False})
    with pytest.raises(check_keys.Invalid):
        check_keys.check(doc, now=NOW)
    doc["keys"][-1]["revoked"] = True
    doc["keys"].append({"key": base64.b64encode(bytes([8]) * 32).decode(), "role": "current", "revoked": True})
    check_keys.check(doc, now=NOW)
    assert check_keys.accepted(doc) == [CURRENT, NEXT]


def test_a_revoked_key_is_not_accepted_and_a_list_that_accepts_nothing_is_refused():
    doc = copy.deepcopy(GOOD)
    doc["keys"][0]["revoked"] = True
    check_keys.check(doc, now=NOW)
    assert check_keys.accepted(doc) == [NEXT]
    doc["keys"][1]["revoked"] = True
    with pytest.raises(check_keys.Invalid):
        check_keys.check(doc, now=NOW)


def test_a_list_not_issued_later_than_the_published_one_is_refused(tmp_path):
    new = tmp_path / "new.json"
    old = tmp_path / "old.json"
    old.write_text(json.dumps(GOOD))
    new.write_text(json.dumps(GOOD))
    assert check_keys.main([str(new), "--published", str(old)]) == 1
    new.write_text(json.dumps(dict(GOOD, issued=GOOD["issued"] + 1)))
    assert check_keys.main([str(new), "--published", str(old)]) == 0


def test_a_signature_checks_only_under_a_key_the_list_accepts(tmp_path):
    ed25519 = pytest.importorskip("cryptography.hazmat.primitives.asymmetric.ed25519")
    serialization = pytest.importorskip("cryptography.hazmat.primitives.serialization")

    def pair():
        private = ed25519.Ed25519PrivateKey.generate()
        raw = private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        return private, base64.b64encode(raw).decode().rstrip("=")

    current, current_key = pair()
    nxt, next_key = pair()
    revoked, revoked_key = pair()
    stranger, _ = pair()
    doc = dict(GOOD, keys=[
        {"key": current_key, "role": "current", "revoked": False},
        {"key": next_key, "role": "next", "revoked": False},
        {"key": revoked_key, "role": "current", "revoked": True},
    ])
    keys = tmp_path / "keys.json"
    keys.write_text(json.dumps(doc))
    signed = tmp_path / "latest.json"
    signed.write_bytes(b'{"type": "clairix-latest"}')

    def sign_with(private):
        sig = base64.b64encode(private.sign(signed.read_bytes())).decode().rstrip("=")
        (tmp_path / "latest.json.sig").write_text(sig + "\n")

    for private, ok in ((current, True), (nxt, True), (revoked, False), (stranger, False)):
        sign_with(private)
        assert check_keys.verify_signed(doc, str(signed)) is ok
        assert check_keys.main([str(keys), "--verify-signed", str(signed)]) == (0 if ok else 1)
