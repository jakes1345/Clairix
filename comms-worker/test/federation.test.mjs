// Federation: phones on different relays reaching each other through their
// home relays, which sign what they forward with their own keys.
// Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, mockFetch, network, resetClock } from "./harness/fake-env.mjs";
import { federationPayload, federationPolicy, relayOrigin } from "../src/federation.ts";

afterEach(() => resetClock());

const A = "https://a.relay.test";
const B = "https://b.relay.test";
const C = "https://c.relay.test";

function seed() {
  return Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString("base64");
}

function relays(varsA = {}, varsB = {}) {
  const a = makeRelay({ RELAY_KEY: seed(), ...varsA }, A);
  const b = makeRelay({ RELAY_KEY: seed(), ...varsB }, B);
  return { a, b };
}

/** Every value stored anywhere on [relay], as text. */
function everythingStored(relay) {
  const out = [];
  for (const [name, entry] of relay.env.MAILBOX.objects) {
    for (const [k, v] of entry.state.storage.data) out.push(`${name} ${k} ${JSON.stringify(v)}`);
  }
  return out.join("\n");
}

/** The app's fingerprint(): base64url of the first 12 bytes of SHA-256 over the key text. */
async function fingerprint(ed25519) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ed25519)));
  return Buffer.from(d.subarray(0, 12)).toString("base64url");
}

function sendVia(phone, token, to, relay, extra = {}) {
  return phone.send(token, to, undefined, undefined, { relay, ...extra });
}

test("relay addresses: https origins with a host name only", () => {
  assert.equal(relayOrigin("https://relay.example.org"), "https://relay.example.org");
  assert.equal(relayOrigin("https://Relay.Example.org/"), "https://relay.example.org");
  assert.equal(relayOrigin("https://relay.example.org:443"), "https://relay.example.org", "the default port is the same address");
  for (const bad of ["https://relay.example.org:8443", "https://relay.example.org.", "https://localhost.", "https://metadata.internal.",
    "http://relay.example.org", "https://relay.example.org/v1", "https://relay.example.org?x=1", "https://u:p@relay.example.org",
    "https://10.0.0.1", "https://[::1]", "https://localhost", "https://box.local", "ftp://relay.example.org", "relay.example.org", "", 7, null]) {
    assert.equal(relayOrigin(bad), null, String(bad));
  }
});

test("loopback relay addresses exist only for relays running on a developer's own computer", async () => {
  assert.equal(relayOrigin("http://127.0.0.1:8788"), null, "never by default");
  assert.equal(relayOrigin("http://127.0.0.1:8788", true), "http://127.0.0.1:8788");
  assert.equal(relayOrigin("http://localhost:8788/", true), "http://localhost:8788");
  for (const bad of ["http://127.0.0.1", "http://10.0.2.2:8788", "http://relay.example.org:8788", "http://127.0.0.1:8788/v1", "http://u:p@127.0.0.1:8788"]) {
    assert.equal(relayOrigin(bad, true), null, bad);
  }
  // A deployed relay (https) refuses a loopback relay address outright.
  const deployed = makeRelay({ RELAY_KEY: seed() }, A);
  const alice = await makePhone(deployed);
  const [t] = await alice.tokens(1);
  const refused = await sendVia(alice, t, "123456789", "http://127.0.0.1:8788");
  assert.equal(refused.status, 400);
  // Two relays side by side on one computer reach each other.
  const a = makeRelay({ RELAY_KEY: seed() }, "http://127.0.0.1:8787");
  const b = makeRelay({ RELAY_KEY: seed() }, "http://127.0.0.1:8788");
  const net = network([a, b]);
  try {
    const ana = await makePhone(a);
    const ben = await makePhone(b);
    const [token] = await ana.tokens(1);
    const res = await sendVia(ana, token, ben.number, "http://127.0.0.1:8788");
    assert.equal(res.status, 202, JSON.stringify(res.json));
    assert.equal((await ben.inbox()).length, 1);
  } finally {
    net.restore();
  }
});

test("FEDERATION is open by default, off, or a list of relays", () => {
  assert.deepEqual(federationPolicy({}), { mode: "open" });
  assert.deepEqual(federationPolicy({ FEDERATION: "OFF" }), { mode: "off" });
  const p = federationPolicy({ FEDERATION: "https://a.relay.test, nonsense, https://b.relay.test/" });
  assert.equal(p.mode, "allow");
  assert.deepEqual([...p.origins].sort(), [A, B]);
  assert.deepEqual(federationPolicy({ FEDERATION: "nonsense" }), { mode: "off" });
});

test("a relay publishes its key and its federation policy", async () => {
  const { a } = relays();
  const info = (await a.call("GET", "/")).json;
  assert.equal(Buffer.from(info.relayKey, "base64").length, 32);
  assert.equal(info.federation, "open");
  const off = makeRelay({ FEDERATION: "off" }, C);
  assert.equal((await off.call("GET", "/")).json.relayKey, null);
});

test("a send to a number on another relay arrives, and the other relay never learns the sender", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const [token] = await alice.tokens(1);
    const res = await sendVia(alice, token, bob.number, B);
    assert.equal(res.status, 202, JSON.stringify(res.json));
    const inbox = await bob.inbox();
    assert.equal(inbox.length, 1);
    assert.equal(inbox[0].id, res.json.id);
    // What reached B: A's address and signature, nothing of Alice.
    const forwarded = net.requests.find((r) => r.url === `${B}/v1/federate/send`);
    assert.ok(forwarded);
    assert.equal(forwarded.headers["x-clairix-relay"], A);
    for (const h of ["x-clairix-number", "x-clairix-sig", "x-clairix-token", "cf-connecting-ip"]) assert.equal(forwarded.headers[h], undefined, h);
    assert.ok(!forwarded.body.includes(alice.number));
    assert.ok(!everythingStored(b).includes(alice.number));
    assert.ok(!everythingStored(b).includes(alice.ed25519));
  } finally {
    net.restore();
  }
});

test("a send to the relay's own address is an ordinary local send", async () => {
  const { a } = relays();
  const net = network([a]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(a);
    const [token] = await alice.tokens(1);
    assert.equal((await sendVia(alice, token, bob.number, `${A}/`)).status, 202);
    assert.equal((await bob.inbox()).length, 1);
    assert.equal(net.requests.length, 0);
  } finally {
    net.restore();
  }
});

test("a lookup through the home relay claims a one-time key there, and the first send spends it", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b, { oneTimeKeys: 5 });
    const res = await alice.signed("POST", "/v1/lookup", { number: bob.number, claim: true, relay: B });
    assert.equal(res.status, 200, JSON.stringify(res.json));
    const bundle = res.json.bundle;
    assert.equal(bundle.number, bob.number);
    assert.equal(bundle.ed25519, bob.ed25519);
    assert.ok(bob.keyIds.includes(bundle.oneTimeKey.id));
    assert.equal(typeof bundle.claim, "string");
    assert.deepEqual(Object.keys(bundle).sort(), ["claim", "curve25519", "ed25519", "fallback", "number", "oneTimeKey", "sealing", "sealingPrekey", "signature"]);
    assert.deepEqual(bundle.sealingPrekey, bob.sealingPrekey, "the sealing prekey crosses relays");
    assert.equal(await bob.oneTimeKeys(), 4);
    const [token] = await alice.tokens(1);
    assert.equal((await alice.send(token, bob.number, undefined, bundle.claim, { relay: B })).status, 202);
    const box = b.env.MAILBOX.storageOf(bob.number);
    assert.deepEqual(box.get("claims"), []);
    assert.ok(box.get("claimedOtks").includes(bundle.oneTimeKey.id));
    // The identity alone claims nothing.
    const identity = await alice.signed("POST", "/v1/lookup", { number: bob.number, claim: false, relay: B });
    assert.equal(identity.status, 200);
    assert.equal(identity.json.identity.sealing, bob.sealing);
    assert.deepEqual(identity.json.identity.sealingPrekey, bob.sealingPrekey);
    assert.equal(await bob.oneTimeKeys(), 4);
  } finally {
    net.restore();
  }
});

test("an unlisted number on another relay is found only with its key's fingerprint", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b, { listed: false });
    const hidden = await alice.signed("POST", "/v1/lookup", { number: bob.number, claim: false, relay: B });
    assert.equal(hidden.status, 404);
    assert.match(hidden.json.error, /other relay refused/);
    const pinned = await alice.signed("POST", "/v1/lookup", { number: bob.number, claim: false, relay: B, pin: await fingerprint(bob.ed25519) });
    assert.equal(pinned.status, 200);
    assert.equal(pinned.json.identity.ed25519, bob.ed25519);
  } finally {
    net.restore();
  }
});

test("a forwarded request cannot be forged, replayed, or reused at another relay", async () => {
  const { a, b } = relays();
  const c = makeRelay({ RELAY_KEY: seed() }, C);
  let captured = null;
  const net = network([a, b, c], (r) => {
    if (r.url === `${B}/v1/federate/send` && captured === null) captured = r;
    return undefined;
  });
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const carol = await makePhone(c);
    const [token] = await alice.tokens(1);
    assert.equal((await sendVia(alice, token, bob.number, B)).status, 202);
    const headers = { ...captured.headers };
    delete headers["content-length"];
    // Replayed as it was.
    const replay = await b.call("POST", "/v1/federate/send", { body: captured.body, headers });
    assert.equal(replay.status, 401);
    assert.match(replay.json.error, /replayed/);
    // Altered: another recipient.
    const altered = JSON.parse(captured.body);
    altered.to = carol.number;
    const forged = await b.call("POST", "/v1/federate/send", { body: JSON.stringify(altered), headers: { ...headers, "x-clairix-relay-nonce": "N".repeat(24) } });
    assert.equal(forged.status, 401);
    assert.match(forged.json.error, /signature rejected/);
    // The same signed request at a third relay: the target origin is signed.
    const elsewhere = await c.call("POST", "/v1/federate/send", { body: captured.body, headers });
    assert.equal(elsewhere.status, 401);
    // A request naming A but signed by someone else.
    const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
    const ts = Date.now();
    const nonce = "M".repeat(24);
    const text = JSON.stringify({ to: bob.number, envelope: "Zm9yZ2Vk" });
    const sha = Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))).toString("hex");
    const payload = federationPayload(A, B, ts, nonce, "POST", "/v1/federate/send", sha);
    const sig = Buffer.from(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, new TextEncoder().encode(payload))).toString("base64");
    const impostor = await b.call("POST", "/v1/federate/send", {
      body: text,
      headers: { "x-clairix-relay": A, "x-clairix-relay-ts": String(ts), "x-clairix-relay-nonce": nonce, "x-clairix-relay-sig": sig },
    });
    assert.equal(impostor.status, 401);
    assert.equal((await bob.inbox()).length, 1);
    assert.equal((await carol.inbox()).length, 0);
  } finally {
    net.restore();
  }
});

test("a stale timestamp is refused", async () => {
  const { a, b } = relays();
  let captured = null;
  const net = network([a, b], (r) => {
    if (r.url === `${B}/v1/federate/send`) captured = r;
    return undefined;
  });
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const [token] = await alice.tokens(1);
    await sendVia(alice, token, bob.number, B);
    advance(6 * 60_000);
    const headers = { ...captured.headers, "x-clairix-relay-nonce": "Z".repeat(24) };
    const res = await b.call("POST", "/v1/federate/send", { body: captured.body, headers });
    assert.equal(res.status, 401);
    assert.match(res.json.error, /timestamp/);
  } finally {
    net.restore();
  }
});

test("a relay with federation off, or not on the list, refuses; the phone is told why", async () => {
  for (const varsB of [{ FEDERATION: "off" }, { FEDERATION: C }]) {
    const { a, b } = relays({}, varsB);
    const net = network([a, b]);
    try {
      const alice = await makePhone(a);
      const bob = await makePhone(b);
      const [token] = await alice.tokens(1);
      const res = await sendVia(alice, token, bob.number, B);
      assert.equal(res.status, 403);
      assert.match(res.json.error, /other relay refused/);
      assert.equal((await bob.inbox()).length, 0);
    } finally {
      net.restore();
    }
  }
  // On the list: accepted.
  const { a, b } = relays({}, { FEDERATION: `${C},${A}` });
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const [token] = await alice.tokens(1);
    assert.equal((await sendVia(alice, token, bob.number, B)).status, 202);
  } finally {
    net.restore();
  }
});

test("a relay with no RELAY_KEY makes its own, keeps it, and publishes the same public key every time", async () => {
  const a = makeRelay({}, A);
  const first = (await a.call("GET", "/")).json.relayKey;
  assert.equal(Buffer.from(first, "base64").length, 32);
  assert.equal((await a.call("GET", "/")).json.relayKey, first);
  const kept = a.env.MAILBOX.storageOf("relay-identity").get("relaySeed");
  assert.equal(Buffer.from(kept, "base64").length, 32);
  // The seed itself is published nowhere.
  assert.ok(!JSON.stringify((await a.call("GET", "/")).json).includes(kept));
  // Another relay makes another key.
  const b = makeRelay({}, B);
  assert.notEqual((await b.call("GET", "/")).json.relayKey, first);
});

test("two relays with keys they made themselves reach each other", async () => {
  const a = makeRelay({}, A);
  const b = makeRelay({}, B);
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const [token] = await alice.tokens(1);
    assert.equal((await sendVia(alice, token, bob.number, B)).status, 202);
    assert.equal((await bob.inbox()).length, 1);
    const back = await bob.signed("POST", "/v1/lookup", { number: alice.number, claim: false, relay: A });
    assert.equal(back.status, 200);
    assert.equal(back.json.identity.ed25519, alice.ed25519);
  } finally {
    net.restore();
  }
});

test("a relay with federation off neither forwards nor publishes a key, and the phone keeps its token", async () => {
  const a = makeRelay({ FEDERATION: "off" }, A);
  const b = makeRelay({}, B);
  const net = network([a, b]);
  try {
    assert.equal((await a.call("GET", "/")).json.relayKey, null);
    assert.equal((await a.call("GET", "/")).json.federation, "off");
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const local = await makePhone(a);
    const [token] = await alice.tokens(1);
    const res = await sendVia(alice, token, bob.number, B);
    assert.equal(res.status, 501);
    assert.match(res.json.error, /federation off/);
    assert.equal(net.requests.length, 0);
    assert.equal((await alice.send(token, local.number)).status, 202, "the token was not spent");
  } finally {
    net.restore();
  }
});

test("a bad relay address is refused before anything is spent", async () => {
  const { a } = relays();
  const alice = await makePhone(a);
  const bob = await makePhone(a);
  const [token] = await alice.tokens(1);
  for (const bad of ["http://b.relay.test", "https://10.1.2.3", "https://b.relay.test/v1", 5]) {
    const res = await sendVia(alice, token, bob.number, bad);
    assert.equal(res.status, 400, String(bad));
  }
  assert.equal((await alice.send(token, bob.number)).status, 202);
  const lookup = await alice.signed("POST", "/v1/lookup", { number: bob.number, claim: false, relay: "https://localhost" });
  assert.equal(lookup.status, 400);
});

test("an unreachable relay is a 502 the phone can retry", async () => {
  const { a } = relays();
  const net = network([a]);
  try {
    const alice = await makePhone(a);
    const [token] = await alice.tokens(1);
    const res = await sendVia(alice, token, "123456789", "https://gone.relay.test");
    assert.equal(res.status, 502);
  } finally {
    net.restore();
  }
});

test("a rotated relay key is picked up once the old one has been held ten minutes", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const tokens = await alice.tokens(3);
    assert.equal((await sendVia(alice, tokens[0], bob.number, B)).status, 202);
    a.env.RELAY_KEY = seed();
    // Within ten minutes of fetching the old key, B does not fetch again.
    const early = await sendVia(alice, tokens[1], bob.number, B);
    assert.equal(early.status, 502);
    advance(11 * 60_000);
    assert.equal((await sendVia(alice, tokens[2], bob.number, B)).status, 202);
    assert.equal((await bob.inbox()).length, 2);
  } finally {
    net.restore();
  }
});

test("another relay answering a lookup with something else is not passed on", async () => {
  const { a, b } = relays();
  const net = network([a, b], (r) => {
    if (r.url !== `${B}/v1/federate/lookup`) return undefined;
    const body = JSON.parse(r.body);
    return new Response(JSON.stringify({
      bundle: { number: body.number === "111111111" ? "222222222" : body.number, ed25519: "e", curve25519: "c", sealing: "s", signature: "g", oneTimeKey: { id: "k", key: "x", signature: "y" }, claim: "not-a-ticket", extra: "tracking" },
    }), { status: 200, headers: { "content-type": "application/json" } });
  });
  try {
    const alice = await makePhone(a);
    const wrongNumber = await alice.signed("POST", "/v1/lookup", { number: "111111111", claim: true, relay: B });
    assert.equal(wrongNumber.status, 502);
    const badClaim = await alice.signed("POST", "/v1/lookup", { number: "333333333", claim: true, relay: B });
    assert.equal(badClaim.status, 502);
  } finally {
    net.restore();
  }
});

test("each other relay is held to its own send limit", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const fed = [...b.env.MAILBOX.objects.keys()];
    // Exhaust A's allowance at B directly through the object that keeps it.
    const [token] = await alice.tokens(1);
    assert.equal((await sendVia(alice, token, bob.number, B)).status, 202);
    const name = [...b.env.MAILBOX.objects.keys()].find((n) => n.startsWith("fed:") && !fed.includes(n));
    assert.ok(name, "B keeps an object for A");
    const box = b.env.MAILBOX.get(b.env.MAILBOX.idFromName(name));
    for (let i = 0; i < 1300; i++) if (!(await box.rateOk("fed-send", 600, 1200)).ok) break;
    const [t2] = await alice.tokens(1);
    const res = await sendVia(alice, t2, bob.number, B);
    assert.equal(res.status, 429);
    assert.match(res.json.error, /other relay refused/);
  } finally {
    net.restore();
  }
});

test("an oversized envelope is refused alike for a number that exists and one that does not, before a token is spent", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b, { listed: false });
    const local = await makePhone(a, { listed: false });
    const huge = "A".repeat(100_000);
    const [token] = await alice.tokens(1);
    const answers = [];
    for (const [to, relay] of [[local.number, undefined], ["123456789", undefined], [bob.number, B], ["123456789", B]]) {
      const res = await alice.send(token, to, huge, undefined, relay ? { relay } : {});
      answers.push(`${res.status} ${res.json.error}`);
    }
    assert.deepEqual(new Set(answers).size, 1, answers.join(" | "));
    assert.match(answers[0], /^413 envelope too large/);
    assert.equal((await alice.send(token, local.number)).status, 202, "the token was not spent");
  } finally {
    net.restore();
  }
});

test("another relay is held per recipient, alike for numbers that exist and ones that do not", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const statuses = async (to) => {
      const out = [];
      for (let i = 0; i < 64; i++) {
        const [t] = await alice.tokens(1);
        out.push((await sendVia(alice, t, to, B)).status);
      }
      return out;
    };
    const real = await statuses(bob.number);
    const made = await statuses("123456789");
    assert.deepEqual(real, made);
    assert.equal(real.filter((s) => s === 202).length, 60);
    assert.equal(real.filter((s) => s === 429).length, 4);
  } finally {
    net.restore();
  }
});

test("other relays together cannot fill a mailbox or use up what this relay's own senders draw on", async () => {
  const { b } = relays();
  const bob = await makePhone(b);
  const box = b.env.MAILBOX.get(b.env.MAILBOX.idFromName(bob.number));
  let federated = 0;
  for (let i = 0; i < 400; i++) if ((await box.enqueue("c2VhbGVk", null, true, "known")).ok) federated++;
  assert.equal(federated, 300, "other relays get their own allowance");
  // This relay's own senders are not affected.
  const carol = await makePhone(b);
  const [t] = await carol.tokens(1);
  assert.equal((await carol.send(t, bob.number)).status, 202);
  // And the queue keeps room for them: other relays stop 500 short of full.
  await b.env.MAILBOX.objects.get(bob.number).state.storage.put("queueCount", 1500);
  advance(3_600_000);
  assert.equal((await box.enqueue("c2VhbGVk", null, true, "known")).status, 404, "dropped, answered as no such number");
  const [t2] = await carol.tokens(1);
  assert.equal((await carol.send(t2, bob.number)).status, 202);
});

test("a mailbox at its limits for other relays answers them as a number that does not exist", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const real = b.env.MAILBOX.objects;
    const box = b.env.MAILBOX.get(b.env.MAILBOX.idFromName(bob.number));
    const answer = async (to) => {
      const [t] = await alice.tokens(1);
      const res = await sendVia(alice, t, to, B);
      return [res.status, Object.keys(res.json ?? {}).sort().join(",")];
    };
    const queued = async () => (await real.get(bob.number).state.storage.get("queueCount")) ?? 0;

    // Allowance for other relays used up (several relays together could). No
    // one on B has written to A, so A's mail draws on the strangers' share.
    for (let i = 0; i < 400; i++) await box.enqueue("c2VhbGVk", null, true, "stranger");
    let before = await queued();
    assert.deepEqual(await answer(bob.number), await answer("123456789"));
    assert.deepEqual(await answer(bob.number), [202, "id"]);
    assert.equal(await queued(), before, "nothing was queued");

    // Queue at the share other relays may fill.
    advance(3_600_000);
    await real.get(bob.number).state.storage.put("queueCount", 1500);
    before = await queued();
    assert.deepEqual(await answer(bob.number), await answer("123456789"));
    assert.deepEqual(await answer(bob.number), [202, "id"]);
    assert.equal(await queued(), before, "nothing was queued");

    // The relay's own senders still get through, and are told when it is full.
    const carol = await makePhone(b);
    const [t] = await carol.tokens(1);
    assert.equal((await carol.send(t, bob.number)).status, 202);
    await real.get(bob.number).state.storage.put("queueCount", 2000);
    const [t2] = await carol.tokens(1);
    assert.equal((await carol.send(t2, bob.number)).status, 413);
  } finally {
    net.restore();
  }
});

test("relays made up to flood a mailbox cannot use up what relays people here write to rely on", async () => {
  const { a, b } = relays();
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const real = b.env.MAILBOX.objects;
    const box = b.env.MAILBOX.get(b.env.MAILBOX.idFromName(bob.number));
    const queued = async () => (await real.get(bob.number).state.storage.get("queueCount")) ?? 0;
    const fromAlice = async () => {
      const [t] = await alice.tokens(1);
      const before = await queued();
      assert.equal((await sendVia(alice, t, bob.number, B)).status, 202);
      return (await queued()) > before;
    };

    // A relay no one on B has written to gets a small share: ten at once.
    let through = 0;
    for (let i = 0; i < 15; i++) if (await fromAlice()) through++;
    assert.equal(through, 10, "strangers share a burst of ten");

    // Made-up relays flood the strangers' share for the hour.
    for (let i = 0; i < 100; i++) await box.enqueue("c2VhbGVk", null, true, "stranger");
    assert.equal(await fromAlice(), false, "A is still a stranger to B");

    // Once a phone on B writes to someone on A, A's mail is no longer held to
    // the strangers' share, however full it is.
    const [t] = await bob.tokens(1);
    assert.equal((await sendVia(bob, t, alice.number, A)).status, 202);
    for (let i = 0; i < 20; i++) assert.equal(await fromAlice(), true, `known relay envelope ${i}`);

    // What B keeps is the day, nothing about who wrote or to whom.
    const kept = [...real].filter(([name]) => name.startsWith("fed:")).flatMap(([, e]) => [...e.state.storage.data.keys()]);
    assert.ok(kept.includes("writtenToDay"));
    const fedLines = everythingStored(b).split("\n").filter((l) => l.startsWith("fed:"));
    assert.ok(!fedLines.some((l) => l.includes(alice.number) || l.includes(bob.number)), "no number is kept with the relay");

    // It lapses after WRITTEN_TO_DAYS without anyone writing there again.
    advance(91 * 24 * 3_600_000);
    for (let i = 0; i < 100; i++) await box.enqueue("c2VhbGVk", null, true, "stranger");
    assert.equal(await fromAlice(), false, "not written to for 91 days");
  } finally {
    net.restore();
  }
});

test("an address that served no key is not fetched again for a while, and other relays' answers are read only so far", async () => {
  resetClock();
  const relay = makeRelay();
  await relay.call("GET", "/");
  const m = mockFetch(() => new Response("nope", { status: 404 }));
  try {
    for (let i = 0; i < 5; i++) {
      const r = await relay.call("POST", "/v1/federate/send", {
        body: "{}",
        headers: { "x-clairix-relay": "https://nokey.example.org", "x-clairix-relay-ts": String(Date.now()),
          "x-clairix-relay-nonce": "aaaaaaaaaaaaaaaa" + i, "x-clairix-relay-sig": "x" },
      });
      assert.equal(r.status, 401);
    }
  } finally {
    m.restore();
  }
  assert.equal(m.calls.length, 1, "fetched once, then remembered as failed");
  // A key answer far larger than any key is not read to the end.
  const huge = mockFetch(() => new Response(JSON.stringify({ relayKey: "A".repeat(100_000) }), { status: 200 }));
  try {
    const r = await relay.call("POST", "/v1/federate/send", {
      body: "{}",
      headers: { "x-clairix-relay": "https://bigkey.example.org", "x-clairix-relay-ts": String(Date.now()),
        "x-clairix-relay-nonce": "bbbbbbbbbbbbbbbbbb", "x-clairix-relay-sig": "x" },
    });
    assert.equal(r.status, 401);
  } finally {
    huge.restore();
  }
});
