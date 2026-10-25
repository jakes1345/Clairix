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

Fixed so far (PRs #12 to #15): observability off on the relay;
`CommsLog` no longer writes to logcat; every data domain excluded from cloud
backup and device transfer; release builds minified with `android.util.Log`
stripped; the four `Store` documents (location log, timeline, cell baseline,
trips) sealed on disk under a Keystore key, with plaintext files from earlier
versions resealed on first load; lookups by signed body (`POST /v1/lookup`);
no stored activity times on the relay (minute-scale limits in memory, slow
allowances to the hour and deleted once refilled, no last-seen/fetched/woken,
registration to the day, lapsed claims to the day); traffic made anonymous
(token-spending requests, per-trip pseudonyms, no salted number hash, no
per-caller incident quota); system-only CA trust and no cleartext app-wide,
with optional strict pinning of the relay's key on every handshake; the lock
gates read receipts and `markRead`, redacts notifications into "Unlock to
read", disarms HCE, and FLAG_SECURE is on for every screen; a reachable wipe
("Wipe Clairix") that also cancels notifications, clears the clipboard,
unregisters push and drops the device-admin role, with the relay call bounded
to four seconds; a hide-previews setting; sensitive-marked clipboard copies;
the duress phrase's no-group warning; releases, map graphs and work zones
signed with a project Ed25519 key and verified in the app; every action
commit-pinned, the release dispatch guarded, the Geofabrik extract checked
against its MD5, pip pinned; the crate refuses to rebind a Curve25519 key to a
second identity and keeps a session whose first message fails padding; the
relay records which account each key belongs to, dates registrations, caps
request bodies, compares secrets as digests, accepts-and-drops sends to
unknown numbers, restricts traffic regions to published ones and refunds a
credit into a deleted mailbox; the READMEs and the website's security and
privacy pages rewritten around the threat model; the unlock ledger moved
into a sealed `Store` document and the plain database migrated and deleted;
the comms log sealed in its prefs; the file provider limited to reports and
camera captures; an armed card disarmed when the screen goes off and five
minutes after arming in any case; a relay-only calls setting; TURN
credentials bounded to thirty an hour; payments made idempotent by a
phone-chosen id; the comms database moved to SQLCipher under a
Keystore-wrapped key, with the plain database converted once; the comms
preferences sealed; optional cover traffic; alternate relay addresses with
failover; destination search on the phone over the map's road names, with
the online search a separate, labelled choice and a dropped pin named from
the map rather than looked up; a history retention setting, 30 days by
default; the Rust compiler and the Gradle distribution pinned, the
dependency-metadata block left out of the APK, build-machine paths kept
out of the crypto library, native libraries stripped (the Android Gradle
plugin had no NDK to strip with, so 3.0.0 beta 2 shipped every native
library with its debug info and symbol tables), and every change built
twice on two machines in CI, which fails unless the two builds agree.


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
   **Partly done** (PR #15): "Cover traffic" (off by default) sends sealed
   envelopes of random size to random contacts with sessions at random
   intervals (exponential, mean twenty minutes), dropped unread with no
   receipt (`CommsWire.T_NOISE`), so a real message is one envelope among
   many to the relay and to the edge. A recipient is still visible per
   envelope, and no mixing network exists; the README says both.
5. **One relay is one order.** Many small relays is the defence; document it.
   **Partly done** (PR #15): the relay publishes alternate addresses it
   answers at (`RELAY_ALTERNATES`, named in `GET /`); the app keeps them and
   fails over to one when the registered address stops answering, staying
   on the one that works, except with the TLS pin on. Doors to one relay,
   documented as such; independent relays remain phase 2.

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
   does and does not protect against. **Done for the unlock ledger** (PR #15):
   it is a sealed `Store` document, `unlock_ledger.json`; the SQLite file is
   read once into it and deleted. **Done for the comms database and the
   comms preferences** (PR #15): the database is SQLCipher under a random key
   that `KeystoreBox` wraps (`CommsDb.kt`), with a plain database from an
   earlier version exported into a sealed copy once and replaced; the comms
   preferences (relay, number, name, push endpoint) are a `SealedPrefs`
   document, migrated from the plain file once. The remaining preference
   files hold settings (thresholds, the sleep window, the passcode's HMAC
   verifier, TLS pins), not records of the owner's life. **Retention done**
   (PR #15): `HistoryRetention`, 30 days by default (7, 90 or forever),
   deletes the location log, the timeline, the vehicle trips and the unlock
   ledger past it, every quarter hour while scanning and at each start.
7. **`KeystoreBox` key is not user-auth bound and not StrongBox**
   (`KeystoreBox.kt:48-55`). A logical extraction with the device unlocked
   reads everything it protects. *Reported.* **Considered, not done:** a
   StrongBox key would put every message-body and store decrypt through the
   secure element, far slower than the TEE, and a software data key wrapped
   by it would be weaker at rest than today's TEE key; user-auth binding
   would stop the background receive that shows a message with the phone in
   a pocket. What StrongBox buys is resistance to a TEE key-extraction
   exploit, and a logical extraction with the device unlocked reads the
   plaintext either way.
8. **App lock does not lock COMMS.** `onResume` locks first
   (`MainActivity.kt:413`), then the open thread's `ON_RESUME` sets
   `openPeer` (`CommsScreen.kt:1089-1114`) and `markRead` queues a READ
   receipt (`CommsRepository.kt:2331-2338`); inbound messages are stored
   `read = openPeer == number` (`:1961`). Nothing checks `AppLock.locked`.
   The sender sees "read" while the lock screen is up and the owner gets no
   notification. *Verified.* Notification previews also show full text
   regardless of the Clairix lock (`CommsNotifications.kt:299-340`); there is
   no hide-previews setting and `lock()` cancels nothing. **Done:** the
   thread and group screens treat "on screen" as resumed *and* not locked
   (`openPeer` is cleared and nothing is marked read behind the lock;
   `markRead`/`markGroupRead` and the inbound `read` flag check the lock as
   well); `AppLock.lock()` collapses every message and missed-call
   notification to one "Unlock to read"; a "Hide names and previews in
   notifications" setting (on by default once a passcode is set) makes every
   message, held-key and missed-call notification name nobody and quote
   nothing.
9. **No one-tap wipe.** `Wiper.start` is reached only from `AppLock.kt`
   (duress code, failure limit, pending marker). README says "one tap".
   *Verified.* The wipe also never cancels notifications (SOS and message
   previews survive process death), clears the clipboard, unregisters
   UnifiedPush or deactivates the device admin, so the app cannot be
   uninstalled normally afterwards. `unpair()` calls `relay.wipe()` first
   with a 25 s timeout inside a 6 s budget, so with the relay unreachable the
   notification cancel and push unregister never run. *Reported.* **Done:**
   "Wipe Clairix" page under the Comms menu (type WIPE, one button); the
   wipe now cancels every notification, clears the clipboard, unregisters
   push, deactivates the device admin and disarms the card before touching
   the files; `unpair()` bounds the relay call to four seconds on its own
   thread so the local steps always run.
10. **HCE stays armed** after vault lock, app lock and screen off
    (`MainActivity.kt:559-565`, `CardEmulationService.kt:59-62`); only the
    device PIN gates it. *Reported.* **Done for the app lock and the wipe:**
    `CardEmulationBridge.disarm()` forgets the pairs and withdraws the AIDs
    whenever the app locks or wipes. **Done for screen-off and time** (PR
    #15): `EmulationGuard` disarms the card when the screen goes off and five
    minutes after arming in any case. The vault re-locking on its own leaves
    it armed, so a card can be armed, the vault closed and the reader tapped
    within those minutes.
11. **`CommsLog` persisted plaintext** in `comms_log` prefs with contact
    names, numbers, call and SOS events (`CommsLog.kt:64-68`). Logcat output
    is removed in this PR; the prefs copy remains. **Done:** sealed under
    `KeystoreBox` in the same prefs (`sealed`, base64); a plaintext `entries`
    value from an earlier version is read once, resealed and removed.
12. **FLAG_SECURE only with a passcode** (`MainActivity.kt:237-243`): vault,
    timeline and map are screenshot-able otherwise. *Reported.* **Done:**
    set unconditionally for every screen.
13. **Clipboard** copies of the safety number, invite link, pairing code and
    diagnostics (`CommsScreen.kt:1898, 2005, 2080, 2780`) lack
    `EXTRA_IS_SENSITIVE` and are never cleared. *Reported.* **Done:** every
    copy goes through `setSensitiveText` (marked sensitive on Android 13+)
    and the wipe clears the clipboard.
14. **`file_paths.xml`** exposes all of `cacheDir` (voicemails, decrypted
    media) rather than a `reports/` subdirectory. *Reported.* **Done:** the
    provider reaches `reports/` and `camera/` only, reports are written to
    `reports/`, and a report an earlier version left at the cache root is
    deleted.

## Tier 3 — supply chain

15. **No TLS trust restriction.** No `network_security_config`, no pinning in
    any OkHttp client. A user-installed or compelled CA is trusted for the
    relay, Nominatim and map downloads; `TlsCanary` notices and
    `RelayClient` keeps talking. A MITM sees the enrollment secret, invite
    codes, TURN credentials and the token-fetch to identity correlation the
    anonymous-send design depends on. *Verified.* **Done:**
    `network_security_config.xml` trusts system CAs only and forbids
    cleartext for every connection the app makes; `RelayTls` installs a
    trust manager on the relay clients that, after the platform's verdict,
    refuses any leaf key but the one the canary pinned when the owner turns
    on "Refuse the relay if its key changes" on the Certificates page. Off
    by default because a Workers relay's key rotates with its certificate
    and the operator does not control that; on, a rotation stops comms until
    the pin is forgotten there, which is the point.
16. **Map graphs and work zones are integrity-checked, not authenticated.**
    `GraphStore.kt:166-168` fetches the `.sha256` from the same release URL;
    `WorkZoneFeed.kt:233-260` has no checksum. `RoadGraph.open` parses that
    binary into a mapped buffer in a location-permissioned service. *Reported.*
    **Done:** `tools/signing` (Ed25519; secret in the `ASSET_SIGNING_KEY`
    secret, public key committed and built into the app as
    `BuildConfig.ASSET_PUBKEY`). The maps workflow signs each graph's checksum
    file and the app verifies that signature before holding the graph to the
    checksum; the work-zones workflow signs every per-region file and the app
    verifies before parsing, and drops a cached copy an earlier version kept
    unverified. A release build fails without the public key; the workflows
    fail without the secret. **Operator action:** run `keygen.py`, commit
    the two public files, set the secret, before the next release.
17. **Release `SHA256SUMS.txt` is unsigned** (`release.yml:208`). *Reported.*
    **Done:** signed by the same key into `SHA256SUMS.txt.sig`, published
    with the release; the notes say how to check it with `openssl` against
    `android/asset-signing.pem`.
18. **Actions are tag-pinned** (`@v4`, `@stable`) including in `release.yml`,
    which runs with `contents: write` and the decoded keystore on disk. A
    hijacked tag signs a trojaned APK with the real key. `maps.yml` and
    `workzones.yml` run on a schedule with `contents: write` against
    unchecksummed Geofabrik data and unpinned pip. *Spot-checked.* **Done:**
    every action pinned to a commit (the Rust toolchain action names its
    toolchain explicitly); the Geofabrik extract is checked against the MD5
    Geofabrik publishes; the builder's and the signing tool's Python
    dependencies are pinned; `pip install --upgrade pip` is gone; a manual
    release run must start from `main`, a `release/v*` branch or a `v*` tag;
    `maps.yml` now triggers on `main` (it named a `master` branch that does
    not exist). Still open: moving release signing off the CI runner.

## Crypto crate and relay logic

19. **One-time-key burn** (`lib.rs:784-809`): `create_inbound_session`
    consumes the key, then `unpad` can fail before the session is pushed. Any
    sender drains a victim's keys with bad-padding first messages at the
    claim rate; the error is `Decrypt` with no sender named. *Verified.*
    **Done:** the session is kept and the pre-key message remembered before
    the padding is checked, so the sender's later messages still read and
    the message cannot be replayed (`lib.rs`, with a test that forges an
    unpadded first message).
20. **Curve-key rebinding** (`index.ts:635`, `lib.rs:617`): the relay checks
    only that the curve/sealing binding is signed by the submitted ed key, so
    Mallory can register Alice's curve key under her own ed key;
    `start_session` then overwrites the peer binding unconditionally and
    Alice's real envelopes fail at `:708`. Targeted DoS. *Spot-checked.*
    **Done, both ends:** the relay records every identity, Curve25519 and
    sealing key as its account's (one object per key) and refuses a
    registration that offers another account's key with 409, releasing them
    when the account is deleted; accounts from before have their keys
    recorded on their next request. The crate refuses to rebind a Curve25519
    key it already knows as one identity's, in `start_session` and on an
    inbound pre-key message alike (`BadKey`), with a test.
21. **Register is signed over the body only** (`index.ts:633-634`): no
    timestamp, nonce or path, so a captured register (carrying
    `ENROLL_SECRET` in JSON) replays forever. *Verified.* **Done:** the body
    carries `ts` and must arrive within five minutes; with each key
    registrable once, a replay inside the window creates nothing either.
22. **Body read with no size cap before auth** (`index.ts:122`), then a
    storage read and Ed25519 verify on the victim's single-threaded DO per
    attempt; no failed-auth limiter. *Reported.* **Body cap done** (256 KiB,
    413 before anything is read). **A failed-auth limiter was considered and
    not built:** any limiter that refuses requests to a mailbox after N bad
    signatures is itself a way to lock the owner out with junk; Cloudflare's
    own rate limiting is the right layer for a flood.
23. **Tokens are not recipient-bound**; `MAX_QUEUE=2000` then 413 for all
    senders. One account fills any mailbox in about 25 minutes. *Reported.*
24. **Existence oracle**: send and `coin/pay` return 404 for unknown numbers
    (`mailbox.ts:608`, `index.ts:335`), undoing what the bundle endpoint
    hides. *Reported.* **Done:** a send to a number that does not exist is
    accepted (202, a fresh id) and dropped, its token spent; a payment to one
    is "Payment refused" like any other refused payment.
25. **Coin**: every registration gets `COIN_INITIAL_BALANCE` with nothing
    binding a registration to a unique key; invites need no secret. Pay is
    debit-then-credit across two DOs with no idempotency key; a `DELETE
    /v1/me` between them leaves coins in a wiped box for the next holder of
    that number. *Reported.* **Partly done:** a credit into a deleted mailbox
    now fails and the payer is refunded. **Idempotency done:** the phone
    names each payment with a UUID and the relay makes the debit and the
    credit once per name, so a request repeated after a lost answer moves
    nothing twice (the phone repeats once, under the same name, on a network
    failure only); a payment refunded under a name is refused under it.
    Farming remains a product question: the coin has no monetary value, and
    the only brake short of a per-identity cost is to grant no starting
    balance.
26. **TURN**: 10/min, 2 h TTL, no daily cap, no call required. *Reported.*
    **Done:** thirty credentials an hour per account on top of the ten a
    minute, as a stored allowance kept to the hour. A call being signalled
    cannot be required: the signalling is inside envelopes the relay cannot
    read.
27. **Traffic DO sprawl**: any account can name any `traffic:<slug>`; objects
    are never deleted. *Reported.* **Done:** a region must be one the graph
    builder publishes a map for (`src/regions.ts`, kept in step with
    `us-regions.txt` by a test); nothing else creates an object.
28. `timingSafeEqual` returns early on length mismatch (`env.ts:93-98`).
    **Done:** secrets are compared as SHA-256 digests (`secretEquals`).
    `Cargo.lock` carries two full crypto stacks (dalek 4 and 5, chacha 0.10
    and 0.11) because the app pins older than vodozemac. Pickle nonce is 96-bit
    under a long-lived key. `Mutex::lock().expect` with `panic = "abort"`.
    *Reported.*

## Calls and pairing

29. ICE host/srflx candidates are sent while dialing
    (`CallManager.kt:630-667`), so placing a call hands your IP to a contact
    who never answers; no relay-only option. *Reported.* **Done for the
    option:** "Relay-only calls" (Comms settings) sets the ICE transport
    policy to relay, so the connection gathers no host or reflexive
    candidates and the contact learns only the TURN server's address; with no
    TURN server the call is refused with a message. Off by default (a little
    delay, and the relay must have call servers); with it off, candidates
    still go out while dialing, as the protocol needs.
30. `clairix://invite` is a custom scheme any app can claim; the invite link
    dictates the relay URL at registration (`CommsRepository.kt:365`).
    *Verified for the URL; interception reported.*
31. The duress phrase sends nothing when the phone is in no group
    (`CommsRepository.kt:1459-1468`) and the UI does not warn. *Reported.*
    **Done:** the Duress phrase page says so, in red, while the owner is in
    no group.

## README claims to correct

All **done** in PR #14: the root README carries the threat model (who it is
built against, what it protects, what it cannot do, what the relay keeps),
names the "Wipe Clairix" page (9) and the duress phrase's no-group case (31),
and lists what the relay stores and for how long (1, 2, 3); the website's
security and privacy pages and the delete-account page say the same, with
the live-capture caveat (addresses and token-batch fetches are visible in
flight, never written down), and the privacy policy's effective date moved;
the in-app privacy text names every network destination; the worker README's
one-time-key limits match the code.

## Suggested order

1. Encrypt scanner stores with an unlock-bound key (6, 7). *Done for the
   stores; the key is not unlock-bound (7), the unlock ledger and prefs are
   still clear (6).*
2. Target number out of lookup URLs; drop `lastFetchAt` and bucket
   timestamps; unsign probes (1, 2, 3). *Done.*
3. Pin the relay certificate; system-only trust elsewhere (15). *Done.*
4. Sign graphs, work zones and release sums; SHA-pin actions (16, 17, 18).
   *Done; the operator still has to generate the key and set the secret.*
5. One-tap wipe with notification, admin and push cleanup; disarm HCE on
   lock; gate `openPeer`/`markRead` on the lock (8, 9, 10). *Done; HCE on
   screen-off and vault lock (10) remains.*
6. OTK-burn ordering and curve-rebind refusal in the crate (19, 20). *Done.*
7. README with an explicit threat model. *Done.*

Done since (PR #16): the known plate readers (Flock Safety and others, as
mapped in OpenStreetMap by the DeFlock project and whoever else) extracted
from each region's extract beside the road graph, signed with the same key,
shown on the map, spoken 600 m ahead, counted per route and routed around by
default (a detour worth 150 s per reader). The list is as complete as the
map: an unmapped reader is not avoided, and the app says so.

Done since (Bluetooth exposure): the radio side of the plate-reader layer.
Flock Safety hardware is recognised by the signatures the field work
published (the XUNTONG battery pack's manufacturer data and serial, the
battery names before and after the March 2025 firmware, the Raven's
0x31xx–0x35xx GATT services, the Flock accessory service, the `Flock-XXXX`
setup hotspot, and Flock's own IEEE block B4:1E:52), matched against the
map list on the phone and recorded with position; a certain match notifies,
a likely one goes to the timeline, a bare "flock" in a name goes nowhere.
Honest limits: the cameras upload over LTE, the beacon is off on many
units since 2025, and a phone cannot see the Wi-Fi probe requests the
newest detectors use. The Bluetooth exposure page shows what the phone's
Bluetooth gives a roadside or in-store sensor: name (one-tap rename to the
model), discoverable state (an alert after three minutes of it), and each
paired accessory's address kind, with which were connected on the owner's
drives. What Android withholds from apps is said rather than faked:
switching Bluetooth off, unpairing, and the address type below Android 15.

Done since (GPS, 2G, VPN coverage, ultrasound, tour): satellite spoofing and
jamming checks (signal spread and strength, satellite against network time,
GPS against Wi-Fi/cell position, impossible jumps, mock-location apps, clock
drift, front-end gain, constellations), a warning over the navigation map; a
2G row that proves 2G is allowed when the phone is seen on it (the switch
itself is unreadable to apps: `getAllowedNetworkTypesForReason` needs
READ_PRIVILEGED_PHONE_STATE and the null-cipher preference is @hide); VPN
coverage checks (DNS, IPv6, per-app exclusion, lockdown, proxy); an
ultrasonic beacon listener with a speaker-to-microphone self-test; and a tour
of every screen whose pages are built from the app's own lists, so a feature
without tour text does not compile. The Kotlin unit tests now run in CI, and
an emulator job drives every screen.

Done since (mesh, mixing, independent relays): an offline Bluetooth LE
mesh that carries sealed texts, group texts and SOS phone to phone under a
daily routing tag (off by default); relay-side mixing, each non-urgent
envelope held an exponential random time (mean 4 s, cap 20 s) and released
in shuffled batches, with call set-up, SOS and group changes exempt; and
federation, so independent relays reach each other: the home relay forwards
anonymous sends and lookups to the contact's relay signed with its own
Ed25519 key, which the other relay fetches from `GET /`, with the target
relay, a timestamp and a single-use nonce under the signature and per-relay
limits. The app keeps each contact's relay and refuses a second contact with
a number it already has. Tested end to end over two in-memory relays, and the
app's relay round trip against a real local relay with mixing on. Limits: one
mixing relay is not a mix network (the operator sees in and out), and a
lookup or send through another relay tells your own relay which relay and
number you write to.

Done since (first review of federation): a review of the new relay and app
code found two real holes, both fixed with tests.

- A mailbox checked whether a number existed before checking an envelope's
  size, so an oversized envelope was "too large" for a real number and
  "accepted" for a made-up one: a one-request test of whether any number,
  unlisted or not, exists. Through another relay it cost nothing. The size is
  now checked first, in the request and in the mailbox, for every number alike.
- Another relay spends no delivery tokens here, so it could fill a mailbox or
  use up the allowance this relay's own senders draw on. Other relays now share
  an allowance of their own per mailbox (300, then 1,800 an hour), stop 500
  envelopes short of a full queue, and each is held to 60 (then 30 a minute)
  per recipient, applied before anything is known about the recipient.
- Group messages matched members by number. With relays reaching each other a
  number is not a person: anyone running a relay can present any number there.
  Group sends and receives now require the key the roster holds for the member
  (or, where the roster has none, a contact on this phone's own relay or one
  verified in person).
- Mixing shipped to the relay before an app that marks calls and SOS as
  urgent: the published Beta 4 sends them unmarked, so the live relay held
  Beta 4's call set-up and SOS for a few seconds. The relay now holds only
  envelopes that ask to be mixed ("mix": true), which no app before mixing
  sends.
- Every text field now asks the keyboard not to learn from what is typed
  (IME_FLAG_NO_PERSONALIZED_LEARNING), and Phone check names the keyboard in
  use and whether it can reach the internet.

Done since (Beta 6):

- **Blind tokens.** The tokens every send and traffic request spends were
  random values the relay made, so a relay that recorded its own answers to
  `POST /v1/tokens` could match each spent token to the account it went to.
  They are now RFC 9497 VOPRF tokens (ristretto255-SHA512, as Privacy Pass):
  the phone makes each input, the relay evaluates only its blinded form with a
  DLEQ proof that it used the key it publishes at `GET /`, and the phone keeps
  the first key it saw each epoch and refuses any other. The relay keeps
  nothing at issuance and a hash of each spent token afterwards. One Rust
  implementation (`voprf` 0.5.0) runs on both ends, the relay's compiled to
  WebAssembly and rebuilt byte for byte in CI; it derives the same keys and
  proofs as an independent RFC 9497 implementation (@noble/curves), which the
  relay's tests use as the phone. Limit: key consistency rests on GET /
  answering every caller alike; a relay that served one phone its own key on
  every request from the start of an epoch, anonymous ones included, would
  not be caught by that phone alone.
- **Mesh tag.** The routing tag was a hash of the recipient's public sealing
  key and the day, which any contact of the recipient (and the relay) could
  compute. It is now HKDF over the X25519 secret the sender's and recipient's
  sealing keys share, computed inside the crypto crate; only the two ends can
  tell whose a bundle is. Bundles between the same two people on one day
  still share a tag. Beta 5's tag is still recognised on arrival, which
  reveals nothing, since a phone never announces the tags it answers to.
- **Existence through other relays.** Several relays together could fill a
  mailbox or its allowance for other relays and learn from a 413 or 429 that
  the number exists. An envelope from another relay that would go over those
  limits is now dropped and answered exactly as one for a number that does not
  exist. This relay's own senders are still told when a mailbox is full; they
  are bounded by the tokens their accounts may draw.
- **Disappearing messages.** A per-conversation timer (5 minutes to 4 weeks),
  told to the other side and carried on every message; each copy is deleted
  that long after it is read (sent, for one's own), with its photo or voice
  note, and the database overwrites deleted rows (`secure_delete`).
- **Pinned NDK and cargo-ndk** (27.2.12479018, 4.1.2): the build refuses any
  other version, so a release can be rebuilt with the same tools.

Known and not fixed:

- The mixing code, the mesh and the blind tokens have had no outside review.
  Nothing here has had an independent audit.

Still open after PR #15: 4, 5, 7 (by decision), 23, 25 (coin farming),
30, and the phase-2 work still ahead: place search beyond road names on the phone, a published release rebuilt and
matched (CI now requires two builds of every change to agree, and from
Beta 6 the NDK and cargo-ndk are pinned; `tools/reproducible/` says how to
compare a release), an audit.

## Not re-tested

No Gradle, cargo or wrangler run was possible in the audit environment. The
minify change in this PR is the one edit that can fail a release build;
check the first release build's R8 output for missing-class warnings.
