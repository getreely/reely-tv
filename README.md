# Reely TV

**Your Plex library and live TV, in one app on the television.**

Reely TV is an app for Android TV and Amazon Fire TV. It plays movies and shows from Plex,
and live TV from an IPTV subscription, in one app. For live TV there's a channel guide and
multiview, which puts up to four channels on screen at once.

It connects to your own Plex server and your own TV provider. No service of ours sits
between you and them.

## Install

1. On a Fire TV, turn on **Settings → My Fire TV → Developer options → Install unknown
   apps** for the **Downloader** app. On Android TV, allow unknown sources for your file
   manager or browser.
2. In Downloader, open
   `https://github.com/getreely/reely-tv/releases/latest/download/reely-tv.apk`
   and install it.

After that, Reely updates itself: **Settings → Updates → Check for updates**.

Android 6 or later is needed, which covers Fire OS 6 and every current Fire TV and Android TV
device.

## What it does

### Plex
- **Sign in with a QR code** or the four-character code at plex.tv/link.
- **Home** has Continue Watching and the newest movies and episodes, across every server
  your account can reach.
- **Movies** and **TV Shows** each have their own home, the full library (sort, genres,
  unwatched), and **collections**.
- **Title pages**: the title's logo and artwork, ratings, the cast and crew, a description
  you can open in full, "More like this", trailers, seasons and episodes, and air dates.
- **Plex Home profiles**: "Who's watching?", with PIN-protected profiles.
- **Skip Intro**, and the **Up Next** screen with a countdown to the next episode. The next
  episode can be in the next season.
- **Plays the file as it is** whenever the television can. Dolby Digital, Dolby Digital Plus,
  DTS and TrueHD audio is decoded in the app when the television can't. When a file won't
  play, Plex converts it on the server instead, automatically.
- Subtitles and audio tracks can be chosen while you watch. Subtitle size and background
  are adjustable.

### Live TV
- Works with **Xtream Codes** providers: the server address, username and password they
  gave you.
- Channels grouped by your provider's own categories.
- **TV guide**, from your provider's own guide data.
- **Multiview**: up to four channels at once. The sound follows whichever one you move
  to, and any of them can be made full screen or changed to another channel.
- Channel up and down from the player, with an on-screen guide while you watch.

## Privacy

- Your Plex sign-in and your TV provider login are kept on the device, encrypted with a key
  held by Android's secure key store. They are only ever sent to Plex and to your provider.
- Reely has no accounts, no analytics and no advertising, and nothing is sent anywhere else.
- If the app crashes, a report stays on the device, where you can read or clear it in
  **Settings → About**.

## Remote

| Button | What it does |
| --- | --- |
| Arrows | Move around. In the player, show the controls; on the progress bar, left and right skip back and forward. |
| OK | Choose. In the player, show the controls. |
| Hold OK | More options for a card, or for a channel in multiview. |
| Back | Go back. In the player, hide the controls, then leave. |
| Left / Right on live TV | Previous or next channel. |
| Down on live TV | The guide. |

## Building

You need JDK 17 or newer, and the Android SDK with platform 37.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

Without a release keystore the APK is signed with the debug key, which is enough to
sideload it. To sign it properly, set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` as Gradle properties.

Releases are built by the **Release APK** workflow. It publishes `reely-tv.apk` together
with `reely-tv.json`, the file the app's update check reads.

Tests: `./gradlew testDebugUnitTest`. Add `-Pscreenshots` to render every screen into
`app/build/screenshots`.

## Licences

Reely TV uses FFmpeg (LGPL 2.1, unmodified, for audio decoding), the Geist typeface (SIL
Open Font License), and open-source libraries under the Apache 2.0 licence. The full texts
are in the app under **Settings → About**.

Reely TV is not affiliated with Plex, Inc. Plex is a trademark of Plex, Inc.
