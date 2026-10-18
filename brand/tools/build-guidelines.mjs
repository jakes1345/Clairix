#!/usr/bin/env node
// Builds brand/guidelines.html from the logo files, tokens.json and
// contrast.json, so the page can never drift from the sources.
//
//   node brand/tools/build-guidelines.mjs
//
// Run after build-logo.mjs and tokens/build.mjs.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MASTERS } from './symbol-geometry.mjs';
import { contrast } from './color.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BRAND = path.resolve(HERE, '..');
const tokens = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/tokens.json'), 'utf8'));
const report = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/contrast.json'), 'utf8'));
const P = tokens.color.primitive;

const logo = (name) =>
  fs
    .readFileSync(path.join(BRAND, 'logo', name), 'utf8')
    .replace(/<!--[\s\S]*?-->\n?/, '')
    .replace(/<title>Clairix<\/title>\n?/, '')
    .replace(/ width="[^"]*" height="[^"]*"/, '')
    .trim();
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const textOn = (hex) => (contrast(hex, '#FFFFFF') >= contrast(hex, P.neutral['950'].hex) ? '#FFFFFF' : P.neutral['950'].hex);
const fmtOklch = (s) => s.replace('oklch(', '').replace(')', '');

// ------------------------------------------------------------ construction
function construction() {
  const m = MASTERS.regular;
  const a = (m.angle * Math.PI) / 180;
  const T = (x, y) => [48 + x * Math.cos(a) - y * Math.sin(a), 48 + x * Math.sin(a) + y * Math.cos(a)];
  const ic = T(m.off, 0), dot = T(m.dotR, 0);
  const w = m.rd + m.clear;
  const line = (y) => { const p = T(0, y), q = T(52, y); return `<line x1="${p[0]}" y1="${p[1]}" x2="${q[0]}" y2="${q[1]}"/>`; };
  const axis = (() => { const p = T(-50, 0), q = T(54, 0); return `<line x1="${p[0]}" y1="${p[1]}" x2="${q[0]}" y2="${q[1]}" stroke-dasharray="1.2 1.2"/>`; })();
  const sym = logo('symbol.svg').replace(/^<svg[^>]*>/, '').replace('</svg>', '');
  return `<svg viewBox="-6 -6 108 108" class="construct" aria-label="Construction of the symbol">
  <g opacity="0.14">${sym}</g>
  <g fill="none" stroke="var(--clx-glint)" stroke-width="0.35">
    <circle cx="48" cy="48" r="${m.R}"/>
    <circle cx="${ic[0]}" cy="${ic[1]}" r="${m.ri}"/>
    <circle cx="${dot[0]}" cy="${dot[1]}" r="${m.rd}"/>
    ${line(w)}${line(-w)}${axis}
  </g>
  <g fill="var(--clx-glint)">
    <circle cx="48" cy="48" r="0.9"/><circle cx="${ic[0]}" cy="${ic[1]}" r="0.9"/><circle cx="${dot[0]}" cy="${dot[1]}" r="0.9"/>
  </g>
</svg>`;
}

// ------------------------------------------------------------ palette
const swatchRow = (group, keys) =>
  keys
    .map((k) => {
      const v = P[group][k];
      return `<div class="sw" style="background:${v.hex};color:${textOn(v.hex)}"><b>${k}</b><span>${v.hex}</span><span>${fmtOklch(v.oklch)}</span></div>`;
    })
    .join('');

const semanticCards = () => {
  const roles = [
    ['Safe', 'safe', 'Nothing found, protection on, a check passed.'],
    ['Caution', 'caution', 'Worth a look. Not urgent.'],
    ['Danger', 'danger', 'Something needs you now. Used sparingly.'],
    ['Info', 'info', 'Neutral facts and tips.'],
  ];
  const resolve = (mode, role) => P[tokens.color.semantic[mode][role].split('.')[0]][tokens.color.semantic[mode][role].split('.')[1]];
  return roles
    .map(([label, key, use]) => {
      const L = (r) => resolve('light', r), D = (r) => resolve('dark', r);
      const cap = key[0].toUpperCase() + key.slice(1);
      return `<div class="sem">
  <div class="sem-pair">
    <div class="sem-half" style="background:${L(key + 'Container').hex};color:${L('on' + cap + 'Container').hex}">
      <span class="dot" style="background:${L(key).hex}"></span><b>${label}</b><small>Light</small>
      <code>${L(key).hex}</code>
    </div>
    <div class="sem-half" style="background:${D(key + 'Container').hex};color:${D('on' + cap + 'Container').hex}">
      <span class="dot" style="background:${D(key).hex}"></span><b>${label}</b><small>Dark</small>
      <code>${D(key).hex}</code>
    </div>
  </div>
  <p>${use}</p>
</div>`;
    })
    .join('');
};

const contrastTable = () => {
  const rows = report.modes.light.map((l, i) => {
    const d = report.modes.dark[i];
    const cell = (r) => `<td><span class="chip" style="background:${r.bgHex};color:${r.fgHex}">Aa</span></td><td class="num">${r.ratio.toFixed(2)}</td><td>${r.aa ? 'AA' : r.aaLarge ? 'AA large' : '—'}</td>`;
    return `<tr><td><code>${l.fg}</code> on <code>${l.bg}</code></td>${cell(l)}${cell(d)}</tr>`;
  });
  return `<div class="table-wrap"><table class="contrast">
<thead><tr><th>Pair</th><th colspan="3">Light</th><th colspan="3">Dark</th></tr></thead>
<tbody>${rows.join('\n')}</tbody></table></div>`;
};

// ------------------------------------------------------------ type
const typeScale = () =>
  Object.entries(tokens.font.scale)
    .filter(([k]) => !k.startsWith('$'))
    .map(([k, t]) => {
      const fam = t.family === 'mono' ? 'var(--clx-font-mono)' : 'var(--clx-font-sans)';
      const sample = t.family === 'mono' ? 'D4:3A:2E:91:0C:7F' : k.startsWith('display') ? 'See clearly.' : k.startsWith('headline') ? 'No hidden cameras found' : k.startsWith('title') ? 'Trackers near you' : k.startsWith('body') ? 'A tracker has been near you for 40 minutes. Here is how to find it.' : 'Scan this room';
      return `<div class="ts"><div class="ts-meta"><b>${k}</b><span>${t.size}/${t.lineHeight} · ${t.weight} · ${t.tracking}em${t.web ? ' · web' : ''}</span></div><div class="ts-sample" style="font-family:${fam};font-size:${t.size}px;line-height:${t.lineHeight}px;font-weight:${t.weight};letter-spacing:${t.tracking}em">${sample}</div></div>`;
    })
    .join('');

// ------------------------------------------------------------ misuse
const sym = logo('symbol.svg');
const misuse = [
  ['Don’t rotate it. The light always arrives from the upper right.', `<div style="transform:rotate(135deg)">${sym}</div>`],
  ['Don’t recolour the Glint, or use status colours in the mark.', sym.replace(P.blue['500'].hex, P.red['600'].hex)],
  ['Don’t stretch or squash it.', `<div style="transform:scaleX(1.32)">${sym}</div>`],
  ['Don’t add gradients, shadows or glows to the mark.', `<div style="filter:drop-shadow(0 6px 6px rgba(0,0,0,.45))">${sym.replace(/fill="#0B0F18"/, 'fill="url(#bad-grad)"').replace('<path', '<defs><linearGradient id="bad-grad" x1="0" x2="1"><stop offset="0" stop-color="#7B2FF7"/><stop offset="1" stop-color="#0C71D1"/></linearGradient></defs><path')}</div>`],
  ['Don’t draw it as an outline.', sym.replace(/fill="(#[0-9A-F]{6})"/g, 'fill="none" stroke="$1" stroke-width="2"')],
  ['Don’t move or detach the Glint.', sym.replace(/(<path fill="#2489EA" d=")[^"]+"/, '$1M40 48A8 8 0 1 0 56 48A8 8 0 1 0 40 48Z"')],
  ['Don’t place it on busy or low-contrast grounds.', `<div style="background:${P.blue['600'].hex};padding:14%;border-radius:12px">${sym}</div>`],
  ['Don’t retype the wordmark or rearrange the lockup.', `<div class="bad-lockup"><span>CLAIRIX</span>${sym}</div>`],
];

// ------------------------------------------------------------ page
const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Clairix Brand Guidelines</title>
<meta name="description" content="Logo, colour, type, voice and imagery for Clairix.">
<link rel="icon" href="assets/favicon.svg" type="image/svg+xml">
<link rel="icon" href="assets/favicon.ico" sizes="16x16 32x32 48x48">
<link rel="stylesheet" href="tokens/tokens.css">
<!-- Generated by brand/tools/build-guidelines.mjs. Edit that script, not this file. -->
<style>
  @font-face { font-family: "Inter"; src: url("fonts/inter/web/InterVariable.woff2") format("woff2"); font-weight: 100 900; font-style: normal; font-display: swap; }
  @font-face { font-family: "Inter"; src: url("fonts/inter/web/InterVariable-Italic.woff2") format("woff2"); font-weight: 100 900; font-style: italic; font-display: swap; }
  @font-face { font-family: "JetBrains Mono"; src: url("fonts/jetbrains-mono/web/JetBrainsMono-Regular.woff2") format("woff2"); font-weight: 400; font-display: swap; }
  @font-face { font-family: "JetBrains Mono"; src: url("fonts/jetbrains-mono/web/JetBrainsMono-Medium.woff2") format("woff2"); font-weight: 500; font-display: swap; }

  *, *::before, *::after { box-sizing: border-box; }
  html { scroll-behavior: smooth; -webkit-text-size-adjust: 100%; }
  @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
  body {
    margin: 0; background: var(--clx-background); color: var(--clx-text);
    font-family: var(--clx-font-sans); font-size: 16px; line-height: 1.6; letter-spacing: -0.006em;
    font-optical-sizing: auto; -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility;
  }
  a { color: var(--clx-primary); text-decoration-thickness: 1px; text-underline-offset: 3px; }
  code, .mono { font-family: var(--clx-font-mono); font-size: 0.86em; letter-spacing: 0; }
  h1, h2, h3 { margin: 0; font-weight: 600; text-wrap: balance; }
  p { margin: 0; text-wrap: pretty; }
  img, svg { display: block; max-width: 100%; }

  .shell { display: grid; grid-template-columns: 220px minmax(0, 1fr); max-width: 1320px; margin: 0 auto; }
  nav.toc { position: sticky; top: 0; align-self: start; height: 100vh; padding: 40px 24px 40px 32px; display: flex; flex-direction: column; gap: 28px; }
  nav.toc .brand { width: 120px; color: var(--clx-text); }
  nav.toc ol { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; counter-reset: s; }
  nav.toc li { counter-increment: s; }
  nav.toc a { display: flex; gap: 10px; padding: 6px 10px; margin-left: -10px; border-radius: var(--clx-radius-sm); color: var(--clx-text-secondary); text-decoration: none; font-size: 14px; font-weight: 500; }
  nav.toc a::before { content: counter(s, decimal-leading-zero); color: var(--clx-text-tertiary); font-variant-numeric: tabular-nums; font-weight: 400; }
  nav.toc a:hover { background: var(--clx-surface-muted); color: var(--clx-text); }
  nav.toc .ver { margin-top: auto; font-size: 12px; color: var(--clx-text-tertiary); }

  main { padding: 0 56px 120px 40px; min-width: 0; }
  section { padding-top: 112px; }
  .eyebrow { font-size: 13px; font-weight: 500; letter-spacing: 0.02em; color: var(--clx-glint); margin-bottom: 14px; font-variant-numeric: tabular-nums; }
  h2 { font-size: var(--clx-text-display-md-size); line-height: var(--clx-text-display-md-line); letter-spacing: var(--clx-text-display-md-tracking); }
  h3 { font-size: var(--clx-text-title-lg-size); line-height: var(--clx-text-title-lg-line); letter-spacing: var(--clx-text-title-lg-tracking); margin: 56px 0 16px; }
  .lead { font-size: 19px; line-height: 30px; color: var(--clx-text-secondary); max-width: 680px; margin-top: 18px; letter-spacing: -0.011em; }
  .body { max-width: 680px; color: var(--clx-text-secondary); }
  .body + .body { margin-top: 12px; }
  .body b { color: var(--clx-text); font-weight: 600; }

  .card { background: var(--clx-surface); border: 1px solid var(--clx-border); border-radius: var(--clx-radius-lg); }
  .panel { border-radius: var(--clx-radius-lg); display: grid; place-items: center; position: relative; overflow: hidden; }
  .panel.light { background: #FFFFFF; border: 1px solid var(--clx-border); color: ${P.neutral['950'].hex}; }
  .panel.dark { background: ${P.ink.hex}; color: #FFFFFF; }
  .panel .tag { position: absolute; left: 16px; bottom: 12px; font-size: 12px; color: ${P.neutral['500'].hex}; }
  .panel.dark .tag { color: ${P.neutral['400'].hex}; }
  .grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
  .grid-3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
  .grid-4 { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }

  /* hero */
  .hero { padding-top: 72px; }
  .hero-stage { margin-top: 48px; height: 440px; background: ${P.ink.hex}; border-radius: var(--clx-radius-xl); position: relative; overflow: hidden; display: grid; place-items: center; }
  .hero-stage::before { content: ""; position: absolute; width: 560px; height: 560px; left: calc(50% + 10px); top: -170px; border-radius: 50%; background: radial-gradient(closest-side, oklch(0.71 0.14 252 / 0.22), oklch(0.71 0.14 252 / 0.05) 60%, transparent); }
  .hero-stage svg { width: min(520px, 76%); position: relative; }
  .hero h1 { font-size: var(--clx-text-display-lg-size); line-height: var(--clx-text-display-lg-line); letter-spacing: var(--clx-text-display-lg-tracking); }
  .meta-row { display: flex; gap: 32px; flex-wrap: wrap; margin-top: 28px; font-size: 14px; color: var(--clx-text-secondary); }
  .meta-row b { display: block; color: var(--clx-text); font-weight: 600; }

  /* concept */
  .concept { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 48px; align-items: center; margin-top: 40px; }
  .construct { width: 100%; max-width: 440px; color: var(--clx-text); }
  .construct path:first-of-type { fill: currentColor; }
  .legend { display: grid; gap: 14px; margin-top: 24px; font-size: 15px; color: var(--clx-text-secondary); }
  .legend div { display: grid; grid-template-columns: 28px 1fr; gap: 12px; }
  .legend span { font-weight: 600; color: var(--clx-glint); font-variant-numeric: tabular-nums; }

  /* logo */
  .logo-main { height: 300px; }
  .logo-main svg { width: min(420px, 70%); }
  .logo-sym { height: 240px; }
  .logo-sym svg { width: 112px; }
  .logo-stack svg { width: 112px; margin-top: -14px; }
  .logo-word svg { width: min(260px, 64%); }

  /* clear space */
  .space-demo { height: 340px; }
  .space-box { position: relative; padding: var(--pad); outline: 1px dashed ${P.blue['400'].hex}; }
  .space-box::after { content: ""; position: absolute; inset: var(--pad); outline: 1px solid ${P.blue['300'].hex}; opacity: .6; }
  .space-box .x { position: absolute; width: var(--pad); height: var(--pad); background: oklch(0.71 0.14 252 / 0.16); display: grid; place-items: center; font-size: 11px; color: ${P.blue['600'].hex}; font-weight: 600; }
  .min-sizes { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 32px; align-items: end; padding: 40px; }
  .min-sizes figure { margin: 0; display: grid; gap: 12px; justify-items: start; }
  .min-sizes figcaption { font-size: 13px; color: var(--clx-text-secondary); line-height: 1.45; }
  .min-sizes figcaption b { color: var(--clx-text); font-weight: 600; display: block; }

  /* misuse */
  .misuse { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
  .misuse figure { margin: 0; }
  .misuse .panel { height: 180px; }
  .misuse .panel > * { width: 84px; }
  .misuse .panel > .bad-lockup { width: auto; }
  .misuse .panel::after { content: ""; position: absolute; top: 12px; right: 12px; width: 22px; height: 22px; border-radius: 50%; background: ${P.red['600'].hex} url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 22 22'%3E%3Cpath d='M7.5 7.5l7 7M14.5 7.5l-7 7' stroke='white' stroke-width='1.8' stroke-linecap='round'/%3E%3C/svg%3E") center/22px no-repeat; }
  .misuse figcaption { font-size: 14px; color: var(--clx-text-secondary); margin-top: 10px; line-height: 1.45; }
  .bad-lockup { display: flex; align-items: center; gap: 10px; font-family: Georgia, serif; font-weight: 700; font-size: 22px; letter-spacing: .12em; color: ${P.neutral['950'].hex}; }
  .bad-lockup svg { width: 44px; }

  /* colour */
  .scale { display: grid; grid-template-columns: repeat(11, minmax(0, 1fr)); border-radius: var(--clx-radius-md); overflow: hidden; }
  .sw { min-height: 132px; padding: 12px 10px; display: flex; flex-direction: column; gap: 2px; font-size: 11px; line-height: 1.35; font-family: var(--clx-font-mono); }
  .sw b { font-family: var(--clx-font-sans); font-size: 13px; font-weight: 600; margin-bottom: auto; }
  .hero-swatches { display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 16px; margin-top: 40px; }
  .big-sw { border-radius: var(--clx-radius-lg); min-height: 220px; padding: 22px; display: flex; flex-direction: column; justify-content: space-between; }
  .big-sw b { font-size: 22px; font-weight: 600; letter-spacing: -0.014em; }
  .big-sw p { font-size: 14px; opacity: .85; max-width: 320px; }
  .big-sw code { font-size: 12px; opacity: .9; display: block; line-height: 1.6; }
  .sem { display: grid; gap: 10px; }
  .sem-pair { display: grid; grid-template-columns: 1fr 1fr; border-radius: var(--clx-radius-md); overflow: hidden; border: 1px solid var(--clx-border); }
  .sem-half { padding: 16px; display: grid; gap: 4px; min-height: 120px; align-content: start; font-size: 14px; }
  .sem-half .dot { width: 14px; height: 14px; border-radius: 50%; margin-bottom: 8px; }
  .sem-half small { opacity: .8; font-size: 12px; }
  .sem-half code { margin-top: auto; padding-top: 8px; font-size: 11px; }
  .sem p { font-size: 14px; color: var(--clx-text-secondary); }
  .table-wrap { overflow-x: auto; border: 1px solid var(--clx-border); border-radius: var(--clx-radius-lg); background: var(--clx-surface); }
  table { border-collapse: collapse; width: 100%; font-size: 14px; }
  th, td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--clx-border); white-space: nowrap; }
  tbody tr:last-child td { border-bottom: 0; }
  th { font-size: 12px; font-weight: 600; color: var(--clx-text-secondary); background: var(--clx-surface-muted); }
  td.num { font-variant-numeric: tabular-nums; font-weight: 600; }
  .chip { display: inline-grid; place-items: center; width: 40px; height: 26px; border-radius: 6px; font-weight: 600; font-size: 13px; box-shadow: inset 0 0 0 1px oklch(0.5 0 0 / 0.15); }

  /* type */
  .fonts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 40px; }
  .font-card { padding: 28px; display: grid; gap: 14px; align-content: start; }
  .font-card .aa { font-size: 88px; line-height: 1; font-weight: 600; letter-spacing: -0.04em; }
  .font-card h4 { margin: 0; font-size: 17px; font-weight: 600; }
  .font-card p { font-size: 14px; color: var(--clx-text-secondary); }
  .ts { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 24px; padding: 18px 0; border-bottom: 1px solid var(--clx-border); align-items: baseline; }
  .ts-meta { display: grid; font-size: 13px; color: var(--clx-text-secondary); }
  .ts-meta b { color: var(--clx-text); font-weight: 600; font-family: var(--clx-font-mono); font-size: 12px; }
  .ts-sample { overflow-wrap: anywhere; }

  /* voice */
  .principles { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 40px; }
  .principle { padding: 24px; }
  .principle b { display: block; font-size: 17px; margin-bottom: 6px; }
  .principle p { font-size: 15px; color: var(--clx-text-secondary); }
  .saydont { display: grid; gap: 12px; }
  .sd { display: grid; grid-template-columns: 180px minmax(0, 1fr) minmax(0, 1fr); gap: 0; border: 1px solid var(--clx-border); border-radius: var(--clx-radius-md); overflow: hidden; background: var(--clx-surface); }
  .sd > div { padding: 16px 18px; font-size: 15px; }
  .sd .ctx { font-size: 13px; font-weight: 600; color: var(--clx-text-secondary); background: var(--clx-surface-muted); }
  .sd .say { border-left: 3px solid var(--clx-safe); }
  .sd .dont { border-left: 3px solid var(--clx-danger); color: var(--clx-text-secondary); }
  .sd .say::before, .sd .dont::before { display: block; font-size: 11px; font-weight: 600; letter-spacing: .04em; margin-bottom: 4px; }
  .sd .say::before { content: "We say"; color: var(--clx-safe); }
  .sd .dont::before { content: "We don’t say"; color: var(--clx-danger); }
  .words { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
  .words .card { padding: 24px; }
  .words ul { margin: 12px 0 0; padding: 0; list-style: none; display: flex; flex-wrap: wrap; gap: 8px; }
  .words li { padding: 4px 12px; border-radius: 999px; font-size: 14px; background: var(--clx-surface-muted); }
  .words .no li { text-decoration: line-through; text-decoration-color: var(--clx-danger); color: var(--clx-text-secondary); }
  .rules { margin: 0; padding: 0; list-style: none; display: grid; gap: 10px; max-width: 760px; }
  .rules li { padding-left: 28px; position: relative; color: var(--clx-text-secondary); }
  .rules li::before { content: ""; position: absolute; left: 4px; top: 10px; width: 8px; height: 8px; border-radius: 50%; background: var(--clx-glint); }
  .rules b { color: var(--clx-text); font-weight: 600; }

  /* imagery */
  .grade { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); height: 88px; border-radius: var(--clx-radius-md); overflow: hidden; margin-top: 16px; }
  .do-dont { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
  .do-dont .card { padding: 24px; }
  .do-dont h4 { margin: 0 0 12px; font-size: 15px; }
  .do-dont ul { margin: 0; padding-left: 18px; color: var(--clx-text-secondary); display: grid; gap: 6px; font-size: 15px; }
  .illo { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
  .illo .panel { height: 180px; }
  .illo svg { width: 96px; }
  .illo figure { margin: 0; }
  .illo figcaption { font-size: 14px; color: var(--clx-text-secondary); margin-top: 10px; }

  /* files */
  .files { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
  .files .card { padding: 22px 24px; }
  .files h4 { margin: 0 0 8px; font-size: 15px; }
  .files ul { margin: 0; padding: 0; list-style: none; font-family: var(--clx-font-mono); font-size: 12.5px; color: var(--clx-text-secondary); display: grid; gap: 4px; }
  footer { margin-top: 112px; padding-top: 28px; border-top: 1px solid var(--clx-border); display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap; font-size: 13px; color: var(--clx-text-tertiary); }

  @media (max-width: 1080px) {
    .shell { grid-template-columns: 1fr; }
    nav.toc { display: none; }
    main { padding: 0 32px 96px; }
    .misuse, .illo { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .grid-4 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
  @media (max-width: 720px) {
    main { padding: 0 16px 80px; }
    section { padding-top: 80px; }
    .hero-stage { height: 260px; border-radius: var(--clx-radius-lg); }
    .hero h1 { font-size: 36px; line-height: 42px; }
    h2 { font-size: 30px; line-height: 36px; }
    .lead { font-size: 17px; line-height: 27px; }
    .concept, .grid-2, .grid-3, .fonts, .principles, .words, .do-dont, .files, .hero-swatches { grid-template-columns: 1fr; }
    .scale { grid-template-columns: repeat(4, minmax(0, 1fr)); }
    .sw { min-height: 104px; }
    .ts { grid-template-columns: 1fr; gap: 8px; }
    .sd { grid-template-columns: 1fr; }
    .sd .say, .sd .dont { border-left: 0; border-top: 3px solid; }
    .sd .say { border-top-color: var(--clx-safe); }
    .sd .dont { border-top-color: var(--clx-danger); }
    .grade { grid-template-columns: repeat(5, 1fr); height: 64px; }
    .logo-main { height: 220px; }
    .min-sizes { padding: 24px; gap: 28px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
</style>
</head>
<body>
<div class="shell">
<nav class="toc" aria-label="Sections">
  <div class="brand">${logo('wordmark.svg').replace('fill="#0B0F18"', 'fill="currentColor"')}</div>
  <ol>
    <li><a href="#concept">Concept</a></li>
    <li><a href="#logo">Logo</a></li>
    <li><a href="#space">Clear space and size</a></li>
    <li><a href="#misuse">Misuse</a></li>
    <li><a href="#colour">Colour</a></li>
    <li><a href="#type">Typography</a></li>
    <li><a href="#voice">Voice and tone</a></li>
    <li><a href="#imagery">Imagery</a></li>
    <li><a href="#files">Files</a></li>
  </ol>
  <div class="ver">Brand guidelines 1.0 · October 2026</div>
</nav>

<main>
<header class="hero">
  <div class="eyebrow">Clairix brand guidelines</div>
  <h1>Calm, clear protection<br>for everyday people.</h1>
  <p class="lead">Clairix finds hidden cameras, trackers and spyware, and keeps calls and messages private. The brand should feel like the app: quiet, confident and on your side. This page sets out how.</p>
  <div class="hero-stage">${logo('lockup-horizontal-reversed.svg')}</div>
  <div class="meta-row">
    <div><b>Name</b>Clairix, from <i>clair</i>: clear, as in clairvoyant.</div>
    <div><b>Domain</b>clairix.app</div>
    <div><b>Plans</b>Clairix Free and Clairix Pro</div>
  </div>
</header>

<section id="concept">
  <div class="eyebrow">01 · Concept</div>
  <h2>The Glint</h2>
  <p class="lead">Shine a light across a room and a hidden camera gives itself away with a single point of reflected light. That glint is the moment the hidden becomes visible. It is what Clairix does, and it is our symbol.</p>
  <div class="concept">
    <div class="panel light" style="padding:32px">${construction()}</div>
    <div>
      <p class="body">The symbol is a lens seen in light: a ring whose stroke is heavy at the lower left and thins toward an opening at the upper right, where one round point of light sits. Read quickly, it is a <b>C</b> for Clairix. Read slowly, it is a lens catching the light, or an eye that has just noticed something.</p>
      <div class="legend">
        <div><span>1</span>The outer circle sets the size. Everything else is measured from it.</div>
        <div><span>2</span>The counter is a circle shifted toward the opening, so the ring tapers the way light falls across a curved surface.</div>
        <div><span>3</span>Two parallel cuts make the opening. Its width leaves the same clearance on both sides of the Glint.</div>
        <div><span>4</span>The Glint is a true circle on the opening’s axis. It is the only colour in the mark.</div>
      </div>
    </div>
  </div>
</section>

<section id="logo">
  <div class="eyebrow">02 · Logo</div>
  <h2>Symbol, wordmark, lockups</h2>
  <p class="lead">The wordmark is set in Inter Display SemiBold, outlined, re-spaced by hand and given round tittles that echo the Glint. Use the horizontal lockup by default. Use the symbol alone once people already know it is us: the app icon, favicons, avatars.</p>
  <h3>Primary lockup</h3>
  <div class="grid-2">
    <div class="panel light logo-main">${logo('lockup-horizontal.svg')}<span class="tag">lockup-horizontal.svg</span></div>
    <div class="panel dark logo-main">${logo('lockup-horizontal-reversed.svg')}<span class="tag">lockup-horizontal-reversed.svg</span></div>
  </div>
  <h3>Symbol and stacked lockup</h3>
  <div class="grid-4">
    <div class="panel light logo-sym">${logo('symbol.svg')}<span class="tag">symbol.svg</span></div>
    <div class="panel dark logo-sym">${logo('symbol-reversed.svg')}<span class="tag">symbol-reversed.svg</span></div>
    <div class="panel light logo-sym logo-stack">${logo('lockup-stacked.svg')}<span class="tag">lockup-stacked.svg</span></div>
    <div class="panel dark logo-sym logo-stack">${logo('lockup-stacked-reversed.svg')}<span class="tag">lockup-stacked-reversed.svg</span></div>
  </div>
  <h3>One colour</h3>
  <p class="body">For engraving, embossing, single-colour print and anywhere the blue cannot be reproduced. The Glint stays a separate shape, never knocked out.</p>
  <div class="grid-4" style="margin-top:20px">
    <div class="panel light logo-sym">${logo('symbol-mono-dark.svg')}<span class="tag">symbol-mono-dark.svg</span></div>
    <div class="panel dark logo-sym">${logo('symbol-mono-light.svg')}<span class="tag">symbol-mono-light.svg</span></div>
    <div class="panel light logo-sym logo-word">${logo('wordmark.svg')}<span class="tag">wordmark.svg</span></div>
    <div class="panel dark logo-sym logo-word">${logo('wordmark-light.svg')}<span class="tag">wordmark-light.svg</span></div>
  </div>
  <h3>App icon</h3>
  <p class="body">White symbol and Glint on Ink. On Android it is an adaptive icon: the symbol is 52 dp across, inside the 66 dp safe zone, so every launcher mask leaves it whole. The themed (monochrome) layer uses the same shapes.</p>
  <div class="grid-4" style="margin-top:20px">
    ${['50%', '30%', '22%'].map((r) => `<div class="panel light logo-sym" style="background:linear-gradient(160deg,#DCE6F5,#EEF1F6)"><div style="width:120px;height:120px;border-radius:${r};overflow:hidden">${logo('app-icon.svg').replace('<svg', '<svg style="width:180px;height:180px;max-width:none;margin:-30px"')}</div></div>`).join('')}
    <div class="panel dark logo-sym"><div style="width:120px;height:120px;border-radius:50%;overflow:hidden;background:#1F2A44">${logo('app-icon-foreground.svg').replace(/fill="#[0-9A-F]{6}"/g, 'fill="#D7E3FF"').replace('<svg', '<svg style="width:180px;height:180px;max-width:none;margin:-30px"')}</div><span class="tag">Themed icon</span></div>
  </div>
</section>

<section id="space">
  <div class="eyebrow">03 · Clear space and minimum size</div>
  <h2>Give it room</h2>
  <p class="lead">Clear space is measured in <b style="color:var(--clx-text)">G</b>, the diameter of the Glint. Keep at least 2G clear on every side of the symbol, and the wordmark’s x-height clear around the lockups. More is always better.</p>
  <div class="grid-2" style="margin-top:40px">
    <div class="panel light space-demo">
      <div class="space-box" style="--pad:36px">
        <div style="width:200px">${logo('symbol.svg')}</div>
        <div class="x" style="left:0;top:0">2G</div>
        <div class="x" style="right:0;bottom:0">2G</div>
      </div>
      <span class="tag">Symbol: 2G on every side</span>
    </div>
    <div class="panel light space-demo">
      <div class="space-box" style="--pad:26px">
        <div style="width:300px">${logo('lockup-horizontal.svg')}</div>
        <div class="x" style="left:0;top:0">x</div>
        <div class="x" style="right:0;bottom:0">x</div>
      </div>
      <span class="tag">Lockups: one x-height on every side</span>
    </div>
  </div>
  <h3>Minimum sizes</h3>
  <div class="card min-sizes">
    <figure><div style="width:16px">${logo('symbol-small.svg')}</div><figcaption><b>Symbol 16 px</b>Use symbol-small.svg from 16 to 39 px. Print 5 mm.</figcaption></figure>
    <figure><div style="width:40px">${logo('symbol.svg')}</div><figcaption><b>Symbol 40 px +</b>Regular master. Print 10 mm.</figcaption></figure>
    <figure><div style="width:88px">${logo('lockup-horizontal.svg')}</div><figcaption><b>Horizontal 88 px wide</b>Print 22 mm.</figcaption></figure>
    <figure><div style="width:56px">${logo('lockup-stacked.svg')}</div><figcaption><b>Stacked 56 px wide</b>Print 14 mm.</figcaption></figure>
    <figure><div style="width:64px">${logo('wordmark.svg')}</div><figcaption><b>Wordmark 64 px wide</b>Print 16 mm.</figcaption></figure>
  </div>
</section>

<section id="misuse">
  <div class="eyebrow">04 · Misuse</div>
  <h2>Keep it as drawn</h2>
  <p class="lead">The mark works because it is simple and exact. Use the supplied files and leave them as they are.</p>
  <div class="misuse" style="margin-top:40px">
    ${misuse.map(([t, s]) => `<figure><div class="panel light">${s}</div><figcaption>${t}</figcaption></figure>`).join('\n    ')}
  </div>
</section>

<section id="colour">
  <div class="eyebrow">05 · Colour</div>
  <h2>Ink, light and one clear blue</h2>
  <p class="lead">Mostly quiet neutrals, with Clair Blue used the way the Glint is used: once, where it matters. Status colours are softened so a warning informs instead of alarms. Every colour is defined in OKLCH, with its sRGB hex.</p>
  <div class="hero-swatches">
    <div class="big-sw" style="background:${P.blue['500'].hex};color:${P.neutral['950'].hex}"><div><b>Clair Blue</b><p>The Glint. The brand accent, links, focus and the one primary action on a screen.</p></div><code>blue.500 · ${P.blue['500'].hex}<br>oklch ${fmtOklch(P.blue['500'].oklch)}</code></div>
    <div class="big-sw" style="background:${P.ink.hex};color:#fff;"><div><b>Ink</b><p>Dark ground, app icon.</p></div><code>ink · ${P.ink.hex}<br>oklch ${fmtOklch(P.ink.oklch)}</code></div>
    <div class="big-sw" style="background:${P.neutral['50'].hex};color:${P.neutral['950'].hex};border:1px solid var(--clx-border)"><div><b>Paper</b><p>Light ground.</p></div><code>neutral.50 · ${P.neutral['50'].hex}<br>oklch ${fmtOklch(P.neutral['50'].oklch)}</code></div>
  </div>
  <h3>Clair Blue</h3>
  <div class="scale">${swatchRow('blue', Object.keys(P.blue).filter((k) => !k.startsWith('$')))}</div>
  <h3>Neutrals</h3>
  <p class="body" style="margin-bottom:16px">A cool grey with a trace of the blue in it, so greys and accent belong together.</p>
  <div class="scale">${swatchRow('neutral', Object.keys(P.neutral))}</div>
  <h3>Status</h3>
  <div class="grid-4">${semanticCards()}</div>
  <h3>Contrast</h3>
  <p class="body" style="margin-bottom:16px">WCAG 2.x contrast ratios for every text pair the product uses, computed from the hex values by <code>tokens/build.mjs</code>. All meet AA (4.5:1) for body text. Caution amber is a fill colour only: it carries dark text and is never used as text on white.</p>
  ${contrastTable()}
</section>

<section id="type">
  <div class="eyebrow">06 · Typography</div>
  <h2>Inter, and only Inter</h2>
  <p class="lead">One family, used with care, reads as confidence. Inter was drawn for screens: a tall x-height, open shapes and clear figures keep it legible at 11 px on a budget phone, and its Display optical size tightens up for headlines. It is free under the SIL Open Font License, so the app, the site and print all use the same files.</p>
  <div class="fonts">
    <div class="card font-card"><div class="aa">Aa</div><h4>Inter</h4><p>Interface and body text, 11 to 19 px. Regular 400, Medium 500, SemiBold 600. Tabular figures for numbers that change.</p></div>
    <div class="card font-card"><div class="aa" style="font-variation-settings:'opsz' 32">Aa</div><h4>Inter Display</h4><p>Headlines from 20 px up. Inter’s display optical size: tighter spacing and finer detail. SemiBold 600.</p></div>
    <div class="card font-card"><div class="aa" style="font-family:var(--clx-font-mono);font-weight:500;letter-spacing:-0.02em">Aa</div><h4>JetBrains Mono</h4><p>Machine values only: MAC addresses, cell IDs, certificate fingerprints. Never for prose or headlines.</p></div>
  </div>
  <h3>Scale</h3>
  <p class="body" style="margin-bottom:8px">Sizes are px on the web and sp on Android. Tracking tightens as size grows. Steps marked web are for marketing pages.</p>
  <div>${typeScale()}</div>
</section>

<section id="voice">
  <div class="eyebrow">07 · Voice and tone</div>
  <h2>Calm, plain, on your side</h2>
  <p class="lead">People open Clairix when they are worried. Our job is to lower the temperature: say what we found, what it means, and what to do next. We never use fear to sell.</p>
  <div class="principles">
    <div class="card principle"><b>Calm</b><p>We state facts at an even pitch. No exclamation marks in alerts. No capitals for emphasis.</p></div>
    <div class="card principle"><b>Plain</b><p>Everyday words. “Tracker”, not “BLE beacon”. Technical detail is one tap away for those who want it.</p></div>
    <div class="card principle"><b>Honest</b><p>We say what we can and cannot know. We never promise total safety, because no one can.</p></div>
    <div class="card principle"><b>Brief</b><p>Short sentences, one idea each. Headlines under eight words, sentences under twenty.</p></div>
    <div class="card principle"><b>Helpful</b><p>Every alert ends in a next step the person can take now.</p></div>
    <div class="card principle"><b>Respectful</b><p>We never blame, never shame, and never assume who is behind a tracker or an app.</p></div>
  </div>
  <h3>What we say, and what we don’t</h3>
  <div class="saydont">
    <div class="sd"><div class="ctx">A tracker is following</div><div class="say">A tracker has been near you for 40 minutes. Here’s how to find it.</div><div class="dont">ALERT! Someone is stalking you!</div></div>
    <div class="sd"><div class="ctx">Room scan is clear</div><div class="say">No hidden cameras found in this scan. Check again if you move rooms.</div><div class="dont">You’re 100% safe. No spies here.</div></div>
    <div class="sd"><div class="ctx">Keylogger keyboard</div><div class="say">This keyboard can read what you type. You can switch keyboards in two steps.</div><div class="dont">Your phone has been hacked. Act now.</div></div>
    <div class="sd"><div class="ctx">Unusual cell tower</div><div class="say">A nearby cell tower is behaving unusually. Your Clairix calls stay encrypted.</div><div class="dont">IMSI catcher detected. Your calls are being intercepted!</div></div>
    <div class="sd"><div class="ctx">Panic wipe</div><div class="say">Erase Clairix data on this phone? This can’t be undone.</div><div class="dont">Nuke everything?</div></div>
    <div class="sd"><div class="ctx">Upgrade to Pro</div><div class="say">Pro checks in the background, so you don’t have to remember.</div><div class="dont">Don’t leave yourself exposed. Upgrade before it’s too late.</div></div>
  </div>
  <h3>Words</h3>
  <div class="words">
    <div class="card"><b>We use</b><ul><li>check</li><li>found</li><li>near you</li><li>private</li><li>remove</li><li>turn off</li><li>encrypted</li><li>only you</li><li>here’s how</li><li>nothing found</li></ul></div>
    <div class="card no"><b>We avoid</b><ul><li>hacker</li><li>threat actor</li><li>attack</li><li>nuke</li><li>stealth</li><li>military-grade</li><li>unhackable</li><li>100% secure</li><li>paranoid</li><li>act now</li></ul></div>
  </div>
  <h3>Rules for every line</h3>
  <ul class="rules">
    <li><b>What happened, what it means, what to do.</b> In that order, every time.</li>
    <li><b>Sentence case everywhere,</b> including buttons and titles.</li>
    <li><b>Buttons are verbs:</b> “Scan room”, “Find tracker”, “Remove app”. Never “OK” when something will happen.</li>
    <li><b>Numbers as digits,</b> with units: 40 minutes, 3 apps, 2 steps.</li>
    <li><b>Name the thing, not the person.</b> “This tracker”, never “your stalker”.</li>
    <li><b>Contractions are fine.</b> We sound like a person, not a policy.</li>
  </ul>
</section>

<section id="imagery">
  <div class="eyebrow">08 · Photography and illustration</div>
  <h2>Daylight, not darkness</h2>
  <p class="lead">Our pictures show the moment after the worry: someone settling into a rental flat, a commuter with a bag on their lap, a friend handing back a phone. Real places, natural light, calm faces. Security, shown as ordinary life going on.</p>
  <h3>Photography</h3>
  <div class="do-dont">
    <div class="card"><h4 style="color:var(--clx-safe)">Do</h4><ul><li>Real people in everyday places: homes, hotels, trains, cafés.</li><li>Soft daylight, open space, a clear subject.</li><li>Phones in hand, screens showing real Clairix UI.</li><li>Diverse ages and backgrounds. Clairix is for everyone.</li><li>A gentle, slightly cool grade with true skin tones.</li></ul></div>
    <div class="card"><h4 style="color:var(--clx-danger)">Don’t</h4><ul><li>Hooded figures, dark rooms, faces lit by screens.</li><li>Padlocks, shields, binary code, matrix rain, glitch effects.</li><li>Eyes peering through blinds, victims, fear, distress.</li><li>Staged stock poses and heavy filters.</li><li>Spy gadgets as decoration.</li></ul></div>
  </div>
  <p class="body" style="margin-top:20px">Grade reference: lift shadows toward Ink, keep highlights clean, let one cool tone carry the frame.</p>
  <div class="grade">
    ${[P.ink.hex, P.neutral['700'].hex, P.blue['300'].hex, P.neutral['200'].hex, '#F4EFE8'].map((c) => `<div style="background:${c}"></div>`).join('')}
  </div>
  <h3>Illustration and icons</h3>
  <p class="body">Built from the same parts as the symbol: circles, arcs and straight cuts on a 24-unit grid. Flat fills from the palette, Clair Blue used once per drawing as the point of light. Icons use a 1.75 px stroke with round caps and joins to match Inter.</p>
  <div class="illo" style="margin-top:20px">
    <figure><div class="panel light"><svg viewBox="0 0 96 96"><rect x="14" y="22" width="68" height="52" rx="10" fill="none" stroke="${P.neutral['950'].hex}" stroke-width="5"/><circle cx="48" cy="48" r="14" fill="none" stroke="${P.neutral['950'].hex}" stroke-width="5"/><circle cx="54" cy="42" r="4.5" fill="${P.blue['500'].hex}"/></svg></div><figcaption>Camera found by its glint</figcaption></figure>
    <figure><div class="panel light"><svg viewBox="0 0 96 96"><circle cx="48" cy="48" r="30" fill="none" stroke="${P.neutral['300'].hex}" stroke-width="5"/><circle cx="48" cy="48" r="16" fill="none" stroke="${P.neutral['950'].hex}" stroke-width="5"/><circle cx="69" cy="27" r="6" fill="${P.blue['500'].hex}"/></svg></div><figcaption>A tracker near you</figcaption></figure>
    <figure><div class="panel light"><svg viewBox="0 0 96 96"><rect x="28" y="12" width="40" height="72" rx="10" fill="none" stroke="${P.neutral['950'].hex}" stroke-width="5"/><path d="M38 56l7 7 13-15" fill="none" stroke="${P.green['600'].hex}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/><circle cx="48" cy="24" r="3.5" fill="${P.blue['500'].hex}"/></svg></div><figcaption>Phone checked, nothing found</figcaption></figure>
    <figure><div class="panel light"><svg viewBox="0 0 96 96"><path d="M20 30h44a10 10 0 0 1 10 10v16a10 10 0 0 1-10 10H40l-14 10V66h-6a10 10 0 0 1-10-10V40a10 10 0 0 1 10-10z" fill="none" stroke="${P.neutral['950'].hex}" stroke-width="5" stroke-linejoin="round"/><circle cx="74" cy="26" r="6" fill="${P.blue['500'].hex}"/></svg></div><figcaption>Private messages</figcaption></figure>
  </div>
</section>

<section id="files">
  <div class="eyebrow">09 · Files</div>
  <h2>Everything in one place</h2>
  <p class="lead">All files live in <code>brand/</code>. Change tokens in one place and regenerate; never edit generated files by hand.</p>
  <div class="files" style="margin-top:32px">
    <div class="card"><h4>Logo · brand/logo</h4><ul><li>symbol.svg, symbol-reversed.svg</li><li>symbol-small.svg (16–39 px)</li><li>symbol-mono-dark.svg, symbol-mono-light.svg</li><li>wordmark.svg, wordmark-light.svg</li><li>lockup-horizontal[-reversed|-mono-*].svg</li><li>lockup-stacked[-reversed|-mono-*].svg</li><li>app-icon.svg, app-icon-foreground.svg</li></ul></div>
    <div class="card"><h4>Web and stores · brand/assets</h4><ul><li>favicon.svg, favicon.ico (16/32/48)</li><li>apple-touch-icon.png (180)</li><li>icon-192.png, icon-512-maskable.png</li><li>app-icon-1024.png, play-store-icon-512.png</li><li>og-image.png (1200×630)</li><li>play-feature-graphic.png (1024×500)</li></ul></div>
    <div class="card"><h4>Tokens · brand/tokens</h4><ul><li>tokens.json (source)</li><li>tokens.css (light and dark)</li><li>ClairixTheme.kt (Compose)</li><li>contrast.json</li><li>build.mjs</li></ul></div>
    <div class="card"><h4>Fonts and tools</h4><ul><li>fonts/inter (OFL)</li><li>fonts/jetbrains-mono (OFL)</li><li>tools/build-logo.mjs</li><li>tools/render.cjs</li><li>tools/build-guidelines.mjs</li></ul></div>
  </div>
  <footer><span>Clairix brand guidelines 1.0</span><span>clairix.app</span></footer>
</section>
</main>
</div>
</body>
</html>
`;
fs.writeFileSync(path.join(BRAND, 'guidelines.html'), html);
console.log('guidelines: wrote brand/guidelines.html');
