import XCTest
@testable import ReelyCore

/// The same cases as the LG app's plex.test.ts: the apps read Plex alike.
final class PlexTests: XCTestCase {
    func testQualityBadges() {
        XCTAssertEqual(qualityBadges("4k", 6, dolbyVision: true, transfer: "smpte2084"), ["4K", "Dolby Vision", "5.1"])
        XCTAssertEqual(qualityBadges("4k", 0, transfer: "smpte2084"), ["4K", "HDR10"])
        XCTAssertEqual(qualityBadges("4k", 0, transfer: "arib-std-b67"), ["4K", "HLG"])
        XCTAssertEqual(qualityBadges("1080", 0, transfer: "bt709"), ["HD"])
        XCTAssertEqual(qualityBadges("720", 2), ["HD", "Stereo"])
        XCTAssertEqual(qualityBadges("sd", 1), ["SD", "Mono"])
        XCTAssertEqual(qualityBadges("2160", 8), ["4K", "7.1"])
        XCTAssertEqual(qualityBadges(nil, 0), [])
        XCTAssertEqual(qualityBadges("weird", 3), [])
    }

    func testVersions() {
        let film = JSON.parse("""
        {"ratingKey": "7", "type": "movie", "title": "Low Orbit", "Media": [
          {"videoResolution": "4k", "videoCodec": "hevc", "audioCodec": "truehd", "audioChannels": 8, "bitrate": 58400, "Part": [{"size": 62400000000, "Stream": [{"streamType": 1, "DOVIPresent": true}]}]},
          {"videoResolution": "1080", "videoCodec": "h264", "audioCodec": "ac3", "audioChannels": 6, "bitrate": 9800, "Part": [{"size": 9800000000, "Stream": [{"streamType": 1}]}]}
        ]}
        """)!
        XCTAssertEqual(PlexAPI.parseDetail(film).versions, [
            PlexVersion(label: "4K Dolby Vision", detail: "HEVC · TrueHD 7.1 · 58 Mbps · 62.4 GB"),
            PlexVersion(label: "1080p", detail: "H.264 · Dolby Digital 5.1 · 10 Mbps · 9.8 GB"),
        ])
        XCTAssertEqual(versionLabel("720"), "720p")
        XCTAssertEqual(versionLabel("sd"), "SD")
        XCTAssertEqual(versionLabel("4k", transfer: "smpte2084"), "4K HDR10")
        XCTAssertEqual(versionLabel(nil), "Other")
        XCTAssertNil(versionDetail(nil, nil, 0, 0, 0))
        XCTAssertEqual(versionDetail("", "aac", 2, 800, 450_000_000), "AAC Stereo · 800 kbps · 450 MB")
    }

    func testAirDates() {
        XCTAssertEqual(formatAirDate("2008-09-16"), "Sep 16, 2008")
        XCTAssertEqual(formatAirDate("2020-01-01"), "Jan 1, 2020")
        XCTAssertNil(formatAirDate(nil))
        XCTAssertNil(formatAirDate(""))
        XCTAssertNil(formatAirDate("2008"))
        XCTAssertNil(formatAirDate("2008-13-40"))
    }

    func testConnectionOrder() {
        let local = PlexConnection(uri: "https://192-168-1-20.abc.plex.direct:32400", address: "192.168.1.20", port: 32400, local: true, relay: false)
        let remote = PlexConnection(uri: "https://104-138-202-217.abc.plex.direct:32400", address: "104.138.202.217", port: 32400, local: false, relay: false)
        let relay = PlexConnection(uri: "https://relay.plex.direct:8443", address: "relay", port: 8443, local: false, relay: true)
        XCTAssertEqual(PlexAPI.connectionOrder([relay, remote, local]), [
            "https://192-168-1-20.abc.plex.direct:32400", "http://192.168.1.20:32400",
            "https://104-138-202-217.abc.plex.direct:32400", "https://relay.plex.direct:8443",
        ])
        XCTAssertEqual(PlexAPI.connectionOrder([remote]), [remote.uri])
    }

    func testHomeUsers() {
        let home = JSON.parse("""
        {"id": 1, "name": "Home", "users": [
          {"id": 11, "uuid": "a1", "title": "owner", "username": "owner", "thumb": "https://plex.tv/users/a1/avatar", "admin": true, "restricted": false, "protected": true, "hasPassword": true},
          {"id": 12, "uuid": "b2", "title": "Kids", "username": "", "thumb": "", "admin": false, "restricted": true, "protected": false, "hasPassword": false},
          {"id": 13, "uuid": "c3", "title": "", "username": "overseerr", "admin": false, "restricted": false, "protected": false, "hasPassword": true},
          {"id": 14, "title": "no uuid"}
        ]}
        """)!
        let users = PlexAPI.homeUsers(from: home)
        XCTAssertEqual(users.map(\.title), ["owner", "Kids", "overseerr"])
        XCTAssertEqual(users.map(\.protected), [true, false, false])
        XCTAssertTrue(users[0].admin)
        XCTAssertTrue(users[1].restricted)
        XCTAssertEqual(users[0].thumb, "https://plex.tv/users/a1/avatar")
        XCTAssertNil(users[1].thumb)
        XCTAssertEqual(PlexAPI.homeUsers(from: JSON.parse("[{\"uuid\": \"x\", \"title\": \"Solo\"}]")!).count, 1)
        XCTAssertEqual(PlexAPI.homeUsers(from: .string("")), [])
        XCTAssertEqual(PlexAPI.homeUsers(from: .null), [])
    }

    private func ep(_ key: String, watched: Bool = false, offset: Int = 0, season: Int? = nil) -> PlexItem {
        PlexItem(ratingKey: key, type: "episode", parentIndex: season, grandparentRatingKey: "show", durationMs: 1_800_000,
                 viewOffsetMs: offset, viewCount: watched ? 1 : 0)
    }

    func testNextEpisode() {
        XCTAssertEqual(PlexAPI.nextEpisode([ep("1", watched: true), ep("2"), ep("3", offset: 500_000)])?.ratingKey, "3")
        XCTAssertEqual(PlexAPI.nextEpisode([ep("1", watched: true), ep("2"), ep("3", watched: true), ep("4")])?.ratingKey, "4")
        XCTAssertEqual(PlexAPI.nextEpisode([ep("1"), ep("2")])?.ratingKey, "1")
        XCTAssertEqual(PlexAPI.nextEpisode([ep("1", watched: true), ep("2"), ep("3", watched: true)])?.ratingKey, "2")
        XCTAssertEqual(PlexAPI.nextEpisode([ep("1", watched: true), ep("2", watched: true)])?.ratingKey, "1")
        XCTAssertNil(PlexAPI.nextEpisode([]))
        XCTAssertEqual(PlexAPI.nextEpisode([ep("special"), ep("s1e1", season: 1), ep("s1e2", season: 1)])?.ratingKey, "s1e1")
        XCTAssertEqual(PlexAPI.nextEpisode([ep("special"), ep("s1e1", watched: true, season: 1), ep("s1e2", season: 1)])?.ratingKey, "s1e2")
        XCTAssertEqual(PlexAPI.nextEpisode([ep("special", offset: 300_000), ep("s1e1", watched: true, season: 1), ep("s1e2", season: 1)])?.ratingKey, "special")
    }

    func testContinueWatching() {
        func keys(_ l: [PlexItem]) -> [String] { l.map(\.ratingKey) }
        func item(_ key: String, type: String = "episode", show: String? = nil, added: Int = 0, viewed: Int = 0, offset: Int = 0, server: String? = nil) -> PlexItem {
            PlexItem(ratingKey: key, type: type, grandparentRatingKey: show, viewOffsetMs: offset, addedAt: added, lastViewedAt: viewed, serverBase: server)
        }
        XCTAssertEqual(keys(continueWatchingOrder([item("newly-added-film", type: "movie", added: 9_000, viewed: 1_000),
                                                    item("watched-just-now", show: "kong", added: 100, viewed: 5_000)])),
                       ["watched-just-now", "newly-added-film"])
        XCTAssertEqual(keys(continueWatchingOrder([item("s1e5-next-up", show: "kong", added: 50),
                                                    item("s1e4-halfway", show: "kong", viewed: 4_000, offset: 600_000)])),
                       ["s1e4-halfway"])
        XCTAssertEqual(keys(continueWatchingOrder([item("film-watched", type: "movie", viewed: 3_000),
                                                    item("next-up", show: "kong", added: 7_000)])),
                       ["next-up", "film-watched"])
        XCTAssertEqual(keys(continueWatchingOrder([item("a-ep", show: "10", viewed: 2_000, server: "http://a"),
                                                    item("b-ep", show: "10", viewed: 1_000, server: "http://b")])),
                       ["a-ep", "b-ep"])
        let film = item("film", type: "movie", viewed: 2_000, offset: 10)
        XCTAssertEqual(keys(continueWatchingOrder([film, film])), ["film"])
        XCTAssertEqual(keys(continueWatchingOrder([item("first", type: "movie", viewed: 1_000), item("second", type: "movie", viewed: 1_000)])),
                       ["first", "second"])
    }

    func testTranscodeAddress() {
        let plex = PlexAPI(identity: PlexIdentity(clientId: "c", version: "1", platform: "iOS", device: "iPhone", deviceName: "Reely"))
        let url = URLComponents(string: plex.transcodeUrl("http://s:32400", "t", ratingKey: "1", sessionId: "s", maxBitrateKbps: 0, resolution: "1920x1080"))!
        let q = Dictionary(url.queryItems!.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        XCTAssertNil(q["offset"])
        XCTAssertEqual(q["fastSeek"], "1")
        XCTAssertEqual(q["path"], "/library/metadata/1")
        func stream(_ codec: String?) -> String? {
            URLComponents(string: plex.transcodeUrl("http://s:32400", "t", ratingKey: "1", sessionId: "s", maxBitrateKbps: 0, resolution: "1920x1080", videoCodec: codec))!
                .queryItems!.first { $0.name == "directStream" }?.value
        }
        XCTAssertEqual(stream("h264"), "1")
        XCTAssertEqual(stream(nil), "1")
        XCTAssertEqual(stream("hevc"), "0")
        XCTAssertEqual(stream("mpeg2video"), "0")
    }

    func testLooseJSON() {
        let j = JSON.parse(#"{"a": "12", "b": 3, "c": true, "d": "1", "e": null, "f": "x", "g": 1.5}"#)!
        XCTAssertEqual(j["a"].int, 12)
        XCTAssertEqual(j["b"].str, "3")
        XCTAssertTrue(j["c"].flag && j["d"].flag)
        XCTAssertFalse(j["f"].flag)
        XCTAssertEqual(j["e"].str, "")
        XCTAssertNil(j["f"].positive)
        XCTAssertEqual(j["g"].str, "1.5")
        XCTAssertEqual(j["missing"]["deeper"][3].str, "")
    }

    func testPlaybackParsing() {
        let m = JSON.parse("""
        {"ratingKey": "e2", "Marker": {"type": "intro", "startTimeOffset": 1000, "endTimeOffset": 30000},
         "Chapter": [{"tag": "Two", "index": 2, "startTimeOffset": 60000, "endTimeOffset": 90000}, {"tag": "One", "index": 1, "startTimeOffset": 0, "endTimeOffset": 60000, "thumb": "/c/1"}],
         "Media": [{"container": "mkv", "videoCodec": "HEVC", "audioCodec": "dca", "audioChannels": 6, "Part": [{"id": 41, "key": "/library/parts/41/file.mkv", "Stream": [
           {"id": 1, "streamType": 1},
           {"id": 2, "streamType": 2, "codec": "eac3", "channels": 6, "selected": "1", "displayTitle": "English (EAC3 5.1)", "languageTag": "en"},
           {"id": 3, "streamType": 2, "codec": "aac", "channels": 2, "displayTitle": "Commentary"},
           {"id": 4, "streamType": 3, "codec": "srt", "key": "/library/streams/4", "displayTitle": "English", "forced": 1},
           {"id": 5, "streamType": 3, "codec": "pgs", "displayTitle": "English (PGS)"}
         ]}]}]}
        """)!
        let p = PlexAPI.parsePlayback(m, base: "http://s", token: "t")!
        XCTAssertEqual(p.url, "http://s/library/parts/41/file.mkv?X-Plex-Token=t")
        XCTAssertEqual(p.markers, [PlexMarker(type: "intro", startMs: 1000, endMs: 30000)])
        XCTAssertEqual(p.chapters.map(\.title), ["One", "Two"])
        XCTAssertEqual(p.chapters[0].thumbUrl, "http://s/c/1?X-Plex-Token=t")
        XCTAssertEqual(p.audioCodec, "eac3")
        XCTAssertEqual(p.audioChannels, 6)
        XCTAssertEqual(p.audioStreams.map(\.selected), [true, false])
        XCTAssertEqual(p.subtitles.map(\.id), ["4"])
        XCTAssertEqual(p.subtitleStreams.map(\.forced), [true, false])
        XCTAssertEqual(p.previewUrl, "http://s/library/parts/41/indexes/sd/{ms}?X-Plex-Token=t")
        XCTAssertEqual(p.container, "mkv")
        XCTAssertEqual(p.videoCodec, "hevc")
    }

    func testServersOwnFirst() {
        let json = JSON.parse("""
        [{"name": "Friend", "provides": "server", "owned": false, "accessToken": "f", "connections": [{"uri": "https://f:32400", "address": "f", "port": 32400, "local": false}]},
         {"name": "Mine", "provides": "server", "owned": true, "connections": [{"uri": "https://m:32400", "address": "10.0.0.2", "port": 32400, "local": true}]},
         {"name": "Player", "provides": "player"}]
        """)!
        let servers = PlexAPI.servers(from: json, token: "account")
        XCTAssertEqual(servers.map(\.name), ["Mine", "Friend"])
        XCTAssertEqual(servers[0].accessToken, "account")
        XCTAssertEqual(servers[0].connections, ["https://m:32400", "http://10.0.0.2:32400"])
    }

    func testEncodeLikeJavaScript() {
        XCTAssertEqual(encodeComponent("/library/metadata/1"), "%2Flibrary%2Fmetadata%2F1")
        XCTAssertEqual(encodeComponent("home.continue,home.ondeck"), "home.continue%2Chome.ondeck")
        XCTAssertEqual(encodeComponent("Amélie (2001)"), "Am%C3%A9lie%20(2001)")
    }
}
