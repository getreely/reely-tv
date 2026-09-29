# Launcher art

The drawings behind the launcher banner (`res/drawable-xhdpi/reely_banner.png`, 320 × 180,
and `drawable-xxxhdpi`, 640 × 360) and icon (`res/mipmap-*/reely_icon.png`, 48 to 192 px).

They ship as PNGs because Fire TV's home screen does not reliably draw a vector banner or
an adaptive icon, and Amazon asks for PNGs. To change them, edit these vectors, put them
back under `res/drawable` for a moment, render each to a bitmap of the sizes above
(a Robolectric test in `GraphicsMode.NATIVE` can do it), and replace the PNGs.
