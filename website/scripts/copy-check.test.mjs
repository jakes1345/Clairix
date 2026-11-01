// The website names only controls the app has (scripts/copy-check.mjs).
// Run with `npm test`; `npm run build` runs the check itself first.
import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { check, offences } from "./copy-check.mjs";

const site = fileURLToPath(new URL("..", import.meta.url));

test("no page names a control the app does not have", () => {
  assert.deepEqual(check(site).map((o) => `${o.file}:${o.line}: ${o.match}`), []);
});

test("the old names are caught in any case or spelling", () => {
  for (const line of [
    "Tap **Panic Wipe** and confirm.",
    "an app lock that can wipe, and a panic wipe.",
    "Panic-wipe now",
    "PANICWIPE",
    "3. Tap **Unpair** and confirm.",
    "Use Unpair or Panic Wipe first, or email us.",
    "Unpair deletes your relay account.",
    "| In-app Unpair / Panic Wipe |",
  ]) {
    assert.ok(offences(line).length > 0, line);
  }
});

test("unpairing something named, and the real controls, are left alone", () => {
  for (const line of [
    "It cannot switch Bluetooth off, unpair a device or change an accessory's address.",
    "Unpairing a Bluetooth accessory is done in Android's settings.",
    "unpaired devices stay in the list",
    "Comms → menu → Wipe Clairix, or Settings → Wipe Clairix; type WIPE",
    "Delete identity and number",
  ]) {
    assert.deepEqual(offences(line), [], line);
  }
});
