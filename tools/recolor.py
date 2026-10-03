# Recolours the apps' images from one palette to another: the background and the accent,
# and everything blended between them (the logo's anti-aliased edges) in proportion.
# Usage: python3 tools/recolor.py FILE...
import sys
from PIL import Image

OLD_BG, OLD_ACCENT = (10, 9, 9), (255, 94, 105)
NEW_BG, NEW_ACCENT = (8, 9, 11), (46, 107, 255)


def recolor(path):
    im = Image.open(path).convert("RGBA")
    d = [OLD_ACCENT[i] - OLD_BG[i] for i in range(3)]
    dd = sum(x * x for x in d)
    out = []
    for r, g, b, a in list(im.getdata()):
        v = (r - OLD_BG[0], g - OLD_BG[1], b - OLD_BG[2])
        t = max(0.0, min(1.0, sum(v[i] * d[i] for i in range(3)) / dd))
        on = [OLD_BG[i] + t * d[i] for i in range(3)]
        off = sum((c - o) ** 2 for c, o in zip((r, g, b), on)) ** 0.5
        if off > 40:
            out.append((r, g, b, a))  # not of the palette: left as it is
        else:
            out.append(tuple(round(NEW_BG[i] + t * (NEW_ACCENT[i] - NEW_BG[i])) for i in range(3)) + (a,))
    im.putdata(out)
    im.save(path, optimize=True)


for p in sys.argv[1:]:
    recolor(p)
    print("recoloured", p)
