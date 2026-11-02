// Blind send tokens (src/tokens.ts): the phone's side is @noble/curves' RFC 9497
// VOPRF, an implementation independent of the relay's Rust one, so these tests
// also check the two agree on the wire.
import { test } from "node:test";
import assert from "node:assert/strict";
import { ristretto255_oprf as S } from "@noble/curves/ed25519.js";
import { advance, makePhone, makeRelay, resetClock } from "./harness/fake-env.mjs";

const b64url = (bytes) => Buffer.from(bytes).toString("base64url");
const fromB64url = (s) => new Uint8Array(Buffer.from(s, "base64url"));

/** Asks for [n] blind tokens of [kind] and finalizes them the way the app does, checking the proof against GET /. */
async function blindTokens(relay, phone, n, kind = "delivery") {
  const keys = (await relay.call("GET", "/")).json.tokens;
  const inputs = Array.from({ length: n }, () => crypto.getRandomValues(new Uint8Array(32)));
  const blinds = inputs.map((i) => S.voprf.blind(i));
  const res = await phone.signed("POST", "/v1/tokens", { kind, blinded: blinds.map((b) => b64url(b.blinded)) });
  if (res.status !== 200) return { res, tokens: [] };
  assert.equal(res.json.key, keys.keys[kind], "issued under the key GET / publishes");
  assert.equal(res.json.epoch, keys.epoch);
  const granted = res.json.evaluated.length;
  const outputs = S.voprf.finalizeBatch(
    inputs.slice(0, granted).map((input, i) => ({ input, blind: blinds[i].blind, blinded: blinds[i].blinded, evaluated: fromB64url(res.json.evaluated[i]) })),
    fromB64url(keys.keys[kind]),
    fromB64url(res.json.proof),
  );
  const tokens = outputs.map((o, i) => `2.${res.json.epoch}.${b64url(inputs[i])}.${b64url(o)}`);
  return { res, tokens, inputs, outputs };
}

test("GET / publishes one key per token kind, the same for every caller", async () => {
  resetClock();
  const relay = makeRelay();
  const a = (await relay.call("GET", "/")).json.tokens;
  const b = (await relay.call("GET", "/", { headers: { "cf-connecting-ip": "203.0.113.9" } })).json.tokens;
  assert.deepEqual(a, b);
  assert.equal(a.version, 2);
  assert.deepEqual(Object.keys(a.keys).sort(), ["delivery", "report", "traffic"]);
  assert.equal(new Set(Object.values(a.keys)).size, 3, "each kind has its own key");
  // A configured secret gives other keys, and the same ones every time.
  const configured = makeRelay({ TOKEN_KEY: Buffer.alloc(32, 5).toString("base64") });
  const c1 = (await configured.call("GET", "/")).json.tokens;
  assert.notDeepEqual(c1.keys, a.keys);
  assert.deepEqual((await makeRelay({ TOKEN_KEY: Buffer.alloc(32, 5).toString("base64") }).call("GET", "/")).json.tokens, c1);
});

test("a blind token buys one send and only one", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const { tokens } = await blindTokens(relay, alice, 3);
  assert.equal(tokens.length, 3);
  assert.equal((await alice.send(tokens[0], bob.number)).status, 202);
  assert.equal((await alice.send(tokens[1], bob.number)).status, 202);
  assert.equal((await alice.send(tokens[0], bob.number)).status, 403, "spent");
  assert.equal((await bob.inbox()).length, 2);
});

test("the relay keeps nothing about the blind tokens it issues", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const snapshot = async () => {
    const out = {};
    for (const [name, obj] of relay.env.MAILBOX.objects) {
      for (const [k, v] of await obj.state.storage.list()) out[`${name}/${k}`] = JSON.stringify(v);
    }
    return out;
  };
  await relay.call("GET", "/"); // the relay makes its own secrets on first use
  const before = await snapshot();
  await blindTokens(relay, alice, 20);
  const after = await snapshot();
  const changed = Object.keys(after).filter((k) => after[k] !== before[k]);
  // What is written: the account's allowance (a count and the hour) and the
  // signed request's nonce. Nothing per token, and nothing anywhere else.
  assert.deepEqual(changed.sort(), [`${alice.number}/hourly:tokens-delivery`, `${alice.number}/nonces`]);
});

test("a token is good for its kind only, and only with the output its key made", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const { tokens: traffic } = await blindTokens(relay, alice, 1, "traffic");
  assert.equal((await alice.send(traffic[0], bob.number)).status, 403, "a traffic token buys no send");
  const { tokens, inputs, outputs } = await blindTokens(relay, alice, 2);
  const [, epoch] = tokens[0].split(".");
  const swapped = `2.${epoch}.${b64url(inputs[0])}.${b64url(outputs[1])}`;
  assert.equal((await alice.send(swapped, bob.number)).status, 403, "another token's output");
  const madeUp = `2.${epoch}.${b64url(crypto.getRandomValues(new Uint8Array(32)))}.${b64url(crypto.getRandomValues(new Uint8Array(64)))}`;
  assert.equal((await alice.send(madeUp, bob.number)).status, 403, "a made-up token");
  const otherRelay = makeRelay();
  const carol = await makePhone(otherRelay);
  const { tokens: elsewhere } = await blindTokens(otherRelay, carol, 1);
  assert.equal((await alice.send(elsewhere[0], bob.number)).status, 403, "another relay's token");
  assert.equal((await alice.send(tokens[0], bob.number)).status, 202);
});

test("a token lasts into the next epoch and not past it", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const epochMs = (await relay.call("GET", "/")).json.tokens.epochMs;
  const { tokens } = await blindTokens(relay, alice, 2);
  advance(epochMs);
  assert.equal((await alice.send(tokens[0], bob.number)).status, 202, "the epoch after");
  advance(epochMs);
  assert.equal((await alice.send(tokens[1], bob.number)).status, 403, "two epochs on");
});

test("blind tokens draw on the same hourly allowance, and a short grant is still proved", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  let got = 0;
  let last;
  for (let i = 0; i < 12; i++) {
    last = await blindTokens(relay, alice, 100);
    if (last.res.status !== 200) break;
    got += last.tokens.length;
  }
  assert.equal(got, 1000, "the delivery burst");
  assert.equal(last.res.status, 429);
  // Reports allow 30 at most: asked for 100, the phone gets 30, with a proof over those.
  const partial = await blindTokens(relay, alice, 100, "report");
  assert.equal(partial.res.status, 200);
  assert.equal(partial.tokens.length, 30);
});

test("malformed blind requests are refused before anything is evaluated", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bad = async (blinded) => (await alice.signed("POST", "/v1/tokens", { blinded })).status;
  assert.equal(await bad([]), 400);
  assert.equal(await bad("x"), 400);
  assert.equal(await bad(Array(101).fill(b64url(S.voprf.blind(new Uint8Array(32).fill(1)).blinded))), 400);
  assert.equal(await bad([b64url(new Uint8Array(32).fill(0xff))]), 400, "not a point");
  assert.equal(await bad([b64url(new Uint8Array(32))]), 400, "the identity");
  assert.equal(await bad(["short"]), 400);
});

test("tokens from before Beta 6 still work alongside, until the legacy cutoff", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const [old] = await alice.tokens(1);
  const { tokens } = await blindTokens(relay, alice, 1);
  assert.equal((await alice.send(old, bob.number)).status, 202);
  assert.equal((await alice.send(tokens[0], bob.number)).status, 202);
});

/** An envelope of [format] as its first byte says (comms-crypto: 2 sealed to the long-term key, 3 to a prekey), base64. */
const envelopeOf = (format) => Buffer.from([format, ...crypto.getRandomValues(new Uint8Array(120))]).toString("base64");

test("from 1 January 2027 the old kind of token and envelopes in format 2 are refused with 426 and a word to update; blind tokens and format 3 go on", async () => {
  const cutoff = Date.UTC(2027, 0, 1);
  const day = 24 * 60 * 60_000;
  resetClock();
  // The relay's own date (LEGACY_CUTOFF unset), seen from twenty past midnight the day before.
  advance(cutoff - day + 20 * 60_000 - Date.now());
  const relay = makeRelay({ LEGACY_CUTOFF: undefined });
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const old = await alice.tokens(3);
  assert.equal((await alice.send(old[0], bob.number, envelopeOf(2))).status, 202, "the day before, all of it still works");

  advance(day + 60_000);
  const issued = await alice.signed("POST", "/v1/tokens", { count: 5 });
  assert.equal(issued.status, 426);
  assert.match(issued.json.error, /Update Clairix from clairix\.app\/download/);
  const spent = await alice.send(old[1], bob.number, envelopeOf(3));
  assert.deepEqual([spent.status, spent.json.error], [426, issued.json.error]);
  // A format-2 envelope is refused on its first byte alone, before a token is
  // spent or a mailbox asked: the same answer for a number nobody holds.
  const { tokens } = await blindTokens(relay, alice, 2);
  assert.equal(tokens.length, 2, "blind tokens are still issued");
  assert.equal((await alice.send(tokens[0], bob.number, envelopeOf(2))).status, 426);
  assert.equal((await alice.send(tokens[0], "987654321", envelopeOf(2))).status, 426);
  assert.equal((await alice.send(tokens[0], bob.number, envelopeOf(3))).status, 202, "the refused sends spent no token");
  assert.equal((await alice.send(tokens[1], bob.number, "AAAA")).status, 202);
  assert.equal((await bob.inbox()).length, 3);
  // The traffic routes spend tokens the same way.
  const { tokens: traffic } = await blindTokens(relay, alice, 1, "traffic");
  assert.equal((await alice.traffic(traffic[0], "GET", "/v1/traffic/live?region=north-america-us-ohio")).status, 200);
  const [oldTraffic] = old.slice(2);
  assert.equal((await alice.traffic(oldTraffic, "GET", "/v1/traffic/live?region=north-america-us-ohio")).status, 426);
  resetClock();
});

test("LEGACY_CUTOFF moves the date; one that is not a real day keeps 1 January 2027", async () => {
  resetClock();
  advance(Date.UTC(2026, 11, 15, 0, 20) - Date.now());
  const early = makeRelay({ LEGACY_CUTOFF: "2026-12-01" });
  const alice = await makePhone(early);
  assert.equal((await alice.signed("POST", "/v1/tokens", { count: 1 })).status, 426);
  for (const LEGACY_CUTOFF of ["2027-02-30", "soon", "2026-12-01T00:00:00Z"]) {
    const relay = makeRelay({ LEGACY_CUTOFF });
    const bob = await makePhone(relay);
    assert.equal((await bob.signed("POST", "/v1/tokens", { count: 1 })).status, 200, LEGACY_CUTOFF);
  }
  resetClock();
});

test("a blind token has one spelling: the spare bits of its last character cannot make it four", async () => {
  resetClock();
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const { tokens } = await blindTokens(relay, alice, 1);
  const [, epoch, input, output] = tokens[0].split(".");
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  const last = alphabet.indexOf(input[42]);
  const statuses = [];
  for (let low = 0; low < 4; low++) {
    const spelling = input.slice(0, 42) + alphabet[(last & ~3) | low];
    statuses.push((await alice.send(`2.${epoch}.${spelling}.${output}`, bob.number)).status);
  }
  assert.equal(statuses.filter((s) => s === 202).length, 1, String(statuses));
  assert.equal((await bob.inbox()).length, 1);
});
