"""Extract the glyph outlines used by the Clairix wordmark.

Reads Inter Display SemiBold (SIL OFL, brand/fonts/inter/static) and writes
wordmark-glyphs.json: for each glyph, its advance width and outline as SVG
path data in font units (y up, baseline at 0). build-logo.mjs then sets the
word with hand-tuned spacing and the drawn adjustments described there.

Requires fontTools:  python3 -m pip install fonttools
Usage:               python3 brand/tools/outline-wordmark.py
"""
import json
import pathlib

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.ttLib import TTFont

HERE = pathlib.Path(__file__).resolve().parent
FONT = HERE.parent / "fonts" / "inter" / "static" / "InterDisplay-SemiBold.ttf"

font = TTFont(FONT)
cmap = font.getBestCmap()
glyphset = font.getGlyphSet()
out = {"font": FONT.name, "unitsPerEm": font["head"].unitsPerEm,
       "xHeight": font["OS/2"].sxHeight, "capHeight": font["OS/2"].sCapHeight,
       "glyphs": {}}

names = {c: cmap[ord(c)] for c in "clairx"}
names["dotlessi"] = cmap[0x0131]  # dotless i: the tittle is drawn separately
for key, gname in names.items():
    pen = SVGPathPen(glyphset)
    glyphset[gname].draw(pen)
    bp = BoundsPen(glyphset)
    glyphset[gname].draw(bp)
    out["glyphs"][key] = {
        "name": gname,
        "advance": font["hmtx"][gname][0],
        "lsb": font["hmtx"][gname][1],
        "bounds": bp.bounds,
        "d": pen.getCommands(),
    }

# Measure the original tittle on 'i' so the drawn round one matches its
# optical size and height.
bp = BoundsPen(glyphset)
glyphset[names["i"]].draw(bp)
ip = BoundsPen(glyphset)
glyphset[names["dotlessi"]].draw(ip)
out["i"] = {"bounds": bp.bounds, "stemBounds": ip.bounds}

(HERE / "wordmark-glyphs.json").write_text(json.dumps(out, indent=1))
print("wrote", HERE / "wordmark-glyphs.json")
