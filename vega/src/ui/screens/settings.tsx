import React, { useRef, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { App, AppState, GuideStatus, PlaybackMode } from '../../app/store';
import { ACCENTS, BITRATE_CHOICES, HOME_ROWS, SCREENSAVER_CHOICES, SUBTITLE_SIZES, THEME_LEVELS, UP_NEXT_CHOICES } from '../../app/store';
import { VERSION } from '../../app/version';
import * as xtream from '../../api/xtream';
import { clearProblem, lastProblem } from '../../core/crash';
import { ChoicePanel, type ChoiceRequest } from '../choice';
import { Chip, ErrorNote } from '../controls';
import { Press, focusWhenReady, useAccent } from '../focus';
import { useBack } from '../menus';
import { Text } from '../text';
import { color, dp, type } from '../theme';
import type { ScreenProps } from './home';

/*
 * Settings (SettingsScreen.kt): the sections down the left, each shown as the cursor
 * reaches it, and its settings on the right. A setting with several values opens the
 * list of them from the right, the one in use ticked; one that's on or off is a switch.
 */

type SectionId = 'playback' | 'home' | 'theme' | 'live' | 'requests' | 'plex' | 'about';
const SECTIONS: Array<[SectionId, string]> = [
  ['playback', 'Playback'], ['home', 'Home'], ['theme', 'Theme'], ['live', 'Live TV'], ['requests', 'Requests'], ['plex', 'Plex'], ['about', 'About'],
];

const MODES: Array<[PlaybackMode, string, string]> = [
  ['auto', 'Automatic', 'Plays the original file. Plex converts it only when needed.'],
  ['direct', 'Original only', 'Always plays the original file. Some may not play.'],
  ['transcode', 'Always convert', 'Plex converts everything. Uses more of your server.'],
];
const bitrateLabel = (kbps: number) => (kbps <= 0 ? 'Original' : `Up to ${kbps / 1000} Mbps`);
const bitrateNote = (kbps: number) =>
  kbps === 0 ? 'As good as the file itself.' : kbps === 20_000 ? '4K' : kbps === 12_000 ? '1080p, very high' : kbps === 8_000 ? '1080p' : kbps === 4_000 ? '720p' : 'For a slow connection';

/** Where from, without what a playlist's address may carry in it: its login. */
const hostOf = (address: string) => address.replace(/^[a-z]+:\/\//i, '').replace(/^[^@/]*@/, '').split(/[/?#]/)[0] || null;

/** Whether the server is reached at home or over the internet, which is what matters. */
export function connectionKind(base: string | null): string {
  const host = base ? hostOf(base)?.split(':')[0] : null;
  if (!host) return '—';
  return /^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))[.-]/.test(host.replace(/-/g, '.')) ? 'Home network' : 'Internet';
}

const clockOf = (at: number) => {
  const d = new Date(at);
  return `${d.getHours() % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${d.getHours() < 12 ? 'AM' : 'PM'}`;
};

function guideValue(status: GuideStatus): string {
  switch (status.kind) {
    case 'updating': return 'Updating…';
    case 'ready': return `Updated ${clockOf(status.at * 1000)}`;
    case 'failed': return "Couldn't update";
    case 'none': return 'None';
    default: return 'Not loaded';
  }
}

interface Option<T> { value: T; label: string; note?: string | null }

/** Tells the screen when the cursor is among the settings rather than the sections. */
const OptionsContext = React.createContext<() => void>(() => undefined);

/** What each section's settings are drawn with. */
interface Kit {
  app: App;
  state: AppState;
  choose: (request: ChoiceRequest) => void;
  /** The first setting on the page: where right from the sections lands. */
  firstRef: React.RefObject<View | null>;
}

/** A titled block of setting rows, with an optional line of explanation under it. */
function Group(props: { title: string; note?: string | null; children: React.ReactNode }) {
  return (
    <View style={{ gap: dp(6) }}>
      <Text style={[type.label, { color: color.faint, letterSpacing: dp(1.2), textTransform: 'uppercase', paddingLeft: dp(4), paddingBottom: dp(2) }]}>{props.title}</Text>
      <View style={{ borderRadius: dp(14), backgroundColor: color.surfaceRaised, padding: dp(4), gap: dp(2) }}>{props.children}</View>
      {props.note ? <Text style={[type.label, { color: color.faint, paddingLeft: dp(4) }]}>{props.note}</Text> : null}
    </View>
  );
}

/** On or off at a glance: a track with its knob at one end, the accent when on. */
function Switch(props: { on: boolean; focused: boolean }) {
  const accent = useAccent();
  const track = props.on ? accent.color : props.focused ? 'rgba(8, 9, 11, 0.22)' : 'rgba(242, 244, 247, 0.18)';
  return (
    <View style={{ width: dp(44), height: dp(26), borderRadius: dp(13), backgroundColor: track, padding: dp(3) }}>
      <View style={{ width: dp(20), height: dp(20), borderRadius: dp(10), marginLeft: props.on ? dp(18) : 0,
        backgroundColor: props.on || !props.focused ? color.chalk : 'rgba(8, 9, 11, 0.55)' }} />
    </View>
  );
}

/**
 * One setting: its name, a line of explanation, and its value on the right. OK changes it.
 * Without onPress it only reports, and the cursor passes over it.
 */
function Setting(props: {
  title: string; note?: string | null; value?: string | null; on?: boolean; opens?: boolean;
  onPress?: () => void; pressRef?: React.Ref<View>; focusKey?: string;
}) {
  const [focused, setFocused] = useState(false);
  const amongOptions = React.useContext(OptionsContext);
  const body = (f: boolean) => (
    <>
      <View style={{ flex: 1, gap: dp(2) }}>
        <Text numberOfLines={1} style={[type.meta, { color: f ? color.ink : color.chalk }]}>{props.title}</Text>
        {props.note ? <Text style={[type.label, { color: f ? 'rgba(8, 9, 11, 0.7)' : color.muted }]}>{props.note}</Text> : null}
      </View>
      {props.on !== undefined ? <Switch on={props.on} focused={f} />
        : props.value ? <Text numberOfLines={1} style={[type.meta, { color: f ? color.ink : color.muted, maxWidth: dp(300) }]}>{props.value}</Text> : null}
      {props.opens ? <Text style={{ color: f ? color.ink : color.faint, fontSize: dp(22), lineHeight: dp(22) }}>›</Text> : null}
    </>
  );
  const row = { flexDirection: 'row' as const, alignItems: 'center' as const, gap: dp(16), paddingHorizontal: dp(14), paddingVertical: dp(11), borderRadius: dp(10) };
  if (!props.onPress) return <View style={row}>{body(false)}</View>;
  return (
    <Press ref={props.pressRef} focusKey={props.focusKey} onPress={props.onPress} onFocusChange={(on) => { setFocused(on); if (on) amongOptions(); }}
      style={[row, { backgroundColor: focused ? color.chalk : 'transparent' }]}>
      {() => body(focused)}
    </Press>
  );
}

/** A setting with several values: OK opens the whole list, the one in use ticked and under the cursor. */
function Choice<T>(props: { kit: Kit; title: string; note?: string | null; options: Array<Option<T>>; selected: T; onSelect: (value: T) => void; first?: boolean }) {
  const row = useRef<View>(null);
  const at = props.options.findIndex((o) => o.value === props.selected);
  const current = at >= 0 ? props.options[at] : null;
  return (
    <Setting
      pressRef={(el) => {
        (row as React.MutableRefObject<View | null>).current = el;
        if (props.first) (props.kit.firstRef as React.MutableRefObject<View | null>).current = el;
      }}
      focusKey={`setting:${props.title}`}
      title={props.title} note={props.note ?? current?.note} value={current?.label} opens
      onPress={() => props.kit.choose({
        title: props.title,
        options: props.options.map((o) => ({ label: o.label, detail: o.note ?? null })),
        selected: Math.max(0, at),
        onPick: (i) => { if (i !== at) props.onSelect(props.options[i].value); },
        returnTo: row,
      })} />
  );
}

/** The first row of a page that isn't a Choice: where right from the sections lands. */
const firstRow = (kit: Kit) => (el: View | null) => { (kit.firstRef as React.MutableRefObject<View | null>).current = el; };

export function Settings(props: ScreenProps & { onProfiles: () => void }) {
  const { app, state } = props;
  const [section, setSection] = useState<SectionId>('playback');
  const [choosing, setChoosing] = useState<ChoiceRequest | null>(null);
  const [inOptions, setInOptions] = useState(false);
  const sectionRefs = useRef<Record<SectionId, React.RefObject<View | null>>>(
    Object.fromEntries(SECTIONS.map(([id]) => [id, React.createRef<View>()])) as Record<SectionId, React.RefObject<View | null>>,
  ).current;
  const firstRef = useRef<View>(null);
  const kit: Kit = { app, state, choose: setChoosing, firstRef };

  // Back from the settings goes back to the section they belong to.
  useBack(() => {
    focusWhenReady(sectionRefs[section]);
    setInOptions(false);
    return true;
  }, inOptions && !choosing);
  // Right from the sections: the page's first setting, rather than whichever sits level.
  const onSections = useRef(false);
  useTVEventHandler((event) => {
    if (event.eventKeyAction !== 0 || choosing) return;
    if (event.eventType === 'right' && onSections.current) focusWhenReady(firstRef);
  });

  return (
    <View style={{ flex: 1, flexDirection: 'row', paddingHorizontal: dp(36), paddingVertical: dp(14) }}>
      {/* Left from the settings comes back to the section showing. */}
      <TVFocusGuideView destinations={sectionRefs[section].current ? [sectionRefs[section].current] : []}
        style={{ width: dp(180), gap: dp(8) }}>
        <Text style={[type.headline, { paddingBottom: dp(8) }]}>Settings</Text>
        {SECTIONS.map(([id, label]) => (
          <Chip key={id} pressRef={sectionRefs[id]} focusKey={`section:${id}`} label={label} selected={section === id} first={id === 'playback'}
            onFocus={() => { setSection(id); setInOptions(false); onSections.current = true; }}
            onPress={() => focusWhenReady(firstRef)} style={{ alignSelf: 'stretch' }} />
        ))}
      </TVFocusGuideView>
      <View style={{ flex: 1, paddingLeft: dp(26) }}>
        <ScrollView key={section} showsVerticalScrollIndicator={false} contentContainerStyle={{ gap: dp(18), paddingBottom: dp(120) }}>
          <OptionsFocus onFocus={() => { setInOptions(true); onSections.current = false; }}>
            {section === 'playback' ? <Playback kit={kit} />
              : section === 'home' ? <HomeSection kit={kit} />
              : section === 'theme' ? <ThemeSection kit={kit} />
              : section === 'live' ? <LiveSection kit={kit} />
              : section === 'requests' ? <RequestsSection kit={kit} />
              : section === 'plex' ? <PlexSection kit={kit} onProfiles={props.onProfiles} />
              : <About kit={kit} />}
          </OptionsFocus>
        </ScrollView>
      </View>
      {choosing ? <ChoicePanel request={choosing} onClose={() => setChoosing(null)} /> : null}
    </View>
  );
}

function OptionsFocus(props: { onFocus: () => void; children: React.ReactNode }) {
  return <OptionsContext.Provider value={props.onFocus}><View style={{ gap: dp(18) }}>{props.children}</View></OptionsContext.Provider>;
}

function Playback({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const prefs = state.prefs;
  return (
    <>
      <Group title="Video">
        <Choice kit={kit} first title="Playback mode" options={MODES.map(([value, label, note]) => ({ value, label, note }))} selected={prefs.playbackMode} onSelect={(m) => app.setPlaybackMode(m)} />
        <Choice kit={kit} title="Conversion quality" note="The highest quality Plex uses when it converts a video."
          options={BITRATE_CHOICES.map((kbps) => ({ value: kbps, label: bitrateLabel(kbps), note: bitrateNote(kbps) }))}
          selected={prefs.maxBitrateKbps} onSelect={(kbps) => app.setMaxBitrate(kbps)} />
      </Group>
      <Group title="Subtitles">
        <Choice kit={kit} title="At the start" note="Whether movies and episodes start with subtitles on."
          options={[
            { value: 'plex' as const, label: 'As Plex has them', note: "Your Plex account's subtitle settings, or what was last picked for the title." },
            { value: 'off' as const, label: 'Off', note: 'Off until you turn them on. Forced subtitles, for parts in another language, still show.' },
          ]}
          selected={prefs.subtitlesAtStart} onSelect={(v) => app.setSubtitlesAtStart(v)} />
        <Choice kit={kit} title="Size" options={SUBTITLE_SIZES.map((size) => ({ value: size, label: `${Math.round(size * 100)}%`, note: size === 0.9 ? 'Standard' : null }))}
          selected={prefs.subtitleScale} onSelect={(size) => app.setSubtitleScale(size)} />
        <Setting title="Background" note="A dark box behind the text, instead of an outline." on={prefs.subtitleBackground} onPress={() => app.setSubtitleBackground(!prefs.subtitleBackground)} />
      </Group>
      <Group title="Episodes">
        <Setting title="Skip intros" note="Goes straight past an episode's intro when Plex has found it." on={prefs.skipIntros} onPress={() => app.setSkipIntros(!prefs.skipIntros)} />
        <Setting title="Skip credits" note="Goes straight to the next episode when Plex finds the credits." on={prefs.skipCredits} onPress={() => app.setSkipCredits(!prefs.skipCredits)} />
        <Choice kit={kit} title="Up Next" note="How long before the next episode starts by itself."
          options={UP_NEXT_CHOICES.map((n) => ({ value: n, label: n > 0 ? `${n} seconds` : 'Off', note: n === 0 ? 'Up Next waits for you to choose.' : null }))}
          selected={prefs.upNextSeconds} onSelect={(n) => app.setUpNextSeconds(n)} />
      </Group>
      <Group title="Show pages">
        <Choice kit={kit} title="Theme music" note="Plays a show's theme song on its page."
          options={[{ value: -1, label: 'Off' }, ...THEME_LEVELS.map(([label], i) => ({ value: i, label }))]}
          selected={prefs.themeLevel} onSelect={(level) => app.setThemeLevel(level)} />
      </Group>
    </>
  );
}

function HomeSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const prefs = state.prefs;
  // IPTV's rows only while its movies and shows are switched on.
  const rows = HOME_ROWS.filter(([id]) => prefs.iptvLibrary || (id !== 'iptvMovies' && id !== 'iptvShows'));
  return (
    <>
      <Group title="Rows on Home">
        {rows.map(([id, label], i) => (
          <Setting key={id} pressRef={i === 0 ? firstRow(kit) : undefined} focusKey={`row:${id}`} title={label} on={!prefs.hiddenRows.includes(id)} onPress={() => app.toggleHomeRow(id)} />
        ))}
      </Group>
      <Group title="Screensaver">
        <Choice kit={kit} title="Screensaver" note="Your library's artwork and the time, when the remote's been put down."
          options={SCREENSAVER_CHOICES.map((m) => ({ value: m, label: m === 0 ? 'Off' : `After ${m} minutes`, note: m === 0 ? "The TV's own screensaver comes on instead." : null }))}
          selected={prefs.screensaverMinutes} onSelect={(m) => app.setScreensaver(m)} />
      </Group>
    </>
  );
}

/** The color things are marked in: Reely's blue unless another is picked. */
function ThemeSection({ kit }: { kit: Kit }) {
  return (
    <Group title="Theme">
      <Choice kit={kit} first title="Theme" note="The color of what's highlighted, progress bars, and the Reely mark."
        options={ACCENTS.map((a) => ({ value: a.id, label: a.label, note: a.id === 'blue' ? "Reely's own" : null }))}
        selected={kit.state.prefs.accent} onSelect={(id) => kit.app.setAccent(id)} />
    </Group>
  );
}

function LiveSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const { prefs, live } = state;
  const credentials = live.credentials;
  if (!credentials) {
    return (
      <Group title="Live TV">
        <Setting pressRef={firstRow(kit)} title="Not signed in" note="Sign in to your provider from the Live TV tab." value="Go to Live TV" onPress={() => app.navigate({ name: 'live' })} />
      </Group>
    );
  }
  const playlist = xtream.isPlaylist(credentials);
  return (
    <>
      {live.error ? <ErrorNote message={live.error} /> : null}
      <Group title="Channels and guide">
        <Setting pressRef={firstRow(kit)} title="Refresh channels" value={live.busy ? 'Refreshing…' : `${live.categories.length} categories`} onPress={() => void app.refreshChannels()} />
        {xtream.guideNamed(credentials) === false ? (
          <Setting title="TV guide" value="None" note="Your playlist doesn't name one. To add one, sign out and sign in again with a TV guide address." />
        ) : (
          <Setting title="Refresh TV guide" value={guideValue(live.guideStatus)} note={live.guideStatus.kind === 'failed' ? live.guideStatus.message : null}
            onPress={() => void app.refreshGuide()} />
        )}
      </Group>
      <Group title="Watching">
        {!playlist ? (
          <Choice kit={kit} title="Stream type" note="Try the other if channels stutter or won't start."
            options={[
              { value: 'm3u8' as const, label: 'HLS', note: 'Works with most providers.' },
              { value: 'ts' as const, label: 'MPEG-TS', note: 'Starts faster with some providers.' },
            ]}
            selected={prefs.streamFormat} onSelect={(f) => app.setStreamFormat(f)} />
        ) : null}
        <Setting title="Guide preview" note="Plays the highlighted channel in the guide." on={prefs.guidePreview} onPress={() => app.setGuidePreview(!prefs.guidePreview)} />
        <Choice kit={kit} title="Multiview layout"
          options={[
            { value: 'grid' as const, label: 'Grid', note: 'Every channel gets an equal share of the screen.' },
            { value: 'focus' as const, label: 'Focus', note: "The channel you're hearing gets most of the screen." },
          ]}
          selected={prefs.multiviewLayout} onSelect={(layout) => app.setMultiviewLayout(layout)} />
      </Group>
      <Group title="Movies and shows"
        note={playlist
          ? "Your provider's movies and shows need an Xtream login rather than a playlist. Sign out and sign in with your server, username and password to use them."
          : state.iptv.error ?? "Your provider's movies and shows in the Movies and TV Shows tabs, on Home and in search, marked IPTV. Off, only Plex's are shown."}>
        {!playlist ? (
          <>
            <Setting title="Show IPTV movies and shows" on={prefs.iptvLibrary} onPress={() => app.setIptvLibrary(!prefs.iptvLibrary)} />
            {prefs.iptvLibrary ? (
              <>
                <Choice kit={kit} title="When a title is in both" note="Which copy shows on Home and in search, and which the IPTV library leaves out."
                  options={[
                    { value: false, label: 'Plex', note: "Plex's copy. The IPTV library only has what Plex doesn't." },
                    { value: true, label: 'IPTV', note: "The provider's copy, in place of Plex's on Home and in search." },
                  ]}
                  selected={prefs.iptvWins} onSelect={(v) => app.setIptvWins(v)} />
                <Setting title="Refresh movies and shows"
                  value={state.iptv.loading ? 'Refreshing…' : state.iptv.ready ? `${app.iptv.catalog.movies.length} movies · ${app.iptv.catalog.series.length} shows` : 'Not loaded'}
                  onPress={() => void app.refreshIptv()} />
              </>
            ) : null}
          </>
        ) : <Setting title="Show IPTV movies and shows" value="Needs an Xtream login" />}
      </Group>
      <Group title="Provider">
        <Setting title={playlist ? 'Playlist' : 'Server'} value={hostOf(credentials.playlistUrl || credentials.base) ?? '—'} />
        {!playlist ? (
          <>
            <Setting title="Account"
              value={[live.account?.status ? live.account.status.charAt(0).toUpperCase() + live.account.status.slice(1) : null,
                live.account?.expiresAt ? `until ${new Date(Number(live.account.expiresAt) * 1000).toDateString().slice(4)}` : null].filter(Boolean).join(' · ') || '—'} />
            <Setting title="Connections" value={`${live.account?.activeConnections ?? '?'} of ${live.account?.maxConnections ?? '?'} in use`}
              note="Each channel on screen uses one, including the guide preview." />
          </>
        ) : null}
        <Setting title="Sign out of live TV" onPress={() => app.signOutLive()} />
      </Group>
    </>
  );
}

/** Reely, where the Request tab sends what's asked for: where it is, and the way out. */
function RequestsSection({ kit }: { kit: Kit }) {
  const { app, state } = kit;
  const address = state.requests.address;
  if (!address) {
    return (
      <Group title="Requests">
        <Setting pressRef={firstRow(kit)} title="Not connected" note="Connect to Reely from the Request tab to ask for movies and shows." value="Go to Request" onPress={() => app.navigate({ name: 'requests' })} />
      </Group>
    );
  }
  return (
    <Group title="Reely" note="Requests go to Reely, signed in with your Plex account.">
      <Setting title="Server" value={hostOf(address) ?? address} />
      <Setting pressRef={firstRow(kit)} title="Disconnect" onPress={() => app.disconnectReely()} />
    </Group>
  );
}

function PlexSection({ kit, onProfiles }: { kit: Kit; onProfiles: () => void }) {
  const { app, state } = kit;
  const { plex, prefs } = state;
  const user = plex.user;
  const canSwitch = plex.homeUsers.length > 1;
  const iptvLibrary = prefs.iptvLibrary && !(state.live.credentials && xtream.isPlaylist(state.live.credentials));
  let first = true;
  const firstOne = () => { const was = first; first = false; return was ? firstRow(kit) : undefined; };
  if (!plex.token) {
    return (
      <Group title="Plex">
        <Setting pressRef={firstRow(kit)} title="Not signed in" note="Sign in from the Home tab." value="Go to Home" onPress={() => app.navigate({ name: 'home' })} />
      </Group>
    );
  }
  return (
    <>
      {plex.error ? <ErrorNote message={plex.error} /> : null}
      {user ? (
        <Group title="Profile" note={canSwitch ? 'Each profile in your Plex Home has its own libraries, watch history and Continue Watching.' : null}>
          <Setting pressRef={canSwitch ? firstOne() : undefined} title={user.title} value={canSwitch ? 'Switch' : null}
            note={canSwitch ? 'Plex Home member' : 'Signed in to Plex'} onPress={canSwitch ? onProfiles : undefined} />
        </Group>
      ) : null}
      <Group title="Server">
        <Setting title="Server" value={plex.serverName ?? '—'} />
        <Setting title="Connection" value={connectionKind(plex.baseUrl)} />
      </Group>
      {plex.libraries.length > 1 || iptvLibrary ? (
        <Group title="Libraries" note={'Pinned libraries are the only ones shown in the Movies and TV Shows menus. With none pinned, all of them are.' + (iptvLibrary ? ' IPTV is shown unless switched off here.' : '')}>
          {plex.libraries.length > 1 ? plex.libraries.map((l) => (
            <Setting key={app.libraryId(l)} pressRef={firstOne()} title={plex.servers.length > 1 ? `${l.section.title} · ${l.serverName}` : l.section.title}
              on={prefs.pinnedLibraries.includes(app.libraryId(l))} onPress={() => app.togglePinnedLibrary(l)} />
          )) : null}
          {iptvLibrary ? <Setting pressRef={firstOne()} title="IPTV" note="In the Movies and TV Shows menus." on={prefs.iptvInMenus} onPress={() => app.setIptvInMenus(!prefs.iptvInMenus)} /> : null}
        </Group>
      ) : null}
      {plex.servers.length > 1 ? (
        <Group title="Servers" note="Which of your servers Reely uses first. Libraries from all of them are on Home.">
          {plex.servers.map((s) => (
            <Setting key={s.name} pressRef={firstOne()} title={s.name} value={plex.serverName === s.name ? 'In use' : 'Switch'}
              onPress={() => { if (plex.serverName !== s.name) void app.chooseServer(s.name); }} />
          ))}
        </Group>
      ) : null}
      <Group title="Account">
        <Setting pressRef={firstOne()} title="Sign out of Plex" onPress={() => app.signOut()} />
      </Group>
    </>
  );
}

const LICENSES: Array<[string, string, string]> = [
  ['React Native', 'MIT', 'Draws the screens.'],
  ['hls.js', 'Apache 2.0', "Plays live channels and Plex's conversions, in Amazon's build."],
  ['qrcode-generator', 'MIT', 'The codes for signing in from a phone.'],
  ['Geist', 'SIL Open Font License', 'The typeface.'],
];

function About({ kit }: { kit: Kit }) {
  const { app } = kit;
  const accent = useAccent();
  const [problem, setProblem] = useState(() => lastProblem(app.store));
  const [reading, setReading] = useState(false);
  return (
    <>
      <View style={{ paddingLeft: dp(4), paddingBottom: dp(10), gap: dp(4) }}>
        <Text style={{ color: accent.color, fontSize: dp(40), lineHeight: dp(44), fontWeight: '700' }}>reely</Text>
        <Text style={[type.meta, { color: color.chalk }]}>Your Plex library and live TV, in one place.</Text>
        <Text style={[type.label, { color: color.muted }]}>{`Version ${VERSION}`}</Text>
      </View>
      <Group title="Help">
        <Setting pressRef={firstRow(kit)} title="Take the tour" note="How to get around with the remote." onPress={() => app.takeTour()} />
      </Group>
      <Group title="Licenses">
        {LICENSES.map(([title, kind, note]) => <Setting key={title} title={title} value={kind} note={note} />)}
      </Group>
      {problem ? (
        <Group title="Problem report" note="Kept on this TV only. Nothing is sent anywhere.">
          <Setting title="Something went wrong" note={`${new Date(problem.at).toLocaleString()}  ·  ${problem.message}`} value={reading ? 'Hide' : 'View'} onPress={() => setReading(!reading)} />
          {reading ? <Text style={[type.label, { color: color.muted, padding: dp(14) }]}>{problem.detail ?? problem.message}</Text> : null}
          <Setting title="Clear the report" onPress={() => { clearProblem(app.store); setProblem(null); setReading(false); }} />
        </Group>
      ) : null}
    </>
  );
}
