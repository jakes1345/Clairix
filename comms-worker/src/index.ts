import { parseSigned, TS_WINDOW_MS, verifyEd25519 } from "./auth";
import {
  checkPushEndpoint,
  dayFloor,
  DELIVERY_TOKEN_BATCH_MAX,
  DELIVERY_TOKEN_RE,
  ENVELOPE_HASH_RE,
  groupByShard,
  isReportReason,
  isTokenKind,
  openRegistrationCap,
  parseIceServers,
  PUSH_CHALLENGE_PREFIX,
  pushHostsOnly,
  randomToken,
  REPORT_REASONS,
  STUN_ONLY,
  summariseReports,
  TOKEN_KINDS,
  tokenAllowance,
  tokenHash,
  tokenShardName,
  tokenBatchExpiry,
  TRIP_RE,
  TURN_TTL_S,
  TURN_PER_HOUR,
  TURN_BURST,
  ALERTS,
  PREKEY_FUTURE_SKEW_MS,
  PREKEY_MAX_AGE_MS,
  prekeyStatement,
  type Alert,
  type IceServer,
  type SealingPrekey,
  type TokenKind,
} from "./comms-logic";
import { HttpError, base64ToBytes, json, jsonText, normaliseNumber, randomNumber, requireSecret, secretEquals, sha256Hex, timingSafeEqual, type Env } from "./env";
import { KNOWN_REGIONS } from "./regions";
import { INVITE_TTL_MS, Mailbox, MAX_ENVELOPE_B64, SUSPENDED, type CoinTx, type Profile, type SignedKey } from "./mailbox";
import { invitePage, invitePageCsp } from "./invite-page";
import {
  FED_CLAIMS_BURST,
  FED_CLAIMS_PER_HOUR,
  FED_LOOKUPS_BURST,
  FED_LOOKUPS_PER_MINUTE,
  FED_LOOKUPS_PER_DOMAIN_BURST,
  FED_LOOKUPS_PER_DOMAIN_PER_MINUTE,
  FED_SENDS_BURST,
  FED_SENDS_PER_MINUTE,
  FED_SENDS_PER_TARGET_BURST,
  FED_SENDS_PER_TARGET_PER_MINUTE,
  federationDomainObjectName,
  FEDERATION_HEADERS,
  federationObjectName,
  federationPolicy,
  fetchRelayKey,
  forward,
  isLoopbackDev,
  isOwnOrigin,
  KEY_FETCHES_BURST,
  KEY_FETCHES_PER_MINUTE,
  keyFetchFailedRecently,
  noteKeyFetchFailed,
  noteWrittenTo,
  parseFederated,
  policyName,
  registrableDomain,
  RELAY_KEY_REFETCH_MS,
  RELAY_KEY_TTL_MS,
  relayKey,
  RELAY_IDENTITY_NAME,
  relayOrigin,
  verifyFederated,
  WRITTEN_TO_DAYS,
} from "./federation";
import { mintCodes, redeemCode, redeemPlay, supporterInfo, supporterTokens } from "./supporter";
import { BLIND_BATCH_MAX, TOKEN_EPOCH_MS, checkBlindToken, epochExpiry, epochOf, issueBlind, tokenPublicKey } from "./tokens";
import { INCIDENTS_NAME, IncidentCache } from "./incidents";
import { parseBBoxParam } from "./incidents-logic";
import { REGISTRY_NAME, Traffic, type ProbeSample } from "./traffic";
import { INCIDENT_TYPES, PROBE_MAX_AGE_SEC, REGION_RE, SEGMENT_RE, bboxContains, fnv1a, parseWzdx, readTextCapped, type BBox, type IncidentType } from "./traffic-logic";

export { IncidentCache, Mailbox, Traffic };

/**
 * Clairix comms relay: store-and-forward for end-to-end encrypted envelopes
 * between Clairix apps, addressed by Clairix number.
 *
 * A Clairix number is not a phone number. It is a random nine-digit ID this
 * relay hands out at registration, and it works only between Clairix apps
 * paired with this relay.
 *
 *   POST   /v1/register        {ts, secret | invite, ed25519, curve25519, sealing, signature, fallback, oneTimeKeys, sealingPrekey?, listed}
 *                              signed by ed25519 (X-Clairix-Sig over the body) → {number}. ts is epoch millis within
 *                              five minutes, and each key may belong to one account, so a captured body cannot be
 *                              replayed into a second account. With OPEN_REGISTRATION on (off by default), neither
 *                              secret nor invite is needed, up to OPEN_REGISTRATION_PER_DAY accounts a UTC day.
 *   GET    /v1/admin/reports                               → {numbers}: abuse reports by number, for the operator
 *   DELETE /v1/admin/reports   {number, relay?}            → {closed}: reports about one number dealt with. Both carry
 *                                                            X-Admin-Secret (ADMIN_SECRET; off without it), no identity
 *   GET    /i                                              → invite page; the invite itself is in the URL fragment,
 *                                                            which browsers never send to the relay
 * Anonymous (no identity header; refused if one is sent). Each request spends one single-use token of
 * the right kind in X-Clairix-Token (see /v1/tokens); the relay keeps only token hashes, apart from the
 * account they went to:
 *   POST   /v1/send            {to, envelope, claim?, mix?, alert?, pass?}  → queue for a contact; a "delivery" token.
 *                              "pass" (HMAC-SHA256 under the recipient's pass key over
 *                              clairix-pass-v1|to|envelope) says the sender is someone the recipient has
 *                              written to. Once a mailbox has a pass key, envelopes without a good pass share
 *                              a small part of its queue and an allowance of their own (Mailbox.enqueue).
 *                              "alert" ("call" or "sos") asks a locked recipient phone to ring or sound an SOS
 *                              for an envelope it cannot open yet; it is delivered with the envelope.
 *                              "claim" is the ticket of the one-time key the envelope's session was built
 *                              on, which spends that key for good. With "mix" true, the envelope is held a
 *                              random few seconds and released in a shuffled batch (MIX_MAX_MS; see
 *                              Mailbox.enqueue). The app asks for it on everything but call set-up, SOS and
 *                              group changes; apps from before mixing never ask, so nothing of theirs waits.
 *   POST   /v1/report          {number, relay?, envelope?, reason} → 202: someone is abusing this relay; a "report"
 *                              token. envelope is the SHA-256 (hex) of an envelope they sent, reason one of
 *                              REPORT_REASONS. Kept for the operator by the day, 90 days at most; nothing says who
 *                              reported, and nothing of what was said reaches the relay.
 *   Traffic (one Durable Object per map region; "traffic" tokens, "report" tokens for new reports; probes,
 *   reports and votes carry a "trip" pseudonym the phone makes up for the drive and rotates):
 *   POST   /v1/traffic/probe   {region, trip, samples:[{seg, kph, t, lat?, lon?}]} → {ok, accepted}
 *   GET    /v1/traffic/live?region=                        → {now, segments: {seg: {kph, n, t}}, incidents}
 *   POST   /v1/traffic/typical {region, segs: [a, b]}       → {segments: {seg: {h: [168]}}}
 *          (GET ?region=&segs=a,b still answered for one release)
 *   POST   /v1/traffic/report  {region, trip, type, lat, lon, seg|null} → {incident}   ("report" token)
 *   POST   /v1/traffic/report/vote {region, trip, id, still} → {incident|null}
 *   GET    /v1/traffic/incidents?bbox=minLat,minLon,maxLat,maxLon (≤ 1°×1°)
 *                              → {now, incidents, providers: {here, tomtom}}: HERE / TomTom incidents,
 *                                cached per 0.25° tile for 5 minutes in the IncidentCache object
 * Signed (see auth.ts):
 *   GET    /v1/me                                          → profile + key counts, and whether a pass key is set
 *   PUT    /v1/keys            {oneTimeKeys, fallback?, sealingPrekey?} → replenish; a sealing prekey ({key, created, signature},
 *                                                            signed over clairix-sealing-prekey-v1|key|created) replaces
 *                                                            the one held only when newer; replace: true makes the keys
 *                                                            given the whole pool (a phone restored from a recovery file,
 *                                                            whose old keys' secrets went with the old phone)
 *   PUT    /v1/listed         {listed}
 *   PUT    /v1/pass            {key|null}                  → the key contacts' passes are made with (32 bytes,
 *                                                            base64); the phone shares it only inside end-to-end
 *                                                            encrypted messages
 *   PUT    /v1/push            {endpoint|null}             → UnifiedPush endpoint to wake this device: in use at
 *                                                            once on a trusted host ({verified: true}), else a code
 *                                                            is pushed through it first ({verified: false})
 *   PUT    /v1/push/verify     {code}                      → the code that arrived by push: the endpoint goes live
 *   DELETE /v1/me                                          → wipe the mailbox
 *   POST   /v1/invites                                     → {code, expiresAt}: one registration, no secret, 7 days
 *   POST   /v1/tokens          {count, kind?}             → {tokens, expiresAt}: single-use tokens of one kind
 *                                                            ("delivery" unless given, "traffic" or "report"); the
 *                                                            relay keeps only their hashes, apart from the account
 *   POST   /v1/lookup          {number, pin?, claim}       → claim true: {bundle}, a contact's keys + one session
 *                                                            key and its claim ticket (listed only; past the claim
 *                                                            limits, the fallback); claim false: {identity}, the keys
 *                                                            alone. pin (the ed25519 fingerprint from a QR) also
 *                                                            unlocks an unlisted number. The number travels in the
 *                                                            body, never in a URL anything might log.
 *   PUT    /v1/transport       {key|null}                  → the phone's transport key (Ed25519). Requests signed
 *                                                            with it (X-Clairix-Key: transport) may only GET /v1/inbox,
 *                                                            POST /v1/ack and GET /v1/ws: a locked phone collects what
 *                                                            it cannot yet open, and the key reads and sends nothing
 *   GET    /v1/inbox                                       → waiting envelopes
 *   POST   /v1/ack             {ids}
 *   GET    /v1/ws                                          → live delivery (hibernatable WebSocket)
 *   GET    /v1/turn                                        → ICE servers for a call, with TURN credentials minted
 *                                                            for this request alone
 * ClairixCoin (each relay is its own coin community; balances live in the mailboxes). On unless COIN is
 * "off": then every route below answers 404, and GET / says coin: false, so the app shows no balance:
 *   GET    /v1/coin/balance                                → {balance, symbol: "AC"}
 *   POST   /v1/coin/pay        {to, pin, amount, txid?, credit?} → {txid, credit, balance, ok}: moves coins to a contact; pin is the
 *                                                            fingerprint of the payee's identity key, as a lookup
 *                                                            takes. A number that does not exist and a pin that does
 *                                                            not match are refused alike, before any coin moves. A
 *                                                            repeat with the same txid moves nothing twice. The payer's
 *                                                            ledger keeps the debit under txid, the payee's the credit
 *                                                            under credit (a second id the payer's phone chose), so the
 *                                                            two ledgers share no id
 *   GET    /v1/coin/history                                → {txs: [{id, amount, ts, direction, note?}]}, newest first:
 *                                                            the hour, never the moment, and no counterparty
 *   POST   /v1/admin/coin/mint {to, amount}                → {ok, balance}; X-Admin-Secret must match ADMIN_SECRET
 * The operator (no signature; X-Admin-Secret must match ADMIN_SECRET; a few tries a minute per address):
 *   DELETE /v1/admin/accounts/:number                      → {ok, number}: deletes the account as DELETE /v1/me
 *                                                            does, for a deletion requested by email
 *                                                            (scripts/delete-account.mjs)
 * The operator, signed by an operator identity (a registered identity whose key OPERATOR_KEYS lists) and with
 * X-Admin-Secret; off unless both are set; a few tries a minute per address (scripts/operator.mjs, docs/ABUSE.md):
 *   POST   /v1/admin/suspend   {number}                    → {ok, number, suspended, since, already}: the number
 *                                                            cannot sign in, its sockets close, its push endpoint
 *                                                            goes, and to everyone else it reads as a number that
 *                                                            does not exist (adminSuspend)
 *   POST   /v1/admin/unsuspend {number}                    → {ok, number, suspended: false, wasSuspended}
 * From LEGACY_CUTOFF (default 2027-01-01) the paths kept for apps before Beta 7 answer 426 and say to update
 * Clairix: the old kind of token ({count} at /v1/tokens, and spending one) and envelopes in format 2.
 * Federation (federation.ts): /v1/send and /v1/lookup take an optional "relay", the https address of the
 * relay the number is on. When it is another relay, this one forwards the request there, signed with its own
 * key (RELAY_KEY), and passes the answer back; the other relay never sees the phone. Other relays reach this
 * one through:
 *   POST   /v1/federate/send   {to, envelope, claim?, mix?, pass?} → as /v1/send (signed by the forwarding relay)
 *   POST   /v1/federate/lookup {number, pin?, claim}           → as /v1/lookup   (likewise)
 * Supporter (supporter.ts, docs/SUPPORTER.md). No identity on any of these; one that names an account is refused.
 * Nothing about an account changes: a code or purchase buys blind "supporter" credentials, and each credential
 * buys one batch of ordinary blind delivery tokens beyond the hourly allowance:
 *   POST   /v1/supporter/redeem {code, blinded}            → {kind: "supporter", epoch, key, evaluated, proof, expiresAt}:
 *                                                            up to 52 credentials for an unused code (404 unknown,
 *                                                            409 used); a few tries a minute per address, in memory
 *   POST   /v1/supporter/play  {productId, purchaseToken, blinded} → as redeem, for a Google Play purchase the relay
 *                                                            has checked with Google; each purchase once. 404 unless
 *                                                            PLAY_VERIFY is on and configured
 *   POST   /v1/supporter/tokens {blinded}                  → {kind: "delivery", ...}: up to 100 delivery tokens; spends
 *                                                            the Supporter credential in X-Clairix-Token
 *   POST   /v1/admin/supporter/codes {count}               → {codes}: mints 1-500 codes; X-Admin-Secret must match
 *                                                            CODES_ADMIN_SECRET (off while unset). The relay keeps
 *                                                            only each code's SHA-256 and whether it was used
 * Cron (every 10 minutes): WZDx work zones from WZDX_FEEDS into every known region.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof HttpError) {
        const res = json({ error: e.message }, e.status);
        if (e.retryAfter !== undefined) res.headers.set("retry-after", String(e.retryAfter));
        return res;
      }
      // A Durable Object being restarted (a deploy, an overloaded object) is
      // temporary: tell the client to try again rather than fail for good.
      const err = e as { retryable?: boolean; overloaded?: boolean };
      if (err?.retryable || err?.overloaded) {
        const res = json({ error: "Relay busy; try again" }, 503);
        res.headers.set("retry-after", "2");
        return res;
      }
      console.error(e);
      return json({ error: "Internal error" }, 500);
    }
  },

  async scheduled(controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      refreshWzdx(env, Math.floor(controller.scheduledTime / 1000)).catch((e) => {
        console.error("wzdx: refresh failed", e);
      }),
    );
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  if (request.method === "GET" && path === "/") {
    const key = await relayKey(env).catch((e) => {
      console.error("RELAY_KEY unusable", e);
      return null;
    });
    return json({
      service: "clairix-comms",
      version: API_VERSION,
      ok: true,
      note: "Clairix numbers are not phone numbers.",
      alternates: alternates(env),
      // Phones that connect through Tor reach the relay here instead.
      onion: onionAddress(env),
      // Other relays verify forwarded requests from this one with relayKey;
      // federation says which relays this one accepts them from.
      relayKey: key?.publicKey ?? null,
      federation: policyName(federationPolicy(env)),
      // The keys blind tokens of each kind are issued under this epoch
      // (tokens.ts). The same for every caller: a phone checks the proof that
      // comes with its tokens against these.
      tokens: await blindTokenKeys(env),
      // The key Supporter credentials are issued under, this year and next
      // (supporter.ts), the same for every caller, and whether Play purchases
      // are checked here.
      supporter: await supporterInfo(env).catch((e) => {
        console.error("supporter key unavailable", e);
        return null;
      }),
      // Whether this relay offers ClairixCoin (on unless COIN=off): the app
      // hides the balance and the Pay button when this is false.
      coin: coinOn(env),
    });
  }

  let bodyText = "";
  if (request.method !== "GET" && request.method !== "HEAD") {
    // Bounded before anything else looks at it: nothing the relay accepts is
    // near this big, and a body is read before the caller is authenticated.
    const text = await readTextCapped(request.body, request.headers.get("content-length"), MAX_BODY_BYTES);
    if (text === null) throw new HttpError(413, `Request body larger than ${MAX_BODY_BYTES} bytes`);
    bodyText = text;
  }

  if (request.method === "GET" && path === "/i") {
    const page = invitePage();
    return new Response(page, {
      headers: {
        "content-type": "text/html; charset=utf-8",
        "content-security-policy": await invitePageCsp(page),
        "x-frame-options": "DENY",
        "cache-control": "public, max-age=3600",
        "referrer-policy": "no-referrer",
        "x-content-type-options": "nosniff",
      },
    });
  }

  if (path === "/v1/register") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    // A few attempts a minute per address: the enrollment secret cannot be guessed by brute force.
    const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`register-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
    const allowed = await guard.rateOk("register", Mailbox.REGISTER_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many registration attempts; try again in a minute", allowed.retryAfter);
    return register(env, request, bodyText);
  }

  if (!path.startsWith("/v1/")) throw new HttpError(404, "Not found");

  // ── Anonymous: one token, no identity ─────────────────────────────────
  if (path === "/v1/send") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    return send(env, request, bodyText);
  }

  if (path === "/v1/report") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    return fileReport(env, request, bodyText);
  }

  // ── Supporter (supporter.ts; docs/SUPPORTER.md): no identity ──────────
  if (path === "/v1/supporter/redeem") return redeemCode(env, request, bodyText);
  if (path === "/v1/supporter/tokens") return supporterTokens(env, request, bodyText);
  if (path === "/v1/supporter/play") return redeemPlay(env, request, bodyText);

  // ── The operator, with the admin secret and no identity ───────────────
  if (path === "/v1/admin/supporter/codes") return mintCodes(env, request, bodyText);
  if (path === "/v1/admin/reports") {
    return adminReports(env, request, bodyText);
  }

  if (path.startsWith("/v1/traffic/")) {
    return trafficRoute(env, request, url, path, bodyText);
  }

  // ── Forwarded by another relay, signed with its key (federation.ts) ───
  if (path === "/v1/federate/send" || path === "/v1/federate/lookup") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    const other = await authenticateRelay(env, request, bodyText);
    if (path === "/v1/federate/send") {
      const allowed = await other.rateOk("fed-send", FED_SENDS_PER_MINUTE, FED_SENDS_BURST);
      if (!allowed.ok) throw new HttpError(429, "Too many envelopes from your relay; try again shortly", allowed.retryAfter);
      const body = parseSendBody(bodyText, isLoopbackDev(request));
      refuseLegacyEnvelope(env, body.envelope);
      if (!(await other.federatedSendOk(body.to, FED_SENDS_PER_TARGET_PER_MINUTE, FED_SENDS_PER_TARGET_BURST))) {
        // The bucket refills one envelope this often.
        throw new HttpError(429, "Too many envelopes from your relay to that number; try again shortly", Math.ceil(60 / FED_SENDS_PER_TARGET_PER_MINUTE));
      }
      // Allow-listed relays, and relays a phone here has written to lately,
      // share the mailbox's allowance for other relays; any other relay draws
      // on a small one of its own (mailbox.ts).
      const known = federationPolicy(env).mode === "allow" || (await other.writtenToWithin(WRITTEN_TO_DAYS));
      return enqueueFor(env, body.to, body.envelope, body.claim, body.urgent, known ? "known" : "stranger", body.alert, body.pass);
    }
    const allowed = await other.rateOk("fed-lookup", FED_LOOKUPS_PER_MINUTE, FED_LOOKUPS_BURST);
    if (!allowed.ok) throw new HttpError(429, "Too many lookups from your relay; try again in a minute", allowed.retryAfter);
    // Per-registrable-domain cap: prevents wildcard-DNS Sybil attacks where
    // *.attacker.com generates unlimited fresh per-origin buckets.
    // Platform tenants (tenant.workers.dev, tenant.github.io, …) each get
    // their own 3-label bucket key so they are not throttled against each other.
    const rawOrigin = request.headers.get(FEDERATION_HEADERS.origin) ?? "";
    const domain = registrableDomain(rawOrigin);
    const domainBox = env.MAILBOX.get(env.MAILBOX.idFromName(await federationDomainObjectName(domain)));
    const domainAllowed = await domainBox.rateOk("fed-lookup", FED_LOOKUPS_PER_DOMAIN_PER_MINUTE, FED_LOOKUPS_PER_DOMAIN_BURST);
    if (!domainAllowed.ok) throw new HttpError(429, "Too many lookups from your relay domain; try again shortly", domainAllowed.retryAfter);
    const body = parseLookupBody(bodyText, isLoopbackDev(request));
    return answerLookup(env, body.target, body.pin, body.claim, async () => body.oneTime && (await other.federatedClaimOk(FED_CLAIMS_PER_HOUR, FED_CLAIMS_BURST)));
  }

  // ── The operator: the admin secret and no identity (adminDeleteAccount) ──
  if (path.startsWith(ADMIN_ACCOUNTS_PREFIX)) {
    if (request.method !== "DELETE") throw new HttpError(405, "DELETE only");
    return adminDeleteAccount(env, request, path);
  }

  // ── Authenticated ─────────────────────────────────────────────────────
  const signed = await parseSigned(request, bodyText);
  const me = env.MAILBOX.get(env.MAILBOX.idFromName(signed.number));
  // Signed with the transport key, a request may only collect envelopes: the
  // phone is locked and its identity key out of reach.
  const transport = request.headers.get("x-clairix-key") === "transport";
  if (transport && !TRANSPORT_ROUTES.has(`${request.method} ${path}`)) {
    throw new HttpError(403, "The transport key only fetches, acknowledges and streams envelopes");
  }
  const profile = await me.authenticate(signed.payload, signed.signature, signed.nonce, signed.ts, transport);
  // Told to the number's own key holder only: the signature checked first.
  if (profile === SUSPENDED) throw new HttpError(403, suspendedMessage(env));
  if (!profile) throw new HttpError(401, "Signature rejected");
  // An account from before keys were recorded as their owner's records them now.
  if (!profile.keysClaimed) {
    await claimKeys(env, profile, false);
    await me.markKeysClaimed();
  }

  if (path === "/v1/me" && request.method === "GET") {
    return json({ profile: publicProfile(profile), oneTimeKeys: await me.oneTimeKeyCount(), pass: await me.hasPassKey() });
  }

  if (path === "/v1/pass" && request.method === "PUT") {
    const body = parseJson<{ key?: unknown }>(bodyText);
    if (body.key !== null && (typeof body.key !== "string" || !PASS_RE.test(body.key))) throw new HttpError(400, "key must be 32 bytes of base64, or null");
    const allowed = await me.allowanceOk("pass", PASS_KEYS_PER_HOUR, PASS_KEYS_BURST);
    if (!allowed.ok) throw new HttpError(429, "Too many pass keys; try again later", allowed.retryAfter);
    await me.setPassKey(body.key);
    return json({ ok: true });
  }

  if (path === "/v1/me" && request.method === "DELETE") {
    await releaseKeys(env, profile);
    await me.wipe();
    return json({ ok: true });
  }

  if (path === "/v1/invites" && request.method === "POST") {
    const allowed = await me.allowanceOk("invite", Mailbox.INVITE_PER_HOUR, Mailbox.INVITE_BURST);
    if (!allowed.ok) throw new HttpError(429, "Too many invites today; try again later", allowed.retryAfter);
    const code = randomInviteCode();
    const expiresAt = Date.now() + INVITE_TTL_MS;
    await env.MAILBOX.get(env.MAILBOX.idFromName(`invite:${code}`)).createInvite(profile.number, expiresAt);
    return json({ code, expiresAt }, 201);
  }

  if (path === "/v1/keys" && request.method === "PUT") {
    const body = parseJson<{ oneTimeKeys?: unknown; fallback?: unknown; sealingPrekey?: unknown; replace?: unknown }>(bodyText);
    const oneTimeKeys = await checkedKeys(profile.ed25519, body.oneTimeKeys);
    const fallback = body.fallback === undefined || body.fallback === null ? null : (await checkedKeys(profile.ed25519, [body.fallback]))[0] ?? null;
    const sealingPrekey = await checkedSealingPrekey(profile.ed25519, body.sealingPrekey);
    if (body.replace !== undefined && typeof body.replace !== "boolean") throw new HttpError(400, "replace must be a boolean");
    return json(await me.putKeys(oneTimeKeys, fallback, sealingPrekey, body.replace === true));
  }

  if (path === "/v1/transport" && request.method === "PUT") {
    const body = parseJson<{ key?: unknown }>(bodyText);
    const key = checkedTransportKey(body.key, profile.ed25519);
    await me.setTransportKey(key);
    return json({ ok: true });
  }

  if (path === "/v1/listed" && request.method === "PUT") {
    const body = parseJson<{ listed?: unknown }>(bodyText);
    if (typeof body.listed !== "boolean") throw new HttpError(400, "listed must be a boolean");
    await me.setListed(body.listed);
    return json({ ok: true, listed: body.listed });
  }

  if (path === "/v1/push" && request.method === "PUT") {
    const body = parseJson<{ endpoint?: unknown }>(bodyText);
    if (body.endpoint === null || body.endpoint === undefined) {
      await me.setPush(null);
      return json({ ok: true, verified: false });
    }
    const check = checkPushEndpoint(body.endpoint, env.PUSH_HOSTS, pushHostsOnly(env.PUSH_HOSTS_ONLY));
    if (!check.ok) throw new HttpError(400, check.reason);
    const allowed = await me.allowanceOk("push", Mailbox.PUSH_PER_HOUR, Mailbox.PUSH_BURST);
    if (!allowed.ok) throw new HttpError(429, "Too many push endpoints; try again later", allowed.retryAfter);
    if (check.trusted) {
      await me.setPush(check.url);
      return json({ ok: true, verified: true });
    }
    // Any other host is woken only once the phone proves the endpoint reaches
    // it, so the relay cannot be pointed at arbitrary URLs to post to.
    const code = randomToken();
    await me.setPendingPush(check.url, await tokenHash(code));
    const status = await postPush(check.url, `${PUSH_CHALLENGE_PREFIX}${code}`);
    if (status < 200 || status >= 300) {
      throw new HttpError(400, status === 0 ? "The push endpoint could not be reached" : `The push endpoint refused the check (HTTP ${status})`);
    }
    return json({ ok: true, verified: false }, 202);
  }

  if (path === "/v1/push/verify" && request.method === "PUT") {
    const body = parseJson<{ code?: unknown }>(bodyText);
    if (typeof body.code !== "string" || !DELIVERY_TOKEN_RE.test(body.code)) throw new HttpError(400, "code required");
    if (!(await me.verifyPush(await tokenHash(body.code)))) throw new HttpError(403, "That code does not match the pending push endpoint");
    return json({ ok: true, verified: true });
  }

  if (path === "/v1/tokens" && request.method === "POST") {
    const body = parseJson<{ count?: unknown; kind?: unknown; blinded?: unknown }>(bodyText);
    let kind: TokenKind = "delivery";
    if (body.kind !== undefined && body.kind !== null) {
      if (!isTokenKind(body.kind)) throw new HttpError(400, "kind must be delivery, traffic or report");
      kind = body.kind;
    }
    const spec = tokenAllowance(env, kind);
    if (body.blinded !== undefined) {
      // Blind tokens (tokens.ts): the relay sees blinded elements only, and
      // keeps nothing about what it issued.
      const blinded = parseBlinded(body.blinded);
      const taken = await me.allowance(`tokens-${kind}`, spec.perHour, spec.burst, blinded.length);
      if (taken.granted === 0) throw new HttpError(429, `No more ${kind} tokens this hour`, taken.retryAfter);
      const issued = await issueBlind(env, kind, blinded.slice(0, taken.granted));
      if (!issued) throw new HttpError(400, "blinded must hold valid ristretto255 elements");
      return json({ kind, ...issued, expiresAt: epochExpiry(issued.epoch) });
    }
    // The old kind of token, which apps before Beta 6 ask for: the relay makes
    // it and could match it to this account. Refused from LEGACY_CUTOFF on.
    refuseLegacy(env);
    const want = body.count;
    if (typeof want !== "number" || !Number.isInteger(want) || want < 1 || want > DELIVERY_TOKEN_BATCH_MAX) {
      throw new HttpError(400, `count must be a whole number from 1 to ${DELIVERY_TOKEN_BATCH_MAX}`);
    }
    const taken = await me.allowance(`tokens-${kind}`, spec.perHour, spec.burst, want);
    if (taken.granted === 0) throw new HttpError(429, `No more ${kind} tokens this hour`, taken.retryAfter);
    const tokens = Array.from({ length: taken.granted }, () => randomToken());
    const hashes = await Promise.all(tokens.map((t) => tokenHash(t)));
    const expiresAt = tokenBatchExpiry(Date.now());
    // Stored by hash in shard objects that never learn the account: the only
    // link between this caller and these tokens is this response.
    await Promise.all(
      [...groupByShard(hashes, kind)].map(([name, list]) => env.MAILBOX.get(env.MAILBOX.idFromName(name)).addTokens(list, expiresAt)),
    );
    return json({ tokens, expiresAt, kind });
  }

  if (path === "/v1/lookup" && request.method === "POST") {
    const body = parseLookupBody(bodyText, isLoopbackDev(request));
    if (body.relay !== null && !isOwnOrigin(env, request, body.relay)) {
      // A number on another relay: asked through this one, which signs the
      // question as itself, so the other relay never learns who is asking.
      const allowed = await me.rateOk("lookup", Mailbox.LOOKUP_LIMIT);
      if (!allowed.ok) throw new HttpError(429, "Too many lookups; try again in a minute", allowed.retryAfter);
      // The phone's own limits on one-time keys hold for contacts elsewhere
      // too; past them, the other relay is asked for the fallback key only.
      const oneTime = body.claim && (await me.mayClaimFrom(`${body.relay} ${body.target}`));
      const res = await forward(env, request, body.relay, "/v1/federate/lookup", { number: body.target, pin: body.pin, claim: body.claim, oneTime });
      if (res.status !== 200) throw otherRelayError(res);
      await noteWrittenTo(env, body.relay);
      if (body.claim) {
        const bundle = checkedBundle(res.json.bundle, body.target);
        if (!bundle) throw new HttpError(502, "The other relay answered with something that is not a key bundle");
        return json({ bundle });
      }
      const identity = checkedIdentity(res.json.identity, body.target);
      if (!identity) throw new HttpError(502, "The other relay answered with something that is not an identity");
      return json({ identity });
    }
    return lookup(env, me, profile, body.target, body.pin, body.claim);
  }

  if (path === "/v1/inbox" && request.method === "GET") {
    return json({ envelopes: await me.inbox() });
  }

  if (path === "/v1/ack" && request.method === "POST") {
    const body = parseJson<{ ids?: unknown }>(bodyText);
    if (!Array.isArray(body.ids)) throw new HttpError(400, "ids must be an array");
    const ids = body.ids.filter((x): x is string => typeof x === "string").slice(0, 500);
    return json({ removed: await me.ack(ids) });
  }

  if (path === "/v1/ws" && request.method === "GET") {
    return me.fetch(request);
  }

  if (path === "/v1/turn" && request.method === "GET") {
    const allowed = await me.rateOk("turn", Mailbox.TURN_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many requests; try again in a minute", allowed.retryAfter);
    const hourly = await me.allowanceOk("turn", TURN_PER_HOUR, TURN_BURST);
    if (!hourly.ok) throw new HttpError(429, "Too many call setups this hour; try again later", hourly.retryAfter);
    // Minted for this request and handed to this caller only: no two phones, and
    // no two calls, ever share TURN credentials.
    const servers = await iceServers(env);
    // "turn" tells the app whether calls can be relayed when the phones cannot reach each other directly.
    return json({ iceServers: servers, turn: servers.some((s) => s.urls.some((u) => u.startsWith("turn"))) });
  }

  // ── ClairixCoin ─────────────────────────────────────────────────────────
  if (path === "/v1/coin/balance" && request.method === "GET") {
    requireCoin(env);
    return json({ balance: await me.getBalance(), symbol: COIN_SYMBOL });
  }

  if (path === "/v1/coin/pay" && request.method === "POST") {
    requireCoin(env);
    const allowed = await me.rateOk("pay", Mailbox.PAY_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many payments; try again in a minute", allowed.retryAfter);
    const body = parseJson<{ to?: unknown; pin?: unknown; amount?: unknown; txid?: unknown; credit?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be a Clairix number");
    if (to === profile.number) throw new HttpError(400, "You cannot pay yourself");
    const pin = coinPin(body.pin);
    const amount = coinAmount(body.amount);
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    // The phone names the payment, so a request it had to repeat (the answer
    // lost on the network) is the same payment: the debit and the credit are
    // each made once per txid, and a repeat gets the balance as it stands.
    const named = typeof body.txid === "string" && TXID_RE.test(body.txid);
    const txid = named ? (body.txid as string).toLowerCase() : crypto.randomUUID();
    // The payee's mailbox keeps the credit under another id ([coinCreditId]),
    // which the payer's phone also chose and tells the payee only inside the
    // end-to-end encrypted notice, so the two ledgers share no id.
    const credit = coinCreditId(body.credit, named, txid);
    // The payer shows it already knows whom it pays: the fingerprint of the
    // payee's identity key, which its phone pinned when it added the contact,
    // as a lookup of an unlisted number asks. A number that does not exist
    // and a pin that does not match get the same answer, before any coin
    // moves and whatever the payer holds, so paying tells nobody whether a
    // number is real; and coins go only to the key the payer's phone knows,
    // never to whoever was given the number since.
    const payee = await them.profile();
    if (!payee || !timingSafeEqual(await fingerprint(payee.ed25519), pin)) throw new HttpError(400, "Payment refused");
    // A suspended payee is refused the same way, before any coin moves.
    if (await them.suspension()) throw new HttpError(400, "Payment refused");
    const debited = await me.debit(amount, txid);
    if (!debited.ok) throw new HttpError(400, debited.reason ?? "Payment refused");
    let credited: number | null;
    try {
      credited = await them.credit(amount, credit);
    } catch (e) {
      await refundPayment(me, amount, txid, e);
      throw e;
    }
    if (credited === null) {
      // The payee's account was deleted between the check and the credit.
      await refundPayment(me, amount, txid, "no such number");
      throw new HttpError(400, "Payment refused");
    }
    return json({ txid, credit, balance: debited.balance, ok: true });
  }

  if (path === "/v1/coin/history" && request.method === "GET") {
    requireCoin(env);
    const raw = Number(url.searchParams.get("limit") ?? "");
    const limit = Number.isFinite(raw) && raw > 0 ? Math.min(100, Math.floor(raw)) : 50;
    const txs: CoinTx[] = await me.txHistory(limit);
    return json({ txs });
  }

  if (path === "/v1/admin/coin/mint" && request.method === "POST") {
    requireCoin(env);
    const secret = requireSecret(env, "ADMIN_SECRET");
    const given = request.headers.get("x-admin-secret") ?? "";
    if (!(await secretEquals(given, secret))) throw new HttpError(403, "Admin secret rejected");
    const body = parseJson<{ to?: unknown; amount?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be a Clairix number");
    const amount = coinAmount(body.amount);
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    if (!(await them.profile())) throw new HttpError(404, "No such Clairix number on this relay");
    // Recorded as a mint, in the same words for every mint: not which phone minted it.
    const balance = await them.credit(amount, crypto.randomUUID(), true);
    if (balance === null) throw new HttpError(404, "No such Clairix number on this relay");
    return json({ ok: true, balance });
  }

  // ── The operator: a signed operator identity and the admin secret ─────
  if ((path === "/v1/admin/suspend" || path === "/v1/admin/unsuspend") && request.method === "POST") {
    return adminSuspend(env, request, profile, bodyText, path === "/v1/admin/suspend");
  }

  throw new HttpError(404, "Not found");
}

/**
 * POST /v1/admin/suspend and POST /v1/admin/unsuspend {number}: the operator
 * stops a number that abuses this relay, or lets it back (the runbook is
 * docs/ABUSE.md; scripts/operator.mjs makes the requests). Three things must
 * hold, so neither the secret nor the operator's key is enough alone: the
 * request is signed by an identity registered here, as every request in this
 * section is (route() has checked it, nonce and all); that identity's key is
 * one OPERATOR_KEYS lists; and X-Admin-Secret matches ADMIN_SECRET, compared in
 * constant time, as the coin mint compares it. Off while either setting is
 * unset, and a few tries a minute per address, as the other admin routes.
 *
 * What suspension does is Mailbox.suspend: the number cannot sign in, its
 * sockets close, its push endpoint goes, and to everyone else it reads as a
 * number that does not exist. Lifting it lets the phone back in with what was
 * queued meanwhile, less what expired.
 */
async function adminSuspend(env: Env, request: Request, caller: Profile, bodyText: string, suspend: boolean): Promise<Response> {
  const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`admin-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
  const allowed = await guard.rateOk("admin", Mailbox.REGISTER_LIMIT);
  if (!allowed.ok) throw new HttpError(429, "Too many admin requests; try again in a minute", allowed.retryAfter);
  const secret = requireSecret(env, "ADMIN_SECRET");
  if (!(await secretEquals(request.headers.get("x-admin-secret") ?? "", secret))) throw new HttpError(403, "Admin secret rejected");
  const operators = operatorKeys(env);
  if (operators.length === 0) throw new HttpError(503, "Relay setting OPERATOR_KEYS is not configured");
  if (!operators.some((spellings) => spellings.includes(caller.ed25519))) {
    throw new HttpError(403, "This identity is not one of this relay's operators (OPERATOR_KEYS)");
  }
  const body = parseJson<{ number?: unknown }>(bodyText);
  const number = typeof body.number === "string" ? normaliseNumber(body.number) : null;
  if (!number) throw new HttpError(400, "number must be a Clairix number");
  // Suspended, the operator's identity could not sign the request that lifts it.
  if (suspend && number === caller.number) throw new HttpError(400, "An operator identity cannot suspend itself");
  const them = env.MAILBOX.get(env.MAILBOX.idFromName(number));
  if (!(await them.profile())) throw new HttpError(404, "No such Clairix number on this relay");
  if (suspend) {
    const done = await them.suspend();
    return json({ ok: true, number, suspended: true, since: done.since, already: done.already });
  }
  const was = await them.unsuspend();
  return json({ ok: true, number, suspended: false, wasSuspended: was });
}

/**
 * The operator identities OPERATOR_KEYS lists: Ed25519 public keys in
 * standard base64, separated by commas or spaces, each as both of its
 * spellings (with and without padding) so it matches however the identity
 * registered it. Anything that is not such a key is left out.
 */
function operatorKeys(env: Pick<Env, "OPERATOR_KEYS">): [string, string][] {
  return (env.OPERATOR_KEYS ?? "")
    .split(/[\s,]+/)
    .map((k) => keySpellings(k.trim()))
    .filter((s): s is [string, string] => s !== null);
}

/**
 * What a suspended number's own phone is told, on every signed request: that
 * the relay's operator suspended it, what that stops, and where to appeal
 * (OPERATOR_CONTACT, when the operator set one).
 */
function suspendedMessage(env: Pick<Env, "OPERATOR_CONTACT">): string {
  const contact = operatorContact(env);
  return "This Clairix number is suspended on this relay by its operator: it cannot sign in, collect messages or get " +
    "new tokens here, and nothing sent to it is delivered. " +
    (contact ? `To appeal, write to ${contact}.` : "To appeal, write to whoever runs this relay.");
}

/** OPERATOR_CONTACT: one line of at most 120 printable characters, or null. */
function operatorContact(env: Pick<Env, "OPERATOR_CONTACT">): string | null {
  const value = (env.OPERATOR_CONTACT ?? "").trim();
  return value.length > 0 && value.length <= 120 && /^[\x20-\x7e]+$/.test(value) ? value : null;
}

/**
 * The legacy paths kept for apps before 3.0.0 Beta 7 end at the start of this
 * UTC day unless LEGACY_CUTOFF names another (YYYY-MM-DD): the old kind of
 * token, which the relay makes and so could match to the account it went to
 * (apps before Beta 6 ask for it), and envelopes in format 2, sealed to the
 * recipient's long-term key with a sender signature anyone can check (apps
 * before Beta 7 send them, and newer apps send them only to such apps).
 * Supported versions (SECURITY.md) use blind tokens and format 3 between
 * themselves; the date is two months after Beta 7 and Beta 8 came out, time
 * for the users of older ones to update.
 */
const LEGACY_CUTOFF_DEFAULT = "2027-01-01";

/** When the legacy paths end, in epoch millis: LEGACY_CUTOFF when it is a date, else the default. */
function legacyCutoffMs(env: Pick<Env, "LEGACY_CUTOFF">): number {
  const parse = (s: string): number | null => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim());
    if (!m) return null;
    const t = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    // Date.UTC rolls 2027-02-30 into March; a day that does not exist is not a date.
    return new Date(t).toISOString().slice(0, 10) === s.trim() ? t : null;
  };
  return parse(env.LEGACY_CUTOFF ?? "") ?? (parse(LEGACY_CUTOFF_DEFAULT) as number);
}

/** What a legacy request is told from the cutoff on: 426, and to update. */
const LEGACY_MESSAGE =
  "Relays stopped carrying the old tokens and envelopes of Clairix versions before 3.0.0 Beta 7. Update Clairix from " +
  "clairix.app/download. If this phone is up to date, the person you are writing to needs to update theirs before " +
  "they can receive your messages.";

/** Refuses a legacy request once the cutoff has passed (426 Upgrade Required). */
function refuseLegacy(env: Pick<Env, "LEGACY_CUTOFF">): void {
  if (Date.now() >= legacyCutoffMs(env)) throw new HttpError(426, LEGACY_MESSAGE);
}

/**
 * Refuses an envelope in format 2 (its first byte, the format, is 2) once the
 * cutoff has passed. Checked on the envelope alone, before a token is spent or
 * a mailbox is asked, so the answer is the same for every number.
 */
function refuseLegacyEnvelope(env: Pick<Env, "LEGACY_CUTOFF">, envelope: string): void {
  if (envelope.length < 4 || Date.now() < legacyCutoffMs(env)) return;
  let first: number | undefined;
  try {
    first = base64ToBytes(envelope.slice(0, 4))[0];
  } catch {
    return; // not base64 after all: the mailbox stores it as the bytes it is
  }
  if (first === ENVELOPE_FORMAT_2) throw new HttpError(426, LEGACY_MESSAGE);
}

/** The first byte of an envelope in format 2 (comms-crypto ENVELOPE_V2). */
const ENVELOPE_FORMAT_2 = 2;

/**
 * DELETE /v1/admin/accounts/:number: the operator deletes an account on a
 * request by email (scripts/delete-account.mjs; the runbook is README.md,
 * "Deleting an account on request"). It does what the owner's own DELETE
 * /v1/me does: the keys the account claimed are released and its mailbox
 * (queued envelopes, push endpoint, pass key, coin balance and history) is
 * wiped. Guarded like the coin mint above, by the timing-safe X-Admin-Secret
 * check, and off unless ADMIN_SECRET is set; unlike the mint it takes no signed
 * request, because the operator holds no key of the account's to sign with.
 * Each address gets a few tries a minute, as registration does, so the secret
 * cannot be guessed by brute force. Reached before signatures are parsed (see
 * route()).
 */
async function adminDeleteAccount(env: Env, request: Request, path: string): Promise<Response> {
  const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`admin-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
  const allowed = await guard.rateOk("admin", Mailbox.REGISTER_LIMIT);
  if (!allowed.ok) throw new HttpError(429, "Too many admin requests; try again in a minute", allowed.retryAfter);
  const secret = requireSecret(env, "ADMIN_SECRET");
  const given = request.headers.get("x-admin-secret") ?? "";
  if (!(await secretEquals(given, secret))) throw new HttpError(403, "Admin secret rejected");
  let raw: string;
  try {
    raw = decodeURIComponent(path.slice(ADMIN_ACCOUNTS_PREFIX.length));
  } catch {
    throw new HttpError(400, "Not a Clairix number");
  }
  const number = normaliseNumber(raw);
  if (!number) throw new HttpError(400, "Not a Clairix number");
  const them = env.MAILBOX.get(env.MAILBOX.idFromName(number));
  const profile = await them.profile();
  if (!profile) throw new HttpError(404, "No such Clairix number on this relay");
  await releaseKeys(env, profile);
  await them.wipe();
  return json({ ok: true, number });
}

const ADMIN_ACCOUNTS_PREFIX = "/v1/admin/accounts/";

/**
 * A contact's keys, by Clairix number. Unlisted numbers are only handed out
 * to a caller who already holds the owner's identity key (from a QR scan) and
 * proves it with its fingerprint; the pin is checked before any key is
 * claimed, so a wrong pin neither reveals the bundle nor consumes a one-time
 * key. With [claim], one one-time key comes with the keys: each caller may
 * take only a few from each number and so many in all, and each number hands
 * out only so many; past any limit the fallback key is offered, which starts
 * a session just as well and drains nothing. Who looked up whom is never
 * stored, and the number asked about is in the request body, not its URL.
 */
async function lookup(env: Env, me: MailboxStub, profile: Profile, target: string, pin: string | null, claim: boolean): Promise<Response> {
  const allowed = await me.rateOk("lookup", Mailbox.LOOKUP_LIMIT);
  if (!allowed.ok) throw new HttpError(429, "Too many lookups; try again in a minute", allowed.retryAfter);
  return answerLookup(env, target, pin, claim, async () => target !== profile.number && (await me.mayClaimFrom(target)));
}

/**
 * The answer to a lookup of [target] here, for a phone on this relay or one
 * asking through another: [mayClaim] says whether the asker is within its
 * own limit for one-time keys.
 */
async function answerLookup(env: Env, target: string, pin: string | null, claim: boolean, mayClaim: () => Promise<boolean>): Promise<Response> {
  const them = env.MAILBOX.get(env.MAILBOX.idFromName(target));
  let unlisted = false;
  if (pin !== null) {
    const p = await them.profile();
    if (!p || !timingSafeEqual(await fingerprint(p.ed25519), pin)) throw new HttpError(404, "No such Clairix number, or it is unlisted");
    unlisted = true;
  }
  if (claim) {
    const bundle = await them.bundle(!unlisted, await mayClaim());
    if (!bundle) throw new HttpError(404, "No such Clairix number, or it is unlisted");
    return json({ bundle });
  }
  // Who holds a number, without claiming one of their one-time keys: used to
  // confirm the sender of a first message.
  const identity = await them.identity(!unlisted);
  if (!identity) throw new HttpError(404, "No such Clairix number, or it is unlisted");
  return json({ identity });
}

/** The blinded elements of a blind token request: 1 to BLIND_BATCH_MAX base64url ristretto255 elements. */
function parseBlinded(raw: unknown): Uint8Array[] {
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > BLIND_BATCH_MAX) {
    throw new HttpError(400, `blinded must be a list of 1 to ${BLIND_BATCH_MAX} elements`);
  }
  return raw.map((e) => {
    if (typeof e !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(e)) throw new HttpError(400, "blinded elements are 32 bytes of base64url");
    return base64ToBytes(e);
  });
}

/** What GET / says about blind tokens: the epoch and each kind's key in it. */
/**
 * The blind-token keys of this epoch, and of the next: a phone pins the next
 * epoch's long before it is issued any tokens under them, on a request that
 * names nobody and at a time of its own choosing, so a relay cannot hand one
 * phone keys of its own at the moment it asks for tokens.
 */
async function blindTokenKeys(env: Env): Promise<{ version: number; epoch: number; epochMs: number; keys: Record<TokenKind, string>; next: { epoch: number; keys: Record<TokenKind, string> } } | null> {
  try {
    const epoch = epochOf(Date.now());
    const keysFor = async (e: number) => {
      const keys = {} as Record<TokenKind, string>;
      for (const kind of Object.keys(TOKEN_KINDS) as TokenKind[]) keys[kind] = await tokenPublicKey(env, kind, e);
      return keys;
    };
    return { version: 2, epoch, epochMs: TOKEN_EPOCH_MS, keys: await keysFor(epoch), next: { epoch: epoch + 1, keys: await keysFor(epoch + 1) } };
  } catch (e) {
    console.error("token keys unavailable", e);
    return null;
  }
}

const IDENTITY_HEADERS = ["x-clairix-number", "x-clairix-sig", "x-clairix-nonce", "x-clairix-ts"] as const;

/**
 * Spends the token an anonymous request carries, of the [kind] the route
 * needs, before anything else happens: each one buys exactly one attempt. A
 * request that also names an identity is refused outright, so a client bug
 * cannot quietly undo the anonymity.
 */
async function spendToken(env: Env, request: Request, kind: TokenKind): Promise<void> {
  for (const h of IDENTITY_HEADERS) {
    if (request.headers.has(h)) throw new HttpError(400, "This request carries no identity; it spends a token");
  }
  const token = request.headers.get("x-clairix-token");
  if (token === null || token.length === 0) throw new HttpError(401, `A ${kind} token is required`);
  if (token.startsWith("2.")) {
    // A blind token: checked against the key that made it, then recorded as
    // spent in the same shard objects, which still learn no account.
    const spent = await checkBlindToken(env, kind, token);
    if (!spent || !(await env.MAILBOX.get(env.MAILBOX.idFromName(tokenShardName(spent.hash, kind))).spendBlindToken(spent.hash, spent.expiresAt))) {
      throw new HttpError(403, `Token rejected: unknown, expired, already used, or not a ${kind} token`);
    }
    return;
  }
  if (!DELIVERY_TOKEN_RE.test(token)) throw new HttpError(403, "Token rejected");
  // The old kind, from apps before Beta 6: no longer spent from LEGACY_CUTOFF on.
  refuseLegacy(env);
  const hash = await tokenHash(token);
  if (!(await env.MAILBOX.get(env.MAILBOX.idFromName(tokenShardName(hash, kind))).redeemToken(hash))) {
    throw new HttpError(403, `Token rejected: unknown, expired, already used, or not a ${kind} token`);
  }
}

const MAX_PROBE_SAMPLES = 60;
const MAX_TYPICAL_SEGS = 500;
/** Phone clocks drift; a sample stamped slightly in the future is still a fresh one. */
const PROBE_CLOCK_SKEW_SEC = 60;
const INCIDENT_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

type MailboxStub = DurableObjectStub<Mailbox>;

/**
 * Traffic. Every request spends a "traffic" token (a new report, a "report"
 * token) and names no identity; what a phone may do is bounded by the tokens
 * its account is issued. Probes, reports and votes carry a trip pseudonym
 * the phone made up for the drive, which is what the region's object counts
 * vehicles and votes by.
 */
async function trafficRoute(env: Env, request: Request, url: URL, path: string, bodyText: string): Promise<Response> {
  const now = Math.floor(Date.now() / 1000);
  const regionStub = (region: string) => env.TRAFFIC.get(env.TRAFFIC.idFromName(`traffic:${region}`));

  if (path === "/v1/traffic/probe" && request.method === "POST") {
    await spendToken(env, request, "traffic");
    const body = parseJson<{ region?: unknown; trip?: unknown; samples?: unknown }>(bodyText);
    const region = trafficRegion(body.region);
    const trip = tripPseudonym(body.trip);
    if (!Array.isArray(body.samples)) throw new HttpError(400, "samples must be an array");
    if (body.samples.length > MAX_PROBE_SAMPLES) throw new HttpError(400, `at most ${MAX_PROBE_SAMPLES} samples per request`);
    const samples: ProbeSample[] = [];
    for (const raw of body.samples) {
      const s = probeSample(raw);
      // Stale samples are normal after a phone was offline; they are dropped, not an error.
      if (s.t < now - PROBE_MAX_AGE_SEC || s.t > now + PROBE_CLOCK_SKEW_SEC) continue;
      samples.push(s);
    }
    const accepted = samples.length === 0 ? 0 : await regionStub(region).ingestProbes(region, trip, samples, now);
    return json({ ok: true, accepted });
  }

  if (path === "/v1/traffic/live" && request.method === "GET") {
    await spendToken(env, request, "traffic");
    const region = trafficRegion(url.searchParams.get("region"));
    return json(await regionStub(region).live(now));
  }

  // The segment ids of a route say where it goes, so the app sends them in a
  // POST body, which no request log keeps. GET with ?segs= is still answered
  // for one release, for apps from before the change.
  if (path === "/v1/traffic/typical" && (request.method === "POST" || request.method === "GET")) {
    await spendToken(env, request, "traffic");
    let regionRaw: unknown;
    let segsRaw: unknown;
    if (request.method === "POST") {
      const body = parseJson<{ region?: unknown; segs?: unknown }>(bodyText);
      regionRaw = body.region;
      segsRaw = body.segs;
      if (!Array.isArray(segsRaw) || segsRaw.some((s) => typeof s !== "string")) throw new HttpError(400, "segs must be an array of strings");
    } else {
      regionRaw = url.searchParams.get("region");
      segsRaw = (url.searchParams.get("segs") ?? "").split(",");
    }
    const region = trafficRegion(regionRaw);
    const segs = (segsRaw as string[]).map((s) => s.trim()).filter((s) => s.length > 0);
    if (segs.length > MAX_TYPICAL_SEGS) throw new HttpError(400, `at most ${MAX_TYPICAL_SEGS} segments per request`);
    for (const seg of segs) if (!SEGMENT_RE.test(seg)) throw new HttpError(400, `bad segment ${seg.slice(0, 40)}`);
    if (segs.length === 0) return json({ segments: {} });
    return json(await regionStub(region).typical(segs));
  }

  if (path === "/v1/traffic/incidents" && request.method === "GET") {
    await spendToken(env, request, "traffic");
    const parsed = parseBBoxParam(url.searchParams.get("bbox"));
    if ("error" in parsed) throw new HttpError(400, parsed.error);
    return jsonText(await env.INCIDENTS.get(env.INCIDENTS.idFromName(INCIDENTS_NAME)).incidents(parsed.bbox, now));
  }

  if (path === "/v1/traffic/report" && request.method === "POST") {
    await spendToken(env, request, "report");
    const body = parseJson<{ region?: unknown; trip?: unknown; type?: unknown; lat?: unknown; lon?: unknown; seg?: unknown }>(bodyText);
    const region = trafficRegion(body.region);
    const trip = tripPseudonym(body.trip);
    if (typeof body.type !== "string" || !(INCIDENT_TYPES as readonly string[]).includes(body.type)) {
      throw new HttpError(400, `type must be one of ${INCIDENT_TYPES.join(", ")}`);
    }
    const { lat, lon } = latLon(body.lat, body.lon);
    let seg: string | null = null;
    if (body.seg !== undefined && body.seg !== null) {
      if (typeof body.seg !== "string" || !SEGMENT_RE.test(body.seg)) throw new HttpError(400, "seg must be \"<wayId>:<0|1>\" or null");
      seg = body.seg;
    }
    const incident = await regionStub(region).report(region, trip, body.type as IncidentType, lat, lon, seg, now);
    return json({ incident });
  }

  if (path === "/v1/traffic/report/vote" && request.method === "POST") {
    const body = parseJson<{ region?: unknown; trip?: unknown; id?: unknown; still?: unknown }>(bodyText);
    const region = trafficRegion(body.region);
    const trip = tripPseudonym(body.trip);
    if (typeof body.id !== "string" || !INCIDENT_ID_RE.test(body.id)) throw new HttpError(400, "id must be an incident id");
    if (typeof body.still !== "boolean") throw new HttpError(400, "still must be a boolean");
    // A trip is whatever name the phone gives it, so one phone can be many
    // voters; voting a report away costs a report token, which are scarce.
    await spendToken(env, request, body.still ? "traffic" : "report");
    const incident = await regionStub(region).vote(region, trip, body.id, body.still, now);
    return json({ incident });
  }

  throw new HttpError(404, "Not found");
}

function trafficRegion(raw: unknown): string {
  if (typeof raw !== "string" || !REGION_RE.test(raw)) throw new HttpError(400, "region must be a map region slug, e.g. north-america-us-ohio");
  if (!KNOWN_REGIONS.has(raw)) throw new HttpError(400, "region is not one Clairix has a map for");
  return raw;
}

function tripPseudonym(raw: unknown): string {
  if (typeof raw !== "string" || !TRIP_RE.test(raw)) throw new HttpError(400, "trip must be a 22-character trip pseudonym");
  return raw;
}

function latLon(rawLat: unknown, rawLon: unknown): { lat: number; lon: number } {
  if (typeof rawLat !== "number" || !Number.isFinite(rawLat) || rawLat < -90 || rawLat > 90) throw new HttpError(400, "lat must be a latitude");
  if (typeof rawLon !== "number" || !Number.isFinite(rawLon) || rawLon < -180 || rawLon > 180) throw new HttpError(400, "lon must be a longitude");
  return { lat: rawLat, lon: rawLon };
}

function probeSample(raw: unknown): ProbeSample {
  if (!raw || typeof raw !== "object") throw new HttpError(400, "each sample must be an object");
  const s = raw as { seg?: unknown; kph?: unknown; t?: unknown; lat?: unknown; lon?: unknown };
  if (typeof s.seg !== "string" || !SEGMENT_RE.test(s.seg)) throw new HttpError(400, "sample seg must be \"<wayId>:<0|1>\"");
  if (typeof s.kph !== "number" || !Number.isFinite(s.kph) || s.kph < 0 || s.kph > 250) throw new HttpError(400, "sample kph must be from 0 to 250");
  if (typeof s.t !== "number" || !Number.isFinite(s.t)) throw new HttpError(400, "sample t must be epoch seconds");
  const sample: ProbeSample = { seg: s.seg, kph: s.kph, t: Math.floor(s.t) };
  if (s.lat !== undefined || s.lon !== undefined) {
    const { lat, lon } = latLon(s.lat, s.lon);
    sample.lat = lat;
    sample.lon = lon;
  }
  return sample;
}

const WZDX_FETCH_TIMEOUT_MS = 30_000;
/** A statewide feed is a few MiB; the cap keeps a misbehaving one from exhausting the isolate's memory. */
const WZDX_MAX_BYTES = 20 * 1024 * 1024;
/** Regions are created by any signed-in phone; the cron serves only the most recently active ones. */
const WZDX_MAX_REGIONS = 200;

/**
 * Pulls every WZDx feed once and hands each known region the work zones
 * inside its bounding box. A feed that fails to load is skipped, so its
 * existing work zones stay until their own end time rather than vanishing.
 */
async function refreshWzdx(env: Env, now: number): Promise<void> {
  const feeds = (env.WZDX_FEEDS ?? "")
    .split(",")
    .map((u) => u.trim())
    .filter((u) => /^https?:\/\/\S+$/.test(u));
  if (feeds.length === 0) return;
  const registry = env.TRAFFIC.get(env.TRAFFIC.idFromName(REGISTRY_NAME));
  const regions = await registry.listRegions(now, WZDX_MAX_REGIONS);
  if (regions.length === 0) return;
  const targets = [];
  for (const region of regions) {
    // The registry only ever holds names that passed REGION_RE, but it is re-checked before naming an object.
    if (!REGION_RE.test(region)) continue;
    const stub = env.TRAFFIC.get(env.TRAFFIC.idFromName(`traffic:${region}`));
    try {
      const bbox = await stub.regionBBox(now);
      // RPC hands the tuple back as a plain array.
      if (bbox) targets.push({ region, stub, bbox: Array.from(bbox) as BBox });
    } catch (e) {
      // One unreachable region must not cost every other region its work zones.
      console.error(`wzdx: area of ${region} unavailable`, e);
    }
  }
  if (targets.length === 0) return;

  for (const feed of feeds) {
    let doc: unknown;
    try {
      const res = await fetch(feed, {
        headers: { accept: "application/geo+json, application/json" },
        signal: AbortSignal.timeout(WZDX_FETCH_TIMEOUT_MS),
      });
      if (!res.ok) {
        console.error(`wzdx: ${feed} answered ${res.status}`);
        continue;
      }
      const text = await readTextCapped(res.body, res.headers.get("content-length"), WZDX_MAX_BYTES);
      if (text === null) {
        console.error(`wzdx: ${feed} is larger than ${WZDX_MAX_BYTES} bytes`);
        continue;
      }
      doc = JSON.parse(text);
    } catch (e) {
      console.error(`wzdx: ${feed} failed`, e);
      continue;
    }
    let events;
    try {
      events = parseWzdx(feed, doc, now);
    } catch (e) {
      console.error(`wzdx: ${feed} unparseable`, e);
      continue;
    }
    const feedKey = fnv1a(feed);
    for (const t of targets) {
      const inside = events.filter((e) => bboxContains(t.bbox, e.lat, e.lon));
      try {
        const result = await t.stub.ingestWzdx(feedKey, inside, now);
        if (result.upserted > 0 || result.removed > 0) {
          console.log(`wzdx: ${t.region} from ${feed}: ${result.upserted} current, ${result.removed} ended`);
        }
      } catch (e) {
        console.error(`wzdx: delivering ${feed} to ${t.region} failed`, e);
      }
    }
  }
}

const COIN_SYMBOL = "AC";

/** The COIN values that switch ClairixCoin off; anything else, unset included, leaves it on. */
const COIN_OFF = new Set(["off", "false", "0", "no"]);

/**
 * Whether this relay offers ClairixCoin: yes unless its operator switched it
 * off (COIN=off), as it always did before the setting existed. Switched off,
 * the coin routes answer 404, a new account starts with nothing whatever
 * COIN_INITIAL_BALANCE says, nobody can mint, and GET / says coin: false,
 * which is what makes the app hide the balance and the Pay button. Switching
 * it off keeps the balances and ledgers there are; the ledgers still forget
 * each transfer after 30 days.
 */
function coinOn(env: Pick<Env, "COIN">): boolean {
  return !COIN_OFF.has((env.COIN ?? "").trim().toLowerCase());
}

/** Refuses a coin request on a relay whose operator switched the coin off. */
function requireCoin(env: Env): void {
  if (!coinOn(env)) throw new HttpError(404, "ClairixCoin is off on this relay");
}

/**
 * The other addresses this relay answers at, from RELAY_ALTERNATES: https
 * origins only, trimmed, at most eight. A phone that registered at one address
 * keeps these and tries them when that address stops answering (a block, a
 * domain seizure); with the relay's key pinned it stays on the pinned one.
 */
function alternates(env: Env): string[] {
  return (env.RELAY_ALTERNATES ?? "")
    .split(",")
    .map((s) => s.trim().replace(/\/+$/, ""))
    .filter((s) => /^https:\/\/[^\s/]+$/.test(s))
    .slice(0, 8);
}

/**
 * This relay's onion address, from RELAY_ONION: `http://<56 characters>.onion`
 * (a v3 onion service, as selfhost/install.sh sets up), or null. Phones that
 * connect through Tor send everything there, so their requests never leave
 * the Tor network.
 */
function onionAddress(env: Env): string | null {
  const value = (env.RELAY_ONION ?? "").trim().replace(/\/+$/, "").toLowerCase();
  return /^http:\/\/[a-z2-7]{56}\.onion$/.test(value) ? value : null;
}

/** A payment id the phone chose: a UUID, so nothing else names two payments alike. */
const TXID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A whole number of coins between 1 and the single-transfer cap, or a 400. */
function coinAmount(raw: unknown): number {
  if (typeof raw !== "number" || !Number.isInteger(raw) || raw < 1 || raw > Mailbox.MAX_SINGLE_TRANSFER) {
    throw new HttpError(400, `amount must be a whole number from 1 to ${Mailbox.MAX_SINGLE_TRANSFER}`);
  }
  return raw;
}

/**
 * The payee's key fingerprint a payment names (as fingerprint() makes it, and
 * a lookup's pin is), or a 400. Checked for its form only, before anything
 * about the payee is read, so the answer says nothing about the number.
 */
function coinPin(raw: unknown): string {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > 64) throw new HttpError(400, "pin must be the payee's key fingerprint");
  return raw;
}

/**
 * The id the payee's mailbox keeps a payment's credit under, or a 400. The
 * payer's mailbox keeps the debit under [txid]; were the credit kept under the
 * same id, anyone reading the relay's storage could join the two ledgers and
 * read off who paid whom. So the payer's phone chooses a second id ([raw])
 * and tells it to the payee only inside the end-to-end encrypted payment
 * notice. A phone that names its payment ([named], so that a repeated request
 * is the same payment) must name the credit too, and differently, or a repeat
 * would be credited under a new id; a request that names neither gets two
 * fresh ids. Checked for its form only, before anything about the payee is read.
 */
function coinCreditId(raw: unknown, named: boolean, txid: string): string {
  if (typeof raw === "string" && TXID_RE.test(raw)) {
    const credit = raw.toLowerCase();
    if (credit === txid) throw new HttpError(400, "credit must differ from txid");
    return credit;
  }
  if (raw !== undefined && raw !== null) throw new HttpError(400, "credit must be a UUID");
  if (named) throw new HttpError(400, "A payment that names its txid must name its credit too");
  return crypto.randomUUID();
}

/**
 * Gives the payer back [amount] for the payment [txid], which was debited and
 * then could not be credited ([cause] says why). If even that fails the error
 * is logged with the txid, so the operator can put it right by hand.
 */
async function refundPayment(me: MailboxStub, amount: number, txid: string, cause: unknown): Promise<void> {
  try {
    await me.credit(amount, `${txid}:refund`);
  } catch (refund) {
    console.error(`coin payment ${txid} for ${amount}: the credit failed and so did the refund`, cause, refund);
  }
}

/**
 * Registration. The body is signed by the new identity's Ed25519 key (proof of
 * possession), carries the enrollment secret, and the relay allocates a fresh
 * Clairix number. The curve25519 and sealing keys must be signed by the Ed25519
 * key, and every one-time key must be too: the relay checks so it never stores
 * a bundle a contact would reject.
 */
async function register(env: Env, request: Request, bodyText: string): Promise<Response> {
  const body = parseJson<{
    ts?: unknown;
    secret?: unknown;
    invite?: unknown;
    ed25519?: unknown;
    curve25519?: unknown;
    sealing?: unknown;
    signature?: unknown;
    fallback?: unknown;
    oneTimeKeys?: unknown;
    sealingPrekey?: unknown;
    transportKey?: unknown;
    listed?: unknown;
  }>(bodyText);
  // Either the relay's enrollment secret, or an invite a registered phone
  // made; or, when the operator opened registration, neither, within the
  // day's allowance for the whole relay (taken below, once the rest checks out).
  const invite = typeof body.invite === "string" ? body.invite : null;
  const givenSecret = typeof body.secret === "string" && body.secret.length > 0 ? body.secret : null;
  const openCap = invite === null && givenSecret === null ? openRegistrationCap(env) : null;
  if (invite !== null) {
    if (!INVITE_CODE_RE.test(invite)) throw new HttpError(403, "That invite code is not valid");
  } else if (givenSecret !== null || openCap === null) {
    if (givenSecret === null) {
      throw new HttpError(403, "This relay gives new numbers by invite or with its enrollment secret: ask someone on it for an invite");
    }
    const secret = requireSecret(env, "ENROLL_SECRET");
    if (!(await secretEquals(givenSecret, secret))) throw new HttpError(403, "Enrollment code rejected");
  }
  // The body is signed by the new key, and this is what keeps a captured body
  // (it carries the enrollment secret) from being replayed later: it must be
  // recent, and below, each of its keys may be registered once.
  if (typeof body.ts !== "number" || !Number.isFinite(body.ts) || Math.abs(Date.now() - body.ts) > TS_WINDOW_MS) {
    throw new HttpError(401, "Registration timestamp missing or outside the allowed window");
  }
  for (const field of ["ed25519", "curve25519", "sealing", "signature"] as const) {
    if (typeof body[field] !== "string" || (body[field] as string).length === 0) throw new HttpError(400, `${field} required`);
  }
  const ed25519 = body.ed25519 as string;
  const curve25519 = body.curve25519 as string;
  const sealing = body.sealing as string;
  const signature = body.signature as string;
  for (const [field, key] of [["ed25519", ed25519], ["curve25519", curve25519], ["sealing", sealing]] as const) {
    if (keySpellings(key) === null) throw new HttpError(400, `${field} must be a 32-byte key in standard base64`);
  }
  for (const [field, key] of [["curve25519", curve25519], ["sealing", sealing]] as const) {
    if (!canonicalX25519(base64ToBytes(key))) throw new HttpError(400, `${field} must be a Curve25519 key in canonical form`);
  }

  const bodySig = request.headers.get("x-clairix-sig") ?? "";
  if (!(await verifyEd25519(ed25519, bodyText, bodySig))) throw new HttpError(401, "Body signature does not match ed25519");
  if (!(await verifyEd25519(ed25519, `${curve25519}|${sealing}`, signature))) throw new HttpError(400, "Key binding signature invalid");
  const fallback = body.fallback === undefined || body.fallback === null ? null : (await checkedKeys(ed25519, [body.fallback]))[0] ?? null;
  const oneTimeKeys = await checkedKeys(ed25519, body.oneTimeKeys ?? []);
  const sealingPrekey = await checkedSealingPrekey(ed25519, body.sealingPrekey);
  const transportKey = body.transportKey === undefined ? null : checkedTransportKey(body.transportKey, ed25519);
  const listed = body.listed !== false;

  // Used up only once everything else about the request has checked out.
  const inviteBox = invite === null ? null : env.MAILBOX.get(env.MAILBOX.idFromName(`invite:${invite}`));
  if (inviteBox) {
    const redeemed = await inviteBox.redeemInvite();
    if (!redeemed.ok) throw new HttpError(403, redeemed.reason);
  }
  const openBox = openCap === null ? null : env.MAILBOX.get(env.MAILBOX.idFromName(OPEN_REGISTRATION_NAME));
  if (openBox && openCap !== null) {
    const taken = await openBox.takeOpenRegistration(openCap);
    if (!taken.ok) {
      throw new HttpError(429, "This relay has given out all the new numbers it gives without an invite today; try again tomorrow, or ask someone on it for an invite", taken.retryAfter);
    }
  }

  for (let attempt = 0; attempt < 8; attempt++) {
    const number = randomNumber();
    const mailbox = env.MAILBOX.get(env.MAILBOX.idFromName(number));
    const now = Date.now();
    // Dated to the day: enough for anything that asks how old an account is.
    const profile: Profile = { number, ed25519, curve25519, sealing, signature, fallback, sealingPrekey, transportKey, listed, createdAt: dayFloor(now), keysClaimed: true };
    if (await mailbox.claim(profile, oneTimeKeys)) {
      if (!(await claimKeys(env, profile, true))) {
        // One of the keys is already another account's. The number just taken
        // goes back, and so does the invite or the open registration, if one was used.
        await mailbox.wipe();
        if (inviteBox) await inviteBox.releaseInvite();
        if (openBox) await openBox.releaseOpenRegistration();
        throw new HttpError(409, "One of these keys is already registered to another Clairix number");
      }
      // A new member of this relay's coin community starts with what the
      // operator set; nothing when they set nothing or the coin is off.
      const initial = coinOn(env) ? parseInt(env.COIN_INITIAL_BALANCE ?? "0", 10) : 0;
      if (Number.isInteger(initial) && initial > 0) await mailbox.setInitialBalance(initial);
      return json({ number, profile: publicProfile(profile), oneTimeKeys: oneTimeKeys.length }, 201);
    }
  }
  if (inviteBox) await inviteBox.releaseInvite();
  if (openBox) await openBox.releaseOpenRegistration();
  throw new HttpError(503, "Could not allocate a number; try again");
}

/** 128 random bits, base64url: 22 characters. */
const INVITE_CODE_RE = /^[A-Za-z0-9_-]{22}$/;

function randomInviteCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Validates a list of signed keys against the owner's Ed25519 key. */
async function checkedKeys(ed25519: string, raw: unknown): Promise<SignedKey[]> {
  if (!Array.isArray(raw)) throw new HttpError(400, "keys must be an array");
  if (raw.length > 100) throw new HttpError(400, "at most 100 keys per request");
  const out: SignedKey[] = [];
  for (const item of raw) {
    const k = item as Partial<SignedKey>;
    if (typeof k.id !== "string" || typeof k.key !== "string" || typeof k.signature !== "string") {
      throw new HttpError(400, "each key needs id, key and signature");
    }
    if (k.id.length > 64 || k.key.length > 64 || k.signature.length > 128) throw new HttpError(400, "key fields too long");
    if (!(await verifyEd25519(ed25519, k.key, k.signature))) throw new HttpError(400, `key ${k.id} is not signed by ed25519`);
    out.push({ id: k.id, key: k.key, signature: k.signature });
  }
  return out;
}

/** What a request signed with the transport key may do: collect envelopes, nothing else. */
const TRANSPORT_ROUTES = new Set(["GET /v1/inbox", "POST /v1/ack", "GET /v1/ws"]);
/** A pass key or a pass: 32 bytes in padded base64. */
const PASS_RE = /^[A-Za-z0-9+/]{43}=$/;
/** Pass keys an owner may set in an hour: a new one only on deleting a contact. */
const PASS_KEYS_PER_HOUR = 20;
const PASS_KEYS_BURST = 20;

/**
 * A transport key: null removes it; otherwise a 32-byte Ed25519 key in standard
 * base64 that is not the identity key itself (which would make the restricted
 * key a full one).
 */
function checkedTransportKey(raw: unknown, ed25519: string): string | null {
  if (raw === null) return null;
  const spellings = typeof raw === "string" ? keySpellings(raw) : null;
  if (typeof raw !== "string" || spellings === null) throw new HttpError(400, "transport key must be a 32-byte key in standard base64");
  if (spellings.includes(ed25519)) throw new HttpError(400, "transport key must not be the identity key");
  return raw;
}

/**
 * A sealing prekey from its owner: absent or null is none; anything else must
 * be a canonical X25519 key, signed by [ed25519] over [prekeyStatement], made
 * neither more than two weeks ago nor more than a day ahead. The relay checks
 * so it never serves a prekey senders would refuse.
 */
async function checkedSealingPrekey(ed25519: string, raw: unknown): Promise<SealingPrekey | null> {
  if (raw === undefined || raw === null) return null;
  const p = raw as Partial<SealingPrekey>;
  if (typeof p.key !== "string" || typeof p.signature !== "string" || typeof p.created !== "number" || !Number.isSafeInteger(p.created)) {
    throw new HttpError(400, "sealingPrekey needs key, created and signature");
  }
  if (p.key.length > 64 || p.signature.length > 128) throw new HttpError(400, "sealingPrekey fields too long");
  if (keySpellings(p.key) === null || !canonicalX25519(base64ToBytes(p.key))) {
    throw new HttpError(400, "sealingPrekey must be a Curve25519 key in canonical form, in standard base64");
  }
  const now = Date.now();
  if (p.created > now + PREKEY_FUTURE_SKEW_MS || now - p.created > PREKEY_MAX_AGE_MS) {
    throw new HttpError(400, "sealingPrekey is out of date; check the phone's clock");
  }
  if (!(await verifyEd25519(ed25519, prekeyStatement(p.key, p.created), p.signature))) {
    throw new HttpError(400, "sealingPrekey is not signed by ed25519");
  }
  return { key: p.key, created: p.created, signature: p.signature };
}

function publicProfile(p: Profile) {
  return {
    number: p.number,
    ed25519: p.ed25519,
    curve25519: p.curve25519,
    sealing: p.sealing,
    signature: p.signature,
    sealingPrekey: p.sealingPrekey ?? null,
    listed: p.listed,
    createdAt: p.createdAt,
  };
}

/**
 * The relay API this Worker speaks; 3 is anonymous sending, claim tickets and
 * checked push endpoints; 4 is lookups by body, anonymous traffic and no
 * stored activity times; 5 is no lookups by URL at all, payments that name
 * the payee's key, a coin ledger that names nobody, and "coin" at GET /.
 */
const API_VERSION = 5;

/** The most a request body may be: nothing the relay accepts is near it (an envelope is 72 KiB sealed, 96 KiB in base64). */
const MAX_BODY_BYTES = 256 * 1024;

/**
 * Records [profile]'s identity, Curve25519 and sealing keys as its own, each
 * in an object named after the key. With [strict], a key another account
 * holds is a failure and whatever this call claimed is released; without
 * it (an account from before keys were recorded), a conflict is logged and
 * the first holder keeps the key.
 */
async function claimKeys(env: Env, profile: Profile, strict: boolean): Promise<boolean> {
  const claimed: string[] = [];
  for (const name of await keyRecordNames(profile)) {
    if (await env.MAILBOX.get(env.MAILBOX.idFromName(name)).claimKey(profile.number)) {
      claimed.push(name);
    } else if (strict) {
      for (const done of claimed) await env.MAILBOX.get(env.MAILBOX.idFromName(done)).releaseKey(profile.number);
      return false;
    } else {
      console.error("a key already registered to another number was found while recording an account's keys");
    }
  }
  return true;
}

async function releaseKeys(env: Env, profile: Profile): Promise<void> {
  for (const name of await keyRecordNames(profile)) {
    await env.MAILBOX.get(env.MAILBOX.idFromName(name)).releaseKey(profile.number);
  }
}

/**
 * The objects that record [profile]'s keys as its own: one per spelling of
 * each key. Records were first named after the key as sent, which is one of
 * the two spellings, so an account from then still holds its records.
 */
async function keyRecordNames(profile: Profile): Promise<string[]> {
  const names: string[] = [];
  for (const key of [profile.ed25519, profile.curve25519, profile.sealing]) {
    for (const spelling of keySpellings(key) ?? [key]) names.push(`key:${await sha256Hex(spelling)}`);
  }
  return names;
}

/**
 * Whether [bytes] is a Curve25519 public key in its one canonical form: top bit
 * clear and below the field prime. X25519 ignores the top bit, so a key with it
 * set is another account's key under a spelling no record covers.
 */
export function canonicalX25519(bytes: Uint8Array): boolean {
  if (bytes.length !== 32 || (bytes[31]! & 0x80) !== 0) return false;
  const p = new Uint8Array(32).fill(0xff);
  p[0] = 0xed;
  p[31] = 0x7f;
  for (let i = 31; i >= 0; i--) if (bytes[i] !== p[i]) return bytes[i]! < p[i]!;
  return false;
}

/**
 * The two spellings of a 32-byte key in standard base64, without padding and
 * with it, when [key] is one of them; otherwise null. Decoding forgives a
 * good deal (URL-safe letters, whitespace, bits past the last byte), and
 * every forgiven variant would be the same key under a name no record covers.
 */
export function keySpellings(key: string): [string, string] | null {
  if (!/^[A-Za-z0-9+/]{43}=?$/.test(key)) return null;
  let bin: string;
  try { bin = atob(key.endsWith("=") ? key : `${key}=`); } catch { return null; }
  if (bin.length !== 32) return null;
  const padded = btoa(bin);
  const unpadded = padded.slice(0, -1);
  return key === padded || key === unpadded ? [unpadded, padded] : null;
}

/**
 * Anonymous delivery. The request names a recipient and spends one delivery
 * token; it carries no identity. Abuse is bounded by the token supply (issued
 * per account at a limited rate, each spent once) and by each mailbox's own
 * inbound and queue limits.
 */
async function send(env: Env, request: Request, bodyText: string): Promise<Response> {
  const body = parseSendBody(bodyText, isLoopbackDev(request));
  refuseLegacyEnvelope(env, body.envelope);
  const foreign = body.relay !== null && !isOwnOrigin(env, request, body.relay);
  // Checked before the token is spent, so a phone on a relay that cannot
  // forward keeps its token.
  if (foreign && !(await relayKey(env))) throw new HttpError(501, "This relay does not reach other relays: its operator has turned federation off");
  await spendToken(env, request, "delivery");
  if (foreign && body.relay !== null) {
    // A number on another relay: this relay forwards the envelope, signed as
    // itself. The other relay sees this relay's address, not the phone's, and
    // the forwarded request names no sender, as the phone's did not.
    const forwarded: Record<string, unknown> = { to: body.to, envelope: body.envelope, mix: !body.urgent };
    if (body.claim !== null) forwarded.claim = body.claim;
    if (body.alert !== null) forwarded.alert = body.alert;
    if (body.pass !== null) forwarded.pass = body.pass;
    const res = await forward(env, request, body.relay, "/v1/federate/send", forwarded);
    if (res.status !== 202 || typeof res.json.id !== "string") throw otherRelayError(res);
    await noteWrittenTo(env, body.relay);
    return json({ id: res.json.id.slice(0, 80) }, 202);
  }
  return enqueueFor(env, body.to, body.envelope, body.claim, body.urgent, false, body.alert, body.pass);
}

/** The object that keeps abuse reports for the operator. */
const REPORTS_NAME = "abuse-reports";
/** The object that counts today's open registrations. */
const OPEN_REGISTRATION_NAME = "open-registration";

/**
 * An abuse report: who is reported (a number, and the relay it is on when
 * that is not this one), the hash of an envelope they sent, and why. It
 * spends a "report" token and names nobody, so what the relay keeps is who
 * was reported and why, never by whom; the relay never sees what was said.
 * The answer is the same whether the number exists, was reported before,
 * or has been reported as often as the relay keeps.
 */
async function fileReport(env: Env, request: Request, bodyText: string): Promise<Response> {
  const body = parseJson<{ number?: unknown; relay?: unknown; envelope?: unknown; reason?: unknown }>(bodyText);
  const number = typeof body.number === "string" ? normaliseNumber(body.number) : null;
  if (!number) throw new HttpError(400, "number must be a Clairix number");
  const reason = body.reason;
  if (!isReportReason(reason)) throw new HttpError(400, `reason must be one of ${REPORT_REASONS.join(", ")}`);
  let envelope: string | null = null;
  if (body.envelope !== undefined && body.envelope !== null) {
    if (typeof body.envelope !== "string" || !ENVELOPE_HASH_RE.test(body.envelope)) {
      throw new HttpError(400, "envelope must be an envelope's fingerprint: 64 lowercase hex characters");
    }
    envelope = body.envelope;
  }
  const named = parseRelayField(body.relay, isLoopbackDev(request));
  const relay = named !== null && isOwnOrigin(env, request, named) ? null : named;
  // Spent only once the report is well formed, as for a send.
  await spendToken(env, request, "report");
  // A number on this relay that nobody holds is not kept: reports about
  // made-up numbers would otherwise fill the relay's share of reports and
  // crowd out real ones. The answer is the same either way, so a report tells
  // nobody whether the number exists.
  const keep = relay !== null || (await env.MAILBOX.get(env.MAILBOX.idFromName(number)).profile()) !== null;
  await env.MAILBOX.get(env.MAILBOX.idFromName(REPORTS_NAME)).fileReport({ number, relay, envelope, reason, day: dayFloor(Date.now()) }, keep);
  return json({ ok: true }, 202);
}

/**
 * What phones reported, for the operator, with ADMIN_SECRET in X-Admin-Secret
 * (off while it is unset): GET lists every reported number with how often,
 * why, the envelope hashes and the first and last day; DELETE {number,
 * relay?} closes the reports about one number once the operator has dealt
 * with it. A few attempts a minute per address, as for registration, so the
 * secret cannot be guessed by brute force.
 */
async function adminReports(env: Env, request: Request, bodyText: string): Promise<Response> {
  const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`admin-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
  const allowed = await guard.rateOk("admin", Mailbox.REGISTER_LIMIT);
  if (!allowed.ok) throw new HttpError(429, "Too many attempts; try again in a minute", allowed.retryAfter);
  const secret = requireSecret(env, "ADMIN_SECRET");
  if (!(await secretEquals(request.headers.get("x-admin-secret") ?? "", secret))) throw new HttpError(403, "Admin secret rejected");
  const reports = env.MAILBOX.get(env.MAILBOX.idFromName(REPORTS_NAME));
  if (request.method === "GET") return json({ numbers: summariseReports(await reports.listReports()) });
  if (request.method === "DELETE") {
    const body = parseJson<{ number?: unknown; relay?: unknown }>(bodyText);
    const number = typeof body.number === "string" ? normaliseNumber(body.number) : null;
    if (!number) throw new HttpError(400, "number must be a Clairix number");
    const named = parseRelayField(body.relay, isLoopbackDev(request));
    const relay = named !== null && isOwnOrigin(env, request, named) ? null : named;
    return json({ closed: await reports.closeReports(number, relay) });
  }
  throw new HttpError(405, "GET or DELETE only");
}

interface SendBody {
  to: string;
  envelope: string;
  claim: string | null;
  /** Not to be held for mixing: everything the sender did not ask to have mixed. */
  urgent: boolean;
  /** The relay the recipient is on, when it is not this one. */
  relay: string | null;
  /** What a locked recipient phone should do with it: ring, or sound an SOS. */
  alert: Alert | null;
  /** Made with the recipient's pass key, from a sender the recipient has written to. */
  pass: string | null;
}

function parseSendBody(bodyText: string, loopback = false): SendBody {
  const body = parseJson<{ to?: unknown; envelope?: unknown; claim?: unknown; mix?: unknown; relay?: unknown; alert?: unknown; pass?: unknown }>(bodyText);
  const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
  if (!to) throw new HttpError(400, "to must be a Clairix number");
  if (typeof body.envelope !== "string" || body.envelope.length === 0 || !/^[A-Za-z0-9+/=]+$/.test(body.envelope)) {
    throw new HttpError(400, "envelope must be base64");
  }
  // Refused here, before a token is spent or a mailbox is asked, for every number alike.
  if (body.envelope.length > MAX_ENVELOPE_B64) throw new HttpError(413, "envelope too large");
  let claim: string | null = null;
  if (body.claim !== undefined && body.claim !== null) {
    if (typeof body.claim !== "string" || !DELIVERY_TOKEN_RE.test(body.claim)) throw new HttpError(400, "claim must be a claim ticket");
    claim = body.claim;
  }
  if (body.mix !== undefined && typeof body.mix !== "boolean") throw new HttpError(400, "mix must be a boolean");
  // Held for mixing only when asked: an app from before mixing sends call
  // set-up and SOS without saying so, and must not have them wait.
  if (body.alert !== undefined && body.alert !== null && !ALERTS.includes(body.alert as Alert)) throw new HttpError(400, "alert must be call or sos");
  const alert = (body.alert ?? null) as Alert | null;
  if (body.pass !== undefined && body.pass !== null && (typeof body.pass !== "string" || !PASS_RE.test(body.pass))) throw new HttpError(400, "pass must be 32 bytes of base64");
  const pass = (body.pass ?? null) as string | null;
  return { to, envelope: body.envelope, claim, urgent: body.mix !== true, relay: parseRelayField(body.relay, loopback), alert, pass };
}

interface LookupBody {
  target: string;
  pin: string | null;
  claim: boolean;
  /** From another relay: false when its phone is past its own limit for one-time keys, so the fallback key is offered instead. */
  oneTime: boolean;
  relay: string | null;
}

function parseLookupBody(bodyText: string, loopback = false): LookupBody {
  const body = parseJson<{ number?: unknown; pin?: unknown; claim?: unknown; oneTime?: unknown; relay?: unknown }>(bodyText);
  const target = typeof body.number === "string" ? normaliseNumber(body.number) : null;
  if (!target) throw new HttpError(400, "number must be a Clairix number");
  let pin: string | null = null;
  if (body.pin !== undefined && body.pin !== null) {
    if (typeof body.pin !== "string" || body.pin.length === 0 || body.pin.length > 64) throw new HttpError(400, "pin must be a key fingerprint");
    pin = body.pin;
  }
  if (body.claim !== undefined && typeof body.claim !== "boolean") throw new HttpError(400, "claim must be a boolean");
  if (body.oneTime !== undefined && typeof body.oneTime !== "boolean") throw new HttpError(400, "oneTime must be a boolean");
  return { target, pin, claim: body.claim !== false, oneTime: body.oneTime !== false, relay: parseRelayField(body.relay, loopback) };
}

function parseRelayField(value: unknown, loopback: boolean): string | null {
  if (value === undefined || value === null) return null;
  const origin = relayOrigin(value, loopback);
  if (!origin) throw new HttpError(400, "relay must be the https address of a relay");
  return origin;
}

/**
 * What the other relay refused, passed on with its status (and how long to
 * wait, when it said), so the phone can tell a full mailbox from a dead relay
 * and a busy one from either.
 */
function otherRelayError(res: { status: number; json: Record<string, unknown>; retryAfter?: number }): HttpError {
  const reason = typeof res.json.error === "string" ? res.json.error.slice(0, 200) : `status ${res.status}`;
  const status = res.status >= 400 && res.status < 600 && res.status !== 401 ? res.status : 502;
  return new HttpError(status, `The other relay refused: ${reason}`, res.retryAfter);
}

function checkedIdentity(value: unknown, number: string): IdentityLike | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (v.number !== number) return null;
  for (const k of ["ed25519", "curve25519", "sealing", "signature"] as const) {
    if (typeof v[k] !== "string" || (v[k] as string).length === 0 || (v[k] as string).length > 200) return null;
  }
  return {
    number,
    ed25519: v.ed25519 as string,
    curve25519: v.curve25519 as string,
    sealing: v.sealing as string,
    signature: v.signature as string,
    sealingPrekey: checkedForeignPrekey(v.sealingPrekey),
  };
}

/**
 * A sealing prekey in another relay's answer, kept only in its expected shape.
 * Its signature is the phone's to check, as for every key another relay hands
 * over; a malformed one is dropped, so a phone that relies on it fetches again.
 */
function checkedForeignPrekey(value: unknown): SealingPrekey | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (typeof v.key !== "string" || typeof v.signature !== "string" || typeof v.created !== "number" || !Number.isSafeInteger(v.created)) return null;
  if (v.key.length > 64 || v.signature.length > 128) return null;
  return { key: v.key, created: v.created, signature: v.signature };
}

function checkedSignedKey(value: unknown): SignedKey | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (typeof v.id !== "string" || typeof v.key !== "string" || typeof v.signature !== "string") return null;
  if (v.id.length > 100 || v.key.length > 200 || v.signature.length > 200) return null;
  return { id: v.id, key: v.key, signature: v.signature };
}

/** A bundle from another relay, rebuilt from the fields a phone reads and nothing else. */
function checkedBundle(value: unknown, number: string): Record<string, unknown> | null {
  const identity = checkedIdentity(value, number);
  if (!identity) return null;
  const v = value as Record<string, unknown>;
  const oneTimeKey = v.oneTimeKey === null || v.oneTimeKey === undefined ? null : checkedSignedKey(v.oneTimeKey);
  const fallback = v.fallback === null || v.fallback === undefined ? null : checkedSignedKey(v.fallback);
  if (!oneTimeKey && !fallback) return null;
  const claim = typeof v.claim === "string" && DELIVERY_TOKEN_RE.test(v.claim) ? v.claim : null;
  if (oneTimeKey && !claim) return null;
  return { ...identity, oneTimeKey, fallback: oneTimeKey ? null : fallback, claim: oneTimeKey ? claim : null };
}

interface IdentityLike {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  signature: string;
  sealingPrekey: SealingPrekey | null;
}

/**
 * Checks a request another relay forwarded: its signature against the key that
 * relay publishes (fetched once a day, or again after a failure at most every
 * ten minutes, so a rotated key is picked up), and its nonce. Returns the
 * object that keeps that relay's limits.
 */
async function authenticateRelay(env: Env, request: Request, bodyText: string): Promise<MailboxStub> {
  const req = await parseFederated(env, request, bodyText);
  const box = env.MAILBOX.get(env.MAILBOX.idFromName(await federationObjectName(req.origin)));
  const now = Date.now();
  let cached = await box.federationKey();
  const refresh = async (): Promise<boolean> => {
    // Nothing about the request has been checked yet but its shape, so the
    // address it names is fetched sparingly: not again soon after a failure,
    // and within one allowance for the whole relay.
    if (keyFetchFailedRecently(req.origin)) return false;
    const allowed = await env.MAILBOX.get(env.MAILBOX.idFromName(RELAY_IDENTITY_NAME)).rateOk("key-fetch", KEY_FETCHES_PER_MINUTE, KEY_FETCHES_BURST);
    if (!allowed.ok) throw new HttpError(503, "Too many new relays at once; try again shortly", allowed.retryAfter);
    const key = await fetchRelayKey(req.origin);
    if (!key) {
      noteKeyFetchFailed(req.origin);
      return false;
    }
    await box.setFederationKey(key);
    cached = { key, fetchedAt: Date.now() };
    return true;
  };
  if (!cached || now - cached.fetchedAt > RELAY_KEY_TTL_MS) {
    if (!(await refresh()) && !cached) throw new HttpError(401, "Could not fetch the forwarding relay's key from its address");
  }
  let ok = cached !== null && (await verifyFederated(cached.key, req));
  if (!ok && cached !== null && now - cached.fetchedAt > RELAY_KEY_REFETCH_MS && (await refresh())) {
    ok = await verifyFederated((cached as { key: string }).key, req);
  }
  if (!ok) throw new HttpError(401, "Relay signature rejected");
  if (!(await box.federationNonceOk(req.nonce))) throw new HttpError(401, "Relay request replayed");
  return box;
}

/** Queues [envelope] for [to] on this relay. */
async function enqueueFor(
  env: Env,
  to: string,
  envelope: string,
  claim: string | null,
  urgent: boolean,
  federated: false | "known" | "stranger" = false,
  alert: Alert | null = null,
  pass: string | null = null,
): Promise<Response> {
  const result = await env.MAILBOX.get(env.MAILBOX.idFromName(to)).enqueue(envelope, claim, urgent, federated, alert, pass);
  if (!result.ok) {
    // Whether a number exists is not told to anyone, listed or not: a send to
    // one that does not is accepted and dropped, its token spent like any other.
    if (result.status === 404) return json({ id: `${Date.now().toString(36)}-${crypto.randomUUID()}` }, 202);
    throw new HttpError(result.status, result.reason, result.retryAfter);
  }
  return json({ id: result.id }, 202);
}

/**
 * ICE servers for a call. With a Cloudflare Realtime TURN key configured,
 * TURN credentials are minted for every request, live two hours and are never
 * cached or shared; without a key, calls get STUN only and connect when both
 * phones can reach each other directly. With a key set but minting failing,
 * the request is answered 503 rather than with STUN only: the app records
 * from a 200 whether this relay offers TURN, and a passing failure must not
 * make it believe the relay offers none and set up a direct call. Media is
 * DTLS-SRTP between the two phones either way: a TURN server forwards packets
 * it cannot decrypt.
 */
async function iceServers(env: Env): Promise<IceServer[]> {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return STUN_ONLY;
  let res: Response;
  try {
    res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ ttl: TURN_TTL_S }),
      signal: AbortSignal.timeout(5_000),
    });
  } catch (e) {
    console.error("TURN credential request threw", e);
    throw new HttpError(503, "The relay's call server is not answering; try again in a moment", 30);
  }
  if (!res.ok) {
    console.error("TURN credential request failed", res.status, await res.text());
    throw new HttpError(503, "The relay's call server is not answering; try again in a moment", 30);
  }
  const servers = parseIceServers(await res.json().catch(() => null));
  if (!servers.some((s) => s.urls.some((u) => u.startsWith("turn")))) {
    console.error("TURN credential response held no TURN server");
    throw new HttpError(503, "The relay's call server is not answering; try again in a moment", 30);
  }
  return servers;
}

/** POSTs [body] to a push endpoint; returns the HTTP status, or 0 when it could not be reached. */
async function postPush(endpoint: string, body: string): Promise<number> {
  try {
    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "text/plain", ttl: "600", urgency: "high" },
      body,
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    return res.status;
  } catch (e) {
    console.error("push check failed", e);
    return 0;
  }
}

/** Matches the app's `fingerprint()`: base64url of the first 12 bytes of SHA-256 over the key text. */
async function fingerprint(ed25519: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ed25519)));
  let s = "";
  for (const b of digest.subarray(0, 12)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function parseJson<T>(text: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new HttpError(400, "Body must be JSON");
  }
}
