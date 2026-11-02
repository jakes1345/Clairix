// The website names only controls the app has (scripts/copy-check.mjs).
// Run with `npm test`; `npm run build` runs the check itself first.
import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { check, offences, playLive } from "./copy-check.mjs";

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

test("while the Play listing is not public, no page states Play distribution as fact", () => {
  for (const line of [
    "| **Distribution** | Google Play (package `app.clairix`); Clairix Full edition as a direct APK |",
    "This policy covers the Clairix app in both the Google Play edition and the Clairix Full edition.",
    "Google Play lists Clairix for adults.",
    "and Google Play for distribution of the Play edition.",
    "The Google Play edition is the version most people should install.",
  ]) {
    assert.ok(offences(line).length > 0, line);
    assert.deepEqual(offences(line, true), [], `once live: ${line}`);
  }
});

test("saying the Play edition is coming is left alone", () => {
  for (const line of [
    "The Google Play edition is coming soon. Requires Android 12 or newer.",
    "The Google Play edition is in preparation.",
    "It will cover the Google Play edition when that is published.",
    "| **Distribution** | Direct APK from clairix.app and GitHub Releases (public beta); a Google Play edition is in preparation |",
    "Android and Google Play are trademarks of Google LLC.",
  ]) {
    assert.deepEqual(offences(line), [], line);
  }
});

test("PLAY_LIVE is read from the site's config", () => {
  assert.equal(typeof playLive(site), "boolean");
  assert.equal(playLive("/nonexistent"), false);
});
