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

  let tls = (env.CADDY_TLS ?? "acme").trim();
  if (tls !== "acme" && tls !== "internal") problems.push('CADDY_TLS must be "acme" (the default) or "internal"');
  // With a Cloudflare token, certificates are proved over DNS (a TXT record in
  // the domain's Cloudflare zone), so the certificate authority never has to
  // reach this container: for hosts whose network does not carry its checks.
  const cloudflareToken = (env.CLOUDFLARE_API_TOKEN ?? "").trim();
  if (cloudflareToken && !/^[A-Za-z0-9_-]{20,}$/.test(cloudflareToken)) problems.push("CLOUDFLARE_API_TOKEN does not look like a Cloudflare API token");
  if (cloudflareToken && tls === "acme") tls = "cloudflare";

  // Caddy's debug log and an access log, for finding out why a certificate
  // will not come. The access log writes down addresses: turn it off again.
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
  return { domain, email, onion, tls, cloudflareToken, debug, backup, origin: (env.RELAY_ORIGIN ?? "").trim() || `https://${domain}` };
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
 * admin API and no access log (unless debugging); the onion service's
 * loopback port when Tor is on. With tls "cloudflare", certificates are
 * proved over DNS with the token Caddy is given as CLOUDFLARE_API_TOKEN.
 */
export function caddyfile({ domain, email, tls, onionHost, debug = false }, { relayPort, onionPort, dataDir }) {
  const lines = ["{", "\tadmin off", `\tstorage file_system {`, `\t\troot ${dataDir}`, "\t}"];
  if (email) lines.push(`\temail ${email}`);
  if (debug) lines.push("\tdebug");
  lines.push("}", "");
  if (debug) lines.push("# CADDY_DEBUG=on: every request is logged, addresses included, until it is turned off.");
  else lines.push("# No log directive: Caddy keeps no access log, so no address or request is written down.");
  lines.push(`${domain} {`);
  if (debug) lines.push("\tlog");
  if (tls === "internal") lines.push("\ttls internal");
  if (tls === "cloudflare") lines.push("\ttls {", "\t\tdns cloudflare {env.CLOUDFLARE_API_TOKEN}", "\t}");
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

/** The container's own secrets: never the relay's, whatever its settings list says. */
const CONTAINER_SECRETS = new Set(["BACKUP_KEY", "BACKUP_ACCESS_KEY", "CLOUDFLARE_API_TOKEN"]);

/** The environment workerd gets: the relay's own settings and nothing else (no backup secrets). */
export function relayEnvironment(env, settings, { origin, onion }) {
  const out = { PATH: "/usr/local/bin:/usr/bin:/bin", HOME: "/nonexistent" };
  for (const name of settings) {
    if (CONTAINER_SECRETS.has(name)) continue;
    if (env[name] !== undefined && env[name] !== "") out[name] = env[name];
  }
  out.RELAY_ORIGIN = origin;
  if (onion) out.RELAY_ONION = `http://${onion}`;
  else delete out.RELAY_ONION;
  return out;
}
