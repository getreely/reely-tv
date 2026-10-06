import XCTest
@testable import ReelyCore

/// A stand-in Plex: plex.tv and a server, answering by path, as the LG app's tests fake it.
final class FakePlex: HttpTransport, @unchecked Sendable {
    var routes: [String: (HttpRequest) -> HttpResponse] = [:]
    var asked: [String] = []
    var down: Set<String> = []
    private let lock = NSLock()

    func send(_ request: HttpRequest) async throws -> HttpResponse {
        let url = URL(string: request.url)!
        let host = "\(url.scheme!)://\(url.host!)" + (url.port.map { ":\($0)" } ?? "")
        lock.lock(); asked.append(url.path); let isDown = down.contains(host); let route = routes[host + url.path]; lock.unlock()
        if isDown { throw URLError(.cannotConnectToHost) }
        guard let route else { return HttpResponse(status: 404, data: Data()) }
        return route(request)
    }

    func json(_ host: String, _ path: String, _ text: String) {
        routes[host + path] = { _ in HttpResponse(status: 200, data: Data(text.utf8)) }
    }
}

@MainActor
final class StoreTests: XCTestCase {
    let tv = "https://plex.tv"
    let home = "https://10-0-0-2.abc.plex.direct:32400"
    let moved = "https://10-0-0-9.abc.plex.direct:32400"

    func testSignedInStartFindsTheServerAndFillsHome() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        XCTAssertEqual(store.plex.baseUrl, home)
        XCTAssertEqual(store.plex.libraries.map(\.section.title), ["Movies", "TV Shows"])
        XCTAssertEqual(store.home.continueWatching.map(\.ratingKey), ["e2"])
        XCTAssertEqual(store.home.recentMovies.map(\.title), ["Low Orbit"])
        XCTAssertEqual(store.home.recentEpisodes.map(\.showTitle), ["Northbound"])
        XCTAssertEqual(store.home.recentEpisodes.first?.count, 2)
        XCTAssertEqual(store.home.playlists.map(\.title), ["Road Trip"])
    }

    func testSigningInWithTheCode() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        fake.routes[tv + "/api/v2/pins"] = { r in
            let strong = String(decoding: r.body ?? Data(), as: UTF8.self).contains("true")
            return HttpResponse(status: 200, data: Data((strong ? #"{"id": 2, "code": "strongcode"}"# : #"{"id": 1, "code": "WXYZ"}"#).utf8))
        }
        fake.json(tv, "/api/v2/pins/1", #"{"authToken": "account-token"}"#)
        fake.json(tv, "/api/v2/pins/2", #"{"authToken": null}"#)
        fake.json(tv, "/api/v2/user", #"{"uuid": "u1", "title": "Ann"}"#)
        let store = makeStore(fake)
        store.startLink()
        for _ in 0..<50 where store.plex.linkCode == nil { try? await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(store.plex.linkCode, "WXYZ")
        XCTAssertTrue(store.plex.linkUrl?.contains("code=strongcode") == true)
        for _ in 0..<300 where store.plex.baseUrl == nil { try? await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(store.plex.token, "account-token")
        XCTAssertEqual(store.plex.user?.title, "Ann")
        XCTAssertNil(store.plex.linkCode)
        XCTAssertEqual(store.plex.baseUrl, home)
    }

    func testAServerThatMovedIsFoundAgainWithoutStartingOver() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        let item = store.home.recentMovies[0]
        // Overnight it's given another address; the old one stops answering.
        fake.down.insert(home)
        fakeServer(fake, at: moved, address: "10.0.0.9")
        await store.refreshHome()
        XCTAssertEqual(store.plex.baseUrl, moved)
        XCTAssertNil(store.homeError)
        // What was on screen, read from the old address, reaches the new one.
        XCTAssertEqual(store.plex.baseFor(item.serverBase), moved)
        XCTAssertEqual(store.plex.tokenFor(item.serverBase), "server-token")
        XCTAssertEqual(store.imageUrl(item.serverBase, "/thumb/m1", width: 10, height: 10)?.absoluteString.hasPrefix(moved), true)
    }

    func testADroppedStreamIsAskedForAfreshFromWhereItWas() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        fake.json(home, "/library/metadata/m1", #"{"MediaContainer": {"Metadata": [{"ratingKey": "m1", "Media": [{"container": "mkv", "videoCodec": "hevc", "audioCodec": "dca", "Part": [{"id": 41, "key": "/library/parts/41/file.mkv"}]}]}]}}"#)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        store.prefs.playbackMode = .transcode
        await store.play(store.home.recentMovies[0], resume: false)
        guard let before = store.playing else { return XCTFail("nothing playing") }
        XCTAssertFalse(before.direct)
        await store.reopen(positionMs: 42_000)
        guard let after = store.playing else { return XCTFail("nothing playing") }
        // A new session from where it got to, converted as before; the old one handed back.
        XCTAssertNotEqual(after.sessionId, before.sessionId)
        XCTAssertTrue(after.url.contains(after.sessionId))
        XCTAssertFalse(after.direct)
        XCTAssertEqual(after.startMs, 42_000)
        XCTAssertEqual(after.attempt, 1)
        XCTAssertTrue(fake.asked.contains("/video/:/transcode/universal/stop"))
    }

    func testSubtitlesTurnedOffMidwayGoAndStayOff() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        fake.json(home, "/library/metadata/m1", #"{"MediaContainer": {"Metadata": [{"ratingKey": "m1", "Media": [{"container": "mkv", "videoCodec": "hevc", "audioCodec": "aac", "Part": [{"id": 41, "key": "/library/parts/41/file.mkv", "Stream": [{"id": 1, "streamType": 1}, {"id": 4, "streamType": 3, "codec": "srt", "key": "/library/streams/4", "displayTitle": "English", "selected": "1"}]}]}]}]}}"#)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        await store.play(store.home.recentMovies[0], resume: false)
        guard let before = store.playing else { return XCTFail("nothing playing") }
        XCTAssertEqual(before.textSubtitle?.id, "4")
        await store.chooseStreams(audioId: nil, subtitleId: "0", positionMs: 5_000)
        guard let after = store.playing else { return XCTFail("nothing playing") }
        XCTAssertNil(after.textSubtitle)
        XCTAssertFalse(after.playback.subtitleStreams.contains(where: \.selected))
        XCTAssertTrue(fake.asked.contains("/library/parts/41"))
        // And on again.
        await store.chooseStreams(audioId: nil, subtitleId: "4", positionMs: 6_000)
        XCTAssertEqual(store.playing?.textSubtitle?.id, "4")
    }

    func testSubtitlesSetToStartOffAreNotBurnedInByPlex() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        // Plex has picture subtitles selected for this file; Settings say subtitles start off.
        fake.json(home, "/library/metadata/m1", #"{"MediaContainer": {"Metadata": [{"ratingKey": "m1", "Media": [{"container": "mkv", "videoCodec": "hevc", "audioCodec": "aac", "Part": [{"id": 41, "key": "/library/parts/41/file.mkv", "Stream": [{"id": 1, "streamType": 1}, {"id": 5, "streamType": 3, "codec": "pgs", "displayTitle": "English (PGS)", "selected": "1"}]}]}]}]}}"#)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        store.prefs.subtitlesAtStart = .off
        await store.play(store.home.recentMovies[0], resume: false)
        XCTAssertEqual(store.playing?.url.contains("subtitles=none"), true)
        // Chosen, Plex draws them in; turned off again, it doesn't.
        let first = store.playing?.sessionId ?? ""
        await store.chooseStreams(audioId: nil, subtitleId: "5", positionMs: 1_000)
        XCTAssertEqual(store.playing?.url.contains("subtitles=burn"), true)
        // A conversion of its own, never the one it replaces.
        let second = store.playing?.sessionId ?? ""
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(store.playing?.url.contains("session=\(second)"), true)
        XCTAssertEqual(store.playing?.url.contains("X-Plex-Session-Identifier"), false)
        XCTAssertFalse(store.replacingStream)
        // The old one told as stopped first, as leaving the player does.
        XCTAssertTrue(fake.asked.contains("/:/timeline"))
        await store.chooseStreams(audioId: nil, subtitleId: "0", positionMs: 2_000)
        XCTAssertEqual(store.playing?.url.contains("subtitles=none"), true)
        // Said outright in the new stream, not left to what the server last had.
        XCTAssertEqual(store.playing?.url.contains("subtitleStreamID=0"), true)
    }

    func testNoServerAnswersSaysSo() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        fake.down.insert(home)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        XCTAssertNil(store.plex.baseUrl)
        XCTAssertEqual(store.plex.error, "Found Living Room, but couldn't reach it. Make sure it's on. Reely keeps trying.")
    }

    func testPinnedLibrariesAreTheOnlyOnes() async {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        XCTAssertEqual(store.shownLibraries().count, 2)
        store.togglePinned(store.plex.libraries[1])
        XCTAssertEqual(store.shownLibraries().map(\.section.title), ["TV Shows"])
        // A kind with none switched on still has its own.
        XCTAssertEqual(store.shownLibraries(kind: "movie").map(\.section.title), ["Movies"])
    }

    func testPagesGoOnTopOfTheirTab() {
        let store = makeStore(FakePlex())
        store.navigate(.library(kind: "show"))
        store.navigate(.detail(ratingKey: "show1", serverBase: nil))
        XCTAssertEqual(store.tab, .library(kind: "show"))
        XCTAssertTrue(store.back())
        XCTAssertEqual(store.route, .library(kind: "show"))
        XCTAssertFalse(store.back())
        store.navigate(.settings)
        XCTAssertEqual(store.tab, .settings)
    }

    func testPrefsKeepEveryDefaultAndOldSavesStillRead() throws {
        let old = try JSONDecoder().decode(Prefs.self, from: Data(#"{"skipIntros": true}"#.utf8))
        XCTAssertTrue(old.skipIntros)
        XCTAssertEqual(old.upNextSeconds, 12)
        XCTAssertEqual(old.screensaverMinutes, 3)
        XCTAssertEqual(old.subtitleScale, 0.9)
        XCTAssertEqual(Accent.of("nope").id, "blue")
    }
}
