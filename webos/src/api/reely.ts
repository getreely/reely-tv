/**
 * Reely — the owner's requesting app — as the television reaches it. A port of the
 * Android app's ReelyRequests: signing in is with the Plex account already in use, and
 * Reely's session is a cookie the browser keeps (asked for with credentials included).
 * When it lapses, the next call signs in again rather than failing.
 */
import { fetcher } from "../core/http";
import { stableSort } from "../core/sort";

export interface RequestTitle {
  kind: "movie" | "show";
  tmdbId: number;
  tvdbId: number;
  title: string;
  year?: number;
  overview?: string;
  /** The poster's full address, ready to load. */
  poster?: string;
  /** Reely's own poster value, handed back as it came when the title is requested. */
  posterPath?: string;
}

export const isShowTitle = (title: RequestTitle) => title.kind === "show";

/** Unique across kinds: a film and a show can share a TMDB number. */
export const requestKey = (title: RequestTitle) =>
  `${title.kind}:${title.tmdbId > 0 ? `t${title.tmdbId}` : `v${title.tvdbId}`}`;

export interface RequestRow {
  id: string;
  title: string;
  titles: RequestTitle[];
}

export interface RequestSeason {
  number: number;
  name: string;
  episodes: number;
}

export interface RequestDetail {
  title: RequestTitle;
  backdrop?: string;
  genres: string[];
  runtime?: number;
  status?: string;
  seasons: RequestSeason[];
  inLibrary: boolean;
  inLibraries: number[];
  /**
   * For a show, which seasons each library holding it has been asked for. Null when Reely
   * doesn't say (one older than this), and then a library holding it is taken to have all of it.
   */
  seasonsAsked?: Record<number, number[]> | null;
}

/** The seasons already asked for in [libraryId]: none where it doesn't hold the show. */
export const askedIn = (detail: RequestDetail, libraryId: number | null | undefined): number[] =>
  libraryId != null ? detail.seasonsAsked?.[libraryId] ?? [] : [];

/**
 * Whether [libraryId] has the whole of it, with nothing left to ask for there: a film it
 * holds, or a show with every season asked for. Holding a show is not holding all of it.
 */
export function completeIn(detail: RequestDetail, libraryId: number): boolean {
  if (!detail.inLibraries.includes(libraryId)) return false;
  if (!isShowTitle(detail.title)) return true;
  const asked = detail.seasonsAsked?.[libraryId];
  if (!asked) return true;
  return detail.seasons.every((s) => asked.includes(s.number));
}

/** The libraries holding all of it: where there's nothing left to ask for. */
export const holdingAll = (detail: RequestDetail) => detail.inLibraries.filter((id) => completeIn(detail, id));

/** The seasons that can still be asked for in [libraryId]. */
export const seasonsLeft = (detail: RequestDetail, libraryId: number | null | undefined) => {
  const asked = askedIn(detail, libraryId);
  return detail.seasons.filter((s) => !asked.includes(s.number));
};

export interface RequestLibrary {
  id: number;
  name: string;
  kind: string;
}

export const libraryTakes = (library: RequestLibrary, title: RequestTitle) =>
  library.kind === (isShowTitle(title) ? "shows" : "movies");

export interface RequestPlaces {
  libraries: RequestLibrary[];
  defaultLibraryId: number;
  /** The owner, or an account that adds without asking. */
  adds: boolean;
}

/** The libraries [title] could go to: the right kind, and not holding it already. */
export function librariesFor(places: RequestPlaces, title: RequestTitle, holding: number[]) {
  return places.libraries.filter((it) => libraryTakes(it, title) && !holding.includes(it.id));
}

/** Where it goes unless another is picked: the default, when it's one of them. */
export function preferredLibrary(places: RequestPlaces, among: RequestLibrary[]) {
  return among.find((it) => it.id === places.defaultLibraryId) ?? among[0];
}

export interface RequestRecord {
  id: number;
  title: RequestTitle;
  /** pending, approved or denied. */
  status: string;
  /** Null for the whole show, and always for a film. */
  seasons: number[] | null;
}

/** How Reely marks a poster: Downloading, In library, Partial, or Requested. */
export interface TitleMarks {
  movies: Map<number, string>;
  showsByTmdb: Map<number, string>;
  showsByTvdb: Map<number, string>;
  requested: Set<string>;
}

export const noMarks = (): TitleMarks => ({
  movies: new Map(),
  showsByTmdb: new Map(),
  showsByTvdb: new Map(),
  requested: new Set(),
});

/** Reely's own rule: a show asked for by TheTVDB is still the same show by TMDB. */
export function requestedKeys(title: RequestTitle): string[] {
  const keys: string[] = [];
  if (isShowTitle(title) && title.tvdbId > 0) keys.push(`show-tvdb-${title.tvdbId}`);
  if (title.tmdbId > 0) keys.push(`${title.kind}-${title.tmdbId}`);
  return keys;
}

export function badge(marks: TitleMarks, title: RequestTitle): string | undefined {
  const held = isShowTitle(title)
    ? (title.tvdbId > 0 ? marks.showsByTvdb.get(title.tvdbId) : undefined) ?? marks.showsByTmdb.get(title.tmdbId)
    : marks.movies.get(title.tmdbId);
  if (held) return held;
  return requestedKeys(title).some((it) => marks.requested.has(it)) ? "Requested" : undefined;
}

/** Which of this account's approved requests have arrived; anything in [seen] has been said. */
export function readyRequests(mine: RequestRecord[], marks: TitleMarks, seen: Set<string>): RequestTitle[] {
  const out: RequestTitle[] = [];
  const keys = new Set<string>();
  for (const record of mine) {
    const key = requestKey(record.title);
    if (record.status !== "approved" || seen.has(key) || keys.has(key)) continue;
    const mark = badge(marks, record.title);
    if (mark === "In library" || (isShowTitle(record.title) && mark === "Partial")) {
      keys.add(key);
      out.push(record.title);
    }
  }
  return out;
}

export type RequestOutcome =
  | { kind: "sent"; approved: boolean }
  | { kind: "already" }
  | { kind: "refused"; message: string };

/** Whether a Plex library here has [title], by the outside ids its items carry. */
export function plexHas(title: RequestTitle, movies: Set<string>, shows: Set<string>): boolean {
  if (isShowTitle(title)) {
    return (title.tvdbId > 0 && shows.has(`tvdb://${title.tvdbId}`)) || (title.tmdbId > 0 && shows.has(`tmdb://${title.tmdbId}`));
  }
  return title.tmdbId > 0 && movies.has(`tmdb://${title.tmdbId}`);
}

const TMDB_IMAGES = "https://image.tmdb.org/t/p";

/** Reely's explore rows, in the order they're shown, and what they're called here. */
const ROWS: Array<[string, string]> = [
  ["movies", "Trending Movies"],
  ["shows", "Trending Shows"],
  ["popularMovies", "Popular Movies"],
  ["popularShows", "Popular Shows"],
  ["topMovies", "Top Rated Movies"],
  ["topShows", "Top Rated Shows"],
];

/** Accepts "reely.example.com", "192.168.1.5:8788", "http://…/", and so on. */
export function normalize(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, "");
  if (!trimmed) return trimmed;
  return /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
}

export function isValid(raw: string): boolean {
  try {
    const url = new URL(normalize(raw));
    return (url.protocol === "http:" || url.protocol === "https:") && !!url.hostname;
  } catch {
    return false;
  }
}

export const hostOf = (url: string) => url.replace(/^[a-z]+:\/\//i, "").split("/")[0];

/** TMDB gives a path to put after its image address; TheTVDB gives a whole address. */
export function imageUrl(value: string | undefined | null, image: string, size: string): string | undefined {
  if (!value || !value.trim()) return undefined;
  if (/^http/i.test(value)) return value;
  return `${image.replace(/\/+$/, "")}/${size}/${value.replace(/^\/+/, "")}`;
}

const num = (value: unknown) => (typeof value === "number" && isFinite(value) ? value : Number(value) || 0);
const str = (value: unknown) => (typeof value === "string" ? value : value == null ? "" : String(value));

export function titleOf(o: any, image: string): RequestTitle | undefined {
  if (!o || typeof o !== "object") return undefined;
  const kind = o.kind === "movie" || o.kind === "show" ? o.kind : undefined;
  if (!kind) return undefined;
  const tmdbId = num(o.tmdbId);
  const tvdbId = num(o.tvdbId);
  if (tmdbId <= 0 && tvdbId <= 0) return undefined;
  const title = str(o.title);
  if (!title.trim()) return undefined;
  const poster = str(o.poster).trim() ? str(o.poster) : undefined;
  const year = num(o.year);
  const overview = str(o.overview);
  return {
    kind,
    tmdbId,
    tvdbId,
    title,
    year: year > 0 ? year : undefined,
    overview: overview.trim() ? overview : undefined,
    poster: imageUrl(poster, image, "w342"),
    posterPath: poster,
  };
}

export function titlesOf(array: unknown, image: string): RequestTitle[] {
  if (!Array.isArray(array)) return [];
  const seen = new Set<string>();
  const out: RequestTitle[] = [];
  for (const o of array) {
    const title = titleOf(o, image);
    if (!title || seen.has(requestKey(title))) continue;
    seen.add(requestKey(title));
    out.push(title);
  }
  return out;
}

export function exploreRows(root: any): RequestRow[] {
  const image = str(root?.imageBase) || TMDB_IMAGES;
  const rows: RequestRow[] = [];
  for (const [field, title] of ROWS) {
    const titles = titlesOf(root?.[field], image);
    if (titles.length) rows.push({ id: field, title, titles });
  }
  for (const row of Array.isArray(root?.providers) ? root.providers : []) {
    if (!row || typeof row !== "object") continue;
    const titles = titlesOf(row.results, image);
    if (!titles.length) continue;
    const kind = row.kind === "show" ? "Shows" : "Movies";
    rows.push({ id: `provider:${str(row.key)}:${str(row.kind)}`, title: `${kind} on ${str(row.name)}`, titles });
  }
  return rows;
}

export function parseDetail(root: any, fallback: RequestTitle): RequestDetail {
  const image = str(root?.imageBase) || TMDB_IMAGES;
  const p = root?.preview;
  if (!p || typeof p !== "object") throw new Error("Reely couldn't do that. Try again.");
  const runtime = num(p.runtime);
  const status = str(p.status);
  const held: number[] = Array.isArray(root.inLibraries) ? root.inLibraries.map(num) : [];
  return {
    title: titleOf(p, image) ?? fallback,
    backdrop: imageUrl(str(p.backdrop), image, "w1280"),
    genres: (Array.isArray(p.genres) ? p.genres : []).map(str).filter((it: string) => it.trim()),
    runtime: runtime > 0 ? runtime : undefined,
    status: status.trim() ? status : undefined,
    seasons: (Array.isArray(p.seasons) ? p.seasons : [])
      .filter((it: any) => it && typeof it === "object")
      .map((it: any) => ({
        number: num(it.number),
        name: str(it.name).trim() || `Season ${num(it.number)}`,
        episodes: Array.isArray(it.episodes) ? it.episodes.length : 0,
      }))
      // Specials are season nought; asked for with the rest, not on their own.
      .filter((it: RequestSeason) => it.number > 0),
    inLibrary: held.length > 0,
    inLibraries: held,
    seasonsAsked: Array.isArray(root.seasonsAsked)
      ? Object.fromEntries(root.seasonsAsked.filter((x: any) => x && typeof x === "object")
        .map((x: any) => [num(x.libraryId), Array.isArray(x.seasons) ? x.seasons.map(num) : []]))
      : null,
  };
}

export function parseMarks(movies: any[], shows: any[], open: any[]): TitleMarks {
  const marks = noMarks();
  for (const m of movies) {
    if (!m || num(m.tmdbId) <= 0) continue;
    marks.movies.set(num(m.tmdbId), m.downloading ? "Downloading" : str(m.filePath).trim() ? "In library" : "Requested");
  }
  const showMark = (s: any) =>
    s.downloading ? "Downloading" : num(s.onDisk) === 0 ? "Requested"
    // Every aired episode here. Nothing wanted only means nothing asked for is missing: a
    // show asked for one season of is that season, not all of it.
    : num(s.aired) > 0 && num(s.onDisk) >= num(s.aired) ? "In library" : "Partial";
  for (const s of shows) {
    if (!s) continue;
    if (num(s.tmdbId) > 0) marks.showsByTmdb.set(num(s.tmdbId), showMark(s));
    if (num(s.tvdbId) > 0) marks.showsByTvdb.set(num(s.tvdbId), showMark(s));
  }
  for (const r of open) {
    if (!r) continue;
    const kind = str(r.kind);
    if (kind === "show" && num(r.tvdbId) > 0) marks.requested.add(`show-tvdb-${num(r.tvdbId)}`);
    if (num(r.tmdbId) > 0) marks.requested.add(`${kind}-${num(r.tmdbId)}`);
  }
  return marks;
}

export function parseRecords(root: any): RequestRecord[] {
  const list = Array.isArray(root?.requests) ? root.requests : [];
  const out: RequestRecord[] = [];
  for (const r of list) {
    const title = titleOf(r, TMDB_IMAGES);
    if (!title) continue;
    out.push({ id: num(r.id), title, status: str(r.status), seasons: Array.isArray(r.seasons) ? r.seasons.map(num) : null });
  }
  return stableSort(out, (a, b) => b.id - a.id);
}

/** Reely's errors are {"error": "…"}, written to be shown as they are. */
/**
 * Reely's answer when plex.tv turned down the sign-in this device gave it: the sign-in
 * here was ended by Plex (a password change, or signed out of all devices).
 */
export const PLEX_REJECTED =
  "Plex didn't accept this device's sign-in, so Reely can't sign you in. Sign out of Plex in Settings and sign in again, then connect.";

/** Reely's words for plex.tv refusing the token it was given. */
export function plexRejected(error: string | undefined): boolean {
  return !!error && error.toLowerCase().includes("didn't accept that sign-in");
}

/**
 * Reely couldn't check who the Plex server is shared with: the owner's own Plex sign-in,
 * saved when Plex was linked in Reely, has stopped working. Only the owner signs in without
 * that check, so everybody else is turned away until it's linked again.
 */
export const OWNER_LINK_BROKEN =
  "Reely's link to Plex has stopped working, so it can't check who the server is shared with. The server's owner can fix it in Reely: Settings, Plex, Unlink, then Link my Plex account.";

export function ownerLinkBroken(code: number, error: string | undefined): boolean {
  return code === 502 && !!error && error.toLowerCase().startsWith("plex.tv");
}

/** Reely's own words, but never a page of markup passed on from somewhere else. */
export const readable = (error: string | undefined) => (error && !(error.includes("<") && error.includes(">")) ? error : undefined);

function errorOf(text: string | undefined): string | undefined {
  if (!text) return undefined;
  try {
    const error = JSON.parse(text)?.error;
    return typeof error === "string" && error.trim() ? error : undefined;
  } catch {
    return undefined;
  }
}

export class ReelyRequests {
  readonly base: string;
  private signedIn = false;

  constructor(baseUrl: string, private readonly plexToken: () => string | undefined) {
    this.base = normalize(baseUrl);
  }

  private async send(path: string, init: { method?: string; body?: string } = {}) {
    const controller = typeof AbortController !== "undefined" ? new AbortController() : undefined;
    const timer = controller ? setTimeout(() => controller.abort(), 20_000) : undefined;
    try {
      const response = await fetcher(this.base + path, {
        method: init.method ?? "GET",
        body: init.body,
        headers: init.body ? { "Content-Type": "application/json", Accept: "application/json" } : { Accept: "application/json" },
        credentials: "include",
        signal: controller?.signal,
      });
      return { code: response.status, text: await response.text() };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /** Signs in with the Plex account; the message to show when that can't be done. */
  async signIn(): Promise<string | undefined> {
    const token = this.plexToken();
    if (!token) return "Sign in to Plex first.";
    try {
      const { code, text } = await this.send("/api/v1/auth/plex/token", { method: "POST", body: JSON.stringify({ token }) });
      this.signedIn = code >= 200 && code < 300;
      if (this.signedIn) return undefined;
      const error = errorOf(text);
      if (plexRejected(error)) return PLEX_REJECTED;
      if (ownerLinkBroken(code, error)) return OWNER_LINK_BROKEN;
      switch (code) {
        case 403: return "Your Plex account doesn't have access to this server's requests.";
        case 404: return "That server doesn't sign in from the TV yet. Update Reely.";
        case 412: return "Signing in with Plex isn't set up on this Reely server yet.";
        case 429: return "Too many tries. Wait a minute and try again.";
        default: return readable(error) ?? "Reely couldn't do that. Try again.";
      }
    } catch {
      this.signedIn = false;
      return `Couldn't reach Reely at ${hostOf(this.base)}.`;
    }
  }

  /** Makes a call, signing in first when there's no session, and once more if it has lapsed. */
  private async call(path: string, init: { method?: string; body?: string } = {}) {
    if (!this.signedIn) {
      const problem = await this.signIn();
      if (problem) throw new Error(problem);
    }
    let answer = await this.send(path, init).catch(() => {
      throw new Error(`Couldn't reach Reely at ${hostOf(this.base)}.`);
    });
    if (answer.code === 401) {
      const problem = await this.signIn();
      if (problem) throw new Error(problem);
      answer = await this.send(path, init);
    }
    return answer;
  }

  private async get(path: string): Promise<any> {
    const { code, text } = await this.call(path);
    if (code < 200 || code >= 300) throw new Error(errorOf(text) ?? "Reely couldn't do that. Try again.");
    try {
      return JSON.parse(text);
    } catch {
      throw new Error("Reely couldn't do that. Try again.");
    }
  }

  async explore(): Promise<RequestRow[]> {
    return exploreRows(await this.get("/api/v1/explore"));
  }

  async search(query: string): Promise<RequestTitle[]> {
    if (!query.trim()) return [];
    const root = await this.get(`/api/v1/search?q=${encodeURIComponent(query.trim())}`);
    return titlesOf(root?.results, str(root?.imageBase) || TMDB_IMAGES);
  }

  async detail(title: RequestTitle): Promise<RequestDetail> {
    // A show found through TheTVDB has only that id; Reely looks it up there.
    const path = isShowTitle(title) && title.tmdbId === 0 && title.tvdbId > 0
      ? `/api/v1/preview/show/${title.tvdbId}?src=tvdb`
      : `/api/v1/preview/${title.kind}/${title.tmdbId}`;
    return parseDetail(await this.get(path), title);
  }

  async places(): Promise<RequestPlaces> {
    const user = (await this.get("/api/v1/auth/me"))?.user ?? {};
    const libraries = (await this.get("/api/v1/libraries"))?.libraries;
    return {
      libraries: (Array.isArray(libraries) ? libraries : [])
        .filter((it: any) => it && typeof it === "object")
        .map((it: any) => ({ id: num(it.id), name: str(it.name), kind: str(it.kind) })),
      defaultLibraryId: num(user.defaultLibraryId),
      adds: user.role === "admin" || user.mayAdd === true,
    };
  }

  /** Best effort: each list on its own, so one that can't be had leaves the others' marks. */
  async marks(): Promise<TitleMarks> {
    const list = async (path: string, field: string): Promise<any[]> => {
      try {
        const value = (await this.get(path))?.[field];
        return Array.isArray(value) ? value : [];
      } catch {
        return [];
      }
    };
    const [movies, shows, open] = await Promise.all([
      list("/api/v1/movies", "movies"),
      list("/api/v1/shows", "shows"),
      list("/api/v1/requests", "requests"),
    ]);
    return parseMarks(movies, shows, open);
  }

  async myRequests(): Promise<RequestRecord[]> {
    return parseRecords(await this.get("/api/v1/requests?mine=1"));
  }

  async request(title: RequestTitle, seasons: number[] | null, libraryId?: number): Promise<RequestOutcome> {
    const body: Record<string, unknown> = {
      kind: title.kind,
      tmdbId: title.tmdbId,
      tvdbId: title.tvdbId,
      title: title.title,
      year: title.year ?? 0,
      poster: title.posterPath ?? "",
    };
    if (seasons) body.seasons = seasons;
    if (libraryId != null) body.libraryId = libraryId;
    const { code, text } = await this.call("/api/v1/requests", { method: "POST", body: JSON.stringify(body) });
    if (code >= 200 && code < 300) {
      let approved = false;
      try {
        approved = JSON.parse(text)?.status === "approved";
      } catch {
        // Recorded either way.
      }
      return { kind: "sent", approved };
    }
    if (code === 409) return { kind: "already" };
    return { kind: "refused", message: errorOf(text) ?? "Reely couldn't take that request. Try again." };
  }
}

/**
 * What a poster says, as the Fire TV's Requests has it: in Plex already, else Reely's own
 * word, else where this account's ask has got to.
 */
export function badgeFor(
  title: RequestTitle,
  marks: TitleMarks,
  mine: RequestRecord[],
  plexMovies: Set<string>,
  plexShows: Set<string>,
): string | undefined {
  const mark = badge(marks, title);
  if ((mark == null || mark === "Requested") && plexHas(title, plexMovies, plexShows)) return "In library";
  if (mark != null && mark !== "Requested") return mark;
  const status = mine.find((r) => requestKey(r.title) === requestKey(title))?.status;
  if (status === "approved") return "Approved";
  if (status === "denied") return "Declined";
  if (status === "pending") return "Requested";
  return mark;
}

/** The browsing rows less what's in the library already; a row left empty isn't shown. */
export function shownRows(rows: RequestRow[], badgeOf: (t: RequestTitle) => string | undefined): RequestRow[] {
  const out: RequestRow[] = [];
  for (const row of rows) {
    const titles = row.titles.filter((t) => badgeOf(t) !== "In library");
    if (titles.length) out.push({ ...row, titles });
  }
  return out;
}
