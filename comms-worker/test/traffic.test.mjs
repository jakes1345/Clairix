// The traffic routes end to end: anonymous token-spending requests into the
// Traffic and IncidentCache objects over SQLite (see harness/fake-env.mjs).
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, resetClock } from "./harness/fake-env.mjs";

afterEach(() => resetClock());

const REGION = "north-america-us-ohio";
const SEG = "123456:0";

function trip() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function samples(n, kph, lat = 39.96, lon = -83.0) {
  const now = Math.floor(Date.now() / 1000);
  return Array.from({ length: n }, (_, i) => ({ seg: SEG, kph, t: now - i * 30, lat, lon }));
}

/** Every value in every traffic and incident table, as text. */
function trafficStored(relay) {
  const out = [];
  for (const [name, entry] of relay.env.TRAFFIC.objects) {
    const db = entry.state.storage.sql.db;
    const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name);
    for (const t of tables) for (const row of db.prepare(`SELECT * FROM "${t}"`).all()) out.push(`${name} ${t} ${JSON.stringify(row)}`);
  }
  return out.join("\n");
}

test("a traffic request spends a traffic token and carries no identity", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [token] = await alice.tokens(1, "traffic");
  const [delivery] = await alice.tokens(1);
  const body = { region: REGION, trip: trip(), samples: samples(3, 50) };
  assert.equal((await alice.traffic(null, "POST", "/v1/traffic/probe", body)).status, 401);
  assert.equal((await alice.traffic(delivery, "POST", "/v1/traffic/probe", body)).status, 403, "a delivery token is not a traffic token");
  const withIdentity = await relay.call("POST", "/v1/traffic/probe", { body, headers: { "x-clairix-token": token, "x-clairix-number": alice.number } });
  assert.equal(withIdentity.status, 400);
  const signedOnly = await alice.signed("POST", "/v1/traffic/probe", body);
  assert.equal(signedOnly.status, 400);
  const ok = await alice.traffic(token, "POST", "/v1/traffic/probe", body);
  assert.equal(ok.status, 200);
  assert.equal(ok.json.accepted, 3);
  // Spent.
  assert.equal((await alice.traffic(token, "POST", "/v1/traffic/probe", body)).status, 403);
});

test("probes are kept under the trip pseudonym and nothing in a region names a phone", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [t1, t2] = await alice.tokens(2, "traffic");
  const mine = trip();
  await alice.traffic(t1, "POST", "/v1/traffic/probe", { region: REGION, trip: mine, samples: samples(4, 55) });
  const live = await alice.traffic(t2, "GET", `/v1/traffic/live?region=${REGION}`);
  assert.equal(live.status, 200);
  assert.ok(live.json.segments[SEG]);
  assert.equal(live.json.segments[SEG].n, 1);
  const stored = trafficStored(relay);
  assert.ok(stored.includes(mine), "the trip pseudonym is what the probes are filed under");
  assert.ok(!stored.includes(alice.number), "a Clairix number reached a traffic object");
  assert.ok(!stored.includes(alice.ed25519));
  assert.ok(!stored.includes("salt"), "a sender salt is still kept");
});

test("a trip pseudonym is one vehicle: two trips make a jam, one trip reporting twice does not", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const tokens = await alice.tokens(6, "traffic");
  const fast = samples(2, 70);
  // The segment is normally fast, then two vehicles stop on it.
  const a = trip();
  const b = trip();
  await alice.traffic(tokens[0], "POST", "/v1/traffic/probe", { region: REGION, trip: a, samples: fast });
  advance(3 * 60_000);
  await alice.traffic(tokens[1], "POST", "/v1/traffic/probe", { region: REGION, trip: a, samples: samples(2, 3) });
  await alice.traffic(tokens[2], "POST", "/v1/traffic/probe", { region: REGION, trip: a, samples: samples(2, 2) });
  let live = (await alice.traffic(tokens[3], "GET", `/v1/traffic/live?region=${REGION}`)).json;
  assert.equal(live.incidents.filter((i) => i.source === "auto").length, 0, "one vehicle stopping is not a jam");
  await alice.traffic(tokens[4], "POST", "/v1/traffic/probe", { region: REGION, trip: b, samples: samples(2, 4) });
  live = (await alice.traffic(tokens[5], "GET", `/v1/traffic/live?region=${REGION}`)).json;
  assert.equal(live.incidents.filter((i) => i.source === "auto").length, 1, "two vehicles stopped on a fast road is a jam");
});

test("a new report needs a report token; votes take one per trip", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [traffic1, traffic2, traffic3] = await alice.tokens(3, "traffic");
  const [report1, report2] = await alice.tokens(2, "report");
  const where = { region: REGION, type: "police", lat: 39.961, lon: -83.001, seg: null };
  const a = trip();
  assert.equal((await alice.traffic(traffic1, "POST", "/v1/traffic/report", { ...where, trip: a })).status, 403, "a traffic token does not buy a report");
  const first = await alice.traffic(report1, "POST", "/v1/traffic/report", { ...where, trip: a });
  assert.equal(first.status, 200);
  assert.equal(first.json.incident.confirmations, 1);
  // The same trip confirming its own report changes nothing; another trip does.
  const same = await alice.traffic(traffic2, "POST", "/v1/traffic/report/vote", { region: REGION, trip: a, id: first.json.incident.id, still: true });
  assert.equal(same.json.incident.confirmations, 1);
  const other = await alice.traffic(traffic3, "POST", "/v1/traffic/report/vote", { region: REGION, trip: trip(), id: first.json.incident.id, still: true });
  assert.equal(other.json.incident.confirmations, 2);
  // A second report of the same thing nearby merges rather than duplicating.
  const merged = await alice.traffic(report2, "POST", "/v1/traffic/report", { ...where, lat: 39.9612, trip: trip() });
  assert.equal(merged.json.incident.id, first.json.incident.id);
  assert.equal(merged.json.incident.confirmations, 3);
  assert.ok(!trafficStored(relay).includes(alice.number));
});

test("a trip pseudonym must be well formed", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [t1, t2] = await alice.tokens(2, "traffic");
  assert.equal((await alice.traffic(t1, "POST", "/v1/traffic/probe", { region: REGION, samples: samples(1, 50) })).status, 400);
  assert.equal((await alice.traffic(t2, "POST", "/v1/traffic/probe", { region: REGION, trip: alice.number, samples: samples(1, 50) })).status, 400);
});

test("provider incidents are served anonymously and count only the daily cap", async () => {
  const relay = makeRelay({ HERE_API_KEY: "here-key", HERE_DAILY_CAP: "2" });
  const alice = await makePhone(relay);
  const tokens = await alice.tokens(4, "traffic");
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({ results: [] });
  };
  try {
    const bbox = "39.9,-83.1,40.0,-83.0";
    const one = await alice.traffic(tokens[0], "GET", `/v1/traffic/incidents?bbox=${bbox}`);
    assert.equal(one.status, 200);
    assert.equal(one.json.providers.here, "ok");
    assert.equal(one.json.providers.tomtom, "off");
    assert.equal(calls, 1);
    // Cached for five minutes: a second poll makes no upstream call.
    await alice.traffic(tokens[1], "GET", `/v1/traffic/incidents?bbox=${bbox}`);
    assert.equal(calls, 1);
    advance(6 * 60_000);
    await alice.traffic(tokens[2], "GET", `/v1/traffic/incidents?bbox=${bbox}`);
    assert.equal(calls, 2);
    advance(6 * 60_000);
    const capped = await alice.traffic(tokens[3], "GET", `/v1/traffic/incidents?bbox=${bbox}`);
    assert.equal(calls, 2, "the daily cap holds");
    assert.equal(capped.status, 200, "the stale tile still serves");
    assert.equal(capped.json.providers.here, "quota");
    const db = relay.env.INCIDENTS.objects.get("incidents").state.storage.sql.db;
    const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name);
    assert.ok(!tables.includes("caller_quota"), "a per-caller table is still kept");
    assert.ok(!tables.includes("meta"));
  } finally {
    globalThis.fetch = original;
  }
});

test("only a region Clairix has a map for can be named, and the list is the graph builder's", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const [t1, t2] = await alice.tokens(2, "traffic");
  assert.equal((await alice.traffic(t1, "GET", "/v1/traffic/live?region=north-america-us-narnia")).status, 400);
  assert.equal((await alice.traffic(t2, "GET", `/v1/traffic/live?region=${REGION}`)).status, 200);
  assert.ok(!relay.env.TRAFFIC.objects.has("traffic:north-america-us-narnia"));
  const { KNOWN_REGIONS } = await import("../src/regions.ts");
  const { readFileSync } = await import("node:fs");
  const listed = readFileSync(new URL("../../tools/graph-builder/us-regions.txt", import.meta.url), "utf8")
    .split("\n").map((l) => l.trim()).filter(Boolean).map((l) => l.replaceAll("/", "-"));
  assert.deepEqual([...KNOWN_REGIONS].sort(), listed.sort());
});
