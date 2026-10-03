# The tab bar's search and gear, drawn as the Fire TV draws them (Icons.kt: SearchGlyph,
# GearGlyph), white, to be tinted with blendColor. Run again if the drawing changes.
import math
from PIL import Image, ImageDraw

SIZE = 40
SCALE = 8
s = SIZE * SCALE


def line(d, a, b, width):
    d.line([a, b], fill=(255, 255, 255, 255), width=int(width))
    r = width / 2
    for (x, y) in (a, b):
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, 255))


def ring(d, cx, cy, radius, width):
    d.ellipse([cx - radius - width / 2, cy - radius - width / 2, cx + radius + width / 2, cy + radius + width / 2], fill=(255, 255, 255, 255))
    d.ellipse([cx - radius + width / 2, cy - radius + width / 2, cx + radius - width / 2, cy + radius - width / 2], fill=(0, 0, 0, 0))


def search():
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ring(d, s * 0.42, s * 0.42, s * 0.3, s * 0.12)
    line(d, (s * 0.63, s * 0.63), (s * 0.86, s * 0.86), s * 0.12)
    return img


def gear():
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rim = s * 0.27
    c = s / 2
    for i in range(8):
        a = i * math.pi / 4
        line(d, (c + math.cos(a) * rim, c + math.sin(a) * rim), (c + math.cos(a) * s * 0.45, c + math.sin(a) * s * 0.45), s * 0.12)
    ring(d, c, c, rim, s * 0.13)
    return img


for name, draw in (("search", search), ("gear", gear)):
    draw().resize((SIZE, SIZE), Image.LANCZOS).save(f"images/{name}.png")
