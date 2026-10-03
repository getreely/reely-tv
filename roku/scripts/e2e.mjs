// The Roku app end to end in the BrightScript simulator: a debug build pointed at a
// stand-in plex.tv and Plex server, driven with Roku's own remote protocol (ECP), with
// screenshots along the way. Fails on anything the server wasn't asked, or a crash.
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync, existsSync, statSync } from "node:fs";
import { execFileSync } from "node:child_process";
import http from "node:http";
import { spawn } from "node:child_process";

const asked = [];
const timeline = [];
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");
const episode = (key, index, viewed = false) => ({
  ratingKey: key, type: "episode", title: `Episode ${index}`, index, parentIndex: 1, parentRatingKey: "s1",
  grandparentRatingKey: "show1", grandparentTitle: "Northbound", addedAt: 1000 + index, viewCount: viewed ? 1 : 0, duration: 60000,
});
let port = 0;
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  asked.push(url.pathname);
  const send = (v) => { res.writeHead(200, { "Content-Type": "application/json" }); res.end(JSON.stringify(v)); };
  const meta = (Metadata) => send({ MediaContainer: { Metadata } });
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
    case "/library/sections/1/genre": return send({ MediaContainer: { Directory: [{ key: "7", title: "Drama" }] } });
    case "/library/sections/1/decade": return send({ MediaContainer: { Directory: [{ key: "2020", title: "2020s" }] } });
    case "/library/sections/1/firstCharacter": return send({ MediaContainer: { Directory: [{ key: "L", title: "L", size: 1 }] } });
    case "/library/metadata/show1/children": return meta([{ ratingKey: "s1", type: "season", title: "Season 1", index: 1 }]);
    case "/library/metadata/s1/children": return meta([episode("e1", 1, true), episode("e2", 2), episode("e3", 3)]);
    case "/library/metadata/e2": return meta([{ ...episode("e2", 2), Media: [{ container: "mkv", videoCodec: "h264", audioCodec: "dca", Part: [{ key: "/library/parts/5/file.mkv" }] }] }]);
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
  await wait(3000);
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
  for (let i = 0; i < 5; i++) { await key("Right"); await wait(400); }
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
  expect("no crash", !/BRIGHTSCRIPT_CRASH|Runtime Error|Syntax Error/i.test(output));
} catch (error) {
  failures.push(String(error.message ?? error));
  console.error(error.message ?? error);
} finally {
  sim.kill("SIGKILL");
  server.close();
}
console.log(failures.length ? `${failures.length} failed` : "all passed");
process.exit(failures.length ? 1 : 0);
