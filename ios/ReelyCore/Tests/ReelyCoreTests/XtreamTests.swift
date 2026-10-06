import XCTest
@testable import ReelyCore

/// The LG app's xtream.test.ts cases: a playlist and a guide read alike on every app.
final class XtreamTests: XCTestCase {
    let playlist = "\u{FEFF}" + [
        #"#EXTM3U url-tvg="http://guide.example.tv/epg.xml.gz,http://backup.example.tv/epg.xml""#,
        #"#EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="UK: BBC One" tvg-logo="http://logos.example.tv/bbc1.png" group-title="UK | Entertainment",UK: BBC One HD"#,
        "http://line.example.tv/live/u/p/101.ts",
        #"#EXTINF:-1 tvg-id="" tvg-logo="" group-title="News, Weather",Sky News"#,
        "#EXTVLCOPT:http-user-agent=Something",
        "http://line.example.tv/live/u/p/102.ts",
        "",
        #"#EXTINF:-1 tvg-chno="7",Channel Seven"#,
        "#EXTGRP:Local",
        "https://cdn.example.tv/seven/index.m3u8",
        #"#EXTINF:-1 tvg-name="The Film" group-title="Movies",The Film (2024)"#,
        "http://line.example.tv/movie/u/p/5001.mkv",
        #"#EXTINF:-1 group-title="Series",Some Show S01 E01"#,
        "http://line.example.tv/series/u/p/7001.mp4",
        "#EXTINF:-1,",
        "http://line.example.tv/live/u/p/103.ts",
    ].joined(separator: "\r\n")

    func testPlaylist() {
        let parsed = Xtream.parseM3u(playlist)
        XCTAssertEqual(parsed.channels.map(\.name), ["UK: BBC One HD", "Sky News", "Channel Seven", "Channel 4"])
        let bbc = parsed.channels[0], news = parsed.channels[1], seven = parsed.channels[2]
        XCTAssertEqual(bbc.epgChannelId, "bbcone.uk")
        XCTAssertEqual(bbc.icon, "http://logos.example.tv/bbc1.png")
        XCTAssertEqual(bbc.group, "UK | Entertainment")
        XCTAssertEqual(bbc.number, 1)
        XCTAssertNil(news.epgChannelId)
        XCTAssertNil(news.icon)
        XCTAssertEqual(news.group, "News, Weather")
        XCTAssertEqual(seven.number, 7)
        XCTAssertEqual(seven.group, "Local")
        XCTAssertEqual(parsed.guideUrl, "http://guide.example.tv/epg.xml.gz")
        // Java's "http://line.example.tv/live/u/p/101.ts".hashCode() & 0x7fffffff: favorites mean the same channel.
        var h: Int32 = 0
        for u in "http://line.example.tv/live/u/p/101.ts".utf16 { h = h &* 31 &+ Int32(u) }
        XCTAssertEqual(bbc.streamId, Int(h) & 0x7fffffff)
        XCTAssertEqual(Set(parsed.channels.map(\.streamId)).count, parsed.channels.count)
    }

    func testPanelLogin() {
        XCTAssertEqual(Xtream.panelLogin(in: "http://line.example.tv:8080/get.php?username=me&password=secret&type=m3u_plus&output=ts"),
                       XtreamCredentials(base: "http://line.example.tv:8080", username: "me", password: "secret"))
        XCTAssertNil(Xtream.panelLogin(in: "http://example.tv/list.m3u"))
        XCTAssertNil(Xtream.panelLogin(in: "http://line.example.tv/get.php?username=me"))
        XCTAssertEqual(Xtream.normalizeBase(" panel.example.com:8080/ "), "http://panel.example.com:8080")
    }

    func testCatchUp() {
        let panel = XtreamCredentials(base: "http://panel.example:8080", username: "me", password: "p@ss")
        let archived = XtreamChannel(streamId: 42, number: 5, name: "News", epgChannelId: "news", archiveDays: 3)
        XCTAssertEqual(Xtream.catchUpUrl(panel, archived, start: 1_710_100_800, stop: 1_710_100_800 + 5400, timezone: "UTC"),
                       "http://panel.example:8080/timeshift/me/p%40ss/90/2024-03-10:20-00/42.ts")
        var live = archived; live.archiveDays = 0
        XCTAssertNil(Xtream.catchUpUrl(panel, live, start: 0, stop: 60, timezone: nil))
        XCTAssertEqual(Xtream.streamUrl(panel, archived, format: .m3u8), "http://panel.example:8080/live/me/p%40ss/42.m3u8")
    }

    func testXmltvSplitAnywhere() {
        let guide = """
        <?xml version="1.0"?><tv><channel id="news"><display-name>News</display-name></channel>
        <programme catchup-start="20000101000000" start="20240115143000 +0000" stop="20240115150000 +0000" channel="News">
          <title lang="en">Lunchtime &amp; Weather</title><desc>Headlines &lt;live&gt; &#233;</desc>
        </programme>
        <programme start="20240115150000 +0000" stop="20240115153000 +0000" channel="sport"><title><![CDATA[Match & Goals]]></title></programme>
        </tv>
        """
        var got: [Programme] = []
        var reader = XmltvReader { got.append($0) }
        var i = guide.startIndex
        while i < guide.endIndex {
            let j = guide.index(i, offsetBy: 7, limitedBy: guide.endIndex) ?? guide.endIndex
            reader.push(String(guide[i..<j]))
            i = j
        }
        XCTAssertEqual(got.map(\.title), ["Lunchtime & Weather", "Match & Goals"])
        XCTAssertEqual(got[0].channelId, "news")
        XCTAssertEqual(got[0].description, "Headlines <live> é")
        XCTAssertEqual(got[0].start, 1_705_329_000)
        var only: [Programme] = []
        var picky = XmltvReader(keep: { $0 == "sport" }) { only.append($0) }
        picky.push(guide)
        XCTAssertEqual(only.map(\.channelId), ["sport"])
        XCTAssertEqual(Xtream.parseXmltvTime("20240115153000 +0100"), 1_705_329_000)
    }

    func testDecodeField() {
        XCTAssertEqual(Xtream.decodeField(.string(Data("Evening News".utf8).base64EncodedString())), "Evening News")
        XCTAssertEqual(Xtream.decodeField(.string("Plain: title!")), "Plain: title!")
        XCTAssertEqual(Xtream.decodeField(.null), "")
        XCTAssertEqual(Xtream.decodeField(.string(Data("Amélie".utf8).base64EncodedString())), "Amélie")
    }

    func testAPlaylistsOwnGuideIsReadOnceAndKept() async throws {
        let fake = FakePlex()
        let now = 1_705_329_000
        func at(_ s: Int) -> String {
            let f = DateFormatter(); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMddHHmmss"
            return f.string(from: Date(timeIntervalSince1970: TimeInterval(s))) + " +0000"
        }
        fake.json("http://lists.example", "/tv.m3u", "#EXTM3U url-tvg=\"http://lists.example/guide.xml\"\n#EXTINF:-1 tvg-id=\"News.Example\",News 24\nhttp://lists.example/live/1.m3u8\n")
        var reads = 0
        fake.routes["http://lists.example/guide.xml"] = { _ in
            reads += 1
            return HttpResponse(status: 200, data: Data("""
            <tv><programme start="\(at(now - 600))" stop="\(at(now + 1200))" channel="news.example"><title>On now</title></programme>
            <programme start="\(at(now + 1200))" stop="\(at(now + 4800))" channel="news.example"><title>Next</title></programme>
            <programme start="\(at(now - 600))" stop="\(at(now + 1200))" channel="someone.else"><title>Not ours</title></programme></tv>
            """.utf8))
        }
        let client = XtreamClient(http: Http(transport: fake))
        let c = XtreamCredentials(base: "", username: "", password: "", playlistUrl: "http://lists.example/tv.m3u")
        let guide = try await client.playlistGuide(c, now: now)
        XCTAssertEqual(Array(guide.keys), ["news.example"])
        let channel = try await client.channels(c)[0]
        let listing = playlistListing(guide, channel)
        XCTAssertEqual(listing.map(\.title), ["On now", "Next"])
        XCTAssertEqual(nowAndNext(listing, now: now, count: 1).map(\.title), ["On now"])
        _ = try await client.playlistGuide(c, now: now + 60)
        XCTAssertEqual(reads, 1)
        let named = await client.guideNamed(c)
        XCTAssertEqual(named, true)
    }
}
