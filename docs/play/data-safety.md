# Data safety: answers for the Play Console

Every answer below is derived from section 2 ("Information that leaves your
device") of the privacy policy, `website/src/content/docs/privacy.md`, and
cites the sentence it comes from. The Data safety form must match the policy;
when the policy changes, this file changes in the same commit, and when this
file finds the policy wrong, the policy is fixed first (see "Mismatches"
below).

Scope: the Play edition (`playSuite`). ClairixCoin (policy 2.4) and the Card
Vault are not in it (`BuildConfig.CLAIRIX_COIN` and `CARD_VAULT` are false), so
they have no row.

How Google's terms are read here (check the console's help text on the day):
"collected" means sent off the phone by the app, to us or to anyone else;
"shared" means passed to a third party other than a service provider acting
for us (Cloudflare is one, policy section 4). Google exempts data that is
end-to-end encrypted so that only sender and recipient can read it, data
processed on the device only, data handled ephemerally (in memory, only to
answer the request), and transfers the user starts and expects (a share sheet,
a report they send). Where an answer depends on one of those exemptions, the
row says so, so that it can be revisited if Google's reading differs.

## Overview questions

| Question | Answer | From the policy |
|---|---|---|
| Does the app collect or share any of the required user data types? | Yes | 2.1, 2.6, 2.9 |
| Is all of the user data collected by the app encrypted in transit? | Yes | 8: "Data in transit is protected with TLS" |
| Do you provide a way for users to request that their data be deleted? | Yes: in the app (Settings → Wipe and delete → Delete identity and number, or Wipe Clairix) and by email to support@clairix.app | 6 |
| Account creation | No email, name or phone number; a Clairix number is assigned by the relay when Comms is set up, which needs an invite | 2.1, 3 |

## Data types

| Play data type | Collected | Shared | Ephemeral | Required or optional | Purposes | Policy sentence |
|---|---|---|---|---|---|---|
| Personal info → User IDs | Yes: the Clairix number, the public keys, the note that the keys belong to the number, the key contacts' passes are checked with, unused invite codes | No | No | Optional (only with Comms) | App functionality; Account management | 2.1: "Your Clairix number and public keys ... a random nine-digit identifier assigned by the relay"; "A note that your keys belong to your number"; "The key your contacts' passes are checked with"; "Unused invite codes you created. These expire after 7 days." |
| Device or other IDs | Yes: the UnifiedPush endpoint, if the user sets one up | No (see note 1) | No | Optional | App functionality | 2.1: "Your push endpoint, if you set one up. This is the UnifiedPush address your phone can be woken through." |
| Location → Precise location | Yes: live-traffic speed samples (a road segment, the segment's map position, a speed, a time) while navigating, deleted after two hours; never the GPS track, nothing for the first and last stretch of a trip; not linked to the Clairix number | No (see note 2) | No | Optional (only while navigating and registered with Comms) | App functionality | 2.6: "Each sample contains a road segment, the segment's map position, a speed and a time. Your GPS track is never sent." "They are deleted after two hours." |
| App activity → Other user-generated content | Yes: a report the user sends about another number (that number, its relay, the reason chosen, a SHA-256 of the last envelope from it), kept at most 90 days; and traffic incidents the user reports, shown to other users until they expire | Incidents: shown to other users of the app at the user's request (user-initiated) | No | Optional | Fraud prevention, security and compliance (reports); App functionality (incidents) | 2.9: "When you report someone, Clairix sends the relay ... their Clairix number, the relay they are on ..., the reason you chose, and a hash (SHA-256) of the last encrypted envelope"; "for at most 90 days". 2.6: "Traffic incidents you choose to report are shared with other users until they expire." |
| App activity → In-app search history | Yes, only when the user taps "Search online instead": the search text goes to OpenStreetMap's Nominatim | Yes: OpenStreetMap Foundation (user-initiated) | No | Optional | App functionality | 2.7: "Only when you choose 'Search online instead' is the search text sent to OpenStreetMap's Nominatim service." |
| Messages → Other in-app messages | No: end-to-end encrypted, the relay cannot read them (exemption) | No | — | — | — | 2.1: "Messages, voice notes, photos, videos, emergency (SOS) alerts and calls are end-to-end encrypted. The relay cannot read their content and does not learn who sent a message." |
| Photos and videos; Audio → Voice or sound recordings | No: sent only end-to-end encrypted (voice notes, photos, videos, call audio) | No | — | — | — | 2.1, as above; "Call audio travels directly between the two phones, or through a relay server that cannot decrypt it." |
| Location (SOS position) | No: sent only end-to-end encrypted to the user's group contacts | No | — | — | — | 2.5: "sends it, end-to-end encrypted, to the contacts in your groups" |
| App activity → Installed apps | No: read on the phone only | No | — | — | — | 5: "Nothing about your apps leaves the phone" (and section 1) |
| Contacts | No: Comms contacts stay on the phone; the phone's contact list is never read | No | — | — | — | 1: "Your Comms encryption keys, contacts and message history"; 3: "We do not collect your name, email address, phone number or contact list." |
| Personal info → Name, Email address, Phone number | No | No | — | — | — | 3, as above |
| App info and performance (crash logs, diagnostics) | No from Clairix's own code. The Google Play Billing library (Supporter purchases, Play edition only) sends Google its own diagnostics: answer for it as Google's SDK index says on the day | No | — | — | — | 3: "There is no analytics SDK, advertising SDK or crash reporter in the app."; 2.12: "the Google Play Billing library in that edition also sends Google its own diagnostics" |
| Financial info → Purchase history | Yes, only when the user buys Clairix Supporter in Google Play: the purchase token goes to the relay, which asks Google whether it is real and keeps a hash of it so it is exchanged once; not linked to the Clairix number (ClairixCoin is not in the Play edition) | No | No (the hash is kept) | Optional | App functionality | 2.12: "keeps a hash of the token so that it is used only once, and does not keep what Google answers" |
| Web browsing, Health and fitness, Calendar, Files and docs | No | No | — | — | — | Nothing in section 2 |

Notes:

1. The relay sends the word "wake" to the endpoint the user chose; the push
   service behind it (the user's own distributor) "learns when each wake was
   sent" (2.1). The endpoint itself is that service's address, chosen by the
   user, so it is answered as not shared. If Google reads a wake-up sent to a
   user-chosen third party as sharing, answer Device or other IDs → Shared,
   App functionality.
2. Incidents are seen by other Clairix users; road-segment speeds from many
   users are combined into live traffic. Neither is passed to a third party.

Not declared, and why:

- Network addresses. "Like any web host, Cloudflare's network handles your IP
  address ... We use connection addresses only to limit abuse of account
  registration, and we do not keep them in our records" (4); "We do not keep
  request logs" (2.1). Ephemeral processing by a service provider.
- What another relay receives (2.2): "the recipient's number and the sealed
  envelope; it sees our relay's address, never yours, and nothing in what it
  receives names you". Nothing about the user is passed on.
- Single-use token hashes (2.1): "do not record which account they were issued
  to". Not user data.
- The offline mesh (2.3): phone to phone over Bluetooth; nothing reaches us.
- Map tiles, offline maps, work-zone, plate-reader and stalkerware lists (2.7,
  5): downloads from OpenStreetMap and GitHub. The request reveals the device's
  IP address and the region or map area asked for, not the user's position or
  any identifier; no user data type applies. Revisit if Google's guidance
  counts a map-area request as location.
- Positions from Google Play services (2.10): the request goes to Google's own
  service on the phone, under Google's policy; "Clairix itself sends Google
  nothing", and no Google SDK is in the app.
- Things the user shares through Android's share menu (2.8). User-initiated.
- Spoken directions (2.6): the phone's text-to-speech engine, not ours.

## Mismatches found in the policy itself

Found while deriving this file; each needs a policy change (and a new
effective date) before the form is submitted, or a reason why not:

1. Section 5 says "Access to the installed-app list is used only by the spyware
   check". The installed-app list is also read by Phone check's "Install unknown
   apps" row (`analysis/UnknownSources.kt`) and its root-manager check
   (`analysis/PhoneHealthMonitor.kt`); section 1 already says "the spyware and
   phone-health checks". Section 5 should say the same.
2. Section 2.6's samples carry "the segment's map position". A road segment's
   position is finer than Google's "approximate location" (an area of 3 square
   kilometres or more), so this file answers Precise location. The 2026-10-31
   audit's suggestion of "Approximate location" does not fit the policy's own
   sentence; if Approximate is wanted, the samples have to carry a coarser
   position first.
3. Section 2.1's token bullet describes the tokens twice: first as hashes kept
   "until spent, or 14 days", then "From Beta 6 the tokens are blind: the relay
   keeps nothing when it issues them". Only one describes the current relay;
   the policy should keep that one.
4. Section 7 said "Google Play lists Clairix for adults" while no listing
   exists; fixed alongside this file (now: "Clairix is for adults;
   you must be 18 or older (Terms, section 2)").
