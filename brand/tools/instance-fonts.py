"""Cuts static instances and a web font from the Archivo variable font.

    python3 brand/tools/instance-fonts.py

Android res/font and the outlined logo use fixed instances; the web uses the
variable WOFF2. Archivo has no Reserved Font Name, so instances keep the
family name (SIL OFL 1.1, section 3).

Requires: fonttools, brotli   (python3 -m pip install fonttools brotli)
"""
import pathlib

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

HERE = pathlib.Path(__file__).resolve().parent
DIR = HERE.parent / "fonts" / "archivo"
SRC = DIR / "Archivo[wdth,wght].ttf"

INSTANCES = {
    # UI text: normal width
    "Archivo-Regular": (100, 400),
    "Archivo-Medium": (100, 500),
    "Archivo-SemiBold": (100, 600),
    "Archivo-Bold": (100, 700),
    # Display: expanded width
    "ArchivoExpanded-Bold": (125, 700),
    "ArchivoExpanded-ExtraBold": (125, 800),
    "ArchivoExpanded-Black": (125, 900),
}

(DIR / "static").mkdir(exist_ok=True)
for name, (wdth, wght) in INSTANCES.items():
    f = instantiateVariableFont(TTFont(SRC), {"wdth": wdth, "wght": wght})
    f.save(DIR / "static" / f"{name}.ttf")
    print("wrote", name)

(DIR / "web").mkdir(exist_ok=True)
web = TTFont(SRC)
web.flavor = "woff2"
web.save(DIR / "web" / "Archivo[wdth,wght].woff2")
print("wrote web woff2")
