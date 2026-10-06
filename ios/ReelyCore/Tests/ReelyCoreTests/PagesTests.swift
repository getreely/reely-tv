import XCTest
@testable import ReelyCore

@MainActor
final class PagesTests: XCTestCase {
    let tv = "https://plex.tv"
    let home = "https://10-0-0-2.abc.plex.direct:32400"

    func signedIn() async -> (ReelyStore, FakePlex) {
        let fake = FakePlex()
        fakeServer(fake, at: home)
        let store = makeStore(fake, secrets: MemoryStore(["plexToken": "account-token"]))
        await store.start()
        return (store, fake)
    }

    func testPlaybackPlanOnApple() {
        func p(_ container: String?, _ video: String?, _ audio: String?) -> PlexPlayback {
            PlexPlayback(url: "", subtitles: [], markers: [], audioCodec: audio, audioChannels: 2, previewUrl: nil, chapters: [], partId: nil,
                         audioStreams: [], subtitleStreams: [], container: container, videoCodec: video)
        }
        XCTAssertTrue(PlaybackPlan.plan(p("mp4", "h264", "aac"), mode: .auto).direct)
        XCTAssertTrue(PlaybackPlan.plan(p("mov", "hevc", "eac3"), mode: .auto).direct)
        XCTAssertEqual(PlaybackPlan.plan(p("mkv", "h264", "aac"), mode: .auto), PlaybackPlan(direct: false, reason: "This device can't open MKV files"))
        XCTAssertEqual(PlaybackPlan.plan(p("mp4", "h264", "dca"), mode: .auto).reason, "This device can't play DCA sound")
        XCTAssertEqual(PlaybackPlan.plan(p("mp4", "vc1", "aac"), mode: .auto).reason, "This device can't play VC1 video")
        XCTAssertFalse(PlaybackPlan.plan(p("mp4", "h264", "aac"), mode: .transcode).direct)
        XCTAssertTrue(PlaybackPlan.plan(p("mkv", "h264", "dca"), mode: .direct).direct)
    }

    func testALibraryPagesAndNarrows() async {
        let (store, fake) = await signedIn()
        var asked: [String] = []
        fake.routes[home + "/library/sections/1/all"] = { r in
            asked.append(r.url)
            return HttpResponse(status: 200, data: Data(#"{"MediaContainer": {"Metadata": [{"ratingKey": "m1", "type": "movie", "title": "Low Orbit"}, {"ratingKey": "m2", "type": "movie", "title": "Paper Moons"}]}}"#.utf8))
        }
        fake.json(home, "/library/sections/1/genre", #"{"MediaContainer": {"Directory": [{"key": "7", "title": "Drama"}]}}"#)
        fake.json(home, "/library/sections/1/firstCharacter", #"{"MediaContainer": {"Directory": [{"title": "L", "size": 1}, {"title": "P", "size": 1}]}}"#)
        await store.openLibrary("movie")
        XCTAssertEqual(store.browse["movie"]?.items.map(\.title), ["Low Orbit", "Paper Moons"])
        XCTAssertEqual(store.browse["movie"]?.total, 2)
        await store.setFilter("movie", unwatched: true, genre: .some(PlexGenre(id: "7", title: "Drama")))
        XCTAssertTrue(asked.last!.contains("&unwatched=1&genre=7"))
        XCTAssertTrue(asked.last!.contains("X-Plex-Container-Start=0"))
        for _ in 0..<50 where store.browse["movie"]?.letters.isEmpty != false { try? await Task.sleep(nanoseconds: 10_000_000) }
        let at = await store.jumpTo("movie", letter: "P")
        XCTAssertEqual(at, 1)
    }

    func testAShowOpensOnTheSeasonAndEpisodeItsUpTo() async {
        let (store, fake) = await signedIn()
        fake.json(home, "/library/metadata/show1", """
        {"MediaContainer": {"Metadata": [{"ratingKey": "show1", "type": "show", "title": "Northbound", "leafCount": 4,
          "OnDeck": {"Metadata": [{"ratingKey": "e22", "parentRatingKey": "s2"}]}}]}}
        """)
        fake.json(home, "/library/metadata/show1/children", #"{"MediaContainer": {"Metadata": [{"ratingKey": "s0", "type": "season", "index": 0}, {"ratingKey": "s1", "type": "season", "index": 1}, {"ratingKey": "s2", "type": "season", "index": 2}]}}"#)
        fake.json(home, "/library/metadata/s2/children", #"{"MediaContainer": {"Metadata": [{"ratingKey": "e21", "type": "episode", "viewCount": 1, "parentIndex": 2}, {"ratingKey": "e22", "type": "episode", "parentIndex": 2}]}}"#)
        await store.openDetail(ratingKey: "show1", serverBase: nil)
        XCTAssertEqual(store.detail?.detail?.title, "Northbound")
        XCTAssertEqual(store.detail?.season?.ratingKey, "s2")
        XCTAssertEqual(store.detail?.focused?.ratingKey, "e22")
        XCTAssertFalse(store.detail?.busy ?? true)
    }
}

/// A phone's Settings and Search sheets: put away, back to what they opened over.
@MainActor
final class SheetTests: XCTestCase {
    func testSettingsAndSearchCloseBackToWhereTheyOpened() {
        let store = makeStore(FakePlex())
        store.pagesLeaveSheets = true
        store.navigate(.library(kind: "movie"))
        store.navigate(.detail(ratingKey: "m1", serverBase: nil))
        store.navigate(.settings)
        XCTAssertEqual(store.route, .settings)
        store.closeSheet()
        XCTAssertEqual(store.route, .detail(ratingKey: "m1", serverBase: nil))
        XCTAssertEqual(store.stack, [.library(kind: "movie"), .detail(ratingKey: "m1", serverBase: nil)])
        // A title found in Search opens in the tab, leaving the sheet.
        store.navigate(.search)
        store.navigate(.detail(ratingKey: "m2", serverBase: nil))
        XCTAssertEqual(store.stack, [.library(kind: "movie"), .detail(ratingKey: "m1", serverBase: nil), .detail(ratingKey: "m2", serverBase: nil)])
        XCTAssertTrue(store.back())
        XCTAssertEqual(store.route, .detail(ratingKey: "m1", serverBase: nil))
    }
}
