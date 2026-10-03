import type { ComponentChildren } from "preact";
import type { App, AppState, PlaybackMode } from "../app/store";
import { BITRATE_CHOICES } from "../app/store";
import { Pill, useRescue } from "./parts";

/*
 * Settings, the Fire TV's sections that mean something on an LG: how video plays, the
 * Plex account and server, Live TV, Requests, and what this is.
 */

const MODES: Array<[PlaybackMode, string, string]> = [
  ["auto", "Automatic", "Plays the original file. Plex converts it only when needed."],
  ["direct", "Original only", "Always plays the original file. Some may not play."],
  ["transcode", "Always convert", "Plex converts everything. Uses more of your server."],
];

/** Where from, without what a playlist's address may carry in it: its login. */
const hostOf = (address: string) => {
  try {
    return new URL(address).host;
  } catch {
    return null;
  }
};

const bitrateLabel = (kbps: number) => (kbps <= 0 ? "Original" : `Up to ${kbps / 1000} Mbps`);
const bitrateNote = (kbps: number) =>
  kbps === 0 ? "As good as the file itself." : kbps === 20_000 ? "4K" : kbps === 12_000 ? "1080p, very high" : kbps === 8_000 ? "1080p" : kbps === 4_000 ? "720p" : "For a slow connection";

function Group(props: { title: string; children: ComponentChildren }) {
  return (
    <section class="setting-group">
      <h2>{props.title}</h2>
      {props.children}
    </section>
  );
}

function Setting(props: { title: string; note?: string | null; children?: ComponentChildren }) {
  return (
    <div class="setting">
      <div class="setting-title">{props.title}</div>
      {props.note ? <div class="note">{props.note}</div> : null}
      {props.children ? <div class="toolbar flush">{props.children}</div> : null}
    </div>
  );
}

export function Settings(props: { app: App; state: AppState; onProfiles: () => void }) {
  const { app, state } = props;
  const { prefs, plex, live, requests } = state;
  useRescue([]);
  const mode = MODES.find(([m]) => m === prefs.playbackMode) ?? MODES[0];
  return (
    <div class="settings">
      <Group title="Playback">
        <Setting title="Playback mode" note={mode[2]}>
          {MODES.map(([m, label]) => (
            <Pill key={m} label={label} on={prefs.playbackMode === m} autofocus={prefs.playbackMode === m} onPress={() => app.setPlaybackMode(m)} />
          ))}
        </Setting>
        <Setting title="Conversion quality" note={`The highest quality Plex uses when it converts a video. ${bitrateNote(prefs.maxBitrateKbps)}`}>
          {BITRATE_CHOICES.map((kbps) => (
            <Pill key={kbps} label={bitrateLabel(kbps)} on={prefs.maxBitrateKbps === kbps} onPress={() => app.setMaxBitrate(kbps)} />
          ))}
        </Setting>
      </Group>

      <Group title="Plex">
        <Setting title={plex.user?.title ?? "Signed in"} note={plex.serverName ? `Watching from ${plex.serverName}` : null}>
          {plex.homeUsers.length > 1 ? <Pill label="Switch profile" onPress={props.onProfiles} /> : null}
          <Pill label="Sign out" onPress={() => app.signOut()} />
        </Setting>
      </Group>

      <Group title="Live TV">
        {live.credentials ? (
          <Setting
            title={live.credentials.playlistUrl ? "M3U playlist" : live.credentials.username}
            note={hostOf(live.credentials.playlistUrl || live.credentials.base)}
          >
            <Pill label="Sign out of Live TV" onPress={() => app.signOutLive()} />
          </Setting>
        ) : (
          <Setting title="Not set up" note="Sign in to your provider from the Live TV tab.">
            <Pill label="Go to Live TV" onPress={() => app.navigate({ name: "live" })} />
          </Setting>
        )}
      </Group>

      <Group title="Requests">
        {requests.address ? (
          <Setting title="Reely" note={requests.address}>
            <Pill label="Disconnect" onPress={() => app.disconnectReely()} />
          </Setting>
        ) : (
          <Setting title="Not connected" note="Connect to Reely from the Requests tab to ask for movies and shows.">
            <Pill label="Go to Requests" onPress={() => app.navigate({ name: "requests" })} />
          </Setting>
        )}
      </Group>

      <Group title="About">
        <Setting title="Version" note={__APP_VERSION__} />
        <Setting title="Reely" note="Your Plex library, live TV and requests, on your TV." />
      </Group>
    </div>
  );
}
