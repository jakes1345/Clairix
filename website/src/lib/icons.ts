/**
 * Clairix icon set. Drawn on a 24 px grid with a 2 px stroke, square caps and
 * mitred joins, using the same cut-corner (chamfer) geometry as the logo's
 * octagon C. Each icon is a list of path strings; `fill:` prefixed paths are
 * filled instead of stroked.
 */

/** Chamfered rectangle: x, y, width, height, corner cut. */
function oct(x: number, y: number, w: number, h: number, c: number): string {
  return `M${x + c} ${y}H${x + w - c}L${x + w} ${y + c}V${y + h - c}L${x + w - c} ${y + h}H${x + c}L${x} ${y + h - c}V${y + c}Z`;
}

export const icons = {
  lens: [oct(3, 3, 18, 18, 5.5), oct(8, 8, 8, 8, 2.4), 'fill:M13.4 9.6h1.8v1.8h-1.8z'],
  tracker: [oct(3, 8, 13, 13, 3.8), oct(7.5, 12.5, 4, 4, 1.2), 'M15 2.5h4l2.5 2.5v4', 'M15 5.8h2.4l1 1V9'],
  spyware: [oct(6, 2, 12, 20, 2.6), 'M8.6 12l1.6-2.2h3.6l1.6 2.2-1.6 2.2h-3.6z', 'fill:M11.2 11.2h1.6v1.6h-1.6z', 'M10.5 18.5h3'],
  tower: ['M12 10v12', 'M7.5 22L12 10l4.5 12', 'M9.2 17.5h5.6', 'M7.6 4l-2 3 2 3', 'M16.4 4l2 3-2 3', 'M4 2L1.5 7 4 12', 'M20 2l2.5 5-2.5 5', 'fill:M11 6h2v2h-2z'],
  comms: ['M5 3h14l2.5 2.5v9.5L19 17.5h-7L7 21.5v-4H5L2.5 15V5.5z', 'M7 10.2h10'],
  sos: [oct(2, 2, 20, 20, 6), 'M12 6.8v6.4', 'fill:M11 15.6h2v2h-2z'],
  device: [oct(6, 2, 12, 20, 2.6), oct(9, 11.5, 6, 5, 1.2), 'M10.4 11.5V9.6l1.6-1.6 1.6 1.6v1.9'],
  gauge: ['M3 18.5v-3.5l2.6-5.4L10.2 6h3.6l4.6 3.6L21 15v3.5', 'M12 18.5l4-6', 'M2 21.5h20'],
  reticle: ['M3 8.5V3h5.5', 'M15.5 3H21v5.5', 'M21 15.5V21h-5.5', 'M8.5 21H3v-5.5', oct(9, 9, 6, 6, 1.8)],
  infrared: [oct(9, 9, 6, 6, 1.8), 'M12 2v3.2', 'M12 18.8V22', 'M2 12h3.2', 'M18.8 12H22', 'M4.9 4.9l2.3 2.3', 'M16.8 16.8l2.3 2.3', 'M19.1 4.9l-2.3 2.3', 'M7.2 16.8l-2.3 2.3'],
  magnet: ['M4.5 3h5.5v10.2l2 2 2-2V3h5.5v11.4L14.2 21H9.8l-5.3-6.6z', 'M4.5 7.5H10', 'M14 7.5h5.5'],
  wifi: ['M2 9l3.6-3.5h12.8L22 9', 'M5.6 13l2.6-2.5h7.6l2.6 2.5', 'M9.2 17l1.4-1.4h2.8l1.4 1.4', 'fill:M11 19.5h2v2h-2z'],
  bluetooth: ['M6.5 7.5l11 9-5.5 5V2.5l5.5 5-11 9'],
  compass: [oct(2, 2, 20, 20, 6), 'M12 5.5l2.6 6.5-2.6 6.5-2.6-6.5z', 'fill:M12 5.5l2.6 6.5h-5.2z'],
  key: [oct(2, 7, 10, 10, 3), 'M12 12h10', 'M18.5 12v3.5', 'M21.5 12v2.5', 'fill:M6 11h2v2H6z'],
  server: [oct(3, 3, 18, 7.5, 2), oct(3, 13.5, 18, 7.5, 2), 'M6.5 6.75h2.5', 'M6.5 17.25h2.5', 'M15 6.75h2.5', 'M15 17.25h2.5'],
  mail: [oct(2, 5, 20, 14, 2.5), 'M2.5 7.2l9.5 6.6 9.5-6.6'],
  timeline: ['M5 2v20', oct(3, 4, 4, 4, 1), oct(3, 10, 4, 4, 1), oct(3, 16, 4, 4, 1), 'M10 6h11', 'M10 12h8', 'M10 18h10'],
  nfc: [oct(3, 3, 18, 18, 4), 'M8.5 9l2.2 3-2.2 3', 'M12.3 7.3l3.4 4.7-3.4 4.7'],
  navigate: ['M12 2.5l8 19-8-4.8-8 4.8z'],
  ban: [oct(2, 2, 20, 20, 6), 'M6.2 6.2l11.6 11.6'],
  check: ['M4 12.5l5 5L20 6.5'],
  close: ['M5.5 5.5l13 13', 'M18.5 5.5l-13 13'],
  arrowRight: ['M3.5 12h16', 'M13.5 6l6 6-6 6'],
  arrowDown: ['M12 3v13', 'M6 10.5l6 6 6-6', 'M4 21h16'],
  external: ['M14 3h7v7', 'M21 3l-9 9', 'M18 14v7H3V6h7'],
  plus: ['M12 4.5v15', 'M4.5 12h15'],
  menu: ['M3 7h18', 'M3 12h18', 'M3 17h12'],
  phone: ['M5 2.5h4l2 5.2-2.6 1.8c1.2 2.6 3.2 4.6 5.8 5.8l1.8-2.6 5.2 2v4L19 21C10.4 21 3 13.6 3 5z'],
  phrase: ['M5 3h14l2.5 2.5v9.5L19 17.5h-7L7 21.5v-4H5L2.5 15V5.5z', 'fill:M7 9.3h2v2H7z', 'fill:M11 9.3h2v2h-2z', 'fill:M15 9.3h2v2h-2z'],
  lock: [oct(4, 10, 16, 12, 2.6), 'M7.5 10V6.2L10 3.5h4l2.5 2.7V10', 'M12 14.5v3'],
  wipe: ['M3.5 6h17', 'M9 6V3h6v3', 'M5.8 6l1.2 15.5h10L18.2 6', 'M10 10v7.5', 'M14 10v7.5'],
  heart: ['M12 20.5l-9-9V6.2L6.2 3h3.6L12 5.2 14.2 3h3.6L21 6.2v5.3z'],
  play: ['fill:M6.5 3.2l13.5 8.8-13.5 8.8z'],
  doc: ['M5 2h10l4 4v16H5z', 'M15 2v4h4', 'M8 11h8', 'M8 15h8', 'M8 19h5'],
  support: [oct(2, 2, 20, 20, 6), oct(8, 8, 8, 8, 2.4), 'M4.6 4.6l4.2 4.2', 'M19.4 4.6l-4.2 4.2', 'M19.4 19.4l-4.2-4.2', 'M4.6 19.4l4.2-4.2'],
  shield: ['M12 2l8.5 3v8.2L12 22l-8.5-8.8V5z', 'M8 12h8'],
  chip: [oct(6, 6, 12, 12, 2.4), 'M9.5 2.5V6', 'M14.5 2.5V6', 'M9.5 18v3.5', 'M14.5 18v3.5', 'M2.5 9.5H6', 'M2.5 14.5H6', 'M18 9.5h3.5', 'M18 14.5h3.5', 'fill:M10.5 10.5h3v3h-3z'],
  eye: ['M2 12l4.6-5.5h10.8L22 12l-4.6 5.5H6.6z', oct(9.5, 9.5, 5, 5, 1.4)],
  car: ['M3 16.5V12l2.5-5.5h13L21 12v4.5', 'M2 16.5h20', 'M3 12h18', oct(5, 15, 4, 4, 1), oct(15, 15, 4, 4, 1)],
} as const;

export type IconName = keyof typeof icons;
