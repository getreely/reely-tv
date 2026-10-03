import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { useFetcher } from "../../src/core/http";
import {
  badge, isValid, OWNER_LINK_BROKEN, PLEX_REJECTED, librariesFor, noMarks, normalize, plexHas, preferredLibrary, readyRequests, ReelyRequests, requestKey,
  type RequestRecord, type RequestTitle,
} from "../../src/api/reely";

/** A stand-in that answers as Reely's own handlers do: a session from the Plex sign-in, then JSON. */
class FakeReely {
  signIns = 0;
  signInStatus = 200;
  signInError = "this server isn't shared with your Plex account";
  session = false;
  requested: any[] = [];
  alreadyRequested = false;
  role = "user";
  credentials: (string | undefined)[] = [];

  fetch: typeof fetch = async (input, init) => {
    const url = new URL(String(input));
    const method = init?.method ?? "GET";
    this.credentials.push(init?.credentials);
    const body = typeof init?.body === "string" ? init.body : "";
    const reply = (status: number, value: unknown) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
    const path = url.pathname;
    if (path === "/api/v1/auth/plex/token") {
      this.signIns++;
      expect(JSON.parse(body).token).toBe("plex-account-token");
      if (this.signInStatus !== 200) return reply(this.signInStatus, { error: this.signInError });
      this.session = true;
      return reply(200, { status: "ok" });
    }
    if (!this.session) return reply(401, { error: "login required" });
    switch (path) {
      case "/api/v1/explore":
        return reply(200, {
          imageBase: "https://image.tmdb.org/t/p",
          movies: [{ tmdbId: 603, kind: "movie", title: "The Matrix", year: 1999, poster: "/matrix.jpg" }],
          shows: [{ tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011, poster: "/got.jpg" }],
          popularMovies: [], popularShows: null, topMovies: [], topShows: [],
          providers: [{ key: "netflix", name: "Netflix", kind: "show", results: [{ tvdbId: 81189, kind: "show", title: "Breaking Bad", year: 2008, poster: "https://artworks.thetvdb.com/bb.jpg" }] }],
        });
      case "/api/v1/auth/me":
        return reply(200, { user: { id: 3, role: this.role, mayAdd: false, defaultLibraryId: 2 } });
      case "/api/v1/libraries":
        return reply(200, { libraries: [{ id: 1, name: "Movies", kind: "movies" }, { id: 2, name: "Kids Movies", kind: "movies" }, { id: 3, name: "TV", kind: "shows" }] });
      case "/api/v1/search":
        expect(url.searchParams.get("q")).toBe("incep");
        return reply(200, { results: [{ tmdbId: 27205, kind: "movie", title: "Inception", year: 2010, poster: "/inc.jpg" }] });
      case "/api/v1/preview/show/1399":
        return reply(200, {
          inLibraries: [],
          preview: { tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011, poster: "/got.jpg", backdrop: "/got-wide.jpg", genres: ["Drama"], status: "Ended",
            seasons: [{ number: 0, name: "Specials", episodes: [{}] }, { number: 1, name: "Season 1", episodes: [{}, {}] }, { number: 2, name: "", episodes: [{}] }] },
        });
      case "/api/v1/preview/show/81189":
        expect(url.searchParams.get("src")).toBe("tvdb");
        return reply(200, { inLibraries: [], preview: { tvdbId: 81189, kind: "show", title: "Breaking Bad", seasons: [] } });
      case "/api/v1/preview/movie/603":
        return reply(200, { inLibraries: [3], preview: { tmdbId: 603, kind: "movie", title: "The Matrix", year: 1999, runtime: 136, genres: [] } });
      case "/api/v1/movies":
        return reply(200, { movies: [{ tmdbId: 603, filePath: "/films/matrix.mkv" }, { tmdbId: 27205, filePath: "", downloading: true }, { tmdbId: 11, filePath: "" }] });
      case "/api/v1/shows":
        return reply(200, { shows: [{ tmdbId: 1399, onDisk: 10, aired: 73, wanted: 63 }, { tmdbId: 0, tvdbId: 81189, onDisk: 62, aired: 62, wanted: 0 }] });
      case "/api/v1/requests":
        if (method === "POST") {
          if (this.alreadyRequested) return reply(409, { error: "already requested" });
          this.requested.push(JSON.parse(body));
          return reply(201, { id: 10, status: "pending" });
        }
        if (url.searchParams.get("mine") === "1") {
          return reply(200, { requests: [
            { id: 4, kind: "movie", tmdbId: 603, title: "The Matrix", year: 1999, poster: "/matrix.jpg", status: "approved" },
            { id: 9, kind: "show", tmdbId: 1399, title: "Game of Thrones", poster: "/got.jpg", seasons: [1], status: "pending" },
          ] });
        }
        return reply(200, { requests: [{ id: 2, kind: "movie", tmdbId: 550, title: "Fight Club", status: "pending" }] });
    }
    return reply(404, { error: "no such endpoint" });
  };
}

let server: FakeReely;
const reely = () => new ReelyRequests("127.0.0.1:8788", () => "plex-account-token");

beforeEach(() => {
  server = new FakeReely();
  useFetcher(server.fetch);
});
afterEach(() => useFetcher((input, init) => fetch(input, init)));

describe("Reely requests", () => {
  it("signs in with the Plex account, then keeps the session", async () => {
    const r = reely();
    expect(await r.signIn()).toBeUndefined();
    await r.explore();
    await r.myRequests();
    expect(server.signIns).toBe(1);
    // The session is a cookie: every call has to carry it.
    expect(server.credentials.every((c) => c === "include")).toBe(true);
  });
  it("signs in again when the session has lapsed", async () => {
    const r = reely();
    await r.explore();
    server.session = false;
    await r.explore();
    expect(server.signIns).toBe(2);
  });
  it("an account the server isn't shared with is told so", async () => {
    server.signInStatus = 403;
    expect(await reely().signIn()).toBe("Your Plex account doesn't have access to this server's requests.");
    await expect(reely().explore()).rejects.toThrow("Your Plex account doesn't have access to this server's requests.");
  });
  it("plex.tv turning this device's sign-in down is said in words", async () => {
    server.signInStatus = 401;
    server.signInError = "plex.tv didn't accept that sign-in";
    expect(await reely().signIn()).toBe(PLEX_REJECTED);
  });
  it("Reely's own Plex link failing is the owner's to fix, and never shown as markup", async () => {
    server.signInStatus = 502;
    server.signInError = 'plex.tv: Unauthorized: <?xml version="1.0" encoding="UTF-8"?>\n<errors>\n  <error>Invalid authentication token.</error>\n</errors>';
    expect(await reely().signIn()).toBe(OWNER_LINK_BROKEN);
    server.signInStatus = 500;
    server.signInError = "<html><body>Bad gateway</body></html>";
    expect(await reely().signIn()).toBe("Reely couldn't do that. Try again.");
  });
  it("no Plex account, no Reely", async () => {
    expect(await new ReelyRequests("x", () => undefined).signIn()).toBe("Sign in to Plex first.");
  });
  it("a server that isn't there says where it looked", async () => {
    useFetcher(async () => { throw new TypeError("Failed to fetch"); });
    expect(await reely().signIn()).toBe("Couldn't reach Reely at 127.0.0.1:8788.");
  });
  it("explore rows, with posters from either TMDB or TheTVDB", async () => {
    const rows = await reely().explore();
    expect(rows.map((r) => r.title)).toEqual(["Trending Movies", "Trending Shows", "Shows on Netflix"]);
    expect(rows[0].titles[0].poster).toBe("https://image.tmdb.org/t/p/w342/matrix.jpg");
    expect(rows[2].titles[0].poster).toBe("https://artworks.thetvdb.com/bb.jpg");
    expect(rows[2].titles[0].tvdbId).toBe(81189);
  });
  it("a show's seasons, without the specials", async () => {
    const r = reely();
    const show = (await r.explore())[1].titles[0];
    const detail = await r.detail(show);
    expect(detail.seasons.map((s) => s.number)).toEqual([1, 2]);
    expect(detail.seasons[0].episodes).toBe(2);
    expect(detail.seasons[1].name).toBe("Season 2");
    expect(detail.backdrop).toBe("https://image.tmdb.org/t/p/w1280/got-wide.jpg");
    expect(detail.inLibrary).toBe(false);
  });
  it("a show known only by TheTVDB is looked up there", async () => {
    const r = reely();
    const bb = (await r.explore())[2].titles[0];
    expect((await r.detail(bb)).title.title).toBe("Breaking Bad");
  });
  it("a film already in the library says so", async () => {
    const r = reely();
    expect((await r.detail((await r.explore())[0].titles[0])).inLibrary).toBe(true);
  });
  it("requesting seasons sends them, and the poster as Reely gave it", async () => {
    const r = reely();
    const show = (await r.explore())[1].titles[0];
    expect(await r.request(show, [1, 2])).toEqual({ kind: "sent", approved: false });
    expect(server.requested).toHaveLength(1);
    expect(server.requested[0]).toMatchObject({ kind: "show", tmdbId: 1399, seasons: [1, 2], poster: "/got.jpg" });
  });
  it("the whole show sends no season list", async () => {
    const r = reely();
    await r.request((await r.explore())[1].titles[0], null);
    expect("seasons" in server.requested[0]).toBe(false);
  });
  it("asking twice is already requested", async () => {
    server.alreadyRequested = true;
    const r = reely();
    expect(await r.request((await r.explore())[0].titles[0], null)).toEqual({ kind: "already" });
  });
  it("my requests, newest first, with their status", async () => {
    const mine = await reely().myRequests();
    expect(mine.map((m) => m.title.title)).toEqual(["Game of Thrones", "The Matrix"]);
    expect(mine.map((m) => m.status)).toEqual(["pending", "approved"]);
    expect(mine[0].seasons).toEqual([1]);
  });
  it("search finds what isn't on the server", async () => {
    expect((await reely().search("incep")).map((t) => t.title)).toEqual(["Inception"]);
    expect(await reely().search("  ")).toEqual([]);
  });
  it("where a request can go, as Reely's own request button offers it", async () => {
    const r = reely();
    const places = await r.places();
    const matrix = (await r.explore())[0].titles[0];
    const detail = await r.detail(matrix);
    expect(detail.inLibraries).toEqual([3]);
    const addable = librariesFor(places, matrix, detail.inLibraries);
    expect(addable.map((l) => l.name)).toEqual(["Movies", "Kids Movies"]);
    expect(preferredLibrary(places, addable)?.name).toBe("Kids Movies");
    expect(places.adds).toBe(false);
  });
  it("the owner adds rather than asks", async () => {
    server.role = "admin";
    expect((await reely().places()).adds).toBe(true);
  });
  it("the library goes with the request, and who it's for is left to Reely", async () => {
    const r = reely();
    await r.request((await r.explore())[0].titles[0], null, 1);
    expect(server.requested[0].libraryId).toBe(1);
    expect("audience" in server.requested[0]).toBe(false);
  });
  it("titles are marked the way Reely's own Explore marks them", async () => {
    const marks = await reely().marks();
    const film = (id: number): RequestTitle => ({ kind: "movie", tmdbId: id, tvdbId: 0, title: "x" });
    const show = (tmdb: number, tvdb = 0): RequestTitle => ({ kind: "show", tmdbId: tmdb, tvdbId: tvdb, title: "x" });
    expect(badge(marks, film(603))).toBe("In library");
    expect(badge(marks, film(27205))).toBe("Downloading");
    expect(badge(marks, film(11))).toBe("Requested");
    expect(badge(marks, film(550))).toBe("Requested");
    expect(badge(marks, film(99))).toBeUndefined();
    expect(badge(marks, show(1399))).toBe("Partial");
    expect(badge(marks, show(0, 81189))).toBe("In library");
  });
});

describe("Reely, without a server", () => {
  const film = (id: number): RequestTitle => ({ kind: "movie", tmdbId: id, tvdbId: 0, title: `Film ${id}` });
  const show = (id: number): RequestTitle => ({ kind: "show", tmdbId: id, tvdbId: 0, title: `Show ${id}` });
  it("what was asked for and has arrived is ready, once", () => {
    const mine: RequestRecord[] = [
      { id: 1, title: film(1), status: "approved", seasons: null },
      { id: 2, title: film(2), status: "approved", seasons: null },
      { id: 3, title: film(3), status: "pending", seasons: null },
      { id: 4, title: show(4), status: "approved", seasons: null },
      { id: 5, title: film(5), status: "approved", seasons: null },
    ];
    const marks = noMarks();
    [[1, "In library"], [2, "Downloading"], [3, "In library"], [5, "In library"]].forEach(([id, m]) => marks.movies.set(id as number, m as string));
    marks.showsByTmdb.set(4, "Partial");
    expect(readyRequests(mine, marks, new Set([requestKey(film(5))])).map((t) => t.title)).toEqual(["Film 1", "Show 4"]);
  });
  it("addresses are taken as people type them", () => {
    expect(normalize(" reely.example.com/ ")).toBe("http://reely.example.com");
    expect(normalize("https://r.example.com:8789")).toBe("https://r.example.com:8789");
    expect(isValid("192.168.1.20:8788")).toBe(true);
    expect(isValid("")).toBe(false);
  });
  it("a film and a show with the same number are different titles", () => {
    expect(requestKey(film(1))).not.toBe(requestKey(show(1)));
  });
  it("Plex has it by the ids its items carry", () => {
    expect(plexHas(film(603), new Set(["tmdb://603"]), new Set())).toBe(true);
    expect(plexHas(show(603), new Set(["tmdb://603"]), new Set())).toBe(false);
    expect(plexHas({ kind: "show", tmdbId: 0, tvdbId: 81189, title: "BB" }, new Set(), new Set(["tvdb://81189"]))).toBe(true);
  });
});

import { badgeFor, shownRows } from "../../src/api/reely";

describe("browsing rows, as the Fire TV shows them", () => {
  const film = (id: number): RequestTitle => ({ kind: "movie", tmdbId: id, tvdbId: 0, title: `Film ${id}` });
  const show = (id: number): RequestTitle => ({ kind: "show", tmdbId: id, tvdbId: 0, title: `Show ${id}` });
  it("leave out what's in the library, and drop a row left empty", () => {
    const marks = noMarks();
    marks.movies.set(1, "In library");
    marks.movies.set(2, "Downloading");
    marks.showsByTmdb.set(4, "Partial");
    const of = (t: RequestTitle) => badgeFor(t, marks, [], new Set(["tmdb://6"]), new Set());
    const rows = shownRows(
      [
        { id: "provider:max:movie", title: "Movies on Max", titles: [film(1), film(2), film(3)] },
        { id: "shows", title: "Trending Shows", titles: [show(4), show(5)] },
        { id: "topMovies", title: "Top Rated Movies", titles: [film(6)] },
      ],
      of,
    );
    expect(rows.map((r) => r.title)).toEqual(["Movies on Max", "Trending Shows"]);
    expect(rows[0].titles.map((t) => t.title)).toEqual(["Film 2", "Film 3"]);
    expect(rows[1].titles.map((t) => t.title)).toEqual(["Show 4", "Show 5"]);
  });
  it("what's held comes first, then my own request, then anybody's", () => {
    const title = film(550);
    const mine: RequestRecord[] = [{ id: 1, title, status: "approved", seasons: null }];
    const asked = noMarks();
    asked.requested.add("movie-550");
    expect(badgeFor(title, asked, mine, new Set(), new Set())).toBe("Approved");
    expect(badgeFor(title, asked, [], new Set(), new Set())).toBe("Requested");
    const held = noMarks();
    held.movies.set(550, "In library");
    expect(badgeFor(title, held, mine, new Set(), new Set())).toBe("In library");
  });
});
