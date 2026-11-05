// Supporter codes and credentials (src/supporter.ts, docs/SUPPORTER.md). The
// phone's side is @noble/curves' RFC 9497 VOPRF, as in tokens.test.mjs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { ristretto255_oprf as S } from "@noble/curves/ed25519.js";
import { advance, makePhone, makeRelay, mockFetch, resetClock } from "./harness/fake-env.mjs";
import {
  CODE_ALPHABET,
  SUPPORTER_CREDENTIALS_PER_CODE,
  SUPPORTER_TOKENS_PER_CREDENTIAL,
  codeCheck,
  codeHash,
  normaliseCode,
  playPurchaseVerdict,
} from "../src/supporter.ts";

const b64url = (bytes) => Buffer.from(bytes).toString("base64url");
const fromB64url = (s) => new Uint8Array(Buffer.from(s, "base64url"));
/** A test value only: what the operator would set with `wrangler secret put CODES_ADMIN_SECRET`. */
const ADMIN = "test-codes-admin-secret-0123456789";
const ip = (n) => ({ "cf-connecting-ip": `198.51.100.${n}` });

async function mint(relay, count, secret = ADMIN, n = 1) {
  return relay.call("POST", "/v1/admin/supporter/codes", { body: { count }, headers: { "x-admin-secret": secret, ...ip(n) } });
}

function blinds(n) {
  const inputs = Array.from({ length: n }, () => crypto.getRandomValues(new Uint8Array(32)));
  return { inputs, blinds: inputs.map((i) => S.voprf.blind(i)) };
}

/** Finalizes an issue the way the app does, checking its proof against [key]. */
function finalize(res, b, key) {
  const n = res.json.evaluated.length;
  const outputs = S.voprf.finalizeBatch(
    b.inputs.slice(0, n).map((input, i) => ({ input, blind: b.blinds[i].blind, blinded: b.blinds[i].blinded, evaluated: fromB64url(res.json.evaluated[i]) })),
    fromB64url(key),
    fromB64url(res.json.proof),
  );
  return outputs.map((o, i) => `2.${res.json.epoch}.${b64url(b.inputs[i])}.${b64url(o)}`);
}

async function redeem(relay, code, n = SUPPORTER_CREDENTIALS_PER_CODE, headers = ip(50)) {
  const info = (await relay.call("GET", "/")).json.supporter;
  const b = blinds(n);
  const res = await relay.call("POST", "/v1/supporter/redeem", { body: { code, blinded: b.blinds.map((x) => b64url(x.blinded)) }, headers });
  if (res.status !== 200) return { res, credentials: [] };
  assert.equal(res.json.key, info.key, "issued under the key GET / publishes");
  return { res, credentials: finalize(res, b, info.key) };
}

async function topUp(relay, credential, n = SUPPORTER_TOKENS_PER_CREDENTIAL) {
  const keys = (await relay.call("GET", "/")).json.tokens;
  const b = blinds(n);
  const res = await relay.call("POST", "/v1/supporter/tokens", {
    body: { blinded: b.blinds.map((x) => b64url(x.blinded)) },
    headers: credential === null ? {} : { "x-clairix-token": credential },
  });
  if (res.status !== 200) return { res, tokens: [] };
  assert.equal(res.json.key, keys.keys.delivery, "ordinary delivery tokens, under the ordinary delivery key");
  return { res, tokens: finalize(res, b, keys.keys.delivery) };
}

test("the code format: 24 symbols in six groups, a check that catches typing mistakes, and forgiving reading", async () => {
  // The same vectors as the app's SupporterCodeTest.
  assert.equal(await codeCheck("0123456789ABCDEFGHJKMN"), "Y8");
  assert.equal(await codeCheck("7KQ2M9XD4TPWHC3R0VNB6Y"), "MY");
  assert.equal(await normaliseCode("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMY"), "7KQ2M9XD4TPWHC3R0VNB6YMY");
  assert.equal(await normaliseCode(" 7kq2 m9xd 4tpw hc3r ovnb 6ymy "), "7KQ2M9XD4TPWHC3R0VNB6YMY", "case, spaces and O for 0");
  assert.equal(await codeHash("7KQ2M9XD4TPWHC3R0VNB6YMY"), "d7e13ac7270abd7c2a536a1626618a13968388434ee0f2e03ddb59e2bead50bb");
  assert.equal(await normaliseCode("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMZ"), null, "wrong check");
  assert.equal(await normaliseCode("7KQ2-M9XD-4TPW-HC3R-0VNB-Y6MY"), null, "two symbols swapped");
  assert.equal(await normaliseCode("7KQ2-M9XD-4TPW-HC3R-0VNB"), null, "short");
  assert.equal(await normaliseCode("7KQ2-M9XD-4TPW-HC3R-0VNB-6YMU"), null, "U is not in the alphabet");
  let caught = 0;
  for (let i = 0; i < 22; i++) {
    const s = "7KQ2M9XD4TPWHC3R0VNB6YMY".split("");
    s[i] = CODE_ALPHABET[(CODE_ALPHABET.indexOf(s[i]) + 1) % 32];
    if ((await normaliseCode(s.join(""))) === null) caught++;
  }
  assert.ok(caught >= 20, `a single wrong symbol is caught (${caught} of 22)`);
});

test("minting needs CODES_ADMIN_SECRET, and the relay keeps only hashes and a used flag", async () => {
  resetClock();
  assert.equal((await mint(makeRelay(), 3)).status, 503, "off while the secret is unset");
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  assert.equal((await mint(relay, 3, "wrong-secret-0123456789", 2)).status, 403);
  assert.equal((await mint(relay, 0, ADMIN, 3)).status, 400);
  assert.equal((await mint(relay, 501, ADMIN, 3)).status, 400);
  const res = await mint(relay, 5, ADMIN, 4);
  assert.equal(res.status, 201);
  assert.equal(res.json.codes.length, 5);
  assert.equal(new Set(res.json.codes).size, 5);
  for (const c of res.json.codes) assert.match(c, /^([0-9A-HJKMNP-TV-Z]{4}-){5}[0-9A-HJKMNP-TV-Z]{4}$/);
  // Nothing anywhere holds a code, nor anything but `code:<sha256>` → false.
  const stored = [];
  for (const [name, obj] of relay.env.MAILBOX.objects) {
    for (const [k, v] of await obj.state.storage.list()) stored.push([name, k, v]);
  }
  const all = JSON.stringify(stored);
  for (const c of res.json.codes) {
    assert.ok(!all.includes(c) && !all.includes(c.replace(/-/g, "")), "the code itself is not stored");
  }
  const codeRows = stored.filter(([name]) => name.startsWith("scode:"));
  assert.equal(codeRows.length, 5);
  for (const [name, k, v] of codeRows) {
    assert.match(k, /^code:[0-9a-f]{64}$/);
    assert.equal(name, `scode:${k.slice(5, 7)}`);
    assert.equal(v, false);
  }
  // Brute force of the secret meets the per-address limit.
  let limited = false;
  for (let i = 0; i < 8 && !limited; i++) limited = (await mint(relay, 1, `guess-${i}-0123456789`, 9)).status === 429;
  assert.ok(limited);
});

test("a code buys Supporter credentials once, anonymously; each credential buys one batch of ordinary delivery tokens once", async () => {
  resetClock();
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const [code, other] = (await mint(relay, 2)).json.codes;
  const info = (await relay.call("GET", "/")).json.supporter;
  assert.equal(info.credentialsPerCode, SUPPORTER_CREDENTIALS_PER_CODE);
  assert.equal(info.tokensPerCredential, SUPPORTER_TOKENS_PER_CREDENTIAL);
  assert.equal(info.play, false);
  assert.equal(info.next.epoch, info.epoch + 1);

  const { res, credentials } = await redeem(relay, code.toLowerCase());
  assert.equal(res.status, 200);
  assert.equal(res.json.kind, "supporter");
  assert.equal(credentials.length, SUPPORTER_CREDENTIALS_PER_CODE);
  assert.ok(res.json.expiresAt - Date.now() >= info.epochMs, "good for at least a year");
  assert.equal((await redeem(relay, code)).res.status, 409, "used");

  // A credential is a "supporter" token: it buys no send, and a delivery token buys no top-up.
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  assert.equal((await alice.send(credentials[0], bob.number)).status, 403);

  const first = await topUp(relay, credentials[0]);
  assert.equal(first.res.status, 200);
  assert.equal(first.tokens.length, SUPPORTER_TOKENS_PER_CREDENTIAL);
  assert.equal((await topUp(relay, credentials[0])).res.status, 403, "spent");
  assert.equal((await topUp(relay, first.tokens[0])).res.status, 403, "a delivery token is not a credential");
  assert.equal((await topUp(relay, null)).res.status, 401);
  // The tokens are spent like any other.
  assert.equal((await alice.send(first.tokens[0], bob.number)).status, 202);
  assert.equal((await alice.send(first.tokens[0], bob.number)).status, 403);

  // Another relay's credential, or a made-up one, buys nothing.
  const elsewhere = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const [foreign] = (await mint(elsewhere, 1)).json.codes;
  const theirs = (await redeem(elsewhere, foreign, 1)).credentials[0];
  assert.equal((await topUp(relay, theirs)).res.status, 403);
  assert.equal((await redeem(relay, foreign)).res.status, 404, "a code another relay minted");
  assert.equal((await redeem(relay, other)).res.status, 200);
});

test("the relay can tell no redemption, and no top-up, from another: nothing it keeps names a code's holder", async () => {
  resetClock();
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const [code] = (await mint(relay, 1)).json.codes;
  await relay.call("GET", "/");
  const snapshot = async () => {
    const out = {};
    for (const [name, obj] of relay.env.MAILBOX.objects) {
      for (const [k, v] of await obj.state.storage.list()) out[`${name}/${k}`] = JSON.stringify(v);
    }
    return out;
  };
  const before = await snapshot();
  const { credentials } = await redeem(relay, code);
  const afterRedeem = await snapshot();
  const changed = Object.keys(afterRedeem).filter((k) => afterRedeem[k] !== before[k]);
  // The code's flag, and nothing else: no account, no address, no time, nothing per credential.
  assert.equal(changed.length, 1);
  assert.match(changed[0], /^scode:[0-9a-f]{2}\/code:[0-9a-f]{64}$/);
  assert.equal(afterRedeem[changed[0]], "true");

  await topUp(relay, credentials[3]);
  const afterTopUp = await snapshot();
  const spent = Object.keys(afterTopUp).filter((k) => afterTopUp[k] !== afterRedeem[k]);
  // One spent record, keyed by a hash of the credential's own input, which the relay never saw at redemption.
  assert.ok(spent.every((k) => k.startsWith("stok:")), spent.join(","));
  assert.ok(spent.some((k) => /\/s:[0-9a-f]{64}$/.test(k)));
});

test("redeeming and topping up name nobody: a request that carries an identity is refused before anything is used", async () => {
  resetClock();
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const [code] = (await mint(relay, 1)).json.codes;
  const alice = await makePhone(relay);
  const b = blinds(1);
  const signed = await alice.signed("POST", "/v1/supporter/redeem", { code, blinded: [b64url(b.blinds[0].blinded)] });
  assert.equal(signed.status, 400);
  assert.equal((await redeem(relay, code)).res.status, 200, "the code was not used by the refused request");
});

test("malformed redemptions are refused, and a bad batch gives the code back", async () => {
  resetClock();
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const [code] = (await mint(relay, 1)).json.codes;
  const call = (body, n) => relay.call("POST", "/v1/supporter/redeem", { body, headers: ip(n) });
  const one = b64url(blinds(1).blinds[0].blinded);
  assert.equal((await call({ code, blinded: [] }, 60)).status, 400);
  assert.equal((await call({ code, blinded: Array(SUPPORTER_CREDENTIALS_PER_CODE + 1).fill(one) }, 61)).status, 400);
  assert.equal((await call({ code: "not a code", blinded: [one] }, 62)).status, 400);
  assert.equal((await call({ code: code.slice(0, -1) + (code.endsWith("0") ? "1" : "0"), blinded: [one] }, 63)).status, 400, "a mistyped code is caught by its check");
  assert.equal((await call({ code, blinded: [b64url(new Uint8Array(32).fill(0xff))] }, 64)).status, 400, "not a point");
  assert.equal((await redeem(relay, code, 4, ip(65))).res.status, 200, "given back after the bad batch");
});

test("redemptions from one address are limited to a few a minute", async () => {
  resetClock();
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const one = b64url(blinds(1).blinds[0].blinded);
  const statuses = [];
  for (let i = 0; i < 8; i++) {
    statuses.push((await relay.call("POST", "/v1/supporter/redeem", { body: { code: "x", blinded: [one] }, headers: ip(70) })).status);
  }
  assert.ok(statuses.includes(429), String(statuses));
  advance(60_000);
  assert.equal((await relay.call("POST", "/v1/supporter/redeem", { body: { code: "x", blinded: [one] }, headers: ip(70) })).status, 400);
});

test("credentials last into the next year and not past it", async () => {
  resetClock();
  const relay = makeRelay({ CODES_ADMIN_SECRET: ADMIN });
  const [code] = (await mint(relay, 1)).json.codes;
  const { epochMs } = (await relay.call("GET", "/")).json.supporter;
  const { credentials } = await redeem(relay, code, 2);
  advance(epochMs);
  assert.equal((await topUp(relay, credentials[0], 1)).res.status, 200, "the year after");
  advance(epochMs);
  assert.equal((await topUp(relay, credentials[1], 1)).res.status, 403, "two years on");
  resetClock();
});

test("Google Play purchases are off by default, and nothing is asked of Google while they are", async () => {
  resetClock();
  const fetches = mockFetch(() => new Response("{}", { status: 500 }));
  try {
    const relay = makeRelay();
    const b = blinds(1);
    const body = { productId: "clairix_supporter", purchaseToken: "a".repeat(40), blinded: [b64url(b.blinds[0].blinded)] };
    assert.equal((await relay.call("POST", "/v1/supporter/play", { body })).status, 404);
    // PLAY_VERIFY alone is not enough: it needs the package and the service account.
    const half = makeRelay({ PLAY_VERIFY: "on", PLAY_PACKAGE_NAME: "app.clairix" });
    assert.equal((await half.call("POST", "/v1/supporter/play", { body })).status, 404);
    assert.equal((await half.call("GET", "/")).json.supporter.play, false);
    // Configured with something that is not a key file: refused, still without a request to Google.
    const broken = makeRelay({ PLAY_VERIFY: "on", PLAY_PACKAGE_NAME: "app.clairix", GOOGLE_PLAY_SERVICE_ACCOUNT: "not json" });
    assert.equal((await broken.call("GET", "/")).json.supporter.play, true);
    assert.equal((await broken.call("POST", "/v1/supporter/play", { body: { ...body, productId: "something_else" } })).status, 400);
    assert.equal((await broken.call("POST", "/v1/supporter/play", { body })).status, 503);
    assert.equal(fetches.calls.length, 0);
  } finally {
    fetches.restore();
  }
});

test("only a completed purchase is exchanged, and a test purchase only when allowed", () => {
  assert.equal(playPurchaseVerdict({ purchaseState: 0, consumptionState: 0 }, false), "ok");
  assert.equal(playPurchaseVerdict({ purchaseState: 1 }, false), "not-purchased", "cancelled");
  assert.equal(playPurchaseVerdict({ purchaseState: 2 }, false), "not-purchased", "pending");
  assert.equal(playPurchaseVerdict({}, false), "not-purchased");
  assert.equal(playPurchaseVerdict(null, false), "not-purchased");
  assert.equal(playPurchaseVerdict({ purchaseState: 0, purchaseType: 0 }, false), "test");
  assert.equal(playPurchaseVerdict({ purchaseState: 0, purchaseType: 0 }, true), "ok");
  assert.equal(playPurchaseVerdict({ purchaseState: 0, purchaseType: 1 }, false), "ok", "a promo code redemption is a real purchase");
});
