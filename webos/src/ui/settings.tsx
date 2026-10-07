import type { ComponentChildren } from "preact";
import type { App, AppState, GuideStatus, PlaybackMode } from "../app/store";
import * as xtream from "../api/xtream";
import { useEffect, useState } from "preact/hooks";
import { ACCENTS, BITRATE_CHOICES, HOME_ROWS, SCREENSAVER_CHOICES, SUBTITLE_SIZES, THEME_LEVELS, UP_NEXT_CHOICES } from "../app/store";
import { clearProblem, lastProblem } from "../core/crash";
import { isVega } from "../core/platform";
import { focus, onKeys } from "./focus";
import { useRescue, useReturnFocus } from "./parts";

/*
 * Settings as the Fire TV lays them out: the sections down the left, each shown as the
 * cursor reaches it, and its settings on the right. A setting with several values opens
 * the list of them from the right, the one in use ticked; one that's on or off is a switch.
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

/** Whether the server is reached at home or over the internet, as the Fire TV says it. */
export function connectionKind(base: string | null): string {
  const host = base ? hostOf(base) : null;
  if (!host) return "—";
  return /^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))[.-]/.test(host.replace(/-/g, ".")) ? "Home network" : "Internet";
}

const bitrateLabel = (kbps: number) => (kbps <= 0 ? "Original" : `Up to ${kbps / 1000} Mbps`);
const bitrateNote = (kbps: number) =>
  kbps === 0 ? "As good as the file itself." : kbps === 20_000 ? "4K" : kbps === 12_000 ? "1080p, very high" : kbps === 8_000 ? "1080p" : kbps === 4_000 ? "720p" : "For a slow connection";

type SectionId = "playback" | "home" | "theme" | "live" | "requests" | "plex" | "about";

const SECTIONS: Array<[SectionId, string]> = [
  ["playback", "Playback"],
  ["home", "Home"],
  ["theme", "Theme"],
  ["live", "Live TV"],
  ["requests", "Requests"],
  ["plex", "Plex"],
  ["about", "About"],
];

/** One of the values a setting can take, with a line on what it does if it needs one. */
export interface Option<T> {
  value: T;
  label: string;
  note?: string | null;
}

export interface ChoiceRequest {
  title: string;
  options: Array<Option<unknown>>;
  selected: number;
  onPick: (index: number) => void;
}

/** What each section's settings are drawn with. */
interface Kit {
  app: App;
  state: AppState;
  choose: (request: ChoiceRequest) => void;
  onProfiles: () => void;
}

/** A titled block of settings, with an optional line of explanation under it. */
function Group(props: { title: string; note?: string | null; children: ComponentChildren }) {
  return (
    <section class="setting-group">
      <h2>{props.title}</h2>
      <div class="setting-box">{props.children}</div>
      {props.note ? <p class="setting-note">{props.note}</p> : null}
    </section>
  );
}

/**
 * One setting: its name, a line of explanation, and its value on the right. OK changes it.
 * Without onPress it only reports, and the cursor passes over it.
 */
function Setting(props: {
  title: string;
  note?: string | null;
  value?: string | null;
  /** On or off: drawn as a switch in place of the value. */
  on?: boolean;
  /** Opens a list of its values: a chevron says so. */
  opens?: boolean;
  onPress?: () => void;
}) {
  const body = (
    <>
      <span class="setting-text">
        <span class="setting-title">{props.title}</span>
        {props.note ? <span class="setting-sub">{props.note}</span> : null}
      </span>
      {props.on !== undefined ? (
        <span class={"switch" + (props.on ? " on" : "")}><span class="knob" /></span>
      ) : props.value ? (
        <span class="setting-value">{props.value}</span>
      ) : null}
      {props.opens ? <span class="chevron">›</span> : null}
    </>
  );
  return props.onPress ? (
    <button class="setting" data-focus onClick={props.onPress}>{body}</button>
  ) : (
    <div class="setting">{body}</div>
  );
}

/** A setting with several values: OK opens the whole list, the one in use ticked and under the cursor. */
function Choice<T>(props: { kit: Kit; title: string; note?: string | null; options: Array<Option<T>>; selected: T; onSelect: (value: T) => void }) {
  const at = props.options.findIndex((o) => o.value === props.selected);
  const current = at >= 0 ? props.options[at] : null;
  return (
    <Setting
      title={props.title}
      note={props.note ?? current?.note}
      value={current?.label}
      opens
      onPress={() =>
        props.kit.choose({
          title: props.title,
          options: props.options as Array<Option<unknown>>,
          selected: Math.max(0, at),
          onPick: (i) => {
            if (i !== at) props.onSelect(props.options[i].value);
          },
        })
      }
    />
  );
}

/** The list itself, from the right like every menu here; Back leaves the setting as it was. */
export function ChoicePanel(props: { request: ChoiceRequest; onClose: () => void }) {
  const { request } = props;
  useEffect(() => onKeys((a) => { if (a === "back") { props.onClose(); return true; } return false; }), []);
  useRescue([]);
  useReturnFocus();
  return (
    <div class="layer menu-shade" data-layer>
      <div class="options choice-panel">
        <div class="player-title">{request.title}</div>
        <div class="menu-list">
          {request.options.map((o, i) => (
            <button
              key={o.label}
              class="option"
              data-focus
              data-autofocus={i === request.selected ? "" : undefined}
              onClick={() => { props.onClose(); request.onPick(i); }}
            >
              <span class="tick">{i === request.selected ? "✓" : ""}</span>
              <span class="option-text">
                {o.label}
                {o.note ? <span class="option-note">{o.note}</span> : null}
              </span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

export function Settings(props: { app: App; state: AppState; onProfiles: () => void }) {
  const [section, setSection] = useState<SectionId>("playback");
  const [choosing, setChoosing] = useState<ChoiceRequest | null>(null);
  useRescue([]);
  const kit: Kit = { app: props.app, state: props.state, choose: setChoosing, onProfiles: props.onProfiles };

  // Right from the sections goes to the page's first setting; left or Back from a setting
  // goes back to its section, rather than to whichever section sits level with it.
  useEffect(
    () =>
      onKeys((a) => {
        const active = document.activeElement as HTMLElement | null;
        if (!active) return false;
        const page = document.querySelector<HTMLElement>(".settings-page");
        // Up and down the sections stay on them, rather than wandering into a setting
        // that happens to be nearer; up off the first goes on up to the tabs.
        if ((a === "up" || a === "down") && active.classList.contains("section-tab")) {
          const tabs = Array.from(document.querySelectorAll<HTMLElement>(".section-tab"));
          const next = tabs[tabs.indexOf(active) + (a === "down" ? 1 : -1)];
          if (next) focus(next);
          return Boolean(next) || a === "down";
        }
        if (a === "right" && active.classList.contains("section-tab")) {
          const first = page?.querySelector<HTMLElement>("[data-focus]");
          if (first) focus(first);
          return true;
        }
        if ((a === "left" || a === "back") && page?.contains(active)) {
          focus(document.querySelector<HTMLElement>(".section-tab.on"));
          return true;
        }
        return false;
      }),
    [],
  );

  return (
    <div class="settings">
      <nav class="settings-rail" data-column>
        <h1>Settings</h1>
        {SECTIONS.map(([id, label]) => (
          <button
            key={id}
            class={"section-tab" + (section === id ? " on" : "")}
            data-focus
            data-autofocus={section === id ? "" : undefined}
            // Moving down the sections shows each one, as a television's own settings do.
            onFocus={() => setSection(id)}
            onClick={() => focus(document.querySelector<HTMLElement>(".settings-page [data-focus]"))}
          >
            {label}
          </button>
        ))}
      </nav>
      <div class="settings-page" data-column key={section}>
        {section === "playback" ? <Playback kit={kit} />
          : section === "home" ? <HomeSection kit={kit} />
          : section === "theme" ? <ThemeSection kit={kit} />
          : section === "live" ? <LiveSection kit={kit} />
          : section === "requests" ? <RequestsSection kit={kit} />
          : section === "plex" ? <PlexSection kit={kit} />
          : <About kit={kit} />}
      </div>
      {choosing ? <ChoicePanel request={choosing} onClose={() => setChoosing(null)} /> : null}
    </div>
  );
}

function Playback({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const prefs = state.prefs;
  return (
    <>
      <Group title="Video">
        <Choice kit={kit} title="Playback mode" options={MODES.map(([value, label, note]) => ({ value, label, note }))} selected={prefs.playbackMode} onSelect={(m) => app.setPlaybackMode(m)} />
        <Choice
          kit={kit}
          title="Conversion quality"
          note="The highest quality Plex uses when it converts a video."
          options={BITRATE_CHOICES.map((kbps) => ({ value: kbps, label: bitrateLabel(kbps), note: bitrateNote(kbps) }))}
          selected={prefs.maxBitrateKbps}
          onSelect={(kbps) => app.setMaxBitrate(kbps)}
        />
      </Group>
      <Group title="Subtitles">
        <Choice
          kit={kit}
          title="At the start"
          note="Whether movies and episodes start with subtitles on."
          options={[
            { value: "plex" as const, label: "As Plex has them", note: "Your Plex account's subtitle settings, or what was last picked for the title." },
            { value: "off" as const, label: "Off", note: "Off until you turn them on. Forced subtitles, for parts in another language, still show." },
          ]}
          selected={prefs.subtitlesAtStart}
          onSelect={(v) => app.setSubtitlesAtStart(v)}
        />
        <Choice
          kit={kit}
          title="Size"
          options={SUBTITLE_SIZES.map((size) => ({ value: size, label: `${Math.round(size * 100)}%`, note: size === 0.9 ? "Standard" : null }))}
          selected={prefs.subtitleScale}
          onSelect={(size) => app.setSubtitleScale(size)}
        />
        <Setting title="Background" note="A dark box behind the text, instead of an outline." on={prefs.subtitleBackground} onPress={() => app.setSubtitleBackground(!prefs.subtitleBackground)} />
      </Group>
      <Group title="Episodes">
        <Setting title="Skip intros" note="Goes straight past an episode's intro when Plex has found it." on={prefs.skipIntros} onPress={() => app.setSkipIntros(!prefs.skipIntros)} />
        <Setting title="Skip credits" note="Goes straight to the next episode when Plex finds the credits." on={prefs.skipCredits} onPress={() => app.setSkipCredits(!prefs.skipCredits)} />
        <Choice
          kit={kit}
          title="Up Next"
          note="How long before the next episode starts by itself."
          options={UP_NEXT_CHOICES.map((n) => ({ value: n, label: n > 0 ? `${n} seconds` : "Off", note: n === 0 ? "Up Next waits for you to choose." : null }))}
          selected={prefs.upNextSeconds}
          onSelect={(n) => app.setUpNextSeconds(n)}
        />
      </Group>
      <Group title="Show pages">
        <Choice
          kit={kit}
          title="Theme music"
          note="Plays a show's theme song on its page."
          options={[{ value: -1, label: "Off" }, ...THEME_LEVELS.map(([label], i) => ({ value: i, label }))]}
          selected={prefs.themeLevel}
          onSelect={(level) => app.setThemeLevel(level)}
        />
      </Group>
    </>
  );
}

function HomeSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const prefs = state.prefs;
  // IPTV's rows only while its movies and shows are switched on.
  const rows = HOME_ROWS.filter(([id]) => prefs.iptvLibrary || (id !== "iptvMovies" && id !== "iptvShows"));
  return (
    <>
      <Group title="Rows on Home">
        {rows.map(([id, label]) => (
          <Setting key={id} title={label} on={!prefs.hiddenRows.includes(id)} onPress={() => app.toggleHomeRow(id)} />
        ))}
      </Group>
      <Group title="Screensaver">
        <Choice
          kit={kit}
          title="Screensaver"
          note="Your library's artwork and the time, when the remote's been put down."
          options={SCREENSAVER_CHOICES.map((m) => ({ value: m, label: m === 0 ? "Off" : `After ${m} minutes`, note: m === 0 ? "The TV's own screensaver comes on instead." : null }))}
          selected={prefs.screensaverMinutes}
          onSelect={(m) => app.setScreensaver(m)}
        />
      </Group>
    </>
  );
}

/** The color things are marked in: Reely's blue unless another is picked. */
function ThemeSection({ kit }: { kit: Kit }) {
  return (
    <Group title="Theme">
      <Choice
        kit={kit}
        title="Theme"
        note="The color of what's highlighted, progress bars, and the Reely mark."
        options={ACCENTS.map((a) => ({ value: a.id, label: a.label, note: a.id === "blue" ? "Reely's own" : null }))}
        selected={kit.state.prefs.accent}
        onSelect={(id) => kit.app.setAccent(id)}
      />
    </Group>
  );
}

/** How the playlist's guide stands, as the Fire TV says it. */
function guideValue(status: GuideStatus): string {
  switch (status.kind) {
    case "updating": return "Updating…";
    case "ready": return `Updated ${new Date(status.at * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}`;
    case "failed": return "Couldn't update";
    case "none": return "None";
    default: return "Not loaded";
  }
}

function LiveSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const { prefs, live } = state;
  const credentials = live.credentials;
  if (!credentials) {
    return (
      <Group title="Live TV">
        <Setting title="Not signed in" note="Sign in to your provider from the Live TV tab." value="Go to Live TV" onPress={() => app.navigate({ name: "live" })} />
      </Group>
    );
  }
  const playlist = Boolean(credentials.playlistUrl);
  return (
    <>
      <Group title="Channels and guide">
        <Setting title="Refresh channels" value={live.busy ? "Refreshing…" : `${live.categories.length} categories`} onPress={() => void app.refreshChannels()} />
        {xtream.guideNamed(credentials) === false ? (
          <Setting
            title="TV guide"
            value="None"
            note="Your playlist doesn't name one. To add one, sign out and sign in again with a TV guide address."
          />
        ) : (
          <Setting title="Refresh TV guide" value={playlist ? guideValue(live.guideStatus) : undefined} note={live.guideStatus.kind === "failed" ? live.guideStatus.message : "What's on, asked for again."} onPress={() => void app.refreshGuide()} />
        )}
      </Group>
      <Group title="Watching">
        {!playlist ? (
          <Choice
            kit={kit}
            title="Stream type"
            note="Try the other if channels stutter or won't start."
            options={[
              { value: "m3u8" as const, label: "HLS", note: "Works with most providers." },
              { value: "ts" as const, label: "MPEG-TS", note: "Starts faster with some providers." },
            ]}
            selected={prefs.streamFormat}
            onSelect={(f) => app.setStreamFormat(f)}
          />
        ) : null}
        <Setting title="Guide preview" note="Plays the highlighted channel in the guide." on={prefs.guidePreview} onPress={() => app.setGuidePreview(!prefs.guidePreview)} />
        {isVega() ? (
          <Choice
            kit={kit}
            title="Multiview layout"
            note="Hold OK on a channel to watch more than one at once."
            options={[
              { value: "grid" as const, label: "Grid", note: "Every channel gets an equal share of the screen." },
              { value: "focus" as const, label: "Focus", note: "The channel you're hearing gets most of the screen." },
            ]}
            selected={prefs.multiviewLayout}
            onSelect={(layout) => app.setMultiviewLayout(layout)}
          />
        ) : null}
      </Group>
      <Group
        title="Movies and shows"
        note={playlist
          ? "Your provider's movies and shows need an Xtream login rather than a playlist. Sign out and sign in with your server, username and password to use them."
          : state.iptv.error ?? "Your provider's movies and shows in the Movies and TV Shows tabs, on Home and in search, marked IPTV. Off, only Plex's are shown."}
      >
        {!playlist ? (
          <>
            <Setting title="Show IPTV movies and shows" on={prefs.iptvLibrary} onPress={() => app.setIptvLibrary(!prefs.iptvLibrary)} />
            {prefs.iptvLibrary ? (
              <>
                <Choice
                  kit={kit}
                  title="When a title is in both"
                  note="Which copy shows on Home and in search, and which the IPTV library leaves out."
                  options={[
                    { value: false, label: "Plex", note: "Plex's copy. The IPTV library only has what Plex doesn't." },
                    { value: true, label: "IPTV", note: "The provider's copy, in place of Plex's on Home and in search." },
                  ]}
                  selected={prefs.iptvWins}
                  onSelect={(v) => app.setIptvWins(v)}
                />
                <Setting
                  title="Refresh movies and shows"
                  value={state.iptv.loading ? "Refreshing…" : state.iptv.ready ? `${app.iptv.catalog.movies.length} movies · ${app.iptv.catalog.series.length} shows` : "Not loaded"}
                  onPress={() => void app.refreshIptv()}
                />
              </>
            ) : null}
          </>
        ) : (
          <Setting title="Show IPTV movies and shows" value="Needs an Xtream login" />
        )}
      </Group>
      <Group title="Provider">
        <Setting title={playlist ? "Playlist" : "Server"} value={hostOf(credentials.playlistUrl || credentials.base) ?? "—"} />
        {!playlist ? (
          <>
            <Setting
              title="Account"
              value={[live.account?.status ? live.account.status.charAt(0).toUpperCase() + live.account.status.slice(1) : null,
                live.account?.expiresAt ? `until ${new Date(Number(live.account.expiresAt) * 1000).toLocaleDateString()}` : null].filter(Boolean).join(" · ") || "—"}
            />
            <Setting
              title="Connections"
              value={`${live.account?.activeConnections ?? "?"} of ${live.account?.maxConnections ?? "?"} in use`}
              note="Each channel on screen uses one, including the guide preview."
            />
          </>
        ) : null}
        <Setting title="Sign out of live TV" onPress={() => app.signOutLive()} />
      </Group>
    </>
  );
}

/** Reely, where the Requests tab sends what's asked for: where it is, and the way out. */
function RequestsSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const address = state.requests.address;
  if (!address) {
    return (
      <Group title="Requests">
        <Setting title="Not connected" note="Connect to Reely from the Requests tab to ask for movies and shows." value="Go to Requests" onPress={() => app.navigate({ name: "requests" })} />
      </Group>
    );
  }
  return (
    <Group title="Reely" note="Requests go to Reely, signed in with your Plex account.">
      <Setting title="Server" value={hostOf(address) ?? address} />
      <Setting title="Disconnect" onPress={() => app.disconnectReely()} />
    </Group>
  );
}

function PlexSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const { plex, prefs } = state;
  const user = plex.user;
  const canSwitch = plex.homeUsers.length > 1;
  const iptvLibrary = prefs.iptvLibrary && !state.live.credentials?.playlistUrl;
  return (
    <>
      {user ? (
        <Group title="Profile" note={canSwitch ? "Each profile in your Plex Home has its own libraries, watch history and Continue Watching." : null}>
          <Setting
            title={user.title}
            value={canSwitch ? "Switch" : null}
            note={canSwitch ? "Plex Home member" : "Signed in to Plex"}
            onPress={canSwitch ? kit.onProfiles : undefined}
          />
        </Group>
      ) : null}
      <Group title="Server">
        <Setting title="Server" value={plex.serverName ?? "—"} />
        <Setting title="Connection" value={connectionKind(plex.baseUrl)} />
      </Group>
      {plex.libraries.length > 1 || iptvLibrary ? (
        <Group title="Libraries" note={"Switched on, a library is one of the only ones in the Movies and TV Shows menus and on Home. With none switched on, all of them are." + (iptvLibrary ? " IPTV is shown unless switched off here." : "")}>
          {plex.libraries.length > 1 ? plex.libraries.map((l) => (
            <Setting
              key={app.libraryId(l)}
              title={plex.servers.length > 1 ? `${l.section.title} · ${l.serverName}` : l.section.title}
              on={prefs.pinnedLibraries.includes(app.libraryId(l))}
              onPress={() => app.togglePinnedLibrary(l)}
            />
          )) : null}
          {iptvLibrary ? <Setting title="IPTV" note="In the Movies and TV Shows menus." on={prefs.iptvInMenus} onPress={() => app.setIptvInMenus(!prefs.iptvInMenus)} /> : null}
        </Group>
      ) : null}
      {plex.servers.length > 1 ? (
        <Group title="Servers" note="Which of your servers Reely uses first. Libraries from all of them are on Home.">
          {plex.servers.map((s) => (
            <Setting
              key={s.name}
              title={s.name}
              value={plex.serverName === s.name ? "In use" : "Switch"}
              onPress={() => { if (plex.serverName !== s.name) void app.chooseServer(s.name); }}
            />
          ))}
        </Group>
      ) : null}
      <Group title="Account">
        <Setting title="Sign out of Plex" onPress={() => app.signOut()} />
      </Group>
    </>
  );
}

const LICENSES: Array<[string, string, string]> = [
  ["Preact", "MIT", "Draws the screens."],
  ["qrcode-generator", "MIT", "The codes for signing in from a phone."],
  ["Geist", "SIL Open Font License", "The typeface."],
];

function About({ kit }: { kit: Kit }) {
  const { app } = kit;
  const [problem, setProblem] = useState(() => lastProblem(app.store));
  const [reading, setReading] = useState(false);
  return (
    <>
      <div class="about-mark">
        <div class="wordmark">reely</div>
        <div class="facts">Your Plex library and live TV, in one place.</div>
        <div class="setting-sub">Version {__APP_VERSION__}</div>
      </div>
      <Group title="Help">
        <Setting title="Take the tour" note="How to get around with the remote." onPress={() => app.takeTour()} />
      </Group>
      <Group title="Licenses">
        {LICENSES.map(([title, kind, note]) => <Setting key={title} title={title} value={kind} note={note} />)}
      </Group>
      {problem ? (
        <Group title="Problem report" note="Kept on this TV only. Nothing is sent anywhere.">
          <Setting
            title="Something went wrong"
            note={`${new Date(problem.at).toLocaleString()}  ·  ${problem.message}`}
            value={reading ? "Hide" : "View"}
            onPress={() => setReading(!reading)}
          />
          {reading ? <pre class="problem">{problem.detail ?? problem.message}</pre> : null}
          <Setting title="Clear the report" onPress={() => { clearProblem(app.store); setProblem(null); setReading(false); }} />
        </Group>
      ) : null}
    </>
  );
}
