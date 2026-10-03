import type { ComponentChildren } from "preact";
import type { App, AppState, PlaybackMode } from "../app/store";
import { useState } from "preact/hooks";
import { BITRATE_CHOICES, HOME_ROWS, SCREENSAVER_CHOICES, SUBTITLE_SIZES, UP_NEXT_CHOICES } from "../app/store";
import { clearProblem, lastProblem } from "../core/crash";
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
  const [problem, setProblem] = useState(() => lastProblem(app.store));
  const [reading, setReading] = useState(false);
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

      <Group title="Subtitles">
        <Setting title="Size" note={prefs.subtitleScale === 0.9 ? "Standard" : null}>
          {SUBTITLE_SIZES.map((size) => (
            <Pill key={size} label={`${Math.round(size * 100)}%`} on={prefs.subtitleScale === size} onPress={() => app.setSubtitleScale(size)} />
          ))}
        </Setting>
        <Setting title="Background" note="A dark box behind the text, instead of an outline.">
          <Pill label="On" on={prefs.subtitleBackground} onPress={() => app.setSubtitleBackground(true)} />
          <Pill label="Off" on={!prefs.subtitleBackground} onPress={() => app.setSubtitleBackground(false)} />
        </Setting>
      </Group>

      <Group title="Episodes">
        <Setting title="Skip intros" note="Goes straight past an episode's intro when Plex has found it.">
          <Pill label="On" on={prefs.skipIntros} onPress={() => app.setSkipIntros(true)} />
          <Pill label="Off" on={!prefs.skipIntros} onPress={() => app.setSkipIntros(false)} />
        </Setting>
        <Setting title="Skip credits" note="Goes straight to the next episode when Plex finds the credits.">
          <Pill label="On" on={prefs.skipCredits} onPress={() => app.setSkipCredits(true)} />
          <Pill label="Off" on={!prefs.skipCredits} onPress={() => app.setSkipCredits(false)} />
        </Setting>
        <Setting title="Up Next" note="How long before the next episode starts by itself.">
          {UP_NEXT_CHOICES.map((n) => (
            <Pill key={n} label={n === 0 ? "Don't start by itself" : `${n} seconds`} on={prefs.upNextSeconds === n} onPress={() => app.setUpNextSeconds(n)} />
          ))}
        </Setting>
      </Group>

      <Group title="Home">
        <Setting title="Rows on Home" note="Switch a row off to leave it out of Home.">
          {HOME_ROWS.map(([id, label]) => (
            <Pill key={id} label={label} on={!prefs.hiddenRows.includes(id)} onPress={() => app.toggleHomeRow(id)} />
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

      <Group title="Movies and shows">
        {live.credentials?.playlistUrl ? (
          <Setting title="Show IPTV movies and shows"
            note="Your provider's movies and shows need an Xtream login rather than a playlist. Sign out and sign in with your server, username and password to use them." />
        ) : (
          <>
            <Setting title="Show IPTV movies and shows"
              note={state.iptv.error ?? (state.iptv.loading ? "Loading your provider's movies and shows…"
                : "Your provider's movies and shows in the Movies and TV Shows tabs, on Home and in search, marked IPTV. Off, only Plex's are shown.")}>
              <Pill label="On" on={prefs.iptvLibrary} onPress={() => app.setIptvLibrary(true)} />
              <Pill label="Off" on={!prefs.iptvLibrary} onPress={() => app.setIptvLibrary(false)} />
            </Setting>
            {prefs.iptvLibrary ? (
              <Setting title="When a title is in both"
                note={prefs.iptvWins ? "The provider's copy, in place of Plex's on Home and in search." : "Plex's copy. The IPTV library only has what Plex doesn't."}>
                <Pill label="Plex" on={!prefs.iptvWins} onPress={() => app.setIptvWins(false)} />
                <Pill label="IPTV" on={prefs.iptvWins} onPress={() => app.setIptvWins(true)} />
              </Setting>
            ) : null}
          </>
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

      <Group title="Screensaver">
        <Setting title="Screensaver" note="Your library's artwork and the time, when the remote's been put down.">
          {SCREENSAVER_CHOICES.map((m) => (
            <Pill key={m} label={m === 0 ? "Off" : `After ${m} minutes`} on={prefs.screensaverMinutes === m} onPress={() => app.setScreensaver(m)} />
          ))}
        </Setting>
      </Group>

      <Group title="About">
        <Setting title="Version" note={__APP_VERSION__} />
        <Setting title="Reely" note="Your Plex library, live TV and requests, on your TV." />
        <Setting title="Take the tour" note="How to get around with the remote.">
          <Pill label="Take the tour" onPress={() => app.takeTour()} />
        </Setting>
      </Group>

      {problem ? (
        <Group title="Problem report">
          <Setting title="Something went wrong" note={`${new Date(problem.at).toLocaleString()}  ·  ${problem.message}`}>
            <Pill label={reading ? "Hide" : "View"} onPress={() => setReading(!reading)} />
            <Pill label="Clear" onPress={() => { clearProblem(app.store); setProblem(null); setReading(false); }} />
          </Setting>
          {reading ? <pre class="problem">{problem.detail ?? problem.message}</pre> : null}
          <p class="note">Kept on this TV only. Nothing is sent anywhere.</p>
        </Group>
      ) : null}
    </div>
  );
}
