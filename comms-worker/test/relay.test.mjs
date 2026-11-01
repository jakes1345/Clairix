// The relay's comms routes end to end: the Worker's own fetch handler and
// Mailbox objects over in-memory storage (see harness/fake-env.mjs).
// Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { ENROLL_SECRET, advance, makePhone, makeRelay, mockFetch, network, resetClock } from "./harness/fake-env.mjs";
import { DAY_MS, HOUR_MS, dayFloor, tokenHash, tokenShardName, OTK_CLAIM_WINDOW_MS, PUSH_CHALLENGE_PREFIX } from "../src/comms-logic.ts";
import { sha256Hex } from "../src/env.ts";
import { closeReports, listReports, registerOperator, setSuspended } from "../scripts/operator.mjs";

afterEach(() => resetClock());

/** Every value stored anywhere on the relay, as text, for "is X kept anywhere" checks. */
function everythingStored(relay, filter = () => true) {
  const out = [];
  for (const [name, entry] of relay.env.MAILBOX.objects) {
    if (!filter(name)) continue;
    for (const [k, v] of entry.state.storage.data) out.push(`${name} ${k} ${JSON.stringify(v)}`);
  }
  return out.join("\n");
}

/** The app's fingerprint(): base64url of the first 12 bytes of SHA-256 over the key text, as a lookup's or a payment's pin. */
async function fingerprint(ed25519) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ed25519)));
  return Buffer.from(d.subarray(0, 12)).toString("base64url");
}

// ── Anonymous sending ──────────────────────────────────────────────────────

test("a send with a valid delivery token is queued, and carries no sender", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const res = await alice.send(token, bob.number, "c2VhbGVk");
  assert.equal(res.status, 202);
  const inbox = await bob.inbox();
  assert.equal(inbox.length, 1);
  assert.equal(inbox[0].data, "c2VhbGVk");
  assert.deepEqual(Object.keys(inbox[0]).sort(), ["data", "id", "ts"]);
  // Nothing in Bob's mailbox names Alice.
  assert.ok(!everythingStored(relay, (n) => n === bob.number).includes(alice.number));
});

test("a delivery token is spent by one send: reuse is refused", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [token] = await alice.tokens(1);
  assert.equal((await alice.send(token, bob.number)).status, 202);
  const again = await alice.send(token, bob.number);
  assert.equal(again.status, 403);
  assert.match(again.json.error, /already used/);
  assert.equal((await bob.inbox()).length, 1);
});

test("a send without a token, or with a made-up one, is refused", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  assert.equal((await alice.send(null, bob.number)).status, 401);
  assert.equal((await alice.send("not-a-token", bob.number)).status, 403);
  const madeUp = "A".repeat(43);
  assert.equal((await alice.send(madeUp, bob.number)).status, 403);
  assert.equal((await bob.inbox()).length, 0);
});

test("a send that names its sender is refused, signed or not", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const withIdentity = await relay.call("POST", "/v1/send", {
    body: { to: bob.number, envelope: "AAAA" },
    headers: { "x-clairix-token": token, "x-clairix-number": alice.number },
  });
  assert.equal(withIdentity.status, 400);
  // The old way, a signed send without a token, no longer delivers anything.
  const signedOnly = await alice.signed("POST", "/v1/send", { to: bob.number, envelope: "AAAA" });
  assert.equal(signedOnly.status, 400);
  assert.equal((await bob.inbox()).length, 0);
  // The token was not spent by the refused requests.
  assert.equal((await alice.send(token, bob.number)).status, 202);
});

test("an expired delivery token is refused", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [token] = await alice.tokens(1);
  advance(15 * 24 * 60 * 60_000);
  assert.equal((await alice.send(token, bob.number)).status, 403);
});

test("issued tokens are stored only as hashes, apart from the account", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const tokens = await alice.tokens(50);
  assert.equal(tokens.length, 50);
  assert.equal(new Set(tokens).size, 50);
  const hashes = await Promise.all(tokens.map((t) => tokenHash(t)));
  const all = everythingStored(relay);
  for (const t of tokens) assert.ok(!all.includes(t), "a token is stored in the clear");
  // Each hash sits in its shard; nothing in a shard names the account.
  for (const h of hashes) assert.ok(relay.env.MAILBOX.storageOf(tokenShardName(h)).has(`t:${h}`));
  const shards = everythingStored(relay, (n) => n.startsWith("dtok:"));
  assert.ok(!shards.includes(alice.number) && !shards.includes(alice.ed25519));
  // And nothing in the account's mailbox names a token.
  const mine = everythingStored(relay, (n) => n === alice.number);
  for (const h of hashes) assert.ok(!mine.includes(h));
});

test("token issuance is an hourly allowance per account", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  let got = 0;
  for (let i = 0; i < 10; i++) got += (await alice.tokens(100)).length;
  assert.equal(got, 1000);
  const more = await alice.signed("POST", "/v1/tokens", { count: 100 });
  assert.equal(more.status, 429);
  const retryAfter = Number(more.headers.get("retry-after"));
  assert.ok(retryAfter >= 1 && retryAfter <= 3600, `retry-after ${retryAfter}`);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 0 })).status, 400);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 101 })).status, 400);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 5, kind: "coupons" })).status, 400);
  // Nothing comes back within the hour; the next hour refills the whole allowance.
  advance(60_000);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 100 })).status, 429);
  advance(HOUR_MS);
  assert.equal((await alice.tokens(100)).length, 100);
});

test("an operator can set the hourly delivery allowance; anything but a whole number in range keeps the default", async () => {
  const issued = async (vars) => {
    const alice = await makePhone(makeRelay(vars));
    let got = 0;
    for (;;) {
      const res = await alice.signed("POST", "/v1/tokens", { count: 100 });
      if (res.status !== 200) return got;
      got += res.json.tokens.length;
    }
  };
  assert.equal(await issued({ DELIVERY_TOKENS_PER_HOUR: "2500" }), 2500);
  assert.equal(await issued({ DELIVERY_TOKENS_PER_HOUR: "150" }), 150);
  for (const bad of ["0", "100001", "-5", "1e4", "lots", ""]) {
    assert.equal(await issued({ DELIVERY_TOKENS_PER_HOUR: bad }), 1000, bad);
  }
  // Other kinds keep their own.
  const alice = await makePhone(makeRelay({ DELIVERY_TOKENS_PER_HOUR: "5000" }));
  let traffic = 0;
  for (;;) {
    const res = await alice.signed("POST", "/v1/tokens", { count: 100, kind: "traffic" });
    if (res.status !== 200) break;
    traffic += res.json.tokens.length;
  }
  assert.equal(traffic, 400);
});

test("the allowance remembers the hour it was drawn on, never the moment, and is forgotten once full", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  await alice.tokens(10);
  const box = relay.env.MAILBOX.storageOf(alice.number);
  const bucket = box.get("hourly:tokens-delivery");
  assert.deepEqual(Object.keys(bucket).sort(), ["burst", "hour", "perHour", "tokens"]);
  assert.equal(bucket.hour, Math.floor(Date.now() / HOUR_MS));
  assert.ok(!JSON.stringify(bucket).includes(String(Date.now()).slice(0, 8)), "a timestamp is stored");
  // A day later the daily cleanup finds it full and drops it.
  advance(DAY_MS + 60_000);
  await relay.env.MAILBOX.runDueAlarms();
  assert.equal(relay.env.MAILBOX.storageOf(alice.number).has("hourly:tokens-delivery"), false);
});

test("each token kind has its own shards and allowance: one kind cannot be spent as another", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [traffic] = await alice.tokens(1, "traffic");
  const [report] = await alice.tokens(1, "report");
  const [delivery] = await alice.tokens(1, "delivery");
  assert.ok(relay.env.MAILBOX.storageOf(tokenShardName(await tokenHash(traffic), "traffic")).has(`t:${await tokenHash(traffic)}`));
  assert.ok(relay.env.MAILBOX.storageOf(tokenShardName(await tokenHash(report), "report")).has(`t:${await tokenHash(report)}`));
  // Neither buys a send.
  assert.equal((await alice.send(traffic, bob.number)).status, 403);
  assert.equal((await alice.send(report, bob.number)).status, 403);
  assert.equal((await alice.send(delivery, bob.number)).status, 202);
  // Report tokens are the scarce kind.
  let reports = 1;
  for (let i = 0; i < 40; i++) {
    const res = await alice.signed("POST", "/v1/tokens", { count: 10, kind: "report" });
    if (res.status !== 200) break;
    reports += res.json.tokens.length;
  }
  assert.equal(reports, 30);
});

test("expired token hashes are cleaned up by the shard's alarm", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const shard = tokenShardName(await tokenHash(token));
  assert.equal(relay.env.MAILBOX.storageOf(shard).size, 2); // the hash and the shard marker
  advance(15 * 24 * 60 * 60_000);
  await relay.env.MAILBOX.runDueAlarms();
  assert.equal(relay.env.MAILBOX.storageOf(shard).size, 0);
});

test("each mailbox caps what it accepts from everyone together", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(bob.number));
  let accepted = 0;
  let last;
  for (let i = 0; i < 9_100; i++) {
    last = await box.enqueue("AAAA");
    if (last.ok) accepted++;
    else break;
    // Bob reads his mail as it comes: the queue's own limit is not what is tested here.
    if (i % 1000 === 999) await relay.env.MAILBOX.objects.get(bob.number).state.storage.put("queueCount", 0);
  }
  assert.equal(accepted, 9_000);
  assert.equal(last.status, 429);
  // Through the Worker the same limit is a 429 with Retry-After.
  const alice = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const res = await alice.send(token, bob.number);
  assert.equal(res.status, 429);
  assert.ok(res.headers.get("retry-after"));
});

test("nothing a mailbox stores says when its owner was last here", async () => {
  const relay = makeRelay({ COIN: "on", COIN_INITIAL_BALANCE: "10" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const fetches = mockFetch(() => new Response("ok"));
  try {
    await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/upAlice" });
    const box = relay.env.MAILBOX.storageOf(alice.number);
    const before = new Map([...box].map(([k, v]) => [k, JSON.stringify(v)]));
    advance(7 * 60_000 + 1234);
    const t0 = Date.now();
    // Everything an active owner does in a quiet quarter of an hour.
    await alice.signed("GET", "/v1/me");
    await alice.signed("GET", "/v1/turn");
    await alice.lookup(bob.number, undefined, false);
    await alice.inbox();
    const [t] = await bob.tokens(1);
    await bob.send(t, alice.number);
    await relay.env.MAILBOX.settle();
    await alice.inbox();
    const paid = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(bob.ed25519), amount: 1 });
    assert.equal(paid.status, 200);
    const after = relay.env.MAILBOX.storageOf(alice.number);
    for (const [k, v] of after) {
      if (k === "nonces") {
        // Kept for the replay window, to the minute.
        for (const seen of Object.values(v)) assert.equal(seen % 60_000, 0, "a nonce is stored with its exact time");
        continue;
      }
      if (k === "txHistory") {
        // The payment: its id, amount, direction and hour, never the moment or whom it went to.
        assert.equal(v.length, 1);
        for (const tx of v) {
          assert.deepEqual(Object.keys(tx).sort(), ["amount", "direction", "id", "ts"]);
          assert.equal(tx.ts, Math.floor(t0 / HOUR_MS) * HOUR_MS, "a transfer keeps something finer than the hour");
        }
        continue;
      }
      if (k === "balance") continue; // a number of coins, not a time
      if (k.startsWith("q:") || k === "queueCount" || k === "wakeCheck") continue; // the envelope itself, until acked
      if (k.startsWith("hourly:")) {
        assert.equal(v.hour, Math.floor(t0 / HOUR_MS), `${k} keeps something finer than the hour`);
        continue;
      }
      assert.equal(JSON.stringify(v), before.get(k), `${k} changed`);
    }
    assert.equal(after.has("lastFetchAt"), false);
    assert.equal(after.has("lastWakeAt"), false);
    assert.ok(![...after.keys()].some((k) => k.startsWith("bucket:")));
    assert.equal(after.get("profile").createdAt % DAY_MS, 0, "the registration time is kept to the day");
  } finally {
    fetches.restore();
  }
});

test("a lookup takes the number in the body, and the old paths that put it in the URL are gone", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 5 });
  const byBody = (await alice.lookup(bob.number, undefined, false)).json.identity;
  assert.equal(byBody.ed25519, bob.ed25519);
  assert.equal((await alice.lookup("123", undefined, false)).status, 400);
  assert.equal((await alice.lookup(bob.number, "wrong-pin")).status, 404);
  const claimed = (await alice.lookup(bob.number)).json.bundle;
  assert.ok(claimed.oneTimeKey);
  // Apps since 3.0.0-beta.3 look up by body; the paths betas 1 and 2 used answer nothing now, and claim no key.
  const keysBefore = await bob.oneTimeKeys();
  for (const path of [`/v1/identity/${bob.number}`, `/v1/bundle/${bob.number}`, `/v1/bundle/${bob.number}?pin=x`]) {
    const res = await alice.signed("GET", path);
    assert.equal(res.status, 404, path);
    assert.equal(res.json.error, "Not found", path);
  }
  assert.equal(await bob.oneTimeKeys(), keysBefore);
  assert.equal((await relay.call("GET", "/")).json.version, 5);
});

// ── One-time keys ──────────────────────────────────────────────────────────

test("one requester can claim only three one-time keys an hour from one number", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 20 });
  const carol = await makePhone(relay);
  const claimed = [];
  for (let i = 0; i < 3; i++) {
    const b = (await alice.bundle(bob.number)).json.bundle;
    assert.ok(b.oneTimeKey, `claim ${i + 1} should get a one-time key`);
    assert.match(b.claim, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(b.fallback, null);
    claimed.push(b.oneTimeKey.id);
  }
  const fourth = (await alice.bundle(bob.number)).json.bundle;
  assert.equal(fourth.oneTimeKey, null);
  assert.equal(fourth.claim, null);
  assert.equal(fourth.fallback.id, bob.fallbackId);
  // Someone else still gets one.
  assert.ok((await carol.bundle(bob.number)).json.bundle.oneTimeKey);
  // An hour later Alice may claim again.
  advance(60 * 60_000);
  assert.ok((await alice.bundle(bob.number)).json.bundle.oneTimeKey);
  assert.equal(new Set(claimed).size, 3);
});

test("a number hands out at most twenty one-time keys at once, whoever asks", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay, { oneTimeKeys: 60 });
  const askers = [];
  for (let i = 0; i < 7; i++) askers.push(await makePhone(relay));
  let keys = 0;
  let fallbacks = 0;
  for (const a of askers) {
    for (let i = 0; i < 3; i++) {
      const b = (await a.bundle(bob.number)).json.bundle;
      if (b.oneTimeKey) keys++;
      else if (b.fallback) fallbacks++;
    }
  }
  assert.equal(keys, 20);
  assert.equal(fallbacks, 1);
});

test("a claimed key that no send follows up goes back into the pool, last", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 3 });
  assert.equal(await bob.oneTimeKeys(), 3);
  const b = (await alice.bundle(bob.number)).json.bundle;
  assert.equal(await bob.oneTimeKeys(), 2);
  advance(OTK_CLAIM_WINDOW_MS + 1);
  assert.equal(await bob.oneTimeKeys(), 3);
  // The lapsed ticket is dated to the day, not the minute the lookup happened.
  for (const l of relay.env.MAILBOX.storageOf(bob.number).get("lapsedClaims")) assert.equal(l.at % DAY_MS, 0);
  // It is offered again only after every key that was never handed out.
  const carol = await makePhone(relay);
  const offered = [];
  for (let i = 0; i < 3; i++) offered.push((await carol.bundle(bob.number)).json.bundle.oneTimeKey.id);
  assert.equal(offered[2], b.oneTimeKey.id);
  assert.ok(!offered.slice(0, 2).includes(b.oneTimeKey.id));
});

test("a late claim takes its key back out of the pool: no two lookups get the same key", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 4 });
  const first = (await alice.bundle(bob.number)).json.bundle;
  // Alice was offline past the window; the key went back into the pool.
  advance(OTK_CLAIM_WINDOW_MS + 1);
  assert.equal(await bob.oneTimeKeys(), 4);
  // Her first envelope still presents the ticket, late.
  const [token] = await alice.tokens(1);
  assert.equal((await alice.send(token, bob.number, "AAAA", first.claim)).status, 202);
  assert.equal(await bob.oneTimeKeys(), 3);
  // Every later lookup, by anyone, gets some other key.
  const seen = new Set([first.oneTimeKey.id]);
  const askers = [await makePhone(relay), await makePhone(relay)];
  for (const a of askers) {
    for (let i = 0; i < 3; i++) {
      const k = (await a.bundle(bob.number)).json.bundle.oneTimeKey;
      if (!k) continue;
      assert.ok(!seen.has(k.id), `key ${k.id} handed out twice`);
      seen.add(k.id);
    }
  }
  assert.equal(seen.size, 4);
  // And it cannot be republished.
  const again = await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [first.oneTimeKey] });
  assert.equal(again.json.oneTimeKeys, 0);
});

test("one requester claims at most ten keys at once in all, whoever from", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const targets = [];
  for (let i = 0; i < 4; i++) targets.push(await makePhone(relay, { oneTimeKeys: 5 }));
  let keys = 0;
  for (const t of targets) for (let i = 0; i < 3; i++) if ((await alice.bundle(t.number)).json.bundle.oneTimeKey) keys++;
  assert.equal(keys, 10);
});

test("who looked up whom is never stored", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 5 });
  await alice.bundle(bob.number);
  await alice.identity(bob.number);
  const stored = everythingStored(relay, (n) => n === alice.number);
  assert.ok(!stored.includes(bob.number), "the target's number is in the requester's storage");
  // Nor any hash of it: no stored key or value mentions a digest of the number.
  for (const prefix of ["", "otk-pair|"]) {
    const h = await tokenHash(prefix + bob.number);
    assert.ok(!stored.includes(h.slice(0, 16)));
  }
  assert.ok(![...relay.env.MAILBOX.storageOf(alice.number).keys()].some((k) => k.startsWith("bucket:")));
});

test("token batches expire on the hour", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const res = await alice.signed("POST", "/v1/tokens", { count: 3 });
  assert.equal(res.json.expiresAt % (60 * 60_000), 0);
  assert.ok(res.json.expiresAt > Date.now() + 13 * 24 * 60 * 60_000);
});

test("a send that presents the claim ticket spends the key for good", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 5 });
  const b = (await alice.bundle(bob.number)).json.bundle;
  const [token] = await alice.tokens(1);
  assert.equal((await alice.send(token, bob.number, "AAAA", b.claim)).status, 202);
  advance(OTK_CLAIM_WINDOW_MS + 1);
  assert.equal(await bob.oneTimeKeys(), 4);
  // The spent key cannot be published again either.
  const reupload = await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [b.oneTimeKey] });
  assert.equal(reupload.json.oneTimeKeys, 4);
  // A malformed ticket is refused before the token is spent.
  const [t2] = await alice.tokens(1);
  assert.equal((await alice.send(t2, bob.number, "AAAA", "nope")).status, 400);
  assert.equal((await alice.send(t2, bob.number, "AAAA")).status, 202);
});

test("a key out on a claim cannot be republished while it waits", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 2 });
  const b = (await alice.bundle(bob.number)).json.bundle;
  const res = await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [b.oneTimeKey] });
  assert.equal(res.json.oneTimeKeys, 1);
});

test("the one-time key pool holds 500", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay, { oneTimeKeys: 100 });
  for (let round = 0; round < 5; round++) {
    const batch = [];
    for (let i = 0; i < 100; i++) batch.push(await bob.signedKey(`extra-${round}-${i}`));
    await bob.signed("PUT", "/v1/keys", { oneTimeKeys: batch });
  }
  assert.equal(await bob.oneTimeKeys(), 500);
});

test("a phone restored from a recovery file makes its new keys the whole pool", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 5 });
  // Alice holds a claim on one of the old phone's keys and has not written yet.
  const claimed = (await alice.bundle(bob.number)).json.bundle;
  assert.ok(claimed.oneTimeKey && !claimed.oneTimeKey.id.startsWith("restored-"));
  const fresh = [await bob.signedKey("restored-1"), await bob.signedKey("restored-2"), await bob.signedKey("restored-3")];
  const res = await bob.signed("PUT", "/v1/keys", { oneTimeKeys: fresh, replace: true });
  assert.equal(res.status, 200);
  assert.equal(res.json.oneTimeKeys, 3);
  // The claim's window passes: the old key is not put back in the pool.
  advance(OTK_CLAIM_WINDOW_MS + 1);
  assert.equal(await bob.oneTimeKeys(), 3);
  // Whoever looks Bob up now gets one of the new keys, never an old one.
  for (let i = 0; i < 3; i++) {
    const asker = await makePhone(relay);
    const k = (await asker.bundle(bob.number)).json.bundle.oneTimeKey;
    assert.ok(k.id.startsWith("restored-"), `an old key ${k.id} was handed out after the restore`);
  }
  // Alice's late send on the old key is still taken, and spends none of the new ones.
  advance(OTK_CLAIM_WINDOW_MS + 1);
  const [token] = await alice.tokens(1);
  assert.equal((await alice.send(token, bob.number, "AAAA", claimed.claim)).status, 202);
  assert.equal(await bob.oneTimeKeys(), 3);
  // Without replace (every app before recovery files) keys are added, as before.
  const added = await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [await bob.signedKey("restored-4")] });
  assert.equal(added.json.oneTimeKeys, 4);
  // replace must be a boolean.
  assert.equal((await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [], replace: "yes" })).status, 400);
});

// ── TURN ───────────────────────────────────────────────────────────────────

test("every /v1/turn call gets credentials minted for it alone", async () => {
  const relay = makeRelay({ TURN_KEY_ID: "key-id", TURN_KEY_API_TOKEN: "api-token" });
  const fetches = mockFetch((call, n) => Response.json({
    iceServers: { urls: ["stun:stun.cloudflare.com:3478", "turn:turn.cloudflare.com:3478?transport=udp"], username: `user-${n}`, credential: `secret-${n}` },
  }));
  try {
    const alice = await makePhone(relay);
    const bob = await makePhone(relay);
    const a1 = (await alice.signed("GET", "/v1/turn")).json;
    const a2 = (await alice.signed("GET", "/v1/turn")).json;
    const b1 = (await bob.signed("GET", "/v1/turn")).json;
    assert.equal(a1.turn, true);
    const creds = [a1, a2, b1].map((r) => r.iceServers[0].username + "/" + r.iceServers[0].credential);
    assert.equal(new Set(creds).size, 3, "credentials were reused between calls or users");
    assert.equal(fetches.calls.length, 3);
    for (const c of fetches.calls) {
      assert.match(c.url, /\/turn\/keys\/key-id\/credentials\/generate-ice-servers$/);
      assert.equal(JSON.parse(c.body).ttl, 2 * 60 * 60);
    }
  } finally {
    fetches.restore();
  }
});

test("when minting fails a call gets STUN only, never someone else's credentials", async () => {
  const relay = makeRelay({ TURN_KEY_ID: "key-id", TURN_KEY_API_TOKEN: "api-token" });
  let fail = false;
  const fetches = mockFetch((call, n) => fail
    ? new Response("upstream down", { status: 502 })
    : Response.json({ iceServers: [{ urls: "turn:turn.cloudflare.com:3478", username: `u${n}`, credential: `c${n}` }] }));
  try {
    const alice = await makePhone(relay);
    const bob = await makePhone(relay);
    assert.equal((await alice.signed("GET", "/v1/turn")).json.turn, true);
    fail = true;
    const res = (await bob.signed("GET", "/v1/turn")).json;
    assert.equal(res.turn, false);
    assert.deepEqual(res.iceServers, [{ urls: ["stun:stun.cloudflare.com:3478"] }]);
  } finally {
    fetches.restore();
  }
});

test("without a TURN key the relay hands out STUN only and calls nobody", async () => {
  const relay = makeRelay();
  const fetches = mockFetch(() => { throw new Error("no fetch expected"); });
  try {
    const alice = await makePhone(relay);
    const res = (await alice.signed("GET", "/v1/turn")).json;
    assert.equal(res.turn, false);
    assert.equal(fetches.calls.length, 0);
  } finally {
    fetches.restore();
  }
});

// ── Push endpoints ─────────────────────────────────────────────────────────

test("an endpoint on a known push host is used at once", async () => {
  const relay = makeRelay();
  const fetches = mockFetch(() => new Response("ok"));
  try {
    const alice = await makePhone(relay);
    const res = await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/upAbc123?up=1" });
    assert.equal(res.status, 200);
    assert.equal(res.json.verified, true);
    assert.equal(fetches.calls.length, 0);
    const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(alice.number));
    assert.equal(await box.pushEndpoint(), "https://ntfy.sh/upAbc123?up=1");
  } finally {
    fetches.restore();
  }
});

test("endpoints that are not public https hosts are refused", async () => {
  const relay = makeRelay();
  const fetches = mockFetch(() => new Response("ok"));
  try {
    const alice = await makePhone(relay);
    for (const endpoint of [
      "http://ntfy.sh/up",
      "https://127.0.0.1/up",
      "https://[::1]/up",
      "https://localhost/up",
      "https://push.internal/up",
      "https://user:pass@ntfy.sh/up",
      "https://intranet/up",
      "ftp://ntfy.sh/up",
      "https://ntfy.sh/has space",
      42,
    ]) {
      const res = await alice.signed("PUT", "/v1/push", { endpoint });
      assert.equal(res.status, 400, `accepted ${endpoint}`);
    }
    assert.equal(fetches.calls.length, 0);
  } finally {
    fetches.restore();
  }
});

test("an endpoint on another host is used only after its code comes back", async () => {
  const relay = makeRelay();
  const fetches = mockFetch(() => new Response("ok", { status: 200 }));
  try {
    const alice = await makePhone(relay);
    const bob = await makePhone(relay);
    const res = await alice.signed("PUT", "/v1/push", { endpoint: "https://push.example.org/up/abc" });
    assert.equal(res.status, 202);
    assert.equal(res.json.verified, false);
    assert.equal(fetches.calls.length, 1);
    const check = fetches.calls[0];
    assert.equal(check.url, "https://push.example.org/up/abc");
    assert.ok(check.body.startsWith(PUSH_CHALLENGE_PREFIX));
    const code = check.body.slice(PUSH_CHALLENGE_PREFIX.length);

    // Not in use yet: a message for Alice wakes nobody.
    const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(alice.number));
    assert.equal(await box.pushEndpoint(), null);
    const [t1] = await bob.tokens(2);
    await bob.send(t1, alice.number);
    await relay.env.MAILBOX.settle();
    assert.equal(fetches.calls.length, 1);

    // A wrong code changes nothing; the right one turns it on.
    assert.equal((await alice.signed("PUT", "/v1/push/verify", { code: "B".repeat(43) })).status, 403);
    assert.equal((await alice.signed("PUT", "/v1/push/verify", { code })).status, 200);
    assert.equal(await box.pushEndpoint(), "https://push.example.org/up/abc");
    // Spent: the same code does not work twice.
    assert.equal((await alice.signed("PUT", "/v1/push/verify", { code })).status, 403);

    const [t2] = await bob.tokens(1);
    await bob.send(t2, alice.number);
    await relay.env.MAILBOX.settle();
    assert.equal(fetches.calls.length, 2);
    assert.equal(fetches.calls[1].body, "wake");
  } finally {
    fetches.restore();
  }
});

test("a check code expires, and a host that refuses the check is not used", async () => {
  const relay = makeRelay();
  let status = 200;
  const fetches = mockFetch(() => new Response("", { status }));
  try {
    const alice = await makePhone(relay);
    await alice.signed("PUT", "/v1/push", { endpoint: "https://push.example.org/up/1" });
    const code = fetches.calls[0].body.slice(PUSH_CHALLENGE_PREFIX.length);
    advance(11 * 60_000);
    assert.equal((await alice.signed("PUT", "/v1/push/verify", { code })).status, 403);
    status = 500;
    const refused = await alice.signed("PUT", "/v1/push", { endpoint: "https://push.example.org/up/2" });
    assert.equal(refused.status, 400);
  } finally {
    fetches.restore();
  }
});

test("PUSH_HOSTS adds the operator's own push host to the trusted ones", async () => {
  const relay = makeRelay({ PUSH_HOSTS: "ntfy.example.net, other.example.com" });
  const fetches = mockFetch(() => new Response("ok"));
  try {
    const alice = await makePhone(relay);
    const res = await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.example.net/up/x" });
    assert.equal(res.json.verified, true);
    assert.equal(fetches.calls.length, 0);
  } finally {
    fetches.restore();
  }
});

test("PUSH_HOSTS_ONLY trusts the operator's push host alone: an endpoint on ntfy.sh then gets the check", async () => {
  const relay = makeRelay({ PUSH_HOSTS: "ntfy.example.net", PUSH_HOSTS_ONLY: "on" });
  const fetches = mockFetch(() => new Response("ok"));
  try {
    const alice = await makePhone(relay);
    const own = await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.example.net/up/x" });
    assert.equal(own.json.verified, true);
    assert.equal(fetches.calls.length, 0);
    const third = await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/up/y" });
    assert.equal(third.status, 202);
    assert.equal(third.json.verified, false);
    assert.equal(fetches.calls.length, 1);
    assert.equal(fetches.calls[0].url, "https://ntfy.sh/up/y");
    assert.ok(fetches.calls[0].body.startsWith(PUSH_CHALLENGE_PREFIX));
  } finally {
    fetches.restore();
  }
});

test("push endpoint changes are rate limited", async () => {
  const relay = makeRelay();
  const fetches = mockFetch(() => new Response("ok"));
  try {
    const alice = await makePhone(relay);
    for (let i = 0; i < 6; i++) {
      assert.equal((await alice.signed("PUT", "/v1/push", { endpoint: `https://push.example.org/up/${i}` })).status, 202);
    }
    assert.equal((await alice.signed("PUT", "/v1/push", { endpoint: "https://push.example.org/up/7" })).status, 429);
    // Clearing the endpoint is always allowed.
    assert.equal((await alice.signed("PUT", "/v1/push", { endpoint: null })).status, 200);
  } finally {
    fetches.restore();
  }
});

// ── Registration, replay and what a send reveals ──────────────────────────

test("a registration must be recent, and a captured one cannot be replayed into a second account", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const headers = { "x-clairix-sig": await alice.sign(alice.registrationBody), "cf-connecting-ip": "203.0.113.9" };
  // The same body again, at once: its keys are Alice's.
  const again = await relay.call("POST", "/v1/register", { body: alice.registrationBody, headers });
  assert.equal(again.status, 409);
  // And six minutes on, it is stale before its keys are even looked at.
  advance(6 * 60_000);
  const stale = await relay.call("POST", "/v1/register", { body: alice.registrationBody, headers });
  assert.equal(stale.status, 401);
  // A body without a timestamp never registers.
  const without = JSON.stringify({ ...JSON.parse(alice.registrationBody), ts: undefined });
  const res = await relay.call("POST", "/v1/register", { body: without, headers: { ...headers, "x-clairix-sig": await alice.sign(without) } });
  assert.equal(res.status, 401);
});

test("a key belongs to one account; deleting the account gives it back", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const mallory = await makePhone(relay);
  // Mallory registers again with her own identity key but Alice's Curve25519 key.
  const body = { ...JSON.parse(mallory.registrationBody), ts: Date.now(), curve25519: alice.curve25519 };
  body.signature = await mallory.sign(`${alice.curve25519}|${body.sealing}`);
  const text = JSON.stringify(body);
  const res = await relay.call("POST", "/v1/register", { body: text, headers: { "x-clairix-sig": await mallory.sign(text), "cf-connecting-ip": "203.0.113.10" } });
  assert.equal(res.status, 409);
  assert.ok(!(await relay.call("GET", "/")).json.error);
  // Alice deletes her account, and so does Mallory: Alice's Curve25519 key and
  // Mallory's own keys are all free, and the same body now registers.
  assert.equal((await alice.signed("DELETE", "/v1/me")).status, 200);
  assert.equal((await mallory.signed("DELETE", "/v1/me")).status, 200);
  const retry = JSON.stringify({ ...body, ts: Date.now() });
  const after = await relay.call("POST", "/v1/register", { body: retry, headers: { "x-clairix-sig": await mallory.sign(retry), "cf-connecting-ip": "203.0.113.11" } });
  assert.equal(after.status, 201);
  // While Alice still held her account, Mallory's first account kept its own keys.
  const stillMine = await relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(`key:${await sha256Hex(mallory.ed25519)}`)).claimKey("000000000");
  assert.equal(stillMine, false);
});

test("the operator deletes an account asked for by email: keys freed, mailbox wiped, and only with the admin secret", async () => {
  const secret = "operator-secret-0123456789";
  const relay = makeRelay({ ADMIN_SECRET: secret });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [token] = await bob.tokens(1);
  assert.equal((await bob.send(token, alice.number)).status, 202);
  const path = `/v1/admin/accounts/${alice.number}`;
  const as = (given) => ({ headers: { "x-admin-secret": given, "cf-connecting-ip": "203.0.113.20" } });
  // No secret, a wrong one, or another method: nothing happens.
  assert.equal((await relay.call("DELETE", path, { headers: { "cf-connecting-ip": "203.0.113.20" } })).status, 403);
  assert.equal((await relay.call("DELETE", path, as("not-the-secret-0123456789"))).status, 403);
  assert.equal((await relay.call("POST", path, as(secret))).status, 405);
  assert.ok(relay.env.MAILBOX.storageOf(alice.number).get("profile"));
  // With it, the account goes as if its owner had deleted it, the number written as people write it.
  const spaced = `${alice.number.slice(0, 3)}%20${alice.number.slice(3, 6)}%20${alice.number.slice(6)}`;
  const res = await relay.call("DELETE", `/v1/admin/accounts/${spaced}`, as(secret));
  assert.equal(res.status, 200);
  assert.deepEqual(res.json, { ok: true, number: alice.number });
  // The queued envelope, the keys and everything else in the mailbox are gone ...
  assert.equal(relay.env.MAILBOX.storageOf(alice.number).size, 0);
  assert.equal((await alice.signed("GET", "/v1/me")).status, 401);
  // ... and the keys belong to nobody: another registration may take them.
  const free = await relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(`key:${await sha256Hex(alice.ed25519)}`)).claimKey("000000000");
  assert.equal(free, true);
  // A number with no account here, and something that is not a number.
  assert.equal((await relay.call("DELETE", path, as(secret))).status, 404);
  assert.equal((await relay.call("DELETE", "/v1/admin/accounts/12345", as(secret))).status, 400);
  assert.equal((await relay.call("DELETE", "/v1/admin/accounts/%E0%A4%A", as(secret))).status, 400);
  // Bob is untouched.
  assert.equal((await bob.signed("GET", "/v1/me")).status, 200);
});

test("without ADMIN_SECRET the operator's delete is off, and guesses at the secret are limited per address", async () => {
  const off = makeRelay();
  const alice = await makePhone(off);
  const offRes = await off.call("DELETE", `/v1/admin/accounts/${alice.number}`, { headers: { "x-admin-secret": "anything-at-all-0123", "cf-connecting-ip": "203.0.113.21" } });
  assert.equal(offRes.status, 503);
  assert.ok(off.env.MAILBOX.storageOf(alice.number).get("profile"));
  const relay = makeRelay({ ADMIN_SECRET: "operator-secret-0123456789" });
  const statuses = [];
  for (let i = 0; i < 12; i++) {
    const res = await relay.call("DELETE", "/v1/admin/accounts/123456789", { headers: { "x-admin-secret": `guess-${i}-0123456789`, "cf-connecting-ip": "203.0.113.22" } });
    statuses.push(res.status);
  }
  assert.deepEqual(statuses.slice(0, 10), Array(10).fill(403));
  assert.deepEqual(statuses.slice(10), [429, 429]);
});

// ── Suspension (docs/ABUSE.md) ─────────────────────────────────────────────

const OPERATOR_SECRET = "operator-secret-0123456789";

/** A request signed by [phone] (an operator identity, or not) to an admin route, with [secret] in X-Admin-Secret. */
async function adminAs(relay, phone, secret, path, body, ip = "203.0.113.30") {
  const text = JSON.stringify(body);
  const ts = Date.now();
  const nonce = `admin-${ts}-${Math.random().toString(36).slice(2)}`;
  const payload = `${phone.number}\n${ts}\n${nonce}\nPOST\n${path}\n${await sha256Hex(text)}`;
  const headers = {
    "x-clairix-number": phone.number,
    "x-clairix-ts": String(ts),
    "x-clairix-nonce": nonce,
    "x-clairix-sig": await phone.sign(payload),
    "cf-connecting-ip": ip,
  };
  if (secret !== null) headers["x-admin-secret"] = secret;
  return relay.call("POST", path, { body: text, headers });
}

/** Gives the mailbox of [number] live sockets that record how they were closed (the harness simulates none). */
function fakeSockets(relay, number) {
  const closed = [];
  relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(number)); // the object exists from here on
  relay.env.MAILBOX.objects.get(number).state.getWebSockets = () => [{ close: (code, reason) => closed.push([code, reason]), send() {} }];
  return closed;
}

test("a suspended number cannot sign in, loses its sockets and push endpoint, and reads to everyone else as a number nobody holds; lifting it restores it", async () => {
  const relay = makeRelay({ ADMIN_SECRET: OPERATOR_SECRET, OPERATOR_CONTACT: "support@clairix.app", COIN: "on", COIN_INITIAL_BALANCE: "10" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const op = await makePhone(relay, { listed: false });
  relay.env.OPERATOR_KEYS = op.ed25519;
  const pushes = mockFetch(() => new Response("ok"));
  try {
    assert.equal((await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/upAlice" })).status, 200);
    assert.equal((await alice.signed("PUT", "/v1/transport", { key: alice.transportKey })).status, 200);
    // Something waits for Alice before the suspension.
    const [t1] = await bob.tokens(1);
    assert.equal((await bob.send(t1, alice.number, "YmVmb3Jl")).status, 202);
    await relay.env.MAILBOX.settle();
    const sockets = fakeSockets(relay, alice.number);

    const res = await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", { number: alice.number });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { ok: true, number: alice.number, suspended: true, since: dayFloor(Date.now()), already: false });
    // Her sockets are closed and nothing is left to wake her phone.
    assert.deepEqual(sockets, [[4003, "suspended"]]);
    const box = relay.env.MAILBOX.storageOf(alice.number);
    assert.equal(box.get("push"), undefined);
    assert.deepEqual(box.get("suspended"), { since: dayFloor(Date.now()) });

    // Her own phone is told, on every signed request, with where to appeal; the transport key too.
    const me = await alice.signed("GET", "/v1/me");
    assert.equal(me.status, 403);
    assert.match(me.json.error, /suspended on this relay/);
    assert.match(me.json.error, /support@clairix\.app/);
    assert.equal((await alice.signedTransport("GET", "/v1/inbox")).status, 403);
    assert.equal((await alice.signed("POST", "/v1/tokens", { count: 5 })).status, 403);
    assert.equal((await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/upAgain" })).status, 403);
    // Someone without her key learns nothing: a forged request is only "rejected".
    const forged = await relay.call("GET", "/v1/me", {
      headers: { "x-clairix-number": alice.number, "x-clairix-ts": String(Date.now()), "x-clairix-nonce": "forged-nonce-0123456789", "x-clairix-sig": await bob.sign("anything") },
    });
    assert.deepEqual([forged.status, forged.json.error], [401, "Signature rejected"]);

    // To everyone else she is a number nobody holds: a send is answered alike and dropped ...
    const [t2, t3] = await bob.tokens(2);
    const toHer = await bob.send(t2, alice.number, "YWZ0ZXI=");
    const toNobody = await bob.send(t3, "987654321", "YWZ0ZXI=");
    assert.deepEqual([toHer.status, Object.keys(toHer.json)], [toNobody.status, Object.keys(toNobody.json)]);
    assert.equal(toHer.status, 202);
    assert.equal(box.get("queueCount"), 1, "the envelope sent to a suspended number was kept");
    // ... a lookup finds nothing, with or without the key's fingerprint ...
    const lookedUp = await bob.lookup(alice.number);
    const nobody = await bob.lookup("987654321");
    assert.deepEqual([lookedUp.status, lookedUp.json], [nobody.status, nobody.json]);
    assert.equal((await bob.lookup(alice.number, await fingerprint(alice.ed25519), false)).status, 404);
    // ... and a payment is refused before any coin moves.
    const paid = await bob.signed("POST", "/v1/coin/pay", { to: alice.number, pin: await fingerprint(alice.ed25519), amount: 3 });
    assert.deepEqual([paid.status, paid.json], [400, { error: "Payment refused" }]);
    assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 10);
    assert.deepEqual((await bob.signed("GET", "/v1/coin/history")).json.txs, []);
    // A second suspension changes nothing but says so.
    const again = await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", { number: alice.number });
    assert.equal(again.json.already, true);

    // Lifted: she signs in again and collects what waited from before; what was sent meanwhile is gone.
    const lift = await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/unsuspend", { number: alice.number });
    assert.deepEqual([lift.status, lift.json], [200, { ok: true, number: alice.number, suspended: false, wasSuspended: true }]);
    assert.equal((await alice.signed("GET", "/v1/me")).status, 200);
    assert.deepEqual((await alice.inbox()).map((e) => e.data), ["YmVmb3Jl"]);
    const [t4] = await bob.tokens(1);
    assert.equal((await bob.send(t4, alice.number, "YmFjaw==")).status, 202);
    assert.deepEqual((await alice.inbox()).map((e) => e.data), ["YmVmb3Jl", "YmFjaw=="]);
    assert.equal((await bob.lookup(alice.number)).status, 200);
    assert.equal((await alice.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/upAgain" })).status, 200);
    // Lifting what is not suspended is not an error.
    const twice = await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/unsuspend", { number: alice.number });
    assert.equal(twice.json.wasSuspended, false);
  } finally {
    pushes.restore();
  }
});

test("suspending takes an operator identity's signature and the admin secret together", async () => {
  const relay = makeRelay({ ADMIN_SECRET: OPERATOR_SECRET });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const op = await makePhone(relay, { listed: false });
  const target = { number: alice.number };
  // No operator keys set: nobody can suspend, secret or not.
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", target)).status, 503);
  // Listed without padding, the key is still the operator's.
  relay.env.OPERATOR_KEYS = `not-a-key, ${op.ed25519.replace(/=+$/, "")}`;
  // The secret alone, unsigned, is not enough ...
  const unsigned = await relay.call("POST", "/v1/admin/suspend", { body: target, headers: { "x-admin-secret": OPERATOR_SECRET } });
  assert.equal(unsigned.status, 401);
  // ... nor is a signature by someone who is not an operator ...
  const notOperator = await adminAs(relay, bob, OPERATOR_SECRET, "/v1/admin/suspend", target);
  assert.equal(notOperator.status, 403);
  assert.match(notOperator.json.error, /not one of this relay's operators/);
  // ... nor the operator's signature without the secret, or with a wrong one.
  assert.equal((await adminAs(relay, op, null, "/v1/admin/suspend", target)).status, 403);
  assert.equal((await adminAs(relay, op, "not-the-secret-0123456789", "/v1/admin/suspend", target)).status, 403);
  assert.equal(relay.env.MAILBOX.storageOf(alice.number).get("suspended"), undefined);
  // The operator does not suspend itself, a number must be a number, and one nobody holds is said so.
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", { number: op.number })).status, 400);
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", { number: "12345" })).status, 400);
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", { number: "987654321" })).status, 404);
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/unsuspend", { number: "987654321" })).status, 404);
  // Both together suspend.
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/suspend", target)).status, 200);
  assert.equal((await alice.signed("GET", "/v1/me")).status, 403);
  // With ADMIN_SECRET unset nothing works, whatever the identity.
  delete relay.env.ADMIN_SECRET;
  assert.equal((await adminAs(relay, op, OPERATOR_SECRET, "/v1/admin/unsuspend", target, "203.0.113.32")).status, 503);
  assert.equal((await alice.signed("GET", "/v1/me")).status, 403);
  // Guesses at the secret meet the same per-address limit as the other admin routes.
  relay.env.ADMIN_SECRET = OPERATOR_SECRET;
  const statuses = [];
  for (let i = 0; i < 11; i++) statuses.push((await adminAs(relay, op, `guess-${i}-0123456789`, "/v1/admin/unsuspend", target, "203.0.113.31")).status);
  assert.deepEqual([statuses.slice(0, 10).every((s) => s === 403), statuses[10]], [true, 429]);
});

test("the operator's script makes its identity, reads reports, suspends and lifts a suspension", async () => {
  const relay = makeRelay({ ADMIN_SECRET: OPERATOR_SECRET });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const net = network([relay]);
  try {
    const identity = await registerOperator(relay.origin, ENROLL_SECRET);
    assert.match(identity.number, /^[1-9][0-9]{8}$/);
    assert.match(identity.privateKey, /BEGIN PRIVATE KEY/);
    // Registered unlisted: nobody finds the operator's identity by its number.
    assert.equal((await bob.lookup(identity.number)).status, 404);
    relay.env.OPERATOR_KEYS = identity.ed25519;
    const [rt] = await bob.tokens(1, "report");
    assert.equal((await relay.call("POST", "/v1/report", { body: { number: alice.number, reason: "harassment" }, headers: { "x-clairix-token": rt } })).status, 202);
    const reports = await listReports(relay.origin, OPERATOR_SECRET);
    assert.deepEqual([reports.status, reports.body.numbers.map((n) => n.number)], [200, [alice.number]]);
    const suspended = await setSuspended(identity, OPERATOR_SECRET, alice.number, true);
    assert.deepEqual([suspended.status, suspended.body.suspended], [200, true]);
    assert.equal((await alice.signed("GET", "/v1/me")).status, 403);
    assert.equal((await closeReports(relay.origin, OPERATOR_SECRET, alice.number)).body.closed, 1);
    const lifted = await setSuspended(identity, OPERATOR_SECRET, alice.number, false);
    assert.deepEqual([lifted.status, lifted.body.wasSuspended], [200, true]);
    assert.equal((await alice.signed("GET", "/v1/me")).status, 200);
    // Without the secret the script gets nowhere.
    assert.equal((await setSuspended(identity, "wrong-secret-0123456789", alice.number, true)).status, 403);
  } finally {
    net.restore();
  }
});

test("an account from before keys were recorded has them recorded on its next request", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const box = relay.env.MAILBOX.storageOf(alice.number);
  const profile = box.get("profile");
  delete profile.keysClaimed;
  box.set("profile", profile);
  for (const [name] of relay.env.MAILBOX.objects) if (name.startsWith("key:")) relay.env.MAILBOX.objects.delete(name);
  await alice.signed("GET", "/v1/me");
  assert.equal(relay.env.MAILBOX.storageOf(alice.number).get("profile").keysClaimed, true);
  // Each of the three keys, under both of its spellings.
  assert.equal([...relay.env.MAILBOX.objects.keys()].filter((n) => n.startsWith("key:")).length, 6);
});

test("a send to a number that does not exist looks exactly like one that does", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [t1, t2] = await alice.tokens(2);
  const res = await alice.send(t1, "987654321");
  assert.equal(res.status, 202);
  assert.match(res.json.id, /^[0-9a-z]+-[0-9a-f-]{36}$/);
  // The token was spent all the same.
  assert.equal((await alice.send(t1, "987654321")).status, 403);
  assert.equal((await alice.send(t2, "987654321")).status, 202);
  // Paying is no way round it either: see "a payment tells nobody whether a number exists".
});

test("a body larger than the relay could ever want is refused before anything else", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const huge = "A".repeat(300 * 1024);
  const res = await relay.call("POST", "/v1/send", { body: { to: alice.number, envelope: huge }, headers: { "x-clairix-token": token } });
  assert.equal(res.status, 413);
  // Not spent: the request was refused before the token was looked at.
  assert.equal((await alice.send(token, alice.number)).status, 202);
});

test("the secret comparison gives nothing away about the secret's length", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const body = { ...JSON.parse(bob.registrationBody), ts: Date.now(), secret: "x".repeat(200) };
  const text = JSON.stringify(body);
  const res = await relay.call("POST", "/v1/register", { body: text, headers: { "x-clairix-sig": await bob.sign(text), "cf-connecting-ip": "203.0.113.12" } });
  assert.equal(res.status, 403);
});

test("TURN credentials stop at thirty an hour per account and come back with the hour", async () => {
  const relay = makeRelay({ TURN_KEY_ID: "key-id", TURN_KEY_API_TOKEN: "api-token" });
  const fetches = mockFetch((call, n) => Response.json({
    iceServers: { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: `user-${n}`, credential: `secret-${n}` },
  }));
  // The minute limit is the older brake; step past it so the hourly one is what is measured.
  async function turn(phone) {
    for (;;) {
      const res = await phone.signed("GET", "/v1/turn");
      if (res.status === 429 && /minute/.test(res.json.error)) { advance(61_000); continue; }
      return res;
    }
  }
  try {
    const alice = await makePhone(relay);
    for (let i = 0; i < 30; i++) assert.equal((await turn(alice)).status, 200, `call ${i + 1}`);
    const refused = await turn(alice);
    assert.equal(refused.status, 429);
    assert.match(refused.json.error, /hour/);
    advance(60 * 60_000);
    assert.equal((await turn(alice)).status, 200);
  } finally {
    fetches.restore();
  }
});

test("a payment repeated under the same txid moves the coins once", async () => {
  const relay = makeRelay({ COIN: "on", COIN_INITIAL_BALANCE: "100" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const pin = await fingerprint(bob.ed25519);
  const txid = "0f0e0d0c-0b0a-4908-8706-050403020100";
  const first = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin, amount: 5, txid });
  assert.equal(first.status, 200);
  assert.equal(first.json.txid, txid);
  assert.equal(first.json.balance, 95);
  // The same request again, as a phone repeats one whose answer was lost.
  const again = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin, amount: 5, txid });
  assert.equal(again.status, 200);
  assert.equal(again.json.balance, 95);
  assert.equal((await alice.signed("GET", "/v1/coin/balance")).json.balance, 95);
  assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 105);
  assert.equal((await bob.signed("GET", "/v1/coin/history")).json.txs.filter((t) => t.id === txid).length, 1);
  assert.equal((await alice.signed("GET", "/v1/coin/history")).json.txs.filter((t) => t.id === txid).length, 1);
  // Another id is another payment; an id that is not a UUID is replaced with one the relay mints.
  assert.equal((await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin, amount: 5, txid: "1f0e0d0c-0b0a-4908-8706-050403020100" })).json.balance, 90);
  const minted = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin, amount: 1, txid: "not-a-uuid" });
  assert.equal(minted.status, 200);
  assert.match(minted.json.txid, /^[0-9a-f-]{36}$/);
  assert.equal(minted.json.balance, 89);
});

test("a payment tells nobody whether a number exists: a missing payee and a wrong pin are refused alike, and no coin moves", async () => {
  const relay = makeRelay({ COIN: "on" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { listed: false });
  const carol = await makePhone(relay);
  const boxOf = (phone) => relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(phone.number));
  const refusals = async () => [
    // A number nobody holds, with a pin that would have to be someone's.
    await alice.signed("POST", "/v1/coin/pay", { to: "987654321", pin: await fingerprint(bob.ed25519), amount: 1 }),
    // A real (unlisted) number, with the fingerprint of somebody else's key.
    await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(carol.ed25519), amount: 1 }),
    await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: "not-a-fingerprint", amount: 1 }),
  ];
  // Whether or not the payer could cover the amount.
  for (const funded of [false, true]) {
    if (funded) await boxOf(alice).setInitialBalance(5);
    const answers = await refusals();
    for (const res of answers) {
      assert.equal(res.status, 400);
      assert.deepEqual(res.json, { error: "Payment refused" });
    }
    assert.equal((await alice.signed("GET", "/v1/coin/balance")).json.balance, funded ? 5 : 0);
    assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 0);
    // Nothing was debited and refunded: neither ledger holds a thing.
    assert.equal(relay.env.MAILBOX.storageOf(alice.number).has("txHistory"), false);
    assert.equal(relay.env.MAILBOX.storageOf(bob.number).has("txHistory"), false);
  }
  // A payment without a pin is malformed, before anything about the payee is read.
  const bare = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, amount: 1 });
  assert.equal(bare.status, 400);
  assert.match(bare.json.error, /pin/);
  // With the payee's own fingerprint it goes through.
  const paid = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(bob.ed25519), amount: 2 });
  assert.equal(paid.status, 200);
  assert.equal(paid.json.balance, 3);
  assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 2);
  // Past the balance, a payer who knows the key hears that, and nothing moves.
  const short = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(bob.ed25519), amount: 4 });
  assert.equal(short.status, 400);
  assert.equal(short.json.error, "Insufficient balance");
  assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 2);
  // A number given up and taken by someone else: the old pin pays nobody.
  assert.equal((await bob.signed("DELETE", "/v1/me")).status, 200);
  const gone = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(bob.ed25519), amount: 1 });
  assert.deepEqual([gone.status, gone.json], [400, { error: "Payment refused" }]);
  assert.equal((await alice.signed("GET", "/v1/coin/balance")).json.balance, 3);
});

test("a transfer is kept as its id, amount, hour and direction, never who paid whom; a mint names nobody either", async () => {
  const ADMIN = "admin-secret-0123456789";
  const relay = makeRelay({ COIN: "on", COIN_INITIAL_BALANCE: "50", ADMIN_SECRET: ADMIN });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  advance(17 * 60_000 + 4321);
  const hour = Math.floor(Date.now() / HOUR_MS) * HOUR_MS;
  const txid = "2f0e0d0c-0b0a-4908-8706-050403020100";
  const paid = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(bob.ed25519), amount: 7, txid, note: "for the taxi" });
  assert.equal(paid.status, 200);
  const minted = await alice.signed("POST", "/v1/admin/coin/mint", { to: bob.number, amount: 3 });
  assert.equal(minted.status, 403, "minting needs the admin secret");
  const mint = await relay.call("POST", "/v1/admin/coin/mint", { body: { to: bob.number, amount: 3 } });
  assert.equal(mint.status, 401, "and a signed request");
  const signedMint = await mintAs(relay, alice, ADMIN, { to: bob.number, amount: 3 });
  assert.deepEqual([signedMint.status, signedMint.json], [200, { ok: true, balance: 60 }]);
  // What each side's history says: no number, no note, and the hour, not the moment.
  const out = (await alice.signed("GET", "/v1/coin/history")).json.txs;
  assert.deepEqual(out, [{ id: txid, amount: 7, ts: hour, direction: "out" }]);
  const into = (await bob.signed("GET", "/v1/coin/history")).json.txs;
  assert.equal(into.length, 2);
  assert.deepEqual(into[1], { id: txid, amount: 7, ts: hour, direction: "in" });
  assert.deepEqual({ ...into[0], id: "x" }, { id: "x", amount: 3, ts: hour, direction: "in", note: "Minted by the relay" });
  // And what is stored: neither mailbox names the other, nor the phone that minted, nor the note.
  const aliceBox = JSON.stringify([...relay.env.MAILBOX.storageOf(alice.number)]);
  const bobBox = JSON.stringify([...relay.env.MAILBOX.storageOf(bob.number)]);
  assert.ok(!aliceBox.includes(bob.number), "the payer's mailbox names the payee");
  assert.ok(!bobBox.includes(alice.number), "the payee's mailbox names the payer or the minter");
  assert.ok(!aliceBox.includes("taxi") && !bobBox.includes("taxi"), "a note reached the ledger");
  // A credit for a number nobody holds is not made, and leaves nothing behind.
  const nobody = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName("987654321"));
  assert.equal(await nobody.credit(5, txid), null);
  assert.equal(relay.env.MAILBOX.storageOf("987654321").size, 0);
});

test("a transfer is kept 30 days at most, and a hundred at most, whether or not the coin is used again", async () => {
  const relay = makeRelay({ COIN: "on", COIN_INITIAL_BALANCE: "1000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const carol = await makePhone(relay);
  const pin = await fingerprint(bob.ed25519);
  assert.equal((await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin, amount: 1 })).status, 200);
  assert.equal((await carol.signed("POST", "/v1/coin/pay", { to: bob.number, pin, amount: 1 })).status, 200);
  // A day short of 30, both are there; at 30 days, a look forgets them.
  advance(29 * DAY_MS);
  assert.equal((await alice.signed("GET", "/v1/coin/history")).json.txs.length, 1);
  advance(DAY_MS);
  assert.deepEqual((await alice.signed("GET", "/v1/coin/history")).json.txs, []);
  assert.equal(relay.env.MAILBOX.storageOf(alice.number).has("txHistory"), false);
  // Bob looked at nothing all that time: his daily cleanup forgot both anyway.
  await relay.env.MAILBOX.runDueAlarms();
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).has("txHistory"), false);
  assert.equal(relay.env.MAILBOX.storageOf(carol.number).has("txHistory"), false);
  // The balances stay.
  assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 1002);
  // A ledger is a hundred deep: the oldest go first.
  const many = Array.from({ length: 120 }, (_, i) => ({ id: `t${i}`, amount: 1, ts: Math.floor(Date.now() / HOUR_MS) * HOUR_MS, direction: "in" }));
  relay.env.MAILBOX.storageOf(bob.number).set("txHistory", many);
  assert.equal((await bob.signed("POST", "/v1/coin/pay", { to: alice.number, pin: await fingerprint(alice.ed25519), amount: 1 })).status, 200);
  const kept = relay.env.MAILBOX.storageOf(bob.number).get("txHistory");
  assert.equal(kept.length, 100);
  assert.equal(kept[0].direction, "out");
  assert.equal(kept[99].id, "t98");
  assert.equal((await bob.signed("GET", "/v1/coin/history?limit=500")).json.txs.length, 100);
});

test("a ledger an earlier version kept gives up its numbers, notes and moments at the first look", async () => {
  const relay = makeRelay({ COIN: "on" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const now = Date.now();
  const old = (to, from) => [
    { id: "a1", from, to, amount: 4, ts: now - 5 * 60_000 - 123, direction: "out", note: "lunch" },
    { id: "a2", from: "mint", to, amount: 9, ts: now - DAY_MS - 7, direction: "in", note: "Minted by 123456789" },
    { id: "a3", from: "222333444", to, amount: 2, ts: now - 31 * DAY_MS, direction: "in" },
  ];
  const expected = [
    { id: "a1", amount: 4, ts: Math.floor((now - 5 * 60_000 - 123) / HOUR_MS) * HOUR_MS, direction: "out" },
    { id: "a2", amount: 9, ts: Math.floor((now - DAY_MS - 7) / HOUR_MS) * HOUR_MS, direction: "in", note: "Minted by the relay" },
  ];
  relay.env.MAILBOX.storageOf(alice.number).set("txHistory", old(bob.number, alice.number));
  relay.env.MAILBOX.storageOf(bob.number).set("txHistory", old(alice.number, bob.number));
  // Alice's first coin request of any kind rewrites hers, before it answers.
  assert.equal((await alice.signed("GET", "/v1/coin/balance")).status, 200);
  assert.deepEqual(relay.env.MAILBOX.storageOf(alice.number).get("txHistory"), expected);
  assert.deepEqual((await alice.signed("GET", "/v1/coin/history")).json.txs, expected);
  // Bob never asks: his mailbox's daily cleanup does the same.
  advance(DAY_MS + 60_000);
  await relay.env.MAILBOX.runDueAlarms();
  const bobs = relay.env.MAILBOX.storageOf(bob.number).get("txHistory");
  assert.deepEqual(bobs.map((t) => Object.keys(t).sort().join(",")), ["amount,direction,id,ts", "amount,direction,id,note,ts"]);
  for (const name of [alice.number, bob.number]) {
    const box = JSON.stringify(relay.env.MAILBOX.storageOf(name).get("txHistory"));
    for (const gone of [alice.number, bob.number, "123456789", "222333444", "lunch"]) assert.ok(!box.includes(gone), `${gone} left in ${name}'s ledger`);
  }
});

test("the coin is on unless the operator switches it off: GET / says so, and switched off no coin route, balance or mint works", async () => {
  const ADMIN = "admin-secret-0123456789";
  for (const COIN of ["off", " OFF ", "false", "0", "no"]) {
    const relay = makeRelay({ COIN, COIN_INITIAL_BALANCE: "100", ADMIN_SECRET: ADMIN });
    assert.equal((await relay.call("GET", "/")).json.coin, false, String(COIN));
    const alice = await makePhone(relay);
    const bob = await makePhone(relay);
    // No starting balance, whatever COIN_INITIAL_BALANCE says.
    assert.equal(relay.env.MAILBOX.storageOf(alice.number).has("balance"), false);
    const answers = [
      await alice.signed("GET", "/v1/coin/balance"),
      await alice.signed("GET", "/v1/coin/history"),
      await alice.signed("POST", "/v1/coin/pay", { to: bob.number, pin: await fingerprint(bob.ed25519), amount: 1 }),
      await mintAs(relay, alice, ADMIN, { to: bob.number, amount: 5 }),
    ];
    for (const res of answers) assert.deepEqual([res.status, res.json], [404, { error: "ClairixCoin is off on this relay" }], String(COIN));
    assert.equal(relay.env.MAILBOX.storageOf(bob.number).has("balance"), false);
  }
  // Unset, as on every relay from before the setting, and anything but an "off" value: on.
  for (const COIN of [undefined, "", "on", " TRUE ", "yes", "1"]) {
    const vars = { COIN_INITIAL_BALANCE: "100" };
    if (COIN !== undefined) vars.COIN = COIN;
    const relay = makeRelay(vars);
    assert.equal((await relay.call("GET", "/")).json.coin, true, String(COIN));
    const alice = await makePhone(relay);
    assert.equal((await alice.signed("GET", "/v1/coin/balance")).json.balance, 100);
  }
});

/** POST /v1/admin/coin/mint as the operator does it: signed by a phone on the relay, with the admin secret. */
async function mintAs(relay, phone, secret, body) {
  const text = JSON.stringify(body);
  const ts = Date.now();
  const nonce = `mint-${ts}-${Math.random().toString(36).slice(2)}`;
  const payload = `${phone.number}\n${ts}\n${nonce}\nPOST\n/v1/admin/coin/mint\n${await sha256Hex(text)}`;
  return relay.call("POST", "/v1/admin/coin/mint", {
    body: text,
    headers: {
      "x-clairix-number": phone.number,
      "x-clairix-ts": String(ts),
      "x-clairix-nonce": nonce,
      "x-clairix-sig": await phone.sign(payload),
      "x-admin-secret": secret,
    },
  });
}

test("the relay names its alternate addresses: https origins only, trimmed, in order", async () => {
  const relay = makeRelay({ RELAY_ALTERNATES: " https://door-two.example/, https://clairix-relay.example.workers.dev ,http://plain.example, https://bad.example/path, " });
  const info = (await relay.call("GET", "/")).json;
  assert.deepEqual(info.alternates, ["https://door-two.example", "https://clairix-relay.example.workers.dev"]);
  assert.deepEqual((await makeRelay().call("GET", "/")).json.alternates, []);
});

test("the relay names its onion address only when it is a v3 onion origin", async () => {
  const onion = "http://" + "abcdefghijklmnopqrstuvwxyz234567".repeat(2).slice(0, 56) + ".onion";
  assert.equal((await makeRelay({ RELAY_ONION: ` ${onion.toUpperCase()}/ ` }).call("GET", "/")).json.onion, onion);
  assert.equal((await makeRelay().call("GET", "/")).json.onion, null);
  for (const bad of ["https://" + onion.slice(7), "http://short.onion", onion + ":8080", onion + "/path", "http://example.org"]) {
    assert.equal((await makeRelay({ RELAY_ONION: bad }).call("GET", "/")).json.onion, null, bad);
  }
});

test("a key registered under one spelling cannot be registered again under another", async () => {
  resetClock();
  const relay = makeRelay();
  const victim = await makePhone(relay);
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const b64 = (u8) => Buffer.from(u8).toString("base64");
  const sign = async (t) => b64(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, new TextEncoder().encode(t))));
  const ed25519 = b64(new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey)));
  const register = async (curve25519, sealing) => {
    const body = JSON.stringify({ ts: Date.now(), secret: "test-enrollment-secret-0123456789", ed25519, curve25519, sealing,
      signature: await sign(`${curve25519}|${sealing}`), oneTimeKeys: [] });
    return relay.call("POST", "/v1/register", { body, headers: { "x-clairix-sig": await sign(body), "cf-connecting-ip": "192.0.2.77" } });
  };
  const unpadded = (k) => k.replace(/=+$/, "");
  const urlSafe = (k) => unpadded(k).replace(/\+/g, "-").replace(/\//g, "_");
  // The other legal spelling is caught by the record under both spellings.
  assert.equal((await register(unpadded(victim.curve25519), unpadded(victim.sealing))).status, 409);
  // Any other spelling (URL-safe letters, whitespace) is not a key at all.
  const k = victim.curve25519.includes("+") || victim.curve25519.includes("/") ? victim.curve25519 : null;
  if (k) assert.equal((await register(urlSafe(k), victim.sealing)).status, 400);
  assert.equal((await register(` ${victim.curve25519}`, victim.sealing)).status, 400);
  // Nor is the same Curve25519 key with its top bit set, which X25519 ignores.
  const raw = Buffer.from(victim.curve25519, "base64");
  raw[31] |= 0x80;
  assert.equal((await register(raw.toString("base64"), victim.sealing)).status, 400);
});

test("a phone's sealing prekey is stored, served with its keys, and only replaced by a newer one", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  // Served with the bundle and with the identity alone, and shown to its owner.
  assert.deepEqual((await alice.bundle(bob.number)).json.bundle.sealingPrekey, bob.sealingPrekey);
  assert.deepEqual((await alice.identity(bob.number)).json.identity.sealingPrekey, bob.sealingPrekey);
  assert.deepEqual((await bob.signed("GET", "/v1/me")).json.profile.sealingPrekey, bob.sealingPrekey);

  // A week on, the phone publishes its next one.
  advance(7 * DAY_MS);
  const next = await bob.signedPrekey();
  assert.equal((await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [], sealingPrekey: next })).status, 200);
  assert.deepEqual((await alice.identity(bob.number)).json.identity.sealingPrekey, next);
  // The old one, sent again (a replayed request), does not take its place.
  assert.equal((await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [], sealingPrekey: bob.sealingPrekey })).status, 200);
  assert.deepEqual((await alice.identity(bob.number)).json.identity.sealingPrekey, next);
});

test("a sealing prekey senders would refuse is refused", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const mallory = await makePhone(relay);
  const now = Date.now();
  const refused = async (prekey, what) => {
    const res = await bob.signed("PUT", "/v1/keys", { oneTimeKeys: [], sealingPrekey: prekey });
    assert.equal(res.status, 400, `${what}: ${JSON.stringify(res.json)}`);
  };
  await refused(await bob.signedPrekey(now - 15 * DAY_MS), "two weeks old");
  await refused(await bob.signedPrekey(now + 2 * DAY_MS), "from the future");
  await refused(await mallory.signedPrekey(now), "signed by someone else");
  const good = await bob.signedPrekey(now);
  await refused({ ...good, created: good.created + 1 }, "time changed under the signature");
  // A key with the top bit set is X25519's second spelling of another key.
  const bytes = Buffer.from(good.key, "base64");
  bytes[31] |= 0x80;
  await refused(await bob.signedPrekey(now, bytes.toString("base64")), "not canonical");
  await refused({ key: good.key, signature: good.signature }, "no time");
  // Nothing refused replaced what was there.
  assert.deepEqual((await bob.signed("GET", "/v1/me")).json.profile.sealingPrekey, bob.sealingPrekey);
});

test("a phone from before sealing prekeys registers and is served without one", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const old = await makePhone(relay, { prekey: false });
  const bundle = (await alice.bundle(old.number)).json.bundle;
  assert.equal(bundle.sealingPrekey, null);
  assert.equal(bundle.sealing, old.sealing);
});

test("the transport key collects envelopes and does nothing else", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  // Not set yet: refused.
  assert.equal((await bob.signedTransport("GET", "/v1/inbox")).status, 401);
  assert.equal((await bob.signed("PUT", "/v1/transport", { key: bob.transportKey })).status, 200);

  const [t] = await alice.tokens(1);
  assert.equal((await alice.send(t, bob.number)).status, 202);
  const inbox = await bob.signedTransport("GET", "/v1/inbox");
  assert.equal(inbox.status, 200);
  assert.equal(inbox.json.envelopes.length, 1);
  const ack = await bob.signedTransport("POST", "/v1/ack", { ids: [inbox.json.envelopes[0].id] });
  assert.equal(ack.status, 200);
  assert.equal(ack.json.removed, 1);

  // Everything else needs the identity key.
  for (const [method, path, body] of [
    ["GET", "/v1/me"],
    ["POST", "/v1/tokens", { count: 1 }],
    ["POST", "/v1/lookup", { number: alice.number, claim: false }],
    ["PUT", "/v1/keys", { oneTimeKeys: [] }],
    ["PUT", "/v1/transport", { key: null }],
    ["DELETE", "/v1/me"],
  ]) {
    assert.equal((await bob.signedTransport(method, path, body)).status, 403, `${method} ${path}`);
  }
  // The identity key still collects too.
  assert.equal((await bob.signed("GET", "/v1/inbox")).status, 200);

  // It cannot be the identity key itself, and it can be removed.
  assert.equal((await bob.signed("PUT", "/v1/transport", { key: bob.ed25519 })).status, 400);
  assert.equal((await bob.signed("PUT", "/v1/transport", { key: null })).status, 200);
  assert.equal((await bob.signedTransport("GET", "/v1/inbox")).status, 401);
});

test("a sender's alert travels with the envelope, and nothing else is accepted as one", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [t1, t2, t3] = await alice.tokens(3);
  assert.equal((await alice.send(t1, bob.number, undefined, undefined, { alert: "call" })).status, 202);
  assert.equal((await alice.send(t2, bob.number)).status, 202);
  assert.equal((await alice.send(t3, bob.number, undefined, undefined, { alert: "ring-loudly" })).status, 400);
  const got = await bob.inbox();
  assert.deepEqual(got.map((e) => e.alert ?? null).sort(), ["call", null].sort());
});

// ── Contacts' passes ───────────────────────────────────────────────────────

/** A pass as the app makes one: HMAC-SHA256 under the recipient's pass key over clairix-pass-v1|number|envelope. */
async function passFor(keyB64, number, envelope) {
  const key = await crypto.subtle.importKey("raw", Buffer.from(keyB64, "base64"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`clairix-pass-v1|${number}|${envelope}`));
  return Buffer.from(mac).toString("base64");
}

/** Every envelope queued for [number], past the 200 one inbox page holds. */
function queued(relay, number) {
  return [...relay.env.MAILBOX.storageOf(number)].filter(([k]) => k.startsWith("q:")).map(([, e]) => e);
}

const randomKey = () => Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString("base64");

test("a pass key is set with the identity key only, must be 32 bytes, and can be removed", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  assert.equal((await bob.signed("GET", "/v1/me")).json.pass, false);
  for (const key of ["", "short", randomKey().slice(0, -1), `${randomKey()}AA`, 42]) {
    assert.equal((await bob.signed("PUT", "/v1/pass", { key })).status, 400, String(key));
  }
  assert.equal((await bob.signed("PUT", "/v1/transport", { key: bob.transportKey })).status, 200);
  assert.equal((await bob.signedTransport("PUT", "/v1/pass", { key: randomKey() })).status, 403);
  assert.equal((await bob.signed("PUT", "/v1/pass", { key: randomKey() })).status, 200);
  assert.equal((await bob.signed("GET", "/v1/me")).json.pass, true);
  assert.equal((await bob.signed("PUT", "/v1/pass", { key: null })).status, 200);
  assert.equal((await bob.signed("GET", "/v1/me")).json.pass, false);
});

test("without a pass key a mailbox takes everyone alike, as before passes", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(bob.number));
  for (let i = 0; i < 400; i++) assert.ok((await box.enqueue("AAAA", null, true)).ok);
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).get("strangerCount"), undefined);
});

test("one sender without the owner's pass cannot fill a mailbox against the owner's contacts", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const mallory = await makePhone(relay);
  const bob = await makePhone(relay);
  const key = randomKey();
  assert.equal((await bob.signed("PUT", "/v1/pass", { key })).status, 200);
  const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(bob.number));

  // Strangers share 300 places; past them envelopes are dropped without a word.
  for (let i = 0; i < 300; i++) assert.ok((await box.enqueue("AAAA", null, true)).ok, `stranger ${i}`);
  const [t1, t2, t3] = await mallory.tokens(3);
  const dropped = await mallory.send(t1, bob.number, "QkJCQg==");
  assert.equal(dropped.status, 202);
  // A made-up pass is no better than none.
  assert.equal((await mallory.send(t2, bob.number, "QkJCQg==", undefined, { pass: randomKey() })).status, 202);
  assert.equal(queued(relay, bob.number).length, 300);

  // Alice, whom Bob has written to, still gets through.
  const envelope = "Q0NDQw==";
  const [ta] = await alice.tokens(1);
  const res = await alice.send(ta, bob.number, envelope, undefined, { pass: await passFor(key, bob.number, envelope) });
  assert.equal(res.status, 202);
  const inbox = queued(relay, bob.number);
  assert.equal(inbox.length, 301);
  assert.ok(inbox.some((e) => e.data === envelope));
  // And what she sent took nothing from the strangers' share.
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).get("strangerCount"), 300);

  // A pass is bound to its envelope: Mallory, having seen Alice's, cannot use it on another.
  const stolen = await passFor(key, bob.number, envelope);
  assert.equal((await mallory.send(t3, bob.number, "RERERA==", undefined, { pass: stolen })).status, 202);
  assert.ok(!queued(relay, bob.number).some((e) => e.data === "RERERA=="));
});

test("the strangers' share comes back as their envelopes are collected, within an hourly allowance", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  assert.equal((await bob.signed("PUT", "/v1/pass", { key: randomKey() })).status, 200);
  const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(bob.number));
  for (let i = 0; i < 300; i++) assert.ok((await box.enqueue("AAAA", null, true)).ok);
  const ids = queued(relay, bob.number).map((e) => e.id);
  assert.equal((await bob.signed("POST", "/v1/ack", { ids })).json.removed, 300);
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).get("strangerCount"), 0);
  assert.equal([...relay.env.MAILBOX.storageOf(bob.number).keys()].filter((k) => k.startsWith("sq:")).length, 0);
  // The queue has room again, but the hour's allowance of 300 is spent until the next hour.
  assert.equal((await box.enqueue("AAAA", null, true)).status, 404);
  advance(HOUR_MS);
  let accepted = 0;
  for (let i = 0; i < 400; i++) if ((await box.enqueue("AAAA", null, true)).ok) accepted++;
  assert.equal(accepted, 300);
});

test("strangers' envelopes that expire give their share back", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  assert.equal((await bob.signed("PUT", "/v1/pass", { key: randomKey() })).status, 200);
  const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(bob.number));
  for (let i = 0; i < 10; i++) assert.ok((await box.enqueue("AAAA", null, true)).ok);
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).get("strangerCount"), 10);
  for (let day = 0; day < 32; day++) {
    advance(DAY_MS);
    await relay.env.MAILBOX.runDueAlarms();
  }
  assert.equal((await bob.inbox()).length, 0);
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).get("strangerCount"), 0);
});

test("a held envelope counts against the strangers' share from the moment it is accepted", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "5000" });
  const bob = await makePhone(relay);
  assert.equal((await bob.signed("PUT", "/v1/pass", { key: randomKey() })).status, 200);
  const box = relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(bob.number));
  for (let i = 0; i < 300; i++) assert.ok((await box.enqueue("AAAA", null, false)).ok);
  assert.equal((await box.enqueue("AAAA", null, false)).status, 404);
  advance(10_000);
  await relay.env.MAILBOX.runDueAlarms();
  const ids = queued(relay, bob.number).map((e) => e.id);
  assert.equal(ids.length, 300);
  await bob.signed("POST", "/v1/ack", { ids });
  assert.equal(relay.env.MAILBOX.storageOf(bob.number).get("strangerCount"), 0);
});

test("a pass in the wrong form is refused before a token is spent", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [t] = await alice.tokens(1);
  assert.equal((await alice.send(t, bob.number, undefined, undefined, { pass: "nope" })).status, 400);
  assert.equal((await alice.send(t, bob.number)).status, 202);
});

// ── Abuse reports ──────────────────────────────────────────────────────────

const ADMIN = "admin-secret-0123456789";
const hash = (c) => c.repeat(64);

/** A report as the app files one: a report token, and nothing that names the phone. */
function fileReport(relay, token, body) {
  return relay.call("POST", "/v1/report", { body, headers: token === null ? {} : { "x-clairix-token": token } });
}

/** The operator's view, with the admin secret. */
function adminReports(relay, method = "GET", body, secret = ADMIN) {
  return relay.call(method, "/v1/admin/reports", { body, headers: { "x-admin-secret": secret, "cf-connecting-ip": "203.0.113.200" } });
}

test("a report spends a report token, names nobody, and waits for the operator by the day", async () => {
  const relay = makeRelay({ ADMIN_SECRET: ADMIN });
  const alice = await makePhone(relay);
  const mallory = await makePhone(relay);
  const [t1, t2] = await alice.tokens(2, "report");
  const res = await fileReport(relay, t1, { number: mallory.number, envelope: hash("a"), reason: "threats" });
  assert.equal(res.status, 202);
  // A token buys one report; none, or one of another kind, buys nothing.
  assert.equal((await fileReport(relay, t1, { number: mallory.number, reason: "spam" })).status, 403);
  assert.equal((await fileReport(relay, null, { number: mallory.number, reason: "spam" })).status, 401);
  const [delivery] = await alice.tokens(1);
  assert.equal((await fileReport(relay, delivery, { number: mallory.number, reason: "spam" })).status, 403);
  // A report that names its sender is refused outright.
  const named = await relay.call("POST", "/v1/report", {
    body: { number: mallory.number, reason: "spam" },
    headers: { "x-clairix-token": t2, "x-clairix-number": alice.number },
  });
  assert.equal(named.status, 400);

  // Kept: who was reported, why, an envelope's hash and the day; nothing about Alice.
  const stored = everythingStored(relay, (n) => n === "abuse-reports");
  assert.ok(stored.includes(mallory.number));
  assert.ok(!stored.includes(alice.number));
  const kept = [...relay.env.MAILBOX.storageOf("abuse-reports")].filter(([k]) => k.startsWith("r:")).map(([, v]) => v);
  assert.deepEqual(kept, [{ number: mallory.number, relay: null, envelope: hash("a"), reason: "threats", day: dayFloor(Date.now()) }]);

  const view = await adminReports(relay);
  assert.equal(view.status, 200);
  assert.deepEqual(view.json.numbers, [
    { number: mallory.number, relay: null, reports: 1, reasons: { threats: 1 }, envelopes: [hash("a")], firstDay: dayFloor(Date.now()), lastDay: dayFloor(Date.now()) },
  ]);
});

test("a malformed report is refused before its token is spent", async () => {
  const relay = makeRelay({ ADMIN_SECRET: ADMIN });
  const alice = await makePhone(relay);
  const [t] = await alice.tokens(1, "report");
  for (const body of [
    { number: "12345", reason: "spam" },
    { number: "482913605", reason: "rude" },
    { number: "482913605", reason: "spam", envelope: "not-a-hash" },
    { number: "482913605", reason: "spam", envelope: hash("A") },
    { number: "482913605", reason: "spam", relay: "http://plain.example" },
  ]) {
    assert.equal((await fileReport(relay, t, body)).status, 400, JSON.stringify(body));
  }
  assert.equal((await fileReport(relay, t, { number: "482913605", reason: "spam" })).status, 202);
});

test("the same envelope counts once, another relay's number is kept apart, and nothing says how often a number was reported", async () => {
  const relay = makeRelay({ ADMIN_SECRET: ADMIN });
  const alice = await makePhone(relay);
  const tokens = await alice.tokens(6, "report");
  assert.equal((await fileReport(relay, tokens[0], { number: "482913605", envelope: hash("b"), reason: "harassment" })).status, 202);
  // The same envelope again (a phone retrying): one report.
  assert.equal((await fileReport(relay, tokens[1], { number: "482913605", envelope: hash("b"), reason: "harassment" })).status, 202);
  assert.equal((await fileReport(relay, tokens[2], { number: "482913605", envelope: hash("c"), reason: "spam" })).status, 202);
  // The same digits on another relay are someone else; this relay's own address is no other relay.
  assert.equal((await fileReport(relay, tokens[3], { number: "482913605", relay: "https://other.example", reason: "impersonation" })).status, 202);
  assert.equal((await fileReport(relay, tokens[4], { number: "111222333", relay: relay.origin, reason: "other" })).status, 202);
  const numbers = (await adminReports(relay)).json.numbers;
  assert.deepEqual(numbers.map((n) => [n.number, n.relay, n.reports]), [
    ["482913605", null, 2],
    ["111222333", null, 1],
    ["482913605", "https://other.example", 1],
  ]);
  assert.deepEqual(numbers[0].reasons, { harassment: 1, spam: 1 });
  assert.deepEqual(numbers[0].envelopes.sort(), [hash("b"), hash("c")]);
});

test("the operator closes what was reported about a number, and reports go after 90 days", async () => {
  const relay = makeRelay({ ADMIN_SECRET: ADMIN });
  const alice = await makePhone(relay);
  const [t1, t2, t3] = await alice.tokens(3, "report");
  await fileReport(relay, t1, { number: "482913605", envelope: hash("d"), reason: "threats" });
  await fileReport(relay, t2, { number: "111222333", reason: "spam" });
  const closed = await adminReports(relay, "DELETE", { number: "482913605" });
  assert.equal(closed.status, 200);
  assert.equal(closed.json.closed, 1);
  assert.deepEqual((await adminReports(relay)).json.numbers.map((n) => n.number), ["111222333"]);
  assert.equal((await adminReports(relay, "DELETE", { number: "bad" })).status, 400);
  assert.equal((await adminReports(relay, "POST")).status, 405);

  await fileReport(relay, t3, { number: "999888777", reason: "other" });
  for (let day = 0; day < 92; day++) {
    advance(DAY_MS);
    await relay.env.MAILBOX.runDueAlarms();
  }
  assert.deepEqual((await adminReports(relay)).json.numbers, []);
  assert.equal(relay.env.MAILBOX.storageOf("abuse-reports").size, 0);
});

test("only the admin secret reads reports, and with none set nobody does", async () => {
  const relay = makeRelay({ ADMIN_SECRET: ADMIN });
  assert.equal((await adminReports(relay, "GET", undefined, "wrong-secret-0123456789")).status, 403);
  assert.equal((await adminReports(relay, "GET", undefined, "")).status, 403);
  const closedRelay = makeRelay();
  assert.equal((await adminReports(closedRelay)).status, 503);
});

// ── Open registration ──────────────────────────────────────────────────────

/** A registration for a new identity, signed as the app signs one, with [secret] (none when null). */
async function registration(relay, { secret = null, ip = "192.0.2.10", keys = null } = {}) {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const b64 = (u8) => Buffer.from(u8).toString("base64");
  const sign = async (t) => b64(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, new TextEncoder().encode(t))));
  const ed25519 = b64(new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey)));
  const curveKey = () => {
    const k = crypto.getRandomValues(new Uint8Array(32));
    k[31] &= 0x3f;
    return b64(k);
  };
  const curve25519 = keys?.curve25519 ?? curveKey();
  const sealing = keys?.sealing ?? curveKey();
  const body = JSON.stringify({
    ts: Date.now(),
    ...(secret === null ? {} : { secret }),
    ed25519, curve25519, sealing,
    signature: await sign(`${curve25519}|${sealing}`),
    oneTimeKeys: [],
  });
  return relay.call("POST", "/v1/register", { body, headers: { "x-clairix-sig": await sign(body), "cf-connecting-ip": ip } });
}

test("without an invite or the secret a relay gives no number, unless its operator opened registration", async () => {
  for (const vars of [{}, { OPEN_REGISTRATION: "off" }, { OPEN_REGISTRATION: "yes please" }]) {
    const relay = makeRelay(vars);
    const res = await registration(relay);
    assert.equal(res.status, 403, JSON.stringify(vars));
    assert.match(res.json.error, /invite/);
    // An empty secret is no secret.
    assert.equal((await registration(relay, { secret: "", ip: "192.0.2.11" })).status, 403);
    // And a wrong one is still refused as before.
    assert.equal((await registration(relay, { secret: "wrong-secret-0123456789", ip: "192.0.2.12" })).status, 403);
  }
});

test("open registration takes phones with no invite or secret up to its daily cap, and keeps only a count", async () => {
  const relay = makeRelay({ OPEN_REGISTRATION: "on", OPEN_REGISTRATION_PER_DAY: "2" });
  assert.equal((await registration(relay, { ip: "192.0.2.21" })).status, 201);
  assert.equal((await registration(relay, { secret: "", ip: "192.0.2.22" })).status, 201);
  const third = await registration(relay, { ip: "192.0.2.23" });
  assert.equal(third.status, 429);
  assert.match(third.json.error, /invite/);
  assert.ok(Number(third.headers.get("retry-after")) > 0);
  // The secret and invites are untouched by the cap.
  assert.equal((await registration(relay, { secret: "test-enrollment-secret-0123456789", ip: "192.0.2.24" })).status, 201);
  const opened = [...relay.env.MAILBOX.storageOf("open-registration")];
  assert.deepEqual(opened, [["openRegistrations", { day: dayFloor(Date.now()), count: 2 }]]);
  // A new day, a new allowance.
  advance(DAY_MS);
  assert.equal((await registration(relay, { ip: "192.0.2.25" })).status, 201);
});

test("an open registration that fails gives its place back", async () => {
  const relay = makeRelay({ OPEN_REGISTRATION: "true", OPEN_REGISTRATION_PER_DAY: "1" });
  const victim = await makePhone(relay);
  // Someone else's keys: refused, and today's one open registration is still there.
  const stolen = await registration(relay, { keys: { curve25519: victim.curve25519, sealing: victim.sealing }, ip: "192.0.2.31" });
  assert.equal(stolen.status, 409);
  assert.equal((await registration(relay, { ip: "192.0.2.32" })).status, 201);
  assert.equal((await registration(relay, { ip: "192.0.2.33" })).status, 429);
});

test("open registration still meets the per-address limit every registration does", async () => {
  const relay = makeRelay({ OPEN_REGISTRATION: "on", OPEN_REGISTRATION_PER_DAY: "100" });
  const statuses = [];
  for (let i = 0; i < 11; i++) statuses.push((await registration(relay, { ip: "192.0.2.40" })).status);
  assert.deepEqual(statuses.slice(0, 10), Array(10).fill(201));
  assert.equal(statuses[10], 429);
  assert.equal((await registration(relay, { ip: "192.0.2.41" })).status, 201);
});
