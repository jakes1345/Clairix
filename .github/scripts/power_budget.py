#!/usr/bin/env python3
"""Checks Clairix's power budget from the platform's own counters on the emulator.

The emulator has no battery model worth measuring, but Android still counts what an
app does: how long it held wake locks, how many alarms woke the phone for it, how long
it scanned Bluetooth, and which satellite measurement requests it holds. Read after a
window of known length, those counts catch the regressions a device run would find
weeks later: a new timer that wakes the phone, a wake lock that is never released, a
scan left running after the scanner stops, a measurement request that keeps the
satellite receiver on while the phone sits still. The device protocol and its targets
are in docs/audit/POWER.md; this is its cheap, automatic proxy.

  power_budget.py --uid UID --batterystats FILE [--power FILE] [--alarm FILE]
                  [--location FILE] [--no-ble] [--settled] [--label NAME] [--report FILE]

  --batterystats  `dumpsys batterystats --charged app.clairix` after `dumpsys battery
                  unplug` and `dumpsys batterystats --reset` at the window's start.
                  Budgets, per ten minutes on battery (at least one window):
                  partial wake-lock time blamed on the app <= 60 s, wake-up alarms <= 1
                  (plus one in all, for an alarm that falls at both edges of a window).
  --power         `dumpsys power`: no wake lock of the app held for over a minute (the
                  platform marks those LONG). Android does not record the timeout an
                  app passed, so a lock still held after a minute is the sign of one
                  without; WakeLockTimeoutTest checks the sources for the rest.
  --alarm         `dumpsys alarm`: no pending wake-up alarm of the app that repeats more
                  often than every ten minutes.
  --no-ble        The scanner was stopped before the window: no Bluetooth scan time.
  --location      `dumpsys location`, with --settled after the debug broadcast that
                  settles the scanner (app.clairix.debug.FORCE_STILL): no satellite
                  measurement request of the app that switches the receiver on. A
                  passive request (Android 14+) or the longest interval (Android 13)
                  costs nothing and is allowed.

Formats are those of AOSP's BatteryStats, PowerManagerService, AlarmManagerService and
LocationManagerService dumps (Android 12 to 15); test_power_budget.py holds examples.
Exit status 0 when every budget holds, 1 when one does not or a dump cannot be read.
Standard library only.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass, field

PACKAGE = "app.clairix"
WINDOW_S = 600
WAKE_LOCK_BUDGET_S = 60.0
ALARM_BUDGET = 1
LONG_HELD_S = 60.0
MIN_REPEAT_MS = 10 * 60 * 1000
# A measurement interval this long or longer is passive (Android 14's PASSIVE_INTERVAL
# is Integer.MAX_VALUE ms, about 24.8 days) or as good as (Android 13's longest).
PASSIVE_INTERVAL_S = 24 * 3600.0

_UNITS = {"d": 86400.0, "h": 3600.0, "m": 60.0, "s": 1.0, "ms": 0.001}
_DURATION_TOKEN = re.compile(r"(\d+)(ms|d|h|m|s)(?![a-z])")


def parse_duration(text: str) -> float:
    """Seconds in an Android duration: "1h 2m 3s 456ms", "-1m2s15ms", "24d20h31m23s647ms"."""
    total = 0.0
    found = False
    for number, unit in _DURATION_TOKEN.findall(text):
        total += int(number) * _UNITS[unit]
        found = True
    if not found:
        raise ValueError(f"no duration in {text!r}")
    return total


def uid_label(uid: int) -> str:
    """BatteryStats' name for an app uid: 10123 -> u0a123 (user 0)."""
    user, app = divmod(uid, 100000)
    if app < 10000:
        return str(uid)
    return f"u{user}a{app - 10000}"


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _block(lines: list[str], start: int) -> list[str]:
    """The lines after lines[start] indented deeper than it."""
    base = _indent(lines[start])
    out = []
    for line in lines[start + 1:]:
        if line.strip() and _indent(line) <= base:
            break
        out.append(line)
    return out


# ── batterystats ─────────────────────────────────────────────────────────────


@dataclass
class BatteryStats:
    on_battery_s: float | None
    wake_lock_s: float
    wake_locks: dict[str, float] = field(default_factory=dict)
    wakeup_alarms: int = 0
    ble_scan_s: float = 0.0
    found_uid: bool = False


def parse_batterystats(text: str, uid: int) -> BatteryStats:
    lines = text.splitlines()
    on_battery = None
    for line in lines:
        m = re.match(r"\s*Time on battery:\s*(.+?)\s*\(", line)
        if m:
            on_battery = parse_duration(m.group(1))
            break
    label = uid_label(uid)
    section: list[str] | None = None
    for i, line in enumerate(lines):
        if line.strip() == f"{label}:":
            section = _block(lines, i)
            break
    stats = BatteryStats(on_battery_s=on_battery, wake_lock_s=0.0)
    if section is None:
        return stats
    stats.found_uid = True
    total = None
    alarms_by_tag = 0
    alarms_apk = None
    ble_actual = None
    ble_blamed = None
    for raw in section:
        line = raw.strip()
        m = re.match(r"Wake lock (.+?):\s+(\d.*?)\s+partial\b", line)
        if m:
            stats.wake_locks[m.group(1)] = stats.wake_locks.get(m.group(1), 0.0) + parse_duration(m.group(2))
            continue
        m = re.match(r"TOTAL wake:\s*(.+?)\s+(?:blamed\s+)?partial\b", line)
        if m:
            total = parse_duration(m.group(1))
            continue
        m = re.match(r"Wakeup alarm .*:\s*(\d+)\s+times?\b", line)
        if m:
            alarms_by_tag += int(m.group(1))
            continue
        m = re.match(r"(\d+)\s+wakeup alarms?\b", line) or re.match(r"Wakeup alarms:\s*(\d+)", line)
        if m:
            alarms_apk = (alarms_apk or 0) + int(m.group(1))
            continue
        m = re.match(r"Bluetooth Scan \(total actual realtime\):\s*(.+?)\s*\(", line)
        if m:
            ble_actual = parse_duration(m.group(1))
            continue
        m = re.match(r"Bluetooth Scan \(total blamed realtime\):\s*(.+?)\s*\(", line)
        if m:
            ble_blamed = parse_duration(m.group(1))
    stats.wake_lock_s = total if total is not None else sum(stats.wake_locks.values())
    stats.wakeup_alarms = max(alarms_by_tag, alarms_apk or 0)
    stats.ble_scan_s = ble_actual if ble_actual is not None else (ble_blamed or 0.0)
    return stats


def windows(on_battery_s: float) -> int:
    """Ten-minute windows in the time on battery, to the nearest (the dumps take a few seconds), at least one."""
    return max(1, math.floor(on_battery_s / WINDOW_S + 0.5))


def alarm_budget(n_windows: int) -> int:
    """
    Wake-up alarms allowed in [n_windows]: one per window, and one more for the fence
    post. A ten-minute timer (the relay watchdog's) can fire at both ends of a window
    ten minutes long; a two-minute timer, the regression this catches, fires five times.
    """
    return ALARM_BUDGET * n_windows + 1


# ── dumpsys power ────────────────────────────────────────────────────────────


@dataclass
class HeldLock:
    tag: str
    held_s: float
    long: bool


def parse_power(text: str, uid: int) -> list[HeldLock]:
    """The app's wake locks in `dumpsys power`'s "Wake Locks:" list, held directly or on its behalf."""
    lines = text.splitlines()
    held = []
    for i, line in enumerate(lines):
        if not re.match(r"\s*Wake Locks: size=\d+", line):
            continue
        for raw in _block(lines, i) or lines[i + 1:]:
            if not raw.strip():
                break
            ours = re.search(rf"\(uid={uid}\b", raw) or re.search(rf"WorkSource\{{[^}}]*\b{uid}\b", raw)
            if not ours:
                continue
            tag = re.search(r"'([^']*)'", raw)
            acq = re.search(r"ACQ=(-?[0-9dhms]+)", raw)
            held.append(HeldLock(
                tag=tag.group(1) if tag else "?",
                held_s=parse_duration(acq.group(1)) if acq else 0.0,
                long=bool(re.search(r"\bLONG\b", raw)),
            ))
        break
    return held


# ── dumpsys alarm ────────────────────────────────────────────────────────────


@dataclass
class PendingAlarm:
    tag: str
    type: str
    repeat_ms: int


def parse_alarms(text: str, package: str = PACKAGE) -> list[PendingAlarm]:
    """The app's pending wake-up alarms: an `Alarm{... <package>}` line, then its tag and type lines."""
    lines = text.splitlines()
    out = []
    for i, line in enumerate(lines):
        m = re.match(r"\s*(RTC_WAKEUP|ELAPSED_WAKEUP|RTC|ELAPSED)\s+#\d+:\s*Alarm\{.*\s(\S+)\}\s*$", line)
        if not m or m.group(2) != package or not m.group(1).endswith("_WAKEUP"):
            continue
        body = " ".join(_block(lines, i))
        tag = re.search(r"tag=(\S+)", body)
        rep = re.search(r"repeatInterval=(\d+)", body)
        out.append(PendingAlarm(tag.group(1) if tag else "?", m.group(1), int(rep.group(1)) if rep else 0))
    return out


# ── dumpsys location ─────────────────────────────────────────────────────────


@dataclass
class Measurement:
    text: str
    passive: bool
    inactive: bool


def _measurement_cost(block: str) -> tuple[bool, bool]:
    """(passive, inactive) for one registration's text."""
    inactive = bool(re.search(r"\binactive\b", block, re.I))
    if re.search(r"passive", block, re.I):
        return True, inactive
    m = re.search(r"GnssMeasurementRequest\[[^\]]*?@\s*([0-9dhms+]+)", block)
    if m:
        try:
            if parse_duration(m.group(1)) >= PASSIVE_INTERVAL_S:
                return True, inactive
        except ValueError:
            pass
    return False, inactive


def parse_measurements(text: str, package: str = PACKAGE) -> list[Measurement]:
    """The app's raw GNSS measurement registrations in `dumpsys location`."""
    lines = text.splitlines()
    blocks: list[str] = []
    for i, line in enumerate(lines):
        if re.search(r"measurements?\b.*\b(provider|listeners?)\b", line, re.I) and line.rstrip().endswith(":"):
            section = _block(lines, i)
            j = 0
            while j < len(section):
                raw = section[j]
                if package in raw:
                    rest = []
                    for nxt in section[j + 1:]:
                        if nxt.strip() and _indent(nxt) <= _indent(raw):
                            break
                        rest.append(nxt)
                    blocks.append(" ".join([raw.strip()] + [r.strip() for r in rest]))
                    j += 1 + len(rest)
                else:
                    j += 1
    if not blocks:
        # No section by that name in this version's dump: any line naming both the app
        # and a measurement request is one.
        blocks = [line.strip() for line in lines if package in line and "GnssMeasurement" in line]
    out = []
    for b in blocks:
        passive, inactive = _measurement_cost(b)
        out.append(Measurement(b, passive, inactive))
    return out


# ── the verdict ──────────────────────────────────────────────────────────────


def check(args: argparse.Namespace) -> tuple[list[str], dict]:
    problems: list[str] = []
    report: dict = {"label": args.label, "uid": args.uid}

    if args.batterystats:
        stats = parse_batterystats(open(args.batterystats, encoding="utf-8", errors="replace").read(), args.uid)
        report["batterystats"] = {
            "on_battery_s": stats.on_battery_s, "wake_lock_s": stats.wake_lock_s, "wake_locks": stats.wake_locks,
            "wakeup_alarms": stats.wakeup_alarms, "ble_scan_s": stats.ble_scan_s, "found_uid": stats.found_uid,
        }
        if stats.on_battery_s is None or stats.on_battery_s <= 0:
            problems.append("batterystats counted no time on battery: the window ran on the charger (run `dumpsys battery unplug` first)")
        else:
            n = windows(stats.on_battery_s)
            report["windows"] = n
            if stats.wake_lock_s > WAKE_LOCK_BUDGET_S * n:
                detail = ", ".join(f"{k} {v:.1f} s" for k, v in sorted(stats.wake_locks.items(), key=lambda kv: -kv[1]))
                problems.append(
                    f"partial wake locks {stats.wake_lock_s:.1f} s in {stats.on_battery_s:.0f} s on battery, "
                    f"over {WAKE_LOCK_BUDGET_S * n:.0f} s ({WAKE_LOCK_BUDGET_S:.0f} s per 10 min): {detail}"
                )
            if stats.wakeup_alarms > alarm_budget(n):
                problems.append(
                    f"{stats.wakeup_alarms} wake-up alarms in {stats.on_battery_s:.0f} s on battery, "
                    f"over {alarm_budget(n)} ({ALARM_BUDGET} per 10 min, and one at the window's edge)"
                )
        if args.no_ble and stats.ble_scan_s > 0:
            problems.append(f"Bluetooth scan time {stats.ble_scan_s:.1f} s after the scanner stopped")

    if args.power:
        held = parse_power(open(args.power, encoding="utf-8", errors="replace").read(), args.uid)
        report["held_wake_locks"] = [vars(h) for h in held]
        for h in held:
            if h.long or h.held_s > LONG_HELD_S:
                problems.append(f"wake lock '{h.tag}' held for {h.held_s:.0f} s: acquired without a timeout, or one too long")

    if args.alarm:
        alarms = parse_alarms(open(args.alarm, encoding="utf-8", errors="replace").read())
        report["pending_wakeup_alarms"] = [vars(a) for a in alarms]
        for a in alarms:
            if 0 < a.repeat_ms < MIN_REPEAT_MS:
                problems.append(f"wake-up alarm {a.tag} repeats every {a.repeat_ms / 1000:.0f} s, more often than every 10 min")

    if args.location:
        found = parse_measurements(open(args.location, encoding="utf-8", errors="replace").read())
        report["gnss_measurements"] = [vars(m) for m in found]
        if args.settled:
            for m in found:
                if not m.passive and not m.inactive:
                    problems.append(f"a GNSS measurement request that keeps the receiver on while settled: {m.text}")

    report["problems"] = problems
    return problems, report


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--uid", type=int, required=True, help="the app's uid (pm list packages -U app.clairix)")
    p.add_argument("--batterystats")
    p.add_argument("--power")
    p.add_argument("--alarm")
    p.add_argument("--location")
    p.add_argument("--no-ble", action="store_true", help="the scanner was stopped: no Bluetooth scan time allowed")
    p.add_argument("--settled", action="store_true", help="the scanner was settled: no active GNSS measurement request allowed")
    p.add_argument("--label", default="window")
    p.add_argument("--report", help="write the counts and the verdict here as JSON")
    args = p.parse_args(argv)
    try:
        problems, report = check(args)
    except (OSError, ValueError) as e:
        print(f"::error::power budget ({args.label}): {e}")
        return 1
    print(json.dumps(report, indent=2))
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
    for problem in problems:
        print(f"::error::power budget ({args.label}): {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
