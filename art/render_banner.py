"""
The TV home screen's tile: the wordmark in Geist Bold, the "r" underlined as in the mark, the app's own coral on its own dark
ground, as wide as it can sit comfortably. It used to be the single "r" mark in the middle
of the tile, which on a Fire TV's row of apps read as a small logo in an empty box.

    python3 art/render_banner.py
"""
from PIL import Image, ImageDraw, ImageFont

FONT = "app/src/main/res/font/geist_bold.ttf"
DARK = (10, 9, 9, 255)       # Ink
CORAL = (255, 94, 105, 255)  # Accent


def banner(w, h, path, frac=0.74):
    s = 4  # drawn at four times the size and scaled down, for clean edges
    W, H = w * s, h * s
    im = Image.new("RGBA", (W, H), DARK)
    d = ImageDraw.Draw(im)
    size = 100
    while True:
        font = ImageFont.truetype(FONT, size)
        box = d.textbbox((0, 0), "reely", font=font)
        if box[2] - box[0] >= frac * W:
            break
        size += 4
    word = d.textbbox((0, 0), "reely", font=font)
    r = d.textbbox((0, 0), "r", font=font)
    # The underline under the "r", as in the mark on the icon and at the top of every
    # screen: a gap of a sixth of the r's height, a bar a ninth as thick, a little
    # narrower than the letter and centred under it.
    r_h = r[3] - r[1]
    gap, thick = round(r_h * 0.17), round(r_h * 0.11)
    bar_w = round((r[2] - r[0]) * 0.8)
    bottom = max(word[3], r[3] + gap + thick)
    tw, th = word[2] - word[0], bottom - word[1]
    x = (W - tw) // 2 - word[0]
    y = (H - th) // 2 - word[1]
    d.text((x, y), "reely", font=font, fill=CORAL)
    cx = x + (r[0] + r[2]) // 2
    top = y + r[3] + gap
    d.rounded_rectangle([cx - bar_w // 2, top, cx + bar_w // 2, top + thick], radius=thick // 2, fill=CORAL)
    im.resize((w, h), Image.LANCZOS).convert("RGB").save(path, optimize=True)


banner(320, 180, "app/src/main/res/drawable-xhdpi/reely_banner_wordmark.png")
banner(640, 360, "app/src/main/res/drawable-xxxhdpi/reely_banner_wordmark.png")
