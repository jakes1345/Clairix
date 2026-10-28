#!/usr/bin/env node
// Runs a Clairix relay in one container (container/Dockerfile): the same
// relay selfhost/install.sh sets up on a server, for hosts that run
// containers rather than lend a machine, such as bunny.net Magic Containers.
//
// In order, before anything answers:
//   1. Checks every setting (config.mjs).
//   2. Takes the lease in the backup zone, so two copies never run at once.
//   3. On an empty volume, restores the newest backup: the relay keeps its
//      keys, accounts and mailboxes when a disk is lost.
//   4. Starts Tor (the onion service), workerd (the relay), then Caddy (HTTPS).
// While it runs: the relay's scheduled work every 10 minutes (Cloudflare's
// cron), a backup every BACKUP_EVERY_MINUTES, the lease renewed every minute,
// and the relay asked for its status every minute. If anything stops, or the
// relay stops answering three times in a row, the container exits and the
// host starts it again. On SIGTERM it takes a last backup and gives up its
// lease before exiting.
//
// Everything persistent lives under /data, the volume.
import { spawn } from "node:child_process";
import { chmod, chown, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { LEASE_TTL_MS, Storage, backUp, copyId, releaseLease, restore, storageIsEmpty, takeLease } from "./backup.mjs";
import { caddyfile, readConfig, relayEnvironment, torrc } from "./config.mjs";

const DATA = "/data";
const RUN = "/run/clairix";
const APP = "/opt/clairix-relay";
const RELAY_PORT = 8787;
const CRON_PORT = 8789;
const ONION_PORT = 8790;
const HEALTH_PORT = 8081;
const RELAY_UID = 10001;
const RELAY_GID = 10001;
const LEASE_WAIT_MS = LEASE_TTL_MS + 60_000;
const CRON_EVERY_MS = 10 * 60_000;

const log = (msg) => console.log(`[supervisor] ${msg}`);

let config;
try {
  config = readConfig(process.env);
} catch (e) {
  console.error(`[supervisor] The relay cannot start; fix these settings:\n${e.message}`);
  process.exit(2);
}

const storage = config.backup
  ? new Storage({ endpoint: config.backup.endpoint, zone: config.backup.zone, accessKey: config.backup.accessKey, path: config.backup.path })
  : null;
const children = new Map();
let owner = null;
let ready = false;
let stopping = false;
let backingUp = null;
let lastBackupOk = Date.now();

async function main() {
  await mkdir(RUN, { recursive: true, mode: 0o755 });
  // However the host mounted the volume, the relay's own user must be able to
  // pass through it to its folders; what is in them stays theirs alone.
  await mkdir(DATA, { recursive: true });
  await chmod(DATA, 0o711);
  for (const dir of [join(DATA, "relay"), join(DATA, "tor"), join(DATA, "caddy")]) await mkdir(dir, { recursive: true, mode: 0o700 });
  startHealthServer();
  owner = await copyId(DATA);

  if (storage) {
    await holdLease();
    if (await storageIsEmpty(DATA)) {
      log("the volume holds no relay storage; looking for a backup");
      const used = await restore({ root: DATA, work: join(DATA, ".restore-work"), storage, key: config.backup.key, log });
      log(used ? `restored the backup taken ${used.at ?? "before the newest"} (${used.slot})` : "no backup yet: starting a new relay");
    }
  } else {
    log("BACKUP=off: nothing is backed up, and nothing stops a second copy of this relay running");
  }
  await giveToRelay(join(DATA, "relay"));
  await giveToRelay(join(DATA, "tor"));

  let onionHost = null;
  if (config.onion) onionHost = await startTor();

  const settings = JSON.parse(await readFile(join(APP, "build", "settings.json"), "utf8"));
  start("workerd", join(APP, "workerd"), ["serve", "--experimental", join(APP, "build", "relay.capnp")], {
    uid: RELAY_UID,
    gid: RELAY_GID,
    env: relayEnvironment(process.env, settings, { origin: config.origin, onion: onionHost }),
  });
  await waitFor(relayAnswers, 60_000, "the relay did not start");

  const caddyConfig = join(RUN, "Caddyfile");
  await writeFile(
    caddyConfig,
    caddyfile({ domain: config.domain, email: config.email, tls: config.tls, onionHost }, { relayPort: RELAY_PORT, onionPort: ONION_PORT, dataDir: join(DATA, "caddy") }),
  );
  start("caddy", "/usr/bin/caddy", ["run", "--config", caddyConfig, "--adapter", "caddyfile"], {
    env: { PATH: "/usr/bin:/bin", HOME: join(DATA, "caddy"), XDG_DATA_HOME: join(DATA, "caddy"), XDG_CONFIG_HOME: join(RUN, "caddy") },
  });

  ready = true;
  log(`the relay is up at https://${config.domain}${onionHost ? ` and http://${onionHost}` : ""}`);
  every(CRON_EVERY_MS, untilNextTen(), runCron, "scheduled work");
  every(60_000, 60_000, checkRelay, "status check");
  if (storage) {
    every(60_000, 60_000, renewLease, "lease");
    every(config.backup.everyMs, 60_000, () => backup("scheduled"), "backup");
  }
}

// ── Lease and backups ─────────────────────────────────────────────────────

async function holdLease() {
  const deadline = Date.now() + LEASE_WAIT_MS;
  for (;;) {
    const got = await takeLease(storage, owner);
    if (got.ok) return;
    if (Date.now() > deadline) {
      throw new Error(
        `another copy of this relay (${got.holder}) holds the lease. Run one copy only: one region, at most one instance, and no autoprovisioning.`,
      );
    }
    log(`another copy of this relay holds the lease until ${new Date(got.until).toISOString()}; waiting`);
    await sleep(15_000);
  }
}

let leaseMisses = 0;
async function renewLease() {
  const got = await takeLease(storage, owner);
  if (got.ok) {
    leaseMisses = 0;
    return;
  }
  if (got.holder && got.holder !== owner) {
    // Ours lapsed and another copy took over: that one is the relay now.
    log(`another copy (${got.holder}) took the lease; stopping this one`);
    await shutdown(1, { finalBackup: false });
  }
  if (++leaseMisses >= 3) log("the lease could not be renewed three times in a row; the backup zone may be unreachable");
}

async function backup(why) {
  if (backingUp) return backingUp;
  backingUp = (async () => {
    const t = Date.now();
    try {
      const record = await backUp({ root: DATA, work: join(DATA, ".backup-work"), storage, key: config.backup.key });
      lastBackupOk = Date.now();
      log(`backup (${why}): ${record.files} files, ${record.size} bytes sealed, to ${record.slot} in ${Date.now() - t} ms`);
    } catch (e) {
      const behind = Math.round((Date.now() - lastBackupOk) / 60_000);
      log(`backup (${why}) failed: ${e.message}${behind >= 60 ? ` (the last good backup is ${behind} minutes old)` : ""}`);
    } finally {
      backingUp = null;
    }
  })();
  return backingUp;
}

// ── Tor ───────────────────────────────────────────────────────────────────

async function startTor() {
  const serviceDir = join(DATA, "tor", "hs");
  const dataDir = join(DATA, "tor", "data");
  for (const dir of [dataDir, serviceDir]) {
    await mkdir(dir, { recursive: true, mode: 0o700 });
    await chown(dir, RELAY_UID, RELAY_GID);
  }
  const conf = join(RUN, "torrc");
  await writeFile(conf, torrc({ dataDir, serviceDir, onionPort: ONION_PORT }));
  start("tor", "/usr/bin/tor", ["-f", conf], { uid: RELAY_UID, gid: RELAY_GID, env: { PATH: "/usr/bin:/bin", HOME: dataDir } });
  const hostnameFile = join(serviceDir, "hostname");
  let host = "";
  await waitFor(async () => {
    host = (await readFile(hostnameFile, "utf8").catch(() => "")).trim();
    return /^[a-z2-7]{56}\.onion$/.test(host);
  }, 90_000, "Tor did not create the onion service");
  return host;
}

// ── Processes ─────────────────────────────────────────────────────────────

function start(name, cmd, args, { uid, gid, env }) {
  const child = spawn(cmd, args, { uid, gid, env, stdio: ["ignore", "inherit", "inherit"] });
  children.set(name, child);
  child.on("exit", (code, signal) => {
    children.delete(name);
    if (stopping) return;
    log(`${name} stopped (${signal ?? `exit ${code}`}); restarting the container`);
    void shutdown(1, { finalBackup: name !== "workerd" });
  });
  child.on("error", (e) => {
    log(`${name} could not start: ${e.message}`);
    void shutdown(1, { finalBackup: false });
  });
  return child;
}

async function shutdown(code, { finalBackup }) {
  if (stopping) return;
  stopping = true;
  ready = false;
  stopChild("caddy");
  if (storage) {
    if (backingUp) await backingUp;
    if (finalBackup && children.has("workerd")) await Promise.race([backup("on stopping"), sleep(20_000)]);
    await releaseLease(storage, owner).catch((e) => log(`could not release the lease: ${e.message}`));
  }
  for (const name of [...children.keys()]) stopChild(name);
  await Promise.race([Promise.all([...children.values()].map((c) => new Promise((r) => c.once("exit", r)))), sleep(5_000)]);
  process.exit(code);
}

function stopChild(name) {
  const child = children.get(name);
  if (child && child.exitCode === null) child.kill("SIGTERM");
}

for (const sig of ["SIGTERM", "SIGINT"]) {
  process.on(sig, () => {
    log(`${sig}: stopping`);
    void shutdown(0, { finalBackup: true });
  });
}

// ── Health, cron and timers ───────────────────────────────────────────────

async function relayAnswers() {
  try {
    const res = await fetch(`http://127.0.0.1:${RELAY_PORT}/`, { signal: AbortSignal.timeout(10_000) });
    return res.ok;
  } catch {
    return false;
  }
}

let misses = 0;
async function checkRelay() {
  if (await relayAnswers()) {
    misses = 0;
    return;
  }
  misses += 1;
  log(`the relay did not answer (${misses} in a row)`);
  if (misses >= 3) await shutdown(1, { finalBackup: false });
}

async function runCron() {
  const res = await fetch(`http://127.0.0.1:${CRON_PORT}/`, { method: "POST", signal: AbortSignal.timeout(120_000) });
  if (!res.ok) log(`scheduled work: HTTP ${res.status} ${(await res.text()).trim()}`);
}

function untilNextTen() {
  return CRON_EVERY_MS - (Date.now() % CRON_EVERY_MS);
}

function every(period, first, fn, what) {
  const run = async () => {
    if (stopping) return;
    try {
      await fn();
    } catch (e) {
      log(`${what}: ${e.message}`);
    }
    if (!stopping) setTimeout(run, what === "scheduled work" ? untilNextTen() : period).unref();
  };
  setTimeout(run, first).unref();
}

async function waitFor(check, ms, failure) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await check()) return;
    if (stopping) throw new Error("stopping");
    await sleep(1_000);
  }
  throw new Error(failure);
}

/** The host's checks: 200 once the relay is up and answering, 503 before and while stopping. */
function startHealthServer() {
  createServer(async (req, res) => {
    const ok = req.url === "/healthz" && ready && (await relayAnswers());
    res.writeHead(ok ? 200 : 503, { "content-type": "text/plain" }).end(ok ? "ok\n" : "not ready\n");
  }).listen(HEALTH_PORT, "0.0.0.0");
}

async function giveToRelay(dir) {
  const walk = async (d) => {
    await chown(d, RELAY_UID, RELAY_GID);
    for (const e of await readdir(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) await walk(p);
      else await chown(p, RELAY_UID, RELAY_GID);
    }
  };
  if ((await stat(dir).catch(() => null))?.isDirectory()) await walk(dir);
}

main().catch(async (e) => {
  console.error(`[supervisor] ${e.message}`);
  await rm(join(DATA, ".restore-work"), { recursive: true, force: true }).catch(() => {});
  if (!stopping) await shutdown(1, { finalBackup: false });
});
