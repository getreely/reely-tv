import { expect, test, type Page, type Route } from "@playwright/test";

const SERVER = "http://192.168.1.20:32400";

/** plex.tv and one server, answering as they do; what was told to the server is kept. */
async function fakePlex(page: Page, options: { tour?: boolean } = {}) {
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
    if (url.pathname === "/api/v2/user") return json(route, { uuid: "me", title: "Taylor" });
    if (url.pathname === "/api/v2/home/users") return json(route, { users: [] });
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
    if (path === "/photo/:/transcode") {
      const of = url.searchParams.get("url") ?? "";
      const hue = [...of].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 90" preserveAspectRatio="none">
        <defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="hsl(${hue},45%,18%)"/><stop offset="1" stop-color="hsl(${(hue + 30) % 360},60%,55%)"/></linearGradient></defs>
        <rect width="160" height="90" fill="url(#s)"/><circle cx="110" cy="40" r="9" fill="hsl(${(hue + 40) % 360},80%,80%)"/>
        <path d="M0 70 Q40 52 80 66 T160 60 V90 H0Z" fill="hsl(${hue},35%,12%)"/></svg>`;
      return route.fulfill({ status: 200, contentType: "image/svg+xml", body: svg });
    }
    if (path === "/library/sections") return json(route, { MediaContainer: { Directory: [{ key: "1", title: "Movies", type: "movie" }, { key: "2", title: "TV Shows", type: "show" }] } });
    if (path === "/hubs") return json(route, { MediaContainer: { Hub: [{ Metadata: [episode("e2", 2)].map((e) => ({ ...e, viewOffset: 20_000, lastViewedAt: 5 })) }] } });
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
      return meta(route, [{ ...episode("e2", 2), Media: [{ container: "mkv", videoCodec: "h264", audioCodec: "dca", Part: [{ id: 5, key: "/library/parts/5/file.mkv", Stream: [] }] }] }]);
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

const press = (page: Page, key: string, times = 1) => (async () => { for (let i = 0; i < times; i++) await page.keyboard.press(key); })();

test("signs in with a code, browses with the remote, plays and comes back", async ({ page }) => {
  const plex = await fakePlex(page);
  await page.goto("/");
  await expect(page.getByText("Sign in to watch your library")).toBeVisible();
  await press(page, "Enter");
  await expect(page.getByTestId("code")).toHaveText("WXYZ");
  await expect(page.locator(".qr svg")).toBeVisible();
  await page.screenshot({ path: "shots/lg-sign-in.png" });

  // Signed in once plex.tv says so: Home fills.
  await expect(page.getByText("Continue Watching")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("Recently Added Movies")).toBeVisible();

  await page.waitForTimeout(400);
  await page.screenshot({ path: "shots/lg-home.png" });
  // The 333 badge: one line, inside its circle.
  const badge = page.locator(".badge", { hasText: "333" });
  await expect(badge).toBeVisible();
  const box = await badge.boundingBox();
  expect(box!.height).toBeLessThanOrEqual(box!.width + 1);
  const fits = await badge.evaluate((el) => el.scrollWidth <= el.clientWidth && el.scrollHeight <= el.clientHeight);
  expect(fits).toBe(true);

  // The cursor starts on Continue Watching; OK opens the show on the episode it's up to.
  await expect(page.locator(".card:focus .title")).toHaveText("Northbound");
  await press(page, "Enter");
  await expect(page.getByRole("heading", { name: "Northbound" })).toBeVisible();
  await expect(page.getByText("Up next  ·  S1 · E2  ·  Episode 2")).toBeVisible();
  await expect(page.locator(".round:focus .label")).toHaveText("Play");
  await page.screenshot({ path: "shots/lg-show.png" });

  // Play: DTS sound the TV can't play, so Plex converts it.
  const transcode = page.waitForRequest((r) => r.url().includes("/video/:/transcode/universal/start.m3u8"));
  await press(page, "Enter");
  await expect(page.locator(".player")).toBeVisible();
  await transcode;

  // Back stops, tells Plex, and lands back on the show.
  await press(page, "Escape");
  await expect(page.getByRole("heading", { name: "Northbound" })).toBeVisible();
  await expect.poll(() => plex.timeline).toContain("stopped@e2");
  // Down to the episodes: the one under the cursor whole, its length too, the page scrolled
  // far enough for it grown.
  for (let i = 0; i < 6 && !(await page.locator(".card.wide:focus").count()); i++) await press(page, "ArrowDown");
  await expect(page.locator(".card.wide:focus")).toHaveCount(1);
  expect(await ringCutOff(page)).toEqual([]);
});

test("Search finds by name and people; Settings keeps a playback choice", async ({ page }) => {
  await fakePlex(page);
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Search" }).focus();
  await press(page, "Enter");
  // The cursor arrives in the box, ready to type.
  await expect(page.locator("input.field:focus")).toBeVisible();
  await page.keyboard.type("orbit");
  await expect(page.getByText("Movies and shows")).toBeVisible();
  await expect(page.locator(".grid .card .title")).toHaveText(["Low Orbit"]);
  await expect(page.getByText("1 in your library  ·  1 person")).toBeVisible();
  await page.screenshot({ path: "shots/lg-search.png" });
  // Down to the person, and in: what they're in.
  await press(page, "ArrowDown");
  await expect(page.locator(".person:focus .name")).toHaveText("Ana Orbit");
  await press(page, "Enter");
  await expect(page.getByRole("heading", { name: "Ana Orbit" })).toBeVisible();
  await expect(page.locator(".grid .card .title")).toHaveText(["Low Orbit"]);
  // Back to the search, as it was, and it's remembered.
  await press(page, "Escape");
  await expect(page.locator("input.field")).toHaveValue("orbit");
  await page.locator("input.field").fill("");
  await page.locator("input.field").dispatchEvent("input");
  await expect(page.getByText("Recent searches")).toBeVisible();

  // Settings: the sections down the left, entered on the one showing.
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".section-tab:focus")).toHaveText("Playback");
  await expect(page.locator(".setting-group h2")).toHaveText(["Video", "Subtitles", "Episodes", "Show pages"]);
  await page.screenshot({ path: "shots/lg-settings.png" });
  // Right to the first setting; OK opens its values, the one in use ticked and under the cursor.
  await press(page, "ArrowRight");
  await expect(page.locator(".setting:focus .setting-title")).toHaveText("Playback mode");
  await press(page, "Enter");
  await expect(page.locator(".choice-panel .option:focus")).toContainText("Automatic");
  await page.screenshot({ path: "shots/lg-settings-choice.png" });
  await press(page, "ArrowDown");
  await press(page, "ArrowDown");
  await press(page, "Enter");
  await expect(page.locator(".choice-panel")).toHaveCount(0);
  // Back on the setting that opened it, showing the new value.
  await expect(page.locator(".setting:focus .setting-value")).toHaveText("Always convert");
  // Down goes through every row of the section, past each group's heading, and no further:
  // never across into the sections on the left.
  const rowTitles = await page.locator(".settings-page .setting .setting-title").allTextContents();
  for (const title of rowTitles.slice(1)) {
    await press(page, "ArrowDown");
    await expect(page.locator(".setting:focus .setting-title")).toHaveText(title);
  }
  await press(page, "ArrowDown");
  await expect(page.locator(".setting:focus .setting-title")).toHaveText(rowTitles[rowTitles.length - 1]);
  // Left goes back to its section, not whichever sits level with it.
  await press(page, "ArrowLeft");
  await expect(page.locator(".section-tab:focus")).toHaveText("Playback");
  // Down the sections shows each one.
  await press(page, "ArrowDown");
  await press(page, "ArrowDown");
  await expect(page.locator(".section-tab:focus")).toHaveText("Theme");
  await expect(page.locator(".setting .setting-title")).toHaveText(["Theme"]);
  await page.reload();
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".setting", { hasText: "Playback mode" }).locator(".setting-value")).toHaveText("Always convert");
});

test("Settings: the libraries switched on are the only ones on Home and in the menus, as on the Fire TV", async ({ page }) => {
  await fakePlex(page);
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await page.locator(".section-tab", { hasText: "Plex" }).focus();
  await press(page, "Enter");
  // A switch for each library, all off: none switched on is every one of them.
  await expect(page.locator(".setting-group", { hasText: "Libraries" }).locator(".setting .setting-title")).toHaveText(["Movies", "TV Shows"]);
  await expect(page.locator(".setting", { hasText: "TV Shows" }).locator(".switch.on")).toHaveCount(0);
  await page.screenshot({ path: "shots/lg-settings-libraries.png" });
  // TV Shows alone: Home is from it only, so no films; Movies' menu still has its own.
  await page.locator(".setting", { hasText: "TV Shows" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".setting", { hasText: "TV Shows" }).locator(".switch.on")).toHaveCount(1);
  await page.locator(".tab", { hasText: "Home" }).click();
  await expect(page.getByText("Continue Watching")).toBeVisible();
  await expect(page.getByText("Recently Added Movies")).toHaveCount(0);
  // Kept: the same after starting again.
  await page.reload();
  await expect(page.getByText("Continue Watching")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("Recently Added Movies")).toHaveCount(0);
  // Switched off again: every library, the films back.
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await page.locator(".section-tab", { hasText: "Plex" }).focus();
  await press(page, "Enter");
  await page.locator(".setting", { hasText: "TV Shows" }).focus();
  await press(page, "Enter");
  await page.locator(".tab", { hasText: "Home" }).click();
  await expect(page.getByText("Recently Added Movies")).toBeVisible();
});

/**
 * A stand-in for the TV's video player: keeps time (twice as fast), fires the events the
 * real one does, and plays whatever it's told it can. What's tested is the app around it.
 */
const scriptedVideo = () => {
  const proto = HTMLMediaElement.prototype;
  type S = { t: number; d: number; paused: boolean; src: string; timer?: ReturnType<typeof setInterval> };
  const all = new WeakMap<HTMLMediaElement, S>();
  const st = (v: HTMLMediaElement) => { let s = all.get(v); if (!s) { s = { t: 0, d: (window as any).__videoSeconds ?? 12, paused: true, src: "" }; all.set(v, s); } return s; };
  const fire = (v: HTMLMediaElement, e: string) => v.dispatchEvent(new Event(e));
  (window as any).__videoSources = [] as string[];
  Object.defineProperty(proto, "src", { configurable: true, get() { return st(this).src; }, set(v: string) { st(this).src = v; (window as any).__videoSources.push(v); } });
  Object.defineProperty(proto, "currentTime", { configurable: true, get() { return st(this).t; }, set(v: number) { st(this).t = v; fire(this, "timeupdate"); } });
  Object.defineProperty(proto, "duration", { configurable: true, get() { return st(this).d; } });
  Object.defineProperty(proto, "paused", { configurable: true, get() { return st(this).paused; } });
  proto.canPlayType = () => "probably";
  proto.load = function () { const s = st(this); clearInterval(s.timer); s.t = 0; s.paused = true; setTimeout(() => { fire(this, "durationchange"); fire(this, "loadedmetadata"); fire(this, "canplay"); }, 20); };
  proto.play = function () {
    const s = st(this);
    s.paused = false;
    fire(this, "play"); fire(this, "playing");
    clearInterval(s.timer);
    s.timer = setInterval(() => {
      s.t += 0.5;
      fire(this, "timeupdate");
      if (s.t >= s.d) { clearInterval(s.timer); s.paused = true; fire(this, "ended"); }
    }, 250);
    return Promise.resolve();
  };
  proto.pause = function () { const s = st(this); clearInterval(s.timer); s.paused = true; fire(this, "pause"); };
};

test("the player: Skip Intro, another sound track kept with Plex, Up Next on to the next episode", async ({ page }) => {
  const plex = await fakePlex(page);
  // Long enough that the credits don't come while the panels are being tried; it's taken
  // to them when they're wanted.
  await page.addInitScript(() => { (window as any).__videoSeconds = 60; });
  await page.addInitScript(scriptedVideo);
  // This episode as a file the TV plays: an intro, two sound tracks, subtitles, credits.
  await page.route(`${SERVER}/library/streams/21*`, (route) =>
    route.fulfill({ status: 200, contentType: "text/plain", headers: { "Access-Control-Allow-Origin": "*" }, body: "1\n00:00:00,000 --> 00:01:00,000\nHello from the subtitles\n" }));
  await page.route(`${SERVER}/library/metadata/e2*`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify({ MediaContainer: { Metadata: [{
      ratingKey: "e2", type: "episode", title: "Episode 2", index: 2, parentIndex: 1, grandparentRatingKey: "show1", grandparentTitle: "Northbound", duration: 60_000,
      Marker: [{ type: "intro", startTimeOffset: 0, endTimeOffset: 4_000 }, { type: "credits", startTimeOffset: 54_000, endTimeOffset: 60_000 }],
      Media: [{ container: "mp4", videoCodec: "h264", audioCodec: "aac", Part: [{ id: 5, key: "/library/parts/5/file.mp4", Stream: [
        { id: 11, streamType: 2, displayTitle: "English (AAC Stereo)", selected: 1 }, { id: 12, streamType: 2, displayTitle: "Commentary" },
        { id: 21, streamType: 3, displayTitle: "English (SRT)", key: "/library/streams/21", codec: "srt" },
      ] }] }],
    }] } }) }));
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Continue Watching")).toBeVisible({ timeout: 10_000 });
  await press(page, "Enter");
  await expect(page.locator(".round:focus .label")).toHaveText("Play");
  await press(page, "Enter");
  await expect(page.locator(".player")).toBeVisible();

  // Over the intro: Skip Intro, and OK skips it.
  await expect(page.locator(".skip-prompt")).toHaveText("Skip Intro");
  await page.screenshot({ path: "shots/lg-player-skip-intro.png" });
  await press(page, "Enter");
  await expect(page.locator(".skip-prompt")).toHaveCount(0);
  // Left skips back, with a picture of where it lands from Plex's index.
  await press(page, "ArrowLeft");
  await expect(page.locator(".preview img")).toHaveAttribute("src", /\/library\/parts\/5\/indexes\/sd\/\d+\?X-Plex-Token=server-token/);

  // Down: the controls, the cursor on play; along to Audio, whose panel opens on the track playing.
  await press(page, "ArrowDown");
  await expect(page.locator(".ctl:focus")).toHaveAttribute("aria-label", "Pause");
  await page.screenshot({ path: "shots/lg-player-controls.png" });
  await toControl(page, "Audio");
  await press(page, "Enter");
  await expect(page.locator(".player-panel .panel-title")).toHaveText("Audio");
  await expect(page.locator(".option:focus")).toContainText("English (AAC Stereo)");
  await page.screenshot({ path: "shots/lg-player-options.png" });
  const sources = () => page.evaluate(() => (window as any).__videoSources as string[]);
  expect((await sources()).pop()).toContain("/library/parts/5/file.mp4");
  // The commentary, kept with Plex and converted.
  await press(page, "ArrowDown");
  await expect(page.locator(".option:focus")).toContainText("Commentary");
  await press(page, "Enter");
  await expect.poll(async () => (await sources()).pop()).toContain("/video/:/transcode/universal/start.m3u8");
  await expect(page.locator(".options")).toHaveCount(0);
  await expect.poll(() => plex.timeline).toContain("choose?audioStreamID=12");

  // Its own SRT subtitles, from the Subtitles panel: drawn by the app over the picture,
  // Plex asked not to burn them in.
  await toControl(page, "Subtitles");
  await press(page, "Enter");
  await expect(page.locator(".player-panel .panel-title")).toHaveText("Subtitles");
  await expect(page.locator(".option:focus")).toContainText("Off");
  await press(page, "ArrowDown");
  await expect(page.locator(".option:focus")).toContainText("English (SRT)");
  await press(page, "Enter");
  await expect(page.locator(".subtitle-line")).toHaveText("Hello from the subtitles");
  expect((await sources()).pop()).toContain("subtitles=none");
  await page.screenshot({ path: "shots/lg-player-subtitles.png" });

  // The credits: Up Next over the screen, the credits in the corner; OK plays it now.
  await page.evaluate(() => { document.querySelector("video")!.currentTime = 54.5; });
  await expect(page.locator(".post-play")).toContainText("Season 1  ·  Episode 3", { timeout: 15_000 });
  await expect(page.locator(".post-play .post-title")).toHaveText("Episode 3");
  await expect(page.locator(".video")).toHaveClass(/windowed/);
  await expect(page.locator(".play-next:focus")).toBeVisible();
  await page.screenshot({ path: "shots/lg-player-up-next.png" });
  const nextFile = page.waitForRequest((r) => r.url().includes("/library/metadata/e3"));
  await press(page, "Enter");
  await nextFile;
  await expect.poll(() => plex.timeline).toContain("stopped@e2");
  await expect(page.locator(".player-bar .facts").first()).toHaveText("S1 · E3 · Episode 3");
});

/** The cursor along the player's buttons to the one named, bringing the controls up first. */
async function toControl(page: Page, label: string) {
  const on = async () => (await page.locator(".ctl:focus").count()) ? page.locator(".ctl:focus").getAttribute("aria-label") : null;
  if (!(await on())) {
    await press(page, "ArrowDown");
    await expect(page.locator(".ctl:focus")).toHaveCount(1);
  }
  // Which way, and how far, along the row; each press landed before the next.
  const labels = await page.locator(".ctl:not([aria-disabled])").evaluateAll((els) => els.map((e) => e.getAttribute("aria-label")));
  const from = labels.indexOf(await on());
  const to = labels.indexOf(label);
  for (let i = 0; i < Math.abs(to - from); i++) {
    const was = await on();
    await press(page, to > from ? "ArrowRight" : "ArrowLeft");
    await expect.poll(on).not.toBe(was);
  }
  await expect(page.locator(".ctl:focus")).toHaveAttribute("aria-label", label);
}

test("holding OK on a poster opens its menu; a Home row can be switched off", async ({ page }) => {
  const plex = await fakePlex(page);
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await press(page, "ArrowDown", 2);
  await expect(page.locator(".card:focus .title")).toHaveText("Low Orbit");
  // Held: the remote repeats OK while it's down.
  await page.keyboard.down("Enter");
  await page.waitForTimeout(500);
  await page.keyboard.down("Enter");
  await page.keyboard.up("Enter");
  await expect(page.locator(".item-menu")).toBeVisible();
  await expect(page.locator(".item-menu .option")).toHaveText(["Play", "Mark as watched", "Details"]);
  await expect(page.locator(".option:focus")).toHaveText("Play");
  await page.screenshot({ path: "shots/lg-menu.png" });
  await press(page, "ArrowDown");
  await press(page, "Enter");
  await expect(page.locator(".item-menu")).toHaveCount(0);
  await expect.poll(() => plex.timeline).toContain("/:/scrobble?m1");
  // A press that isn't held is still a press: the film's page.
  await expect(page.locator(".card:focus .title")).toHaveText("Low Orbit");

  // Settings, Home: Continue Watching off, and it's gone from Home.
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".section-tab:focus")).toHaveText("Playback");
  await press(page, "ArrowDown");
  await expect(page.locator(".section-tab:focus")).toHaveText("Home");
  await press(page, "ArrowRight");
  await expect(page.locator(".setting:focus .setting-title")).toHaveText("Continue Watching");
  await press(page, "Enter");
  await expect(page.locator(".setting:focus .switch")).not.toHaveClass(/on/);
  await page.getByRole("button", { name: "Home" }).first().focus();
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible();
  await expect(page.getByText("Continue Watching")).toHaveCount(0);
});

test("the remote's tour comes up once, after signing in, and steps through to the end", async ({ page }) => {
  await fakePlex(page, { tour: true });
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Welcome to Reely")).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".pill:focus")).toHaveText("Next");
  await page.screenshot({ path: "shots/lg-tour.png" });
  for (let i = 0; i < 6; i++) await press(page, "Enter");
  await expect(page.getByText("You're all set")).toBeVisible();
  await press(page, "Enter");
  await expect(page.locator(".tour")).toHaveCount(0);
  await page.reload();
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".tour")).toHaveCount(0);
});

// The sides of the cursor's ring that something it sits in cuts off: a row that scrolls
// clips whatever reaches outside it, and a ring drawn round a poster grown under the cursor
// lost its top that way (as it did on the Roku). Nothing cut off is [].
async function ringCutOff(page: Page): Promise<string[]> {
  await page.waitForTimeout(400); // the grow on focus is animated
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el) return ["nothing has the cursor"];
    const target = (el.querySelector(".art, .avatar, .disc") as HTMLElement | null) ?? el;
    // The widest ring drawn outside it (an inset one is inside, and can't be cut off).
    let spread = 0;
    const shadow = getComputedStyle(target).boxShadow;
    if (shadow && shadow !== "none") {
      for (const part of shadow.split(/,(?![^(]*\))/)) {
        if (part.includes("inset")) continue;
        const lengths = (part.replace(/rgba?\([^)]*\)/g, "").match(/-?[\d.]+px/g) ?? []).map(parseFloat);
        if (lengths.length >= 4 && lengths[0] === 0 && lengths[1] === 0) spread = Math.max(spread, lengths[3]);
      }
    }
    const r = target.getBoundingClientRect();
    // The ring round the picture, and the whole of what has the cursor: its name and length too.
    const whole = el.getBoundingClientRect();
    const ring = {
      top: Math.min(r.top - spread, whole.top), bottom: Math.max(r.bottom + spread, whole.bottom),
      left: Math.min(r.left - spread, whole.left), right: Math.max(r.right + spread, whole.right),
    };
    const cut = new Set<string>();
    for (let a = target.parentElement; a && a !== document.body; a = a.parentElement) {
      const style = getComputedStyle(a);
      const box = a.getBoundingClientRect();
      const inner = { top: box.top + a.clientTop, left: box.left + a.clientLeft, bottom: box.top + a.clientTop + a.clientHeight, right: box.left + a.clientLeft + a.clientWidth };
      if (style.overflowY !== "visible") {
        if (ring.top < inner.top - 0.5) cut.add("top");
        if (ring.bottom > inner.bottom + 0.5) cut.add("bottom");
      }
      if (style.overflowX !== "visible") {
        if (ring.left < inner.left - 0.5) cut.add("left");
        if (ring.right > inner.right + 0.5) cut.add("right");
      }
    }
    return [...cut];
  });
}

test("arrows move along a row and down to the next; Back from a tab goes Home", async ({ page }) => {
  await fakePlex(page);
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await press(page, "ArrowDown");
  await expect(page.locator(".card:focus .title")).toHaveText("Northbound");
  // The ring round the poster under the cursor is whole, at the start of a row too.
  expect(await ringCutOff(page)).toEqual([]);
  await press(page, "ArrowDown");
  await expect(page.locator(".card:focus .title")).toHaveText("Low Orbit");
  expect(await ringCutOff(page)).toEqual([]);
  await press(page, "ArrowRight");
  await expect(page.locator(".card:focus .title")).toHaveText("Glasshouse");
  expect(await ringCutOff(page)).toEqual([]);
  // Under the hero, the row with the cursor at the top of the rows, the one before it out of
  // sight; and no scrollbar drawn beside them.
  const rowsAt = await page.evaluate(() => {
    const rows = document.querySelector<HTMLElement>(".hero-rows")!;
    const row = document.activeElement!.closest<HTMLElement>(".row")!;
    return { gap: Math.round(row.getBoundingClientRect().top - rows.getBoundingClientRect().top), bar: rows.offsetWidth - rows.clientWidth };
  });
  expect(rowsAt).toEqual({ gap: 0, bar: 0 });
  // Up goes a row at a time, to the nearest in the row above, then into the tabs on the
  // one that's open.
  await press(page, "ArrowUp");
  await expect(page.locator(".card:focus .title")).toHaveText("Northbound");
  await press(page, "ArrowUp");
  await expect(page.locator(".card:focus .title")).toHaveText("Northbound");
  await press(page, "ArrowUp");
  await expect(page.locator(".tab:focus")).toHaveText("Home");
  // Moving along the tabs opens each, as on the Fire TV.
  await press(page, "ArrowRight");
  await expect(page.locator(".tab:focus")).toHaveText("Movies");
  // The tab's own home, as on the Fire TV; All has the whole library.
  await expect(page.getByRole("heading", { name: "Recently Released" })).toBeVisible();
  await expect(page.locator(".tab:focus")).toHaveText("Movies");
  await page.screenshot({ path: "shots/lg-movies-home.png" });
  await page.getByRole("button", { name: "All", exact: true }).click();
  await expect(page.locator(".grid .card").first()).toBeVisible();
  // The grid's first poster under the cursor, its ring whole at the top of the page.
  await page.locator(".grid .card").first().focus();
  expect(await ringCutOff(page)).toEqual([]);
  // Sort, the watched filter, the decade and the genres in a row, as on the Fire TV.
  await expect(page.locator(".toolbar.chips .pill")).toHaveText(["Sort · A–Z", "Unwatched", "All decades", "All genres", "Drama", "Thriller"]);
  await page.screenshot({ path: "shots/lg-movies-all.png" });
  // Sort opens its list from the right, on the one in use.
  await page.getByRole("button", { name: "Sort · A–Z" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".choice-panel .option:focus")).toContainText("A–Z");
  await press(page, "ArrowDown");
  await press(page, "Enter");
  await expect(page.getByRole("button", { name: "Sort · Recently added" })).toBeFocused();
  // A genre narrows it.
  await page.getByRole("button", { name: "Drama" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".grid .card .title")).toHaveText(["Glasshouse"]);
  await press(page, "Escape");
  await expect(page.getByText("Recently Added Movies")).toBeVisible();
});

test("opened from file://, as an installed webOS app is, it starts and draws in Geist", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto(new URL("../../dist/index.html", import.meta.url).href);
  await expect(page.getByText("Sign in to watch your library")).toBeVisible();
  const font = await page.evaluate(async () => {
    await document.fonts.ready;
    return document.fonts.check("700 16px Geist");
  });
  expect(font).toBe(true);
  expect(errors).toEqual([]);
});

test("Requests: connect, rows without what's in the library, and ask for a show", async ({ page }) => {
  await fakePlex(page);
  const REELY = "http://192.168.1.5:8788";
  const asked: unknown[] = [];
  const cors = { "Access-Control-Allow-Origin": "http://127.0.0.1:4173", "Access-Control-Allow-Credentials": "true", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "*" };
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
          { tmdbId: 27205, kind: "movie", title: "Inception", year: 2010 },
        ],
        shows: [{ tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011 }],
        providers: [{ key: "max", name: "Max", kind: "movie", results: [{ tmdbId: 603, kind: "movie", title: "The Matrix", year: 1999 }] }],
      });
    }
    if (path === "/api/v1/movies") return send(route, { movies: [{ tmdbId: 603, filePath: "/m.mkv" }] });
    if (path === "/api/v1/shows") return send(route, { shows: [{ tmdbId: 1399, onDisk: 10, aired: 73, wanted: 63 }] });
    if (path === "/api/v1/requests" && req.method() === "GET") return send(route, { requests: [] });
    if (path === "/api/v1/auth/me") return send(route, { user: { role: "user", defaultLibraryId: 3 } });
    if (path === "/api/v1/libraries") return send(route, { libraries: [{ id: 3, name: "TV", kind: "shows" }] });
    if (path === "/api/v1/preview/show/1399") {
      return send(route, { inLibraries: [], preview: { tmdbId: 1399, kind: "show", title: "Game of Thrones", year: 2011, seasons: [{ number: 1, name: "Season 1", episodes: [{}] }, { number: 2, name: "Season 2", episodes: [{}] }] } });
    }
    if (path === "/api/v1/requests" && req.method() === "POST") {
      asked.push(JSON.parse(req.postData() ?? "{}"));
      return send(route, { id: 1, status: "pending" }, 201);
    }
    return send(route, { error: "no" }, 404);
  });
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Request", exact: true }).click();
  await page.getByPlaceholder("Reely address, like 192.168.1.5:8788").fill("192.168.1.5:8788");
  await page.getByRole("button", { name: "Connect" }).click();
  await expect(page.getByText("Trending Movies")).toBeVisible();
  // The Matrix is in the library: gone from Trending, and Max's row, left empty, isn't shown.
  await expect(page.getByText("Inception")).toBeVisible();
  await expect(page.locator(".card", { hasText: "The Matrix" })).toHaveCount(0);
  await expect(page.getByText("Movies on Max")).toHaveCount(0);
  // Partly there: still offered, and marked so.
  const got = page.locator(".card", { hasText: "Game of Thrones" });
  await expect(got.locator(".tag")).toHaveText("Partial");
  await page.screenshot({ path: "shots/lg-requests.png" });
  await got.click();
  await expect(page.getByRole("button", { name: "Request all seasons" })).toBeVisible();
  await page.getByRole("button", { name: "Season 2" }).click();
  await page.getByRole("button", { name: "Request 1 season" }).click();
  await expect(page.getByText("Requested for TV. You'll see it here once it's approved.")).toBeVisible();
  expect(asked).toEqual([expect.objectContaining({ kind: "show", tmdbId: 1399, seasons: [1], libraryId: 3 })]);
});

test("Live TV from an M3U playlist: its own XMLTV guide is read, as on the Fire TV", async ({ page }) => {
  await fakePlex(page);
  const cors = { "Access-Control-Allow-Origin": "*" };
  const now = Math.floor(Date.now() / 1000);
  const at = (s: number) => new Date(s * 1000).toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";
  let guides = 0;
  await page.route("http://lists.example/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/tv.m3u") {
      return route.fulfill({ status: 200, headers: cors, body: [
        '#EXTM3U url-tvg="http://lists.example/guide.xml"',
        '#EXTINF:-1 tvg-id="news.example" group-title="News",News 24',
        "http://lists.example/live/1.m3u8",
      ].join("\n") });
    }
    if (url.pathname === "/guide.xml") {
      guides++;
      return route.fulfill({ status: 200, headers: cors, body: `<?xml version="1.0"?><tv>` +
        `<programme start="${at(now - 600)}" stop="${at(now + 1200)}" channel="News.Example"><title>Playlist Evening News</title></programme>` +
        `<programme start="${at(now + 1200)}" stop="${at(now + 4800)}" channel="news.example"><title>Playlist Late Show</title></programme>` +
        `</tv>` });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Live TV" }).click();
  await page.getByRole("button", { name: "M3U playlist" }).click();
  await page.getByPlaceholder("Playlist address").fill("http://lists.example/tv.m3u");
  await page.getByRole("button", { name: "Add playlist" }).click();
  await page.getByRole("button", { name: "News", exact: true }).click();
  await expect(page.locator(".channel").first()).toContainText("Playlist Evening News");
  await page.getByRole("button", { name: "Guide" }).click();
  await expect(page.getByText("Playlist Late Show")).toBeVisible();
  // Read once, whole, and kept.
  expect(guides).toBe(1);
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await page.locator(".section-tab", { hasText: "Live TV" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".setting", { hasText: "Refresh TV guide" })).toContainText("Updated");
});

test("Live TV from an M3U playlist without a guide: Settings says there's none, as on the Fire TV", async ({ page }) => {
  await fakePlex(page);
  const cors = { "Access-Control-Allow-Origin": "*" };
  await page.route("http://lists.example/**", (route) => route.fulfill({ status: 200, headers: cors, body: '#EXTM3U\n#EXTINF:-1 group-title="News",News 24\nhttp://lists.example/live/1.m3u8\n' }));
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Live TV" }).click();
  await page.getByRole("button", { name: "M3U playlist" }).click();
  await page.getByPlaceholder("Playlist address").fill("http://lists.example/tv.m3u");
  await page.getByRole("button", { name: "Add playlist" }).click();
  // The cursor onto the categories first, so it isn't taken there from Settings.
  await expect(page.getByRole("button", { name: "News", exact: true })).toBeVisible();
  await expect(page.locator(".pill:focus, button:focus")).toHaveCount(1);
  await page.waitForTimeout(300);
  await page.getByRole("button", { name: "Settings" }).focus();
  await press(page, "Enter");
  await page.locator(".section-tab", { hasText: "Live TV" }).focus();
  await press(page, "Enter");
  const row = page.locator(".setting", { hasText: "TV guide" });
  await expect(row).toContainText("None");
  await expect(row).toContainText("Your playlist doesn't name one.");
  await expect(page.getByText("Refresh TV guide")).toHaveCount(0);
});

test("Live TV: sign in to a provider, pick a category, watch, change channel, favorite", async ({ page }) => {
  await fakePlex(page);
  const PANEL = "http://panel.example:8080";
  const cors = { "Access-Control-Allow-Origin": "*" };
  const now = Math.floor(Date.now() / 1000);
  await page.route(`${PANEL}/**`, async (route) => {
    const url = new URL(route.request().url());
    const send = (v: unknown) => route.fulfill({ status: 200, contentType: "application/json", headers: cors, body: JSON.stringify(v) });
    if (url.pathname === "/player_api.php") {
      const action = url.searchParams.get("action");
      if (!action) return send({ user_info: { auth: 1, status: "Active", max_connections: "2", active_cons: "0" }, server_info: { timezone: "UTC" } });
      if (action === "get_live_categories") return send([{ category_id: "1", category_name: "News" }, { category_id: "2", category_name: "Sport" }]);
      if (action === "get_live_streams") {
        return send([
          { stream_id: 101, num: 101, name: "News 24", stream_icon: "", epg_channel_id: "news", tv_archive: 1, tv_archive_duration: 3 },
          { stream_id: 102, num: 102, name: "World Report", stream_icon: "", epg_channel_id: "world" },
        ]);
      }
      if (action === "get_simple_data_table") {
        // News keeps an archive: what was on before, what's on, and what's next.
        return send({ epg_listings: [
          { title: btoa("Morning Briefing"), description: btoa("The day's news."), start_timestamp: String(now - 4200), stop_timestamp: String(now - 600) },
          { title: btoa("The Evening Report"), description: "", start_timestamp: String(now - 600), stop_timestamp: String(now + 1200) },
          { title: btoa("Late Edition"), description: "", start_timestamp: String(now + 1200), stop_timestamp: String(now + 4800) },
        ] });
      }
      if (action === "get_short_epg") {
        return send({ epg_listings: [{ title: btoa("The Evening Report"), description: "", start_timestamp: String(now - 600), stop_timestamp: String(now + 1200) }] });
      }
    }
    return route.fulfill({ status: 404, headers: cors });
  });
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Live TV" }).click();
  await page.getByPlaceholder("Server address").fill("panel.example:8080");
  await page.getByPlaceholder("Username").fill("me");
  await page.getByPlaceholder("Password").fill("secret");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("button", { name: "News", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "News", exact: true }).click();
  await expect(page.locator(".channel").first()).toContainText("The Evening Report");
  await page.screenshot({ path: "shots/lg-live.png" });
  // Watching: the stream is the provider's HLS; down goes to the next channel.
  const stream = page.waitForRequest((r) => r.url().includes("/live/me/secret/101.m3u8"));
  await page.locator(".channel").first().click();
  await stream;
  await expect(page.locator(".player-title")).toContainText("News 24");
  const next = page.waitForRequest((r) => r.url().includes("/live/me/secret/102.m3u8"));
  await press(page, "ArrowRight");
  await next;
  await expect(page.locator(".player-title")).toContainText("World Report");
  // The green key (404) favorites it; Back goes to the list, and Favorites is offered.
  await page.evaluate(() => document.dispatchEvent(new KeyboardEvent("keydown", { keyCode: 404, bubbles: true } as KeyboardEventInit)));
  await expect(page.locator(".player-title")).toContainText("♥");
  // Down: the guide over the channel, which plays on behind it, as on the Fire TV; the
  // cursor on what's on now, on the channel playing.
  await press(page, "ArrowDown");
  await expect(page.locator(".guide-overlay")).toBeVisible();
  await expect(page.locator(".player > video")).toHaveAttribute("src", /\/102\.m3u8$/);
  await expect(page.locator('.guide-overlay .guide-row[data-channel="102"] .programme.now:focus')).toHaveCount(1);
  await expect(page.locator(".guide-overlay .guide-about")).toContainText("OK to watch  ·  hold OK for more");
  await page.screenshot({ path: "shots/lg-live-guide-over.png" });
  // Up a channel and OK: that one plays, and the guide goes.
  const back = page.waitForRequest((r) => r.url().includes("/live/me/secret/101.m3u8"));
  await press(page, "ArrowUp");
  await expect(page.locator('.guide-overlay .guide-row[data-channel="101"] .programme.now:focus')).toHaveCount(1);
  await press(page, "Enter");
  await back;
  await expect(page.locator(".guide-overlay")).toHaveCount(0);
  await expect(page.locator(".player-title")).toContainText("News 24");
  // Up from the first channel: the categories. Another's channels are browsed while this one plays on.
  await press(page, "ArrowDown");
  await expect(page.locator(".guide-overlay")).toBeVisible();
  await press(page, "ArrowUp");
  await expect(page.locator(".guide-cats .pill:focus")).toHaveCount(1);
  const sport = page.waitForRequest((r) => r.url().includes("action=get_live_streams") && r.url().includes("category_id=2"));
  await page.locator(".guide-cats .pill", { hasText: "Sport" }).focus();
  await press(page, "Enter");
  await sport;
  await expect(page.locator(".guide-overlay .guide-category")).toHaveText("Sport");
  await expect(page.locator(".guide-overlay .programme:focus")).toHaveCount(1);
  await expect(page.locator(".player > video")).toHaveAttribute("src", /\/101\.m3u8$/);
  // A held OK on what's to come: the channel's menu, with a reminder; Back puts it away.
  await page.locator('.guide-overlay .guide-row[data-channel="102"] .programme', { hasText: "Late Edition" }).focus();
  await page.keyboard.down("Enter");
  await page.waitForTimeout(550);
  await page.keyboard.down("Enter");
  await page.keyboard.up("Enter");
  await expect(page.locator(".guide-menu")).toContainText("World Report");
  await expect(page.locator(".guide-menu .option:focus")).toHaveText("Watch this channel");
  await page.locator(".guide-menu .option", { hasText: "Remind me" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".guide-menu")).toHaveCount(0);
  await expect(page.locator('.guide-overlay .guide-row[data-channel="102"] .programme.reminded')).toContainText("Late Edition");
  // Back closes the guide; the channel is still on; Back again leaves it.
  await press(page, "Escape");
  await expect(page.locator(".guide-overlay")).toHaveCount(0);
  await expect(page.locator(".player-title")).toContainText("News 24");
  await press(page, "Escape");
  // Off the channel: its category's list; Back again, the categories, Favorites offered.
  await expect(page.locator(".channel").first()).toBeVisible();
  await press(page, "Escape");
  await expect(page.getByRole("button", { name: "Favorites" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Recently watched" })).toBeVisible();

  // The guide: time across. What's over plays from the archive; what's to come, a reminder.
  await page.getByRole("button", { name: "News", exact: true }).click();
  await page.getByRole("button", { name: "Guide" }).click();
  await expect(page.locator(".guide")).toBeVisible();
  await expect(page.locator(".programme", { hasText: "Morning Briefing" }).first()).toBeVisible();
  await page.locator(".programme", { hasText: "Late Edition" }).first().focus();
  await expect(page.locator(".guide-about")).toContainText("OK to be reminded when it starts");
  // The highlighted channel plays beside the grid, a moment after the cursor stops.
  await expect(page.locator(".guide-preview video")).toHaveAttribute("src", /\/live\/me\/secret\/101\.m3u8$/);
  await press(page, "Enter");
  await expect(page.locator('.guide-row[data-channel="101"] .programme.reminded')).toContainText("Late Edition");
  await page.screenshot({ path: "shots/lg-guide.png" });
  const archive = page.waitForRequest((r) => /\/timeshift\/me\/secret\/\d+\/[\d:-]+\/101\.ts$/.test(r.url()));
  await page.locator(".programme", { hasText: "Morning Briefing" }).first().focus();
  await expect(page.locator(".guide-about")).toContainText("OK to watch it again");
  await press(page, "Enter");
  await archive;
  await expect(page.locator(".player-bar")).toContainText("From the archive");
  // Back from the archive: the guide, as it was.
  await press(page, "Escape");
  await expect(page.locator(".guide")).toBeVisible();
});
