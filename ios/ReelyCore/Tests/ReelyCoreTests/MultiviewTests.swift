import XCTest
@testable import ReelyCore

/// Multiview's layouts and its channels, as the Fire TV has them.
@MainActor
final class MultiviewTests: XCTestCase {
    func testGridTwoSideBySideThreeWithTheFirstOnTheLeftFourInQuarters() {
        XCTAssertEqual(Multiview.gridRects(1, width: 1920, height: 1080, gap: 4), [TileRect(x: 0, y: 0, width: 1920, height: 1080)])
        XCTAssertEqual(Multiview.gridRects(2, width: 1920, height: 1080, gap: 4),
                       [TileRect(x: 0, y: 0, width: 958, height: 1080), TileRect(x: 962, y: 0, width: 958, height: 1080)])
        let three = Multiview.gridRects(3, width: 1920, height: 1080, gap: 4)
        XCTAssertEqual(three[0].height, 1080)
        XCTAssertEqual(three[2], TileRect(x: 962, y: 542, width: 958, height: 538))
        XCTAssertEqual(Multiview.gridRects(4, width: 1920, height: 1080, gap: 4).map { [$0.x, $0.y] }, [[0, 0], [962, 0], [0, 542], [962, 542]])
    }

    func testFocusLayoutKeepsEachInItsPlaceTheOneWithTheCursorLarge() {
        let middle = Multiview.focusRects(3, focused: 1, width: 1920, height: 1080, gap: 4)
        XCTAssertEqual(middle[0].x, 0)
        XCTAssertEqual(middle[1].x, middle[0].width + 4)
        XCTAssertEqual(middle[1].height, 1080)
        XCTAssertEqual(middle[2].x, middle[1].x + middle[1].width + 4)
        XCTAssertEqual(middle[0].height, (middle[0].width * 9 / 16).rounded(.down))
        let first = Multiview.focusRects(2, focused: 0, width: 1920, height: 1080, gap: 4)
        XCTAssertEqual(first[0].x, 0)
        XCTAssertGreaterThan(first[1].x, first[0].width)
    }

    func testArrowsWalkTheGridAndTheFocusLayoutAsALine() {
        XCTAssertEqual(Multiview.neighbour(2, from: 0, dx: 1, dy: 0), 1)
        XCTAssertNil(Multiview.neighbour(2, from: 1, dx: 1, dy: 0))
        XCTAssertEqual(Multiview.neighbour(3, from: 2, dx: -1, dy: 0), 0)
        XCTAssertEqual(Multiview.neighbour(4, from: 3, dx: 0, dy: -1), 1)
        XCTAssertNil(Multiview.neighbour(4, from: 2, dx: 0, dy: 1))
        XCTAssertEqual(Multiview.nextPlace(3, from: 0, dx: 0, dy: 1, focusLayout: true), 1)
        XCTAssertNil(Multiview.nextPlace(3, from: 2, dx: 1, dy: 0, focusLayout: true))
        XCTAssertEqual([1, 2, 3, 4].map { Multiview.hasSpareCell($0, focusLayout: true) }, [false, true, true, false])
        XCTAssertEqual([1, 2, 3, 4].map { Multiview.hasSpareCell($0, focusLayout: false) }, [false, false, true, false])
    }

    func testTilesKeepTheirPlacesAndMoveBySwapping() {
        XCTAssertEqual(Multiview.tileOrder([0], count: 3), [0, 1, 2])
        XCTAssertEqual(Multiview.tileOrder([2, 0, 1], count: 2), [0, 1])
        XCTAssertEqual(Multiview.withoutTile([2, 0, 1, 3], 1), [1, 0, 2])
        XCTAssertEqual(Multiview.movedTile([0, 1, 2], from: 0, delta: 1), [1, 0, 2])
        XCTAssertEqual(Multiview.movedTile([0, 1, 2], from: 2, delta: 1), [0, 1, 2])
        XCTAssertEqual(Multiview.savedLabel(["BBC One", "ITV", "Sky"]), "BBC One and 2 more")
    }

    private func ch(_ id: Int, _ name: String) -> XtreamChannel { XtreamChannel(streamId: id, number: id, name: name, archiveDays: 3) }

    private func watching() -> ReelyStore {
        let store = makeStore(FakePlex())
        store.live.credentials = XtreamCredentials(base: "http://panel:8080", username: "me", password: "pw")
        store.live.channels = [ch(1, "News"), ch(2, "Sport"), ch(3, "Film"), ch(4, "Kids"), ch(5, "Music")]
        store.watchChannel(0)
        return store
    }

    func testAddsBesideTheOnePlayingNeverTwiceFourAtMost() {
        let store = watching()
        for c in store.live.channels { store.addToMultiview(c) }
        store.addToMultiview(store.live.channels[1])
        XCTAssertEqual(store.live.multiview.map(\.name), ["Sport", "Film", "Kids"])
        store.removeFromMultiview(1)
        XCTAssertEqual(store.live.multiview.map(\.name), ["Sport", "Kids"])
    }

    func testReplacingTileZeroChangesChannelAndNothingIsOnScreenTwice() {
        let store = watching()
        store.addToMultiview(store.live.channels[1])
        store.replaceInMultiview(1, store.live.channels[2])
        XCTAssertEqual(store.live.multiview.map(\.name), ["Film"])
        store.replaceInMultiview(1, store.live.channels[0])
        XCTAssertEqual(store.live.multiview.map(\.name), ["Film"])
        store.replaceInMultiview(0, store.live.channels[2])
        XCTAssertEqual(store.live.watching, 2)
        XCTAssertEqual(store.live.multiview, [])
    }

    func testTheArchiveIsAskedForAsTheChannelPlays() {
        let store = watching()
        let now = Int(Date().timeIntervalSince1970)
        let on = Programme(channelId: "1", start: now - 600, stop: now + 600, title: "The Report", description: nil)
        store.prefs.streamFormat = .ts
        XCTAssertTrue(store.playCatchUp(0, on))
        XCTAssertTrue(store.live.catchUp?.url.hasSuffix("/1.ts") ?? false)
        store.prefs.streamFormat = .m3u8
        XCTAssertTrue(store.playCatchUp(0, on))
        XCTAssertTrue(store.live.catchUp?.url.hasSuffix("/1.m3u8") ?? false)
    }

    func testTheArchiveOrLeavingIsOnePictureAgain() {
        let store = watching()
        store.addToMultiview(store.live.channels[1])
        let now = Int(Date().timeIntervalSince1970)
        XCTAssertTrue(store.playCatchUp(0, Programme(channelId: "1", start: now - 600, stop: now + 600, title: "The Report", description: nil)))
        XCTAssertEqual(store.live.multiview, [])
        store.goLive()
        store.addToMultiview(store.live.channels[1])
        store.stopLive()
        XCTAssertEqual(store.live.multiview, [])
    }

    func testSavesTheSetInItsPlacesAndOpensItAgain() async {
        let store = watching()
        store.addToMultiview(store.live.channels[1])
        store.addToMultiview(store.live.channels[2])
        XCTAssertNil(store.savedMultiviewLabel)
        store.saveMultiview([2, 0, 1])
        XCTAssertEqual(store.live.savedMultiview.map(\.name), ["Film", "News", "Sport"])
        XCTAssertNil(store.savedMultiviewLabel)
        store.stopLive()
        store.watchChannel(3)
        XCTAssertEqual(store.savedMultiviewLabel, "Film and 2 more")
        await store.openSavedMultiview()
        XCTAssertEqual(store.live.watching, 2)
        XCTAssertEqual(store.live.multiview.map(\.name), ["News", "Sport"])
        store.saveMultiview([0])
        XCTAssertEqual(store.live.savedMultiview.count, 3)
    }
}
