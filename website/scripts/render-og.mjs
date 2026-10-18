#!/usr/bin/env node
// Renders the per-page Open Graph images (1200x630) into public/og/ from the
// brand OG template style and the lines in src/config/site.ts (ogImageText).
// The home image is the brand's own og-image.png, synced by sync-brand.mjs.
//
// Not part of the build: run it when the OG lines change, then commit the PNGs.
//   node scripts/render-og.mjs
// Needs Playwright with a Chromium (uses the global install if not local).
// Set CHROMIUM_PATH to point at a specific browser binary.

import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { mkdirSync, writeFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { ogImageText } from '../src/config/site.ts';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BRAND = path.resolve(ROOT, '..', 'brand');
const OUT = path.join(ROOT, 'public', 'og');
mkdirSync(OUT, { recursive: true });

const require = createRequire(import.meta.url);
let playwright;
try {
  playwright = require('playwright');
} catch {
  playwright = require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
}

const url = (p) => pathToFileURL(path.join(BRAND, p)).href;
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');

function html({ big, small }) {
  const lines = esc(big)
    .split('\n')
    .map((l) => l.replace(/([.])$/, '<em>$1</em>'))
    .join('<br>');
  return `<!doctype html><html data-theme="acid"><head><meta charset="utf-8">
<link rel="stylesheet" href="${url('tokens/tokens.css')}">
<style>
@font-face { font-family: "Archivo"; src: url("${url('fonts/archivo/web/Archivo[wdth,wght].woff2').replace('[', '%5B').replace(']', '%5D')}") format("woff2"); font-weight: 100 900; font-stretch: 62% 125%; font-display: block; }
html, body { margin: 0; }
body { width: 1200px; height: 630px; position: relative; overflow: hidden; background: var(--clx-base); color: var(--clx-text); font-family: var(--clx-font-ui); -webkit-font-smoothing: antialiased; }
.grid { position: absolute; inset: 0; background-image: linear-gradient(rgb(241 242 234 / .045) 1px, transparent 1px), linear-gradient(90deg, rgb(241 242 234 / .045) 1px, transparent 1px); background-size: 60px 60px; background-position: -1px -1px; }
.lock { position: absolute; left: 72px; top: 64px; height: 40px; }
.mark { position: absolute; right: 72px; top: 64px; width: 150px; }
.rule { position: absolute; left: 72px; top: 200px; width: 56px; height: 6px; background: var(--clx-accent); }
h1 { position: absolute; left: 68px; top: 240px; margin: 0; font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; text-transform: uppercase; line-height: .92; letter-spacing: -0.01em; font-size: 112px; white-space: nowrap; }
h1 em { font-style: normal; color: var(--clx-accent); }
.sub { position: absolute; left: 72px; bottom: 76px; margin: 0; font-size: 26px; line-height: 34px; color: var(--clx-text-muted); max-width: 760px; }
.url { position: absolute; right: 72px; bottom: 80px; font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 800; text-transform: uppercase; letter-spacing: .14em; font-size: 13px; color: var(--clx-text-muted); }
.url b { color: var(--clx-accent); }
</style></head><body>
<div class="grid"></div>
<img class="lock" src="${url('logo/wordmark-reversed.svg')}" alt="">
<img class="mark" src="${url('logo/symbol-reversed.svg')}" alt="">
<div class="rule"></div>
<h1>${lines}</h1>
<p class="sub">${esc(small)}</p>
<div class="url">clairix<b>.</b>app</div>
</body></html>`;
}

const browser = await playwright.chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage({ viewport: { width: 1200, height: 630 } });
for (const [slug, text] of Object.entries(ogImageText)) {
  const tmp = path.join(BRAND, 'tools', 'templates', `.og-${slug}-${process.pid}.html`);
  writeFileSync(tmp, html(text));
  try {
    await page.goto(pathToFileURL(tmp).href);
    await page.evaluate(() => document.fonts.ready);
    // Two lines at most: shrink the headline until it fits the canvas.
    await page.evaluate(() => {
      const h = document.querySelector('h1');
      let size = 112;
      while ((h.scrollWidth > 1060 || h.getBoundingClientRect().bottom > 500) && size > 40) h.style.fontSize = `${(size -= 2)}px`;
    });
    await page.screenshot({ path: path.join(OUT, `${slug}.png`) });
    console.log(`og/${slug}.png`);
  } finally {
    unlinkSync(tmp);
  }
}
await browser.close();
