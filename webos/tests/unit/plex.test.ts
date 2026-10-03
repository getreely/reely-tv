import { describe, expect, it } from "vitest";
import { connectionOrder, homeUsersFrom, nextEpisode, parseDetail, transcodeUrl, type PlexConnection } from "../../src/api/plex";
import { formatAirDate, qualityBadges, versionDetail, versionLabel } from "../../src/core/quality";
import { continueWatchingOrder } from "../../src/core/continueWatching";
import { item } from "./fixtures";

describe("quality badges", () => {
  it("a 4K Dolby Vision file with 5.1 sound", () => {
    expect(qualityBadges("4k", 6, true, "smpte2084")).toEqual(["4K", "Dolby Vision", "5.1"]);
  });
  it("transfer functions name the HDR format", () => {
    expect(qualityBadges("4k", 0, false, "smpte2084")).toEqual(["4K", "HDR10"]);
    expect(qualityBadges("4k", 0, false, "arib-std-b67")).toEqual(["4K", "HLG"]);
    expect(qualityBadges("1080", 0, false, "bt709")).toEqual(["HD"]);
  });
  it("resolutions read the way a viewer says them", () => {
    expect(qualityBadges("720", 2)).toEqual(["HD", "Stereo"]);
    expect(qualityBadges("sd", 1)).toEqual(["SD", "Mono"]);
    expect(qualityBadges("2160", 8)).toEqual(["4K", "7.1"]);
  });
  it("nothing known, nothing shown", () => {
    expect(qualityBadges(null, 0)).toEqual([]);
    expect(qualityBadges("weird", 3)).toEqual([]);
  });
});

describe("versions", () => {
  const film = {
    ratingKey: "7",
    type: "movie",
    title: "Low Orbit",
    Media: [
      { videoResolution: "4k", videoCodec: "hevc", audioCodec: "truehd", audioChannels: 8, bitrate: 58400, Part: [{ size: 62_400_000_000, Stream: [{ streamType: 1, DOVIPresent: true }] }] },
      { videoResolution: "1080", videoCodec: "h264", audioCodec: "ac3", audioChannels: 6, bitrate: 9800, Part: [{ size: 9_800_000_000, Stream: [{ streamType: 1 }] }] },
    ],
  };
  it("each file is named by what tells it apart", () => {
    expect(parseDetail(film).versions).toEqual([
      { label: "4K Dolby Vision", detail: "HEVC · TrueHD 7.1 · 58 Mbps · 62.4 GB" },
      { label: "1080p", detail: "H.264 · Dolby Digital 5.1 · 10 Mbps · 9.8 GB" },
    ]);
  });
  it("one file is no choice at all", () => {
    expect(parseDetail({ ...film, Media: [film.Media[0]] }).versions).toEqual([]);
  });
  it("labels", () => {
    expect(versionLabel("720")).toBe("720p");
    expect(versionLabel("sd")).toBe("SD");
    expect(versionLabel("4k", false, "smpte2084")).toBe("4K HDR10");
    expect(versionLabel(null)).toBe("Other");
    expect(versionDetail(null, null, 0, 0, 0)).toBeNull();
    expect(versionDetail("", "aac", 2, 800, 450_000_000)).toBe("AAC Stereo · 800 kbps · 450 MB");
  });
});

describe("air dates", () => {
  it("a Plex date reads as a date", () => {
    expect(formatAirDate("2008-09-16", "en-US")).toBe("Sep 16, 2008");
    expect(formatAirDate("2020-01-01", "en-US")).toBe("Jan 1, 2020");
  });
  it("nothing for what isn't a whole date", () => {
    expect(formatAirDate(null, "en-US")).toBeNull();
    expect(formatAirDate("", "en-US")).toBeNull();
    expect(formatAirDate("2008", "en-US")).toBeNull();
    expect(formatAirDate("2008-13-40", "en-US")).toBeNull();
  });
});

describe("connection order", () => {
  const local: PlexConnection = { uri: "https://192-168-1-20.abc.plex.direct:32400", address: "192.168.1.20", port: 32400, local: true, relay: false };
  const remote: PlexConnection = { uri: "https://104-138-202-217.abc.plex.direct:32400", address: "104.138.202.217", port: 32400, local: false, relay: false };
  const relay: PlexConnection = { uri: "https://relay.plex.direct:8443", address: "relay", port: 8443, local: false, relay: true };
  it("local, then the plain local address, then the internet, then the relay", () => {
    expect(connectionOrder([relay, remote, local])).toEqual([
      "https://192-168-1-20.abc.plex.direct:32400",
      "http://192.168.1.20:32400",
      "https://104-138-202-217.abc.plex.direct:32400",
      "https://relay.plex.direct:8443",
    ]);
  });
  it("a remote address gets no plain twin", () => {
    expect(connectionOrder([remote])).toEqual([remote.uri]);
  });
});

describe("Plex Home users", () => {
  const home = {
    id: 1,
    name: "Home",
    users: [
      { id: 11, uuid: "a1", title: "owner", username: "owner", thumb: "https://plex.tv/users/a1/avatar", admin: true, restricted: false, protected: true, hasPassword: true },
      { id: 12, uuid: "b2", title: "Kids", username: "", thumb: "", admin: false, restricted: true, protected: false, hasPassword: false },
      { id: 13, uuid: "c3", title: "", username: "overseerr", admin: false, restricted: false, protected: false, hasPassword: true },
      { id: 14, title: "no uuid" },
    ],
  };
  it("reads everybody in the Home", () => {
    expect(homeUsersFrom(home).map((u) => u.title)).toEqual(["owner", "Kids", "overseerr"]);
  });
  it("a PIN is the protected flag, not the account password", () => {
    expect(homeUsersFrom(home).map((u) => u.protected)).toEqual([true, false, false]);
  });
  it("owner, managed, and pictures", () => {
    const [owner, kids] = homeUsersFrom(home);
    expect(owner.admin).toBe(true);
    expect(kids.restricted).toBe(true);
    expect(owner.thumb).toBe("https://plex.tv/users/a1/avatar");
    expect(kids.thumb).toBeNull();
  });
  it("a bare list reads too, and anything else is nobody", () => {
    expect(homeUsersFrom([{ uuid: "x", title: "Solo" }])).toHaveLength(1);
    expect(homeUsersFrom("")).toEqual([]);
    expect(homeUsersFrom(null)).toEqual([]);
  });
});

describe("next episode", () => {
  const ep = (key: string, watched = false, offset = 0, season: number | null = null) =>
    item({ ratingKey: key, type: "episode", viewCount: watched ? 1 : 0, viewOffsetMs: offset, parentIndex: season, grandparentRatingKey: "show" });
  it("the one part watched comes first", () => {
    expect(nextEpisode([ep("1", true), ep("2"), ep("3", false, 500_000)])?.ratingKey).toBe("3");
  });
  it("otherwise the one after the last watched", () => {
    expect(nextEpisode([ep("1", true), ep("2"), ep("3", true), ep("4")])?.ratingKey).toBe("4");
  });
  it("nothing watched starts at the beginning", () => {
    expect(nextEpisode([ep("1"), ep("2")])?.ratingKey).toBe("1");
  });
  it("all watched, or a gap left behind", () => {
    expect(nextEpisode([ep("1", true), ep("2"), ep("3", true)])?.ratingKey).toBe("2");
    expect(nextEpisode([ep("1", true), ep("2", true)])?.ratingKey).toBe("1");
    expect(nextEpisode([])).toBeNull();
  });
  it("a show not started begins with its first episode, not a special", () => {
    expect(nextEpisode([ep("special"), ep("s1e1", false, 0, 1), ep("s1e2", false, 0, 1)])?.ratingKey).toBe("s1e1");
  });
  it("specials don't count towards where the show is up to", () => {
    expect(nextEpisode([ep("special"), ep("s1e1", true, 0, 1), ep("s1e2", false, 0, 1)])?.ratingKey).toBe("s1e2");
  });
  it("a special part watched is still carried on with", () => {
    expect(nextEpisode([ep("special", false, 300_000), ep("s1e1", true, 0, 1), ep("s1e2", false, 0, 1)])?.ratingKey).toBe("special");
  });
});

describe("continue watching", () => {
  const keys = (list: { ratingKey: string }[]) => list.map((it) => it.ratingKey);
  const ep = (key: string, f: Parameters<typeof item>[0] extends infer T ? Partial<T> : never) => item({ type: "episode", ...f, ratingKey: key });
  it("most recently watched comes first, not most recently added", () => {
    expect(keys(continueWatchingOrder([
      ep("newly-added-film", { type: "movie", addedAt: 9_000, lastViewedAt: 1_000 }),
      ep("watched-just-now", { grandparentRatingKey: "kong", addedAt: 100, lastViewedAt: 5_000 }),
    ]))).toEqual(["watched-just-now", "newly-added-film"]);
  });
  it("one entry per show, preferring the one in progress", () => {
    expect(keys(continueWatchingOrder([
      ep("s1e5-next-up", { grandparentRatingKey: "kong", addedAt: 50 }),
      ep("s1e4-halfway", { grandparentRatingKey: "kong", lastViewedAt: 4_000, viewOffsetMs: 600_000 }),
    ]))).toEqual(["s1e4-halfway"]);
  });
  it("a next-up episode with no viewing yet falls back to when it was added", () => {
    expect(keys(continueWatchingOrder([
      ep("film-watched", { type: "movie", lastViewedAt: 3_000 }),
      ep("next-up", { grandparentRatingKey: "kong", addedAt: 7_000 }),
    ]))).toEqual(["next-up", "film-watched"]);
  });
  it("shows on different servers are not merged", () => {
    expect(keys(continueWatchingOrder([
      ep("a-ep", { grandparentRatingKey: "10", lastViewedAt: 2_000, serverBase: "http://a" }),
      ep("b-ep", { grandparentRatingKey: "10", lastViewedAt: 1_000, serverBase: "http://b" }),
    ]))).toEqual(["a-ep", "b-ep"]);
  });
  it("the same film listed by both hubs appears once", () => {
    const film = ep("film", { type: "movie", lastViewedAt: 2_000, viewOffsetMs: 10 });
    expect(keys(continueWatchingOrder([film, film]))).toEqual(["film"]);
  });
  it("ties keep the server's order", () => {
    expect(keys(continueWatchingOrder([
      ep("first", { type: "movie", lastViewedAt: 1_000 }),
      ep("second", { type: "movie", lastViewedAt: 1_000 }),
    ]))).toEqual(["first", "second"]);
  });
});

describe("transcode address", () => {
  it("a full transcode keeps the file's own timeline", () => {
    const params = new URL(transcodeUrl("http://s:32400", "t", "1", "s", 0, "1920x1080")).searchParams;
    expect(params.has("offset")).toBe(false);
    expect(params.get("fastSeek")).toBe("1");
    expect(params.get("path")).toBe("/library/metadata/1");
  });
});
