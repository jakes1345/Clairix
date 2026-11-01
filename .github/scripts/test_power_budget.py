"""power_budget.py over dumps in the formats AOSP's services print (Android 12 to 15)."""
import importlib.util
import pathlib
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("power_budget", HERE / "power_budget.py")
pb = importlib.util.module_from_spec(spec)
# Its dataclasses look themselves up by module name.
sys.modules["power_budget"] = pb
spec.loader.exec_module(pb)

UID = 10123

BATTERYSTATS = """\
Battery History (0% used, 1234 used of 4096KB, 12 strings using 1016):
                    0 (14) RESET:TIME: 2026-11-01-10-00-00

Per-PID Stats:

Statistics since last charge:
  System starts: 0, currently on battery: true
  Estimated battery capacity: 3000 mAh
  Time on battery: 10m 2s 15ms (100.0%) realtime, 10m 2s 15ms (100.0%) uptime
  Time on battery screen off: 10m 0s 1ms (99.7%) realtime, 1m 3s 2ms (10.5%) uptime

  u0a123:
    Bluetooth Scan (total blamed realtime): 9m 58s 100ms  (1 times)
    Bluetooth Scan (total actual realtime): 9m 58s 100ms  (1 times)
    Bluetooth Scan (background realtime): 9m 58s 100ms  (1 times)
    Bluetooth Scan Results: 812 (812 in background)
    Wake lock *alarm*: 120ms partial (1 times) max=120 actual=120 realtime
    Wake lock clairix:tls-canary: 3s 401ms partial (1 times) max=3401 actual=3401 realtime
    TOTAL wake: 3s 521ms blamed partial, 3s 600ms actual partial realtime
    Wakeup alarm *walarm*:app.clairix.TLS_CANARY: 1 times
    Sensor GPS: 4m 0s 12ms realtime (3 times)
    Foreground service for: 10m 2s 15ms
    Apk app.clairix:
      1 wakeup alarms
      Service app.clairix.ScanService:
        Created for: 10m 2s 15ms uptime
"""

POWER = """\
POWER MANAGER (dumpsys power)

Power Manager State:
  mDirty=0x0

Wake Locks: size=2
  PARTIAL_WAKE_LOCK              'AudioMix' ACQ=-1h2m3s (uid=1041)
  PARTIAL_WAKE_LOCK              'clairix:relay-probe' ACQ=-2s15ms (uid=10123 pid=4321)

Suspend Blockers: size=4
"""

ALARM = """\
Current Alarm Manager state:
  Pending alarm batches: 2
Batch{3a1 num=1 start=123 end=456 flgs=0x8}:
    ELAPSED_WAKEUP #0: Alarm{8d1c3a2 type 2 origWhen 123456 whenElapsed 123456 app.clairix}
      tag=*walarm*:app.clairix.TLS_CANARY
      type=ELAPSED_WAKEUP origWhen=+3h59m whenElapsed=+3h59m window=+30m repeatInterval=0 count=0 flags=0x0
    ELAPSED #1: Alarm{8d1c3a3 type 3 origWhen 123456 whenElapsed 123456 com.android.phone}
      tag=*alarm*:com.android.phone.X
      type=ELAPSED origWhen=+1m whenElapsed=+1m window=0 repeatInterval=60000 count=0 flags=0x0
"""

LOCATION_SETTLED_34 = """\
Location Manager State:
  GNSS:
    Navigation Message Provider:
      listeners:
    Measurements Provider:
      service: GnssMeasurementsProvider
      listeners:
        10123/app.clairix/7a3bd [GnssMeasurementRequest[passive]]
        10077/com.example.maps/1b2c [GnssMeasurementRequest[@1s0ms]] (inactive)
    Status Provider:
      listeners:
        10123/app.clairix/91ee
"""

LOCATION_RUNNING = """\
Location Manager State:
  GNSS:
    Measurements Provider:
      listeners:
        10123/app.clairix/7a3bd [GnssMeasurementRequest[@1s0ms]]
"""

LOCATION_RARE_33 = """\
  GNSS:
    Measurements Provider:
      listeners:
        10123/app.clairix/7a3bd
          request: GnssMeasurementRequest[@24d20h31m23s647ms]
"""


def write(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text)
    return str(p)


# ── parsing ─────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("text,seconds", [
    ("10m 2s 15ms", 602.015),
    ("-2s15ms", 2.015),
    ("1h2m3s", 3723.0),
    ("24d20h31m23s647ms", 2147483.647),
    ("120ms", 0.12),
])
def test_durations_in_both_spellings(text, seconds):
    assert pb.parse_duration(text) == pytest.approx(seconds)


def test_uid_labels_are_batterystats_names():
    assert pb.uid_label(10123) == "u0a123"
    assert pb.uid_label(1010123) == "u10a123"
    assert pb.uid_label(1000) == "1000"


def test_batterystats_counts():
    s = pb.parse_batterystats(BATTERYSTATS, UID)
    assert s.found_uid
    assert s.on_battery_s == pytest.approx(602.015)
    assert s.wake_lock_s == pytest.approx(3.521)  # the TOTAL line, blamed
    assert s.wake_locks["clairix:tls-canary"] == pytest.approx(3.401)
    assert s.wakeup_alarms == 1
    assert s.ble_scan_s == pytest.approx(598.1)
    assert pb.windows(s.on_battery_s) == 1
    assert pb.windows(1199) == 2
    assert pb.windows(30) == 1
    assert pb.alarm_budget(1) == 2


def test_wake_locks_are_summed_without_a_total_line():
    text = BATTERYSTATS.replace("    TOTAL wake: 3s 521ms blamed partial, 3s 600ms actual partial realtime\n", "")
    assert pb.parse_batterystats(text, UID).wake_lock_s == pytest.approx(3.521)


def test_another_apps_section_is_not_ours():
    s = pb.parse_batterystats(BATTERYSTATS, 10124)
    assert not s.found_uid
    assert s.wake_lock_s == 0


def test_held_wake_locks_of_the_app_only():
    held = pb.parse_power(POWER, UID)
    assert [(h.tag, h.long) for h in held] == [("clairix:relay-probe", False)]
    assert held[0].held_s == pytest.approx(2.015)


def test_a_lock_held_on_the_apps_behalf_counts():
    text = POWER.replace("(uid=1041)", "(uid=1000 ws=WorkSource{10123})")
    assert [h.tag for h in pb.parse_power(text, UID)] == ["AudioMix", "clairix:relay-probe"]


def test_pending_wakeup_alarms_of_the_app_only():
    alarms = pb.parse_alarms(ALARM)
    assert [(a.tag, a.type, a.repeat_ms) for a in alarms] == [("*walarm*:app.clairix.TLS_CANARY", "ELAPSED_WAKEUP", 0)]


def test_measurement_requests():
    passive = pb.parse_measurements(LOCATION_SETTLED_34)
    assert len(passive) == 1 and passive[0].passive
    running = pb.parse_measurements(LOCATION_RUNNING)
    assert len(running) == 1 and not running[0].passive and not running[0].inactive
    rare = pb.parse_measurements(LOCATION_RARE_33)
    assert len(rare) == 1 and rare[0].passive


def test_measurement_lines_outside_a_named_section_are_still_found():
    text = "  some header:\n    10123/app.clairix/aa GnssMeasurementRequest[@1s0ms]\n"
    assert not pb.parse_measurements(text)[0].passive


# ── the verdict ─────────────────────────────────────────────────────────────


def run(tmp_path, **files_and_flags):
    argv = ["--uid", str(UID), "--label", "test"]
    for key in ("batterystats", "power", "alarm", "location"):
        if key in files_and_flags:
            argv += [f"--{key}", write(tmp_path, f"{key}.txt", files_and_flags[key])]
    if files_and_flags.get("no_ble"):
        argv.append("--no-ble")
    if files_and_flags.get("settled"):
        argv.append("--settled")
    argv += ["--report", str(tmp_path / "report.json")]
    return pb.main(argv)


def test_a_quiet_scanner_window_passes(tmp_path):
    assert run(tmp_path, batterystats=BATTERYSTATS, power=POWER, alarm=ALARM, location=LOCATION_SETTLED_34, settled=True) == 0
    assert (tmp_path / "report.json").exists()


def test_too_much_wake_lock_time_fails(tmp_path, capsys):
    text = BATTERYSTATS.replace("TOTAL wake: 3s 521ms", "TOTAL wake: 2m 1s 0ms")
    assert run(tmp_path, batterystats=text) == 1
    assert "partial wake locks 121.0 s" in capsys.readouterr().out


def test_too_many_wakeup_alarms_fail(tmp_path):
    text = BATTERYSTATS.replace("TLS_CANARY: 1 times", "TLS_CANARY: 3 times").replace("1 wakeup alarms", "3 wakeup alarms")
    assert run(tmp_path, batterystats=text) == 1


def test_a_window_on_the_charger_cannot_pass(tmp_path, capsys):
    text = BATTERYSTATS.replace("  Time on battery: 10m 2s 15ms (100.0%) realtime, 10m 2s 15ms (100.0%) uptime\n", "")
    assert run(tmp_path, batterystats=text) == 1
    assert "on the charger" in capsys.readouterr().out


def test_bluetooth_scan_time_after_the_scanner_stopped_fails(tmp_path):
    assert run(tmp_path, batterystats=BATTERYSTATS, no_ble=True) == 1
    no_scan = "\n".join(line for line in BATTERYSTATS.splitlines() if "Bluetooth Scan" not in line)
    assert run(tmp_path, batterystats=no_scan, no_ble=True) == 0


def test_a_long_held_wake_lock_fails(tmp_path):
    assert run(tmp_path, power=POWER.replace("ACQ=-2s15ms", "ACQ=-1m30s2ms")) == 1
    assert run(tmp_path, power=POWER.replace("ACQ=-2s15ms (uid=10123", "ACQ=-20s LONG (uid=10123")) == 1


def test_a_wakeup_alarm_repeating_under_ten_minutes_fails(tmp_path):
    assert run(tmp_path, alarm=ALARM.replace("window=+30m repeatInterval=0", "window=+30m repeatInterval=300000")) == 1


def test_a_settled_scanner_holding_the_receiver_on_fails(tmp_path):
    assert run(tmp_path, location=LOCATION_RUNNING, settled=True) == 1
    # Not settled, the same request is only recorded.
    assert run(tmp_path, location=LOCATION_RUNNING) == 0
    # No registration at all (Android 12, settled) passes.
    assert run(tmp_path, location="Location Manager State:\n  GNSS:\n    Measurements Provider:\n      listeners:\n", settled=True) == 0


# What Android 15 printed after the scanner had asked once a second while moving
# and then stopped: the live section is empty; history and the event log still
# name the old request, which holds nothing on now.
LOCATION_HISTORY_ONLY_35 = """\
  Historical Aggregate Gnss Measurement Provider Data:
    10123/app.clairix: min/max interval = 1s/1s, total duration = +3m46s787ms, tracking mode = always duty-cycling, GNSS measurement events = 226
  GNSS Manager:
    Measurements Provider:
      service: unregistered
      last measurements=[elapsedRealtimeNs=139287 measurementCount=7 avgBasebandCn0=27.143293]
  Event Log:
    11-01 16:43:21.221: gnss measurements +registration 10123/app.clairix/D47B5412 -> GnssMeasurementRequest[@+1s0ms]
    11-01 16:44:38.239: gnss measurements delivered GnssMeasurements[7] to 10123/app.clairix/D47B5412
    11-01 16:47:08.008: gnss measurements -registration 10123/app.clairix/D47B5412
"""


def test_history_and_removed_registrations_are_not_live_requests():
    assert pb.parse_measurements(LOCATION_HISTORY_ONLY_35) == []


def test_an_event_log_registration_never_removed_is_live():
    text = "  Event Log:\n    11-01 16:43:21.221: gnss measurements +registration 10123/app.clairix/D4 -> GnssMeasurementRequest[@+1s0ms]\n"
    found = pb.parse_measurements(text)
    assert len(found) == 1 and not found[0].passive
    removed = text + "    11-01 16:47:08.008: gnss measurements -registration 10123/app.clairix/D4\n"
    assert pb.parse_measurements(removed) == []


def test_the_failing_android_15_dump_now_passes(tmp_path):
    assert run(tmp_path, location=LOCATION_HISTORY_ONLY_35, settled=True) == 0


def test_a_registration_still_open_in_the_event_log_counts_even_with_an_empty_section():
    text = LOCATION_HISTORY_ONLY_35.replace("    11-01 16:47:08.008: gnss measurements -registration 10123/app.clairix/D47B5412\n", "")
    found = pb.parse_measurements(text)
    assert len(found) == 1 and not found[0].passive and not found[0].inactive
