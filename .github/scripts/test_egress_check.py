"""egress_check.py over a small made-up repository, and over this one."""
import importlib.util
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("egress_check", HERE / "egress_check.py")
ec = importlib.util.module_from_spec(spec)
sys.modules["egress_check"] = ec
spec.loader.exec_module(ec)

SECURITY = """\
## What leaves your phone {#egress}

Intro.

| Destination | When | What is sent | What they can see |
|---|---|---|---|
| **Clairix relay** (ours) | Always | Envelopes | Little |
| **HERE and TomTom**, via the relay | Navigating | A box | The relay |

Nothing else.
"""


def write(root, rel, text):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    return p


def make_repo(tmp_path, egress=None, security=SECURITY, kotlin=None, ts=None):
    write(tmp_path, "android/app/src/main/java/app/A.kt", kotlin or (
        'package app\n'
        '// https://commented.example.org/ is not code\n'
        '/* nor https://block.example.org */\n'
        'const val TILES = "https://tile.example.org/{z}/{x}/{y}.png"\n'
        'const val INVITE_HOST = "invite.example.org"\n'
        'val dynamic = "https://$host:${port}/x"\n'
        'val local = "http://127.0.0.1:8080"\n'
    ))
    write(tmp_path, "android/app/src/test/java/app/ATest.kt", 'val x = "https://only-in-a-test.example.org"\n')
    write(tmp_path, "android/app/src/androidTest/java/app/B.kt", 'val x = "https://only-in-a-device-test.example.org"\n')
    write(tmp_path, "android/app/src/testSuite/java/app/CTest.kt", 'val x = "https://only-in-a-tier-test.example.org"\n')
    write(tmp_path, "android/app/src/androidTestFullSuite/java/app/D.kt", 'val x = "https://only-in-a-variant-device-test.example.org"\n')
    write(tmp_path, "comms-worker/src/x.ts", ts or (
        "export const DEFAULT_PUSH_HOSTS: readonly string[] = [\"push.example.org\", 'push2.example.org'];\n"
        "const u = `https://api.here.example.org/v7?${q}`;\n"
        "const onion = `http://<56 characters>.onion`;\n"
    ))
    write(tmp_path, "website/src/content/docs/security.md", security)
    egress = egress if egress is not None else {
        "destinations": [
            {"row": "Clairix relay (ours)", "from": "app", "hosts": ["tile.example.org", "push.example.org", "push2.example.org"]},
            {"row": "HERE and TomTom, via the relay", "from": "relay", "hosts": ["api.here.example.org"]},
        ],
        "links": {"invite.example.org": "shared by the user"},
        "not_fetched": {},
    }
    write(tmp_path, "docs/egress.json", json.dumps(egress))
    return tmp_path


def run(root):
    return ec.check(root, root / "docs/egress.json", root / "website/src/content/docs/security.md")


def test_hosts_are_found_in_literals_and_host_constants_but_not_in_comments():
    src = (
        '// https://a.example.org\n'
        'val s = "see https://b.example.org/path?q=1"\n'
        'const val PUSH_HOST = "c.example.org"\n'
        'const val LABEL = "d.example.org"\n'
        "val ch = '\"'\n"
        'val e = """raw https://e.example.org"""\n'
    )
    hosts = sorted(h for h, _ in ec.hosts_in(src, kotlin=True))
    assert hosts == ["b.example.org", "c.example.org", "e.example.org"]


def test_typescript_template_placeholders_and_local_addresses_are_not_hosts():
    src = "const a = `https://${host}/x`; const b = 'http://localhost:8787'; const c = `https://real.example.com/${p}`;\n"
    assert [h for h, _ in ec.hosts_in(src, kotlin=False)] == ["real.example.com"]


def test_a_clean_repository_passes(tmp_path):
    root = make_repo(tmp_path)
    assert run(root) == []
    found = ec.scan(root).hosts
    assert "only-in-a-test.example.org" not in found
    assert "only-in-a-device-test.example.org" not in found
    assert "only-in-a-tier-test.example.org" not in found
    assert "only-in-a-variant-device-test.example.org" not in found
    assert "commented.example.org" not in found and "block.example.org" not in found
    assert found["tile.example.org"] == ["android/app/src/main/java/app/A.kt:4"]


def test_a_new_host_in_the_code_fails_with_where_it_is(tmp_path):
    root = make_repo(tmp_path, ts="const u = `https://api.here.example.org/v7?${q}`;\nfetch(\"https://new.example.net/collect\");\n"
                     "export const DEFAULT_PUSH_HOSTS = [\"push.example.org\", \"push2.example.org\"];\n")
    errors = run(root)
    assert len(errors) == 1
    assert "new.example.net" in errors[0] and "comms-worker/src/x.ts:2" in errors[0]


def test_a_host_no_longer_in_the_code_fails(tmp_path):
    root = make_repo(tmp_path)
    egress = json.loads((root / "docs/egress.json").read_text())
    egress["links"]["gone.example.org"] = "was removed"
    (root / "docs/egress.json").write_text(json.dumps(egress))
    assert any("gone.example.org" in e and "no app or relay source" in e for e in run(root))


def test_a_host_under_two_headings_fails(tmp_path):
    root = make_repo(tmp_path)
    egress = json.loads((root / "docs/egress.json").read_text())
    egress["not_fetched"]["invite.example.org"] = "twice"
    (root / "docs/egress.json").write_text(json.dumps(egress))
    assert any("both" in e for e in run(root))


def test_the_table_and_the_list_must_have_the_same_rows(tmp_path):
    extra_row = SECURITY.replace(
        "| **HERE and TomTom**, via the relay |",
        "| **Somewhere new** | Sometimes | Something | Someone |\n| **HERE and TomTom**, via the relay |",
    )
    root = make_repo(tmp_path, security=extra_row)
    assert any("'Somewhere new'" in e and "does not list" in e for e in run(root))

    missing_row = SECURITY.replace("| **HERE and TomTom**, via the relay | Navigating | A box | The relay |\n", "")
    root2 = make_repo(tmp_path / "two", security=missing_row)
    assert any("'HERE and TomTom, via the relay'" in e and "does not have" in e for e in run(root2))


def test_a_destination_must_say_who_contacts_it(tmp_path):
    root = make_repo(tmp_path)
    egress = json.loads((root / "docs/egress.json").read_text())
    egress["destinations"][1]["from"] = "somebody"
    (root / "docs/egress.json").write_text(json.dumps(egress))
    assert any("\"from\"" in e for e in run(root))


def test_the_table_needs_its_anchor(tmp_path):
    root = make_repo(tmp_path, security=SECURITY.replace("{#egress}", ""))
    try:
        run(root)
    except ValueError as e:
        assert "{#egress}" in str(e)
    else:
        raise AssertionError("a security page without the egress anchor passed")


def test_this_repository_passes():
    root = HERE.parents[1]
    if not (root / "docs/egress.json").exists():
        return
    assert run(root) == []
