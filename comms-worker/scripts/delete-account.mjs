// Deletes a Clairix account on a relay you operate, for a deletion requested by
// email (website: /delete-account; runbook: README.md, "Deleting an account on
// request"). It does what the owner's own wipe does at the relay: the account's
// keys are released and its mailbox (queued envelopes, push endpoint, pass key,
// coin balance and history) is wiped.
//
//   ADMIN_SECRET=… node scripts/delete-account.mjs https://relay.example 482913605
//   ADMIN_SECRET=… node scripts/delete-account.mjs https://relay.example "482 913 605"
//
// The secret is read from the environment, never from the command line, so it
// does not land in the shell's history or the process list. Exit codes: 0 the
// account was deleted, 3 there is no such account on that relay, 1 anything else.
const [relayArg, numberArg] = process.argv.slice(2);
const secret = process.env.ADMIN_SECRET ?? "";

function usage(message) {
  if (message) console.error(message);
  console.error("usage: ADMIN_SECRET=… node scripts/delete-account.mjs <relay https URL> <Clairix number>");
  process.exit(1);
}

if (!relayArg || !numberArg) usage();
if (secret.length < 16) usage("ADMIN_SECRET must be set to the relay's admin secret (16 characters at least).");

let relay;
try {
  relay = new URL(relayArg);
} catch {
  usage(`Not a URL: ${relayArg}`);
}
const local = relay.hostname === "localhost" || relay.hostname === "127.0.0.1" || relay.hostname === "[::1]";
if (relay.protocol !== "https:" && !(relay.protocol === "http:" && local)) {
  usage("The relay must be an https URL (http only for a relay on this machine): the admin secret travels with the request.");
}

const number = numberArg.replace(/\D/g, "");
if (!/^[1-9][0-9]{8}$/.test(number)) usage(`Not a Clairix number: ${numberArg} (nine digits, not starting with 0)`);

const target = new URL(`/v1/admin/accounts/${number}`, relay.origin);
let response;
for (;;) {
  try {
    response = await fetch(target, { method: "DELETE", headers: { "x-admin-secret": secret }, redirect: "error" });
  } catch (e) {
    console.error(`Could not reach ${relay.origin}: ${e.message}`);
    process.exit(1);
  }
  // A few requests a minute per address: wait as long as the relay says, then go on.
  if (response.status !== 429) break;
  const wait = Math.min(120, Number(response.headers.get("retry-after")) || 30);
  console.error(`The relay asks to wait ${wait} s before the next admin request.`);
  await new Promise((resolve) => setTimeout(resolve, wait * 1000));
}

let body = {};
try {
  body = await response.json();
} catch {
  // not JSON
}
const formatted = `${number.slice(0, 3)} ${number.slice(3, 6)} ${number.slice(6)}`;
if (response.ok) {
  console.log(`Deleted ${formatted} on ${relay.origin}.`);
  process.exit(0);
}
if (response.status === 404) {
  console.error(`No account ${formatted} on ${relay.origin}: nothing to delete (it may have been deleted already).`);
  process.exit(3);
}
const reason = body.error ?? response.statusText;
if (response.status === 403) console.error(`The relay refused the admin secret: ${reason}`);
else if (response.status === 503) console.error(`The relay cannot do this: ${reason} (is ADMIN_SECRET set on the relay?)`);
else console.error(`The relay answered ${response.status}: ${reason}`);
process.exit(1);
