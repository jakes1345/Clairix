#!/usr/bin/env python3
"""Which regions the next round of the Maps build has to build again, on new runners.

usage:
  retry_plan.py --regions JSON --attempt N --round N < artifact-names

A build job whose runner could not reach download.geofabrik.de at all
(fetch_extract.sh exit 75) uploads an empty artifact named
refused-a<attempt>-r<round>-<slug> and stops without failing. This reads the
names of the run's artifacts, one to a line, and prints as JSON the regions
from --regions (the list that round built) that left such an artifact in that
attempt and round, in the order --regions has them. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys


def slug(region: str) -> str:
    """The slug the workflow names a region's files and artifacts by."""
    return region.replace("/", "-")


def marker(attempt: int, round_: int, region: str) -> str:
    """The artifact name a refused build job leaves for its region."""
    return f"refused-a{attempt}-r{round_}-{slug(region)}"


def regions_to_retry(regions: list[str], names: list[str], attempt: int, round_: int) -> list[str]:
    left = {n.strip() for n in names}
    return [r for r in regions if marker(attempt, round_, r) in left]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--regions", required=True, help="JSON list of the regions that round built")
    p.add_argument("--attempt", type=int, required=True, help="the run attempt (github.run_attempt)")
    p.add_argument("--round", type=int, required=True, dest="round_", help="the round to look at")
    args = p.parse_args(argv)
    regions = json.loads(args.regions)
    if not isinstance(regions, list) or not all(isinstance(r, str) for r in regions):
        p.error("--regions must be a JSON list of strings")
    print(json.dumps(regions_to_retry(regions, sys.stdin.read().splitlines(), args.attempt, args.round_)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
