import { describe, expect, it } from "vitest";
import {
  catchUpProgramme, catchUpUrl, decodeField, panelLoginIn, parseM3u, parseXmltvTime, progressAt, streamUrl, xmltvUrl,
  XmltvReader, type Programme, type XtreamChannel, type XtreamCredentials,
} from "../../src/api/xtream";

const playlist = "﻿" + [
  '#EXTM3U url-tvg="http://guide.example.tv/epg.xml.gz,http://backup.example.tv/epg.xml"',
  '#EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="UK: BBC One" tvg-logo="http://logos.example.tv/bbc1.png" group-title="UK | Entertainment",UK: BBC One HD',
  "http://line.example.tv/live/u/p/101.ts",
  '#EXTINF:-1 tvg-id="" tvg-logo="" group-title="News, Weather",Sky News',
  "#EXTVLCOPT:http-user-agent=Something",
  "http://line.example.tv/live/u/p/102.ts",
  "",
  '#EXTINF:-1 tvg-chno="7",Channel Seven',
  "#EXTGRP:Local",
  "https://cdn.example.tv/seven/index.m3u8",
  '#EXTINF:-1 tvg-name="The Film" group-title="Movies",The Film (2024)',
  "http://line.example.tv/movie/u/p/5001.mkv",
  '#EXTINF:-1 group-title="Series",Some Show S01 E01',
  "http://line.example.tv/series/u/p/7001.mp4",
  "#EXTINF:-1,",
  "http://line.example.tv/live/u/p/103.ts",
].join("\r\n");

describe("M3U playlists", () => {
  const parsed = parseM3u(playlist);
  it("keeps the live channels and passes over films and series", () => {
    expect(parsed.channels.map((c) => c.name)).toEqual(["UK: BBC One HD", "Sky News", "Channel Seven", "Channel 4"]);
  });
  it("reads the guide id, logo, number and group", () => {
    const [bbc, news, seven] = parsed.channels;
    expect(bbc.epgChannelId).toBe("bbcone.uk");
    expect(bbc.icon).toBe("http://logos.example.tv/bbc1.png");
    expect(bbc.group).toBe("UK | Entertainment");
    expect(bbc.number).toBe(1);
    expect(news.epgChannelId).toBeNull();
    expect(news.icon).toBeNull();
    expect(news.group).toBe("News, Weather");
    expect(seven.number).toBe(7);
    expect(seven.group).toBe("Local");
    expect(seven.url).toBe("https://cdn.example.tv/seven/index.m3u8");
  });
  it("takes the first guide the header names", () => {
    expect(parsed.guideUrl).toBe("http://guide.example.tv/epg.xml.gz");
  });
  it("a channel keeps its id from one download to the next", () => {
    const again = parseM3u(playlist);
    expect(again.channels.map((c) => c.streamId)).toEqual(parsed.channels.map((c) => c.streamId));
    expect(new Set(parsed.channels.map((c) => c.streamId)).size).toBe(parsed.channels.length);
  });
  it("ids match the Android app's, so favorites mean the same channel", () => {
    // Java's "http://line.example.tv/live/u/p/101.ts".hashCode() & 0x7fffffff.
    let h = 0;
    for (const ch of "http://line.example.tv/live/u/p/101.ts") h = (Math.imul(31, h) + ch.charCodeAt(0)) | 0;
    expect(parsed.channels[0].streamId).toBe(h & 0x7fffffff);
  });
  it("a playlist channel plays from its own address", () => {
    const c: XtreamCredentials = { base: "", username: "", password: "", playlistUrl: "http://example.tv/list.m3u" };
    expect(streamUrl(c, parsed.channels[2], "ts")).toBe(parsed.channels[2].url);
  });
  it("a guide entered with the playlist is the one used", () => {
    const c: XtreamCredentials = { base: "", username: "", password: "", playlistUrl: "http://example.tv/list.m3u", guideUrl: "http://example.tv/guide.xml" };
    expect(xmltvUrl(c)).toBe("http://example.tv/guide.xml");
  });
  it("a panel's playlist address signs in to the panel", () => {
    expect(panelLoginIn("http://line.example.tv:8080/get.php?username=me&password=secret&type=m3u_plus&output=ts"))
      .toEqual({ base: "http://line.example.tv:8080", username: "me", password: "secret" });
    expect(panelLoginIn("http://example.tv/list.m3u")).toBeNull();
    expect(panelLoginIn("http://line.example.tv/get.php?username=me")).toBeNull();
    expect(panelLoginIn("https://x.tv/sub/get.php?username=a&password=b")).not.toBeNull();
  });
});

describe("catch-up", () => {
  const panel: XtreamCredentials = { base: "http://panel.example:8080", username: "me", password: "p@ss" };
  const archived: XtreamChannel = { streamId: 42, number: 5, name: "News", icon: null, epgChannelId: "news", archiveDays: 3 };
  const live = { ...archived, archiveDays: 0 };
  const start = 1_710_100_800;
  const stop = start + 90 * 60;
  it("the address is the panel's timeshift one, in the panel's own time", () => {
    expect(catchUpUrl(panel, archived, start, stop, "UTC")).toBe("http://panel.example:8080/timeshift/me/p%40ss/90/2024-03-10:20-00/42.ts");
    expect(catchUpUrl(panel, archived, start, stop, "America/New_York")).toBe("http://panel.example:8080/timeshift/me/p%40ss/90/2024-03-10:16-00/42.ts");
  });
  it("a part minute counts as a whole one", () => {
    expect(catchUpUrl(panel, archived, start, start + 61, "UTC")!.split("/")[6]).toBe("2");
  });
  it("no archive, or no panel, no address", () => {
    expect(catchUpUrl(panel, live, start, stop, "UTC")).toBeNull();
    expect(catchUpUrl({ base: "", username: "", password: "", playlistUrl: "http://x/list.m3u" }, archived, start, stop, "UTC")).toBeNull();
  });
  it("only what's over, and still in the archive, is watched again", () => {
    const now = stop + 3_600;
    const p = (s: number, e: number, title: string): Programme => ({ channelId: "news", start: s, stop: e, title, description: null });
    const earlier = p(start, stop, "Evening News");
    const onNow = p(now - 600, now + 600, "Late News");
    const tooOld = p(now - 5 * 86_400, now - 5 * 86_400 + 1_800, "Old");
    const listing = [tooOld, earlier, onNow];
    expect(catchUpProgramme(archived, listing, start + 60, now)).toEqual(earlier);
    expect(catchUpProgramme(archived, listing, now, now)).toBeNull();
    expect(catchUpProgramme(archived, listing, tooOld.start + 60, now)).toBeNull();
    expect(catchUpProgramme(live, listing, start + 60, now)).toBeNull();
  });
});

describe("XMLTV times", () => {
  it("plain, and with offsets either way", () => {
    expect(parseXmltvTime("20240115143000")).toBe(1_705_329_000);
    expect(parseXmltvTime("20240115143000 +0100")).toBe(1_705_325_400);
    expect(parseXmltvTime("20240115143000 -0500")).toBe(1_705_347_000);
    expect(parseXmltvTime("20240115143000 +0530")).toBe(1_705_309_200);
    expect(parseXmltvTime("20240115143000+0100")).toBe(1_705_325_400);
    expect(parseXmltvTime("19700101000000")).toBe(0);
  });
  it("leap days, centuries and year ends", () => {
    expect(parseXmltvTime("20240229000000")).toBe(1_709_164_800);
    expect(parseXmltvTime("20240301000000")).toBe(1_709_251_200);
    expect(parseXmltvTime("20000229000000")).toBe(951_782_400);
    expect(parseXmltvTime("20231231235959")).toBe(1_704_067_199);
    expect(parseXmltvTime("20240101000000")).toBe(1_704_067_200);
  });
  it("every month starts where a calendar says it does", () => {
    const firsts = [1_704_067_200, 1_706_745_600, 1_709_251_200, 1_711_929_600, 1_714_521_600, 1_717_200_000,
      1_719_792_000, 1_722_470_400, 1_725_148_800, 1_727_740_800, 1_730_419_200, 1_733_011_200];
    firsts.forEach((expected, i) => expect(parseXmltvTime(`2024${String(i + 1).padStart(2, "0")}01000000`)).toBe(expected));
  });
  it("rubbish is zero rather than a crash", () => {
    for (const bad of [null, undefined, "", "2024", "not a timestamp", "2024xx15143000", "20241315000000", "20240100000000", "20230229000000"]) {
      expect(parseXmltvTime(bad)).toBe(0);
    }
  });
});

describe("programme progress", () => {
  const p = { start: 1_000, stop: 2_000 };
  it("how far through", () => {
    expect(progressAt(p, 1_000)).toBe(0);
    expect(progressAt(p, 1_500)).toBe(0.5);
    expect(progressAt(p, 2_000)).toBe(1);
  });
  it("not on, or no length, no progress", () => {
    expect(progressAt(p, 999)).toBeNull();
    expect(progressAt(p, 2_001)).toBeNull();
    expect(progressAt({ start: 1_000, stop: 1_000 }, 1_000)).toBeNull();
    expect(progressAt({ start: 2_000, stop: 1_000 }, 1_500)).toBeNull();
  });
});

describe("XMLTV reader", () => {
  const guide = `<?xml version="1.0"?><tv>
<channel id="news"><display-name>News</display-name></channel>
<programme start="20240115143000 +0000" stop="20240115150000 +0000" channel="News">
  <title lang="en">Lunchtime &amp; Weather</title><desc>Headlines &lt;live&gt;</desc>
</programme>
<programme start="20240115150000 +0000" stop="20240115153000 +0000" channel="sport"><title><![CDATA[Match & Goals]]></title></programme>
</tv>`;
  it("reads programmes even when they arrive split across chunks", () => {
    const got: Programme[] = [];
    const reader = new XmltvReader((p) => got.push(p));
    for (let i = 0; i < guide.length; i += 7) reader.push(guide.slice(i, i + 7));
    expect(got.map((p) => p.title)).toEqual(["Lunchtime & Weather", "Match & Goals"]);
    expect(got[0].channelId).toBe("news");
    expect(got[0].description).toBe("Headlines <live>");
    expect(got[0].start).toBe(1_705_329_000);
  });
  it("keeps only the channels asked for", () => {
    const got: Programme[] = [];
    new XmltvReader((p) => got.push(p), (id) => id === "sport").push(guide);
    expect(got.map((p) => p.channelId)).toEqual(["sport"]);
  });
});

describe("panel text", () => {
  it("base64 titles are decoded, plain ones kept", () => {
    expect(decodeField(btoa("Evening News"))).toBe("Evening News");
    expect(decodeField("Plain: title!")).toBe("Plain: title!");
    expect(decodeField(null)).toBe("");
  });
  it("UTF-8 inside base64 comes out whole", () => {
    const bytes = new TextEncoder().encode("Amélie");
    expect(decodeField(btoa(String.fromCharCode(...bytes)))).toBe("Amélie");
  });
});

describe("XMLTV odd corners", () => {
  it("attributes are matched whole, entities and CDATA read right", () => {
    const got: Programme[] = [];
    new XmltvReader((p) => got.push(p)).push(
      `<programme catchup-start="20000101000000" start="20240115143000 +0000" stop="20240115150000 +0000" channel="a">` +
      `<title>Caf&#xE9; &#233; &#128512;</title><desc><![CDATA[Fish &amp; chips]]></desc></programme>`,
    );
    expect(got[0].start).toBe(1_705_329_000);
    expect(got[0].title).toBe("Café é 😀");
    expect(got[0].description).toBe("Fish &amp; chips");
  });
});

describe("a playlist's own guide", () => {
  const now = 1_705_329_000;
  const at = (s: number) => new Date(s * 1000).toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";
  const show = (channel: string, start: number, stop: number, title: string) =>
    `<programme start="${at(start)}" stop="${at(stop)}" channel="${channel}"><title>${title}</title></programme>`;
  const xml = `<?xml version="1.0"?><tv>` +
    show("BBCOne.uk", now - 3 * 86_400, now - 3 * 86_400 + 1800, "Long gone") +
    show("BBCOne.uk", now + 1800, now + 3600, "Next") +
    show("BBCOne.uk", now - 1800, now + 1800, "On now") +
    show("BBCOne.uk", now - 1800, now + 1800, "On now") +
    show("someone.else", now - 1800, now + 1800, "Not ours") +
    `</tv>`;
  const list = (header: string) => `#EXTM3U${header}\n#EXTINF:-1 tvg-id="BBCOne.uk",BBC One\nhttp://line.example.tv/live/1.ts\n`;

  async function withServer(files: Record<string, string | Uint8Array>, run: () => Promise<void>) {
    const http = await import("../../src/core/http");
    const before = http.fetcher;
    const asked: string[] = [];
    http.useFetcher((async (input: string) => {
      asked.push(String(input));
      const body = files[String(input)];
      return body == null ? new Response("", { status: 404 }) : new Response(body as BodyInit);
    }) as typeof fetch);
    try {
      await run();
    } finally {
      http.useFetcher(before);
    }
    return asked;
  }

  it("is read whole once, kept for its own channels, in order, each programme once", async () => {
    const xt = await import("../../src/api/xtream");
    xt.forgetPlaylistGuide();
    const c: XtreamCredentials = { base: "", username: "", password: "", playlistUrl: "http://example.tv/a.m3u" };
    const asked = await withServer({ "http://example.tv/a.m3u": list(' url-tvg="http://example.tv/a.xml"'), "http://example.tv/a.xml": xml }, async () => {
      const byChannel = await xt.playlistGuide(c, false, now);
      expect([...byChannel.keys()]).toEqual(["bbcone.uk"]);
      const channel = xt.parseM3u(list("")).channels[0];
      const listing = xt.playlistListing(byChannel, channel);
      expect(listing.map((p) => p.title)).toEqual(["On now", "Next"]);
      expect(listing[0].channelId).toBe(String(channel.streamId));
      expect(xt.nowAndNext(listing, now, 1).map((p) => p.title)).toEqual(["On now"]);
      expect(xt.playlistGuideAt(c)).toBe(now);
      expect(xt.guideNamed(c)).toBe(true);
      // Kept: asked again, it isn't downloaded again.
      await xt.playlistGuide(c, false, now + 60);
    });
    expect(asked.filter((u) => u.endsWith(".xml"))).toHaveLength(1);
  });

  it("a gzipped guide is opened", async () => {
    const xt = await import("../../src/api/xtream");
    xt.forgetPlaylistGuide();
    const gz = new Uint8Array(await new Response(new Blob([xml]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
    const c: XtreamCredentials = { base: "", username: "", password: "", playlistUrl: "http://example.tv/b.m3u", guideUrl: "http://example.tv/b.xml.gz" };
    await withServer({ "http://example.tv/b.m3u": list(""), "http://example.tv/b.xml.gz": gz }, async () => {
      const byChannel = await xt.playlistGuide(c, false, now);
      expect(byChannel.get("bbcone.uk")?.map((p) => p.title)).toEqual(["On now", "Next"]);
    });
  });

  it("a playlist that names no guide has none, and says so", async () => {
    const xt = await import("../../src/api/xtream");
    xt.forgetPlaylistGuide();
    const c: XtreamCredentials = { base: "", username: "", password: "", playlistUrl: "http://example.tv/c.m3u" };
    const asked = await withServer({ "http://example.tv/c.m3u": list("") }, async () => {
      expect((await xt.playlistGuide(c, false, now)).size).toBe(0);
      expect(xt.guideNamed(c)).toBe(false);
      expect(xt.playlistGuideAt(c)).toBeNull();
    });
    expect(asked).toEqual(["http://example.tv/c.m3u"]);
  });
});
