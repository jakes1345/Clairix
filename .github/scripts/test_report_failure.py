"""report-failure.sh against a stub GitHub CLI: an open issue with exactly the title gets a
comment, a pull request or a near-miss title does not count, and with none open one issue is
created. Needs bash and jq (both on GitHub's runners); skipped without them."""
import json
import os
import pathlib
import shutil
import subprocess

import pytest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "report-failure.sh"
TITLE = "Work zones publish failing"

pytestmark = pytest.mark.skipif(not (shutil.which("bash") and shutil.which("jq")), reason="needs bash and jq")

# Records every call; answers the issue listing from a file and an issue creation with a URL.
STUB_GH = """#!/bin/sh
printf '%s\\n' "$*" >> "$STUB_LOG"
case "$1 $2" in
  "api --paginate") cat "$STUB_ISSUES" ;;
  "issue create") echo "https://github.com/jakes1345/Clairix/issues/42" ;;
esac
"""


def run(tmp_path: pathlib.Path, pages: list[list[dict]], **env: str):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    gh = bin_dir / "gh"
    gh.write_text(STUB_GH)
    gh.chmod(0o755)
    issues = tmp_path / "issues.json"
    # gh api --paginate prints one JSON array per page, one after another.
    issues.write_text("".join(json.dumps(p) + "\n" for p in pages))
    log = tmp_path / "gh.log"
    log.unlink(missing_ok=True)
    e = {
        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
        "GH_TOKEN": "t", "GITHUB_REPOSITORY": "jakes1345/Clairix", "GITHUB_RUN_ID": "123",
        "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SERVER_URL": "https://github.com", "GITHUB_WORKFLOW": "Work zones",
        "GITHUB_SHA": "0123456789abcdef0123", "GITHUB_EVENT_NAME": "schedule",
        "FAILURE_TITLE": TITLE, "FAILURE_MEANS": "No new work-zone files were published.",
        "STUB_ISSUES": str(issues), "STUB_LOG": str(log),
    }
    e.update(env)
    r = subprocess.run(["bash", str(SCRIPT)], capture_output=True, text=True, env=e)
    return r, (log.read_text() if log.exists() else "")


def test_the_open_issue_with_exactly_that_title_gets_the_run(tmp_path):
    pages = [
        [{"number": 5, "title": TITLE + "!"},                                   # a near miss
         {"number": 6, "title": TITLE, "pull_request": {"url": "https://x"}}],  # a pull request
        [{"number": 7, "title": TITLE}],                                         # on the second page
    ]
    r, calls = run(tmp_path, pages)
    assert r.returncode == 0, r.stderr
    assert "repos/jakes1345/Clairix/issues?state=open&per_page=100" in calls
    assert "issue comment 7 --repo jakes1345/Clairix --body Run [123](https://github.com/jakes1345/Clairix/actions/runs/123)" in calls
    assert "of the **Work zones** workflow failed" in calls and "attempt 2" in calls and "commit 0123456789ab," in calls
    assert "No new work-zone files were published." in calls
    assert "issue create" not in calls
    assert "Added the run to issue #7" in r.stdout


@pytest.mark.parametrize("pages", [[[{"number": 5, "title": "Something else"}]], [[]]])
def test_with_no_such_open_issue_one_is_opened(tmp_path, pages):
    r, calls = run(tmp_path, pages)
    assert r.returncode == 0, r.stderr
    assert f"issue create --repo jakes1345/Clairix --title {TITLE} --body Run [123]" in calls
    assert "issue comment" not in calls
    assert "Opened https://github.com/jakes1345/Clairix/issues/42" in r.stdout


def test_a_missing_title_or_token_stops_before_any_call(tmp_path):
    for missing in ("FAILURE_TITLE", "GH_TOKEN"):
        r, calls = run(tmp_path, [[]], **{missing: ""})
        assert r.returncode != 0 and calls == ""
        assert missing in r.stderr
