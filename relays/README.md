# The Clairix relay list

`relays.json` is the list of relays Clairix offers when someone sets up
Comms. Merging a change to it on `main` runs the Relay list workflow
(`.github/workflows/relays.yml`), which checks it, signs it with the asset key
(`tools/signing`) and publishes it with its signature as the `relays` release.
Phones fetch it from there once a day, use it only if the signature checks,
and never go back to a list issued earlier than one they have. The app also
carries the copy that was in this file when it was built, for its first start.

## Adding or changing a relay

1. Add or edit its entry. Every field is one line of text:

   | Field | What |
   |---|---|
   | `url` | The relay's address, `https://host` with nothing after the host |
   | `name` | What the app calls it |
   | `country` | Where the server is: an ISO country code, or `Global` |
   | `host` | Who hosts it, and where that company is registered: a company answers to its own country's courts wherever its servers are |
   | `operator` | Who runs the relay |
   | `key` | The `relayKey` its `GET /` publishes |
   | `join` | Optional: how someone gets on it. An invite from someone already on it; open registration, when its operator turned it on (`OPEN_REGISTRATION`, with how many new people a day); or a partner organisation that invites people with nobody else to ask, such as a support service, named with how to reach it. The app shows this under the relay in Comms setup |
   | `onion` | Optional: its onion address, `http://<56 characters>.onion` |

2. Set `issued` to the current time in seconds (`date +%s`). It must be later
   than the published list's, or phones would refuse the new one.
3. Check it: `python3 tools/relays/check.py relays/relays.json --live`. With
   `--live` every relay is asked for its key, and a relay answering with a
   different one fails the check.

A relay belongs on the list only once someone has checked, and written down in
the pull request, where its operator and host company are registered, that
the host does not require identification beyond what the list says, and that
the relay has run for a week without trouble (see docs/RELAY-HOSTING-PLAN.md).
A relay that changes its key (a move to another account, a lost
`relay-identity` object) has to be relisted with the new key: until then the
app refuses to register on it, because a relay answering at a listed address
with an unlisted key is what a taken-over address looks like.

## Taking a relay off

Delete its entry and bump `issued`. Phones that fetch the new list stop
offering it; phones already registered there are not moved.

## The version note: latest.json

`latest.json` tells phones which Clairix version is the newest and which is
the oldest still safe to use. The workflow signs it with the asset key and
publishes it on the same release; once a day, with the relay list, the app
fetches it, refuses it unless the signature checks under the key list below,
refuses one issued earlier than the note it has, and compares it with its own
version on the phone. Nothing about the phone is sent: it is the same request
for everyone.

| Field | What |
|---|---|
| `type`, `version` | `"clairix-latest"` and `1` |
| `issued` | When it was written, in seconds (`date +%s`); later than the published note's |
| `versionCode`, `versionName` | The newest release, exactly as `android/app/build.gradle.kts` builds it |
| `minSafeVersionCode` | The oldest version still safe to use, at most `versionCode` |
| `notes` | One line, at most 200 characters, shown with the update: what the newest version fixes |

Below `versionCode` the app offers the update in Settings → About, and the
owner can dismiss it until a newer one is published. Below
`minSafeVersionCode` the Scan and Comms screens carry a banner that says the
version has a security flaw fixed in a later one, and the app sends no
messages, SOS alerts excepted, until it is updated. That is an emergency
brake: raise `minSafeVersionCode` only for a flaw that makes using the old
version unsafe, and only once the fixed version can be had everywhere people
get Clairix (the release page, and Google Play once the Play edition is
there). It stands at 27, Beta 7, the oldest supported version (SECURITY.md):
versions before it let a crafted link take over a verified contact. The brake
is also a power: whoever holds a key the key list accepts can stop every phone
that reads the note from sending anything but SOS alerts until it is updated.
They cannot make a phone send or read anything; it is one more reason the
asset key is kept as tools/signing/README.md describes.

With each release, change `versionCode`, `versionName`, `notes` and
`issued` here in the same commit that changes the version in
`android/app/build.gradle.kts`; the checks fail when the two differ. The
workflow publishes the note only once that version's release, with its APK,
is out; merged before, it fails and says so, and running it again from the
Actions tab after the release publishes the note.

    python3 tools/relays/check_latest.py relays/latest.json --gradle android/app/build.gradle.kts

## The key list: keys.json

`keys.json` lists the keys the files phones download may be signed with: the
relay list, the version note, the maps, the work zones and the stalkerware
list. Each entry is a key (32 bytes of Ed25519, base64, as
`android/asset-signing.pub` holds it), a `role`, `"current"` or `"next"`, and
`revoked`, true or false. The list itself is signed with one key only, the
one built into the app (`android/asset-signing.pub`): phones take a list with
no other signature, refuse one issued earlier than the list they have, and
then accept a file signed by any listed key that is not revoked. A phone with
no list yet accepts the built-in key alone; the app also carries the list
that was in this file when it was built.

This is what makes the asset key replaceable without a new app. To move to a
new key, add it as `"next"` and publish; phones learn it in their daily fetch.
Once they have had time to, sign with it, list it as `"current"`, and mark
the old one revoked, or take it off. A key that leaked is marked revoked at
once, with a new one listed. At most one key of each role may be unrevoked,
and at least one key must be.

The workflow signs the list with the asset signing key, which today is the
built-in key. Once that key is kept offline (docs/KEYS.md), sign the list
there and commit the signature as `relays/keys.json.sig`; the workflow then
publishes that signature instead of making one, and checks either against
the built-in key before publishing.

    python3 tools/relays/check_keys.py relays/keys.json
