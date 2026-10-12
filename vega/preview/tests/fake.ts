import type { Page, Route } from "@playwright/test";

/*
 * plex.tv and one server, as the LG app's tests have them (webos/tests/e2e), answering
 * Vega's screens in the preview.
 */

export const SERVER = "http://192.168.1.20:32400";

/** plex.tv and one server, answering as they do; what was told to the server is kept. */
export async function fakePlex(page: Page, options: { tour?: boolean; many?: boolean; big?: boolean; home?: boolean } = {}) {
  const timeline: string[] = [];
  // The remote's tour seen already, except where it's what's tested; kept across a reload.
  if (!options.tour) {
    await page.addInitScript(() => {
      if (!localStorage.getItem("reely:prefs")) localStorage.setItem("reely:prefs", JSON.stringify({ tourSeen: true }));
    });
  }
  let claims = 0;
  const json = (route: Route, value: unknown) =>
    route.fulfill({ status: 200, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify(value) });
  const meta = (route: Route, Metadata: unknown[]) => json(route, { MediaContainer: { Metadata } });
  const episode = (key: string, index: number, viewed = false) => ({
    ratingKey: key, type: "episode", title: `Episode ${index}`, index, parentIndex: 1, parentRatingKey: "s1", grandparentRatingKey: "show1",
    grandparentTitle: "Northbound", addedAt: 1000 + index, viewCount: viewed ? 1 : 0, duration: 60_000, summary: `Episode ${index} of Northbound.`,
    art: "/art/show1", thumb: `/thumb/${key}`,
  });
  await page.route("https://plex.tv/**", async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "*" } });
    if (url.pathname === "/api/v2/pins") return json(route, { id: 7, code: "WXYZ" });
    if (url.pathname.startsWith("/api/v2/pins/")) return json(route, { authToken: ++claims >= 2 ? "account-token" : null });
    if (url.pathname === "/api/v2/user") return json(route, options.home ? { uuid: "u1", title: "Sam" } : { uuid: "me", title: "Taylor" });
    if (url.pathname === "/api/v2/home/users") {
      return json(route, { users: options.home ? [
        { uuid: "u1", title: "Sam", admin: true, protected: false, restricted: false, thumb: null },
        { uuid: "u2", title: "Alex", admin: false, protected: true, restricted: false, thumb: null },
        { uuid: "u3", title: "Kids", admin: false, protected: false, restricted: true, thumb: null },
      ] : [] });
    }
    if (url.pathname === "/api/v2/resources") {
      return json(route, [{ name: "Living Room", provides: "server", owned: true, accessToken: "server-token",
        connections: [{ uri: SERVER, address: "192.168.1.20", port: 32400, local: true, relay: false }] }]);
    }
    return route.fulfill({ status: 404 });
  });
  await page.route(`${SERVER}/**`, async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "*" } });
    if (path === "/identity") return json(route, {});
    // Pictures: a dusk sky over hills, coloured by what's asked for, so the screenshots
    // show artwork where a real library would have it.
    // The scrub bar's preview pictures, one for each moment asked for.
    const previewAt = path.match(/^\/library\/parts\/\d+\/indexes\/sd\/(\d+)$/);
    if (path === "/photo/:/transcode" || previewAt) {
      const of = previewAt ? `preview${Math.floor(Number(previewAt[1]) / 60_000)}` : url.searchParams.get("url") ?? "";
      const hue = [...of].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 90" preserveAspectRatio="none">
        <defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="hsl(${hue},45%,18%)"/><stop offset="1" stop-color="hsl(${(hue + 30) % 360},60%,55%)"/></linearGradient></defs>
        <rect width="160" height="90" fill="url(#s)"/><circle cx="110" cy="40" r="9" fill="hsl(${(hue + 40) % 360},80%,80%)"/>
        <path d="M0 70 Q40 52 80 66 T160 60 V90 H0Z" fill="hsl(${hue},35%,12%)"/></svg>`;
      return route.fulfill({ status: 200, contentType: "image/svg+xml", body: svg });
    }
    if (path === "/library/sections") return json(route, { MediaContainer: { Directory: [{ key: "1", title: "Movies", type: "movie" }, { key: "2", title: "TV Shows", type: "show" }] } });
    // A full library, for rows that run off the right of the screen.
    const titles = ["Low Orbit", "Glasshouse", "The Long Field", "Saltwater", "Night Shift", "Paper Moons", "Ironside Road", "Kestrel", "Blue Hour",
      "Undertow", "The Quiet Year", "Harbour Lights", "Cinder", "Wild Acre", "Static", "Northern Line", "Copperhead", "The Last Ferry", "Driftwood",
      "Marrow", "Afterglow", "Tidewater", "Second Sun", "Juniper"];
    const movies = titles.map((title, i) => ({ ratingKey: `m${i + 1}`, type: "movie", title, year: 2025 - (i % 9), addedAt: 100 - i,
      thumb: `/thumb/m${i + 1}`, art: `/art/m${i + 1}`, summary: `${title}: a film in the library.`, duration: 6_000_000 }));
    if ((options.many || options.big) && path === "/hubs") {
      return json(route, { MediaContainer: { Hub: [{ Metadata: [
        ...[2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => ({ ...episode(`e${n}`, n), viewOffset: 20_000, lastViewedAt: 20 - n })),
        ...movies.slice(0, 14).map((m, i) => ({ ...m, viewOffset: 900_000, lastViewedAt: 5 - i })),
      ] }] } });
    }
    // A library big enough for the A–Z rail: the same films again under other names.
    const big = [...movies, ...titles.flatMap((title, i) => ["Return to ", "After "].map((pre, j) => ({ ...movies[i], ratingKey: `b${j}${i}`, title: `${pre}${title}` })))]
      .sort((a, b) => a.title.localeCompare(b.title));
    if (options.big && path === "/library/sections/1/firstCharacter") {
      const counts = new Map<string, number>();
      for (const m of big) counts.set(m.title[0].toUpperCase(), (counts.get(m.title[0].toUpperCase()) ?? 0) + 1);
      return json(route, { MediaContainer: { Directory: [...counts].map(([title, size]) => ({ key: title, title, size })) } });
    }
    if (options.big && path === "/library/sections/1/collections") {
      return meta(route, [{ ratingKey: "c1", type: "collection", title: "Space Films", childCount: 3, thumb: "/thumb/m1" }, { ratingKey: "c2", type: "collection", title: "Sea Stories", childCount: 4, thumb: "/thumb/m4" }]);
    }
    if (options.big && path === "/library/sections/1/all" && !url.searchParams.get("genre") && !url.searchParams.get("actor")) {
      const start = Number(url.searchParams.get("X-Plex-Container-Start") ?? 0);
      const size = Number(url.searchParams.get("X-Plex-Container-Size") ?? big.length);
      return json(route, { MediaContainer: { totalSize: big.length, size: Math.min(size, big.length - start), Metadata: big.slice(start, start + size) } });
    }
    if ((options.many || options.big) && path === "/library/sections/1/all" && !url.searchParams.get("genre") && !url.searchParams.get("actor")) return meta(route, movies);
    if (path === "/hubs") return json(route, { MediaContainer: { Hub: [{ Metadata: [episode("e2", 2)].map((e) => ({ ...e, viewOffset: 20_000, lastViewedAt: 5 })) }] } });
    if (options.big && path === "/playlists") return meta(route, [{ ratingKey: "p1", type: "playlist", title: "Weekend Films", leafCount: 4, duration: 24_000_000, composite: "/thumb/m3" }]);
    if (options.big && path === "/playlists/p1/items") return meta(route, movies.slice(2, 6));
    if (options.big && path === "/library/metadata/c1") return meta(route, [{ ratingKey: "c1", type: "collection", title: "Space Films", childCount: 3, thumb: "/thumb/m1", art: "/art/m1", summary: "Films set above the atmosphere." }]);
    if (options.big && path === "/library/collections/c1/children") return meta(route, movies.slice(0, 3));
    if (path === "/playlists") return meta(route, []);
    if (url.searchParams.get("actor") === "77") {
      return meta(route, path === "/library/sections/1/all" ? [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025 }] : []);
    }
    if (path === "/library/sections/1/genre") return json(route, { MediaContainer: { Directory: [{ key: "7", title: "Drama" }, { key: "8", title: "Thriller" }] } });
    if (path === "/library/sections/1/decade") return json(route, { MediaContainer: { Directory: [{ key: "2020", title: "2020s" }, { key: "2010", title: "2010s" }] } });
    if (path === "/library/sections/1/all" && url.searchParams.get("genre") === "7") return meta(route, [{ ratingKey: "m2", type: "movie", title: "Glasshouse", year: 2024, thumb: "/thumb/m2" }]);
    if (path === "/library/sections/1/all") return meta(route, [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, addedAt: 9, thumb: "/thumb/m1", art: "/art/m1", summary: "A satellite engineer is stranded on the station she built." }, { ratingKey: "m2", type: "movie", title: "Glasshouse", year: 2024, addedAt: 8, thumb: "/thumb/m2", art: "/art/m2" }]);
    if (path === "/library/sections/2/all") {
      // 333 new episodes of one show: the count must sit on one line in its circle.
      const start = Number(url.searchParams.get("X-Plex-Container-Start") ?? 0);
      const size = Number(url.searchParams.get("X-Plex-Container-Size") ?? 333);
      const all = Array.from({ length: 333 }, (_, i) => episode(`n${i}`, i + 1));
      return meta(route, all.slice(start, start + size));
    }
    if (path === "/library/metadata/show1") return meta(route, [{ ratingKey: "show1", type: "show", title: "Northbound", year: 2024, art: "/art/show1", thumb: "/thumb/show1",
      rating: 8.1, audienceRating: 8.6, contentRating: "TV-14", studio: "Harbourside", childCount: 1,
      summary: "A long-haul driver takes the jobs nobody else will, on roads that don't appear on any map, and starts to notice who keeps booking her." }]);
    if (path === "/library/metadata/m1") return meta(route, [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, art: "/art/m1", thumb: "/thumb/m1",
      rating: 7.4, audienceRating: 8.1, contentRating: "PG-13", studio: "Northlight", duration: 6_000_000, guid: "plex://movie/lo",
      summary: "A satellite engineer is stranded on the station she built, and has to talk the ground crew through bringing her home before the orbit decays.",
      Genre: [{ tag: "Drama" }, { tag: "Thriller" }], Director: [{ tag: "Mara Quinn" }], Writer: [{ tag: "Tom Ellis" }],
      Role: [{ id: "77", tag: "Ana Orbit", role: "Dr. Vale" }, { id: "78", tag: "Joe Field", role: "Flight" }],
      Chapter: [{ tag: "Launch", startTimeOffset: 0, endTimeOffset: 900_000 }, { tag: "The station", startTimeOffset: 900_000, endTimeOffset: 3_000_000 }, { tag: "Coming home", startTimeOffset: 3_000_000, endTimeOffset: 6_000_000 }],
      Media: [
        { videoResolution: "4k", container: "mkv", videoCodec: "hevc", audioCodec: "eac3", Part: [{ id: 1, key: "/library/parts/1/file.mkv", Stream: [
          { id: 11, streamType: 2, displayTitle: "English (EAC3 5.1)", language: "English", selected: true, codec: "eac3", channels: 6 },
          { id: 12, streamType: 2, displayTitle: "Commentary (AAC Stereo)", language: "English", codec: "aac", channels: 2 },
          { id: 21, streamType: 3, displayTitle: "English (SRT)", language: "English", codec: "srt", key: "/library/streams/21" },
          { id: 22, streamType: 3, displayTitle: "Spanish (SRT)", language: "Spanish", codec: "srt", key: "/library/streams/22" },
        ] }] },
        { videoResolution: "1080", container: "mp4", videoCodec: "h264", audioCodec: "aac", Part: [{ id: 2, key: "/library/parts/2/file.mp4" }] },
      ] }]);
    if (path === "/library/streams/21") {
      return route.fulfill({ status: 200, contentType: "text/plain", headers: { "Access-Control-Allow-Origin": "*" }, body: "1\n00:00:00,000 --> 00:30:00,000\nStation, this is ground. Do you read me?\n" });
    }
    if (path === "/library/metadata/m1/related") return json(route, { MediaContainer: { Hub: [{ Metadata: movies.slice(1, 8) }] } });
    if (path === "/library/metadata/show1/children") return meta(route, [{ ratingKey: "s1", type: "season", title: "Season 1", index: 1 }]);
    if (path === "/library/metadata/s1/children") return meta(route, [episode("e1", 1, true), episode("e2", 2), episode("e3", 3)]);
    if (path === "/library/metadata/show1/related") return json(route, { MediaContainer: {} });
    if (path === "/hubs/search") {
      return json(route, { MediaContainer: { Hub: [
        { type: "movie", Metadata: [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025 }] },
        { type: "actor", Directory: [{ id: "77", tag: "Ana Orbit" }] },
      ] } });
    }
    if (path === "/library/metadata/e2") {
      return meta(route, [{ ...episode("e2", 2), duration: 600_000,
        Marker: [{ type: "intro", startTimeOffset: 0, endTimeOffset: 90_000 }, { type: "credits", startTimeOffset: 120_000, endTimeOffset: 600_000 }],
        Media: [{ container: "mkv", videoCodec: "h264", audioCodec: "dca", Part: [{ id: 5, key: "/library/parts/5/file.mkv", Stream: [
          { id: 50, streamType: 1, codec: "h264", profile: "high", level: 41, width: 1920, height: 1080, bitDepth: 8, frameRate: 23.976 },
          { id: 51, streamType: 2, displayTitle: "English (DTS 5.1)", language: "English", selected: true, codec: "dca", channels: 6 },
        ] }] }] }]);
    }
    if (path === "/library/metadata/e3") {
      return meta(route, [{ ...episode("e3", 3), Media: [{ container: "mp4", videoCodec: "h264", audioCodec: "aac", Part: [{ id: 6, key: "/library/parts/6/file.mp4", Stream: [] }] }] }]);
    }
    if (path.startsWith("/library/parts/") && route.request().method() === "PUT") {
      timeline.push(`choose${url.search.replace(/&?allParts=1/, "")}`);
      return json(route, {});
    }
    if (path === "/:/scrobble" || path === "/actions/removeFromContinueWatching") {
      timeline.push(`${path}?${url.searchParams.get("key") ?? url.searchParams.get("ratingKey")}`);
      return json(route, {});
    }
    if (path === "/:/timeline") {
      timeline.push(`${url.searchParams.get("state")}@${url.searchParams.get("ratingKey")}`);
      return json(route, {});
    }
    return route.fulfill({ status: 404, headers: { "Access-Control-Allow-Origin": "*" } });
  });
  return { timeline };
}


/** Signed in already: the account's token kept, as on a stick that's been used. */
export async function signedIn(page: Page) {
  await page.addInitScript(() => {
    if (!localStorage.getItem("reely:plexToken")) localStorage.setItem("reely:plexToken", "account-token");
  });
}

export const press = (page: Page, key: string, times = 1) => (async () => { for (let i = 0; i < times; i++) await page.keyboard.press(key); })();

export const PANEL = "http://panel.example:8080";

/** An Xtream Codes provider with two categories and two channels, one keeping an archive. */
export async function fakePanel(page: Page) {
  const cors = { "Access-Control-Allow-Origin": "*" };
  const now = Math.floor(Date.now() / 1000);
  await page.route(`${PANEL}/**`, async (route) => {
    const url = new URL(route.request().url());
    const send = (v: unknown) => route.fulfill({ status: 200, contentType: "application/json", headers: cors, body: JSON.stringify(v) });
    if (url.pathname === "/player_api.php") {
      const action = url.searchParams.get("action");
      if (!action) return send({ user_info: { auth: 1, status: "Active", max_connections: "2", active_cons: "0" }, server_info: { timezone: "UTC" } });
      if (action === "get_live_categories") return send([{ category_id: "1", category_name: "News" }, { category_id: "2", category_name: "Sport" }, { category_id: "3", category_name: "Movies" }, { category_id: "4", category_name: "Kids" }, { category_id: "5", category_name: "Documentaries" }]);
      if (action === "get_live_streams") {
        return send([
          { stream_id: 101, num: 101, name: "News 24", stream_icon: "", epg_channel_id: "news", tv_archive: 1, tv_archive_duration: 3 },
          { stream_id: 102, num: 102, name: "World Report", stream_icon: "", epg_channel_id: "world" },
          { stream_id: 103, num: 103, name: "UK | Business Now", stream_icon: "", epg_channel_id: "biz" },
        ]);
      }
      if (action === "get_simple_data_table" || action === "get_short_epg") {
        return send({ epg_listings: [
          { title: btoa("Morning Briefing"), description: btoa("The day's news."), start_timestamp: String(now - 4200), stop_timestamp: String(now - 600) },
          { title: btoa("The Evening Report"), description: btoa("The day's stories, and what they mean."), start_timestamp: String(now - 600), stop_timestamp: String(now + 1200) },
          { title: btoa("Late Edition"), description: "", start_timestamp: String(now + 1200), stop_timestamp: String(now + 4800) },
        ] });
      }
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

/** Signed in to the provider already, as a stick that's watched it before. */
export async function signedInLive(page: Page) {
  await page.addInitScript((panel) => {
    if (!localStorage.getItem("reely:xtream")) localStorage.setItem("reely:xtream", JSON.stringify({ base: panel, username: "me", password: "secret" }));
  }, PANEL);
}

/** Reely, the owner's requesting app, as the LG app's tests have it: what's asked for is kept. */
export async function fakeReely(page: Page) {
  const REELY = "http://192.168.1.5:8788";
  const asked: unknown[] = [];
  const cors = { "Access-Control-Allow-Origin": "http://localhost:5199", "Access-Control-Allow-Credentials": "true", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "*" };
  const send = (route: Route, value: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", headers: cors, body: JSON.stringify(value) });
  await page.route(`${REELY}/**`, async (route) => {
    const req = route.request();
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204, headers: cors });
    const path = new URL(req.url()).pathname;
    if (path === "/api/v1/auth/plex/token") return send(route, { status: "ok" });
    if (path === "/api/v1/explore") {
      return send(route, {
        movies: [
          { tmdbId: 603, kind: "movie", title: "The Matrix", year: 1999 },
          { tmdbId: 27205, kind: "movie", title: "Inception", year: 2010, poster: null },
          { tmdbId: 1, kind: "movie", title: "Arrival", year: 2016 },
        ],
        shows: [{ tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011 }, { tmdbId: 2, kind: "show", title: "Severance", year: 2022 }],
      });
    }
    if (path === "/api/v1/movies") return send(route, { movies: [{ tmdbId: 603, filePath: "/m.mkv" }] });
    if (path === "/api/v1/shows") return send(route, { shows: [{ tmdbId: 1399, onDisk: 10, aired: 73, wanted: 63 }] });
    if (path === "/api/v1/requests" && req.method() === "GET") return send(route, { requests: [] });
    if (path === "/api/v1/auth/me") return send(route, { user: { role: "user", defaultLibraryId: 3 } });
    if (path === "/api/v1/libraries") return send(route, { libraries: [{ id: 3, name: "TV", kind: "shows" }] });
    if (path === "/api/v1/preview/show/1399") {
      return send(route, { inLibraries: [], preview: { tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011, overview: "Nine noble families fight for control over the lands of Westeros.",
        seasons: [{ number: 1, name: "Season 1", episodes: [{}] }, { number: 2, name: "Season 2", episodes: [{}] }, { number: 3, name: "Season 3", episodes: [{}] }] } });
    }
    if (path === "/api/v1/requests" && req.method() === "POST") {
      asked.push(JSON.parse(req.postData() ?? "{}"));
      return send(route, { id: 1, status: "pending" }, 201);
    }
    return send(route, { error: "no" }, 404);
  });
  return { asked };
}
