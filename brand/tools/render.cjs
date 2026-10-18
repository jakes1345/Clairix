#!/usr/bin/env node
// Renders every Clairix PNG/ICO from the SVG and HTML sources with Playwright.
//
//   node brand/tools/render.cjs              previews + web/social assets
//   node brand/tools/render.cjs guidelines   also a full-page shot of guidelines.html
//
// Needs Playwright with Chromium (uses the global install if it is not a
// local dependency). Run build-logo.mjs and tokens/build.mjs first.

const fs = require('node:fs');
const path = require('node:path');
const { execSync } = require('node:child_process');

function loadPlaywright() {
  try {
    return require('playwright');
  } catch {
    const root = execSync('npm root -g').toString().trim();
    return require(path.join(root, 'playwright'));
  }
}
const { chromium } = loadPlaywright();

const BRAND = path.resolve(__dirname, '..');
const LOGO = path.join(BRAND, 'logo');
const PREV = path.join(BRAND, 'previews');
const ASSETS = path.join(BRAND, 'assets');
fs.mkdirSync(PREV, { recursive: true });
fs.mkdirSync(ASSETS, { recursive: true });

const tokens = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/tokens.json'), 'utf8'));
const C = tokens.color.primitive;
const INK = C.ink.hex, N950 = C.neutral['950'].hex, N50 = C.neutral['50'].hex;

const svgFile = (name) => fs.readFileSync(path.join(LOGO, name), 'utf8').replace(/<!--[\s\S]*?-->\n?/, '');
const sized = (svg, w, h = w) => svg.replace(/width="[^"]*" height="[^"]*"/, `width="${w}" height="${h}"`);

let browser;
async function shot(html, file, w, h, { transparent = false, scale = 1, fullPage = false, base = BRAND } = {}) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  const tmp = path.join(base, `.render-${process.pid}.html`);
  fs.writeFileSync(tmp, html);
  try {
    await page.goto('file://' + tmp);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: file, omitBackground: transparent, fullPage });
  } finally {
    fs.unlinkSync(tmp);
    await page.close();
  }
  return fs.readFileSync(file);
}
async function shotUrl(url, file, w, h, opts = {}) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: opts.scale || 1 });
  await page.goto(url);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(150);
  await page.screenshot({ path: file, fullPage: !!opts.fullPage });
  await page.close();
}
const page = (body, bg = 'transparent') =>
  `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;background:${bg}}body>svg,body>div{display:block}</style></head><body>${body}</body></html>`;

// Minimal ICO writer: PNG-compressed entries (supported since Windows Vista).
function ico(pngs) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(pngs.length, 4);
  const dir = Buffer.alloc(16 * pngs.length);
  let offset = 6 + dir.length;
  pngs.forEach(({ size, data }, i) => {
    const o = i * 16;
    dir.writeUInt8(size >= 256 ? 0 : size, o);
    dir.writeUInt8(size >= 256 ? 0 : size, o + 1);
    dir.writeUInt8(0, o + 2);
    dir.writeUInt8(0, o + 3);
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(data.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += data.length;
  });
  return Buffer.concat([header, dir, ...pngs.map((p) => p.data)]);
}

async function previews() {
  const sym = svgFile('symbol.svg'), symSmall = svgFile('symbol-small.svg');
  const symRev = svgFile('symbol-reversed.svg'), symSmallRev = svgFile('symbol-small-reversed.svg');
  // Single sizes, as the mark is meant to be used at each (small master below 40 px).
  for (const s of [16, 32, 48, 192, 1024]) {
    const src = s < 40 ? symSmall : sym;
    await shot(page(sized(src, s), N50), path.join(PREV, `symbol-${s}.png`), s, s);
  }
  // Contact sheet: each size at 1x, then the small ones blown up 8x with
  // nearest-neighbour scaling so pixel fit can be judged.
  const cells = [16, 32, 48, 192]
    .map((s) => `<div style="display:flex;align-items:flex-end;gap:28px">${sized(s < 40 ? symSmall : sym, s)}${sized(s < 40 ? symSmallRev : symRev, s).replace('<svg', `<svg style="background:${INK}"`)}</div>`)
    .join('');
  await shot(page(`<div style="display:flex;gap:56px;align-items:flex-end;padding:40px;font:13px sans-serif">${cells}</div>`, '#FFFFFF'), path.join(PREV, 'symbol-sizes.png'), 1060, 280);
  const zoom = [16, 32, 48]
    .map((s) => {
      const img = (bg, src) => `<img src="data:image/svg+xml;base64,${Buffer.from(sized(src, s)).toString('base64')}" style="width:${s}px;height:${s}px;background:${bg};display:block">`;
      return `<div style="display:flex;gap:4px">${img(N50, s < 40 ? symSmall : sym)}${img(INK, s < 40 ? symSmallRev : symRev)}</div>`;
    })
    .join('<div style="width:6px"></div>');
  // render at 1x, then upscale the bitmap
  const raw = await shot(page(`<div style="display:flex;align-items:flex-start;padding:4px">${zoom}</div>`, '#FFFFFF'), path.join(PREV, '.zoom-raw.png'), 220, 60);
  await shot(
    page(`<img src="data:image/png;base64,${raw.toString('base64')}" style="width:1760px;height:480px;image-rendering:pixelated;display:block">`, '#FFFFFF'),
    path.join(PREV, 'symbol-pixels-8x.png'), 1760, 480,
  );
  fs.unlinkSync(path.join(PREV, '.zoom-raw.png'));

  // Wordmark and lockups
  const pad = (svg, h, bg, padX = 80, padY = 64) => {
    const vb = svg.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
    const w = Math.round((h * +vb[1]) / +vb[2]);
    return { html: page(`<div style="padding:${padY}px ${padX}px">${sized(svg, w, h)}</div>`, bg), w: w + padX * 2, h: h + padY * 2 };
  };
  for (const [file, h, bg, out] of [
    ['wordmark.svg', 160, '#FFFFFF', 'wordmark.png'],
    ['lockup-horizontal.svg', 160, '#FFFFFF', 'lockup-horizontal.png'],
    ['lockup-horizontal-reversed.svg', 160, INK, 'lockup-horizontal-reversed.png'],
    ['lockup-stacked.svg', 420, '#FFFFFF', 'lockup-stacked.png'],
    ['lockup-stacked-reversed.svg', 420, INK, 'lockup-stacked-reversed.png'],
  ]) {
    const p = pad(svgFile(file), h, bg);
    await shot(p.html, path.join(PREV, out), p.w, p.h);
  }

  // Launcher icon as Android masks it: circle, squircle, rounded square, and
  // the themed (monochrome) icon, at 48 dp x 4 and at launcher size.
  const fgSvg = svgFile('app-icon-foreground.svg');
  const monoSvg = fgSvg.replace(/fill="#[0-9A-F]{6}"/g, 'fill="#D7E3FF"');
  const icon = (size, radius, ground, art) =>
    `<div style="width:${size}px;height:${size}px;border-radius:${radius};overflow:hidden;background:${ground};position:relative">
       <div style="position:absolute;left:${-size / 4}px;top:${-size / 4}px;width:${size * 1.5}px;height:${size * 1.5}px">${sized(art, size * 1.5)}</div></div>`;
  // the 108 dp canvas is shown through a 72 dp window: scale so window = size
  const iconCell = (size, radius, ground, art) => icon(size, radius, ground, art);
  const masks = [['50%'], ['32%'], ['22%']];
  let row = masks.map(([r]) => iconCell(192, r, INK, fgSvg)).join('');
  row += iconCell(192, '50%', '#1F2A44', monoSvg);
  const small = [48, 32].map((s) => iconCell(s, '50%', INK, fgSvg)).join('');
  await shot(
    page(`<div style="display:flex;gap:40px;align-items:center;padding:48px;background:linear-gradient(160deg,#DCE6F5,#EEF1F6)">${row}<div style="display:flex;gap:20px;align-items:center">${small}</div></div>`),
    path.join(PREV, 'launcher-icon.png'), 1100, 290,
  );

  // Hero render of the symbol for the owner
  await shot(page(`<div style="padding:160px">${sized(sym, 1024)}</div>`, '#FFFFFF'), path.join(PREV, 'symbol-hero.png'), 1344, 1344);
}

async function assets() {
  const smallLight = svgFile('symbol-small.svg');
  // favicon.svg: small master, follows the browser's colour scheme.
  const ring = smallLight.match(/<path fill="[^"]+" d="([^"]+)"/g).map((m) => m.match(/d="([^"]+)"/)[1]);
  const fav = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">
<style>.r{fill:${N950}}.g{fill:${C.blue['500'].hex}}@media (prefers-color-scheme:dark){.r{fill:#FFFFFF}.g{fill:${C.blue['400'].hex}}}</style>
<path class="r" d="${ring[0]}"/>
<path class="g" d="${ring[1]}"/>
</svg>
`;
  fs.writeFileSync(path.join(ASSETS, 'favicon.svg'), fav);

  // favicon.ico 16/32/48: the small master on a transparent ground (dark ink
  // reads on light tabs; on dark tabs browsers use favicon.svg).
  const pngs = [];
  for (const s of [16, 32, 48]) {
    const data = await shot(page(sized(s < 40 ? smallLight : svgFile('symbol.svg'), s)), path.join(ASSETS, `.f${s}.png`), s, s, { transparent: true });
    fs.unlinkSync(path.join(ASSETS, `.f${s}.png`));
    pngs.push({ size: s, data });
  }
  fs.writeFileSync(path.join(ASSETS, 'favicon.ico'), ico(pngs));

  // App icons: ink ground, symbol reversed.
  const rev = svgFile('symbol-reversed.svg');
  const tile = (size, inkFraction) => {
    const art = (size * inkFraction * 96) / 88;
    const off = (size - art) / 2;
    return page(`<div style="width:${size}px;height:${size}px;background:${INK};position:relative"><div style="position:absolute;left:${off}px;top:${off}px">${sized(rev, art)}</div></div>`);
  };
  // apple-touch-icon: iOS applies its own mask; keep the ink circle at 62%.
  await shot(tile(180, 0.62), path.join(ASSETS, 'apple-touch-icon.png'), 180, 180);
  await shot(tile(192, 0.62), path.join(ASSETS, 'icon-192.png'), 192, 192);
  // maskable: everything inside the 80% safe circle; ink circle at 56%.
  await shot(tile(512, 0.56), path.join(ASSETS, 'icon-512-maskable.png'), 512, 512);
  await shot(tile(1024, 0.6), path.join(ASSETS, 'app-icon-1024.png'), 1024, 1024);
  await shot(tile(512, 0.6), path.join(ASSETS, 'play-store-icon-512.png'), 512, 512);

  await shotUrl('file://' + path.join(BRAND, 'tools/templates/og-image.html'), path.join(ASSETS, 'og-image.png'), 1200, 630);
  await shotUrl('file://' + path.join(BRAND, 'tools/templates/play-feature-graphic.html'), path.join(ASSETS, 'play-feature-graphic.png'), 1024, 500);
}

async function guidelines() {
  await shotUrl('file://' + path.join(BRAND, 'guidelines.html'), path.join(PREV, 'guidelines-full.png'), 1440, 900, { fullPage: true });
  await shotUrl('file://' + path.join(BRAND, 'guidelines.html'), path.join(PREV, 'guidelines-mobile.png'), 390, 844, { fullPage: true });
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
  console.log('rendered to', path.relative(process.cwd(), PREV), 'and', path.relative(process.cwd(), ASSETS));
})();
