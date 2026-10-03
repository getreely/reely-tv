// The Roku app end to end in the BrightScript simulator: a debug build pointed at a
// stand-in plex.tv and Plex server, driven with Roku's own remote protocol (ECP), with
// screenshots along the way. Fails on anything the server wasn't asked, or a crash.
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync, existsSync, statSync } from "node:fs";
import { execFileSync } from "node:child_process";
import http from "node:http";
import { spawn } from "node:child_process";

const asked = [];
const queries = [];
const timeline = [];
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");
const episode = (key, index, viewed = false) => ({
  ratingKey: key, type: "episode", title: `Episode ${index}`, index, parentIndex: 1, parentRatingKey: "s1",
  grandparentRatingKey: "show1", grandparentTitle: "Northbound", addedAt: 1000 + index, viewCount: viewed ? 1 : 0, duration: 60000,
});
let port = 0;
// A stand-in Xtream panel: a login, two categories, two channels and their guide.
const b64 = (t) => Buffer.from(t).toString("base64");
const schedule = Math.floor(Date.now() / 60000) * 60 - 900;
const panel = (url, send) => {
  const q = url.searchParams;
  if (q.get("username") !== "ann" || q.get("password") !== "pw") return send({ user_info: { auth: 0 } });
  const now = Math.floor(Date.now() / 1000);
  // Programmes placed round the moment the test began, so none starts or ends during it.
  const half = schedule;
  switch (q.get("action")) {
    case null: return send({ user_info: { auth: 1, status: "Active", max_connections: "2", active_cons: "0", exp_date: "1893456000" },
      server_info: { timezone: "UTC", time_now: new Date(now * 1000).toISOString().slice(0, 19).replace("T", " "), timestamp_now: now } });
    case "get_live_categories": return send([{ category_id: "1", category_name: "News" }, { category_id: "2", category_name: "Sport" }]);
    case "get_live_streams": {
      const all = [
        { stream_id: 101, num: 1, name: "Reely News", tv_archive: 1, tv_archive_duration: 3, category_id: "1", epg_channel_id: "news" },
        { stream_id: 102, num: 2, name: "Reely Sport", category_id: "2" }];
      return send(q.get("category_id") ? all.filter((c) => c.category_id === q.get("category_id")) : all);
    }
    case "get_short_epg": case "get_simple_data_table": {
      const id = q.get("stream_id");
      const name = id === "101" ? "News" : "Match";
      return send({ epg_listings: [
        { start_timestamp: String(half - 3600), stop_timestamp: String(half), title: b64(`Earlier ${name}`), description: b64("Before.") },
        { start_timestamp: String(half), stop_timestamp: String(half + 1800), title: b64(`Evening ${name}`), description: b64("What's on now.") },
        { start_timestamp: String(half + 1800), stop_timestamp: String(half + 3600), title: b64(`Late ${name}`), description: b64("Later.") }] });
    }
  }
  return send([]);
};
// A stand-in Reely: a session from the Plex sign-in, then its rows, a show's page and a request.
const reelyAsked = [];
let reelySignedIn = false;
const reelyApi = (req, res, url) => {
  const reply = (code, v, cookie) => {
    res.writeHead(code, { "Content-Type": "application/json", ...(cookie ? { "Set-Cookie": cookie } : {}) });
    res.end(JSON.stringify(v));
  };
  let body = "";
  req.on("data", (c) => { body += c; });
  req.on("end", () => {
    const path = url.pathname;
    if (path === "/api/v1/auth/plex/token") {
      reelySignedIn = JSON.parse(body || "{}").token === "account-token";
      return reelySignedIn ? reply(200, { status: "ok" }, "reely_session=abc; Path=/; HttpOnly") : reply(401, { error: "plex.tv: Unauthorized" });
    }
    // The simulator's HTTP, like a browser's, neither shows Set-Cookie nor sends Cookie, so
    // a signed-in simulator is let in without it; the cookie itself is unit-tested.
    if (!(req.headers.cookie ?? "").includes("reely_session=abc") && !reelySignedIn) return reply(401, { error: "login required" });
    switch (path) {
      case "/api/v1/explore": return reply(200, { imageBase: "https://image.tmdb.org/t/p",
        movies: [{ tmdbId: 603, kind: "movie", title: "The Matrix", year: 1999 }, { tmdbId: 438631, kind: "movie", title: "Dune", year: 2021 }],
        shows: [{ tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011 }] });
      case "/api/v1/movies": return reply(200, { movies: [{ tmdbId: 603, filePath: "/m/matrix.mkv" }] });
      case "/api/v1/shows": return reply(200, { shows: [] });
      case "/api/v1/requests":
        if (req.method === "POST") { reelyAsked.push(JSON.parse(body)); return reply(201, { status: "pending" }); }
        return reply(200, { requests: reelyAsked.map((r, i) => ({ id: i + 1, ...r, status: "pending" })) });
      case "/api/v1/preview/show/1399": return reply(200, { imageBase: "https://image.tmdb.org/t/p", inLibraries: [],
        preview: { kind: "show", tmdbId: 1399, title: "Game of Thrones", overview: "Seven noble families fight for the land.", genres: ["Drama"], status: "Ended",
          seasons: [{ number: 1, name: "Season 1" }, { number: 2, name: "Season 2" }, { number: 3, name: "Season 3" }] } });
      case "/api/v1/auth/me": return reply(200, { user: { id: 3, role: "user", mayAdd: false, defaultLibraryId: 2 } });
      case "/api/v1/libraries": return reply(200, { libraries: [{ id: 1, name: "Movies", kind: "movies" }, { id: 2, name: "TV", kind: "shows" }, { id: 3, name: "Kids TV", kind: "shows" }] });
    }
    reply(404, { error: "not here" });
  });
};
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  asked.push(url.pathname);
  queries.push(url.pathname + url.search);
  const send = (v) => { res.writeHead(200, { "Content-Type": "application/json" }); res.end(JSON.stringify(v)); };
  const meta = (Metadata) => send({ MediaContainer: { Metadata } });
  if (url.pathname === "/player_api.php") return panel(url, send);
  if (url.pathname.startsWith("/api/v1/")) return reelyApi(req, res, url);
  switch (url.pathname) {
    case "/api/v2/pins": return send({ id: 1, code: "R0KU" });
    case "/api/v2/pins/1": return send({ authToken: "account-token" });
    case "/api/v2/resources":
      return send([{ name: "Living Room", provides: "server", owned: true, accessToken: "server-token",
        connections: [{ uri: `http://127.0.0.1:${port}`, address: "127.0.0.1", port, local: true, relay: false }] }]);
    case "/identity": return send({});
    case "/library/sections": return send({ MediaContainer: { Directory: [{ key: "1", title: "Movies", type: "movie" }, { key: "2", title: "TV Shows", type: "show" }] } });
    case "/hubs": return send({ MediaContainer: { Hub: [{ Metadata: [{ ...episode("e2", 2), viewOffset: 20000, lastViewedAt: 5 }] }] } });
    case "/library/sections/1/all": return meta([{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, addedAt: 9 }]);
    case "/library/sections/2/all": {
      const start = Number(url.searchParams.get("X-Plex-Container-Start") ?? 0);
      const all = Array.from({ length: 333 }, (_, i) => episode(`n${i}`, i + 1));
      return meta(all.slice(start, start + 200));
    }
    case "/library/metadata/show1": return meta([{ ratingKey: "show1", type: "show", title: "Northbound", year: 2024, summary: "A long-haul driver.", OnDeck: { Metadata: [{ ratingKey: "e2" }] } }]);
    case "/library/metadata/show1/related": return send({ MediaContainer: { Hub: [{ Metadata: [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, addedAt: 9 }] }] } });
    case "/library/metadata/show1/extras": return meta([]);
    case "/playlists": return meta([{ ratingKey: "p1", type: "playlist", title: "Road Trip", leafCount: 2 }]);
    case "/library/sections/watchlist/all": return meta([{ guid: "plex://movie/low-orbit", type: "movie", title: "Low Orbit" }]);
    case "/library/all": return meta([{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, addedAt: 9, guid: "plex://movie/low-orbit" }]);
    case "/hubs/search": return send({ MediaContainer: { Hub: [
      { type: "movie", Metadata: [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, addedAt: 9 }] },
      { type: "actor", Metadata: [{ id: "77", tag: "Mara Lowe", type: "actor" }] }] } });
    case "/library/sections/1/genre": return send({ MediaContainer: { Directory: [{ key: "7", title: "Drama" }] } });
    case "/library/sections/1/decade": return send({ MediaContainer: { Directory: [{ key: "2020", title: "2020s" }] } });
    case "/library/sections/1/firstCharacter": return send({ MediaContainer: { Directory: [{ key: "L", title: "L", size: 1 }] } });
    case "/library/metadata/show1/children": return meta([{ ratingKey: "s1", type: "season", title: "Season 1", index: 1 }]);
    case "/library/metadata/s1/children": return meta([episode("e1", 1, true), episode("e2", 2), episode("e3", 3)]);
    case "/library/metadata/e2": return meta([{ ...episode("e2", 2), viewOffset: 20000,
      Media: [{ container: "mkv", videoCodec: "h264", audioCodec: "dca", Part: [{ id: 5, key: "/library/parts/5/file.mkv", Stream: [
        { id: 11, streamType: 2, displayTitle: "English (DTS 5.1)", selected: true, codec: "dca" },
        { id: 12, streamType: 2, displayTitle: "Commentary (AAC Stereo)", codec: "aac" },
        { id: 21, streamType: 3, displayTitle: "English (SRT)", codec: "srt", key: "/library/streams/21" }] }] }],
      Marker: [{ type: "intro", startTimeOffset: 0, endTimeOffset: 30000 }, { type: "credits", startTimeOffset: 50000, endTimeOffset: 60000 }],
      Chapter: [{ tag: "Cold open", startTimeOffset: 0 }, { tag: "Last stop", startTimeOffset: 52000 }] }]);
    case "/:/timeline": timeline.push(`${url.searchParams.get("state")}@${url.searchParams.get("ratingKey")}`); return send({});
    case "/photo/:/transcode": res.writeHead(200, { "Content-Type": "image/png" }); return res.end(png);
  }
  res.writeHead(404); res.end();
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
port = server.address().port;

// A debug build: the same app, with its Plex address open to the test.
execFileSync("npx", ["bsc", "--project", "bsconfig.json"], { stdio: "ignore" });
rmSync("build/debug", { recursive: true, force: true });
cpSync("build/staging", "build/debug", { recursive: true });
writeFileSync("build/debug/manifest", readFileSync("build/debug/manifest", "utf8").replace("bs_const=DEBUG=false", "bs_const=DEBUG=true"));
rmSync("build/debug.zip", { force: true });
execFileSync("zip", ["-qr", "../debug.zip", "."], { cwd: "build/debug" });
mkdirSync("shots", { recursive: true });

// Under a terminal, so Ctrl+S takes a screenshot.
const shot = "build/shot.png";
const sim = spawn("script", ["-qfc", `npx brs-cli -e -s ${shot} -c 0 -k plexTv=http://127.0.0.1:${port},discover=http://127.0.0.1:${port} build/debug.zip`, "/dev/null"], { stdio: ["pipe", "pipe", "pipe"] });
let output = "";
sim.stdout.on("data", (d) => { output += d.toString(); });
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (what, test, ms = 20000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (test()) return; await wait(200); }
  throw new Error(`Timed out waiting for ${what}. Asked: ${asked.join(" ")}\n${output.slice(-2000)}`);
};
const key = (k) => fetch(`http://127.0.0.1:8060/keypress/${k}`, { method: "POST" }).catch(() => undefined);
const snap = async (name) => {
  const before = existsSync(shot) ? statSync(shot).mtimeMs : 0;
  sim.stdin.write("\x13");
  await until(`screenshot ${name}`, () => existsSync(shot) && statSync(shot).mtimeMs > before, 8000);
  await wait(300);
  copyFileSync(shot, `shots/${name}.png`);
};
// The last place the debug build said the cursor was ("TAB home", "PILL Genre").
const lastSaid = (kind) => {
  const found = [...output.matchAll(new RegExp(`^${kind} (.+?)\\r?$`, "gm"))];
  return found.length ? found[found.length - 1][1].trim() : null;
};
// Along a row with the arrows until the cursor is on [target]: each press waited for, and
// pressed again if the simulator let it go while it was busy.
const moveTo = async (kind, target, direction) => {
  for (let tries = 0; tries < 20 && lastSaid(kind) !== target; tries++) {
    const before = lastSaid(kind);
    const count = output.length;
    await key(direction);
    const end = Date.now() + 1500;
    while (Date.now() < end && (output.length === count || lastSaid(kind) === before)) await wait(100);
    await wait(250);
  }
  if (lastSaid(kind) !== target) throw new Error(`Couldn't get to ${kind} ${target}; on ${lastSaid(kind)}`);
};
// Up until the cursor is on the tabs.
const toTabs = async () => {
  const count = () => (output.match(/^TAB /gm) ?? []).length;
  for (let i = 0; i < 8; i++) {
    const before = count();
    await key("Up");
    await wait(600);
    if (count() > before) return;
  }
  throw new Error("Couldn't get up to the tabs");
};
const failures = [];
const expect = (what, ok) => { if (!ok) failures.push(what); console.log(`${ok ? "PASS" : "FAIL"} ${what}`); };

try {
  await until("Home's rows to load", () => asked.includes("/hubs") && asked.filter((p) => p === "/library/sections/2/all").length >= 2);
  await wait(2500);
  await snap("roku-home");
  expect("signed in with the code, found the server", asked.includes("/api/v2/resources") && asked.includes("/identity"));
  expect("episodes paged until the show was counted whole", asked.filter((p) => p === "/library/sections/2/all").length === 2);
  expect("the Watchlist row finds the title on the server", asked.includes("/library/sections/watchlist/all") && asked.includes("/library/all"));
  // OK on Continue Watching opens the show on the episode it's up to.
  await key("Select");
  await until("the show's episodes", () => asked.includes("/library/metadata/s1/children"));
  await wait(2000);
  await snap("roku-show");
  // Resume: DTS sound the Roku can't play, so it goes to Plex's conversion.
  const before = asked.filter((p) => p === "/library/metadata/e2").length;
  const hubs = asked.filter((p) => p === "/hubs").length;
  await key("Select");
  await until("the file", () => asked.filter((p) => p === "/library/metadata/e2").length > before);
  // Resumed inside the intro: Skip Intro is offered, and OK skips it.
  await until("Skip Intro", () => output.includes("TRACE skip intro shown"), 15000).catch(() => undefined);
  await wait(800);
  await snap("roku-player-skip-intro");
  expect("Skip Intro is offered in the intro", output.includes("TRACE skip intro shown"));
  await key("Select");
  await until("the intro skipped", () => output.includes("TRACE skipped intro"), 5000).catch(() => undefined);
  expect("OK skips the intro", output.includes("TRACE skipped intro"));
  // The options panel: sound, subtitles, chapters and the sleep timer.
  await key("Down");
  await until("the options", () => output.includes("TRACE options shown"), 5000).catch(() => undefined);
  await wait(800);
  await snap("roku-player-options");
  expect("Down opens sound, subtitles, chapters and sleep", output.includes("TRACE options shown"));
  // A chapter, chosen: the panel closes and it plays on. (The simulator doesn't play
  // video, so Up Next at the credits is tested in tests/player.test.brs instead.)
  await moveTo("TRACE option", "chapter:52000", "Down");
  await key("Select");
  await wait(800);
  await key("Back");
  await until("the stop told to Plex", () => timeline.includes("stopped@e2"), 10000);
  expect("stopping tells Plex where it got to", timeline.includes("stopped@e2"));
  // Back on the show's page, its episodes asked again so what's watched is marked.
  await until("the show's episodes again", () => asked.filter((p) => p === "/library/metadata/s1/children").length >= 2, 10000);
  await wait(1500);
  await snap("roku-back-on-show");
  expect("Back from the player returns to the show with its episodes", asked.filter((p) => p === "/library/metadata/s1/children").length >= 2);
  await until("Home asked again", () => asked.filter((p) => p === "/hubs").length > hubs, 10000);
  expect("Home refreshes after watching", asked.filter((p) => p === "/hubs").length > hubs);
  await key("Back");
  await wait(1500);
  await snap("roku-home-again");
  // Up to the tabs; moving onto Movies opens it.
  const moviePages = asked.filter((p) => p === "/library/sections/1/all").length;
  await key("Up");
  await wait(500);
  await key("Right");
  await until("the Movies library", () => asked.includes("/library/sections/1/genre") && asked.filter((p) => p === "/library/sections/1/all").length > moviePages, 10000);
  await wait(2000);
  await snap("roku-movies");
  expect("Movies opens on arriving at its tab", asked.includes("/library/sections/1/firstCharacter"));
  // Along to Settings, which opens on OK.
  await moveTo("TAB", "settings", "Right");
  await key("Select");
  await wait(1500);
  await snap("roku-settings");
  // Down to Skip intros, and on.
  await key("Down"); await wait(300);
  await key("Down"); await wait(300);
  await key("Left"); await wait(300);
  await key("Select");
  await wait(1200);
  await snap("roku-settings-skip-intros");
  await key("Back");
  await wait(1500);
  await snap("roku-home-from-settings");
  // The poster menu, on the * key, as Roku apps have it.
  await key("Info");
  await wait(1000);
  await snap("roku-poster-menu");
  expect("no crash after the poster menu", !/BRIGHTSCRIPT_CRASH|Runtime Error|Syntax Error|Error processing/i.test(output));
  await key("Back");
  await wait(800);
  // Search: typed on the keyboard, the results in rows.
  await key("Up"); await wait(600);
  await moveTo("TAB", "search", "Left");
  await key("Select");
  await wait(1200);
  for (const c of "low") { await key(`Lit_${c}`); await wait(250); }
  await until("the search", () => queries.some((q) => q.startsWith("/hubs/search") && q.includes("query=low")), 10000).catch(() => undefined);
  await wait(2000);
  await snap("roku-search");
  expect("searching asks the server as it's typed", queries.some((q) => q.startsWith("/hubs/search") && q.includes("query=low")));
  // Movies, the grid, narrowed by genre.
  await key("Up"); await wait(600);
  await moveTo("TAB", "movies", "Right");
  await wait(1500);
  await key("Down"); await wait(700);
  await moveTo("PILL", "All", "Right");
  await key("Select"); await wait(1500);
  await key("Down"); await wait(700);
  await moveTo("PILL", "Genre", "Right");
  await key("Select"); await wait(1500);
  await snap("roku-genre-chooser");
  await key("Down"); await wait(600);
  await key("Select");
  await until("the grid narrowed by genre", () => queries.some((q) => q.startsWith("/library/sections/1/all") && q.includes("genre=7")), 10000).catch(() => undefined);
  await wait(1500);
  await snap("roku-movies-drama");
  expect("a genre narrows the library", queries.some((q) => q.startsWith("/library/sections/1/all") && q.includes("genre=7")));
  // Live TV: signed in to the provider with the on-screen keyboard.
  await toTabs();
  await moveTo("TAB", "live", "Right");
  await wait(1200);
  await key("Down"); await wait(800);
  const typeInto = async (field, text) => {
    await key("Select");
    await until(`the keyboard for ${field}`, () => lastSaid("TRACE typing") === field, 5000);
    await wait(800);
    // The dialog opens on its OK button: up into the keys, type, down to OK.
    await key("Up"); await wait(400);
    for (const ch of text) { await key(`Lit_${/[a-z0-9.]/i.test(ch) ? ch : ch === ":" ? ":" : encodeURIComponent(ch)}`); await wait(350); }
    await wait(400);
    for (let i = 0; i < 4; i++) { await key("Down"); await wait(450); }
    await snap(`roku-live-typing-${field}`);
    await key("Select");
    await wait(900);
  };
  await typeInto("host", `127.0.0.1:${port}`);
  await key("Down"); await wait(500);
  await typeInto("user", "ann");
  await key("Down"); await wait(500);
  await typeInto("password", "pw");
  await snap("roku-live-sign-in");
  await key("Down"); await wait(500);
  await key("Select");
  await until("signed in to live TV", () => output.includes("TRACE live signed in"), 15000).catch(() => undefined);
  expect("signs in to the provider", output.includes("TRACE live signed in"));
  await until("the channels' guide", () => queries.some((q) => q.includes("action=get_short_epg")), 15000).catch(() => undefined);
  await wait(2500);
  await snap("roku-live-channels");
  expect("channels with what's on", queries.some((q) => q.includes("action=get_live_streams")) && queries.some((q) => q.includes("action=get_short_epg")));
  expect("opens on All channels when there are no favorites", lastSaid("TRACE category") === "all");
  // A favorite, with ✱ on the channel.
  await key("Right"); await wait(600);
  await key("Info");
  await until("a favorite", () => output.includes("TRACE favorited 101"), 5000).catch(() => undefined);
  expect("✱ adds a channel to Favorites", output.includes("TRACE favorited 101"));
  await wait(800);
  await snap("roku-live-channels-on");
  // Watching: the banner, right to the next channel, ✱ for its menu, Back to the list.
  await key("Select");
  await until("the channel", () => output.includes("TRACE tuned 101"), 8000).catch(() => undefined);
  expect("OK watches the channel", output.includes("TRACE tuned 101"));
  await wait(1200);
  await snap("roku-live-player");
  await key("Right");
  await until("the next channel", () => output.includes("TRACE tuned 102"), 5000).catch(() => undefined);
  expect("right changes channel", output.includes("TRACE tuned 102"));
  await key("Left"); await wait(800);
  await key("Info");
  await until("the channel's menu", () => output.includes("TRACE live menu shown"), 5000).catch(() => undefined);
  await wait(600);
  await snap("roku-live-player-menu");
  expect("✱ in the player: Favorites and Start over", output.includes("TRACE live menu shown"));
  await key("Back");
  await until("the menu closed", () => output.includes("TRACE live menu hidden"), 5000).catch(() => undefined);
  await wait(500);
  await key("Back");
  await until("off the channel", () => output.includes("TRACE live stopped"), 5000).catch(() => undefined);
  expect("Back closes the menu, then leaves the channel", output.includes("TRACE live stopped"));
  await wait(1200);
  // The guide: what's to come, a reminder; what's over, from the archive.
  await key("Up"); await wait(800);
  await moveTo("PILL", "Guide", "Right");
  await key("Select");
  await until("the guide's grid", () => queries.some((q) => q.includes("action=get_simple_data_table")), 10000).catch(() => undefined);
  await wait(2500);
  await snap("roku-live-guide");
  expect("the guide asks for each channel's listing", queries.some((q) => q.includes("action=get_simple_data_table")));
  // Right to what's to come: a reminder. Left to what's over: from the channel's archive.
  await moveTo("TRACE guide on", "Late News", "Right");
  await key("Select");
  await until("a reminder", () => output.includes("TRACE reminder on Late News"), 5000).catch(() => undefined);
  expect("OK on what's to come sets a reminder", output.includes("TRACE reminder on Late News"));
  await wait(600);
  await snap("roku-live-guide-reminder");
  await moveTo("TRACE guide on", "Earlier News", "Left");
  await key("Select");
  await until("catch-up", () => output.includes("TRACE tuned 101 from the archive"), 5000).catch(() => undefined);
  expect("OK on what's over plays it from the archive", output.includes("TRACE tuned 101 from the archive"));
  await wait(1500);
  expect("from the panel's timeshift address", /^TRACE catch-up from \/timeshift\/ann\/pw\/60\/\d{4}-\d{2}-\d{2}:\d{2}-\d{2}\/101\.ts/m.test(output));
  await key("Back"); await wait(1200);
  // Requests: connect to Reely, its rows less what's in the library, and ask for a show.
  // Back from a tab's page goes Home, as on the Fire TV; up from there to the tabs.
  await key("Back"); await wait(1500);
  await toTabs();
  await moveTo("TAB", "requests", "Right");
  await wait(1200);
  await key("Down"); await wait(800);
  await typeInto("address", `127.0.0.1:${port}`);
  await key("Down"); await wait(500);
  await key("Select");
  await until("Reely's rows", () => output.includes("TRACE reely rows"), 15000).catch(() => undefined);
  expect("connects to Reely with the Plex sign-in", output.includes("TRACE reely connected"));
  await wait(2000);
  await snap("roku-requests");
  // Down from the first row (Dune: The Matrix is in the library) to the shows'.
  await key("Down"); await wait(800);
  await key("Select");
  await until("the show's page", () => output.includes("TRACE reely title"), 10000).catch(() => undefined);
  expect("a title's page, and it can be asked for", output.includes("TRACE reely title can ask"));
  await wait(1200);
  await snap("roku-request-title");
  // The libraries (TV, the default, and Kids TV), then the seasons: Season 1 off.
  await key("Down"); await wait(600);
  await key("Down"); await wait(600);
  await key("Select"); await wait(800);
  await key("Up"); await wait(600);
  await key("Up"); await wait(600);
  await key("Select");
  await until("asked", () => output.includes("TRACE reely asked"), 10000).catch(() => undefined);
  await wait(1000);
  await snap("roku-request-sent");
  const sent = reelyAsked[0];
  console.log("Reely was asked: " + JSON.stringify(sent));
  expect("asks for the show, the seasons chosen, in the default library",
    !!sent && sent.tmdbId === 1399 && JSON.stringify(sent.seasons) === "[2,3]" && sent.libraryId === 2);
  expect("no crash", !/BRIGHTSCRIPT_CRASH|Runtime Error|Syntax Error/i.test(output));
} catch (error) {
  failures.push(String(error.message ?? error));
  console.error(error.message ?? error);
} finally {
  sim.kill("SIGKILL");
  server.close();
}
if (failures.length) console.log("Last traces:\n" + (output.match(/^TRACE .*$/gm) ?? []).slice(-25).join("\n"));
console.log(failures.length ? `${failures.length} failed` : "all passed");
process.exit(failures.length ? 1 : 0);
