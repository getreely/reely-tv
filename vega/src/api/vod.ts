import { askJson } from "../core/http";
import { stableSort } from "../core/sort";
import { relevant, words } from "../core/searchMatch";
import type { PlexDetail, PlexIndexEntry, PlexItem, PlexRole } from "./plex";
import { isWatched } from "./plex";
import type { XtreamCategory, XtreamCredentials } from "./xtream";

/*
 * The IPTV provider's films and series, as the Android app has them (XtreamVod.kt,
 * VodItems.kt, IptvLibrary.kt): read, tidied, matched with Plex, and shown as the same
 * items as Plex's, marked IPTV, with where each was left kept on the TV.
 */

export const IPTV_SOURCE = "iptv:";
export const isIptv = (item: Pick<PlexItem, "serverBase">) => item.serverBase === IPTV_SOURCE;
export const sourceTag = (item: Pick<PlexItem, "serverBase">) => (isIptv(item) ? "IPTV" : null);

export interface VodTitle {
  series: boolean;
  id: number;
  name: string;
  year: number | null;
  tag: string | null;
  poster: string | null;
  rating: number | null;
  addedAt: number;
  categoryId: string | null;
  tmdbId: string | null;
  extension: string | null;
  plot: string | null;
  genre: string | null;
  key: string;
}

export interface VodCatalog {
  movies: VodTitle[];
  series: VodTitle[];
  movieCategories: XtreamCategory[];
  seriesCategories: XtreamCategory[];
  loadedAt: number;
}

export const emptyCatalog = (): VodCatalog => ({ movies: [], series: [], movieCategories: [], seriesCategories: [], loadedAt: 0 });

export interface VodInfo {
  name: string | null;
  plot: string | null;
  cast: string[];
  directors: string[];
  genres: string[];
  durationMs: number;
  backdrop: string | null;
  poster: string | null;
  releaseDate: string | null;
  rating: number | null;
  tmdbId: string | null;
  extension: string | null;
  ageRating: string | null;
}

export interface VodEpisode {
  id: number;
  season: number;
  number: number | null;
  title: string;
  extension: string | null;
  plot: string | null;
  still: string | null;
  durationMs: number;
  airDate: string | null;
}

export interface VodSeason {
  number: number;
  name: string;
  poster: string | null;
  episodes: VodEpisode[];
}

export interface SeriesInfo extends Omit<VodInfo, "durationMs" | "extension" | "ageRating"> {
  seasons: VodSeason[];
}

// ---------------------------------------------------------------- Names

const BRACKETED = /^\s*[[|(]\s*([A-Za-z0-9+ ]{1,8})\s*[\]|)]\s*[-:|]?\s*/;
const BARE = /^\s*([A-Z]{2}|4K|UHD|FHD|HD|SD|HEVC|VIP|MULTI|NF|AMZ|DSNP|ATV|HBO|D\+)\s*[-:|]\s+/;
const TRAILING_YEAR = /\s*(?:[([]\s*((?:19|20)\d{2})\s*[)\]]|[-–]\s*((?:19|20)\d{2}))\s*$/;

/** "EN - The Matrix (1999)" as title, year and tag. */
export function parseName(raw: string, knownYear: number | null = null): { name: string; year: number | null; tag: string | null } {
  let name = raw.trim();
  let tag: string | null = null;
  const found = BRACKETED.exec(name) ?? BARE.exec(name);
  if (found) {
    const rest = name.slice(found[0].length).trim();
    if (rest) {
      tag = found[1].trim().toUpperCase();
      name = rest;
    }
  }
  let year = knownYear;
  const y = TRAILING_YEAR.exec(name);
  if (y) {
    const rest = name.slice(0, y.index).trim();
    if (rest) {
      year = year ?? Number(y[1] || y[2]);
      name = rest;
    }
  }
  return { name: name || raw.trim(), year, tag };
}

export const nameKey = (name: string) => words(name).join("");

export function sortName(name: string): string | null {
  const m = /^(the|a|an)\s+/i.exec(name);
  if (!m) return null;
  const rest = name.slice(m[0].length);
  return rest.trim() ? rest : null;
}

// ---------------------------------------------------------------- Reading the provider

/** A whole number written out, and nothing else: "12" but not "12abc" or "". */
function intOf(v: unknown): number | null {
  const t = v == null ? "" : String(v).trim();
  return /^-?\d+$/.test(t) ? Number(t) : null;
}

function api(c: XtreamCredentials, action: string, extras: Record<string, string> = {}): string {
  const q = new URLSearchParams({ username: c.username, password: c.password, action, ...extras });
  return `${c.base}/player_api.php?${q.toString()}`;
}

export async function catalog(c: XtreamCredentials): Promise<VodCatalog> {
  if (c.playlistUrl) throw new Error("Films and series need an Xtream login, not a playlist.");
  const categories = async (action: string): Promise<XtreamCategory[]> => {
    try {
      const list = await askJson<any[]>(api(c, action));
      const seen = new Set<string>();
      return (Array.isArray(list) ? list : [])
        .filter(isObject)
        .map((x) => ({ id: String(x.category_id ?? ""), name: String(x.category_name || "Unnamed") }))
        .filter((x) => x.id && !seen.has(x.id) && !!seen.add(x.id));
    } catch {
      return [];
    }
  };
  const failure = "Your provider couldn't send its films and series. Try again.";
  const [movieCategories, seriesCategories, movies, series] = await Promise.all([
    categories("get_vod_categories"),
    categories("get_series_categories"),
    askJson<any[]>(api(c, "get_vod_streams"), { failure, timeoutMs: 180_000 }).then((l) => readTitles(l, false)),
    askJson<any[]>(api(c, "get_series"), { failure, timeoutMs: 180_000 }).then((l) => readTitles(l, true)),
  ]);
  return { movies, series, movieCategories, seriesCategories, loadedAt: Date.now() };
}

export function readTitles(list: unknown, series: boolean): VodTitle[] {
  if (!Array.isArray(list)) return [];
  const seen = new Set<number>();
  const out: VodTitle[] = [];
  for (const entry of list) {
    if (!entry || typeof entry !== "object") continue;
    const t = titleOf(entry as Record<string, unknown>, series);
    if (t && !seen.has(t.id)) {
      seen.add(t.id);
      out.push(t);
    }
  }
  return out;
}

export function titleOf(f: Record<string, unknown>, series: boolean): VodTitle | null {
  const s = (k: string) => (f[k] == null ? "" : String(f[k]));
  const id = intOf(s(series ? "series_id" : "stream_id"));
  if (id == null) return null;
  const raw = s("name").trim() || s("title");
  if (!raw.trim()) return null;
  // The list's year when it's a number; else the release date's.
  const listedYear = intOf(s("year").slice(0, 4)) ?? intOf((s("releaseDate") || s("release_date")).slice(0, 4));
  const parsed = parseName(raw, listedYear != null && listedYear >= 1900 && listedYear <= 2100 ? listedYear : null);
  const poster = s(series ? "cover" : "stream_icon");
  const rating = Number.parseFloat(s("rating"));
  const tmdb = s("tmdb") || s("tmdb_id");
  return {
    series,
    id,
    name: parsed.name,
    year: parsed.year,
    tag: parsed.tag,
    poster: poster.startsWith("http") ? poster : null,
    rating: rating > 0 ? rating : null,
    addedAt: Number.parseInt(s(series ? "last_modified" : "added"), 10) || 0,
    categoryId: s("category_id").trim() || null,
    tmdbId: tmdb.trim() && tmdb !== "0" ? tmdb.trim() : null,
    extension: s("container_extension").trim() || null,
    plot: s("plot").trim() || null,
    genre: s("genre").trim() || null,
    key: nameKey(parsed.name),
  };
}

export async function movieInfo(c: XtreamCredentials, id: number): Promise<VodInfo | null> {
  try {
    return parseMovieInfo(await askJson(api(c, "get_vod_info", { vod_id: String(id) })));
  } catch {
    return null;
  }
}

export function parseMovieInfo(root: any): VodInfo | null {
  if (!root || typeof root !== "object") return null;
  const info = isObject(root.info) ? root.info : {};
  const movie = isObject(root.movie_data) ? root.movie_data : {};
  return {
    name: text(info.name) ?? text(movie.name),
    plot: text(info.plot) ?? text(info.description),
    cast: names(info.cast).length ? names(info.cast) : names(info.actors),
    directors: names(info.director),
    genres: names(info.genre),
    durationMs: ((Number(info.duration_secs) || durationOf(text(info.duration))) ?? 0) * 1000,
    backdrop: firstUrl(info.backdrop_path),
    poster: url(info.movie_image) ?? url(info.cover_big),
    releaseDate: text(info.releasedate) ?? text(info.release_date),
    rating: Number(info.rating) > 0 ? Number(info.rating) : null,
    tmdbId: text(info.tmdb_id) && text(info.tmdb_id) !== "0" ? text(info.tmdb_id) : null,
    extension: text(movie.container_extension),
    ageRating: text(info.age) ?? text(info.mpaa),
  };
}

export async function seriesInfo(c: XtreamCredentials, id: number): Promise<SeriesInfo | null> {
  try {
    return parseSeriesInfo(await askJson(api(c, "get_series_info", { series_id: String(id) })));
  } catch {
    return null;
  }
}

export function parseSeriesInfo(root: any): SeriesInfo | null {
  if (!root || typeof root !== "object") return null;
  const info = isObject(root.info) ? root.info : {};
  const episodes: VodEpisode[] = [];
  const readSeason = (key: string | null, list: any[]) => {
    for (const e of list) {
      if (!isObject(e)) continue;
      const id = intOf(e.id);
      if (id == null) continue;
      const d = isObject(e.info) ? e.info : {};
      // Season 0 is the specials: a number, not a missing one.
      const season = intOf(e.season) ?? intOf(key) ?? 1;
      episodes.push({
        id,
        season,
        number: intOf(e.episode_num),
        title: text(e.title) ? parseName(text(e.title)!).name : `Episode ${e.episode_num ?? ""}`,
        extension: text(e.container_extension),
        plot: text(d.plot),
        still: url(d.movie_image),
        durationMs: ((Number(d.duration_secs) || durationOf(text(d.duration))) ?? 0) * 1000,
        airDate: text(d.releasedate) ?? text(d.air_date),
      });
    }
  };
  const all = root.episodes;
  if (Array.isArray(all)) all.forEach((l: any) => Array.isArray(l) && readSeason(null, l));
  else if (isObject(all)) Object.keys(all).forEach((k) => Array.isArray(all[k]) && readSeason(k, all[k]));
  const listed: any[] = Array.isArray(root.seasons) ? root.seasons : [];
  const about = new Map(listed.filter(isObject).map((s: any) => [intOf(s.season_number), s]));
  const seen = new Set<number>();
  const unique = episodes.filter((e) => !seen.has(e.id) && !!seen.add(e.id));
  const numbers = stableSort(Array.from(new Set(unique.map((e) => e.season))), (a, b) => a - b);
  return {
    name: text(info.name),
    plot: text(info.plot),
    cast: names(info.cast),
    directors: names(info.director),
    genres: names(info.genre),
    backdrop: firstUrl(info.backdrop_path),
    poster: url(info.cover),
    releaseDate: text(info.releaseDate) ?? text(info.release_date),
    rating: Number(info.rating) > 0 ? Number(info.rating) : null,
    tmdbId: (text(info.tmdb) !== "0" && text(info.tmdb)) || (text(info.tmdb_id) !== "0" && text(info.tmdb_id)) || null,
    seasons: numbers.map((n) => ({
      number: n,
      name: n === 0 ? "Specials" : `Season ${n}`,
      poster: url(about.get(n)?.cover_big) ?? url(about.get(n)?.cover),
      episodes: stableSort(unique.filter((e) => e.season === n), (a, b) => (a.number ?? Infinity) - (b.number ?? Infinity) || a.id - b.id),
    })),
  };
}

export const movieUrl = (c: XtreamCredentials, id: number, ext: string | null) =>
  `${c.base}/movie/${encodeURIComponent(c.username)}/${encodeURIComponent(c.password)}/${id}.${ext ?? "mp4"}`;
export const episodeUrl = (c: XtreamCredentials, id: number, ext: string | null) =>
  `${c.base}/series/${encodeURIComponent(c.username)}/${encodeURIComponent(c.password)}/${id}.${ext ?? "mp4"}`;

// ---------------------------------------------------------------- Keys and items

export type IptvKey =
  | { kind: "movie"; id: number; extension: string | null }
  | { kind: "show"; id: number }
  | { kind: "season"; showId: number; number: number }
  | { kind: "episode"; id: number; extension: string | null };

export const keyOf = {
  movie: (id: number, ext: string | null) => `m${id}${ext ? "." + ext : ""}`,
  show: (id: number) => `s${id}`,
  season: (show: number, n: number) => `s${show}:${n}`,
  episode: (id: number, ext: string | null) => `e${id}${ext ? "." + ext : ""}`,
};

export function parseKey(key: string): IptvKey | null {
  if (key.length < 2) return null;
  const body = key.slice(1);
  const idExt = () => {
    const [id, ...rest] = body.split(".");
    const n = Number.parseInt(id, 10);
    return /^\d+$/.test(id) ? { id: n, extension: rest.join(".") || null } : null;
  };
  switch (key[0]) {
    case "m": {
      const v = idExt();
      return v ? { kind: "movie", ...v } : null;
    }
    case "e": {
      const v = idExt();
      return v ? { kind: "episode", ...v } : null;
    }
    case "s": {
      if (body.includes(":")) {
        const [s, n] = body.split(":");
        return /^\d+$/.test(s) && /^\d+$/.test(n) ? { kind: "season", showId: +s, number: +n } : null;
      }
      return /^\d+$/.test(body) ? { kind: "show", id: +body } : null;
    }
  }
  return null;
}

function blank(ratingKey: string, title: string, type: string): PlexItem {
  return {
    ratingKey, title, type, thumb: null, art: null, summary: null, year: null, index: null, parentIndex: null,
    parentRatingKey: null, parentTitle: null, grandparentRatingKey: null, grandparentTitle: null, grandparentThumb: null,
    durationMs: 0, viewOffsetMs: 0, leafCount: 0, viewedLeafCount: 0, viewCount: 0, addedAt: 0, lastViewedAt: 0,
    qualities: [], librarySectionId: null, serverBase: IPTV_SOURCE,
  };
}

export function itemOf(t: VodTitle): PlexItem {
  return {
    ...blank(t.series ? keyOf.show(t.id) : keyOf.movie(t.id, t.extension), t.name, t.series ? "show" : "movie"),
    thumb: t.poster,
    summary: t.plot,
    year: t.year,
    addedAt: t.addedAt,
    qualities: t.tag ? [t.tag] : [],
    librarySectionId: t.categoryId,
    titleSort: sortName(t.name),
  };
}

export function seasonItems(showId: number, showName: string, poster: string | null, info: SeriesInfo): PlexItem[] {
  return info.seasons.map((s) => ({
    ...blank(keyOf.season(showId, s.number), s.name, "season"),
    thumb: s.poster ?? poster,
    index: s.number,
    parentRatingKey: keyOf.show(showId),
    parentTitle: showName,
    leafCount: s.episodes.length,
  }));
}

export function episodeItems(showId: number, showName: string, poster: string | null, backdrop: string | null, season: VodSeason): PlexItem[] {
  return season.episodes.map((e) => ({
    ...blank(keyOf.episode(e.id, e.extension), e.title, "episode"),
    thumb: e.still ?? poster,
    art: backdrop,
    summary: e.plot,
    index: e.number,
    parentIndex: season.number > 0 ? season.number : null,
    parentRatingKey: keyOf.season(showId, season.number),
    parentTitle: season.name,
    grandparentRatingKey: keyOf.show(showId),
    grandparentTitle: showName,
    grandparentThumb: poster,
    durationMs: e.durationMs,
    airDate: e.airDate,
  }));
}

export function detailOf(key: string, title: VodTitle | null, info: VodInfo | SeriesInfo | null, show: boolean): PlexDetail {
  const series = info && "seasons" in info ? (info as SeriesInfo) : null;
  const movie = info && !("seasons" in info) ? (info as VodInfo) : null;
  return {
    ratingKey: key,
    type: show ? "show" : "movie",
    title: title?.name ?? (info?.name ? parseName(info.name).name : show ? "Series" : "Film"),
    summary: info?.plot ?? title?.plot ?? null,
    tagline: null,
    year: title?.year ?? (Number.parseInt(info?.releaseDate?.slice(0, 4) ?? "", 10) || null),
    durationMs: movie?.durationMs ?? 0,
    viewOffsetMs: 0,
    contentRating: movie?.ageRating ?? null,
    rating: info?.rating ?? title?.rating ?? null,
    audienceRating: null,
    airDate: info?.releaseDate ?? null,
    viewCount: 0,
    viewedLeafCount: 0,
    studio: null,
    thumb: info?.poster ?? title?.poster ?? null,
    art: info?.backdrop ?? null,
    theme: null,
    genres: info?.genres.length ? info.genres : title?.genre ? [title.genre] : [],
    directors: info?.directors ?? [],
    roles: (info?.cast ?? []).slice(0, 24).map((name): PlexRole => ({ name, role: null, thumb: null, id: null })),
    writers: [],
    childCount: series?.seasons.length ?? 0,
    leafCount: series ? series.seasons.reduce((n, s) => n + s.episodes.length, 0) : 0,
    grandparentTitle: null,
    index: null,
    parentIndex: null,
    logo: null,
    qualities: title?.tag ? [title.tag] : [],
    versions: [],
    guid: null,
    onDeckKey: null,
    onDeckSeasonKey: null,
  };
}

// ---------------------------------------------------------------- Where things were left

export interface IptvMark {
  offsetMs: number;
  durationMs: number;
  watched: boolean;
  /** Epoch seconds. */
  at: number;
  item: PlexItem;
}

export interface KeyValue {
  get(key: string): string | null;
  set(key: string, value: string): void;
}

/** What's been watched from the provider, kept on this TV: the provider keeps nothing. */
export class IptvWatch {
  private marks: Map<string, IptvMark>;
  private counts: Map<string, number> | null = null;

  constructor(private storage: KeyValue, private storageKey: string, private now: () => number = () => Math.floor(Date.now() / 1000)) {
    this.marks = new Map();
    try {
      const list = JSON.parse(storage.get(storageKey) ?? "[]") as IptvMark[];
      for (const m of list) if (m?.item?.ratingKey) this.marks.set(m.item.ratingKey, m);
    } catch {
      /* nothing kept */
    }
  }

  mark(key: string) {
    return this.marks.get(key) ?? null;
  }

  progress(item: PlexItem, positionMs: number, durationMs: number) {
    if (positionMs <= 0) return;
    const done = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION;
    this.put(item, { offsetMs: done ? 0 : positionMs, durationMs, watched: done || this.marks.get(item.ratingKey)?.watched === true, at: this.now(), item });
    this.save();
  }

  setWatched(items: PlexItem[], watched: boolean) {
    for (const item of items) {
      const before = this.marks.get(item.ratingKey);
      this.put(item, { offsetMs: 0, durationMs: before?.durationMs ?? item.durationMs, watched, at: watched ? this.now() : before?.at ?? this.now(), item });
    }
    this.save();
  }

  forgetProgress(key: string) {
    const m = this.marks.get(key);
    if (!m) return;
    this.marks.set(key, { ...m, offsetMs: 0 });
    this.save();
  }

  apply(item: PlexItem): PlexItem {
    if (item.type === "show" || item.type === "season") {
      const watched = this.watchedUnder(item.ratingKey, item.type === "season");
      return watched === item.viewedLeafCount ? item : { ...item, viewedLeafCount: watched };
    }
    const m = this.marks.get(item.ratingKey);
    if (!m) return item;
    return {
      ...item,
      viewOffsetMs: m.offsetMs,
      viewCount: m.watched ? Math.max(1, item.viewCount) : 0,
      durationMs: item.durationMs > 0 ? item.durationMs : m.durationMs,
      lastViewedAt: m.at,
    };
  }

  watchedUnder(key: string, season: boolean): number {
    if (!this.counts) {
      const counts = new Map<string, number>();
      this.marks.forEach((m) => {
        if (!m.watched) return;
        if (m.item.grandparentRatingKey) counts.set("show|" + m.item.grandparentRatingKey, (counts.get("show|" + m.item.grandparentRatingKey) ?? 0) + 1);
        if (m.item.parentRatingKey) counts.set("season|" + m.item.parentRatingKey, (counts.get("season|" + m.item.parentRatingKey) ?? 0) + 1);
      });
      this.counts = counts;
    }
    return this.counts.get((season ? "season|" : "show|") + key) ?? 0;
  }

  continueWatching(): PlexItem[] {
    return stableSort(Array.from(this.marks.values()).filter((m) => m.offsetMs > 0 && !m.watched), (a, b) => b.at - a.at)
      .map((m) => this.apply(m.item));
  }

  private put(item: PlexItem, mark: IptvMark) {
    this.counts = null;
    this.marks.delete(item.ratingKey);
    this.marks.set(item.ratingKey, { ...mark, item: { ...item, viewOffsetMs: 0, viewCount: 0, lastViewedAt: 0 } });
    while (this.marks.size > 3000) this.marks.delete(this.marks.keys().next().value!);
  }

  private save() {
    try {
      this.storage.set(this.storageKey, JSON.stringify(Array.from(this.marks.values())));
    } catch {
      /* full: what's watched next time is still kept in memory */
    }
  }
}

export const WATCHED_FRACTION = 0.9;

// ---------------------------------------------------------------- Matching with Plex

export class TitleIndex {
  private constructor(private tmdb: Set<string>, private named: Set<string>, private names: Set<string>) {}

  static readonly EMPTY = new TitleIndex(new Set(), new Set(), new Set());

  has(tmdbId: string | null, key: string, year: number | null): boolean {
    if (tmdbId && this.tmdb.has(tmdbId)) return true;
    if (!key) return false;
    return year != null ? this.named.has(`${key}|${year}`) : this.names.has(key);
  }

  get isEmpty() {
    return this.tmdb.size === 0 && this.names.size === 0;
  }

  static ofPlex(entries: PlexIndexEntry[]): TitleIndex {
    const tmdb = new Set<string>(), named = new Set<string>(), names = new Set<string>();
    for (const e of entries) {
      const id = e.guids.map(tmdbOf).find((x) => x);
      if (id) tmdb.add(id);
      for (const t of [e.title, e.originalTitle]) {
        if (!t) continue;
        const key = nameKey(t);
        if (!key) continue;
        names.add(key);
        if (e.year != null) for (let y = e.year - 1; y <= e.year + 1; y++) named.add(`${key}|${y}`);
      }
    }
    return new TitleIndex(tmdb, named, names);
  }

  static ofIptv(titles: VodTitle[]): TitleIndex {
    const tmdb = new Set<string>(), named = new Set<string>(), names = new Set<string>();
    for (const t of titles) {
      if (t.tmdbId) tmdb.add(t.tmdbId);
      if (!t.key) continue;
      names.add(t.key);
      if (t.year != null) for (let y = t.year - 1; y <= t.year + 1; y++) named.add(`${t.key}|${y}`);
    }
    return new TitleIndex(tmdb, named, names);
  }
}

export const tmdbOf = (guid: string) => (guid.startsWith("tmdb://") && guid.length > 7 ? guid.slice(7) : null);

// ---------------------------------------------------------------- The library

export type Sort = "TITLE" | "ADDED" | "RELEASED" | "OLDEST" | "RATED" | "AUDIENCE" | "WATCHED";

export interface BrowseOptions {
  sort: Sort;
  categoryId: string | null;
  unwatchedOnly: boolean;
}

export interface IptvGrid {
  items: PlexItem[];
  categories: XtreamCategory[];
  letters: { letter: string; count: number }[];
}

/** The rail letter a title goes under: "Émile" under E, "2012" under #. */
export const letterOf = (item: PlexItem) => {
  const first = (item.titleSort ?? item.title).trim().charAt(0).normalize("NFD").charAt(0).toUpperCase();
  return first >= "A" && first <= "Z" ? first : "#";
};

/** Where a letter's titles begin in a grid sorted by title; -1 when it has none. */
export function letterStart(grid: Pick<IptvGrid, "letters">, letter: string): number {
  let at = 0;
  for (const l of grid.letters) {
    if (l.letter === letter) return at;
    at += l.count;
  }
  return -1;
}

/** The provider's catalogue, and every view of it the screens ask for. */
export class IptvLibrary {
  catalog: VodCatalog = emptyCatalog();
  watch: IptvWatch | null = null;
  private moviesById = new Map<number, VodTitle>();
  private seriesById = new Map<number, VodTitle>();
  private iptvMovies = TitleIndex.EMPTY;
  private iptvShows = TitleIndex.EMPTY;
  private plexMovies = TitleIndex.EMPTY;
  private plexShows = TitleIndex.EMPTY;
  private plexEntries = new Map<string, PlexIndexEntry>();
  private seriesCache = new Map<number, SeriesInfo>();

  setCatalog(next: VodCatalog) {
    this.catalog = next;
    this.moviesById = new Map(next.movies.map((t) => [t.id, t]));
    this.seriesById = new Map(next.series.map((t) => [t.id, t]));
    this.iptvMovies = TitleIndex.ofIptv(next.movies);
    this.iptvShows = TitleIndex.ofIptv(next.series);
  }

  setPlex(movies: PlexIndexEntry[], shows: PlexIndexEntry[]) {
    this.plexMovies = TitleIndex.ofPlex(movies);
    this.plexShows = TitleIndex.ofPlex(shows);
    this.plexEntries = new Map([...movies, ...shows].map((e) => [(e.serverBase ?? "") + "|" + e.ratingKey, e]));
  }

  clear() {
    this.setCatalog(emptyCatalog());
    this.seriesCache.clear();
    this.watch = null;
  }

  movie = (id: number) => this.moviesById.get(id) ?? null;
  series = (id: number) => this.seriesById.get(id) ?? null;
  cachedSeries = (id: number) => {
    const info = this.seriesCache.get(id);
    if (!info) return null;
    // Most recently looked at last, so the one let go is the one longest unseen.
    this.seriesCache.delete(id);
    this.seriesCache.set(id, info);
    return info;
  };
  keepSeries(id: number, info: SeriesInfo) {
    this.seriesCache.delete(id);
    this.seriesCache.set(id, info);
    if (this.seriesCache.size > 12) this.seriesCache.delete(this.seriesCache.keys().next().value!);
  }

  marked = (item: PlexItem) => (isIptv(item) && this.watch ? this.watch.apply(item) : item);

  shown(movies: boolean, iptvWins: boolean): VodTitle[] {
    const titles = movies ? this.catalog.movies : this.catalog.series;
    if (iptvWins) return titles;
    const plex = movies ? this.plexMovies : this.plexShows;
    return plex.isEmpty ? titles : titles.filter((t) => !plex.has(t.tmdbId, t.key, t.year));
  }

  hides(item: PlexItem, iptvWins: boolean): boolean {
    if (!iptvWins || isIptv(item)) return false;
    const index = item.type === "movie" ? this.iptvMovies : item.type === "show" ? this.iptvShows : null;
    if (!index) return false;
    const entry = this.plexEntries.get((item.serverBase ?? "") + "|" + item.ratingKey);
    const tmdb = entry?.guids.map(tmdbOf).find((x) => x) ?? null;
    return index.has(tmdb, nameKey(item.title), item.year);
  }

  browse(movies: boolean, options: BrowseOptions, iptvWins: boolean): IptvGrid {
    const all = this.shown(movies, iptvWins);
    const titles = options.categoryId ? all.filter((t) => t.categoryId === options.categoryId) : all;
    const rating = new Map(titles.map((t) => [t.id, t.rating ?? 0]));
    let items = titles.map((t) => this.marked(itemOf(t)));
    if (options.unwatchedOnly) items = items.filter((i) => !isWatched(i));
    const idOf = (i: PlexItem) => {
      const k = parseKey(i.ratingKey);
      return k && (k.kind === "movie" || k.kind === "show") ? k.id : -1;
    };
    const by = {
      TITLE: (a: PlexItem, b: PlexItem) => {
        const la = letterOf(a), lb = letterOf(b);
        if (la !== lb) return la === "#" ? -1 : lb === "#" ? 1 : la < lb ? -1 : 1;
        return (a.titleSort ?? a.title).localeCompare(b.titleSort ?? b.title, "en", { sensitivity: "base" });
      },
      ADDED: (a: PlexItem, b: PlexItem) => b.addedAt - a.addedAt,
      RELEASED: (a: PlexItem, b: PlexItem) => (b.year ?? 0) - (a.year ?? 0),
      OLDEST: (a: PlexItem, b: PlexItem) => (a.year ?? Infinity) - (b.year ?? Infinity),
      RATED: (a: PlexItem, b: PlexItem) => (rating.get(idOf(b)) ?? 0) - (rating.get(idOf(a)) ?? 0),
      AUDIENCE: (a: PlexItem, b: PlexItem) => (rating.get(idOf(b)) ?? 0) - (rating.get(idOf(a)) ?? 0),
      WATCHED: (a: PlexItem, b: PlexItem) => b.lastViewedAt - a.lastViewedAt,
    }[options.sort];
    items = stableSort(items, by);
    const used = new Set(all.map((t) => t.categoryId).filter(Boolean));
    const categories = (movies ? this.catalog.movieCategories : this.catalog.seriesCategories).filter((c) => used.has(c.id));
    const letters: { letter: string; count: number }[] = [];
    if (options.sort === "TITLE") {
      // The title order keeps each letter's titles together ("#" first), so the counts
      // add up to where each letter starts.
      const counts = new Map<string, number>();
      for (const item of items) counts.set(letterOf(item), (counts.get(letterOf(item)) ?? 0) + 1);
      counts.forEach((count, letter) => letters.push({ letter, count }));
    }
    return { items, categories, letters };
  }

  newest(movies: boolean, iptvWins: boolean, count = 40): PlexItem[] {
    return stableSort(this.shown(movies, iptvWins), (a, b) => b.addedAt - a.addedAt)
      .slice(0, count)
      .map((t) => this.marked(itemOf(t)));
  }

  search(query: string, iptvWins: boolean, count = 40): PlexItem[] {
    const wanted = words(query);
    if (!wanted.length) return [];
    const candidates: PlexItem[] = [];
    for (const t of [...this.shown(true, iptvWins), ...this.shown(false, iptvWins)]) {
      if (wanted.every((w) => t.key.includes(w))) {
        candidates.push(this.marked(itemOf(t)));
        if (candidates.length >= count * 4) break;
      }
    }
    return relevant(query, candidates).slice(0, count);
  }

  related(item: PlexItem, iptvWins: boolean, count = 40): PlexItem[] {
    const k = parseKey(item.ratingKey);
    if (!k || (k.kind !== "movie" && k.kind !== "show")) return [];
    const movies = k.kind === "movie";
    const own = movies ? this.moviesById.get(k.id) : this.seriesById.get(k.id);
    if (!own?.categoryId) return [];
    return stableSort(
      this.shown(movies, iptvWins).filter((t) => t.categoryId === own.categoryId && t.id !== k.id),
      (a, b) => b.addedAt - a.addedAt,
    )
      .slice(0, count)
      .map((t) => this.marked(itemOf(t)));
  }
}


// ---------------------------------------------------------------- Bits

function isObject(v: unknown): v is Record<string, any> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}

function text(v: unknown): string | null {
  const t = v == null ? "" : String(v).trim();
  return t && t !== "null" ? t : null;
}

function url(v: unknown): string | null {
  const t = text(v);
  return t && t.startsWith("http") ? t : null;
}

function names(v: unknown): string[] {
  const t = text(v);
  if (!t) return [];
  return Array.from(new Set(t.split(/[,/]/).map((s) => s.trim()).filter(Boolean)));
}

function firstUrl(v: unknown): string | null {
  if (Array.isArray(v)) return v.map(String).find((s) => s.startsWith("http")) ?? null;
  return url(v);
}

function durationOf(t: string | null): number | null {
  if (!t) return null;
  const parts = t.split(":").map((p) => Number(p.trim()));
  if (parts.some(isNaN)) return null;
  const total = parts.reduce((sum, p) => sum * 60 + p, 0);
  return total > 0 ? total : null;
}
