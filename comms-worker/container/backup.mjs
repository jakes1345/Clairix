// Backups and the single-copy lease for the container relay (container/),
// kept in a Bunny Storage zone.
//
// A backup holds what the relay cannot rebuild: every Durable Object's SQLite
// file (the relay's keys, its accounts and every mailbox) and the onion
// service's keys. It is copied with SQLite's online backup, so a file being
// written is still copied whole, packed, gzipped and sealed with AES-256-GCM
// under BACKUP_KEY before it leaves the container. Bunny Storage sees only
// the sealed bytes.
//
// Two slots, backup-a and backup-b, take turns; latest.json names the newer
// one only once it has uploaded whole, so an upload cut off half way leaves
// the previous backup in place.
//
// The lease (lease.json) keeps two copies of the relay from running at once.
// Two copies would hold two different sets of mailboxes under one address,
// and after a restore the same keys. A copy renews its lease every minute and
// another copy waits for it to lapse. The lease names the copy by the id kept
// on its own volume, so a container that restarts on the same volume takes
// its own lease straight back.
import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { createReadStream, createWriteStream, openAsBlob } from "node:fs";
import { chmod, mkdir, open, readdir, readFile, rename, rm, stat } from "node:fs/promises";
import { dirname, join, sep } from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { DatabaseSync, backup as sqliteBackup } from "node:sqlite";
import { createGunzip, createGzip } from "node:zlib";

export const MAGIC = Buffer.from("CLXRB1\n");
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const SLOTS = ["backup-a", "backup-b"];
export const LEASE_TTL_MS = 3 * 60_000;

/** Decodes BACKUP_KEY: base64 of exactly 32 random bytes. */
export function parseBackupKey(text) {
  const t = (text ?? "").trim();
  if (!/^[A-Za-z0-9+/]{43}=$/.test(t)) throw new Error("BACKUP_KEY must be 32 random bytes in base64 (44 characters ending in =)");
  return Buffer.from(t, "base64");
}

// ── What is backed up ─────────────────────────────────────────────────────

/** The files under `root` a backup holds, as paths relative to it. */
export async function backupSet(root) {
  const out = [];
  for (const rel of await walk(join(root, "relay"))) {
    if (rel.endsWith(".sqlite")) out.push(join("relay", rel));
  }
  for (const name of ["hostname", "hs_ed25519_public_key", "hs_ed25519_secret_key"]) {
    if (await isFile(join(root, "tor", "hs", name))) out.push(join("tor", "hs", name));
  }
  return out.sort();
}

/** True when the relay has no storage yet: a new volume, or one that lost its disk. */
export async function storageIsEmpty(root) {
  return (await walk(join(root, "relay"))).every((rel) => !rel.endsWith(".sqlite"));
}

async function walk(dir) {
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch (e) {
    if (e.code === "ENOENT") return [];
    throw e;
  }
  const out = [];
  for (const e of entries) {
    if (e.isDirectory()) out.push(...(await walk(join(dir, e.name))).map((r) => join(e.name, r)));
    else if (e.isFile()) out.push(e.name);
  }
  return out;
}

async function isFile(path) {
  try {
    return (await stat(path)).isFile();
  } catch (e) {
    if (e.code === "ENOENT") return false;
    throw e;
  }
}

// ── The archive: one sealed file ──────────────────────────────────────────
//
// Plaintext: records of [u16 name length][name, UTF-8][u64 size][bytes],
// ended by a zero name length; gzipped. Sealed: MAGIC, a 12-byte nonce, the
// AES-256-GCM ciphertext of the gzip, its 16-byte tag. MAGIC is the
// associated data.

function* records(files) {
  for (const f of files) {
    const name = Buffer.from(f.name.split(sep).join("/"), "utf8");
    const head = Buffer.alloc(2 + name.length + 8);
    head.writeUInt16BE(name.length, 0);
    name.copy(head, 2);
    head.writeBigUInt64BE(BigInt(f.size), 2 + name.length);
    yield { head, path: f.path };
  }
}

/** Packs `files` ({ name, path, size }) into `out`, sealed with `key`. Returns { size, sha256 }. */
export async function seal(files, key, out) {
  const nonce = randomBytes(NONCE_BYTES);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(MAGIC);
  const hash = createHash("sha256");
  let size = 0;
  const count = new Transform({
    transform(chunk, _enc, done) {
      hash.update(chunk);
      size += chunk.length;
      done(null, chunk);
    },
  });
  const sink = createWriteStream(out, { mode: 0o600 });
  const prefix = Buffer.concat([MAGIC, nonce]);
  hash.update(prefix);
  size += prefix.length;
  sink.write(prefix);
  const source = Readable.from(
    (async function* () {
      for (const r of records(files)) {
        yield r.head;
        for await (const chunk of createReadStream(r.path)) yield chunk;
      }
      yield Buffer.alloc(2);
    })(),
  );
  await pipeline(source, createGzip(), cipher, count, sink, { end: false });
  const tag = cipher.getAuthTag();
  hash.update(tag);
  size += tag.length;
  await new Promise((resolve, reject) => sink.end(tag, (e) => (e ? reject(e) : resolve())));
  return { size, sha256: hash.digest("hex") };
}

/**
 * Opens a sealed archive into `dir` (which must not exist yet). The whole
 * file is authenticated before anything is unpacked: it is decrypted to a
 * scratch file first, and a wrong key or a changed byte fails there.
 */
export async function unseal(sealedPath, key, dir) {
  const { size } = await stat(sealedPath);
  const min = MAGIC.length + NONCE_BYTES + TAG_BYTES;
  if (size < min) throw new Error("backup is too short to be one");
  const fh = await open(sealedPath, "r");
  let nonce, tag;
  try {
    const head = Buffer.alloc(MAGIC.length + NONCE_BYTES);
    await fh.read(head, 0, head.length, 0);
    if (!head.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error("not a Clairix relay backup");
    nonce = head.subarray(MAGIC.length);
    tag = Buffer.alloc(TAG_BYTES);
    await fh.read(tag, 0, TAG_BYTES, size - TAG_BYTES);
  } finally {
    await fh.close();
  }
  const decipher = createDecipheriv("aes-256-gcm", key, nonce);
  decipher.setAAD(MAGIC);
  decipher.setAuthTag(tag);
  const plain = `${sealedPath}.plain`;
  try {
    await pipeline(
      createReadStream(sealedPath, { start: MAGIC.length + NONCE_BYTES, end: size - TAG_BYTES - 1 }),
      decipher,
      createGunzip(),
      createWriteStream(plain, { mode: 0o600 }),
    );
  } catch (e) {
    await rm(plain, { force: true });
    if (/unable to authenticate|auth/i.test(String(e?.message))) throw new Error("backup does not open with this BACKUP_KEY, or was changed");
    throw e;
  }
  try {
    await unpack(plain, dir);
  } finally {
    await rm(plain, { force: true });
  }
}

async function unpack(plainPath, dir) {
  await mkdir(dir, { recursive: false, mode: 0o700 });
  const fh = await open(plainPath, "r");
  try {
    let pos = 0;
    const read = async (n) => {
      const b = Buffer.alloc(n);
      const { bytesRead } = await fh.read(b, 0, n, pos);
      if (bytesRead !== n) throw new Error("backup ends in the middle of a file");
      pos += n;
      return b;
    };
    for (;;) {
      const nameLength = (await read(2)).readUInt16BE(0);
      if (nameLength === 0) break;
      const name = (await read(nameLength)).toString("utf8");
      const size = Number((await read(8)).readBigUInt64BE(0));
      if (!safeName(name)) throw new Error(`backup names a file outside its folder: ${name}`);
      const target = join(dir, ...name.split("/"));
      await mkdir(dirname(target), { recursive: true, mode: 0o700 });
      const out = await open(target, "wx", 0o600);
      try {
        let left = size;
        const chunk = Buffer.alloc(1 << 20);
        while (left > 0) {
          const n = Math.min(left, chunk.length);
          const { bytesRead } = await fh.read(chunk, 0, n, pos);
          if (bytesRead !== n) throw new Error("backup ends in the middle of a file");
          await out.write(chunk, 0, n);
          pos += n;
          left -= n;
        }
      } finally {
        await out.close();
      }
    }
  } finally {
    await fh.close();
  }
}

function safeName(name) {
  const parts = name.split("/");
  return (
    parts.length > 1 &&
    (parts[0] === "relay" || parts[0] === "tor") &&
    parts.every((p) => p !== "" && p !== "." && p !== ".." && !p.includes("\\") && !p.includes("\0"))
  );
}

// ── Bunny Storage ─────────────────────────────────────────────────────────

/** A folder in a Bunny Storage zone, over its HTTP API. */
export class Storage {
  constructor({ endpoint, zone, accessKey, path, fetchImpl = fetch }) {
    const base = new URL(endpoint);
    const loopback = ["127.0.0.1", "localhost", "[::1]"].includes(base.hostname);
    if (base.protocol !== "https:" && !(base.protocol === "http:" && loopback)) {
      throw new Error("BACKUP_ENDPOINT must be https:// (the storage password travels with every request)");
    }
    const folder = [zone, ...path.split("/").filter(Boolean)].map(encodeURIComponent).join("/");
    this.base = `${base.origin}/${folder}/`;
    this.accessKey = accessKey;
    this.fetch = fetchImpl;
  }

  url(name) {
    return this.base + encodeURIComponent(name);
  }

  async request(method, name, init = {}) {
    const res = await this.fetch(this.url(name), {
      ...init,
      method,
      headers: { AccessKey: this.accessKey, ...(init.headers ?? {}) },
      signal: init.signal ?? AbortSignal.timeout(init.timeoutMs ?? 30_000),
    });
    return res;
  }

  /** The file's bytes, or null when there is no such file. Throws on any other failure. */
  async getBytes(name) {
    const res = await this.request("GET", name);
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`Bunny Storage GET ${name}: HTTP ${res.status}`);
    return Buffer.from(await res.arrayBuffer());
  }

  async getJson(name) {
    const bytes = await this.getBytes(name);
    return bytes === null ? null : JSON.parse(bytes.toString("utf8"));
  }

  /** Streams the file to `path`. Returns its SHA-256, or null when there is no such file. */
  async download(name, path) {
    const res = await this.request("GET", name, { timeoutMs: 30 * 60_000 });
    if (res.status === 404) return null;
    if (!res.ok || !res.body) throw new Error(`Bunny Storage GET ${name}: HTTP ${res.status}`);
    const hash = createHash("sha256");
    await pipeline(
      Readable.fromWeb(res.body),
      new Transform({
        transform(chunk, _enc, done) {
          hash.update(chunk);
          done(null, chunk);
        },
      }),
      createWriteStream(path, { mode: 0o600 }),
    );
    return hash.digest("hex");
  }

  async put(name, body, sha256, timeoutMs = 30_000) {
    const res = await this.request("PUT", name, {
      body,
      timeoutMs,
      headers: { "Content-Type": "application/octet-stream", Checksum: sha256.toUpperCase() },
    });
    if (res.status !== 201 && !res.ok) throw new Error(`Bunny Storage PUT ${name}: HTTP ${res.status}`);
  }

  async putJson(name, value) {
    const body = Buffer.from(JSON.stringify(value));
    await this.put(name, body, createHash("sha256").update(body).digest("hex"));
  }

  async putFile(name, path, sha256) {
    await this.put(name, await openAsBlob(path), sha256, 30 * 60_000);
  }

  async delete(name) {
    const res = await this.request("DELETE", name);
    if (res.status !== 404 && !res.ok) throw new Error(`Bunny Storage DELETE ${name}: HTTP ${res.status}`);
  }
}

// ── Backing up and restoring ──────────────────────────────────────────────

/**
 * Backs up `root` to `storage`. `work` is a scratch folder on the volume,
 * emptied first. Returns what latest.json now says.
 */
export async function backUp({ root, work, storage, key, now = Date.now() }) {
  await rm(work, { recursive: true, force: true });
  await mkdir(work, { recursive: true, mode: 0o700 });
  try {
    const files = [];
    for (const rel of await backupSet(root)) {
      const copy = join(work, "files", rel);
      await mkdir(dirname(copy), { recursive: true, mode: 0o700 });
      if (rel.endsWith(".sqlite")) {
        // Online backup: a consistent copy while workerd keeps writing.
        const db = new DatabaseSync(join(root, rel), { readOnly: true });
        try {
          await sqliteBackup(db, copy);
        } finally {
          db.close();
        }
      } else {
        await pipeline(createReadStream(join(root, rel)), createWriteStream(copy, { mode: 0o600 }));
      }
      files.push({ name: rel, path: copy, size: (await stat(copy)).size });
    }
    const sealed = join(work, "backup.bin");
    const { size, sha256 } = await seal(files, key, sealed);
    const latest = await storage.getJson("latest.json");
    const slot = latest?.slot === SLOTS[0] ? SLOTS[1] : SLOTS[0];
    await storage.putFile(slot, sealed, sha256);
    const record = { version: 1, slot, at: new Date(now).toISOString(), size, sha256, files: files.length };
    await storage.putJson("latest.json", record);
    return record;
  } finally {
    await rm(work, { recursive: true, force: true });
  }
}

/**
 * Puts the newest backup that opens back under `root`, before anything
 * starts. Returns the latest.json record it used, or null when the zone
 * holds no backup yet. Throws when backups exist but none can be read, or
 * the storage cannot be reached: starting empty then would give the relay
 * new keys while its real ones sit in storage.
 */
export async function restore({ root, work, storage, key, log = () => {} }) {
  const latest = await storage.getJson("latest.json");
  if (latest === null) return null;
  const order = [latest.slot, ...SLOTS.filter((s) => s !== latest.slot)];
  const failures = [];
  for (const slot of order) {
    await rm(work, { recursive: true, force: true });
    await mkdir(work, { recursive: true, mode: 0o700 });
    try {
      const sealed = join(work, "backup.bin");
      const sha256 = await storage.download(slot, sealed);
      if (sha256 === null) {
        failures.push(`${slot}: missing`);
        continue;
      }
      if (slot === latest.slot && sha256 !== latest.sha256) {
        failures.push(`${slot}: does not match latest.json`);
        continue;
      }
      const opened = join(work, "opened");
      await unseal(sealed, key, opened);
      for (const rel of await walk(opened)) {
        const target = join(root, rel);
        await mkdir(dirname(target), { recursive: true, mode: 0o700 });
        await rm(target, { force: true });
        for (const suffix of ["-wal", "-shm"]) await rm(target + suffix, { force: true });
        await rename(join(opened, rel), target);
      }
      if (slot !== latest.slot) log(`the newest backup (${latest.slot}) could not be used; restored the one before it`);
      return slot === latest.slot ? latest : { version: 1, slot };
    } catch (e) {
      failures.push(`${slot}: ${e.message}`);
    } finally {
      await rm(work, { recursive: true, force: true });
    }
  }
  throw new Error(`no backup could be restored (${failures.join("; ")})`);
}

// ── The lease ─────────────────────────────────────────────────────────────

/**
 * Takes the lease for `owner` unless another copy holds one that has not
 * lapsed. Returns { ok: true } or { ok: false, holder, until }.
 */
export async function takeLease(storage, owner, now = Date.now(), ttl = LEASE_TTL_MS) {
  const held = await storage.getJson("lease.json");
  if (held && held.owner !== owner && Number(held.until) > now) return { ok: false, holder: held.owner, until: Number(held.until) };
  await storage.putJson("lease.json", { owner, until: now + ttl });
  // Two copies that both saw no lease both wrote one; only the last write
  // stands, and the copy that reads back someone else's backs off.
  const check = await storage.getJson("lease.json");
  if (!check || check.owner !== owner) return { ok: false, holder: check?.owner ?? null, until: Number(check?.until ?? 0) };
  return { ok: true };
}

/** Releases the lease if `owner` still holds it. */
export async function releaseLease(storage, owner) {
  const held = await storage.getJson("lease.json");
  if (held && held.owner === owner) await storage.delete("lease.json");
}

/** The id this volume's copy of the relay goes by, made on first use. */
export async function copyId(root) {
  const path = join(root, ".copy-id");
  try {
    const id = (await readFile(path, "utf8")).trim();
    if (/^[0-9a-f]{32}$/.test(id)) return id;
  } catch (e) {
    if (e.code !== "ENOENT") throw e;
  }
  const id = randomBytes(16).toString("hex");
  const fh = await open(path, "w", 0o600);
  try {
    await fh.writeFile(`${id}\n`);
    await fh.sync();
  } finally {
    await fh.close();
  }
  await chmod(path, 0o600);
  return id;
}

