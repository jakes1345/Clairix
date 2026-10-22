import { parseSigned, TS_WINDOW_MS, verifyEd25519 } from "./auth";
import {
  checkPushEndpoint,
  dayFloor,
  DELIVERY_TOKEN_BATCH_MAX,
  DELIVERY_TOKEN_RE,
  groupByShard,
  isTokenKind,
  parseIceServers,
  PUSH_CHALLENGE_PREFIX,
  randomToken,
  STUN_ONLY,
  TOKEN_KINDS,
  tokenHash,
  tokenShardName,
  tokenBatchExpiry,
  TRIP_RE,
  TURN_TTL_S,
  type IceServer,
  type TokenKind,
} from "./comms-logic";
import { HttpError, json, jsonText, normaliseNumber, randomNumber, requireSecret, secretEquals, sha256Hex, timingSafeEqual, type Env } from "./env";
import { KNOWN_REGIONS } from "./regions";
import { INVITE_TTL_MS, Mailbox, type CoinTx, type Profile, type SignedKey } from "./mailbox";
import { invitePage } from "./invite-page";
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
 *   POST   /v1/register        {ts, secret | invite, ed25519, curve25519, sealing, signature, fallback, oneTimeKeys, listed}
 *                              signed by ed25519 (X-Clairix-Sig over the body) → {number}. ts is epoch millis within
 *                              five minutes, and each key may belong to one account, so a captured body cannot be
 *                              replayed into a second account.
 *   GET    /i                                              → invite page; the invite itself is in the URL fragment,
 *                                                            which browsers never send to the relay
 * Anonymous (no identity header; refused if one is sent). Each request spends one single-use token of
 * the right kind in X-Clairix-Token (see /v1/tokens); the relay keeps only token hashes, apart from the
 * account they went to:
 *   POST   /v1/send            {to, envelope, claim?}      → queue for a contact; a "delivery" token.
 *                              "claim" is the ticket of the one-time key the envelope's session was built
 *                              on, which spends that key for good.
 *   Traffic (one Durable Object per map region; "traffic" tokens, "report" tokens for new reports; probes,
 *   reports and votes carry a "trip" pseudonym the phone makes up for the drive and rotates):
 *   POST   /v1/traffic/probe   {region, trip, samples:[{seg, kph, t, lat?, lon?}]} → {ok, accepted}
 *   GET    /v1/traffic/live?region=                        → {now, segments: {seg: {kph, n, t}}, incidents}
 *   GET    /v1/traffic/typical?region=&segs=a,b            → {segments: {seg: {h: [168]}}}
 *   POST   /v1/traffic/report  {region, trip, type, lat, lon, seg|null} → {incident}   ("report" token)
 *   POST   /v1/traffic/report/vote {region, trip, id, still} → {incident|null}
 *   GET    /v1/traffic/incidents?bbox=minLat,minLon,maxLat,maxLon (≤ 1°×1°)
 *                              → {now, incidents, providers: {here, tomtom}}: HERE / TomTom incidents,
 *                                cached per 0.25° tile for 5 minutes in the IncidentCache object
 * Signed (see auth.ts):
 *   GET    /v1/me                                          → profile + key counts
 *   PUT    /v1/keys            {oneTimeKeys, fallback?}    → replenish
 *   PUT    /v1/listed          {listed}
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
 *   GET    /v1/bundle/:number[?pin=]                       → as a claiming lookup; for apps before 3.0.0-beta.3
 *   GET    /v1/identity/:number[?pin=]                     → as a non-claiming lookup; likewise
 *   GET    /v1/inbox                                       → waiting envelopes
 *   POST   /v1/ack             {ids}
 *   GET    /v1/ws                                          → live delivery (hibernatable WebSocket)
 *   GET    /v1/turn                                        → ICE servers for a call, with TURN credentials minted
 *                                                            for this request alone
 * ClairixCoin (each relay is its own coin community; balances live in the mailboxes):
 *   GET    /v1/coin/balance                                → {balance, symbol: "AC"}
 *   POST   /v1/coin/pay        {to, amount, note?}         → {txid, balance, ok}: moves coins to a contact
 *   GET    /v1/coin/history                                → {txs: [...]}, newest first
 *   POST   /v1/admin/coin/mint {to, amount}                → {ok, balance}; X-Admin-Secret must match ADMIN_SECRET
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
    return json({ service: "clairix-comms", version: API_VERSION, ok: true, note: "Clairix numbers are not phone numbers." });
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
    return new Response(invitePage(), {
      headers: {
        "content-type": "text/html; charset=utf-8",
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

  if (path.startsWith("/v1/traffic/")) {
    return trafficRoute(env, request, url, path, bodyText);
  }

  // ── Authenticated ─────────────────────────────────────────────────────
  const signed = await parseSigned(request, bodyText);
  const me = env.MAILBOX.get(env.MAILBOX.idFromName(signed.number));
  const profile = await me.authenticate(signed.payload, signed.signature, signed.nonce, signed.ts);
  if (!profile) throw new HttpError(401, "Signature rejected");
  // An account from before keys were recorded as their owner's records them now.
  if (!profile.keysClaimed) {
    await claimKeys(env, profile, false);
    await me.markKeysClaimed();
  }

  if (path === "/v1/me" && request.method === "GET") {
    return json({ profile: publicProfile(profile), oneTimeKeys: await me.oneTimeKeyCount() });
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
    const body = parseJson<{ oneTimeKeys?: unknown; fallback?: unknown }>(bodyText);
    const oneTimeKeys = await checkedKeys(profile.ed25519, body.oneTimeKeys);
    const fallback = body.fallback === undefined || body.fallback === null ? null : (await checkedKeys(profile.ed25519, [body.fallback]))[0] ?? null;
    return json(await me.putKeys(oneTimeKeys, fallback));
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
    const check = checkPushEndpoint(body.endpoint, env.PUSH_HOSTS);
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
    const body = parseJson<{ count?: unknown; kind?: unknown }>(bodyText);
    const want = body.count;
    if (typeof want !== "number" || !Number.isInteger(want) || want < 1 || want > DELIVERY_TOKEN_BATCH_MAX) {
      throw new HttpError(400, `count must be a whole number from 1 to ${DELIVERY_TOKEN_BATCH_MAX}`);
    }
    let kind: TokenKind = "delivery";
    if (body.kind !== undefined && body.kind !== null) {
      if (!isTokenKind(body.kind)) throw new HttpError(400, "kind must be delivery, traffic or report");
      kind = body.kind;
    }
    const spec = TOKEN_KINDS[kind];
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
    const body = parseJson<{ number?: unknown; pin?: unknown; claim?: unknown }>(bodyText);
    const target = typeof body.number === "string" ? normaliseNumber(body.number) : null;
    if (!target) throw new HttpError(400, "number must be a Clairix number");
    let pin: string | null = null;
    if (body.pin !== undefined && body.pin !== null) {
      if (typeof body.pin !== "string" || body.pin.length === 0 || body.pin.length > 64) throw new HttpError(400, "pin must be a key fingerprint");
      pin = body.pin;
    }
    if (body.claim !== undefined && typeof body.claim !== "boolean") throw new HttpError(400, "claim must be a boolean");
    return lookup(env, me, profile, target, pin, body.claim !== false);
  }

  // The lookups apps before 3.0.0-beta.3 make, with the number in the path.
  // They answer exactly as /v1/lookup does, and go once every phone has moved.
  const bundleMatch = /^\/v1\/bundle\/([0-9]{9})$/.exec(path);
  if (bundleMatch && bundleMatch[1] && request.method === "GET") {
    const target = normaliseNumber(bundleMatch[1]);
    if (!target) throw new HttpError(400, "Bad number");
    return lookup(env, me, profile, target, url.searchParams.get("pin"), true);
  }

  const identityMatch = /^\/v1\/identity\/([0-9]{9})$/.exec(path);
  if (identityMatch && identityMatch[1] && request.method === "GET") {
    const target = normaliseNumber(identityMatch[1]);
    if (!target) throw new HttpError(400, "Bad number");
    return lookup(env, me, profile, target, url.searchParams.get("pin"), false);
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
    // Minted for this request and handed to this caller only: no two phones, and
    // no two calls, ever share TURN credentials.
    const servers = await iceServers(env);
    // "turn" tells the app whether calls can be relayed when the phones cannot reach each other directly.
    return json({ iceServers: servers, turn: servers.some((s) => s.urls.some((u) => u.startsWith("turn"))) });
  }

  // ── ClairixCoin ─────────────────────────────────────────────────────────
  if (path === "/v1/coin/balance" && request.method === "GET") {
    return json({ balance: await me.getBalance(), symbol: COIN_SYMBOL });
  }

  if (path === "/v1/coin/pay" && request.method === "POST") {
    const allowed = await me.rateOk("pay", Mailbox.PAY_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many payments; try again in a minute", allowed.retryAfter);
    const body = parseJson<{ to?: unknown; amount?: unknown; note?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be a Clairix number");
    if (to === profile.number) throw new HttpError(400, "You cannot pay yourself");
    const amount = coinAmount(body.amount);
    const note = coinNote(body.note);
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    // Whether a number exists is not told to anyone; a payment to one that does not is refused like any other.
    if (!(await them.profile())) throw new HttpError(400, "Payment refused");
    const txid = crypto.randomUUID();
    const debited = await me.debit(to, amount, txid, note);
    if (!debited.ok) throw new HttpError(400, debited.reason ?? "Payment refused");
    try {
      await them.credit(profile.number, amount, txid, note);
    } catch (e) {
      // The sender has paid and the recipient has not been credited: give the
      // coins back. If even that fails the error is logged with the txid, so
      // the operator can put it right by hand.
      try {
        await me.credit(to, amount, `${txid}:refund`, "Refund: payment could not be delivered");
      } catch (refund) {
        console.error(`coin payment ${txid} from ${profile.number} to ${to} for ${amount}: credit failed and so did the refund`, e, refund);
      }
      throw e;
    }
    return json({ txid, balance: debited.balance, ok: true });
  }

  if (path === "/v1/coin/history" && request.method === "GET") {
    const raw = Number(url.searchParams.get("limit") ?? "");
    const limit = Number.isFinite(raw) && raw > 0 ? Math.min(100, Math.floor(raw)) : 50;
    const txs: CoinTx[] = await me.txHistory(limit);
    return json({ txs });
  }

  if (path === "/v1/admin/coin/mint" && request.method === "POST") {
    const secret = requireSecret(env, "ADMIN_SECRET");
    const given = request.headers.get("x-admin-secret") ?? "";
    if (!(await secretEquals(given, secret))) throw new HttpError(403, "Admin secret rejected");
    const body = parseJson<{ to?: unknown; amount?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be a Clairix number");
    const amount = coinAmount(body.amount);
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    if (!(await them.profile())) throw new HttpError(404, "No such Clairix number on this relay");
    const balance = await them.credit("mint", amount, crypto.randomUUID(), `Minted by ${profile.number}`);
    return json({ ok: true, balance });
  }

  throw new HttpError(404, "Not found");
}

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
  const them = env.MAILBOX.get(env.MAILBOX.idFromName(target));
  let unlisted = false;
  if (pin !== null) {
    const p = await them.profile();
    if (!p || !timingSafeEqual(await fingerprint(p.ed25519), pin)) throw new HttpError(404, "No such Clairix number, or it is unlisted");
    unlisted = true;
  }
  if (claim) {
    const mayClaim = target !== profile.number && (await me.mayClaimFrom(target));
    const bundle = await them.bundle(!unlisted, mayClaim);
    if (!bundle) throw new HttpError(404, "No such Clairix number, or it is unlisted");
    return json({ bundle });
  }
  // Who holds a number, without claiming one of their one-time keys: used to
  // confirm the sender of a first message.
  const identity = await them.identity(!unlisted);
  if (!identity) throw new HttpError(404, "No such Clairix number, or it is unlisted");
  return json({ identity });
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
  if (!DELIVERY_TOKEN_RE.test(token)) throw new HttpError(403, "Token rejected");
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

  if (path === "/v1/traffic/typical" && request.method === "GET") {
    await spendToken(env, request, "traffic");
    const region = trafficRegion(url.searchParams.get("region"));
    const segs = (url.searchParams.get("segs") ?? "").split(",").map((s) => s.trim()).filter((s) => s.length > 0);
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
    await spendToken(env, request, "traffic");
    const body = parseJson<{ region?: unknown; trip?: unknown; id?: unknown; still?: unknown }>(bodyText);
    const region = trafficRegion(body.region);
    const trip = tripPseudonym(body.trip);
    if (typeof body.id !== "string" || !INCIDENT_ID_RE.test(body.id)) throw new HttpError(400, "id must be an incident id");
    if (typeof body.still !== "boolean") throw new HttpError(400, "still must be a boolean");
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
const COIN_NOTE_MAX = 140;

/** A whole number of coins between 1 and the single-transfer cap, or a 400. */
function coinAmount(raw: unknown): number {
  if (typeof raw !== "number" || !Number.isInteger(raw) || raw < 1 || raw > Mailbox.MAX_SINGLE_TRANSFER) {
    throw new HttpError(400, `amount must be a whole number from 1 to ${Mailbox.MAX_SINGLE_TRANSFER}`);
  }
  return raw;
}

/** The optional note on a payment: a short string, or nothing. */
function coinNote(raw: unknown): string {
  if (raw === undefined || raw === null) return "";
  if (typeof raw !== "string") throw new HttpError(400, "note must be a string");
  if (raw.length > COIN_NOTE_MAX) throw new HttpError(400, `note must be at most ${COIN_NOTE_MAX} characters`);
  return raw;
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
    listed?: unknown;
  }>(bodyText);
  // Either the relay's enrollment secret, or an invite a registered phone made.
  const invite = typeof body.invite === "string" ? body.invite : null;
  if (invite !== null) {
    if (!INVITE_CODE_RE.test(invite)) throw new HttpError(403, "That invite code is not valid");
  } else {
    const secret = requireSecret(env, "ENROLL_SECRET");
    if (typeof body.secret !== "string" || !(await secretEquals(body.secret, secret))) throw new HttpError(403, "Enrollment code rejected");
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

  const bodySig = request.headers.get("x-clairix-sig") ?? "";
  if (!(await verifyEd25519(ed25519, bodyText, bodySig))) throw new HttpError(401, "Body signature does not match ed25519");
  if (!(await verifyEd25519(ed25519, `${curve25519}|${sealing}`, signature))) throw new HttpError(400, "Key binding signature invalid");
  const fallback = body.fallback === undefined || body.fallback === null ? null : (await checkedKeys(ed25519, [body.fallback]))[0] ?? null;
  const oneTimeKeys = await checkedKeys(ed25519, body.oneTimeKeys ?? []);
  const listed = body.listed !== false;

  // Used up only once everything else about the request has checked out.
  const inviteBox = invite === null ? null : env.MAILBOX.get(env.MAILBOX.idFromName(`invite:${invite}`));
  if (inviteBox) {
    const redeemed = await inviteBox.redeemInvite();
    if (!redeemed.ok) throw new HttpError(403, redeemed.reason);
  }

  for (let attempt = 0; attempt < 8; attempt++) {
    const number = randomNumber();
    const mailbox = env.MAILBOX.get(env.MAILBOX.idFromName(number));
    const now = Date.now();
    // Dated to the day: enough for anything that asks how old an account is.
    const profile: Profile = { number, ed25519, curve25519, sealing, signature, fallback, listed, createdAt: dayFloor(now), keysClaimed: true };
    if (await mailbox.claim(profile, oneTimeKeys)) {
      if (!(await claimKeys(env, profile, true))) {
        // One of the keys is already another account's. The number just taken
        // goes back, and so does the invite, if one was used.
        await mailbox.wipe();
        if (inviteBox) await inviteBox.releaseInvite();
        throw new HttpError(409, "One of these keys is already registered to another Clairix number");
      }
      // A new member of this relay's coin community starts with what the
      // operator set; nothing when they set nothing.
      const initial = parseInt(env.COIN_INITIAL_BALANCE ?? "0", 10);
      if (Number.isInteger(initial) && initial > 0) await mailbox.setInitialBalance(initial);
      return json({ number, profile: publicProfile(profile), oneTimeKeys: oneTimeKeys.length }, 201);
    }
  }
  if (inviteBox) await inviteBox.releaseInvite();
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
    if (!(await verifyEd25519(ed25519, k.key, k.signature))) throw new HttpError(400, `key ${k.id} is not signed by ed25519`);
    out.push({ id: k.id, key: k.key, signature: k.signature });
  }
  return out;
}

function publicProfile(p: Profile) {
  return {
    number: p.number,
    ed25519: p.ed25519,
    curve25519: p.curve25519,
    sealing: p.sealing,
    signature: p.signature,
    listed: p.listed,
    createdAt: p.createdAt,
  };
}

/**
 * The relay API this Worker speaks; 3 is anonymous sending, claim tickets and
 * checked push endpoints; 4 is lookups by body, anonymous traffic and no
 * stored activity times.
 */
const API_VERSION = 4;

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
  for (const key of [profile.ed25519, profile.curve25519, profile.sealing]) {
    const name = `key:${await sha256Hex(key)}`;
    if (await env.MAILBOX.get(env.MAILBOX.idFromName(name)).claimKey(profile.number)) {
      claimed.push(name);
    } else if (strict) {
      for (const done of claimed) await env.MAILBOX.get(env.MAILBOX.idFromName(done)).releaseKey(profile.number);
      return false;
    } else {
      console.error(`a key already registered to another number was found while recording ${profile.number}'s keys`);
    }
  }
  return true;
}

async function releaseKeys(env: Env, profile: Profile): Promise<void> {
  for (const key of [profile.ed25519, profile.curve25519, profile.sealing]) {
    await env.MAILBOX.get(env.MAILBOX.idFromName(`key:${await sha256Hex(key)}`)).releaseKey(profile.number);
  }
}

/**
 * Anonymous delivery. The request names a recipient and spends one delivery
 * token; it carries no identity. Abuse is bounded by the token supply (issued
 * per account at a limited rate, each spent once) and by each mailbox's own
 * inbound and queue limits.
 */
async function send(env: Env, request: Request, bodyText: string): Promise<Response> {
  const body = parseJson<{ to?: unknown; envelope?: unknown; claim?: unknown }>(bodyText);
  const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
  if (!to) throw new HttpError(400, "to must be a Clairix number");
  if (typeof body.envelope !== "string" || body.envelope.length === 0 || !/^[A-Za-z0-9+/=]+$/.test(body.envelope)) {
    throw new HttpError(400, "envelope must be base64");
  }
  let claim: string | null = null;
  if (body.claim !== undefined && body.claim !== null) {
    if (typeof body.claim !== "string" || !DELIVERY_TOKEN_RE.test(body.claim)) throw new HttpError(400, "claim must be a claim ticket");
    claim = body.claim;
  }
  await spendToken(env, request, "delivery");
  const result = await env.MAILBOX.get(env.MAILBOX.idFromName(to)).enqueue(body.envelope, claim);
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
 * cached or shared; without a key, or when minting fails, calls get STUN only
 * and connect when both phones can reach each other directly. Media is
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
    return STUN_ONLY;
  }
  if (!res.ok) {
    console.error("TURN credential request failed", res.status, await res.text());
    return STUN_ONLY;
  }
  const servers = parseIceServers(await res.json().catch(() => null));
  return servers.length > 0 ? servers : STUN_ONLY;
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
