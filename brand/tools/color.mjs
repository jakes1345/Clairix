// OKLCH <-> sRGB conversion and WCAG 2.x contrast. No dependencies.
// Reference: Björn Ottosson, "A perceptual color space for image processing" (2020).

const cbrt = Math.cbrt;

export function oklchToOklab(L, C, h) {
  const r = (h * Math.PI) / 180;
  return [L, C * Math.cos(r), C * Math.sin(r)];
}

export function oklabToLinearSrgb([L, a, b]) {
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
}

export function linearSrgbToOklab([r, g, b]) {
  const l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

const toGamma = (x) => (x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055);
const toLinear = (x) => (x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4);
const inGamut = (rgb) => rgb.every((v) => v >= -1e-4 && v <= 1 + 1e-4);

/** OKLCH -> hex. Out-of-gamut colours are brought in by reducing chroma at constant L and h. */
export function oklchToHex(L, C, h) {
  let lo = 0, hi = C, c = C;
  let lin = oklabToLinearSrgb(oklchToOklab(L, c, h));
  if (!inGamut(lin)) {
    for (let i = 0; i < 40; i++) {
      c = (lo + hi) / 2;
      lin = oklabToLinearSrgb(oklchToOklab(L, c, h));
      if (inGamut(lin)) lo = c; else hi = c;
    }
    lin = oklabToLinearSrgb(oklchToOklab(L, lo, h));
  }
  return (
    '#' +
    lin
      .map((v) => Math.round(Math.min(1, Math.max(0, toGamma(v))) * 255).toString(16).padStart(2, '0'))
      .join('')
      .toUpperCase()
  );
}

export function hexToRgb(hex) {
  const n = hex.replace('#', '');
  return [0, 2, 4].map((i) => parseInt(n.slice(i, i + 2), 16) / 255);
}

export function hexToOklch(hex) {
  const [L, a, b] = linearSrgbToOklab(hexToRgb(hex).map(toLinear));
  const C = Math.hypot(a, b);
  let h = (Math.atan2(b, a) * 180) / Math.PI;
  if (h < 0) h += 360;
  return [L, C, h];
}

export function parseOklch(str) {
  const m = /^oklch\(\s*([\d.]+)(%?)\s+([\d.]+)\s+([\d.]+)\s*(?:\/\s*([\d.]+)(%?))?\s*\)$/.exec(str.trim());
  if (!m) throw new Error(`Not an oklch() value: ${str}`);
  const L = m[2] ? parseFloat(m[1]) / 100 : parseFloat(m[1]);
  let alpha = 1;
  if (m[5] !== undefined) alpha = m[6] ? parseFloat(m[5]) / 100 : parseFloat(m[5]);
  return { L, C: parseFloat(m[3]), h: parseFloat(m[4]), alpha };
}

export function luminance(hex) {
  const [r, g, b] = hexToRgb(hex).map(toLinear);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}
