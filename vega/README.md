# Reely for Fire TV on Vega OS

Newer Fire TV sticks (the Fire TV Stick 4K Select and later) run **Vega OS**, which doesn't
run Android apps, so the APK can't be installed on them. This is the Vega app.

It's a React Native app of its own, built for Vega with Amazon's SDK, and it looks and works
like the Fire TV app screen for screen: Home and its hero, Movies and TV Shows with their
library grids, title pages, search, the player with its panels, Skip Intro and Up Next, Live
TV with the guide, catch-up and Multiview, IPTV movies and shows, Requests, Settings,
profiles, the tour and the screensaver. The remote works as it does on the Fire TV: arrows,
OK, Back, holding OK for a menu, play/pause, rewind and fast-forward, and number keys for a
channel.

- **`src/api`, `src/core`, `src/app`**: Plex, the IPTV provider, Reely and everything the
  screens do with them, in plain TypeScript (`src/app/store.ts` holds it together).
- **`src/ui`**: the screens, drawn with React Native's views at the Fire TV's sizes, and the
  remote's focus moving between them as Vega's focus engine does it.
- **`src/player`**: Vega's own video player (W3C Media), with Amazon's build of hls.js for HLS:
  Plex's conversions and live channels. Several at once for Multiview.

Vega has no way for an app to update itself, so there's no update prompt: a new version is
installed as below, or from the Appstore once it's there.

## What you need

- A Mac (macOS 10.15 or later) or Ubuntu (20.04 or later) computer. The Vega tools don't run
  on Windows.
- A free Amazon Developer account: <https://developer.amazon.com>
- Node.js 18 or later.
- The **Vega CLI and SDK**, from Amazon's installer (no sign-in needed):

  ```bash
  curl -fsSL https://sdk-installer.vega.labcollab.net/get_vvm.sh | bash
  ```

  Open a new terminal afterwards. `vega --version` should say Vega CLI 1.4 or later, with
  `vega devmode` in `vega --help`: an older `vega` (from an SDK downloaded before 0.24) has no
  `devmode` or `update`, so run the installer again to replace it.

## Build it

```bash
git clone https://github.com/getreely/reely-tv.git
cd reely-tv/vega
vega project install --fix    # lines Amazon's packages and the manifest up with your SDK
npm install
npm run build:release         # fetches Amazon's hls.js, then builds the Vega package
```

`npm run build:release` runs `scripts/hlsjs.sh` first. That fetches Amazon's build of hls.js for
Vega (checked against its checksum) and builds it into `src/hls`. It isn't kept in this
repository, as it's under Amazon's own licence.

## Try it without a Vega stick

The SDK comes with the **Vega Virtual Device**, a Vega Fire TV that runs on your computer:

```bash
vega virtual-device start     # wait for it to finish starting (30–60 seconds)
vega device install-app --dir . -b Release
vega device launch-app --dir .
```

The arrow keys, Enter and Escape on your keyboard work as the remote.

## Put it on a Vega Fire TV

Once, to put the stick in developer mode:

1. On the computer, run `vega devmode login` and sign in with your Amazon Developer account.
2. On the Fire TV, go to **Settings → My Fire TV → About** and select the device name
   **7 times**. Then go back to **Developer Options → Developer Mode → Continue**. The TV
   shows a 6-character code, which lasts 5 minutes.
3. On the computer, run `vega devmode enable-device --code <the code>`. The stick restarts.
4. The stick starts out reachable only by USB. To reach it over Wi-Fi instead, note its IP
   address under **Settings → My Fire TV → About → Network**, then go to **Developer Options →
   Connection Mode**, choose **TCP/IP** on port **5555** and select **Continue**. The stick
   restarts. Then, on the computer:

   ```bash
   vega exec vda connect <the stick's IP>:5555
   vega exec vda devices      # the stick, followed by "device"
   ```

   If it isn't listed, `vega exec vda kill-server` and connect again. The computer and the
   stick need the same network, with port 5555 open between them.

Then, to install a release, with nothing to build: download `reely-vega-armv7.vpkg` from the
latest `vega-v…` release on <https://github.com/getreely/reely-tv/releases>, and with the
computer on the same network as the stick:

```bash
vega device install-app --packagePath reely-vega-armv7.vpkg
```

Reely is then in the stick's apps. Each release has a package for each kind of device:
`armv7` is for Fire TV sticks; `aarch64` and `x86_64` are for the Vega Virtual Device on an
Apple-silicon Mac or an Intel computer.

Or, to install your own build:

```bash
npm run build:release
vega device install-app --dir . -b Release
vega device launch-app --dir .
```

## Checks

- `npm run typecheck`: the app against Amazon's packages.
- `npm test`: the project's files agree with each other: `app.json`, `manifest.toml`,
  `package.json` and the version the app shows in Settings.
- `preview/`: the screens in a browser, through react-native-web, driven with the keyboard as
  the remote by Playwright against a stand-in Plex, IPTV provider and Reely. Each test saves
  screenshots in `preview/shots`, which is how each screen is checked against the Fire TV's.

  ```bash
  cd preview
  npm install
  npx playwright test
  ```

  The keyboard as the remote: the arrows, Enter (OK), Escape (Back), the space bar
  (play/pause), comma and full stop (rewind and fast-forward), `m` (the Menu key), `i` (Info)
  and the number keys.
- `vega project doctor`, with the SDK installed: Amazon's own check of the project.
