#!/usr/bin/env node
// Renders every Clairix PNG/ICO from the SVG and HTML sources with Playwright.
//
//   node brand/tools/render.cjs              previews + web, store and social assets
//   node brand/tools/render.cjs guidelines   also full-page shots of guidelines.html
//
// Run python3 brand/tools/build_logo.py and node brand/tokens/build.mjs first.
// Uses a local Playwright if installed, otherwise the global one.

const fs = require('node:fs');
const path = require('node:path');
const { execSync } = require('node:child_process');

function loadPlaywright() {
  try { return require('playwright'); } catch {
    return require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
  }
}
const { chromium } = loadPlaywright();

const BRAND = path.resolve(__dirname, '..');
const LOGO = path.join(BRAND, 'logo');
const PREV = path.join(BRAND, 'previews');
const ASSETS = path.join(BRAND, 'assets');
const TPL = path.join(__dirname, 'templates');
fs.mkdirSync(PREV, { recursive: true });
fs.mkdirSync(ASSETS, { recursive: true });

const tokens = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/tokens.json'), 'utf8'));
const A = tokens.color.themes.acid;
const INK = A.base.hex, BONE = A.text.hex;

const svgFile = (name) => fs.readFileSync(path.join(LOGO, name), 'utf8').replace(/<!--[\s\S]*?-->\n?/, '');
const sized = (svg, w, h = w) => svg.replace(/width="[^"]*" height="[^"]*"/, `width="${w}" height="${h}"`);
const page = (body, bg = 'transparent') =>
  `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;background:${bg}}svg,img{display:block}</style></head><body>${body}</body></html>`;

let browser;
async function shot(html, file, w, h, { transparent = false, fullPage = false } = {}) {
  const p = await browser.newPage({ viewport: { width: w, height: h } });
  const tmp = path.join(TPL, `.render-${process.pid}.html`);
  fs.writeFileSync(tmp, html);
  try {
    await p.goto('file://' + tmp);
    await p.evaluate(() => document.fonts.ready);
    await p.screenshot({ path: file, omitBackground: transparent, fullPage });
  } finally {
    fs.unlinkSync(tmp);
    await p.close();
  }
  return fs.readFileSync(file);
}
async function shotUrl(file, out, w, h, fullPage = false) {
  const p = await browser.newPage({ viewport: { width: w, height: h } });
  await p.goto('file://' + file);
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(150);
  await p.screenshot({ path: out, fullPage });
  await p.close();
}

// PNG-compressed ICO (Windows Vista and later, every current browser).
function ico(pngs) {
  const head = Buffer.alloc(6);
  head.writeUInt16LE(1, 2);
  head.writeUInt16LE(pngs.length, 4);
  const dir = Buffer.alloc(16 * pngs.length);
  let off = 6 + dir.length;
  pngs.forEach(({ size, data }, i) => {
    const o = i * 16;
    dir.writeUInt8(size % 256, o);
    dir.writeUInt8(size % 256, o + 1);
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(data.length, o + 8);
    dir.writeUInt32LE(off, o + 12);
    off += data.length;
  });
  return Buffer.concat([head, dir, ...pngs.map((p) => p.data)]);
}

const symbolFor = (px, variant = 'reversed') =>
  svgFile(`symbol${px < 40 ? '-small' : ''}${variant === 'positive' ? '' : '-' + variant}.svg`);

async function previews() {
  for (const s of [16, 32, 48, 192, 1024]) {
    await shot(page(`<div style="padding:${Math.round(s / 8)}px">${sized(symbolFor(s), s)}</div>`, INK),
      path.join(PREV, `symbol-${s}.png`), s + 2 * Math.round(s / 8), s + 2 * Math.round(s / 8));
  }
  // 1x renders of the small sizes, blown up 8x with nearest-neighbour scaling
  // to judge pixel fit; dark and light grounds.
  const cell = (s, v, bg) =>
    `<img src="data:image/svg+xml;base64,${Buffer.from(sized(symbolFor(s, v), s)).toString('base64')}" style="width:${s}px;height:${s}px;background:${bg};padding:2px">`;
  const strip = [16, 32, 48].map((s) => cell(s, 'reversed', INK) + cell(s, 'positive', '#FFFFFF')).join('<i style="width:4px"></i>');
  const raw = await shot(page(`<div style="display:flex;gap:2px;align-items:flex-start;padding:2px">${strip}</div>`, '#777'), path.join(PREV, '.raw.png'), 228, 56);
  fs.unlinkSync(path.join(PREV, '.raw.png'));
  await shot(page(`<img src="data:image/png;base64,${raw.toString('base64')}" style="width:1824px;height:448px;image-rendering:pixelated">`, '#777'),
    path.join(PREV, 'symbol-pixels-8x.png'), 1824, 448);

  const pad = (file, h, bg, px = 96, py = 80) => {
    const svg = svgFile(file);
    const [, vw, vh] = svg.match(/viewBox="[-\d.]+ [-\d.]+ ([\d.]+) ([\d.]+)"/).map(Number);
    const w = Math.round((h * vw) / vh);
    return [page(`<div style="padding:${py}px ${px}px">${sized(svg, w, h)}</div>`, bg), w + 2 * px, h + 2 * py];
  };
  for (const [file, h, bg, out] of [
    ['wordmark-reversed.svg', 140, INK, 'wordmark.png'],
    ['lockup-horizontal-reversed.svg', 180, INK, 'lockup-horizontal.png'],
    ['lockup-horizontal.svg', 180, '#FFFFFF', 'lockup-horizontal-positive.png'],
    ['lockup-stacked-reversed.svg', 460, INK, 'lockup-stacked.png'],
  ]) {
    const [html, w, hh] = pad(file, h, bg);
    await shot(html, path.join(PREV, out), w, hh);
  }

  // Launcher icon under Android's masks (the 108 dp canvas seen through its
  // 72 dp window), the themed icon, and real phone sizes.
  const fg = svgFile('app-icon-foreground.svg');
  const mono = fg.replace(/fill="#[0-9A-Fa-f]{6}"/g, 'fill="#DDE5C8"');
  const icon = (size, radius, ground, art) =>
    `<div style="width:${size}px;height:${size}px;border-radius:${radius};overflow:hidden;background:${ground};position:relative;flex:none">
      <div style="position:absolute;left:${-size / 4}px;top:${-size / 4}px">${sized(art, size * 1.5)}</div></div>`;
  const big = ['50%', '30%', '18%'].map((r) => icon(192, r, INK, fg)).join('') + icon(192, '50%', '#2E3326', mono);
  const phone = [72, 48].map((s) => icon(s, '50%', INK, fg)).join('');
  await shot(page(`<div style="display:flex;gap:44px;align-items:center;padding:56px;background:linear-gradient(150deg,#3A3F33,#16180F)">${big}<div style="display:flex;gap:22px;align-items:center">${phone}</div></div>`),
    path.join(PREV, 'launcher-icon.png'), 1224, 304);
}

async function assets() {
  // favicon.svg: small master on an Ink tile so it reads on any tab colour.
  const small = svgFile('symbol-small-reversed.svg');
  const inner = small.replace(/^[\s\S]*?<title>Clairix<\/title>\n?/, '').replace('</svg>', '').trim();
  fs.writeFileSync(path.join(ASSETS, 'favicon.svg'),
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-8 -8 116 116">\n<rect x="-8" y="-8" width="116" height="116" rx="14" fill="${INK}"/>\n${inner}\n</svg>\n`);
  const pngs = [];
  for (const s of [16, 32, 48]) {
    const f = path.join(ASSETS, `.f${s}.png`);
    const data = await shot(page(`<img src="favicon.svg" style="width:${s}px;height:${s}px">`.replace('favicon.svg', 'file://' + path.join(ASSETS, 'favicon.svg'))), f, s, s, { transparent: true });
    fs.unlinkSync(f);
    pngs.push({ size: s, data });
  }
  fs.writeFileSync(path.join(ASSETS, 'favicon.ico'), ico(pngs));

  const rev = svgFile('symbol-reversed.svg');
  const tile = (size, frac) => {
    const art = size * frac;
    return page(`<div style="width:${size}px;height:${size}px;background:${INK};display:grid;place-items:center">${sized(rev, art)}</div>`);
  };
  await shot(tile(180, 0.58), path.join(ASSETS, 'apple-touch-icon.png'), 180, 180); // iOS masks to its own squircle
  await shot(tile(192, 0.58), path.join(ASSETS, 'icon-192.png'), 192, 192);
  await shot(tile(512, 0.5), path.join(ASSETS, 'icon-512-maskable.png'), 512, 512); // corners within the 80% safe circle
  await shot(tile(512, 0.58), path.join(ASSETS, 'play-store-icon-512.png'), 512, 512);
  await shot(tile(1024, 0.58), path.join(ASSETS, 'app-icon-1024.png'), 1024, 1024);

  await shotUrl(path.join(TPL, 'og-image.html'), path.join(ASSETS, 'og-image.png'), 1200, 630);
  await shotUrl(path.join(TPL, 'play-feature-graphic.html'), path.join(ASSETS, 'play-feature-graphic.png'), 1024, 500);
  await shotUrl(path.join(TPL, 'social-banner.html'), path.join(ASSETS, 'social-banner-1920x1080.png'), 1920, 1080);
}

async function guidelines() {
  await shotUrl(path.join(BRAND, 'guidelines.html'), path.join(PREV, 'guidelines-full.png'), 1440, 900, true);
  await shotUrl(path.join(BRAND, 'guidelines.html'), path.join(PREV, 'guidelines-mobile.png'), 390, 844, true);
}

(async () => {
  browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  try {
    if (process.argv.includes('guidelines-only')) await guidelines();
    else {
      await previews();
      await assets();
      if (process.argv.includes('guidelines')) await guidelines();
    }
  } finally {
    await browser.close();
  }
  console.log('rendered to brand/previews and brand/assets');
})();
