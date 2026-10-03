import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { App } from "../../src/app/store";
import { useFetcher } from "../../src/core/http";
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
