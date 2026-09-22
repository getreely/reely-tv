# Live television inside Plezy

This is the whole of the 0.26.0 experiment: Plezy's Plex client, with this project's
live television grafted on as a native Activity beside it.

## Why it is shaped like this

Plezy is Flutter. Everything here is Kotlin and Compose for TV. The obvious plan — port
the Xtream code, the guide and the multiview grid to Dart — would have meant rewriting
about 2,400 lines inside an unfamiliar 386,000-line codebase, and rebuilding multiview
from nothing because Plezy has no equivalent.

So the two halves do not share a screen. Flutter draws the Plex library; a second
Activity draws the channels. The Xtream code carries over unchanged, and the two focus
systems never have to be reconciled because they are never composed together.

What that costs is a seam: crossing from one to the other is crossing between two
toolkits, and it will look like it unless the styling is matched deliberately.

## What was added

- `android/app/src/main/kotlin/tv/reely/` — the live television half of this app,
  moved rather than rewritten. Xtream panel API, XMLTV import, the guide, the player,
  multiview, and the focus helpers they depend on.
- `tv/reely/live/LiveViewModel.kt` — the live half of the original view model, lifted
  out of the class that also carried Plex. It needed no changes: the live state never
  referred to Plex in the first place, which is the whole reason this was worth trying.
- A method channel (`tv.reely/live`) in Plezy's `MainActivity`, and a leanback launcher
  entry, so live television can be reached either from inside the app or directly.

## What was stripped

Everything Plex-shaped that the live path cannot use: intro and credit markers, the
episode queue and the Up Next prompt, sidecar subtitle tracks, library search, and the
theme music that plays under a show's page. All of that is Flutter's side of the app now.

## Building it

```
git clone https://github.com/edde746/plezy.git
cd plezy && git checkout $(cat UPSTREAM_COMMIT)
git apply /path/to/reely-livetv.patch
flutter build apk --release --target-platform android-arm64
```

Needs CMake 4.1.2 from the Android SDK, and `compileSdk` 37 — Flutter still defaults to
36, and androidx.compose 1.12 will not build against it. The patch handles the second.

## Licence

Plezy is GPL-3.0, so anything built from it is too. `UPSTREAM_COMMIT` plus this patch is
the complete corresponding source for the binary published as 0.26.0.
