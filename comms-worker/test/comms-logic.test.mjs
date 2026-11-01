// Run with `npm test` (node --test).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  checkPushEndpoint,
  DELIVERY_TOKEN_RE,
  ENVELOPE_HASH_RE,
  expireClaims,
  groupByShard,
  isReportReason,
  MemoryBuckets,
  OPEN_REGISTRATION_DEFAULT_PER_DAY,
  openRegistrationCap,
  OTK_CLAIM_WINDOW_MS,
  DELIVERY_TOKEN_TTL_MS,
  tokenBatchExpiry,
  parseIceServers,
  randomToken,
  REPORT_REASONS,
  summariseReports,
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

test("in-memory buckets limit per key, refill, and forget the oldest keys", () => {
  const b = new MemoryBuckets(1, 2, 3);
  const t = 1_000_000;
  assert.equal(b.take("a", t), true);
  assert.equal(b.take("a", t), true);
  assert.equal(b.take("a", t), false);
  assert.equal(b.take("b", t), true);
  assert.equal(b.take("a", t + 60_000), true);
  // Only three keys are kept: "a" is forgotten once three others were used after it.
  for (const k of ["c", "d", "e"]) b.take(k, t + 60_000);
  assert.equal(b.take("a", t + 60_000), true);
  assert.equal(b.take("a", t + 60_000), true);
});

test("a token batch expires on the hour, jittered by up to twelve hours", () => {
  const hour = 60 * 60_000;
  const now = 1_760_000_123_456;
  for (const r of [0, 0.25, 0.5, 0.999999]) {
    const e = tokenBatchExpiry(now, () => r);
    assert.equal(e % hour, 0);
    assert.ok(e > now + DELIVERY_TOKEN_TTL_MS - hour && e <= now + DELIVERY_TOKEN_TTL_MS + 12 * hour);
  }
  assert.notEqual(tokenBatchExpiry(now, () => 0), tokenBatchExpiry(now, () => 0.5));
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

test("open registration is off unless the operator says on, and its daily cap is read strictly", () => {
  assert.equal(openRegistrationCap({}), null);
  for (const mode of ["", "off", "false", "1", "yes", "open"]) assert.equal(openRegistrationCap({ OPEN_REGISTRATION: mode }), null, mode);
  assert.equal(openRegistrationCap({ OPEN_REGISTRATION: "on" }), OPEN_REGISTRATION_DEFAULT_PER_DAY);
  assert.equal(openRegistrationCap({ OPEN_REGISTRATION: " TRUE " }), OPEN_REGISTRATION_DEFAULT_PER_DAY);
  assert.equal(openRegistrationCap({ OPEN_REGISTRATION: "on", OPEN_REGISTRATION_PER_DAY: "7" }), 7);
  assert.equal(openRegistrationCap({ OPEN_REGISTRATION: "on", OPEN_REGISTRATION_PER_DAY: "100000" }), 100_000);
  for (const bad of ["0", "100001", "-3", "2.5", "lots", "1e3"]) {
    assert.equal(openRegistrationCap({ OPEN_REGISTRATION: "on", OPEN_REGISTRATION_PER_DAY: bad }), OPEN_REGISTRATION_DEFAULT_PER_DAY, bad);
  }
  // A cap with registration off opens nothing.
  assert.equal(openRegistrationCap({ OPEN_REGISTRATION_PER_DAY: "7" }), null);
});

test("report reasons are the app's words, and reports are summed up by the number they name", () => {
  assert.deepEqual([...REPORT_REASONS], ["harassment", "threats", "spam", "impersonation", "other"]);
  assert.ok(isReportReason("threats"));
  assert.ok(!isReportReason("Threats"));
  assert.ok(!isReportReason(7));
  assert.match("a".repeat(64), ENVELOPE_HASH_RE);
  assert.doesNotMatch("A".repeat(64), ENVELOPE_HASH_RE);
  const r = (number, relay, envelope, reason, day) => ({ number, relay, envelope, reason, day });
  const summary = summariseReports([
    r("482913605", null, "e1", "threats", 10),
    r("111222333", null, null, "spam", 12),
    r("482913605", null, "e1", "threats", 11),
    r("482913605", null, "e2", "harassment", 14),
    r("482913605", "https://other.example", null, "spam", 13),
  ]);
  assert.deepEqual(summary, [
    { number: "482913605", relay: null, reports: 3, reasons: { threats: 2, harassment: 1 }, envelopes: ["e1", "e2"], firstDay: 10, lastDay: 14 },
    { number: "482913605", relay: "https://other.example", reports: 1, reasons: { spam: 1 }, envelopes: [], firstDay: 13, lastDay: 13 },
    { number: "111222333", relay: null, reports: 1, reasons: { spam: 1 }, envelopes: [], firstDay: 12, lastDay: 12 },
  ]);
  assert.deepEqual(summariseReports([]), []);
});
