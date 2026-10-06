#!/usr/bin/env python3
"""Reely's app icons for iPhone, iPad and Apple TV, drawn from the mark's own path (the one
in Logo.swift and the LG and Fire TV apps): the lowercase r with its underline, in Reely's
blue on near-black. Run from ios/: python3 scripts/icons.py"""
import json, os
from PIL import Image, ImageDraw

INK, BLUE = (8, 9, 11), (46, 107, 255)

def quad(p0, c, p1, n=24):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1])
            for t in (i / n for i in range(1, n + 1))]

def mark(draw, cx, top, height):
    """The mark, [height] tall, centred on [cx] from [top]: viewBox 43.1 30 22 46."""
    s = height / 46
    left = cx - 22 * s / 2
    P = lambda x, y: (left + (x - 43.1) * s, top + (y - 30) * s)
    pts = [P(43.19, 66), P(43.19, 30), P(52.72, 30), P(52.99, 37.19)]
    pts += quad(P(52.99, 37.19), P(54.07, 33.43), P(56.15, 31.71))
    pts += quad(P(56.15, 31.71), P(58.23, 30), P(61.52, 30))
    pts += [P(64.81, 30), P(64.81, 38.33), P(61.52, 38.33)]
    pts += quad(P(61.52, 38.33), P(57.29, 38.33), P(55.28, 40.04))
    pts += quad(P(55.28, 40.04), P(53.26, 41.75), P(53.26, 45.78))
    pts += [P(53.26, 66)]
    draw.polygon(pts, fill=BLUE)
    x0, y0 = P(45.5, 72)
    draw.rounded_rectangle([x0, y0, x0 + 17 * s, y0 + 4 * s], radius=2 * s, fill=BLUE)

def image(w, h, mark_height, background=True, scale=4):
    """Drawn large and shrunk, for smooth edges."""
    big = Image.new("RGBA", (w * scale, h * scale), INK + (255,) if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    if mark_height:
        mh = mark_height * scale
        mark(d, w * scale / 2, (h * scale - mh) / 2, mh)
    out = big.resize((w, h), Image.LANCZOS)
    return out.convert("RGB") if background else out

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)

INFO = {"author": "xcode", "version": 1}

# iPhone and iPad: one picture, every size made from it.
ios = "App/iOS/Assets.xcassets"
write(f"{ios}/Contents.json", {"info": INFO})
os.makedirs(f"{ios}/AppIcon.appiconset", exist_ok=True)
image(1024, 1024, 460).save(f"{ios}/AppIcon.appiconset/icon-1024.png")
write(f"{ios}/AppIcon.appiconset/Contents.json",
      {"images": [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": INFO})

# Apple TV: a layered icon (the mark over its ground, for the parallax), and the Top Shelf.
tv = "App/tvOS/Assets.xcassets"
brand = f"{tv}/App Icon & Top Shelf Image.brandassets"
write(f"{tv}/Contents.json", {"info": INFO})

def stack(name, w, h, idiom, scales):
    folder = f"{brand}/{name}.imagestack"
    write(f"{folder}/Contents.json", {"info": INFO, "layers": [{"filename": "Front.imagestacklayer"}, {"filename": "Back.imagestacklayer"}]})
    for layer, bg, mh in [("Front", False, h * 0.5), ("Back", True, 0)]:
        lf = f"{folder}/{layer}.imagestacklayer"
        write(f"{lf}/Contents.json", {"info": INFO})
        images = []
        for sc in scales:
            fn = f"{layer.lower()}-{sc}x.png"
            os.makedirs(f"{lf}/Content.imageset", exist_ok=True)
            image(w * sc, h * sc, mh * sc, background=bg).save(f"{lf}/Content.imageset/{fn}")
            images.append({"filename": fn, "idiom": idiom, "scale": f"{sc}x"})
        write(f"{lf}/Content.imageset/Contents.json", {"images": images, "info": INFO})

def shelf(name, w, h):
    folder = f"{brand}/{name}.imageset"
    os.makedirs(folder, exist_ok=True)
    images = []
    for sc in (1, 2):
        fn = f"shelf-{sc}x.png"
        image(w * sc, h * sc, h * 0.42 * sc).save(f"{folder}/{fn}")
        images.append({"filename": fn, "idiom": "tv", "scale": f"{sc}x"})
    write(f"{folder}/Contents.json", {"images": images, "info": INFO})

stack("App Icon", 400, 240, "tv", (1, 2))
stack("App Icon - App Store", 1280, 768, "tv", (1,))
shelf("Top Shelf Image", 1920, 720)
shelf("Top Shelf Image Wide", 2320, 720)
write(f"{brand}/Contents.json", {"info": INFO, "assets": [
    {"filename": "App Icon - App Store.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "1280x768"},
    {"filename": "App Icon.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "400x240"},
    {"filename": "Top Shelf Image Wide.imageset", "idiom": "tv", "role": "top-shelf-image-wide", "size": "2320x720"},
    {"filename": "Top Shelf Image.imageset", "idiom": "tv", "role": "top-shelf-image", "size": "1920x720"},
]})
print("icons written")
