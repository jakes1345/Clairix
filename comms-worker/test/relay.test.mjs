// The relay's comms routes end to end: the Worker's own fetch handler and
// Mailbox objects over in-memory storage (see harness/fake-env.mjs).
// Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, mockFetch, resetClock } from "./harness/fake-env.mjs";
import { tokenHash, tokenShardName, OTK_CLAIM_WINDOW_MS, PUSH_CHALLENGE_PREFIX } from "../src/comms-logic.ts";

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

test("token issuance is rate limited per account", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  let got = 0;
  for (let i = 0; i < 10; i++) got += (await alice.tokens(100)).length;
  assert.equal(got, 1000);
  const more = await alice.signed("POST", "/v1/tokens", { count: 100 });
  assert.equal(more.status, 429);
  assert.ok(Number(more.headers.get("retry-after")) >= 1);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 0 })).status, 400);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 101 })).status, 400);
  // Refilled with time.
  advance(60_000);
  assert.equal((await alice.tokens(100)).length, 40);
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
  for (let i = 0; i < 1300; i++) {
    last = await box.enqueue("AAAA");
    if (last.ok) accepted++;
    else break;
  }
  assert.equal(accepted, 1200);
  assert.equal(last.status, 429);
  // Through the Worker the same limit is a 429 with Retry-After.
  const alice = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const res = await alice.send(token, bob.number);
  assert.equal(res.status, 429);
  assert.ok(res.headers.get("retry-after"));
});

test("a signed request no longer records when its owner was last seen", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const before = relay.env.MAILBOX.storageOf(alice.number).get("profile");
  assert.equal(before.lastSeen, undefined);
  advance(60_000);
  await alice.signed("GET", "/v1/me");
  assert.deepEqual(relay.env.MAILBOX.storageOf(alice.number).get("profile"), before);
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

test("a claimed key that no send follows up goes back into the pool", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay, { oneTimeKeys: 5 });
  assert.equal(await bob.oneTimeKeys(), 5);
  const b = (await alice.bundle(bob.number)).json.bundle;
  assert.equal(await bob.oneTimeKeys(), 4);
  advance(OTK_CLAIM_WINDOW_MS + 1);
  assert.equal(await bob.oneTimeKeys(), 5);
  // And it is the next one offered.
  const carol = await makePhone(relay);
  assert.equal((await carol.bundle(bob.number)).json.bundle.oneTimeKey.id, b.oneTimeKey.id);
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
