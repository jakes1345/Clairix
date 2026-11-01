// Fails when the website names an app control that does not exist, or speaks of
// the Google Play edition as published while it is not.
//
// Earlier pages sent people to "Panic Wipe" and "Unpair", names no edition of
// the app has had: someone erasing the app under pressure looked for a button
// that was not there. The controls are "Wipe Clairix" (Comms → menu, or
// Settings; type WIPE) and "Delete identity and number" (Settings → Wipe and
// delete, or Comms → Settings). Runs before every build and with `npm test`.
//
// The Play rule applies only while PLAY_LIVE in src/config/site.ts is false: the
// press sheet, privacy policy and terms once stated Play distribution as fact
// while every button on the site said "coming soon".
//
//   node scripts/copy-check.mjs     exit 0 when clean; 1, with every offence listed, when not
import { readdirSync, readFileSync, statSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const RULES = [
  {
    name: "Panic Wipe",
    hint: "the control is Wipe Clairix (Comms → menu → Wipe Clairix, or Settings → Wipe Clairix; type WIPE)",
    re: /\bpanic[\s-]*wipe\b/gi,
  },
  {
    name: "Unpair, with nothing said of what is unpaired",
    hint: "the control is Delete identity and number (Settings → Wipe and delete, or Comms → Settings); \"unpair a device\" is fine",
    re: /\bunpair(?:s|ed|ing)?\b(?!\s+(?:a|an|the|your|this|that|its|their|it|them|devices?|accessor(?:y|ies)|headphones|earbuds|phones?)\b)/gi,
  },
  {
    name: "Google Play stated as live",
    hint: "PLAY_LIVE is false: say the Play edition is coming, or gate the sentence on PLAY_LIVE",
    re: /\bGoogle Play (?:lists|for distribution)\b|\bGoogle Play edition is (?!coming|on its way|in preparation|not\b)|\bboth the Google Play edition\b|\*\*Distribution\*\*\s*\|\s*Google Play\b/gi,
    onlyWhilePlayIsNotLive: true,
  },
];

/** Whether src/config/site.ts at [siteDir] sets PLAY_LIVE to true; false when it cannot be read. */
export function playLive(siteDir) {
  let text;
  try {
    text = readFileSync(join(siteDir, "src", "config", "site.ts"), "utf8");
  } catch {
    return false;
  }
  return /export const PLAY_LIVE\s*=\s*true\b/.test(text);
}

/** Files read as text; everything else (fonts, images) is skipped. */
const TEXT = new Set([".md", ".mdx", ".astro", ".ts", ".tsx", ".js", ".mjs", ".css", ".html", ".json", ".txt", ".svg", ".yml", ".yaml", ".xml"]);

/** What the site is built from and what it serves as is. */
const SCANNED = ["src", "public"];

/**
 * Every offence in [text]: the rule broken, the line number, the words matched and the line.
 * [isPlayLive] switches the Play rule off once the listing is public.
 */
export function offences(text, isPlayLive = false) {
  const out = [];
  const rules = RULES.filter((rule) => !(rule.onlyWhilePlayIsNotLive && isPlayLive));
  text.split("\n").forEach((line, i) => {
    for (const rule of rules) {
      for (const m of line.matchAll(rule.re)) {
        out.push({ rule: rule.name, hint: rule.hint, line: i + 1, match: m[0], text: line.trim() });
      }
    }
  });
  return out;
}

/** Every text file under [dir], however deep. */
export function textFiles(dir) {
  const out = [];
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...textFiles(path));
    else if (entry.isFile() && TEXT.has(extname(entry.name).toLowerCase())) out.push(path);
  }
  return out;
}

/** Every offence in the website at [siteDir], each with the file it is in. */
export function check(siteDir) {
  const found = [];
  const live = playLive(siteDir);
  for (const sub of SCANNED) {
    const dir = join(siteDir, sub);
    if (!statSync(dir, { throwIfNoEntry: false })?.isDirectory()) continue;
    for (const file of textFiles(dir)) {
      for (const o of offences(readFileSync(file, "utf8"), live)) found.push({ file: relative(siteDir, file), ...o });
    }
  }
  return found;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const site = fileURLToPath(new URL("..", import.meta.url));
  const found = check(site);
  if (found.length === 0) {
    console.log("copy-check: no page names a control the app does not have or calls the Play edition published");
  } else {
    for (const o of found) console.error(`${o.file}:${o.line}: "${o.match}" (${o.rule}): ${o.hint}\n    ${o.text}`);
    console.error(`copy-check: ${found.length} ${found.length === 1 ? "place breaks" : "places break"} the rules above`);
    process.exit(1);
  }
}
