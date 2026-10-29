// The container relay's backups, lease and settings (container/), against
// a Bunny Storage stand-in (harness/fake-bunny-storage.mjs). The whole
// container, built and restored from a backup, is test/container-restore.mjs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createCipheriv, createHash, randomBytes } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { gzipSync } from "node:zlib";
import {
  MAGIC,
  Storage,
  backUp,
  backupSet,
  copyId,
  parseBackupKey,
  releaseLease,
  restore,
  seal,
  storageIsEmpty,
  takeLease,
  unseal,
} from "../container/backup.mjs";
import { caddyEnvironment, caddyfile, readConfig, relayEnvironment, torrc } from "../container/config.mjs";
import { startFakeBunnyStorage } from "./harness/fake-bunny-storage.mjs";

const KEY = randomBytes(32);
const ZONE = "zone";
const ACCESS = "storage-password";

async function scratch() {
  return mkdtemp(join(tmpdir(), "clairix-container-"));
}

/** A volume as the relay leaves it: a few Durable Object databases and the onion keys. */
async function volumeWith(root, rows) {
  await mkdir(join(root, "relay", "clairix-relay-Mailbox"), { recursive: true });
  for (const [name, value] of Object.entries(rows)) {
    const db = new DatabaseSync(join(root, "relay", "clairix-relay-Mailbox", `${name}.sqlite`));
    db.exec("PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)");
    db.prepare("INSERT OR REPLACE INTO kv VALUES ('value', ?)").run(value);
    db.close();
  }
  await mkdir(join(root, "tor", "hs"), { recursive: true });
  await writeFile(join(root, "tor", "hs", "hostname"), "a".repeat(56) + ".onion\n");
  await writeFile(join(root, "tor", "hs", "hs_ed25519_secret_key"), randomBytes(96));
}

function valueIn(root, name) {
  const db = new DatabaseSync(join(root, "relay", "clairix-relay-Mailbox", `${name}.sqlite`), { readOnly: true });
  try {
    return db.prepare("SELECT v FROM kv WHERE k = 'value'").get()?.v;
  } finally {
    db.close();
  }
}

async function withStorage(fn) {
  const fake = await startFakeBunnyStorage({ zone: ZONE, accessKey: ACCESS });
  try {
    await fn(new Storage({ endpoint: fake.endpoint, zone: ZONE, accessKey: ACCESS, path: "clairix-relay/relay.example.org" }), fake);
  } finally {
    await fake.close();
  }
}

// ── The sealed archive ────────────────────────────────────────────────────

test("a sealed archive opens with its key to the same files", async () => {
  const dir = await scratch();
  try {
    const a = join(dir, "a");
    const b = join(dir, "b");
    await writeFile(a, randomBytes(70_000));
    await writeFile(b, "");
    const sealed = join(dir, "sealed");
    const { size, sha256 } = await seal(
      [
        { name: "relay/x/one.sqlite", path: a, size: 70_000 },
        { name: "tor/hs/hostname", path: b, size: 0 },
      ],
      KEY,
      sealed,
    );
    const bytes = await readFile(sealed);
    assert.equal(bytes.length, size);
    assert.equal(createHash("sha256").update(bytes).digest("hex"), sha256);
    assert.ok(bytes.subarray(0, MAGIC.length).equals(MAGIC));
    await unseal(sealed, KEY, join(dir, "out"));
    assert.deepEqual(await readFile(join(dir, "out", "relay", "x", "one.sqlite")), await readFile(a));
    assert.equal((await readFile(join(dir, "out", "tor", "hs", "hostname"))).length, 0);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("a sealed archive does not open with another key, or once a byte is changed", async () => {
  const dir = await scratch();
  try {
    const a = join(dir, "a");
    await writeFile(a, "the relay's keys");
    const sealed = join(dir, "sealed");
    await seal([{ name: "relay/x/one.sqlite", path: a, size: 16 }], KEY, sealed);
    await assert.rejects(unseal(sealed, randomBytes(32), join(dir, "wrong-key")), /does not open with this BACKUP_KEY/);
    const bytes = await readFile(sealed);
    bytes[MAGIC.length + 12 + 3] ^= 1;
    await writeFile(sealed, bytes);
    await assert.rejects(unseal(sealed, KEY, join(dir, "changed")), /does not open with this BACKUP_KEY/);
    // Nothing was unpacked from either.
    await assert.rejects(readFile(join(dir, "changed", "relay", "x", "one.sqlite")), { code: "ENOENT" });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("an archive naming a file outside its folder is refused, even with the right key", async () => {
  const dir = await scratch();
  try {
    for (const name of ["relay/../../etc/passwd", "/etc/passwd", "other/file", "relay"]) {
      const nameBytes = Buffer.from(name);
      const record = Buffer.alloc(2 + nameBytes.length + 8);
      record.writeUInt16BE(nameBytes.length, 0);
      nameBytes.copy(record, 2);
      record.writeBigUInt64BE(1n, 2 + nameBytes.length);
      const plain = gzipSync(Buffer.concat([record, Buffer.from("x"), Buffer.alloc(2)]));
      const nonce = randomBytes(12);
      const cipher = createCipheriv("aes-256-gcm", KEY, nonce);
      cipher.setAAD(MAGIC);
      const sealed = join(dir, "evil");
      await writeFile(sealed, Buffer.concat([MAGIC, nonce, cipher.update(plain), cipher.final(), cipher.getAuthTag()]));
      const out = join(dir, `out-${randomBytes(4).toString("hex")}`);
      await assert.rejects(unseal(sealed, KEY, out), /outside its folder/, name);
    }
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("BACKUP_KEY must be 32 bytes of base64", () => {
  assert.equal(parseBackupKey(KEY.toString("base64")).length, 32);
  assert.throws(() => parseBackupKey("short"), /32 random bytes/);
  assert.throws(() => parseBackupKey(randomBytes(16).toString("base64")), /32 random bytes/);
  assert.throws(() => parseBackupKey(undefined), /32 random bytes/);
});

// ── Backing up and restoring ──────────────────────────────────────────────

test("what is backed up: every database and the onion keys, never their -wal and -shm files", async () => {
  const root = await scratch();
  try {
    assert.equal(await storageIsEmpty(root), true);
    await volumeWith(root, { one: "1", two: "2" });
    await writeFile(join(root, "relay", "clairix-relay-Mailbox", "one.sqlite-wal"), "");
    assert.equal(await storageIsEmpty(root), false);
    assert.deepEqual(await backupSet(root), [
      "relay/clairix-relay-Mailbox/one.sqlite",
      "relay/clairix-relay-Mailbox/two.sqlite",
      "tor/hs/hostname",
      "tor/hs/hs_ed25519_secret_key",
    ]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("a backup restores onto an empty volume, and the two slots take turns", async () => {
  const root = await scratch();
  const fresh = await scratch();
  try {
    await withStorage(async (storage, fake) => {
      await volumeWith(root, { mailbox: "first" });
      const one = await backUp({ root, work: join(root, ".work"), storage, key: KEY });
      assert.equal(one.slot, "backup-a");
      assert.equal(one.files, 3);
      await volumeWith(root, { mailbox: "second" });
      const two = await backUp({ root, work: join(root, ".work"), storage, key: KEY });
      assert.equal(two.slot, "backup-b");
      const latest = JSON.parse(fake.files.get("clairix-relay/relay.example.org/latest.json"));
      assert.equal(latest.slot, "backup-b");
      // The zone holds only sealed bytes: no database text in the clear.
      const stored = fake.files.get("clairix-relay/relay.example.org/backup-b");
      assert.ok(!stored.includes(Buffer.from("second")));
      assert.ok(!stored.includes(Buffer.from("SQLite format")));

      const used = await restore({ root: fresh, work: join(fresh, ".work"), storage, key: KEY });
      assert.equal(used.slot, "backup-b");
      assert.equal(valueIn(fresh, "mailbox"), "second");
      assert.equal(await readFile(join(fresh, "tor", "hs", "hostname"), "utf8"), "a".repeat(56) + ".onion\n");
    });
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(fresh, { recursive: true, force: true });
  }
});

test("when the newest backup is damaged, the one before it is restored", async () => {
  const root = await scratch();
  const fresh = await scratch();
  try {
    await withStorage(async (storage, fake) => {
      await volumeWith(root, { mailbox: "older" });
      await backUp({ root, work: join(root, ".work"), storage, key: KEY });
      await volumeWith(root, { mailbox: "newer" });
      await backUp({ root, work: join(root, ".work"), storage, key: KEY });
      const name = "clairix-relay/relay.example.org/backup-b";
      const damaged = Buffer.from(fake.files.get(name));
      damaged[40] ^= 0xff;
      fake.files.set(name, damaged);
      const notes = [];
      const used = await restore({ root: fresh, work: join(fresh, ".work"), storage, key: KEY, log: (m) => notes.push(m) });
      assert.equal(used.slot, "backup-a");
      assert.equal(valueIn(fresh, "mailbox"), "older");
      assert.match(notes.join("\n"), /could not be used/);
    });
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(fresh, { recursive: true, force: true });
  }
});

test("no backup yet means a new relay; backups that cannot be read, or a zone that cannot be reached, stop it", async () => {
  const fresh = await scratch();
  try {
    await withStorage(async (storage) => {
      assert.equal(await restore({ root: fresh, work: join(fresh, ".work"), storage, key: KEY }), null);
      const root = await scratch();
      await volumeWith(root, { mailbox: "x" });
      await backUp({ root, work: join(root, ".work"), storage, key: KEY });
      await rm(root, { recursive: true, force: true });
      // The wrong key: starting empty would give the relay new keys while its real ones sit in storage.
      await assert.rejects(restore({ root: fresh, work: join(fresh, ".work"), storage, key: randomBytes(32) }), /no backup could be restored/);
    });
    const wrongPassword = await startFakeBunnyStorage({ zone: ZONE, accessKey: "other" });
    try {
      const storage = new Storage({ endpoint: wrongPassword.endpoint, zone: ZONE, accessKey: ACCESS, path: "p" });
      await assert.rejects(restore({ root: fresh, work: join(fresh, ".work"), storage, key: KEY }), /HTTP 401/);
    } finally {
      await wrongPassword.close();
    }
    const nobody = new Storage({ endpoint: "http://127.0.0.1:9", zone: ZONE, accessKey: ACCESS, path: "p" });
    await assert.rejects(restore({ root: fresh, work: join(fresh, ".work"), storage: nobody, key: KEY }));
  } finally {
    await rm(fresh, { recursive: true, force: true });
  }
});

test("the storage password is only ever sent over HTTPS (or to this machine)", () => {
  assert.throws(() => new Storage({ endpoint: "http://storage.bunnycdn.com", zone: ZONE, accessKey: ACCESS, path: "p" }), /must be https/);
  assert.doesNotThrow(() => new Storage({ endpoint: "https://storage.bunnycdn.com", zone: ZONE, accessKey: ACCESS, path: "p" }));
  assert.doesNotThrow(() => new Storage({ endpoint: "http://127.0.0.1:8000", zone: ZONE, accessKey: ACCESS, path: "p" }));
});

// ── The lease ─────────────────────────────────────────────────────────────

test("one copy at a time: a second copy waits for the first one's lease to lapse", async () => {
  await withStorage(async (storage) => {
    const t = 1_800_000_000_000;
    assert.deepEqual(await takeLease(storage, "copy-1", t), { ok: true });
    const refused = await takeLease(storage, "copy-2", t + 60_000);
    assert.equal(refused.ok, false);
    assert.equal(refused.holder, "copy-1");
    // The same copy (same volume) restarting takes its own lease straight back.
    assert.deepEqual(await takeLease(storage, "copy-1", t + 60_000), { ok: true });
    // Once a lease lapses (the copy died without giving it up), another may take it.
    assert.deepEqual(await takeLease(storage, "copy-2", t + 60_000 + 3 * 60_000 + 1), { ok: true });
    assert.equal((await takeLease(storage, "copy-1", t + 5 * 60_000)).ok, false);
  });
});

test("a copy gives up only its own lease", async () => {
  await withStorage(async (storage) => {
    await takeLease(storage, "copy-1");
    await releaseLease(storage, "copy-2");
    assert.equal((await takeLease(storage, "copy-2")).ok, false);
    await releaseLease(storage, "copy-1");
    assert.deepEqual(await takeLease(storage, "copy-2"), { ok: true });
  });
});

test("a volume keeps the id its copy goes by", async () => {
  const root = await scratch();
  try {
    const id = await copyId(root);
    assert.match(id, /^[0-9a-f]{32}$/);
    assert.equal(await copyId(root), id);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

// ── Settings ──────────────────────────────────────────────────────────────

const good = {
  RELAY_DOMAIN: "relay.example.org",
  ENROLL_SECRET: "0123456789abcdef0123",
  BACKUP_ZONE: ZONE,
  BACKUP_ACCESS_KEY: ACCESS,
  BACKUP_KEY: KEY.toString("base64"),
};

test("settings: the defaults, and every missing or mistyped one named at once", () => {
  const c = readConfig(good);
  assert.equal(c.origin, "https://relay.example.org");
  assert.equal(c.onion, true);
  assert.equal(c.tls, "acme");
  assert.equal(c.backup.endpoint, "https://storage.bunnycdn.com");
  assert.equal(c.backup.path, "clairix-relay/relay.example.org");
  assert.equal(c.backup.everyMs, 10 * 60_000);
  assert.ok(c.backup.key.equals(KEY));

  assert.throws(
    () => readConfig({ RELAY_DOMAIN: "Relay.Example.org", ENROLL_SECRET: "short", BACKUP_KEY: "x", ONION: "yes", BACKUP_EVERY_MINUTES: "1" }),
    (e) => {
      for (const want of ["RELAY_DOMAIN", "ENROLL_SECRET", "BACKUP_ZONE", "BACKUP_ACCESS_KEY", "BACKUP_KEY", "ONION", "BACKUP_EVERY_MINUTES"]) {
        assert.match(e.message, new RegExp(want));
      }
      return true;
    },
  );
  assert.equal(readConfig({ ...good, BACKUP: "off", BACKUP_ZONE: "", BACKUP_KEY: "" }).backup, null);
  assert.equal(readConfig({ ...good, ONION: "off" }).onion, false);
  assert.throws(() => readConfig({ ...good, BACKUP_PATH: "../elsewhere" }), /BACKUP_PATH/);
});

test("the relay gets its own settings and never the backup secrets", () => {
  const env = { ...good, MIX_MAX_MS: "0", FEDERATION: "off", PATH: "/evil", NODE_OPTIONS: "--require x" };
  const out = relayEnvironment(env, ["ENROLL_SECRET", "MIX_MAX_MS", "FEDERATION", "RELAY_ONION"], { origin: "https://relay.example.org", onion: "b".repeat(56) + ".onion" });
  assert.equal(out.ENROLL_SECRET, good.ENROLL_SECRET);
  assert.equal(out.MIX_MAX_MS, "0");
  assert.equal(out.RELAY_ORIGIN, "https://relay.example.org");
  assert.equal(out.RELAY_ONION, `http://${"b".repeat(56)}.onion`);
  assert.equal(out.PATH, "/usr/local/bin:/usr/bin:/bin");
  for (const secret of ["BACKUP_KEY", "BACKUP_ACCESS_KEY", "BACKUP_ZONE", "NODE_OPTIONS"]) assert.equal(out[secret], undefined, secret);
});

test("Caddy: HTTPS for the domain, no admin API, no access log, the client address set by Caddy", () => {
  const text = caddyfile(
    { domain: "relay.example.org", email: "ops@example.org", tls: "acme", onionHost: "c".repeat(56) + ".onion" },
    { relayPort: 8787, onionPort: 8790, dataDir: "/data/caddy" },
  );
  assert.match(text, /admin off/);
  assert.match(text, /root \/data\/caddy/);
  assert.match(text, /email ops@example\.org/);
  assert.match(text, /^relay\.example\.org \{/m);
  assert.match(text, /header_up CF-Connecting-IP \{remote_host\}/);
  assert.doesNotMatch(text, /^\s*log\b/m);
  assert.doesNotMatch(text, /tls internal/);
  assert.match(text, /http:\/\/:8790 \{\n\tbind 127\.0\.0\.1/);
  assert.match(text, /header_up -CF-Connecting-IP/);
  const plain = caddyfile({ domain: "relay.example.org", email: "", tls: "internal", onionHost: null }, { relayPort: 8787, onionPort: 8790, dataDir: "/d" });
  assert.match(plain, /tls internal/);
  assert.doesNotMatch(plain, /8790/);
  assert.doesNotMatch(plain, /email/);
});

const CF_TOKEN = "cfut_" + "Ab9-_".repeat(8);

test("settings: certificates checked through Cloudflare DNS need a token of the right shape", () => {
  const c = readConfig({ ...good, ACME_DNS: "cloudflare", CLOUDFLARE_API_TOKEN: CF_TOKEN });
  assert.equal(c.acmeDns, "cloudflare");
  assert.equal(c.debug, false);
  assert.equal(readConfig(good).acmeDns, "");
  assert.equal(readConfig({ ...good, ACME_DNS: "cloudflare", CLOUDFLARE_API_TOKEN: "x".repeat(40) }).acmeDns, "cloudflare");

  assert.throws(() => readConfig({ ...good, ACME_DNS: "cloudflare" }), /CLOUDFLARE_API_TOKEN is required/);
  for (const bad of [`"${CF_TOKEN}"`, `Bearer ${CF_TOKEN}`, `${CF_TOKEN}\n`, "short"]) {
    assert.throws(() => readConfig({ ...good, ACME_DNS: "cloudflare", CLOUDFLARE_API_TOKEN: bad }), /does not look like a Cloudflare API token/, bad);
  }
  assert.throws(() => readConfig({ ...good, ACME_DNS: "route53" }), /ACME_DNS must be "cloudflare"/);
  assert.throws(() => readConfig({ ...good, ACME_DNS: "cloudflare", CLOUDFLARE_API_TOKEN: CF_TOKEN, CADDY_TLS: "internal" }), /ACME_DNS needs CADDY_TLS=acme/);
  assert.equal(readConfig({ ...good, CADDY_DEBUG: "on" }).debug, true);
  assert.throws(() => readConfig({ ...good, CADDY_DEBUG: "yes" }), /CADDY_DEBUG must be "on" or "off"/);
});

test("Caddy: a DNS check through Cloudflare, with the token read from the environment, never the file", () => {
  const text = caddyfile(
    { domain: "relay.example.org", email: "", tls: "acme", acmeDns: "cloudflare", onionHost: null },
    { relayPort: 8787, onionPort: 8790, dataDir: "/data/caddy" },
  );
  assert.match(text, /^relay\.example\.org \{\n\ttls \{\n\t\tdns cloudflare \{env\.CLOUDFLARE_API_TOKEN\}\n\t\tresolvers 1\.1\.1\.1 1\.0\.0\.1\n\t\}/m);
  assert.doesNotMatch(text, /cfut_/);
  assert.doesNotMatch(text, /^\s*(log|debug)\b/m);
  assert.doesNotMatch(caddyfile({ domain: "relay.example.org", email: "", tls: "acme", onionHost: null }, { relayPort: 8787, onionPort: 8790, dataDir: "/d" }), /dns cloudflare/);
});

test("Caddy: CADDY_DEBUG adds the debug log and an access log for the domain only", () => {
  const text = caddyfile(
    { domain: "relay.example.org", email: "", tls: "acme", debug: true, onionHost: "c".repeat(56) + ".onion" },
    { relayPort: 8787, onionPort: 8790, dataDir: "/data/caddy" },
  );
  assert.ok(text.indexOf("\tdebug\n}") > 0 && text.indexOf("\tdebug\n}") < text.indexOf("relay.example.org {"), "debug is a global option");
  assert.match(text, /^relay\.example\.org \{\n\tlog\n/m);
  assert.equal(text.match(/^\tlog$/gm).length, 1, "the onion service stays unlogged");
});

test("Caddy's environment holds the Cloudflare token only when DNS checks are on", () => {
  const env = { ...good, CLOUDFLARE_API_TOKEN: CF_TOKEN, PATH: "/evil" };
  const dirs = { dataDir: "/data/caddy", configDir: "/run/clairix/caddy" };
  const on = caddyEnvironment(env, { acmeDns: "cloudflare" }, dirs);
  assert.equal(on.CLOUDFLARE_API_TOKEN, CF_TOKEN);
  assert.equal(on.PATH, "/usr/bin:/bin");
  assert.equal(on.XDG_DATA_HOME, "/data/caddy");
  for (const secret of ["ENROLL_SECRET", "BACKUP_KEY", "BACKUP_ACCESS_KEY"]) assert.equal(on[secret], undefined, secret);
  assert.equal(caddyEnvironment(env, { acmeDns: "" }, dirs).CLOUDFLARE_API_TOKEN, undefined);
  // Nor does the relay ever get it.
  assert.equal(relayEnvironment(env, ["ENROLL_SECRET"], { origin: "https://relay.example.org", onion: null }).CLOUDFLARE_API_TOKEN, undefined);
});

test("Tor: the onion service only, no SOCKS port", () => {
  const text = torrc({ dataDir: "/data/tor/data", serviceDir: "/data/tor/hs", onionPort: 8790 });
  assert.match(text, /^SocksPort 0$/m);
  assert.match(text, /^HiddenServiceDir \/data\/tor\/hs$/m);
  assert.match(text, /^HiddenServicePort 80 127\.0\.0\.1:8790$/m);
});
