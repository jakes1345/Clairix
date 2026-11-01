# Clairix

**A security app for a stock Android phone.**

Clairix finds hidden cameras, trackers, fake cell towers and spyware, gives
you encrypted messages and calls that keep working when other things do not,
and navigates without the internet. It is built for the phone you already
have: no root, no custom ROM, no unlocked bootloader, because most people
cannot get those, and a locked phone from a carrier is still a phone that
needs protecting.

This README says what the app does, who it is built to stand up to, and,
just as plainly, what it cannot do.

---

## What it does

| What you want to do | How Clairix helps |
|---|---|
| Find a hidden camera | Point the phone around the room and it spots the glint a lens throws back when it can see you |
| Know if someone is tracking you | Watches for AirTags, Tiles and similar trackers, and says FOLLOWING only once one away from its owner has been heard at places far apart while your own position shows you travelled between them; anything else that does the same is reported as travelling with you, not as a tracker |
| Catch a fake cell tower | Learns the towers that are normal for the places you go and warns when one breaks the rules |
| Check for spyware | Scans every app on the phone and flags anything that can read your screen, your passwords or your microphone, matches every app against a public list of known stalkerware by package name and signing certificate, says when the phone is managed by someone, and checks the phone itself: installed certificates, proxies, USB debugging, patch level. It runs at boot, after each update, once a day while Clairix is in use or scanning, and after each install while the scanner runs, and says what is new |
| Spot a fake Wi-Fi | Detects hotspots set up to impersonate the ones your phone already trusts |
| Talk without being listened to | Messages and calls are end-to-end encrypted on the phone and sent as sealed envelopes the relay cannot open and is not told the sender of |
| Navigate without internet | Turn-by-turn directions from signed offline map files, with anonymous crowd-sourced traffic and incident reports when you are online. United States only for now: the 50 states, DC and Puerto Rico |
| Know where the plate readers are | Flock Safety and other licence-plate cameras mapped in OpenStreetMap are shown on the map, spoken before you reach one, counted per route and routed around by default (on the US maps above); a Flock camera battery, Raven gunshot detector or Flock hotspot heard over radio is reported too, matched against the map or flagged as unmapped |
| Know if your GPS is lying | Checks that the satellite signals are from satellites: strengths too even or too strong, satellite time against network time, the GPS position against Wi-Fi and cell towers, impossible jumps, mock-location apps, and jamming; navigation warns on the map |
| Hear the inaudible | Listens for the ultrasonic beacons ad-tracking kits play from TVs, shops and web pages to tie your phone to them, after a self-test that proves the microphone can hear that band |
| Know where everything is | A guided tour of every screen and tool on first launch, from Settings any time after |
| Know what your Bluetooth gives away | Shows what a roadside or in-store sensor can log: the phone's name (renamed to the model in one tap if it is yours), whether it is discoverable, and which paired accessories keep one address for life and were connected on your drives |
| See your car's health | Connects to the car over Bluetooth OBD and shows live engine data and trip history |
| Track aircraft nearby | Plug in an SDR stick and list nearby aircraft from their ADS-B broadcasts, with callsign, altitude, speed and heading (a list, not map markers) |
| Listen to a scanner | The same stick scans the channels you add and plays them; transmissions are recorded only when you turn recording on, sealed on the phone like its other records. The stick runs on the phone's battery, and below 15 % with no charger drive mode stops it until the phone charges |

---

## Who it is built against

Clairix assumes the adversary is a state: one that can compel the companies
the app depends on (Cloudflare, which runs the relay; GitHub, which hosts the
releases and the map files; Google, whose Play services work out the phone's
position on most phones; your carrier), watch the network, seize the phone,
and may hold an exploit for the operating system. Everything below is written
for that case. A jealous partner, an employer or a thief gets the same
protection with far less effort.

### What it protects

**What you say.** Every message, voice note, photo and call is encrypted on
your phone for one contact with the Olm double ratchet (the audited
[vodozemac](https://github.com/matrix-org/vodozemac) library), then sealed
in an anonymous envelope to the recipient's key. The relay stores and forwards
envelopes it cannot open. A compelled relay, or a compelled Cloudflare, hands
over ciphertext. Call audio is DTLS-SRTP between the two phones; a TURN server
in the path forwards packets it cannot decrypt.

**Who you talk to, from the relay's records.** Sending an envelope is not
signed and names no account: it spends a single-use token your phone fetched
earlier, in a batch, over its signed channel, and the relay keeps only each
token's hash in a store that never learns which account it went to. Looking
someone up names them in a signed body, never in a URL that a request log
could keep. Nothing a mailbox stores says when its owner was last here:
limits are kept to the hour, nonces to the minute, registrations to the day,
ClairixCoin transfers to the hour.
Traffic and incident reports are sent the same way, under a pseudonym the
phone makes up for each drive and replaces every ten minutes. An image of the
relay's storage shows public keys, queued envelopes with their recipient, size
and time, and nothing about senders, lookups or activity, except ClairixCoin
where the relay's operator has not switched it off: a balance, and the amount
and hour of each transfer for 30 days, never who paid whom.

**What is on the phone when it is seized locked.** The location log, the
timeline, the cell-tower baseline, vehicle trips, the unlock ledger, the
radio scanner's recordings, the whole Comms database (contacts, messages,
groups), the Comms settings, keys and saved cards are sealed on disk under
keys in the phone's secure hardware, so a copy of the app's files is
ciphertext. (Recordings made by earlier versions were plain audio; the
Scanner card offers to encrypt or delete any that are left.) The app lock
can wipe after a run of wrong passcodes (a number you pick when you set it;
until you do, none) and then closes. A separate duress passcode wipes at
once and leaves a decoy: the lock screen says "Wrong passcode" as it does
for any wrong guess, goes blank for a moment while Clairix restarts behind
it and is back, and from then on that code opens Clairix, empty (no history,
contacts or Clairix number, first run done). The "Wipe Clairix" page (Comms
menu, or Settings) wipes on demand; a wipe deletes the keys first, so cutting it
short leaves only ciphertext. Locking collapses every message notification
into "Unlock to read" and every alert (a tracker, a cell anomaly, a
phone-security warning) into one "Unlock to see alerts", stops read receipts,
disarms a card armed for NFC readers, and hides the app from accessibility
services as well as the eye; it locks the moment the screen goes off. An alert
names what it found only while Clairix is unlocked and only if you turn "Show
alert details in notifications" on (it is off by default), and the ongoing
scanner, drive-mode, mesh and navigation notifications say only that they run.
On a locked phone the alert and connection notifications do not appear at all,
message notifications show only that something came (previews are hidden by
default), and every screen is kept out of screenshots and the recents
thumbnail. History
older than the retention you choose (30 days by default) is deleted, so there
is less to give up in the first place.

**What you install and download.** Releases, offline map graphs and work-zone
files are signed with the project's Ed25519 key, and the app refuses a map or
work-zone file whose signature does not verify. A signature shows that a file
was published by this repository's own workflows with that key; it does not
show that what was signed is right. A map is built from Geofabrik's
OpenStreetMap extract, which can be checked only against the MD5 published on
the same server, so the signature cannot show that the roads are
OpenStreetMap's. The Python tools that build and sign the map and work-zone
files are installed only if they match hashes recorded in the repository, and
the job that signs them runs apart from the one that builds them. Each map's
release names the extract, its MD5 and its dates, and the commit it was built
from, so a published graph can be traced to what it was made from. Every
workflow action is pinned to a commit. The app trusts only the system's
certificate authorities, never a
certificate someone installed on the phone, and refuses cleartext; the
Certificates page can pin the relay's key so that a certificate from a
compelled authority is refused too.

**Where you go.** Routing runs from map files on the phone, so directions
keep working with no connection, and so does searching for a road: the map's
road names are indexed on the phone, and a dropped pin is named after the
nearest road from the same map, so a destination is never sent anywhere to be
found or named. Searching online for an address or a place is a separate tap
that says it sends the text to OpenStreetMap. The map's background tiles come
from OpenStreetMap's servers when you are online, which see your IP address and
the area you are looking at; they are not part of the download, so offline the
map shows only the areas you looked at before (up to 64 MB of them), and the
route line on a blank background anywhere else. The GPS track never leaves the
phone, and roughly the first kilometre of a trip (a distance that varies from
trip to trip) and its final approach are never reported to traffic. Spoken
directions use a voice that works offline when the phone has one; when it has
none, the route options say that the speech engine may send what it reads out
to its maker.

### What it cannot do

Read this part twice.

- **A compromised operating system.** Clairix is an ordinary app. If the
  phone's OS, baseband or a system app is working for the adversary, it can
  read what Clairix reads and see what you type. Clairix can notice some
  signs (root, an unlocked bootloader, failed verified boot, an added
  certificate, a proxy, USB debugging, a microphone or camera in use, a
  screen recording) and tell you. It cannot stop or remove an implant below
  it, and it does not claim to remove viruses.
- **A phone seized while unlocked**, or taken from your hand with Clairix
  open. Whatever the app can show you, the person holding it can see, except
  that with a passcode set, Patterns, Unlocks, the tracker map and the
  evidence export ask for it again each time they are opened. The wipe needs
  you to reach it, or the wrong-passcode limit to be hit.
- **Where you are, from the network.** Your carrier, and anyone who compels
  it, always knows roughly where a phone is from the towers it talks to, and
  the law in most countries lets it keep that. Clairix can detect a fake
  tower; it cannot hide you from a real one. Nothing an app can do changes
  this. Airplane mode, or leaving the phone behind, is the only answer.
- **Where you are, from Google.** On a phone with Google Play services, every
  position fix Clairix asks for (the scanner, navigation, SOS) goes through
  it, and one worked out from Wi-Fi and cell towers sends Google their
  identifiers. "Satellite-only positioning" (Settings, Location) asks the
  satellite receiver alone, at the cost of slower fixes and often none
  indoors. A phone without Google Play services uses Android's own location
  service.
- **Traffic analysis.** Anyone watching the network sees that your phone
  talks to the relay and when. Envelopes are padded to a few fixed sizes, and
  "Cover traffic" (Comms settings, off by default) sends envelopes nobody
  reads to random contacts at random intervals, so the real ones are not the
  only traffic there is. The relay holds each envelope a random few seconds
  (mean four, at most twenty) and releases what is due together in shuffled
  order, so traffic in cannot be paired with deliveries out by timing alone;
  call set-up, SOS and group membership changes skip the wait. That is a
  single mixing relay, not a mix network: the relay itself still sees every
  envelope arrive and leave, on a quiet relay there is little to mix with,
  and someone watching both ends for weeks can still correlate by volume. The relay's
  operator, or the platform under it, could capture live requests and see the
  address each one came from. The relay writes none of this down; what the
  platform keeps (Cloudflare's edge, for the project's relay) is under the
  platform's own policy, which we cannot check. From 3.0.0 Beta 6 the tokens a send
  spends are blind (RFC 9497, as Privacy Pass): the phone makes each one and
  the relay signs it without seeing it, so not even a relay that recorded
  every request can tell which account a send's token came from. If your IP address matters, use Tor or a VPN. A call hands your
  address to the contact's phone, answered or not, unless "Relay-only calls"
  is on, which it is by default whenever your relay offers a TURN server.
- **Relays.** Anyone can run a relay (comms-worker), and a contact on
  another relay is added by scanning their QR code: everything for them goes
  to your relay, which forwards it to theirs signed with its own key, so
  their relay sees your relay's address and never your phone's. Relays do
  this out of the box (an operator can turn it off); the contact's page says
  which relay they are on. An order can shut a relay down or make it refuse you;
  it cannot make it read your messages, and people on other relays are not
  affected. The operator can publish other addresses the same relay answers
  at, and the app falls back to them when the first is blocked (unless you
  have pinned the relay's key, in which case only the pinned address is
  used). With no network at all, the offline mesh carries texts and SOS
  phone to phone over Bluetooth. ClairixCoin stays within one relay.
- **Your keyboard.** It sees everything typed in every app before any app can
  encrypt it. Clairix asks it not to learn from what is typed in Clairix, which
  Gboard and Samsung's keyboard honour, and Phone check says whether the
  keyboard in use can reach the internet. A keyboard is free to ignore the
  request; one with no internet access cannot send anything anywhere.
- **Your contacts' phones.** A delivered message is theirs. A contact whose
  phone is compromised or seized unlocked gives up every conversation with
  them.
- **Detection is best-effort.** No app finds every camera, tracker or piece
  of spyware. A finding is a reason to look, not proof.
- **Someone who shares your home.** A partner or relative who handles the
  phone unlocked can see that Clairix is there: its name and icon are in the
  launcher and the app list, its device admin, once enabled, is listed by
  name in Android's settings, and the shade shows when the scanner runs.
  Anyone who knows the app passcode, or makes you unlock Clairix, sees what
  it shows; the duress passcode wipes instead of opening, and a wipe can
  itself be noticed. Removing a tracker or a monitoring app can tell whoever
  placed it that it was found, and Clairix cannot know whether they will
  notice; it says so before you remove anything and offers to save the
  evidence first. A call, text or chat to a helpline stays in the phone's
  call log, messages or browser history.
- **Other profiles.** The spyware check sees the apps in the profile Clairix
  runs in and no other: apps in a work profile, Samsung's Secure Folder,
  Android's Private space, a second space or a dual-apps profile are not
  scanned. Phone check says how many other profiles Android shows it; Private
  space is hidden from every app, Clairix included.
- **The scanner's keys work while the phone is locked.** The scanner has
  to write while the keyguard is up, so the keys that seal its records work
  while the phone is locked. Comms is different: since Beta 7 what reads
  conversations needs the phone unlocked, and what arrives while it is
  locked waits sealed until then. Code running as the app on an unlocked
  phone can read what the app can. The settings files (thresholds, the sleep
  window, the passcode's verifier, TLS pins) are not encrypted; they hold
  settings, not records.
- **No independent audit.** vodozemac has been audited. Clairix has not.
  `docs/SECURITY-FINDINGS.md` is the running list of what has been found,
  what is fixed and what is still open.

### What the relay keeps

Per Clairix number: the public keys; envelopes waiting for the phone, deleted
when collected or after 30 days, each marked if it came without a contact's
pass; the pass key your contacts' passes are made with; a UnifiedPush endpoint
if you set one;
unused invite codes for 7 days; the hashes of the old kind of token, which
apps from before Beta 6 fetch, for about 14 days while unspent; the hashes of
spent blind tokens, so none is spent twice, until the key they were made with
is retired, 14 to 28 days; a note that each key belongs to that number, so nobody else can
register it. For traffic: anonymous speed samples for 2 hours, learned
road-segment positions for 30 days, incidents until they expire. For
ClairixCoin, in Clairix Full on a relay that offers it: the balance, until
the account is deleted, and for each transfer you make or receive its id, the
amount, the hour and whether it came in or went out, the 100 most recent and
none older than 30 days. Never a name, a phone number, an email
address, a contact list, a sender, a lookup, a last-seen time, or who paid
whom. The full account is in [`comms-worker/README.md`](comms-worker/README.md).

### What ClairixCoin is

A balance of whole coins (AC) that people registered on the same relay can
pass to one another, in Clairix Full only, on a relay whose operator has not
switched it off (`COIN=off`); the app hides the balance and the Pay button on
a relay that says it is off. Coins come from the relay's operator and from
nowhere else: an account registered while the coin is on starts with
`COIN_INITIAL_BALANCE` coins (none unless the operator sets a number), and
the operator, holding `ADMIN_SECRET`, can mint coins into any account on the
relay. A transfer moves 1 to 1,000,000 coins on the payer's signed request,
and only to the account holding the identity key the payer's phone has
pinned for that contact; a note goes to the payee end-to-end encrypted, never
to the relay. It is not money: nothing in Clairix sells, buys, redeems or
exchanges it, it cannot leave the relay it was minted on, and it is not in
the Play edition. On the project's own relay, whether the coin is switched
off, the starting balance and whether minting is possible are set by its
maintainer on the live service, not in this repository.

---

## Emergency features

- **Wipe Clairix.** In the Comms menu or in Settings, in both editions: type
  WIPE, one button. It destroys every key in the secure hardware first; then
  it deletes every file, setting and database, cancels every notification and
  deletes Clairix's notification categories, clears the clipboard, unregisters
  push, gives up the device-admin role and disarms any card; then it asks the
  relay to delete the account, and Clairix closes. If the relay cannot be
  reached, that request waits on the phone, in a file that names the Clairix
  number and nothing else, and is tried again about every ten minutes for up
  to a day (with Tor on, only through Tor, while the wipe runs). The
  wrong-passcode limit runs the same wipe; so does the duress passcode, except
  that Clairix stays on its lock screen and that code then opens it, empty.
  Android keeps the app installed, and its settings keep the access you gave
  it and its battery history: uninstall Clairix when its presence is the risk.
- **Duress phrase.** A sentence you can type into any chat. It is sent as
  written; underneath, an SOS with your position (the last known one, or a
  fresh fix when the phone can read one within a few seconds) goes to every
  group you are in, and nothing about it is written on this phone: anyone the
  relay cannot take right away is tried again for about thirty minutes from
  memory only, and if Android closes Clairix those tries stop. In no group it
  sends nothing, and the Duress phrase page says so in red until you join one.
- **SOS.** Hold the red octagon until it fills and confirm: your position goes,
  end-to-end encrypted, to your groups, and the app then says how many people
  it reached. Anyone the relay could not take stays queued, one entry per
  person, and is tried again for about thirty minutes, with the screen off
  too; if Android closes Clairix meanwhile, the tries resume when it next runs
  with the phone unlocked, until the thirty minutes are up. A notification
  gives the count as retries reach more people, and the final one. Members
  you have blocked are sent nothing.
  A phone that missed a change to a group's members finds out from the next
  group message, which carries the newer list number, asks an admin for the
  current list and meanwhile holds back from anyone it cannot confirm, so
  someone removed stops getting your SOS. With no Clairix number, or no group
  with anyone else in it, the button says nobody would receive it instead of
  sending. A timeline entry records the count. Location is read at that
  moment and at no other time.
  For the weeks before you have a group, or as well, Settings, SOS has two
  extras, both off until you turn them on. "Also text this number" texts one
  number your position as an OpenStreetMap link (never a faked or old one;
  the text says when there is none): the full edition sends it itself, with
  the SMS permission it asks for when you turn the option on; the Google Play
  edition may not hold that permission, so it opens your messaging app with
  the text written and you press send. A text is not encrypted: your carrier
  can read it, and it stays in the messaging app's sent texts. "Emergency
  call button" puts a Call button on the SOS sheet that opens the phone app
  with the emergency number for the country of the mobile network (112 when
  that cannot be told); you press call, and Clairix needs no phone
  permission. The sheet says whether the text went, the messaging app
  opened, and the number was dialled. Both work with nobody in Clairix to
  send to. The duress phrase never texts or calls.
- **Fake call.** Rings the phone with its own ringtone and vibration for
  45 seconds, honouring silent mode, and shows a real-looking call screen over
  the lock screen, so you can walk away. It needs notifications and
  full-screen calls allowed for Clairix; the page schedules nothing until they
  are. Unlocked and in another app, Android shows a call banner carrying
  Clairix's name instead of the screen, which is why the page can also lock
  the phone as it schedules, once Clairix's device admin is on.
- **App lock.** A passcode for Clairix itself, with optional biometrics,
  offered on the first run. Wrong passcodes lock you out for longer each
  time; they wipe Clairix only after a limit you pick, which it asks for as
  soon as you set the passcode. Once you are in, it tells you when a wrong
  passcode was entered while you were away. The passcode is never stored;
  only a verifier made with a hardware key is.
- **Get help.** In Settings and the Comms menu: helplines for abuse and
  stalking in the country of your SIM card, or the phone's region, or any
  other on the list, with a button that opens the dialler and, where the
  service offers one, a text or chat for when calling is not safe. Every
  number was checked against the service's own website or an official
  government page on the date the page shows; the list is not exhaustive.
  Before Clairix opens the settings of an app it flagged, it offers to save
  the app's details (installer, install date, access, why it was flagged,
  its signing certificate's SHA-256) to the timeline and the clipboard, or
  to talk to someone first.

---

## Settings that change what the adversary gets

- **Hide names and previews in notifications** (Comms settings, on by
  default once a passcode is set): notifications name nobody and quote
  nothing.
- **Relay-only calls** (Comms settings): call audio always goes through the
  relay's TURN server, so a contact never learns your IP address from a
  call. On by default whenever your relay offers a TURN server, and off
  while it offers none, so calls still connect directly. Switched on by hand
  it stays on, and a call cannot connect while the relay has no TURN server;
  switched off, calls connect directly whenever both networks allow it.
- **Cover traffic** (Comms settings): envelopes nobody reads, to random
  contacts at random intervals while the background connection is on. Off by
  default; costs a little data and battery.
- **Refuse the relay if its key changes** (Phone check → Certificates): pins
  the relay's TLS key. Off by default because the relay's key rotates with its
  certificate; turn it on if you would rather lose the relay than trust a
  new certificate without checking.
- **Wipe after N wrong passcodes** (Settings → App lock): 1, 3, 5, 10 or
  never. Asked for when you set the passcode; never until you answer.
- **Keep history for** (Settings, with Export and Clear): 7, 30 or 90 days,
  or forever. 30 by default. The location log, the timeline, vehicle trips,
  the unlock ledger, the radio scanner's recordings and the map's tile and
  traffic caches older than that are deleted, once a day even with Clairix closed. The cell-tower baseline is
  kept, as which towers are usual and not when you were near them, and a tower
  not seen for three times the period is forgotten. An exported evidence
  report is deleted from the phone after a day.
- **Satellite-only positioning** (Settings → Location): off by
  default. On, every position fix comes from the satellite receiver alone
  and Clairix asks for no Wi-Fi/cell position, so no location service
  (Google Play services, on most phones) is sent the networks and towers
  around you; fixes are slower, often absent indoors, and cost more battery
  while the phone sits still. **Cross-check GPS against Wi-Fi/cell position**
  (Settings → Location, with the scanner): on by default; off, the GPS check
  asks for no Wi-Fi/cell position and its comparison does not run.
- **Record transmissions** (Drive → Radio → Scanner): off by default, and
  off again whenever drive mode ends. On, each transmission that opens the
  squelch is kept, sealed like the scanner's other records, for at most ten
  minutes each and 256 MB in all (the oldest go first), and is deleted with the
  rest of your history. The Scanner card lists them with play and delete. The
  first time drive mode is turned on or a channel is added, the app says that
  receiving and recording radio traffic is restricted in some places.
- **Route around known plate readers** and **Say when one is ahead** (Drive,
  route options): both on by default. The list is what OpenStreetMap has,
  largely from the DeFlock project; a camera nobody has mapped is not on it,
  so a route the app calls clear may not be. Avoiding one is worth a detour
  of up to two and a half minutes to the router; the route card says how
  many each route still passes.
- **Alert on surveillance hardware** (Settings → Scanning): on by default.
  A notification when a Flock Safety camera battery pack, a Raven gunshot
  detector or a Flock setup hotspot is heard; off, the sighting still goes
  to the timeline. The signatures are the ones the DeFlock and flock-you
  projects published; Flock has been turning these radios down since 2025,
  so silence next to a camera is normal and the map list is the better guide.
- **Bluetooth exposure** (Scan → Trackers): what the phone's Bluetooth tells
  a sensor it passes. The phone's own advertising address rotates; a
  personal-looking name can be replaced with the model name from the page;
  Classic accessories (a car's hands-free, most earbuds) keep one address for
  life, and the page lists which were connected on your drives. Android lets
  no app switch Bluetooth off or unpair a device, so those stay in Settings.
- **GPS** (Scan → GPS): runs while the page is open, and in the background
  with the scanner whenever the GPS is on anyway (the scanner's own fixes while
  you move, navigation, another app). In the background it switches the
  satellites on only on Android 12, and there only while you are moving; on
  later versions the receiver clock and gain-control checks have data in the
  background only when another app asks for raw measurements.
  It catches the spoofers that are cheap to build; one that simulates a full
  sky at the right time passes the signal checks, and the Wi-Fi and cell
  comparison is the one it cannot pass while a tower or access point is in
  range. That comparison needs a Wi-Fi/cell location provider on the phone
  and "Cross-check GPS against Wi-Fi/cell position" on (Settings, Location;
  it is by default, and Satellite-only positioning turns it off); where it
  cannot run, the GPS page says why.
- **2G** (Phone check → Health): Android keeps the "Allow 2G" switch's state
  from apps, so the row says whether the phone has the switch, marks it red if
  the phone has used a 2G tower in the past week, and opens the page where it is
  turned off. Say you have turned it off and the row reads off, keeps the last
  2G date as history, and turns red again only if the phone uses 2G after that.
  On Android 14 and later a second row points to Android's own cell network
  protections, which apps cannot read.
- **VPN coverage** (Phone check → Health): with a VPN up, flags DNS still going
  to the router or carrier, IPv6 the VPN does not carry, Clairix itself left
  outside the VPN, "Block connections without VPN" off, and a proxy on the
  network. The Wi-Fi tab's VPN row says whether Clairix's own traffic goes
  through the VPN, not just whether one is up.
- **Wi-Fi trap** (Scan → Wi-Fi): while the scanner runs, a made-up network
  name, replaced every six hours and on every start, is registered for the
  phone to probe for; only an answer-everything box (Karma, Pineapple) answers
  it. Android asks first whether Clairix may suggest networks, and the tab says
  whether the trap is armed. The name is untrusted, has auto-join off and a
  password nobody knows, and is withdrawn when scanning stops.
- **Ultrasound** (Scan → Ultrasound): on demand only, never in the background.
  Nothing is recorded; the microphone feeds a spectrum and the spectrum is
  thrown away.
- **Resume scanning after reboot** needs "Allow all the time" location;
  without it, scanning waits until you open the app.

---

## What Clairix looks like to another scanner, and what Settings shows

To do its own checks Clairix holds some of the access a monitoring app wants,
so a stalkerware scanner, another company's or Clairix's own scoring run
against Clairix, has things to point at. Clairix's own check skips Clairix.
What there is to see:

- **Where it came from.** Clairix Full and the Comms edition are installed
  from an APK file, and Settings says so; the Play edition says Google Play.
- **Permissions.** Location, and "Allow all the time" if you let the scanner
  keep running in your pocket; the microphone (calls, voice notes, the
  ultrasound listener); the camera (the camera sweep, pairing codes); nearby
  devices; notifications. The scanner editions can also see every installed
  app, for the spyware check, and hold usage access and the alarms permission
  if you grant them. Clairix Full and the Comms edition declare permission to
  send SMS, asked for only when you turn on "Also text this number" (Settings,
  SOS); they cannot read or receive texts. The Play edition does not declare it.
- **Device admin.** Only once you switch on the unlock ledger: Settings →
  Security → Device admin apps then lists Clairix, with two policies,
  watching lock-screen events and locking the screen. Android's activation
  screen says only that it records lock-screen events and can lock the
  screen when asked.
- **Running in the background.** The scanner, and Comms when it stays
  online, run as foreground services, each with its notification while it
  runs.
- **What it does not hold.** No accessibility service, notification access,
  keyboard, VPN or permission to draw over other apps.

Scored by Clairix's own model (`StalkwareScanner`), Clairix Full comes to 5:
2 for an install from outside a store, and 1 each for the microphone,
background location and a device-admin receiver. HIGH starts at 5. With the
unlock ledger's admin switched on it is 7, and 9 if the scanner then ran for a
week without the app being opened. The Comms edition comes to 3, listed as
medium. The Play edition comes to 2, below the 3 at which an app is listed,
or 4 after a week unopened; its device admin is known to the model, since it
comes from a store. Other scanners weigh these things differently. A unit
test feeds Clairix's own manifest to the model and fails when a permission
change moves these numbers.

---

## How to build it

**You need:** [Android Studio](https://developer.android.com/studio) or the
Android SDK and NDK with JDK 17, and [Rust](https://rustup.rs) with
`cargo-ndk` for the crypto crate in `comms-crypto`.

```bash
git clone https://github.com/jakes1345/Clairix
cd Clairix/android
./gradlew assembleFullSuiteDebug
# app/build/outputs/apk/fullSuite/debug/app-full-suite-debug.apk
```

Every push also builds the debug APKs in CI (Actions → Android APK →
`clairix-debug-apk`), and builds the app twice on two machines, failing
unless the two builds agree. Editions, variants, release
signing and how to cut a release are in
[`android/README.md`](android/README.md), which also says how to check a
published release against the source; the relay is in
[`comms-worker/README.md`](comms-worker/README.md); the crypto crate in
[`comms-crypto`](comms-crypto).

**Before the first release** the asset signing key has to exist:
`python3 tools/signing/keygen.py` writes `android/asset-signing.pub` and
`android/asset-signing.pem` (commit both) and prints the secret for the
`ASSET_SIGNING_KEY` secret, which belongs in the `release` and `assets`
environments ([`docs/PROCESS.md`](docs/PROCESS.md)). Without them, release
builds and the maps and work-zones workflows fail on purpose. How changes
reach main and how a release is checked before it is signed are in
[`docs/PROCESS.md`](docs/PROCESS.md).

## Requirements

- Android 12 or newer.
- Permissions for camera, location, microphone, Bluetooth and NFC, each asked
  for only when the feature that needs it is used; in Clairix Full and the
  Comms edition, sending SMS, asked for only when the SOS text is turned on.

## Reporting a vulnerability

Write to **security@clairix.app** first. Give us enough to reproduce it; a
proof of concept is welcome, other people's data is not. We acknowledge a
report within 3 business days, triage it within 10 and fix it, or agree a plan
with you, within 90 days; good-faith research is covered by a safe harbour,
and we credit you if you want to be credited. [SECURITY.md](SECURITY.md) has
the whole policy, including how to send a report encrypted;
[docs/audit/](docs/audit/README.md) is for auditors. Abuse of the relay is
handled as [docs/ABUSE.md](docs/ABUSE.md) describes.

## License

Copyright © 2026 the Clairix authors.

Clairix (the Android app, the Rust crypto crate, the relay, the website and
the tools in this repository) is free software: you can redistribute it and/or
modify it under the terms of the GNU General Public License, version 3, as
published by the Free Software Foundation. It is distributed in the hope that
it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE)
for the full text.

**Additional permission under GNU GPL version 3 section 7.** If you modify
this program, or any covered work, by linking or combining it with Google Play
services libraries (or a modified version of those libraries), containing
parts covered by the terms of the Android Software Development Kit License
Agreement, the licensors of this program grant you additional permission to
convey the resulting work. Every edition includes Google Play services'
location library, used for the position fixes of the scanner, navigation and
SOS on a phone that has Google Play services, unless Satellite-only
positioning is on; messages and calls use no Google service.

The fonts the app ships with keep their own licence, the SIL Open Font License
1.1: Archivo ([licenses/OFL-1.1-Archivo.txt](licenses/OFL-1.1-Archivo.txt))
and JetBrains Mono ([licenses/OFL-1.1-JetBrains-Mono.txt](licenses/OFL-1.1-JetBrains-Mono.txt)).
Libraries the app and the relay depend on keep theirs.

The stalkerware list (`android/app/src/suite/assets/ioc.json.gz`, and the
`ioc` release the app updates it from) is adapted by `tools/ioc/convert.py`
from [Stalkerware Indicators of Compromise](https://github.com/AssoEchap/stalkerware-indicators),
maintained by Julien Voisin and Tek for the Echap non-profit, and used under
the Creative Commons Attribution 4.0 licence; the converter keeps the app
names, package names and certificate fingerprints and leaves out the rest
([licenses/CC-BY-4.0-stalkerware-indicators.txt](licenses/CC-BY-4.0-stalkerware-indicators.txt)).

---

*Map data © OpenStreetMap contributors, ODbL.*
