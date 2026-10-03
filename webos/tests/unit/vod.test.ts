import { describe, expect, it } from "vitest";
import {
  episodeItems, IPTV_SOURCE, IptvLibrary, IptvWatch, isIptv, itemOf, keyOf, letterStart, parseKey, parseMovieInfo, parseName,
  parseSeriesInfo, readTitles, seasonItems, TitleIndex, titleOf, type BrowseOptions, type KeyValue, type VodCatalog,
} from "../../src/api/vod";
import type { PlexIndexEntry } from "../../src/api/plex";
import { isWatched } from "../../src/api/plex";
import { item } from "./fixtures";

class Memory implements KeyValue {
  values = new Map<string, string>();
  get = (key: string) => this.values.get(key) ?? null;
  set = (key: string, value: string) => void this.values.set(key, value);
}

const entry = (ratingKey: string, title: string, year: number | null, guids: string[] = [], originalTitle: string | null = null): PlexIndexEntry =>
  ({ ratingKey, serverBase: "http://plex", title, originalTitle, year, guids });

describe("provider names", () => {
  it("come apart into title, year and tag", () => {
    expect(parseName("EN - The Matrix (1999)")).toEqual({ name: "The Matrix", year: 1999, tag: "EN" });
    expect(parseName("|4K| Dune")).toEqual({ name: "Dune", year: null, tag: "4K" });
    expect(parseName("[FR] Amélie - 2001")).toEqual({ name: "Amélie", year: 2001, tag: "FR" });
    expect(parseName("Heat [1995]")).toEqual({ name: "Heat", year: 1995, tag: null });
  });
  it("names that only look like tags are left alone", () => {
    expect(parseName("CSI: Miami").name).toBe("CSI: Miami");
    expect(parseName("WALL-E").name).toBe("WALL-E");
    expect(parseName("2012").name).toBe("2012");
    expect(parseName("Blade Runner 2049").name).toBe("Blade Runner 2049");
    expect(parseName("Blade Runner 2049 (2017)", 2017).year).toBe(2017);
  });
});

describe("the provider's lists", () => {
  it("a film list is read, odd fields and all", () => {
    const titles = readTitles([
      { num: 1, name: "EN - The Matrix (1999)", stream_id: 10, stream_icon: "http://img/m.jpg", rating: "8.7", added: "1600000000", category_id: "3", container_extension: "mkv", tmdb: "603" },
      { num: 2, name: "Heat", stream_id: "11", stream_icon: "", rating: 0, added: null, category_id: 3 },
      { num: 3, name: "No id" },
      "junk",
      null,
      { num: 4, name: "The Matrix again", stream_id: 10 },
      { num: 5, name: "Half a number", stream_id: "12abc" },
    ], false);
    expect(titles.map((t) => t.id)).toEqual([10, 11]);
    const [matrix, heat] = titles;
    expect(matrix).toMatchObject({ name: "The Matrix", year: 1999, tag: "EN", rating: 8.7, addedAt: 1_600_000_000, tmdbId: "603", extension: "mkv", categoryId: "3" });
    expect(heat.poster).toBeNull();
    expect(heat.rating).toBeNull();
    expect(heat.categoryId).toBe("3");
  });
  it("a refused login is no films, not a crash", () => {
    expect(readTitles({ user_info: { auth: 0 } }, false)).toEqual([]);
  });
  it("a series list reads its own fields", () => {
    const [show] = readTitles([{ name: "Breaking Bad", series_id: 5, cover: "http://img/bb.jpg", plot: "Chemistry.", genre: "Drama", releaseDate: "2008-01-20", last_modified: "1700000000", category_id: "9", tmdb: "1396" }], true);
    expect(show).toMatchObject({ series: true, id: 5, year: 2008, plot: "Chemistry.", addedAt: 1_700_000_000 });
  });
  it("a year that isn't one falls back to the release date", () => {
    expect(readTitles([{ name: "Show", series_id: 1, year: "N/A", releaseDate: "2011-04-17" }], true)[0].year).toBe(2011);
  });
});

describe("pages", () => {
  it("a film's page", () => {
    const info = parseMovieInfo({
      info: { name: "The Matrix", plot: "Neo.", cast: "Keanu Reeves, Carrie-Anne Moss", director: "Lana Wachowski", genre: "Action / Sci-Fi", duration_secs: "8160", backdrop_path: ["http://img/back.jpg"], releasedate: "1999-03-31", rating: "8.7", tmdb_id: "603" },
      movie_data: { stream_id: 10, container_extension: "mkv" },
    })!;
    expect(info.cast).toEqual(["Keanu Reeves", "Carrie-Anne Moss"]);
    expect(info.genres).toEqual(["Action", "Sci-Fi"]);
    expect(info.durationMs).toBe(8_160_000);
    expect(info.backdrop).toBe("http://img/back.jpg");
    expect(info.extension).toBe("mkv");
  });
  it("a page with nothing to say is still a page", () => {
    const info = parseMovieInfo({ info: [], movie_data: { stream_id: 10 } })!;
    expect(info.plot).toBeNull();
    expect(info.durationMs).toBe(0);
  });
  it("a series' seasons and episodes, keyed by season or listed", () => {
    const keyed = parseSeriesInfo({
      info: { name: "Show", plot: "About.", backdrop_path: [] },
      seasons: [{ season_number: 1, cover: "http://img/s1.jpg" }],
      episodes: {
        "2": [{ id: "22", episode_num: 1, title: "Two One", container_extension: "mp4", info: { duration: "00:42:00" } }],
        "1": [{ id: "12", episode_num: 2, title: "One Two" }, { id: "11", episode_num: 1, title: "One One", info: { plot: "First." } }],
        "0": [{ id: "1", episode_num: 1, title: "Special" }],
      },
    })!;
    expect(keyed.seasons.map((s) => s.number)).toEqual([0, 1, 2]);
    expect(keyed.seasons.map((s) => s.name)).toEqual(["Specials", "Season 1", "Season 2"]);
    expect(keyed.seasons[1].episodes.map((e) => e.id)).toEqual([11, 12]);
    expect(keyed.seasons[1].poster).toBe("http://img/s1.jpg");
    expect(keyed.seasons[2].episodes[0].durationMs).toBe(2_520_000);

    const listed = parseSeriesInfo({ info: {}, episodes: [[{ id: "5", season: 1, episode_num: 1, title: "A" }], [{ id: "6", season: 2, episode_num: 1, title: "B" }]] })!;
    expect(listed.seasons.map((s) => s.number)).toEqual([1, 2]);
  });
  it("a special numbered season 0 in its own field stays a special", () => {
    const info = parseSeriesInfo({ info: {}, episodes: [[{ id: "5", season: 0, episode_num: 1, title: "Special" }]] })!;
    expect(info.seasons.map((s) => s.name)).toEqual(["Specials"]);
  });
});

describe("keys and items", () => {
  it("keys say what they are and carry what playing needs", () => {
    expect(parseKey(keyOf.movie(10, "mkv"))).toEqual({ kind: "movie", id: 10, extension: "mkv" });
    expect(parseKey(keyOf.movie(10, null))).toEqual({ kind: "movie", id: 10, extension: null });
    expect(parseKey(keyOf.show(5))).toEqual({ kind: "show", id: 5 });
    expect(parseKey(keyOf.season(5, 2))).toEqual({ kind: "season", showId: 5, number: 2 });
    expect(parseKey(keyOf.episode(22, "mp4"))).toEqual({ kind: "episode", id: 22, extension: "mp4" });
    expect(parseKey("12345")).toBeNull();
    expect(parseKey("x")).toBeNull();
  });
  it("episodes are items under their show, specials outside any season", () => {
    const info = parseSeriesInfo({ info: {}, episodes: { "0": [{ id: "1", episode_num: 1, title: "Special" }], "1": [{ id: "11", episode_num: 1, title: "Pilot", container_extension: "mkv" }] } })!;
    const [special] = episodeItems(5, "Show", null, null, info.seasons[0]);
    const [first] = episodeItems(5, "Show", null, null, info.seasons[1]);
    expect(special.parentIndex).toBeNull();
    expect(first.parentIndex).toBe(1);
    expect(first.grandparentRatingKey).toBe("s5");
    expect(first.parentRatingKey).toBe("s5:1");
    expect(first.serverBase).toBe(IPTV_SOURCE);
    expect(parseKey(first.ratingKey)).toEqual({ kind: "episode", id: 11, extension: "mkv" });
  });
});

const film = (id: number, name: string, year: number | null, extra: Record<string, string> = {}) =>
  titleOf({ stream_id: String(id), name, ...(year != null ? { year: String(year) } : {}), ...extra }, false)!;

describe("matching with Plex", () => {
  it("by id, else by name and year", () => {
    const plex = TitleIndex.ofPlex([
      entry("1", "The Matrix", 1999, ["tmdb://603", "imdb://tt0133093"]),
      entry("2", "Amélie", 2001, [], "Le Fabuleux Destin d'Amélie Poulain"),
    ]);
    const matrix = film(10, "EN - The Matrix Reloaded", 2003, { tmdb: "603" });
    expect(plex.has(matrix.tmdbId, matrix.key, matrix.year)).toBe(true);
    const amelie = film(11, "Amelie (2002)", null);
    expect(plex.has(amelie.tmdbId, amelie.key, amelie.year)).toBe(true);
    const remake = film(12, "Amelie", 2021);
    expect(plex.has(remake.tmdbId, remake.key, remake.year)).toBe(false);
    const undated = film(13, "The Matrix", null);
    expect(plex.has(undated.tmdbId, undated.key, undated.year)).toBe(true);
    expect(TitleIndex.EMPTY.has("603", "thematrix", 1999)).toBe(false);
  });
});

describe("where things were left", () => {
  it("is kept, and near the end is watched", () => {
    const store = new Memory();
    let clock = 1_000;
    const watch = new IptvWatch(store, "w", () => clock);
    const heat = itemOf(film(10, "Heat", 1995));
    watch.progress(heat, 600_000, 6_000_000);
    expect(watch.apply(heat).viewOffsetMs).toBe(600_000);
    expect(watch.continueWatching().map((i) => i.ratingKey)).toEqual([heat.ratingKey]);

    clock = 2_000;
    watch.progress(heat, 5_500_000, 6_000_000);
    const done = new IptvWatch(store, "w").apply(heat);
    expect(isWatched(done)).toBe(true);
    expect(done.viewOffsetMs).toBe(0);
    expect(new IptvWatch(store, "w").continueWatching()).toEqual([]);

    watch.setWatched([heat], false);
    expect(isWatched(watch.apply(heat))).toBe(false);
  });
  it("a show counts its watched episodes", () => {
    const watch = new IptvWatch(new Memory(), "w");
    const info = parseSeriesInfo({ info: {}, episodes: { "1": [{ id: "11", episode_num: 1, title: "A" }, { id: "12", episode_num: 2, title: "B" }] } })!;
    const episodes = episodeItems(5, "Show", null, null, info.seasons[0]);
    const [season] = seasonItems(5, "Show", null, info);
    watch.setWatched([episodes[0]], true);
    expect(watch.apply(season).viewedLeafCount).toBe(1);
    expect(isWatched(watch.apply(season))).toBe(false);
    watch.setWatched([episodes[1]], true);
    expect(isWatched(watch.apply(season))).toBe(true);
  });
  it("storage that's broken or full loses nothing on screen", () => {
    const broken: KeyValue = { get: () => "{not json", set: () => { throw new Error("full"); } };
    const watch = new IptvWatch(broken, "w");
    const heat = itemOf(film(10, "Heat", 1995));
    watch.progress(heat, 1_000, 100_000);
    expect(watch.apply(heat).viewOffsetMs).toBe(1_000);
  });
});

describe("the IPTV library", () => {
  const vod: VodCatalog = {
    movies: [
      film(1, "The Matrix", 1999, { added: "100", category_id: "1", tmdb: "603", rating: "8.7" }),
      film(2, "Heat", 1995, { added: "300", category_id: "1", rating: "8.3" }),
      film(3, "Amélie", 2001, { added: "200", category_id: "2", rating: "8.0" }),
      film(4, "Zodiac", 2007, { added: "50", category_id: "2" }),
    ],
    series: [],
    movieCategories: [{ id: "1", name: "Action" }, { id: "2", name: "Drama" }, { id: "3", name: "Empty" }],
    seriesCategories: [],
    loadedAt: 0,
  };
  const library = () => {
    const l = new IptvLibrary();
    l.setCatalog(vod);
    l.setPlex([entry("10", "Matrix", 1999, ["tmdb://603"]), entry("11", "Heat", 1995)], []);
    return l;
  };
  const options = (o: Partial<BrowseOptions> = {}): BrowseOptions => ({ sort: "TITLE", categoryId: null, unwatchedOnly: false, ...o });
  const names = (list: { title: string }[]) => list.map((i) => i.title);
  const plexMovie = (key: string, title: string, year: number) => item({ ratingKey: key, title, year, serverBase: "http://plex" });

  it("with Plex winning, it leaves out what Plex has", () => {
    const grid = library().browse(true, options(), false);
    expect(names(grid.items)).toEqual(["Amélie", "Zodiac"]);
    expect(grid.items.every(isIptv)).toBe(true);
    expect(grid.categories.map((c) => c.name)).toEqual(["Drama"]);
  });
  it("with IPTV winning, it keeps everything and Plex's copies give way", () => {
    const iptv = library();
    expect(iptv.browse(true, options(), true).items).toHaveLength(4);
    expect(iptv.hides(plexMovie("11", "Heat", 1995), true)).toBe(true);
    expect(iptv.hides(plexMovie("11", "Heat", 1995), false)).toBe(false);
    expect(iptv.hides(plexMovie("12", "Alien", 1979), true)).toBe(false);
  });
  it("sorted and filtered like a Plex library", () => {
    const iptv = library();
    const grid = (o: Partial<BrowseOptions>) => names(iptv.browse(true, options(o), true).items);
    expect(grid({})).toEqual(["Amélie", "Heat", "The Matrix", "Zodiac"]);
    expect(grid({ sort: "ADDED" })).toEqual(["Heat", "Amélie", "The Matrix", "Zodiac"]);
    expect(grid({ sort: "RATED" })).toEqual(["The Matrix", "Heat", "Amélie", "Zodiac"]);
    expect(grid({ sort: "RELEASED" })).toEqual(["Zodiac", "Amélie", "The Matrix", "Heat"]);
    expect(grid({ categoryId: "2" })).toEqual(["Amélie", "Zodiac"]);
  });
  it('"The Matrix" goes under M, and the rail counts the letters', () => {
    const grid = library().browse(true, options(), true);
    expect(grid.letters).toEqual([{ letter: "A", count: 1 }, { letter: "H", count: 1 }, { letter: "M", count: 1 }, { letter: "Z", count: 1 }]);
    expect(letterStart(grid, "M")).toBe(2);
  });
  it("accents sit with their letter and numbers come first, so the rail lands right", () => {
    const l = new IptvLibrary();
    l.setCatalog({ ...vod, movies: [film(1, "Zulu", null), film(2, "Émile", null), film(3, "2012", null), film(4, "Eden", null), film(5, "alpha", null)] });
    const grid = l.browse(true, options(), true);
    expect(names(grid.items)).toEqual(["2012", "alpha", "Eden", "Émile", "Zulu"]);
    expect(grid.letters).toEqual([{ letter: "#", count: 1 }, { letter: "A", count: 1 }, { letter: "E", count: 2 }, { letter: "Z", count: 1 }]);
    expect(letterStart(grid, "Z")).toBe(4);
    expect(letterStart(grid, "Q")).toBe(-1);
  });
  it("unwatched only leaves out what's been watched here", () => {
    const iptv = library();
    iptv.watch = new IptvWatch(new Memory(), "w");
    iptv.watch.setWatched([itemOf(vod.movies[1])], true);
    expect(names(iptv.browse(true, options({ unwatchedOnly: true }), true).items)).toEqual(["Amélie", "The Matrix", "Zodiac"]);
  });
  it("Home's row is the newest, less what Plex has", () => {
    expect(names(library().newest(true, false))).toEqual(["Amélie", "Zodiac"]);
    expect(names(library().newest(true, true))).toEqual(["Heat", "Amélie", "The Matrix", "Zodiac"]);
  });
  it("search finds by name, accents and all, best first", () => {
    const iptv = library();
    expect(names(iptv.search("amelie", false))).toEqual(["Amélie"]);
    expect(names(iptv.search("matrix", true))).toEqual(["The Matrix"]);
    expect(iptv.search("matrix", false)).toEqual([]);
    expect(iptv.search("  ", true)).toEqual([]);
  });
  it("more like this is the same category", () => {
    expect(names(library().related(itemOf(vod.movies[2]), true))).toEqual(["Zodiac"]);
  });
  it("switched off, there's nothing", () => {
    const iptv = library();
    iptv.clear();
    expect(iptv.browse(true, options(), true).items).toEqual([]);
    expect(iptv.newest(true, true)).toEqual([]);
    expect(iptv.search("heat", true)).toEqual([]);
  });
  it("a series looked at again is kept over older ones", () => {
    const iptv = new IptvLibrary();
    const info = parseSeriesInfo({ info: {}, episodes: {} })!;
    for (let id = 1; id <= 12; id++) iptv.keepSeries(id, info);
    iptv.cachedSeries(1);
    iptv.keepSeries(13, info);
    expect(iptv.cachedSeries(1)).not.toBeNull();
    expect(iptv.cachedSeries(2)).toBeNull();
  });
});
