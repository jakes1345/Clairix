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

// ── Token kinds ─────────────────────────────────────────────────────────────
//
// The same mechanism authorises every request the relay must not be able to
// tie to an account: sends, and everything about traffic. Each kind has its
// own shards (a traffic token cannot buy a send, nor the other way round) and
// its own allowance per account per hour, which is the only per-phone bound
// the anonymous routes have.

export type TokenKind = "delivery" | "traffic" | "report";

export interface TokenKindSpec {
  /** Shard object name prefix: one of 256 objects per kind, by the hash's first byte. */
  shard: string;
  /** Issued per account per hour, and the most saved up. */
  perHour: number;
  burst: number;
}

export const TOKEN_KINDS: Record<TokenKind, TokenKindSpec> = {
  /** A 5 MB video is 171 envelopes and a message to a full group 199; a script cannot flood anyone faster than this. */
  delivery: { shard: "dtok", perHour: 1000, burst: 1000 },
  /** A navigating phone spends about four a minute: a probe batch, a live poll, a provider poll, the odd vote. */
  traffic: { shard: "ttok", perHour: 360, burst: 400 },
  /** New incident reports: a person makes a handful an hour; a script that makes more is what this stops. */
  report: { shard: "rtok", perHour: 20, burst: 30 },
};

export function isTokenKind(raw: unknown): raw is TokenKind {
  return raw === "delivery" || raw === "traffic" || raw === "report";
}

/**
 * A trip pseudonym: 16 random bytes the phone makes up for a drive and
 * changes every few minutes. Probes, reports and votes carry it so the
 * traffic object can count distinct vehicles and take one vote per vehicle,
 * without anything that reaches back to a phone.
 */
export const TRIP_RE = /^[A-Za-z0-9_-]{22}$/;

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

/** The object a token hash lives in: one of 256 per kind, by its first byte. */
export function tokenShardName(hashHex: string, kind: TokenKind = "delivery"): string {
  return `${TOKEN_KINDS[kind].shard}:${hashHex.slice(0, 2)}`;
}

/** Groups token hashes by the shard object that keeps them. */
export function groupByShard(hashes: string[], kind: TokenKind = "delivery"): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const h of hashes) {
    const name = tokenShardName(h, kind);
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

/**
 * What a sender may ask a recipient's phone to do with an envelope it cannot
 * open yet (the phone is locked, so its keys are out of reach): ring for a
 * call, or sound the alarm for an SOS. The relay learns no more from it than
 * the timing of a call set-up or an SOS fan-out already shows.
 */
export type Alert = "call" | "sos";
export const ALERTS: readonly Alert[] = ["call", "sos"];

/**
 * A phone's current sealing prekey (envelope format 3): an X25519 key it
 * replaces every week, signed with its identity key over [prekeyStatement].
 * Envelopes to the phone are sealed to it, and once the phone deletes an old
 * one, envelopes logged earlier no longer show who sent them.
 */
export interface SealingPrekey {
  key: string;
  /** When the phone made it, ms since the epoch. */
  created: number;
  signature: string;
}

/** What a sealing prekey's signature covers; the phone's crypto signs the same text. */
export function prekeyStatement(key: string, created: number): string {
  return `clairix-sealing-prekey-v1|${key}|${created}`;
}

/** A prekey older than this is not taken: senders would refuse to seal to it anyway. */
export const PREKEY_MAX_AGE_MS = 14 * 24 * 60 * 60_000;
/** Nor one from further in the future than this. */
export const PREKEY_FUTURE_SKEW_MS = 24 * 60 * 60_000;

export interface OtkClaim {
  /** SHA-256 (hex) of the ticket handed out with the key. */
  ticket: string;
  key: SignedKey;
  at: number;
}

/** How long a claimed key waits for the send that uses it. */
export const OTK_CLAIM_WINDOW_MS = 15 * 60_000;
/**
 * One-time keys one requester may claim in all, from anyone: ten an hour.
 * The allowance is the requester's own and names no target.
 */
export const OTK_CLAIMS_PER_REQUESTER_PER_HOUR = 10;
export const OTK_CLAIMS_PER_REQUESTER_BURST = 10;
/**
 * One-time keys one requester may claim from one target: three an hour. Kept
 * only in the requester's object's memory, never in storage, so the relay
 * keeps no record of who looked whom up (a hash of a nine-digit number would
 * be no protection: there are only a billion of them).
 */
export const OTK_CLAIMS_PER_PAIR_PER_MINUTE = 3 / 60;
export const OTK_CLAIMS_PER_PAIR_BURST = 3;
/** One-time keys a target hands out in all: twenty an hour. */
export const OTK_CLAIMS_PER_TARGET_PER_HOUR = 20;
export const OTK_CLAIMS_PER_TARGET_BURST = 20;
/**
 * How long the ticket of a claim whose window passed is remembered. A send
 * that presents it late (the phone was offline) still spends the key, which
 * by then is back in the pool and must not be handed out again.
 */
export const OTK_LAPSED_TICKET_TTL_MS = 30 * 24 * 60 * 60_000;
export const OTK_LAPSED_TICKETS_MAX = 2000;

/** A claim whose window passed: its ticket and the id of the key that went back into the pool. */
export interface LapsedClaim {
  ticket: string;
  keyId: string;
  /** The day (not the moment) the window passed, for the ticket's own expiry. */
  at: number;
}

export const DAY_MS = 24 * 60 * 60_000;
export const HOUR_MS = 60 * 60_000;
export const MINUTE_MS = 60_000;

/** [nowMs] rounded down to the start of its UTC day. */
export function dayFloor(nowMs: number): number {
  return Math.floor(nowMs / DAY_MS) * DAY_MS;
}

/** [nowMs] rounded down to the minute. */
export function minuteFloor(nowMs: number): number {
  return Math.floor(nowMs / MINUTE_MS) * MINUTE_MS;
}

/** Splits claims into those still waiting and those whose window has passed. */
export function expireClaims(claims: OtkClaim[], now: number, windowMs = OTK_CLAIM_WINDOW_MS): { kept: OtkClaim[]; expired: OtkClaim[] } {
  const kept: OtkClaim[] = [];
  const expired: OtkClaim[] = [];
  for (const c of claims) (now - c.at >= windowMs ? expired : kept).push(c);
  return { kept, expired };
}

/**
 * An in-memory token bucket per key, for limits whose keys must never be
 * written to storage. It forgets everything when the object is evicted, which
 * at worst lets a requester claim a few more keys sooner; the stored
 * per-requester and per-target limits still hold.
 */
export class MemoryBuckets {
  private readonly buckets = new Map<string, { tokens: number; at: number }>();
  private readonly perMinute: number;
  private readonly burst: number;
  private readonly maxKeys: number;

  constructor(perMinute: number, burst: number, maxKeys = 1000) {
    this.perMinute = perMinute;
    this.burst = burst;
    this.maxKeys = maxKeys;
  }

  take(key: string, now: number): boolean {
    const b = this.buckets.get(key) ?? { tokens: this.burst, at: now };
    b.tokens = Math.min(this.burst, b.tokens + ((now - b.at) * this.perMinute) / 60_000);
    b.at = now;
    const ok = b.tokens >= 1;
    if (ok) b.tokens -= 1;
    this.buckets.delete(key);
    this.buckets.set(key, b);
    // Oldest first: the map keeps insertion order, and every use re-inserts.
    while (this.buckets.size > this.maxKeys) {
      const oldest = this.buckets.keys().next().value;
      if (oldest === undefined) break;
      this.buckets.delete(oldest);
    }
    return ok;
  }
}

// ── Hourly allowances ────────────────────────────────────────────────────────
//
// A limit that must survive the object being evicted is stored, and what is
// stored about it is kept to the hour: the bucket remembers the hour it was
// last drawn on, not the moment, refills at the top of each hour, and is
// deleted once it is full again. So the relay holds "drawn on in hour X" for
// as long as the allowance takes to come back, and nothing after that.

export interface HourlyBucket {
  tokens: number;
  /** Hours since the epoch of the last draw. */
  hour: number;
  perHour: number;
  burst: number;
}

export function hourOf(nowMs: number): number {
  return Math.floor(nowMs / HOUR_MS);
}

/** [b], or a fresh full bucket, refilled to [nowMs]. */
export function hourlyRefill(b: HourlyBucket | null, nowMs: number, perHour: number, burst: number): HourlyBucket {
  const hour = hourOf(nowMs);
  if (!b) return { tokens: burst, hour, perHour, burst };
  const tokens = Math.min(burst, Math.max(0, b.tokens) + Math.max(0, hour - b.hour) * perHour);
  return { tokens, hour, perHour, burst };
}

/**
 * Draws up to [want] from the bucket: as many as it holds, and nothing with
 * a Retry-After (seconds until it next holds one) when it holds none.
 */
export function hourlyTake(
  b: HourlyBucket | null,
  nowMs: number,
  perHour: number,
  burst: number,
  want = 1,
): { granted: number; bucket: HourlyBucket; retryAfter: number } {
  const bucket = hourlyRefill(b, nowMs, perHour, burst);
  const granted = Math.max(0, Math.min(Math.floor(want), Math.floor(bucket.tokens)));
  bucket.tokens -= granted;
  let retryAfter = 0;
  if (granted === 0) {
    const hoursNeeded = perHour > 0 ? Math.max(1, Math.ceil((1 - bucket.tokens) / perHour)) : 1;
    retryAfter = Math.max(1, Math.ceil(((bucket.hour + hoursNeeded) * HOUR_MS - nowMs) / 1000));
  }
  return { granted, bucket, retryAfter };
}

/** Whether the bucket would be full again now: nothing left worth remembering. */
export function hourlyFull(b: HourlyBucket, nowMs: number): boolean {
  return hourlyRefill(b, nowMs, b.perHour, b.burst).tokens >= b.burst;
}

/**
 * When a batch of delivery tokens expires: the TTL plus up to twelve hours of
 * jitter, rounded down to the hour, so the expiry the relay returns (and
 * stores with every hash) does not give away the second the batch was issued.
 */
export function tokenBatchExpiry(now: number, random: () => number = Math.random): number {
  const hour = 60 * 60_000;
  const jitter = Math.floor(random() * 12 * hour);
  return Math.floor((now + DELIVERY_TOKEN_TTL_MS + jitter) / hour) * hour;
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
/**
 * TURN credentials an account may mint an hour: one per call attempt, so a
 * person never meets it, kept to the hour like every slow allowance so the
 * mailbox records no moment at which a call was placed.
 */
export const TURN_PER_HOUR = 30;
export const TURN_BURST = 30;

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
