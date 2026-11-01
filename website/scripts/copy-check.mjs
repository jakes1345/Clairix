// Fails when the website names an app control that does not exist.
//
// Earlier pages sent people to "Panic Wipe" and "Unpair", names no edition of
// the app has had: someone erasing the app under pressure looked for a button
// that was not there. The controls are "Wipe Clairix" (Comms → menu, or
// Settings; type WIPE) and "Delete identity and number" (Settings → Wipe and
// delete, or Comms → Settings). Runs before every build and with `npm test`.
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
];

/** Files read as text; everything else (fonts, images) is skipped. */
const TEXT = new Set([".md", ".mdx", ".astro", ".ts", ".tsx", ".js", ".mjs", ".css", ".html", ".json", ".txt", ".svg", ".yml", ".yaml", ".xml"]);

/** What the site is built from and what it serves as is. */
const SCANNED = ["src", "public"];

/** Every offence in [text]: the rule broken, the line number, the words matched and the line. */
export function offences(text) {
  const out = [];
  text.split("\n").forEach((line, i) => {
    for (const rule of RULES) {
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
  for (const sub of SCANNED) {
    const dir = join(siteDir, sub);
    if (!statSync(dir, { throwIfNoEntry: false })?.isDirectory()) continue;
    for (const file of textFiles(dir)) {
      for (const o of offences(readFileSync(file, "utf8"))) found.push({ file: relative(siteDir, file), ...o });
    }
  }
  return found;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const site = fileURLToPath(new URL("..", import.meta.url));
  const found = check(site);
  if (found.length === 0) {
    console.log("copy-check: no page names a control the app does not have");
  } else {
    for (const o of found) console.error(`${o.file}:${o.line}: "${o.match}" (${o.rule}): ${o.hint}\n    ${o.text}`);
    console.error(`copy-check: ${found.length} ${found.length === 1 ? "place names" : "places name"} a control the app does not have`);
    process.exit(1);
  }
}
