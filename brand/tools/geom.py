"""Small geometry kit for the street concepts: font outlines and shapes as
skia-pathops paths, boolean operations, and SVG output.

Coordinates are in "cap units": y grows downward, the cap height is 100 and
the baseline sits at y = 100, so glyphs and hand-drawn shapes share one grid.

Requires: fonttools, skia-pathops  (python3 -m pip install fonttools skia-pathops)
"""
import math

import pathops
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

CAP = 100.0
K = 0.5522847498  # cubic circle constant


# ------------------------------------------------------------------ shapes
def poly(pts):
    p = pathops.Path()
    pen = p.getPen()
    pen.moveTo(pts[0])
    for q in pts[1:]:
        pen.lineTo(q)
    pen.closePath()
    return clean(p)


def rect(x, y, w, h):
    return poly([(x, y), (x + w, y), (x + w, y + h), (x, y + h)])


def circle(cx, cy, r):
    p = pathops.Path()
    pen = p.getPen()
    k = K * r
    pen.moveTo((cx + r, cy))
    pen.curveTo((cx + r, cy + k), (cx + k, cy + r), (cx, cy + r))
    pen.curveTo((cx - k, cy + r), (cx - r, cy + k), (cx - r, cy))
    pen.curveTo((cx - r, cy - k), (cx - k, cy - r), (cx, cy - r))
    pen.curveTo((cx + k, cy - r), (cx + r, cy - k), (cx + r, cy))
    pen.closePath()
    return clean(p)


def octagon(x, y, w, h, c):
    """Rectangle with all four corners chamfered by c."""
    return poly([(x + c, y), (x + w - c, y), (x + w, y + c), (x + w, y + h - c),
                 (x + w - c, y + h), (x + c, y + h), (x, y + h - c), (x, y + c)])


def band(p0, p1, width):
    """A straight bar of the given width from p0 to p1 (flat ends)."""
    (x0, y0), (x1, y1) = p0, p1
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy)
    nx, ny = -dy / L * width / 2, dx / L * width / 2
    return poly([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)])


# ------------------------------------------------------------------ booleans
def clean(p):
    return pathops.simplify(p, fix_winding=True, keep_starting_points=False)


def union(*paths):
    out = pathops.Path()
    for p in paths:
        out = pathops.op(out, p, pathops.PathOp.UNION, fix_winding=True)
    return out


def minus(a, *bs):
    for b in bs:
        a = pathops.op(a, b, pathops.PathOp.DIFFERENCE, fix_winding=True)
    return a


def intersect(a, b):
    return pathops.op(a, b, pathops.PathOp.INTERSECTION, fix_winding=True)


def transform(p, a=1, b=0, c=0, d=1, e=0, f=0):
    out = pathops.Path()
    p.draw(TransformPen(out.getPen(), (a, b, c, d, e, f)))
    return out


def move(p, dx, dy):
    return transform(p, 1, 0, 0, 1, dx, dy)


def scale(p, s, ox=0, oy=0):
    return transform(p, s, 0, 0, s, ox, oy)


def bounds(p):
    return p.bounds  # (xMin, yMin, xMax, yMax)


def svg_d(p):
    pen = SVGPathPen(None, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    p.draw(pen)
    return pen.getCommands()


# ------------------------------------------------------------------ type
class Face:
    """A font instance scaled so its cap height is 100 units."""

    def __init__(self, file, axes=None):
        f = TTFont(file)
        if axes:
            f = instantiateVariableFont(f, axes)
        self.font = f
        self.gs = f.getGlyphSet()
        self.cmap = f.getBestCmap()
        self.s = CAP / f["OS/2"].sCapHeight

    def advance(self, ch):
        return self.font["hmtx"][self.cmap[ord(ch)]][0] * self.s

    def glyph(self, ch, x=0.0):
        out = pathops.Path()
        pen = TransformPen(out.getPen(), (self.s, 0, 0, -self.s, x, CAP))
        self.gs[self.cmap[ord(ch)]].draw(pen)
        return clean(out)


def set_word(face, text, tracking=0.0, kern=None, custom=None):
    """Lay out text. custom maps a character index to fn(x) -> (path, advance)
    for hand-drawn letters. Returns (list of (index, char, path), ink bounds)."""
    kern = kern or {}
    custom = custom or {}
    x = 0.0
    out = []
    for i, ch in enumerate(text):
        if i in custom:
            p, adv = custom[i](x)
        else:
            p, adv = face.glyph(ch, x), face.advance(ch)
        out.append((i, ch, p))
        if i + 1 < len(text):
            x += adv + tracking + kern.get(text[i:i + 2], 0.0)
    allp = union(*[p for _, _, p in out])
    xMin = bounds(allp)[0]
    out = [(i, ch, move(p, -xMin, 0)) for i, ch, p in out]
    return out, bounds(union(*[p for _, _, p in out]))
