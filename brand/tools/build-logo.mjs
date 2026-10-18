#!/usr/bin/env node
// Builds every Clairix logo file from the symbol geometry and the outlined
// wordmark glyphs.
//
//   node brand/tools/build-logo.mjs
//
// Writes brand/logo/*.svg and the Android launcher layers in
// android/app/src/main/res/drawable/. No dependencies beyond Node 18+.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MASTERS, symbolPaths } from './symbol-geometry.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BRAND = path.resolve(HERE, '..');
const ROOT = path.resolve(BRAND, '..');
const OUT = path.join(BRAND, 'logo');
const RES = path.join(ROOT, 'android/app/src/main/res');
fs.mkdirSync(OUT, { recursive: true });

const tokens = JSON.parse(fs.readFileSync(path.join(BRAND, 'tokens/tokens.json'), 'utf8'));
const P = tokens.color.primitive;
export const COLORS = {
  ink: P.neutral['950'].hex, // the mark on light grounds
  white: P.white.hex,
  glint: P.blue['500'].hex, // glint on light grounds
  glintOnDark: P.blue['400'].hex, // glint on dark grounds
  iconGround: P.ink.hex, // launcher icon background
};

const n = (v) => {
  const s = (+v.toFixed(2)).toString();
  return s === '-0' ? '0' : s;
};

// ------------------------------------------------------------------ path utils
// Parse absolute/relative SVG path data (M L H V Q C Z, implicit repeats) and
// apply an affine map. The glyph outlines from fontTools only use absolute
// M L H V Q Z, but the parser is general enough for hand edits.
function transformPath(d, map) {
  const tokens = d.match(/[MLHVQCAZmlhvqcaz]|-?\d*\.?\d+(?:e-?\d+)?/g);
  const k = map.scale ?? 1; // uniform scale of the map, for arc radii
  let i = 0, cmd = null, x = 0, y = 0, sx = 0, sy = 0;
  const out = [];
  const num = () => parseFloat(tokens[i++]);
  const pt = (px, py) => {
    const [a, b] = map(px, py);
    return `${n(a)} ${n(b)}`;
  };
  while (i < tokens.length) {
    if (/[A-Za-z]/.test(tokens[i])) cmd = tokens[i++];
    const rel = cmd === cmd.toLowerCase();
    const C = cmd.toUpperCase();
    if (C === 'Z') { out.push('Z'); x = sx; y = sy; continue; }
    if (C === 'M' || C === 'L') {
      let px = num(), py = num();
      if (rel) { px += x; py += y; }
      out.push((C === 'M' ? 'M' : 'L') + pt(px, py));
      x = px; y = py;
      if (C === 'M') { sx = x; sy = y; cmd = rel ? 'l' : 'L'; }
    } else if (C === 'H') {
      let px = num(); if (rel) px += x;
      out.push('L' + pt(px, y)); x = px;
    } else if (C === 'V') {
      let py = num(); if (rel) py += y;
      out.push('L' + pt(x, py)); y = py;
    } else if (C === 'Q') {
      let [a, b, c, e] = [num(), num(), num(), num()];
      if (rel) { a += x; b += y; c += x; e += y; }
      out.push('Q' + pt(a, b) + ' ' + pt(c, e)); x = c; y = e;
    } else if (C === 'C') {
      let [a, b, c, e, f, g] = [num(), num(), num(), num(), num(), num()];
      if (rel) { a += x; b += y; c += x; e += y; f += x; g += y; }
      out.push('C' + pt(a, b) + ' ' + pt(c, e) + ' ' + pt(f, g)); x = f; y = g;
    } else if (C === 'A') {
      const [rx, ry, rot, large, sweep] = [num(), num(), num(), num(), num()];
      let [ex, ey] = [num(), num()];
      if (rel) { ex += x; ey += y; }
      out.push(`A${n(rx * k)} ${n(ry * k)} ${rot} ${large} ${sweep} ` + pt(ex, ey)); x = ex; y = ey;
    } else throw new Error('unsupported path command ' + cmd);
  }
  return out.join('');
}

const circlePath = (cx, cy, r) =>
  `M${n(cx - r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx + r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx - r)} ${n(cy)}Z`;

// ------------------------------------------------------------------ wordmark
// Set in Inter Display SemiBold, lowercase, then drawn over:
//  - spacing is set pair by pair below (font units, added to the advance);
//  - the square-ish tittles of both i's are redrawn as true circles, a quiet
//    echo of the Glint, sized to match the optical weight of the originals.
const G = JSON.parse(fs.readFileSync(path.join(HERE, 'wordmark-glyphs.json'), 'utf8'));
const WORD = ['c', 'l', 'a', 'i', 'r', 'i', 'x'];
// Hand kerning, in font units (2048/em). Tuned by eye on 1024 px renders.
const KERN = {
  'c|l': -8,
  'l|a': -6,
  'a|i': 14,
  'i|r': 6,
  'r|i': -34,
  'i|x': -8,
};
const TRACK = -24; // overall tracking for display use, about -1.2%
const TITTLE = { r: 150, cy: 1352 }; // round tittle: radius and centre height

export function wordmark({ scale = 0.05 } = {}) {
  const top = TITTLE.cy + TITTLE.r; // highest point of the word
  let pen = 0;
  const parts = [];
  WORD.forEach((ch, idx) => {
    const key = ch === 'i' ? 'dotlessi' : ch;
    const g = G.glyphs[key];
    // trim the first glyph's side bearing so the word starts at x=0
    if (idx === 0) pen = -g.lsb;
    const ox = pen;
    parts.push(transformPath(g.d, (x, y) => [(x + ox) * scale, (top - y) * scale]));
    if (ch === 'i') {
      const st = G.i.stemBounds;
      const cx = (st[0] + st[2]) / 2 + ox;
      parts.push(circlePath(cx * scale, (top - TITTLE.cy) * scale, TITTLE.r * scale));
    }
    const next = WORD[idx + 1];
    pen += g.advance + (next ? TRACK + (KERN[`${ch}|${next}`] ?? 0) : 0);
  });
  // right edge = last glyph's ink, not its advance
  const last = G.glyphs.x;
  const xInkRight = pen - last.advance + last.bounds[2];
  const yMin = Math.min(...WORD.map((ch) => G.glyphs[ch === 'i' ? 'dotlessi' : ch].bounds[1]));
  return {
    d: parts.join(''),
    width: xInkRight * scale,
    height: (top - yMin) * scale,
    baseline: top * scale,
    xHeight: G.xHeight * scale,
    scale,
  };
}

// ------------------------------------------------------------------ writers
const header = (title) => `<!-- Clairix. ${title}. Generated by brand/tools/build-logo.mjs. -->\n`;
const write = (name, body) => fs.writeFileSync(path.join(OUT, name), body);
const svgDoc = (w, h, title, inner) =>
  `${header(title)}<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n(w)} ${n(h)}" width="${n(w)}" height="${n(h)}" role="img" aria-label="Clairix">\n<title>Clairix</title>\n${inner}</svg>\n`;

function symbolGroup(master, { size = 96, ox = 0, oy = 0, ring, glint }) {
  const p = symbolPaths(master, { size, ox, oy });
  return `  <path fill="${ring}" d="${p.ring}"/>\n  <path fill="${glint}" d="${p.glint}"/>\n`;
}

// Symbol files
const R = MASTERS.regular, S = MASTERS.small;
write('symbol.svg', svgDoc(96, 96, 'Symbol, full colour, for light grounds', symbolGroup(R, { ring: COLORS.ink, glint: COLORS.glint })));
write('symbol-reversed.svg', svgDoc(96, 96, 'Symbol, full colour, for dark grounds', symbolGroup(R, { ring: COLORS.white, glint: COLORS.glintOnDark })));
write('symbol-small.svg', svgDoc(96, 96, 'Symbol, small-size master for 16-32 px, light grounds', symbolGroup(S, { ring: COLORS.ink, glint: COLORS.glint })));
write('symbol-small-reversed.svg', svgDoc(96, 96, 'Symbol, small-size master for 16-32 px, dark grounds', symbolGroup(S, { ring: COLORS.white, glint: COLORS.glintOnDark })));
write('symbol-mono-dark.svg', svgDoc(96, 96, 'Symbol, one colour, dark ink for light grounds', symbolGroup(R, { ring: COLORS.ink, glint: COLORS.ink })));
write('symbol-mono-light.svg', svgDoc(96, 96, 'Symbol, one colour, white for dark grounds', symbolGroup(R, { ring: COLORS.white, glint: COLORS.white })));

// Wordmark
const W = wordmark();
const wordFile = (fill, title) => svgDoc(W.width, W.height, title, `  <path fill="${fill}" d="${W.d}"/>\n`);
write('wordmark.svg', wordFile(COLORS.ink, 'Wordmark, dark ink'));
write('wordmark-light.svg', wordFile(COLORS.white, 'Wordmark, white for dark grounds'));

// Lockups. Proportions are tied to the wordmark's x-height (xh):
//   horizontal: symbol diameter = 1.9 xh, gap = 0.72 xh, symbol centred on the
//               x-height band (optically nudged up 0.06 xh to sit with the ascender).
//   stacked:    symbol diameter = 3.2 xh, gap = 1.1 xh, centred.
// The symbol's ink diameter is 88/96 of its artboard, so artboards are sized from that.
function lockupHorizontal(ringFill, glintFill, wordFill) {
  const xh = W.xHeight;
  const dia = 1.9 * xh; // ink diameter
  const art = (dia * 96) / 88;
  const inset = (art - dia) / 2;
  const gap = 0.72 * xh;
  const wordTop = 0;
  const xBandCentre = W.baseline - xh / 2 - 0.06 * xh;
  const symCy = xBandCentre;
  // overall box: from symbol ink top/bottom or word top/bottom
  const symTop = symCy - dia / 2, symBottom = symCy + dia / 2;
  const top = Math.min(symTop, wordTop), bottom = Math.max(symBottom, W.height);
  const oy = -top;
  const sx = -inset, sy = symTop - inset + oy;
  const wordX = dia + gap;
  const w = wordX + W.width, h = bottom - top;
  const inner =
    symbolGroup(R, { size: art, ox: sx, oy: sy, ring: ringFill, glint: glintFill }) +
    `  <path fill="${wordFill}" d="${transformPath(W.d, (x, y) => [x + wordX, y + oy])}"/>\n`;
  return { w, h, inner };
}
function lockupStacked(ringFill, glintFill, wordFill) {
  const xh = W.xHeight;
  const dia = 3.2 * xh;
  const art = (dia * 96) / 88;
  const inset = (art - dia) / 2;
  const gap = 1.1 * xh;
  const w = Math.max(dia, W.width);
  const symX = (w - dia) / 2;
  const wordY = dia + gap;
  const h = wordY + W.height;
  const inner =
    symbolGroup(R, { size: art, ox: symX - inset, oy: -inset, ring: ringFill, glint: glintFill }) +
    `  <path fill="${wordFill}" d="${transformPath(W.d, (x, y) => [x + (w - W.width) / 2, y + wordY])}"/>\n`;
  return { w, h, inner };
}
for (const [suffix, ring, glint, word, label] of [
  ['', COLORS.ink, COLORS.glint, COLORS.ink, 'for light grounds'],
  ['-reversed', COLORS.white, COLORS.glintOnDark, COLORS.white, 'for dark grounds'],
  ['-mono-dark', COLORS.ink, COLORS.ink, COLORS.ink, 'one colour, dark ink'],
  ['-mono-light', COLORS.white, COLORS.white, COLORS.white, 'one colour, white'],
]) {
  const h = lockupHorizontal(ring, glint, word);
  write(`lockup-horizontal${suffix}.svg`, svgDoc(h.w, h.h, `Horizontal lockup, ${label}`, h.inner));
  const s = lockupStacked(ring, glint, word);
  write(`lockup-stacked${suffix}.svg`, svgDoc(s.w, s.h, `Stacked lockup, ${label}`, s.inner));
}

// ------------------------------------------------------------------ app icon
// Adaptive icon: 108 dp canvas, 66 dp safe circle centred on (54,54). The
// symbol's ink circle is 52 dp across: inside the 66 dp safe zone with room to
// breathe in a circular mask, and still as large as typical launcher icons.
const ICON_INK = 52;
const iconArt = (ICON_INK * 96) / 88;
const iconOff = (108 - iconArt) / 2;
const fg = symbolPaths(R, { size: iconArt, ox: iconOff, oy: iconOff });

write(
  'app-icon-foreground.svg',
  svgDoc(108, 108, 'Adaptive icon foreground, 108 unit canvas, 66 unit safe zone', `  <path fill="${COLORS.white}" d="${fg.ring}"/>\n  <path fill="${COLORS.glintOnDark}" d="${fg.glint}"/>\n`),
);
write(
  'app-icon.svg',
  svgDoc(108, 108, 'App icon, full bleed square with ground; mask to the platform shape', `  <rect width="108" height="108" fill="${COLORS.iconGround}"/>\n  <path fill="${COLORS.white}" d="${fg.ring}"/>\n  <path fill="${COLORS.glintOnDark}" d="${fg.glint}"/>\n`),
);

const vector = (comment, paths) => `<?xml version="1.0" encoding="utf-8"?>
<!--
${comment}
    Generated by brand/tools/build-logo.mjs from brand/tools/symbol-geometry.mjs.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${paths}
</vector>
`;
const vpath = (name, color, d) => `
    <!-- ${name} -->
    <path
        android:fillColor="${color}"
        android:pathData="${d}" />
`;
fs.writeFileSync(
  path.join(RES, 'drawable/ic_launcher_fg.xml'),
  vector(
    `    Launcher icon foreground: the Clairix symbol (the Glint), a white
    eccentric ring with its point of light in Clair Blue, over the
    @color/ic_launcher_background ground.
    108dp adaptive-icon canvas. The symbol's ink circle is ${ICON_INK}dp across,
    centred on (54,54), inside the 66dp safe zone, so no launcher mask clips it.`,
    vpath('Ring', `#FF${COLORS.white.slice(1)}`, fg.ring) + vpath('Glint', `#FF${COLORS.glintOnDark.slice(1)}`, fg.glint),
  ),
);
fs.writeFileSync(
  path.join(RES, 'drawable/ic_launcher_mono.xml'),
  vector(
    `    Monochrome launcher layer for themed icons (Android 13+). The launcher
    reads only its alpha and tints it, so it is the foreground's shapes in
    solid white. Same geometry and safe-zone placement as ic_launcher_fg.`,
    vpath('Ring', '#FFFFFFFF', fg.ring) + vpath('Glint', '#FFFFFFFF', fg.glint),
  ),
);
fs.writeFileSync(
  path.join(RES, 'values/colors.xml'),
  `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Background colour for the adaptive launcher icon: Clairix Ink, ${tokens.color.primitive.ink.oklch}. -->
    <color name="ic_launcher_background">${COLORS.iconGround}</color>
</resources>
`,
);

console.log(`logo: wordmark ${n(W.width)} x ${n(W.height)}, files in ${path.relative(ROOT, OUT)}`);
