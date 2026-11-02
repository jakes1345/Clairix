# Open issues

Every security finding that is not closed, in one table: open, partly done,
or not done by decision. [SECURITY-FINDINGS.md](SECURITY-FINDINGS.md) tells
each in full; this is the list. Checked against the code on 1 November 2026,
and brought up to date with each release (`android/README.md`, "Cutting a
release").

- **Id.** A number is the finding's number in SECURITY-FINDINGS.md. R2.1 to
  R2.5 are the design changes its second review left open, in the order it
  lists them; A is the audit; P2 is phase-2 work it lists; GA-39 comes from
  item 39 of the [2026-10-31 gap audit](audit/GAP-AUDIT-2026-10-31.md), whose
  other defects are in that report and not repeated here.
- **Commit.** Where the row's current state was reached: the change that
  partly fixed it or, where nothing has changed, the commit that recorded it.

| Id | Finding | Status | Commit | What remains |
|---|---|---|---|---|
| 4 | Send and fetch times can be matched at the edge | Partly done | `8b322cc`, `99f3364` | Cover traffic (off by default) and relay mixing (each envelope held a random few seconds, released shuffled); the recipient of each envelope stays visible, and one mixing relay is not a mix network |
| 5 | One relay is one order | Partly done | `8b322cc`, `99f3364` | A relay can publish alternate addresses, and relays reach each other; the signed relay list (`relays/relays.json`) names one relay |
| 7 | `KeystoreBox`'s key is not bound to user authentication or StrongBox | Not done, by decision; partly superseded | `e71ecdb` | Since Beta 7 what reads conversations is under a key that works only while the phone is unlocked (`VaultBox`); the scanner's records, the Comms settings and the transport key still work while it is locked, after the first unlock |
| 10 | A card stays armed for NFC readers | Partly done | `4553e1c` | Disarmed when the app locks or wipes, when the screen goes off and five minutes after arming; the vault re-locking leaves it armed, so a card can be armed, the vault closed and a reader tapped within those minutes |
| 18 | Release signing on the CI runner | Open | `e43c42a` | Every action is pinned to a commit; `release.yml` still decodes the release keystore (`ANDROID_KEYSTORE_BASE64`) onto the GitHub runner for each release ([KEYS.md](KEYS.md)) |
| 22 | No limit on failed authentication | Not done, by decision | `3394c4b` | Request bodies are capped at 256 KiB before anything reads them; each bad signature still costs a mailbox a storage read and a check. A limiter would let anyone lock the owner out, and Cloudflare's rate limiting is the layer meant for floods |
| 25 | ClairixCoin can be farmed | Partly done | `3394c4b`, `4553e1c`, `b8fd93e` | A credit into a deleted mailbox refunds the payer, a payment is made once per id the phone chooses, and an operator can switch the coin off (`COIN=off`); where it is on, as it is by default, and `COIN_INITIAL_BALANCE` is set, every new number gets that balance, and invites let one account make more |
| R2.1 | The identity's pickle key never rotates | Open | `9178dc5` | An earlier copy of the identity file opens with the key the phone holds today |
| R2.2 | Invite trees | Open | `9178dc5` | An account can make 20 invites a day and every account it brings in can do the same, so one account can multiply itself |
| R2.3 | A mesh hello lists the bundles a phone carries | Open | `9178dc5` | The list can single a phone out for as long as bundles are kept, 48 hours |
| R2.4 | The relay's clock drives the clock check | Open | `9178dc5` | The Device tab compares the phone's clock with the relay's (`security/RelayClock.kt`), so a relay that lies about the time can make the check read wrong |
| R2.5 | The lock's failure counter and wipe threshold are in a plain file | Open | `9178dc5` | Whoever can edit the app's private files (root, a debuggable build) can reset the counter or change the threshold |
| A | No independent audit | Open | | vodozemac has been audited; Clairix has not, including the mixing, the mesh and the blind tokens |
| P2 | Place search beyond road names on the phone | Open | `eaa9839` | Roads are found on the phone; an address or a place needs "Search online instead", which sends the text to OpenStreetMap's Nominatim |
| GA-39 | `.expect()` in the crypto crate | Stated, not changed | `9b1558e` | 24 calls outside tests in `comms-crypto/src/lib.rs`, built with `panic = "abort"` for release: 18 check for a poisoned mutex, which cannot happen when every panic aborts, and 6 assert what the libraries promise for the inputs given (HKDF outputs of 16 and 32 bytes, an HMAC key, ChaCha20-Poly1305 over a buffer in memory, VOPRF blinding of 32 bytes). A library change that broke one of those promises would end the app instead of returning an error |
