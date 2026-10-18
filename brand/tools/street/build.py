"""Builds the four streetwear / esports logo concepts.

    python3 brand/tools/street/build.py

For each concept writes brand/concepts/N-name/:
    mark.svg, mark-mono-black.svg, mark-mono-white.svg, wordmark.svg,
    lockup.svg, app-icon.svg (108-unit adaptive canvas), palette.json
All artwork is outlined paths: fonts are only used as raw material.
Then run brand/tools/street/render.cjs to draw the comparison sheet.
"""
import json
import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from geom import (CAP, Face, band, bounds, circle, intersect, minus, move,  # noqa: E402
                  octagon, poly, rect, scale, set_word, svg_d, transform, union)

HERE = pathlib.Path(__file__).resolve().parent
BRAND = HERE.parent.parent
FONTS = BRAND / "fonts" / "display"
OUT = BRAND / "concepts"


# ------------------------------------------------------------------ colour
def _lin(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(h):
    r, g, b = (int(h[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def ratio(a, b):
    x, y = sorted([lum(a), lum(b)], reverse=True)
    return round((x + 0.05) / (y + 0.05), 2)


def check_palette(p):
    pairs = [("text", "base"), ("text", "surface"), ("textMuted", "base"), ("textMuted", "surface"),
             ("accent", "base"), ("accent", "surface"), ("onAccent", "accent"),
             ("safe", "base"), ("safe", "surface"), ("caution", "base"), ("caution", "surface"),
             ("danger", "base"), ("danger", "surface")]
    res = []
    for fg, bg in pairs:
        r = ratio(p[fg], p[bg])
        res.append({"fg": fg, "bg": bg, "ratio": r, "aa": r >= 4.5})
        if r < 4.5:
            raise SystemExit(f"{p['name']}: {fg} on {bg} = {r} < 4.5")
    return res


# ------------------------------------------------------------------ concepts
def concept_acid():
    """1 - Acid stencil. Archivo Expanded Black with a visor slit cut through
    every letter; an octagonal C locked around an X."""
    face = Face(FONTS / "archivo/Archivo[wdth,wght].ttf", {"wdth": 125, "wght": 900})
    slit = (46, 8.5)  # y, height of the stencil cut

    def x_custom(x):
        # Custom X: two straight bars with flat horizontal terminals, crossing
        # in a hard point; wider than the font's X so it reads as the hero letter.
        w, t = 92, 25
        a = intersect(band((x - 20, -60), (x + w + 20, 160), t * 1.414), rect(x, 0, w, CAP))
        b = intersect(band((x + w + 20, -60), (x - 20, 160), t * 1.414), rect(x, 0, w, CAP))
        return union(a, b), w

    glyphs, bb = set_word(face, "CLAIRIX", tracking=-2, kern={"LA": -9, "RI": -2, "IX": 2},
                          custom={6: x_custom})
    cut = rect(-50, slit[0], 2000, slit[1])
    word = [(minus(p, cut), "accent" if ch == "X" and i == 6 else "main") for i, ch, p in glyphs]

    # Mark: octagonal C, a heavy X in its counter, and the same slit.
    outer = octagon(0, 0, 100, 100, 24)
    inner = octagon(23, 23, 54, 54, 13)
    mouth = poly([(60, 38), (100, 26), (100, 74), (60, 62)])
    c = minus(outer, inner, mouth)
    xs = union(intersect(band((30, 30), (70, 70), 13), rect(33, 33, 34, 34)),
               intersect(band((70, 30), (30, 70), 13), rect(33, 33, 34, 34)))
    mark = [(minus(c, rect(-1, slit[0], 40, slit[1])), "main"), (xs, "accent")]
    palette = {
        "name": "Acid", "base": "#0A0B09", "surface": "#171914", "text": "#F1F2EA",
        "textMuted": "#A4A89A", "accent": "#C8FF1F", "onAccent": "#0A0B09",
        "safe": "#3EE6B0", "caution": "#FFB52E", "danger": "#FF5A4E",
        "description": "Black, bone white and one acid green. Loud on merch, clear in the app.",
    }
    return dict(id="1-acid", label="Acid stencil", palette=palette, mark=mark, word=word,
                font="Archivo Expanded Black (OFL), slit and X redrawn",
                tagline="NOTHING HIDES.",
                line="Expanded black stencil wordmark cut by a visor slit; octagonal C locked around an acid X.")


def concept_infrared():
    """2 - Infrared visor. A faceted helmet crest with a red visor; Chakra
    Petch with an X drawn as two targeting chevrons."""
    face = Face(FONTS / "chakra-petch/ChakraPetch-Bold.ttf")

    def x_custom(x):
        w, t, g = 86, 21, 5
        c = w / 2
        left = poly([(x, 0), (x + t, 0), (x + c - g, 50), (x + t, 100), (x, 100), (x + c - g - t, 50)])
        right = poly([(x + w, 0), (x + w - t, 0), (x + c + g, 50), (x + w - t, 100), (x + w, 100), (x + c + g + t, 50)])
        return union(left, right), w

    glyphs, _ = set_word(face, "CLAIRIX", tracking=9, custom={6: x_custom})
    # 12 degree forward slant, pivoting on the baseline
    sk = math.tan(math.radians(12))
    word = [(transform(p, 1, 0, -sk, 1, sk * CAP, 0), "accent" if i == 6 else "main") for i, ch, p in glyphs]

    crest = poly([(18, 2), (82, 2), (98, 18), (98, 58), (50, 98), (2, 58), (2, 18)])
    visor = poly([(9, 36), (91, 36), (85, 53), (15, 53)])
    ridge = rect(48.6, -2, 2.8, 38)
    chin = poly([(48.6, 53), (51.4, 53), (51.4, 102), (48.6, 102)])
    shell = minus(crest, visor, ridge, chin)
    right_half = intersect(shell, rect(50, -10, 60, 120))
    left_half = minus(shell, rect(50, -10, 60, 120))
    # the red insert sits inside the cut, leaving a thin shell line around it
    vis = poly([(13.2, 39.2), (86.8, 39.2), (82.6, 49.8), (17.4, 49.8)])
    mark = [(left_half, "main"), (right_half, "dim"), (vis, "accent")]
    palette = {
        "name": "Infrared", "base": "#0A0B0F", "surface": "#15171E", "text": "#F3F4F7",
        "textMuted": "#9CA3B2", "accent": "#FF2E4A", "onAccent": "#0A0B0F",
        "safe": "#35E39A", "caution": "#FFC23D", "danger": "#FF5468",
        "dim": "#B7BECB",
        "description": "Carbon black and steel with an infrared red that means one thing: threat found.",
    }
    return dict(id="2-infrared", label="Infrared visor", palette=palette, mark=mark, word=word,
                font="Chakra Petch Bold (OFL), X redrawn as targeting chevrons",
                tagline="LOCKED ON.",
                line="Faceted helmet crest with a red visor slit; angular tech wordmark whose X is two targeting chevrons.")


def concept_hazard():
    """3 - Hazard tag. Big Shoulders Display Black, tall and condensed, with
    a crossed X; the mark is a chamfered ID tag with hazard stripes."""
    face = Face(FONTS / "big-shoulders-display/BigShouldersDisplay[wght].ttf", {"wght": 900})

    def x_custom(x):
        w, t, gap = 58, 19, 4.5
        back = intersect(band((x - 8, -20), (x + w + 8, 120), t), rect(x, 0, w, CAP))
        front_line = ((x + w + 8, -20), (x - 8, 120))
        front = intersect(band(*front_line, t), rect(x, 0, w, CAP))
        back = minus(back, band(*front_line, t + 2 * gap))
        return union(back, front), w

    glyphs, _ = set_word(face, "CLAIRIX", tracking=3, custom={6: x_custom})
    # split the custom X into its two strokes for colouring
    word = []
    for i, ch, p in glyphs:
        if i == 6:
            x0 = bounds(p)[0]
            w = 58
            front = intersect(band((x0 + w + 8, -20), (x0 - 8, 120), 19), rect(x0, 0, w, CAP))
            word.append((minus(p, front), "main"))
            word.append((front, "accent"))
        else:
            word.append((p, "main"))

    tag = octagon(14, 0, 72, 100, 12)
    hole = circle(50, 11, 4.6)
    cx_glyphs, cbb = set_word(face, "CX", tracking=4)
    cx = union(*[p for _, _, p in cx_glyphs])
    s = 46 / CAP
    cx = scale(cx, s)
    cb = bounds(cx)
    cx = move(cx, 50 - (cb[0] + cb[2]) / 2, 22 - cb[1])
    stripes = union(*[band((14 + k * 13, 100), (14 + k * 13 + 16, 76), 6.2) for k in range(-1, 7)])
    stripes = intersect(stripes, rect(14, 78, 72, 14))
    body = minus(tag, hole, cx, stripes)
    mark = [(body, "accent")]
    palette = {
        "name": "Hazard", "base": "#0C0C0B", "surface": "#191917", "text": "#F4EFE4",
        "textMuted": "#A9A396", "accent": "#FF6A13", "onAccent": "#0C0C0B",
        "safe": "#4BE08A", "caution": "#FFD43B", "danger": "#FF5247",
        "description": "Workwear black, bone and hazard orange. Built like a warning label.",
    }
    return dict(id="3-hazard", label="Hazard tag", palette=palette, mark=mark, word=word,
                font="Big Shoulders Display Black (OFL), X redrawn with a crossed stroke",
                tagline="HANDLE WITH CARE.",
                line="Tall condensed workwear wordmark with a crossed hazard X; chamfered ID tag mark with punch hole and stripes.")


def concept_volt():
    """4 - Volt eye. An angular eye split by a vertical slit pupil, the way a
    predator sees in the dark; Unbounded Black with a bladed X."""
    face = Face(FONTS / "unbounded/Unbounded[wght].ttf", {"wght": 900})

    def blade(p0, p1, w):
        # stroke that tapers to points at both ends: a long lozenge
        (x0, y0), (x1, y1) = p0, p1
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        dx, dy = x1 - x0, y1 - y0
        L = math.hypot(dx, dy)
        nx, ny = -dy / L * w / 2, dx / L * w / 2
        return poly([(x0, y0), (mx + nx, my + ny), (x1, y1), (mx - nx, my - ny)])

    def x_custom(x):
        w = 104
        a = intersect(blade((x - 6, -14), (x + w + 6, 114), 34), rect(x, 0, w, CAP))
        b = intersect(blade((x + w + 6, -14), (x - 6, 114), 34), rect(x, 0, w, CAP))
        return union(a, b), w

    glyphs, _ = set_word(face, "CLAIRIX", tracking=0, kern={"LA": -6, "IX": 4}, custom={6: x_custom})
    word = [(p, "accent" if i == 6 else "main") for i, ch, p in glyphs]

    eye = poly([(0, 50), (27, 21), (73, 21), (100, 50), (73, 79), (27, 79)])
    slit = poly([(50, 8), (65, 50), (50, 92), (35, 50)])
    shards = minus(eye, slit)
    pupil = poly([(50, 21), (59.5, 50), (50, 79), (40.5, 50)])
    mark = [(shards, "main"), (pupil, "accent")]
    palette = {
        "name": "Volt", "base": "#0B0812", "surface": "#171221", "text": "#F2EFFA",
        "textMuted": "#A69DBA", "accent": "#A47BFF", "onAccent": "#0B0812",
        "safe": "#45E3A5", "caution": "#FFC84A", "danger": "#FF5C7A",
        "description": "Deep night violet with an electric violet signal. Gaming energy, kept precise.",
    }
    return dict(id="4-volt", label="Volt eye", palette=palette, mark=mark, word=word,
                font="Unbounded Black (OFL), X redrawn as two tapered blades",
                tagline="EYES OPEN.",
                line="Angular eye split by a violet slit pupil, night-vision energy; wide heavy wordmark with a bladed violet X.")


# ------------------------------------------------------------------ writers
def colours(pal, mode):
    if mode == "dark":
        return {"main": pal["text"], "accent": pal["accent"], "dim": pal.get("dim", pal["text"])}
    if mode == "black":
        return {"main": "#000000", "accent": "#000000", "dim": "#000000"}
    return {"main": "#FFFFFF", "accent": "#FFFFFF", "dim": "#FFFFFF"}


def layers_svg(layers, cols, dx=0.0, dy=0.0, s=1.0):
    out = []
    for p, role in layers:
        q = transform(p, s, 0, 0, s, dx, dy)
        out.append(f'<path fill="{cols[role]}" d="{svg_d(q)}"/>')
    return "\n".join(out)


def doc(w, h, title, inner, bg=None):
    r = f'<rect width="{w:.2f}" height="{h:.2f}" fill="{bg}"/>\n' if bg else ""
    return (f"<!-- Clairix street concept. {title}. Generated by brand/tools/street/build.py. -->\n"
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:.2f} {h:.2f}" '
            f'width="{w:.2f}" height="{h:.2f}" role="img" aria-label="Clairix"><title>Clairix</title>\n{r}{inner}\n</svg>\n')


def ink_bounds(layers):
    return bounds(union(*[p for p, _ in layers]))


def write_concept(c):
    d = OUT / c["id"]
    d.mkdir(parents=True, exist_ok=True)
    pal = c["palette"]
    mb = ink_bounds(c["mark"])
    mw, mh = mb[2] - mb[0], mb[3] - mb[1]
    for name, mode in [("mark.svg", "dark"), ("mark-mono-black.svg", "black"), ("mark-mono-white.svg", "white")]:
        (d / name).write_text(doc(mw, mh, f'{c["label"]}, mark ({mode})', layers_svg(c["mark"], colours(pal, mode), -mb[0], -mb[1])))
    wb = ink_bounds(c["word"])
    ww, wh = wb[2] - wb[0], wb[3] - wb[1]
    (d / "wordmark.svg").write_text(doc(ww, wh, f'{c["label"]}, wordmark', layers_svg(c["word"], colours(pal, "dark"), -wb[0], -wb[1])))
    # lockup: mark 1.5 cap high, centred on the cap height, gap 0.45 cap
    s = 150 / mh
    gap = 45
    lw = mw * s + gap + ww
    lh = max(mh * s, wh)
    mark_y = (lh - mh * s) / 2
    word_y = (lh - wh) / 2
    inner = (layers_svg(c["mark"], colours(pal, "dark"), -mb[0] * s, -mb[1] * s + mark_y, s) + "\n" +
             layers_svg(c["word"], colours(pal, "dark"), mw * s + gap - wb[0], -wb[1] + word_y))
    (d / "lockup.svg").write_text(doc(lw, lh, f'{c["label"]}, horizontal lockup', inner))
    # app icon: 108 canvas, mark fits a 58-unit box, centred
    k = 58 / max(mw, mh)
    ix = 54 - (mw * k) / 2 - mb[0] * k
    iy = 54 - (mh * k) / 2 - mb[1] * k
    (d / "app-icon.svg").write_text(doc(108, 108, f'{c["label"]}, app icon (108-unit adaptive canvas)',
                                        layers_svg(c["mark"], colours(pal, "dark"), ix, iy, k), bg=pal["base"]))
    pal_out = dict(pal)
    pal_out["contrast"] = check_palette(pal)
    (d / "palette.json").write_text(json.dumps(pal_out, indent=2) + "\n")
    return {k2: c[k2] for k2 in ("id", "label", "font", "tagline", "line")} | {"palette": pal}


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    index = [write_concept(c()) for c in (concept_acid, concept_infrared, concept_hazard, concept_volt)]
    (OUT / "concepts.json").write_text(json.dumps(index, indent=2) + "\n")
    print("concepts:", ", ".join(c["id"] for c in index))
