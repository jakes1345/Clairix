// Blind send tokens: RFC 9497 VOPRF over ristretto255-SHA512, the construction
// Privacy Pass uses for privately verifiable tokens.
//
// The tokens /v1/tokens handed out before were random values the relay made:
// it saw each one as it issued it to an account, so a relay that kept those
// responses could match every send to its sender. A blind token is made by the
// phone: it picks a random input, blinds it and sends only the blinded element.
// The relay evaluates that with its key for the token's kind and epoch and
// returns a DLEQ proof that it used the key it publishes at GET /, and the
// phone unblinds the answer. Spending the token shows the input and the
// output; the relay checks it made that output, but it has never seen the
// input, so it cannot tell which account the token went to, nor that two
// tokens went to the same one.
//
// The proof is what stops a relay from giving each account a key of its own
// (it would then know a token's account by which key it verifies under): the
// phone checks it against the key GET / publishes, which is the same for
// every caller, and keeps the first key it saw for each kind and epoch.
//
// The arithmetic is voprf/ (Rust, the same crate the phone runs), compiled to
// src/voprf.wasm; this file holds the keys and the wire format.

import voprfModule from "./voprf.wasm";
import { type Env, base64ToBytes, sha256Hex } from "./env";
import { type TokenKind, base64url } from "./comms-logic";

/** A key lives one epoch; tokens of the epoch before are still accepted, so a token is good for 14 to 28 days. */
export const TOKEN_EPOCH_MS = 14 * 24 * 60 * 60_000;
/** Most blinded elements evaluated in one request (the module's own limit). */
export const BLIND_BATCH_MAX = 100;
/** The object that keeps the token secret when TOKEN_KEY is not set (shared with federation's relay seed). */
const SECRET_OBJECT = "relay-identity";

/** A spent blind token in X-Clairix-Token: "2.<epoch>.<input>.<output>", input 32 and output 64 bytes base64url. */
export const BLIND_TOKEN_RE = /^2\.(\d{1,9})\.([A-Za-z0-9_-]{43})\.([A-Za-z0-9_-]{86})$/;

const ELEMENT_LEN = 32;
const PROOF_LEN = 64;
const OUTPUT_LEN = 64;

interface VoprfExports {
  memory: WebAssembly.Memory;
  buffer(): number;
  buffer_len(): number;
  public_key(infoLen: number): number;
  evaluate_batch(infoLen: number, n: number): number;
  verify(infoLen: number, inputLen: number): number;
}

let instance: VoprfExports | null = null;

function voprf(): VoprfExports {
  if (!instance) instance = new WebAssembly.Instance(voprfModule, {}).exports as unknown as VoprfExports;
  return instance;
}

function buffer(w: VoprfExports): Uint8Array {
  return new Uint8Array(w.memory.buffer, w.buffer(), w.buffer_len());
}

export function epochOf(now: number): number {
  return Math.floor(now / TOKEN_EPOCH_MS);
}

/** When a token of [epoch] stops being accepted: the end of the epoch after it. */
export function epochExpiry(epoch: number): number {
  return (epoch + 2) * TOKEN_EPOCH_MS;
}

function info(kind: TokenKind, epoch: number): Uint8Array {
  return new TextEncoder().encode(`clairix-token-v2|${kind}|${epoch}`);
}

const secrets = new WeakMap<object, Uint8Array>();

/** The 32-byte secret every token key is derived from: TOKEN_KEY, or the one the relay made itself. */
async function secret(env: Pick<Env, "TOKEN_KEY" | "MAILBOX">): Promise<Uint8Array> {
  const configured = env.TOKEN_KEY?.trim();
  if (configured) {
    const bytes = base64ToBytes(configured);
    if (bytes.length !== 32) throw new Error("TOKEN_KEY must be 32 bytes of base64");
    return bytes;
  }
  const cached = secrets.get(env.MAILBOX);
  if (cached) return cached;
  const made = base64ToBytes(await env.MAILBOX.get(env.MAILBOX.idFromName(SECRET_OBJECT)).tokenSeed());
  secrets.set(env.MAILBOX, made);
  return made;
}

const publicKeys = new Map<string, string>();

/** The public key for [kind] in [epoch], base64url: what GET / publishes and phones check proofs against. */
export async function tokenPublicKey(env: Pick<Env, "TOKEN_KEY" | "MAILBOX">, kind: TokenKind, epoch: number): Promise<string> {
  const s = await secret(env);
  const id = `${base64url(s).slice(0, 8)}|${kind}|${epoch}`;
  const known = publicKeys.get(id);
  if (known) return known;
  const w = voprf();
  const i = info(kind, epoch);
  const b = buffer(w);
  b.set(s, 0);
  b.set(i, 32);
  if (w.public_key(i.length) !== 0) throw new Error("token key derivation failed");
  const key = base64url(buffer(w).slice(0, ELEMENT_LEN));
  if (publicKeys.size > 64) publicKeys.clear();
  publicKeys.set(id, key);
  return key;
}

export interface BlindIssue {
  epoch: number;
  key: string;
  evaluated: string[];
  proof: string;
}

/**
 * Evaluates [blinded] (base64url ristretto255 elements) with the key for
 * [kind] in the current epoch, with one proof over them all. Null when an
 * element is not a valid point: the caller answers 400.
 */
export async function issueBlind(env: Pick<Env, "TOKEN_KEY" | "MAILBOX">, kind: TokenKind, blinded: Uint8Array[], now = Date.now()): Promise<BlindIssue | null> {
  if (blinded.length === 0 || blinded.length > BLIND_BATCH_MAX) throw new Error("blinded batch out of range");
  const epoch = epochOf(now);
  const key = await tokenPublicKey(env, kind, epoch);
  const s = await secret(env);
  const w = voprf();
  const i = info(kind, epoch);
  const b = buffer(w);
  b.set(s, 0);
  b.set(crypto.getRandomValues(new Uint8Array(32)), 32);
  b.set(i, 64);
  blinded.forEach((e, n) => b.set(e, 64 + i.length + n * ELEMENT_LEN));
  const rc = w.evaluate_batch(i.length, blinded.length);
  if (rc === 2) return null;
  if (rc !== 0) throw new Error(`token evaluation failed (${rc})`);
  const out = buffer(w);
  const evaluated = blinded.map((_, n) => base64url(out.slice(n * ELEMENT_LEN, (n + 1) * ELEMENT_LEN)));
  const proof = base64url(out.slice(blinded.length * ELEMENT_LEN, blinded.length * ELEMENT_LEN + PROOF_LEN));
  return { epoch, key, evaluated, proof };
}

export interface SpentBlindToken {
  /** What the spent-token shard keys it by: SHA-256 of kind, epoch and input. */
  hash: string;
  /** When the record of its spending can go: its key is retired by then. */
  expiresAt: number;
}

/**
 * Checks a blind token of [kind]: its epoch is the current or the previous
 * one, and its output is what the key for that kind and epoch makes of its
 * input. Null when not; the caller still has to record it as spent, which is
 * what stops it being used twice.
 */
export async function checkBlindToken(env: Pick<Env, "TOKEN_KEY" | "MAILBOX">, kind: TokenKind, token: string, now = Date.now()): Promise<SpentBlindToken | null> {
  const m = BLIND_TOKEN_RE.exec(token);
  if (!m) return null;
  const epoch = Number(m[1]);
  const current = epochOf(now);
  if (epoch !== current && epoch !== current - 1) return null;
  const input = base64ToBytes(m[2]!);
  const output = base64ToBytes(m[3]!);
  if (input.length !== 32 || output.length !== OUTPUT_LEN) return null;
  const s = await secret(env);
  const w = voprf();
  const i = info(kind, epoch);
  const b = buffer(w);
  b.set(s, 0);
  b.set(i, 32);
  b.set(input, 32 + i.length);
  b.set(output, 32 + i.length + input.length);
  if (w.verify(i.length, input.length) !== 0) return null;
  return { hash: await sha256Hex(`${kind}|${epoch}|${m[2]}`), expiresAt: epochExpiry(epoch) };
}
