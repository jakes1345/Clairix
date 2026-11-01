## What never leaves your phone

The following is created and stored only on your device. It is not uploaded, and we have no way to read it:

- Bluetooth, Wi-Fi, NFC and cellular scan results, including nearby device identifiers and your cell-tower history and baseline.
- The event timeline.
- Your location history, which the tracker scanner and the pattern-of-life report use.
- Vehicle trip records.
- Spyware and phone-health results, including the list of apps installed on your phone.
- The unlock ledger.
- Your Comms encryption keys, contacts and message history. Message bodies are encrypted at rest with a key in your phone's hardware keystore, and so are the location history, the timeline, the cell-tower baseline and vehicle trip records.
- Your duress phrase, stored encrypted and compared only in memory.
- Hidden-camera sweep frames. The camera feed is analysed live and no frame is saved unless you press Keep a photo on a finding: that one picture is sealed with the same hardware-keystore key as the timeline and deleted with its timeline entry. The sweep's findings and checklist are sealed the same way.
- Radio scanner recordings, only while "Record transmissions" is on in Drive (off by default). Each is encrypted with the same hardware-keystore key as the scanner's records, stops after 10 minutes, and goes with your history or once all recordings pass 256 MB, oldest first. Recordings from earlier versions were not encrypted; the app offers to encrypt or delete any that are left.

Android backups are turned off for Clairix, so none of this is copied to a cloud backup. You can delete all of it at any time with Wipe Clairix (Comms → menu → Wipe Clairix, or Settings → Wipe Clairix; type WIPE), or by uninstalling the app. History (the location log, the timeline with any photo kept on it, vehicle trips, the unlock ledger, the hidden-camera sweep's findings, and the map's tile and traffic caches) older than the retention you choose is deleted on its own: 30 days by default, or 7, 90 or forever in Settings, once a day even when Clairix is closed. The cell-tower baseline keeps which towers are usual, not when you were near them, and forgets a tower not seen for three times the retention; an exported evidence report is deleted from the phone after a day.

## What leaves your phone, and where it goes {#egress}

This is the complete list of destinations the app connects to, taken from the code.

<div class="stack egress">

| Destination | When | What is sent | What they can see |
|---|---|---|---|
| **Clairix relay** (our server, run on Cloudflare) | When you register for Comms, then while Comms is online | Your public keys, your Clairix number, sealed message envelopes, and a UnifiedPush endpoint if you set one up | Your Clairix number, a pseudonymous ID. Envelope content is end-to-end encrypted and the relay cannot read it. A send names no account: it spends a single-use token your phone fetched earlier, so nothing the relay stores says who sent an envelope. It sees the recipient, the size and the time. |
| **Your contact's relay**, if they use another one | When you message, call or look up a contact on another relay | The same sealed envelopes and lookups, forwarded by our relay, signed with our relay's key | Our relay's address, never your phone's, and the recipient's number. Nothing names you as the sender. Our relay sees which relay and number you write to. |
| **Nearby Clairix phones**, over Bluetooth | Only with the offline mesh on (off by default), and only for texts, group texts and SOS that cannot reach the relay | The same sealed envelopes, addressed by a tag that changes every day instead of a number | That a Clairix phone is nearby, never whose. The tag is one only the sender and recipient can work out. Content is end-to-end encrypted. |
| **Clairix relay**, SOS or duress | Only when you press SOS or type your duress phrase, and, for anyone the relay could not take right away, again during the next thirty minutes | Your last known or freshly read position, inside an end-to-end encrypted envelope to the members of your groups, never to anyone you blocked | Precise location, encrypted so the relay cannot read it |
| **Clairix relay**, traffic | Only during active turn-by-turn navigation, and only if you are registered for Comms | Anonymous speed samples: a road segment, its map position, a speed and a time. Never your GPS track. Nothing for roughly the first kilometre of a trip (a distance that varies per trip) or the final approach to your destination | Approximate location of road segments. Sent without any identity: each request spends a single-use token, and samples from one drive share a pseudonym your phone makes up and replaces every ten minutes. Samples are deleted after two hours. |
| **Clairix relay**, incident reports | When you report or confirm a traffic incident | Incident type and position | Approximate location. Reports and confirmations carry the same per-drive pseudonym, never your number. |
| **Cloudflare TURN/STUN** | During every call while "Relay-only calls" is on, which it is by default when your relay offers TURN; otherwise during a call that cannot connect directly | Call audio, encrypted end to end with DTLS-SRTP | Your IP address. Not the audio. |
| **OpenStreetMap Nominatim** | Only when you choose "Search online instead". Road names on the offline map are searched on the phone, and a dropped pin is named after the nearest road on it, with nothing sent | The search text | Approximate location, your IP address |
| **Map tile server**: OpenStreetMap's, unless the signed tile setting names another | When you open the map | The coordinates of the map tiles you view, and the app's name and version | The area you are looking at, your IP address |
| **GitHub Releases** | When you download an offline map region (United States only), or the work-zone or plate-reader list for it; before a map downloads, and at most once a week for each map on the phone when you open Drive, to read the map file's first few kilobytes and last four bytes for its size and build date; and, about once a day while you use Clairix, for a newer copy of the stalkerware list the spyware check matches apps against, of the relay list, of the keys our files are signed with, and of the note that says which Clairix version is newest and which is the oldest still safe; and about once a day while a map is open, for the signed setting that names the map's tile server | The name of the region, or of the list or setting | Your IP address. The files are signed, and the app refuses one whose signature does not verify. Nothing about the apps on your phone, or which version it runs, is sent. |
| **Google Play services**, on your phone, where it is installed | Every position fix the tracker scanner, navigation and SOS ask for, unless Satellite-only positioning is on (Settings, Location). The GPS check also asks Android for a Wi-Fi/cell position to compare the satellites with, unless that cross-check is off | Clairix sends Google nothing itself. To work out a position from Wi-Fi and cell towers, Google Play services sends Google the identifiers of the networks and towers around you, under Google's privacy policy, unless Google Location Accuracy is off in Android's location settings | Where you are, from those identifiers and your IP address. With Satellite-only positioning on, Clairix asks only the satellite receiver and asks for no Wi-Fi/cell position at all. On a phone without Google Play services, Clairix uses Android's own location service instead, and a Wi-Fi/cell position goes to whatever location provider that phone has, if any. |
| **Your phone's text-to-speech engine** | While spoken directions are on during navigation | What is read out: turns with street names, "plate reader ahead", incidents | Clairix picks a voice that works offline when one is installed for the phone's language, and asks the engine not to use the network. With no such voice the route options say so, and the engine may send the words to its maker under its own policy. Muting directions sends nothing. |
| **An app you pick** (Android share sheet) | When you export a report or share an invite | Whatever you chose to share | Up to that app |

</div>

Nothing else. There is no analytics SDK, no advertising SDK and no crash reporter in the app. We do not know how many people use Clairix, which features they use, or whether it crashed.

OpenStreetMap services are run by the OpenStreetMap Foundation under its own [privacy policy](https://osmfoundation.org/wiki/Privacy_Policy). Google Play services is Google's, under Google's own [privacy policy](https://policies.google.com/privacy); it does not work for us and we receive nothing from it. Cloudflare processes relay and call traffic on our behalf. Like any host, its network sees your IP address when your phone connects. We use connection addresses only to rate-limit account registration, and we do not keep them in our records.

The app is built with open-source libraries: Android libraries, the native code compiled into them, and the Rust crates of the encryption library. Each keeps its own licence. The app lists them, with their licences, under Settings, About, Third-party licences, and [THIRD-PARTY-NOTICES.md](https://github.com/jakes1345/Clairix/blob/main/THIRD-PARTY-NOTICES.md) in the source code lists the libraries Clairix declares and every Rust crate it compiles in.

## Encryption {#encryption}

Clairix uses standard, well-studied constructions. Nothing here is invented.

### Messages: the Olm double ratchet
Every message is encrypted on your phone for one contact using the Olm double ratchet, implemented by **vodozemac**, the audited Rust library used by the Matrix ecosystem. Clairix uses its version-2 session format, which authenticates every message with a full-length HMAC.

Each identity has a long-term Ed25519 signing key and a Curve25519 identity key, plus signed one-time and fallback keys published to the relay so a contact can start a session while you are offline. Every message moves the ratchet forward, so each message has its own key. This gives:

- **Forward secrecy.** A key stolen later cannot decrypt messages sent earlier.
- **Post-compromise security.** A session heals itself after a compromise once the two phones exchange messages again.

The relay checks that every key it stores is signed by the owner's identity key, so it cannot substitute its own, and your contact's phone checks the same signatures.

### Sealed envelopes: hiding the sender
The encrypted Olm message is then wrapped in an anonymous box addressed to the recipient's sealing key: an ephemeral X25519 key agreement, HKDF-SHA256 bound to both public keys, then ChaCha20-Poly1305. This is the `crypto_box_seal` construction. The envelope carries no sender. The relay stores and forwards envelopes it cannot open, and even if it could open the box, it would find another layer of ciphertext inside.

### Authentication without passwords
Every request that acts on your account (registering, collecting your envelopes, looking up a contact, fetching tokens) is signed with your Ed25519 identity key, with a timestamp and a single-use nonce. There are no passwords or session cookies to leak. The same key is what your contacts verify when they compare safety numbers with you.

Sending an envelope and reporting traffic are deliberately not signed. Each spends a single-use token that your phone fetched earlier over the signed channel, so the relay cannot tie the send to an account. From Beta 6 the tokens are blind (RFC 9497, the construction Privacy Pass uses): your phone makes each token and the relay signs it without seeing it, with a proof that it used the key it publishes to everyone, so even a relay that recorded every request cannot match a spent token to the account it was issued to. A key belongs to one account: the relay refuses to register a key another number already holds, so nobody can take over your keys by registering them as their own.

### Calls
Signalling for a call travels inside the same encrypted envelopes as messages. The audio is WebRTC with DTLS-SRTP between the two phones. The fingerprints that key the audio are carried inside the encrypted envelopes and are therefore authenticated by your contact's pinned identity. Neither the relay nor a TURN server can read or replace them. A TURN server forwards packets it cannot decrypt.

To connect directly, the two phones exchange their network addresses, as WebRTC does; a contact you call learns yours whether or not they answer. "Relay-only calls" in Comms settings sends the audio through the TURN server always, so your contact's phone learns only the server's address. It is on by default whenever your relay offers a TURN server; on a relay with none, calls connect directly unless you switch it on, and then they cannot connect until the relay has one. A choice you make in Comms settings is kept either way.

### On your phone
Your Olm account, sealing key and sessions are serialised under a random 32-byte key. That key is wrapped by a key in Android's hardware-backed keystore. The Comms database (contacts, messages, groups, receipts) is SQLCipher, every page encrypted under a random key wrapped by the keystore, and message bodies inside it are encrypted once more; the Comms settings (your relay, number, name and push endpoint) are sealed the same way, and so are the scanner's records (location history, timeline, cell-tower baseline, trips), the unlock ledger and the radio scanner's recordings, each second of audio sealed to its place in its file. The app-lock passcode is never stored; only an HMAC verifier made with a hardware key is, so it cannot be brute-forced from a copy of the files. A biometric unlock key is destroyed by Android whenever a fingerprint or face is added or removed.

Two limits. The scanner has to write while the keyguard is up, so the keys that seal its records and the unlock ledger work while the phone is locked. Comms is different: since Beta 7, what reads your conversations (your identity, the key to the Comms database, stored photos and videos, the duress phrase and the Comms log) needs the phone unlocked, and what arrives while it is locked waits sealed until then; only what the phone needs to keep collecting it, such as your relay and number, stays readable. On a phone with no secure lock screen there is no locked state, and those keys work at any time. Either way, code running as the app on an unlocked phone can read what the app can. And the remaining settings files (detection thresholds, the sleep window, the passcode's verifier, TLS pins) are not encrypted; they hold settings, not records of your life.

## What the relay stores, and for how long {#relay}

The relay is a small store-and-forward service. It is built to know as little as possible. Per Clairix number it holds:

| Data | Kept for |
|---|---|
| Your Clairix number and public keys | Until you delete your account |
| Encrypted envelopes waiting for your phone | Deleted when your phone collects them, or after 30 days. Each is first held a random few seconds for mixing, and marked if it came without a contact's pass |
| Your UnifiedPush endpoint, if you set one | Until you change or remove it, or delete your account |
| The key your contacts' passes are checked with (your phone gives contacts the passes inside your encrypted messages; envelopes without one share a small part of your mailbox) | Until your phone replaces it, which it does when you delete a contact, or you delete your account |
| Unused invite codes you created | 7 days |
| Hashes of the old kind of single-use token, which apps from before Beta 6 fetch and have not spent | 14 days, plus up to twelve hours; none are issued or spent from 1 January 2027 |
| Hashes of blind tokens already spent, so none is spent twice; nothing is kept when they are issued | Until the key they were made with is retired, 14 to 28 days |
| A note that each of your keys belongs to your number | Until you delete your account |
| ClairixCoin, in Clairix Full on a relay that offers it: your balance, and for each transfer you make or receive its id, the amount, the hour and whether it came in or went out (never who paid whom; any note travels end-to-end encrypted between the two phones) | The balance until you delete your account; each transfer for 30 days at most, the 100 most recent |
| That your number is suspended, and the day it began, only if the relay's operator suspended it under the terms ([abuse policy](https://github.com/jakes1345/Clairix/blob/main/docs/ABUSE.md)). Suspended, a number cannot sign in, and the relay answers everyone else as if it did not exist | Until the suspension is lifted or the account is deleted |
| Rate-limit allowances, as a count and the hour | Deleted once they have refilled |
| The nonce of each signed request | 5 minutes |
| Anonymous traffic samples (navigation only) | 2 hours |
| Learned road-segment positions (navigation only) | 30 days |
| Incident reports (navigation only) | Until they expire or are voted away |
| The relay's own signing key, which it made for itself and uses to sign what it forwards to other relays | Until the operator replaces it |
| For each other relay that forwards to ours: its public key, its request nonces and its rate-limit allowances | Key: refetched daily. Nonces: 5 minutes. Allowances: until they refill |

The relay never holds message content, a name, a phone number, an email address or your contact list. Group membership lives on the phones, not on the relay.

What the relay can observe: the recipient of each envelope, its size, the time, and whether it carried a pass from someone the recipient has written to (never whose). Each envelope is held a random few seconds (about four on average, never more than twenty) and released with whatever else is due, in shuffled order; calls being set up, SOS alerts and group membership changes are not held, and are marked so the relay can tell. A send is not signed and names no account, and a lookup names its target in the signed body, never in the URL, so a request log holds neither senders nor who looked up whom. A ClairixCoin payment is signed by the payer and names the payee, so the relay sees who pays whom while it moves the coins; it writes down only the amount and the hour, on each side. The relay's own logging is off. Nothing a mailbox stores says when its owner was last here: limits are kept to the hour, nonces to the minute, registrations to the day, ClairixCoin transfers to the hour.

We say the rest plainly too. A relay operator, or the platform under it, who captured live requests could see the network address each one came from. Apps from before Beta 6 fetch tokens the relay hands out, which it could tie to the account; current tokens are blind and that link is gone, and from 1 January 2027 the relay refuses the old kind. That is possible only while a request is in flight; none of it is written down. If your IP address matters to you, turn on Tor in Comms settings (it is built into the app, and relays with an onion address are reached without leaving Tor), or use a VPN. If you use a VPN or a Tor app outside Clairix, also turn on "Refuse the relay if its key changes" (Phone check → Certificates): a Tor exit relay sits between you and our relay, and in October 2026 more than 300 hijacked exits swapped in fake keys to steal SSH passwords. Messages stay end-to-end encrypted either way, and a forged web certificate would have to fool the normal certificate checks first; with the key pinned, even one that did is refused.

What of this could be handed over under a court order, what could not, and how many requests have arrived is on the [legal process page](/legal-process).

You delete your relay account from inside the app with Wipe Clairix or Delete identity and number (both in Settings, under Wipe and delete), or by email. [How to delete your account.](/delete-account)

## No analytics, by design

There is nothing in Clairix that reports back to us. No usage events, no device identifiers, no crash reports, no A/B flags. The privacy page in the app says the same thing in the same words. If you want to help us improve Clairix, write to us. That is the only telemetry we have.

## Responsible disclosure {#disclosure}

If you find a vulnerability in the app, the relay, this website or anything else [SECURITY.md lists as in scope](https://github.com/jakes1345/Clairix/blob/main/SECURITY.md#in-scope), please tell us first. This is the same policy as SECURITY.md in the repository.

- Email **security@clairix.app**. Please do not open a public issue for anything that could put people at risk before it is fixed.
- Tell us the version (Settings → About), the edition, the Android version, and the steps or a proof of concept. A proof of concept is welcome; other people's data is not.

**Sending it encrypted.** There is no published key for security@clairix.app yet. To send a report encrypted today, write first without the details and include your own OpenPGP or age public key, or your Clairix number and relay. Within three business days we answer encrypted to your key with a key of ours for your reply, or write to you in Clairix, whose messages are end-to-end encrypted; send the details that way. A published key, and a signed security.txt naming it, are still to come.

**What we do, and by when.**

- We acknowledge your report within 3 business days.
- We triage it within 10 business days: whether we can reproduce it, how serious we think it is and what we plan to do.
- We fix it, or agree a plan with you, within 90 days of the report.
- We keep you informed while we work on it, and tell you before the fix ships.

Business days are Monday to Friday, leaving out public holidays.

**Disclosure.** We agree a disclosure date with you, normally the day the fixed version is published; after 90 days from the report you may publish whether or not a fix is out, unless we have agreed a later date. When the fix ships, we describe the problem in the release notes and in the project's security findings. If a flaw is being used against people, we may tell users sooner, and we tell you first.

**Safe harbour.** We will not take or support legal action against anyone who, in good faith, reports a vulnerability to us first and keeps to this policy: tests only phones, accounts and relays they own or run, does not access, keep or share anyone else's data, does not disrupt our relay or anyone's use of it, and gives us the agreed time before publishing. If you are unsure whether something is covered, ask us first.

**Credit.** With your permission we credit you, by name or handle, in the release notes and in the project's security findings. There is no bug bounty.

**How fixes reach people.** Once a day the app reads a signed note of the newest version and of the oldest one still safe to use. Settings → About then offers the update; below the oldest safe version the Scan and Comms screens say why, and the app stops sending messages, SOS alerts excepted, until it is updated. Only versions after 3.0.0 Beta 8 read the note. For older versions our relay ends what it kept only for them: from 1 January 2027 it refuses the old kind of token (apps before Beta 6) and envelopes in format 2 (apps before Beta 7), with a message to update Clairix.

## Where the limits are

- **Detection is best-effort.** No app can find every camera, tracker or piece of spyware. Each feature page says what it will miss.
- **A compromised phone cannot be trusted by anything running on it.** Clairix is an ordinary app. If the operating system, the baseband or a system app is working against you, it can read what Clairix reads and see what you type. Clairix can notice some signs (root, an unlocked bootloader, failed verified boot, an added certificate, a proxy, USB debugging, a microphone or camera in use) and tell you. It cannot stop or remove an implant below it.
- **A phone seized while unlocked**, or taken with Clairix open, shows its holder whatever it would show you, except that with a passcode set, Patterns, Unlocks, the tracker map and the evidence export ask for it again each time they are opened. The wipe needs you to reach it, or the wrong-passcode limit to be hit.
- **Your location from the network.** Your carrier always knows roughly where a phone is from the towers it talks to. Clairix can detect a fake tower; it cannot hide you from a real one.
- **Traffic analysis is possible.** Anyone watching your network can see that your phone talks to the Clairix relay and roughly when. They cannot see what it says.
- **The relay can see envelope metadata** as described above: the recipient, the size and the time of each envelope, though not the sender. The relay mixes: it holds each envelope a few seconds and releases them shuffled, so someone watching traffic into and out of the relay cannot pair them by timing alone. That is one relay, not a network of independent mixes: its operator still sees every envelope arrive and leave, a quiet relay has little to mix with, and someone watching both ends for weeks can still match people by volume. "Cover traffic" in Comms settings sends envelopes nobody reads to random contacts at random intervals, so your real messages are not the only traffic.
- **Relays.** Anyone can run a relay, and relays reach each other, so an order against one does not cut off people on others. If yours is blocked, you can move to another and keep your contacts. An order can shut a relay down or make it refuse you; it cannot make it read your messages. A relay can publish other addresses it answers at, and the app falls back to them when the first is blocked; with the relay's key pinned, only the pinned address is used. A number is unique on one relay only: anyone running a relay can present any number on it, which is why groups recognise members by their keys and why you should verify a contact on another relay in person.
- **Your keyboard** sees everything you type before Clairix can encrypt it. Clairix asks it not to learn from what is typed in the app, and Phone check warns if it can reach the internet; a keyboard is free to ignore the request.
- **No independent audit yet.** vodozemac, the encryption library, has been audited. Clairix as a whole has not been independently reviewed. We will say so here until it has. Our own review of the code that lets relays reach each other found and fixed three problems before it shipped: a way to learn whether any number exists, a way for another relay to fill someone's mailbox, and group messages that recognised members by number alone. Beta 6 closed two more our review had left open: the mesh tag, which anyone with a contact's public keys could recognise, and a way for several relays together to learn that a number exists by filling its mailbox. The mixing, the mesh and the blind tokens have had no outside review.
- **We can only promise what the code does.** This page was written from the code, not from a marketing brief. If something here turns out not to match the app's behaviour, tell us and we will correct the page or the app.
