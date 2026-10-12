import * as plex from "../api/plex";
import type { PlexItem, PlexSection } from "../api/plex";
import { continueWatchingOrder } from "../core/continueWatching";
import { stableSort } from "../core/sort";

/*
 * Home as the Fire TV app builds it (ReelyViewModel.refreshHome): Continue Watching from
 * every server, most recently watched first; new episodes gathered onto their show; new
 * films; playlists. The same rules, so the two apps show the same rows.
 */

/** A library on one server, with what's needed to ask it things. */
export interface LibraryChoice {
  serverName: string;
  baseUrl: string;
  token: string;
  section: PlexSection;
}

export const choiceId = (c: LibraryChoice) => `${c.serverName}|${c.section.key}`;

/** Several episodes of one show arriving at once, shown as the show with a count. */
export interface EpisodeGroup {
  showTitle: string;
  showRatingKey: string | null;
  thumb: string | null;
  newest: PlexItem;
  count: number;
  addedAt: number;
  librarySectionId: string | null;
  serverBase: string | null;
}

export const groupKey = (g: EpisodeGroup) => (g.serverBase ?? "") + "|" + (g.showRatingKey ?? g.showTitle);

export interface HomeRows {
  continueWatching: PlexItem[];
  recentEpisodes: EpisodeGroup[];
  recentMovies: PlexItem[];
  playlists: PlexItem[];
  watchlist: PlexItem[];
  /** The IPTV provider's newest, while its movies and shows are switched on. */
  iptvMovies: PlexItem[];
  iptvShows: PlexItem[];
}

export const emptyHome = (): HomeRows => ({ continueWatching: [], recentEpisodes: [], recentMovies: [], playlists: [], watchlist: [], iptvMovies: [], iptvShows: [] });

export const homeIsEmpty = (h: HomeRows) =>
  !h.continueWatching.length && !h.recentEpisodes.length && !h.recentMovies.length && !h.playlists.length && !h.watchlist.length;

export const TARGET_SHOW_COUNT = 30;
export const EPISODE_PAGE = 200;
export const MAX_EPISODE_SCAN = 2_000;

/**
 * Episodes, newest first, folded onto their shows: the newest kept, the rest a tally.
 * Keyed by server as well, since a rating key is only unique on its own server.
 */
export function foldEpisodes(groups: Map<string, EpisodeGroup>, page: PlexItem[], base: string, sectionKey: string) {
  for (const episode of page) {
    const key = base + "|" + (episode.grandparentRatingKey ?? episode.ratingKey);
    const existing = groups.get(key);
    if (!existing) {
      groups.set(key, {
        showTitle: episode.grandparentTitle ?? episode.title,
        showRatingKey: episode.grandparentRatingKey ?? episode.ratingKey,
        thumb: episode.grandparentThumb ?? episode.thumb,
        newest: episode,
        count: 1,
        addedAt: episode.addedAt,
        librarySectionId: episode.librarySectionId ?? sectionKey,
        serverBase: episode.serverBase,
      });
    } else {
      existing.count++;
    }
  }
}

/** The row measured in shows, paged until there are enough: one big import mustn't fill it. */
export async function recentEpisodeGroups(sources: LibraryChoice[]): Promise<EpisodeGroup[]> {
  const groups = new Map<string, EpisodeGroup>();
  for (const source of sources) {
    let offset = 0;
    let scanned = 0;
    while (groups.size < TARGET_SHOW_COUNT && scanned < MAX_EPISODE_SCAN) {
      let page: PlexItem[];
      try {
        page = await plex.recentlyAdded(source.baseUrl, source.token, source.section.key, plex.TYPE_EPISODE, EPISODE_PAGE, offset);
      } catch {
        break;
      }
      if (!page.length) break;
      foldEpisodes(groups, page, source.baseUrl, source.section.key);
      scanned += page.length;
      offset += page.length;
      if (page.length < EPISODE_PAGE) break;
    }
  }
  return stableSort(Array.from(groups.values()), (a, b) => b.addedAt - a.addedAt).slice(0, TARGET_SHOW_COUNT);
}

/** Every server Home draws on, once each. */
export function serversOf(sources: LibraryChoice[]): Array<[string, string]> {
  const seen = new Set<string>();
  const out: Array<[string, string]> = [];
  for (const s of sources) {
    const key = s.baseUrl + "|" + s.token;
    if (!seen.has(key)) {
      seen.add(key);
      out.push([s.baseUrl, s.token]);
    }
  }
  return out;
}

/**
 * Home's Plex rows. Null when no server answered at all: the rows on screen are left as
 * they are, rather than wiped blank by a moment's lost connection.
 */
export async function loadHome(sources: LibraryChoice[]): Promise<HomeRows | null> {
  const servers = serversOf(sources);
  let answered = false;
  const continuing = await Promise.all(
    servers.map(([base, token]) =>
      plex.continueWatching(base, token).then(
        (items) => {
          answered = true;
          return items;
        },
        () => [] as PlexItem[],
      ),
    ),
  );
  if (!answered) return null;
  const playlists = await Promise.all(servers.map(([base, token]) => plex.playlists(base, token).catch(() => [] as PlexItem[])));
  const movieSources = sources.filter((s) => s.section.type === "movie");
  const showSources = sources.filter((s) => s.section.type === "show");
  const movies = await Promise.all(
    movieSources.map((s) => plex.recentlyAdded(s.baseUrl, s.token, s.section.key, plex.TYPE_MOVIE, 40).catch(() => [] as PlexItem[])),
  );
  return {
    continueWatching: continueWatchingOrder(flatten(continuing)).slice(0, 40),
    recentEpisodes: await recentEpisodeGroups(showSources),
    recentMovies: stableSort(flatten(movies), (a, b) => b.addedAt - a.addedAt).slice(0, 40),
    playlists: flatten(playlists),
    watchlist: [],
    iptvMovies: [],
    iptvShows: [],
  };
}

export function flatten<T>(lists: T[][]): T[] {
  const out: T[] = [];
  for (const list of lists) for (const x of list) out.push(x);
  return out;
}
