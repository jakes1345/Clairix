// Run with `npm test` (node --test).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  checkPushEndpoint,
  DELIVERY_TOKEN_RE,
  expireClaims,
  groupByShard,
  OTK_CLAIM_WINDOW_MS,
  pairBucket,
  parseIceServers,
  randomToken,
  tokenHash,
  tokenShardName,
  trustedPushHosts,
} from "../src/comms-logic.ts";

test("delivery tokens are 256 random bits in base64url", () => {
  const seen = new Set();
  for (let i = 0; i < 200; i++) {
    const t = randomToken();
    assert.match(t, DELIVERY_TOKEN_RE);
    seen.add(t);
  }
  assert.equal(seen.size, 200);
});

test("token hashes pick one of 256 shards", async () => {
  const h = await tokenHash("abc");
  assert.equal(h, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  assert.equal(tokenShardName(h), "dtok:ba");
  const groups = groupByShard(["aa01", "aa02", "bb03"]);
  assert.deepEqual([...groups], [["dtok:aa", ["aa01", "aa02"]], ["dtok:bb", ["bb03"]]]);
});

test("claim-rate buckets name the target only by a hash", async () => {
  const b = await pairBucket("123456789");
  assert.match(b, /^claim:[0-9a-f]{32}$/);
  assert.ok(!b.includes("123456789"));
  assert.notEqual(b, await pairBucket("123456780"));
});

test("claims past their window are split off", () => {
  const key = { id: "k", key: "K", signature: "S" };
  const now = 1_000_000_000;
  const claims = [
    { ticket: "a", key, at: now - OTK_CLAIM_WINDOW_MS - 1 },
    { ticket: "b", key, at: now - OTK_CLAIM_WINDOW_MS },
    { ticket: "c", key, at: now - 1 },
  ];
  const { kept, expired } = expireClaims(claims, now);
  assert.deepEqual(kept.map((c) => c.ticket), ["c"]);
  assert.deepEqual(expired.map((c) => c.ticket), ["a", "b"]);
});

test("push endpoints: trusted hosts, checked hosts and refused ones", () => {
  assert.deepEqual(checkPushEndpoint("https://ntfy.sh/upX?up=1", undefined), { ok: true, url: "https://ntfy.sh/upX?up=1", trusted: true });
  assert.equal(checkPushEndpoint("https://up.conversations.im/push/abc", undefined).trusted, true);
  // A trusted name on an unusual port, or a look-alike, needs the check.
  assert.equal(checkPushEndpoint("https://ntfy.sh:8443/up", undefined).trusted, false);
  assert.equal(checkPushEndpoint("https://ntfy.sh.evil.example/up", undefined).trusted, false);
  assert.equal(checkPushEndpoint("https://evilntfy.sh/up", undefined).trusted, false);
  assert.equal(checkPushEndpoint("https://push.example.org/up", undefined).trusted, false);
  assert.equal(checkPushEndpoint("https://push.example.org/up", "push.example.org").trusted, true);
  for (const bad of [
    "http://ntfy.sh/up",
    "https://10.0.0.1/up",
    "https://0x7f000001/up",
    "https://2130706433/up",
    "https://[fe80::1]/up",
    "https://localhost/up",
    "https://foo.localhost/up",
    "https://printer.local/up",
    "https://router.lan/up",
    "https://1.0.0.127.in-addr.arpa/up",
    "https://single/up",
    "https://a:b@ntfy.sh/up",
    "not a url",
    "",
    "https://ntfy.sh/" + "x".repeat(2100),
    null,
  ]) {
    assert.equal(checkPushEndpoint(bad, undefined).ok, false, `accepted ${bad}`);
  }
});

test("PUSH_HOSTS is read leniently and only as host names", () => {
  const hosts = trustedPushHosts(" Push.Example.org., ,not a host, https://x.example/, ntfy.example.net");
  assert.ok(hosts.has("ntfy.sh"));
  assert.ok(hosts.has("push.example.org"));
  assert.ok(hosts.has("ntfy.example.net"));
  assert.ok(![...hosts].some((h) => h.includes("/") || h.includes(" ")));
});

test("ICE servers are read from either shape of the provider's answer", () => {
  assert.deepEqual(parseIceServers({ iceServers: { urls: "turn:a", username: "u", credential: "c" } }), [{ urls: ["turn:a"], username: "u", credential: "c" }]);
  assert.deepEqual(parseIceServers({ iceServers: [{ urls: ["stun:b", 7] }, { urls: [] }, null, "x"] }), [{ urls: ["stun:b"] }]);
  assert.deepEqual(parseIceServers(null), []);
  assert.deepEqual(parseIceServers({}), []);
});
