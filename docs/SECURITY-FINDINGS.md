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
    **Done (Beta 8), with contacts' passes:** each phone gives its relay a
    pass key (`PUT /v1/pass`) and gives its contacts the key inside their
    end-to-end encrypted messages. Their envelopes carry a pass, HMAC-SHA256
    under that key over `clairix-pass-v1|number|envelope`, so it is bound to
    the envelope and a relay forwarding one cannot reuse it. A mailbox with a
    pass key gives envelopes without a good pass 300 places in the queue and
    300 an hour, and drops what goes past them as it drops a send to a number
    that does not exist. Deleting a contact makes a new key, given to the
    relay and sent to every other contact, so a contact deleted for flooding
    is a stranger from then on. Left: a stranger can still use up the
    strangers' share, holding up first messages from other new contacts for
    up to an hour; contacts are unaffected. The relay learns whether an
    envelope came from someone the owner wrote to, not who.
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
    *Verified for the URL; interception reported.* **Done (Beta 8):** invite
    links are `https://clairix.app/i#…`, a verified App Link: the site
    publishes `/.well-known/assetlinks.json` naming `app.clairix` and the
    release signing key, so Android opens the link in Clairix alone. Off the
    phone the link opens the project's page rather than the relay's, so a
    relay named in a crafted invite cannot serve its own page (or its own
    download) to the person invited; that page opens Clairix with an intent
    naming its package, as the relay's page already did. Before registering
    from an invite the app names the relay and says whether it is on the
    signed relay list; for one that is not, it says what the operator would
    see and Register waits until the owner confirms they know who runs it.
    The `clairix://invite` filter stays for links made before Beta 8 (the
    relay's page reaches it only by an intent naming the package); a link
    still only fills in the setup screen. The Play edition, when published,
    needs Play's app signing key added to `assetlinks.json`.
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
  still share a tag. (Beta 6 still recognised Beta 5's tag on arrival, and
  said that revealed nothing. It did: see the second review below, which
  dropped it.)
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

Done since (second review, after Beta 6): four separate reviews, of the
crypto crate, the relay, the app's messaging code and its on-device
protection, each reporting findings with a concrete attack and, where it
could, a reproduction. Every finding was checked before it was fixed, and
every fix has a test (relay findings were reproduced against the real Worker
code first, and those reproductions are now regression tests). What was
fixed:

- **A crafted link could take over a verified contact.** A pairing code is
  only self-signed, yet one arriving by link or paste replaced an existing
  contact's keys and marked them verified, rewriting group rosters too: a
  full man in the middle. Codes are now trusted by where they came from:
  scanned in person they add a verified contact, only a scan on the
  contact's own page replaces their keys, and a link, invite or paste never
  verifies anyone or changes a contact already held. Contacts from invites
  start unverified (the invite page is served by the relay).
- **The relay could tag blind tokens by epoch.** The phone took the token
  epoch from the relay's signed answer and, for a new one, fetched the keys
  at once, on a connection the relay could tie to the request. A relay could
  give each account an epoch and key of its own, and every token it spent
  would name it. The phone now accepts only epochs its own clock agrees with,
  pins each epoch's keys ahead of time (`GET /` publishes the next epoch's
  too), never fetches keys right after asking for tokens, and reads them at
  a random later moment on a fresh connection. A relay that ever issued
  blind tokens is never asked for the old linkable kind again.
- **Anonymous requests shared a connection.** Sends to every recipient,
  cover traffic and traffic probes (with positions) all went over one
  long-lived connection, which grouped them for the relay into one phone's
  contact set and drives. Each anonymous request now has its own connection
  and TLS session.
- **One blind token spent four times.** 43 base64url characters carry two
  bits beyond 32 bytes and the decoder ignored them; spent tokens were
  recorded by text. Only the canonical spelling is accepted now.
- **One key, several spellings.** The relay's one-account-per-key record was
  keyed by text, and padding or X25519's ignored top bit gave other texts for
  the same key; the crate keyed sessions the same way. Both now accept only
  canonical keys (the relay records both base64 spellings) and the crate
  keeps sessions under the canonical text.
- **Payments told whether an unlisted number exists**, for free: "Payment
  refused" against "Insufficient balance". The debit now comes first, so a
  payer who cannot cover the amount hears the same either way.
- **The relay fetched any https address for anyone**, before checking a
  signature, again on every request, reading the answer without a limit.
  Relay addresses are port 443 only with no trailing-dot hosts; a failed
  fetch is remembered for ten minutes; key fetches share one relay-wide
  allowance; answers are read only to 4 KiB (keys) and 64 KiB (forwards).
- **One-time keys through other relays** skipped the phone's own claim
  limits; they now apply, and past them the other relay is asked for the
  fallback key.
- **Traffic.** Two made-up trips could delete any report; voting a report
  away now costs report tokens, needs as many votes as it had confirmations,
  and never removes a work zone. One phone could spend a provider's whole
  daily quota in an hour; quotas are now paced over the day.
- **The mesh singled phones out.** A phone listed bundles it had been handed
  but never forwarded them, so a peer connecting with an empty hello learned
  which bundles were for it; Beta 5's tag, which anyone with a public key
  could make, let a stranger push a probe only the target would take; own
  bundles left with the first hop count at the exact minute. Delivered
  bundles are now carried on like any other, the old tag is no longer
  answered, and new bundles start one to three hops along, dated to the
  hour. A phone's own bundles cannot be pushed out by a flood, one tag holds
  at most 50, and a meeting at most 200.
- **The relay controlled the disappearing-messages timer.** An envelope time
  far in the future escaped the bounds check; a timer change so dated
  outlasted every later one. Both times are now bounded.
- **Message ids** were global across conversations, so reusing one could
  draw a receipt for a group message; ids must now be UUIDs, and an id used
  elsewhere is dropped without a receipt. Media files could collide on a
  near-identical id; they cannot now.
- **Payment notices** were believed as sent; they now show as unconfirmed
  until the relay's record shows the credit.
- **One contact could use up session recovery** for everyone (ten an hour in
  all); now three per contact out of thirty.
- **The app lock was only drawn over the app.** Its semantics stayed in the
  accessibility tree, so TalkBack, an accessibility service or uiautomator
  could read and operate what was underneath, and a keyboard could move focus
  into it. While locked the app now has no semantics, focus cannot enter it
  and its key events are dropped. A device test checks nothing shows through.
- **The duress wipe waited on the network** for up to six seconds before
  deleting anything, and could be interrupted with everything intact. It now
  deletes every Keystore key first, then everything else, and asks the relay
  last with a deletion signed before the keys went; an interrupted wipe is
  finished by any process start, and nothing can make a new key mid-wipe.
- **Notifications on a locked phone** showed senders, text and SOS positions
  unless "hide previews" was on (it was off without a passcode). Finding 8
  claimed more than the code did. The channels are now private on the lock
  screen, previews are hidden by default, what arrives while Clairix is
  locked is redacted from the start, and an SOS on the lock screen names no
  one and gives no position.
- **A duress-phrase SOS left evidence**: a timeline entry and a comms-log
  line a captor would find after a forced unlock. It is now recorded nowhere.
- **The lock's settings file said whether a duress code exists.** A phone
  with a passcode now always holds a duress salt and verifier, random ones
  when none is set.
- **Map tiles**, a street-level record of where the owner has been, sat in
  shared external storage outside sealing and retention. They are now in the
  private no-backup directory, capped, and pruned with the history.
- Smaller: the lock comes down when the screen goes off; the relay clock
  sample and the token-key pins are sealed; a Keystore hiccup no longer
  deletes the message database; exported reports and plaintext play copies
  no longer linger; the sealed box refuses low-order keys; `encrypt` keeps a
  Curve25519 key to the identity it belongs to; the identity state is
  serialised without leaving unwiped partial copies.

Open from the second review, each a design change rather than a fix:

- **The sealing key never rotates**, so sealed sender has no forward
  secrecy: whoever logs envelopes at the relay and later seizes the phone
  learns who sent each one and when. *Since fixed (envelope format 3):*
  envelopes are sealed to a signed sealing prekey each phone replaces every
  week. A replaced one is deleted once nothing can still arrive for it: two
  weeks and three days after it was made, once the mailbox has been emptied
  since, and 30 days after it was replaced at the latest. Senders refuse a
  prekey over two weeks old, and never fall back to the long-term key for a
  contact once seen with a prekey, so a relay cannot force the old format by
  withholding one. Limits: the window is up to about two and a half weeks,
  not per message; format-2 envelopes from apps before Beta 7 are still
  accepted and still sealed to the long-term key; the offline mesh cannot
  reach a contact whose prekey this phone last fetched over two weeks ago.
- **The sender's claim is a signature**, so a relay log plus the recipient's
  key proves to anyone that the sender sent a given ciphertext. Olm itself
  is deniable; this layer is not. *Since fixed (format 3):* the claim is an
  HMAC keyed from X25519 between the sender's long-term sealing key and the
  recipient's prekey. The recipient could have made it, so it proves nothing
  to anyone else. (The sender's bundle signature over its own keys travels
  with it; that says who owns the keys, not that they sent anything.)
- **Every comms key works while the phone is locked** (after first unlock),
  so an exploit that runs as the app reads everything (finding 7). *Since
  fixed:* what reads conversations is under a Keystore key that works only
  while the phone is unlocked (setUnlockedDeviceRequired). That covers the
  identity's pickle key, the message database's key, stored media, the
  duress phrase and the comms log. Each moves from the old key on its first
  read after the update. When the screen goes off, the app drops the
  identity from memory, closes the database and puts the log away.
  Envelopes that arrive meanwhile are collected with a transport key that
  can only fetch, acknowledge and stream envelopes. They wait in a locked
  inbox, still sealed to the phone, and are read on unlock. A call rings as
  "Clairix call" (full screen, "Incoming Clairix call"), naming no one:
  Answer unlocks and answers, Not now silences it. An SOS shows as "SOS
  from a group member", anything else as "New Clairix message".
  Limits: a call in progress keeps everything open until it ends (the screen
  goes off against a cheek). The database password lives in a Java string
  until the garbage collector reclaims it. The relay address, number and
  delivery tokens stay readable, so the phone can connect. Disappearing
  messages due while locked go at the unlock. The offline mesh cannot tell
  its own bundles apart until then. A phone with no secure lock screen has
  no locked state, so nothing changes there: Android 14 will not make an
  unlock-bound key without one at all, so those phones use a key with no
  unlock condition. Everything is encrypted with one data key, and only that
  key is wrapped by the Keystore. Android 14 deletes unlock-bound keys the
  moment the lock screen is removed (measured on the emulator), so the data
  key, in memory while the vault is open, is wrapped again under the key
  that fits whenever the lock screen changes: on every screen and unlock
  event and whenever Clairix comes to the front. A lock screen removed while
  Clairix's process is not running (or has not opened the vault since it
  started) still loses the vault. The data key, like the database password,
  stays in the process's memory after sealing until it is reused.
- **Fake relays cost nothing.** Any https host serving a key counts as a
  relay, so wildcard subdomains can use up a mailbox's allowance for other
  relays and silently crowd out real cross-relay mail. *Since fixed:* mail
  from a relay no phone here has written to (or looked a number up on) in 90
  days draws on a small share of its own per mailbox (10 at once, 30 an
  hour). A made-up relay now has to be written to from here first, which
  costs a delivery token each, before it shares the allowance relays people
  use rely on. The relay keeps only the day someone last wrote to each
  relay: not who, nor to which number.
- **Envelope length shows the Olm message type** (a new session is larger)
  and roughly how many messages went unanswered. *Since fixed for short
  messages (format 3):* the sealed inside is padded to 1 KiB steps, so a
  first message and a reply of up to about 480 bytes of text are one size;
  longer ones differ only when the extra hundred bytes cross a step.
- Old pickles stay decryptable under one never-rotated key; invite trees let
  one account multiply itself; a hello's list of carried ids fingerprints a
  phone for up to 48 hours; the relay's clock drives the clock-integrity
  check; the lock's failure counter and wipe threshold are in a plain file.

Known and not fixed:

- The mixing code, the mesh and the blind tokens have had no outside review.
  The second review above was internal; nothing here has had an independent
  audit.

Still open after PR #15: 4, 5, 7 (by decision), 23 (since done, Beta 8), 25 (coin farming),
30 (since done, Beta 8), and the phase-2 work still ahead: place search beyond road names on the phone, a published release rebuilt and
matched (since done: Beta 7 rebuilt from its tag matches both published APKs,
and `verify-release.yml` checks every release as it is published), an audit.

## Not re-tested

No Gradle, cargo or wrangler run was possible in the audit environment. The
minify change in this PR is the one edit that can fail a release build;
check the first release build's R8 output for missing-class warnings.
