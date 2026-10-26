// An in-memory stand-in for the parts of the Workers runtime the relay uses:
// Durable Object storage, namespaces and RPC stubs. Each object is a real
// instance of the Worker's class; arguments and results are structured-cloned
// across the stub, as Durable Object RPC does, so no test can share an object
// with the "remote" side by accident.

import { DatabaseSync } from "node:sqlite";
import worker, { IncidentCache, Mailbox, Traffic } from "../../src/index.ts";

export const ENROLL_SECRET = "test-enrollment-secret-0123456789";
export const RELAY = "https://relay.test";

// ── Clock ───────────────────────────────────────────────────────────────────

const realNow = Date.now.bind(Date);
const HOUR = 60 * 60_000;
/**
 * Every test starts at twenty past an hour, never in the last seconds of
 * one: allowances and stored times are kept to the hour and the day, and a
 * test that ran across midnight or the top of the hour would see its own
 * bookkeeping change under it.
 */
function startOffset() {
  const now = realNow();
  return (((20 * 60_000 - (now % HOUR)) % HOUR) + HOUR) % HOUR;
}
let offsetMs = startOffset();
Date.now = () => realNow() + offsetMs;

/** Moves the clock every part of the relay (and the test phones) reads. */
export function advance(ms) {
  offsetMs += ms;
}

export function resetClock() {
  offsetMs = startOffset();
}

// ── Storage ─────────────────────────────────────────────────────────────────

/**
 * The SQL side of Durable Object storage, over Node's own SQLite: exec() with
 * bindings, cursors with toArray(), one() and rowsWritten, and
 * transactionSync(). Opened on first use, so mailboxes (key-value only) never
 * pay for a database.
 */
class FakeSql {
  constructor() {
    this.db = new DatabaseSync(":memory:");
  }

  exec(query, ...bindings) {
    const text = query.trim();
    // A script of several statements (the schema) has no bindings and no rows.
    if (bindings.length === 0 && /;[\s\S]*\S/.test(text)) {
      this.db.exec(text);
      return cursor([], 0);
    }
    const stmt = this.db.prepare(text);
    const args = bindings.map((b) => (b === undefined ? null : b));
    if (/^(SELECT|WITH|PRAGMA)\b/i.test(text) || /\bRETURNING\b/i.test(text)) return cursor(stmt.all(...args), 0);
    const r = stmt.run(...args);
    return cursor([], r.changes);
  }
}

function cursor(rows, rowsWritten) {
  const plain = rows.map((r) => ({ ...r }));
  return {
    rowsWritten,
    toArray: () => plain,
    one: () => {
      if (plain.length !== 1) throw new Error(`expected one row, got ${plain.length}`);
      return plain[0];
    },
    [Symbol.iterator]: () => plain[Symbol.iterator](),
  };
}

class FakeStorage {
  constructor() {
    this.data = new Map();
    this.alarm = null;
    this.sqlInstance = null;
  }

  get sql() {
    this.sqlInstance ??= new FakeSql();
    return this.sqlInstance;
  }

  transactionSync(fn) {
    const db = this.sql.db;
    db.exec("BEGIN");
    try {
      const result = fn();
      db.exec("COMMIT");
      return result;
    } catch (e) {
      db.exec("ROLLBACK");
      throw e;
    }
  }

  async get(key) {
    if (Array.isArray(key)) {
      const out = new Map();
      for (const k of key) if (this.data.has(k)) out.set(k, structuredClone(this.data.get(k)));
      return out;
    }
    return this.data.has(key) ? structuredClone(this.data.get(key)) : undefined;
  }

  async put(key, value) {
    if (typeof key === "object" && key !== null) {
      for (const [k, v] of Object.entries(key)) this.data.set(k, structuredClone(v));
      return;
    }
    this.data.set(key, structuredClone(value));
  }

  async delete(key) {
    if (Array.isArray(key)) {
      if (key.length > 128) throw new Error("at most 128 keys per delete");
      let n = 0;
      for (const k of key) if (this.data.delete(k)) n++;
      return n;
    }
    return this.data.delete(key);
  }

  async list({ prefix = "", startAfter, limit } = {}) {
    const keys = [...this.data.keys()].filter((k) => k.startsWith(prefix) && (startAfter === undefined || k > startAfter)).sort();
    const out = new Map();
    for (const k of keys.slice(0, limit ?? keys.length)) out.set(k, structuredClone(this.data.get(k)));
    return out;
  }

  async deleteAll() {
    this.data.clear();
    this.alarm = null;
  }

  async getAlarm() {
    return this.alarm;
  }

  async setAlarm(t) {
    this.alarm = typeof t === "number" ? t : t.getTime();
  }

  async deleteAlarm() {
    this.alarm = null;
  }
}

class FakeState {
  constructor(name) {
    this.id = { name, toString: () => name };
    this.storage = new FakeStorage();
    this.pending = [];
  }

  getWebSockets() {
    return [];
  }

  acceptWebSocket() {
    throw new Error("WebSockets are not simulated");
  }

  waitUntil(promise) {
    this.pending.push(Promise.resolve(promise).catch(() => {}));
  }
}

// ── Namespaces and stubs ───────────────────────────────────────────────────

function stub(instance) {
  return new Proxy(instance, {
    get(target, prop) {
      const value = target[prop];
      if (typeof value !== "function" || prop === "fetch") return value;
      return async (...args) => structuredClone(await value.apply(target, structuredClone(args)));
    },
  });
}

class FakeNamespace {
  constructor(cls, env) {
    this.cls = cls;
    this.env = env;
    this.objects = new Map();
  }

  idFromName(name) {
    return { name, toString: () => name };
  }

  get(id) {
    let entry = this.objects.get(id.name);
    if (!entry) {
      const state = new FakeState(id.name);
      entry = { state, instance: new this.cls(state, this.env) };
      this.objects.set(id.name, entry);
    }
    return stub(entry.instance);
  }

  /** The storage of object [name], as a plain Map of key to value. */
  storageOf(name) {
    return this.objects.get(name)?.state.storage.data ?? new Map();
  }

  /** Runs every alarm that is due, as the runtime would. */
  async runDueAlarms() {
    for (const { state, instance } of this.objects.values()) {
      if (state.storage.alarm !== null && state.storage.alarm <= Date.now()) {
        state.storage.alarm = null;
        await instance.alarm();
      }
    }
  }

  /** Waits for everything objects handed to waitUntil (push wakes, say). */
  async settle() {
    for (const { state } of this.objects.values()) {
      const pending = state.pending.splice(0);
      await Promise.all(pending);
    }
  }
}

/** A fresh relay: the Worker's own fetch handler over empty storage. */
export function makeRelay(vars = {}, origin = RELAY) {
  // Mixing holds envelopes for a random few seconds; the tests that are not
  // about it turn it off so a send is in the inbox at once.
  const env = { ENROLL_SECRET, MIX_MAX_MS: "0", ...vars };
  env.MAILBOX = new FakeNamespace(Mailbox, env);
  env.TRAFFIC = new FakeNamespace(Traffic, env);
  env.INCIDENTS = new FakeNamespace(IncidentCache, env);
  const call = async (method, path, { body, headers = {} } = {}) => {
    const init = { method, headers: { ...headers } };
    if (body !== undefined) init.body = typeof body === "string" ? body : JSON.stringify(body);
    const res = await worker.fetch(new Request(origin + path, init), env);
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      // not JSON
    }
    return { status: res.status, json, headers: res.headers };
  };
  return { env, call, origin };
}

/**
 * Connects [relays] to each other: a fetch the relay code makes to one of
 * their origins is answered by that relay's own handler, as over the network.
 * [intercept], when given, sees each such request first and may answer it
 * itself (a Response) or let it through (undefined). Returns the requests
 * made, and restore().
 */
export function network(relays, intercept) {
  const byOrigin = new Map(relays.map((r) => [r.origin, r]));
  const requests = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (input, init = {}) => {
    const request = new Request(input, init);
    const url = new URL(request.url);
    const body = request.method === "GET" ? null : await request.clone().text();
    const record = { url: request.url, method: request.method, headers: Object.fromEntries(request.headers), body };
    requests.push(record);
    if (intercept) {
      const answer = await intercept(record);
      if (answer !== undefined) return answer;
    }
    const relay = byOrigin.get(url.origin);
    if (!relay) throw new TypeError(`fetch failed: ${url.origin} is not on this network`);
    return worker.fetch(request, relay.env);
  };
  return { requests, restore: () => { globalThis.fetch = original; } };
}

// ── Phones ─────────────────────────────────────────────────────────────────

const enc = new TextEncoder();

function b64(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s);
}

function randomB64(n = 32) {
  return b64(crypto.getRandomValues(new Uint8Array(n)));
}

/** A random Curve25519 public key in canonical form (top bit clear), as a real phone's always is. */
function randomCurveB64() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  bytes[31] &= 0x7f;
  if (bytes[31] === 0x7f) bytes[31] = 0x7e; // and below the field prime
  return b64(bytes);
}

async function sha256Hex(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(text)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

let addressCounter = 0;

/**
 * Registers a phone with [oneTimeKeys] signed one-time keys and returns a
 * client that signs its requests the way RelayClient does.
 */
export async function makePhone(relay, { listed = true, oneTimeKeys = 10 } = {}) {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const ed25519 = b64(await crypto.subtle.exportKey("raw", pair.publicKey));
  const sign = async (text) => b64(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, enc.encode(text)));
  const curve25519 = randomCurveB64();
  const sealing = randomCurveB64();
  const signedKey = async (id) => {
    const key = randomB64();
    return { id, key, signature: await sign(key) };
  };
  const tag = randomB64(6).replace(/[^A-Za-z0-9]/g, "x");
  const keys = [];
  for (let i = 0; i < oneTimeKeys; i++) keys.push(await signedKey(`${tag}-otk-${i}`));
  const fallback = await signedKey(`${tag}-fallback`);
  const body = JSON.stringify({
    ts: Date.now(),
    secret: ENROLL_SECRET,
    ed25519,
    curve25519,
    sealing,
    signature: await sign(`${curve25519}|${sealing}`),
    fallback,
    oneTimeKeys: keys,
    listed,
  });
  const reg = await relay.call("POST", "/v1/register", {
    body,
    headers: { "x-clairix-sig": await sign(body), "cf-connecting-ip": `198.51.100.${++addressCounter % 250}` },
  });
  if (reg.status !== 201) throw new Error(`registration failed: ${reg.status} ${JSON.stringify(reg.json)}`);
  const number = reg.json.number;

  const signed = async (method, path, bodyObj) => {
    const text = bodyObj === undefined ? "" : JSON.stringify(bodyObj);
    const ts = Date.now();
    const nonce = randomB64(18).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    const payload = `${number}\n${ts}\n${nonce}\n${method}\n${path}\n${await sha256Hex(text)}`;
    return relay.call(method, path, {
      body: method === "GET" ? undefined : text,
      headers: {
        "x-clairix-number": number,
        "x-clairix-ts": String(ts),
        "x-clairix-nonce": nonce,
        "x-clairix-sig": await sign(payload),
      },
    });
  };

  return {
    number,
    ed25519,
    curve25519,
    sealing,
    sign,
    registrationBody: body,
    keyIds: keys.map((k) => k.id),
    fallbackId: fallback.id,
    signed,
    signedKey,
    async tokens(count, kind) {
      const res = await signed("POST", "/v1/tokens", kind === undefined ? { count } : { count, kind });
      if (res.status !== 200) throw new Error(`tokens: ${res.status} ${JSON.stringify(res.json)}`);
      return res.json.tokens;
    },
    /** An anonymous traffic request: a token and nothing that names this phone. */
    traffic(token, method, path, body) {
      const headers = token === null ? {} : { "x-clairix-token": token };
      return relay.call(method, path, body === undefined ? { headers } : { body, headers });
    },
    /** An anonymous send: a token, the recipient and the envelope, and nothing that names this phone. */
    send(token, to, envelope = randomB64(200), claim, extra = {}) {
      const body = { to, envelope, ...extra };
      if (claim !== undefined) body.claim = claim;
      const headers = token === null ? {} : { "x-clairix-token": token };
      return relay.call("POST", "/v1/send", { body, headers });
    },
    async inbox() {
      const res = await signed("GET", "/v1/inbox");
      return res.json.envelopes;
    },
    async oneTimeKeys() {
      return (await signed("GET", "/v1/me")).json.oneTimeKeys;
    },
    /** A lookup as the app makes it: the number in the body, never in the URL. */
    lookup(target, pin, claim = true) {
      const body = { number: target, claim };
      if (pin) body.pin = pin;
      return signed("POST", "/v1/lookup", body);
    },
    bundle(target, pin) {
      return this.lookup(target, pin, true);
    },
    identity(target, pin) {
      return this.lookup(target, pin, false);
    },
  };
}

/** Records every fetch the relay makes and answers it with [respond]. */
export function mockFetch(respond) {
  const calls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (input, init = {}) => {
    const url = typeof input === "string" ? input : input.url;
    const call = { url, method: init.method ?? "GET", body: typeof init.body === "string" ? init.body : null, headers: init.headers ?? {} };
    calls.push(call);
    return respond(call, calls.length);
  };
  return { calls, restore: () => { globalThis.fetch = original; } };
}
