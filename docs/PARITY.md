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
| Finds the server: home first, then internet; moves with it | ✅ | ✅ | ✅ | 🟡 (home first; looks again when it stops answering) | ⬜ |
| Signed in, no server: says so, keeps looking, Try again | ✅ | ✅ | ✅ | 🟡 (says so, keeps looking, OK tries again) | ⬜ |
| Plex Home profiles, PINs, "Who's watching?" after sign-in | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Several servers, libraries from all of them | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | ⬜ |
| Home: Continue Watching, recent episodes and movies, Watchlist, playlists | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Home rows switched on and off | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | ⬜ |
| Movies / TV Shows: tab home, library grid, collections | ✅ | ✅ | ✅ | 🟡 (tab home tested; grid and collections built) | ⬜ |
| Library sort, unwatched, genre, decade, A–Z | ✅ | ✅ (A–Z by sort) | ✅ | 🟡 (genre tested; the rest built) | ⬜ |
| Title page: play/resume/restart, watched, Watchlist, trailer, version | ✅ | ✅ | ✅ | 🟡 (play and resume tested; the rest built) | ⬜ |
| Seasons and episodes; show opens where it's up to | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Cast and actor pages, More like this, collections' pages | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | ⬜ |
| Playlists: play, shuffle | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | ⬜ |
| Hold a poster for its menu | ✅ | ✅ (long press) | ✅ | ✅ (the ✱ key, as Roku apps do) | ⬜ |
| Search: titles, people, collections, channels, recent searches | ✅ | ✅ | ✅ | 🟡 (titles and people tested; collections and recent searches built; channels with Live TV) | ⬜ |
| **Player** | | | | | |
| Direct play, conversion when needed, quality limit | ✅ | ✅ | ✅ | 🟡 (conversion tested; quality limit built) | ⬜ |
| Resume, progress and watched kept with Plex | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Sound and subtitle tracks, kept with Plex; subtitle size and style | ✅ | ✅ | ✅ | 🟡 (the choices tested; size and style are the Roku's caption style) | ⬜ |
| Color of your own (blue by default): highlights, progress and the mark | ✅ | ✅ (same setting) | ✅ | ✅ (as each screen is drawn) | ⬜ |
| Home and the Movies/TV Shows homes: hero with the title, details, summary and backdrop of what has the cursor, the first title before it does | ✅ | ✅ | ✅ | ✅ (title in text: Roku items carry no logo) | ⬜ |
| Subtitles off at the start (forced ones still shown) | ✅ | ✅ (same setting) | ✅ | ✅ | ⬜ |
| Find subtitles online | ✅ | ✅ | ✅ | ✅ (found, added and switched on, in the simulator) | ⬜ |
| Skip intro and credits; Up Next; next season | ✅ | ✅ | ✅ | ✅ (Skip Intro in the simulator; Up Next and skipping in unit tests) | ⬜ |
| Chapters, preview pictures while scrubbing | ✅ | ✅ | ✅ | 🟡 (chapters tested; preview pictures are Roku's own, from Plex's index) | ⬜ |
| Sleep timer | ✅ | ✅ | ✅ | 🟡 (offered in the player; built) | ⬜ |
| Choose where sound plays (Bluetooth headphones) | ✅ | ✅ | — | — | ⬜ |
| Touch: tap, double-tap skip, drag the bar | — | ✅ | — | — | ⬜ |
| **Live TV** | | | | | |
| Xtream login and M3U playlists | ✅ | ✅ | ✅ | 🟡 (Xtream tested; M3U parsing unit-tested) | ⬜ |
| Categories, favorites, recently watched | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Guide (grid on TV, list on phone), now and next | ✅ | ✅ | ✅ (grid) | ✅ (grid; a playlist's own XMLTV guide not yet) | ⬜ |
| Catch-up, start over, rewind and Go live | ✅ | ✅ | ✅ (coloured keys) | ✅ (catch-up tested; Start over and Go live on ✱) | ⬜ |
| Reminders | ✅ | ✅ | ✅ | ✅ (set in the guide; when they're due, unit-tested) | ⬜ |
| Channel up/down, numbers on the remote | ✅ | ✅ (swipe) | ✅ | ✅ (left and right; Roku remotes have no number keys) | ⬜ |
| Multiview | ✅ | 🟡 (through the player's menus) | — (webOS gives an app one video at a time) | — | ⬜ |
| **IPTV movies and shows** | | | | | |
| Switch, tabs' IPTV library, Home rows, search, IPTV badge | ✅ | ✅ | ✅ | ✅ (switch, Movies tab, Home row, search and a series' page in the simulator) | ⬜ |
| Matching with Plex, winner chosen | ✅ | ✅ | ✅ | ✅ (matching in the simulator; the winner unit-tested) | ⬜ |
| Progress and watched kept on the device | ✅ | ✅ | ✅ | ✅ (unit-tested; the latest 50 kept, the Roku's storage being small) | ⬜ |
| **Requests (Reely)** | | | | | |
| Connect, rows, search, your requests, ready notices | ✅ | ✅ | ✅ | 🟡 (connect, rows, your requests and ready notices tested; search built) | ⬜ |
| Request with seasons and library | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Rows leave out what's in the library; Reely sends three pages a row | ✅ | ✅ | ✅ | ✅ (what Reely and Plex have left out, in the simulator) | ⬜ |
| **App** | | | | | |
| Settings (every row) | ✅ | ✅ (same rows) | ✅ (all but frame rate, buffer and Bluetooth, which webOS handles itself) | 🟡 (no theme music, guide preview, or refresh channels and guide; frame rate, buffer, Bluetooth and subtitle style are the Roku's own) | ⬜ |
| Updates from GitHub | ✅ | ✅ | — (store) | — (store) | — (store) |
| Screensaver, remote tour | ✅ | — | ✅ | ✅ (the Roku starts the screensaver after its own idle time, set in its Settings) | — |
| Crash report | ✅ | ✅ | ✅ (problem report) | 🟡 (problem report of what fails in background work; Roku gives apps no hook for the rest) | ⬜ |

## Look and feel

The table above is what each app can do. How it looks is a separate question, and the
LG and Roku apps are not there yet: they have the same screens and choices as the Fire
TV, in the same colors, but drawn more plainly. Measured against the Fire TV's
screenshots:

| Screen | LG | Roku |
|---|---|---|
| Home and the tabs' homes: hero with picture, title, details | ✅ | ✅ |
| Overall size of text and spacing | ✅ | ✅ |
| Title page: backdrop, logo, ratings, round buttons, episodes as a row of pictures | ✅ | ⬜ no backdrop, episodes as a text list |
| Settings: sections down the left, a row opens its choices | ✅ | ⬜ one long list of pills |
| Player: a panel for each of sound, subtitles, chapters; full-screen Up Next with picture | ✅ | ⬜ one list; Up Next card |
| Guide: channel tiles, the programme's details above | 🟡 small grid, no tiles | 🟡 plain text grid |
| Library: views and sort in the tab's menu, filter chips | 🟡 pills | 🟡 pills |
| Poster menu: side sheet with the title's picture | ✅ | 🟡 no icons |

## Tests

| App | Where | How |
|---|---|---|
| Fire TV / Android TV and phone | `app/src/test` | `./gradlew :app:testDebugUnitTest` (Robolectric, Compose UI tests, screenshots with `-Pscreenshots`) |
| LG | `webos/` | `npm test` (unit, 168) and `npm run e2e` (browser, the built app as the TV opens it); `npm run package` makes `reely-lg.ipk` |
| Roku | `roku/` | `npm run check` (BrighterScript compile), `npm test` (unit, 275, BrightScript simulator) and `npm run e2e` (the app in the SceneGraph simulator, driven by the remote protocol, against a stand-in Plex); `npm run package` makes `reely-roku.zip` |
