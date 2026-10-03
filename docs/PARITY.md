# Feature parity

What Reely does, and which apps do it. The Fire TV / Android TV app is the reference:
every other app is measured against it. ✅ done and tested · 🟡 partly · ⬜ not yet ·
— doesn't apply on that device.

| Feature | Fire TV / Android TV | Android phone & tablet | LG (webOS) | Roku | iPhone / iPad |
|---|---|---|---|---|---|
| **Plex** | | | | | |
| Sign in with a code / QR (TV) or the browser (phone) | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Finds the server: home first, then internet; moves with it | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Signed in, no server: says so, keeps looking, Try again | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Plex Home profiles, PINs, "Who's watching?" after sign-in | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Several servers, libraries from all of them | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Home: Continue Watching, recent episodes and movies, Watchlist, playlists | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Home rows switched on and off | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Movies / TV Shows: tab home, library grid, collections | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Library sort, unwatched, genre, decade, A–Z | ✅ | ✅ (A–Z by sort) | ⬜ | ⬜ | ⬜ |
| Title page: play/resume/restart, watched, Watchlist, trailer, version | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Seasons and episodes; show opens where it's up to | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Cast and actor pages, More like this, collections' pages | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Playlists: play, shuffle | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Hold a poster for its menu | ✅ | ✅ (long press) | ⬜ | ⬜ | ⬜ |
| Search: titles, people, collections, channels, recent searches | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| **Player** | | | | | |
| Direct play, conversion when needed, quality limit | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Resume, progress and watched kept with Plex | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Sound and subtitle tracks, kept with Plex; subtitle size and style | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Find subtitles online | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Skip intro and credits; Up Next; next season | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Chapters, preview pictures while scrubbing | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Sleep timer | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Choose where sound plays (Bluetooth headphones) | ✅ | ✅ | — | — | ⬜ |
| Touch: tap, double-tap skip, drag the bar | — | ✅ | — | — | ⬜ |
| **Live TV** | | | | | |
| Xtream login and M3U playlists | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Categories, favorites, recently watched | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Guide (grid on TV, list on phone), now and next | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Catch-up, start over, rewind and Go live | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Reminders | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Channel up/down, numbers on the remote | ✅ | ✅ (swipe) | ⬜ | ⬜ | ⬜ |
| Multiview | ✅ | 🟡 (through the player's menus) | ⬜ | — | ⬜ |
| **IPTV movies and shows** | | | | | |
| Switch, tabs' IPTV library, Home rows, search, IPTV badge | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Matching with Plex, winner chosen | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Progress and watched kept on the device | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| **Requests (Reely)** | | | | | |
| Connect, rows, search, your requests, ready notices | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Request with seasons and library | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| **App** | | | | | |
| Settings (every row) | ✅ | ✅ (same rows) | ⬜ | ⬜ | ⬜ |
| Updates from GitHub | ✅ | ✅ | — (store) | — (store) | — (store) |
| Screensaver, remote tour | ✅ | — | ⬜ | ⬜ | — |
| Crash report | ✅ | ✅ | ⬜ | ⬜ | ⬜ |

## Tests

| App | Where | How |
|---|---|---|
| Fire TV / Android TV and phone | `app/src/test` | `./gradlew :app:testDebugUnitTest` (Robolectric, Compose UI tests, screenshots with `-Pscreenshots`) |
| LG | `webos/` | `npm test` (unit) and `npm run e2e` (browser) |
| Roku | `roku/` | `npm test` (BrightScript simulator) |
