import copy
import json
import os

import pytest

import check_latest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
GOOD = {
    "type": "clairix-latest",
    "version": 1,
    "issued": 1_700_000_000,
    "versionCode": 29,
    "versionName": "3.0.0-beta.9",
    "minSafeVersionCode": 27,
    "notes": "Fixes a flaw that let a crafted link take over a verified contact.",
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


def test_a_good_note_passes():
    assert check_latest.check(GOOD, now=NOW)["versionCode"] == 29


def test_the_repository_note_passes_and_names_the_version_the_app_builds():
    with open(os.path.join(ROOT, "relays", "latest.json"), "rb") as f:
        doc = json.loads(f.read().decode("utf-8"))
    check_latest.check(doc)
    with open(os.path.join(ROOT, "android", "app", "build.gradle.kts"), encoding="utf-8") as f:
        code, name = check_latest.gradle_version(f.read())
    assert (doc["versionCode"], doc["versionName"]) == (code, name), "bump relays/latest.json with the app's version"


@pytest.mark.parametrize("doc", [
    broken(type="clairix-relay-list"),
    broken(version=2),
    broken(version=True),
    broken(issued=NOW + 7200),
    broken(issued=0),
    broken(issued=True),
    broken(versionCode=0),
    broken(versionCode=True),
    broken(versionCode=2_100_000_001),
    broken(versionCode="29"),
    broken(minSafeVersionCode=30),
    broken(minSafeVersionCode=0),
    broken(versionName="3.0"),
    broken(versionName="v3.0.0"),
    broken(versionName="3.0.0 Beta 9"),
    broken(versionName="3.0.0-" + "b" * 64),
    broken(notes=""),
    broken(notes="two\nlines"),
    broken(notes="x" * 201),
    broken(extra="field"),
    broken(minSafeVersionCode=None),
    broken(notes=None),
])
def test_a_bad_note_is_refused(doc):
    with pytest.raises(check_latest.Invalid):
        check_latest.check(doc, now=NOW)


def test_the_oldest_safe_version_may_be_the_newest():
    assert check_latest.check(broken(minSafeVersionCode=29), now=NOW)


def test_a_note_not_issued_later_than_the_published_one_is_refused(tmp_path):
    new = tmp_path / "new.json"
    old = tmp_path / "old.json"
    old.write_text(json.dumps(GOOD))
    new.write_text(json.dumps(GOOD))
    assert check_latest.main([str(new), "--published", str(old)]) == 1
    new.write_text(json.dumps(dict(GOOD, issued=GOOD["issued"] - 1)))
    assert check_latest.main([str(new), "--published", str(old)]) == 1
    new.write_text(json.dumps(dict(GOOD, issued=GOOD["issued"] + 1)))
    assert check_latest.main([str(new), "--published", str(old)]) == 0


def test_the_note_must_name_the_version_the_build_file_builds(tmp_path):
    note = tmp_path / "latest.json"
    note.write_text(json.dumps(GOOD))
    gradle = tmp_path / "build.gradle.kts"
    gradle.write_text('        versionCode = 29\n        versionName = "3.0.0-beta.9"\n            versionNameSuffix = "-comms"\n')
    assert check_latest.main([str(note), "--gradle", str(gradle)]) == 0
    gradle.write_text('        versionCode = 30\n        versionName = "3.0.0-beta.9"\n')
    assert check_latest.main([str(note), "--gradle", str(gradle)]) == 1
    gradle.write_text('        versionCode = 29\n        versionName = "3.0.0-beta.10"\n')
    assert check_latest.main([str(note), "--gradle", str(gradle)]) == 1
    gradle.write_text("nothing here\n")
    assert check_latest.main([str(note), "--gradle", str(gradle)]) == 1


def test_the_version_name_is_printed_for_the_workflow(tmp_path, capsys):
    note = tmp_path / "latest.json"
    note.write_text(json.dumps(GOOD))
    assert check_latest.main([str(note), "--print-version-name"]) == 0
    assert capsys.readouterr().out == "3.0.0-beta.9\n"
    note.write_text("not json")
    assert check_latest.main([str(note), "--print-version-name"]) == 1
