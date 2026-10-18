#!/usr/bin/env node
// Regenerates every Clairix token output from tokens.json.
//
//   node brand/tokens/build.mjs          check hex values and contrast, write outputs
//   node brand/tokens/build.mjs --sync   first rewrite every hex from its OKLCH value
//
// Outputs:
//   brand/tokens/tokens.css      CSS custom properties, one block per [data-theme]
//   brand/tokens/contrast.json   WCAG 2.x ratio for every checked pair in every theme
//   android/app/src/main/java/app/clairix/ui/theme/ClairixTheme.kt
//   android/app/src/main/res/font/*.ttf   (copied from brand/fonts)
//
// The build fails if any text pair in any theme is below 4.5:1, or if a hex
// value no longer matches its OKLCH source. No dependencies beyond Node 18+.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { oklchToHex, parseOklch, contrast } from '../tools/color.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BRAND = path.resolve(HERE, '..');
const ROOT = path.resolve(BRAND, '..');
const SRC = path.join(HERE, 'tokens.json');
const KT = path.join(ROOT, 'android/app/src/main/java/app/clairix/ui/theme/ClairixTheme.kt');
const RES_FONT = path.join(ROOT, 'android/app/src/main/res/font');
const sync = process.argv.includes('--sync');

const tokens = JSON.parse(fs.readFileSync(SRC, 'utf8'));
const isMeta = (k) => k.startsWith('$');
const T = tokens.color.themes;
const THEMES = Object.keys(T);

// ---------------------------------------------------------------- colours
// A role is either {oklch, hex} or the name of another role in the same theme.
let mismatches = 0;
for (const [name, theme] of Object.entries(T)) {
  for (const [role, v] of Object.entries(theme)) {
    if (!v || typeof v !== 'object' || !('oklch' in v)) continue;
    const { L, C, h } = parseOklch(v.oklch);
    const hex = oklchToHex(L, C, h);
    if (v.hex !== hex) {
      if (sync) v.hex = hex;
      else {
        mismatches++;
        console.error(`hex mismatch ${name}.${role}: ${v.hex} in file, ${hex} from ${v.oklch}`);
      }
    }
  }
}
if (mismatches) {
  console.error(`\n${mismatches} hex value(s) out of date. Run with --sync.`);
  process.exit(1);
}
if (sync) fs.writeFileSync(SRC, JSON.stringify(tokens, null, 2) + '\n');

const ROLES = Object.keys(T[tokens.color.defaultTheme]).filter((k) => !['label', 'description'].includes(k));
function resolve(theme, role, seen = []) {
  const v = T[theme][role];
  if (v === undefined) throw new Error(`theme ${theme} has no role ${role}`);
  if (typeof v === 'string') {
    if (seen.includes(v)) throw new Error(`reference loop in ${theme}: ${[...seen, v].join(' -> ')}`);
    return { ...resolve(theme, v, [...seen, role]), ref: v };
  }
  return v;
}
for (const t of THEMES) for (const r of ROLES) resolve(t, r); // every theme must define every role

// ---------------------------------------------------------------- contrast
const report = { generated: 'node brand/tokens/build.mjs', standard: 'WCAG 2.x, AA body text 4.5:1', themes: {} };
let failures = 0;
for (const t of THEMES) {
  report.themes[t] = tokens.color.contrastPairs.pairs.map(([fg, bg]) => {
    const a = resolve(t, fg).hex, b = resolve(t, bg).hex;
    const ratio = Math.round(contrast(a, b) * 100) / 100;
    if (ratio < 4.5) {
      failures++;
      console.error(`contrast FAIL ${t}: ${fg} ${a} on ${bg} ${b} = ${ratio}`);
    }
    return { fg, bg, fgHex: a, bgHex: b, ratio, aa: ratio >= 4.5, aaa: ratio >= 7 };
  });
}
fs.writeFileSync(path.join(HERE, 'contrast.json'), JSON.stringify(report, null, 2) + '\n');
if (failures) {
  console.error(`\n${failures} contrast failure(s). Nothing else written.`);
  process.exit(1);
}

// ---------------------------------------------------------------- CSS
const kebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
const F = tokens.font;
let css = `/* Clairix design tokens. Generated from tokens.json by build.mjs. Do not edit by hand.
   Dark-only. Themes: ${THEMES.join(', ')} (default ${tokens.color.defaultTheme}). Set data-theme on <html>. */\n\n`;
css += `:root {\n  color-scheme: dark;\n\n  /* Type */\n`;
for (const [k, f] of Object.entries(F.family)) css += `  --clx-font-${k}: ${f.stack};\n  --clx-stretch-${k}: ${f.stretch};\n`;
for (const [k, t] of Object.entries(F.scale)) {
  if (isMeta(k)) continue;
  const n = kebab(k);
  css += `  --clx-text-${n}: ${t.weight} ${t.size / 16}rem/${t.lineHeight / 16}rem var(--clx-font-${t.family});\n`;
  css += `  --clx-text-${n}-stretch: var(--clx-stretch-${t.family});\n`;
  css += `  --clx-text-${n}-tracking: ${t.tracking}em;\n`;
  if (t.case === 'upper') css += `  --clx-text-${n}-case: uppercase;\n`;
}
css += `\n  /* Space, radius */\n`;
for (const [k, v] of Object.entries(tokens.space)) if (!isMeta(k)) css += `  --clx-space-${k}: ${v / 16}rem;\n`;
for (const [k, v] of Object.entries(tokens.radius)) if (!isMeta(k)) css += `  --clx-radius-${k}: ${v === 9999 ? '9999px' : v + 'px'};\n`;
css += `\n  /* Elevation */\n`;
for (const [k, v] of Object.entries(tokens.elevation)) if (!isMeta(k)) css += `  --clx-shadow-${k}: ${v.shadow};\n`;
css += `\n  /* Motion */\n`;
for (const [k, v] of Object.entries(tokens.motion.duration)) css += `  --clx-duration-${k}: ${v}ms;\n`;
for (const [k, v] of Object.entries(tokens.motion.easing)) css += `  --clx-ease-${k}: cubic-bezier(${v.join(', ')});\n`;
css += `}\n`;

const themeBlock = (t, fmt) =>
  ROLES.map((r) => {
    const v = T[t][r];
    if (typeof v === 'string') return `  --clx-${kebab(r)}: var(--clx-${kebab(v)});`;
    return `  --clx-${kebab(r)}: ${fmt === 'oklch' ? v.oklch : v.hex};`;
  }).join('\n');
for (const t of THEMES) {
  const sel = t === tokens.color.defaultTheme ? `:root,\n[data-theme="${t}"]` : `[data-theme="${t}"]`;
  css += `\n/* ${T[t].label}: ${T[t].description} */\n${sel} {\n${themeBlock(t, 'hex')}\n}\n`;
  css += `@supports (color: oklch(0 0 0)) {\n  ${sel.replace('\n', '\n  ')} {\n${themeBlock(t, 'oklch').replace(/^/gm, '  ')}\n  }\n}\n`;
}
css += `\n@media (prefers-reduced-motion: reduce) {\n  :root {\n${Object.keys(tokens.motion.duration).map((k) => `    --clx-duration-${k}: 0ms;`).join('\n')}\n  }\n}\n`;
fs.writeFileSync(path.join(HERE, 'tokens.css'), css);

// ---------------------------------------------------------------- fonts for Android
const FONTS = {
  archivo_regular: 'archivo/static/Archivo-Regular.ttf',
  archivo_medium: 'archivo/static/Archivo-Medium.ttf',
  archivo_semibold: 'archivo/static/Archivo-SemiBold.ttf',
  archivo_bold: 'archivo/static/Archivo-Bold.ttf',
  archivo_expanded_bold: 'archivo/static/ArchivoExpanded-Bold.ttf',
  archivo_expanded_extrabold: 'archivo/static/ArchivoExpanded-ExtraBold.ttf',
  archivo_expanded_black: 'archivo/static/ArchivoExpanded-Black.ttf',
  jetbrains_mono_regular: 'jetbrains-mono/static/JetBrainsMono-Regular.ttf',
  jetbrains_mono_medium: 'jetbrains-mono/static/JetBrainsMono-Medium.ttf',
};
fs.mkdirSync(RES_FONT, { recursive: true });
for (const [res, src] of Object.entries(FONTS)) fs.copyFileSync(path.join(BRAND, 'fonts', src), path.join(RES_FONT, `${res}.ttf`));

// ---------------------------------------------------------------- Kotlin
const argb = (hex) => `Color(0xFF${hex.slice(1)})`;
const Up = (s) => s[0].toUpperCase() + s.slice(1);
const M3 = {
  primary: 'accent', onPrimary: 'onAccent', primaryContainer: 'accentContainer', onPrimaryContainer: 'onAccentContainer',
  inversePrimary: 'accent',
  secondary: 'textMuted', onSecondary: 'base', secondaryContainer: 'surfaceOverlay', onSecondaryContainer: 'text',
  tertiary: 'info', onTertiary: 'onInfo', tertiaryContainer: 'infoContainer', onTertiaryContainer: 'onInfoContainer',
  background: 'base', onBackground: 'text', surface: 'base', onSurface: 'text',
  surfaceVariant: 'surfaceRaised', onSurfaceVariant: 'textMuted', surfaceTint: 'accent',
  inverseSurface: 'text', inverseOnSurface: 'base',
  error: 'danger', onError: 'onDanger', errorContainer: 'dangerContainer', onErrorContainer: 'onDangerContainer',
  outline: 'borderStrong', outlineVariant: 'border',
  surfaceBright: 'surfaceOverlay', surfaceDim: 'base',
  surfaceContainerLowest: 'base', surfaceContainerLow: 'surface', surfaceContainer: 'surface',
  surfaceContainerHigh: 'surfaceRaised', surfaceContainerHighest: 'surfaceOverlay',
};
const weightName = { 400: 'Normal', 500: 'Medium', 600: 'SemiBold', 700: 'Bold', 800: 'ExtraBold', 900: 'Black' };
const famName = { display: 'ArchivoExpanded', ui: 'Archivo', mono: 'JetBrainsMono' };

let kt = `// Clairix theme for Jetpack Compose.
// Generated from brand/tokens/tokens.json by brand/tokens/build.mjs. Do not edit by hand.
//
// Dark-only, four colour themes (${THEMES.map((t) => T[t].label).join(', ')}); ${T[tokens.color.defaultTheme].label} is the default.
// The logo and app icon never change with the theme: only these colours do.
// Fonts live in res/font (Archivo, Archivo Expanded, JetBrains Mono; SIL OFL).
// Usage: ClairixTheme(theme = ClairixThemeName.ACID) { ... }

package app.clairix.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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

/** The selectable colour themes. ACID is the brand default. */
enum class ClairixThemeName(val label: String) {
${THEMES.map((t) => `    ${t.toUpperCase()}("${T[t].label}"),`).join('\n')}
}

/** Every colour role of a Clairix theme, including those Material 3 has no slot for. */
@Immutable
data class ClairixColors(
${ROLES.map((r) => `    val ${r}: Color,`).join('\n')}
)

`;
for (const t of THEMES) {
  kt += `/** ${T[t].label}: ${T[t].description} */\nval ${Up(t)}Colors = ClairixColors(\n`;
  kt += ROLES.map((r) => {
    const v = resolve(t, r);
    return `    ${r} = ${argb(v.hex)},${v.ref ? ` // = ${v.ref}` : v.oklch ? ` // ${v.oklch}` : ''}`;
  }).join('\n');
  kt += `\n)\n\n`;
}
kt += `fun ClairixThemeName.colors(): ClairixColors = when (this) {
${THEMES.map((t) => `    ClairixThemeName.${t.toUpperCase()} -> ${Up(t)}Colors`).join('\n')}
}

/** Maps a Clairix theme onto a Material 3 dark ColorScheme. */
fun ClairixColors.toColorScheme(): ColorScheme = darkColorScheme(
${Object.entries(M3).map(([slot, role]) => `    ${slot} = ${role},`).join('\n')}
    scrim = Color.Black,
)

/** Archivo at normal width: body, labels, buttons. */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

/** Archivo Expanded: headlines and display. Set these styles in capitals. */
val ArchivoExpanded = FontFamily(
    Font(R.font.archivo_expanded_bold, FontWeight.Bold),
    Font(R.font.archivo_expanded_extrabold, FontWeight.ExtraBold),
    Font(R.font.archivo_expanded_black, FontWeight.Black),
)

/** Machine values only: MAC addresses, cell IDs, fingerprints. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/**
 * The full Clairix type scale. Styles marked UPPERCASE are always set in
 * capitals: pass text.uppercase(), since TextStyle has no text-transform.
 */
object ClairixType {
`;
for (const [k, t] of Object.entries(F.scale)) {
  if (isMeta(k) || t.web) continue;
  kt += `    ${t.case === 'upper' ? '/** UPPERCASE */\n    ' : ''}val ${k} = TextStyle(
        fontFamily = ${famName[t.family]},
        fontWeight = FontWeight.${weightName[t.weight]},
        fontSize = ${t.size}.sp,
        lineHeight = ${t.lineHeight}.sp,
        letterSpacing = ${t.tracking === 0 ? '0' : `(${t.tracking})`}.em,
    )
`;
}
kt += `}

val ClairixTypography = Typography(
${Object.entries(F.material3).filter(([k]) => !isMeta(k)).map(([slot, ref]) => `    ${slot} = ClairixType.${ref},`).join('\n')}
)

val ClairixShapes = Shapes(
    extraSmall = RoundedCornerShape(${tokens.radius.xs}.dp),
    small = RoundedCornerShape(${tokens.radius.sm}.dp),
    medium = RoundedCornerShape(${tokens.radius.md}.dp),
    large = RoundedCornerShape(${tokens.radius.lg}.dp),
    extraLarge = RoundedCornerShape(${tokens.radius.xl}.dp),
)

/** The brand's cut corner, from the octagon in the logo. For badges, tags and hero cards. */
val ClairixChamfer = CutCornerShape(${tokens.radius.chamfer}.dp)

/** 4 dp spacing grid. */
object ClairixSpace {
${Object.entries(tokens.space).filter(([k]) => !isMeta(k)).map(([k, v]) => `    val s${k} = ${v}.dp`).join('\n')}
}

/** Elevation in dp. On black, depth is mostly the surface step. */
object ClairixElevation {
${Object.entries(tokens.elevation).filter(([k]) => !isMeta(k)).map(([k, v]) => `    val level${k} = ${v.dp}.dp`).join('\n')}
}

/** Fast and decisive, never bouncy. Durations in milliseconds. */
object ClairixMotion {
${Object.entries(tokens.motion.duration).map(([k, v]) => `    const val ${k} = ${v}`).join('\n')}
${Object.entries(tokens.motion.easing).map(([k, v]) => `    val ${k} = CubicBezierEasing(${v.map((x) => `${x}f`).join(', ')})`).join('\n')}
}

val LocalClairixColors = staticCompositionLocalOf { ${Up(tokens.color.defaultTheme)}Colors }

@Composable
fun ClairixTheme(
    theme: ClairixThemeName = ClairixThemeName.${tokens.color.defaultTheme.toUpperCase()},
    content: @Composable () -> Unit,
) {
    val colors = theme.colors()
    CompositionLocalProvider(LocalClairixColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = ClairixTypography,
            shapes = ClairixShapes,
            content = content,
        )
    }
}

/** Clairix-only roles: ClairixTheme.colors.safe, .caution, .textSubtle ... */
object ClairixTheme {
    val colors: ClairixColors
        @Composable get() = LocalClairixColors.current
}
`;
fs.mkdirSync(path.dirname(KT), { recursive: true });
fs.writeFileSync(KT, kt);

const n = Object.values(report.themes).reduce((a, r) => a + r.length, 0);
console.log(`tokens: ${THEMES.length} themes x ${ROLES.length} roles, ${n} contrast pairs checked, all >= 4.5`);
