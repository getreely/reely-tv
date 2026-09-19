# Reely TV

An Android TV / Fire TV client that puts a Plex library and category-first live TV into one
app on the television. Each install points at its own Plex server and its own IPTV provider;
nothing is baked in and no server of ours sits in the path.

This repository currently holds **the thin spike**, not the app. It exists to answer two
questions that cannot be settled by reasoning — whether a provider's streams decode
acceptably on a Firestick, and whether D-pad navigation feels tolerable.

## What it does

- **Movies** and **TV Shows** are separate tabs, filled from the Plex libraries of the
  matching type. Shows drill down show → season → episode.
- **Live TV** lists the provider's own categories down the left and that category's channels
  on the right. Category-first browsing is the whole reason this app exists.
- **Status** reports what is connected and lets you sign out of either half.
- Either sign-in is skippable. Configure Plex and no provider, or a provider and no Plex,
  and the app still starts and fills the tabs it can.

Sign in to Plex with the plex.tv PIN flow (a code you type at `plex.tv/link`). Sign in to
live TV with the Xtream panel address, username and password your provider gave you.

## The player is an instrument

Playback runs through Media3/ExoPlayer, and the overlay reports what the spike needs to know:
time to first frame, the decoded video and audio formats, buffer depth, and the actual error
when a stream is refused — a provider at its connection cap looks exactly like a broken app
unless it says so.

| Key | On demand | Live |
| --- | --- | --- |
| OK / Play-Pause | play / pause | play / pause |
| Up / Down | cycle subtitle tracks | previous / next channel |
| Left / Right | seek back / forward | (right) switch MPEG-TS ↔ HLS |
| Back | leave the player | leave the player |

Channel surfing from the player is deliberate: channel-switch latency is what separates a
good TV app from a homebrew one, and the overlay times it on every switch.

## Playback, transcoding and subtitles

Playback is **direct play only**. The app asks Plex for the file's part and streams it as it
sits on disk, so the stick has to decode whatever the file actually contains. Plex does all
its transcoding server-side, but a server only transcodes when a client *asks* it to, via the
universal transcoder — and this build never asks. A file the stick cannot decode will fail
rather than fall back.

Subtitles work for text tracks. Sidecar and separately-served subtitles (SRT, ASS/SSA, WebVTT)
are sideloaded as selectable tracks, and anything embedded in the container that ExoPlayer can
read joins the same list. Image-based subtitles (PGS, VOBSUB) are absent by design: those can
only be burned into the video by the server, which is a transcode.

## Building

Needs a JDK 17+ and an Android SDK with platform 35 and build-tools 35.0.0.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`. Without a release keystore
it is signed with the debug key, which is all a sideloaded build needs. To sign it properly,
set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` as Gradle properties.

Install it with `adb install -r app-release.apk`, or host the file and fetch it with the
Downloader app. There is no Play Store involved.

`minSdk` is 23, which covers Fire OS 6 and later. Fire OS 5 sticks are excluded: the
credential store needs the Keystore APIs from API 23.

## Credentials

The IPTV login is the user's own subscription; the Plex login is how they reach a library
somebody else owns and has shared with them. Both are entered per install, sealed with an
AES/GCM key held in the Android Keystore, and never leave the device.

## Deliberately not here yet

The EPG and any guide UI, Continue Watching and Recently Added hubs, per-group channel
visibility, recordings, catch-up and favourites, plain M3U/XMLTV providers, and any visual
polish. See the handoff brief for why each is out.
