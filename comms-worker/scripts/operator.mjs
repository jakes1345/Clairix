// The operator's tool for abuse on a relay you run (the runbook is
// docs/ABUSE.md at the repository's root): read the reports phones sent,
// suspend a number or lift a suspension, and close the reports once dealt with.
//
//   ENROLL_SECRET=… node scripts/operator.mjs register  https://relay.example
//   ADMIN_SECRET=…  node scripts/operator.mjs reports   https://relay.example
//   ADMIN_SECRET=…  node scripts/operator.mjs suspend   https://relay.example "482 913 605"
//   ADMIN_SECRET=…  node scripts/operator.mjs unsuspend https://relay.example "482 913 605"
//   ADMIN_SECRET=…  node scripts/operator.mjs close     https://relay.example "482 913 605" [https://their.relay]
//
// Suspending takes two things the relay checks together: a request signed by an
// operator identity (an account on the relay whose key is in OPERATOR_KEYS) and
// the admin secret. `register` makes that identity once: an Ed25519 key that
// lives in a file on this computer (OPERATOR_IDENTITY, or
// ~/.clairix-operator/<relay host>.json, readable by you alone) and an unlisted
// account on the relay that holds it, registered with the enrollment secret.
// It prints the key to add to the relay's OPERATOR_KEYS. The identity only
// signs these requests; it sends and receives no messages.
//
// Secrets are read from the environment, never from the command line, so they
// do not land in the shell's history or the process list. Exit codes: 0 done,
// 3 there is no such account on that relay, 1 anything else.
import { createHash, createPrivateKey, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** The origin of an https relay address (http only for one on this machine), or an Error saying why not. */
export function relayOrigin(arg) {
  let url;
  try {
    url = new URL(arg);
  } catch {
    throw new Error(`Not a URL: ${arg}`);
  }
  const local = url.hostname === "localhost" || url.hostname === "127.0.0.1" || url.hostname === "[::1]";
  if (url.protocol !== "https:" && !(url.protocol === "http:" && local)) {
    throw new Error("The relay must be an https URL (http only for a relay on this machine): the admin secret travels with the request.");
  }
  return url.origin;
}

/** A Clairix number as nine digits, or an Error. */
export function clairixNumber(arg) {
  const digits = String(arg ?? "").replace(/\D/g, "");
  if (!/^[1-9][0-9]{8}$/.test(digits)) throw new Error(`Not a Clairix number: ${arg} (nine digits, not starting with 0)`);
  return digits;
}

/** Where the identity for [origin] is kept unless OPERATOR_IDENTITY says otherwise. */
export function identityPath(origin, env = process.env) {
  if (env.OPERATOR_IDENTITY) return resolve(env.OPERATOR_IDENTITY);
  return join(homedir(), ".clairix-operator", `${new URL(origin).host.replace(/[^A-Za-z0-9.-]/g, "_")}.json`);
}

const rawKey = (publicKey) => Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url").toString("base64");

/**
 * Makes an operator identity and registers it on [origin] as an unlisted
 * account, admitted by the enrollment secret ([enrollSecret]). Its Curve25519
 * and sealing keys are made fresh and their secrets thrown away: nothing is
 * ever sealed to this account that it would need to open. Returns the identity
 * to keep: the relay, the number it was given, its public key and its private
 * key (PKCS#8 PEM).
 */
export async function registerOperator(origin, enrollSecret) {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const ed25519 = rawKey(publicKey);
  const signText = (text) => sign(null, Buffer.from(text, "utf8"), privateKey).toString("base64");
  const curve25519 = rawKey(generateKeyPairSync("x25519").publicKey);
  const sealing = rawKey(generateKeyPairSync("x25519").publicKey);
  const body = JSON.stringify({
    ts: Date.now(),
    secret: enrollSecret,
    ed25519,
    curve25519,
    sealing,
    signature: signText(`${curve25519}|${sealing}`),
    listed: false,
  });
  const res = await call(new URL("/v1/register", origin), { method: "POST", headers: { "content-type": "application/json", "x-clairix-sig": signText(body) }, body });
  if (res.status !== 201 || typeof res.body.number !== "string") {
    throw new Error(`The relay refused the registration (${res.status}): ${res.body.error ?? "no reason given"}`);
  }
  return { relay: origin, number: res.body.number, ed25519, privateKey: privateKey.export({ format: "pem", type: "pkcs8" }) };
}

/** Keeps [identity] at [path], readable by this user alone; an existing file is never overwritten. */
export function saveIdentity(path, identity) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  writeFileSync(path, JSON.stringify(identity, null, 2) + "\n", { mode: 0o600, flag: "wx" });
  chmodSync(path, 0o600);
}

export function loadIdentity(path) {
  const identity = JSON.parse(readFileSync(path, "utf8"));
  if (typeof identity.relay !== "string" || typeof identity.number !== "string" || typeof identity.privateKey !== "string") {
    throw new Error(`${path} is not an operator identity made by this script`);
  }
  return identity;
}

/**
 * A request signed by [identity] the way the app signs one (comms-worker/src/auth.ts):
 * number, time, a fresh nonce, method, path and the body's SHA-256, with the admin
 * secret alongside. Returns the status and the parsed answer.
 */
export async function signedAdmin(identity, adminSecret, method, path, bodyObj) {
  const text = bodyObj === undefined ? "" : JSON.stringify(bodyObj);
  const url = new URL(path, identity.relay);
  const ts = Date.now();
  const nonce = randomBytes(18).toString("base64url");
  const digest = createHash("sha256").update(text, "utf8").digest("hex");
  const payload = `${identity.number}\n${ts}\n${nonce}\n${method}\n${url.pathname}${url.search}\n${digest}`;
  const signature = sign(null, Buffer.from(payload, "utf8"), createPrivateKey(identity.privateKey)).toString("base64");
  return call(url, {
    method,
    headers: {
      "content-type": "application/json",
      "x-clairix-number": identity.number,
      "x-clairix-ts": String(ts),
      "x-clairix-nonce": nonce,
      "x-clairix-sig": signature,
      "x-admin-secret": adminSecret,
    },
    body: method === "GET" ? undefined : text,
  });
}

/** Suspends [number] ([suspended] true) or lifts its suspension, signed by [identity]. */
export function setSuspended(identity, adminSecret, number, suspended) {
  return signedAdmin(identity, adminSecret, "POST", suspended ? "/v1/admin/suspend" : "/v1/admin/unsuspend", { number });
}

/** Every reported number on [origin], with the admin secret alone (the reports route takes no identity). */
export function listReports(origin, adminSecret) {
  return call(new URL("/v1/admin/reports", origin), { method: "GET", headers: { "x-admin-secret": adminSecret } });
}

/** Closes the reports about [number] (on [theirRelay], when that is another relay). */
export function closeReports(origin, adminSecret, number, theirRelay) {
  const body = theirRelay ? { number, relay: theirRelay } : { number };
  return call(new URL("/v1/admin/reports", origin), {
    method: "DELETE",
    headers: { "content-type": "application/json", "x-admin-secret": adminSecret },
    body: JSON.stringify(body),
  });
}

/** One request, waiting as long as the relay asks when it says to slow down; never follows a redirect. */
async function call(url, init) {
  for (;;) {
    const response = await fetch(url, { ...init, redirect: "error" });
    if (response.status === 429) {
      const wait = Math.min(120, Number(response.headers.get("retry-after")) || 30);
      console.error(`The relay asks to wait ${wait} s before the next admin request.`);
      await new Promise((r) => setTimeout(r, wait * 1000));
      continue;
    }
    let body = {};
    try {
      body = await response.json();
    } catch {
      // not JSON
    }
    return { status: response.status, body };
  }
}

const day = (ms) => new Date(ms).toISOString().slice(0, 10);
const formatted = (n) => `${n.slice(0, 3)} ${n.slice(3, 6)} ${n.slice(6)}`;

const USAGE = `usage:
  ENROLL_SECRET=… node scripts/operator.mjs register  <relay>
  ADMIN_SECRET=…  node scripts/operator.mjs reports   <relay>
  ADMIN_SECRET=…  node scripts/operator.mjs suspend   <relay> <Clairix number>
  ADMIN_SECRET=…  node scripts/operator.mjs unsuspend <relay> <Clairix number>
  ADMIN_SECRET=…  node scripts/operator.mjs close     <relay> <Clairix number> [the relay that number is on]`;

/** What the relay's refusal means, for the person at the terminal. */
function refusal(res) {
  const reason = res.body.error ?? `HTTP ${res.status}`;
  if (res.status === 401) return `The relay refused the operator identity's signature: ${reason} (is this computer's clock right, and is the identity registered on this relay?)`;
  if (res.status === 403) return `The relay refused: ${reason}`;
  if (res.status === 503) return `The relay cannot do this: ${reason} (are ADMIN_SECRET and OPERATOR_KEYS set on the relay?)`;
  return `The relay answered ${res.status}: ${reason}`;
}

async function main(argv = process.argv.slice(2), env = process.env) {
  const [command, relayArg, numberArg, theirArg] = argv;
  if (!command || !relayArg) throw new Error(USAGE);
  const origin = relayOrigin(relayArg);

  if (command === "register") {
    const enroll = env.ENROLL_SECRET ?? "";
    if (enroll.length === 0) throw new Error("ENROLL_SECRET must be set to the relay's enrollment secret.");
    const path = identityPath(origin, env);
    if (existsSync(path)) throw new Error(`${path} already holds an operator identity; set OPERATOR_IDENTITY to make another.`);
    const identity = await registerOperator(origin, enroll);
    saveIdentity(path, identity);
    console.log(`Registered operator identity ${formatted(identity.number)} on ${origin}; its private key is in ${path}.`);
    console.log("Add this key to the relay's OPERATOR_KEYS setting (comma-separated if there are several):");
    console.log(identity.ed25519);
    return 0;
  }

  const secret = env.ADMIN_SECRET ?? "";
  if (secret.length < 16) throw new Error("ADMIN_SECRET must be set to the relay's admin secret (16 characters at least).");

  if (command === "reports") {
    const res = await listReports(origin, secret);
    if (res.status !== 200) throw new Error(refusal(res));
    const numbers = res.body.numbers ?? [];
    if (numbers.length === 0) console.log(`No open reports on ${origin}.`);
    for (const n of numbers) {
      const reasons = Object.entries(n.reasons).map(([r, c]) => `${r} ${c}`).join(", ");
      const where = n.relay ? ` on ${n.relay}` : "";
      console.log(`${formatted(n.number)}${where}: ${n.reports} report(s), ${reasons}; ${day(n.firstDay)} to ${day(n.lastDay)}`);
      for (const e of n.envelopes) console.log(`  envelope ${e}`);
    }
    return 0;
  }

  if (command === "close") {
    const number = clairixNumber(numberArg);
    const res = await closeReports(origin, secret, number, theirArg ? relayOrigin(theirArg) : undefined);
    if (res.status !== 200) throw new Error(refusal(res));
    console.log(`Closed ${res.body.closed} report(s) about ${formatted(number)}.`);
    return 0;
  }

  if (command === "suspend" || command === "unsuspend") {
    const number = clairixNumber(numberArg);
    const path = identityPath(origin, env);
    if (!existsSync(path)) throw new Error(`No operator identity for ${origin} at ${path}: run register first, or set OPERATOR_IDENTITY.`);
    const identity = loadIdentity(path);
    if (identity.relay !== origin) throw new Error(`${path} is an identity on ${identity.relay}, not ${origin}.`);
    const res = await setSuspended(identity, secret, number, command === "suspend");
    if (res.status === 404) {
      console.error(`No account ${formatted(number)} on ${origin}.`);
      return 3;
    }
    if (res.status !== 200) throw new Error(refusal(res));
    if (command === "suspend") {
      console.log(`${formatted(number)} is suspended on ${origin}${res.body.already ? " (it already was)" : ""}, since ${day(res.body.since)}.`);
    } else {
      console.log(res.body.wasSuspended ? `Lifted the suspension of ${formatted(number)} on ${origin}.` : `${formatted(number)} was not suspended on ${origin}.`);
    }
    return 0;
  }

  throw new Error(USAGE);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(
    (code) => process.exit(code),
    (e) => {
      console.error(e.message);
      process.exit(1);
    },
  );
}
