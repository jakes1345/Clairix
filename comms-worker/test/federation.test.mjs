// Federation: phones on different relays reaching each other through their
// home relays, which sign what they forward with their own keys.
// Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, network, resetClock } from "./harness/fake-env.mjs";
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
  assert.equal(relayOrigin("https://relay.example.org:8443"), "https://relay.example.org:8443");
  for (const bad of ["http://relay.example.org", "https://relay.example.org/v1", "https://relay.example.org?x=1", "https://u:p@relay.example.org",
    "https://10.0.0.1", "https://[::1]", "https://localhost", "https://box.local", "ftp://relay.example.org", "relay.example.org", "", 7, null]) {
    assert.equal(relayOrigin(bad), null, String(bad));
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
  const plain = makeRelay({}, C);
  const none = (await plain.call("GET", "/")).json;
  assert.equal(none.relayKey, null);
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
    assert.deepEqual(Object.keys(bundle).sort(), ["claim", "curve25519", "ed25519", "fallback", "number", "oneTimeKey", "sealing", "signature"]);
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

test("a relay without a key does not forward, and the phone keeps its token", async () => {
  const a = makeRelay({}, A);
  const b = makeRelay({ RELAY_KEY: seed() }, B);
  const net = network([a, b]);
  try {
    const alice = await makePhone(a);
    const bob = await makePhone(b);
    const local = await makePhone(a);
    const [token] = await alice.tokens(1);
    const res = await sendVia(alice, token, bob.number, B);
    assert.equal(res.status, 501);
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
