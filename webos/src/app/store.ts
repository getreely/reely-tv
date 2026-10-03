import * as plex from "../api/plex";
import type { PlexDetail, PlexHomeUser, PlexItem, PlexPerson, PlexServer } from "../api/plex";
import { rememberedSearches, split } from "../core/searchMatch";
import { stableSort } from "../core/sort";
import { readable } from "../core/http";
import { Store, clientId } from "../core/storage";
import { emptyHome, loadHome, type HomeRows, type LibraryChoice } from "./home";
import { randomHex } from "../core/storage";
import * as reely from "../api/reely";
import * as xtream from "../api/xtream";
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
}

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
}

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
}

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
}

export const UP_NEXT_CHOICES = [0, 5, 10, 12, 15, 20, 30];
const DEFAULT_PREFS: Prefs = { playbackMode: "auto", maxBitrateKbps: 0, skipIntros: false, skipCredits: false, upNextSeconds: 12 };

/** As the Fire TV offers them. */
export const BITRATE_CHOICES = [0, 20_000, 12_000, 8_000, 4_000, 2_000];

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
}

const emptyBrowse = (): Browse => ({
  choice: null, items: [], total: 0, busy: false, sort: "titleSort:asc", error: null,
  unwatched: false, genre: null, decade: null, genres: [], decades: [], letters: [],
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
  };
}

const emptySearch = (recent: string[]): SearchState => ({
  query: "", busy: false, results: [], more: [], people: [], collections: [], channels: [], recent, unreachable: false,
});

const emptyLive = (): LiveState => ({
  credentials: null, account: null, categories: [], category: null, channels: [], guide: {}, favorites: [], busy: false, error: null, watching: null,
});

const emptyRequests = (): RequestsState => ({
  address: null, connecting: false, loading: false, error: null, rows: [], mine: [], marks: reely.noMarks(),
  plexMovies: new Set(), plexShows: new Set(), query: "", results: [], searching: false,
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

  constructor(
    readonly store: Store = new Store(),
    private wait: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {
    plex.identity.clientId = clientId(store);
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
  }

  /** Back one page; false when there's nowhere back to go (Home's Back leaves the app). */
  goBack(): boolean {
    const s = this.current;
    // Live TV's own steps first: off the channel, then out of the category.
    if (s.live.watching != null) {
      this.stopLive();
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
      },
    }));
    const live = this.store.json<XtreamCredentials | null>("xtream", null);
    if (live) this.setLive({ credentials: live, favorites: this.store.json<number[]>("favorites", []) });
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
      this.setPlex({ servers, finding: false, error: servers.length ? "Can't find your Plex server. Make sure it's on." : "No Plex servers on this account." });
      return;
    }
    this.store.set("server", chosen.server.name);
    this.setPlex({ servers, serverName: chosen.server.name, baseUrl: chosen.base, serverToken: chosen.server.accessToken, libraries, finding: false, error: null });
    await this.refreshHome();
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
    const libraries = this.current.plex.libraries;
    if (!libraries.length) return;
    const run = ++this.homeRun;
    this.set((s) => ({ ...s, homeBusy: true, homeError: null }));
    const rows = await loadHome(libraries);
    // The one asked for last is the one that counts.
    if (run !== this.homeRun) return;
    if (!rows) {
      this.set((s) => ({ ...s, homeBusy: false, homeError: "Couldn't reach your Plex server. Trying again…" }));
      return;
    }
    this.set((s) => ({ ...s, home: { ...rows, watchlist: s.home.watchlist }, homeBusy: false, homeError: null }));
    void this.refreshWatchlist();
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
    const servers = this.current.plex.libraries
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

  librariesOf(kind: Kind): LibraryChoice[] {
    return this.current.plex.libraries.filter((l) => l.section.type === kind);
  }

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
          ...(switching ? { genre: null, decade: null, genres: [], decades: [], letters: [] } : {}),
        },
      },
    }));
    const type = kind === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW;
    void Promise.all([
      plex.genres(target.baseUrl, target.token, target.section.key, type).catch(() => []),
      plex.decades(target.baseUrl, target.token, target.section.key, type).catch(() => []),
    ]).then(([genres, decades]) => {
      if (this.current.browse[kind].choice === target) this.setBrowse(kind, { genres, decades });
    });
    void this.loadLetters(kind);
    await this.loadMore(kind);
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
    if (!choice) return;
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
  async loadMore(kind: Kind) {
    const browse = this.current.browse[kind];
    const choice = browse.choice;
    if (!choice) return;
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

  async selectSeason(season: PlexItem) {
    const page = this.current.detail;
    if (!page || !page.serverBase) return;
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
    const token = this.tokenFor(base);
    if (!base || !token) {
      this.set((s) => ({ ...s, playError: "Couldn't reach the server this is on." }));
      return;
    }
    try {
      const playback = await plex.playback(base, token, item.ratingKey, mediaIndex);
      if (!playback) throw new Error("That file isn't on the server any more.");
      const sessionId = randomHex(12);
      const mode = this.current.prefs.playbackMode;
      const asIs = mode === "direct" ? true : mode === "transcode" ? false : direct(playback);
      const url = asIs ? playback.url : this.converted(base, token, item.ratingKey, sessionId, mediaIndex);
      const startMs = resume && item.viewOffsetMs > 0 && !(item.durationMs > 0 && item.viewOffsetMs >= item.durationMs * 0.95) ? item.viewOffsetMs : 0;
      this.set((s) => ({ ...s, playError: null, playing: { item, base, token, playback, url, direct: asIs, startMs, sessionId, queue, mediaIndex } }));
    } catch (error) {
      this.set((s) => ({ ...s, playError: readable(error) }));
    }
  }

  /** The file wouldn't play as it is: Plex converts it instead, from where it had got to. */
  convert(positionMs: number) {
    const p = this.current.playing;
    if (!p || !p.direct) return false;
    const url = this.converted(p.base, p.token, p.item.ratingKey, p.sessionId, p.mediaIndex);
    this.set((s) => ({ ...s, playing: { ...p, url, direct: false, startMs: positionMs } }));
    return true;
  }

  /** Plex's conversion, at the quality chosen in Settings. */
  private converted(base: string, token: string, ratingKey: string, sessionId: string, mediaIndex = 0) {
    const kbps = this.current.prefs.maxBitrateKbps;
    const resolution = kbps >= 20_000 ? "3840x2160" : kbps === 0 || kbps >= 8_000 ? "1920x1080" : "1280x720";
    return plex.transcodeUrl(base, token, ratingKey, sessionId, kbps, resolution, mediaIndex);
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
    const subtitles = subtitleStreams.some((s) => s.selected);
    const otherSound = audioStreams.length > 1 && !audioStreams[0].selected && audioStreams.some((s) => s.selected);
    const mode = this.current.prefs.playbackMode;
    const asIs = mode === "transcode" ? false : !subtitles && !otherSound && (mode === "direct" || canDirect(playback));
    // A conversion running for the old choice stops; the new one starts afresh.
    if (!p.direct) void plex.stopTranscode(p.base, p.token, p.sessionId);
    const sessionId = randomHex(12);
    const url = asIs ? playback.url : this.converted(p.base, p.token, p.item.ratingKey, sessionId, p.mediaIndex);
    this.set((s) => ({ ...s, playing: { ...p, playback, url, direct: asIs, startMs: positionMs, sessionId } }));
  }

  /** Where playback is, told to the server: what keeps Continue Watching right everywhere. */
  report(positionMs: number, durationMs: number, state: "playing" | "paused" | "stopped", p: Playing | null = this.current.playing) {
    // A trailer isn't something to pick up again.
    if (!p || p.item.type === "clip") return Promise.resolve();
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
    // What was watched shows as watched, and where it was left, on the way back.
    void this.refreshHome();
    const page = this.current.detail;
    if (page?.detail) void this.openDetail(page.detail.ratingKey, page.serverBase, p.item.type === "episode" ? p.item.ratingKey : null);
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
    const libraries = this.current.plex.libraries;
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
      const addable = places ? reely.librariesFor(places, detail.title, detail.inLibraries) : [];
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
    const show = page.title.kind === "show" && page.detail.seasons.length > 0;
    if (show && page.chosen.length === 0) {
      this.setRequestPage(key, { outcome: "Pick at least one season." });
      return;
    }
    const all = show && page.chosen.length === page.detail.seasons.length;
    this.setRequestPage(key, { sending: true, outcome: null });
    try {
      const outcome = await client.request(page.detail.title, show && !all ? page.chosen : null, page.libraryId ?? undefined);
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
      this.store.setJson("xtream", credentials);
      this.setLive({ credentials, account, busy: false });
      await this.loadLive();
    } catch (error) {
      this.setLive({ busy: false, error: readable(error) });
    }
  }

  signOutLive() {
    this.allChannels = null;
    this.store.remove("xtream");
    this.set((s) => ({ ...s, live: { ...emptyLive(), favorites: s.live.favorites } }));
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
    return live.favorites.length ? [FAVORITES, ...live.categories] : live.categories;
  }

  async openCategory(category: XtreamCategory) {
    const c = this.current.live.credentials;
    if (!c) return;
    this.setLive({ category, channels: [], busy: true, error: null });
    try {
      const channels = category.id === FAVORITES.id
        ? (await xtream.liveChannels(c)).filter((ch) => this.current.live.favorites.includes(ch.streamId))
        : await xtream.liveChannels(c, category.id);
      if (this.current.live.category !== category) return;
      this.setLive({ channels, busy: false });
      void this.loadGuide(channels.slice(0, 40));
    } catch (error) {
      this.setLive({ busy: false, error: readable(error) });
    }
  }

  closeCategory() {
    this.setLive({ category: null, channels: [], watching: null });
  }

  /** Now and next for what's on screen, a few at a time so the panel isn't flooded. */
  async loadGuide(channels: XtreamChannel[]) {
    const c = this.current.live.credentials;
    if (!c) return;
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
    if (index < 0 || index >= this.current.live.channels.length) return;
    this.setLive({ watching: index });
  }

  /** Channel up and down, round the list's ends. */
  stepChannel(by: number) {
    const live = this.current.live;
    if (live.watching == null || !live.channels.length) return;
    this.setLive({ watching: (live.watching + by + live.channels.length) % live.channels.length });
  }

  /** A channel by the number on it, as typed on the remote. */
  tuneNumber(number: number): boolean {
    const at = this.current.live.channels.findIndex((ch) => ch.number === number);
    if (at < 0) return false;
    this.setLive({ watching: at });
    return true;
  }

  stopLive() {
    this.setLive({ watching: null });
  }

  /** Where the channel plays from: the provider's HLS, which the TV plays itself. */
  channelUrl(channel: XtreamChannel): string | null {
    const c = this.current.live.credentials;
    return c ? xtream.streamUrl(c, channel, "m3u8") : null;
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
    const servers = p.libraries
      .map((l) => [l.baseUrl, l.token] as const)
      .filter(([base], i, all) => all.findIndex(([b]) => b === base) === i);
    if (!servers.length && p.baseUrl && p.serverToken) servers.push([p.baseUrl, p.serverToken]);
    const [found, channels] = await Promise.all([
      Promise.all(servers.map(([base, token]) => plex.searchAll(base, token, query).catch(() => null))),
      this.channelsMatching(query),
    ]);
    if (run !== this.queryRun) return;
    const answered = found.filter((f): f is plex.PlexFound => f !== null);
    const [matches, others] = split(query, answered.reduce<PlexItem[]>((all, f) => all.concat(f.items), []));
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
        unreachable: servers.length > 0 && answered.length === 0,
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
    this.setLive({ category: null, channels, watching: at });
    void this.loadGuide(channels.slice(0, 12));
  }

  // ---------------------------------------------------------------- A person's and a collection's titles

  /** Everything they're in, from the libraries on the server they were found on. */
  async openPerson(person: PlexPerson) {
    const key = `person:${person.serverBase ?? ""}|${person.id}`;
    const run = ++this.listRun;
    this.set((s) => ({ ...s, list: { key, items: [], busy: true, error: null } }));
    const libraries = this.current.plex.libraries.filter((l) => !person.serverBase || l.baseUrl === person.serverBase);
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
    const base = serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token || !path) return null;
    return plex.imageUrl(base, token, path, width, height);
  }
}
