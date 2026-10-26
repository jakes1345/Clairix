import { DurableObject } from "cloudflare:workers";
import { verifyEd25519 } from "./auth";
import type { Env } from "./env";
import {
  DAY_MS,
  DELIVERY_TOKEN_TTL_MS,
  dayFloor,
  expireClaims,
  hourlyFull,
  hourlyTake,
  type HourlyBucket,
  MemoryBuckets,
  minuteFloor,
  OTK_CLAIMS_PER_PAIR_BURST,
  OTK_CLAIMS_PER_PAIR_PER_MINUTE,
  OTK_CLAIMS_PER_REQUESTER_BURST,
  OTK_CLAIMS_PER_REQUESTER_PER_HOUR,
  OTK_CLAIMS_PER_TARGET_BURST,
  OTK_CLAIMS_PER_TARGET_PER_HOUR,
  OTK_LAPSED_TICKET_TTL_MS,
  OTK_LAPSED_TICKETS_MAX,
  type LapsedClaim,
  PUSH_CHALLENGE_TTL_MS,
  randomToken,
  tokenHash,
  type Alert,
  type OtkClaim,
  type SealingPrekey,
  type SignedKey,
} from "./comms-logic";

export type { SignedKey } from "./comms-logic";

/**
 * One Durable Object per Clairix number: the device's published keys, its queue
 * of sealed envelopes, its live WebSocket(s) and its push endpoint.
 *
 * The relay never sees a message: envelopes are opaque bytes sealed to the
 * owner's sealing key, and they carry no sender, not even in the request that
 * delivered them. Sending is not signed: it spends a single-use delivery token
 * (see comms-logic.ts), and the token's hash is kept in a separate shard object
 * with nothing that links it to the account it was issued to. What this object
 * knows is the owner's public keys (which are public), how many envelopes are
 * waiting, the one-time keys handed out and not yet used, and the push endpoint
 * the owner chose to be woken through.
 *
 * What it does not know is when its owner was last here. Nothing about a
 * request is written beyond its nonce (kept five minutes, to the minute), and
 * the allowances that have to be stored remember the hour they were drawn on,
 * not the moment, and only until they have refilled (see comms-logic.ts).
 *
 * The same class also serves a few objects that are not mailboxes, each named
 * after what it holds: "invite:<code>", "register-guard:<address>" and the
 * token shards "dtok:<xx>" (delivery), "ttok:<xx>" (traffic) and "rtok:<xx>"
 * (incident reports).
 */

export interface Profile {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  /** Signature by ed25519 over `curve25519|sealing`, so a relay cannot swap keys. */
  signature: string;
  fallback: SignedKey | null;
  /** The current sealing prekey; absent for apps from before envelope format 3. */
  sealingPrekey?: SealingPrekey | null;
  /**
   * The phone's transport key (Ed25519, base64): it signs only the requests
   * that fetch, acknowledge and stream envelopes, so a locked phone can
   * collect what it cannot yet open. It reads nothing and sends nothing.
   */
  transportKey?: string | null;
  /** Whether the number can be looked up. Unlisted numbers are reached by QR only. */
  listed: boolean;
  createdAt: number;
  /** Whether this account's keys are recorded as its own (see Mailbox.claimKey); accounts from before that are claimed on their next request. */
  keysClaimed?: boolean;
}

export interface Envelope {
  id: string;
  ts: number;
  /** Base64 of the sealed envelope bytes. */
  data: string;
  /** Present when the sender asked a locked phone to ring or sound an SOS for it. */
  alert?: Alert;
}

/** What a lookup returns: the identity and one key to start a session with. */
export interface Bundle {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  signature: string;
  sealingPrekey: SealingPrekey | null;
  oneTimeKey: SignedKey | null;
  fallback: SignedKey | null;
  /**
   * With a one-time key: the ticket the send that starts the session presents.
   * Unpresented within the claim window, the key goes back into the pool.
   */
  claim: string | null;
}

const MAX_QUEUE = 2000;
/**
 * The largest envelope: a plaintext padded to the top 64 KiB bucket, sealed,
 * fits (see comms-crypto's padding). Media chunks sit well below it.
 */
const MAX_ENVELOPE_BYTES = 72 * 1024;
/** The longest base64 envelope a mailbox takes; checked before anything else, for every number alike. */
export const MAX_ENVELOPE_B64 = Math.floor((MAX_ENVELOPE_BYTES * 4) / 3) + 4;
/**
 * What envelopes forwarded by other relays may use of a mailbox, all other
 * relays together: an hourly allowance of their own, and a queue that stops
 * short of full, so another relay (which spends no delivery tokens here) can
 * neither exhaust the allowance this relay's own senders draw on nor fill the
 * mailbox against them.
 *
 * Over what goes past it is dropped without a word (see enqueue), so it must
 * hold everything one relay can hand one mailbox in a clock hour with room to
 * spare: federation.ts holds each relay to FED_SENDS_PER_TARGET_BURST plus an
 * hour of FED_SENDS_PER_TARGET_PER_MINUTE per recipient, 2,100, about a third
 * of this. Then a contact on another relay is never silently dropped (a long
 * video is a few hundred envelopes), and it takes several relays flooding
 * together to crowd out the owner's other contacts.
 */
const FED_INBOUND_PER_HOUR = 6_000;
const FED_INBOUND_BURST = 6_000;
const FED_QUEUE_RESERVE = 500;
/**
 * Envelopes from relays no one here has written to, per mailbox: enough for
 * first messages from new contacts elsewhere, small enough that relays made up
 * for a flood cannot take the allowance relays people do write to rely on.
 */
const FED_STRANGER_PER_HOUR = 30;
const FED_STRANGER_BURST = 10;

/** The answer to a send for a number that does not exist; the caller accepts and drops it. */
const NO_SUCH_NUMBER: EnqueueResult = { ok: false, reason: "no such number", status: 404 };
/** An envelope from another relay dropped at a limit: answered as NO_SUCH_NUMBER is. */
const DROPPED: EnqueueResult = NO_SUCH_NUMBER;
const ENVELOPE_TTL_MS = 30 * 24 * 60 * 60_000;
/** One-time keys a mailbox holds; the app keeps a few hundred published so lookups cannot empty it. */
const MAX_ONE_TIME_KEYS = 500;
const NONCE_WINDOW_MS = 5 * 60_000;
const LOOKUP_LIMIT_PER_MINUTE = 30;
/**
 * Envelopes into one mailbox, from everyone together: the relay does not know
 * who sends, so the recipient is protected here and by MAX_QUEUE. A
 * reconnecting contact sends a receipt for every message it missed, so bursts
 * are normal. Stored to the hour, so the burst is what any one hour allows;
 * above FED_INBOUND_BURST, since other relays' envelopes past it are dropped
 * without a word too.
 */
const INBOUND_PER_HOUR = 36_000;
const INBOUND_BURST = 9_000;
/** Registration attempts per client address, against guessing the enrollment secret. */
const REGISTER_LIMIT_PER_MINUTE = 10;
/** After one push wake, further envelopes do not wake again until the phone has fetched, or this long. */
const WAKE_COALESCE_MS = 30_000;
/** One-time key ids already used, remembered so a key is never offered twice. */
const MAX_CLAIMED_IDS = 5_000;
const CLEANUP_INTERVAL_MS = 24 * 60 * 60_000;
/** How long a live socket has to ack an envelope before the owner is also woken by push. */
const UNACKED_WAKE_MS = 8_000;
/**
 * A connected phone hears from the relay at least this often. The heartbeat
 * keeps carrier NAT from forgetting an idle connection, and briefly wakes a
 * sleeping phone, which lets it notice a connection that has died; the app
 * reconnects when it has heard nothing for a few heartbeats.
 */
const HEARTBEAT_MS = 120_000;
/** Durable Object storage deletes at most this many keys per call. */
const DELETE_BATCH = 128;
/** How long an invite can be used. */
export const INVITE_TTL_MS = 7 * 24 * 60 * 60_000;
/** ClairixCoin: transactions kept per mailbox, newest first. */
const MAX_TX_HISTORY = 100;
/** ClairixCoin: the most one transfer may move. */
const MAX_SINGLE_TRANSFER = 1_000_000;
/**
 * Mixing: the longest a non-urgent envelope is held before it is released to
 * its recipient, unless MIX_MAX_MS says otherwise ("0" turns mixing off). The
 * hold is drawn from an exponential distribution with a fifth of this as its
 * mean, cut off here.
 */
const MIX_MAX_MS_DEFAULT = 20_000;
/** Staged envelopes released by one alarm at most; the rest go on the next, at once. */
const MIX_RELEASE_BATCH = 500;
/** Storage writes per call stay under the platform's 128-key limit. */
const PUT_BATCH = 64;

/**
 * How long to hold one envelope: exponential with mean [max]/5, at most [max].
 * The memoryless distribution means how long an envelope has already waited
 * says nothing about when it leaves, so departures do not line up with arrivals.
 */
export function mixDelay(max: number, uniform: number = randomUnit()): number {
  if (max <= 0) return 0;
  const mean = max / 5;
  const u = Math.min(Math.max(uniform, Number.MIN_VALUE), 1);
  return Math.min(max, Math.max(0, Math.round(-mean * Math.log(u))));
}

/** A uniform number in (0, 1] from the platform's CSPRNG. */
function randomUnit(): number {
  const word = crypto.getRandomValues(new Uint32Array(1))[0] ?? 0;
  return (word + 1) / 2 ** 32;
}

/** The hold limit [env] sets, in milliseconds; 0 means mixing is off. */
export function mixMaxMs(env: Pick<Env, "MIX_MAX_MS">): number {
  const raw = env.MIX_MAX_MS?.trim();
  if (raw === undefined || raw === "") return MIX_MAX_MS_DEFAULT;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? Math.min(Math.round(n), 120_000) : MIX_MAX_MS_DEFAULT;
}

/** Fisher-Yates over the platform's CSPRNG. */
function shuffle<T>(items: T[]): T[] {
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(randomUnit() * (i + 1)) % (i + 1);
    const t = items[i] as T;
    items[i] = items[j] as T;
    items[j] = t;
  }
  return items;
}

/** The storage key of an envelope held for mixing: sorts by release time. */
function stagedKey(releaseAt: number, id: string): string {
  return `m:${releaseAt.toString(36).padStart(10, "0")}:${id}`;
}

function stagedReleaseAt(key: string): number {
  return parseInt(key.slice(2, 12), 36);
}

/** An invite: one registration without the enrollment secret. Stored in its own object, named after its code. */
interface Invite {
  inviter: string;
  expiresAt: number;
  used: boolean;
}

interface NonceRecord {
  [nonce: string]: number;
}

/** Envelopes handed to a socket that must be acked by `due`, or the owner is woken by push. */
interface WakeCheck {
  due: number;
  ids: string[];
}

/** A push endpoint on an untrusted host, waiting for the owner to echo the code sent through it. */
interface PendingPush {
  endpoint: string;
  codeHash: string;
  expiresAt: number;
}

/** An envelope held for mixing, released to the queue at [releaseAt]. */
interface StagedEnvelope {
  envelope: Envelope;
  releaseAt: number;
}

export type EnqueueResult =
  | { ok: true; id: string }
  | { ok: false; reason: string; status: 404 | 413 | 429; retryAfter?: number };

/** Constant-time comparison of two hex digests. */
function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** How long an issued delivery token can be spent; re-exported for the Worker. */
export const TOKEN_TTL_MS = DELIVERY_TOKEN_TTL_MS;

/**
 * One ClairixCoin transfer as this mailbox's owner saw it. Every relay is its
 * own coin community: a balance lives in the owner's mailbox and moves only
 * through requests signed by the owner's identity key. The relay learns who
 * paid whom and how much; the note the payer wrote travels end-to-end
 * encrypted, so what is kept here is only the note the payer chose to give the
 * relay (the app sends none).
 */
export interface CoinTx {
  id: string;
  from: string;
  to: string;
  amount: number;
  ts: number;
  direction: "in" | "out";
  note?: string;
}

/** A contact's public identity, without claiming a one-time key. */
export interface IdentityKeys {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  signature: string;
  sealingPrekey: SealingPrekey | null;
}

export class Mailbox extends DurableObject<Env> {
  // ── Registration and keys ────────────────────────────────────────────

  /** Claims this number for a profile. False when the number is already taken. */
  async claim(profile: Profile, oneTimeKeys: SignedKey[]): Promise<boolean> {
    const existing = await this.ctx.storage.get<Profile>("profile");
    if (existing) return false;
    await this.ctx.storage.put("profile", profile);
    await this.ctx.storage.put("otks", oneTimeKeys.slice(0, MAX_ONE_TIME_KEYS));
    await this.ctx.storage.put("cleanupAt", Date.now() + CLEANUP_INTERVAL_MS);
    await this.scheduleAlarm();
    return true;
  }

  // ── Key ownership (in objects of their own, named "key:<sha256 of the key>") ──

  /**
   * Records that [holder] registered this key. False when another number
   * already did: one identity, Curve25519 or sealing key belongs to one
   * account, so nobody can register a key that is someone else's and have
   * their contacts' phones bind it to the wrong identity.
   */
  async claimKey(holder: string): Promise<boolean> {
    const current = await this.ctx.storage.get<string>("keyHolder");
    if (current !== undefined && current !== holder) return false;
    if (current === undefined) await this.ctx.storage.put("keyHolder", holder);
    return true;
  }

  /** Gives the key back when [holder]'s account is deleted. */
  async releaseKey(holder: string): Promise<void> {
    if ((await this.ctx.storage.get<string>("keyHolder")) === holder) await this.ctx.storage.deleteAll();
  }

  async markKeysClaimed(): Promise<void> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile || profile.keysClaimed) return;
    profile.keysClaimed = true;
    await this.ctx.storage.put("profile", profile);
  }

  // ── Invites (in an object of their own, named "invite:<code>") ────────

  async createInvite(inviter: string, expiresAt: number): Promise<void> {
    await this.ctx.storage.put<Invite>("invite", { inviter, expiresAt, used: false });
    // Deleted once expired, used or not.
    await this.ctx.storage.setAlarm(expiresAt);
  }

  /**
   * Uses the invite up. One object handles one call at a time, so two
   * registrations racing for the same code cannot both succeed.
   */
  async redeemInvite(): Promise<{ ok: true; inviter: string } | { ok: false; reason: string }> {
    const invite = await this.ctx.storage.get<Invite>("invite");
    if (!invite) return { ok: false, reason: "This invite does not exist or has expired" };
    if (invite.used) return { ok: false, reason: "This invite has already been used; ask for a new one" };
    if (invite.expiresAt <= Date.now()) return { ok: false, reason: "This invite has expired; ask for a new one" };
    await this.ctx.storage.put<Invite>("invite", { ...invite, used: true });
    return { ok: true, inviter: invite.inviter };
  }

  /** Gives the invite back when the registration it was redeemed for did not go through. */
  async releaseInvite(): Promise<void> {
    const invite = await this.ctx.storage.get<Invite>("invite");
    if (invite) await this.ctx.storage.put<Invite>("invite", { ...invite, used: false });
  }

  async profile(): Promise<Profile | null> {
    return (await this.ctx.storage.get<Profile>("profile")) ?? null;
  }

  /**
   * Checks a request signature against the owner's key and enforces the
   * single-use nonce. Returns the profile on success, null otherwise.
   */
  async authenticate(payload: string, signature: string, nonce: string, ts: number, transport = false): Promise<Profile | null> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return null;
    const key = transport ? profile.transportKey : profile.ed25519;
    if (!key || !(await verifyEd25519(key, payload, signature))) return null;
    const nonces = (await this.ctx.storage.get<NonceRecord>("nonces")) ?? {};
    const now = Date.now();
    for (const [n, seen] of Object.entries(nonces)) if (now - seen > NONCE_WINDOW_MS) delete nonces[n];
    if (nonces[nonce] !== undefined) return null;
    // Only the nonces are written, each with the minute it arrived (enough to
    // expire it): no "last seen" is kept, so the object does not record when
    // its owner was active, and nothing outlives the replay window.
    nonces[nonce] = minuteFloor(now);
    await this.ctx.storage.put("nonces", nonces);
    return profile;
  }

  /**
   * Replaces the fallback key, adds one-time keys, and takes a sealing prekey
   * newer than the one held (an older one, replayed, is ignored).
   */
  async putKeys(oneTimeKeys: SignedKey[], fallback: SignedKey | null, sealingPrekey: SealingPrekey | null = null): Promise<{ oneTimeKeys: number }> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) throw new Error("unregistered");
    const existing = (await this.ctx.storage.get<SignedKey[]>("otks")) ?? [];
    // A key already handed out is spent on the owner's phone; offering it again
    // would make the next contact's first message undecryptable.
    const claimed = new Set((await this.ctx.storage.get<string[]>("claimedOtks")) ?? []);
    for (const c of (await this.ctx.storage.get<OtkClaim[]>("claims")) ?? []) claimed.add(c.key.id);
    const known = new Set(existing.map((k) => k.id));
    const merged = existing.concat(oneTimeKeys.filter((k) => !known.has(k.id) && !claimed.has(k.id))).slice(-MAX_ONE_TIME_KEYS);
    if (fallback) profile.fallback = fallback;
    if (sealingPrekey && sealingPrekey.created > (profile.sealingPrekey?.created ?? -1)) profile.sealingPrekey = sealingPrekey;
    await this.ctx.storage.put({ otks: merged, profile });
    return { oneTimeKeys: merged.length };
  }

  async oneTimeKeyCount(): Promise<number> {
    return (await this.releaseExpiredClaims()).length;
  }

  /**
   * Puts the keys of claims whose window has passed back at the end of the
   * pool, and returns the pool. A claim nobody followed up costs the owner
   * nothing. Each lapsed ticket is remembered with its key's id, so a send
   * that presents it late still takes the key out of the pool (see
   * [confirmClaim]); at the end of the pool the key is the last to be offered
   * again in the meantime.
   */
  private async releaseExpiredClaims(now = Date.now()): Promise<SignedKey[]> {
    const otks = (await this.ctx.storage.get<SignedKey[]>("otks")) ?? [];
    const claims = (await this.ctx.storage.get<OtkClaim[]>("claims")) ?? [];
    if (claims.length === 0) return otks;
    const { kept, expired } = expireClaims(claims, now);
    if (expired.length === 0) return otks;
    const pool = otks.concat(expired.map((c) => c.key)).slice(-MAX_ONE_TIME_KEYS);
    // A lapsed ticket is dated to the day: its expiry needs no more, and the
    // moment someone looked this number up is not worth keeping for a month.
    const lapsed = ((await this.ctx.storage.get<LapsedClaim[]>("lapsedClaims")) ?? [])
      .concat(expired.map((c) => ({ ticket: c.ticket, keyId: c.key.id, at: dayFloor(now) })))
      .filter((l) => now - l.at < OTK_LAPSED_TICKET_TTL_MS)
      .slice(-OTK_LAPSED_TICKETS_MAX);
    await this.ctx.storage.put({ otks: pool, claims: kept, lapsedClaims: lapsed });
    return pool;
  }

  /** Per-requester-per-target claim limits: in this object's memory only, never stored. */
  private pairClaims: MemoryBuckets | null = null;

  /**
   * Whether this mailbox's owner may claim a one-time key from [target] now:
   * within its own total (stored, naming no target) and within its limit for
   * that target (kept in memory only, so no contact graph is ever written).
   */
  async mayClaimFrom(target: string): Promise<boolean> {
    this.pairClaims ??= new MemoryBuckets(OTK_CLAIMS_PER_PAIR_PER_MINUTE, OTK_CLAIMS_PER_PAIR_BURST);
    if (!this.pairClaims.take(target, Date.now())) return false;
    return (await this.allowanceOk("otk-claims-out", OTK_CLAIMS_PER_REQUESTER_PER_HOUR, OTK_CLAIMS_PER_REQUESTER_BURST)).ok;
  }

  /**
   * The bundle a contact needs. With [mayClaim] (the requester is within its
   * own limit for this number) and this number within the limit of keys it
   * hands out, one one-time key is taken from the pool with a claim ticket;
   * otherwise, or when none are left, the fallback key is offered instead.
   */
  async bundle(requireListed: boolean, mayClaim = true): Promise<Bundle | null> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return null;
    if (requireListed && !profile.listed) return null;
    const now = Date.now();
    const otks = await this.releaseExpiredClaims(now);
    let oneTimeKey: SignedKey | null = null;
    let claim: string | null = null;
    if (mayClaim && otks.length > 0 && (await this.allowanceOk("otk-claims", OTK_CLAIMS_PER_TARGET_PER_HOUR, OTK_CLAIMS_PER_TARGET_BURST)).ok) {
      oneTimeKey = otks.shift() ?? null;
      if (oneTimeKey) {
        claim = randomToken();
        const claims = (await this.ctx.storage.get<OtkClaim[]>("claims")) ?? [];
        claims.push({ ticket: await tokenHash(claim), key: oneTimeKey, at: now });
        await this.ctx.storage.put({ otks, claims });
      }
    }
    return {
      number: profile.number,
      ed25519: profile.ed25519,
      curve25519: profile.curve25519,
      sealing: profile.sealing,
      signature: profile.signature,
      sealingPrekey: profile.sealingPrekey ?? null,
      oneTimeKey,
      fallback: oneTimeKey ? null : profile.fallback,
      claim,
    };
  }

  /**
   * Spends the one-time key claimed with [ticket], so it is never offered
   * again. A ticket presented after its window (the sender was offline) still
   * spends its key: the key is taken back out of the pool it returned to.
   * False when no claim, current or lapsed, matches.
   */
  private async confirmClaim(ticket: string): Promise<boolean> {
    const hash = await tokenHash(ticket);
    const claims = (await this.ctx.storage.get<OtkClaim[]>("claims")) ?? [];
    const spent = (await this.ctx.storage.get<string[]>("claimedOtks")) ?? [];
    const i = claims.findIndex((c) => c.ticket === hash);
    if (i >= 0) {
      const [used] = claims.splice(i, 1);
      if (used) spent.push(used.key.id);
      await this.ctx.storage.put({ claims, claimedOtks: spent.slice(-MAX_CLAIMED_IDS) });
      return true;
    }
    const lapsed = (await this.ctx.storage.get<LapsedClaim[]>("lapsedClaims")) ?? [];
    const j = lapsed.findIndex((l) => l.ticket === hash);
    if (j < 0) return false;
    const [late] = lapsed.splice(j, 1);
    if (!late) return false;
    const otks = ((await this.ctx.storage.get<SignedKey[]>("otks")) ?? []).filter((k) => k.id !== late.keyId);
    spent.push(late.keyId);
    await this.ctx.storage.put({ otks, lapsedClaims: lapsed, claimedOtks: spent.slice(-MAX_CLAIMED_IDS) });
    return true;
  }

  /** The owner's public identity, for confirming who sent a message; claims no key. */
  async identity(requireListed: boolean): Promise<IdentityKeys | null> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return null;
    if (requireListed && !profile.listed) return null;
    return {
      number: profile.number,
      ed25519: profile.ed25519,
      curve25519: profile.curve25519,
      sealing: profile.sealing,
      signature: profile.signature,
      sealingPrekey: profile.sealingPrekey ?? null,
    };
  }

  /** Sets (or with null removes) the phone's transport key. */
  async setTransportKey(key: string | null): Promise<void> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) throw new Error("unregistered");
    profile.transportKey = key;
    await this.ctx.storage.put("profile", profile);
  }

  async setListed(listed: boolean): Promise<void> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) throw new Error("unregistered");
    profile.listed = listed;
    await this.ctx.storage.put("profile", profile);
  }

  /** Sets (or with null clears) the endpoint the owner is woken through; any pending check is dropped. */
  async setPush(endpoint: string | null): Promise<void> {
    await this.ctx.storage.delete("pushPending");
    if (endpoint) await this.ctx.storage.put("push", endpoint);
    else await this.ctx.storage.delete("push");
  }

  /**
   * Holds [endpoint] until the owner echoes the code that was sent through it
   * (whose hash is [codeHash]). The endpoint in use, if any, stays in use.
   */
  async setPendingPush(endpoint: string, codeHash: string): Promise<void> {
    await this.ctx.storage.put<PendingPush>("pushPending", { endpoint, codeHash, expiresAt: Date.now() + PUSH_CHALLENGE_TTL_MS });
  }

  /** Makes the pending endpoint the one in use when [codeHash] matches its code; false otherwise. */
  async verifyPush(codeHash: string): Promise<boolean> {
    const pending = await this.ctx.storage.get<PendingPush>("pushPending");
    if (!pending || pending.expiresAt <= Date.now() || !timingSafeEqualHex(pending.codeHash, codeHash)) return false;
    await this.ctx.storage.delete("pushPending");
    await this.ctx.storage.put("push", pending.endpoint);
    return true;
  }

  /** The endpoint in use (null when none); for tests and the owner's own view. */
  async pushEndpoint(): Promise<string | null> {
    return (await this.ctx.storage.get<string>("push")) ?? null;
  }

  // ── Delivery tokens (in shard objects of their own, named "dtok:<xx>") ──

  /** Stores issued token hashes until [expiresAt]. Nothing about who they went to is kept. */
  async addTokens(hashes: string[], expiresAt: number): Promise<void> {
    const entries: Record<string, number> = {};
    for (const h of hashes) entries[`t:${h}`] = expiresAt;
    for (let i = 0, keys = Object.keys(entries); i < keys.length; i += DELETE_BATCH) {
      const batch: Record<string, number> = {};
      for (const k of keys.slice(i, i + DELETE_BATCH)) batch[k] = expiresAt;
      await this.ctx.storage.put(batch);
    }
    if (!(await this.ctx.storage.get("tokenShard"))) await this.ctx.storage.put("tokenShard", true);
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null) await this.ctx.storage.setAlarm(Date.now() + CLEANUP_INTERVAL_MS);
  }

  /**
   * Records a blind token (tokens.ts) as spent, keyed by the hash of its kind,
   * epoch and input, until its key is retired. False when it was spent before.
   * The token's output has already been checked; nothing here knows who it was
   * issued to, because nothing anywhere does.
   */
  async spendBlindToken(hash: string, expiresAt: number): Promise<boolean> {
    const key = `s:${hash}`;
    if ((await this.ctx.storage.get<number>(key)) !== undefined) return false;
    await this.ctx.storage.put({ [key]: expiresAt, tokenShard: true });
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now() + CLEANUP_INTERVAL_MS);
    return true;
  }

  /** Spends the token with this hash. False when it is unknown, expired or already spent. */
  async redeemToken(hash: string): Promise<boolean> {
    const key = `t:${hash}`;
    const expiresAt = await this.ctx.storage.get<number>(key);
    if (expiresAt === undefined) return false;
    await this.ctx.storage.delete(key);
    return expiresAt > Date.now();
  }

  /** Drops expired token hashes, issued ("t:") and spent blind ones ("s:"), a page at a time; true when any are left. */
  private async cleanupTokens(now: number): Promise<boolean> {
    let left = 0;
    for (const prefix of ["t:", "s:"]) left += await this.cleanupTokenPrefix(prefix, now);
    return left > 0;
  }

  private async cleanupTokenPrefix(prefix: string, now: number): Promise<number> {
    let startAfter: string | undefined;
    let left = 0;
    for (;;) {
      const page = await this.ctx.storage.list<number>({ prefix, startAfter, limit: DELETE_BATCH });
      if (page.size === 0) break;
      const keys = [...page.keys()];
      const stale = [...page].filter(([, exp]) => exp <= now).map(([k]) => k);
      if (stale.length > 0) await this.deleteKeys(stale);
      left += keys.length - stale.length;
      startAfter = keys[keys.length - 1];
    }
    return left;
  }

  // ── Limits ───────────────────────────────────────────────────────────
  //
  // Two kinds. Minute-scale limits live in this object's memory and are
  // forgotten when it is evicted: what they guard (lookups, TURN credentials,
  // payments) is cheap to grant a few more of, and they must leave no trace
  // of when the owner was active. Allowances that an eviction must not reset
  // (invites, push endpoints, one-time keys, the tokens an account may draw,
  // what a mailbox takes in) are stored, down to the hour, and only until
  // they have refilled.

  private readonly memory = new Map<string, { tokens: number; at: number }>();

  /**
   * A token bucket per action in memory: [perMinute] refill, up to [burst]
   * saved up. Returns whether this request may go ahead and, if not, how many
   * seconds until it may (sent to the client as Retry-After).
   */
  async rateOk(action: string, perMinute: number, burst: number = perMinute): Promise<{ ok: boolean; retryAfter: number }> {
    const now = Date.now();
    const b = this.memory.get(action) ?? { tokens: burst, at: now };
    b.tokens = Math.min(burst, b.tokens + ((now - b.at) * perMinute) / 60_000);
    b.at = now;
    const ok = b.tokens >= 1;
    if (ok) b.tokens -= 1;
    this.memory.set(action, b);
    return { ok, retryAfter: ok ? 0 : Math.max(1, Math.ceil(((1 - b.tokens) * 60) / perMinute)) };
  }

  /**
   * Draws [want] from a stored hourly allowance: as many as it holds, up to
   * [want], and how many that was (0 with a Retry-After when none). What is
   * written is the bucket's level and the hour, never the time.
   */
  async allowance(action: string, perHour: number, burst: number, want = 1): Promise<{ granted: number; retryAfter: number }> {
    const key = `hourly:${action}`;
    const stored = (await this.ctx.storage.get<HourlyBucket>(key)) ?? null;
    const { granted, bucket, retryAfter } = hourlyTake(stored, Date.now(), perHour, burst, want);
    await this.ctx.storage.put(key, bucket);
    return { granted, retryAfter };
  }

  /** [allowance] for one unit. */
  async allowanceOk(action: string, perHour: number, burst: number): Promise<{ ok: boolean; retryAfter: number }> {
    const { granted, retryAfter } = await this.allowance(action, perHour, burst, 1);
    return { ok: granted > 0, retryAfter };
  }

  /** Forgets every stored allowance that is full again, and anything an earlier version kept about activity. */
  private async forgetRefilledAllowances(now: number): Promise<void> {
    const stale: string[] = [];
    for (const [key, bucket] of await this.ctx.storage.list<HourlyBucket>({ prefix: "hourly:" })) {
      if (hourlyFull(bucket, now)) stale.push(key);
    }
    for (const key of (await this.ctx.storage.list({ prefix: "bucket:" })).keys()) stale.push(key);
    stale.push("lastWakeAt", "lastFetchAt");
    await this.deleteKeys(stale);
  }

  static readonly LOOKUP_LIMIT = LOOKUP_LIMIT_PER_MINUTE;
  static readonly REGISTER_LIMIT = REGISTER_LIMIT_PER_MINUTE;
  /** Push endpoints an owner may set in an hour: each one that needs a check costs a request to it. */
  static readonly PUSH_PER_HOUR = 6;
  static readonly PUSH_BURST = 6;
  /** Invites per identity: twenty a day, refilled over the day. */
  static readonly INVITE_PER_HOUR = 20 / 24;
  static readonly INVITE_BURST = 20;
  /** Each request mints fresh TURN credentials with the provider: ten a minute is plenty for calls. */
  static readonly TURN_LIMIT = 10;

  /** storage.delete() takes at most 128 keys; larger sets go in batches. */
  private async deleteKeys(keys: string[]): Promise<number> {
    let removed = 0;
    for (let i = 0; i < keys.length; i += DELETE_BATCH) {
      removed += await this.ctx.storage.delete(keys.slice(i, i + DELETE_BATCH));
    }
    return removed;
  }

  // ── ClairixCoin ───────────────────────────────────────────────────────

  static readonly MAX_SINGLE_TRANSFER = MAX_SINGLE_TRANSFER;
  /** Payments per minute per payer: plenty for a person, a brake on a script. */
  static readonly PAY_LIMIT = 30;

  async getBalance(): Promise<number> {
    return (await this.ctx.storage.get<number>("balance")) ?? 0;
  }

  /**
   * The coins a new registration starts with. Only ever sets a balance that
   * does not exist yet, so a repeat (a retried registration, a redeploy) can
   * never mint twice.
   */
  async setInitialBalance(amount: number): Promise<void> {
    if (!Number.isInteger(amount) || amount <= 0) return;
    const existing = await this.ctx.storage.get<number>("balance");
    if (existing !== undefined) return;
    await this.ctx.storage.put("balance", amount);
  }

  /**
   * Takes [amount] off the owner's balance for a payment to [to]. The check
   * and the write happen in one call on the one object that holds this
   * balance, so two payments racing each other cannot both spend the same
   * coins.
   */
  async debit(to: string, amount: number, txid: string, note: string): Promise<{ ok: boolean; balance: number; reason?: string; repeat?: boolean }> {
    const balance = (await this.ctx.storage.get<number>("balance")) ?? 0;
    if (!Number.isInteger(amount) || amount <= 0 || amount > MAX_SINGLE_TRANSFER) {
      return { ok: false, balance, reason: "Invalid amount" };
    }
    const history = (await this.ctx.storage.get<CoinTx[]>("txHistory")) ?? [];
    // A payment already made under this id is not made again. One that was
    // made and then refunded (the recipient vanished before the credit) is
    // over; a new payment needs a new id, so nothing is paid twice for one.
    if (history.some((t) => t.id === txid && t.direction === "out")) {
      if (history.some((t) => t.id === `${txid}:refund`)) return { ok: false, balance, reason: "Payment refused" };
      return { ok: true, balance, repeat: true };
    }
    if (balance < amount) return { ok: false, balance, reason: "Insufficient balance" };
    const profile = await this.ctx.storage.get<Profile>("profile");
    const from = profile?.number ?? "";
    const next = balance - amount;
    const entries = this.appendTx(history, { id: txid, from, to, amount, ts: Date.now(), direction: "out", note });
    await this.ctx.storage.put({ balance: next, txHistory: entries });
    return { ok: true, balance: next };
  }

  /**
   * Adds [amount] to the owner's balance, for a payment from [from]; returns
   * the balance afterwards. A credit already made under [txid] is not made
   * again, so a payment the relay had to repeat lands once.
   */
  async credit(from: string, amount: number, txid: string, note: string): Promise<number> {
    if (!Number.isInteger(amount) || amount <= 0 || amount > MAX_SINGLE_TRANSFER) throw new Error("Invalid amount");
    const profile = await this.ctx.storage.get<Profile>("profile");
    // A mailbox deleted between the payer's debit and this credit must not
    // keep coins for whoever is given the number next; the payer is refunded.
    if (!profile) throw new Error("no such number");
    const balance = (await this.ctx.storage.get<number>("balance")) ?? 0;
    const history = (await this.ctx.storage.get<CoinTx[]>("txHistory")) ?? [];
    if (history.some((t) => t.id === txid && t.direction === "in")) return balance;
    const to = profile.number;
    const next = balance + amount;
    const entries = this.appendTx(history, { id: txid, from, to, amount, ts: Date.now(), direction: "in", note });
    await this.ctx.storage.put({ balance: next, txHistory: entries });
    return next;
  }

  /** The last [limit] transactions, newest first. */
  async txHistory(limit: number): Promise<CoinTx[]> {
    const history = (await this.ctx.storage.get<CoinTx[]>("txHistory")) ?? [];
    const n = Math.max(0, Math.min(MAX_TX_HISTORY, Math.floor(limit)));
    return history.slice(0, n);
  }

  /** [history] with [tx] at the front, trimmed to MAX_TX_HISTORY; not yet written. */
  private appendTx(history: CoinTx[], tx: CoinTx): CoinTx[] {
    const entry: CoinTx = { id: tx.id, from: tx.from, to: tx.to, amount: tx.amount, ts: tx.ts, direction: tx.direction };
    if (tx.note) entry.note = tx.note;
    return [entry, ...history].slice(0, MAX_TX_HISTORY);
  }

  /** Deletes everything about this number. */
  async wipe(): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.close(1000, "mailbox deleted");
      } catch {
        // already gone
      }
    }
    await this.ctx.storage.deleteAll();
  }

  // ── This relay's own identity ("relay-identity"; see federation.ts) ──

  /**
   * The seed of the key this relay signs forwarded requests with, made here
   * from the platform's CSPRNG the first time it is asked for and kept in this
   * object only. It is never sent anywhere; GET / publishes its public half.
   */
  async relayIdentitySeed(): Promise<string> {
    return this.relaySecret("relaySeed");
  }

  /**
   * The secret this relay derives its blind-token keys from (tokens.ts), made
   * and kept the same way as the relay's signing seed, in this object only.
   */
  async tokenSeed(): Promise<string> {
    return this.relaySecret("tokenSeed");
  }

  private async relaySecret(name: "relaySeed" | "tokenSeed"): Promise<string> {
    const existing = await this.ctx.storage.get<string>(name);
    if (existing) return existing;
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    let s = "";
    for (const b of bytes) s += String.fromCharCode(b);
    const seed = btoa(s);
    await this.ctx.storage.put(name, seed);
    return seed;
  }

  // ── Another relay ("fed:<hash>" objects; see federation.ts) ─────────

  /** The signing key the other relay publishes, as last fetched, and when. */
  async federationKey(): Promise<{ key: string; fetchedAt: number } | null> {
    return (await this.ctx.storage.get<{ key: string; fetchedAt: number }>("fedKey")) ?? null;
  }

  async setFederationKey(key: string): Promise<void> {
    await this.ctx.storage.put("fedKey", { key, fetchedAt: Date.now() });
  }

  /** Whether [nonce] is new from this relay within the replay window; records it if so. */
  async federationNonceOk(nonce: string): Promise<boolean> {
    const nonces = (await this.ctx.storage.get<NonceRecord>("nonces")) ?? {};
    const now = Date.now();
    for (const [n, seen] of Object.entries(nonces)) if (now - seen > NONCE_WINDOW_MS + 60_000) delete nonces[n];
    if (nonces[nonce] !== undefined) return false;
    nonces[nonce] = minuteFloor(now);
    await this.ctx.storage.put("nonces", nonces);
    return true;
  }

  /** Per-target send limits for the other relay this object stands for: in memory only. */
  private fedTargets: MemoryBuckets | null = null;

  /**
   * Whether the other relay may send one more envelope to [target] now. Kept
   * in memory and applied before anything is known about the target, so it
   * reads the same for a number that does not exist.
   */
  async federatedSendOk(target: string, perMinute: number, burst: number): Promise<boolean> {
    this.fedTargets ??= new MemoryBuckets(perMinute, burst);
    return this.fedTargets.take(target, Date.now());
  }

  /**
   * Records that a phone here wrote to the relay this object stands for. Day
   * precision, written at most once a day: when exactly, and by whom, is
   * not kept.
   */
  async noteWrittenTo(): Promise<void> {
    const day = Math.floor(Date.now() / DAY_MS);
    if ((await this.ctx.storage.get<number>("writtenToDay")) === day) return;
    await this.ctx.storage.put("writtenToDay", day);
  }

  /** Whether a phone here wrote to this relay within the last [days] days. */
  async writtenToWithin(days: number): Promise<boolean> {
    const day = await this.ctx.storage.get<number>("writtenToDay");
    return day !== undefined && Math.floor(Date.now() / DAY_MS) - day <= days;
  }

  /** Whether the other relay, for all its phones together, may claim one more one-time key this hour. */
  async federatedClaimOk(perHour: number, burst: number): Promise<boolean> {
    return (await this.allowanceOk("fed-claims", perHour, burst)).ok;
  }

  // ── Envelopes ───────────────────────────────────────────────────────

  /**
   * Queues an envelope for the owner and delivers it to any live socket. Returns
   * false when the mailbox does not exist or is full.
   *
   * Unless it is [urgent] (the sender did not ask for mixing: a call being set
   * up, an SOS, or anything from an app older than mixing), the envelope is
   * first held for a few seconds, a random time for each one, and released by
   * the alarm together with whatever else is due, in shuffled order. Someone who
   * watches traffic into the relay and traffic out to the owner's phone then
   * cannot pair an envelope in with the delivery out by timing alone, and a
   * burst from one sender arrives interleaved with everyone else's. The hold
   * counts towards the queue, so a full mailbox is full either way.
   */
  async enqueue(
    data: string,
    claim: string | null = null,
    urgent = false,
    federated: false | "known" | "stranger" = false,
    alert: Alert | null = null,
  ): Promise<EnqueueResult> {
    // The size is checked before anything about the number, so an oversized
    // envelope is answered the same whether the number exists or not.
    if (data.length > MAX_ENVELOPE_B64) return { ok: false, reason: "envelope too large", status: 413 };
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return NO_SUCH_NUMBER;
    const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
    if (federated) {
      // Envelopes from other relays that would go over this mailbox's limits
      // are dropped and answered exactly as a send to a number that does not
      // exist: several relays acting together could otherwise fill a mailbox
      // or its allowance and learn from the refusal that the number is real.
      // Only other relays' envelopes are dropped, and only while this mailbox
      // is being flooded from outside; this relay's own senders are untouched.
      //
      // Relays no one here has written to ("stranger") draw on a small
      // allowance of their own, so made-up relays flooding a mailbox cannot use
      // up what mail from the relays its owner's contacts are on relies on.
      if (count >= MAX_QUEUE - FED_QUEUE_RESERVE) return DROPPED;
      if (federated === "stranger") {
        if (!(await this.allowanceOk("inbound-fed-new", FED_STRANGER_PER_HOUR, FED_STRANGER_BURST)).ok) return DROPPED;
      } else if (!(await this.allowanceOk("inbound-fed", FED_INBOUND_PER_HOUR, FED_INBOUND_BURST)).ok) return DROPPED;
      if (!(await this.allowanceOk("inbound", INBOUND_PER_HOUR, INBOUND_BURST)).ok) return DROPPED;
    } else {
      if (count >= MAX_QUEUE) return { ok: false, reason: "mailbox full", status: 413 };
      const inbound = await this.allowanceOk("inbound", INBOUND_PER_HOUR, INBOUND_BURST);
      if (!inbound.ok) return { ok: false, reason: "this number is receiving too much; try again shortly", status: 429, retryAfter: inbound.retryAfter };
    }
    // The first envelope of a session spends the one-time key it was built on.
    if (claim !== null) await this.confirmClaim(claim);
    const now = Date.now();
    const id = `${now.toString(36)}-${crypto.randomUUID()}`;
    const envelope: Envelope = alert ? { id, ts: now, data, alert } : { id, ts: now, data };
    const hold = urgent ? 0 : mixDelay(mixMaxMs(this.env));
    if (hold > 0) {
      const staged: StagedEnvelope = { envelope, releaseAt: now + hold };
      await this.ctx.storage.put({ [stagedKey(staged.releaseAt, id)]: staged, queueCount: count + 1 });
      await this.scheduleAlarm();
      return { ok: true, id };
    }
    await this.ctx.storage.put({ [`q:${id}`]: envelope, queueCount: count + 1 });
    await this.handOver([envelope], true);
    return { ok: true, id };
  }

  /**
   * Gives envelopes just queued to the owner's live socket, and wakes the
   * phone through push when there is none. Outside a request ([fromRequest]
   * false: the alarm) the wake is awaited rather than left to waitUntil.
   */
  private async handOver(envelopes: Envelope[], fromRequest: boolean): Promise<void> {
    if (envelopes.length === 0) return;
    const sockets = this.ctx.getWebSockets();
    let delivered = false;
    for (const envelope of envelopes) {
      for (const ws of sockets) {
        try {
          ws.send(JSON.stringify({ type: "envelope", envelope }));
          delivered = true;
        } catch {
          // a dead socket; the owner will fetch on reconnect
        }
      }
    }
    const endpoint = await this.ctx.storage.get<string>("push");
    if (!endpoint) return;
    if (delivered) {
      // A socket whose phone lost its network still accepts a send without
      // error, so "delivered" only means handed over. If the phone has not
      // acked within a few seconds, the alarm wakes it through push as well.
      const check = (await this.ctx.storage.get<WakeCheck>("wakeCheck")) ?? { due: Date.now() + UNACKED_WAKE_MS, ids: [] };
      check.ids.push(...envelopes.map((e) => e.id));
      await this.ctx.storage.put("wakeCheck", { due: check.due, ids: check.ids.slice(-200) });
      await this.scheduleAlarm();
    } else if (fromRequest) {
      this.ctx.waitUntil(this.wakeOwner(endpoint));
    } else {
      await this.wakeOwner(endpoint);
    }
  }

  /**
   * Moves every held envelope that is due into the queue, in shuffled order,
   * and hands them over together. Returns how many were released.
   */
  private async releaseStaged(now: number): Promise<number> {
    const page = await this.ctx.storage.list<StagedEnvelope>({ prefix: "m:", limit: MIX_RELEASE_BATCH });
    const due: [string, StagedEnvelope][] = [];
    for (const entry of page) {
      if (stagedReleaseAt(entry[0]) > now) break;
      due.push(entry);
    }
    if (due.length === 0) return 0;
    const released = shuffle(due.map(([, s]) => s.envelope));
    for (let i = 0; i < released.length; i += PUT_BATCH) {
      const batch: Record<string, Envelope> = {};
      for (const e of released.slice(i, i + PUT_BATCH)) batch[`q:${e.id}`] = e;
      await this.ctx.storage.put(batch);
    }
    await this.deleteKeys(due.map(([key]) => key));
    await this.handOver(released, false);
    return released.length;
  }

  /** When the next held envelope is due, or null when none is held. */
  private async nextRelease(): Promise<number | null> {
    const first = await this.ctx.storage.list<StagedEnvelope>({ prefix: "m:", limit: 1 });
    for (const key of first.keys()) return stagedReleaseAt(key);
    return null;
  }

  /**
   * Wakes the owner's phone through push. One wake covers everything queued
   * until the phone next fetches, so a busy chat does not use up the push
   * distributor's quota and leave the next call offer unannounced. An
   * endpoint the distributor says is gone is forgotten.
   */
  /**
   * When the owner was last woken and last fetched, in memory only: they
   * exist to coalesce wakes, and an eviction costs at worst one extra wake.
   * Nothing stored says when the owner was last here.
   */
  private lastWakeAt = 0;
  private lastFetchAt = 0;

  private async wakeOwner(endpoint: string, force = false): Promise<void> {
    const now = Date.now();
    if (!force && this.lastWakeAt > this.lastFetchAt && now - this.lastWakeAt < WAKE_COALESCE_MS) return;
    this.lastWakeAt = now;
    const status = await wake(endpoint);
    if (status === 404 || status === 410) await this.ctx.storage.delete("push");
  }

  /**
   * Sets the alarm for whichever comes first: the next held envelope's
   * release, the unacked-delivery check, the next heartbeat while a phone is
   * connected, or the daily cleanup.
   */
  private async scheduleAlarm(rearm = false): Promise<void> {
    const cleanupAt = (await this.ctx.storage.get<number>("cleanupAt")) ?? Date.now() + CLEANUP_INTERVAL_MS;
    const check = await this.ctx.storage.get<WakeCheck>("wakeCheck");
    let next = check ? Math.min(cleanupAt, check.due) : cleanupAt;
    if (this.ctx.getWebSockets().length > 0) next = Math.min(next, Date.now() + HEARTBEAT_MS);
    const release = await this.nextRelease();
    if (release !== null) next = Math.min(next, release);
    // From inside alarm() the alarm being handled may still read as set, so the
    // handler re-arms outright; anyone else only ever brings the alarm forward.
    const current = rearm ? null : await this.ctx.storage.getAlarm();
    if (current === null || current > next) await this.ctx.storage.setAlarm(next);
  }

  /** Queued envelopes, oldest first, one page at a time (acked ones are deleted, so the next call gets the next page). */
  async inbox(limit = 200): Promise<Envelope[]> {
    this.lastFetchAt = Date.now();
    const map = await this.ctx.storage.list<Envelope>({ prefix: "q:", limit });
    return [...map.values()].sort((a, b) => a.ts - b.ts || a.id.localeCompare(b.id));
  }

  /** Deletes envelopes the owner has stored locally. */
  async ack(ids: string[]): Promise<number> {
    const keys = ids.map((id) => `q:${id}`);
    const removed = await this.deleteKeys(keys);
    const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
    await this.ctx.storage.put("queueCount", Math.max(0, count - removed));
    return removed;
  }

  /**
   * Four jobs share the object's one alarm: releasing envelopes held for
   * mixing, the heartbeat to a connected phone, waking a phone that did not
   * ack a live delivery in time, and the daily drop of envelopes past their TTL.
   */
  override async alarm(): Promise<void> {
    const now = Date.now();
    const invite = await this.ctx.storage.get<Invite>("invite");
    if (invite) {
      if (invite.expiresAt <= now) await this.ctx.storage.deleteAll();
      else await this.ctx.storage.setAlarm(invite.expiresAt);
      return;
    }
    if (await this.ctx.storage.get("tokenShard")) {
      try {
        if (await this.cleanupTokens(now)) await this.ctx.storage.setAlarm(now + CLEANUP_INTERVAL_MS);
        else await this.ctx.storage.deleteAll();
      } catch (e) {
        console.error("token cleanup failed", e);
        await this.ctx.storage.setAlarm(now + CLEANUP_INTERVAL_MS);
      }
      return;
    }
    try {
      // A release past the batch limit leaves the rest due at once: the
      // re-arm below brings the alarm straight back for them.
      await this.releaseStaged(now);
    } catch (e) {
      console.error("mix release failed", e);
    }
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.send(JSON.stringify({ type: "hb", ts: now }));
      } catch {
        // a dead socket; the phone reconnects when it notices
      }
    }
    try {
      const check = await this.ctx.storage.get<WakeCheck>("wakeCheck");
      if (check && check.due <= now) {
        await this.ctx.storage.delete("wakeCheck");
        const still = await this.ctx.storage.get(check.ids.map((id) => `q:${id}`));
        const endpoint = await this.ctx.storage.get<string>("push");
        if (still.size > 0 && endpoint) await this.wakeOwner(endpoint, true);
      }
    } catch (e) {
      console.error("unacked-delivery wake failed", e);
    }
    const cleanupAt = (await this.ctx.storage.get<number>("cleanupAt")) ?? 0;
    if (cleanupAt <= now) {
      await this.cleanup();
      await this.ctx.storage.put("cleanupAt", now + CLEANUP_INTERVAL_MS);
    }
    if (await this.ctx.storage.get("profile")) await this.scheduleAlarm(true);
  }

  /** Drops envelopes older than the TTL. */
  private async cleanup(): Promise<void> {
    // A full mailbox is up to 2,000 envelopes of ~87 KiB, more than the
    // object's memory allows in one list; the scan is paged. Cleanup errors
    // are swallowed so the alarm is always re-armed: the platform stops
    // retrying a throwing alarm after six attempts.
    try {
      const cutoff = Date.now() - ENVELOPE_TTL_MS;
      let startAfter: string | undefined;
      let removed = 0;
      for (;;) {
        const page = await this.ctx.storage.list<Envelope>({ prefix: "q:", startAfter, limit: DELETE_BATCH });
        if (page.size === 0) break;
        const keys = [...page.keys()];
        const stale = [...page].filter(([, e]) => e.ts < cutoff).map(([key]) => key);
        if (stale.length > 0) removed += await this.deleteKeys(stale);
        startAfter = keys[keys.length - 1];
      }
      if (removed > 0) {
        const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
        await this.ctx.storage.put("queueCount", Math.max(0, count - removed));
      }
    } catch (e) {
      console.error("envelope cleanup failed", e);
    }
    try {
      await this.releaseExpiredClaims();
    } catch (e) {
      console.error("claim cleanup failed", e);
    }
    try {
      await this.forgetRefilledAllowances(Date.now());
    } catch (e) {
      console.error("allowance cleanup failed", e);
    }
  }

  // ── Live delivery ───────────────────────────────────────────────────

  /** Upgrades an already-authenticated request to a hibernatable WebSocket. */
  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    // One identity is one phone: a socket it left behind (on a network it has
    // since left, say) is dead, and would otherwise count as "delivered".
    for (const old of this.ctx.getWebSockets()) {
      try {
        old.close(4000, "replaced by a new connection");
      } catch {
        // already closed
      }
    }
    this.ctx.acceptWebSocket(server);
    // Anything already waiting goes out immediately, a page at a time; the
    // client fetches the rest through /v1/inbox when told there is more.
    const pending = await this.inbox();
    for (const envelope of pending) server.send(JSON.stringify({ type: "envelope", envelope }));
    server.send(JSON.stringify({ type: "ready", pending: pending.length, more: pending.length >= 200 }));
    await this.scheduleAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (typeof message !== "string") return;
    let parsed: { type?: string; ids?: unknown };
    try {
      parsed = JSON.parse(message) as { type?: string; ids?: unknown };
    } catch {
      return;
    }
    if (parsed.type === "ack" && Array.isArray(parsed.ids)) {
      const ids = parsed.ids.filter((x): x is string => typeof x === "string");
      await this.ack(ids);
      ws.send(JSON.stringify({ type: "acked", ids }));
    } else if (parsed.type === "ping") {
      ws.send(JSON.stringify({ type: "pong", ts: Date.now() }));
    }
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try {
      ws.close(code, reason);
    } catch {
      // already closed
    }
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    try {
      ws.close(1011, "error");
    } catch {
      // already closed
    }
  }
}

/**
 * Wakes the owner's phone through its UnifiedPush endpoint. The body says
 * nothing: the app fetches its inbox over the authenticated channel. Returns
 * the distributor's HTTP status, or 0 when it could not be reached.
 */
async function wake(endpoint: string): Promise<number> {
  try {
    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "text/plain", ttl: "86400", urgency: "high" },
      body: "wake",
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) console.error("push wake refused", res.status);
    return res.status;
  } catch (e) {
    console.error("push wake failed", e);
    return 0;
  }
}
