import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { App } from "../../src/app/store";
import { useFetcher } from "../../src/core/http";
import { clearProblem, lastProblem, recordProblem } from "../../src/core/crash";
import { slidesFrom } from "../../src/ui/screensaver";
import { noMarks } from "../../src/api/reely";
import { MemoryStorage, Store } from "../../src/core/storage";

const SERVER = "http://192.168.1.20:32400";

/** plex.tv and one server, answering the way they do. */
function fakePlex(options: { claimAfter?: number; homeUsers?: number; serverUp?: boolean } = {}) {
  let claims = 0;
  const calls: string[] = [];
  const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
  const meta = (Metadata: unknown[]) => json({ MediaContainer: { Metadata } });
  const episode = (key: string, index: number, viewed = false, show = "show1") => ({
    ratingKey: key, type: "episode", title: `Episode ${index}`, index, parentIndex: 1, grandparentRatingKey: show,
    grandparentTitle: show === "show1" ? "Northbound" : "Harbor", addedAt: 1000 + index, viewCount: viewed ? 1 : 0, duration: 1000,
  });
  const fetch: typeof globalThis.fetch = async (input) => {
    const url = new URL(String(input));
    calls.push(url.origin + url.pathname);
    const path = url.pathname;
    if (url.host === "plex.tv") {
      if (path === "/api/v2/pins") return json({ id: url.searchParams.size, code: "ABCD" });
      if (path.startsWith("/api/v2/pins/")) return json({ authToken: ++claims >= (options.claimAfter ?? 1) ? "account-token" : null });
      if (path === "/api/v2/user") return json({ uuid: "me", title: "Taylor", admin: true });
      if (path === "/api/v2/home/users") {
        const n = options.homeUsers ?? 1;
        return json({ users: Array.from({ length: n }, (_, i) => ({ uuid: `u${i}`, title: `Person ${i}` })) });
      }
      if (path === "/api/v2/resources") {
        return json([{ name: "Living Room", provides: "server", owned: true, accessToken: "server-token",
          connections: [{ uri: SERVER, address: "192.168.1.20", port: 32400, local: true, relay: false }] }]);
      }
    }
    if (url.origin === SERVER) {
      if (options.serverUp === false) throw new TypeError("Failed to fetch");
      if (path === "/identity") return json({});
      if (path === "/library/sections") return json({ MediaContainer: { Directory: [{ key: "1", title: "Movies", type: "movie" }, { key: "2", title: "TV Shows", type: "show" }] } });
      if (path === "/hubs") return json({ MediaContainer: { Hub: [{ Metadata: [{ ratingKey: "m1", type: "movie", title: "Low Orbit", viewOffset: 100, duration: 1000, lastViewedAt: 5 }] }] } });
      if (path === "/playlists") return meta([]);
      if (path === "/hubs/search") {
        // Plex's answer to "orbit": the film by name, a guess, a person and a collection.
        return json({ MediaContainer: { Hub: [
          { type: "movie", Metadata: [{ ratingKey: "x1", type: "movie", title: "Gravity" }, { ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025 }] },
          { type: "actor", Directory: [{ id: "77", tag: "Ana Orbit", thumb: "/p/77" }, { id: "78", tag: "ana orbit" }] },
          { type: "collection", Metadata: [{ ratingKey: "c1", type: "collection", title: "Orbit Films" }] },
        ] } });
      }
      if (path === "/library/collections/c1/children") return meta([{ ratingKey: "m1", type: "movie", title: "Low Orbit" }]);
      if (url.searchParams.get("actor") === "77") {
        return path === "/library/sections/1/all"
          ? meta([{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025 }, { ratingKey: "m3", type: "movie", title: "Dust", year: 2019 }])
          : meta([{ ratingKey: "show1", type: "show", title: "Northbound", year: 2024 }]);
      }
      if (path === "/library/sections/1/all") return meta([{ ratingKey: "m2", type: "movie", title: "Glasshouse", addedAt: 9 }]);
      if (path === "/library/sections/2/all") {
        // Three episodes of one show and one of another: two shows on Home, one with a count.
        return meta([episode("e3", 3), episode("e2", 2), episode("h1", 1, false, "show2"), episode("e1", 1)]);
      }
      if (path === "/library/metadata/show1") return meta([{ ratingKey: "show1", type: "show", title: "Northbound" }]);
      if (path === "/library/metadata/show1/children") return meta([{ ratingKey: "s1", type: "season", title: "Season 1", index: 1 }]);
      if (path === "/library/metadata/s1/children") return meta([episode("e1", 1, true), episode("e2", 2, true), episode("e3", 3)]);
      if (path === "/library/metadata/show1/related") return json({ MediaContainer: {} });
    }
    return json({}, 404);
  };
  return { fetch, calls };
}

let app: App;
const instant = () => Promise.resolve();

beforeEach(() => {
  app = new App(new Store("t:", new MemoryStorage()), instant);
});
afterEach(() => useFetcher((i, init) => fetch(i, init)));

describe("the LG app's state", () => {
  it("signs in with a code, finds the server and fills Home", async () => {
    const plex = fakePlex({ claimAfter: 3 });
    useFetcher(plex.fetch);
    const signing = app.startSignIn();
    await Promise.resolve();
    await signing;
    const s = app.state;
    expect(s.plex.token).toBe("account-token");
    expect(s.plex.baseUrl).toBe(SERVER);
    expect(s.plex.serverName).toBe("Living Room");
    expect(s.home.continueWatching.map((i) => i.title)).toEqual(["Low Orbit"]);
    expect(s.home.recentMovies.map((i) => i.title)).toEqual(["Glasshouse"]);
    expect(s.home.recentEpisodes.map((g) => [g.showTitle, g.count])).toEqual([["Northbound", 3], ["Harbor", 1]]);
    // Kept for next time.
    expect(app.store.get("plexToken")).toBe("account-token");
    expect(app.store.get("server")).toBe("Living Room");
  });

  it("a Plex Home of several asks who's watching, once", async () => {
    useFetcher(fakePlex({ homeUsers: 3 }).fetch);
    await app.startSignIn();
    expect(app.state.askWho).toBe(true);
    app.askedWho();
    expect(app.state.askWho).toBe(false);
  });

  it("started again, it's signed in already and finds the server", async () => {
    const store = new Store("t:", new MemoryStorage());
    store.set("plexToken", "account-token");
    useFetcher(fakePlex().fetch);
    const again = new App(store, instant);
    await again.start();
    expect(again.state.plex.baseUrl).toBe(SERVER);
    expect(again.state.home.continueWatching).toHaveLength(1);
  });

  it("a server that's off says so, rather than an empty Home", async () => {
    useFetcher(fakePlex({ serverUp: false }).fetch);
    await app.startSignIn();
    expect(app.state.plex.baseUrl).toBeNull();
    expect(app.state.plex.error).toBe("Can't find your Plex server. Make sure it's on.");
  });

  it("a show's page lands on the episode it's up to", async () => {
    useFetcher(fakePlex().fetch);
    await app.startSignIn();
    app.navigate({ name: "detail", ratingKey: "show1", serverBase: SERVER });
    await new Promise((r) => setTimeout(r, 0));
    for (let i = 0; i < 20 && app.state.detail?.busy; i++) await new Promise((r) => setTimeout(r, 0));
    expect(app.state.detail?.episodes.map((e) => e.title)).toEqual(["Episode 1", "Episode 2", "Episode 3"]);
    expect(app.state.detail?.focused?.title).toBe("Episode 3");
  });

  it("Back walks back, then to Home, then lets the app close", async () => {
    app.navigate({ name: "library", kind: "movie" });
    app.navigate({ name: "search" });
    expect(app.goBack()).toBe(true);
    expect(app.state.route.name).toBe("library");
    expect(app.goBack()).toBe(true);
    expect(app.state.route.name).toBe("home");
    expect(app.goBack()).toBe(false);
  });

  it("signing out forgets the account", async () => {
    useFetcher(fakePlex().fetch);
    await app.startSignIn();
    app.signOut();
    expect(app.state.plex.token).toBeNull();
    expect(app.store.get("plexToken")).toBeNull();
  });
});

describe("search, as the Fire TV searches", () => {
  const signedIn = async () => {
    useFetcher(fakePlex().fetch);
    await app.startSignIn();
  };

  it("what matches by name first, Plex's guesses after; one of each person; collections", async () => {
    await signedIn();
    await app.setQuery("orbit");
    const s = app.state.search;
    expect(s.results.map((i) => i.title)).toEqual(["Low Orbit"]);
    expect(s.more.map((i) => i.title)).toEqual(["Gravity"]);
    expect(s.people.map((p) => [p.id, p.name])).toEqual([["77", "Ana Orbit"]]);
    expect(s.collections.map((c) => c.title)).toEqual(["Orbit Films"]);
    expect(s.busy).toBe(false);
  });

  it("an empty box clears the results; a search that found something is remembered once", async () => {
    await signedIn();
    await app.setQuery("orbit");
    app.rememberSearch();
    await app.setQuery("Orbit ");
    app.rememberSearch();
    expect(app.state.search.recent).toEqual(["Orbit"]);
    expect(app.store.json("recentSearches", [])).toEqual(["Orbit"]);
    await app.setQuery("");
    expect(app.state.search.results).toEqual([]);
    expect(app.state.search.recent).toEqual(["Orbit"]);
    app.clearRecentSearches();
    expect(app.state.search.recent).toEqual([]);
  });

  it("a slower answer to an earlier query doesn't overwrite a later one", async () => {
    await signedIn();
    const first = app.setQuery("orbit");
    await app.setQuery("");
    await first;
    expect(app.state.search.query).toBe("");
    expect(app.state.search.results).toEqual([]);
  });

  it("a person's page has what they're in from every library, newest first", async () => {
    await signedIn();
    app.navigate({ name: "person", person: { id: "77", name: "Ana Orbit", thumb: null, serverBase: SERVER } });
    for (let i = 0; i < 20 && (!app.state.list || app.state.list.busy); i++) await new Promise((r) => setTimeout(r, 0));
    expect(app.state.list?.items.map((i) => i.title)).toEqual(["Low Orbit", "Northbound", "Dust"]);
  });

  it("a collection opens on its titles", async () => {
    await signedIn();
    await app.setQuery("orbit");
    app.navigate({ name: "collection", item: app.state.search.collections[0] });
    for (let i = 0; i < 20 && (!app.state.list || app.state.list.busy); i++) await new Promise((r) => setTimeout(r, 0));
    expect(app.state.list?.items.map((i) => i.title)).toEqual(["Low Orbit"]);
  });
});

describe("a title's page and a library's filters", () => {
  /** A server with 300 films, A–Z, and plex.tv's Watchlist; everything asked is kept. */
  function library() {
    const asked: string[] = [];
    const listed = new Set(["plex://movie/a1"]);
    const films = Array.from({ length: 300 }, (_, i) => ({ ratingKey: `f${i}`, type: "movie", title: `${i < 100 ? "A" : i < 250 ? "B" : "C"} film ${i}` }));
    const json = (v: unknown) => new Response(JSON.stringify(v), { headers: { "Content-Type": "application/json" } });
    useFetcher(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? "GET";
      asked.push(`${method} ${url.pathname}${url.search.replace(/[?&]X-Plex-Token=[^&]*/, "")}`);
      if (url.host === "discover.provider.plex.tv") {
        if (url.pathname === "/library/sections/watchlist/all") return json({ MediaContainer: { Metadata: [...listed].map((guid) => ({ guid })) } });
        const key = url.searchParams.get("ratingKey");
        if (url.pathname === "/actions/addToWatchlist") listed.add(`plex://movie/${key}`);
        if (url.pathname === "/actions/removeFromWatchlist") listed.delete(`plex://movie/${key}`);
        return json({});
      }
      if (url.pathname === "/library/all") return json({ MediaContainer: { Metadata: url.searchParams.get("guid") === "plex://movie/a1" ? [{ ratingKey: "f0", type: "movie", title: "A film 0" }] : [] } });
      if (url.pathname === "/library/sections/1/firstCharacter") return json({ MediaContainer: { Directory: [{ title: "A", size: 100 }, { title: "B", size: 150 }, { title: "C", size: 50 }] } });
      if (url.pathname === "/library/sections/1/genre") return json({ MediaContainer: { Directory: [{ key: "9", title: "Drama" }] } });
      if (url.pathname === "/library/sections/1/decade") return json({ MediaContainer: { Directory: [{ key: "1990", title: "1990s" }, { key: "2020", title: "2020s" }] } });
      if (url.pathname === "/library/sections/1/all") {
        const start = Number(url.searchParams.get("X-Plex-Container-Start") ?? 0);
        const size = Number(url.searchParams.get("X-Plex-Container-Size") ?? 300);
        return json({ MediaContainer: { Metadata: films.slice(start, start + size) } });
      }
      if (url.pathname === "/library/metadata/f0") return json({ MediaContainer: { Metadata: [{ ratingKey: "f0", type: "movie", title: "A film 0", guid: "plex://movie/a1", viewCount: 0 }] } });
      return json({ MediaContainer: {} });
    });
    const section = { key: "1", title: "Films", type: "movie" };
    (app as any).current.plex = { ...app.state.plex, token: "account-token", baseUrl: SERVER, serverToken: "server-token",
      libraries: [{ serverName: "Living Room", baseUrl: SERVER, token: "server-token", section }] };
    return { asked, listed };
  }
  const settle = async () => { for (let i = 0; i < 30; i++) await new Promise((r) => setTimeout(r, 0)); };

  it("the Watchlist row has what a server here has; the button flips at once and sticks", async () => {
    const { listed } = library();
    await app.refreshWatchlist();
    expect(app.state.home.watchlist.map((i) => i.title)).toEqual(["A film 0"]);
    app.navigate({ name: "detail", ratingKey: "f0", serverBase: SERVER });
    await settle();
    expect(app.state.plex.watchlist.has("plex://movie/a1")).toBe(true);
    const off = app.toggleWatchlist();
    expect(app.state.plex.watchlist.has("plex://movie/a1")).toBe(false);
    await off;
    await settle();
    expect(listed.has("plex://movie/a1")).toBe(false);
    expect(app.state.home.watchlist).toEqual([]);
  });

  it("Watched tells Plex and shows straight away", async () => {
    const { asked } = library();
    app.navigate({ name: "detail", ratingKey: "f0", serverBase: SERVER });
    await settle();
    await app.toggleWatched();
    expect(asked.some((a) => a.startsWith("GET /:/scrobble?key=f0"))).toBe(true);
    expect(app.state.detail?.detail?.viewCount).toBe(1);
  });

  it("Unwatched, a genre and a decade go to Plex; another narrowing starts again from the top", async () => {
    const { asked } = library();
    await app.openLibrary("movie");
    await settle();
    expect(app.state.browse.movie.genres.map((g) => g.title)).toEqual(["Drama"]);
    expect(app.state.browse.movie.decades.map((g) => g.title)).toEqual(["2020s", "1990s"]);
    await app.setFilter("movie", { unwatched: true, genre: app.state.browse.movie.genres[0] });
    expect(asked.filter((a) => a.startsWith("GET /library/sections/1/all")).pop()).toContain("&unwatched=1&genre=9");
    expect(asked.filter((a) => a.startsWith("GET /library/sections/1/firstCharacter")).pop()).toContain("&unwatched=1&genre=9");
  });

  it("A–Z: a letter further down than what's loaded loads down to it", async () => {
    library();
    await app.openLibrary("movie");
    await settle();
    expect(app.state.browse.movie.items).toHaveLength(120);
    const at = await app.jumpTo("movie", "C");
    expect(at).toBe(250);
    expect(app.state.browse.movie.items[at].title).toBe("C film 250");
  });
});

describe("playback settings", () => {
  it("are kept, and an unknown quality is refused", async () => {
    app.setPlaybackMode("transcode");
    app.setMaxBitrate(8_000);
    app.setMaxBitrate(1234);
    app.setSkipIntros(true);
    app.setUpNextSeconds(7);
    app.setUpNextSeconds(5);
    app.toggleHomeRow("playlists");
    app.toggleHomeRow("playlists");
    expect(app.state.prefs).toMatchObject({ playbackMode: "transcode", maxBitrateKbps: 8_000, skipIntros: true, skipCredits: false, upNextSeconds: 5, hiddenRows: [], iptvLibrary: false, iptvWins: false });
    const again = new App(app.store, instant);
    await again.start();
    expect(again.state.prefs).toMatchObject({ playbackMode: "transcode", maxBitrateKbps: 8_000, skipIntros: true, skipCredits: false, upNextSeconds: 5, hiddenRows: [], iptvLibrary: false, iptvWins: false });
  });

  it("Always convert converts what the TV could play, at the quality chosen", async () => {
    useFetcher(async (input) => {
      const url = new URL(String(input));
      if (url.pathname === "/library/metadata/m1") {
        return new Response(JSON.stringify({ MediaContainer: { Metadata: [{ ratingKey: "m1", Media: [{ container: "mp4", videoCodec: "h264", audioCodec: "aac", Part: [{ id: 5, key: "/library/parts/5/file.mp4" }] }] }] } }));
      }
      return new Response("{}", { status: 404 });
    });
    app.setMaxBitrate(4_000);
    app.setPlaybackMode("transcode");
    const item = { ratingKey: "m1", type: "movie", title: "Low Orbit", serverBase: SERVER, viewOffsetMs: 0, durationMs: 1000 } as any;
    (app as any).current.plex = { ...app.state.plex, baseUrl: SERVER, serverToken: "server-token" };
    await app.play(item, false, () => true);
    expect(app.state.playing?.direct).toBe(false);
    expect(app.state.playing?.url).toContain("maxVideoBitrate=4000");
    expect(app.state.playing?.url).toContain("videoResolution=1280x720");
    app.setPlaybackMode("direct");
    await app.play(item, false, () => false);
    expect(app.state.playing?.direct).toBe(true);
  });

  it("another sound track or subtitles are kept with Plex and converted; Off plays the file as it is again", async () => {
    const asked: string[] = [];
    useFetcher(async (input, init) => {
      const url = new URL(String(input));
      asked.push(`${init?.method ?? "GET"} ${url.pathname}${url.search.includes("StreamID") ? url.search.replace(/&?allParts=1/, "") : ""}`);
      if (url.pathname === "/library/metadata/m1") {
        return new Response(JSON.stringify({ MediaContainer: { Metadata: [{ ratingKey: "m1", Media: [{ container: "mp4", videoCodec: "h264", audioCodec: "aac", Part: [{ id: 5, key: "/library/parts/5/file.mp4", Stream: [
          { id: 11, streamType: 2, displayTitle: "English", selected: 1 }, { id: 12, streamType: 2, displayTitle: "Commentary" },
          { id: 21, streamType: 3, displayTitle: "English (SRT)", key: "/library/streams/21", codec: "srt" },
        ] }] }] }] } }));
      }
      return new Response("{}");
    });
    (app as any).current.plex = { ...app.state.plex, baseUrl: SERVER, serverToken: "server-token" };
    const item = { ratingKey: "m1", type: "movie", title: "Low Orbit", serverBase: SERVER, viewOffsetMs: 0, durationMs: 1000 } as any;
    await app.play(item, false, () => true);
    expect(app.state.playing?.direct).toBe(true);

    await app.chooseStreams("12", undefined, 5000, () => true);
    expect(asked).toContain("PUT /library/parts/5?audioStreamID=12");
    expect(app.state.playing?.direct).toBe(false);
    expect(app.state.playing?.startMs).toBe(5000);
    expect(app.state.playing?.playback.audioStreams.find((s) => s.selected)?.id).toBe("12");

    await app.chooseStreams("11", "21", 6000, () => true);
    expect(asked).toContain("PUT /library/parts/5?audioStreamID=11&subtitleStreamID=21");
    expect(app.state.playing?.direct).toBe(false);

    await app.chooseStreams(undefined, "0", 7000, () => true);
    expect(asked).toContain("PUT /library/parts/5?subtitleStreamID=0");
    expect(app.state.playing?.direct).toBe(true);
    expect(app.state.playing?.url).toContain("/library/parts/5/file.mp4");
  });
});

import { plan } from "../../src/app/playback";

describe("playing a file as it is, or converted", () => {
  const tv = (mime: string) => !mime.includes("dts") && !mime.includes("av01");
  it("H.264 and AAC in an MP4 or MKV plays as it is", () => {
    expect(plan({ container: "mp4", videoCodec: "h264", audioCodec: "aac" }, tv).direct).toBe(true);
    expect(plan({ container: "mkv", videoCodec: "hevc", audioCodec: "eac3" }, tv).direct).toBe(true);
  });
  it("DTS sound, or a container the TV doesn't know, is converted, and says why", () => {
    expect(plan({ container: "mkv", videoCodec: "h264", audioCodec: "dca" }, tv)).toEqual({ direct: false, reason: "The TV can't play DCA sound" });
    expect(plan({ container: "avi", videoCodec: "mpeg4", audioCodec: "mp3" }, tv).reason).toBe("The TV can't open AVI files");
  });
  it("what the TV says no to is converted", () => {
    expect(plan({ container: "mp4", videoCodec: "av1", audioCodec: "aac" }, tv).direct).toBe(false);
  });
});

describe("telling Plex where playback got to", () => {
  it("stopping tells the server where, for the sitting just ended", async () => {
    const told: string[] = [];
    const base = fakePlex();
    useFetcher(async (input, init) => {
      const url = new URL(String(input));
      if (url.pathname === "/:/timeline") {
        told.push(`${url.searchParams.get("state")}@${url.searchParams.get("time")}`);
        return new Response("{}", { status: 200 });
      }
      if (url.pathname === "/library/metadata/e3") {
        return new Response(JSON.stringify({ MediaContainer: { Metadata: [{ ratingKey: "e3", type: "episode", Media: [{ container: "mp4", Part: [{ key: "/p/1.mp4" }] }] }] } }), { status: 200 });
      }
      return base.fetch(input, init);
    });
    await app.startSignIn();
    const episode = { ...app.state.home.recentEpisodes[0].newest };
    await app.play(episode, false, () => true);
    expect(app.state.playing?.direct).toBe(true);
    await app.stop(42_000, 60_000);
    expect(app.state.playing).toBeNull();
    expect(told).toContain("stopped@42000");
  });
});

describe("Live TV's guide, catch-up and reminders", () => {
  const news = { streamId: 1, number: 1, name: "News", icon: null, epgChannelId: null, archiveDays: 3 };
  const sport = { streamId: 2, number: 2, name: "Sport", icon: null, epgChannelId: null, archiveDays: 0 };
  const setLive = (c: object) => (app as unknown as { setLive: (c: object) => void }).setLive(c);
  const signedIn = () => setLive({ credentials: { base: "http://panel:8080", username: "me", password: "pw" }, account: { timezone: "UTC" }, channels: [news, sport] });

  it("watching a channel keeps it as recently watched, newest first, and offers the list", () => {
    signedIn();
    app.watchChannel(1);
    app.watchChannel(0);
    expect(app.state.live.recent).toEqual([1, 2]);
    expect(app.store.json("recentChannels", [])).toEqual([1, 2]);
    expect(app.shownCategories().map((c) => c.name)).toEqual(["Recently watched"]);
  });

  it("start over plays this programme from the archive; Go live goes back; a channel without one can't", () => {
    signedIn();
    const now = Math.floor(Date.now() / 1000);
    const on = { channelId: "1", start: now - 1200, stop: now + 600, title: "The Evening Report", description: null };
    expect(app.canCatchUp(news, on, now)).toBe(true);
    expect(app.canCatchUp(sport, on, now)).toBe(false);
    expect(app.playCatchUp(0, on)).toBe(true);
    expect(app.state.live.catchUp?.url).toMatch(/^http:\/\/panel:8080\/timeshift\/me\/pw\/30\/\d{4}-\d{2}-\d{2}:\d{2}-\d{2}\/1\.ts$/);
    expect(app.state.live.watching).toBe(0);
    app.goLive();
    expect(app.state.live.catchUp).toBeNull();
    expect(app.playCatchUp(1, on)).toBe(false);
  });

  it("a reminder comes due a minute before, once, and is kept until then", () => {
    signedIn();
    const start = Math.floor(Date.now() / 1000) + 600;
    const later = { channelId: "2", start, stop: start + 1800, title: "The Match", description: null };
    app.toggleReminder(sport, later);
    expect(app.hasReminder(sport, later)).toBe(true);
    expect(app.store.json<unknown[]>("reminders", [])).toHaveLength(1);
    app.checkReminders(start * 1000 - 5 * 60_000);
    expect(app.state.live.due).toBeNull();
    app.checkReminders(start * 1000 - 30_000);
    expect(app.state.live.due?.title).toBe("The Match");
    expect(app.state.live.reminders).toEqual([]);
    app.dismissReminder();
    app.checkReminders(start * 1000);
    expect(app.state.live.due).toBeNull();
  });

  it("watching from a reminder finds the channel and tunes it", async () => {
    signedIn();
    const start = Math.floor(Date.now() / 1000);
    app.toggleReminder(sport, { channelId: "2", start, stop: start + 60, title: "The Match", description: null });
    app.checkReminders();
    await app.watchReminder();
    expect(app.state.live.watching).toBe(1);
    expect(app.state.live.due).toBeNull();
  });
});

describe("the provider's movies and shows", () => {
  /** A panel with two films and a series, and a Plex server that has one of the films. */
  async function withIptv(wins = false) {
    const json = (v: unknown) => new Response(JSON.stringify(v), { headers: { "Content-Type": "application/json" } });
    useFetcher(async (input) => {
      const url = new URL(String(input));
      if (url.host === "panel:8080") {
        switch (url.searchParams.get("action")) {
          case "get_vod_categories": return json([{ category_id: "7", category_name: "Action" }]);
          case "get_series_categories": return json([{ category_id: "8", category_name: "Drama" }]);
          case "get_vod_streams": return json([
            { stream_id: 1, name: "EN - Low Orbit (2025)", category_id: "7", added: "200", container_extension: "mkv", tmdb: "555" },
            { stream_id: 2, name: "EN - Dust (2019)", category_id: "7", added: "100", container_extension: "mp4" },
          ]);
          case "get_series": return json([{ series_id: 9, name: "Harbor Lights", category_id: "8", last_modified: "300" }]);
          case "get_series_info": return json({ info: { name: "Harbor Lights" }, episodes: { "1": [
            { id: 91, episode_num: 1, title: "Arrival", container_extension: "mp4", info: { duration_secs: 1800 } },
            { id: 92, episode_num: 2, title: "Tide", container_extension: "mp4", info: { duration_secs: 1800 } },
          ] } });
        }
      }
      if (url.pathname === "/library/sections/1/all") return json({ MediaContainer: { Metadata: [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, Guid: [{ id: "tmdb://555" }] }] } });
      return json({ MediaContainer: {} });
    });
    const section = { key: "1", title: "Films", type: "movie" };
    (app as any).current.plex = { ...app.state.plex, token: "t", baseUrl: SERVER, serverToken: "server-token",
      libraries: [{ serverName: "Living Room", baseUrl: SERVER, token: "server-token", section }] };
    (app as unknown as { setLive: (c: object) => void }).setLive({ credentials: { base: "http://panel:8080", username: "me", password: "pw" } });
    (app as any).plexHome = { ...(app as any).plexHome, recentMovies: [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, serverBase: SERVER, addedAt: 1, lastViewedAt: 0 }] };
    app.setIptvWins(wins);
    app.setIptvLibrary(true);
    for (let i = 0; i < 30 && !app.state.iptv.ready; i++) await new Promise((r) => setTimeout(r, 0));
  }

  it("Plex's copy wins by default: the IPTV library leaves out what Plex has", async () => {
    await withIptv();
    expect(app.state.home.iptvMovies.map((i) => i.title)).toEqual(["Dust"]);
    expect(app.state.home.recentMovies.map((i) => i.title)).toEqual(["Low Orbit"]);
    expect(app.librariesOf("movie").map((l) => l.section.title)).toEqual(["Films", "IPTV"]);
    await app.openLibrary("movie", app.librariesOf("movie")[1]);
    expect(app.state.browse.movie.items.map((i) => i.title)).toEqual(["Dust"]);
    expect(app.state.browse.movie.genres.map((g) => g.title)).toEqual(["Action"]);
  });

  it("with the provider's copy winning, Plex's copy of the same film leaves Home", async () => {
    await withIptv(true);
    expect(app.state.home.iptvMovies.map((i) => i.title)).toEqual(["Low Orbit", "Dust"]);
    expect(app.state.home.recentMovies).toEqual([]);
  });

  it("an IPTV episode plays from the panel, and where it was left is kept on the TV", async () => {
    await withIptv();
    app.navigate({ name: "detail", ratingKey: "s9", serverBase: "iptv:" });
    for (let i = 0; i < 30 && app.state.detail?.busy; i++) await new Promise((r) => setTimeout(r, 0));
    expect(app.state.detail?.episodes.map((e) => e.title)).toEqual(["Arrival", "Tide"]);
    const first = app.state.detail!.episodes[0];
    await app.play(first, true, () => false, app.state.detail!.episodes);
    expect(app.state.playing?.url).toBe("http://panel:8080/series/me/pw/91.mp4");
    expect(app.state.playing?.direct).toBe(true);
    expect(app.nextInQueue()?.title).toBe("Tide");
    await app.stop(600_000, 1_800_000);
    for (let i = 0; i < 30 && app.state.detail?.busy !== false; i++) await new Promise((r) => setTimeout(r, 0));
    expect(app.state.home.continueWatching.map((i) => i.title)).toEqual(["Arrival"]);
    // The show's page lands on it, part watched.
    for (let i = 0; i < 30 && app.state.detail?.focused?.viewOffsetMs !== 600_000; i++) await new Promise((r) => setTimeout(r, 0));
    expect(app.state.detail?.focused?.viewOffsetMs).toBe(600_000);
  });

  it("marking a series watched marks every episode, kept on the TV", async () => {
    await withIptv();
    await app.setItemWatched(app.state.home.iptvShows[0], true);
    expect(app.state.home.iptvShows[0].viewedLeafCount).toBe(2);
    const again = new App(app.store, instant);
    expect(again.iptv.watch?.mark("e92.mp4")?.watched).toBe(true);
  });
});

describe("ready notices, the problem report and the screensaver's pictures", () => {
  const dune = { kind: "movie", tmdbId: 438631, tvdbId: 0, title: "Dune", year: 2021, poster: null, overview: null } as any;
  const fakeReely = (approved: boolean, held: boolean) => {
    (app as any).client = () => ({
      myRequests: async () => [{ id: 1, title: dune, status: approved ? "approved" : "pending", seasons: null }],
      marks: async () => ({ ...noMarks(), movies: new Map(held ? [[438631, "In library"]] : []) }),
    });
  };

  it("the first look takes in what was ready already; what arrives after is said, until dismissed", async () => {
    fakeReely(true, true);
    await app.checkReadyRequests();
    expect(app.state.requests.ready).toEqual([]);
    expect(app.store.json("readySeen", null)).toEqual(["movie:t438631"]);

    app.store.setJson("readySeen", []);
    await app.checkReadyRequests();
    expect(app.state.requests.ready.map((t) => t.title)).toEqual(["Dune"]);
    app.dismissReady(dune);
    expect(app.state.requests.ready).toEqual([]);
    await app.checkReadyRequests();
    expect(app.state.requests.ready).toEqual([]);
  });

  it("something approved but not here yet isn't ready", async () => {
    app.store.setJson("readySeen", []);
    fakeReely(true, false);
    await app.checkReadyRequests();
    expect(app.state.requests.ready).toEqual([]);
  });

  it("a problem is kept, the last one only, until it's cleared", () => {
    recordProblem(app.store, new Error("first"), 1);
    recordProblem(app.store, new TypeError("second"), 2);
    expect(lastProblem(app.store)).toMatchObject({ at: 2, message: "second" });
    clearProblem(app.store);
    expect(lastProblem(app.store)).toBeNull();
  });

  it("the screensaver shows each picture once, from what's on Home", () => {
    (app as any).current.plex = { ...app.state.plex, baseUrl: SERVER, serverToken: "server-token" };
    const film = { ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, art: "/art/1", thumb: "/t/1", serverBase: SERVER } as any;
    const state = { ...app.state, home: { ...app.state.home, continueWatching: [film], recentMovies: [film, { ...film, ratingKey: "m2", art: null }] } };
    const slides = slidesFrom(app, state);
    expect(slides).toHaveLength(1);
    expect(slides[0]).toMatchObject({ title: "Low Orbit", caption: "2025" });
  });
});

describe("Back in Live TV", () => {
  it("leaves the channel, then the category, then goes Home", () => {
    app.navigate({ name: "live" });
    (app as unknown as { setLive: (c: object) => void }).setLive({
      category: { id: "1", name: "News" },
      channels: [{ streamId: 1, number: 1, name: "One", icon: null, epgChannelId: null, archiveDays: 0 }],
      watching: 0,
    });
    expect(app.goBack()).toBe(true);
    expect(app.state.live.watching).toBeNull();
    expect(app.state.live.category?.name).toBe("News");
    expect(app.goBack()).toBe(true);
    expect(app.state.live.category).toBeNull();
    expect(app.state.route.name).toBe("live");
    expect(app.goBack()).toBe(true);
    expect(app.state.route.name).toBe("home");
  });
});
