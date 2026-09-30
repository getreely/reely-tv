# Launcher art

The drawings behind the launcher banner (`res/drawable-xhdpi/reely_banner_wordmark.png`, 320 × 180,
and `drawable-xxxhdpi`, 640 × 360) and icon (`res/mipmap-*/reely_icon.png`, 48 to 192 px).

They ship as PNGs because Fire TV's home screen does not reliably draw a vector banner or
an adaptive icon, and Amazon asks for PNGs.

The banner is the "reely" wordmark in Geist Bold, the "r" underlined as in the mark, filling
the tile. Fire TV's home screen keeps its own copy of an app's tile and doesn't reliably
look again when the app updates; a new file name is what gets it to, so a changed banner
goes out under a new name. `python3 art/render_banner.py` draws both sizes from the font
in `res/font`.

The icon is drawn from `icon.xml`: put it back under `res/drawable` for a moment, render it to a bitmap of each size
above (a Robolectric test in `GraphicsMode.NATIVE` can do it), and replace the PNGs.
