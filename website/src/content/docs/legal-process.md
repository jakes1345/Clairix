## Who would receive a request {#operator}

Clairix has one maintainer, an individual, who writes the app and runs the project relay: the relay the in-app relay list calls "Clairix", run by "The Clairix project". That name is not a company, a foundation or any other legal entity. None exists yet, so this page names no jurisdiction of its own: a request about the project relay reaches the maintainer personally, under whatever law applies to them. When an entity exists, this page will name it and the jurisdiction whose legal process it answers, and the change will be dated here.

The project relay runs on Cloudflare Workers, and calls that go through a TURN server use Cloudflare's. Cloudflare, Inc. is a US company and can be served directly, without the maintainer being asked or told. What it holds for the relay is the same storage described below, and its network sees the IP address of every connection while it is open. The relay's request logs (Cloudflare's Workers logs) are switched off; what Cloudflare's network keeps about connections is under Cloudflare's own policies.

A relay someone else runs is theirs. Its operator, its host and their law decide what they hand over; nothing on this page binds them. Setup in the app says which relay an invite puts you on before you register.

Requests about the project relay go to **support@clairix.app**.

## What could be handed over {#producible}

Only what the relay stores. Per Clairix number, that is:

| Data | Kept for |
|---|---|
| The Clairix number, its public keys, the day it registered and whether it can be looked up | Until the account is deleted |
| Encrypted envelopes waiting for the phone, each with its recipient, size and arrival time, and whether it came with a contact's pass (never whose) | Until the phone collects them, or 30 days |
| The UnifiedPush endpoint, if one is set: an address at the push service the user chose | Until it is changed or removed, or the account is deleted |
| The key contacts' passes are checked with | Until the phone replaces it, or the account is deleted |
| Unused invite codes the number created | 7 days |
| That someone, never who, took one of the number's one-time keys to start a conversation | The exact time for up to 15 minutes, until a message follows; if none does, the day, for 30 days |
| A note that each of its keys belongs to the number | Until the account is deleted |
| ClairixCoin, in Clairix Full on a relay that offers it: the balance, and for each transfer its id, the amount, the hour and whether it came in or went out (never who paid whom) | The balance until the account is deleted; each transfer for 30 days at most, the 100 most recent |
| Rate-limit allowances, as a count and the hour | Until they refill |
| The nonce of each signed request | 5 minutes |
| Reports other users made about the number: the number, its relay, the reason chosen and, when there is one, a hash of an encrypted envelope, dated to the day, never who reported it | 90 days at most, or until the operator closes them |

Not tied to any number: hashes of spent single-use tokens, which cannot be matched to the account they were issued to; anonymous traffic samples (2 hours), learned road positions (30 days) and incident reports, which carry a per-drive pseudonym and no number; and, for each relay that forwards to this one, its public key, nonces and allowances. The full table, with what the relay can observe while a request is in flight, is on the [security page](/security#relay).

Queued envelopes would be handed over as they are stored: ciphertext that only the recipient's phone can open.

## What cannot be handed over {#not-held}

The relay does not hold these, so no order can obtain them from it:

- **Content.** Messages, photos, voice notes and call audio are end-to-end encrypted, and the keys exist only on the phones. Envelopes already collected are deleted.
- **The sender of anything.** A send names no account. It spends a blind token that the relay signed without seeing it (apps from before Beta 6 use an older kind of token, described on the [security page](/security#encryption)).
- **Contact lists and groups.** They live on the phones.
- **Who looked up whom.** Nothing stored names who made a lookup, and a lookup names its target in the signed body, never in a URL a log could keep.
- **When someone was last online, or from where.** No last-seen time is kept, and connection addresses are not written down.
- **A name, email address or phone number.** The relay never asks for one.
- **Anything on your phone.** Scan results, location history, the timeline and everything else listed under [what never leaves your phone](/security) stay there.

An order can demand things the relay does not do today: that it start recording network addresses from then on, refuse an account, or shut down. Such an order could not make it read messages already encrypted to keys it never holds. Use Tor in Comms settings, or a VPN, if your network address matters to you, and see [where the limits are](/security) for what any single relay can see.

## How a request is handled {#handling}

- A request must be in writing and come through legal process that binds the maintainer. Informal requests, from anyone, are declined.
- Only what the request lawfully requires is handed over, and only from the table above.
- **The holder of the number is told**, before anything is handed over where the law allows, unless a law or court order forbids it; if it does, they are told once it no longer does. The relay holds no way to reach anyone, so the notice is a reply to whoever has written to us about that number, and an entry in the transparency section below naming the number.
- Every request received goes into the transparency section, with what was handed over, unless the law forbids saying so.

If you delete your account, its data is gone from the relay as described on [the deletion page](/delete-account). What stays is what other people hold or made: their own copies of your messages on their phones, and any report about your number, which the relay keeps its 90 days.

## Transparency {#transparency}

**1 November 2026.** No request for data about any Clairix user has been received: no subpoena, court order, warrant, national security letter or gag order, and no informal request from any government, company or person. None has been complied with, and no user has needed to be told of one.

| Requests received | Complied with, in whole or in part | Users notified |
|---|---|---|
| 0 | 0 | 0 |

This section is updated whenever a request arrives and at least every six months, with the date. If its date is more than six months old, take that into account.

The maintainer publishes the text of this page, signed with the project's asset key, as `legal-process.md` and `legal-process.md.sig` in the [`keys` release](https://github.com/jakes1345/Clairix/releases/tag/keys), each time they run the workflow that signs [the record of Clairix's signing keys](https://github.com/jakes1345/Clairix/blob/main/docs/KEYS.md). A signature that verifies means the text was published by whoever holds that key; the date above says when the statement was made.
