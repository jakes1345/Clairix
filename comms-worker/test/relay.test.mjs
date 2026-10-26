// The relay's comms routes end to end: the Worker's own fetch handler and
// Mailbox objects over in-memory storage (see harness/fake-env.mjs).
// Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, mockFetch, resetClock } from "./harness/fake-env.mjs";
import { DAY_MS, HOUR_MS, tokenHash, tokenShardName, OTK_CLAIM_WINDOW_MS, PUSH_CHALLENGE_PREFIX } from "../src/comms-logic.ts";
import { sha256Hex } from "../src/env.ts";

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
  for (let i = 0; i < 2500; i++) {
    last = await box.enqueue("AAAA");
    if (last.ok) accepted++;
    else break;
  }
  assert.equal(accepted, 1800);
  assert.equal(last.status, 429);
  // Through the Worker the same limit is a 429 with Retry-After.
  const alice = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const res = await alice.send(token, bob.number);
  assert.equal(res.status, 429);
  assert.ok(res.headers.get("retry-after"));
});

test("nothing a mailbox stores says when its owner was last here", async () => {
  const relay = makeRelay();
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
    const after = relay.env.MAILBOX.storageOf(alice.number);
    for (const [k, v] of after) {
      if (k === "nonces") {
        // Kept for the replay window, to the minute.
        for (const seen of Object.values(v)) assert.equal(seen % 60_000, 0, "a nonce is stored with its exact time");
        continue;
      }
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

test("a lookup takes the number in the body, and the old paths still answer the same", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 5 });
  const byBody = (await alice.lookup(bob.number, undefined, false)).json.identity;
  const byPath = (await alice.signed("GET", `/v1/identity/${bob.number}`)).json.identity;
  assert.deepEqual(byBody, byPath);
  assert.equal(byBody.ed25519, bob.ed25519);
  assert.equal((await alice.lookup("123", undefined, false)).status, 400);
  assert.equal((await alice.lookup(bob.number, "wrong-pin")).status, 404);
  const claimed = (await alice.lookup(bob.number)).json.bundle;
  assert.ok(claimed.oneTimeKey);
  const legacy = (await alice.signed("GET", `/v1/bundle/${bob.number}`)).json.bundle;
  assert.ok(legacy.oneTimeKey);
  assert.notEqual(legacy.oneTimeKey.id, claimed.oneTimeKey.id);
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
  // A payer who cannot cover the amount hears the same whether or not the
  // number exists, so an unlisted number cannot be found by paying it.
  const bob = await makePhone(relay, { listed: false });
  for (const to of ["987654321", bob.number]) {
    const pay = await alice.signed("POST", "/v1/coin/pay", { to, amount: 1 });
    assert.equal(pay.status, 400);
    assert.equal(pay.json.error, "Insufficient balance", to);
  }
  // One who can is refused for a number that does not exist, and keeps the coins.
  await relay.env.MAILBOX.get(relay.env.MAILBOX.idFromName(alice.number)).setInitialBalance(5);
  const refused = await alice.signed("POST", "/v1/coin/pay", { to: "987654321", amount: 1 });
  assert.equal(refused.status, 400);
  assert.equal(refused.json.error, "Payment refused");
  assert.equal((await alice.signed("GET", "/v1/coin/balance")).json.balance, 5);
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
  const relay = makeRelay({ COIN_INITIAL_BALANCE: "100" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const txid = "0f0e0d0c-0b0a-4908-8706-050403020100";
  const first = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, amount: 5, txid });
  assert.equal(first.status, 200);
  assert.equal(first.json.txid, txid);
  assert.equal(first.json.balance, 95);
  // The same request again, as a phone repeats one whose answer was lost.
  const again = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, amount: 5, txid });
  assert.equal(again.status, 200);
  assert.equal(again.json.balance, 95);
  assert.equal((await alice.signed("GET", "/v1/coin/balance")).json.balance, 95);
  assert.equal((await bob.signed("GET", "/v1/coin/balance")).json.balance, 105);
  assert.equal((await bob.signed("GET", "/v1/coin/history")).json.txs.filter((t) => t.id === txid).length, 1);
  assert.equal((await alice.signed("GET", "/v1/coin/history")).json.txs.filter((t) => t.id === txid).length, 1);
  // Another id is another payment; an id that is not a UUID is replaced with one the relay mints.
  assert.equal((await alice.signed("POST", "/v1/coin/pay", { to: bob.number, amount: 5, txid: "1f0e0d0c-0b0a-4908-8706-050403020100" })).json.balance, 90);
  const minted = await alice.signed("POST", "/v1/coin/pay", { to: bob.number, amount: 1, txid: "not-a-uuid" });
  assert.equal(minted.status, 200);
  assert.match(minted.json.txid, /^[0-9a-f-]{36}$/);
  assert.equal(minted.json.balance, 89);
});

test("the relay names its alternate addresses: https origins only, trimmed, in order", async () => {
  const relay = makeRelay({ RELAY_ALTERNATES: " https://door-two.example/, https://clairix-relay.example.workers.dev ,http://plain.example, https://bad.example/path, " });
  const info = (await relay.call("GET", "/")).json;
  assert.deepEqual(info.alternates, ["https://door-two.example", "https://clairix-relay.example.workers.dev"]);
  assert.deepEqual((await makeRelay().call("GET", "/")).json.alternates, []);
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
