// Clairix symbol: "the Glint".
// An eccentric ring (a C whose counter is shifted toward its opening, so the
// stroke is heavy at the lower left and thins toward the light) plus one
// round point of light sitting in the opening.
//
// Everything is built from three circles and two parallel lines, on a 96-unit
// artboard centred on (48,48), then rotated so the opening faces the upper
// right. All output is plain path data: no masks, no strokes, no transforms,
// so the same numbers drive SVG and Android vector drawables.

export const MASTERS = {
  // Default master, used from 40 px up.
  regular: { R: 44, ri: 26.5, off: 6.75, angle: -45, rd: 8, dotR: 34.75, clear: 5 },
  // Small-size master (16-32 px): more open counter and channel, heavier
  // thin side, larger glint, so the shapes survive pixel snapping.
  small: { R: 46, ri: 25, off: 5.5, angle: -45, rd: 10, dotR: 35, clear: 6.5 },
};

const n = (v) => {
  const s = (+v.toFixed(3)).toString();
  return s === '-0' ? '0' : s;
};

/**
 * Returns { ring, glint } path strings plus the glint circle, for the given
 * master scaled to `size` and translated to (ox, oy).
 */
export function symbolPaths(master = MASTERS.regular, { size = 96, ox = 0, oy = 0 } = {}) {
  const { R, ri, off, angle, rd, dotR, clear } = master;
  const s = size / 96;
  const a = (angle * Math.PI) / 180;
  const cos = Math.cos(a), sin = Math.sin(a);
  // local frame: opening along +x, y down. Map to artboard.
  const T = ([x, y]) => [ox + (48 + x * cos - y * sin) * s, oy + (48 + x * sin + y * cos) * s];
  const w = rd + clear; // half-width of the channel
  const xo = Math.sqrt(R * R - w * w);
  const xi = off + Math.sqrt(ri * ri - w * w);
  const p1 = T([xo, w]), p2 = T([xo, -w]), p3 = T([xi, -w]), p4 = T([xi, w]);
  const Rs = R * s, ris = ri * s;
  const ring =
    `M${n(p1[0])} ${n(p1[1])}` +
    `A${n(Rs)} ${n(Rs)} 0 1 1 ${n(p2[0])} ${n(p2[1])}` +
    `L${n(p3[0])} ${n(p3[1])}` +
    `A${n(ris)} ${n(ris)} 0 1 0 ${n(p4[0])} ${n(p4[1])}Z`;
  const c = T([dotR, 0]);
  const r = rd * s;
  // circle as two arcs, so it also works as Android pathData
  const glint =
    `M${n(c[0] - r)} ${n(c[1])}` +
    `A${n(r)} ${n(r)} 0 1 0 ${n(c[0] + r)} ${n(c[1])}` +
    `A${n(r)} ${n(r)} 0 1 0 ${n(c[0] - r)} ${n(c[1])}Z`;
  return { ring, glint, cx: c[0], cy: c[1], r };
}
