"""Builds the Clairix logo system (Acid Stencil) and the Android launcher layers.

    python3 brand/tools/build_logo.py

The mark: an octagonal C, cut once by a horizontal stencil slit, locked
around a heavy X. The wordmark: Archivo Expanded Black, outlined, with the
same slit cut through every letter and a redrawn X. In the horizontal lockup
the two slits line up into one cut running through the whole logo.

Everything is written as plain filled paths (no strokes, masks, fonts or
transforms), so the same data drives SVG and Android vector drawables.

Requires: fonttools, skia-pathops
"""
import json
import pathlib
import sys

import pathops

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from geom import (CAP, Face, band, bounds, intersect, minus, octagon,  # noqa: E402
                  poly, rect, set_word, svg_d, transform, union)

HERE = pathlib.Path(__file__).resolve().parent
BRAND = HERE.parent
ROOT = BRAND.parent
OUT = BRAND / "logo"
RES = ROOT / "android/app/src/main/res"

TOK = json.loads((BRAND / "tokens/tokens.json").read_text())
ACID = TOK["color"]["themes"]["acid"]
INK = ACID["base"]["hex"]       # #0A0B09-ish, the black ground
BONE = ACID["text"]["hex"]      # bone white
ACIDC = ACID["accent"]["hex"]   # acid green

# ------------------------------------------------------------------ mark
# Two masters on a 100-unit square. "regular" from 40 px up; "small" for
# 16-39 px, with a wider slit, a heavier X and more room between them so
# nothing closes up when pixels snap.
MASTERS = {
    "regular": dict(chamfer=24, inner=(23, 11), mouth=27, slit=(46.75, 6.5),
                    x_box=(33.5, 33), x_bar=13),
    "small": dict(chamfer=24, inner=(21, 9), mouth=22, slit=(45, 9.5),
                  x_box=(31, 38), x_bar=12.5),
}


def mark(m):
    """Returns [(path, role)] for one master. Roles: main (the C), accent (the X)."""
    c = m["chamfer"]
    ins, ic = m["inner"]
    outer = octagon(0, 0, 100, 100, c)
    inner = octagon(ins, ins, 100 - 2 * ins, 100 - 2 * ins, ic)
    flare = m["mouth"]
    # The mouth opens toward the right and flares, so the terminals are cut
    # on a slant: the C reads as "open", not "broken". Each cut runs exactly
    # through the counter's corner point, so no sliver of the counter edge
    # survives between cut and chamfer.
    vx, vy = 100 - ins, ins + ic
    k = flare / 100
    y_at = lambda x: vy - (x - vx) * k  # noqa: E731
    mouth = poly([(vx - 20, y_at(vx - 20)), (101, y_at(101)),
                  (101, 100 - y_at(101)), (vx - 20, 100 - y_at(vx - 20))])
    y, h = m["slit"]
    slit = rect(-1, y, ins + 2, h)
    C = minus(outer, inner, mouth, slit)
    x0, w = m["x_box"]
    box = rect(x0, x0, w, w)
    t = m["x_bar"]  # perpendicular bar width
    X = union(intersect(band((x0 - 10, x0 - 10), (x0 + w + 10, x0 + w + 10), t), box),
              intersect(band((x0 + w + 10, x0 - 10), (x0 - 10, x0 + w + 10), t), box))
    return [(C, "main"), (X, "accent")]


# ------------------------------------------------------------------ wordmark
SLIT_WORD = (47, 8.5)  # y, height in cap units


def wordmark():
    face = Face(BRAND / "fonts/archivo/static/ArchivoExpanded-Black.ttf")

    def custom_x(x):
        # Redrawn X: two straight bars with flat, horizontal terminals that
        # cross in a hard point; slightly wider than Archivo's so it carries
        # the colour at the end of the word.
        w, t = 94, 26
        box = rect(x, 0, w, CAP)
        a = intersect(band((x - 21, -60), (x + w + 21, 160), t * 1.414), box)
        b = intersect(band((x + w + 21, -60), (x - 21, 160), t * 1.414), box)
        return union(a, b), w

    # Hand spacing in cap units (cap = 100). Expanded Black is dense: the
    # round C and A diagonals need pulling in, straight I-R pairs opening.
    kern = {"CL": -4, "LA": -2, "AI": -3, "IR": 1, "RI": -3, "IX": 3}
    glyphs, _ = set_word(face, "CLAIRIX", tracking=-2, kern=kern, custom={6: custom_x})
    cut = rect(-50, SLIT_WORD[0], 3000, SLIT_WORD[1])
    return [(minus(p, cut), "accent" if i == 6 else "main") for i, ch, p in glyphs]


# ------------------------------------------------------------------ output
SCHEMES = {
    # primary: light on dark
    "reversed": {"main": BONE, "accent": ACIDC},
    # for light grounds: ink C, acid X held by an ink keyline (see guidelines)
    "positive": {"main": INK, "accent": ACIDC, "keyline": INK},
    "mono-black": {"main": "#000000", "accent": "#000000"},
    "mono-white": {"main": "#FFFFFF", "accent": "#FFFFFF"},
}


def fmt(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def paths(layers, scheme, s=1.0, dx=0.0, dy=0.0):
    cols = SCHEMES[scheme]
    out = []
    for p, role in layers:
        q = transform(p, s, 0, 0, s, dx, dy)
        if role == "accent" and "keyline" in cols:
            # acid on white needs an ink edge to hold its shape: the X grown
            # by 2.5 units with mitred corners, drawn underneath as a shape
            edge = transform(q)
            edge.stroke(5 * s, pathops.LineCap.BUTT_CAP, pathops.LineJoin.MITER_JOIN, 4)
            out.append(f'<path fill="{cols["keyline"]}" d="{svg_d(union(edge, q))}"/>')
        out.append(f'<path fill="{cols[role]}" d="{svg_d(q)}"/>')
    return "\n".join(out)


def doc(w, h, title, inner, pad=0.0):
    return (f"<!-- Clairix. {title}. Generated by brand/tools/build_logo.py. -->\n"
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{fmt(-pad)} {fmt(-pad)} {fmt(w + 2 * pad)} {fmt(h + 2 * pad)}" '
            f'width="{fmt(w + 2 * pad)}" height="{fmt(h + 2 * pad)}" role="img" aria-label="Clairix">'
            f"<title>Clairix</title>\n{inner}\n</svg>\n")


def ink(layers):
    return bounds(union(*[p for p, _ in layers]))


SUFFIX = {"reversed": "-reversed", "positive": "", "mono-black": "-mono-black", "mono-white": "-mono-white"}
LABEL = {"reversed": "light on dark (primary)", "positive": "for light grounds",
         "mono-black": "one colour, black", "mono-white": "one colour, white"}


def main():
    OUT.mkdir(exist_ok=True)
    for f in OUT.glob("*.svg"):
        f.unlink()
    M = mark(MASTERS["regular"])
    S = mark(MASTERS["small"])
    W = wordmark()
    wb = ink(W)
    ww, wh = wb[2] - wb[0], wb[3] - wb[1]

    for scheme in SCHEMES:
        sfx = SUFFIX[scheme]
        # keyline overhang: pad positive files by half the keyline
        pad = 2.5 if scheme == "positive" else 0
        (OUT / f"symbol{sfx}.svg").write_text(doc(100, 100, f"Symbol, {LABEL[scheme]}", paths(M, scheme), pad))
        (OUT / f"symbol-small{sfx}.svg").write_text(doc(100, 100, f"Symbol small master (16-39 px), {LABEL[scheme]}", paths(S, scheme), pad))
        (OUT / f"wordmark{sfx}.svg").write_text(doc(ww, wh, f"Wordmark, {LABEL[scheme]}", paths(W, scheme, 1, -wb[0], -wb[1]), pad))

        # Horizontal lockup. The mark is scaled so its slit is exactly as tall
        # as the wordmark's, and placed so the two slits share one baseline:
        # a single cut runs through the whole logo.
        ms = SLIT_WORD[1] / MASTERS["regular"]["slit"][1]
        my = SLIT_WORD[0] - MASTERS["regular"]["slit"][0] * ms
        gap = 40
        top = min(my, wb[1])
        bottom = max(my + 100 * ms, wb[3])
        inner = paths(M, scheme, ms, 0, my - top) + "\n" + paths(W, scheme, 1, 100 * ms + gap - wb[0], -top)
        (OUT / f"lockup-horizontal{sfx}.svg").write_text(
            doc(100 * ms + gap + ww, bottom - top, f"Horizontal lockup, {LABEL[scheme]}", inner, pad))

        # Stacked lockup: mark centred over the word, 0.42 of its width.
        sm = 0.42 * ww / 100
        gap_s = 0.16 * ww * 0.42 * 1.0
        inner = (paths(M, scheme, sm, (ww - 100 * sm) / 2, 0) + "\n" +
                 paths(W, scheme, 1, -wb[0], 100 * sm + gap_s - wb[1]))
        (OUT / f"lockup-stacked{sfx}.svg").write_text(
            doc(ww, 100 * sm + gap_s + wh, f"Stacked lockup, {LABEL[scheme]}", inner, pad))

    # App icon. Adaptive canvas 108; the octagon's corner points lie on a
    # circle of radius 0.56 x its width, so a 50-unit mark keeps every corner
    # inside the 66-unit safe circle (radius 28 < 33), with room to breathe.
    size = 50
    off = (108 - size) / 2
    k = size / 100
    fg = [(transform(p, k, 0, 0, k, off, off), r) for p, r in M]
    (OUT / "app-icon-foreground.svg").write_text(doc(108, 108, "Adaptive icon foreground, 108-unit canvas, 66-unit safe zone",
                                                     paths(fg, "reversed")))
    (OUT / "app-icon.svg").write_text(doc(108, 108, "App icon, full-bleed square on Ink; the platform applies its mask",
                                          f'<rect width="108" height="108" fill="{INK}"/>\n' + paths(fg, "reversed")))

    def vector(comment, body):
        return f"""<?xml version="1.0" encoding="utf-8"?>
<!--
{comment}
    Generated by brand/tools/build_logo.py.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
"""

    def vpath(name, colour, p):
        return f"""
    <!-- {name} -->
    <path
        android:fillColor="{colour}"
        android:pathData="{svg_d(p)}" />
"""
    C_fg, X_fg = fg[0][0], fg[1][0]
    (RES / "drawable/ic_launcher_fg.xml").write_text(vector(
        f"""    Launcher icon foreground: the Clairix mark. A bone-white octagonal C,
    cut by the stencil slit, locked around an acid-green X, over the
    @color/ic_launcher_background Ink ground. 108dp adaptive canvas; the mark
    is {size}dp wide and every corner sits inside the 66dp safe circle.""",
        vpath("C", "#FF" + BONE[1:], C_fg) + vpath("X", "#FF" + ACIDC[1:], X_fg)))
    (RES / "drawable/ic_launcher_mono.xml").write_text(vector(
        """    Monochrome launcher layer for themed icons (Android 13+). The launcher
    reads only its alpha and tints it: the same C and X in solid white, same
    placement as ic_launcher_fg.""",
        vpath("C", "#FFFFFFFF", C_fg) + vpath("X", "#FFFFFFFF", X_fg)))
    (RES / "values/colors.xml").write_text(f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Adaptive launcher icon ground: Clairix Ink (Acid theme base, {ACID['base']['oklch']}). -->
    <color name="ic_launcher_background">{INK}</color>
</resources>
""")
    print("logo: wrote", len(list(OUT.glob("*.svg"))), "files to brand/logo and the launcher layers")


if __name__ == "__main__":
    main()
