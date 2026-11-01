# Power: how Clairix's battery use is measured, the targets, and the results

The website says the scan is designed to run all day. This document is how that
gets checked on real phones: the devices, the method, the scenarios, what to
record, the targets, and the results per release. It also says what the
emulator checks on every pull request and what the app itself shows.

## Results, as of this commit

**No device run is recorded yet.** There is no batterystats capture, Battery
Historian report or Perfetto trace from a real phone in this repository, so no
discharge figure, per-feature cost or mAh number can be stated, and none is.
The design work behind the "all day" aim is in the code and its commit
messages (positions only while moving, passive satellite measurements, a
scanner that idles when the phone is still, the relay connection without a
45-second ping), and the emulator checks below keep it from regressing, but
until the table below has rows, the claim is a design aim, not a measurement.

| Release | Phone (Android) | Battery optimisation | Scenario | %/h | mAh/h | Clairix share | Wake-lock s | Wake-up alarms | GNSS on | BLE scan | Wi-Fi scans | Radio active | Meets target |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| (none yet) | | | | | | | | | | | | | |

A release whose run misses a target below, or is worse than the previous
release's run of the same scenario on the same phone by more than the
run-to-run spread, is not published until that is explained or fixed.

## Targets

What Clairix itself may cost, on top of the same phone's S0 run:

| Scenario | Target |
|---|---|
| S1: scanner, stationary, screen off, 8 h | at most 5 % of the battery per day |
| S2: scanner, carried, 8 h | at most 15 % per day |
| S3: S2 with comms online | at most 3 % per day more than S2 |

S4 to S7 have no target yet: the first runs set the baseline that later
releases are held to.

## Devices

- One stock Google Pixel on Android 14 or 15.
- One Samsung Galaxy on One UI (Android 14 or 15), whose own battery manager
  is the most common reason Clairix is stopped in the background.

Each scenario runs twice on each phone: once with Clairix exempt from battery
optimisation (as the support page tells people who use Comms to do) and once
not exempt. The phone's other apps, accounts and settings stay the same between
runs; the build under test is a release APK, its SHA-256 written next to the
results.

## Procedure, per run

1. Charge to 100 % and leave the phone on the charger for ten minutes more.
2. `adb shell dumpsys batterystats --reset`
3. Unplug (over Wi-Fi adb, or unplug the cable and reconnect only at the end).
4. Run the scenario for its full length. Note the start and end level.
5. Plug in, then collect:
   - `adb bugreport bugreport.zip` and open it in Battery Historian, or
   - `adb shell dumpsys batterystats --charged app.clairix > batterystats.txt`
   - `adb shell dumpsys alarm > alarm.txt`
   - `adb shell dumpsys power > power.txt`
   - `adb shell dumpsys location > location.txt`
6. For one 30-minute window inside the scenario, a Perfetto trace (below).

## Scenarios

| | What runs | Length |
|---|---|---|
| S0 | The phone alone, Clairix installed but not running: the baseline | 8 h |
| S1 | Scanner only, phone stationary on a table, screen off | 8 h |
| S2 | Scanner only, carried: two 30-minute drives, two 20-minute walks, the rest at a desk | 8 h |
| S3 | S2 with comms online (registered, the background connection on) | 8 h |
| S4 | S3 with "Connect through Tor" on | 8 h |
| S5 | S3 with the offline mesh on | 8 h |
| S6 | Navigation along a planned route, screen on | 1 h |
| S7 | Drive mode with an RTL-SDR stick on the phone's USB port and an OBD-II adapter | 1 h |

Each scenario runs with "Low power" off; S1 and S2 run again with it on, to
show what it saves.

## What to record

Per run, from batterystats (or Battery Historian):

- discharge in % per hour and mAh per hour, and Clairix's estimated mAh and share;
- partial wake-lock seconds by tag: `clairix:relay-watchdog`, `clairix:relay-probe`,
  `clairix:relay-reconnect`, `clairix:relay-envelope`, `clairix:comms-sync`, and any
  other `clairix:` tag;
- the number of wake-up alarms;
- GNSS on-time;
- Bluetooth scan time, and the time batterystats lists as unoptimised;
- Wi-Fi scan count;
- mobile-radio active time;
- CPU time and process wake-ups.

## Perfetto window

Thirty minutes inside each scenario, giving wake-ups per minute by source:

```
adb shell perfetto -o /data/misc/perfetto-traces/clairix.pftrace -t 30m -c - --txt <<'EOF'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "android.power" android_power_config {
  battery_poll_ms: 1000 collect_power_rails: true
  battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
  battery_counters: BATTERY_COUNTER_CHARGE
  battery_counters: BATTERY_COUNTER_CURRENT } } }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "power/suspend_resume"
  ftrace_events: "power/wakeup_source_activate"
  ftrace_events: "power/wakeup_source_deactivate"
  ftrace_events: "power/cpu_idle" } } }
data_sources { config { name: "android.log" android_log_config { log_ids: LID_DEFAULT log_ids: LID_SYSTEM } } }
EOF
adb pull /data/misc/perfetto-traces/clairix.pftrace
```

## What the emulator checks on every change

`.github/workflows/emulator.yml` reads Android's own counters on every API level
it boots (12 to 15, and 15 without Google Play services) and fails the job on a
breach (`.github/scripts/power_budget.py`, tested by `test_power_budget.py`). The
emulator's battery is unplugged for the window (`dumpsys battery unplug`) so that
batterystats counts at all; it has no battery model worth a percentage, so only
counts and durations are judged:

- after the smoke test, ten minutes with the scanner running and the screen off:
  partial wake-lock time blamed on Clairix at most 60 s per ten minutes, wake-up
  alarms at most one per ten minutes (and one more for an alarm that falls on
  both edges of the window), no Clairix wake lock held for over a minute in
  `dumpsys power`, no pending wake-up alarm repeating more often than every ten
  minutes;
- then the scanner is settled by a broadcast only debug builds have
  (`app.clairix.debug.FORCE_STILL`), and `dumpsys location` must list no
  Clairix satellite measurement request that keeps the receiver on. A passive
  request (Android 14 and later) or the longest interval (Android 13) is
  allowed: it switches nothing on;
- after the soak test, ten minutes with comms online: the same wake-lock, alarm
  and held-lock budgets, and no Bluetooth scan time, since the scanner was
  stopped before the messaging tests.

Android does not record the timeout an app passes when it takes a wake lock, so
the "no wake lock without a timeout" rule is also kept in the sources:
`WakeLockTimeoutTest` fails when any `acquire()` on a wake lock has no duration.
Every dump is uploaded with the job's other logs.

These are proxies. They catch a new timer, a stuck wake lock or a scan left
running; they say nothing about how many hours a phone lasts. Only the device
runs above can.

## What the app shows

Settings > Battery has:

- **Low power**: a switch, also on by itself below 20 % with no charger (until
  the phone charges or reaches 25 %) and while Android's battery saver is on.
  The scanner counts the phone as settled after 2 minutes still instead of 4
  (the mesh after 5 instead of 10), scans Bluetooth at low power with results
  handed over once a minute while the screen is off, reads the serving cell
  every 2 minutes and the Wi-Fi around every 15; cover traffic pauses, and the
  mesh stays at its quiet pace unless it has something to carry. The scanner's
  notification says "Scanning · low power". An SOS (through the relay or the
  mesh, which keeps its full pace while it carries one), the duress passcode and
  phrase, incoming calls and the relay connection that rings them read nothing
  from it.
- **Used since the scan started**: the whole phone's drain since the scanner
  started, from the battery level and, where the phone reports it, the charge
  counter (BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER), with a rate per hour
  after half an hour. A charge in between starts the count again from the unplug.
  Android does not tell an app its own share; its battery page does.
- One line per feature with what it runs, from Clairix's own counters: Bluetooth
  scan time by mode, time moving (precise fixes every 15 s) and settled (a coarse
  fix every 3 min), cell readings and Wi-Fi scans, the relay connection and cover
  traffic, the mesh's pace, and what drive mode and navigation do. No milliamp
  figure is shown: none has been measured.

Drive mode and navigation have their own limits, independent of Low power:
below 15 % with no charger, drive mode stops the radio stick (which the phone
powers through its USB port) and OBD-II polling until the phone charges or
reaches 20 %, and navigation's traffic, speed-report and faster-route timers
slow to every 5 minutes; OBD-II is polled every 500 ms on screen and every
second off it.
