"""
The Vega app's icon, 512 x 512: the "r" in Geist Bold with its underline, in the app's blue
on near-black, as on the other apps' icons.

    python3 vega/scripts/render-icon.py
"""
from PIL import Image, ImageDraw, ImageFont

FONT = "app/src/main/res/font/geist_bold.ttf"
INK = (8, 9, 11)
BLUE = (46, 107, 255)
S = 512 * 4  # drawn at four times the size and scaled down, for clean edges

im = Image.new("RGB", (S, S), INK)
d = ImageDraw.Draw(im)
font = ImageFont.truetype(FONT, int(S * 0.70))
r = d.textbbox((0, 0), "r", font=font)
rw, rh = r[2] - r[0], r[3] - r[1]
# The underline: a gap of a sixth of the r's height, a bar a ninth as thick, set under the
# letter as the font's box puts it, which leans it to the left, under the stem.
gap, thick = round(rh * 0.17), round(rh * 0.11)
x = (S - rw) // 2 - r[0]
y = (S - (rh + gap + thick)) // 2 - r[1]
d.text((x, y), "r", font=font, fill=BLUE)
bar = round(rw * 0.8)
cx = x + (r[0] + r[2]) // 2
top = y + r[3] + gap
d.rounded_rectangle([cx - bar // 2, top, cx + bar // 2, top + thick], radius=thick // 2, fill=BLUE)
im.resize((512, 512), Image.LANCZOS).save("vega/assets/image/app_icon.png")
