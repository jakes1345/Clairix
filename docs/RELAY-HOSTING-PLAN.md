# Relay hosting plan (for when Clairix grows)

Researched October 2026. Prices and laws change: check every figure and
provider claim again before acting on it. Rows marked *unverified* come only
from a provider's own marketing or from affiliate reviews.

## Why more than Cloudflare

The relay runs on Cloudflare Workers today. Cloudflare is a US company,
subject to US legal process, and one host is one order. Relays already reach
each other (federation), so every extra relay we run is useful at once:
people on any relay can message people on any other, and an order against one
does not cut off the rest.

No jurisdiction protects against a government determined to reach one
person. What protects people is what the relay holds (it cannot read
messages, does not know senders, and from Beta 6 cannot link tokens to
accounts), relays spread across legal systems that do not readily cooperate,
and an onion address so neither the relay nor its host learns users' IP
addresses.

**Who owns the host matters as much as where the server is.** A company
registered in the US with a server in Tokyo still answers to US courts. Both
the company's country and the data centre's must be acceptable.

## Where

| Region | Country | Why | Host lead | Monthly |
|---|---|---|---|---|
| Europe | Iceland | Strong privacy law, outside the 14 Eyes | 1984 Hosting VPS #2 (1 CPU, 2 GB, 50 GB, 2 TB, DDoS protection), verified €17.44 | ~$19 |
| Europe (alternative) | Romania | EU, 6 TB traffic | FlokiNET VPS II (2 CPU, 2 GB, 50 GB, 6 TB, DDoS protection), verified €14.99 | ~$16 |
| Americas | Panama | No logging obligation for hosts, outside every Eyes alliance | PrivateHostLab (Panama operator, email only, Monero), *unverified* | ~$10–20 |
| Asia | Japan or Taiwan | Near Asian users; neither in the Eyes alliances (Japan cooperates closely with the US) | none verified yet; needs a host not registered in the US | ~$15–20 |
| Global | Cloudflare (existing) | Fast everywhere, absorbs floods | Workers Paid | $5 |

Avoid as homes for a relay:

- US, UK, Canada, Australia, New Zealand (Five Eyes; the UK's Investigatory
  Powers Act lets it secretly order a provider to break its encryption, as it
  did to Apple in 2025).
- India (VPN and cloud providers must keep user logs for five years).
- Brazil (data retention for messaging services).
- Hong Kong, China, Russia.
- Singapore: fine for latency, weak for privacy (a national retention
  framework and a strict state).
- Switzerland: unsettled while its surveillance ordinance revision (VÜPF) is
  contested; Proton has started moving infrastructure out.

## Cost

| Setup | Monthly |
|---|---|
| Cloudflare free (testers) | $0, about 300 active users a day before requests fail |
| Cloudflare Workers Paid | $5, about 1,000 active users included, then roughly $3–4 per extra 1,000 |
| Cloudflare + Iceland | ~$25 |
| + Panama | ~$40 |
| + Japan or Taiwan | ~$55–65 |
| ~10,000 users across all | ~$90–110 |

Sizing: a heavy user (texts and about 20 photos a day) moves roughly 150 MB a
month through a relay, so 2 TB of traffic covers about 10,000 of them. The
processor is not the limit: issuing 50 blind tokens takes about 14 ms.
Other costs: an onion address is free; a domain is about $15 a year;
snapshots are a few euros a month. The real cost is upkeep: updates, disk,
uptime, certificates and restarts, about an hour a month per server once
automated.

## Work to do first

1. **Self-hostable relay.** *Done:* `comms-worker/selfhost/install.sh` runs
   the same Worker under workerd with on-disk Durable Object storage, behind
   Caddy (TLS) and a Tor onion service, with restart on failure, an uptime
   check, the scheduled work, and automatic security updates. See
   "On your own server" in `comms-worker/README.md`. The emulator tests run
   one of their two relays this way. Multi-machine Durable Objects (Deno's
   celld, being merged into workerd after Cloudflare bought Deno) were at
   v0.1.0 in August 2026: too early.
2. **Stand up relays** in the countries above, after checking each host's
   current terms, whether it really needs no ID, where the company is
   registered, and real prices.
3. **Relay list in the app.** *Done:* `relays/relays.json`, signed with the
   asset key and published by the Relay list workflow; phones offer it when
   setting up, refuse an older list than they have, and check a listed
   relay's key before registering on it (`relays/README.md`). Still to do:
   moving a phone to another relay when its own is blocked (a number is per
   relay, so this needs contacts told of the new number).
4. **The app over Tor** (Arti), so the phone's address is hidden from every
   relay, ours or not. *Done:* "Connect through Tor" in Comms settings runs
   Arti in the app's native library behind a password-protected SOCKS5 proxy
   on 127.0.0.1, every connection on a circuit of its own; relay traffic and
   the relay list go through it and fail closed while it connects. Calls
   (UDP) and push wake-ups (the distributor's own connection) do not. Still
   to do: reaching a relay at its onion address when it has one.

## Sources

- [Cloudflare Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [Durable Objects SQLite storage billing](https://developers.cloudflare.com/changelog/2026-01-07-durable-objects-sqlite-storage-billing)
- [1984 Hosting price list](https://1984.hosting/product/pricelist/)
- [FlokiNET VPS](https://flokinet.is/vps)
- [PrivateHostLab FAQ](https://privatehostlab.com/faq/)
- [Best offshore VPS payable in crypto, 60-day test](https://privacytestlabs.com/best-offshore-vps-crypto.html)
- [Data retention obligations in the Americas](https://www.cullen-international.com/news/2025/09/Data-retention-obligations-in-the-Americas.html)
- [Data retention laws by country (2026)](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/data-retention-laws-by-country/)
- [Apple and the UK's encryption order](https://www.malwarebytes.com/?p=447093)
- [Proton moves infrastructure out of Switzerland](https://heise.de/-10538664)
- [celld: self-hosted Durable Objects](https://github.com/denoland/celld)
- [Deno is joining Cloudflare](https://blog.cloudflare.com/deno-joins-cloudflare/)
- [Arti 2.6.0](https://blog.torproject.org/arti_2_6_0_released/)
