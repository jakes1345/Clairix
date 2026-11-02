import gzip
import io
import json
import os

import pytest

import convert

SAMPLE = """
- name: TheTruthSpy
  names:
  - Copy9
  - TheTruthSpy
  - ExactSpy
  type: stalkerware
  packages:
  - com.thetruth
  - com.mxspy
  certificates:
  - 31a6ececd97cf39bc4126b8745cd94a7c30bf81c
  - 36:E6:67:1B:C4:39:7F:47:5A:35:09:05:D9:A6:49:A5:AD:E9:7B:B2
  websites:
  - thetruthspy.com
  c2:
    ips:
    - 69.64.74.239
    domains:
    - copy9.com
- name: WebsiteOnly
  type: stalkerware
  websites:
  - example.org
- name: Cocospy
  names:
  - Spyzie
  packages:
  - com.sc.spyier.v2
  - not a package
  - 1bad.start
  certificates:
  - 0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF
  - 1234
- name: Spyier
  packages:
  - com.sc.spyier.v2
- name: Later
  packages:
  - com.later.app
  certificates:
  - 31A6ECECD97CF39BC4126B8745CD94A7C30BF81C
"""

NOW = 1_800_000_000


def sample(**kwargs):
    warnings = []
    doc = convert.convert(convert.parse_yaml(SAMPLE.encode()), NOW, "test", warn=warnings.append, floors=False, **kwargs)
    return doc, warnings


def test_keeps_names_packages_and_certificates_only():
    doc, _ = sample()
    assert set(doc) == convert.FIELDS
    assert doc["type"] == "clairix-stalkerware-ioc" and doc["version"] == 1 and doc["issued"] == NOW
    assert doc["licence"] == "CC BY 4.0"
    # The main name first, aliases after it, the repeat of the main name dropped.
    assert doc["names"][0][:3] == ["TheTruthSpy", "Copy9", "ExactSpy"]
    assert doc["packages"]["com.thetruth"] == 0 and doc["packages"]["com.mxspy"] == 0
    # Fingerprints in any case or with colons become bare uppercase hex.
    assert doc["certs"]["31A6ECECD97CF39BC4126B8745CD94A7C30BF81C"] == 0
    assert doc["certs"]["36E6671BC4397F475A350905D9A649A5ADE97BB2"] == 0
    assert "thetruthspy.com" not in json.dumps(doc) and "69.64.74.239" not in json.dumps(doc)


def test_an_app_with_nothing_to_match_is_left_out():
    doc, _ = sample()
    assert all("WebsiteOnly" not in family for family in doc["names"])


def test_bad_packages_and_fingerprints_are_left_out_with_a_warning():
    doc, warnings = sample()
    assert "not a package" not in doc["packages"] and "1bad.start" not in doc["packages"]
    assert "1234" not in doc["certs"]
    # A SHA-256 fingerprint is kept as well as the SHA-1 ones the source gives today.
    assert doc["certs"]["0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"] == doc["packages"]["com.sc.spyier.v2"]
    assert any("not a package" in w for w in warnings) and any("certificate 1234 " in w for w in warnings)


def test_a_key_listed_twice_goes_to_the_first_app_and_keeps_the_second_name():
    doc, warnings = sample()
    cocospy = doc["packages"]["com.sc.spyier.v2"]
    assert doc["names"][cocospy] == ["Cocospy", "Spyzie", "Spyier"]
    # Spyier had nothing of its own, so it is not an app of its own either.
    assert sum(1 for family in doc["names"] if family[0] == "Spyier") == 0
    # Later keeps its own package; the certificate TheTruthSpy already had stays with it.
    later = doc["packages"]["com.later.app"]
    assert doc["names"][later] == ["Later"]
    assert doc["certs"]["31A6ECECD97CF39BC4126B8745CD94A7C30BF81C"] == 0
    assert "Later" in doc["names"][0]
    assert any("already listed under TheTruthSpy" in w for w in warnings)


def test_a_watchware_entry_is_not_stalkerware():
    entries = convert.parse_yaml(b"- name: KidsApp\n  type: watchware\n  packages:\n  - com.kids.app\n- name: Spy\n  packages:\n  - com.spy.app\n")
    doc = convert.convert(entries, NOW, "test", floors=False)
    assert "com.kids.app" not in doc["packages"] and "com.spy.app" in doc["packages"]


def test_the_encoding_is_the_same_bytes_every_time():
    doc, _ = sample()
    first, second = convert.encode(doc), convert.encode(doc)
    assert first == second
    # No file name and no modification time in the gzip header.
    assert first[3] == 0 and first[4:8] == b"\x00\x00\x00\x00"
    assert convert.decode(first) == doc


def test_the_same_indicators_compare_equal_whatever_the_order_or_issue_time():
    doc, _ = sample()
    shuffled = json.loads(json.dumps(doc))
    shuffled["issued"] = NOW + 10
    shuffled["names"] = list(reversed(shuffled["names"]))
    last = len(shuffled["names"]) - 1
    shuffled["packages"] = {k: last - v for k, v in shuffled["packages"].items()}
    shuffled["certs"] = {k: last - v for k, v in shuffled["certs"].items()}
    assert convert.indicators(shuffled) == convert.indicators(doc)
    changed = json.loads(json.dumps(doc))
    changed["packages"]["com.new.spy"] = 0
    assert convert.indicators(changed) != convert.indicators(doc)


def gz(obj):
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as f:
        f.write(json.dumps(obj).encode())
    return out.getvalue()


@pytest.mark.parametrize("change", [
    {"type": "clairix-relay-list"},
    {"version": 2},
    {"version": True},
    {"issued": True},
    {"issued": 0},
    {"extra": 1},
    {"names": []},
    {"names": [[]]},
    {"names": [["two\nlines"]]},
    {"packages": {"com.ok.app": 5}},
    {"packages": {"com.ok.app": True}},
    {"packages": {"no_dot": 0}},
    {"packages": {}},
    {"certs": {"31a6ececd97cf39bc4126b8745cd94a7c30bf81c": 0}},
    {"certs": {"ABCD": 0}},
])
def test_what_the_app_refuses_is_refused(change):
    doc, _ = sample()
    doc.update(change)
    with pytest.raises(convert.Invalid):
        convert.decode(gz(doc))


def test_not_gzip_or_not_json_is_refused():
    with pytest.raises(convert.Invalid):
        convert.decode(b"{}")
    with pytest.raises(convert.Invalid):
        convert.decode(gzip.compress(b"not json"))


def test_a_list_far_shorter_than_the_source_has_ever_been_is_refused():
    doc, _ = sample()
    with pytest.raises(convert.Invalid, match="moved or broken"):
        convert.validate(doc, floors=True)


BUNDLED = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "suite", "assets", "ioc.json.gz")


def test_the_copy_built_into_the_app_is_valid_and_whole():
    with open(BUNDLED, "rb") as f:
        doc = convert.decode(f.read())
    convert.validate(doc, floors=True)
    assert doc["source"].startswith("https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/")
    # The five names of the old hard-coded list that the source does have are on it.
    for package in ("com.mspy.lite", "com.hellospy.system", "com.lsdroid.cerberus", "com.spy2mobile.light", "com.mxspy"):
        assert package in doc["packages"], package
    # The fourteen that were invented are not.
    for package in ("com.mspy.android", "com.flexispy.android", "com.spyzie.android", "com.cocospy.android",
                    "com.spyic.android", "com.hoverwatch.android", "com.thetruthspy.android", "com.spyhuman.android",
                    "com.xnspy.android", "com.mobistealth.android", "com.ikeymonitor.android", "com.cerberusapp",
                    "com.pcTattletale", "com.retinax.mobilespy"):
        assert package not in doc["packages"], package


def test_the_command_line_converts_checks_and_compares(tmp_path):
    source = tmp_path / "ioc.yaml"
    source.write_text(SAMPLE)
    out = tmp_path / "ioc.json.gz"
    # Too short to publish: refused, nothing written.
    assert convert.main(["--yaml", str(source), "--out", str(out), "--issued", str(NOW)]) == 2
    assert not out.exists()
    doc, _ = sample()
    out.write_bytes(convert.encode(doc))
    assert convert.main(["--check", str(BUNDLED)]) == 0
    assert convert.main(["--check", str(out)]) == 2
    again = tmp_path / "again.json.gz"
    later = dict(doc, issued=NOW + 1)
    again.write_bytes(convert.encode(later))
    assert convert.main(["--same", str(out), str(again)]) == 0
    assert convert.main(["--same", str(out), str(BUNDLED)]) == 1
