import XCTest
@testable import ReelyCore

/// The LG app's search.test.ts cases: the apps find the same things in the same order.
final class SearchTests: XCTestCase {
    func film(_ t: String) -> PlexItem { PlexItem(ratingKey: t, title: t) }
    func episode(_ t: String, _ show: String) -> PlexItem { PlexItem(ratingKey: "\(show)/\(t)", title: t, type: "episode", grandparentTitle: show) }
    func titles(_ q: String, _ items: PlexItem...) -> [String] { relevant(q, items).map(\.title) }

    func testMatching() {
        XCTAssertEqual(titles("nfl", film("Friday Night Lights"), film("NFL Films Presents"), film("Inflation")), ["NFL Films Presents"])
        XCTAssertEqual(titles("dune", film("The Dune Chronicles"), film("Dune: Part Two"), film("Dune")), ["Dune", "Dune: Part Two", "The Dune Chronicles"])
        XCTAssertEqual(titles("nfl game", episode("Week 1", "NFL Game Day"), episode("Pilot", "Suits")), ["Week 1"])
        XCTAssertEqual(titles("spiderman", film("Spider-Man")), ["Spider-Man"])
        XCTAssertEqual(titles("AMELIE", film("Amélie")), ["Amélie"])
        XCTAssertEqual(titles("ofsteel", film("Man of Steel")), ["Man of Steel"])
        let (matches, others) = splitResults("nfl", [film("Friday Night Lights"), film("NFL Films Presents"), film("Inflation")])
        XCTAssertEqual(matches.map(\.title), ["NFL Films Presents"])
        XCTAssertEqual(others.map(\.title), ["Friday Night Lights", "Inflation"])
    }

    func testRecentSearches() {
        XCTAssertEqual(rememberedSearches(["dune", "Heat"], "  heat  "), ["heat", "dune"])
        XCTAssertEqual(rememberedSearches(["a"], "   "), ["a"])
        XCTAssertEqual(rememberedSearches(["1", "2", "3", "4", "5", "6", "7", "8"], "new").count, 8)
    }

    func testSubtitleReading() {
        let srt = "1\n00:00:01,000 --> 00:00:02,500\n<i>Hello</i> there\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n"
        let cues = parseSubtitles(srt, codec: "srt")
        XCTAssertEqual(cues.map(\.text), ["Hello there", "Second"])
        XCTAssertEqual(cueAt(cues, 1_500), "Hello there")
        XCTAssertNil(cueAt(cues, 2_700))
        let ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}Top, and more\\Nnext\n"
        XCTAssertEqual(parseSubtitles(ass, codec: "ass").first?.text, "Top, and more\nnext")
        XCTAssertEqual(subtitleTime("01:02.345"), 62_345)
        XCTAssertNil(subtitleTime("soon"))
    }

    func testPeopleNamedByEveryWord() {
        XCTAssertTrue(namesAll("Tom Hanks", "hanks TOM"))
        XCTAssertTrue(namesAll("Tom Hanks", "tom"))
        XCTAssertFalse(namesAll("Tom Hanks", "tom cruise"))
        XCTAssertFalse(namesAll("Tom Hanks", "  "))
    }
}
