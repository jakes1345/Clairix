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
- Hidden-camera sweep frames. The camera feed is analysed live and no frame is saved.

Android backups are turned off for Clairix, so none of this is copied to a cloud backup. You can delete all of it at any time with Panic Wipe, or by uninstalling the app. History (the location log, the timeline, vehicle trips and the unlock ledger) older than the retention you choose is deleted on its own: 30 days by default, or 7, 90 or forever in Settings.

## What leaves your phone, and where it goes {#egress}

This is the complete list of destinations the app connects to, taken from the code.

<div class="stack egress">

| Destination | When | What is sent | What they can see |
|---|---|---|---|
| **Clairix relay** (our server, run on Cloudflare) | When you register for Comms, then while Comms is online | Your public keys, your Clairix number, sealed message envelopes, and a UnifiedPush endpoint if you set one up | Your Clairix number, a pseudonymous ID. Envelope content is end-to-end encrypted and the relay cannot read it. A send names no account: it spends a single-use token your phone fetched earlier, so nothing the relay stores says who sent an envelope. It sees the recipient, the size and the time. |
| **Your contact's relay**, if they use another one (Beta 5) | When you message, call or look up a contact on another relay | The same sealed envelopes and lookups, forwarded by our relay, signed with our relay's key | Our relay's address, never your phone's, and the recipient's number. Nothing names you as the sender. Our relay sees which relay and number you write to. |
| **Nearby Clairix phones**, over Bluetooth (Beta 5) | Only with the offline mesh on (off by default), and only for texts, group texts and SOS that cannot reach the relay | The same sealed envelopes, addressed by a tag that changes every day instead of a number | That a Clairix phone is nearby, never whose. Up to Beta 5, anyone who already had a person's public keys, such as their contacts, could tell a carried envelope was for that person; from Beta 6 the tag is one only the sender and recipient can work out. Content is end-to-end encrypted. |
| **Clairix relay**, SOS or duress | Only when you press SOS or type your duress phrase | Your position, inside an end-to-end encrypted envelope to your chosen contacts | Precise location, encrypted so the relay cannot read it |
| **Clairix relay**, traffic | Only during active turn-by-turn navigation, and only if you are registered for Comms | Anonymous speed samples: a road segment, its map position, a speed and a time. Never your GPS track. Nothing for the first 500 m of a trip or the final approach to your destination | Approximate location of road segments. Sent without any identity: each request spends a single-use token, and samples from one drive share a pseudonym your phone makes up and replaces every ten minutes. Samples are deleted after two hours. |
| **Clairix relay**, incident reports | When you report or confirm a traffic incident | Incident type and position | Approximate location. Reports and confirmations carry the same per-drive pseudonym, never your number. |
| **Cloudflare TURN/STUN** | During a call that cannot connect directly | Call audio, encrypted end to end with DTLS-SRTP | Your IP address. Not the audio. |
| **OpenStreetMap Nominatim** | Only when you choose "Search online instead". Road names on the offline map are searched on the phone, and a dropped pin is named after the nearest road on it, with nothing sent | The search text | Approximate location, your IP address |
| **OpenStreetMap tile servers** | When you open the map | The coordinates of the map tiles you view | The area you are looking at, your IP address |
| **GitHub Releases** | When you download an offline map region, or the work-zone or plate-reader list for it | The name of the region | Your IP address. The files are signed, and the app refuses one whose signature does not verify. |
| **An app you pick** (Android share sheet) | When you export a report or share an invite | Whatever you chose to share | Up to that app |

</div>

Nothing else. There is no analytics SDK, no advertising SDK and no crash reporter in the app. We do not know how many people use Clairix, which features they use, or whether it crashed.

OpenStreetMap services are run by the OpenStreetMap Foundation under its own [privacy policy](https://osmfoundation.org/wiki/Privacy_Policy). Cloudflare processes relay and call traffic on our behalf. Like any host, its network sees your IP address when your phone connects. We use connection addresses only to rate-limit account registration, and we do not keep them in our records.

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

To connect directly, the two phones exchange their network addresses, as WebRTC does; a contact you call learns yours whether or not they answer. "Relay-only calls" in Comms settings sends the audio through the TURN server always, so your contact's phone learns only the server's address.

### On your phone
Your Olm account, sealing key and sessions are serialised under a random 32-byte key. That key is wrapped by a key in Android's hardware-backed keystore. The Comms database (contacts, messages, groups, receipts) is SQLCipher, every page encrypted under a random key wrapped by the keystore, and message bodies inside it are encrypted once more; the Comms settings (your relay, number, name and push endpoint) are sealed the same way, and so are the scanner's records (location history, timeline, cell-tower baseline, trips) and the unlock ledger. The app-lock passcode is never stored; only an HMAC verifier made with a hardware key is, so it cannot be brute-forced from a copy of the files. A biometric unlock key is destroyed by Android whenever a fingerprint or face is added or removed.

Two limits. The keys that seal these are not bound to the phone being unlocked, because the scanner has to write while the keyguard is up, so code running as the app on an unlocked phone can read what the app can. And the remaining settings files (detection thresholds, the sleep window, the passcode's verifier, TLS pins) are not encrypted; they hold settings, not records of your life.

## What the relay stores, and for how long {#relay}

The relay is a small store-and-forward service. It is built to know as little as possible. Per Clairix number it holds:

| Data | Kept for |
|---|---|
| Your Clairix number and public keys | Until you delete your account |
| Encrypted envelopes waiting for your phone | Deleted when your phone collects them, or after 30 days. From Beta 5, each is first held a random few seconds for mixing |
| Your UnifiedPush endpoint, if you set one | Until you change or remove it, or delete your account |
| Unused invite codes you created | 7 days |
| Hashes of the single-use tokens your phone fetched and has not spent (up to Beta 5) | 14 days, plus up to twelve hours |
| Hashes of blind tokens already spent (from Beta 6), so none is spent twice; nothing is kept when they are issued | Until the key they were made with is retired, 14 to 28 days |
| A note that each of your keys belongs to your number | Until you delete your account |
| Rate-limit allowances, as a count and the hour | Deleted once they have refilled |
| The nonce of each signed request | 5 minutes |
| Anonymous traffic samples (navigation only) | 2 hours |
| Learned road-segment positions (navigation only) | 30 days |
| Incident reports (navigation only) | Until they expire or are voted away |
| The relay's own signing key, which it made for itself and uses to sign what it forwards to other relays | Until the operator replaces it |
| For each other relay that forwards to ours: its public key, its request nonces and its rate-limit allowances | Key: refetched daily. Nonces: 5 minutes. Allowances: until they refill |

The relay never holds message content, a name, a phone number, an email address or your contact list. Group membership lives on the phones, not on the relay.

What the relay can observe: the recipient of each envelope, its size and the time. From Beta 5, each envelope is held a random few seconds (about four on average, never more than twenty) and released with whatever else is due, in shuffled order; calls being set up, SOS alerts and group membership changes are not held, and are marked so the relay can tell. A send is not signed and names no account, and a lookup names its target in the signed body, never in the URL, so a request log holds neither senders nor who looked up whom. The relay's own logging is off. Nothing a mailbox stores says when its owner was last here: limits are kept to the hour, nonces to the minute, registrations to the day.

We say the rest plainly too. A relay operator, or the platform under it, who captured live requests could see the network address each one came from. Up to Beta 5 they could also see which account fetched a batch of tokens, because those tokens were random values the relay handed out; from Beta 6 the tokens are blind and that link is gone. That is possible only while a request is in flight; none of it is written down. If your IP address matters to you, reach the relay through Tor or a VPN. If you use Tor, also turn on "Refuse the relay if its key changes" (Phone check → Certificates): a Tor exit relay sits between you and our relay, and in October 2026 more than 300 hijacked exits swapped in fake keys to steal SSH passwords. Messages stay end-to-end encrypted either way, and a forged web certificate would have to fool the normal certificate checks first; with the key pinned, even one that did is refused.

You delete your relay account from inside the app with Panic Wipe or Unpair, or by email. [How to delete your account.](/delete-account)

## No analytics, by design

There is nothing in Clairix that reports back to us. No usage events, no device identifiers, no crash reports, no A/B flags. The privacy page in the app says the same thing in the same words. If you want to help us improve Clairix, write to us. That is the only telemetry we have.

## Responsible disclosure {#disclosure}

If you find a vulnerability in the app, the relay or this website, please tell us first.

- Email **security@clairix.app**.
- Give us enough to reproduce the issue. A proof of concept is welcome; stolen data is not.
- We will acknowledge your report, keep you informed, and credit you when the fix ships if you want to be credited.
- Please give us a reasonable window to fix the issue before publishing it.

We ask that you do not test against other people's accounts or disrupt the relay for other users. We will not pursue anyone who reports in good faith under these terms.

There is no bug bounty at present. If you would like to encrypt your report, ask at security@clairix.app and we will arrange a key with you.

## Where the limits are

- **Detection is best-effort.** No app can find every camera, tracker or piece of spyware. Each feature page says what it will miss.
- **A compromised phone cannot be trusted by anything running on it.** Clairix is an ordinary app. If the operating system, the baseband or a system app is working against you, it can read what Clairix reads and see what you type. Clairix can notice some signs (root, an unlocked bootloader, failed verified boot, an added certificate, a proxy, USB debugging, a microphone or camera in use) and tell you. It cannot stop or remove an implant below it.
- **A phone seized while unlocked**, or taken with Clairix open, shows its holder whatever it would show you. The wipe needs you to reach it, or the wrong-passcode limit to be hit.
- **Your location from the network.** Your carrier always knows roughly where a phone is from the towers it talks to. Clairix can detect a fake tower; it cannot hide you from a real one.
- **Traffic analysis is possible.** Anyone watching your network can see that your phone talks to the Clairix relay and roughly when. They cannot see what it says.
- **The relay can see envelope metadata** as described above: the recipient, the size and the time of each envelope, though not the sender. From Beta 5 the relay mixes: it holds each envelope a few seconds and releases them shuffled, so someone watching traffic into and out of the relay cannot pair them by timing alone. That is one relay, not a network of independent mixes: its operator still sees every envelope arrive and leave, a quiet relay has little to mix with, and someone watching both ends for weeks can still match people by volume. "Cover traffic" in Comms settings sends envelopes nobody reads to random contacts at random intervals, so your real messages are not the only traffic.
- **Relays.** Anyone can run a relay, and from Beta 5 relays reach each other, so an order against one does not cut off people on others. An order can shut a relay down or make it refuse you; it cannot make it read your messages. A relay can publish other addresses it answers at, and the app falls back to them when the first is blocked; with the relay's key pinned, only the pinned address is used. A number is unique on one relay only: anyone running a relay can present any number on it, which is why groups recognise members by their keys and why you should verify a contact on another relay in person.
- **Your keyboard** sees everything you type before Clairix can encrypt it. Clairix asks it not to learn from what is typed in the app, and Phone check warns if it can reach the internet; a keyboard is free to ignore the request.
- **No independent audit yet.** vodozemac, the encryption library, has been audited. Clairix as a whole has not been independently reviewed. We will say so here until it has. Our own review of the code that lets relays reach each other found and fixed three problems before it shipped: a way to learn whether any number exists, a way for another relay to fill someone's mailbox, and group messages that recognised members by number alone. Beta 6 closed two more our review had left open: the mesh tag, which anyone with a contact's public keys could recognise, and a way for several relays together to learn that a number exists by filling its mailbox. The mixing, the mesh and the blind tokens have had no outside review.
- **We can only promise what the code does.** This page was written from the code, not from a marketing brief. If something here turns out not to match the app's behaviour, tell us and we will correct the page or the app.
