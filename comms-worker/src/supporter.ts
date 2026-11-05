// Supporter: relay-cost limits lifted for people who pay, or who were given a
// code (an organisation such as a shelter buys a batch and hands them out).
// docs/SUPPORTER.md is the design note: what each party learns and what is
// not protected.
//
// No account is ever marked as a Supporter. A code, or a Google Play
// purchase, is exchanged on a request that names nobody for a batch of blind
// "supporter" credentials (RFC 9497 VOPRF, tokens.ts, under a key of their
// own). Each credential, spent on another request that names nobody, buys one
// batch of ordinary delivery tokens on top of the account's hourly allowance.
// Those are the same delivery tokens every phone spends, under the same key.
// The relay sees a code being used and, separately, credentials being spent;
// blindness means it cannot tell which redemption a credential came from, and
// neither request carries an account.
//
// Safety features never come through here: scanning, alerts, evidence,
// helplines, SOS and ordinary messaging work the same with or without
// Supporter. What it lifts is what costs the relay money to carry.

import { HttpError, base64ToBytes, json, requireSecret, secretEquals, sha256Hex, type Env } from "./env";
import { BLIND_BATCH_MAX, SUPPORTER_EPOCH_MS, checkBlindToken, epochExpiry, epochOf, issueBlind, tokenPublicKey } from "./tokens";

/** Credentials one code, or one purchase, is exchanged for: about one a week for a year. */
export const SUPPORTER_CREDENTIALS_PER_CODE = 52;
/** Delivery tokens one credential buys, beyond the hourly allowance (one batch; the VOPRF module's limit). */
export const SUPPORTER_TOKENS_PER_CREDENTIAL = BLIND_BATCH_MAX;
/** Codes minted by one admin request at most. */
export const CODES_PER_MINT_MAX = 500;
/** Redemptions tried a minute from one address, in memory only: a code cannot be guessed, but each guess costs a lookup. */
export const REDEEMS_PER_MINUTE = 6;
/** Admin requests a minute from one address, as for the other admin routes: the secret cannot be guessed by brute force. */
const ADMIN_TRIES_PER_MINUTE = 5;
/** Google Play product ids exchanged for credentials: one-time, consumable products made in Play Console. */
export const PLAY_PRODUCT_IDS: readonly string[] = ["clairix_supporter"];

// ── Codes ───────────────────────────────────────────────────────────────────
//
// 22 random symbols of Crockford's base32 (110 bits) and 2 check symbols, in
// six groups of four: "7KQ2-M9XD-4TPW-HC3R-0VNB-6YMY". The check catches a
// mistyped code on the phone before anything is sent. The app's copy of this
// is android/.../supporter/SupporterCode.kt; both are tested against the same
// vectors.

export const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CODE_DATA_LEN = 22;
export const CODE_CHECK_LEN = 2;
const CODE_DOMAIN = "clairix-supporter-code-v1|";

async function digest(text: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}

/** The two check symbols of 22 data symbols. */
export async function codeCheck(data: string): Promise<string> {
  const d = await digest(CODE_DOMAIN + "check|" + data);
  return CODE_ALPHABET[d[0]! & 31]! + CODE_ALPHABET[d[1]! & 31]!;
}

/**
 * A typed code as its 24 symbols, or null when it is not one: case, spaces
 * and dashes do not matter, O reads as 0 and I or L as 1, and the check
 * symbols must match.
 */
export async function normaliseCode(raw: string): Promise<string | null> {
  if (raw.length > 64) return null;
  const s = raw.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  if (s.length !== CODE_DATA_LEN + CODE_CHECK_LEN) return null;
  for (const ch of s) if (!CODE_ALPHABET.includes(ch)) return null;
  return (await codeCheck(s.slice(0, CODE_DATA_LEN))) === s.slice(CODE_DATA_LEN) ? s : null;
}

/** A new code, grouped for printing. Made by the relay itself, from the platform's random generator. */
export async function newCode(): Promise<string> {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_DATA_LEN));
  let data = "";
  for (const b of bytes) data += CODE_ALPHABET[b & 31];
  return groupCode(data + (await codeCheck(data)));
}

export function groupCode(symbols: string): string {
  return symbols.match(/.{1,4}/g)!.join("-");
}

/** What the relay keeps of a code: SHA-256 of the normalised code. The code itself is never stored. */
export async function codeHash(normalised: string): Promise<string> {
  return sha256Hex(CODE_DOMAIN + normalised);
}

function shard(prefix: string, hash: string): string {
  return `${prefix}:${hash.slice(0, 2)}`;
}

// ── What GET / says ─────────────────────────────────────────────────────────

export interface SupporterInfo {
  version: 1;
  epoch: number;
  epochMs: number;
  key: string;
  next: { epoch: number; key: string };
  credentialsPerCode: number;
  tokensPerCredential: number;
  play: boolean;
}

/** The Supporter key of this year and the next, the same for every caller: a phone pins them and checks every issue against them. */
export async function supporterInfo(env: Env, now = Date.now()): Promise<SupporterInfo> {
  const epoch = epochOf(now, "supporter");
  return {
    version: 1,
    epoch,
    epochMs: SUPPORTER_EPOCH_MS,
    key: await tokenPublicKey(env, "supporter", epoch),
    next: { epoch: epoch + 1, key: await tokenPublicKey(env, "supporter", epoch + 1) },
    credentialsPerCode: SUPPORTER_CREDENTIALS_PER_CODE,
    tokensPerCredential: SUPPORTER_TOKENS_PER_CREDENTIAL,
    play: playConfigured(env),
  };
}

// ── Routes ──────────────────────────────────────────────────────────────────

const IDENTITY_HEADERS = ["x-clairix-number", "x-clairix-sig", "x-clairix-nonce", "x-clairix-ts", "x-clairix-key"] as const;

/** These requests name nobody: one that carries an identity is refused, so a client bug cannot tie a code to an account. */
function refuseIdentity(request: Request): void {
  for (const h of IDENTITY_HEADERS) {
    if (request.headers.has(h)) throw new HttpError(400, "This request carries no identity");
  }
}

function parseBody(bodyText: string): Record<string, unknown> {
  try {
    const v: unknown = JSON.parse(bodyText);
    if (v && typeof v === "object" && !Array.isArray(v)) return v as Record<string, unknown>;
  } catch {
    // falls through
  }
  throw new HttpError(400, "Body must be a JSON object");
}

function blindedOf(raw: unknown, max: number): Uint8Array[] {
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > max) throw new HttpError(400, `blinded must be a list of 1 to ${max} elements`);
  return raw.map((e) => {
    if (typeof e !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(e)) throw new HttpError(400, "blinded elements are 32 bytes of base64url");
    return base64ToBytes(e);
  });
}

async function issueCredentials(env: Env, blinded: Uint8Array[], release: () => Promise<void>): Promise<Response> {
  let issued;
  try {
    issued = await issueBlind(env, "supporter", blinded);
  } catch (e) {
    await release();
    throw e;
  }
  if (!issued) {
    await release();
    throw new HttpError(400, "blinded must hold valid ristretto255 elements");
  }
  return json({ kind: "supporter", ...issued, expiresAt: epochExpiry(issued.epoch, "supporter") });
}

/**
 * POST /v1/admin/supporter/codes {count} with X-Admin-Secret: CODES_ADMIN_SECRET
 * → {codes}. The codes are in this answer and nowhere else; the relay keeps
 * their hashes. Who they are for is the operator's business, kept off the
 * relay.
 */
export async function mintCodes(env: Env, request: Request, bodyText: string): Promise<Response> {
  if (request.method !== "POST") throw new HttpError(405, "POST only");
  const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`admin-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
  const allowed = await guard.rateOk("admin", ADMIN_TRIES_PER_MINUTE);
  if (!allowed.ok) throw new HttpError(429, "Too many admin requests; try again in a minute", allowed.retryAfter);
  const secret = requireSecret(env, "CODES_ADMIN_SECRET");
  if (!(await secretEquals(request.headers.get("x-admin-secret") ?? "", secret))) throw new HttpError(403, "Admin secret rejected");
  const count = parseBody(bodyText).count;
  if (typeof count !== "number" || !Number.isInteger(count) || count < 1 || count > CODES_PER_MINT_MAX) {
    throw new HttpError(400, `count must be a whole number from 1 to ${CODES_PER_MINT_MAX}`);
  }
  const codes: string[] = [];
  const byShard = new Map<string, string[]>();
  for (let i = 0; i < count; i++) {
    const code = await newCode();
    const hash = await codeHash(code.replace(/-/g, ""));
    codes.push(code);
    const name = shard("scode", hash);
    byShard.set(name, [...(byShard.get(name) ?? []), hash]);
  }
  await Promise.all([...byShard].map(([name, hashes]) => env.MAILBOX.get(env.MAILBOX.idFromName(name)).addSupporterCodes(hashes)));
  return json({ codes, credentialsPerCode: SUPPORTER_CREDENTIALS_PER_CODE }, 201);
}

/**
 * POST /v1/supporter/redeem {code, blinded}, naming nobody → blind Supporter
 * credentials, with a proof against the key GET / publishes. The code is
 * used up before anything is issued, and given back if issuing fails.
 */
export async function redeemCode(env: Env, request: Request, bodyText: string): Promise<Response> {
  if (request.method !== "POST") throw new HttpError(405, "POST only");
  refuseIdentity(request);
  const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`redeem-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
  const allowed = await guard.rateOk("redeem", REDEEMS_PER_MINUTE);
  if (!allowed.ok) throw new HttpError(429, "Too many tries; wait a minute and try again", allowed.retryAfter);
  const body = parseBody(bodyText);
  const blinded = blindedOf(body.blinded, SUPPORTER_CREDENTIALS_PER_CODE);
  const code = typeof body.code === "string" ? await normaliseCode(body.code) : null;
  if (!code) throw new HttpError(400, "That is not a Clairix code; check it was typed correctly");
  const hash = await codeHash(code);
  const holder = env.MAILBOX.get(env.MAILBOX.idFromName(shard("scode", hash)));
  const used = await holder.useSupporterCode(hash);
  if (used === "unknown") throw new HttpError(404, "This relay does not know that code");
  if (used === "used") throw new HttpError(409, "That code has already been used");
  return issueCredentials(env, blinded, () => holder.releaseSupporterCode(hash));
}

/**
 * POST /v1/supporter/tokens {blinded} with a Supporter credential in
 * X-Clairix-Token, naming nobody → up to SUPPORTER_TOKENS_PER_CREDENTIAL
 * ordinary blind delivery tokens. The credential is spent first; each buys
 * one batch.
 */
export async function supporterTokens(env: Env, request: Request, bodyText: string): Promise<Response> {
  if (request.method !== "POST") throw new HttpError(405, "POST only");
  refuseIdentity(request);
  const blinded = blindedOf(parseBody(bodyText).blinded, SUPPORTER_TOKENS_PER_CREDENTIAL);
  const token = request.headers.get("x-clairix-token") ?? "";
  if (!token.startsWith("2.")) throw new HttpError(401, "A Supporter credential is required");
  const spent = await checkBlindToken(env, "supporter", token);
  if (!spent || !(await env.MAILBOX.get(env.MAILBOX.idFromName(shard("stok", spent.hash))).spendBlindToken(spent.hash, spent.expiresAt))) {
    throw new HttpError(403, "Credential rejected: unknown, expired or already used");
  }
  const issued = await issueBlind(env, "delivery", blinded);
  if (!issued) throw new HttpError(400, "blinded must hold valid ristretto255 elements");
  return json({ kind: "delivery", ...issued, expiresAt: epochExpiry(issued.epoch, "delivery") });
}

// ── Google Play ─────────────────────────────────────────────────────────────

/** Whether this relay checks Play purchases: PLAY_VERIFY on, and the two settings it needs present. */
export function playConfigured(env: Pick<Env, "PLAY_VERIFY" | "PLAY_PACKAGE_NAME" | "GOOGLE_PLAY_SERVICE_ACCOUNT">): boolean {
  const on = (env.PLAY_VERIFY ?? "").trim().toLowerCase();
  return (on === "on" || on === "true") && !!env.PLAY_PACKAGE_NAME?.trim() && !!env.GOOGLE_PLAY_SERVICE_ACCOUNT?.trim();
}

/**
 * What a purchases.products.get answer says about exchanging the purchase:
 * only a completed purchase of the product asked about is, and a test
 * purchase only when the operator allows them.
 * https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products
 */
export function playPurchaseVerdict(answer: unknown, allowTest: boolean): "ok" | "not-purchased" | "test" {
  const a = (answer ?? {}) as { purchaseState?: unknown; purchaseType?: unknown };
  if (a.purchaseState !== 0) return "not-purchased";
  // purchaseType is absent for a real purchase; 0 is a licence tester's.
  if (a.purchaseType === 0 && !allowTest) return "test";
  return "ok";
}

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GOOGLE_PUBLISHER_URL = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const PURCHASE_TOKEN_RE = /^[A-Za-z0-9._-]{16,4096}$/;

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

function serviceAccount(env: Env): ServiceAccount {
  try {
    const sa = JSON.parse(env.GOOGLE_PLAY_SERVICE_ACCOUNT ?? "") as Partial<ServiceAccount>;
    if (typeof sa.client_email === "string" && typeof sa.private_key === "string") return { client_email: sa.client_email, private_key: sa.private_key };
  } catch {
    // falls through
  }
  throw new HttpError(503, "GOOGLE_PLAY_SERVICE_ACCOUNT is not a service account key file");
}

function b64url(bytes: Uint8Array | string): string {
  const b = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let bin = "";
  for (const x of b) bin += String.fromCharCode(x);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

let cachedAccess: { token: string; until: number; email: string } | null = null;

/** An OAuth access token for the Play Developer API, from a JWT the relay signs with the service account's key. */
async function googleAccessToken(env: Env): Promise<string> {
  const sa = serviceAccount(env);
  const now = Date.now();
  if (cachedAccess && cachedAccess.email === sa.client_email && cachedAccess.until > now) return cachedAccess.token;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey("pkcs8", base64ToBytes(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  } catch {
    throw new HttpError(503, "GOOGLE_PLAY_SERVICE_ACCOUNT's private key is unreadable");
  }
  const iat = Math.floor(now / 1000);
  const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(JSON.stringify({ iss: sa.client_email, scope: PUBLISHER_SCOPE, aud: GOOGLE_TOKEN_URL, iat, exp: iat + 3600 }))}`;
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const res = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${unsigned}.${b64url(signature)}`,
  });
  const answer = (await res.json().catch(() => ({}))) as { access_token?: unknown; expires_in?: unknown };
  if (!res.ok || typeof answer.access_token !== "string") throw new HttpError(503, "Google did not grant the relay an access token");
  const ttl = typeof answer.expires_in === "number" ? answer.expires_in : 3600;
  cachedAccess = { token: answer.access_token, until: now + Math.max(0, ttl - 120) * 1000, email: sa.client_email };
  return answer.access_token;
}

/**
 * POST /v1/supporter/play {productId, purchaseToken, blinded}, naming nobody
 * → blind Supporter credentials, as for a code. Off unless playConfigured():
 * the relay asks Google whether the purchase is real, and exchanges each
 * purchase once. The app consumes the purchase after this answers.
 */
export async function redeemPlay(env: Env, request: Request, bodyText: string): Promise<Response> {
  if (request.method !== "POST") throw new HttpError(405, "POST only");
  if (!playConfigured(env)) throw new HttpError(404, "This relay does not check Google Play purchases");
  refuseIdentity(request);
  const body = parseBody(bodyText);
  const blinded = blindedOf(body.blinded, SUPPORTER_CREDENTIALS_PER_CODE);
  const productId = body.productId;
  if (typeof productId !== "string" || !PLAY_PRODUCT_IDS.includes(productId)) throw new HttpError(400, "Unknown product");
  const purchaseToken = body.purchaseToken;
  if (typeof purchaseToken !== "string" || !PURCHASE_TOKEN_RE.test(purchaseToken)) throw new HttpError(400, "purchaseToken required");
  const access = await googleAccessToken(env);
  const url = `${GOOGLE_PUBLISHER_URL}/${encodeURIComponent(env.PLAY_PACKAGE_NAME!.trim())}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
  const res = await fetch(url, { headers: { authorization: `Bearer ${access}` } });
  if (res.status === 400 || res.status === 404 || res.status === 410) throw new HttpError(403, "Google does not know that purchase");
  if (!res.ok) throw new HttpError(503, "Google could not be asked about the purchase; try again later", 60);
  const verdict = playPurchaseVerdict(await res.json().catch(() => null), (env.PLAY_ALLOW_TEST_PURCHASES ?? "").trim().toLowerCase() === "on");
  if (verdict !== "ok") throw new HttpError(403, verdict === "test" ? "Test purchases are not accepted on this relay" : "That purchase is not complete");
  const hash = await sha256Hex(`clairix-play-v1|${purchaseToken}`);
  const holder = env.MAILBOX.get(env.MAILBOX.idFromName(shard("splay", hash)));
  if (!(await holder.usePlayPurchase(hash))) throw new HttpError(409, "That purchase has already been exchanged");
  return issueCredentials(env, blinded, () => holder.releasePlayPurchase(hash));
}
