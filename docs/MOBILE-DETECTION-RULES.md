# Mobile Detection Rules (MDR)

A portable, YAML-based detection rule format for Android threats — the same idea
as SigmaHQ/sigma, applied to mobile log sources instead of SIEM event logs.

**Status:** draft / research prototype. No production backend exists yet.
Rules here document *what to look for*; the backend that evaluates them is a
future problem.

---

## Why this doesn't exist yet

Sigma decouples detection logic from the query language of any specific SIEM. For
mobile, the equivalent gap is:

- **MVT** (Amnesty Tech): forensic, IoC-driven, runs once on a device dump. Not a
  persistent runtime detection format.
- **YARA rules for APKs**: static analysis of installed packages. Useful, but no
  runtime visibility.
- **MTD vendors** (Lookout, Zimperium, CrowdStrike Falcon Mobile): runtime detection
  exists, but rules are proprietary and non-portable.
- **SnoopSnitch**: detects some baseband/IMSI-catcher signals; not a general format.

The gap: **no open, community-maintainable format** for expressing mobile runtime
detection logic that can be evaluated against logcat, bugreport, network captures,
or vendor-specific security APIs.

This document drafts what that format could look like, plus concrete rules relevant
to Clairix's threat model.

---

## Log source taxonomy

| `category` | `platform` | Description | Backend examples |
|---|---|---|---|
| `telephony` | `android` | Cell tower events, signal changes, SMS/call records | `telephony_manager` via ADB, SnoopSnitch |
| `process` | `android` | Process starts, kills, uid/gid changes | logcat `ActivityManager`, `am` |
| `network` | `android` | Connections, DNS queries, TLS SNI | VPN service API, `tcpdump` on hotspot, `netstat` |
| `permission` | `android` | Runtime permission grant/deny/use events | `pm`, logcat `PermissionManager` |
| `ipc` | `android` | Binder transactions, intent broadcasts, content-provider queries | logcat `Binder`, `dumpsys binder_calls_stats` |
| `file` | `android` | File creates, opens, deletes by app | `inotify` (root), `auditd` |
| `sensor` | `android` | Accelerometer, GPS, mic, camera access events | logcat `SensorManager`, `AppOpsManager` |
| `app_install` | `android` | APK installs, updates, source, signing key | `pm`, Play Protect logs |
| `bugreport` | `android` | Full system snapshot sections | `adb bugreport` + MVT |
| `relay_access` | `cloudflare_worker` | Clairix relay HTTP access logs | Cloudflare Logpush → SIEM |

---

## Rule format

Based on Sigma's structure; extends it with mobile-specific fields.

```yaml
title: <human-readable name>
id: <UUID>
status: stable | experimental | deprecated
description: <what this detects and why it matters>
references:
  - <URL>
author: <name>
date: YYYY-MM-DD
logsource:
  platform: android | ios | cloudflare_worker
  category: <from taxonomy above>
detection:
  <named-selection>:
    <field>: <value>          # exact match
    <field>|contains: <str>   # substring
    <field>|re: <regex>       # regex
    <field>|gt: <number>      # numeric comparison
  timeframe: <duration>       # for aggregation rules (e.g. 5m, 1h)
  condition: <selection> [| count() > N] [| groupby(<field>)]
falsepositives:
  - <scenario>
level: low | medium | high | critical
tags:
  - attack.<tactic>
  - attack.<technique-id>  # MITRE ATT&CK Mobile (https://attack.mitre.org/matrices/mobile/)
```

---

## Rules

### MDR-001 — IMSI Catcher: 2G Downgrade

```yaml
title: Potential IMSI Catcher — Forced 2G/EDGE Downgrade
id: a1b2c3d4-0001-0000-0000-000000000001
status: experimental
description: >
  Detects a sudden drop from 4G/LTE or 5G to 2G/EDGE while the device is stationary.
  Many IMSI catchers only support 2G. A single event is low-signal; combined with
  rapid tower switching (MDR-002) it is higher confidence.
references:
  - https://snoopsnitch.seemo.org/
  - https://attack.mitre.org/techniques/T1416/
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: telephony
detection:
  downgrade:
    event.type: network_type_change
    network.previous_type|re: "LTE|NR|HSPA"
    network.current_type|re: "GPRS|EDGE|GSM"
  condition: downgrade
falsepositives:
  - Rural area with no LTE coverage
  - Building with thick concrete blocking LTE signals
  - Legitimate carrier maintenance window
level: medium
tags:
  - attack.collection
  - attack.T1416
```

---

### MDR-002 — IMSI Catcher: Rapid Tower Switching

```yaml
title: Potential IMSI Catcher — Rapid Cell Tower Switching While Stationary
id: a1b2c3d4-0002-0000-0000-000000000002
status: experimental
description: >
  Detects more than 8 distinct cell tower IDs (cell_id) within 5 minutes while
  GPS/accelerometer shows the device is not moving. IMSI catchers can cause phones
  to cycle through fake tower registrations rapidly.
references:
  - https://snoopsnitch.seemo.org/
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: telephony
detection:
  tower_switch:
    event.type: cell_change
  stationary:
    sensor.accelerometer_variance|lt: 0.05
  timeframe: 5m
  condition: tower_switch | count(network.cell_id) > 8 and stationary
falsepositives:
  - Commuting through dense urban cell grid (but motion would be detected)
  - Carrier network restructuring
level: high
tags:
  - attack.collection
  - attack.T1416
```

---

### MDR-003 — Stalkerware: Mic Access Outside Foreground App

```yaml
title: Microphone Access by Background App
id: a1b2c3d4-0003-0000-0000-000000000003
status: experimental
description: >
  Detects microphone access by an app that is not currently in the foreground and
  does not have a declared foreground service for audio capture (e.g. a call or
  recording app). Commercial stalkerware commonly accesses the mic silently.
references:
  - https://attack.mitre.org/techniques/T1429/
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: sensor
detection:
  mic_access:
    event.type: op_noted
    app_ops.op: RECORD_AUDIO
  not_foreground:
    app.importance|gt: 100   # ImportanceLevel: 100 = FOREGROUND
  no_service:
    app.has_audio_foreground_service: false
  condition: mic_access and not_foreground and no_service
falsepositives:
  - Push-to-talk apps using background audio
  - Some AI assistant apps
  - Live captioning services
level: high
tags:
  - attack.collection
  - attack.T1429
```

---

### MDR-004 — Stalkerware: Location Access at Unusual Frequency

```yaml
title: High-Frequency Location Access by Non-Navigation App
id: a1b2c3d4-0004-0000-0000-000000000004
status: experimental
description: >
  Detects an app requesting precise GPS location more than 20 times per minute
  without being an active navigation or fitness tracking app. Stalkerware typically
  polls location continuously to upload a movement track.
references:
  - https://attack.mitre.org/techniques/T1430/
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: sensor
detection:
  location_poll:
    event.type: op_noted
    app_ops.op: FINE_LOCATION
  not_navigation:
    app.category|re: "maps|navigation|fitness|health"
    app.category.match: false
  timeframe: 1m
  condition: location_poll | count() > 20 and not_navigation
falsepositives:
  - Legitimate delivery/rideshare driver apps
  - Some weather apps in background
level: high
tags:
  - attack.collection
  - attack.T1430
```

---

### MDR-005 — App Install from Non-Standard Source

```yaml
title: APK Installed from Source Outside Known Stores
id: a1b2c3d4-0005-0000-0000-000000000005
status: stable
description: >
  Detects installation of an APK from a source other than GrapheneOS App Store,
  Accrescent, F-Droid, or the Sandboxed Play Store. Sideloaded APKs are the
  primary delivery mechanism for stalkerware and spyware on Android.
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: app_install
detection:
  sideload:
    install.source|contains|all:
      - "installer_package: null"
  not_known_store:
    install.installer_package|re: "app\\.grapheneos\\.apps|app\\.accrescent\\.client|org\\.fdroid\\.fdroid|com\\.android\\.vending"
    install.installer_package.match: false
  condition: sideload or not_known_store
falsepositives:
  - Developer sideloading their own app via ADB
  - Known APK from developer's direct download with verified signing key
level: medium
tags:
  - attack.execution
  - attack.T1476
```

---

### MDR-006 — Network Exfiltration: Large Upload to Unknown Host

```yaml
title: Large Data Upload to Unrecognized Host
id: a1b2c3d4-0006-0000-0000-000000000006
status: experimental
description: >
  Detects an app uploading more than 5 MB to a host in a single session that is not
  in the app's known legitimate endpoints. Spyware typically uploads device data,
  call records, messages, and location logs to C2 infrastructure.
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: network
detection:
  large_upload:
    event.type: flow_end
    network.bytes_sent|gt: 5242880   # 5 MB
    network.direction: outbound
  unknown_host:
    network.dest_domain|re: "\\.google\\.com|\\.apple\\.com|\\.cloudflare\\.com|\\.amazonaws\\.com|\\.fastly\\.net"
    network.dest_domain.match: false
  condition: large_upload and unknown_host
falsepositives:
  - Cloud backup apps
  - Video upload to social media
  - Software updates
level: medium
tags:
  - attack.exfiltration
  - attack.T1532
```

---

### MDR-007 — Clairix Relay: Federation Sybil Attack

This rule targets the **relay** log source (Cloudflare Logpush → SIEM), not the device.
It detects the wildcard-DNS Sybil pattern fixed in `federation.ts` — useful for
operators who want SIEM alerting even after the rate-limit mitigations are in place.

```yaml
title: Clairix Relay — Federation Sybil Enumeration Attempt
id: a1b2c3d4-0007-0000-0000-000000000007
status: stable
description: >
  Detects a burst of federation lookup requests from many distinct X-Clairix-Origin
  values that all share the same registrable domain (eTLD+1). The per-domain
  rate-limit bucket in federation.ts will fire a 429, but this rule surfaces
  the attempt for SOC visibility and tuning.
references:
  - docs/THREAT-MODEL-GAPS-2026-11.md gap 11
author: Clairix Security
date: 2026-11-20
logsource:
  platform: cloudflare_worker
  category: relay_access
detection:
  fed_lookup:
    request.path|contains: "/fed-lookup"
    response.status: 429
  timeframe: 1m
  condition: fed_lookup | count(request.header.x_clairix_origin) > 20
    | groupby(request.registrable_domain)
falsepositives:
  - Legitimate high-volume relay federation (increase threshold)
level: high
tags:
  - attack.discovery
  - attack.T1590
```

---

### MDR-008 — Baseband Silent SMS (Type 0 SMS)

```yaml
title: Silent SMS (Type 0) Received — Potential Location Ping
id: a1b2c3d4-0008-0000-0000-000000000008
status: experimental
description: >
  Detects receipt of a Type 0 (flash/silent) SMS, which is delivered to the
  baseband without user notification and can be used to silently ping a device's
  cell-site location. Some law enforcement tools and commercial tracking services
  use Type 0 SMS for covert location tracking.
references:
  - https://snoopsnitch.seemo.org/
  - https://attack.mitre.org/techniques/T1416/
author: Clairix Security
date: 2026-11-20
logsource:
  platform: android
  category: telephony
detection:
  silent_sms:
    event.type: sms_received
    sms.message_class: 0   # TP-PID 0x40 = Type 0 / flash
  condition: silent_sms
falsepositives:
  - Legitimate carrier network diagnostic messages (rare in consumer service)
level: high
tags:
  - attack.discovery
  - attack.T1416
```

---

## Backends (future work)

| Backend | Log source | Notes |
|---|---|---|
| ADB + logcat (rooted) | process, sensor, permission, ipc | Requires root or ADB debug; most useful for forensics |
| SnoopSnitch | telephony | Already does MDR-001 and -002 style checks; not portable |
| GrapheneOS VPN service API | network | Per-app netflow data accessible to a trusted VPN app |
| Orbot / custom VPN | network | Full packet capture on hotspot for the network rules |
| `adb bugreport` + MVT | bugreport | One-shot forensics; adapts to MDR-003 through -006 |
| Cloudflare Logpush → Splunk/Elastic | relay_access | MDR-007 is immediately usable here |

A minimal open-source evaluator would:
1. Accept MDR YAML rules
2. Tail logcat (or parse a bugreport) and match events against `detection` blocks
3. Emit STIX 2.1 Sighting objects for matches
4. Optionally push to a SIEM or alert via local notification

A MVP could be a Python script (<500 LOC) that runs on a rooted device via Termux
or on a connected machine via ADB.

---

## Relationship to Sigma

MDR rules are intentionally Sigma-compatible in spirit. Differences:
- `logsource.platform` replaces Sigma's `product`/`service`; values are `android`,
  `ios`, `cloudflare_worker`
- `timeframe` and aggregation (`count()`, `groupby()`) are first-class, not
  pipe-modifiers, because mobile rules often need sliding-window counts
- `app.*` and `sensor.*` are new field namespaces with no Sigma equivalent
- `telephony.*` is new

Where rules target a standard log source (e.g. `relay_access` via Cloudflare Logpush
to Splunk), they can be expressed as standard Sigma rules with no modification and
converted with `sigmac` or `pySigma`.
