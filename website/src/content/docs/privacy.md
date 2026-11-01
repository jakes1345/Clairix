This policy explains what information the Clairix app handles, where that information goes, and how long it is kept. It is issued by the operator of Clairix ("Clairix", "we", "us"). Clairix is built to keep your data on your device. Most of what the app does never leaves your phone.

Contact: support@clairix.app
Website: https://clairix.app

This policy covers the Clairix app in both the Google Play edition and the Clairix Full edition downloaded from our website. Where a section applies only to Clairix Full, it says so.

## 1. Information that stays on your device

The following is created and stored only on your phone. We never upload it and cannot access it:

- Bluetooth, Wi-Fi, NFC and cellular scan results, including nearby device identifiers and your cell-tower history.
- The event timeline, the location history used for pattern-of-life and following detection, and vehicle trip records.
- The results of the spyware and phone-health checks, including the list of apps installed on your phone.
- The unlock ledger, which records lock-screen unlock successes and failures.
- Your Comms encryption keys, contacts and message history, in a database that is encrypted as a whole.
- Who you have blocked, and the message requests waiting for you, in the same database (see section 2.9).
- Your duress phrase and app-lock settings.
- Hidden-camera sweep images. The camera feed is analysed live and is not saved.
- **Clairix Full only:** cards you save in the Card Vault. These are encrypted with a key held in your phone's secure hardware.

You can delete all of this at any time with Wipe Clairix (Comms → menu → Wipe Clairix, or Settings → Wipe Clairix; type WIPE), or by uninstalling Clairix. History (the location log, the timeline, vehicle trip records, the unlock ledger, and the map's tile and traffic caches) older than the retention you choose is deleted on its own: 30 days by default, or 7, 90 or forever in Settings. The cell-tower baseline keeps which towers are usual where you go, not when you were near them, and forgets a tower not seen for three times the retention. An evidence report you export is deleted from the phone a day later. Android backups are turned off for Clairix, so this data is not copied to cloud backups.

## 2. Information that leaves your device

### 2.1 Encrypted messages and calls (Comms)

If you choose to use Comms, your phone registers with the Clairix relay server. The relay stores:

- **Your Clairix number and public keys.** These let other people send you encrypted messages. The Clairix number is a random nine-digit identifier assigned by the relay. It is not your phone number and is not linked to your name, email or any other identity.
- **Encrypted message envelopes waiting to be delivered.** These are deleted when your phone collects them, or after 30 days if they are never collected.
- **Your push endpoint, if you set one up.** This is the UnifiedPush address your phone can be woken through. The push itself carries only the word "wake".
- **Unused invite codes you created.** These expire after 7 days.
- **Hashes of the single-use tokens your phone fetched ahead of time** to send messages and traffic reports with. They are kept until spent, or 14 days, and do not record which account they were issued to. From Beta 6 the tokens are blind: the relay keeps nothing when it issues them and, once one is spent, a hash of it until the key it was made with is retired (14 to 28 days), so it cannot be spent twice. Neither says which account it was issued to.
- **A note that your keys belong to your number**, so that nobody else can register them.
- **The key your contacts' passes are checked with.** Your phone gives your contacts passes inside your end-to-end encrypted messages; envelopes that arrive without one share a small part of your mailbox, so a stranger cannot fill it. The relay keeps the key until your phone replaces it (it does when you delete or block a contact) or you delete your account, and notes on each waiting envelope whether it came without a pass. It never learns who a pass belonged to.
- **Reports about your number**, if someone sends one: your number, the reason they chose, a hash of an envelope you sent them and the day, for at most 90 days (see section 2.9).

Messages, voice notes, photos, videos, emergency (SOS) alerts and calls are end-to-end encrypted. The relay cannot read their content and does not learn who sent a message. Call audio travels directly between the two phones, or through a relay server that cannot decrypt it.

Before delivery the relay holds each envelope a random few seconds and releases it together with others, so that timing does not pair a sender with a recipient. The relay can observe the recipient of each envelope, its size and the time it was queued. A send is not signed and names no account: it spends one of the single-use tokens above, and the token's hash does not say which account it was issued to. Like any server, the relay handles the network address a request comes from while answering it. We do not keep request logs, and the relay stores nothing that says when you were last online, beyond the hour of a ClairixCoin transfer you make (section 2.4).

### 2.2 Contacts on other relays

Anyone can run a Clairix relay. If you add a contact who uses another one, our relay forwards your sealed envelopes and lookups for that contact to their relay, signed with our relay's own key. Their relay is run by someone else under their own terms. It receives the recipient's number and the sealed envelope; it sees our relay's address, never yours, and nothing in what it receives names you. Our relay keeps, for each other relay, its public key and short-lived limits on what it may send.

### 2.3 Offline mesh

If you turn on the offline mesh (off by default), texts, group texts and SOS alerts that cannot reach the relay are passed over Bluetooth to nearby phones running Clairix with the mesh on, which pass them on until they reach the person they are for. They are the same sealed envelopes the relay carries, addressed by a tag that changes every day rather than by a number. While the mesh is on, your phone tells nearby Bluetooth scanners that a Clairix phone is present, but not whose.

### 2.4 ClairixCoin (Clairix Full only)

ClairixCoin is in Clairix Full, on any relay whose operator has not switched it off. If you use it, the relay keeps your balance and, for each transfer you make or receive, its id, the amount, the hour it was made and whether it came in or went out. It does not record who paid whom: your phone tells the person you pay inside an end-to-end encrypted notice, together with any note you add, and the relay sees who pays whom only while it moves the coins. It keeps the 100 most recent transfers per account, none for longer than 30 days, and the balance until you delete your account. ClairixCoin is an in-app token with no monetary value.

### 2.5 Emergency alerts

When you send an SOS or type your duress phrase, Clairix takes your last known position, or reads a fresh one when the phone can get a fix within a few seconds, and sends it, end-to-end encrypted, to the contacts in your groups (over the offline mesh too, if it is on and the relay cannot be reached). Location is not read for this purpose at any other time.

### 2.6 Traffic and navigation

Live traffic is used only when you are actively navigating and are registered with Comms. In that case Clairix sends anonymous speed samples to the relay. Each sample contains a road segment, the segment's map position, a speed and a time. Your GPS track is never sent. Nothing is sent for roughly the first kilometre of a trip or roughly the last kilometre before your destination; each stretch is between 500 metres and 1.5 kilometres long and is drawn at random for every trip, so trips cannot be traced back to where they start or end, and the first and last samples of a repeated trip are not a fixed distance from either. Samples are sent without your Clairix number: each request spends a single-use token, and samples from one drive share a temporary pseudonym your phone creates and replaces every ten minutes. They are deleted after two hours. Learned road-segment positions are kept for 30 days. Traffic incidents you choose to report are shared with other users until they expire.

### 2.7 Maps and search

- **Map tiles.** When you view the map, tiles are downloaded from OpenStreetMap's servers. Those servers see your IP address and the area being viewed.
- **Address search.** A search runs on your phone over the road names of the offline map you downloaded, and a dropped pin is named after the nearest road on that map; nothing is sent. Only when you choose "Search online instead" is the search text sent to OpenStreetMap's Nominatim service.
- **OpenStreetMap's terms.** These services are run by the OpenStreetMap Foundation under its own privacy policy: https://osmfoundation.org/wiki/Privacy_Policy
- **Offline road maps, work-zone lists and plate-reader lists.** These are downloaded from GitHub, which sees your IP address and the region you downloaded. The files are signed, and the app refuses one it cannot verify. The plate-reader list is the licence-plate cameras mapped in OpenStreetMap for that region; the app shows them, warns you before one and routes around them, all on the phone.

### 2.8 Things you choose to share

When you export a report or share an invite link, Clairix hands it to the app you pick through Android's share menu. What happens next is up to that app.

### 2.9 Blocking, message requests and reports

Blocking and message requests happen on your phone. When you block someone, Clairix keeps their public keys, their Clairix number, the relay they are on and the name you had for them, so that whatever they send is dropped unread: no message, call or group message from them is kept or shown. Nothing about the block is sent to anyone, and they are not told. A first message from someone who is not in your contacts waits on your phone as a request, and no delivery or read receipt goes back to them unless you accept it; someone who joined with your invite, or a member of one of your groups writing to the group, comes in as a contact instead. If you decline, the request is deleted and the number is blocked.

When you report someone, Clairix sends the relay you are registered with their Clairix number, the relay they are on when it is another one, the reason you chose, and a hash (SHA-256) of the last encrypted envelope they sent you, when one has arrived since this version. Nothing you or they wrote is sent. The report spends one of the single-use tokens described in section 2.1, so it does not say who sent it. The relay keeps reports, dated to the day, for at most 90 days, or until its operator closes them, even if the reported account is deleted sooner. The relay's operator reads them; what they do about an account is up to them and their terms.

## 3. What we do not do

- We do not sell your data.
- We do not use advertising or analytics services. There is no analytics SDK, advertising SDK or crash reporter in the app.
- We do not track you across apps or websites.
- We do not use your data to build advertising profiles.
- We do not collect your name, email address, phone number or contact list.

## 4. Service providers

The Clairix relay and call-relay servers run on Cloudflare, which processes this data on our behalf. Like any web host, Cloudflare's network handles your IP address when your phone connects. We use connection addresses only to limit abuse of account registration, and we do not keep them in our records.

## 5. Permissions

Clairix asks for each permission when you use the feature that needs it. Location, Bluetooth, camera, microphone, NFC, phone state, usage access and device-admin access are used only for the features described inside the app, and the results stay on your device except as described in section 2.

In particular:

- **Background location** is used only by the tracker scanner you start, to tell whether a device is moving with you. Location history stays on the device.
- **Camera** is used for the hidden-camera sweep and to scan a contact's pairing code. Nothing is recorded.
- **Microphone** is used only during a call you place or answer, and while you hold the record button for a voice note.
- **Access to the installed-app list** is used only by the spyware check, on the device. Nothing about your apps leaves the phone: the check matches them against a stalkerware list built into the app, and about once a day while you use Clairix it asks GitHub for a newer, signed copy of that list, which tells GitHub your IP address and that the list was asked for.

## 6. Deleting your data

- **On your device.** Use Wipe Clairix (Comms → menu → Wipe Clairix, or Settings → Wipe Clairix; type WIPE), or uninstall the app.
- **On the relay.** Wipe Clairix and Delete identity and number (Settings → Wipe and delete, or Comms → Settings) both ask the relay to delete your account: your keys, queued envelopes, push endpoint and, in Clairix Full, your coin balance and transfer history. If the relay cannot be reached, the request waits on your phone and is tried again for up to a day. A few things are not removed straight away:
  - Anonymous traffic samples expire within two hours.
  - People you have messaged keep their own copies of your messages, and in Clairix Full, people you sent coins to keep their own record of the transfer.
- **By email.** You can also write to support@clairix.app and ask us to delete your relay account. Tell us your Clairix number. We have no other way to identify your account. Requests are handled within 30 days.
- **Automatic expiry.** Relay data expires on its own as described above.

Full instructions: https://clairix.app/delete-account

## 7. Children

Clairix is not directed to children under 13, and we do not knowingly collect information from them. Google Play lists Clairix for adults.

## 8. Security

Data in transit is protected with TLS, using only the certificate authorities built into Android, and Comms content is also end-to-end encrypted with the Olm double ratchet and sealed envelopes. Your keys, contacts, messages, groups, Comms settings, saved cards, location history, timeline, cell-tower baseline, trip records and unlock ledger on your device are encrypted with keys held in Android's hardware-backed keystore. No system is perfectly secure, and you are responsible for keeping your device and passcode safe. Details are at https://clairix.app/security.

## 9. Legal basis and your rights

Where data protection law applies to you, our basis for handling the data in section 2 is the performance of the service you asked for: delivering your encrypted messages, your emergency alerts and your navigation. You have the right to access, correct and delete your data, and to object to its processing. Because the relay holds only pseudonymous keys and sealed envelopes, the practical way to exercise these rights is to delete your account as described in section 6. For anything else, write to support@clairix.app. We answer within 30 days. If you live somewhere with a data protection authority, you also have the right to complain to it.

## 10. Changes

If we change this policy, we will update the effective date above and post the new version at https://clairix.app/privacy. For significant changes we will also give notice in the app.

## 11. Contact

Questions or requests: support@clairix.app
Security reports: security@clairix.app
