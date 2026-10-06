import XCTest
@testable import ReelyCore

/// The LG app's vod.test.ts cases: the provider's films and series read alike on every app.
final class VodTests: XCTestCase {
    func entry(_ key: String, _ title: String, _ year: Int?, _ guids: [String] = [], original: String? = nil) -> PlexIndexEntry {
        PlexIndexEntry(ratingKey: key, serverBase: "http://plex", title: title, originalTitle: original, year: year, guids: guids)
    }

    func film(_ id: Int, _ name: String, _ year: Int?, _ extra: [String: String] = [:]) -> VodTitle {
        var o: [String: JSON] = ["stream_id": .string(String(id)), "name": .string(name)]
        if let year { o["year"] = .string(String(year)) }
        for (k, v) in extra { o[k] = .string(v) }
        return Vod.titleOf(.object(o), series: false)!
    }

    func testNames() {
        XCTAssertTrue(Vod.parseName("EN - The Matrix (1999)") == ("The Matrix", 1999, "EN"))
        XCTAssertTrue(Vod.parseName("|4K| Dune") == ("Dune", nil, "4K"))
        XCTAssertTrue(Vod.parseName("[FR] Amélie - 2001") == ("Amélie", 2001, "FR"))
        XCTAssertTrue(Vod.parseName("Heat [1995]") == ("Heat", 1995, nil))
        XCTAssertEqual(Vod.parseName("CSI: Miami").name, "CSI: Miami")
        XCTAssertEqual(Vod.parseName("WALL-E").name, "WALL-E")
        XCTAssertEqual(Vod.parseName("2012").name, "2012")
        XCTAssertEqual(Vod.parseName("Blade Runner 2049").name, "Blade Runner 2049")
        XCTAssertEqual(Vod.parseName("Blade Runner 2049 (2017)", knownYear: 2017).year, 2017)
    }

    func testLists() {
        let titles = Vod.readTitles(JSON.parse("""
        [{"num": 1, "name": "EN - The Matrix (1999)", "stream_id": 10, "stream_icon": "http://img/m.jpg", "rating": "8.7", "added": "1600000000", "category_id": "3", "container_extension": "mkv", "tmdb": "603"},
         {"num": 2, "name": "Heat", "stream_id": "11", "stream_icon": "", "rating": 0, "added": null, "category_id": 3},
         {"num": 3, "name": "No id"}, "junk", null,
         {"num": 4, "name": "The Matrix again", "stream_id": 10},
         {"num": 5, "name": "Half a number", "stream_id": "12abc"}]
        """)!, series: false)
        XCTAssertEqual(titles.map(\.id), [10, 11])
        let matrix = titles[0], heat = titles[1]
        XCTAssertEqual(matrix.name, "The Matrix")
        XCTAssertEqual(matrix.year, 1999)
        XCTAssertEqual(matrix.tag, "EN")
        XCTAssertEqual(matrix.rating, 8.7)
        XCTAssertEqual(matrix.addedAt, 1_600_000_000)
        XCTAssertEqual(matrix.tmdbId, "603")
        XCTAssertEqual(matrix.fileExtension, "mkv")
        XCTAssertNil(heat.poster)
        XCTAssertNil(heat.rating)
        XCTAssertEqual(heat.categoryId, "3")
        XCTAssertEqual(Vod.readTitles(JSON.parse(#"{"user_info": {"auth": 0}}"#)!, series: false), [])
        let show = Vod.readTitles(JSON.parse(#"[{"name": "Show", "series_id": 1, "year": "N/A", "releaseDate": "2011-04-17", "last_modified": "1700000000"}]"#)!, series: true)[0]
        XCTAssertEqual(show.year, 2011)
        XCTAssertEqual(show.addedAt, 1_700_000_000)
    }

    func testPages() {
        let info = Vod.parseMovieInfo(JSON.parse("""
        {"info": {"name": "The Matrix", "plot": "Neo.", "cast": "Keanu Reeves, Carrie-Anne Moss", "director": "Lana Wachowski", "genre": "Action / Sci-Fi",
                  "duration_secs": "8160", "backdrop_path": ["http://img/back.jpg"], "releasedate": "1999-03-31", "rating": "8.7", "tmdb_id": "603"},
         "movie_data": {"stream_id": 10, "container_extension": "mkv"}}
        """)!)!
        XCTAssertEqual(info.cast, ["Keanu Reeves", "Carrie-Anne Moss"])
        XCTAssertEqual(info.genres, ["Action", "Sci-Fi"])
        XCTAssertEqual(info.durationMs, 8_160_000)
        XCTAssertEqual(info.backdrop, "http://img/back.jpg")
        XCTAssertEqual(info.fileExtension, "mkv")
        let empty = Vod.parseMovieInfo(JSON.parse(#"{"info": [], "movie_data": {"stream_id": 10}}"#)!)!
        XCTAssertNil(empty.plot)
        XCTAssertEqual(empty.durationMs, 0)

        let keyed = Vod.parseSeriesInfo(JSON.parse("""
        {"info": {"name": "Show", "plot": "About.", "backdrop_path": []}, "seasons": [{"season_number": 1, "cover": "http://img/s1.jpg"}],
         "episodes": {"2": [{"id": "22", "episode_num": 1, "title": "Two One", "container_extension": "mp4", "info": {"duration": "00:42:00"}}],
                      "1": [{"id": "12", "episode_num": 2, "title": "One Two"}, {"id": "11", "episode_num": 1, "title": "One One", "info": {"plot": "First."}}],
                      "0": [{"id": "1", "episode_num": 1, "title": "Special"}]}}
        """)!)!
        let seasons = keyed.seasons!
        XCTAssertEqual(seasons.map(\.number), [0, 1, 2])
        XCTAssertEqual(seasons.map(\.name), ["Specials", "Season 1", "Season 2"])
        XCTAssertEqual(seasons[1].episodes.map(\.id), [11, 12])
        XCTAssertEqual(seasons[1].poster, "http://img/s1.jpg")
        XCTAssertEqual(seasons[2].episodes[0].durationMs, 2_520_000)
        let listed = Vod.parseSeriesInfo(JSON.parse(#"{"info": {}, "episodes": [[{"id": "5", "season": 1, "episode_num": 1, "title": "A"}], [{"id": "6", "season": 2, "episode_num": 1, "title": "B"}]]}"#)!)!
        XCTAssertEqual(listed.seasons!.map(\.number), [1, 2])

        let special = Vod.episodeItems(showId: 5, showName: "Show", poster: nil, backdrop: nil, season: seasons[0])[0]
        let first = Vod.episodeItems(showId: 5, showName: "Show", poster: nil, backdrop: nil, season: seasons[1])[0]
        XCTAssertNil(special.parentIndex)
        XCTAssertEqual(first.parentIndex, 1)
        XCTAssertEqual(first.grandparentRatingKey, "s5")
        XCTAssertEqual(first.parentRatingKey, "s5:1")
        XCTAssertTrue(first.isIptv)
    }

    func testKeys() {
        XCTAssertEqual(IptvKey.parse(IptvKey.movie(id: 10, ext: "mkv").key), .movie(id: 10, ext: "mkv"))
        XCTAssertEqual(IptvKey.parse(IptvKey.movie(id: 10, ext: nil).key), .movie(id: 10, ext: nil))
        XCTAssertEqual(IptvKey.parse("s5"), .show(id: 5))
        XCTAssertEqual(IptvKey.parse("s5:2"), .season(showId: 5, number: 2))
        XCTAssertEqual(IptvKey.parse("e22.mp4"), .episode(id: 22, ext: "mp4"))
        XCTAssertNil(IptvKey.parse("12345"))
        XCTAssertNil(IptvKey.parse("x"))
    }

    func testMatchingWithPlex() {
        let plex = TitleIndex.ofPlex([entry("1", "The Matrix", 1999, ["tmdb://603", "imdb://tt0133093"]),
                                      entry("2", "Amélie", 2001, original: "Le Fabuleux Destin d'Amélie Poulain")])
        let reloaded = film(10, "EN - The Matrix Reloaded", 2003, ["tmdb": "603"])
        XCTAssertTrue(plex.has(tmdbId: reloaded.tmdbId, key: reloaded.key, year: reloaded.year))
        let amelie = film(11, "Amelie (2002)", nil)
        XCTAssertTrue(plex.has(tmdbId: amelie.tmdbId, key: amelie.key, year: amelie.year))
        let remake = film(12, "Amelie", 2021)
        XCTAssertFalse(plex.has(tmdbId: remake.tmdbId, key: remake.key, year: remake.year))
        let undated = film(13, "The Matrix", nil)
        XCTAssertTrue(plex.has(tmdbId: undated.tmdbId, key: undated.key, year: undated.year))
    }

    func testWhereThingsWereLeft() {
        var watch = IptvWatch()
        let heat = Vod.itemOf(film(10, "Heat", 1995))
        watch.progress(heat, positionMs: 600_000, durationMs: 6_000_000, now: 1_000)
        XCTAssertEqual(watch.apply(heat).viewOffsetMs, 600_000)
        XCTAssertEqual(watch.continueWatching().map(\.ratingKey), [heat.ratingKey])
        watch.progress(heat, positionMs: 5_500_000, durationMs: 6_000_000, now: 2_000)
        // Kept, and read back as it was.
        let kept = IptvWatch(try! JSONDecoder().decode([IptvMark].self, from: JSONEncoder().encode(watch.all)))
        XCTAssertTrue(kept.apply(heat).isWatched)
        XCTAssertEqual(kept.apply(heat).viewOffsetMs, 0)
        XCTAssertEqual(kept.continueWatching(), [])
        watch.setWatched([heat], false, now: 3_000)
        XCTAssertFalse(watch.apply(heat).isWatched)

        let info = Vod.parseSeriesInfo(JSON.parse(#"{"info": {}, "episodes": {"1": [{"id": "11", "episode_num": 1, "title": "A"}, {"id": "12", "episode_num": 2, "title": "B"}]}}"#)!)!
        let episodes = Vod.episodeItems(showId: 5, showName: "Show", poster: nil, backdrop: nil, season: info.seasons![0])
        let season = Vod.seasonItems(showId: 5, showName: "Show", poster: nil, info: info)[0]
        var shows = IptvWatch()
        shows.setWatched([episodes[0]], true, now: 1)
        XCTAssertEqual(shows.apply(season).viewedLeafCount, 1)
        XCTAssertFalse(shows.apply(season).isWatched)
        shows.setWatched([episodes[1]], true, now: 2)
        XCTAssertTrue(shows.apply(season).isWatched)
    }

    func library() -> IptvLibrary {
        var catalog = VodCatalog()
        catalog.movies = [film(1, "The Matrix", 1999, ["added": "100", "category_id": "1", "tmdb": "603", "rating": "8.7"]),
                          film(2, "Heat", 1995, ["added": "300", "category_id": "1", "rating": "8.3"]),
                          film(3, "Amélie", 2001, ["added": "200", "category_id": "2", "rating": "8.0"]),
                          film(4, "Zodiac", 2007, ["added": "50", "category_id": "2"])]
        catalog.movieCategories = [XtreamCategory(id: "1", name: "Action"), XtreamCategory(id: "2", name: "Drama"), XtreamCategory(id: "3", name: "Empty")]
        var l = IptvLibrary()
        l.setCatalog(catalog)
        l.setPlex(movies: [entry("10", "Matrix", 1999, ["tmdb://603"]), entry("11", "Heat", 1995)], shows: [])
        return l
    }

    func testTheLibrary() {
        let l = library()
        let names = { (items: [PlexItem]) in items.map(\.title) }
        let plexWins = l.browse(movies: true, sort: .title, categoryId: nil, unwatchedOnly: false, iptvWins: false)
        XCTAssertEqual(names(plexWins.items), ["Amélie", "Zodiac"])
        XCTAssertTrue(plexWins.items.allSatisfy(\.isIptv))
        XCTAssertEqual(plexWins.categories.map(\.name), ["Drama"])
        XCTAssertEqual(l.browse(movies: true, sort: .title, categoryId: nil, unwatchedOnly: false, iptvWins: true).items.count, 4)
        let heat = PlexItem(ratingKey: "11", title: "Heat", year: 1995, serverBase: "http://plex")
        XCTAssertTrue(l.hides(heat, iptvWins: true))
        XCTAssertFalse(l.hides(heat, iptvWins: false))
        XCTAssertFalse(l.hides(PlexItem(ratingKey: "12", title: "Alien", year: 1979, serverBase: "http://plex"), iptvWins: true))
        func grid(_ sort: IptvSort, _ category: String? = nil) -> [String] {
            names(l.browse(movies: true, sort: sort, categoryId: category, unwatchedOnly: false, iptvWins: true).items)
        }
        XCTAssertEqual(grid(.title), ["Amélie", "Heat", "The Matrix", "Zodiac"])
        XCTAssertEqual(grid(.added), ["Heat", "Amélie", "The Matrix", "Zodiac"])
        XCTAssertEqual(grid(.rated), ["The Matrix", "Heat", "Amélie", "Zodiac"])
        XCTAssertEqual(grid(.released), ["Zodiac", "Amélie", "The Matrix", "Heat"])
        XCTAssertEqual(grid(.title, "2"), ["Amélie", "Zodiac"])
        let letters = l.browse(movies: true, sort: .title, categoryId: nil, unwatchedOnly: false, iptvWins: true).letters
        XCTAssertEqual(letters.map(\.letter), ["A", "H", "M", "Z"])
        XCTAssertEqual(names(l.newest(movies: true, iptvWins: false)), ["Amélie", "Zodiac"])
        XCTAssertEqual(names(l.search("amelie", iptvWins: false)), ["Amélie"])
        XCTAssertEqual(names(l.search("matrix", iptvWins: true)), ["The Matrix"])
        XCTAssertEqual(l.search("matrix", iptvWins: false), [])
        XCTAssertEqual(names(l.related(Vod.itemOf(l.catalog.movies[2]), iptvWins: true)), ["Zodiac"])

        var watched = library()
        watched.watch.setWatched([Vod.itemOf(watched.catalog.movies[1])], true, now: 1)
        XCTAssertEqual(names(watched.browse(movies: true, sort: .title, categoryId: nil, unwatchedOnly: true, iptvWins: true).items), ["Amélie", "The Matrix", "Zodiac"])

        var accents = IptvLibrary()
        var catalog = VodCatalog()
        catalog.movies = [film(1, "Zulu", nil), film(2, "Émile", nil), film(3, "2012", nil), film(4, "Eden", nil), film(5, "alpha", nil)]
        accents.setCatalog(catalog)
        let g = accents.browse(movies: true, sort: .title, categoryId: nil, unwatchedOnly: false, iptvWins: true)
        XCTAssertEqual(names(g.items), ["2012", "alpha", "Eden", "Émile", "Zulu"])
        XCTAssertEqual(g.letters.map(\.letter), ["#", "A", "E", "Z"])
        XCTAssertEqual(g.letters.map(\.count), [1, 1, 2, 1])
    }
}
