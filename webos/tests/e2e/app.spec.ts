import { expect, test, type Page, type Route } from "@playwright/test";

const SERVER = "http://192.168.1.20:32400";

/** plex.tv and one server, answering as they do; what was told to the server is kept. */
async function fakePlex(page: Page) {
  const timeline: string[] = [];
  let claims = 0;
  const json = (route: Route, value: unknown) =>
    route.fulfill({ status: 200, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify(value) });
  const meta = (route: Route, Metadata: unknown[]) => json(route, { MediaContainer: { Metadata } });
  const episode = (key: string, index: number, viewed = false) => ({
    ratingKey: key, type: "episode", title: `Episode ${index}`, index, parentIndex: 1, parentRatingKey: "s1", grandparentRatingKey: "show1",
    grandparentTitle: "Northbound", addedAt: 1000 + index, viewCount: viewed ? 1 : 0, duration: 60_000, summary: `Episode ${index} of Northbound.`,
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
    if (path === "/library/sections") return json(route, { MediaContainer: { Directory: [{ key: "1", title: "Movies", type: "movie" }, { key: "2", title: "TV Shows", type: "show" }] } });
    if (path === "/hubs") return json(route, { MediaContainer: { Hub: [{ Metadata: [episode("e2", 2)].map((e) => ({ ...e, viewOffset: 20_000, lastViewedAt: 5 })) }] } });
    if (path === "/playlists") return meta(route, []);
    if (path === "/library/sections/1/all") return meta(route, [{ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, addedAt: 9 }, { ratingKey: "m2", type: "movie", title: "Glasshouse", year: 2024, addedAt: 8 }]);
    if (path === "/library/sections/2/all") {
      // 333 new episodes of one show: the count must sit on one line in its circle.
      const start = Number(url.searchParams.get("X-Plex-Container-Start") ?? 0);
      const size = Number(url.searchParams.get("X-Plex-Container-Size") ?? 333);
      const all = Array.from({ length: 333 }, (_, i) => episode(`n${i}`, i + 1));
      return meta(route, all.slice(start, start + size));
    }
    if (path === "/library/metadata/show1") return meta(route, [{ ratingKey: "show1", type: "show", title: "Northbound", year: 2024, summary: "A long-haul driver." }]);
    if (path === "/library/metadata/show1/children") return meta(route, [{ ratingKey: "s1", type: "season", title: "Season 1", index: 1 }]);
    if (path === "/library/metadata/s1/children") return meta(route, [episode("e1", 1, true), episode("e2", 2), episode("e3", 3)]);
    if (path === "/library/metadata/show1/related") return json(route, { MediaContainer: {} });
    if (path === "/library/metadata/e2") {
      return meta(route, [{ ...episode("e2", 2), Media: [{ container: "mkv", videoCodec: "h264", audioCodec: "dca", Part: [{ id: 5, key: "/library/parts/5/file.mkv", Stream: [] }] }] }]);
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
  await expect(page.locator(".pill:focus")).toHaveText("Play");
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
});

test("arrows move along a row and down to the next; Back from a tab goes Home", async ({ page }) => {
  await fakePlex(page);
  await page.goto("/");
  await press(page, "Enter");
  await expect(page.getByText("Recently Added Movies")).toBeVisible({ timeout: 10_000 });
  await press(page, "ArrowDown");
  await expect(page.locator(".card:focus .title")).toHaveText("Northbound");
  await press(page, "ArrowDown");
  await expect(page.locator(".card:focus .title")).toHaveText("Low Orbit");
  await press(page, "ArrowRight");
  await expect(page.locator(".card:focus .title")).toHaveText("Glasshouse");
  // Up and up again reaches the tabs; along to Movies and in.
  await press(page, "ArrowUp", 3);
  await expect(page.locator(".tab:focus")).toBeVisible();
  await page.getByRole("button", { name: "Movies" }).focus();
  await press(page, "Enter");
  await expect(page.locator(".grid .card").first()).toBeVisible();
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
