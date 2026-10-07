# Reely for Fire TV on Vega OS

Newer Fire TV sticks (the Fire TV Stick 4K Select and later) run **Vega OS**, which doesn't
run Android apps, so the APK can't be installed on them. This is the Vega app.

It is the LG app (`../webos`) in a WebView, inside a small React Native shell (`src/`). The
LG app already matches the Fire TV app screen for screen: every page and setting, the remote
(arrows, OK, Back, holding OK for a poster's menu), Live TV, IPTV and Requests. So the Vega
app does too, and fixes to one are fixes to both.

The shell does what a page can't do on its own:

- **Closes the app** when Back is pressed at Home.
- **Passes Back to the page.** Vega tells the shell, not the page.
- **Asks servers that don't answer other sites.** The page runs from the app's own files, so
  an IPTV provider that only answers its own site refuses it. The shell's requests aren't held
  to that.
- **Tells the page when the app goes away and comes back.** Vega takes the video decoder back
  while it's away, so the page pauses and then picks up again with a fresh stream.

The page plays HLS through hls.js and MPEG-TS through mpegts.js, because the WebView plays
neither by itself. On an LG TV, the TV's own player still plays both, as before.

## What you need

- A Mac (macOS 10.15 or later) or Ubuntu (20.04 or later) computer. The Vega tools don't run
  on Windows.
- A free Amazon Developer account: <https://developer.amazon.com>
- Node.js 18 or later.
- The **Vega SDK**. Sign in at
  <https://developer.amazon.com/docs/vega/0.21/install-vega-sdk.html>, run its installer, and
  open a new terminal afterwards so `vega` is on the path.

## Build it

```bash
git clone https://github.com/getreely/reely-tv.git
cd reely-tv/vega
vega project install --fix    # lines Amazon's packages and the manifest up with your SDK
npm install
npm run build:release         # builds the page (../webos), then the Vega package
```

`npm run build:release` runs `scripts/bundle-web.mjs` first. That builds the LG app and copies
it into `assets/web`, where the shell opens it.

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
4. Run `vega device list` and check that the stick is listed.

Then, each time:

```bash
npm run build:release
vega device install-app --dir . -b Release
vega device launch-app --dir .
```

## Checks

- `npm run typecheck`: the shell against Amazon's packages.
- `npm test`: the project's files agree with each other: `app.json`, `manifest.toml` and
  `package.json`.
- The bridge between the page and the shell is tested with the LG app's tests:
  `cd ../webos && npm test` (`tests/unit/vega.test.ts`).
- `vega project doctor`, with the SDK installed: Amazon's own check of the project.
