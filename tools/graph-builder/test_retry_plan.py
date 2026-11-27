import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import retry_plan  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "retry_plan.py"
REGIONS = ["north-america/us/california", "north-america/us/new-york", "north-america/us/utah"]


def test_the_marker_names_the_attempt_round_and_slug():
    assert retry_plan.marker(1, 2, "north-america/us/new-york") == "refused-a1-r2-north-america-us-new-york"


def test_only_regions_this_round_left_a_marker_for_are_built_again():
    names = [
        "graph-north-america-us-california",
        "tosign-north-america-us-california",
        "refused-a1-r1-north-america-us-utah",
        "refused-a1-r1-north-america-us-new-york",
    ]
    assert retry_plan.regions_to_retry(REGIONS, names, 1, 1) == ["north-america/us/new-york", "north-america/us/utah"]


@pytest.mark.parametrize("attempt, round_", [(2, 1), (1, 2)])
def test_markers_from_another_attempt_or_round_are_ignored(attempt, round_):
    names = ["refused-a1-r1-north-america-us-utah"]
    assert retry_plan.regions_to_retry(REGIONS, names, attempt, round_) == []


def test_a_marker_for_a_region_not_in_the_round_is_ignored():
    assert retry_plan.regions_to_retry(REGIONS, ["refused-a1-r1-north-america-us-ohio"], 1, 1) == []


def test_the_script_prints_json_the_workflow_can_use_as_a_matrix():
    names = "graph-north-america-us-utah\nrefused-a3-r2-north-america-us-california\n"
    r = subprocess.run(
        [sys.executable, str(SCRIPT), "--regions", json.dumps(REGIONS), "--attempt", "3", "--round", "2"],
        input=names, capture_output=True, text=True, check=True,
    )
    assert json.loads(r.stdout) == ["north-america/us/california"]


def test_nothing_refused_prints_an_empty_list():
    r = subprocess.run(
        [sys.executable, str(SCRIPT), "--regions", json.dumps(REGIONS), "--attempt", "1", "--round", "1"],
        input="", capture_output=True, text=True, check=True,
    )
    assert r.stdout.strip() == "[]"


def test_regions_must_be_a_list_of_strings():
    r = subprocess.run(
        [sys.executable, str(SCRIPT), "--regions", '{"a": 1}', "--attempt", "1", "--round", "1"],
        input="", capture_output=True, text=True,
    )
    assert r.returncode != 0
