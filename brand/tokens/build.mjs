#!/usr/bin/env node
// Regenerates the Clairix token outputs from tokens.json.
//
//   node brand/tokens/build.mjs          check hex values, write outputs
//   node brand/tokens/build.mjs --sync   first rewrite every hex from its OKLCH value
//
// Outputs (next to this file):
//   tokens.css        CSS custom properties, light + dark
//   ClairixTheme.kt   Jetpack Compose theme (package app.clairix.ui.theme)
//   contrast.json     WCAG 2.x ratios for every pair in color.contrastPairs
//
// No dependencies beyond Node 18+.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { oklchToHex, parseOklch, contrast } from '../tools/color.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, 'tokens.json');
const sync = process.argv.includes('--sync');

const tokens = JSON.parse(fs.readFileSync(SRC, 'utf8'));
const isMeta = (k) => k.startsWith('$');

// ---------------------------------------------------------------- colours
// Flatten primitives to { 'blue.500': {oklch, hex}, white: {...} }.
const prim = {};
(function walk(node, prefix) {
  for (const [k, v] of Object.entries(node)) {
    if (isMeta(k)) continue;
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object' && 'oklch' in v) prim[key] = v;
    else if (v && typeof v === 'object') walk(v, key);
  }
})(tokens.color.primitive, '');

let mismatches = 0;
for (const [key, v] of Object.entries(prim)) {
  const { L, C, h } = parseOklch(v.oklch);
  const hex = oklchToHex(L, C, h);
  if (hex !== v.hex) {
    if (sync) v.hex = hex;
    else {
      mismatches++;
      console.error(`hex mismatch ${key}: ${v.hex} in file, ${hex} from ${v.oklch}`);
    }
  }
}
if (mismatches) {
  console.error(`\n${mismatches} hex value(s) out of date. Run with --sync.`);
  process.exit(1);
}
if (sync) fs.writeFileSync(SRC, JSON.stringify(tokens, null, 2) + '\n');

const modes = ['light', 'dark'];
const sem = (mode) =>
  Object.fromEntries(
    Object.entries(tokens.color.semantic[mode])
      .filter(([k]) => !isMeta(k))
      .map(([k, ref]) => {
        if (!prim[ref]) throw new Error(`semantic ${mode}.${k} references unknown primitive ${ref}`);
        return [k, { ref, ...prim[ref] }];
      }),
  );
const semantic = { light: sem('light'), dark: sem('dark') };

// ---------------------------------------------------------------- contrast
const report = { generated: 'node brand/tokens/build.mjs', standard: 'WCAG 2.x relative luminance', modes: {} };
let failures = 0;
for (const mode of modes) {
  report.modes[mode] = tokens.color.contrastPairs.pairs.map(([fg, bg, min]) => {
    const a = semantic[mode][fg], b = semantic[mode][bg];
    const ratio = Math.round(contrast(a.hex, b.hex) * 100) / 100;
    const pass = ratio >= min;
    if (!pass) {
      failures++;
      console.error(`contrast FAIL ${mode}: ${fg} ${a.hex} on ${bg} ${b.hex} = ${ratio} (< ${min})`);
    }
    return { fg, bg, fgHex: a.hex, bgHex: b.hex, ratio, min, aa: ratio >= 4.5, aaLarge: ratio >= 3, pass };
  });
}
fs.writeFileSync(path.join(HERE, 'contrast.json'), JSON.stringify(report, null, 2) + '\n');

// ---------------------------------------------------------------- CSS
const kebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
const cssVar = (key) => `--clx-${key.replace('.', '-')}`;
const bezier = (b) => `cubic-bezier(${b.join(', ')})`;

let css = `/* Clairix design tokens. Generated from tokens.json by build.mjs. Do not edit by hand. */\n\n`;
css += `:root {\n  /* Primitives (hex fallback; OKLCH below where supported) */\n`;
for (const [k, v] of Object.entries(prim)) css += `  ${cssVar(k)}: ${v.hex};\n`;
css += `\n  /* Type */\n`;
for (const [k, f] of Object.entries(tokens.font.family)) css += `  --clx-font-${k}: ${f.stack};\n`;
for (const [k, w] of Object.entries(tokens.font.weight)) css += `  --clx-weight-${k}: ${w};\n`;
for (const [k, t] of Object.entries(tokens.font.scale)) {
  if (isMeta(k)) continue;
  const n = kebab(k);
  css += `  --clx-text-${n}-size: ${t.size / 16}rem;\n`;
  css += `  --clx-text-${n}-line: ${t.lineHeight / 16}rem;\n`;
  css += `  --clx-text-${n}-weight: ${t.weight};\n`;
  css += `  --clx-text-${n}-tracking: ${t.tracking}em;\n`;
}
css += `\n  /* Space, radius */\n`;
for (const [k, v] of Object.entries(tokens.space)) if (!isMeta(k)) css += `  --clx-space-${k}: ${v / 16}rem;\n`;
for (const [k, v] of Object.entries(tokens.radius)) if (!isMeta(k)) css += `  --clx-radius-${k}: ${v === 9999 ? '9999px' : v / 16 + 'rem'};\n`;
css += `\n  /* Elevation */\n`;
for (const [k, v] of Object.entries(tokens.elevation)) if (!isMeta(k)) css += `  --clx-shadow-${k}: ${v.shadow};\n`;
css += `\n  /* Motion */\n`;
for (const [k, v] of Object.entries(tokens.motion.duration)) css += `  --clx-duration-${k}: ${v}ms;\n`;
for (const [k, v] of Object.entries(tokens.motion.easing)) css += `  --clx-ease-${k}: ${bezier(v)};\n`;
css += `}\n\n@supports (color: oklch(0 0 0)) {\n  :root {\n`;
for (const [k, v] of Object.entries(prim)) css += `    ${cssVar(k)}: ${v.oklch};\n`;
css += `  }\n}\n\n`;

const semBlock = (mode, indent) =>
  Object.entries(semantic[mode])
    .map(([k, v]) => `${indent}--clx-${kebab(k)}: var(${cssVar(v.ref)});`)
    .join('\n');
css += `/* Semantic roles: light is the default */\n:root,\n[data-theme="light"] {\n  color-scheme: light;\n${semBlock('light', '  ')}\n}\n\n`;
css += `@media (prefers-color-scheme: dark) {\n  :root:not([data-theme="light"]) {\n    color-scheme: dark;\n${semBlock('dark', '    ')}\n  }\n}\n\n`;
css += `[data-theme="dark"] {\n  color-scheme: dark;\n${semBlock('dark', '  ')}\n}\n\n`;
css += `@media (prefers-reduced-motion: reduce) {\n  :root {\n${Object.keys(tokens.motion.duration).map((k) => `    --clx-duration-${k}: 0ms;`).join('\n')}\n  }\n}\n`;
fs.writeFileSync(path.join(HERE, 'tokens.css'), css);

// ---------------------------------------------------------------- Kotlin
const argb = (hex) => `Color(0xFF${hex.slice(1)})`;
const kName = (key) =>
  key
    .split('.')
    .map((p, i) => (i === 0 ? p[0].toUpperCase() + p.slice(1) : p))
    .join('');

// Material 3 ColorScheme slots -> semantic roles (or primitives prefixed with '#').
const m3 = {
  light: {
    primary: 'primary', onPrimary: 'onPrimary', primaryContainer: 'primaryContainer', onPrimaryContainer: 'onPrimaryContainer',
    inversePrimary: '#blue.300',
    secondary: '#neutral.700', onSecondary: '#white', secondaryContainer: '#neutral.100', onSecondaryContainer: '#neutral.800',
    tertiary: 'info', onTertiary: 'onInfo', tertiaryContainer: 'infoContainer', onTertiaryContainer: 'onInfoContainer',
    background: 'background', onBackground: 'text',
    surface: 'surface', onSurface: 'text', surfaceVariant: 'surfaceMuted', onSurfaceVariant: 'textSecondary',
    surfaceTint: 'primary', inverseSurface: '#neutral.900', inverseOnSurface: '#neutral.50',
    error: 'danger', onError: 'onDanger', errorContainer: 'dangerContainer', onErrorContainer: 'onDangerContainer',
    outline: 'borderStrong', outlineVariant: 'border', scrim: 'scrim',
    surfaceBright: '#white', surfaceDim: '#neutral.200',
    surfaceContainerLowest: '#white', surfaceContainerLow: '#neutral.50', surfaceContainer: '#neutral.100',
    surfaceContainerHigh: '#neutral.200', surfaceContainerHighest: '#neutral.300',
  },
  dark: {
    primary: 'primary', onPrimary: 'onPrimary', primaryContainer: 'primaryContainer', onPrimaryContainer: 'onPrimaryContainer',
    inversePrimary: '#blue.600',
    secondary: '#neutral.300', onSecondary: '#neutral.900', secondaryContainer: '#neutral.800', onSecondaryContainer: '#neutral.100',
    tertiary: 'info', onTertiary: 'onInfo', tertiaryContainer: 'infoContainer', onTertiaryContainer: 'onInfoContainer',
    background: 'background', onBackground: 'text',
    surface: 'surface', onSurface: 'text', surfaceVariant: 'surfaceMuted', onSurfaceVariant: 'textSecondary',
    surfaceTint: 'primary', inverseSurface: '#neutral.100', inverseOnSurface: '#neutral.900',
    error: 'danger', onError: 'onDanger', errorContainer: 'dangerContainer', onErrorContainer: 'onDangerContainer',
    outline: 'borderStrong', outlineVariant: 'border', scrim: 'scrim',
    surfaceBright: '#neutral.800', surfaceDim: '#ink',
    surfaceContainerLowest: '#ink', surfaceContainerLow: '#neutral.950', surfaceContainer: '#neutral.900',
    surfaceContainerHigh: '#neutral.800', surfaceContainerHighest: '#neutral.700',
  },
};
const resolve = (mode, v) => (v.startsWith('#') ? { ref: v.slice(1), ...prim[v.slice(1)] } : semantic[mode][v]);

const ease = (b) => `CubicBezierEasing(${b.map((x) => `${x}f`).join(', ')})`;
const famOf = (t) => (t.family === 'display' ? 'InterDisplay' : t.family === 'mono' ? 'JetBrainsMono' : 'Inter');
const weightName = { 400: 'Normal', 500: 'Medium', 600: 'SemiBold', 700: 'Bold' };
const styleOf = (t) =>
  `TextStyle(\n    fontFamily = ${famOf(t)},\n    fontWeight = FontWeight.${weightName[t.weight]},\n    fontSize = ${t.size}.sp,\n    lineHeight = ${t.lineHeight}.sp,\n    letterSpacing = ${t.tracking === 0 ? '0' : `(${t.tracking})`}.em,\n)`;

let kt = `// Clairix theme for Jetpack Compose.
// Generated from brand/tokens/tokens.json by brand/tokens/build.mjs. Do not edit by hand.
//
// To use: copy this file into app/src/main/java/app/clairix/ui/theme/, and copy
// these fonts from brand/fonts into app/src/main/res/font/ (lowercase names):
//   inter/static/Inter-Regular.ttf          -> inter_regular.ttf
//   inter/static/Inter-Medium.ttf           -> inter_medium.ttf
//   inter/static/Inter-SemiBold.ttf         -> inter_semibold.ttf
//   inter/static/Inter-Bold.ttf             -> inter_bold.ttf
//   inter/static/InterDisplay-Medium.ttf    -> inter_display_medium.ttf
//   inter/static/InterDisplay-SemiBold.ttf  -> inter_display_semibold.ttf
//   jetbrains-mono/static/JetBrainsMono-Regular.ttf -> jetbrains_mono_regular.ttf
//   jetbrains-mono/static/JetBrainsMono-Medium.ttf  -> jetbrains_mono_medium.ttf
// Then wrap the app content in ClairixTheme { ... }.

package app.clairix.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.clairix.R

/** Raw palette. Prefer MaterialTheme.colorScheme or ClairixTheme.colors in UI code. */
object ClairixPalette {
`;
for (const [k, v] of Object.entries(prim)) kt += `    /** ${v.oklch} */\n    val ${kName(k)} = ${argb(v.hex)}\n`;
kt += `}\n\n`;

for (const mode of modes) {
  const fn = mode === 'light' ? 'lightColorScheme' : 'darkColorScheme';
  kt += `val Clairix${mode === 'light' ? 'Light' : 'Dark'}ColorScheme: ColorScheme = ${fn}(\n`;
  for (const [slot, v] of Object.entries(m3[mode])) {
    const r = resolve(mode, v);
    kt += `    ${slot} = ClairixPalette.${kName(r.ref)},\n`;
  }
  kt += `)\n\n`;
}

// Status colours Material does not have.
const extra = ['glint', 'safe', 'onSafe', 'safeContainer', 'onSafeContainer', 'caution', 'onCaution', 'cautionContainer', 'onCautionContainer', 'info', 'onInfo', 'infoContainer', 'onInfoContainer', 'textTertiary', 'border'];
kt += `/** Clairix roles that Material 3 has no slot for: the Glint accent and the status colours. */\n@Immutable\ndata class ClairixColors(\n${extra.map((k) => `    val ${k}: Color,`).join('\n')}\n)\n\n`;
for (const mode of modes) {
  kt += `val Clairix${mode === 'light' ? 'Light' : 'Dark'}Colors = ClairixColors(\n${extra.map((k) => `    ${k} = ClairixPalette.${kName(semantic[mode][k].ref)},`).join('\n')}\n)\n\n`;
}

kt += `val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Inter's display optical size, for text 20 sp and larger. */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
)

/** Machine values only: MAC addresses, cell IDs, fingerprints. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** The full Clairix type scale, including steps Material has no slot for. */
object ClairixType {
`;
for (const [k, t] of Object.entries(tokens.font.scale)) {
  if (isMeta(k) || t.web) continue;
  kt += `    val ${k} = ${styleOf(t).replace(/\n/g, '\n    ')}\n`;
}
kt += `}\n\nval ClairixTypography = Typography(\n`;
for (const [slot, ref] of Object.entries(tokens.font.material3)) {
  if (isMeta(slot)) continue;
  kt += `    ${slot} = ClairixType.${ref},\n`;
}
kt += `)\n\n`;

const R = tokens.radius;
kt += `val ClairixShapes = Shapes(
    extraSmall = RoundedCornerShape(${R.xs}.dp),
    small = RoundedCornerShape(${R.sm}.dp),
    medium = RoundedCornerShape(${R.md}.dp),
    large = RoundedCornerShape(${R.lg}.dp),
    extraLarge = RoundedCornerShape(${R.xl}.dp),
)

/** 4 dp spacing grid. */
object ClairixSpace {
${Object.entries(tokens.space).filter(([k]) => !isMeta(k)).map(([k, v]) => `    val s${k} = ${v}.dp`).join('\n')}
}

/** Elevation levels in dp. Depth is carried mostly by surface colour. */
object ClairixElevation {
${Object.entries(tokens.elevation).filter(([k]) => !isMeta(k)).map(([k, v]) => `    val level${k} = ${v.dp}.dp`).join('\n')}
}

/** Calm motion: short, decelerating, never bouncy. Durations in milliseconds. */
object ClairixMotion {
${Object.entries(tokens.motion.duration).map(([k, v]) => `    const val ${k} = ${v}`).join('\n')}
${Object.entries(tokens.motion.easing).map(([k, v]) => `    val ${k} = ${ease(v)}`).join('\n')}
}

val LocalClairixColors = staticCompositionLocalOf { ClairixLightColors }

@Composable
fun ClairixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalClairixColors provides if (darkTheme) ClairixDarkColors else ClairixLightColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) ClairixDarkColorScheme else ClairixLightColorScheme,
            typography = ClairixTypography,
            shapes = ClairixShapes,
            content = content,
        )
    }
}

/** Access Clairix-only roles: ClairixTheme.colors.safe, ClairixTheme.colors.glint ... */
object ClairixTheme {
    val colors: ClairixColors
        @Composable get() = LocalClairixColors.current
}
`;
fs.writeFileSync(path.join(HERE, 'ClairixTheme.kt'), kt);

console.log(`tokens: ${Object.keys(prim).length} primitives, contrast pairs checked ${tokens.color.contrastPairs.pairs.length * 2}, failures ${failures}`);
if (failures) process.exit(1);
