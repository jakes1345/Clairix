// Reads the container relay's settings from its environment (container/).
// Everything here is checked before anything starts, so a missing or
// mistyped setting stops the container with a message saying which, rather
// than a relay that half works.
import { parseBackupKey } from "./backup.mjs";

const DOMAIN_RE = /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

/** Returns the settings, or throws an Error listing every problem found. */
export function readConfig(env) {
  const problems = [];
  const domain = (env.RELAY_DOMAIN ?? "").trim();
  if (!domain) problems.push("RELAY_DOMAIN is required: the name this relay answers at, e.g. relay.example.org");
  else if (!DOMAIN_RE.test(domain)) problems.push("RELAY_DOMAIN must be a lowercase host name, e.g. relay.example.org");

  const enroll = env.ENROLL_SECRET ?? "";
  if (enroll.length < 16) problems.push("ENROLL_SECRET is required, at least 16 characters: the code people type into Clairix to register here");

  const email = (env.ACME_EMAIL ?? "").trim();
  if (email && !/^[^@\s]+@[^@\s]+$/.test(email)) problems.push("ACME_EMAIL does not look like an address");

  const onion = onOff(env.ONION, true, "ONION", problems);

  const tls = (env.CADDY_TLS ?? "acme").trim();
  if (tls !== "acme" && tls !== "internal") problems.push('CADDY_TLS must be "acme" (the default) or "internal"');

  // How Let's Encrypt checks the domain. Unset: over ports 80 and 443, which
  // needs its validators to reach this container. "cloudflare": through a TXT
  // record Caddy writes in the domain's Cloudflare DNS, so the certificate no
  // longer depends on any path from the validators to the host's IP.
  const acmeDns = (env.ACME_DNS ?? "").trim();
  if (acmeDns !== "" && acmeDns !== "cloudflare") problems.push('ACME_DNS must be "cloudflare" or left unset');
  if (acmeDns && tls !== "acme") problems.push('ACME_DNS needs CADDY_TLS=acme: an internal certificate is not checked by anyone');
  if (acmeDns === "cloudflare") {
    const token = env.CLOUDFLARE_API_TOKEN ?? "";
    if (!token.trim()) problems.push("CLOUDFLARE_API_TOKEN is required with ACME_DNS=cloudflare: a Cloudflare API token with Zone:Read and DNS:Edit on the domain's zone");
    // The shapes Caddy's Cloudflare module accepts; anything else stops Caddy at start.
    else if (!/^[A-Za-z0-9_-]{35,50}$/.test(token) && !/^cf(ut|at)_[A-Za-z0-9_-]{32,256}$/.test(token)) {
      problems.push("CLOUDFLARE_API_TOKEN does not look like a Cloudflare API token: paste the token alone, without quotes, spaces or Bearer");
    }
  }

  // Caddy's debug log and an access log, for finding out why a certificate
  // will not come. Off by default: with it on, client addresses are written
  // to the container's log.
  const debug = onOff(env.CADDY_DEBUG, false, "CADDY_DEBUG", problems);

  let backup = null;
  if ((env.BACKUP ?? "").trim() !== "off") {
    const zone = (env.BACKUP_ZONE ?? "").trim();
    const accessKey = (env.BACKUP_ACCESS_KEY ?? "").trim();
    if (!zone) problems.push('BACKUP_ZONE is required: the Bunny Storage zone backups go to (or BACKUP=off to run without backups)');
    if (!accessKey) problems.push("BACKUP_ACCESS_KEY is required: that storage zone's password");
    let key = null;
    try {
      key = parseBackupKey(env.BACKUP_KEY);
    } catch (e) {
      problems.push(e.message);
    }
    const endpoint = (env.BACKUP_ENDPOINT ?? "https://storage.bunnycdn.com").trim().replace(/\/+$/, "");
    const minutes = Number(env.BACKUP_EVERY_MINUTES ?? "10");
    if (!Number.isInteger(minutes) || minutes < 5 || minutes > 1440) problems.push("BACKUP_EVERY_MINUTES must be a whole number from 5 to 1440");
    const path = (env.BACKUP_PATH ?? `clairix-relay/${domain}`).trim();
    if (!/^[A-Za-z0-9._/-]+$/.test(path) || path.split("/").some((p) => p === "." || p === "..")) problems.push("BACKUP_PATH may use letters, digits, . _ - and /");
    backup = { zone, accessKey, key, endpoint, path, everyMs: minutes * 60_000 };
  }

  if (problems.length) throw new Error(problems.map((p) => `  - ${p}`).join("\n"));
  return { domain, email, onion, tls, acmeDns, debug, backup, origin: (env.RELAY_ORIGIN ?? "").trim() || `https://${domain}` };
}

function onOff(value, fallback, name, problems) {
  const v = (value ?? "").trim();
  if (v === "") return fallback;
  if (v === "on") return true;
  if (v === "off") return false;
  problems.push(`${name} must be "on" or "off"`);
  return fallback;
}

/**
 * The Caddyfile: HTTPS for the domain, certificates kept on the volume, no
 * admin API and no access log (unless CADDY_DEBUG is on); the certificate
 * checked over DNS when ACME_DNS is set; the onion service's loopback port
 * when Tor is on.
 */
export function caddyfile({ domain, email, tls, acmeDns = "", debug = false, onionHost }, { relayPort, onionPort, dataDir }) {
  const lines = ["{", "\tadmin off", `\tstorage file_system {`, `\t\troot ${dataDir}`, "\t}"];
  if (email) lines.push(`\temail ${email}`);
  if (debug) lines.push("\tdebug");
  lines.push("}", "");
  if (debug) lines.push("# CADDY_DEBUG=on: every request, with its client address, goes to the container's log.");
  else lines.push("# No log directive: Caddy keeps no access log, so no address or request is written down.");
  lines.push(`${domain} {`);
  if (debug) lines.push("\tlog");
  if (tls === "internal") lines.push("\ttls internal");
  else if (acmeDns === "cloudflare") {
    // The token is read from Caddy's environment when it runs, so it is never
    // written into this file. Public resolvers for the propagation check, so a
    // host's own resolver cannot hide or cache the TXT record.
    lines.push("\ttls {", "\t\tdns cloudflare {env.CLOUDFLARE_API_TOKEN}", "\t\tresolvers 1.1.1.1 1.0.0.1", "\t}");
  }
  lines.push(`\treverse_proxy 127.0.0.1:${relayPort} {`);
  lines.push("\t\t# The relay's registration limit counts per address. Set here, so a client cannot choose its own.");
  lines.push("\t\theader_up CF-Connecting-IP {remote_host}");
  lines.push("\t}", "}");
  if (onionHost) {
    lines.push("");
    lines.push("# The onion service. Tor hands its connections to this loopback port; the Host the relay sees is");
    lines.push("# fixed, and no client address is passed on (there is none, and one a client sent would let it");
    lines.push("# pick its own registration limit).");
    lines.push(`http://:${onionPort} {`);
    lines.push("\tbind 127.0.0.1");
    lines.push(`\treverse_proxy 127.0.0.1:${relayPort} {`);
    lines.push(`\t\theader_up Host ${onionHost}`);
    lines.push("\t\theader_up -CF-Connecting-IP");
    lines.push("\t}", "}");
  }
  return lines.join("\n") + "\n";
}

/** The environment Caddy gets: its own folders, and the Cloudflare token only when DNS checks are on. */
export function caddyEnvironment(env, { acmeDns }, { dataDir, configDir }) {
  const out = { PATH: "/usr/bin:/bin", HOME: dataDir, XDG_DATA_HOME: dataDir, XDG_CONFIG_HOME: configDir };
  if (acmeDns === "cloudflare") out.CLOUDFLARE_API_TOKEN = env.CLOUDFLARE_API_TOKEN;
  return out;
}

/** The torrc: the onion service only, no SOCKS port, keys on the volume. */
export function torrc({ dataDir, serviceDir, onionPort }) {
  return [
    `DataDirectory ${dataDir}`,
    "SocksPort 0",
    `HiddenServiceDir ${serviceDir}`,
    "HiddenServiceVersion 3",
    `HiddenServicePort 80 127.0.0.1:${onionPort}`,
    "Log notice stdout",
    "",
  ].join("\n");
}

/** The environment workerd gets: the relay's own settings and nothing else (no backup secrets). */
export function relayEnvironment(env, settings, { origin, onion }) {
  const out = { PATH: "/usr/local/bin:/usr/bin:/bin", HOME: "/nonexistent" };
  for (const name of settings) if (env[name] !== undefined && env[name] !== "") out[name] = env[name];
  out.RELAY_ORIGIN = origin;
  if (onion) out.RELAY_ONION = `http://${onion}`;
  else delete out.RELAY_ONION;
  return out;
}
