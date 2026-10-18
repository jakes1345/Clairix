#!/usr/bin/env node
// Copies the brand system from ../brand into the website so the site never
// carries its own copy of colours, type, logos or icons.
//
//   brand/tokens/tokens.css      -> src/styles/tokens.css
//   brand/logo/*.svg             -> src/assets/brand/
//   brand/assets/favicons, icons -> public/
//   brand/assets/og-image.png    -> public/og/home.png
//   brand/fonts (woff2)          -> public/fonts/ (subset to Latin, axes kept)
//
// Runs automatically before `npm run dev`, `npm run build` and `npm run check`.
// Every output is git-ignored: edit the brand source, never these copies.

import { mkdir, readFile, writeFile, copyFile, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BRAND = path.resolve(ROOT, '..', 'brand');

if (!existsSync(BRAND)) {
  console.error(`[sync-brand] Brand folder not found at ${BRAND}. Build from a full checkout of the repository.`);
  process.exit(1);
}

const out = (...p) => path.join(ROOT, ...p);
const src = (...p) => path.join(BRAND, ...p);
await Promise.all(['src/styles', 'src/assets/brand', 'public/fonts', 'public/og'].map((d) => mkdir(out(d), { recursive: true })));

// 1. Tokens: verbatim copy with a provenance line.
const tokens = await readFile(src('tokens/tokens.css'), 'utf8');
await writeFile(out('src/styles/tokens.css'), `/* Synced from brand/tokens/tokens.css by scripts/sync-brand.mjs. Do not edit. */\n${tokens}`);

// 2. Logos: every SVG, comments stripped.
for (const f of (await readdir(src('logo'))).filter((f) => f.endsWith('.svg'))) {
  const svg = (await readFile(src('logo', f), 'utf8')).replace(/<!--[\s\S]*?-->\s*/g, '');
  await writeFile(out('src/assets/brand', f), svg);
}

// 3. Icons and social images.
const assets = {
  'favicon.svg': 'favicon.svg',
  'favicon.ico': 'favicon.ico',
  'apple-touch-icon.png': 'apple-touch-icon.png',
  'icon-192.png': 'icon-192.png',
  'icon-512-maskable.png': 'icon-512-maskable.png',
  'og-image.png': 'og/home.png',
};
for (const [from, to] of Object.entries(assets)) await copyFile(src('assets', from), out('public', to));

// 4. Fonts. Subset to Latin (keeping the wdth and wght axes) when the
//    subsetter is available; otherwise ship the brand files unchanged.
const LATIN =
  ' !"#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~' +
  ' ¡¢£¤¥¦§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿ' +
  'ŒœŠšŸŽžƒˆ˜–—‘’‚“”„†‡•…‰‹›€™←↑→↓−✓';
let subsetFont = null;
try { subsetFont = (await import('subset-font')).default; } catch { /* optional */ }

async function font(from, to, variationAxes) {
  const buf = await readFile(from);
  if (subsetFont) {
    try {
      const sub = await subsetFont(buf, LATIN, { targetFormat: 'woff2', ...(variationAxes ? { variationAxes } : {}) });
      await writeFile(to, sub);
      return `${path.basename(to)} ${(buf.length / 1024).toFixed(0)} KB -> ${(sub.length / 1024).toFixed(0)} KB`;
    } catch (e) {
      console.warn(`[sync-brand] subset failed for ${path.basename(from)}, copying as is: ${e.message}`);
    }
  }
  await writeFile(to, buf);
  return `${path.basename(to)} ${(buf.length / 1024).toFixed(0)} KB (unsubset)`;
}

const report = [
  await font(src('fonts/archivo/web/Archivo[wdth,wght].woff2'), out('public/fonts/archivo.woff2')),
  await font(src('fonts/jetbrains-mono/web/JetBrainsMono-Regular.woff2'), out('public/fonts/jetbrains-mono.woff2')),
];
console.log(`[sync-brand] tokens, logos, icons synced. Fonts: ${report.join('; ')}`);
