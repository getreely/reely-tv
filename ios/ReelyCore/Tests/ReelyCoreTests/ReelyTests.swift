import XCTest
@testable import ReelyCore

/// A stand-in that answers as Reely's own handlers do: a session from the Plex sign-in, then JSON.
final class FakeReely: HttpTransport, @unchecked Sendable {
    var signIns = 0
    var signInStatus = 200
    var signInError = "this server isn't shared with your Plex account"
    var session = false
    var requested: [[String: Any]] = []
    var alreadyRequested = false
    var role = "user"
    var down = false
    private let lock = NSLock()

    func send(_ request: HttpRequest) async throws -> HttpResponse {
        lock.lock(); defer { lock.unlock() }
        if down { throw URLError(.cannotConnectToHost) }
        let url = URLComponents(string: request.url)!
        let query = Dictionary(uniqueKeysWithValues: (url.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        func reply(_ status: Int, _ text: String) -> HttpResponse { HttpResponse(status: status, data: Data(text.utf8)) }
        if url.path == "/api/v1/auth/plex/token" {
            signIns += 1
            let body = try JSONSerialization.jsonObject(with: request.body ?? Data()) as? [String: Any]
            XCTAssertEqual(body?["token"] as? String, "plex-account-token")
            if signInStatus != 200 {
                return HttpResponse(status: signInStatus, data: try JSONSerialization.data(withJSONObject: ["error": signInError]))
            }
            session = true
            return reply(200, #"{"status": "ok"}"#)
        }
        guard session else { return reply(401, #"{"error": "login required"}"#) }
        switch url.path {
        case "/api/v1/explore":
            return reply(200, """
            {"imageBase": "https://image.tmdb.org/t/p",
             "movies": [{"tmdbId": 603, "kind": "movie", "title": "The Matrix", "year": 1999, "poster": "/matrix.jpg"}],
             "shows": [{"tmdbId": 1399, "kind": "show", "title": "Game of Thrones", "year": 2011, "poster": "/got.jpg"}],
             "popularMovies": [], "popularShows": null, "topMovies": [], "topShows": [],
             "providers": [{"key": "netflix", "name": "Netflix", "kind": "show", "results": [{"tvdbId": 81189, "kind": "show", "title": "Breaking Bad", "year": 2008, "poster": "https://artworks.thetvdb.com/bb.jpg"}]}]}
            """)
        case "/api/v1/auth/me":
            return reply(200, #"{"user": {"id": 3, "role": "\#(role)", "mayAdd": false, "defaultLibraryId": 2}}"#)
        case "/api/v1/libraries":
            return reply(200, #"{"libraries": [{"id": 1, "name": "Movies", "kind": "movies"}, {"id": 2, "name": "Kids Movies", "kind": "movies"}, {"id": 3, "name": "TV", "kind": "shows"}]}"#)
        case "/api/v1/search":
            XCTAssertEqual(query["q"], "incep")
            return reply(200, #"{"results": [{"tmdbId": 27205, "kind": "movie", "title": "Inception", "year": 2010, "poster": "/inc.jpg"}]}"#)
        case "/api/v1/preview/show/1399":
            return reply(200, """
            {"inLibraries": [], "preview": {"tmdbId": 1399, "kind": "show", "title": "Game of Thrones", "year": 2011, "poster": "/got.jpg", "backdrop": "/got-wide.jpg",
              "genres": ["Drama"], "status": "Ended",
              "seasons": [{"number": 0, "name": "Specials", "episodes": [{}]}, {"number": 1, "name": "Season 1", "episodes": [{}, {}]}, {"number": 2, "name": "", "episodes": [{}]}]}}
            """)
        case "/api/v1/preview/show/81189":
            XCTAssertEqual(query["src"], "tvdb")
            return reply(200, #"{"inLibraries": [], "preview": {"tvdbId": 81189, "kind": "show", "title": "Breaking Bad", "seasons": []}}"#)
        case "/api/v1/preview/movie/603":
            return reply(200, #"{"inLibraries": [3], "preview": {"tmdbId": 603, "kind": "movie", "title": "The Matrix", "year": 1999, "runtime": 136, "genres": []}}"#)
        case "/api/v1/movies":
            return reply(200, #"{"movies": [{"tmdbId": 603, "filePath": "/films/matrix.mkv"}, {"tmdbId": 27205, "filePath": "", "downloading": true}, {"tmdbId": 11, "filePath": ""}]}"#)
        case "/api/v1/shows":
            return reply(200, #"{"shows": [{"tmdbId": 1399, "onDisk": 10, "aired": 73, "wanted": 63}, {"tmdbId": 1396, "onDisk": 7, "aired": 62, "wanted": 0}, {"tmdbId": 0, "tvdbId": 81189, "onDisk": 62, "aired": 62, "wanted": 0}]}"#)
        case "/api/v1/requests":
            if request.method == "POST" {
                if alreadyRequested { return reply(409, #"{"error": "already requested"}"#) }
                requested.append(try JSONSerialization.jsonObject(with: request.body ?? Data()) as? [String: Any] ?? [:])
                return reply(201, #"{"id": 10, "status": "pending"}"#)
            }
            if query["mine"] == "1" {
                return reply(200, """
                {"requests": [{"id": 4, "kind": "movie", "tmdbId": 603, "title": "The Matrix", "year": 1999, "poster": "/matrix.jpg", "status": "approved"},
                              {"id": 9, "kind": "show", "tmdbId": 1399, "title": "Game of Thrones", "poster": "/got.jpg", "seasons": [1], "status": "pending"}]}
                """)
            }
            return reply(200, #"{"requests": [{"id": 2, "kind": "movie", "tmdbId": 550, "title": "Fight Club", "status": "pending"}]}"#)
        default:
            return reply(404, #"{"error": "no such endpoint"}"#)
        }
    }
}

/// The LG app's reely.test.ts cases: Reely is reached the same way from every app.
final class ReelyTests: XCTestCase {
    func client(_ fake: FakeReely, token: String? = "plex-account-token") -> ReelyClient {
        ReelyClient(base: "127.0.0.1:8788", transport: fake) { token }
    }

    func testSignsInOnceThenKeepsTheSession() async throws {
        let fake = FakeReely()
        let r = client(fake)
        let problem = await r.signIn()
        XCTAssertNil(problem)
        _ = try await r.explore()
        _ = try await r.myRequests()
        XCTAssertEqual(fake.signIns, 1)
    }

    func testSignsInAgainWhenTheSessionHasLapsed() async throws {
        let fake = FakeReely()
        let r = client(fake)
        _ = try await r.explore()
        fake.session = false
        _ = try await r.explore()
        XCTAssertEqual(fake.signIns, 2)
    }

    func testRefusalsAreSaidInWords() async {
        let fake = FakeReely()
        fake.signInStatus = 403
        var said = await client(fake).signIn()
        XCTAssertEqual(said, "Your Plex account doesn't have access to this server's requests.")
        fake.signInStatus = 401
        fake.signInError = "plex.tv didn't accept that sign-in"
        said = await client(fake).signIn()
        XCTAssertEqual(said, Reely.plexRejected)
        fake.signInStatus = 502
        fake.signInError = "plex.tv: Unauthorized: <?xml version=\"1.0\"?><errors><error>Invalid authentication token.</error></errors>"
        said = await client(fake).signIn()
        XCTAssertEqual(said, Reely.ownerLinkBroken)
        fake.signInStatus = 500
        fake.signInError = "<html><body>Bad gateway</body></html>"
        said = await client(fake).signIn()
        XCTAssertEqual(said, "Reely couldn't do that. Try again.")
        said = await client(fake, token: nil).signIn()
        XCTAssertEqual(said, "Sign in to Plex first.")
        fake.down = true
        said = await client(fake).signIn()
        XCTAssertEqual(said, "Couldn't reach Reely at 127.0.0.1:8788.")
    }

    func testExploreShowsAndRequests() async throws {
        let fake = FakeReely()
        let r = client(fake)
        let rows = try await r.explore()
        XCTAssertEqual(rows.map(\.title), ["Trending Movies", "Trending Shows", "Shows on Netflix"])
        XCTAssertEqual(rows[0].titles[0].poster, "https://image.tmdb.org/t/p/w342/matrix.jpg")
        XCTAssertEqual(rows[2].titles[0].poster, "https://artworks.thetvdb.com/bb.jpg")
        let detail = try await r.detail(rows[1].titles[0])
        XCTAssertEqual(detail.seasons.map(\.number), [1, 2])
        XCTAssertEqual(detail.seasons[0].episodes, 2)
        XCTAssertEqual(detail.seasons[1].name, "Season 2")
        XCTAssertEqual(detail.backdrop, "https://image.tmdb.org/t/p/w1280/got-wide.jpg")
        let bb = try await r.detail(rows[2].titles[0])
        XCTAssertEqual(bb.title.title, "Breaking Bad")
        let matrix = try await r.detail(rows[0].titles[0])
        XCTAssertTrue(matrix.inLibrary)
        let sent = try await r.request(rows[1].titles[0], seasons: [1, 2], libraryId: 3)
        XCTAssertEqual(sent, .sent(approved: false))
        XCTAssertEqual(fake.requested[0]["seasons"] as? [Int], [1, 2])
        XCTAssertEqual(fake.requested[0]["poster"] as? String, "/got.jpg")
        XCTAssertEqual(fake.requested[0]["libraryId"] as? Int, 3)
        _ = try await r.request(rows[1].titles[0], seasons: nil, libraryId: nil)
        XCTAssertNil(fake.requested[1]["seasons"])
        fake.alreadyRequested = true
        let again = try await r.request(rows[0].titles[0], seasons: nil, libraryId: nil)
        XCTAssertEqual(again, .already)
        let mine = try await r.myRequests()
        XCTAssertEqual(mine.map(\.title.title), ["Game of Thrones", "The Matrix"])
        XCTAssertEqual(mine[0].seasons, [1])
        let found = try await r.search("incep")
        XCTAssertEqual(found.map(\.title), ["Inception"])
    }

    func testWhereARequestCanGoAndMarks() async throws {
        let fake = FakeReely()
        let r = client(fake)
        let places = try await r.places()
        let matrix = try await r.explore()[0].titles[0]
        let detail = try await r.detail(matrix)
        let addable = places.librariesFor(matrix, holding: detail.inLibraries)
        XCTAssertEqual(addable.map(\.name), ["Movies", "Kids Movies"])
        XCTAssertEqual(places.preferred(among: addable)?.name, "Kids Movies")
        XCTAssertFalse(places.adds)
        let marks = await r.marks()
        func film(_ id: Int) -> RequestTitle { RequestTitle(kind: "movie", tmdbId: id, title: "x") }
        func show(_ tmdb: Int, _ tvdb: Int = 0) -> RequestTitle { RequestTitle(kind: "show", tmdbId: tmdb, tvdbId: tvdb, title: "x") }
        XCTAssertEqual(marks.badge(film(603)), "In library")
        XCTAssertEqual(marks.badge(film(27205)), "Downloading")
        XCTAssertEqual(marks.badge(film(11)), "Requested")
        XCTAssertEqual(marks.badge(film(550)), "Requested")
        XCTAssertNil(marks.badge(film(99)))
        XCTAssertEqual(marks.badge(show(1399)), "Partial")
        XCTAssertEqual(marks.badge(show(1396)), "Partial")
        XCTAssertEqual(marks.badge(show(0, 81189)), "In library")
    }

    func testAShowPartlyHere() throws {
        let fallback = RequestTitle(kind: "show", tmdbId: 1396, title: "Breaking Bad")
        let detail = try Reely.parseDetail(JSON.parse("""
        {"inLibraries": [4], "seasonsAsked": [{"libraryId": 4, "seasons": [1]}],
         "preview": {"kind": "show", "tmdbId": 1396, "title": "Breaking Bad", "seasons": [{"number": 1, "episodes": [{}]}, {"number": 2, "episodes": [{}]}, {"number": 3, "episodes": [{}]}]}}
        """)!, fallback: fallback)
        XCTAssertEqual(detail.askedIn(4), [1])
        XCTAssertEqual(detail.seasonsLeft(4).map(\.number), [2, 3])
        XCTAssertEqual(detail.seasonsLeft(5).map(\.number), [1, 2, 3])
        XCTAssertFalse(detail.completeIn(4))
        XCTAssertEqual(detail.holdingAll, [])
        var whole = detail
        whole.seasonsAsked = nil
        XCTAssertEqual(whole.holdingAll, [4])
        var page = RequestPage(title: fallback, detail: detail, places: RequestPlaces(libraries: [RequestLibrary(id: 4, name: "TV", kind: "shows")], defaultLibraryId: 4, adds: false),
                               chosen: [1, 2, 3], libraryId: 4, busy: false)
        XCTAssertEqual(page.actionLabel, "Request the other 2 seasons")
        XCTAssertEqual(page.heldNote, "Season 1 here already. Pick more to ask for.")
        page.chosen = [2]
        XCTAssertEqual(page.actionLabel, "Request 1 season")
    }

    func testReadyBadgesAndRows() {
        func film(_ id: Int) -> RequestTitle { RequestTitle(kind: "movie", tmdbId: id, title: "Film \(id)") }
        func show(_ id: Int) -> RequestTitle { RequestTitle(kind: "show", tmdbId: id, title: "Show \(id)") }
        let mine = [RequestRecord(id: 1, title: film(1), status: "approved"), RequestRecord(id: 2, title: film(2), status: "approved"),
                    RequestRecord(id: 3, title: film(3), status: "pending"), RequestRecord(id: 4, title: show(4), status: "approved"),
                    RequestRecord(id: 5, title: film(5), status: "approved")]
        var marks = TitleMarks()
        marks.movies = [1: "In library", 2: "Downloading", 3: "In library", 5: "In library"]
        marks.showsByTmdb[4] = "Partial"
        XCTAssertEqual(Reely.readyRequests(mine, marks, seen: [film(5).key]).map(\.title), ["Film 1", "Show 4"])

        XCTAssertEqual(Reely.normalize(" reely.example.com/ "), "http://reely.example.com")
        XCTAssertEqual(Reely.normalize("https://r.example.com:8789"), "https://r.example.com:8789")
        XCTAssertTrue(Reely.isValid("192.168.1.20:8788"))
        XCTAssertFalse(Reely.isValid(""))
        XCTAssertNotEqual(film(1).key, show(1).key)
        XCTAssertTrue(Reely.plexHas(film(603), movies: ["tmdb://603"], shows: []))
        XCTAssertFalse(Reely.plexHas(show(603), movies: ["tmdb://603"], shows: []))

        var state = RequestsState()
        state.marks.movies = [1: "In library", 2: "Downloading"]
        state.marks.showsByTmdb[4] = "Partial"
        state.plexMovies = ["tmdb://6"]
        state.rows = [RequestRow(id: "provider:max:movie", title: "Movies on Max", titles: [film(1), film(2), film(3)]),
                      RequestRow(id: "shows", title: "Trending Shows", titles: [show(4), show(5)]),
                      RequestRow(id: "topMovies", title: "Top Rated Movies", titles: [film(6)])]
        XCTAssertEqual(state.shownRows.map(\.title), ["Movies on Max", "Trending Shows"])
        XCTAssertEqual(state.shownRows[0].titles.map(\.title), ["Film 2", "Film 3"])
        state.rows.append(RequestRow(id: "movies", title: "Trending Movies", titles: [film(7), film(8)]))
        XCTAssertEqual(state.homeRow(.trending).map(\.title), ["Film 7", "Show 4", "Film 8", "Show 5"])

        let title = film(550)
        var asked = TitleMarks()
        asked.requested.insert("movie-550")
        XCTAssertEqual(Reely.badgeFor(title, marks: asked, mine: [RequestRecord(id: 1, title: title, status: "approved")], plexMovies: [], plexShows: []), "Approved")
        XCTAssertEqual(Reely.badgeFor(title, marks: asked, mine: [], plexMovies: [], plexShows: []), "Requested")
    }
}
