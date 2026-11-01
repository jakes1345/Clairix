# Clairix comms relay

A Cloudflare Worker that stores and forwards end-to-end encrypted envelopes
between Clairix apps. It is the only server in the design, and it is built to
know as little as possible.

**Clairix numbers are not phone numbers.** Each Clairix identity gets a random
nine-digit Clairix number from this relay at registration. It works only between
Clairix apps paired with the same relay. It cannot call or text a phone, and a
phone cannot reach it. What it buys is a handle you can give someone without
giving them your real number or your identity key.

## What the relay sees

- The public keys of each registered identity (they are public by design).
- For each envelope: the recipient's Clairix number, a size (envelopes are
  padded to a few fixed sizes, so a receipt, a call signal and a short text look
  alike) and a timestamp. Not the sender: a send is not signed. It spends a
  single-use delivery token that the sender's phone fetched earlier, in a
  batch, over its signed channel. Tokens are blind (see "Blind tokens" below):
  the phone makes each one and the relay signs it without seeing it, so the
  relay cannot tell which account a spent token was issued to, even while it
  runs. All it keeps is a hash of each spent token, until its key is retired,
  in a shard object that never learns an account. Never the content: envelopes are sealed to
  the recipient's sealing key with an anonymous box, and inside that sits an
  Olm double-ratchet message the relay could not read even if it opened the box.
- Not when you were last here. A signed request leaves behind its nonce, kept
  five minutes to the minute, and nothing else: no last-seen, no last-fetched,
  no last-woken. Limits that must survive the object being evicted (invites,
  push endpoints, one-time keys handed out, the tokens an account may draw,
  what a mailbox takes in) are stored down to the hour, not the moment, and
  are deleted once they have refilled; the faster limits live in memory and
  are forgotten with it. Registration dates are kept to the day.
- Not who you look up. A lookup names the number in its signed body, never in
  the URL, so no request log at Cloudflare or anywhere else carries "who asked
  about whom"; the relay's own observability logging is off. The one-time key
  it hands out is remembered only as a claim ticket in the target's mailbox,
  with no requester.
- What it can still learn while running, if its operator (or the platform it
  runs on) captures live requests: the address a request comes from (the same
  phone fetches tokens and sends, so use Tor or a VPN if that matters). Apps
  from before 3.0.0 Beta 6 still fetch the old kind of token, random values the
  relay makes, which a relay that recorded its own answers could match to the
  account they went to; from 1 January 2027 the relay refuses that kind (see
  "Old versions"). The first envelope
  of a new session also carries the claim ticket of the one-time key it was
  built on, which the relay handed to the sender's account with the key; that
  envelope is linkable to that lookup, as the lookup itself already showed who
  wanted to reach whom. None of this is written down.
- Whether an envelope came from someone the recipient has written to, though
  not who. The phone gives its relay a pass key and gives its contacts the same
  key inside their end-to-end encrypted messages; their envelopes then carry a
  pass, an HMAC under that key over the recipient's number and the envelope.
  Once a mailbox has a pass key, envelopes without a good pass (from people the
  owner has never written to, contacts on apps from before passes, anyone the
  owner has deleted) share 300 places in the queue and 300 envelopes an hour.
  What goes past that is dropped and answered as a send to a number that does
  not exist, so the limit tells nobody whether the number is real. One account
  with a supply of delivery tokens can no longer fill a mailbox against
  everyone; at worst it holds up other first messages for an hour. The phone
  makes a new pass key when its owner deletes a contact and sends it to every
  other contact, so a pass taken from that contact stops working. A pass is
  bound to its envelope, so a relay that forwards one cannot reuse it.
- What phones report about a number (see "Abuse reports"): the number, a
  reason, an envelope's hash and the day. Never who reported it, and never
  anything that was said.
- Whether its operator suspended a number, and the day the suspension began
  (see "Suspending a number"). Nothing about why.
- Where its operator has turned ClairixCoin on (see "ClairixCoin"), each
  account's balance and its own record of the transfers it made and received:
  the id, the amount, the hour and which way it went, for 30 days and a
  hundred deep. Never who paid whom. A payment is signed by the payer and
  names the payee, so the relay sees the pair while it moves the coins, and
  writes down neither.
- The UnifiedPush endpoint an owner registers, if any, which it POSTs the word
  "wake" to when a live socket is not connected. Endpoints on ntfy.sh,
  up.conversations.im or a host in `PUSH_HOSTS` are used at once; any other
  host first gets a one-time code the phone must echo back, so the relay cannot
  be pointed at arbitrary URLs (see "Push wake-ups").
- For each live socket, while it is open and in memory only: when an envelope
  or an ack last went over it, which sets how often the heartbeat comes (see
  "The live connection"). It goes when the socket closes.

Authentication is by Ed25519 signature with the same identity key contacts
verify. There are no passwords or session tokens to leak; a delivery token
authorises one envelope and nothing else.

Each lookup of a contact's bundle hands out one of their one-time keys with a
claim ticket. The send that starts the session presents the ticket and spends
the key; a key whose ticket does not come back within 15 minutes returns to the
end of the pool, and a ticket that arrives later still takes its key back out,
so no key is handed out twice. A caller may claim at most three keys an hour
from any one number (counted in memory only, so the relay stores no record of
who looked up whom) and ten an hour in all, and a number hands out at most
twenty an hour; past any limit the lookup offers the fallback key (rotated
daily), so nobody can drain anyone's keys.

TURN credentials are minted per call attempt, ten a minute and thirty an hour
per account (the hour recorded as an hour, never the moment). A payment carries
an id the phone chose, and a repeat under the same id moves nothing twice.

Each account may draw 1,000 delivery tokens an hour.

## Blind tokens

From 3.0.0 Beta 6 every token is a blind token: RFC 9497 VOPRF over
ristretto255 with SHA-512, the construction Privacy Pass uses for privately
verifiable tokens (`src/tokens.ts`).

1. The phone picks a random 32-byte input, blinds it, and sends only the
   blinded element in `POST /v1/tokens {kind, blinded: [...]}` (signed, so the
   hourly allowance applies to its account).
2. The relay multiplies each element by its secret key for that kind of token
   and returns the results with one DLEQ proof that it used the public key it
   publishes at `GET /` (`tokens.keys`). It stores nothing.
3. The phone checks the proof against that published key, the same for every
   caller, and against the first key it saw that epoch; a relay that gave one
   phone a key of its own (and so could recognise its tokens) is refused.
   `GET /` also publishes the next epoch's keys (`tokens.next`), so a phone has
   pinned them long before it is issued any tokens under them, on a request
   that names nobody. It accepts only an epoch its own clock agrees with
   (this one or a neighbour), never reads the keys straight after asking for
   tokens, and never asks a relay that once issued blind tokens for the old
   kind again.
4. Spending a token shows `2.<epoch>.<input>.<output>` in `X-Clairix-Token`.
   The relay checks the output is what its key makes of the input and records
   the token as spent; it never saw the input before, so it cannot tell whose
   it was.

Each kind (`delivery`, `traffic`, `report`) has its own key, so one kind cannot
be spent as another. Keys live 14 days (an epoch) and tokens of the epoch
before are still accepted, so a token is good for 14 to 28 days. Every key is
derived from one secret: `TOKEN_KEY` if set (base64 of 32 random bytes), or
one the relay makes on first use and keeps in its "relay-identity" object.
Do not change it: phones refuse tokens under a key other than the first they
saw in an epoch, so a changed key stops sends until the next epoch.

The arithmetic is `voprf/`, a small Rust crate using the same `voprf` crate
the app does, compiled to `src/voprf.wasm` and committed so the relay deploys
without a Rust toolchain. `sh scripts/build-voprf.sh` rebuilds it (Rust 1.97.0
with the wasm32 target), and CI fails if the committed file is not exactly
what the source builds. Issuing 50 tokens costs the relay about 14 ms of CPU, checking one about 0.3 ms.

Tokens from apps before Beta 6 (32 random bytes the relay made and stored by
hash) are still issued and accepted until 1 January 2027 (see "Old versions").

## Old versions

Three things were kept for apps older than the oldest version Clairix still
supports (3.0.0 Beta 7; see SECURITY.md), each weaker than what replaced it:

- the old kind of token (`POST /v1/tokens {count}`, and spending one), which
  the relay makes itself and so could match to the account it went to; apps
  before Beta 6 use it;
- envelopes in format 2, whose first byte is 2: sealed to the recipient's
  long-term key, so whoever logs them and later seizes the phone learns who
  sent each one, and carrying a sender signature anyone can check. Apps
  before Beta 7 send nothing else, and newer apps send it only to them;
- lookups by URL, which went with API version 5.

From **1 January 2027** (00:00 UTC), two months after Beta 7 and Beta 8 came
out, the relay answers the first two with `426 Upgrade Required` and a message
that says to update Clairix from clairix.app/download, or, for someone whose
phone is up to date, that the person they are writing to needs to update
theirs. A format-2 envelope is refused on its first byte alone, before a token
is spent or a mailbox is asked, so the answer says nothing about the number it
was for. Blind tokens and format-3 envelopes, which every supported version
uses, are untouched.

`LEGACY_CUTOFF` (a UTC day, `YYYY-MM-DD`) moves the date for a relay of your
own; anything that is not a real day keeps 1 January 2027. The app itself
learns of newer versions, and of a version too old to be safe, from the
signed `latest.json` on the `relays` release (relays/README.md), not from the
relay.

## One-time setup

```sh
cd comms-worker
npm install
npx wrangler login              # or set CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID
npx wrangler secret put ENROLL_SECRET   # a long random string typed into Clairix once
npm run deploy                  # prints the Worker URL
```

In Clairix, open the COMMS tab, enter the Worker URL and the enrollment secret.
The app generates its identity on the phone, registers it, and shows the Clairix
number it was given. A registration is dated and must reach the relay within
five minutes, and each of its keys may belong to one account at a time, so a
captured registration (it carries the secret) cannot be replayed into a second
account, and nobody can register someone else's key as their own. Two people pair by scanning each other's QR code in
person, or by typing a listed Clairix number and then comparing safety numbers.

### How much each account may send

Every envelope a phone sends spends one delivery token, and each account is
issued 1,000 an hour (a 5 MB video is 171 envelopes). An operator whose
people send more, or who wants tighter limits, sets `DELIVERY_TOKENS_PER_HOUR`
to a whole number from 1 to 100000. A phone that has spent its hour's tokens
keeps what it sends queued and sends it when the next hour's arrive.

### Several doors to one relay

A relay that lives at one name can be blocked at that name. Set
`RELAY_ALTERNATES` to the other addresses the same Worker answers at (a second
custom domain, its `workers.dev` name, comma-separated) and the relay names
them in `GET /`. A phone keeps the list and, when the address it registered at
stops answering, tries the others and stays on the one that works; a phone
whose owner has pinned the relay's TLS key stays on the pinned address, since
the pin is that host's key. These are doors to one relay, not several relays:
the accounts, the envelopes and the coin community are the same behind each.

### Mixing

An envelope sent with `"mix": true` is held for a random time before it
reaches its recipient: exponential, with a mean of four seconds, never longer than twenty
(`MIX_MAX_MS` sets the cap; `"0"` turns mixing off). Every envelope due at
the same moment is released together, in shuffled order. Someone watching
traffic into the relay and traffic out to phones then cannot match an
envelope going in with a delivery coming out by its timing alone, and a burst
from one sender arrives interleaved with whatever else was due. The app asks
for it on everything except call set-up, SOS alerts and group membership
changes, which go straight through: a call that rings four seconds late is a
call missed, and an add followed by a remove must not arrive the other way
round. The relay holds only what asks, so apps from before mixing, which send
calls and SOS without saying which they are, are never delayed; the flag does
tell the relay which envelopes are which. Everything that asks may arrive in any
order, which the app handles: messages sort by their own time, receipts only
move forward, and a photo's chunks may overtake its header.

What it does not do: the relay operator still sees each envelope arrive and
leave, so mixing guards against outside observers, not the relay. It works
best with traffic to mix with; on a relay with a handful of quiet users the
delay hides little. A global observer who watches both ends for weeks can
still correlate by volume. The app's optional cover traffic (Settings, off by
default) adds dummy envelopes to random contacts, about one every twenty
minutes, which blurs volume a little but not against that observer.

### The live connection

A phone kept online holds one WebSocket to its mailbox. The relay sends it a
small `hb` frame, carrying `next`, the most milliseconds until the following
one: every two minutes for ten minutes after an envelope or an ack went over
the socket, and after a reconnect; then at the pace the app asked for on the
upgrade (`X-Clairix-Heartbeat`, two to ten minutes; the app asks for five). The
app sends no pings on a timer: it pings when a heartbeat is overdue, when
Clairix comes on screen or a call starts, and after a minute's silence while
Clairix is on screen or a call is up. An app that does not send the header,
every version before it, gets a heartbeat every two minutes, as before. The
heartbeat keeps carrier NAT from dropping the idle connection and lets a
sleeping phone notice a dead one.

### Push wake-ups

When a phone has no live socket, or does not ack a delivery within eight
seconds, the relay POSTs the word "wake" to the UnifiedPush endpoint the phone
registered, at most once until the phone next fetches or 30 seconds have
passed. The push service behind that endpoint (the server the phone's
distributor app uses: ntfy.sh unless its owner picked another) learns the
relay's address and when each wake was sent, which is when something arrived
while the phone was not connected; never what, or from whom. It also sees the
phone's own address, through the distributor's connection to it.

Endpoints on a trusted host are used at once; any other host first gets a
one-time code the phone must echo back. The trusted hosts are ntfy.sh and
up.conversations.im, plus any in `PUSH_HOSTS`. An operator who runs a push
server of their own ([ntfy](https://docs.ntfy.sh/install/) is one program) and
wants it to be the one their people use puts its host name in `PUSH_HOSTS`
and sets `PUSH_HOSTS_ONLY` to `on`: then only that host is trusted, and an
endpoint on ntfy.sh or anywhere else still works, after the check. The relay
cannot choose the server for anyone: each person points their distributor at
it (in the ntfy app, the default server in its settings) before setting up
push in Clairix.

```toml
# wrangler.toml
[vars]
PUSH_HOSTS = "push.example.org"
PUSH_HOSTS_ONLY = "on"
```

### Running your own relay, and reaching other relays

Anyone can run this Worker, and relays reach each other out of the box. Each
relay signs what it forwards with its own Ed25519 key, which it makes for
itself the first time it needs one and keeps in its `relay-identity` Durable
Object: nobody makes, copies or pastes a secret, and the seed never leaves the
relay. `GET /` publishes the public half. To supply the key yourself instead
(to keep it across a move to another account, say):

```bash
npm run relay-key                 # prints RELAY_KEY=… and the public key
npx wrangler secret put RELAY_KEY # paste the base64 seed
```

`FEDERATION=off` turns it off in both directions.

A phone then adds a contact by scanning the contact's QR code, which names
their relay. Everything for that contact goes through the phone's own relay:
the phone asks its relay, its relay forwards the request to the contact's relay,
signed with its key, and passes the answer back. The contact's relay
checks the signature against the key the phone's relay publishes at `GET /`
(fetched once a day, and again when a signature stops verifying, so a
replaced key is picked up), with a five-minute window and a single-use nonce
in each request. The target relay's address is part of what is signed, so a
request made for one relay is worthless at another.

What each side learns:

- The contact's relay sees the forwarding relay's address, never the phone's,
  and the recipient's number, as it would for any envelope. Neither the
  envelope nor the request names the sender.
- The phone's own relay sees which relay and which number the phone writes to.
  For a contact on the same relay it learns as much from the recipient alone.
- Each relay holds the other to a limit for all its phones together: 600
  envelopes a minute, 120 lookups a minute and 600 one-time keys an hour.
- Each mailbox takes at most 6,000 envelopes an hour from other relays
  together, and stops 500 short of a full queue for them. A relay no phone
  here has written to or looked a number up on in the last 90 days is a
  stranger: strangers together get 10 at once and 30 an hour per mailbox,
  so relays made up to flood a mailbox cannot crowd out the relays its
  owner's contacts are on. A relay on the `FEDERATION` list is never a
  stranger. The relay keeps the day someone last wrote to each relay, not
  who wrote or to whom.

`FEDERATION` chooses which relays may forward to this one: `open` (the
default), `off`, or a comma-separated list of `https://` addresses. A relay
reached at a custom domain while requests come in through another sets
`RELAY_ORIGIN` to the address other relays should fetch its key from.
Clairix numbers are random per relay, so the same number can exist on two
relays; the app refuses a second contact with a number it already has.
ClairixCoin does not cross relays: each relay is its own coin community.

A phone whose relay is blocked or gone can move to another one ("Move to
another relay" in Comms settings). It registers the identity it already has
there, so the new relay sees an ordinary registration with keys it has not
seen before, and gets a new number. Its contacts are told over the sessions
they already share, and their phones follow. The old relay keeps the old
account, and what is sent to it, for up to 30 days, while the phone collects
from it whenever it answers; then the phone deletes it. Both relays can see
that the same keys are registered on each, which ties the old number to the
new one for anyone who can read both.

### On your own server

The same Worker runs on any Debian 12 or Ubuntu 24.04 server under
[workerd](https://github.com/cloudflare/workerd), Cloudflare's open-source
Workers runtime, with no Cloudflare account. From a checkout, on a server
whose DNS name already points at it:

```bash
cd comms-worker
sudo ./selfhost/install.sh --domain relay.example.org --email you@example.org
```

It builds the Worker (`selfhost/build.mjs`: the bundle `wrangler deploy`
uploads, and a workerd config) and sets up:

- **The relay** as the `clairix-relay` service, its Durable Objects in SQLite
  files under `/var/lib/clairix-relay` (back this up: it holds the relay's keys
  and every mailbox). It listens on `127.0.0.1:8787` only, and fetches public
  addresses only, so nothing that gets it to fetch a URL (a relay address, a
  push endpoint) reaches the server's own services.
- **Settings** in `/etc/clairix-relay/relay.env`: every setting above, one
  `NAME=value` a line. `ENROLL_SECRET` is generated on the first run,
  `RELAY_ORIGIN` set to `https://<domain>` and `RELAY_ONION` to the onion
  address.
- **Caddy** in front, with HTTPS certificates it renews itself and no access
  log. It sets `CF-Connecting-IP`, which the registration limit counts by, so
  a client cannot choose its own.
- **An onion service** (leave it out with `--no-onion`), through Caddy too: the
  relay always sees the onion name as its host and never a client-supplied
  address. The relay names it at `GET /` (`onion`), and phones with "Connect
  through Tor" on use it instead of the https address.
- **Upkeep**: systemd restarts the relay if it exits, a timer restarts it if it
  stops answering for six minutes, another runs its scheduled work every 10
  minutes (what `crons` does on Cloudflare), and security updates install
  themselves, rebooting at 04:30 when one needs it.

Run the script again after `git pull` to update: it keeps storage and
settings, and swaps the new build in only once it has built. Logs:
`journalctl -u clairix-relay`. One server holds all of a relay's Durable
Objects; workerd does not spread them across machines.

### In a container, with no server to look after

The same relay, Caddy and onion service also come as one container image,
`ghcr.io/jakes1345/clairix-relay` (`container/`), for hosts that run
containers rather than lend a machine: bunny.net Magic Containers, step by
step in [container/README.md](container/README.md). A small supervisor
stands in for systemd, and because a container host's disk is not backed up,
it backs the relay up itself: sealed with a key only the operator holds, to
a Bunny Storage zone, every 10 minutes and on every stop. On an empty volume
it restores the newest backup, keys, accounts and waiting mail included,
before anything answers. A lease in the same zone keeps a second copy from
running. CI (`relay-container.yml`) tests a restore after a lost volume,
under gVisor too, as Magic Containers runs it.

### Inviting people

Only the relay's owner needs the URL and the enrollment secret. Everyone else
joins by invite (or, on a relay whose operator opened registration, with the
URL alone; see below): in Clairix, COMMS → INVITE makes a one-time invite. Someone
standing next to you scans its QR code from COMMS setup (SCAN INVITE); someone
far away gets a link, `https://clairix.app/i#…`. On a phone with Clairix it
opens Clairix straight away: the site names the app and its signing key, so
Android hands the link to Clairix alone (a verified App Link). Elsewhere it
opens a page on clairix.app with the download and an OPEN IN CLAIRIX button.
Links made before Beta 8 open this relay's own page at `/i`, which still works.
Before registering, the app shows the relay the invite names and whether it is
on the signed relay list; for one that is not, it says what that relay's
operator would see and asks the owner to confirm they know who runs it.
The invite registers exactly one phone,
expires after seven days, and never reveals the enrollment secret. The new
phone starts with the inviter as a contact, since the invite carries the
inviter's keys, and sends them a first message. Not a verified one: the link
passed through other hands, so the two compare safety numbers (or scan each
other's code) to verify. The part after `#` never reaches any server; the page
reads it in the browser. Each identity can make 20 invites a day.

### Open registration (off unless you turn it on)

By default a relay gives new numbers only by invite or with the enrollment
secret, so someone who knows nobody on it cannot get in, however much they
need to. A relay run for people with nobody to invite them (a support
service's, say) can let phones register with neither:

```toml
# wrangler.toml
[vars]
OPEN_REGISTRATION = "on"
OPEN_REGISTRATION_PER_DAY = "50"   # new accounts a UTC day, for the whole relay; 1 to 100000, default 50
```

On your own server the same two lines go in `/etc/clairix-relay/relay.env`
(`OPEN_REGISTRATION=on`, `OPEN_REGISTRATION_PER_DAY=50`), and in the container
they are environment variables. In Clairix the person picks the relay, or
enters its address, and leaves the enrollment secret empty. Every
registration attempt still meets the per-address limit (ten a minute,
counted in memory and never written down), and the only record that a
registration came in this way is the day's count: nothing says which
accounts did, nor from where. Past the day's number a phone without an invite or the secret is told
to try again the next day; invites and the secret keep working. One address
can use up a day's open registrations, so choose a number you would accept
going to one person. While the coin is on, `COIN_INITIAL_BALANCE` is given
to open registrations too. The project's own relay keeps open registration off.

### Abuse reports

Report, on a contact's page or on a message request in Clairix, sends
`POST /v1/report`: the number reported, the relay it is on when that is not
this one, the SHA-256 of the newest envelope it sent (when the phone has
one) and a reason, one of `harassment`, `threats`, `spam`, `impersonation`
or `other`. The request spends a report token and names nobody, so the relay
keeps who was reported and why, never who reported it, and never anything
that was said, which it cannot read. The same envelope reported twice counts
once. Reports are kept to the day, for 90 days at most, at most 500 about one
number and 50,000 in all, and the answer to a report is the same whether the
number exists or was reported before.

With `ADMIN_SECRET` set, the operator reads them:

```sh
ADMIN_SECRET=… node scripts/operator.mjs reports https://relay.example.org
ADMIN_SECRET=… node scripts/operator.mjs close https://relay.example.org "482 913 605"
# or by hand:
curl -H "X-Admin-Secret: $ADMIN_SECRET" https://relay.example.org/v1/admin/reports
curl -X DELETE -H "X-Admin-Secret: $ADMIN_SECRET" -d '{"number":"482913605"}' https://relay.example.org/v1/admin/reports
```

`reports`, or the first `curl`, lists every reported number with how many
reports there are, their reasons, the envelope hashes and the first and last
day; `close`, or the second, closes the reports about one number (add
`"relay"`, or the script's last argument, for a number on another relay) once
it has been dealt with. A report is
someone's word: the relay never sees the messages, and an envelope's hash only
lets a report be matched with a message the person who reported it chooses to
show you. A number on another relay is that relay's operator's to act on; this
relay's operator can pass the report on, or stop taking that relay's envelopes
with `FEDERATION`. A few attempts a minute per address are allowed, so the
secret cannot be guessed; without `ADMIN_SECRET` the endpoint is off. What to
do with a report, start to finish, is [docs/ABUSE.md](../docs/ABUSE.md).

### Suspending a number

A number that abuses the relay can be suspended, and the suspension lifted
again. Suspended, the number cannot sign in: it collects nothing, renews
nothing and is issued no tokens, and every signed request from its phone is
answered 403 with a sentence saying it is suspended and where to appeal
(`OPERATOR_CONTACT`, when set). Its live connections are closed and its push
endpoint is deleted. To everyone else it reads as a number that does not
exist: envelopes to it are answered exactly as a send to a number nobody holds
is, and dropped; lookups find nothing; a payment is "Payment refused". So
nobody learns from the relay that the number was suspended, or that it exists.
What was queued for it stays, and reaches the phone if the suspension is
lifted before it expires after 30 days. The relay keeps that the number is
suspended and the day it began: nothing about why.

Two limits. Sending needs no sign-in, only a token, and the relay cannot tell
whose tokens are whose (that is what blind tokens are for), so a suspended
phone can still spend the tokens it already holds, a hundred or so at most,
over the four weeks they last. And a suspension stops a number, not a person:
someone suspended can come back with a new number if someone on the relay
invites them, or while registration is open.

Suspending takes two things together: a request signed by an **operator
identity**, an account on this relay whose key is in `OPERATOR_KEYS`, and
`X-Admin-Secret`. A leaked secret alone, or a stolen operator key alone, does
nothing. To set it up once:

```sh
ENROLL_SECRET=… node scripts/operator.mjs register https://relay.example.org
```

It makes an Ed25519 key on your computer (in
`~/.clairix-operator/<relay host>.json`, readable by you alone, or wherever
`OPERATOR_IDENTITY` says), registers it on the relay as an unlisted account
that sends and receives nothing, and prints the key. Add the key to the
relay's settings (comma-separated, for several operators), and set where
people appeal:

```toml
# wrangler.toml
[vars]
OPERATOR_KEYS = "<the key register printed>"
OPERATOR_CONTACT = "abuse@relay.example.org"
```

On your own server the same go in `/etc/clairix-relay/relay.env`, and in the
container they are environment variables. Then:

```sh
ADMIN_SECRET=… node scripts/operator.mjs suspend   https://relay.example.org "482 913 605"
ADMIN_SECRET=… node scripts/operator.mjs unsuspend https://relay.example.org "482 913 605"
```

The script signs `POST /v1/admin/suspend` or `POST /v1/admin/unsuspend` with
`{"number": "…"}` as the app signs a request, with the secret alongside. An
operator identity cannot suspend itself. Keep the identity file as you keep
the secret: with both, anyone can suspend anyone on the relay. Deleting an
account is "Deleting an account on request" below; when the project's own
relay suspends and when it deletes is set out in docs/ABUSE.md.

### Deleting an account on request

People normally delete their own account from the app (Wipe Clairix, or
Delete identity and number), which signs `DELETE /v1/me`. Someone who has lost
the phone, or whose app will not open, can ask by email instead
(clairix.app/delete-account promises an answer within 30 days). The relay holds
nothing that could confirm who is asking, so a request that names a Clairix
number on your relay is acted on as it stands. To do it:

1. Set the admin secret once, if it is not set yet (16 characters at least; it
   also guards coin minting, the abuse-report listing and, with
   `OPERATOR_KEYS`, suspending a number):

   ```sh
   npx wrangler secret put ADMIN_SECRET
   ```

2. Run the script from this folder with the relay's address and the number
   from the email. The secret comes from the environment, never the command
   line:

   ```sh
   ADMIN_SECRET=… node scripts/delete-account.mjs https://relay.example "482 913 605"
   ```

   It prints `Deleted …` and exits 0 when the account is gone, or says there is
   no such account (exit 3), which is also the answer when the owner's own wipe
   got there first.
3. Reply to the email that the account is deleted.

The script calls `DELETE /v1/admin/accounts/<number>` with `X-Admin-Secret`.
It does what the owner's own deletion does: the keys the account claimed are
released and its mailbox (queued envelopes, push endpoint, pass key, coin
balance and history) is wiped. Messages already delivered stay on the phones
that received them. The route needs no signed request (you hold no key of the
account's), is off while `ADMIN_SECRET` is unset, and takes a few tries a minute
from one address, so the secret cannot be guessed by brute force; the script
waits when it is told to.

### Calls (optional TURN)

Calls are WebRTC audio between the two phones, keyed by DTLS fingerprints that
travel inside the encrypted envelopes, so the relay never sees or handles the
media keys. Without TURN, calls use STUN only and connect when both phones can
reach each other directly (most home and mobile networks, not all). For calls
across strict NATs, create a TURN key in the Cloudflare dashboard under
Realtime → TURN and give it to the Worker:

```sh
npx wrangler secret put TURN_KEY_ID
npx wrangler secret put TURN_KEY_API_TOKEN
```

The Worker then mints TURN credentials for every `GET /v1/turn` request: they
are valid for two hours, go to that caller alone and are never cached or
shared. A TURN server forwards encrypted packets it cannot decrypt.

### ClairixCoin (off unless you turn it on)

Every relay is its own small currency community. Each Clairix number holds a
balance of ClairixCoin (`AC`) in its mailbox, and a phone pays another by a
signed request that names the payee's number and the fingerprint of the
payee's identity key (the key the payer's phone pinned when it added the
contact, as a lookup of an unlisted number asks); the relay moves the coins
between the two mailboxes. A number that does not exist and a fingerprint
that does not match get the same "Payment refused" before any coin moves, so
paying tells nobody whether a number is real, and coins never go to whoever
was given a number after the person the payer knew. The recipient hears
about the payment, with whatever note the payer wrote, inside an end-to-end
encrypted envelope, and its phone checks it against its own record by id and
amount.

Each mailbox keeps a record of its own transfers: the id, the amount, the
hour and whether it came in or went out, the 100 most recent and none older
than 30 days. It names nobody: not the other party, not a note, and for a
mint not who minted it (the record says "Minted by the relay"). A ledger
written by an earlier version, which named both parties and the moment, is
rewritten the first time the mailbox's coin is touched, or by the mailbox's
daily cleanup, whichever comes first.

The coin is on unless the operator switches it off:

```toml
# wrangler.toml
[vars]
COIN = "off"                    # optional: no balances shown, no payments, no starting balance, no minting
COIN_INITIAL_BALANCE = "100"    # optional: coins every account registered while the coin is on starts with
```

```sh
npx wrangler secret put ADMIN_SECRET   # 16+ characters; lets the operator mint (and delete accounts on request)
```

`GET /` says `"coin": true` while it is on, and the app shows the balance and
the Pay button only then (a relay from before this setting says nothing, and
offers the coin, as it always did). While it is off every coin route answers 404,
nobody can mint and new accounts start with nothing; balances and ledgers
already there are kept (the ledgers still forget each transfer after 30 days)
and come back if it is switched on again. Minting is
`POST /v1/admin/coin/mint` with `{"to": "<Clairix number>", "amount": 50}`,
signed like any request by a registered identity (the operator's own phone,
say) and carrying `X-Admin-Secret`; it needs the coin on and
`ADMIN_SECRET` set. `ADMIN_SECRET` guards the operator's other routes too (the
abuse reports, for one), so while the coin is on, setting it for them makes
minting possible as well. A single transfer moves at most 1,000,000 AC.

The project's own relay is configured on the live Worker, not in this
repository: whether the coin is on there, the starting balance and whether
anyone can mint are its maintainer's settings.

### Traffic (navigation)

The relay also carries crowd-sourced traffic for Clairix navigation, in a
`Traffic` Durable Object per map region (`traffic:<region>`, e.g.
`traffic:north-america-us-ohio`). Roads are identified by directed OSM way,
`"<wayId>:<dir>"`.

Nothing about traffic is signed. Every request spends a single-use **traffic
token** (a new report, a **report token**) that the phone fetched earlier over
its signed channel, exactly as sends spend delivery tokens: the relay keeps
only token hashes, in shards that never learn the account, so a traffic
request carries no identity and a request that names one is refused. An
account may draw 400 traffic tokens and 30 report tokens an hour, which is
the only per-phone bound on traffic. Probes, reports and votes carry a
**trip pseudonym**, 16 random bytes the phone makes up when a drive begins and
replaces every ten minutes; it lets the region's object count distinct
vehicles and take one vote per vehicle, and reaches back to no phone.

- **Probes** (`POST /v1/traffic/probe`, up to 60 samples, each at most 15
  minutes old) give a segment's speed under the trip pseudonym. Raw probes
  are deleted after two hours, so the most a region ever holds is two hours
  of segment speeds from pseudonymous vehicles. The phone reports nothing for
  roughly the first kilometre of a drive (a distance that varies per drive) or
  the last stretch before its destination.
- **Live** speed is the median over the last ten minutes, one vote per vehicle
  per segment (its latest sample). **Typical** speed is a 168-slot week
  (hour of week, UTC, Monday first) updated with an EWMA (α = 0.2) of each
  segment's 15-minute medians.
- **Reports** (police, crash, hazard, closure, jam) of the same type within
  150 m and 30 minutes merge into one incident with more confirmations, one
  per trip pseudonym. Incidents last 45 / 90 / 60 / 240 / 30 minutes
  respectively, renewed by each new confirmation; two net "gone" votes remove
  one.
- **Automatic detection**: two or more phones under 8 km/h on a normally fast
  segment (typical 40 km/h or more) within five minutes make a "jam"; if they
  were doing over 60 km/h in the two minutes before, a "crash". The relay has
  no road graph, so such incidents are placed only on segments whose position
  it has learned (from reports that name the segment, or probes that carry an
  optional `lat`/`lon`).
- **Work zones**: set `WZDX_FEEDS` to a comma-separated list of WZDx v4 GeoJSON
  feed URLs (published by most US state DOTs). A cron every ten minutes
  fetches them and adds each active work zone (a closure when all lanes are
  closed, otherwise a hazard) to every region whose area, learned from
  positioned reports and probes, contains it. Work zones leave when the feed
  drops them or their end date passes. The phone also downloads every feed in
  the USDOT WZDx registry directly (the `workzones` release built by
  `tools/workzones/aggregate.py`), so `WZDX_FEEDS` is only needed for a feed
  the registry does not list; when both carry a zone, the phone shows it once,
  as the registry's work zone, and drops the relay's copy lying on its stretch.

```toml
# wrangler.toml
[vars]
WZDX_FEEDS = "https://example-dot.gov/wzdx/v4/feed.geojson"
```

### Commercial incidents (HERE, TomTom)

`GET /v1/traffic/incidents?bbox=minLat,minLon,maxLat,maxLon` (at most 1° × 1°)
proxies the HERE Traffic v7 and TomTom Traffic v5 incident APIs, so the API
keys stay on the relay. Both are optional; a provider without a key is
reported as `"off"`:

```sh
npx wrangler secret put HERE_API_KEY     # HERE Traffic API v7
npx wrangler secret put TOMTOM_API_KEY   # TomTom Traffic API (incidentDetails v5)
```

The requested box is snapped outward to 0.25° tiles. Each provider's tile is
fetched once and cached for five minutes in a single `IncidentCache` Durable
Object, so every phone in that area shares one upstream call; concurrent
requests for a tile that is being fetched wait for that same call. One request
starts at most 3 upstream calls per provider (tiles with nothing cached first,
then the oldest); the rest of a large box keeps its last result and is filled
in by the phone's next polls, which keeps a request within the Workers Free
subrequest and connection limits and a phone within the daily caps. An
upstream failure is not retried for a minute, and meanwhile a tile's last good
result (up to 30 minutes old) is still served. Daily caps protect the free
tiers: `HERE_DAILY_CAP` (default 1000) and `TOMTOM_DAILY_CAP` (default 2300)
upstream calls per UTC day; at the cap the provider reports `"quota"` until
UTC midnight. Once a provider has used 80% of its cap only tiles with nothing
cached are fetched; a tile held back keeps serving its last result, and the
provider then reports `"ok"` if anything was served, else `"quota"`. The
requests arrive anonymously, each having spent a traffic token, so the relay
cannot count per phone; what bounds one phone's share of a cap is the 400
traffic tokens an hour its account may draw. A failing provider reports
`"error"`; the request itself still succeeds with whatever the other provider
returned.

```json
{"now": 1760500000,
 "incidents": [{"id": "tomtom:abc", "type": "jam", "lat": 39.96, "lon": -83.0,
   "geom": [[39.96, -83.0], [39.97, -83.0]], "road": "I-71", "description": "Stationary traffic",
   "start": 1760499000, "end": null, "delay_s": 240, "source": "tomtom"}],
 "providers": {"here": "ok", "tomtom": "ok"}}
```

`type` is one of `crash`, `closure`, `lane_closure`, `work_zone`, `jam`,
`hazard`, `police`, `other`; `geom` is `[lat, lon]` pairs (null for a point);
times are epoch seconds.

## API

Signed requests carry `X-Clairix-Number`, `X-Clairix-Ts` (epoch millis, ±5 min),
`X-Clairix-Nonce` (single use) and `X-Clairix-Sig`, an Ed25519 signature over
`number\nts\nnonce\nMETHOD\npath?query\nsha256hex(body)`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/register` | Body signed by the new identity's key, dated (`ts`, ±5 min), with `secret` or a one-time `invite` (or neither, while `OPEN_REGISTRATION` is on and the day's number lasts); allocates a Clairix number. 409 when a key is already another account's |
| POST | `/v1/invites` | Makes a one-time invite: `{code, expiresAt}` (seven days) |
| GET | `/` | `{service, version, alternates, onion, relayKey, federation, tokens, coin}`: the relay's other addresses, its onion address, the public key it signs forwarded requests with, which relays it accepts them from, its blind-token keys for this epoch and the next, and whether ClairixCoin is on |
| GET | `/i` | Invite landing page (unsigned); the invite is in the URL fragment |
| GET | `/v1/me` | Own profile, one-time-key count, and whether a pass key is set (`pass`) |
| PUT | `/v1/pass` | `{key}` (32 bytes, base64; or `null`): the key contacts' passes are made with. 20 an hour |
| DELETE | `/v1/me` | Wipe the mailbox |
| PUT | `/v1/transport` | `{key}` (or `null` to remove): the phone's transport key, a second Ed25519 key. Requests signed with it carry `X-Clairix-Key: transport` and may only `GET /v1/inbox`, `POST /v1/ack` and `GET /v1/ws`, so a locked phone collects envelopes it cannot yet open; the key reads and sends nothing |
| PUT | `/v1/keys` | Replenish one-time keys; rotate the fallback key; publish the next sealing prekey (`sealingPrekey`: `{key, created, signature}`, taken only when newer than the one held, and refused when over two weeks old) |
| PUT | `/v1/listed` | Whether the number can be looked up |
| PUT | `/v1/push` | UnifiedPush endpoint to wake this device, or null |
| POST | `/v1/tokens` | `{kind?, blinded: [up to 100 base64url elements]}` → `{kind, epoch, key, evaluated, proof, expiresAt}`: blind tokens (see above), `delivery` (default), `traffic` or `report`. `{count, kind?}` → `{tokens, expiresAt, kind}` is the old kind, for apps before Beta 6, refused with 426 from `LEGACY_CUTOFF` (see "Old versions") |
| POST | `/v1/lookup` | `{number, pin?, claim, relay?}`: a contact's keys, with one session key when `claim` is true (unlisted numbers need `pin`, the key's fingerprint). The number is in the body, never in a URL. With `relay`, another relay's address, the lookup is forwarded there |
| POST | `/v1/send` | `{to, envelope, claim?, mix?, relay?, alert?, pass?}` with a delivery token: queue a sealed envelope for a contact. A number that does not exist is answered exactly like one that does. `pass` is base64 HMAC-SHA256 under the recipient's pass key over `clairix-pass-v1|to|envelope`; without a good one, a mailbox with a pass key takes the envelope only within the strangers' share (see above). With `mix`, the envelope is held for mixing. With `relay`, another relay's address, it is forwarded there. With `alert` (`call` or `sos`), the envelope is delivered with it, so a locked phone can ring or sound an SOS before it can open the envelope. An envelope in format 2 is refused with 426 from `LEGACY_CUTOFF`, whatever the number |
| POST | `/v1/report` | `{number, relay?, envelope?, reason}` with a report token → 202: an abuse report kept for the operator (see "Abuse reports"); the same answer whatever the number |
| GET, DELETE | `/v1/admin/reports` | With `X-Admin-Secret` and no identity: every reported number, or (`DELETE {number, relay?}`) close the reports about one; off unless `ADMIN_SECRET` is set |
| POST | `/v1/federate/send`, `/v1/federate/lookup` | The same, forwarded by another relay and signed with its key (`X-Clairix-Relay`, `-Ts`, `-Nonce`, `-Sig`) |
| GET | `/v1/inbox` | Waiting envelopes |
| POST | `/v1/ack` | Delete envelopes the app has stored |
| GET | `/v1/ws` | Live delivery over a hibernatable WebSocket: `envelope` frames (acked with `{"type":"ack","ids":[…]}`), a `ready` frame after the backlog, a `hb` heartbeat with `next` (the most milliseconds until the following one: two minutes, or with `X-Clairix-Heartbeat: <ms>` on the upgrade, two minutes for ten minutes after an envelope or an ack and that pace otherwise; see "The live connection"), and `pong` for a client's `{"type":"ping"}` |
| GET | `/v1/turn` | ICE servers for a call: STUN, plus short-lived TURN credentials when a TURN key is configured |
| GET | `/v1/coin/balance` | `{balance, symbol: "AC"}`: this identity's ClairixCoin. Every coin route answers 404 while `COIN` is off |
| POST | `/v1/coin/pay` | `{to, pin, amount, txid?}` → `{txid, balance, ok}`: pays a contact; `pin` is the payee's key fingerprint, and a number that does not exist or a pin that does not match is "Payment refused" before any coin moves; 30 a minute |
| GET | `/v1/coin/history` | `{txs: [{id, amount, ts, direction, note?}]}`: the last transfers, newest first (`?limit=`, up to 100), each dated to the hour; `note` only on a mint |
| POST | `/v1/admin/coin/mint` | `{to, amount}` with `X-Admin-Secret` → `{ok, balance}`; off unless `COIN` is on and `ADMIN_SECRET` is set |
| DELETE | `/v1/admin/accounts/:number` | With `X-Admin-Secret` and no signature → `{ok, number}`: deletes the account as `DELETE /v1/me` does, for a deletion requested by email (`scripts/delete-account.mjs`, see above); 404 for no such account; off unless `ADMIN_SECRET` is set; a few tries a minute per address |
| POST | `/v1/admin/suspend`, `/v1/admin/unsuspend` | `{number}`, signed by an operator identity (its key in `OPERATOR_KEYS`) and with `X-Admin-Secret` → `{ok, number, suspended, since, already}` or `{ok, number, suspended: false, wasSuspended}` (see "Suspending a number"; `scripts/operator.mjs`); 404 for no such account; off unless both settings are set; a few tries a minute per address |
| POST | `/v1/traffic/probe` | `{region, trip, samples: [{seg, kph, t, lat?, lon?}]}` → `{ok, accepted}`; a traffic token, no identity |
| GET | `/v1/traffic/live?region=` | `{now, segments: {seg: {kph, n, t}}, incidents}`; a traffic token |
| GET | `/v1/traffic/typical?region=&segs=a,b` | `{segments: {seg: {h: [168 × km/h or null]}}}`, up to 500 segments; a traffic token |
| POST | `/v1/traffic/report` | `{region, trip, type, lat, lon, seg?}` → `{incident}`; merges with a nearby report of the same type; a report token |
| POST | `/v1/traffic/report/vote` | `{region, trip, id, still}` → `{incident}`, null once voted away; a traffic token |
| GET | `/v1/traffic/incidents?bbox=minLat,minLon,maxLat,maxLon` | `{now, incidents, providers: {here, tomtom}}`: HERE / TomTom incidents in a box of at most 1° × 1°; a traffic token |

Each Clairix number is a Durable Object holding the keys, the queue (up to 2000
envelopes of 64 KiB, kept 30 days) and the live sockets. Request bodies are
read up to 256 KiB and refused beyond it before anything else happens. A
traffic region must be one the graph builder publishes a map for
(`src/regions.ts`); nothing else makes the relay create an object. Incident times
(`createdAt`, `expiresAt`) and probe times are epoch seconds.

## Development

`npm run typecheck` runs the TypeScript compiler; `npm test` runs the relay,
traffic and incident tests end to end over in-memory storage and Node's own
SQLite (Node 22.18 or later, which runs TypeScript directly); `npx wrangler
deploy --dry-run` bundles the Worker. `npm run dev` runs it locally with a
local Durable Object.

Two relays can run side by side to try relays reaching each other, each with
storage of its own:

```sh
npx wrangler dev --ip 127.0.0.1 --port 8787 --persist-to .wrangler/relay-a
npx wrangler dev --ip 127.0.0.1 --port 8788 --inspector-port 9230 --persist-to .wrangler/relay-b
```

A relay reached over plain http on 127.0.0.1 or localhost accepts
`http://127.0.0.1:<port>` and `http://localhost:<port>` as relay addresses
(`isLoopbackDev` in `src/federation.ts`). A deployed relay is always reached
over https, so this never applies to one. Debug builds of the app accept such
an address too, and the emulator job in CI runs the app's end-to-end tests
against two relays this way, through `adb reverse`.
