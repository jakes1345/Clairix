## What never leaves your phone

The following is created and stored only on your device. It is not uploaded, and we have no way to read it:

- Bluetooth, Wi-Fi, NFC and cellular scan results, including nearby device identifiers and your cell-tower history and baseline.
- The event timeline.
- Your location history, which the tracker scanner and the pattern-of-life report use.
- Vehicle trip records.
- Spyware and phone-health results, including the list of apps installed on your phone.
- The unlock ledger.
- Your Comms encryption keys, contacts and message history. Message bodies are encrypted at rest with a key in your phone's hardware keystore.
- Your duress phrase, stored encrypted and compared only in memory.
- Hidden-camera sweep frames. The camera feed is analysed live and no frame is saved.

Android backups are turned off for Clairix, so none of this is copied to a cloud backup. You can delete all of it at any time with Panic Wipe, or by uninstalling the app.

## What leaves your phone, and where it goes {#egress}

This is the complete list of destinations the app connects to, taken from the code.

<div class="stack egress">

| Destination | When | What is sent | What they can see |
|---|---|---|---|
| **Clairix relay** (our server, run on Cloudflare) | When you register for Comms, then while Comms is online | Your public keys, your Clairix number, sealed message envelopes, and a UnifiedPush endpoint if you set one up | Your Clairix number, a pseudonymous ID. Envelope content is end-to-end encrypted and the relay cannot read it or see who sent it. |
| **Clairix relay**, SOS or duress | Only when you press SOS or type your duress phrase | Your position, inside an end-to-end encrypted envelope to your chosen contacts | Precise location, encrypted so the relay cannot read it |
| **Clairix relay**, traffic | Only during active turn-by-turn navigation, and only if you are registered for Comms | Anonymous speed samples: a road segment, its map position, a speed and a time. Never your GPS track. Nothing for the first 500 m of a trip or the final approach to your destination | Approximate location of road segments. The sender is stored only as a salted one-way hash of your Clairix number. Samples are deleted after two hours. |
| **Clairix relay**, incident reports | When you report or confirm a traffic incident | Incident type and position | Approximate location, with a hashed reporter ID |
| **Cloudflare TURN/STUN** | During a call that cannot connect directly | Call audio, encrypted end to end with DTLS-SRTP | Your IP address. Not the audio. |
| **OpenStreetMap Nominatim** | When you search for an address | The search text, or coordinates for a reverse lookup | Approximate location, your IP address |
| **OpenStreetMap tile servers** | When you open the map | The coordinates of the map tiles you view | The area you are looking at, your IP address |
| **GitHub Releases** | When you download an offline map region | The name of the region | Your IP address |
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
Every request to the relay is signed with your Ed25519 identity key, with a timestamp and a single-use nonce. There are no passwords, tokens or session cookies to leak. The same key is what your contacts verify when they compare safety numbers with you.

### Calls
Signalling for a call travels inside the same encrypted envelopes as messages. The audio is WebRTC with DTLS-SRTP between the two phones. The fingerprints that key the audio are carried inside the encrypted envelopes and are therefore authenticated by your contact's pinned identity. Neither the relay nor a TURN server can read or replace them. A TURN server forwards packets it cannot decrypt.

### On your phone
Your Olm account, sealing key and sessions are serialised under a random 32-byte key. That key is wrapped by a key in Android's hardware-backed keystore. Message bodies in the local database are encrypted the same way. The app-lock passcode is never stored; only an HMAC verifier made with a hardware key is, so it cannot be brute-forced from a copy of the files. A biometric unlock key is destroyed by Android whenever a fingerprint or face is added or removed.

## What the relay stores, and for how long {#relay}

The relay is a small store-and-forward service. It is built to know as little as possible. Per Clairix number it holds:

| Data | Kept for |
|---|---|
| Your Clairix number and public keys | Until you delete your account |
| Encrypted envelopes waiting for your phone | Deleted when your phone collects them, or after 30 days |
| Your UnifiedPush endpoint, if you set one | Until you change or remove it, or delete your account |
| Unused invite codes you created | 7 days |
| Anonymous traffic samples (navigation only) | 2 hours |
| Learned road-segment positions (navigation only) | 30 days |

The relay never holds message content, a name, a phone number, an email address or your contact list. Group membership lives on the phones, not on the relay.

What the relay can observe: the recipient of each envelope, its size and the time, and, at the moment a message is sent, the sender who signed the send request. The sender is not stored with the envelope. We say this plainly because a relay operator who logged every request could see who messaged whom and when. We do not keep such logs, and the design means that even the fullest log would contain no content.

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
- **A compromised phone cannot be trusted by anything running on it.** If your phone is rooted, has an unlocked bootloader or fails verified boot, Clairix tells you, and its own results should be treated with care.
- **Traffic analysis is possible.** Anyone watching your network can see that your phone talks to the Clairix relay and roughly when. They cannot see what it says.
- **The relay can see envelope metadata** as described above. This is a property of any store-and-forward design that does not have a mixing network behind it.
- **No independent audit yet.** vodozemac, the encryption library, has been audited. Clairix as a whole has not been independently reviewed. We will say so here until it has.
- **We can only promise what the code does.** This page was written from the code, not from a marketing brief. If something here turns out not to match the app's behaviour, tell us and we will correct the page or the app.
