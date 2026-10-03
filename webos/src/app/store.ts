import * as plex from "../api/plex";
import type { PlexDetail, PlexHomeUser, PlexItem, PlexServer } from "../api/plex";
import { readable } from "../core/http";
import { Store, clientId } from "../core/storage";
import { emptyHome, loadHome, type HomeRows, type LibraryChoice } from "./home";

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
  | { name: "live" }
  | { name: "requests" }
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
}

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
  busy: boolean;
  error: string | null;
}

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
}

const emptyBrowse = (): Browse => ({ choice: null, items: [], total: 0, busy: false, sort: "titleSort:asc", error: null });

export function initialState(): AppState {
  return {
    route: { name: "home" },
    stack: [{ name: "home" }],
    signIn: { code: null, url: null, busy: false, error: null },
    plex: { token: null, user: null, homeUsers: [], servers: [], serverName: null, baseUrl: null, serverToken: null, libraries: [], finding: false, error: null },
    home: emptyHome(),
    homeBusy: false,
    homeError: null,
    browse: { movie: emptyBrowse(), show: emptyBrowse() },
    detail: null,
    askWho: false,
  };
}

export const isConnected = (s: AppState) => !!s.plex.baseUrl && !!s.plex.serverToken;

/** How long a sign-in code is asked about before it's given up as expired. */
const PIN_TRIES = 300;
const PIN_EVERY_MS = 2_000;
const GRID_PAGE = 120;

export class App {
  private current: AppState = initialState();
  private listeners = new Set<(s: AppState) => void>();
  private signInRun = 0;
  private homeRun = 0;

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
  }

  /** Back one page; false when there's nowhere back to go (Home's Back leaves the app). */
  goBack(): boolean {
    const s = this.current;
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
    this.set((s) => ({ ...fresh, route: s.route.name === "settings" ? { name: "home" } : fresh.route }));
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
    this.set((s) => ({ ...s, browse: { ...s.browse, [kind]: { ...s.browse[kind], choice: target, items: [], busy: true, error: null } } }));
    await this.loadMore(kind);
  }

  async setSort(kind: Kind, sort: string) {
    this.set((s) => ({ ...s, browse: { ...s.browse, [kind]: { ...s.browse[kind], sort, items: [], busy: true } } }));
    await this.loadMore(kind);
  }

  /** The next page of the grid, in before it's reached. */
  async loadMore(kind: Kind) {
    const browse = this.current.browse[kind];
    const choice = browse.choice;
    if (!choice) return;
    const offset = browse.items.length;
    const type = kind === "movie" ? plex.TYPE_MOVIE : plex.TYPE_SHOW;
    try {
      const page = await plex.items(choice.baseUrl, choice.token, `/library/sections/${choice.section.key}/all?type=${type}&sort=${browse.sort}`, GRID_PAGE, offset);
      const now = this.current.browse[kind];
      // Not over a different library or order chosen meanwhile.
      if (now.choice !== choice || now.sort !== browse.sort || now.items.length !== offset) return;
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
    this.set((s) => ({ ...s, detail: { key, serverBase: base, detail: null, seasons: [], season: null, episodes: [], focused: null, related: [], busy: true, error: null } }));
    if (!base || !token) {
      this.setDetail(key, { busy: false, error: "Couldn't reach the server this title is on." });
      return;
    }
    try {
      const detail = await plex.detail(base, token, ratingKey);
      if (!detail) throw new Error("That title isn't on the server any more.");
      this.setDetail(key, { detail });
      plex.related(base, token, ratingKey).then((related) => this.setDetail(key, { related }));
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

  /** A picture from the server a title is on, at the size it's drawn. */
  image(serverBase: string | null, path: string | null | undefined, width: number, height: number): string | null {
    const base = serverBase ?? this.current.plex.baseUrl;
    const token = this.tokenFor(base);
    if (!base || !token || !path) return null;
    return plex.imageUrl(base, token, path, width, height);
  }
}
