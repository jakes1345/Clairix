"""Writes brand/tools/street/sheet.html: the comparison sheet for the four
street concepts (mark, lockup, launcher icon, 32 px favicon, hoodie chest
print, splash screen). render.cjs screenshots it to
brand/previews/street-concepts.png.

    python3 brand/tools/street/sheet.py
"""
import json
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
BRAND = HERE.parent.parent
CON = BRAND / "concepts"


def svg(path, **style):
    s = (CON / path).read_text()
    s = re.sub(r"<!--.*?-->\n?", "", s, flags=re.S).replace("<title>Clairix</title>", "")
    s = re.sub(r' width="[^"]*" height="[^"]*"', "", s, count=1)
    css = ";".join(f"{k.replace('_', '-')}:{v}" for k, v in style.items())
    return s.replace("<svg ", f'<svg style="{css}" ', 1)


def hoodie(print_svg):
    """Front flat of a heavyweight black hoodie, drawn by hand, with the
    print placed on the chest."""
    return f"""<svg viewBox="0 0 600 660" style="width:100%;height:100%">
<defs>
  <radialGradient id="hb{{i}}" cx="50%" cy="42%" r="62%"><stop offset="0" stop-color="#232323"/><stop offset="1" stop-color="#121212"/></radialGradient>
  <linearGradient id="hs{{i}}" x1="0" x2="1"><stop offset="0" stop-color="#0E0E0E"/><stop offset=".5" stop-color="#1C1C1C"/><stop offset="1" stop-color="#0E0E0E"/></linearGradient>
</defs>
<ellipse cx="300" cy="640" rx="250" ry="12" fill="#000" opacity=".18"/>
<path d="M205 132C198 72 240 26 300 26S402 72 395 132Z" fill="#141414"/>
<path d="M60 470L98 222C104 184 122 160 152 148L205 128C232 150 266 164 300 164S368 150 395 128L448 148C478 160 496 184 502 222L540 470L546 560L492 566L482 482L452 312L450 604H150L148 312L118 482L108 566L54 560Z" fill="url(#hb{{i}})"/>
<path d="M232 128C236 84 262 60 300 60S364 84 368 128C350 152 326 164 300 164S250 152 232 128Z" fill="#0B0B0B"/>
<path d="M232 128C250 152 274 164 300 164S350 152 368 128" fill="none" stroke="#2A2A2A" stroke-width="3"/>
<path d="M226 142L152 312M374 142L448 312" stroke="#232323" stroke-width="2.5" fill="none"/>
<path d="M150 600H450V628Q450 636 442 636H158Q150 636 150 628Z" fill="url(#hs{{i}})"/>
<path d="M54 556L108 562L106 590L52 584Z" fill="#141414"/><path d="M546 556L492 562L494 590L548 584Z" fill="#141414"/>
<path d="M206 468H394L424 596H176Z" fill="none" stroke="#252525" stroke-width="2.5"/>
<path d="M206 468L196 510M394 468L404 510" stroke="#1E1E1E" stroke-width="2"/>
<path d="M284 160L278 262M316 160L322 262" stroke="#D9D9D4" stroke-width="3.5" stroke-linecap="round"/>
<rect x="273.5" y="258" width="9" height="20" rx="2" fill="#BDBDB7" transform="rotate(4 278 268)"/>
<rect x="317.5" y="258" width="9" height="20" rx="2" fill="#BDBDB7" transform="rotate(-4 322 268)"/>
<foreignObject x="165" y="196" width="270" height="150"><div xmlns="http://www.w3.org/1999/xhtml" style="width:270px;height:150px;display:grid;place-items:center">{print_svg}</div></foreignObject>
</svg>"""


def splash(c, pal):
    d = c["id"]
    return f"""<div class="phone"><div class="screen" style="background:{pal['base']}">
  <div class="glow" style="background:radial-gradient(closest-side,{pal['accent']}33,transparent)"></div>
  <div class="grid" style="--g:{pal['text']}0D"></div>
  <div class="status" style="color:{pal['text']}"><span>9:41</span><span>5G</span></div>
  <div class="splash-mark">{svg(d + '/mark.svg', width='118px', height='auto', max_height='118px')}</div>
  <div class="splash-word">{svg(d + '/wordmark.svg', width='150px', height='auto')}</div>
  <div class="tag" style="color:{pal['accent']}">{c['tagline']}</div>
  <div class="bar" style="background:{pal['text']}22"><i style="background:{pal['accent']}"></i></div>
</div></div>"""


def row(n, c):
    d, pal = c["id"], c["palette"]
    sw = "".join(
        f'<div class="sw"><i style="background:{pal[k]}"></i><b>{lbl}</b><code>{pal[k]}</code></div>'
        for k, lbl in [("base", "Base"), ("surface", "Surface"), ("text", "Text"), ("accent", "Accent"),
                       ("safe", "Safe"), ("caution", "Caution"), ("danger", "Danger")])
    hood = hoodie(svg(d + "/lockup.svg", width="250px", height="auto", max_height="96px")).replace("{i}", str(n))
    return f"""<section class="row" style="--base:{pal['base']};--accent:{pal['accent']};--text:{pal['text']}">
  <div class="info">
    <div class="num" style="color:{pal['accent']}">{n:02d}</div>
    <h2>{c['label']}</h2>
    <p>{c['line']}</p>
    <p class="font">{c['font']}</p>
    <div class="swatches">{sw}</div>
  </div>
  <div class="tile big" style="background:{pal['base']}">{svg(d + '/mark.svg', width='300px', height='auto', max_height='300px')}<span>Mark</span></div>
  <div class="col">
    <div class="tile lock" style="background:{pal['base']}">{svg(d + '/lockup.svg', width='470px', height='auto', max_height='110px')}<span>Lockup</span></div>
    <div class="pair">
      <div class="tile home"><div class="icon">{svg(d + '/app-icon.svg', width='100%', height='100%')}</div><div class="ilabel">Clairix</div><span>Launcher icon</span></div>
      <div class="tile fav"><div class="tab"><div class="fi" style="background:{pal['base']}">{svg(d + '/mark.svg', width='24px', height='24px')}</div><em>Clairix</em></div>
        <div class="fav32" style="background:{pal['base']}">{svg(d + '/mark.svg', width='26px', height='26px')}</div><span>32 px favicon</span></div>
    </div>
  </div>
  <div class="tile hoodie">{hood}<span>Chest print</span></div>
  <div class="tile splash">{splash(c, pal)}<span>Splash screen</span></div>
</section>"""


def main():
    cs = json.loads((CON / "concepts.json").read_text())
    rows = "\n".join(row(i + 1, c) for i, c in enumerate(cs))
    html = f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Clairix street concepts</title>
<style>
@font-face {{ font-family: Inter; src: url("../../fonts/inter/web/InterVariable.woff2") format("woff2"); font-weight: 100 900; }}
@font-face {{ font-family: Archivo; src: url("../../fonts/display/archivo/Archivo[wdth,wght].ttf"); font-weight: 100 900; font-stretch: 62% 125%; }}
* {{ box-sizing: border-box; }}
body {{ margin: 0; width: 2400px; background: #050505; color: #EDEDED; font-family: Inter, sans-serif; -webkit-font-smoothing: antialiased; }}
header {{ padding: 64px 64px 40px; display: flex; justify-content: space-between; align-items: flex-end; }}
header h1 {{ margin: 0; font-family: Archivo; font-stretch: 125%; font-weight: 900; font-size: 56px; letter-spacing: -0.01em; text-transform: uppercase; }}
header p {{ margin: 8px 0 0; color: #8C8C8C; font-size: 18px; }}
header .r {{ text-align: right; color: #8C8C8C; font-size: 16px; line-height: 1.5; }}
.row {{ display: grid; grid-template-columns: 330px 420px 560px 520px 330px; gap: 22px; padding: 22px 64px; }}
.row + .row {{ border-top: 1px solid #1A1A1A; }}
.info {{ padding: 8px 4px; }}
.num {{ font-family: Archivo; font-stretch: 125%; font-weight: 900; font-size: 84px; line-height: .9; letter-spacing: -0.02em; }}
.info h2 {{ margin: 14px 0 10px; font-family: Archivo; font-stretch: 112%; font-weight: 800; font-size: 30px; text-transform: uppercase; letter-spacing: 0.01em; }}
.info p {{ margin: 0 0 10px; font-size: 15px; line-height: 1.5; color: #B5B5B5; }}
.info .font {{ font-size: 13px; color: #7F7F7F; }}
.swatches {{ display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin-top: 14px; }}
.sw {{ display: grid; grid-template-columns: 20px 1fr; column-gap: 8px; align-items: center; font-size: 12px; }}
.sw i {{ grid-row: span 2; width: 20px; height: 20px; border-radius: 4px; box-shadow: inset 0 0 0 1px #ffffff22; }}
.sw b {{ font-weight: 600; color: #DADADA; }}
.sw code {{ font-family: ui-monospace, monospace; font-size: 11px; color: #8C8C8C; }}
.tile {{ position: relative; border-radius: 18px; overflow: hidden; display: grid; place-items: center; height: 520px; }}
.tile > span {{ position: absolute; left: 16px; bottom: 12px; font-size: 12px; letter-spacing: .06em; text-transform: uppercase; color: #8A8A8A; }}
.big {{ box-shadow: inset 0 0 0 1px #ffffff10; }}
.col {{ display: grid; grid-template-rows: 250px 250px; gap: 20px; }}
.col .tile {{ height: auto; }}
.lock {{ box-shadow: inset 0 0 0 1px #ffffff10; padding: 0 30px; }}
.pair {{ display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }}
.home {{ background: linear-gradient(160deg, #2B2F3A, #14161C); }}
.home .icon {{ width: 120px; height: 120px; border-radius: 30px; overflow: hidden; margin-top: -44px; box-shadow: 0 10px 24px #0008; }}
.home .ilabel {{ position: absolute; top: 178px; width: 100%; text-align: center; font-size: 14px; color: #EDEDED; }}
.fav {{ background: #E9E9E6; align-content: center; gap: 18px; }}
.tab {{ display: flex; align-items: center; gap: 10px; background: #fff; border-radius: 10px 10px 0 0; padding: 10px 16px; width: 200px; box-shadow: 0 1px 0 #0001; }}
.tab .fi {{ width: 16px; height: 16px; display: grid; place-items: center; border-radius: 3px; overflow: hidden; }}
.tab .fi svg {{ width: 13px !important; height: 13px !important; }}
.tab em {{ font-style: normal; font-size: 13px; color: #333; }}
.fav32 {{ width: 32px; height: 32px; border-radius: 6px; display: grid; place-items: center; }}
.hoodie {{ background: linear-gradient(180deg, #DADAD6, #C8C8C3); padding: 16px 30px 34px; }}
.hoodie > svg {{ width: 460px; height: 490px; }}
.splash {{ background: #111; }}
.phone {{ width: 236px; height: 486px; border-radius: 40px; background: #1B1B1D; padding: 8px; box-shadow: 0 0 0 2px #2C2C2F, 0 24px 48px #0009; }}
.screen {{ position: relative; width: 100%; height: 100%; border-radius: 33px; overflow: hidden; display: grid; place-items: center; }}
.glow {{ position: absolute; width: 360px; height: 360px; left: -60px; top: 40px; }}
.grid {{ position: absolute; inset: 0; background-image: linear-gradient(var(--g) 1px, transparent 1px), linear-gradient(90deg, var(--g) 1px, transparent 1px); background-size: 22px 22px; }}
.status {{ position: absolute; top: 14px; left: 26px; right: 26px; display: flex; justify-content: space-between; font-size: 12px; font-weight: 600; }}
.splash-mark {{ position: absolute; top: 140px; width: 100%; display: grid; place-items: center; }}
.splash-word {{ position: absolute; top: 300px; width: 100%; display: grid; place-items: center; }}
.tag {{ position: absolute; bottom: 64px; width: 100%; text-align: center; font-family: Archivo; font-stretch: 125%; font-weight: 800; font-size: 11px; letter-spacing: .22em; }}
.bar {{ position: absolute; bottom: 40px; left: 70px; right: 70px; height: 3px; border-radius: 2px; overflow: hidden; }}
.bar i {{ display: block; width: 46%; height: 100%; }}
footer {{ padding: 30px 64px 56px; color: #6F6F6F; font-size: 14px; }}
</style></head><body>
<header><div><h1>Clairix · Street concepts</h1><p>Four directions. Pick one number. Each has its own mark, wordmark and colour palette.</p></div>
<div class="r">All type is SIL Open Font License, outlined and redrawn.<br>Every palette passes WCAG AA for text (4.5:1).</div></header>
{rows}
<footer>Sources: brand/concepts/N-name/ · Built by brand/tools/street/build.py and sheet.py</footer>
</body></html>"""
    (HERE / "sheet.html").write_text(html)
    print("sheet: wrote", HERE / "sheet.html")


if __name__ == "__main__":
    main()
