/**
 * Pure comms logic shared by the Worker, the Mailbox object and their tests:
 * anonymous delivery tokens, one-time-key claims, push endpoint checks and
 * TURN credential parsing. Only Web Crypto and plain data here, so
 * `node --test` can load this file directly.
 */

import { hex } from "./env";

// ── Delivery tokens ─────────────────────────────────────────────────────────
//
// Sending an envelope is not signed. A registered phone asks for a batch of
// random single-use tokens over its signed channel; the relay stores only each
// token's SHA-256, in a shard object named by the hash, and nothing that links
// a token to the account it went to. A send presents one token and no identity,
// so what the relay keeps about a send is a recipient and a spent hash.

/** 32 random bytes, base64url without padding. */
export const DELIVERY_TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;
/** How long an issued token can be spent. */
export const DELIVERY_TOKEN_TTL_MS = 14 * 24 * 60 * 60_000;
/** Most tokens handed out in one request. */
export const DELIVERY_TOKEN_BATCH_MAX = 100;
/**
 * Tokens per account: a refill of 40 a minute (2,400 an hour) and up to 1,000
 * saved up. A 5 MB video is 171 envelopes and a message to a full group 199, so
 * a burst covers either; a script cannot flood anyone faster than this.
 */
export const DELIVERY_TOKEN_PER_MINUTE = 40;
export const DELIVERY_TOKEN_BURST = 1000;

export function base64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function randomToken(): string {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

/** SHA-256 of a token (or claim ticket) as hex: what the relay stores. */
export async function tokenHash(token: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
}

/** The object a token hash lives in: one of 256, by its first byte. */
export function tokenShardName(hashHex: string): string {
  return `dtok:${hashHex.slice(0, 2)}`;
}

/** Groups token hashes by the shard object that keeps them. */
export function groupByShard(hashes: string[]): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const h of hashes) {
    const name = tokenShardName(h);
    const list = out.get(name);
    if (list) list.push(h);
    else out.set(name, [h]);
  }
  return out;
}

// ── One-time-key claims ─────────────────────────────────────────────────────
//
// A bundle lookup takes one of the owner's one-time keys out of the pool and
// hands it out with a claim ticket. The send that starts the session presents
// the ticket, which spends the key for good. A key whose ticket is not
// presented within the window goes back into the pool, so looking numbers up
// without ever writing to them cannot drain anyone's keys.

export interface SignedKey {
  id: string;
  key: string;
  signature: string;
}

export interface OtkClaim {
  /** SHA-256 (hex) of the ticket handed out with the key. */
  ticket: string;
  key: SignedKey;
  at: number;
}

/** How long a claimed key waits for the send that uses it. */
export const OTK_CLAIM_WINDOW_MS = 15 * 60_000;
/** One-time keys one requester may claim from one target: three an hour. */
export const OTK_CLAIMS_PER_PAIR_PER_MINUTE = 3 / 60;
export const OTK_CLAIMS_PER_PAIR_BURST = 3;
/** One-time keys a target hands out in all: sixty an hour, twenty at once. */
export const OTK_CLAIMS_PER_TARGET_PER_MINUTE = 1;
export const OTK_CLAIMS_PER_TARGET_BURST = 20;
/** Claim-rate buckets older than this are deleted by the daily cleanup. */
export const OTK_PAIR_BUCKET_TTL_MS = 24 * 60 * 60_000;

/** Splits claims into those still waiting and those whose window has passed. */
export function expireClaims(claims: OtkClaim[], now: number, windowMs = OTK_CLAIM_WINDOW_MS): { kept: OtkClaim[]; expired: OtkClaim[] } {
  const kept: OtkClaim[] = [];
  const expired: OtkClaim[] = [];
  for (const c of claims) (now - c.at >= windowMs ? expired : kept).push(c);
  return { kept, expired };
}

/** The rate-bucket name for one requester claiming from one target; the target appears only hashed. */
export async function pairBucket(target: string): Promise<string> {
  return `claim:${(await tokenHash(`otk-pair|${target}`)).slice(0, 32)}`;
}

// ── Push endpoints ──────────────────────────────────────────────────────────

/** Push hosts trusted without a check: ntfy's public server and Conversations' push service. */
export const DEFAULT_PUSH_HOSTS: readonly string[] = ["ntfy.sh", "up.conversations.im"];
export const PUSH_ENDPOINT_MAX = 2048;
/** How long the code sent through an unknown push host can be echoed back. */
export const PUSH_CHALLENGE_TTL_MS = 10 * 60_000;
/** The body of the check push; the app recognises the prefix and echoes the code. */
export const PUSH_CHALLENGE_PREFIX = "clairix-verify:";

export type PushCheck = { ok: true; url: string; trusted: boolean } | { ok: false; reason: string };

/** Host names that never belong to a push service reachable from the internet. */
const PRIVATE_SUFFIXES = [".localhost", ".local", ".internal", ".lan", ".home", ".arpa", ".intranet", ".corp", ".localdomain"];

function isIpLiteral(host: string): boolean {
  if (host.startsWith("[") || host.includes(":")) return true;
  // Dotted decimal, and the shortened or numeric forms URL parsing also accepts.
  return /^[0-9.]+$/.test(host) || /^0x[0-9a-f]+$/i.test(host);
}

/** The trusted push hosts: the defaults plus the operator's PUSH_HOSTS. */
export function trustedPushHosts(extra: string | undefined): Set<string> {
  const hosts = new Set(DEFAULT_PUSH_HOSTS);
  for (const raw of (extra ?? "").split(",")) {
    const h = raw.trim().toLowerCase().replace(/\.$/, "");
    if (/^[a-z0-9.-]+\.[a-z0-9-]+$/.test(h)) hosts.add(h);
  }
  return hosts;
}

/**
 * Whether [raw] may be used as a push endpoint. It must be an https URL to a
 * public DNS name, without credentials. On a trusted host it is used at once;
 * anywhere else ([trusted] false) only after the phone echoes a code sent to it.
 */
export function checkPushEndpoint(raw: unknown, extraHosts: string | undefined): PushCheck {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > PUSH_ENDPOINT_MAX || /\s/.test(raw)) {
    return { ok: false, reason: "endpoint must be an https URL" };
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { ok: false, reason: "endpoint must be an https URL" };
  }
  if (url.protocol !== "https:") return { ok: false, reason: "endpoint must be an https URL" };
  if (url.username || url.password) return { ok: false, reason: "endpoint must not carry credentials" };
  const host = url.hostname.toLowerCase().replace(/\.$/, "");
  if (isIpLiteral(host)) return { ok: false, reason: "endpoint must name a host, not an address" };
  if (!host.includes(".") || host === "localhost" || PRIVATE_SUFFIXES.some((s) => host.endsWith(s))) {
    return { ok: false, reason: "endpoint must be on a public host" };
  }
  const trusted = trustedPushHosts(extraHosts).has(host) && (url.port === "" || url.port === "443");
  return { ok: true, url: url.toString(), trusted };
}

// ── TURN ────────────────────────────────────────────────────────────────────

/** An ICE server entry as WebRTC clients consume it. */
export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

export const STUN_ONLY: IceServer[] = [{ urls: ["stun:stun.cloudflare.com:3478"] }];
/** TURN credentials are minted for each request and live this long: long enough for a long call, no more. */
export const TURN_TTL_S = 2 * 60 * 60;

/** The ICE servers in a Cloudflare Realtime generate-ice-servers response. */
export function parseIceServers(body: unknown): IceServer[] {
  const raw = (body as { iceServers?: unknown } | null)?.iceServers;
  const list = Array.isArray(raw) ? raw : raw ? [raw] : [];
  const servers: IceServer[] = [];
  for (const item of list as Array<{ urls?: unknown; username?: unknown; credential?: unknown }>) {
    if (!item || typeof item !== "object") continue;
    const urls = typeof item.urls === "string" ? [item.urls] : Array.isArray(item.urls) ? item.urls.filter((u): u is string => typeof u === "string") : [];
    if (urls.length === 0) continue;
    const entry: IceServer = { urls };
    if (typeof item.username === "string") entry.username = item.username;
    if (typeof item.credential === "string") entry.credential = item.credential;
    servers.push(entry);
  }
  return servers;
}
