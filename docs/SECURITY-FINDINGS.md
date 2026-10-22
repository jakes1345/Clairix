# Security findings — October 2026

A code audit of Clairix 3.0.0-beta.2 under the threat model the project
actually serves: the adversary is a state actor who can compel Cloudflare,
GitHub and the carrier, seize and image the phone, watch the network
passively, and may hold an OS exploit. Nothing here protects against a
compromised OS; everything else is what the app can still do.

Each item says whether it was **verified** (read end to end in this audit),
**spot-checked** (both halves confirmed, path between them not traced) or
**reported** (anchored to file:line by a reviewer but not independently
re-read). Line numbers are as of commit `366aabb`.

Fixed so far: observability off on the relay; `CommsLog` no longer writes
to logcat; every data domain excluded from cloud backup and device transfer;
release builds minified with `android.util.Log` stripped; the four `Store`
documents (location log, timeline, cell baseline, trips) sealed on disk under
a Keystore key, with plaintext files from earlier versions resealed on first
load; lookups by signed body (`POST /v1/lookup`); no stored activity times on
the relay (minute-scale limits in memory, slow allowances to the hour and
deleted once refilled, no last-seen/fetched/woken, registration to the day,
lapsed claims to the day); traffic made anonymous (token-spending requests,
per-trip pseudonyms, no salted number hash, no per-caller incident quota).

## Tier 1 — the relay is a single point of compulsion

1. **Lookups reveal the contact graph.** `GET /v1/bundle/<number>` and
   `/v1/identity/<number>` carry the target in the URL and the caller in
   `X-Clairix-Number`. With observability on, that pair was in Cloudflare's
   logs for days. *Verified.* **Done:** observability off; `POST /v1/lookup`
   takes the number in the signed body and the app uses it; the GET routes
   remain for beta-2 phones and are to be removed once they have updated;
   README rewritten to name the platform as the operator that matters.
2. **Traffic probes are tied to the sender.** `RelayClient.trafficProbe`
   (`RelayClient.kt:400`) goes through `signed()`, against the comment at
   `:396`. `trafficReport` sends exact lat/lon with the number. The relay's
   pseudonym is `sha256(salt:number)[0..32]` with the salt in the same
   object's meta (`traffic.ts:364-371`, `incidents.ts:154-161`): a nine-digit
   space, reversed in one pass by anyone with storage access. The code's own
   comment at `comms-logic.ts:95` says a hash of a number is no protection.
   README lines 139-140 and 196-198 overstate this. *Verified.* **Done:**
   every traffic request spends a single-use `traffic` (or `report`) token
   issued over the signed channel into its own shards, carries no identity
   and is refused if it names one; probes, reports and votes carry a trip
   pseudonym the phone rotates every ten minutes; the salted hash, its salt
   and the per-caller incident quota are gone (`traffic.ts`, `incidents.ts`,
   `TripPseudonym.kt`). What remains: the relay sees a region per request
   and, for two hours, pseudonymous segment speeds; and a token fetch can be
   paired with the request that follows it by timing, which the phone blunts
   by fetching ahead in batches.
3. **Mailbox keeps when the owner was last online.** `lastFetchAt` on every
   inbox fetch and WebSocket connect (`mailbox.ts:680, 796`), `lastWakeAt`
   (`:658`), and `at` on every rate bucket (`:475, :491`) — `bucket:turn.at`
   alone is "when did they last make a call". `relay.test.mjs:154-162` only
   asserts `profile` is unchanged, so it passes. *Verified.* **Done:** both
   wake fields are memory-only; minute-scale limits (lookup, TURN, pay,
   register) are memory-only; stored allowances keep `{tokens, hour}` and are
   deleted by the daily cleanup once full; nonces hold the minute; the test
   now walks every key a mailbox holds after a quarter-hour of activity.
   Legacy `bucket:*`/`last*At` keys are deleted by the next daily cleanup.
4. **Send-time to fetch-time correlation** across the relay is visible to
   anyone at Cloudflare's edge regardless of padding. Not solved; say so.
5. **One relay is one order.** Many small relays is the defence; document it.

## Tier 2 — the seized phone

6. **Scanner data is plaintext.** `location_log.json` (up to 30,000 fixes,
   `PatternOfLife.kt:33-66`), `timeline.json` (every detection with lat/lon
   and the "SOS triggered by duress phrase" event, `EventLog.kt:81-90`,
   `DuressPhrase.kt:150-165`), `imsi_baseline.json`, `vehicle_trips.json`
   (`ScanService.kt:212,215`), `unlock_ledger.db` (`UnlockLedger.kt:60-101`)
   and about a dozen prefs files are written as-is by `Store.kt:185-199`.
   `KeystoreBox` exists and is not used for any of them. *Verified.*
   **Done for the `Store` documents** (`StoreCipher.kt`): AES-256-GCM under
   a hardware Keystore key. Not unlock-bound, because the scanner writes
   the baseline with the keyguard up; see the class comment for what that
   does and does not protect against. Still open: `unlock_ledger.db`, the
   prefs files, and a retention setting that defaults short.
7. **`KeystoreBox` key is not user-auth bound and not StrongBox**
   (`KeystoreBox.kt:48-55`). A logical extraction with the device unlocked
   reads everything it protects. *Reported.*
8. **App lock does not lock COMMS.** `onResume` locks first
   (`MainActivity.kt:413`), then the open thread's `ON_RESUME` sets
   `openPeer` (`CommsScreen.kt:1089-1114`) and `markRead` queues a READ
   receipt (`CommsRepository.kt:2331-2338`); inbound messages are stored
   `read = openPeer == number` (`:1961`). Nothing checks `AppLock.locked`.
   The sender sees "read" while the lock screen is up and the owner gets no
   notification. *Verified.* Notification previews also show full text
   regardless of the Clairix lock (`CommsNotifications.kt:299-340`); there is
   no hide-previews setting and `lock()` cancels nothing.
9. **No one-tap wipe.** `Wiper.start` is reached only from `AppLock.kt`
   (duress code, failure limit, pending marker). README says "one tap".
   *Verified.* The wipe also never cancels notifications (SOS and message
   previews survive process death), clears the clipboard, unregisters
   UnifiedPush or deactivates the device admin, so the app cannot be
   uninstalled normally afterwards. `unpair()` calls `relay.wipe()` first
   with a 25 s timeout inside a 6 s budget, so with the relay unreachable the
   notification cancel and push unregister never run. *Reported.*
10. **HCE stays armed** after vault lock, app lock and screen off
    (`MainActivity.kt:559-565`, `CardEmulationService.kt:59-62`); only the
    device PIN gates it. *Reported.*
11. **`CommsLog` persisted plaintext** in `comms_log` prefs with contact
    names, numbers, call and SOS events (`CommsLog.kt:64-68`). Logcat output
    is removed in this PR; the prefs copy remains.
12. **FLAG_SECURE only with a passcode** (`MainActivity.kt:237-243`): vault,
    timeline and map are screenshot-able otherwise. *Reported.*
13. **Clipboard** copies of the safety number, invite link, pairing code and
    diagnostics (`CommsScreen.kt:1898, 2005, 2080, 2780`) lack
    `EXTRA_IS_SENSITIVE` and are never cleared. *Reported.*
14. **`file_paths.xml`** exposes all of `cacheDir` (voicemails, decrypted
    media) rather than a `reports/` subdirectory. *Reported.*

## Tier 3 — supply chain

15. **No TLS trust restriction.** No `network_security_config`, no pinning in
    any OkHttp client. A user-installed or compelled CA is trusted for the
    relay, Nominatim and map downloads; `TlsCanary` notices and
    `RelayClient` keeps talking. A MITM sees the enrollment secret, invite
    codes, TURN credentials and the token-fetch to identity correlation the
    anonymous-send design depends on. *Verified.* Fix: pin the relay's key
    (you control rotation); `<certificates src="system"/>` only elsewhere.
16. **Map graphs and work zones are integrity-checked, not authenticated.**
    `GraphStore.kt:166-168` fetches the `.sha256` from the same release URL;
    `WorkZoneFeed.kt:233-260` has no checksum. `RoadGraph.open` parses that
    binary into a mapped buffer in a location-permissioned service. *Reported.*
    Fix: Ed25519-sign the assets with a key embedded in the app.
17. **Release `SHA256SUMS.txt` is unsigned** (`release.yml:208`). *Reported.*
18. **Actions are tag-pinned** (`@v4`, `@stable`) including in `release.yml`,
    which runs with `contents: write` and the decoded keystore on disk. A
    hijacked tag signs a trojaned APK with the real key. `maps.yml` and
    `workzones.yml` run on a schedule with `contents: write` against
    unchecksummed Geofabrik data and unpinned pip. *Spot-checked.* Fix:
    SHA-pin; move signing out of CI.

## Crypto crate and relay logic

19. **One-time-key burn** (`lib.rs:784-809`): `create_inbound_session`
    consumes the key, then `unpad` can fail before the session is pushed. Any
    sender drains a victim's keys with bad-padding first messages at the
    claim rate; the error is `Decrypt` with no sender named. *Verified.*
20. **Curve-key rebinding** (`index.ts:635`, `lib.rs:617`): the relay checks
    only that the curve/sealing binding is signed by the submitted ed key, so
    Mallory can register Alice's curve key under her own ed key;
    `start_session` then overwrites the peer binding unconditionally and
    Alice's real envelopes fail at `:708`. Targeted DoS. *Spot-checked.*
21. **Register is signed over the body only** (`index.ts:633-634`): no
    timestamp, nonce or path, so a captured register (carrying
    `ENROLL_SECRET` in JSON) replays forever. *Verified.*
22. **Body read with no size cap before auth** (`index.ts:122`), then a
    storage read and Ed25519 verify on the victim's single-threaded DO per
    attempt; no failed-auth limiter. *Reported.*
23. **Tokens are not recipient-bound**; `MAX_QUEUE=2000` then 413 for all
    senders. One account fills any mailbox in about 25 minutes. *Reported.*
24. **Existence oracle**: send and `coin/pay` return 404 for unknown numbers
    (`mailbox.ts:608`, `index.ts:335`), undoing what the bundle endpoint
    hides. *Reported.*
25. **Coin**: every registration gets `COIN_INITIAL_BALANCE` with nothing
    binding a registration to a unique key; invites need no secret. Pay is
    debit-then-credit across two DOs with no idempotency key; a `DELETE
    /v1/me` between them leaves coins in a wiped box for the next holder of
    that number. *Reported.*
26. **TURN**: 10/min, 2 h TTL, no daily cap, no call required. *Reported.*
27. **Traffic DO sprawl**: any account can name any `traffic:<slug>`; objects
    are never deleted. *Reported.*
28. `timingSafeEqual` returns early on length mismatch (`env.ts:93-98`).
    `Cargo.lock` carries two full crypto stacks (dalek 4 and 5, chacha 0.10
    and 0.11) because the app pins older than vodozemac. Pickle nonce is 96-bit
    under a long-lived key. `Mutex::lock().expect` with `panic = "abort"`.
    *Reported.*

## Calls and pairing

29. ICE host/srflx candidates are sent while dialing
    (`CallManager.kt:630-667`), so placing a call hands your IP to a contact
    who never answers; no relay-only option. *Reported.*
30. `clairix://invite` is a custom scheme any app can claim; the invite link
    dictates the relay URL at registration (`CommsRepository.kt:365`).
    *Verified for the URL; interception reported.*
31. The duress phrase sends nothing when the phone is in no group
    (`CommsRepository.kt:1459-1468`) and the UI does not warn. *Reported.*

## README claims to correct

- "Panic wipe — one tap": not implemented (9).
- "Quietly sends your location as an SOS": silent with no group (31).
- "The relay knows as little as possible": true of content, not of metadata
  (1, 2, 3). List what is kept, by whom, for how long.
- Add: the app cannot protect you if the OS is compromised or the phone is
  seized unlocked.

## Suggested order

1. Encrypt scanner stores with an unlock-bound key (6, 7).
2. Target number out of lookup URLs; drop `lastFetchAt` and bucket
   timestamps; unsign probes (1, 2, 3).
3. Pin the relay certificate; system-only trust elsewhere (15).
4. Sign graphs, work zones and release sums; SHA-pin actions (16, 17, 18).
5. One-tap wipe with notification, admin and push cleanup; disarm HCE on
   lock; gate `openPeer`/`markRead` on the lock (8, 9, 10).
6. OTK-burn ordering and curve-rebind refusal in the crate (19, 20).
7. README with an explicit threat model.

## Not re-tested

No Gradle, cargo or wrangler run was possible in the audit environment. The
minify change in this PR is the one edit that can fail a release build;
check the first release build's R8 output for missing-class warnings.
