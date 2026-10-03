# Feature parity

What Reely does, and which apps do it. The Fire TV / Android TV app is the reference:
every other app is measured against it. ✅ done and tested · 🟡 partly · ⬜ not yet ·
— doesn't apply on that device.

Newer Fire TV sticks run **Vega OS**, which doesn't run Android apps. They need their own
app (Amazon's Vega SDK, React Native), which can reuse the LG app's TypeScript core
(`webos/src/api`, `webos/src/app`). Fire TV sticks on Fire OS run the APK.

| Feature | Fire TV / Android TV | Android phone & tablet | LG (webOS) | Roku | iPhone / iPad |
|---|---|---|---|---|---|
| **Plex** | | | | | |
| Sign in with a code / QR (TV) or the browser (phone) | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Finds the server: home first, then internet; moves with it | ✅ | ✅ | 🟡 (home first, no moving yet) | 🟡 (home first, no moving yet) | ⬜ |
| Signed in, no server: says so, keeps looking, Try again | ✅ | ✅ | 🟡 (says so, Try again) | 🟡 (says so) | ⬜ |
| Plex Home profiles, PINs, "Who's watching?" after sign-in | ✅ | ✅ | ✅ | ⬜ | ⬜ |
| Several servers, libraries from all of them | ✅ | ✅ | ✅ | ⬜ | ⬜ |
| Home: Continue Watching, recent episodes and movies, Watchlist, playlists | ✅ | ✅ | 🟡 (no Watchlist yet) | 🟡 (Continue Watching, recent episodes and movies) | ⬜ |
| Home rows switched on and off | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Movies / TV Shows: tab home, library grid, collections | ✅ | ✅ | 🟡 (grid) | ⬜ | ⬜ |
| Library sort, unwatched, genre, decade, A–Z | ✅ | ✅ (A–Z by sort) | 🟡 (sort) | ⬜ | ⬜ |
| Title page: play/resume/restart, watched, Watchlist, trailer, version | ✅ | ✅ | 🟡 (play/resume/restart) | 🟡 (play/resume/restart) | ⬜ |
| Seasons and episodes; show opens where it's up to | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Cast and actor pages, More like this, collections' pages | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Playlists: play, shuffle | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Hold a poster for its menu | ✅ | ✅ (long press) | ⬜ | ⬜ | ⬜ |
| Search: titles, people, collections, channels, recent searches | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| **Player** | | | | | |
| Direct play, conversion when needed, quality limit | ✅ | ✅ | 🟡 (no quality limit yet) | 🟡 (no quality limit yet) | ⬜ |
| Resume, progress and watched kept with Plex | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Sound and subtitle tracks, kept with Plex; subtitle size and style | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Find subtitles online | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Skip intro and credits; Up Next; next season | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Chapters, preview pictures while scrubbing | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Sleep timer | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Choose where sound plays (Bluetooth headphones) | ✅ | ✅ | — | — | ⬜ |
| Touch: tap, double-tap skip, drag the bar | — | ✅ | — | — | ⬜ |
| **Live TV** | | | | | |
| Xtream login and M3U playlists | ✅ | ✅ | ✅ | ⬜ | ⬜ |
| Categories, favorites, recently watched | ✅ | ✅ | 🟡 (categories, favorites) | ⬜ | ⬜ |
| Guide (grid on TV, list on phone), now and next | ✅ | ✅ | 🟡 (now and next on each channel) | ⬜ | ⬜ |
| Catch-up, start over, rewind and Go live | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Reminders | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Channel up/down, numbers on the remote | ✅ | ✅ (swipe) | ✅ | ⬜ | ⬜ |
| Multiview | ✅ | 🟡 (through the player's menus) | ⬜ | — | ⬜ |
| **IPTV movies and shows** | | | | | |
| Switch, tabs' IPTV library, Home rows, search, IPTV badge | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Matching with Plex, winner chosen | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Progress and watched kept on the device | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| **Requests (Reely)** | | | | | |
| Connect, rows, search, your requests, ready notices | ✅ | ✅ | 🟡 (no ready notices yet) | ⬜ | ⬜ |
| Request with seasons and library | ✅ | ✅ | ✅ | ⬜ | ⬜ |
| Rows leave out what's in the library; Reely sends three pages a row | ✅ | ✅ | ✅ | ⬜ | ⬜ |
| **App** | | | | | |
| Settings (every row) | ✅ | ✅ (same rows) | ⬜ | ⬜ | ⬜ |
| Updates from GitHub | ✅ | ✅ | — (store) | — (store) | — (store) |
| Screensaver, remote tour | ✅ | — | ⬜ | ⬜ | — |
| Crash report | ✅ | ✅ | ⬜ | ⬜ | ⬜ |

## Tests

| App | Where | How |
|---|---|---|
| Fire TV / Android TV and phone | `app/src/test` | `./gradlew :app:testDebugUnitTest` (Robolectric, Compose UI tests, screenshots with `-Pscreenshots`) |
| LG | `webos/` | `npm test` (unit, 135) and `npm run e2e` (browser, the built app as the TV opens it); `npm run package` makes `reely-lg.ipk` |
| Roku | `roku/` | `npm run check` (BrighterScript compile), `npm test` (unit, BrightScript simulator) and `npm run e2e` (the app in the SceneGraph simulator, driven by the remote protocol, against a stand-in Plex); `npm run package` makes `reely-roku.zip` |
