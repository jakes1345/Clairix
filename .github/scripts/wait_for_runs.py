#!/usr/bin/env python3
"""Waits for GitHub Actions workflow runs and says whether they passed.

Two uses, both reading the Actions API with the job's own token (actions: read),
standard library only:

  wait_for_runs.py pull-request --sha HEAD_SHA [--wait-minutes N]
      The Gate workflow (.github/workflows/gate.yml). Waits until every other
      workflow run that the pull request started on HEAD_SHA has finished, and
      passes only if each of them succeeded (a run whose jobs were all skipped
      counts as passed). A workflow that a pull request's paths do not concern
      starts no run and is not waited for, which is why the main ruleset can
      require this one check instead of every workflow's jobs (docs/PROCESS.md).

  wait_for_runs.py covered --sha SHA --workflow FILE --paths PATH... [--wait-minutes N]
      The Release workflow's emulator job. Passes once a run of FILE that a push
      or a person started has succeeded on a commit where every PATH is the same
      as at SHA, waits while such a run is still going, and fails when there is
      none: a release is never signed without the emulator suite having passed on
      what it ships, and never makes the suite run twice on one tree. Pull request
      runs do not count: they test a merge with the base branch, not the commit
      they are reported on. Needs the full history (checkout fetch-depth: 0).

Exit status 0 means passed; 1 means failed or ran out of time.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Iterable

# A finished run passed if it succeeded, or if every job in it was skipped.
PASSED = {"success", "skipped", "neutral"}

# Runs that test the commit they are reported on: a push tests its head commit,
# a run started by hand tests the commit of the ref it was started on.
COVERING_EVENTS = {"push", "workflow_dispatch"}


def latest_per_workflow(runs: Iterable[dict]) -> list[dict]:
    """The newest run of each workflow. A re-run keeps its run's id and number
    and the API reports its latest attempt, so the highest number is current."""
    best: dict[int, dict] = {}
    for run in runs:
        key = run["workflow_id"]
        if key not in best or run["run_number"] > best[key]["run_number"]:
            best[key] = run
    return sorted(best.values(), key=lambda r: (r.get("path") or "", r["id"]))


def pull_request_verdict(runs: Iterable[dict], own_workflow_id: int) -> tuple[str, list[dict]]:
    """("wait" | "pass" | "fail", the runs it was decided on) for the runs a
    pull request started, leaving out every run of the gate's own workflow."""
    latest = latest_per_workflow(r for r in runs if r["workflow_id"] != own_workflow_id)
    if any(r["status"] != "completed" for r in latest):
        return "wait", latest
    if all(r["conclusion"] in PASSED for r in latest):
        return "pass", latest
    return "fail", latest


def snapshot(runs: Iterable[dict]) -> tuple:
    """What has to stay the same between two looks before the gate decides."""
    return tuple(sorted((r["id"], r.get("run_attempt", 1), r["status"], r.get("conclusion")) for r in runs))


def covered_verdict(runs: Iterable[dict], covers: Callable[[str], bool]) -> tuple[str, dict | None]:
    """("pass", the passing run) | ("wait", a covering run still going) | ("fail", None).

    runs are the workflow's runs, newest first; covers(sha) says whether that
    commit has the same paths as the one being released."""
    going = None
    for run in runs:
        if run["event"] not in COVERING_EVENTS or not covers(run["head_sha"]):
            continue
        if run["status"] == "completed":
            if run["conclusion"] == "success":
                return "pass", run
        elif going is None:
            going = run
    if going is not None:
        return "wait", going
    return "fail", None


def wait_for(
    look: Callable[[], tuple[str, object, tuple]],
    *,
    interval: float,
    deadline: float,
    settle: int,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> tuple[str, object]:
    """Calls look() until it gives the same final verdict over the same state
    `settle` times in a row, sleeping `interval` seconds between looks.

    look() returns (verdict, details, state). A run that GitHub creates a moment
    after the others changes the state and starts the count again. Gives
    ("timeout", details) at the deadline."""
    seen = None
    count = 0
    details: object = None
    while True:
        verdict, details, state = look()
        if verdict in ("pass", "fail"):
            count = count + 1 if seen == (verdict, state) else 1
            seen = (verdict, state)
            if count >= settle:
                return verdict, details
        else:
            seen, count = None, 0
        if clock() + interval > deadline:
            return "timeout", details
        sleep(interval)


# ── GitHub and git ──────────────────────────────────────────────────────────


def api_get(path: str) -> dict:
    """GET a repository API path with the job's token, retrying what may pass."""
    base = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    repo = os.environ["GITHUB_REPOSITORY"]
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN", "")
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "clairix-wait-for-runs",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{base}/repos/{repo}{path}", headers=headers)
    error: Exception | None = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                raise
            error = e
        except urllib.error.URLError as e:
            error = e
        time.sleep(10 * (attempt + 1))
    assert error is not None
    raise error


def list_runs(path: str, params: dict, pages: int = 3) -> list[dict]:
    """Workflow runs from a runs listing, newest first, up to `pages` pages of 100."""
    runs: list[dict] = []
    for page in range(1, pages + 1):
        query = urllib.parse.urlencode({**params, "per_page": 100, "page": page})
        batch = api_get(f"{path}?{query}").get("workflow_runs", [])
        runs.extend(batch)
        if len(batch) < 100:
            break
    return runs


def same_paths(sha: str, target: str, paths: list[str]) -> bool:
    """Whether every path is the same at sha as at target, in this checkout."""
    if subprocess.run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], capture_output=True).returncode != 0:
        return False  # not in this history: a deleted branch's commit, say
    return subprocess.run(["git", "diff", "--quiet", sha, target, "--", *paths]).returncode == 0


def describe(run: dict) -> str:
    conclusion = run.get("conclusion") or run["status"]
    return f"{run.get('name') or run.get('path')}: {conclusion} ({run.get('html_url', '')})"


def summarise(lines: list[str]) -> None:
    for line in lines:
        print(line)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


def run_pull_request(args: argparse.Namespace) -> int:
    own = api_get(f"/actions/runs/{os.environ['GITHUB_RUN_ID']}")
    own_workflow = own["workflow_id"]

    def look():
        runs = list_runs("/actions/runs", {"head_sha": args.sha, "event": "pull_request"})
        verdict, considered = pull_request_verdict(runs, own_workflow)
        if verdict == "wait":
            going = [r.get("name") or r.get("path") for r in considered if r["status"] != "completed"]
            print(f"Waiting for: {', '.join(going)}", flush=True)
        return verdict, considered, snapshot(considered)

    time.sleep(args.first_look)
    verdict, considered = wait_for(
        look, interval=args.interval, deadline=time.monotonic() + args.wait_minutes * 60, settle=2
    )
    lines = [f"Workflow runs for {args.sha[:12]}:"] + [f"- {describe(r)}" for r in considered or []]
    if verdict == "pass":
        summarise(lines + ["Every workflow this pull request started passed."])
        return 0
    if verdict == "timeout":
        summarise(lines + [f"Gave up after {args.wait_minutes} minutes; run this job again once the others have finished."])
        return 1
    failed = [r for r in considered if r.get("conclusion") not in PASSED]
    summarise(lines + ["Did not pass: " + ", ".join(r.get("name") or r.get("path") for r in failed)])
    return 1


def run_covered(args: argparse.Namespace) -> int:
    cache: dict[str, bool] = {}

    def covers(sha: str) -> bool:
        if sha not in cache:
            cache[sha] = same_paths(sha, args.sha, args.paths)
        return cache[sha]

    def look():
        runs = []
        for event in sorted(COVERING_EVENTS):
            runs += list_runs(f"/actions/workflows/{args.workflow}/runs", {"event": event}, pages=1)
        runs.sort(key=lambda r: r["created_at"], reverse=True)
        verdict, run = covered_verdict(runs, covers)
        if verdict == "wait":
            print(f"Waiting for {describe(run)}", flush=True)
        return verdict, run, (run or {}).get("id")

    verdict, run = wait_for(look, interval=args.interval, deadline=time.monotonic() + args.wait_minutes * 60, settle=1)
    paths = ", ".join(args.paths)
    if verdict == "pass":
        summarise([f"{args.workflow} passed on {run['head_sha'][:12]}, where {paths} are the same as at {args.sha[:12]}: {describe(run)}"])
        return 0
    if verdict == "timeout":
        summarise([f"{args.workflow} was still running after {args.wait_minutes} minutes ({describe(run)}); run this job again once it has finished."])
        return 1
    summarise([
        f"No run of {args.workflow} has passed on a commit where {paths} are the same as at {args.sha[:12]}.",
        f"Start it by hand on this ref (Actions, {args.workflow}, Run workflow), and run this release again once it has passed.",
    ])
    return 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    pr = sub.add_parser("pull-request", help="wait for every workflow a pull request started")
    pr.add_argument("--sha", required=True, help="the pull request's head commit")
    pr.add_argument("--first-look", type=float, default=30, help="seconds to wait before the first look")
    cov = sub.add_parser("covered", help="require a passing run of one workflow on the same tree")
    cov.add_argument("--sha", required=True, help="the commit being released")
    cov.add_argument("--workflow", required=True, help="workflow file name, e.g. emulator.yml")
    cov.add_argument("--paths", nargs="+", required=True, help="paths whose content must match")
    for p in (pr, cov):
        p.add_argument("--wait-minutes", type=float, default=300, help="give up after this long")
        p.add_argument("--interval", type=float, default=60, help="seconds between looks")
    args = ap.parse_args(argv)
    return run_pull_request(args) if args.mode == "pull-request" else run_covered(args)


if __name__ == "__main__":
    sys.exit(main())
