// The live socket's heartbeat: every two minutes for apps that ask for nothing
// else, and for an app that does, two minutes for ten minutes after an envelope
// or an ack and its own quiet pace otherwise. Pings are the app checking the
// link and leave the pace alone. Run with `npm test`.
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { advance, makePhone, makeRelay, resetClock } from "./harness/fake-env.mjs";
import {
  HEARTBEAT_ACTIVE_MS,
  HEARTBEAT_ACTIVE_WINDOW_MS,
  HEARTBEAT_HEADER,
  HEARTBEAT_IDLE_MAX_MS,
  heartbeatGap,
  heartbeatPreference,
} from "../src/mailbox.ts";

afterEach(() => resetClock());

const MIN = 60_000;
const enc = new TextEncoder();

async function sha256Hex(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(text)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** One end of a socket pair: what the relay sends on it, and what it keeps with it, as the runtime does. */
function fakeSocket() {
  let kept = null;
  const frames = [];
  return {
    frames,
    send(text) {
      frames.push(JSON.parse(text));
    },
    close() {},
    serializeAttachment(value) {
      kept = structuredClone(value);
    },
    deserializeAttachment() {
      return kept === null ? null : structuredClone(kept);
    },
  };
}

/**
 * Opens the phone's live socket through the Worker's own GET /v1/ws, signed as
 * RelayClient signs it, with [heartbeat] as X-Clairix-Heartbeat (none when
 * undefined). Node has no WebSocketPair and refuses a 101 response, so both are
 * stood in for while the upgrade runs. Returns the socket the relay accepted.
 */
async function connect(relay, phone, heartbeat) {
  const object = relay.env.MAILBOX.objects.get(phone.number);
  const accepted = [];
  object.state.acceptWebSocket = (ws) => accepted.push(ws);
  object.state.getWebSockets = () => accepted;
  const RealResponse = globalThis.Response;
  const hadPair = "WebSocketPair" in globalThis;
  const realPair = globalThis.WebSocketPair;
  globalThis.WebSocketPair = class {
    constructor() {
      this[0] = fakeSocket();
      this[1] = fakeSocket();
    }
  };
  globalThis.Response = class extends RealResponse {
    constructor(body, init) {
      super(body, init?.status === 101 ? { ...init, status: 200 } : init);
    }
  };
  try {
    const ts = Date.now();
    const nonce = crypto.randomUUID().replace(/-/g, "");
    const payload = `${phone.number}\n${ts}\n${nonce}\nGET\n/v1/ws\n${await sha256Hex("")}`;
    const headers = {
      upgrade: "websocket",
      "x-clairix-number": phone.number,
      "x-clairix-ts": String(ts),
      "x-clairix-nonce": nonce,
      "x-clairix-sig": await phone.sign(payload),
    };
    if (heartbeat !== undefined) headers[HEARTBEAT_HEADER] = heartbeat;
    const res = await relay.call("GET", "/v1/ws", { headers });
    assert.equal(res.status, 200, `upgrade refused: ${JSON.stringify(res.json)}`);
  } finally {
    globalThis.Response = RealResponse;
    if (hadPair) globalThis.WebSocketPair = realPair;
    else delete globalThis.WebSocketPair;
  }
  assert.equal(accepted.length, 1);
  return { ws: accepted[0], object };
}

/** When the mailbox's alarm is due, as an offset from now. */
function alarmIn(object) {
  return object.state.storage.alarm - Date.now();
}

/** Runs the alarm due [ms] from now and returns the heartbeat it sent. */
async function heartbeatAfter(relay, ws, ms) {
  advance(ms);
  const before = ws.frames.length;
  await relay.env.MAILBOX.runDueAlarms();
  const hb = ws.frames.slice(before).find((f) => f.type === "hb");
  assert.ok(hb, `no heartbeat ${ms} ms on`);
  return hb;
}

function near(actual, expected, what) {
  assert.ok(Math.abs(actual - expected) < 2_000, `${what}: ${actual}, expected about ${expected}`);
}

test("the pace an app asks for is read leniently and kept between two and ten minutes", () => {
  assert.equal(heartbeatPreference(undefined), null);
  assert.equal(heartbeatPreference(null), null);
  assert.equal(heartbeatPreference(""), null);
  assert.equal(heartbeatPreference("soon"), null);
  assert.equal(heartbeatPreference("-5"), null);
  assert.equal(heartbeatPreference("0"), null);
  assert.equal(heartbeatPreference("1e6"), null);
  assert.equal(heartbeatPreference(" 300000 "), 300_000);
  assert.equal(heartbeatPreference("1000"), HEARTBEAT_ACTIVE_MS);
  assert.equal(heartbeatPreference("999999999"), HEARTBEAT_IDLE_MAX_MS);
  assert.equal(HEARTBEAT_ACTIVE_MS, 2 * MIN);
  assert.equal(HEARTBEAT_ACTIVE_WINDOW_MS, 10 * MIN);
  assert.equal(HEARTBEAT_IDLE_MAX_MS, 10 * MIN);
});

test("two minutes for ten minutes after traffic, then the app's pace; always two minutes for an app that asked for none", () => {
  const t = 1_700_000_000_000;
  assert.equal(heartbeatGap({ idle: 300_000, trafficAt: t }, t), 2 * MIN);
  assert.equal(heartbeatGap({ idle: 300_000, trafficAt: t }, t + 10 * MIN - 1), 2 * MIN);
  assert.equal(heartbeatGap({ idle: 300_000, trafficAt: t }, t + 10 * MIN), 5 * MIN);
  assert.equal(heartbeatGap({ idle: 600_000, trafficAt: t }, t + 3 * 60 * MIN), 10 * MIN);
  assert.equal(heartbeatGap({ idle: null, trafficAt: t }, t + 3 * 60 * MIN), 2 * MIN);
  assert.equal(heartbeatGap({ idle: null, trafficAt: 0 }, t), 2 * MIN);
});

test("an app that asks for a quiet pace gets two-minute heartbeats while envelopes move and its own pace once quiet", async () => {
  const relay = makeRelay();
  const alice = await makePhone(relay);
  const bob = await makePhone(relay);
  const { ws, object } = await connect(relay, bob, "300000");
  assert.equal(ws.frames.at(-1).type, "ready");
  // A new connection starts at the two-minute pace.
  near(alarmIn(object), 2 * MIN, "first heartbeat after connecting");
  for (let elapsed = 2; elapsed <= 10; elapsed += 2) {
    const hb = await heartbeatAfter(relay, ws, 2 * MIN + 1);
    assert.equal(hb.next, elapsed < 10 ? 2 * MIN : 5 * MIN, `heartbeat at ${elapsed} min`);
  }
  // Quiet: one heartbeat every five minutes, announced as such.
  near(alarmIn(object), 5 * MIN, "quiet pace");
  assert.equal((await heartbeatAfter(relay, ws, 5 * MIN + 1)).next, 5 * MIN);
  near(alarmIn(object), 5 * MIN, "quiet pace again");

  // An envelope arrives: delivered at once, and the next heartbeat comes within two minutes.
  const [token] = await alice.tokens(1);
  assert.equal((await alice.send(token, bob.number, "aGk=")).status, 202);
  const envelope = ws.frames.findLast((f) => f.type === "envelope");
  assert.equal(envelope?.envelope.data, "aGk=");
  near(alarmIn(object), 2 * MIN, "heartbeat after an envelope");
  assert.equal((await heartbeatAfter(relay, ws, 2 * MIN + 1)).next, 2 * MIN);

  // The phone acks it ten minutes on: traffic too, so the two-minute pace holds.
  advance(8 * MIN);
  await relay.env.MAILBOX.runDueAlarms();
  await object.instance.webSocketMessage(ws, JSON.stringify({ type: "ack", ids: [envelope.envelope.id] }));
  assert.equal(ws.frames.at(-1).type, "acked");
  assert.equal((await heartbeatAfter(relay, ws, 2 * MIN + 1)).next, 2 * MIN);
});

test("a ping is answered and is not traffic: it does not bring the two-minute pace back", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const { ws, object } = await connect(relay, bob, "600000");
  advance(11 * MIN);
  await relay.env.MAILBOX.runDueAlarms();
  near(alarmIn(object), 10 * MIN, "quiet pace of ten minutes");
  await object.instance.webSocketMessage(ws, JSON.stringify({ type: "ping" }));
  assert.equal(ws.frames.at(-1).type, "pong");
  near(alarmIn(object), 10 * MIN, "pace after a ping");
  assert.equal((await heartbeatAfter(relay, ws, 10 * MIN + 1)).next, 10 * MIN);
});

test("an app that sends no heartbeat header keeps a heartbeat every two minutes, however quiet", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const { ws, object } = await connect(relay, bob, undefined);
  for (let i = 0; i < 8; i++) assert.equal((await heartbeatAfter(relay, ws, 2 * MIN + 1)).next, 2 * MIN);
  near(alarmIn(object), 2 * MIN, "legacy pace");
});

test("a socket that carries nothing, as one accepted before the pace existed, keeps two minutes", async () => {
  const relay = makeRelay();
  const bob = await makePhone(relay);
  const object = relay.env.MAILBOX.objects.get(bob.number);
  const frames = [];
  const bare = { send: (text) => frames.push(JSON.parse(text)), close() {} };
  object.state.getWebSockets = () => [bare];
  advance(25 * 60 * MIN);
  await relay.env.MAILBOX.runDueAlarms();
  assert.equal(frames.find((f) => f.type === "hb")?.next, 2 * MIN);
  near(alarmIn(object), 2 * MIN, "pace of a bare socket");
});
