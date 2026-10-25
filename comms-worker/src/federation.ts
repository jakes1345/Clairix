import { verifyEd25519 } from "./auth";
import { base64ToBytes, HttpError, sha256Hex, type Env } from "./env";

/**
 * Federation: Clairix numbers on different relays reaching each other.
 *
 * Anyone can run a relay. A phone registers with one (its home relay) and
 * talks only to that one; a contact on another relay is reached through it.
 * The home relay forwards the phone's anonymous send, or its lookup, to the
 * other relay, signing the forwarded request with the home relay's own
 * Ed25519 key (RELAY_KEY, or one the relay made for itself; see relayKey). The other relay checks that signature against the
 * key the home relay publishes at `GET /`, fetched from the origin the
 * request names.
 *
 * What each side learns: the other relay sees the home relay's address, never
 * the phone's, and the recipient's number (it holds that mailbox anyway). The
 * home relay sees which relay the phone sends to and the number there, which
 * it would not for a contact on its own relay only because then it is both.
 * Neither learns who the sender is: a forwarded send carries no identity, just
 * as a local one does.
 *
 *   POST /v1/federate/send    {to, envelope, claim?, mix?}     → as /v1/send
 *   POST /v1/federate/lookup  {number, pin?, claim}            → as /v1/lookup
 *
 * both signed with the headers below over
 *   clairix-relay-v1 \n origin \n target-origin \n ts \n nonce \n METHOD \n path \n sha256(body).
 * The target origin is in the signature, so a request made for one relay is
 * worthless at another; the nonce is single-use within the five-minute window.
 */

export const FEDERATION_HEADERS = {
  origin: "x-clairix-relay",
  ts: "x-clairix-relay-ts",
  nonce: "x-clairix-relay-nonce",
  sig: "x-clairix-relay-sig",
} as const;

export const FEDERATION_WINDOW_MS = 5 * 60_000;
/** How long a fetched relay key is trusted before it is fetched again. */
export const RELAY_KEY_TTL_MS = 24 * 60 * 60_000;
/** A key that fails to verify is fetched again, but no more often than this. */
export const RELAY_KEY_REFETCH_MS = 10 * 60_000;
const FORWARD_TIMEOUT_MS = 10_000;

/** Per other relay, for everyone on it together. */
export const FED_SENDS_PER_MINUTE = 600;
export const FED_SENDS_BURST = 1_200;
/** Per other relay and recipient: one relay alone cannot reach a mailbox's allowance for other relays. */
export const FED_SENDS_PER_TARGET_PER_MINUTE = 30;
export const FED_SENDS_PER_TARGET_BURST = 60;
export const FED_LOOKUPS_PER_MINUTE = 120;
export const FED_LOOKUPS_BURST = 240;
/** One-time keys any one other relay may claim, per hour across all numbers here. */
export const FED_CLAIMS_PER_HOUR = 600;
export const FED_CLAIMS_BURST = 120;

export type FederationPolicy = { mode: "off" } | { mode: "open" } | { mode: "allow"; origins: Set<string> };

/**
 * Which relays this one accepts forwarded requests from: FEDERATION "open"
 * (the default: any relay, within the per-relay limits), "off", or a
 * comma-separated list of https origins.
 */
export function federationPolicy(env: Pick<Env, "FEDERATION">): FederationPolicy {
  const raw = (env.FEDERATION ?? "").trim();
  if (raw === "" || raw.toLowerCase() === "open") return { mode: "open" };
  if (raw.toLowerCase() === "off") return { mode: "off" };
  const origins = new Set(
    raw
      .split(",")
      .map((s) => relayOrigin(s.trim()))
      .filter((s): s is string => s !== null),
  );
  return origins.size > 0 ? { mode: "allow", origins } : { mode: "off" };
}

export function policyName(p: FederationPolicy): string {
  return p.mode === "allow" ? "allowlist" : p.mode;
}

/**
 * The canonical origin of a relay address: `https://host[:port]`, no path,
 * query, fragment or credentials, and a host name rather than an IP literal.
 * Null when [value] is not one.
 *
 * [loopback] (see [isLoopbackDev]) also admits `http://127.0.0.1:port` and
 * `http://localhost:port`: relays a developer runs side by side on one
 * computer, which is how relays reaching each other is tested end to end.
 */
export function relayOrigin(value: unknown, loopback = false): string | null {
  if (typeof value !== "string" || value.length === 0 || value.length > 256) return null;
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    return null;
  }
  if (loopback && url.protocol === "http:" && (url.hostname === "127.0.0.1" || url.hostname === "localhost") && url.port !== "" &&
    url.username === "" && url.password === "" && (url.pathname === "/" || url.pathname === "") && url.search === "" && url.hash === "") {
    return url.origin.toLowerCase();
  }
  if (url.protocol !== "https:" || url.username !== "" || url.password !== "") return null;
  if ((url.pathname !== "/" && url.pathname !== "") || url.search !== "" || url.hash !== "") return null;
  const host = url.hostname.toLowerCase();
  if (host.length === 0 || host.startsWith("[") || /^[0-9.]+$/.test(host) || !host.includes(".")) return null;
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null;
  return url.origin.toLowerCase();
}

/**
 * Whether [request] reached a relay running on a developer's own computer:
 * plain http to 127.0.0.1 or localhost. A deployed relay never sees that
 * (Cloudflare hands the Worker the https address it was reached at), so the
 * loopback relay addresses [relayOrigin] admits with it exist only there.
 */
export function isLoopbackDev(request: Request): boolean {
  const url = new URL(request.url);
  return url.protocol === "http:" && (url.hostname === "127.0.0.1" || url.hostname === "localhost");
}

/** The address this relay is reached at, as other relays should fetch its key from. */
export function ownOrigin(env: Pick<Env, "RELAY_ORIGIN">, request: Request): string {
  return relayOrigin(env.RELAY_ORIGIN, isLoopbackDev(request)) ?? new URL(request.url).origin.toLowerCase();
}

/** Every address this relay answers at: a send "to" any of them is a local send. */
export function isOwnOrigin(env: Pick<Env, "RELAY_ORIGIN" | "RELAY_ALTERNATES">, request: Request, origin: string): boolean {
  if (origin === ownOrigin(env, request) || origin === new URL(request.url).origin.toLowerCase()) return true;
  return (env.RELAY_ALTERNATES ?? "")
    .split(",")
    .map((s) => relayOrigin(s.trim(), isLoopbackDev(request)))
    .includes(origin);
}

export function federationPayload(origin: string, target: string, ts: number, nonce: string, method: string, path: string, bodySha256: string): string {
  return `clairix-relay-v1\n${origin}\n${target}\n${ts}\n${nonce}\n${method.toUpperCase()}\n${path}\n${bodySha256}`;
}

// ── This relay's key ──────────────────────────────────────────────────────

/** DER prefix of a PKCS#8 Ed25519 private key; the 32-byte seed follows it. */
const PKCS8_ED25519_PREFIX = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]);

interface RelayKey {
  seed: string;
  privateKey: CryptoKey;
  publicKey: string;
}

let cachedKey: RelayKey | null = null;
/** The generated seed, per relay (per MAILBOX namespace), once read from its object. */
const generatedSeeds = new WeakMap<object, string>();

/** The object that keeps the relay's own generated signing seed. */
export const RELAY_IDENTITY_NAME = "relay-identity";

/**
 * This relay's signing key, or null when it does not reach other relays
 * (FEDERATION "off"). RELAY_KEY, when set, is the key (base64 of a 32-byte
 * Ed25519 seed; `npm run relay-key` makes one). Otherwise the relay makes its
 * own on first use and keeps it in its "relay-identity" object, so turning
 * federation on needs no secret to be made, copied or pasted by anyone, and
 * the seed never leaves the relay. Replacing it is safe: other relays fetch
 * the new public key from GET / once the old one stops verifying.
 */
export async function relayKey(env: Pick<Env, "RELAY_KEY" | "FEDERATION" | "MAILBOX">): Promise<RelayKey | null> {
  if (federationPolicy(env).mode === "off") return null;
  let seedB64 = env.RELAY_KEY?.trim();
  if (!seedB64) {
    seedB64 = generatedSeeds.get(env.MAILBOX);
    if (!seedB64) {
      seedB64 = await env.MAILBOX.get(env.MAILBOX.idFromName(RELAY_IDENTITY_NAME)).relayIdentitySeed();
      generatedSeeds.set(env.MAILBOX, seedB64);
    }
  }
  if (cachedKey && cachedKey.seed === seedB64) return cachedKey;
  let seed: Uint8Array;
  try {
    seed = base64ToBytes(seedB64);
  } catch {
    throw new Error("RELAY_KEY is not base64");
  }
  if (seed.length !== 32) throw new Error("RELAY_KEY must be 32 bytes of base64");
  const der = new Uint8Array(PKCS8_ED25519_PREFIX.length + 32);
  der.set(PKCS8_ED25519_PREFIX, 0);
  der.set(seed, PKCS8_ED25519_PREFIX.length);
  const privateKey = await crypto.subtle.importKey("pkcs8", der, { name: "Ed25519" }, true, ["sign"]);
  const jwk = (await crypto.subtle.exportKey("jwk", privateKey)) as JsonWebKey;
  if (!jwk.x) throw new Error("RELAY_KEY: no public key");
  const publicKey = bytesToBase64(base64ToBytes(jwk.x));
  cachedKey = { seed: seedB64, privateKey, publicKey };
  return cachedKey;
}

function bytesToBase64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function randomNonce(): string {
  return bytesToBase64(crypto.getRandomValues(new Uint8Array(18))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ── Forwarding ────────────────────────────────────────────────────────────

/**
 * POSTs [body] to [path] on the relay at [target], signed as this relay.
 * Returns the other relay's status and JSON body; a relay that cannot be
 * reached is a 502.
 */
export async function forward(env: Env, request: Request, target: string, path: string, body: unknown): Promise<{ status: number; json: Record<string, unknown> }> {
  const key = await relayKey(env);
  if (!key) throw new HttpError(501, "This relay does not reach other relays: its operator has turned federation off");
  const origin = ownOrigin(env, request);
  const text = JSON.stringify(body);
  const ts = Date.now();
  const nonce = randomNonce();
  const payload = federationPayload(origin, target, ts, nonce, "POST", path, await sha256Hex(text));
  const sig = bytesToBase64(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key.privateKey, new TextEncoder().encode(payload))));
  let res: Response;
  try {
    res = await fetch(target + path, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        [FEDERATION_HEADERS.origin]: origin,
        [FEDERATION_HEADERS.ts]: String(ts),
        [FEDERATION_HEADERS.nonce]: nonce,
        [FEDERATION_HEADERS.sig]: sig,
      },
      body: text,
      redirect: "manual",
      signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),
    });
  } catch {
    throw new HttpError(502, "The other relay did not answer");
  }
  const raw = await res.text().catch(() => "");
  let json: Record<string, unknown> = {};
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) json = parsed as Record<string, unknown>;
  } catch {
    // not JSON
  }
  return { status: res.status, json };
}

// ── Receiving ─────────────────────────────────────────────────────────────

export interface FederatedRequest {
  origin: string;
  ts: number;
  nonce: string;
  signature: string;
  payload: string;
}

/** Reads the federation headers of [request]; checks shape, policy and clock, not the signature. */
export async function parseFederated(env: Env, request: Request, bodyText: string): Promise<FederatedRequest> {
  const policy = federationPolicy(env);
  if (policy.mode === "off") throw new HttpError(403, "This relay does not accept requests from other relays");
  const origin = relayOrigin(request.headers.get(FEDERATION_HEADERS.origin), isLoopbackDev(request));
  if (!origin) throw new HttpError(401, "X-Clairix-Relay must be the https origin of the forwarding relay");
  if (policy.mode === "allow" && !policy.origins.has(origin)) throw new HttpError(403, "This relay does not accept requests from that relay");
  if (isOwnOrigin(env, request, origin)) throw new HttpError(400, "A relay does not forward to itself");
  const ts = Number(request.headers.get(FEDERATION_HEADERS.ts) ?? "");
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > FEDERATION_WINDOW_MS) throw new HttpError(401, "Request timestamp outside the allowed window");
  const nonce = request.headers.get(FEDERATION_HEADERS.nonce) ?? "";
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(nonce)) throw new HttpError(401, "X-Clairix-Relay-Nonce required");
  const signature = request.headers.get(FEDERATION_HEADERS.sig) ?? "";
  if (signature.length === 0) throw new HttpError(401, "X-Clairix-Relay-Sig required");
  const url = new URL(request.url);
  const payload = federationPayload(origin, ownOrigin(env, request), ts, nonce, request.method, url.pathname, await sha256Hex(bodyText));
  return { origin, ts, nonce, signature, payload };
}

/** Fetches the signing key the relay at [origin] publishes at `GET /`; null when it publishes none. */
export async function fetchRelayKey(origin: string): Promise<string | null> {
  try {
    const res = await fetch(`${origin}/`, {
      headers: { accept: "application/json" },
      redirect: "manual",
      signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    const info = (await res.json()) as { relayKey?: unknown };
    if (typeof info.relayKey !== "string") return null;
    return base64ToBytes(info.relayKey).length === 32 ? info.relayKey : null;
  } catch {
    return null;
  }
}

export async function verifyFederated(publicKey: string, req: FederatedRequest): Promise<boolean> {
  return verifyEd25519(publicKey, req.payload, req.signature);
}

/** The object that holds what this relay keeps about another relay: its key, recent nonces and allowances. */
export async function federationObjectName(origin: string): Promise<string> {
  return `fed:${(await sha256Hex(origin)).slice(0, 32)}`;
}
