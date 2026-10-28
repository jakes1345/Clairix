// The container relay (container/) end to end, as the host runs it: built
// image, a volume, backups to a Bunny Storage zone (here the stand-in in
// harness/fake-bunny-storage.mjs), Caddy in front. It registers two phones
// and leaves a message waiting, stops the container as the host does
// (SIGTERM), throws the volume away as a failed disk would, starts a new
// container on an empty volume, and requires the relay to come back from
// the backup with its keys, its onion address, both accounts and the
// waiting message.
//
//   node --import ./test/harness/register.mjs test/container-restore.mjs <image> [runc|runsc]
//
// Needs Docker, openssl, and ports 80, 443 and 8081 free on this machine's
// loopback (the container's are published there). The container reaches the
// storage stand-in by name over HTTPS, as it reaches Bunny Storage, trusting
// a certificate made for this run.
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { randomBytes } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { request } from "node:https";
import { setTimeout as sleep } from "node:timers/promises";
import { startFakeBunnyStorage } from "./harness/fake-bunny-storage.mjs";
import { ENROLL_SECRET, advance, makePhone } from "./harness/fake-env.mjs";

// fake-env.mjs moves the clock for its in-memory relays; this relay keeps
// real time, and checks the phones' timestamps against it.
advance(-Math.round(Date.now() - (performance.timeOrigin + performance.now())));

const [image, runtime = "runc"] = process.argv.slice(2);
if (!image) throw new Error("usage: container-restore.mjs <image> [runc|runsc]");
const DOMAIN = "relay.test";
const ZONE = "relay-backups";
const ACCESS = randomBytes(12).toString("hex");
const KEY = randomBytes(32).toString("base64");
const volume = `clairix-relay-test-${process.pid}`;

// Asynchronous: the storage stand-in answers from this process, and must keep
// answering while `docker stop` waits for the container's last backup.
const docker = async (...args) => (await promisify(execFile)("docker", args, { encoding: "utf8" })).stdout.trim();
const logs = async (name) => {
  const { stdout, stderr } = await promisify(execFile)("docker", ["logs", name], { encoding: "utf8", maxBuffer: 16 << 20 });
  return stdout + stderr;
};
const certs = await mkdtemp(join(tmpdir(), "clairix-storage-tls-"));
await promisify(execFile)("openssl", [
  "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "1",
  "-subj", "/CN=storage.test", "-addext", "subjectAltName=DNS:storage.test",
  "-keyout", join(certs, "key.pem"), "-out", join(certs, "cert.pem"),
]);
const storage = await startFakeBunnyStorage({
  zone: ZONE,
  accessKey: ACCESS,
  host: "0.0.0.0",
  name: "storage.test",
  tls: { key: await readFile(join(certs, "key.pem")), cert: await readFile(join(certs, "cert.pem")) },
});
const prefix = `clairix-relay/${DOMAIN}/`;
const held = () => [...storage.files.keys()].filter((k) => k.startsWith(prefix)).map((k) => k.slice(prefix.length)).sort();

async function run(name) {
  await docker("volume", "create", volume);
  // A host may mount the volume readable by root alone; the relay must cope.
  await docker("run", "--rm", "-v", `${volume}:/data`, "--entrypoint", "chmod", image, "700", "/data");
  await docker(
    "run", "-d", "--name", name, "--runtime", runtime, "-v", `${volume}:/data`,
    "-p", "127.0.0.1:443:443", "-p", "127.0.0.1:80:80", "-p", "127.0.0.1:8081:8081",
    "--add-host", "storage.test:host-gateway", "-v", `${join(certs, "cert.pem")}:/etc/storage-test.pem:ro`, "-e", "NODE_EXTRA_CA_CERTS=/etc/storage-test.pem",
    "-e", `RELAY_DOMAIN=${DOMAIN}`, "-e", `ENROLL_SECRET=${ENROLL_SECRET}`, "-e", "CADDY_TLS=internal", "-e", "MIX_MAX_MS=0",
    "-e", `BACKUP_ZONE=${ZONE}`, "-e", `BACKUP_ACCESS_KEY=${ACCESS}`, "-e", `BACKUP_KEY=${KEY}`, "-e", `BACKUP_ENDPOINT=${storage.endpoint}`,
    image,
  );
}

async function healthy(name) {
  for (let i = 0; i < 120; i++) {
    try {
      const res = await fetch("http://127.0.0.1:8081/healthz");
      if (res.ok) return;
    } catch {
      // not up yet
    }
    if ((await docker("inspect", "-f", "{{.State.Running}}", name)) !== "true") break;
    await sleep(1_000);
  }
  throw new Error(`${name} did not become healthy:\n${await logs(name)}`);
}

/** The relay as harness/fake-env.mjs's phones call it, over HTTPS through Caddy. */
const live = {
  origin: `https://${DOMAIN}`,
  call(method, path, { body, headers = {} } = {}) {
    return new Promise((resolve, reject) => {
      const req = request(
        { host: "127.0.0.1", port: 443, servername: DOMAIN, rejectUnauthorized: false, method, path, headers: { ...headers, host: DOMAIN } },
        (res) => {
          let text = "";
          res.setEncoding("utf8");
          res.on("data", (c) => (text += c));
          res.on("end", () => {
            let json = null;
            try {
              json = JSON.parse(text);
            } catch {
              // not JSON
            }
            resolve({ status: res.statusCode, json, headers: new Headers(res.headers) });
          });
        },
      );
      req.on("error", reject);
      if (body !== undefined) req.write(typeof body === "string" ? body : JSON.stringify(body));
      req.end();
    });
  },
};

const status = async () => (await live.call("GET", "/")).json;

try {
  console.log(`== first container (${runtime})`);
  await run("relay-a");
  await healthy("relay-a");
  const before = await status();
  assert.equal(before.service, "clairix-comms");
  assert.match(before.onion ?? "", /^http:\/\/[a-z2-7]{56}\.onion$/);

  const alice = await makePhone(live);
  const bob = await makePhone(live);
  const [token] = await alice.tokens(1);
  const envelope = randomBytes(150).toString("base64");
  assert.equal((await alice.send(token, bob.number, envelope)).status, 202);

  console.log("== stopping it as the host does (SIGTERM)");
  await docker("stop", "-t", "30", "relay-a");
  const stopped = await logs("relay-a");
  assert.equal(await docker("inspect", "-f", "{{.State.ExitCode}}", "relay-a"), "0", stopped);
  assert.match(stopped, /backup \(on stopping\)/);
  assert.ok(held().includes("latest.json"), `no backup in storage: ${held()}`);
  assert.ok(!held().includes("lease.json"), "the lease was not given up on stopping");
  await docker("rm", "relay-a");

  console.log("== losing the volume, then a new container on an empty one");
  await docker("volume", "rm", volume);
  await run("relay-b");
  await healthy("relay-b");
  assert.match(await logs("relay-b"), /restored the backup taken/);
  const after = await status();
  assert.equal(after.relayKey, before.relayKey, "the relay came back with a different key");
  assert.equal(after.onion, before.onion, "the relay came back with a different onion address");

  const inbox = await bob.inbox();
  assert.deepEqual(inbox?.map((e) => e.data), [envelope], "the waiting message did not survive");
  const [again] = await bob.tokens(1);
  assert.equal((await bob.send(again, alice.number)).status, 202, "accounts did not survive");
  console.log("ok: keys, onion address, accounts and the waiting message all came back from the backup");
} finally {
  for (const name of ["relay-a", "relay-b"]) {
    try {
      await docker("rm", "-f", name);
    } catch {
      // never started
    }
  }
  try {
    await docker("volume", "rm", "-f", volume);
  } catch {
    // already gone
  }
  await storage.close();
  await rm(certs, { recursive: true, force: true });
}
