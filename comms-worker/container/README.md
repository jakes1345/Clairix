# The relay as a container (bunny.net Magic Containers)

One image holds the whole relay: the Worker under workerd with its Durable
Objects on disk (the same build `selfhost/install.sh` puts on a server),
Caddy for HTTPS, Tor for the onion service, and `supervisor.mjs` in place of
systemd. It is built and tested by `.github/workflows/relay-container.yml`
and published as:

```
ghcr.io/jakes1345/clairix-relay:<date>-<commit>     (and :main, the newest)
```

This page sets it up on [bunny.net](https://bunny.net) Magic Containers:
bunny.net is a Slovenian company, so the relay runs under EU law, apart from
the Cloudflare one. There is no server to log in to or update.

## What it costs

The whole container uses about 170 MB of memory at rest (workerd 60 MB, the
supervisor 55, Caddy 38, Tor 13), so give it **half a CPU and 512 MB**. At
bunny.net's prices (October 2026), run all month: at most about **$9.30** for
the container (CPU is billed only as it is used, so less when quiet), **$2**
for its own IP address (Anycast IPv4), **$1** for a 10 GB volume (not yet
billed while volumes are in preview), and cents for the backups in Bunny
Storage ($1 a month minimum). Roughly **$10–13 a month**.

## What runs where

- **HTTPS is ended inside the container**, by Caddy, with a Let's Encrypt
  certificate it gets and renews itself. The relay uses an *Anycast IP*
  endpoint, which passes connections through untouched, not a CDN endpoint,
  which would open them on bunny.net's servers. So bunny.net carries only
  encrypted connections, as any network does.
- **Everything kept** is on a volume mounted at `/data`: the relay's
  databases (its keys, accounts and mailboxes), the onion service's keys and
  Caddy's certificates. Message contents are end-to-end encrypted on the
  phones before they arrive, so the volume never holds a readable message.
- **bunny.net's volumes are not backed up or copied** (their documentation
  says so): a failed disk comes back empty. So the supervisor backs the relay
  up itself, every 10 minutes and whenever the container stops, to a Bunny
  Storage zone. Each backup is sealed with AES-256-GCM under `BACKUP_KEY`
  before it leaves the container; Bunny Storage holds only sealed bytes. When
  the container starts on an empty volume it restores the newest backup
  before anything answers, so the relay keeps its keys, its onion address,
  every account and the mail still waiting. Mail that arrived after the last
  backup (at most 10 minutes' worth) is lost with the disk.
- **One copy only.** Two copies would be two relays with the same address and
  different mailboxes. The supervisor holds a lease in the storage zone and
  renews it every minute; a second copy waits for it and, if it never lapses,
  stops with a message saying so.

## Setting it up

You need a bunny.net account, a domain whose DNS you can edit (for example
`relay.clairix.app`), and about half an hour.

### 1. Make the image public (once)

The first time the workflow publishes the image, GitHub makes it private.
On GitHub: your profile → **Packages** → `clairix-relay` → **Package
settings** → **Change visibility** → **Public**. (Or keep it private and add
GitHub as a private registry in Magic Containers with a read-only token.)

### 2. Make two secrets

Run these on your own computer and keep the results in a password manager.

macOS or Linux:

```bash
openssl rand -hex 24      # ENROLL_SECRET: the code people type into Clairix to register
openssl rand -base64 32   # BACKUP_KEY: seals the backups
```

Windows (PowerShell):

```powershell
$b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | % { $_.ToString('x2') })
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

**Without `BACKUP_KEY` the backups cannot be opened.** Lose it and a lost
disk means a new relay with new keys; every phone would have to register
again.

### 3. A storage zone for the backups

bunny.net dashboard → **Storage** → **Add Storage Zone**. Name it (say
`clairix-relay-backups`), pick the **Standard** tier and a main region (the
one nearest the region you will run the relay in), and no replication.
Open the zone → **Access** and note:

- the zone name (`BACKUP_ZONE`),
- the **Password** (`BACKUP_ACCESS_KEY`), not the read-only one,
- the **Hostname**, for `BACKUP_ENDPOINT`: `https://` and the hostname, e.g.
  `https://se.storage.bunnycdn.com` for Stockholm. Leave it out for
  Frankfurt (`storage.bunnycdn.com`).

### 4. The app

**Magic Containers** → **+ Add App**:

- **Image**: `ghcr.io/jakes1345/clairix-relay`, the newest dated tag
  (`20261028-abc1234` style; prefer it to `main`, so you choose when to update).
- **Endpoints**: two **Anycast IP** endpoints, container port **443** exposed
  on **443**, and container port **80** on **80** (Let's Encrypt checks the
  domain over port 80). No CDN endpoint.
- **Persistent volume**: mount path **`/data`**, **10 GB**.
- **Environment variables** (raw editor):

  ```
  RELAY_DOMAIN=relay.clairix.app
  ENROLL_SECRET=<from step 2>
  BACKUP_ZONE=<from step 3>
  BACKUP_ACCESS_KEY=<from step 3>
  BACKUP_KEY=<from step 2>
  BACKUP_ENDPOINT=https://se.storage.bunnycdn.com
  ACME_EMAIL=you@example.org
  ```

- **Resources** (if offered): half a CPU, 512 MB.
- **Region**: one region (e.g. Stockholm).

Deploy. Then, in the app:

- **Regions and Scaling**: minimum **1**, maximum **1** instance, and turn
  **autoprovisioning** off if it is on. More than one copy is refused by the
  lease, but set it here so bunny.net never tries.
- **Container Settings → Edit → Monitoring**: a **Startup** and a
  **Liveness** check, both **HTTP GET** on path `/healthz`, port **8081**.
  (No endpoint for 8081: the check reaches the container directly.)

### 5. DNS

Point the domain at the Anycast IP the app shows: an `A` record,
`relay.clairix.app → <the IP>`. Within a few minutes Caddy has its
certificate, and `https://relay.clairix.app/` answers with the relay's
status: `"service":"clairix-comms"`, its key, and its `onion` address.

If no certificate comes (the log shows `challenge failed` or `connection`
errors from `acme_client`, or only the backups and Tor, and the page still
gives an SSL error), the certificate authority's checks are not reaching the
container through the Anycast IP. Prove the domain over DNS instead, so the
checks never need to reach it:

1. In Cloudflare: **My Profile → API Tokens → Create Token → Edit zone DNS**
   template. Under **Zone Resources** choose *Include → Specific zone →*
   your domain (`clairix.app`). Create it and copy the token.
2. In the app: add `CLOUDFLARE_API_TOKEN=<the token>` to the environment
   variables, **Update Container**, **Save Changes**.

Caddy then puts a `_acme-challenge` TXT record in the Cloudflare zone, the
certificate authority reads it, and Caddy removes it again; renewals work the
same way. The token can edit that one zone's DNS and nothing else, and only
Caddy is given it.

### 6. Check the logs

**Logs** in the app should show, within ten minutes:

```
[supervisor] the relay is up at https://relay.clairix.app and http://<56 letters>.onion
[supervisor] backup (scheduled): ... to backup-a in ... ms
```

## Settings

Required:

| Setting | |
|---|---|
| `RELAY_DOMAIN` | The name the relay answers at. Its `A` record points at the app's Anycast IP. |
| `ENROLL_SECRET` | At least 16 characters: the code people type into Clairix to register here. |
| `BACKUP_ZONE` | The Bunny Storage zone's name. |
| `BACKUP_ACCESS_KEY` | That zone's password. |
| `BACKUP_KEY` | 32 random bytes in base64 (step 2). |

Optional:

| Setting | Default | |
|---|---|---|
| `BACKUP_ENDPOINT` | `https://storage.bunnycdn.com` | The zone's region (step 3). Must be `https://`. |
| `BACKUP_PATH` | `clairix-relay/<domain>` | The folder in the zone. |
| `BACKUP_EVERY_MINUTES` | `10` | 5 to 1440. |
| `ACME_EMAIL` | none | Let's Encrypt writes here before a certificate would expire unrenewed. |
| `ONION` | `on` | `off` leaves the onion service out. |
| `CLOUDFLARE_API_TOKEN` | none | Prove certificates over DNS in the domain's Cloudflare zone (step 5), not by connecting to the container. |
| `CADDY_DEBUG` | `off` | `on`: Caddy's debug log and an access log, to find out why a certificate does not come. The access log writes down addresses: turn it off again. |
| `BACKUP` | on | `off` runs with no backups and no lease: a lost disk is a new relay. |

Every relay setting in the main README (`FEDERATION`, `MIX_MAX_MS`,
`DELIVERY_TOKENS_PER_HOUR`, `COIN_INITIAL_BALANCE`, `ADMIN_SECRET`, ...)
works here too, as an environment variable. `RELAY_ORIGIN` defaults to
`https://<RELAY_DOMAIN>` and `RELAY_ONION` is set from Tor. The relay itself
is given its own settings only, never the backup keys or the Cloudflare token.

## Updating

Each push to main, and each Monday (for the base image's security updates),
publishes a new dated tag. To move to it: **Container Settings → Edit**,
pick the tag, **Update Container**, **Save Changes**. bunny.net stops the old
container before starting the new one (a volume can be attached to one at a
time), so the relay is away for under a minute; the old one takes a last
backup and gives up its lease on the way out.

## When something goes wrong

- **The container keeps restarting and the log lists settings**: fix those
  settings; nothing starts until they are right.
- **"another copy of this relay holds the lease"**: a second instance or
  region is running. Set scaling to 1/1, one region, autoprovisioning off.
  A copy that died without giving the lease up releases it after 3 minutes.
- **"no backup could be restored"**: the volume was empty and backups exist,
  but none opened. Usually a changed `BACKUP_KEY` or storage password: put
  the right ones back. The relay will not start empty while its real keys
  sit in storage.
- **Backups failing** (`backup (scheduled) failed` in the log): the relay
  keeps running; check the zone's password and hostname.

## Limits worth knowing

- bunny.net runs the container in gVisor, a sandbox; CI runs the same image
  under gVisor too, through a full restore.
- The registration limit counts attempts per client address. If bunny.net's
  Anycast IP hands connections over from its own addresses rather than the
  phones', every phone shares one allowance: a few registrations a minute in
  all. Phones that connect through Tor already share one.
- One copy, one region: if the machine under it fails, the relay is away
  until bunny.net brings the container back (on the same volume) or, if the
  disk is lost, until it restores from the backup on a new one.
