"""wait_for_runs.py: the gate's verdict over a pull request's workflow runs, the release's
check for an emulator run on the same tree, the loop that waits for both, and the tree
comparison against a real git repository."""
import importlib.util
import pathlib
import shutil
import subprocess

import pytest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("wait_for_runs", HERE / "wait_for_runs.py")
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

GATE = 7


def run(id, workflow, status="completed", conclusion="success", number=1, event="pull_request", sha="a" * 40, attempt=1):
    return {
        "id": id, "workflow_id": workflow, "path": f".github/workflows/w{workflow}.yml", "name": f"W{workflow}",
        "status": status, "conclusion": conclusion if status == "completed" else None, "run_number": number,
        "run_attempt": attempt, "event": event, "head_sha": sha, "created_at": f"2026-11-01T00:00:{id:02d}Z",
    }


# ── the gate ────────────────────────────────────────────────────────────────


def test_waits_while_any_other_workflow_is_still_running():
    verdict, considered = w.pull_request_verdict([run(1, 1), run(2, 2, status="in_progress")], GATE)
    assert verdict == "wait"
    assert [r["id"] for r in considered] == [1, 2]


def test_passes_when_every_other_run_succeeded_or_was_skipped():
    runs = [run(1, 1), run(2, 2, conclusion="skipped"), run(3, 3, conclusion="neutral")]
    assert w.pull_request_verdict(runs, GATE)[0] == "pass"


@pytest.mark.parametrize("conclusion", ["failure", "cancelled", "timed_out", "action_required", "startup_failure"])
def test_fails_when_a_run_did_not_pass(conclusion):
    assert w.pull_request_verdict([run(1, 1), run(2, 2, conclusion=conclusion)], GATE)[0] == "fail"


def test_its_own_workflow_is_left_out_even_while_it_runs():
    runs = [run(1, 1), run(9, GATE, status="in_progress"), run(8, GATE, conclusion="failure")]
    verdict, considered = w.pull_request_verdict(runs, GATE)
    assert verdict == "pass"
    assert [r["id"] for r in considered] == [1]


def test_a_newer_run_of_a_workflow_replaces_an_older_one():
    # Closing and reopening a pull request starts every workflow again on the same commit.
    old_failed = run(1, 1, conclusion="failure", number=10)
    new_passed = run(2, 1, number=11)
    assert w.pull_request_verdict([new_passed, old_failed], GATE)[0] == "pass"
    assert w.pull_request_verdict([run(1, 1, number=10), run(2, 1, conclusion="failure", number=11)], GATE)[0] == "fail"


def test_no_other_workflow_at_all_passes():
    assert w.pull_request_verdict([run(9, GATE, status="in_progress")], GATE) == ("pass", [])


# ── the release's emulator check ────────────────────────────────────────────


def test_a_passing_push_run_on_the_same_tree_passes():
    runs = [run(2, 1, event="push", sha="b" * 40), run(1, 1, event="push", sha="c" * 40)]
    verdict, found = w.covered_verdict(runs, covers=lambda sha: sha == "c" * 40)
    assert verdict == "pass" and found["id"] == 1


def test_a_run_started_by_hand_counts():
    verdict, found = w.covered_verdict([run(1, 1, event="workflow_dispatch")], covers=lambda sha: True)
    assert verdict == "pass"


def test_pull_request_runs_never_count():
    # They test a merge with the base branch, not the commit they are reported on.
    assert w.covered_verdict([run(1, 1, event="pull_request")], covers=lambda sha: True) == ("fail", None)


def test_waits_for_a_covering_run_still_going():
    runs = [run(3, 1, event="push", status="in_progress"), run(2, 1, event="push", conclusion="failure")]
    verdict, found = w.covered_verdict(runs, covers=lambda sha: True)
    assert verdict == "wait" and found["id"] == 3


def test_any_passing_covering_run_is_enough_even_after_a_failed_one():
    runs = [run(3, 1, event="push", conclusion="failure"), run(2, 1, event="workflow_dispatch")]
    assert w.covered_verdict(runs, covers=lambda sha: True)[0] == "pass"


def test_fails_when_only_other_trees_passed_or_only_failures_cover_it():
    runs = [run(3, 1, event="push", conclusion="failure", sha="b" * 40), run(2, 1, event="push", sha="c" * 40)]
    assert w.covered_verdict(runs, covers=lambda sha: sha == "b" * 40) == ("fail", None)


# ── the waiting loop ────────────────────────────────────────────────────────


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    def sleep(self, seconds):
        self.t += seconds


def test_a_verdict_counts_only_once_it_holds_over_the_same_runs_twice():
    looks = iter([
        ("wait", "a", None),
        ("pass", "b", ("one",)),
        # A run GitHub created a moment late: the same verdict over new runs starts again.
        ("pass", "c", ("one", "two")),
        ("pass", "d", ("one", "two")),
    ])
    clock = Clock()
    verdict, details = w.wait_for(lambda: next(looks), interval=60, deadline=10_000, settle=2, clock=clock, sleep=clock.sleep)
    assert (verdict, details) == ("pass", "d")
    assert clock.t == 180


def test_settle_one_decides_at_once():
    clock = Clock()
    assert w.wait_for(lambda: ("fail", None, None), interval=60, deadline=1, settle=1, clock=clock, sleep=clock.sleep) == ("fail", None)
    assert clock.t == 0


def test_gives_up_at_the_deadline():
    clock = Clock()
    verdict, details = w.wait_for(lambda: ("wait", "still", None), interval=60, deadline=300, settle=2, clock=clock, sleep=clock.sleep)
    assert (verdict, details) == ("timeout", "still")
    assert clock.t <= 300


# ── comparing trees ─────────────────────────────────────────────────────────


@pytest.mark.skipif(shutil.which("git") is None, reason="needs git")
def test_same_paths_compares_only_the_paths_named(tmp_path, monkeypatch):
    def git(*args):
        return subprocess.run(["git", *args], cwd=tmp_path, check=True, capture_output=True, text=True).stdout.strip()

    git("init", "-q")
    git("config", "user.email", "t@example.com")
    git("config", "user.name", "t")
    git("config", "commit.gpgsign", "false")
    (tmp_path / "android").mkdir()
    (tmp_path / "android" / "app.kt").write_text("one\n")
    (tmp_path / "README.md").write_text("one\n")
    git("add", ".")
    git("commit", "-q", "-m", "first")
    first = git("rev-parse", "HEAD")
    (tmp_path / "README.md").write_text("two\n")
    git("commit", "-q", "-am", "docs only")
    docs = git("rev-parse", "HEAD")
    (tmp_path / "android" / "app.kt").write_text("two\n")
    git("commit", "-q", "-am", "app change")
    app = git("rev-parse", "HEAD")

    monkeypatch.chdir(tmp_path)
    assert w.same_paths(first, docs, ["android"])
    assert not w.same_paths(first, app, ["android"])
    assert not w.same_paths(first, docs, ["android", "README.md"])
    assert not w.same_paths("0" * 40, app, ["android"])


def test_snapshot_ignores_order_and_sees_a_rerun():
    a, b = run(1, 1), run(2, 2)
    assert w.snapshot([a, b]) == w.snapshot([b, a])
    assert w.snapshot([a, b]) != w.snapshot([a, run(2, 2, attempt=2)])
