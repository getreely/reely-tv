# Feature parity

What Reely does, and which apps do it. The Fire TV / Android TV app is the reference:
every other app is measured against it. ✅ done and tested · 🟡 partly · ⬜ not yet ·
— doesn't apply on that device.

Newer Fire TV sticks run **Vega OS**, which doesn't run Android apps; Fire TV sticks on Fire
OS run the APK. The Vega app (`vega/`) is the LG app's page in a WebView, inside a small React
Native shell, so it has everything the **LG** column below has, the remote included. The
differences are Vega's own:

- Back comes to the shell, which hands it to the page.
- The shell closes the app.
- A server that won't answer the page (an IPTV provider's, say) is asked by the shell instead.
- HLS plays through hls.js and MPEG-TS through mpegts.js, as the WebView plays neither itself.
- Holding OK opens a poster's menu when OK comes up, if the remote doesn't repeat it.
- Leaving the app pauses the video, and coming back picks up with a fresh stream.
- Multiview, as on the Fire TV, which webOS can't do: hold OK on a channel for up to four at
  once, in a grid or with the one being heard large; arrows walk the tiles, OK makes one full
  screen, Back closes the one with the cursor, and the set can be saved. A provider that won't
  open another channel at once is said so on its tile.

None of it has been tried on a Vega device yet: building the app needs Amazon's Vega SDK (see
`vega/README.md`).

| Feature | Fire TV / Android TV | Android phone & tablet | LG (webOS) | Roku | iPhone, iPad and Apple TV |
|---|---|---|---|---|---|
| **Plex** | | | | | |
| Sign in with a code / QR (TV) or the browser (phone) | ✅ | ✅ | ✅ | ✅ | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Finds the server: home first, then internet; moves with it | ✅ | ✅ | ✅ | 🟡 (home first; looks again when it stops answering) | 🟡 (built and unit-tested; not yet tried on a device) |
| Signed in, no server: says so, keeps looking, Try again | ✅ | ✅ | ✅ | 🟡 (says so, keeps looking, OK tries again) | 🟡 (built; not yet tried on a device) |
| Plex Home profiles, PINs, "Who's watching?" after sign-in | ✅ | ✅ | ✅ | ✅ | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Several servers, libraries from all of them | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | 🟡 (built; not yet tried on a device) |
| A switch for each library in Settings: those switched on are the only ones on Home, in search and in the menus | ✅ | ✅ | ✅ | ✅ | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Home: Continue Watching, recent episodes and movies, Watchlist, playlists | ✅ | ✅ | ✅ | ✅ | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Home rows switched on and off | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Movies / TV Shows: tab home, library grid, collections | ✅ | ✅ | ✅ | 🟡 (tab home tested; grid and collections built) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Library sort, unwatched, genre, decade, A–Z | ✅ | ✅ (A–Z by sort) | ✅ | 🟡 (genre tested; the rest built) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Title page: play/resume/restart, watched, Watchlist, trailer, version | ✅ | ✅ | ✅ | 🟡 (play and resume tested; the rest built) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Seasons and episodes; show opens where it's up to | ✅ | ✅ | ✅ | ✅ | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Cast and actor pages, More like this, collections' pages | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | 🟡 (built; not yet tried on a device) |
| Playlists: play, shuffle | ✅ | ✅ | ✅ | 🟡 (built; simulator test to come) | 🟡 (built; not yet tried on a device) |
| Hold a poster for its menu | ✅ | ✅ (long press) | ✅ | ✅ (the ✱ key, as Roku apps do) | 🟡 (long press on iPhone, a held OK on Apple TV; built) |
| Search: titles, people, collections, channels, recent searches | ✅ | ✅ | ✅ | 🟡 (titles and people tested; collections and recent searches built; channels with Live TV) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| **Player** | | | | | |
| Direct play, conversion when needed, quality limit | ✅ | ✅ | ✅ | 🟡 (conversion tested; quality limit built) | 🟡 (Apple's player plays MP4/MOV with H.264, HEVC and common sound as it is; Plex converts the rest; built) |
| Resume, progress and watched kept with Plex | ✅ | ✅ | ✅ | ✅ | 🟡 (built; not yet tried on a device) |
| Sound and subtitle tracks, kept with Plex; subtitle size and style | ✅ | ✅ | ✅ | 🟡 (the choices tested; size and style are the Roku's caption style) | 🟡 (built; not yet tried on a device) |
| Color of your own (blue by default): highlights, progress and the mark | ✅ | ✅ (same setting) | ✅ | ✅ (as each screen is drawn) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Home and the Movies/TV Shows homes: hero with the title, details, summary and backdrop of what has the cursor, the first title before it does | ✅ | ✅ | ✅ | ✅ (title in text: Roku items carry no logo) | 🟡 (hero on Apple TV; seen in the simulator) |
| Subtitles off at the start (forced ones still shown) | ✅ | ✅ (same setting) | ✅ | ✅ | 🟡 (built; not yet tried on a device) |
| Find subtitles online | ✅ | ✅ | ✅ | ✅ (found, added and switched on, in the simulator) | 🟡 (built; not yet tried on a device) |
| Skip intro and credits; Up Next; next season | ✅ | ✅ | ✅ | ✅ (Skip Intro in the simulator; Up Next and skipping in unit tests) | 🟡 (built; not yet tried on a device) |
| Chapters, preview pictures while scrubbing | ✅ | ✅ | ✅ | 🟡 (chapters tested; preview pictures are Roku's own, from Plex's index) | 🟡 (chapters, and the picture above the bar while dragging it, or while scrubbing with left and right on Apple TV; built) |
| Sleep timer | ✅ | ✅ | ✅ | 🟡 (offered in the player; built) | 🟡 (built; not yet tried on a device) |
| A dropped connection picks up again: a fresh stream from where it was, three tries, then OK / Try again | ✅ | ✅ | 🟡 (built; not yet tried on a TV) | 🟡 (built; not yet tried on a device) | 🟡 (built; unit-tested) |
| Choose where sound plays (Bluetooth headphones) | ✅ | ✅ | — | — | 🟡 (AirPlay picker in Settings on iPhone and iPad; Apple TV's own) |
| Touch: tap, double-tap skip, drag the bar | — | ✅ | — | — | 🟡 (tap, double-tap skip and drag the bar; built) |
| **Live TV** | | | | | |
| Xtream login and M3U playlists | ✅ | ✅ | ✅ | ✅ (Xtream signed in in the simulator; a playlist's channels and guide there too) | 🟡 (built and unit-tested; HLS in Apple's player, MPEG-TS in VLC's) |
| Categories, favorites, recently watched | ✅ | ✅ | ✅ | ✅ | 🟡 (built; seen in the simulator, not yet tried on a device) |
| Guide (grid on TV, list on phone), now and next | ✅ | ✅ | ✅ (grid) | ✅ (grid) | 🟡 (built; seen in the simulator, not yet tried on a device) |
| A playlist's own XMLTV guide (named in it or entered at sign-in), read whole and kept; "TV guide: None" in Settings when there isn't one | ✅ | ✅ | ✅ (a gzipped guide opens on 2022 TVs and later, webOS 22 on; older ones say they can't) | ✅ (six hours back to a day and a half ahead; a gzipped guide can't be opened on a Roku, and it says so) | 🟡 (built and unit-tested) |
| Down while watching a channel: the guide over it, the channel playing on; categories above, OK to watch (or from the archive), a channel menu with a reminder and Favorites, Back to put it away | ✅ | ✅ (the Channels button over the channel: categories and their channels in a sheet, the channel playing on) | ✅ | ✅ (the menu on ✱, as everywhere on a Roku; categories in it as well as above, as a Roku's grid goes round from the first channel) | 🟡 (Apple TV, seen in the simulator; on iPhone the Channels button over the channel) |
| Catch-up, start over, rewind and Go live | ✅ | ✅ | ✅ (coloured keys) | ✅ (catch-up tested; Start over and Go live on ✱) | 🟡 (built; not yet tried on a device) |
| Reminders | ✅ | ✅ | ✅ | ✅ (set in the guide; when they're due, unit-tested) | 🟡 (built and unit-tested) |
| Channel up/down, numbers on the remote | ✅ | ✅ (swipe) | ✅ | ✅ (left and right; Roku remotes have no number keys) | 🟡 (left and right on Apple TV, swipe on iPhone; numbers typed in, as the Siri remote has no number keys) |
| Multiview | ✅ | ✅ (a tap hears a tile, a double tap makes it full screen, a hold has its menu; channels added from the sheet) | — (webOS gives an app one video at a time; on Vega, ✅, as above) | — | 🟡 (built: up to four, grid or focus, the tile menu on a long press or held OK, saved set; not yet tried on a device) |
| **IPTV movies and shows** | | | | | |
| Switch, tabs' IPTV library, Home rows, search, IPTV badge | ✅ | ✅ | ✅ | ✅ (switch, Movies tab, Home row, search and a series' page in the simulator) | 🟡 (built and unit-tested; MKV and the like play in VLC's player, the file's own sound and subtitle tracks with them) |
| Matching with Plex, winner chosen | ✅ | ✅ | ✅ | ✅ (matching in the simulator; the winner unit-tested) | 🟡 (unit-tested) |
| Progress and watched kept on the device | ✅ | ✅ | ✅ | ✅ (unit-tested; the latest 50 kept, the Roku's storage being small) | 🟡 (unit-tested) |
| **Requests (Reely)** | | | | | |
| Connect, rows, search, your requests, ready notices | ✅ | ✅ | ✅ | 🟡 (connect, rows, your requests and ready notices tested; search built) | 🟡 (built and unit-tested; seen in the simulator) |
| Request with seasons and library | ✅ | ✅ | ✅ | ✅ | 🟡 (built and unit-tested) |
| Rows leave out what's in the library; Reely sends three pages a row | ✅ | ✅ | ✅ | ✅ (what Reely and Plex have left out, in the simulator) | 🟡 (unit-tested) |
| **App** | | | | | |
| Settings (every row) | ✅ | ✅ (same rows) | ✅ (all but frame rate, buffer and Bluetooth, which webOS handles itself) | ✅ (theme music on or off: the Roku gives an app's sound no volume of its own; channels and the guide are read afresh each time Live TV opens; frame rate, buffer, Bluetooth and subtitle style are the Roku's own) | 🟡 (every row that applies; seen in the simulator) |
| Updates from GitHub | ✅ | ✅ | — (store) | — (store) | — (store) |
| Screensaver, remote tour | ✅ | — | ✅ | ✅ (the Roku starts the screensaver after its own idle time, set in its Settings) | 🟡 (Apple TV: screensaver and the remote tour, again from Settings; built) |
| Crash report | ✅ | ✅ | ✅ (problem report) | 🟡 (problem report of what fails in background work; Roku gives apps no hook for the rest) | 🟡 (a crash as iOS reports it on iPhone and iPad, and an end while open everywhere, in Settings under About; built) |

## Look and feel

The table above is what each app can do. How it looks is a separate question: each
screen of the LG and Roku apps measured against the Fire TV's screenshots.

| Screen | LG | Roku |
|---|---|---|
| Home and the tabs' homes: hero with picture, title, details | ✅ | ✅ |
| Overall size of text and spacing | ✅ | ✅ |
| Title page: backdrop, logo, ratings, round buttons, episodes as a row of pictures | ✅ | ✅ |
| Settings: sections down the left, a row opens its choices | ✅ | ✅ |
| Player: a panel for each of sound, subtitles, chapters; full-screen Up Next with picture | ✅ | ✅ |
| Guide: channel tiles, the programme's details above | ✅ | ✅ (one color for the tiles, and the cursor white, as the Roku's grid draws them) |
| Library: sort, decade and genres as chips, sort and decade opening their lists | ✅ | ✅ |
| Poster menu: side sheet with the title's picture, each choice with its icon | ✅ | ✅ |

## Tests

| App | Where | How |
|---|---|---|
| Fire TV / Android TV and phone | `app/src/test` | `./gradlew :app:testDebugUnitTest` (Robolectric, Compose UI tests, screenshots with `-Pscreenshots`) |
| LG | `webos/` | `npm test` (unit, 168) and `npm run e2e` (browser, the built app as the TV opens it); `npm run package` makes `reely-lg.ipk` |
| iPhone, iPad and Apple TV | `ios/` | `scripts/vlckit.sh` fetches VLC's engine (VideoLAN's builds, checksums checked); `swift test` in `ios/ReelyCore` (the core, on Linux or a Mac); GitHub builds both apps on a Mac and posts simulator screenshots of each screen (`.github/workflows/ios.yml`) |
| Roku | `roku/` | `npm run check` (BrighterScript compile), `npm test` (unit, 275, BrightScript simulator) and `npm run e2e` (the app in the SceneGraph simulator, driven by the remote protocol, against a stand-in Plex); `npm run package` makes `reely-roku.zip` |
