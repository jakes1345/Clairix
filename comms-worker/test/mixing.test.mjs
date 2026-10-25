// Relay-side mixing: envelopes the sender asks to have mixed are held a random
// few seconds and released in shuffled batches; the rest (call set-up, SOS,
// and everything from apps older than mixing) are not held.
// Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, mockFetch, resetClock } from "./harness/fake-env.mjs";
import { mixDelay, mixMaxMs } from "../src/mailbox.ts";

afterEach(() => resetClock());

/** What the app sends with everything but call set-up, SOS and group changes. */
const MIX = { mix: true };

/** A stand-in for the owner's live socket, recording what the relay hands it. */
function attachSocket(relay, number) {
  const sent = [];
  const ws = { send: (text) => sent.push(JSON.parse(text)), close() {} };
  relay.env.MAILBOX.objects.get(number).state.getWebSockets = () => [ws];
  return sent;
}

function stagedKeys(relay, number) {
  return [...relay.env.MAILBOX.storageOf(number).keys()].filter((k) => k.startsWith("m:"));
}

test("the hold is exponential with a fifth of the limit as its mean, and never past the limit", () => {
  assert.equal(mixDelay(20_000, 1), 0);
  assert.equal(mixDelay(20_000, 1e-300), 20_000);
  assert.equal(mixDelay(0, 0.5), 0);
  // Median of an exponential is mean·ln 2.
  assert.equal(mixDelay(20_000, 0.5), Math.round(4_000 * Math.LN2));
  let sum = 0;
  const n = 40_000;
  for (let i = 0; i < n; i++) {
    const d = mixDelay(20_000);
    assert.ok(d >= 0 && d <= 20_000);
    sum += d;
  }
  // The cut-off at five means takes off under 1%.
  const mean = sum / n;
  assert.ok(mean > 3_750 && mean < 4_150, `mean hold ${mean}`);
});

test("MIX_MAX_MS sets the limit; 0 turns mixing off, and nonsense keeps the default", () => {
  assert.equal(mixMaxMs({}), 20_000);
  assert.equal(mixMaxMs({ MIX_MAX_MS: "" }), 20_000);
  assert.equal(mixMaxMs({ MIX_MAX_MS: "0" }), 0);
  assert.equal(mixMaxMs({ MIX_MAX_MS: "5000" }), 5_000);
  assert.equal(mixMaxMs({ MIX_MAX_MS: "-1" }), 20_000);
  assert.equal(mixMaxMs({ MIX_MAX_MS: "soon" }), 20_000);
  assert.equal(mixMaxMs({ MIX_MAX_MS: "9999999" }), 120_000);
});

test("a send is held, counted against the queue, and released by the alarm with its arrival time", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [token] = await alice.tokens(1);
  const sentAt = Date.now();
  // Held envelopes are not visible until released; send until one is held
  // (a hold of 0 ms, drawn with probability ~1/4000 per send, goes straight out).
  const res = await alice.send(token, bob.number, "aGVsZA==", undefined, MIX);
  assert.equal(res.status, 202);
  const box = relay.env.MAILBOX.storageOf(bob.number);
  if (stagedKeys(relay, bob.number).length === 1) {
    assert.deepEqual(await bob.inbox(), []);
    assert.equal(box.get("queueCount"), 1);
    const alarm = relay.env.MAILBOX.objects.get(bob.number).state.storage.alarm;
    assert.ok(alarm !== null && alarm <= sentAt + 20_000 + 1_000, "the alarm is set for the release");
  }
  advance(20_001);
  await relay.env.MAILBOX.runDueAlarms();
  const inbox = await bob.inbox();
  assert.equal(inbox.length, 1);
  assert.equal(inbox[0].data, "aGVsZA==");
  assert.equal(inbox[0].id, res.json.id);
  assert.ok(Math.abs(inbox[0].ts - sentAt) < 1_000, "the envelope keeps the time it arrived");
  assert.deepEqual(stagedKeys(relay, bob.number), []);
  assert.equal(box.get("queueCount"), 1);
});

test("a send that does not ask for mixing is not held, as from every app before mixing", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const tokens = await alice.tokens(5);
  assert.equal((await alice.send(tokens[0], bob.number, undefined, undefined, { mix: false })).status, 202);
  for (const t of tokens.slice(1)) assert.equal((await alice.send(t, bob.number)).status, 202);
  assert.equal((await bob.inbox()).length, 5);
  assert.deepEqual(stagedKeys(relay, bob.number), []);
});

test("mix must be a boolean", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [t] = await alice.tokens(1);
  const res = await alice.send(t, bob.number, undefined, undefined, { mix: "yes" });
  assert.equal(res.status, 400);
});

test("a burst from one sender reaches the owner's socket in shuffled order, all at once when due", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const tokens = await alice.tokens(40);
  const ids = [];
  for (const t of tokens) ids.push((await alice.send(t, bob.number, undefined, undefined, MIX)).json.id);
  const sent = attachSocket(relay, bob.number);
  advance(20_001);
  await relay.env.MAILBOX.runDueAlarms();
  const handed = sent.filter((m) => m.type === "envelope").map((m) => m.envelope.id);
  // A hold of 0 ms skips the stage and goes out at once, before the socket was attached.
  const held = ids.filter((id) => handed.includes(id));
  assert.ok(held.length >= 38, `only ${held.length} of 40 were held`);
  assert.equal(new Set(handed).size, handed.length);
  assert.notDeepEqual(handed, held, "released in the order they arrived");
  assert.equal((await bob.inbox()).length, 40);
});

test("envelopes not yet due stay held while earlier ones go", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const tokens = await alice.tokens(60);
  for (const t of tokens) await alice.send(t, bob.number, undefined, undefined, MIX);
  const state = relay.env.MAILBOX.objects.get(bob.number).state;
  let seen = 0;
  // Step through the alarms as the runtime would fire them.
  for (let step = 0; step < 200 && stagedKeys(relay, bob.number).length > 0; step++) {
    const alarm = state.storage.alarm;
    assert.ok(alarm !== null, "held envelopes always have an alarm set");
    const due = stagedKeys(relay, bob.number).filter((k) => parseInt(k.slice(2, 12), 36) <= alarm).length;
    assert.ok(due >= 1, "the alarm is set for the earliest release");
    advance(Math.max(0, alarm - Date.now()));
    await relay.env.MAILBOX.runDueAlarms();
    const now = (await bob.inbox()).length;
    assert.ok(now > seen);
    seen = now;
  }
  assert.equal(seen, 60);
});

test("a release with no socket wakes the owner through push", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const fetches = mockFetch(() => new Response("ok"));
  try {
    await bob.signed("PUT", "/v1/push", { endpoint: "https://ntfy.sh/upBob" });
    const [t] = await alice.tokens(1);
    await alice.send(t, bob.number, undefined, undefined, MIX);
    await relay.env.MAILBOX.settle();
    advance(20_001);
    await relay.env.MAILBOX.runDueAlarms();
    await relay.env.MAILBOX.settle();
    assert.equal(fetches.calls.filter((c) => c.url === "https://ntfy.sh/upBob").length, 1);
  } finally {
    fetches.restore();
  }
});

test("a full mailbox counts held envelopes", async () => {
  const relay = makeRelay({ MIX_MAX_MS: "20000" });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  await relay.env.MAILBOX.objects.get(bob.number).state.storage.put("queueCount", 1999);
  const [a, b] = await alice.tokens(2);
  assert.equal((await alice.send(a, bob.number, undefined, undefined, MIX)).status, 202);
  const full = await alice.send(b, bob.number, undefined, undefined, MIX);
  assert.equal(full.status, 413);
});
