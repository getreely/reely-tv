import * as plex from "../api/plex";
import type { PlexDetail, PlexHomeUser, PlexItem, PlexPerson, PlexServer } from "../api/plex";
import { rememberedSearches, split } from "../core/searchMatch";
import { stableSort } from "../core/sort";
import { isTextCodec } from "../core/subtitles";
import { readable } from "../core/http";
import { Store, clientId } from "../core/storage";
import { emptyHome, loadHome, recentEpisodeGroups, type EpisodeGroup, type HomeRows, type LibraryChoice } from "./home";
import { randomHex } from "../core/storage";
import * as reely from "../api/reely";
import * as xtream from "../api/xtream";
import * as vod from "../api/vod";
import { IptvLibrary, IptvWatch, IPTV_SOURCE, isIptv } from "../api/vod";
import type { Programme, XtreamAccount, XtreamCategory, XtreamChannel, XtreamCredentials } from "../api/xtream";
import type { RequestDetail, RequestPlaces, RequestRecord, RequestRow, RequestTitle, TitleMarks } from "../api/reely";

/*
 * The LG app's state and what can be done to it: the Fire TV's view model, for the
 * screens this app has. One object, replaced on every change, so a screen draws from a
 * single moment and never half of one change.
 */

export type Kind = "movie" | "show";

export type Route =
  | { name: "home" }
  | { name: "library"; kind: Kind }
  | { name: "detail"; ratingKey: string; serverBase: string | null; episodeKey?: string | null }
  | { name: "search" }
  | { name: "person"; person: PlexPerson }
  | { name: "collection"; item: PlexItem }
  | { name: "playlist"; item: PlexItem }
  | { name: "live" }
  | { name: "requests" }
  | { name: "requestTitle"; title: RequestTitle }
  | { name: "settings" };

export interface SignIn {
  code: string | null;
  url: string | null;
  busy: boolean;
  error: string | null;
}

export interface PlexSession {
  token: string | null;
  user: PlexHomeUser | null;
  homeUsers: PlexHomeUser[];
  servers: PlexServer[];
  serverName: string | null;
  baseUrl: string | null;
  serverToken: string | null;
  libraries: LibraryChoice[];
  /** The account's Watchlist, as Plex's own ids ("plex://movie/…"). */
  watchlist: Set<string>;
  /** Looking for the server: at home first, then the internet. */
  finding: boolean;
  error: string | null;
}

export interface Browse {
  choice: LibraryChoice | null;
  items: PlexItem[];
  total: number;
  busy: boolean;
  sort: string;
  error: string | null;
  unwatched: boolean;
  genre: plex.PlexGenre | null;
  decade: plex.PlexGenre | null;
  /** What this library can be narrowed to. */
  genres: plex.PlexGenre[];
  decades: plex.PlexGenre[];
  /** How many titles start with each letter, in the order shown: the A–Z jump. */
  letters: plex.PlexLetter[];
  /** The tab's own home of rows, everything, or the library's collections. */
  view: LibraryView;
  /** The library's newest releases, for the tab's home. */
  released: PlexItem[];
  /** Newest to this library, for the tab's home: films, or episodes gathered on their show. */
  added: PlexItem[];
  addedShows: EpisodeGroup[];
  /** The library's collections; null until they're in. */
  collections: PlexItem[] | null;
}

export type LibraryView = "home" | "grid" | "collections";

/** The narrowing asked of Plex, as query parameters. */
export const filtersOf = (b: Pick<Browse, "unwatched" | "genre" | "decade">) =>
  `${b.unwatched ? "&unwatched=1" : ""}${b.genre ? `&genre=${encodeURIComponent(b.genre.id)}` : ""}${b.decade ? `&decade=${encodeURIComponent(b.decade.id)}` : ""}`;

export interface DetailPage {
  key: string;
  serverBase: string | null;
  detail: PlexDetail | null;
  seasons: PlexItem[];
  season: PlexItem | null;
  episodes: PlexItem[];
  /** The episode the page is about: the one it was opened on, else the one you're up to. */
  focused: PlexItem | null;
  related: PlexItem[];
  trailers: plex.PlexExtra[];
  /** Which of several copies of the title plays. */
  versionIndex: number;
  busy: boolean;
  error: string | null;
}

/** Something playing: what, from where, and how. */
export interface Playing {
  item: PlexItem;
  base: string;
  token: string;
  playback: plex.PlexPlayback;
  /** Plex's address for the file itself, or its converted stream. */
  url: string;
  direct: boolean;
  startMs: number;
  sessionId: string;
  /** The rest of the season, for the next episode. */
  queue: PlexItem[];
  /** Which copy of the title. */
  mediaIndex: number;
  /** Text subtitles the app draws itself, with the size and background from Settings. */
  textSubtitle: plex.PlexSubtitle | null;
}

/** As the Fire TV offers them; 0.9 is its standard. */
export const SUBTITLE_SIZES = [0.7, 0.8, 0.9, 1.0, 1.2, 1.4];

export interface LiveState {
  credentials: XtreamCredentials | null;
  account: XtreamAccount | null;
  categories: XtreamCategory[];
  category: XtreamCategory | null;
  channels: XtreamChannel[];
  /** Now and next, by stream id, from the provider's short guide. */
  guide: Record<number, Programme[]>;
  favorites: number[];
  busy: boolean;
  error: string | null;
  /** The channel on screen, by its place in [channels]. */
  watching: number | null;
  /** Channels watched lately, newest first, by stream id. */
  recent: number[];
  /** A programme from the archive playing instead of the channel live. */
  catchUp: { programme: Programme; url: string } | null;
  /** The guide's listings, what's been as well as what's coming, by stream id. */
  table: Record<number, Programme[]>;
  /** A playlist's own guide: being read, read when, or what went wrong; "none" when it names none. */
  guideStatus: GuideStatus;
  reminders: Reminder[];
  /** A reminder whose programme is starting: up on screen until it's answered. */
  due: Reminder | null;
}

export type GuideStatus =
  | { kind: "idle" }
  | { kind: "updating" }
  | { kind: "ready"; at: number }
  | { kind: "none" }
  | { kind: "failed"; message: string };

/** "Starting now", asked for from the guide. */
export interface Reminder {
  streamId: number;
  channelName: string;
  title: string;
  /** Epoch seconds. */
  start: number;
}

/** Not one of the provider's: what was watched lately. */
export const RECENT: XtreamCategory = { id: "reely:recent", name: "Recently watched" };
const RECENT_KEPT = 20;

/** Not one of the provider's: the channels marked as favorites, from all of them. */
export const FAVORITES: XtreamCategory = { id: "reely:favorites", name: "Favorites" };

export interface RequestsState {
  address: string | null;
  connecting: boolean;
  loading: boolean;
  error: string | null;
  rows: RequestRow[];
  mine: RequestRecord[];
  marks: TitleMarks;
  plexMovies: Set<string>;
  plexShows: Set<string>;
  query: string;
  results: RequestTitle[];
  searching: boolean;
  /** Asked for, and now on the server: "Dune is ready to watch". */
  ready: RequestTitle[];
}

export interface RequestPage {
  title: RequestTitle;
  detail: RequestDetail | null;
  places: RequestPlaces | null;
  chosen: number[];
  libraryId: number | null;
  busy: boolean;
  sending: boolean;
  outcome: string | null;
  error: string | null;
}

export interface SearchState {
  query: string;
  busy: boolean;
  /** What matched by name, best first; else Plex's own guesses. */
  results: PlexItem[];
  /** Plex's other guesses, after the matches. */
  more: PlexItem[];
  people: PlexPerson[];
  collections: PlexItem[];
  channels: XtreamChannel[];
  recent: string[];
  /** No server answered. */
  unreachable: boolean;
}

/** A person's page, or a collection's: the titles in it. */
export interface ListPage {
  key: string;
  items: PlexItem[];
  busy: boolean;
  error: string | null;
}

export type PlaybackMode = "auto" | "direct" | "transcode";

export interface Prefs {
  /** Automatic: the file as it is when the TV can play it. */
  playbackMode: PlaybackMode;
  /** The most Plex sends when it converts; 0 for as good as the file. */
  maxBitrateKbps: number;
  /** Past the intro without asking. */
  skipIntros: boolean;
  /** Straight on to the next episode when the credits start, rather than Up Next. */
  skipCredits: boolean;
  /** Up Next's count before the next episode plays; 0 waits to be asked. */
  upNextSeconds: number;
  /** Home's rows switched off, by id. */
  hiddenRows: HomeRowId[];
  /** The IPTV provider's movies and shows in the tabs, on Home and in search. */
  iptvLibrary: boolean;
  /**
   * The libraries switched on in Settings, by libraryId, as on the Fire TV: the only ones in
   * the Movies and TV Shows menus, on Home, and searched. None switched on is all of them.
   */
  pinnedLibraries: string[];
  /** A title in both: the provider's copy shown rather than Plex's. */
  iptvWins: boolean;
  /** Minutes without a button before the screensaver; 0 for none. */
  screensaverMinutes: number;
  /** The remote's tour has been seen (or skipped). */
  tourSeen: boolean;
  subtitleScale: number;
  /** A dark box behind subtitles rather than an outline. */
  subtitleBackground: boolean;
  /** What a title starts with: the subtitles Plex has on, or none but forced ones. */
  subtitlesAtStart: SubtitlesAtStart;
  /** The colour things are marked in; see ACCENTS. */
  accent: string;
  /** The IPTV library offered in the Movies and TV Shows menus, beside Plex's. */
  iptvInMenus: boolean;
  /** A show's theme on its page: -1 off, else an index into THEME_LEVELS. */
  themeLevel: number;
  /** The highlighted channel plays in the guide. */
  guidePreview: boolean;
  /** How live channels are asked for: HLS, or a continuous MPEG-TS connection. */
  streamFormat: xtream.StreamFormat;
}

/** Theme music's volumes, as the Fire TV's. */
export const THEME_LEVELS: Array<[string, number]> = [["Quiet", 0.05], ["Medium", 0.1], ["Loud", 0.2]];

export const SCREENSAVER_CHOICES = [0, 3, 5, 10];

export interface IptvState {
  loading: boolean;
  error: string | null;
  /** The catalogue is in hand. */
  ready: boolean;
}

/** The provider's movies or shows, as one more library in a tab. */
export const iptvChoice = (kind: Kind): LibraryChoice => ({
  serverName: "IPTV", baseUrl: IPTV_SOURCE, token: "", section: { key: `iptv-${kind}`, title: "IPTV", type: kind },
});
export const isIptvChoice = (c: LibraryChoice | null) => c?.baseUrl === IPTV_SOURCE;
const IPTV_SORTS: Record<string, vod.Sort> = { "titleSort:asc": "TITLE", "addedAt:desc": "ADDED", "originallyAvailableAt:desc": "RELEASED", "rating:desc": "RATED" };

/** Home's rows, as Settings lists them. */
export type HomeRowId = "continueWatching" | "recentEpisodes" | "recentMovies" | "watchlist" | "playlists" | "iptvMovies" | "iptvShows";
export const HOME_ROWS: Array<[HomeRowId, string]> = [
  ["continueWatching", "Continue Watching"],
  ["recentEpisodes", "Recently Added Episodes"],
  ["recentMovies", "Recently Added Movies"],
  ["watchlist", "Watchlist"],
  ["playlists", "Playlists"],
  ["iptvMovies", "New Movies on IPTV"],
  ["iptvShows", "New Shows on IPTV"],
];

export type SubtitlesAtStart = "plex" | "off";

/**
 * The colours things can be marked in, as the Fire TV offers them: the electric blue
 * unless another is picked. Each comes with what's written on it, white or black.
 */
export const ACCENTS: Array<{ id: string; label: string; color: string; on: string }> = [
  { id: "blue", label: "Blue", color: "#2e6bff", on: "#ffffff" },
  { id: "red", label: "Red", color: "#ff5e69", on: "#08090b" },
  { id: "purple", label: "Purple", color: "#8b5cf6", on: "#ffffff" },
  { id: "pink", label: "Pink", color: "#ec4899", on: "#ffffff" },
  { id: "orange", label: "Orange", color: "#ff8a3d", on: "#08090b" },
  { id: "gold", label: "Gold", color: "#f5c542", on: "#08090b" },
  { id: "teal", label: "Teal", color: "#14b8a6", on: "#08090b" },
];

/** The colour for an id; the blue for anything unknown. */
export const accentOf = (id: string | undefined) => ACCENTS.find((a) => a.id === id) ?? ACCENTS[0];

export const UP_NEXT_CHOICES = [0, 5, 10, 12, 15, 20, 30];
const DEFAULT_PREFS: Prefs = { playbackMode: "auto", maxBitrateKbps: 0, skipIntros: false, skipCredits: false, upNextSeconds: 12, hiddenRows: [], iptvLibrary: false, pinnedLibraries: [], iptvWins: false, screensaverMinutes: 3, tourSeen: false, subtitleScale: 0.9, subtitleBackground: false, subtitlesAtStart: "plex", accent: "blue", iptvInMenus: true, themeLevel: -1, guidePreview: true, streamFormat: "m3u8" };

/** As the Fire TV offers them. */
export const BITRATE_CHOICES = [0, 20_000, 12_000, 8_000, 4_000, 2_000];

/**
 * The file's streams as a title starts: with subtitles set to start off, Plex's choice of
 * subtitles is passed over unless it's forced — the lines for parts in another language,
 * which are part of the film rather than a choice. Nothing is saved back to Plex.
 */
export function atStart<P extends Pick<plex.PlexPlayback, "subtitleStreams">>(playback: P, setting: SubtitlesAtStart): P {
  const on = playback.subtitleStreams.find((s) => s.selected);
  if (setting !== "off" || !on || on.forced) return playback;
  return { ...playback, subtitleStreams: playback.subtitleStreams.map((s) => ({ ...s, selected: false })) };
}

/** One step bigger or smaller among SUBTITLE_SIZES, from wherever it is. */
export function nextSubtitleSize(current: number, step: 1 | -1): number {
  const at = SUBTITLE_SIZES.indexOf(current);
  const from = at >= 0 ? at : SUBTITLE_SIZES.indexOf(DEFAULT_PREFS.subtitleScale);
  return SUBTITLE_SIZES[Math.max(0, Math.min(SUBTITLE_SIZES.length - 1, from + step))];
}

/**
 * The subtitles Plex has on: text ones the app can draw (their own file, SRT and the
 * like), or ones only Plex's conversion can put in the picture.
 */
export function subtitlePlan(playback: Pick<plex.PlexPlayback, "subtitleStreams" | "subtitles">): { text: plex.PlexSubtitle | null; burn: boolean } {
  const on = playback.subtitleStreams.find((s) => s.selected);
  if (!on) return { text: null, burn: false };
  const text = playback.subtitles.find((s) => s.id === on.id && isTextCodec(s.codec)) ?? null;
  return { text, burn: !text };
}

/** A bare item: the film a page is about, when there's nothing more to go on. */
function emptyItemFor(ratingKey: string, title: string, type: string): PlexItem {
  return {
    ratingKey, title, type, thumb: null, art: null, summary: null, year: null, index: null, parentIndex: null, parentRatingKey: null,
    parentTitle: null, grandparentRatingKey: null, grandparentTitle: null, grandparentThumb: null, durationMs: 0, viewOffsetMs: 0,
    leafCount: 0, viewedLeafCount: 0, viewCount: 0, addedAt: 0, lastViewedAt: 0, qualities: [], librarySectionId: null, serverBase: IPTV_SOURCE,
  };
}

const PEOPLE_RESULTS = 20;
const WATCHLIST_ROW = 40;
const CHANNEL_RESULTS = 30;

export interface AppState {
  route: Route;
  stack: Route[];
  signIn: SignIn;
  plex: PlexSession;
  home: HomeRows;
  homeBusy: boolean;
  homeError: string | null;
  browse: Record<Kind, Browse>;
  detail: DetailPage | null;
  askWho: boolean;
  playing: Playing | null;
  playError: string | null;
  requests: RequestsState;
  requestPage: RequestPage | null;
  live: LiveState;
  search: SearchState;
  list: ListPage | null;
  prefs: Prefs;
  iptv: IptvState;
}

const emptyBrowse = (): Browse => ({
  choice: null, items: [], total: 0, busy: false, sort: "titleSort:asc", error: null,
  unwatched: false, genre: null, decade: null, genres: [], decades: [], letters: [],
  view: "home", released: [], added: [], addedShows: [], collections: null,
});

export function initialState(): AppState {
  return {
    route: { name: "home" },
    stack: [{ name: "home" }],
    signIn: { code: null, url: null, busy: false, error: null },
    plex: { token: null, user: null, homeUsers: [], servers: [], serverName: null, baseUrl: null, serverToken: null, libraries: [], watchlist: new Set(), finding: false, error: null },
    home: emptyHome(),
    homeBusy: false,
    homeError: null,
    browse: { movie: emptyBrowse(), show: emptyBrowse() },
    detail: null,
    askWho: false,
    playing: null,
    playError: null,
    requests: emptyRequests(),
    requestPage: null,
    live: emptyLive(),
    search: emptySearch([]),
    list: null,
    prefs: DEFAULT_PREFS,
    iptv: { loading: false, error: null, ready: false },
  };
}

const emptySearch = (recent: string[]): SearchState => ({
  query: "", busy: false, results: [], more: [], people: [], collections: [], channels: [], recent, unreachable: false,
});

const emptyLive = (): LiveState => ({
  credentials: null, account: null, categories: [], category: null, channels: [], guide: {}, favorites: [], busy: false, error: null, watching: null,
  recent: [], catchUp: null, table: {}, guideStatus: { kind: "idle" }, reminders: [], due: null,
});

const emptyRequests = (): RequestsState => ({
  address: null, connecting: false, loading: false, error: null, rows: [], mine: [], marks: reely.noMarks(),
  plexMovies: new Set(), plexShows: new Set(), query: "", results: [], searching: false, ready: [],
});

/** A poster's word in Requests: In library, Downloading, Approved and the rest. */
export function requestBadge(r: RequestsState, title: RequestTitle) {
  return reely.badgeFor(title, r.marks, r.mine, r.plexMovies, r.plexShows);
}

/** The browsing rows less what's in the library already. */
export const shownRequestRows = (r: RequestsState) => reely.shownRows(r.rows, (t) => requestBadge(r, t));

export const isConnected = (s: AppState) => !!s.plex.baseUrl && !!s.plex.serverToken;

/** How long a sign-in code is asked about before it's given up as expired. */
const PIN_TRIES = 300;
const PIN_EVERY_MS = 2_000;
const GRID_PAGE = 120;
/** How long before a server that couldn't be reached is looked for again. */
export const SERVER_RETRY_MS = 30_000;

export class App {
  private current: AppState = initialState();
  private listeners = new Set<(s: AppState) => void>();
  private signInRun = 0;
  private reelyClient: reely.ReelyRequests | null = null;
  private searchRun = 0;
  private homeRun = 0;
  private queryRun = 0;
  private listRun = 0;
  /** Every live channel, fetched once, for search to match names against. */
  private allChannels: Promise<XtreamChannel[]> | null = null;
  /** The provider's movies and shows, matched with Plex, with where each was left. */
  readonly iptv = new IptvLibrary();
  /** Home's rows as Plex gave them, before the provider's are put in among them. */
  private plexHome: HomeRows = emptyHome();

  constructor(
    readonly store: Store = new Store(),
    private wait: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {
    plex.identity.clientId = clientId(store);
    this.iptv.watch = new IptvWatch(store, "iptvWatch");
  }

  get state() {
    return this.current;
  }

  subscribe(listener: (s: AppState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private set(change: (s: AppState) => AppState) {
    this.current = change(this.current);
    this.listeners.forEach((l) => l(this.current));
  }

  private setPlex(change: Partial<PlexSession>) {
    this.set((s) => ({ ...s, plex: { ...s.plex, ...change } }));
  }

  // ---------------------------------------------------------------- Getting about

  navigate(route: Route) {
    this.set((s) => {
      // A tab replaces the stack; anything else goes on top of it.
      const tab = route.name === "home" || route.name === "library" || route.name === "live" || route.name === "requests";
      return { ...s, route, stack: tab ? [route] : [...s.stack, route] };
    });
    if (route.name === "library") void this.openLibrary(route.kind);
    if (route.name === "detail") void this.openDetail(route.ratingKey, route.serverBase, route.episodeKey ?? null);
    if (route.name === "requests") void this.loadRequests();
    if (route.name === "live") void this.loadLive();
    if (route.name === "requestTitle") void this.openRequestTitle(route.title);
    if (route.name === "person") void this.openPerson(route.person);
    if (route.name === "collection") void this.openCollection(route.item);
    if (route.name === "playlist") void this.openPlaylist(route.item);
  }

  /** Back one page; false when there's nowhere back to go (Home's Back leaves the app). */
  goBack(): boolean {
    const s = this.current;
    // Live TV's own steps first: off the channel, then out of the category.
    if (s.live.watching != null) {
      this.stopLive();
      return true;
    }
    if (s.live.due) {
      this.dismissReminder();
      return true;
    }
    if (s.route.name === "live" && s.live.category) {
      this.closeCategory();
      return true;
    }
    if (s.stack.length > 1) {
      const stack = s.stack.slice(0, -1);
      this.set((x) => ({ ...x, stack, route: stack[stack.length - 1] }));
      return true;
    }
    if (s.route.name !== "home") {
      this.navigate({ name: "home" });
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- Starting up and signing in

  /** Back where it was left: the account kept, its server found again. */
  async start() {
    const recent = this.store.json<string[]>("recentSearches", []);
    const prefs = this.store.json<Partial<Prefs>>("prefs", {});
    this.set((s) => ({
      ...s,
      search: { ...s.search, recent },
      prefs: {
        playbackMode: prefs.playbackMode === "direct" || prefs.playbackMode === "transcode" ? prefs.playbackMode : "auto",
        maxBitrateKbps: BITRATE_CHOICES.includes(prefs.maxBitrateKbps ?? 0) ? prefs.maxBitrateKbps ?? 0 : 0,
        skipIntros: prefs.skipIntros === true,
        skipCredits: prefs.skipCredits === true,
        upNextSeconds: typeof prefs.upNextSeconds === "number" ? Math.max(0, Math.min(30, prefs.upNextSeconds)) : DEFAULT_PREFS.upNextSeconds,
        hiddenRows: Array.isArray(prefs.hiddenRows) ? prefs.hiddenRows.filter((r) => HOME_ROWS.some(([id]) => id === r)) : [],
        iptvLibrary: prefs.iptvLibrary === true,
        pinnedLibraries: Array.isArray(prefs.pinnedLibraries) ? prefs.pinnedLibraries.filter((id) => typeof id === "string") : [],
        iptvWins: prefs.iptvWins === true,
        screensaverMinutes: SCREENSAVER_CHOICES.includes(prefs.screensaverMinutes ?? -1) ? prefs.screensaverMinutes! : DEFAULT_PREFS.screensaverMinutes,
        tourSeen: prefs.tourSeen === true,
        subtitleScale: SUBTITLE_SIZES.includes(prefs.subtitleScale ?? -1) ? prefs.subtitleScale! : DEFAULT_PREFS.subtitleScale,
        subtitleBackground: prefs.subtitleBackground === true,
        subtitlesAtStart: prefs.subtitlesAtStart === "off" ? "off" : "plex",
        accent: accentOf(prefs.accent).id,
        iptvInMenus: prefs.iptvInMenus !== false,
        themeLevel: typeof prefs.themeLevel === "number" && prefs.themeLevel >= -1 && prefs.themeLevel < THEME_LEVELS.length ? prefs.themeLevel : -1,
        guidePreview: prefs.guidePreview !== false,
        streamFormat: prefs.streamFormat === "ts" ? "ts" : "m3u8",
      },
    }));
    const live = this.store.json<XtreamCredentials | null>("xtream", null);
    if (live) {
      this.setLive({
        credentials: live,
        favorites: this.store.json<number[]>("favorites", []),
        recent: this.store.json<number[]>("recentChannels", []),
        reminders: this.store.json<Reminder[]>("reminders", []).filter((r) => r.start * 1000 > Date.now() - 60 * 60_000),
      });
    }
    const reelyUrl = this.store.get("reelyUrl");
    if (reelyUrl) this.setRequests({ address: reelyUrl });
    const token = this.store.get("plexToken");
    if (!token) return;
    const user = this.store.json<PlexHomeUser | null>("plexUser", null);
    this.setPlex({ token, user });
    await this.connect(token);
  }

  /** A code to enter at plex.tv/link, and the same sign-in as a QR code's address. */
  async startSignIn() {
    const run = ++this.signInRun;
    this.set((s) => ({ ...s, signIn: { code: null, url: null, busy: true, error: null } }));
    let short: plex.PlexPin;
    let strong: plex.PlexPin | null = null;
    try {
      [short, strong] = await Promise.all([plex.createPin(false), plex.createPin(true).catch(() => null)]);
    } catch (error) {
      if (run === this.signInRun) this.set((s) => ({ ...s, signIn: { code: null, url: null, busy: false, error: readable(error) } }));
      return;
    }
    if (run !== this.signInRun) return;
    this.set((s) => ({ ...s, signIn: { code: short.code, url: strong ? plex.authUrl(strong.code) : null, busy: false, error: null } }));
    for (let i = 0; i < PIN_TRIES; i++) {
      await this.wait(PIN_EVERY_MS);
      if (run !== this.signInRun) return;
      for (const pin of strong ? [short, strong] : [short]) {
        const token = await plex.claimPin(pin.id);
        if (run !== this.signInRun) return;
        if (token) {
          this.set((s) => ({ ...s, signIn: { code: null, url: null, busy: false, error: null } }));
          await this.signedIn(token);
          return;
        }
      }
    }
    this.set((s) => ({ ...s, signIn: { code: null, url: null, busy: false, error: "That code has expired. Try signing in again." } }));
  }

  cancelSignIn() {
    this.signInRun++;
    this.set((s) => ({ ...s, signIn: { code: null, url: null, busy: false, error: null } }));
  }

  private async signedIn(token: string) {
    this.store.set("plexToken", token);
    this.store.set("plexAccountToken", token);
    const [user, homeUsers] = await Promise.all([plex.account(token), plex.homeUsers(token)]);
    this.store.setJson("plexUser", user);
    this.setPlex({ token, user, homeUsers });
    // A Plex Home of several people: "Who's watching?" once, straight after signing in.
    if (homeUsers.length > 1) this.set((s) => ({ ...s, askWho: true }));
    await this.connect(token);
  }

  signOut() {
    if (this.retry) clearTimeout(this.retry);
    this.retry = null;
    for (const key of ["plexToken", "plexAccountToken", "plexUser", "server"]) this.store.remove(key);
    const fresh = initialState();
    // What's kept on the TV rather than the account stays: recent searches and playback choices.
    this.set((s) => ({ ...fresh, search: emptySearch(s.search.recent), prefs: s.prefs, live: s.live, requests: s.requests }));
  }

  /** The servers, and the first that answers: the one last used, else the account's own. */
  async connect(token: string) {
    this.setPlex({ finding: true, error: null });
    let servers: PlexServer[];
    try {
      servers = await plex.servers(token);
    } catch (error) {
      this.setPlex({ finding: false, error: readable(error) });
      return;
    }
    if (this.current.plex.token !== token) return;
    const last = this.store.get("server");
    const ordered = [...servers.filter((s) => s.name === last), ...servers.filter((s) => s.name !== last)];
    const libraries: LibraryChoice[] = [];
    let chosen: { server: PlexServer; base: string } | null = null;
    // Every server's libraries: Home is the account's, not one machine's.
    const reached = await Promise.all(ordered.map(async (server) => ({ server, base: await plex.firstReachable(server) })));
    for (const { server, base } of reached) {
      if (!base) continue;
      chosen ??= { server, base };
      try {
        for (const section of await plex.sections(base, server.accessToken)) {
          if (section.type === "movie" || section.type === "show") libraries.push({ serverName: server.name, baseUrl: base, token: server.accessToken, section });
        }
      } catch {
        /* that server's libraries another time */
      }
    }
    if (this.current.plex.token !== token) return;
    if (!chosen) {
      this.setPlex({ servers, finding: false, error: servers.length
        ? `Found ${servers.map((s) => s.name).join(" and ")}, but couldn't reach ${servers.length > 1 ? "any of them" : "it"}. Make sure it's on. Reely keeps trying.`
        : "This Plex account has no Plex server of its own, and nobody has shared one with it yet." });
      // It keeps looking, every little while, as well as at Try again.
      this.lookAgainSoon(token);
      return;
    }
    this.store.set("server", chosen.server.name);
    this.setPlex({ servers, serverName: chosen.server.name, baseUrl: chosen.base, serverToken: chosen.server.accessToken, libraries, finding: false, error: null });
    await this.refreshHome();
    void this.loadIptv();
  }

  private retry: ReturnType<typeof setTimeout> | null = null;

  /** Looks for the server again in a while, if it's still not there. */
  private lookAgainSoon(token: string) {
    if (this.retry) clearTimeout(this.retry);
    this.retry = setTimeout(() => {
      this.retry = null;
      const p = this.current.plex;
      if (p.token === token && !p.finding && (!p.baseUrl || this.current.homeError)) void this.connect(token);
    }, SERVER_RETRY_MS);
  }

  // ---------------------------------------------------------------- Profiles

  askedWho() {
    this.set((s) => ({ ...s, askWho: false }));
  }

  async switchUser(user: PlexHomeUser, pin: string | null): Promise<string | null> {
    const account = this.store.get("plexAccountToken") ?? this.current.plex.token;
    if (!account) return "Sign in to Plex first.";
    try {
      const token = await plex.switchHomeUser(account, user.uuid, pin);
      this.store.set("plexToken", token);
      this.store.setJson("plexUser", user);
      this.set((s) => ({ ...s, home: emptyHome(), plex: { ...s.plex, token, user } }));
      await this.connect(token);
      return null;
    } catch (error) {
      return readable(error);
    }
  }

  // ---------------------------------------------------------------- Home

  async refreshHome() {
    const libraries = this.shownLibraries();
    if (!libraries.length) return;
    const run = ++this.homeRun;
    this.set((s) => ({ ...s, homeBusy: true, homeError: null }));
    const rows = await loadHome(libraries);
    // The one asked for last is the one that counts.
    if (run !== this.homeRun) return;
    if (!rows) {
      this.set((s) => ({ ...s, homeBusy: false, homeError: "Couldn't reach your Plex server. Trying again…" }));
      // Addresses change: a router hands the server a new one, the server moves house. Its
      // addresses are looked up afresh, and wherever it answers now is where it's used from.
      const token = this.current.plex.token;
      if (token) this.lookAgainSoon(token);
      return;
    }
    this.plexHome = rows;
    this.set((s) => ({ ...s, homeBusy: false, homeError: null }));
    this.composeHome();
    void this.refreshWatchlist();
    void this.checkReadyRequests();
  }

  /**
   * Whether anything asked for has arrived, as the Fire TV looks. The first look takes in
   * what was ready already without saying so: that isn't news.
   */
  async checkReadyRequests() {
    const client = this.client();
    if (!client) return;
    const mine = await client.myRequests().catch(() => null);
    if (!mine) return;
    const kept = this.store.json<string[] | null>("readySeen", null);
    const seen = new Set(kept ?? []);
    if (!mine.some((r) => r.status === "approved" && !seen.has(reely.requestKey(r.title)))) {
      if (kept == null) this.store.setJson("readySeen", []);
      this.setRequests({ mine });
      return;
    }
    const marks = await client.marks().catch(() => null);
    if (!marks) return;
    const arrived = reely.readyRequests(mine, marks, seen);
    if (kept == null) {
      this.store.setJson("readySeen", arrived.map(reely.requestKey));
      this.setRequests({ mine, marks });
      return;
    }
    this.setRequests({ mine, marks, ready: arrived });
  }

  /** Put away without watching: not said again. */
  dismissReady(title: RequestTitle) {
    const key = reely.requestKey(title);
    const seen = this.store.json<string[]>("readySeen", []);
    if (!seen.includes(key)) this.store.setJson("readySeen", [...seen, key]);
    this.setRequests({ ready: this.current.requests.ready.filter((t) => reely.requestKey(t) !== key) });
  }

  /** What arrived: its page on the server, found by name, kind and year; else Search with its name. */
  async openReady(title: RequestTitle) {
    this.dismissReady(title);
    const kind = title.kind === "show" ? "show" : "movie";
    const servers = this.shownLibraries()
      .map((l) => [l.baseUrl, l.token] as const)
      .filter(([base], i, all) => all.findIndex(([b]) => b === base) === i);
    for (const [base, token] of servers) {
      const found = (await plex.searchAll(base, token, title.title).catch(() => null))?.items.find(
        (i) => i.type === kind && i.title.toLowerCase() === title.title.toLowerCase() && (title.year == null || i.year == null || i.year === title.year),
      );
      if (found) {
        this.navigate({ name: "detail", ratingKey: found.ratingKey, serverBase: found.serverBase });
        return;
      }
    }
    // The box filled in before the screen opens on it.
    const searching = this.setQuery(title.title);
    this.navigate({ name: "search" });
    await searching;
  }

  /**
   * Home: Plex's rows, with the provider's put in among them while they're switched on —
   * its newest, and what was being watched from it in Continue Watching — and, where the
   * provider's copy wins, Plex's copy of the same title left out.
   */
  private composeHome() {
    const on = this.iptvOn();
    const wins = this.current.prefs.iptvWins;
    const rows = this.plexHome;
    const keep = (i: PlexItem) => !on || !this.iptv.hides(i, wins);
    const continuing = on && this.iptv.watch ? this.iptv.watch.continueWatching() : [];
    const continueWatching = stableSort([...rows.continueWatching.filter(keep), ...continuing], (a, b) => b.lastViewedAt - a.lastViewedAt).slice(0, 40);
    this.set((s) => ({
      ...s,
      home: {
        ...rows,
        continueWatching,
        recentMovies: rows.recentMovies.filter(keep),
        watchlist: s.home.watchlist,
        iptvMovies: on ? this.iptv.newest(true, wins) : [],
        iptvShows: on ? this.iptv.newest(false, wins) : [],
      },
    }));
  }

  /** The provider's movies and shows are switched on and there to show. */
  private iptvOn() {
    const c = this.current.live.credentials;
    return this.current.prefs.iptvLibrary && !!c && !xtream.isPlaylist(c) && this.current.iptv.ready;
  }

  /**
   * The provider's catalogue, once, and what Plex has, for matching the two: a title in
   * both is shown once, as the Fire TV shows it.
   */
  async loadIptv() {
    const c = this.current.live.credentials;
    if (!this.current.prefs.iptvLibrary || !c || xtream.isPlaylist(c) || this.current.iptv.loading) return;
    this.set((s) => ({ ...s, iptv: { ...s.iptv, loading: true, error: null } }));
    try {
      if (!this.current.iptv.ready) this.iptv.setCatalog(await vod.catalog(c));
      const libraries = this.shownLibraries();
      const entries = async (type: Kind) =>
        (await Promise.all(
          libraries.filter((l) => l.section.type === type).map((l) =>
            plex.libraryEntries(l.baseUrl, l.token, l.section.key, type === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW).catch(() => [] as plex.PlexIndexEntry[]),
          ),
        )).reduce<plex.PlexIndexEntry[]>((all, e) => all.concat(e), []);
      const [movies, shows] = await Promise.all([entries("movie"), entries("show")]);
      this.iptv.setPlex(movies, shows);
      if (this.current.live.credentials !== c) return;
      this.set((s) => ({ ...s, iptv: { loading: false, error: null, ready: true } }));
      this.composeHome();
    } catch (error) {
      this.set((s) => ({ ...s, iptv: { ...s.iptv, loading: false, error: readable(error) } }));
    }
  }

  setIptvLibrary(on: boolean) {
    this.setPrefs({ iptvLibrary: on });
    this.composeHome();
    if (on) void this.loadIptv();
    else this.leaveIptvTabs();
  }

  setIptvWins(wins: boolean) {
    this.setPrefs({ iptvWins: wins });
    this.composeHome();
    for (const kind of ["movie", "show"] as Kind[]) if (isIptvChoice(this.current.browse[kind].choice)) void this.loadMore(kind, true);
  }

  /** The tabs off the provider's library, once it's switched off. */
  private leaveIptvTabs() {
    for (const kind of ["movie", "show"] as Kind[]) {
      if (isIptvChoice(this.current.browse[kind].choice)) this.setBrowse(kind, { ...emptyBrowse() });
    }
  }

  /** Changes made here, so a list read before one isn't put over it. */
  private watchlistEdits = 0;

  /**
   * The account's Watchlist, and which of it the servers here have, in the Watchlist's
   * own order. Something no server has can't be opened or played, so it isn't shown.
   */
  async refreshWatchlist() {
    const token = this.current.plex.token;
    if (!token) return;
    const edits = this.watchlistEdits;
    let guids: string[];
    try {
      guids = await plex.watchlist(token);
    } catch {
      return;
    }
    if (this.current.plex.token !== token) return;
    if (this.watchlistEdits === edits) this.setPlex({ watchlist: new Set(guids) });
    const servers = this.shownLibraries()
      .map((l) => [l.baseUrl, l.token] as const)
      .filter(([base], i, all) => all.findIndex(([b]) => b === base) === i);
    const found: PlexItem[] = [];
    for (let i = 0; i < Math.min(guids.length, WATCHLIST_ROW); i += 8) {
      const batch = await Promise.all(
        guids.slice(i, Math.min(i + 8, WATCHLIST_ROW)).map(async (guid) => {
          for (const [base, serverToken] of servers) {
            const item = await plex.byGuid(base, serverToken, guid).catch(() => null);
            if (item) return item;
          }
          return null;
        }),
      );
      batch.forEach((item) => { if (item) found.push(item); });
    }
    if (this.current.plex.token !== token) return;
    this.set((s) => ({ ...s, home: { ...s.home, watchlist: found } }));
  }

  /** On the Watchlist, or off it, for the title whose page this is; the button flips at once. */
  async toggleWatchlist() {
    const guid = this.current.detail?.detail?.guid;
    const token = this.current.plex.token;
    if (!guid || !token) return;
    const on = !this.current.plex.watchlist.has(guid);
    const mark = (listed: boolean) => {
      const next = new Set(this.current.plex.watchlist);
      if (listed) next.add(guid); else next.delete(guid);
      this.setPlex({ watchlist: next });
    };
    this.watchlistEdits++;
    mark(on);
    try {
      await plex.setWatchlisted(token, guid, on);
      void this.refreshWatchlist();
    } catch (error) {
      mark(!on);
      const key = this.current.detail?.key;
      if (key) this.setDetail(key, { error: readable(error) });
    }
  }

  /** Watched, or not: the film, or the episode a show's page is on. Then the page and Home again. */
  async toggleWatched() {
    const page = this.current.detail;
    const d = page?.detail;
    if (!page || !d || !page.serverBase) return;
    if (page.serverBase === IPTV_SOURCE) {
      const target = plex.isShow(d) ? page.focused : null;
      const k = vod.parseKey(d.ratingKey);
      const title = k?.kind === "movie" ? this.iptv.movie(k.id) : null;
      const film = { ...(title ? vod.itemOf(title) : emptyItemFor(d.ratingKey, d.title, "movie")), durationMs: d.durationMs };
      const item = target ?? film;
      const watched = plex.isWatched(this.iptv.marked(item));
      this.iptv.watch?.setWatched([item], !watched);
      void this.openDetail(d.ratingKey, IPTV_SOURCE, target?.ratingKey ?? null);
      this.composeHome();
      return;
    }
    const token = this.tokenFor(page.serverBase);
    if (!token) return;
    const target = plex.isShow(d) ? page.focused : null;
    const key = target?.ratingKey ?? d.ratingKey;
    const watched = target ? plex.isWatched(target) : d.viewCount > 0;
    try {
      await plex.setWatched(page.serverBase, token, key, !watched);
    } catch (error) {
      this.setDetail(page.key, { error: readable(error) });
      return;
    }
    void this.refreshHome();
    if (this.current.detail?.key === page.key) {
      if (target) {
        const episodes = page.episodes.map((e) => (e.ratingKey === key ? { ...e, viewCount: watched ? 0 : 1, viewOffsetMs: 0 } : e));
        const focused = episodes.find((e) => e.ratingKey === key) ?? null;
        this.setDetail(page.key, { episodes, focused });
      } else {
        this.setDetail(page.key, { detail: { ...d, viewCount: watched ? 0 : 1, viewOffsetMs: 0 } });
      }
    }
  }

  chooseVersion(versionIndex: number) {
    const key = this.current.detail?.key;
    if (key) this.setDetail(key, { versionIndex });
  }

  // ---------------------------------------------------------------- Libraries

  /** A library, the same on every start: its server and its key there. */
  libraryId(l: LibraryChoice): string {
    return `${l.baseUrl}|${l.section.key}`;
  }

  /** The libraries switched on in Settings; all of them when none are, or none of those are found. */
  shownLibraries(): LibraryChoice[] {
    const all = this.current.plex.libraries;
    const pinned = this.current.prefs.pinnedLibraries;
    if (!pinned.length) return all;
    const shown = all.filter((l) => pinned.includes(this.libraryId(l)));
    return shown.length ? shown : all;
  }

  /** A library on or off: Home is put together again from those on, and a tab showing one switched off goes back to the first. */
  togglePinnedLibrary(l: LibraryChoice) {
    const id = this.libraryId(l);
    const pinned = this.current.prefs.pinnedLibraries;
    this.setPrefs({ pinnedLibraries: pinned.includes(id) ? pinned.filter((p) => p !== id) : [...pinned, id] });
    for (const kind of ["movie", "show"] as Kind[]) {
      const choice = this.current.browse[kind].choice;
      if (choice && !isIptvChoice(choice) && !this.librariesOf(kind).some((c) => c === choice)) this.setBrowse(kind, { ...emptyBrowse() });
    }
    void this.refreshHome();
  }

  librariesOf(kind: Kind): LibraryChoice[] {
    // The ones of this kind switched on; all of this kind when none of them are, as on the Fire TV.
    const all = this.current.plex.libraries.filter((l) => l.section.type === kind);
    const pinnedHere = all.filter((l) => this.current.prefs.pinnedLibraries.includes(this.libraryId(l)));
    const plexOnes = pinnedHere.length ? pinnedHere : all;
    // The provider's, after Plex's: one choice, the same object each time, so it reads as chosen.
    // Unless taken out of the menus in Settings.
    return this.iptvOn() && this.current.prefs.iptvInMenus ? [...plexOnes, this.iptvChoices[kind]] : plexOnes;
  }

  private readonly iptvChoices: Record<Kind, LibraryChoice> = { movie: iptvChoice("movie"), show: iptvChoice("show") };

  async openLibrary(kind: Kind, choice?: LibraryChoice) {
    const target = choice ?? this.current.browse[kind].choice ?? this.librariesOf(kind)[0] ?? null;
    if (!target) return;
    const same = this.current.browse[kind].choice === target && this.current.browse[kind].items.length > 0;
    if (same) return;
    const switching = this.current.browse[kind].choice !== target;
    this.set((s) => ({
      ...s,
      browse: {
        ...s.browse,
        [kind]: {
          ...s.browse[kind], choice: target, items: [], busy: true, error: null,
          // Another library's genres and decades aren't this one's.
          ...(switching ? { genre: null, decade: null, genres: [], decades: [], letters: [], released: [], added: [], addedShows: [], collections: null } : {}),
        },
      },
    }));
    const type = kind === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW;
    if (isIptvChoice(target)) {
      await this.loadMore(kind);
      return;
    }
    void Promise.all([
      plex.genres(target.baseUrl, target.token, target.section.key, type).catch(() => []),
      plex.decades(target.baseUrl, target.token, target.section.key, type).catch(() => []),
    ]).then(([genres, decades]) => {
      if (this.current.browse[kind].choice === target) this.setBrowse(kind, { genres, decades });
    });
    void this.loadLetters(kind);
    void this.loadTabHome(kind, target);
    await this.loadMore(kind);
  }

  /** The tab's own home: this library's newest releases, and its collections. */
  private async loadTabHome(kind: Kind, choice: LibraryChoice) {
    const type = kind === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW;
    // What's newly arrived is asked of this library itself: Home's row is the newest across
    // every library, which may have none of this one's.
    const [released, collections, added, addedShows] = await Promise.all([
      plex.items(choice.baseUrl, choice.token, `/library/sections/${choice.section.key}/all?type=${type}&sort=originallyAvailableAt:desc`, 40).catch(() => [] as PlexItem[]),
      plex.collections(choice.baseUrl, choice.token, choice.section.key).catch(() => [] as PlexItem[]),
      kind === "movie"
        ? plex.items(choice.baseUrl, choice.token, `/library/sections/${choice.section.key}/all?type=${type}&sort=addedAt:desc`, 40).catch(() => [] as PlexItem[])
        : Promise.resolve([] as PlexItem[]),
      kind === "show" ? recentEpisodeGroups([choice]).catch(() => [] as EpisodeGroup[]) : Promise.resolve([] as EpisodeGroup[]),
    ]);
    if (this.current.browse[kind].choice === choice) this.setBrowse(kind, { released, collections, added, addedShows });
  }

  setLibraryView(kind: Kind, view: LibraryView) {
    this.setBrowse(kind, { view });
  }

  private setBrowse(kind: Kind, change: Partial<Browse>) {
    this.set((s) => ({ ...s, browse: { ...s.browse, [kind]: { ...s.browse[kind], ...change } } }));
  }

  async setSort(kind: Kind, sort: string) {
    this.setBrowse(kind, { sort, items: [], busy: true });
    await this.loadMore(kind);
  }

  /** Unwatched only, a genre, a decade: the grid again from the top, narrowed. */
  async setFilter(kind: Kind, change: Partial<Pick<Browse, "unwatched" | "genre" | "decade">>) {
    this.setBrowse(kind, { ...change, items: [], total: 0, busy: true, error: null });
    void this.loadLetters(kind);
    await this.loadMore(kind);
  }

  private async loadLetters(kind: Kind) {
    const b = this.current.browse[kind];
    const choice = b.choice;
    if (!choice || isIptvChoice(choice)) return;
    const filters = filtersOf(b);
    const letters = await plex
      .firstCharacters(choice.baseUrl, choice.token, choice.section.key, kind === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW, filters)
      .catch(() => [] as plex.PlexLetter[]);
    const now = this.current.browse[kind];
    if (now.choice === choice && filtersOf(now) === filters) this.setBrowse(kind, { letters });
  }

  /**
   * Where [letter] starts in the A–Z grid, loading down to it first: its place, or -1.
   * Only for the A–Z order, which is the one the counts are in.
   */
  async jumpTo(kind: Kind, letter: string): Promise<number> {
    const b = this.current.browse[kind];
    const at = b.letters.findIndex((l) => l.letter === letter);
    if (at < 0 || b.sort !== "titleSort:asc") return -1;
    const offset = b.letters.slice(0, at).reduce((n, l) => n + l.count, 0);
    for (let guard = 0; guard < 100; guard++) {
      const now = this.current.browse[kind];
      if (now.items.length > offset) return offset;
      if (now.total <= now.items.length && !now.busy && now.items.length > 0) return Math.min(offset, now.items.length - 1);
      const before = now.items.length;
      await this.loadMore(kind);
      if (this.current.browse[kind].items.length === before) return -1;
    }
    return -1;
  }

  /** The next page of the grid, in before it's reached. */
  async loadMore(kind: Kind, restart = false) {
    const browse = this.current.browse[kind];
    const choice = browse.choice;
    if (!choice) return;
    if (isIptvChoice(choice)) {
      // The provider's library is all in hand: the whole grid at once, its categories as
      // the genres, the A–Z counts from it.
      if (!restart && browse.items.length && !browse.busy) return;
      const grid = this.iptv.browse(
        kind === "movie",
        { sort: IPTV_SORTS[browse.sort] ?? "TITLE", categoryId: browse.genre?.id ?? null, unwatchedOnly: browse.unwatched },
        this.current.prefs.iptvWins,
      );
      this.setBrowse(kind, {
        items: grid.items, total: grid.items.length, busy: false, error: null,
        genres: grid.categories.map((c) => ({ id: c.id, title: c.name })), decades: [], letters: grid.letters,
      });
      return;
    }
    const offset = browse.items.length;
    const type = kind === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW;
    const filters = filtersOf(browse);
    try {
      const page = await plex.items(choice.baseUrl, choice.token, `/library/sections/${choice.section.key}/all?type=${type}&sort=${browse.sort}${filters}`, GRID_PAGE, offset);
      const now = this.current.browse[kind];
      // Not over a different library, order or narrowing chosen meanwhile.
      if (now.choice !== choice || now.sort !== browse.sort || filtersOf(now) !== filters || now.items.length !== offset) return;
      const seen = new Set(now.items.map(plex.listKey));
      const items = [...now.items, ...page.filter((i) => !seen.has(plex.listKey(i)))];
      this.set((s) => ({ ...s, browse: { ...s.browse, [kind]: { ...now, items, busy: false, total: page.length < GRID_PAGE ? items.length : Math.max(now.total, items.length + 1) } } }));
    } catch (error) {
      this.set((s) => ({ ...s, browse: { ...s.browse, [kind]: { ...s.browse[kind], busy: false, error: readable(error) } } }));
    }
  }

  // ---------------------------------------------------------------- A title's page

  tokenFor(base: string | null): string | null {
    const p = this.current.plex;
    if (!base || base === p.baseUrl) return p.serverToken;
    return p.libraries.find((l) => l.baseUrl === base)?.token ?? null;
  }

  async openDetail(ratingKey: string, serverBase: string | null, episodeKey: string | null) {
    const base = serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    const key = `${base}|${ratingKey}`;
    this.set((s) => ({ ...s, detail: { key, serverBase: base, detail: null, seasons: [], season: null, episodes: [], focused: null, related: [], trailers: [], versionIndex: 0, busy: true, error: null } }));
    if (base === IPTV_SOURCE) {
      await this.openIptvDetail(key, ratingKey, episodeKey);
      return;
    }
    if (!base || !token) {
      this.setDetail(key, { busy: false, error: "Couldn't reach the server this title is on." });
      return;
    }
    try {
      const detail = await plex.detail(base, token, ratingKey);
      if (!detail) throw new Error("That title isn't on the server any more.");
      this.setDetail(key, { detail });
      plex.related(base, token, ratingKey).then((related) => this.setDetail(key, { related }));
      plex.trailers(base, token, ratingKey).then((trailers) => this.setDetail(key, { trailers }));
      if (plex.isShow(detail)) {
        const seasons = (await plex.children(base, token, ratingKey)).filter((i) => i.type === "season");
        // The season it's up to (or the episode it was opened on), else the first proper one.
        const upTo = seasons.find((s) => s.ratingKey === detail.onDeckSeasonKey);
        const first = seasons.find((s) => (s.index ?? 0) > 0) ?? seasons[0] ?? null;
        await this.loadSeason(key, base, token, upTo ?? first, episodeKey ?? detail.onDeckKey, seasons);
      } else {
        this.setDetail(key, { busy: false });
      }
    } catch (error) {
      this.setDetail(key, { busy: false, error: readable(error) });
    }
  }

  /** One of the provider's films or series: its page from the panel, its episodes marked from what's kept here. */
  private async openIptvDetail(key: string, ratingKey: string, episodeKey: string | null) {
    const c = this.current.live.credentials;
    const k = vod.parseKey(ratingKey);
    if (!c || !k || (k.kind !== "movie" && k.kind !== "show")) {
      this.setDetail(key, { busy: false, error: "Sign in to your IPTV provider in Live TV to watch this." });
      return;
    }
    const wins = this.current.prefs.iptvWins;
    try {
      if (k.kind === "movie") {
        const title = this.iptv.movie(k.id);
        const info = await vod.movieInfo(c, k.id);
        const item = this.iptv.marked(title ? vod.itemOf(title) : emptyItemFor(ratingKey, info?.name ?? "Film", "movie"));
        const detail = { ...vod.detailOf(ratingKey, title, info, false), durationMs: info?.durationMs || item.durationMs, viewOffsetMs: item.viewOffsetMs, viewCount: item.viewCount };
        this.setDetail(key, { detail, busy: false, related: this.iptv.related(item, wins) });
        return;
      }
      const title = this.iptv.series(k.id);
      const info = this.iptv.cachedSeries(k.id) ?? (await vod.seriesInfo(c, k.id));
      if (!info) throw new Error("Your provider doesn't have this series any more.");
      this.iptv.keepSeries(k.id, info);
      const detail = vod.detailOf(ratingKey, title, info, true);
      const seasons = vod.seasonItems(k.id, detail.title, detail.thumb, info).map(this.iptv.marked);
      const all = info.seasons.reduce<PlexItem[]>((list, s) => list.concat(vod.episodeItems(k.id, detail.title, detail.thumb, detail.art, s)), []).map(this.iptv.marked);
      // The season it's up to (or the episode it was opened on), else the first proper one.
      const target = all.find((e) => e.ratingKey === episodeKey) ?? plex.nextEpisode(all);
      const season = seasons.find((s) => s.ratingKey === target?.parentRatingKey) ?? seasons.find((s) => (s.index ?? 0) > 0) ?? seasons[0] ?? null;
      const episodes = season ? all.filter((e) => e.parentRatingKey === season.ratingKey) : [];
      const focused = episodes.find((e) => e.ratingKey === target?.ratingKey) ?? plex.nextEpisode(episodes);
      this.setDetail(key, { detail, seasons, season, episodes, focused, busy: false, related: this.iptv.related(title ? vod.itemOf(title) : emptyItemFor(ratingKey, detail.title, "show"), wins) });
    } catch (error) {
      this.setDetail(key, { busy: false, error: readable(error) });
    }
  }

  async selectSeason(season: PlexItem) {
    const page = this.current.detail;
    if (!page || !page.serverBase) return;
    if (page.serverBase === IPTV_SOURCE) {
      const k = vod.parseKey(season.ratingKey);
      const show = k?.kind === "season" ? this.iptv.cachedSeries(k.showId) : null;
      const raw = k?.kind === "season" ? show?.seasons.find((s) => s.number === k.number) : null;
      if (!k || k.kind !== "season" || !raw || !page.detail) return;
      const episodes = vod.episodeItems(k.showId, page.detail.title, page.detail.thumb, page.detail.art, raw).map(this.iptv.marked);
      this.setDetail(page.key, { season, episodes, focused: plex.nextEpisode(episodes) });
      return;
    }
    const token = this.tokenFor(page.serverBase);
    if (!token) return;
    await this.loadSeason(page.key, page.serverBase, token, season, page.detail?.onDeckKey ?? null, page.seasons);
  }

  private async loadSeason(key: string, base: string, token: string, season: PlexItem | null, focusKey: string | null, seasons: PlexItem[]) {
    this.setDetail(key, { seasons, season, episodes: [], focused: null, busy: true });
    if (!season) {
      this.setDetail(key, { busy: false });
      return;
    }
    const episodes = (await plex.children(base, token, season.ratingKey)).filter((e) => e.type === "episode");
    const focused = episodes.find((e) => e.ratingKey === focusKey) ?? plex.nextEpisode(episodes);
    this.setDetail(key, { episodes, focused, busy: false });
  }

  private setDetail(key: string, change: Partial<DetailPage>) {
    this.set((s) => (s.detail?.key === key ? { ...s, detail: { ...s.detail, ...change } } : s));
  }

  // ---------------------------------------------------------------- Playing

  /**
   * Plays [item] from where it was left, or the top. [direct] is asked of the TV's own
   * player: the file as it is when it can, else Plex's conversion.
   */
  async play(item: PlexItem, resume: boolean, direct: (p: plex.PlexPlayback) => boolean, queue: PlexItem[] = [], mediaIndex = 0) {
    const base = item.serverBase ?? this.current.plex.baseUrl;
    if (base === IPTV_SOURCE) {
      this.playIptv(item, resume, queue);
      return;
    }
    const token = this.tokenFor(base);
    if (!base || !token) {
      this.set((s) => ({ ...s, playError: "Couldn't reach the server this is on." }));
      return;
    }
    try {
      const found = await plex.playback(base, token, item.ratingKey, mediaIndex);
      if (!found) throw new Error("That file isn't on the server any more.");
      const playback = atStart(found, this.current.prefs.subtitlesAtStart);
      const sessionId = randomHex(12);
      const mode = this.current.prefs.playbackMode;
      // The subtitles Plex has on for this file: text ones the app draws over the file as it
      // is; picture ones (PGS) only Plex's conversion can put in.
      const { text, burn } = subtitlePlan(playback);
      const asIs = mode === "direct" ? true : mode === "transcode" || burn ? false : direct(playback);
      const url = asIs ? playback.url : this.converted(base, token, item.ratingKey, sessionId, mediaIndex, text ? "none" : "burn", playback.videoCodec);
      const startMs = resume && item.viewOffsetMs > 0 && !(item.durationMs > 0 && item.viewOffsetMs >= item.durationMs * 0.95) ? item.viewOffsetMs : 0;
      this.set((s) => ({ ...s, playError: null, playing: { item, base, token, playback, url, direct: asIs, startMs, sessionId, queue, mediaIndex, textSubtitle: text } }));
    } catch (error) {
      this.set((s) => ({ ...s, playError: readable(error) }));
    }
  }

  /** One of the provider's: the file from the panel, as it is; where it's left is kept on the TV. */
  private playIptv(item: PlexItem, resume: boolean, queue: PlexItem[]) {
    const c = this.current.live.credentials;
    const k = vod.parseKey(item.ratingKey);
    if (!c || !k || (k.kind !== "movie" && k.kind !== "episode")) {
      this.set((s) => ({ ...s, playError: "Sign in to your IPTV provider in Live TV to watch this." }));
      return;
    }
    const marked = this.iptv.marked(item);
    const url = k.kind === "movie" ? vod.movieUrl(c, k.id, k.extension) : vod.episodeUrl(c, k.id, k.extension);
    const startMs = resume && marked.viewOffsetMs > 0 ? marked.viewOffsetMs : 0;
    const playback: plex.PlexPlayback = {
      url, subtitles: [], markers: [], audioCodec: null, audioChannels: 0, previewUrl: null, chapters: [], partId: null,
      audioStreams: [], subtitleStreams: [], container: k.extension, videoCodec: null,
    };
    this.set((s) => ({ ...s, playError: null, playing: { item: marked, base: IPTV_SOURCE, token: "", playback, url, direct: true, startMs, sessionId: randomHex(12), queue, mediaIndex: 0, textSubtitle: null } }));
  }

  /** The file wouldn't play as it is: Plex converts it instead, from where it had got to. */
  convert(positionMs: number) {
    const p = this.current.playing;
    // The provider's files have no Plex to convert them.
    if (!p || !p.direct || p.base === IPTV_SOURCE) return false;
    const url = this.converted(p.base, p.token, p.item.ratingKey, p.sessionId, p.mediaIndex, p.textSubtitle ? "none" : "burn", p.playback.videoCodec);
    this.set((s) => ({ ...s, playing: { ...p, url, direct: false, startMs: positionMs } }));
    return true;
  }

  /** Plex's conversion, at the quality chosen in Settings. */
  private converted(base: string, token: string, ratingKey: string, sessionId: string, mediaIndex: number, subtitles: "burn" | "none", videoCodec: string | null) {
    const kbps = this.current.prefs.maxBitrateKbps;
    const resolution = kbps >= 20_000 ? "3840x2160" : kbps === 0 || kbps >= 8_000 ? "1920x1080" : "1280x720";
    return plex.transcodeUrl(base, token, ratingKey, sessionId, kbps, resolution, mediaIndex, subtitles, Math.round(this.current.prefs.subtitleScale * 100), videoCodec);
  }

  /**
   * Another sound track or subtitles, kept with Plex as the Fire TV keeps them, and playing
   * on from [positionMs]. Subtitles are drawn in by Plex's conversion, and so is a sound
   * track other than the file's own first; with neither, the file plays as it is again
   * when the TV can. Undefined leaves that one as it is; subtitles "0" turns them off.
   */
  async chooseStreams(audioId: string | undefined, subtitleId: string | undefined, positionMs: number, canDirect: (p: plex.PlexPlayback) => boolean) {
    const p = this.current.playing;
    if (!p) return;
    if (p.playback.partId != null) await plex.selectStream(p.base, p.token, p.playback.partId, audioId ?? null, subtitleId ?? null);
    const audioStreams = audioId === undefined ? p.playback.audioStreams : p.playback.audioStreams.map((s) => ({ ...s, selected: s.id === audioId }));
    const subtitleStreams = subtitleId === undefined ? p.playback.subtitleStreams : p.playback.subtitleStreams.map((s) => ({ ...s, selected: s.id === subtitleId }));
    const playback = { ...p.playback, audioStreams, subtitleStreams };
    if (this.current.playing !== p) return;
    const { text, burn } = subtitlePlan(playback);
    const otherSound = audioStreams.length > 1 && !audioStreams[0].selected && audioStreams.some((s) => s.selected);
    const mode = this.current.prefs.playbackMode;
    const asIs = mode === "transcode" ? false : !burn && !otherSound && (mode === "direct" || canDirect(playback));
    // A conversion running for the old choice stops; the new one starts afresh.
    if (!p.direct) void plex.stopTranscode(p.base, p.token, p.sessionId);
    const sessionId = randomHex(12);
    // Only text subtitles changed, and the file plays as it is either way: the picture
    // carries on where it is, and the words change over it.
    if (asIs && p.direct && playback.url === p.url) {
      this.set((s) => ({ ...s, playing: { ...p, playback, textSubtitle: text } }));
      return;
    }
    const url = asIs ? playback.url : this.converted(p.base, p.token, p.item.ratingKey, sessionId, p.mediaIndex, text ? "none" : "burn", playback.videoCodec);
    this.set((s) => ({ ...s, playing: { ...p, playback, url, direct: asIs, startMs: positionMs, sessionId, textSubtitle: text } }));
  }

  /** Subtitles online for what's playing, in the TV's language, found by the Plex server. */
  async findSubtitles(language = (navigator.language || "en").slice(0, 2)): Promise<{ language: string; results: plex.PlexOnlineSubtitle[]; error: string | null }> {
    const p = this.current.playing;
    if (!p || p.base === IPTV_SOURCE) return { language, results: [], error: "Subtitles can only be found for what's on your Plex server." };
    try {
      return { language, results: await plex.searchSubtitles(p.base, p.token, p.item.ratingKey, language), error: null };
    } catch {
      return { language, results: [], error: "Couldn't look for subtitles. Try again." };
    }
  }

  /**
   * Has the server fetch [subtitle] and add it to the file, then plays on with it: the
   * file's subtitles are read again and the new one is picked, and kept with Plex.
   */
  async addFoundSubtitle(subtitle: plex.PlexOnlineSubtitle, language: string, positionMs: number, canDirect: (p: plex.PlexPlayback) => boolean): Promise<string | null> {
    const p = this.current.playing;
    if (!p) return null;
    const added = await plex.addSubtitle(p.base, p.token, p.item.ratingKey, subtitle, language);
    const fresh = added ? await plex.playback(p.base, p.token, p.item.ratingKey, p.mediaIndex).catch(() => null) : null;
    const before = new Set(p.playback.subtitleStreams.map((s) => s.id));
    const newOne = fresh?.subtitleStreams.find((s) => !before.has(s.id));
    if (!fresh || !newOne || this.current.playing?.sessionId !== p.sessionId) return "Plex couldn't add those subtitles. Try another.";
    this.set((s) => ({ ...s, playing: s.playing && { ...s.playing, playback: { ...s.playing.playback, subtitles: fresh.subtitles, subtitleStreams: fresh.subtitleStreams } } }));
    await this.chooseStreams(undefined, newOne.id, positionMs, canDirect);
    return null;
  }

  /** Where playback is, told to the server: what keeps Continue Watching right everywhere. */
  report(positionMs: number, durationMs: number, state: "playing" | "paused" | "stopped", p: Playing | null = this.current.playing) {
    // A trailer isn't something to pick up again.
    if (!p || p.item.type === "clip") return Promise.resolve();
    if (p.base === IPTV_SOURCE) {
      // The provider keeps nothing: where it got to is kept on the TV.
      this.iptv.watch?.progress(p.item, positionMs, durationMs || p.item.durationMs);
      return Promise.resolve();
    }
    return plex.reportTimeline(p.base, p.token, p.item.ratingKey, positionMs, durationMs || p.item.durationMs, state, p.sessionId);
  }

  async stop(positionMs: number, durationMs: number) {
    const p = this.current.playing;
    if (!p) return;
    this.set((s) => ({ ...s, playing: null }));
    // Told about the sitting just ended, which is no longer the one in the state.
    await this.report(positionMs, durationMs, "stopped", p);
    if (!p.direct) void plex.stopTranscode(p.base, p.token, p.sessionId);
    if (p.item.type === "clip") return;
    if (p.base === IPTV_SOURCE) this.composeHome();
    // What was watched shows as watched, and where it was left, on the way back.
    void this.refreshHome();
    const page = this.current.detail;
    if (page?.detail) void this.refreshDetail(p.item);
  }

  /**
   * The page open, read again where it stands after watching: what was watched shows so,
   * without the page going back to nothing first and the cursor with it to Play. Another
   * season's episode (Up Next went on into it) has the page opened afresh on that one.
   */
  private async refreshDetail(watched: PlexItem) {
    const page = this.current.detail;
    if (!page?.detail) return;
    const episodeKey = watched.type === "episode" ? watched.ratingKey : null;
    const base = page.serverBase;
    const token = this.tokenFor(base);
    const otherSeason = episodeKey != null && page.season != null && watched.parentRatingKey != null && watched.parentRatingKey !== page.season.ratingKey;
    if (base === IPTV_SOURCE || !base || !token || otherSeason) {
      void this.openDetail(page.detail.ratingKey, base, episodeKey);
      return;
    }
    const { key, season } = page;
    try {
      const detail = await plex.detail(base, token, page.detail.ratingKey);
      if (detail) this.setDetail(key, { detail });
      if (season) {
        const episodes = (await plex.children(base, token, season.ratingKey)).filter((e) => e.type === "episode");
        const focused = episodes.find((e) => e.ratingKey === episodeKey) ?? plex.nextEpisode(episodes);
        this.setDetail(key, { episodes, focused });
      }
    } catch {
      // Left as it was: it's only a little behind.
    }
  }

  /** The episode after this one in its season, if there is one. */
  nextInQueue(): PlexItem | null {
    const p = this.current.playing;
    if (!p) return null;
    const at = p.queue.findIndex((e) => e.ratingKey === p.item.ratingKey);
    return at >= 0 ? p.queue[at + 1] ?? null : null;
  }

  dismissPlayError() {
    this.set((s) => ({ ...s, playError: null }));
  }

  // ---------------------------------------------------------------- Requests (Reely)

  private setRequests(change: Partial<RequestsState>) {
    this.set((s) => ({ ...s, requests: { ...s.requests, ...change } }));
  }

  private client(): reely.ReelyRequests | null {
    const address = this.current.requests.address ?? this.store.get("reelyUrl");
    if (!address) return null;
    if (!this.reelyClient || this.reelyClient.base !== reely.normalize(address)) {
      this.reelyClient = new reely.ReelyRequests(address, () => this.store.get("plexAccountToken") ?? this.current.plex.token ?? undefined);
    }
    return this.reelyClient;
  }

  /** Reely at [address], signed in with the Plex account in use. */
  async connectReely(address: string) {
    if (!reely.isValid(address)) {
      this.setRequests({ error: "That doesn't look like an address. Try reely.example.com or 192.168.1.5:8788." });
      return;
    }
    this.setRequests({ connecting: true, error: null });
    const client = new reely.ReelyRequests(address, () => this.store.get("plexAccountToken") ?? this.current.plex.token ?? undefined);
    const problem = await client.signIn();
    if (problem) {
      this.setRequests({ connecting: false, error: problem });
      return;
    }
    this.reelyClient = client;
    this.store.set("reelyUrl", client.base);
    this.setRequests({ connecting: false, address: client.base });
    await this.loadRequests();
  }

  disconnectReely() {
    this.store.remove("reelyUrl");
    this.reelyClient = null;
    this.set((s) => ({ ...s, requests: emptyRequests(), requestPage: null }));
  }

  /**
   * Reely's rows, this account's requests and Reely's marks, with what the Plex libraries
   * hold read first: titles already there are left out of the rows, and mustn't vanish
   * from under the cursor once they're up.
   */
  async loadRequests() {
    const client = this.client();
    if (!client) return;
    if (!this.current.requests.address) this.setRequests({ address: client.base });
    this.setRequests({ loading: this.current.requests.rows.length === 0, error: null });
    const holdings = this.plexHoldings();
    const [rows, mine, marks] = await Promise.all([
      client.explore().then((r) => ({ ok: true as const, r }), (e) => ({ ok: false as const, e })),
      client.myRequests().catch(() => null),
      client.marks().catch(() => null),
    ]);
    const held = await holdings;
    const now = this.current.requests;
    this.setRequests({
      loading: false,
      rows: rows.ok ? rows.r : now.rows,
      mine: mine ?? now.mine,
      marks: marks ?? now.marks,
      plexMovies: held?.movies ?? now.plexMovies,
      plexShows: held?.shows ?? now.plexShows,
      error: rows.ok ? null : readable(rows.e),
    });
  }

  private async plexHoldings(): Promise<{ movies: Set<string>; shows: Set<string> } | null> {
    const libraries = this.shownLibraries();
    if (!libraries.length) return null;
    const of = async (type: "movie" | "show") => {
      const sets = await Promise.all(
        libraries.filter((l) => l.section.type === type).map((l) =>
          plex.libraryGuids(l.baseUrl, l.token, l.section.key, type === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW).catch(() => new Set<string>()),
        ),
      );
      const all = new Set<string>();
      sets.forEach((set) => set.forEach((g) => all.add(g)));
      return all;
    };
    const [movies, shows] = await Promise.all([of("movie"), of("show")]);
    return { movies, shows };
  }

  async searchRequests(query: string) {
    const run = ++this.searchRun;
    this.setRequests({ query, searching: !!query.trim(), results: query.trim() ? this.current.requests.results : [] });
    const client = this.client();
    if (!client || !query.trim()) return;
    try {
      const results = await client.search(query);
      if (run === this.searchRun) this.setRequests({ results, searching: false });
    } catch (error) {
      if (run === this.searchRun) this.setRequests({ searching: false, error: readable(error) });
    }
  }

  private setRequestPage(key: string, change: Partial<RequestPage>) {
    this.set((s) => (s.requestPage && reely.requestKey(s.requestPage.title) === key ? { ...s, requestPage: { ...s.requestPage, ...change } } : s));
  }

  async openRequestTitle(title: RequestTitle) {
    const key = reely.requestKey(title);
    this.set((s) => ({ ...s, requestPage: { title, detail: null, places: null, chosen: [], libraryId: null, busy: true, sending: false, outcome: null, error: null } }));
    const client = this.client();
    if (!client) return;
    try {
      const [detail, places] = await Promise.all([client.detail(title), client.places().catch(() => null)]);
      const addable = places ? reely.librariesFor(places, detail.title, reely.holdingAll(detail)) : [];
      const preferred = places ? reely.preferredLibrary(places, addable) : undefined;
      this.setRequestPage(key, {
        detail, places, busy: false,
        chosen: detail.seasons.map((x) => x.number),
        libraryId: preferred?.id ?? null,
      });
    } catch (error) {
      this.setRequestPage(key, { busy: false, error: readable(error) });
    }
  }

  toggleRequestSeason(number: number) {
    const page = this.current.requestPage;
    if (!page) return;
    const chosen = page.chosen.includes(number) ? page.chosen.filter((n) => n !== number) : [...page.chosen, number].sort((a, b) => a - b);
    this.setRequestPage(reely.requestKey(page.title), { chosen });
  }

  chooseRequestLibrary(id: number) {
    const page = this.current.requestPage;
    if (page) this.setRequestPage(reely.requestKey(page.title), { libraryId: id });
  }

  async submitRequest() {
    const page = this.current.requestPage;
    const client = this.client();
    if (!page || !page.detail || !client || page.sending) return;
    const key = reely.requestKey(page.title);
    // Of a show partly here, the seasons it hasn't got: Reely keeps the ones already asked for.
    const offered = reely.seasonsLeft(page.detail, page.libraryId).map((s) => s.number);
    const picked = page.chosen.filter((n) => offered.includes(n));
    const show = page.title.kind === "show" && offered.length > 0;
    if (show && picked.length === 0) {
      this.setRequestPage(key, { outcome: "Pick at least one season." });
      return;
    }
    // Every season is the whole show, which also takes in seasons still to come.
    const all = show && picked.length === offered.length && reely.askedIn(page.detail, page.libraryId).length === 0;
    this.setRequestPage(key, { sending: true, outcome: null });
    try {
      const outcome = await client.request(page.detail.title, show && !all ? picked : null, page.libraryId ?? undefined);
      // As the Fire TV words it.
      const library = page.places?.libraries.find((l) => l.id === page.libraryId);
      const said =
        outcome.kind === "sent"
          ? outcome.approved
            ? `Adding it${library ? ` to ${library.name}` : ""} now.`
            : `Requested${library ? ` for ${library.name}` : ""}. You'll see it here once it's approved.`
          : outcome.kind === "already" ? "This has already been requested."
          : outcome.message;
      this.setRequestPage(key, { sending: false, outcome: said });
      void this.loadRequests();
    } catch (error) {
      this.setRequestPage(key, { sending: false, outcome: readable(error) });
    }
  }

  // ---------------------------------------------------------------- Live TV

  private setLive(change: Partial<LiveState>) {
    this.set((s) => ({ ...s, live: { ...s.live, ...change } }));
  }

  /** An Xtream login: the panel's address, a username and a password. */
  async signInXtream(base: string, username: string, password: string) {
    if (!base.trim() || !username.trim() || !password) {
      this.setLive({ error: "Enter the server address, username and password your provider gave you." });
      return;
    }
    const panel = xtream.panelLoginIn(base.trim());
    await this.signInLive(panel ?? { base: xtream.normalizeBase(base), username: username.trim(), password });
  }

  /** An M3U playlist; a panel's own playlist address signs in to the panel instead. */
  async signInPlaylist(url: string, guideUrl: string) {
    if (!/^https?:\/\//i.test(url.trim())) {
      this.setLive({ error: "Enter the playlist's full address, starting http:// or https://." });
      return;
    }
    const panel = xtream.panelLoginIn(url.trim());
    await this.signInLive(panel ?? { base: "", username: "", password: "", playlistUrl: url.trim(), guideUrl: guideUrl.trim() || null });
  }

  private async signInLive(credentials: XtreamCredentials) {
    this.setLive({ busy: true, error: null });
    try {
      const account = await xtream.login(credentials);
      this.allChannels = null;
      xtream.forgetPlaylistGuide();
      this.store.setJson("xtream", credentials);
      this.setLive({ credentials, account, busy: false });
      // Another provider's catalogue isn't this one's.
      this.iptv.setCatalog(vod.emptyCatalog());
      this.set((s) => ({ ...s, iptv: { loading: false, error: null, ready: false } }));
      void this.loadIptv();
      await this.loadLive();
    } catch (error) {
      this.setLive({ busy: false, error: readable(error) });
    }
  }

  signOutLive() {
    this.allChannels = null;
    xtream.forgetPlaylistGuide();
    this.iptv.setCatalog(vod.emptyCatalog());
    this.set((s) => ({ ...s, iptv: { loading: false, error: null, ready: false } }));
    this.leaveIptvTabs();
    this.composeHome();
    this.store.remove("xtream");
    this.set((s) => ({ ...s, live: { ...emptyLive(), favorites: s.live.favorites, recent: s.live.recent } }));
  }

  async loadLive() {
    const c = this.current.live.credentials;
    if (!c || this.current.live.categories.length) return;
    this.setLive({ busy: true, error: null });
    try {
      const categories = await xtream.liveCategories(c);
      this.setLive({ categories, busy: false });
    } catch (error) {
      this.setLive({ busy: false, error: readable(error) });
    }
  }

  shownCategories(): XtreamCategory[] {
    const live = this.current.live;
    return [...(live.favorites.length ? [FAVORITES] : []), ...(live.recent.length ? [RECENT] : []), ...live.categories];
  }

  async openCategory(category: XtreamCategory) {
    const c = this.current.live.credentials;
    if (!c) return;
    this.setLive({ category, channels: [], busy: true, error: null });
    try {
      const channels = await this.channelsOf(category);
      if (this.current.live.category !== category) return;
      this.setLive({ channels, busy: false });
      void this.loadGuide(channels.slice(0, 40));
    } catch (error) {
      this.setLive({ busy: false, error: readable(error) });
    }
  }

  /** A category's channels, without opening it: the guide over a channel browses them. */
  async channelsOf(category: XtreamCategory): Promise<XtreamChannel[]> {
    const c = this.current.live.credentials;
    if (!c) return [];
    const recent = this.current.live.recent;
    return category.id === FAVORITES.id
      ? (await xtream.liveChannels(c)).filter((ch) => this.current.live.favorites.includes(ch.streamId))
      : category.id === RECENT.id
        ? stableSort((await xtream.liveChannels(c)).filter((ch) => recent.includes(ch.streamId)), (a, b) => recent.indexOf(a.streamId) - recent.indexOf(b.streamId))
        : await xtream.liveChannels(c, category.id);
  }

  /**
   * A channel picked in the guide over another: from the category browsed there, which
   * becomes the one open, all at once, so what's playing never points into the wrong list.
   * A programme that's over plays from the archive.
   */
  watchIn(category: XtreamCategory, channels: XtreamChannel[], index: number, programme: Programme | null = null) {
    const live = this.current.live;
    const channel = channels[index];
    const c = live.credentials;
    if (!channel || !c) return;
    const url = programme ? xtream.catchUpUrl(c, channel, programme.start, programme.stop, live.account?.timezone ?? null) : null;
    this.noteWatched(channel);
    this.setLive({ category, channels, watching: index, catchUp: programme && url ? { programme, url } : null });
  }

  closeCategory() {
    this.setLive({ category: null, channels: [], watching: null, catchUp: null });
  }

  /** The guide's listings for these channels: what's been, for catch-up, and what's coming. */
  async loadTable(channels: XtreamChannel[]) {
    const c = this.current.live.credentials;
    if (!c) return;
    if (xtream.isPlaylist(c)) return this.fromPlaylistGuide(channels);
    const wanted = channels.filter((ch) => !this.current.live.table[ch.streamId]);
    for (let i = 0; i < wanted.length; i += 4) {
      const batch = wanted.slice(i, i + 4);
      const found = await Promise.all(batch.map((ch) => xtream.epgTable(c, ch.streamId).catch(() => [] as Programme[])));
      if (this.current.live.credentials !== c) return;
      const table = { ...this.current.live.table };
      batch.forEach((ch, n) => { table[ch.streamId] = found[n]; });
      this.setLive({ table });
    }
  }

  /** A programme that's over, from the channel's archive; or this one from its start. */
  playCatchUp(index: number, programme: Programme): boolean {
    const live = this.current.live;
    const channel = live.channels[index];
    const c = live.credentials;
    if (!channel || !c) return false;
    const url = xtream.catchUpUrl(c, channel, programme.start, programme.stop, live.account?.timezone ?? null);
    if (!url) return false;
    this.noteWatched(channel);
    this.setLive({ watching: index, catchUp: { programme, url } });
    return true;
  }

  /** Back to the channel as it is now. */
  goLive() {
    this.setLive({ catchUp: null });
  }

  /** Whether [channel] keeps an archive this programme is still in. */
  canCatchUp(channel: XtreamChannel, programme: Programme, now = Math.floor(Date.now() / 1000)): boolean {
    const from = xtream.catchUpFrom(channel, now);
    return !!this.current.live.credentials && !xtream.isPlaylist(this.current.live.credentials) && from != null && programme.start >= from && programme.start < now;
  }

  private noteWatched(channel: XtreamChannel) {
    const recent = [channel.streamId, ...this.current.live.recent.filter((id) => id !== channel.streamId)].slice(0, RECENT_KEPT);
    this.store.setJson("recentChannels", recent);
    this.setLive({ recent });
  }

  // ---------------------------------------------------------------- Reminders

  toggleReminder(channel: XtreamChannel, programme: Programme) {
    const now = this.current.live.reminders;
    const has = now.some((r) => r.streamId === channel.streamId && r.start === programme.start);
    const reminders = has
      ? now.filter((r) => !(r.streamId === channel.streamId && r.start === programme.start))
      : [...now, { streamId: channel.streamId, channelName: channel.name, title: programme.title, start: programme.start }];
    this.store.setJson("reminders", reminders);
    this.setLive({ reminders });
  }

  hasReminder(channel: XtreamChannel, programme: Programme) {
    return this.current.live.reminders.some((r) => r.streamId === channel.streamId && r.start === programme.start);
  }

  /** A reminder whose programme starts within the minute comes due, once; old ones go. */
  checkReminders(nowMs = Date.now()) {
    const live = this.current.live;
    if (live.due) return;
    const due = live.reminders.find((r) => r.start * 1000 - nowMs <= 60_000 && nowMs - r.start * 1000 < 10 * 60_000);
    const kept = live.reminders.filter((r) => r !== due && nowMs - r.start * 1000 < 10 * 60_000);
    if (due || kept.length !== live.reminders.length) {
      this.store.setJson("reminders", kept);
      this.setLive({ reminders: kept, due: due ?? null });
    }
  }

  dismissReminder() {
    this.setLive({ due: null });
  }

  /** The reminded channel, wherever it is in the lists. */
  async watchReminder() {
    const due = this.current.live.due;
    const c = this.current.live.credentials;
    this.setLive({ due: null });
    if (!due || !c) return;
    let at = this.current.live.channels.findIndex((ch) => ch.streamId === due.streamId);
    if (at < 0) {
      try {
        const all = await xtream.liveChannels(c);
        const channel = all.find((ch) => ch.streamId === due.streamId);
        if (!channel) return;
        this.setLive({ category: null, channels: [channel] });
        at = 0;
      } catch (error) {
        this.setLive({ error: readable(error) });
        return;
      }
    }
    this.watchChannel(at);
  }

  /** Now and next for what's on screen, a few at a time so the panel isn't flooded. */
  async loadGuide(channels: XtreamChannel[]) {
    const c = this.current.live.credentials;
    if (!c) return;
    if (xtream.isPlaylist(c)) return this.fromPlaylistGuide(channels);
    for (let i = 0; i < channels.length; i += 6) {
      const batch = channels.slice(i, i + 6);
      const found = await Promise.all(batch.map((ch) => xtream.shortEpg(c, ch.streamId, 2).catch(() => [] as Programme[])));
      const guide = { ...this.current.live.guide };
      batch.forEach((ch, n) => { guide[ch.streamId] = found[n]; });
      this.setLive({ guide });
    }
  }

  toggleFavorite(channel: XtreamChannel) {
    const now = this.current.live.favorites;
    const favorites = now.includes(channel.streamId) ? now.filter((id) => id !== channel.streamId) : [...now, channel.streamId];
    this.store.setJson("favorites", favorites);
    this.setLive({ favorites });
  }

  watchChannel(index: number) {
    const channel = this.current.live.channels[index];
    if (!channel) return;
    this.noteWatched(channel);
    this.setLive({ watching: index, catchUp: null });
  }

  /** Channel up and down, round the list's ends. */
  stepChannel(by: number) {
    const live = this.current.live;
    if (live.watching == null || !live.channels.length) return;
    this.watchChannel((live.watching + by + live.channels.length) % live.channels.length);
  }

  /** A channel by the number on it, as typed on the remote. */
  tuneNumber(number: number): boolean {
    const at = this.current.live.channels.findIndex((ch) => ch.number === number);
    if (at < 0) return false;
    this.watchChannel(at);
    return true;
  }

  stopLive() {
    this.setLive({ watching: null, catchUp: null });
  }

  /** Where the channel plays from: the provider's HLS, which the TV plays itself. */
  channelUrl(channel: XtreamChannel): string | null {
    const c = this.current.live.credentials;
    return c ? xtream.streamUrl(c, channel, this.current.prefs.streamFormat) : null;
  }

  // ---------------------------------------------------------------- Search

  /** Movies, shows, people, collections and channels, from every server, as the Fire TV searches. */
  async setQuery(query: string) {
    const run = ++this.queryRun;
    if (!query.trim()) {
      this.set((s) => ({ ...s, search: { ...emptySearch(s.search.recent), query } }));
      return;
    }
    this.set((s) => ({ ...s, search: { ...s.search, query, busy: true } }));
    // Typing on a remote is slow: a pause, rather than a search for every letter.
    await this.wait(400);
    if (run !== this.queryRun) return;
    const p = this.current.plex;
    const servers = this.shownLibraries()
      .map((l) => [l.baseUrl, l.token] as const)
      .filter(([base], i, all) => all.findIndex(([b]) => b === base) === i);
    if (!servers.length && p.baseUrl && p.serverToken) servers.push([p.baseUrl, p.serverToken]);
    const [found, channels] = await Promise.all([
      Promise.all(servers.map(([base, token]) => plex.searchAll(base, token, query).catch(() => null))),
      this.channelsMatching(query),
    ]);
    if (run !== this.queryRun) return;
    const answered = found.filter((f): f is plex.PlexFound => f !== null);
    // The provider's films and series as well, matched from the list in hand; a title in
    // both is the winner's copy only.
    const on = this.iptvOn();
    const wins = this.current.prefs.iptvWins;
    const fromIptv = on ? this.iptv.search(query, wins) : [];
    const plexFound = answered.reduce<PlexItem[]>((all, f) => all.concat(f.items), []).filter((i) => !on || !this.iptv.hides(i, wins));
    const [matches, others] = split(query, [...plexFound, ...fromIptv]);
    const people = answered
      .reduce<PlexPerson[]>((all, f) => all.concat(f.people), [])
      .filter((x, i, all) => all.findIndex((y) => y.name.toLowerCase() === x.name.toLowerCase()) === i)
      .slice(0, PEOPLE_RESULTS);
    const collections = answered
      .reduce<PlexItem[]>((all, f) => all.concat(f.collections), [])
      .filter((x, i, all) => all.findIndex((y) => y.title.toLowerCase() === x.title.toLowerCase()) === i);
    this.set((s) => ({
      ...s,
      search: {
        ...s.search,
        busy: false,
        // Nothing by name, a misspelling most likely: then Plex's own guesses are the results.
        results: matches.length ? matches : others,
        more: matches.length ? others : [],
        people,
        collections,
        channels,
        unreachable: servers.length > 0 && answered.length === 0 && fromIptv.length === 0,
      },
    }));
  }

  /** Channels whose names have the words in them; the panel has no search of its own. */
  private async channelsMatching(query: string): Promise<XtreamChannel[]> {
    const c = this.current.live.credentials;
    if (!c) return [];
    this.allChannels ??= xtream.liveChannels(c).catch(() => {
      this.allChannels = null;
      return [];
    });
    const wanted = query.trim().toLowerCase();
    return (await this.allChannels).filter((ch) => ch.name.toLowerCase().includes(wanted)).slice(0, CHANNEL_RESULTS);
  }

  /** What was searched kept, when something it found is opened: a search that worked. */
  rememberSearch() {
    const recent = rememberedSearches(this.current.search.recent, this.current.search.query);
    this.store.setJson("recentSearches", recent);
    this.set((s) => ({ ...s, search: { ...s.search, recent } }));
  }

  clearRecentSearches() {
    this.store.setJson("recentSearches", []);
    this.set((s) => ({ ...s, search: { ...s.search, recent: [] } }));
  }

  /** A channel found by search, watched with the others found beside it for channel up and down. */
  watchFound(channel: XtreamChannel) {
    const channels = this.current.search.channels;
    const at = channels.findIndex((ch) => ch.streamId === channel.streamId);
    if (at < 0) return;
    this.noteWatched(channel);
    this.setLive({ category: null, channels, watching: at, catchUp: null });
    void this.loadGuide(channels.slice(0, 12));
  }

  // ---------------------------------------------------------------- A person's and a collection's titles

  /** Everything they're in, from the libraries on the server they were found on. */
  async openPerson(person: PlexPerson) {
    const key = `person:${person.serverBase ?? ""}|${person.id}`;
    const run = ++this.listRun;
    this.set((s) => ({ ...s, list: { key, items: [], busy: true, error: null } }));
    const libraries = this.shownLibraries().filter((l) => !person.serverBase || l.baseUrl === person.serverBase);
    const found = await Promise.all(
      libraries.map((l) =>
        plex.withActor(l.baseUrl, l.token, l.section.key, l.section.type === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW, person.id).catch(() => null),
      ),
    );
    if (run !== this.listRun) return;
    const items = found.reduce<PlexItem[]>((all, f) => all.concat(f ?? []), []);
    const newest = stableSort(items, (a, b) => (b.year ?? 0) - (a.year ?? 0));
    this.set((s) => ({
      ...s,
      list: { key, items: newest, busy: false, error: libraries.length && found.every((f) => f === null) ? "Couldn't reach your Plex server." : null },
    }));
  }

  async openCollection(item: PlexItem) {
    const key = `collection:${plex.listKey(item)}`;
    const run = ++this.listRun;
    this.set((s) => ({ ...s, list: { key, items: [], busy: true, error: null } }));
    const base = item.serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    try {
      if (!base || !token) throw new Error("Couldn't reach the server this collection is on.");
      const items = await plex.collectionItems(base, token, item.ratingKey);
      if (run === this.listRun) this.set((s) => ({ ...s, list: { key, items, busy: false, error: null } }));
    } catch (error) {
      if (run === this.listRun) this.set((s) => ({ ...s, list: { key, items: [], busy: false, error: readable(error) } }));
    }
  }

  async openPlaylist(item: PlexItem) {
    const key = `playlist:${plex.listKey(item)}`;
    const run = ++this.listRun;
    this.set((s) => ({ ...s, list: { key, items: [], busy: true, error: null } }));
    const base = item.serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    try {
      if (!base || !token) throw new Error("Couldn't reach the server this playlist is on.");
      const items = (await plex.playlistItems(base, token, item.ratingKey)).filter((i) => i.type === "movie" || i.type === "episode");
      if (run === this.listRun) this.set((s) => ({ ...s, list: { key, items, busy: false, error: null } }));
    } catch (error) {
      if (run === this.listRun) this.set((s) => ({ ...s, list: { key, items: [], busy: false, error: readable(error) } }));
    }
  }

  // ---------------------------------------------------------------- A poster's menu

  /** Watched or not, for any poster: then Home again, and the page it's on if it's open. */
  async setItemWatched(item: PlexItem, watched: boolean) {
    if (isIptv(item)) {
      // A series: every episode of it, as far as the panel says.
      const k = vod.parseKey(item.ratingKey);
      const c = this.current.live.credentials;
      let items = [item];
      if (k?.kind === "show" && c) {
        const info = this.iptv.cachedSeries(k.id) ?? (await vod.seriesInfo(c, k.id).catch(() => null));
        if (info) {
          this.iptv.keepSeries(k.id, info);
          items = info.seasons.reduce<PlexItem[]>((list, s) => list.concat(vod.episodeItems(k.id, item.title, item.thumb, null, s)), []);
        }
      }
      this.iptv.watch?.setWatched(items, watched);
      const mark = (i: PlexItem) => (isIptv(i) ? this.iptv.marked(i) : i);
      this.set((s) => ({
        ...s,
        browse: { movie: { ...s.browse.movie, items: s.browse.movie.items.map(mark) }, show: { ...s.browse.show, items: s.browse.show.items.map(mark) } },
        list: s.list ? { ...s.list, items: s.list.items.map(mark) } : s.list,
      }));
      this.composeHome();
      return;
    }
    const base = item.serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token) return;
    try {
      await plex.setWatched(base, token, item.ratingKey, watched);
    } catch (error) {
      this.set((s) => ({ ...s, playError: readable(error) }));
      return;
    }
    const mark = (i: PlexItem) =>
      plex.listKey(i) === plex.listKey(item) ? { ...i, viewCount: watched ? 1 : 0, viewOffsetMs: 0, viewedLeafCount: watched ? i.leafCount : 0 } : i;
    this.set((s) => ({
      ...s,
      browse: { movie: { ...s.browse.movie, items: s.browse.movie.items.map(mark) }, show: { ...s.browse.show, items: s.browse.show.items.map(mark) } },
      list: s.list ? { ...s.list, items: s.list.items.map(mark) } : s.list,
    }));
    void this.refreshHome();
  }

  async removeFromContinueWatching(item: PlexItem) {
    const base = item.serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token) return;
    // Gone at once; Home asked again behind it.
    this.set((s) => ({ ...s, home: { ...s.home, continueWatching: s.home.continueWatching.filter((i) => plex.listKey(i) !== plex.listKey(item)) } }));
    try {
      await plex.removeFromContinueWatching(base, token, item.ratingKey);
    } catch (error) {
      this.set((s) => ({ ...s, playError: readable(error) }));
    }
    void this.refreshHome();
  }

  /** A show's next episode, as its menu offers it: the one it's up to. */
  async nextEpisodeOf(show: PlexItem): Promise<{ episode: PlexItem; queue: PlexItem[] } | null> {
    if (isIptv(show)) {
      const k = vod.parseKey(show.ratingKey);
      const c = this.current.live.credentials;
      if (k?.kind !== "show" || !c) return null;
      const info = this.iptv.cachedSeries(k.id) ?? (await vod.seriesInfo(c, k.id).catch(() => null));
      if (!info) return null;
      this.iptv.keepSeries(k.id, info);
      const episodes = info.seasons.reduce<PlexItem[]>((list, s) => list.concat(vod.episodeItems(k.id, show.title, show.thumb, null, s)), []).map(this.iptv.marked);
      const episode = plex.nextEpisode(episodes);
      return episode ? { episode, queue: episodes } : null;
    }
    const base = show.serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token) return null;
    try {
      const episodes = (await plex.episodesOf(base, token, show.ratingKey)).map((e) => ({ ...e, serverBase: base }));
      const episode = plex.nextEpisode(episodes);
      return episode ? { episode, queue: episodes } : null;
    } catch (error) {
      this.set((s) => ({ ...s, playError: readable(error) }));
      return null;
    }
  }

  // ---------------------------------------------------------------- Settings

  setPlaybackMode(playbackMode: PlaybackMode) {
    this.setPrefs({ playbackMode });
  }

  setMaxBitrate(maxBitrateKbps: number) {
    if (BITRATE_CHOICES.includes(maxBitrateKbps)) this.setPrefs({ maxBitrateKbps });
  }

  setSkipIntros(skipIntros: boolean) {
    this.setPrefs({ skipIntros });
  }

  setSkipCredits(skipCredits: boolean) {
    this.setPrefs({ skipCredits });
  }

  toggleHomeRow(id: HomeRowId) {
    const hidden = this.current.prefs.hiddenRows;
    this.setPrefs({ hiddenRows: hidden.includes(id) ? hidden.filter((r) => r !== id) : [...hidden, id] });
  }

  /** The tour over: not shown again unless asked for from Settings. */
  finishTour() {
    this.setPrefs({ tourSeen: true });
  }

  takeTour() {
    this.setPrefs({ tourSeen: false });
  }

  setThemeLevel(level: number) {
    if (level >= -1 && level < THEME_LEVELS.length) this.setPrefs({ themeLevel: level });
  }

  setGuidePreview(on: boolean) {
    this.setPrefs({ guidePreview: on });
  }

  setStreamFormat(format: xtream.StreamFormat) {
    this.setPrefs({ streamFormat: format });
  }

  /** The provider's channels asked for afresh. */
  async refreshChannels() {
    this.allChannels = null;
    this.setLive({ categories: [], category: null, channels: [] });
    await this.loadLive();
  }

  /** The guide asked for afresh, for the channels on screen; a playlist's read again whole. */
  async refreshGuide() {
    const c = this.current.live.credentials;
    const channels = this.current.live.channels;
    if (c && xtream.isPlaylist(c)) return this.fromPlaylistGuide(channels, true);
    this.setLive({ guide: {}, table: {} });
    await this.loadGuide(channels.slice(0, 40));
  }

  /**
   * A playlist's channels' listings, from the XMLTV guide it names: read once, whole, as the
   * Fire TV does, then kept for a while. A playlist that names none has none to show.
   */
  private async fromPlaylistGuide(channels: XtreamChannel[], fresh = false) {
    const c = this.current.live.credentials;
    if (!c || !xtream.isPlaylist(c)) return;
    const at = xtream.playlistGuideAt(c);
    const now = Math.floor(Date.now() / 1000);
    if (fresh || at == null || now - at >= xtream.PLAYLIST_GUIDE_KEEP_SECONDS) this.setLive({ guideStatus: { kind: "updating" } });
    try {
      const byChannel = await xtream.playlistGuide(c, fresh);
      if (this.current.live.credentials !== c) return;
      const read = xtream.playlistGuideAt(c);
      const guide = { ...this.current.live.guide };
      const table = { ...this.current.live.table };
      for (const ch of channels) {
        const list = xtream.playlistListing(byChannel, ch);
        table[ch.streamId] = list;
        guide[ch.streamId] = xtream.nowAndNext(list, now, 4);
      }
      this.setLive({ guide, table, guideStatus: read == null ? { kind: "none" } : { kind: "ready", at: read } });
    } catch (error) {
      if (this.current.live.credentials !== c) return;
      this.setLive({ guideStatus: { kind: "failed", message: readable(error) } });
    }
  }

  /** The provider's movies and shows asked for afresh. */
  async refreshIptv() {
    this.iptv.setCatalog(vod.emptyCatalog());
    this.set((s) => ({ ...s, iptv: { loading: false, error: null, ready: false } }));
    await this.loadIptv();
  }

  /** Another of the account's servers first: its libraries and Home, from now on. */
  async chooseServer(name: string) {
    const token = this.current.plex.token;
    if (!token) return;
    this.store.set("server", name);
    await this.connect(token);
  }

  /** A file of the server's (a show's theme), with the token it needs. */
  mediaUrl(serverBase: string | null, path: string | null | undefined): string | null {
    const base = serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token || !path) return null;
    return `${base}${path}${path.includes("?") ? "&" : "?"}X-Plex-Token=${token}`;
  }

  setSubtitleScale(scale: number) {
    if (SUBTITLE_SIZES.includes(scale)) this.setPrefs({ subtitleScale: scale });
  }

  setSubtitleBackground(on: boolean) {
    this.setPrefs({ subtitleBackground: on });
  }

  setIptvInMenus(on: boolean) {
    this.setPrefs({ iptvInMenus: on });
  }

  setAccent(id: string) {
    this.setPrefs({ accent: accentOf(id).id });
  }

  setSubtitlesAtStart(setting: SubtitlesAtStart) {
    this.setPrefs({ subtitlesAtStart: setting });
  }

  /** The player's Increase size and Decrease size. */
  nudgeSubtitleScale(step: 1 | -1) {
    this.setPrefs({ subtitleScale: nextSubtitleSize(this.current.prefs.subtitleScale, step) });
  }

  setScreensaver(minutes: number) {
    if (SCREENSAVER_CHOICES.includes(minutes)) this.setPrefs({ screensaverMinutes: minutes });
  }

  setUpNextSeconds(upNextSeconds: number) {
    if (UP_NEXT_CHOICES.includes(upNextSeconds)) this.setPrefs({ upNextSeconds });
  }

  private setPrefs(change: Partial<Prefs>) {
    const prefs = { ...this.current.prefs, ...change };
    this.store.setJson("prefs", prefs);
    this.set((s) => ({ ...s, prefs }));
  }

  /** A picture from the server a title is on, at the size it's drawn. */
  image(serverBase: string | null, path: string | null | undefined, width: number, height: number): string | null {
    // The provider's pictures are whole addresses already.
    if (path && /^https?:\/\//i.test(path)) return path;
    const base = serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token || !path) return null;
    return plex.imageUrl(base, token, path, width, height);
  }
}
