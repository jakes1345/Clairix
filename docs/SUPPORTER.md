# Supporter: paid relay capacity that cannot be traced to a phone

Clairix is free, and every safety feature stays free: the stalkerware scan,
Phone check, alerts, removal safety, evidence, helplines, Clean link,
bug-report import, SOS and ordinary encrypted messaging. None of them asks
whether a phone is a Supporter; a unit test
(`android/app/src/test/java/app/clairix/supporter/SafetyStaysFreeTest.kt`)
fails if code under `analysis/`, `security/`, `detect/` or the scanner and
evidence files names the entitlement API.

**Supporter** lifts only what costs the relay money to carry:

| Limit | Free | Supporter | Where |
|---|---|---|---|
| Delivery tokens | the hourly allowance (1000 an hour by default, `DELIVERY_TOKENS_PER_HOUR`) | the same, plus 100 more for each Supporter credential spent | relay, `SUPPORTER_TOKENS_PER_CREDENTIAL` |
| Credentials per code or purchase | | 52 (about one a week for a year) | relay, `SUPPORTER_CREDENTIALS_PER_CODE` |
| A photo or video sent | 5 MB | 10 MB | app, `Entitlement.FREE_MEDIA_MAX_BYTES` / `SUPPORTER_MEDIA_MAX_BYTES`, read through `CommsMedia.sendLimitBytes()` |
| A photo or video received | 10 MB, for everyone | | app, `CommsMedia.MAX_BYTES` |

Clairix has no linked devices, so there is no device limit to lift.

Supporter comes three ways: a code an organisation handed out (a shelter buys
a batch and gives one to each person it helps), a code the project gave
someone, or a purchase in the Google Play edition. A code needs no account, no
card and no email.

## The requirement

The relay must not be able to link a redeemed code, or the organisation that
bought it, to the Clairix number that redeemed it, or to that phone's later
traffic. A Play purchase must not be linkable to the number either.

## The design

It reuses the blind tokens the relay already issues (RFC 9497 VOPRF over
ristretto255, `comms-worker/src/tokens.ts`), with one more kind of token,
`supporter`, under a key of its own.

1. **Redeeming.** The phone makes 52 random inputs, blinds them, and sends
   `POST /v1/supporter/redeem {code, blinded}` on a fresh connection that
   carries no account: no signature, no number, the same kind of request an
   anonymous send is (and through Tor when Tor is on). The relay checks the
   code's hash is held and unused, marks it used, and evaluates the blinded
   elements under this year's Supporter key, with a DLEQ proof. The phone
   checks the proof against the key it pinned from `GET /` (the same for
   every caller) and unblinds: it now holds 52 credentials the relay has
   never seen.
2. **Using a credential.** When the phone's hourly delivery allowance runs
   out, it sends `POST /v1/supporter/tokens {blinded}` with one credential in
   `X-Clairix-Token`, again naming nobody, at a moment of its own choosing
   (not straight after the refusal). The relay checks the credential, records
   it as spent, and evaluates up to 100 blinded *delivery* tokens under the
   ordinary delivery key. Those are the same tokens every phone spends on
   every send; nothing about them says they came through Supporter.
3. **Play.** The Play edition buys the consumable product
   `clairix_supporter`, then sends `POST /v1/supporter/play {productId,
   purchaseToken, blinded}`, naming nobody. The relay asks Google whether the
   purchase is real (only when the operator has set this up; it is off by
   default), exchanges each purchase token once, and answers with
   credentials exactly as for a code. The app then consumes the purchase.
4. **Entitlement.** The app counts as a Supporter while it holds an unexpired
   credential (`Entitlement`, backed by the credential pool). There is no
   local switch that billing or anything else sets.

The Supporter key lives a year (an epoch of 365 days), and a credential is
accepted in its own year and the next, so a credential is good for one to two
years. Every credential issued in a year is under one key, so the key says
nothing about when it was issued, or for which code. Keys are derived from
the relay's existing token secret (`TOKEN_KEY`, or the one the relay makes
itself), exactly like the other kinds: no new key is made or configured.

## What each party learns

| Party | Learns | Does not learn |
|---|---|---|
| **Relay**, at redemption | that a code with this hash was used, at this time, from this connection's address (Tor's, with Tor on) | which account or number redeemed it; the credentials it issued (it saw only blinded elements) |
| **Relay**, at a top-up | that some valid, unspent Supporter credential was spent, at this time, from this address | which redemption or purchase it came from; which account; which sends the resulting delivery tokens pay for |
| **Relay**, at a send | what it learns today: the recipient, size and time | whether the token came from the hourly allowance or a credential |
| **Operator** (who mints codes) | which batch of codes went to which organisation, from their own records off the relay; so, at redemption, "a code from this organisation was just used" | who used it, or which number |
| **Organisation** (shelter) | which code it gave to whom, if it keeps a list | anything the relay sees |
| **Google** (Play) | that this Google account bought Clairix Supporter, as for any purchase. The Play Billing library also reports its own diagnostics to Google | the Clairix number, or anything the relay sees |
| **Relay**, for a Play purchase | that a purchase with this token was exchanged; Google's answer (order id, time, country), which the relay reads and does not keep | the Clairix number |

## What is not protected

- **Timing and address correlation.** A redemption, a top-up and an account's
  own signed requests all come from the same phone. A relay (or Cloudflare)
  that logged connection addresses and times could guess that the account
  whose signed requests come from the same address a minute later is the one
  that redeemed. The relay keeps no such log (Workers observability is off,
  and nothing stores an address), but that is a policy, not a proof. Tor
  removes the address, not the timing. The app does the top-up at a later,
  randomised moment, never straight after the refusal that prompted it, and
  never in the same request as anything signed. Redeeming over Tor is the
  stronger choice where the relay itself is not trusted.
- **Few users.** While few people redeem codes, the set a credential hides in
  is small. Blindness still holds; but a relay that sees one redemption and
  then one top-up a week later can guess they are the same phone.
- **The phone itself.** Someone holding the unlocked phone can see under
  Settings that it is a Supporter, and how many credentials are left. The
  credentials are kept like the delivery tokens, in the app's encrypted
  storage. A Play purchase also shows in the Google account's order history.
- **The paper code.** A code is a bearer value: whoever has it first can
  redeem it. An abuser who finds the card can try it; if it was used, the
  relay says so ("already used"), which tells them it was redeemed somewhere,
  not where or by whom.
- **A lost answer.** The relay marks a code used before it answers. If the
  answer never reaches the phone (the connection drops at that moment), the
  code reads as used and its credentials are lost; the operator can mint a
  replacement. Making a retry idempotent would mean keeping something per
  redemption beside the used flag, which this design avoids.
- **Older versions.** Apps from before Supporter accept photos and videos up
  to 5 MB, so a Supporter's 10 MB video does not reach a contact who has not
  updated.
- **The organisation's list.** If a shelter writes down which person got which
  code, the shelter can be asked for it. The relay cannot add to that
  anything about the number.

## If the relay is seized

The code table holds, per code: SHA-256 of the code (domain-separated) and a
used flag, spread over 256 objects by the hash's first byte. There is no
code, no organisation, no batch, no time and nothing about who redeemed. The
codes carry 110 random bits, so the hashes cannot be reversed by guessing.
What a seizure shows:

- how many codes were minted and how many were used;
- given a code in hand (a printed card), whether it was used;
- the spent-credential records, hashes of inputs the relay never saw issued,
  which link to nothing;
- for Play, a hash of each exchanged purchase token, which matches only a
  token taken from the phone or from Google.

The relay's token secret, if taken with the storage, lets someone mint
credentials (and delivery tokens) of their own; it does not undo blindness,
so it does not link anything already issued.

## Endpoints

| Method | Path | |
|---|---|---|
| POST | `/v1/supporter/redeem` | `{code, blinded}` → `{kind: "supporter", epoch, key, evaluated, proof, expiresAt}`. No identity (refused with 400 if one is sent). 400 for a mistyped code, 404 unknown, 409 used. A few tries a minute per address, counted in memory only |
| POST | `/v1/supporter/tokens` | `{blinded}` with a credential in `X-Clairix-Token` → up to 100 blind delivery tokens. No identity |
| POST | `/v1/supporter/play` | `{productId, purchaseToken, blinded}` → as redeem. 404 unless `PLAY_VERIFY` is on and configured; 403 for a purchase Google does not confirm; 409 when it was exchanged before |
| POST | `/v1/admin/supporter/codes` | `{count}` (1-500) with `X-Admin-Secret: $CODES_ADMIN_SECRET` → `{codes}`. Off while the secret is unset; a few tries a minute per address |

`GET /` carries `supporter: {version, epoch, epochMs, key, next, credentialsPerCode, tokensPerCredential, play}`.

## Setting it up (the operator)

**Codes.** Choose a long random secret yourself and set it on the Worker:

    npx wrangler secret put CODES_ADMIN_SECRET

then mint a batch, and keep the answer: the codes are in it and nowhere else.

    curl -sS -X POST -H "X-Admin-Secret: $CODES_ADMIN_SECRET" \
      -d '{"count": 50}' https://relay.example.org/v1/admin/supporter/codes

Give the codes to the organisation as they are (printed, or in a file they
print). Do not record which code went to which person; recording which batch
went to which organisation is your own bookkeeping, and is what a code
reveals at redemption.

**Google Play** (Play edition only; off until all of this is done):

1. In Play Console, under Monetize → Products → In-app products, create a
   one-time, consumable product with the id `clairix_supporter`, named
   "Clairix Supporter" (never "stalkerware" or "spy"), and set its price.
   The app's id for it is `PlaySupporterBilling.PRODUCT_SUPPORTER`, and the
   relay's `PLAY_PRODUCT_IDS`: change all three together.
2. In Google Cloud, in a project linked to the Play Console account, enable
   the Google Play Android Developer API and create a service account. Create
   a JSON key for it (Google makes it; keep it off the repository).
3. In Play Console, under Users and permissions, invite the service
   account's email and give it access to the app with "View financial data,
   orders and cancellation survey responses".
4. On the Worker:

        npx wrangler secret put GOOGLE_PLAY_SERVICE_ACCOUNT   # paste the JSON key file
        # and as plain variables (wrangler.toml [vars] or the dashboard):
        PLAY_PACKAGE_NAME = "app.clairix"
        PLAY_VERIFY       = "on"

   `PLAY_ALLOW_TEST_PURCHASES = "on"` accepts licence testers' purchases
   while you try it out; turn it off afterwards.

The relay then reaches `oauth2.googleapis.com` and
`androidpublisher.googleapis.com` for each purchase exchanged (the egress
table on the security page lists them).
