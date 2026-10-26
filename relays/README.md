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
   | `join` | Optional: how someone gets on it (an invite, where to ask for a code) |
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
