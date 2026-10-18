#!/usr/bin/env node
// Builds brand/guidelines.html from the logo files, tokens.json and
// contrast.json, so the page can never drift from the sources.
//
//   node brand/tools/build-guidelines.mjs
//
// Run after build_logo.py and tokens/build.mjs.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BRAND = path.resolve(HERE, '..');
const tokens = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/tokens.json'), 'utf8'));
const report = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/contrast.json'), 'utf8'));
const T = tokens.color.themes;
const A = T.acid;
const INK = A.base.hex, BONE = A.text.hex, ACID = A.accent.hex;

const logo = (name, style = '') =>
  fs.readFileSync(path.join(BRAND, 'logo', name), 'utf8')
    .replace(/<!--[\s\S]*?-->\n?/, '')
    .replace(/<title>Clairix<\/title>\n?/, '')
    .replace(/ width="[^"]*" height="[^"]*"/, style ? ` style="${style}"` : '')
    .trim();
const hex = (theme, role) => {
  let v = T[theme][role];
  while (typeof v === 'string') v = T[theme][v];
  return v.hex;
};

// ------------------------------------------------------------ misuse
const sym = logo('symbol-reversed.svg');
const misuse = [
  ['Don’t recolour it. The X is always the theme-free acid green; status colours never go in the logo.', sym.replace(ACID, '#FF3B3B')],
  ['Don’t remove or move the slit. It is the cut that runs through every part of the logo.', sym.replace('</svg>', `<rect x="-1" y="46.5" width="25" height="7" fill="${BONE}"/><rect x="77" y="20" width="23" height="6" fill="${INK}"/></svg>`)],
  ['Don’t stretch, squash or skew it.', `<div style="transform:scaleX(1.35) skewX(-8deg)">${sym}</div>`],
  ['Don’t add glows, gradients or outlines to the mark.', `<div style="filter:drop-shadow(0 0 14px ${ACID})">${sym.replace(BONE, 'url(#g1)').replace('<path', `<defs><linearGradient id="g1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#7A7F70"/></linearGradient></defs><path`)}</div>`],
  ['Don’t retype the wordmark. It is drawn, not typed.', `<span class="bad-type">Clairix</span>`],
  ['Don’t rearrange the lockup or break the shared slit line.', `<div class="bad-lock">${logo('wordmark-reversed.svg', 'width:150px')}${logo('symbol-reversed.svg', 'width:46px;margin-top:-30px')}</div>`],
  ['Don’t put the acid X on white without its ink keyline: use symbol.svg.', sym.replace(BONE, INK), 'light'],
  ['Don’t place it on acid green or busy, low-contrast grounds.', sym, 'acid'],
];

// ------------------------------------------------------------ themes
const roleSw = (t, role, label) =>
  `<div class="sw"><i style="background:${hex(t, role)}"></i><b>${label}</b><code>${hex(t, role)}</code></div>`;
function themeCard(t) {
  const min = Math.min(...report.themes[t].map((r) => r.ratio));
  return `<article class="theme" data-theme="${t}">
  <div class="phone">
    <div class="ph-top"><span class="ph-title">Scan</span><span class="ph-pill">Live</span></div>
    <div class="ph-hero"><div class="ph-num">3</div><div><b>Things found</b><span>Living room · 2 min ago</span></div></div>
    <div class="ph-row danger"><i></i><div><b>Hidden camera</b><span>Smoke alarm, IR glow</span></div><em>Check</em></div>
    <div class="ph-row caution"><i></i><div><b>Tracker near you</b><span>40 min · 3 places</span></div><em>Find</em></div>
    <div class="ph-row safe"><i></i><div><b>Wi-Fi is clean</b><span>No interception</span></div><em>OK</em></div>
    <button class="ph-btn">Scan again</button>
  </div>
  <div class="theme-info">
    <div class="theme-name">${T[t].label}${t === tokens.color.defaultTheme ? '<small>Default</small>' : ''}</div>
    <p>${T[t].description}</p>
    <div class="sws">
      ${roleSw(t, 'base', 'Base')}${roleSw(t, 'surface', 'Surface')}${roleSw(t, 'surfaceRaised', 'Raised')}${roleSw(t, 'border', 'Border')}
      ${roleSw(t, 'text', 'Text')}${roleSw(t, 'textMuted', 'Muted')}${roleSw(t, 'accent', 'Accent')}${roleSw(t, 'onAccent', 'On accent')}
      ${roleSw(t, 'safe', 'Safe')}${roleSw(t, 'caution', 'Caution')}${roleSw(t, 'danger', 'Danger')}${roleSw(t, 'info', 'Info')}
    </div>
    <div class="aa">${report.themes[t].length} text pairs checked · lowest ${min.toFixed(2)}:1 · all AA</div>
  </div>
</article>`;
}

// ------------------------------------------------------------ type scale
const typeRows = Object.entries(tokens.font.scale)
  .filter(([k]) => !k.startsWith('$'))
  .map(([k, t]) => {
    const fam = { display: 'var(--clx-font-display)', ui: 'var(--clx-font-ui)', mono: 'var(--clx-font-mono)' }[t.family];
    const stretch = t.family === 'display' ? '125%' : '100%';
    const sample = t.family === 'mono' ? 'D4:3A:2E:91:0C:7F'
      : t.family === 'display' ? (k.startsWith('display') ? 'Nothing hides.' : 'Tracker on you')
      : k.startsWith('title') ? 'Tracker near you' : k.startsWith('body') ? 'Seen in 3 places over 40 minutes. Tap to make it ring.' : 'Scan room';
    return `<div class="ts"><div class="ts-meta"><b>${k}</b><span>${t.size}/${t.lineHeight} · ${t.weight} · ${t.family}${t.case === 'upper' ? ' · caps' : ''}${t.web ? ' · web' : ''}</span></div>
<div class="ts-sample" style="font-family:${fam};font-stretch:${stretch};font-size:min(${t.size}px, ${t.family === 'display' ? '9vw' : '100vw'});line-height:${t.family === 'display' ? '1.05' : t.lineHeight + 'px'};font-weight:${t.weight};letter-spacing:${t.tracking}em;${t.case === 'upper' ? 'text-transform:uppercase' : ''}">${sample}</div></div>`;
  })
  .join('\n');

// ------------------------------------------------------------ merch drawings
const hoodie = (print) => `<svg viewBox="0 0 600 640" class="garment">
<path d="M205 132C198 72 240 26 300 26S402 72 395 132Z" fill="#141414"/>
<path d="M60 470L98 222C104 184 122 160 152 148L205 128C232 150 266 164 300 164S368 150 395 128L448 148C478 160 496 184 502 222L540 470L546 560L492 566L482 482L452 312L450 604H150L148 312L118 482L108 566L54 560Z" fill="#171717"/>
<path d="M232 128C236 84 262 60 300 60S364 84 368 128C350 152 326 164 300 164S250 152 232 128Z" fill="#0B0B0B"/>
<path d="M226 142L152 312M374 142L448 312" stroke="#262626" stroke-width="2.5" fill="none"/>
<path d="M150 600H450V628Q450 636 442 636H158Q150 636 150 628Z" fill="#121212"/>
<path d="M206 468H394L424 596H176Z" fill="none" stroke="#272727" stroke-width="2.5"/>
<path d="M284 160L278 262M316 160L322 262" stroke="#D9D9D4" stroke-width="3.5" stroke-linecap="round"/>
<foreignObject x="140" y="196" width="320" height="150"><div xmlns="http://www.w3.org/1999/xhtml" style="width:320px;height:150px;display:grid;place-items:center">${print}</div></foreignObject>
</svg>`;
const tee = (print, fabric = '#F1F0EA', seam = '#D9D8D0') => `<svg viewBox="0 0 600 640" class="garment">
<path d="M222 70C246 92 270 100 300 100S354 92 378 70L470 104L560 196L506 262L452 222L456 600H144L148 222L94 262L40 196L130 104Z" fill="${fabric}"/>
<path d="M222 70C246 92 270 100 300 100S354 92 378 70" fill="none" stroke="${seam}" stroke-width="8"/>
<path d="M148 222L130 104M452 222L470 104" stroke="${seam}" stroke-width="2.5" fill="none"/>
<foreignObject x="190" y="150" width="220" height="200"><div xmlns="http://www.w3.org/1999/xhtml" style="width:220px;height:200px;display:grid;place-items:center">${print}</div></foreignObject>
</svg>`;
const cap = (patch) => `<svg viewBox="0 0 600 640" class="garment">
<path d="M120 380C120 230 200 150 300 150S480 230 480 380Z" fill="#161616"/>
<path d="M120 380C240 360 360 360 480 380C540 390 590 420 580 446C470 430 330 420 120 432C100 420 104 390 120 380Z" fill="#101010"/>
<path d="M300 150V378M210 172C250 230 262 300 262 376M390 172C350 230 338 300 338 376" stroke="#222" stroke-width="2.5" fill="none"/>
<circle cx="300" cy="152" r="9" fill="#1E1E1E"/>
<foreignObject x="215" y="214" width="170" height="160"><div xmlns="http://www.w3.org/1999/xhtml" style="width:170px;height:160px;display:grid;place-items:center">${patch}</div></foreignObject>
</svg>`;
const patch = `<div class="patch">${logo('symbol-reversed.svg', 'width:92px')}</div>`;

// ------------------------------------------------------------ page
const html = `<!doctype html>
<html lang="en" data-theme="acid">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Clairix Brand Book</title>
<meta name="description" content="Clairix logo, themes, type, voice and merch.">
<link rel="icon" href="assets/favicon.svg" type="image/svg+xml">
<link rel="icon" href="assets/favicon.ico" sizes="16x16 32x32 48x48">
<link rel="stylesheet" href="tokens/tokens.css">
<!-- Generated by brand/tools/build-guidelines.mjs. Edit that script, not this file. -->
<style>
  @font-face { font-family: "Archivo"; src: url("fonts/archivo/web/Archivo%5Bwdth,wght%5D.woff2") format("woff2"); font-weight: 100 900; font-stretch: 62% 125%; font-display: swap; }
  @font-face { font-family: "JetBrains Mono"; src: url("fonts/jetbrains-mono/web/JetBrainsMono-Regular.woff2") format("woff2"); font-weight: 400; font-display: swap; }
  @font-face { font-family: "JetBrains Mono"; src: url("fonts/jetbrains-mono/web/JetBrainsMono-Medium.woff2") format("woff2"); font-weight: 500; font-display: swap; }

  *, *::before, *::after { box-sizing: border-box; }
  html { scroll-behavior: smooth; -webkit-text-size-adjust: 100%; }
  @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
  body { margin: 0; background: var(--clx-base); color: var(--clx-text); font-family: var(--clx-font-ui); font-size: 16px; line-height: 1.6; -webkit-font-smoothing: antialiased; }
  a { color: var(--clx-accent); }
  code { font-family: var(--clx-font-mono); font-size: .86em; }
  p { margin: 0; text-wrap: pretty; }
  svg, img { display: block; max-width: 100%; }
  .disp { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; text-transform: uppercase; letter-spacing: -0.005em; line-height: .95; margin: 0; }
  .disp em { font-style: normal; color: var(--clx-accent); }
  .label { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 800; text-transform: uppercase; letter-spacing: .14em; font-size: 12px; color: var(--clx-text-muted); }
  .label b { color: var(--clx-accent); }

  .wrap { max-width: 1240px; margin: 0 auto; padding: 0 48px; }
  .grid-bg { background-image: linear-gradient(rgb(241 242 234 / .04) 1px, transparent 1px), linear-gradient(90deg, rgb(241 242 234 / .04) 1px, transparent 1px); background-size: 56px 56px; }
  header.top { position: sticky; top: 0; z-index: 5; background: rgb(10 11 9 / .86); backdrop-filter: blur(10px); border-bottom: 1px solid var(--clx-border); }
  header.top .wrap { display: flex; align-items: center; justify-content: space-between; height: 64px; gap: 24px; }
  header.top nav { display: flex; gap: 22px; overflow-x: auto; scrollbar-width: none; }
  header.top nav a { color: var(--clx-text-muted); text-decoration: none; font-size: 13px; font-weight: 600; white-space: nowrap; }
  header.top nav a:hover { color: var(--clx-text); }

  .hero { padding: 120px 0 96px; border-bottom: 1px solid var(--clx-border); }
  .hero .lock { width: min(860px, 100%); }
  .hero h1 { font-size: clamp(44px, 9vw, 120px); margin-top: 72px; }
  .hero .intro { display: grid; grid-template-columns: 1fr 1fr; gap: 48px; margin-top: 48px; color: var(--clx-text-muted); font-size: 18px; line-height: 1.6; max-width: 980px; }
  .hero .intro b { color: var(--clx-text); }

  section { padding: 104px 0; border-bottom: 1px solid var(--clx-border); }
  .sec-head { display: grid; grid-template-columns: 180px 1fr; gap: 24px; margin-bottom: 48px; }
  .sec-head .label { padding-top: 10px; }
  .sec-head h2 { font-size: clamp(30px, 4.6vw, 56px); }
  .sec-head p { margin-top: 18px; color: var(--clx-text-muted); font-size: 17px; max-width: 720px; }
  h3 { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 800; text-transform: uppercase; font-size: 15px; letter-spacing: .06em; margin: 56px 0 18px; }
  .body { color: var(--clx-text-muted); max-width: 760px; }
  .body b { color: var(--clx-text); }

  .tile { position: relative; border-radius: 6px; border: 1px solid var(--clx-border); background: var(--clx-surface); display: grid; place-items: center; overflow: hidden; min-height: 240px; padding: 40px; }
  .tile.ink { background: ${INK}; }
  .tile.light { background: #FFFFFF; border-color: #FFFFFF; }
  .tile.acid { background: ${ACID}; border-color: ${ACID}; }
  .tile .tag { position: absolute; left: 14px; bottom: 10px; font-family: var(--clx-font-mono); font-size: 11px; color: var(--clx-text-subtle); }
  .tile.light .tag { color: #6A6D63; }
  .g2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
  .g3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
  .g4 { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }

  .anatomy { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 48px; align-items: center; }
  .anatomy ol { margin: 0; padding: 0; list-style: none; display: grid; gap: 22px; counter-reset: a; }
  .anatomy li { counter-increment: a; display: grid; grid-template-columns: 44px 1fr; color: var(--clx-text-muted); }
  .anatomy li::before { content: counter(a, decimal-leading-zero); font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; color: var(--clx-accent); }
  .anatomy li b { color: var(--clx-text); display: block; }

  .space { position: relative; outline: 1px dashed var(--clx-accent); padding: var(--u); }
  .space::after { content: ""; position: absolute; inset: var(--u); outline: 1px solid rgb(200 255 31 / .35); }
  .space .u { position: absolute; left: 0; top: 0; width: var(--u); height: var(--u); background: rgb(200 255 31 / .14); display: grid; place-items: center; font-family: var(--clx-font-mono); font-size: 11px; color: var(--clx-accent); }
  .sizes { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 28px; align-items: end; padding: 40px; border: 1px solid var(--clx-border); border-radius: 6px; background: var(--clx-surface); }
  .sizes figure { margin: 0; display: grid; gap: 12px; justify-items: start; }
  .sizes figcaption { font-size: 13px; color: var(--clx-text-muted); line-height: 1.45; }
  .sizes figcaption b { display: block; color: var(--clx-text); }

  .misuse { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
  .misuse figure { margin: 0; }
  .misuse .tile { min-height: 190px; padding: 30px; }
  .misuse .tile > svg, .misuse .tile > div:not(.bad-lock) { width: 92px; }
  .misuse .tile::after { content: ""; position: absolute; top: 12px; right: 12px; width: 24px; height: 24px; background: #FF3B3B url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M7 7l10 10M17 7L7 17' stroke='black' stroke-width='3'/%3E%3C/svg%3E") center/24px no-repeat; clip-path: polygon(25% 0, 75% 0, 100% 25%, 100% 75%, 75% 100%, 25% 100%, 0 75%, 0 25%); }
  .misuse figcaption { margin-top: 10px; font-size: 14px; color: var(--clx-text-muted); line-height: 1.45; }
  .bad-type { font-family: "Arial Black", Impact, sans-serif; font-size: 34px; font-style: italic; color: ${BONE}; }
  .bad-lock { display: flex; align-items: flex-start; gap: 8px; }

  .theme { display: grid; grid-template-columns: 300px 1fr; gap: 32px; padding: 28px; border: 1px solid var(--clx-border); border-radius: 8px; background: var(--clx-base); }
  .theme + .theme { margin-top: 14px; }
  .phone { background: var(--clx-base); border: 1px solid var(--clx-border); border-radius: 22px; padding: 18px 16px; display: grid; gap: 10px; font-size: 13px; color: var(--clx-text); }
  .ph-top { display: flex; justify-content: space-between; align-items: center; }
  .ph-title { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; text-transform: uppercase; font-size: 20px; }
  .ph-pill { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .08em; padding: 3px 9px; border-radius: 3px; background: var(--clx-accent-container); color: var(--clx-on-accent-container); }
  .ph-hero { display: flex; gap: 14px; align-items: center; padding: 14px; background: var(--clx-surface); border-radius: 6px; }
  .ph-num { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; font-size: 42px; line-height: 1; color: var(--clx-accent); }
  .ph-hero b, .ph-row b { display: block; font-weight: 600; }
  .ph-hero span, .ph-row span { color: var(--clx-text-muted); font-size: 12px; }
  .ph-row { display: grid; grid-template-columns: 8px 1fr auto; gap: 12px; align-items: center; padding: 10px 12px; background: var(--clx-surface-raised); border-radius: 6px; }
  .ph-row i { width: 8px; height: 30px; border-radius: 2px; }
  .ph-row.danger i { background: var(--clx-danger); } .ph-row.caution i { background: var(--clx-caution); } .ph-row.safe i { background: var(--clx-safe); }
  .ph-row em { font-style: normal; font-size: 12px; font-weight: 700; }
  .ph-row.danger em { color: var(--clx-danger); } .ph-row.caution em { color: var(--clx-caution); } .ph-row.safe em { color: var(--clx-safe); }
  .ph-btn { margin-top: 4px; height: 42px; border: 0; border-radius: 4px; background: var(--clx-accent); color: var(--clx-on-accent); font: 700 14px var(--clx-font-ui); text-transform: uppercase; letter-spacing: .06em; }
  .theme-name { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; font-size: 34px; text-transform: uppercase; color: var(--clx-accent); display: flex; align-items: baseline; gap: 14px; }
  .theme-name small { font-size: 11px; letter-spacing: .14em; color: var(--clx-text-muted); }
  .theme-info > p { color: var(--clx-text-muted); margin: 8px 0 20px; }
  .sws { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px 16px; }
  .sw { display: grid; grid-template-columns: 30px 1fr; column-gap: 10px; align-items: center; font-size: 12px; }
  .sw i { grid-row: span 2; width: 30px; height: 30px; border-radius: 4px; box-shadow: inset 0 0 0 1px rgb(255 255 255 / .12); }
  .sw b { color: var(--clx-text); font-weight: 600; }
  .sw code { color: var(--clx-text-muted); font-size: 11px; }
  .aa { margin-top: 18px; font-size: 12px; color: var(--clx-text-muted); font-family: var(--clx-font-mono); }

  .fonts { display: grid; grid-template-columns: 1.3fr 1fr 1fr; gap: 14px; }
  .font-card { padding: 28px; border: 1px solid var(--clx-border); border-radius: 6px; background: var(--clx-surface); display: grid; gap: 12px; align-content: start; }
  .font-card .aa-big { font-size: 96px; line-height: 1; }
  .font-card h4 { margin: 0; font-size: 16px; }
  .font-card p { color: var(--clx-text-muted); font-size: 14px; }
  .ts { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 24px; padding: 18px 0; border-bottom: 1px solid var(--clx-border); align-items: baseline; }
  .ts-meta { display: grid; font-size: 12px; color: var(--clx-text-muted); }
  .ts-meta b { color: var(--clx-text); font-family: var(--clx-font-mono); font-weight: 500; }
  .ts-sample { overflow-wrap: break-word; }

  .principles { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
  .principle { padding: 24px; border: 1px solid var(--clx-border); border-radius: 6px; background: var(--clx-surface); }
  .principle b { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 900; text-transform: uppercase; font-size: 18px; display: block; margin-bottom: 8px; }
  .principle p { color: var(--clx-text-muted); font-size: 15px; }
  .taglines { display: grid; gap: 4px; }
  .taglines .main { font-size: clamp(40px, 7vw, 88px); }
  .taglines ul { list-style: none; margin: 24px 0 0; padding: 0; display: flex; flex-wrap: wrap; gap: 10px; }
  .taglines li { font-family: var(--clx-font-display); font-stretch: 125%; font-weight: 800; text-transform: uppercase; font-size: 15px; padding: 10px 16px; border: 1px solid var(--clx-border-strong); border-radius: 3px; }
  .sd { display: grid; grid-template-columns: 170px minmax(0, 1fr) minmax(0, 1fr); border: 1px solid var(--clx-border); border-radius: 6px; overflow: hidden; background: var(--clx-surface); }
  .sd + .sd { margin-top: 10px; }
  .sd > div { padding: 16px 18px; font-size: 15px; }
  .sd .ctx { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--clx-text-muted); background: var(--clx-surface-raised); }
  .sd .say { border-left: 3px solid var(--clx-safe); }
  .sd .dont { border-left: 3px solid var(--clx-danger); color: var(--clx-text-muted); }
  .sd .say::before, .sd .dont::before { display: block; font-size: 11px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; margin-bottom: 4px; }
  .sd .say::before { content: "We say"; color: var(--clx-safe); }
  .sd .dont::before { content: "Not this"; color: var(--clx-danger); }
  .words { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
  .words > div { padding: 24px; border: 1px solid var(--clx-border); border-radius: 6px; background: var(--clx-surface); }
  .words ul { margin: 14px 0 0; padding: 0; list-style: none; display: flex; flex-wrap: wrap; gap: 8px; }
  .words li { padding: 4px 11px; border-radius: 3px; background: var(--clx-surface-overlay); font-size: 14px; }
  .words .no li { text-decoration: line-through; text-decoration-color: var(--clx-danger); color: var(--clx-text-muted); }
  .rules { margin: 0; padding: 0; list-style: none; display: grid; gap: 10px; max-width: 820px; }
  .rules li { padding-left: 26px; position: relative; color: var(--clx-text-muted); }
  .rules li::before { content: ""; position: absolute; left: 2px; top: 9px; width: 10px; height: 10px; background: var(--clx-accent); clip-path: polygon(30% 0, 70% 0, 100% 30%, 100% 70%, 70% 100%, 30% 100%, 0 70%, 0 30%); }
  .rules b { color: var(--clx-text); }

  .merch { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
  .merch figure { margin: 0; }
  .merch .tile { min-height: 300px; padding: 18px; background: linear-gradient(180deg, #D8D8D3, #C3C3BD); border: 0; }
  .merch .garment { width: 100%; height: 280px; }
  .merch figcaption { margin-top: 10px; font-size: 14px; color: var(--clx-text-muted); }
  .merch figcaption b { color: var(--clx-text); display: block; }
  .patch { width: 150px; height: 150px; background: ${INK}; display: grid; place-items: center; clip-path: polygon(24% 0, 76% 0, 100% 24%, 100% 76%, 76% 100%, 24% 100%, 0 76%, 0 24%); box-shadow: inset 0 0 0 4px ${BONE}; }
  .sticker { width: min(220px, 82%); padding: 22px 20px; background: ${INK}; border-radius: 4px; transform: rotate(-4deg); box-shadow: 0 0 0 6px #fff, 0 12px 24px rgb(0 0 0 / .25); }
  .specs { margin-top: 28px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
  .specs > div { padding: 22px; border: 1px solid var(--clx-border); border-radius: 6px; background: var(--clx-surface); font-size: 14px; color: var(--clx-text-muted); }
  .specs b { display: block; color: var(--clx-text); margin-bottom: 6px; }

  .files { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
  .files > div { padding: 22px 24px; border: 1px solid var(--clx-border); border-radius: 6px; background: var(--clx-surface); }
  .files h4 { margin: 0 0 8px; font-size: 14px; }
  .files ul { margin: 0; padding: 0; list-style: none; font-family: var(--clx-font-mono); font-size: 12.5px; color: var(--clx-text-muted); display: grid; gap: 4px; }
  footer { padding: 48px 0 72px; }
  footer .wrap { display: flex; justify-content: space-between; gap: 24px; flex-wrap: wrap; align-items: center; }

  @media (max-width: 1000px) {
    .wrap { padding: 0 28px; }
    .misuse, .merch { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .g4 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .theme { grid-template-columns: 1fr; }
    .phone { max-width: 340px; }
    .fonts { grid-template-columns: 1fr; }
  }
  @media (max-width: 680px) {
    .wrap { padding: 0 16px; }
    header.top nav { display: none; }
    .hero { padding: 64px 0 56px; }
    .hero h1 { margin-top: 40px; }
    .hero .intro, .anatomy, .g2, .g3, .principles, .words, .files, .specs { grid-template-columns: 1fr; }
    section { padding: 72px 0; }
    .sec-head { grid-template-columns: 1fr; gap: 8px; margin-bottom: 32px; }
    .sws { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .ts { grid-template-columns: 1fr; gap: 6px; }
    .sd { grid-template-columns: 1fr; }
    .sd .say, .sd .dont { border-left: 0; border-top: 3px solid; }
    .sd .say { border-top-color: var(--clx-safe); } .sd .dont { border-top-color: var(--clx-danger); }
    .tile { padding: 28px; min-height: 200px; }
    .misuse .tile > svg, .misuse .tile > div:not(.bad-lock) { width: 70px; }
    .bad-type { font-size: 24px; }
    .sizes { padding: 24px; }
  }
</style>
</head>
<body>
<header class="top"><div class="wrap">
  ${logo('wordmark-reversed.svg', 'height:18px;width:auto')}
  <nav aria-label="Sections"><a href="#mark">Mark</a><a href="#logo">Logo</a><a href="#space">Space</a><a href="#misuse">Misuse</a><a href="#themes">Themes</a><a href="#type">Type</a><a href="#voice">Voice</a><a href="#merch">Merch</a><a href="#files">Files</a></nav>
</div></header>

<div class="hero grid-bg"><div class="wrap">
  <div class="label">Clairix <b>/</b> Brand book 2.0 <b>/</b> Acid Stencil</div>
  <div style="margin-top:40px">${logo('lockup-horizontal-reversed.svg', 'width:100%;height:auto')}</div>
  <h1 class="disp">Nothing hides<em>.</em></h1>
  <div class="intro">
    <p><b>Clairix is counter-surveillance you’d actually wear.</b> It finds hidden cameras, trackers following you, fake cell towers, rogue Wi-Fi and spyware, and keeps your calls and messages private.</p>
    <p>The brand looks like it belongs on a hoodie and talks like a friend who knows security: <b>confident, direct, a little attitude</b>, and always clear about what to do next.</p>
  </div>
</div></div>

<section id="mark"><div class="wrap">
  <div class="sec-head"><div class="label">01 <b>/</b> Mark</div><div><h2 class="disp">C locks the X<em>.</em></h2>
  <p>The X is the thing that was hiding: the camera, the tracker, the spyware. The C closes around it. One stencil slit cuts straight through, like light through a visor.</p></div></div>
  <div class="anatomy">
    <div class="tile ink" style="min-height:440px">${logo('symbol-reversed.svg', 'width:min(340px,80%)')}</div>
    <ol>
      <li><div><b>Octagon C</b>Cut corners at 24% of the width. Tactical, not round. The same chamfer appears in tags, badges and buttons.</div></li>
      <li><div><b>The X</b>Two straight bars with flat terminals, always in acid green. The only colour in the logo.</div></li>
      <li><div><b>The slit</b>One horizontal cut through the C’s spine. In the wordmark it runs through every letter, and in the lockup the two cuts line up into one.</div></li>
      <li><div><b>The mouth</b>Opens to the right with slanted cuts that pass exactly through the counter’s corners. Open, not broken.</div></li>
    </ol>
  </div>
</div></section>

<section id="logo"><div class="wrap">
  <div class="sec-head"><div class="label">02 <b>/</b> Logo</div><div><h2 class="disp">Lockups and versions</h2>
  <p>Light on dark is the primary logo. Use the horizontal lockup by default, the stacked lockup in tall spaces, and the mark alone where the name is already known: icon, patch, avatar.</p></div></div>
  <div class="tile ink" style="min-height:320px">${logo('lockup-horizontal-reversed.svg', 'width:min(760px,90%)')}<span class="tag">lockup-horizontal-reversed.svg · primary</span></div>
  <div class="g3" style="margin-top:14px">
    <div class="tile ink">${logo('lockup-stacked-reversed.svg', 'width:62%')}<span class="tag">lockup-stacked-reversed.svg</span></div>
    <div class="tile ink">${logo('symbol-reversed.svg', 'width:42%')}<span class="tag">symbol-reversed.svg</span></div>
    <div class="tile ink">${logo('wordmark-reversed.svg', 'width:78%')}<span class="tag">wordmark-reversed.svg</span></div>
  </div>
  <h3>On light grounds</h3>
  <p class="body">Ink C and wordmark. The acid X keeps a 2.5-unit ink keyline so it holds its shape on white. These are the only files allowed on light grounds.</p>
  <div class="g2" style="margin-top:18px">
    <div class="tile light">${logo('lockup-horizontal.svg', 'width:84%')}<span class="tag">lockup-horizontal.svg</span></div>
    <div class="tile light">${logo('symbol.svg', 'width:30%')}<span class="tag">symbol.svg</span></div>
  </div>
  <h3>One colour</h3>
  <p class="body">For embroidery, engraving, one-colour screen print, stamps and laser etching.</p>
  <div class="g4" style="margin-top:18px">
    <div class="tile light">${logo('symbol-mono-black.svg', 'width:46%')}<span class="tag">symbol-mono-black.svg</span></div>
    <div class="tile ink">${logo('symbol-mono-white.svg', 'width:46%')}<span class="tag">symbol-mono-white.svg</span></div>
    <div class="tile light">${logo('lockup-stacked-mono-black.svg', 'width:66%')}<span class="tag">lockup-stacked-mono-black.svg</span></div>
    <div class="tile ink">${logo('lockup-stacked-mono-white.svg', 'width:66%')}<span class="tag">lockup-stacked-mono-white.svg</span></div>
  </div>
  <h3>App icon</h3>
  <p class="body">The mark on Ink, every theme, every time. On Android it is an adaptive icon: the mark is 50 dp wide and all its corners sit inside the 66 dp safe circle. The themed (monochrome) layer uses the same shapes.</p>
  <div class="g4" style="margin-top:18px">
    ${['50%', '30%', '18%'].map((r) => `<div class="tile" style="background:linear-gradient(150deg,#3A3F33,#16180F)"><div style="width:132px;height:132px;border-radius:${r};overflow:hidden">${logo('app-icon.svg', 'width:198px;height:198px;max-width:none;margin:-33px')}</div></div>`).join('')}
    <div class="tile" style="background:linear-gradient(150deg,#3A3F33,#16180F)"><div style="width:132px;height:132px;border-radius:50%;overflow:hidden;background:#2E3326">${logo('app-icon-foreground.svg', 'width:198px;height:198px;max-width:none;margin:-33px').replace(/fill="#[0-9A-Fa-f]{6}"/g, 'fill="#DDE5C8"')}</div><span class="tag">Themed icon</span></div>
  </div>
</div></section>

<section id="space"><div class="wrap">
  <div class="sec-head"><div class="label">03 <b>/</b> Space and size</div><div><h2 class="disp">Room to breathe</h2>
  <p>Clear space is one <b style="color:var(--clx-text)">C</b>: the thickness of the C’s stroke, 23% of the mark’s height. Keep it clear on every side of every version. On merch, double it.</p></div></div>
  <div class="g2">
    <div class="tile ink" style="min-height:360px"><div class="space" style="--u:46px"><div style="width:200px">${logo('symbol-reversed.svg')}</div><span class="u">C</span></div></div>
    <div class="tile ink" style="min-height:360px"><div class="space" style="--u:24px"><div style="width:min(440px,60vw)">${logo('lockup-horizontal-reversed.svg')}</div><span class="u">C</span></div></div>
  </div>
  <h3>Minimum sizes</h3>
  <div class="sizes">
    <figure>${logo('symbol-small-reversed.svg', 'width:16px')}<figcaption><b>Mark 16 px</b>symbol-small from 16 to 39 px. Print 6 mm.</figcaption></figure>
    <figure>${logo('symbol-reversed.svg', 'width:40px')}<figcaption><b>Mark 40 px and up</b>Regular master. Embroidery 25 mm.</figcaption></figure>
    <figure>${logo('lockup-horizontal-reversed.svg', 'width:120px')}<figcaption><b>Horizontal 120 px</b>Below this the slit closes. Print 30 mm.</figcaption></figure>
    <figure>${logo('lockup-stacked-reversed.svg', 'width:72px')}<figcaption><b>Stacked 72 px</b>Print 20 mm.</figcaption></figure>
    <figure>${logo('wordmark-reversed.svg', 'width:96px')}<figcaption><b>Wordmark 96 px</b>Print 24 mm.</figcaption></figure>
  </div>
</div></section>

<section id="misuse"><div class="wrap">
  <div class="sec-head"><div class="label">04 <b>/</b> Misuse</div><div><h2 class="disp">Don’t touch it</h2>
  <p>The logo is drawn to the unit. Use the files as supplied.</p></div></div>
  <div class="misuse">
    ${misuse.map(([t, s, g]) => `<figure><div class="tile ${g || 'ink'}">${s}</div><figcaption>${t}</figcaption></figure>`).join('\n    ')}
  </div>
</div></section>

<section id="themes"><div class="wrap">
  <div class="sec-head"><div class="label">05 <b>/</b> Themes</div><div><h2 class="disp">One brand, four themes</h2>
  <p>People pick the colour theme in the app. Acid is the default and the brand’s own colour. Infrared, Hazard and Volt change the interface colours only: <b style="color:var(--clx-text)">the logo, the app icon and all marketing stay Acid.</b></p></div></div>
  <p class="body" style="margin-bottom:24px">${tokens.color.$mode.replace(/^Dark-only by design\./, "<b>Dark only, by design.</b>")} Every theme defines the same ${Object.keys(A).length - 2} roles, and the token build fails if any text pair drops below 4.5:1.</p>
  ${Object.keys(T).map(themeCard).join('\n')}
</div></section>

<section id="type"><div class="wrap">
  <div class="sec-head"><div class="label">06 <b>/</b> Type</div><div><h2 class="disp">Archivo, wide and normal</h2>
  <p>One family, two widths. Archivo Expanded shouts in headlines and on merch; Archivo at normal width does the reading. They share one skeleton, so the loud parts and the quiet parts always match. Archivo was built for screen and print, has sturdy open shapes and clear figures at 12 to 14 px, and it is SIL Open Font License, so the app, site and merch use the same files.</p></div></div>
  <div class="fonts">
    <div class="font-card"><div class="aa-big disp">Aa<em>.</em></div><h4>Archivo Expanded · ExtraBold, Black</h4><p>Display, headlines, big numbers, labels. Always capitals. Width 125.</p></div>
    <div class="font-card"><div class="aa-big" style="font-weight:600">Aa</div><h4>Archivo · 400 to 700</h4><p>Body, buttons, lists, settings. Sentence case. Width 100.</p></div>
    <div class="font-card"><div class="aa-big" style="font-family:var(--clx-font-mono);font-weight:500;letter-spacing:-.04em">Aa</div><h4>JetBrains Mono</h4><p>Machine values only: MAC addresses, cell IDs, fingerprints.</p></div>
  </div>
  <h3>Scale</h3>
  <div>${typeRows}</div>
</div></section>

<section id="voice"><div class="wrap">
  <div class="sec-head"><div class="label">07 <b>/</b> Voice</div><div><h2 class="disp">Talk like you’ve got this</h2>
  <p>Confident, direct, a little attitude. The attitude lives in headlines and taglines. Instructions stay plain and calm, because the person reading them might be scared. We never use fear to sell, and we are never mean.</p></div></div>
  <div class="taglines">
    <div class="label">Primary tagline</div>
    <div class="disp main">Nothing hides<em>.</em></div>
    <ul><li>See it first.</li><li>Check the room.</li><li>Found. Flagged. Handled.</li><li>Your phone answers to you.</li><li>Watch the watchers.</li></ul>
  </div>
  <h3>Principles</h3>
  <div class="principles">
    <div class="principle"><b>Confident</b><p>We know this stuff, so we say it straight. No hedging, no jargon walls.</p></div>
    <div class="principle"><b>Direct</b><p>Short sentences. What happened, what it means, what to do. Then stop.</p></div>
    <div class="principle"><b>Some attitude</b><p>We’re on your side, against whoever is watching. A little swagger, never smug.</p></div>
    <div class="principle"><b>Clear</b><p>Plain words. “Tracker”, not “BLE beacon”. Details are a tap away.</p></div>
    <div class="principle"><b>No fear</b><p>No panic, no all-caps alarms, no “act now or else”. Calm people act better.</p></div>
    <div class="principle"><b>Never mean</b><p>We never shame, blame or label people. We name the device, not the person.</p></div>
  </div>
  <h3>We say / Not this</h3>
  <div class="sd"><div class="ctx">Tracker following</div><div class="say">Tracker on you. Seen in 3 places over 40 minutes. Make it ring to find it.</div><div class="dont">SOMEONE IS STALKING YOU!!!</div></div>
  <div class="sd"><div class="ctx">Room is clear</div><div class="say">Room’s clear. Nothing hiding here. Scan again if you switch rooms.</div><div class="dont">You’re 100% safe. Unhackable.</div></div>
  <div class="sd"><div class="ctx">Keylogger keyboard</div><div class="say">This keyboard reads every key you press. Swap it out in 2 taps.</div><div class="dont">You’ve been hacked. Your life is exposed.</div></div>
  <div class="sd"><div class="ctx">Fake cell tower</div><div class="say">Sketchy cell tower nearby. Your Clairix calls stay encrypted.</div><div class="dont">IMSI catcher detected! Calls intercepted!</div></div>
  <div class="sd"><div class="ctx">Panic wipe</div><div class="say">Wipe Clairix data now? There’s no undo.</div><div class="dont">Burn it all and disappear.</div></div>
  <div class="sd"><div class="ctx">Pro upsell</div><div class="say">Pro scans in the background. You get on with your day.</div><div class="dont">Upgrade or stay exposed.</div></div>
  <h3>Words</h3>
  <div class="words">
    <div><b>We use</b><ul><li>found</li><li>flagged</li><li>clear</li><li>locked</li><li>check</li><li>swap</li><li>on you</li><li>nearby</li><li>yours</li><li>handled</li></ul></div>
    <div class="no"><b>We avoid</b><ul><li>you’ve been hacked</li><li>victim</li><li>stalker (for a person)</li><li>100% secure</li><li>unhackable</li><li>military-grade</li><li>paranoid</li><li>act now</li><li>threat actor</li></ul></div>
  </div>
  <h3>Rules</h3>
  <ul class="rules">
    <li><b>Attitude up top, clarity below.</b> Headline can swagger; the instruction under it is calm and exact.</li>
    <li><b>Capitals only in display type and labels.</b> Never in alerts or body text.</li>
    <li><b>One exclamation mark per year.</b> Ideally zero.</li>
    <li><b>Buttons are verbs:</b> Scan room, Find it, Swap keyboard, Wipe now.</li>
    <li><b>Numbers as digits:</b> 40 minutes, 3 places, 2 taps.</li>
    <li><b>Never promise total safety.</b> We say what we found and what we checked.</li>
  </ul>
</div></section>

<section id="merch"><div class="wrap">
  <div class="sec-head"><div class="label">08 <b>/</b> Merch</div><div><h2 class="disp">Built to be worn</h2>
  <p>Merch is where the brand gets loud. Black garments first, bone and acid ink, the slit always intact.</p></div></div>
  <div class="merch">
    <figure><div class="tile">${hoodie(logo('lockup-horizontal-reversed.svg', 'width:300px'))}</div><figcaption><b>Hoodie, chest print</b>Horizontal lockup, 24 cm wide, centred 9 cm below the collar.</figcaption></figure>
    <figure><div class="tile">${tee(logo('lockup-stacked.svg', 'width:150px'))}</div><figcaption><b>White tee</b>Positive stacked lockup with the keylined X. Never the reversed files on white.</figcaption></figure>
    <figure><div class="tile">${cap(patch)}</div><figcaption><b>Cap, woven patch</b>Octagon patch, mark in bone and acid thread on Ink, 45 mm.</figcaption></figure>
    <figure><div class="tile" style="background:linear-gradient(180deg,#EDEDE8,#DADAD4)"><div class="sticker">${logo('wordmark-reversed.svg', 'width:100%')}</div></div><figcaption><b>Sticker</b>Wordmark on Ink with a 6 mm white die-cut border.</figcaption></figure>
  </div>
  <div class="specs">
    <div><b>Screen print</b>Bone ink (match ${BONE}) and fluorescent green for the X (start from Pantone 802 C, approve against ${ACID} by drawdown). Max 2 colours plus garment.</div>
    <div><b>Embroidery</b>Mark only, 25 mm minimum, slit at least 1 mm. Two thread colours. Below 25 mm use the one-colour mark.</div>
    <div><b>Placement</b>Chest, left chest (mark, 7 cm), back yoke (wordmark, 28 cm) or sleeve (wordmark running down, 4 cm high). Keep 2C clear space on fabric.</div>
  </div>
</div></section>

<section id="files"><div class="wrap">
  <div class="sec-head"><div class="label">09 <b>/</b> Files</div><div><h2 class="disp">Everything, sourced</h2>
  <p>Everything is generated from source. Change the source and rebuild; never edit outputs by hand.</p></div></div>
  <div class="files">
    <div><h4>brand/logo</h4><ul><li>symbol[-reversed|-mono-black|-mono-white].svg</li><li>symbol-small[...].svg (16–39 px)</li><li>wordmark[...].svg</li><li>lockup-horizontal[...].svg</li><li>lockup-stacked[...].svg</li><li>app-icon.svg, app-icon-foreground.svg</li></ul></div>
    <div><h4>brand/assets</h4><ul><li>favicon.svg, favicon.ico (16/32/48)</li><li>apple-touch-icon.png, icon-192.png</li><li>icon-512-maskable.png, play-store-icon-512.png</li><li>app-icon-1024.png</li><li>og-image.png (1200×630)</li><li>play-feature-graphic.png (1024×500)</li><li>social-banner-1920x1080.png</li></ul></div>
    <div><h4>brand/tokens</h4><ul><li>tokens.json (source, 4 themes)</li><li>tokens.css ([data-theme])</li><li>contrast.json</li><li>build.mjs → also writes ClairixTheme.kt and res/font</li></ul></div>
    <div><h4>brand/tools</h4><ul><li>build_logo.py (logo + launcher layers)</li><li>instance-fonts.py</li><li>render.cjs (PNG, ICO)</li><li>build-guidelines.mjs</li><li>templates/ (OG, Play, banner)</li></ul></div>
  </div>
</div></section>

<footer><div class="wrap">${logo('lockup-horizontal-reversed.svg', 'width:200px')}<span class="label">Nothing hides<b>.</b> · clairix<b>.</b>app</span></div></footer>
</body>
</html>
`;
fs.writeFileSync(path.join(BRAND, 'guidelines.html'), html);
console.log('guidelines: wrote brand/guidelines.html');
