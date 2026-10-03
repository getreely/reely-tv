' The provider's films and series, against the same cases as the LG app's vod.test.ts.

function Film_(id as integer, name as string, year as dynamic, extra = invalid as dynamic) as object
    f = { stream_id: id.ToStr(), name: name }
    if year <> invalid then f.year = year.ToStr()
    if extra = invalid then extra = {}
    for each k in extra
        f[k] = extra[k]
    end for
    return Vod_TitleOf(f, false)
end function

function Names_(list as object) as object
    out = []
    for each i in list
        if i.title <> invalid then out.Push(i.title) else out.Push(i.name)
    end for
    return out
end function

function Library_() as object
    lib = Vod_NewLibrary()
    movies = [
        Film_(1, "The Matrix", 1999, { added: "100", category_id: "1", tmdb: "603", rating: "8.7" }),
        Film_(2, "Heat", 1995, { added: "300", category_id: "1", rating: "8.3" }),
        Film_(3, "Amélie", 2001, { added: "200", category_id: "2", rating: "8.0" }),
        Film_(4, "Zodiac", 2007, { added: "50", category_id: "2" })
    ]
    Vod_SetCatalog(lib, movies, [], [{ id: "1", name: "Action" }, { id: "2", name: "Drama" }, { id: "3", name: "Empty" }], [])
    ' What Plex has: The Matrix by its tmdb id, Heat by name and year.
    Vod_AddPlexEntry(lib.plexMovies, lib.plexTmdb, { ratingKey: "10", title: "Matrix", year: 1999, Guid: [{ id: "tmdb://603" }] }, "http://plex")
    Vod_AddPlexEntry(lib.plexMovies, lib.plexTmdb, { ratingKey: "11", title: "Heat", year: 1995 }, "http://plex")
    return lib
end function

function PlexMovie_(key as string, title as string, year as integer) as object
    return Plex_ParseItem({ ratingKey: key, title: title, year: year, type: "movie" }, "http://plex")
end function

sub Main()
    ' Names come apart into title, year and tag.
    Expect("a tag and a year", Vod_ParseName("EN - The Matrix (1999)"), { name: "The Matrix", year: 1999, tag: "EN" })
    Expect("a tag in bars", Vod_ParseName("|4K| Dune"), { name: "Dune", year: invalid, tag: "4K" })
    Expect("a tag in brackets, a year after a dash", Vod_ParseName("[FR] Amélie - 2001"), { name: "Amélie", year: 2001, tag: "FR" })
    Expect("a year in square brackets", Vod_ParseName("Heat [1995]"), { name: "Heat", year: 1995, tag: invalid })
    Expect("a year after an en dash", Vod_ParseName("Heat – 1995").year, 1995)
    Expect("names that only look like tags are left alone", [Vod_ParseName("CSI: Miami").name, Vod_ParseName("WALL-E").name, Vod_ParseName("2012").name, Vod_ParseName("Blade Runner 2049").name], ["CSI: Miami", "WALL-E", "2012", "Blade Runner 2049"])
    Expect("a year given is kept", Vod_ParseName("Blade Runner 2049 (2017)", 2017).year, 2017)

    ' The provider's lists.
    titles = Vod_ReadTitles([
        { num: 1, name: "EN - The Matrix (1999)", stream_id: 10, stream_icon: "http://img/m.jpg", rating: "8.7", added: "1600000000", category_id: "3", container_extension: "mkv", tmdb: "603" },
        { num: 2, name: "Heat", stream_id: "11", stream_icon: "", rating: 0, added: invalid, category_id: 3 },
        { num: 3, name: "No id" },
        "junk",
        invalid,
        { num: 4, name: "The Matrix again", stream_id: 10 },
        { num: 5, name: "Half a number", stream_id: "12abc" }
    ], false)
    ids = []
    for each t in titles
        ids.Push(t.id)
    end for
    Expect("a film list, each once, the broken ones left out", ids, [10, 11])
    matrix = titles[0]
    Expect("a film read", [matrix.name, matrix.year, matrix.tag, matrix.added, matrix.tmdbId, matrix.extension, matrix.categoryId, matrix.poster], ["The Matrix", 1999, "EN", 1600000000, "603", "mkv", "3", "http://img/m.jpg"])
    Expect("its rating", Int(matrix.rating * 10 + 0.5), 87)
    Expect("no poster, no rating, a number category", [titles[1].poster, titles[1].rating, titles[1].categoryId], ["", 0, "3"])
    Expect("a refused login is no films, not a crash", Vod_ReadTitles({ user_info: { auth: 0 } }, false), [])
    shows = Vod_ReadTitles([{ name: "Breaking Bad", series_id: 5, cover: "http://img/bb.jpg", plot: "Chemistry.", genre: "Drama", releaseDate: "2008-01-20", last_modified: "1700000000", category_id: "9", tmdb: "1396" }], true)
    Expect("a series reads its own fields", [shows[0].series, shows[0].id, shows[0].year, shows[0].plot, shows[0].added, shows[0].poster], [true, 5, 2008, "Chemistry.", 1700000000, "http://img/bb.jpg"])
    Expect("a year that isn't one falls back to the release date", Vod_ReadTitles([{ name: "Show", series_id: 1, year: "N/A", releaseDate: "2011-04-17" }], true)[0].year, 2011)

    ' Pages.
    info = Vod_ParseMovieInfo({
        info: { name: "The Matrix", plot: "Neo.", cast: "Keanu Reeves, Carrie-Anne Moss", director: "Lana Wachowski", genre: "Action / Sci-Fi", duration_secs: "8160", backdrop_path: ["http://img/back.jpg"], releasedate: "1999-03-31", rating: "8.7", tmdb_id: "603" },
        movie_data: { stream_id: 10, container_extension: "mkv" }
    })
    Expect("a film's page", [info.cast, info.genres, info.durationMs, info.backdrop, info.extension], [["Keanu Reeves", "Carrie-Anne Moss"], ["Action", "Sci-Fi"], 8160000, "http://img/back.jpg", "mkv"])
    empty = Vod_ParseMovieInfo({ info: [], movie_data: { stream_id: 10 } })
    Expect("a page with nothing to say is still a page", [empty.plot, empty.durationMs], ["", 0])
    Expect("no page at all", Vod_ParseMovieInfo("nope"), invalid)
    keyed = Vod_ParseSeriesInfo({
        info: { name: "Show", plot: "About.", backdrop_path: [] },
        seasons: [{ season_number: 1, cover: "http://img/s1.jpg" }],
        episodes: {
            "2": [{ id: "22", episode_num: 1, title: "Two One", container_extension: "mp4", info: { duration: "00:42:00" } }],
            "1": [{ id: "12", episode_num: 2, title: "One Two" }, { id: "11", episode_num: 1, title: "One One", info: { plot: "First." } }],
            "0": [{ id: "1", episode_num: 1, title: "Special" }]
        }
    })
    numbers = []
    names = []
    for each s in keyed.seasons
        numbers.Push(s.number)
        names.Push(s.name)
    end for
    Expect("seasons by number, specials first", [numbers, names], [[0, 1, 2], ["Specials", "Season 1", "Season 2"]])
    Expect("a season's episodes in order", [keyed.seasons[1].episodes[0].id, keyed.seasons[1].episodes[1].id], [11, 12])
    Expect("a season's poster and an episode's length", [keyed.seasons[1].poster, keyed.seasons[2].episodes[0].durationMs], ["http://img/s1.jpg", 2520000])
    listed = Vod_ParseSeriesInfo({ info: {}, episodes: [[{ id: "5", season: 1, episode_num: 1, title: "A" }], [{ id: "6", season: 2, episode_num: 1, title: "B" }]] })
    Expect("seasons listed rather than keyed", [listed.seasons[0].number, listed.seasons[1].number], [1, 2])
    special = Vod_ParseSeriesInfo({ info: {}, episodes: [[{ id: "5", season: 0, episode_num: 1, title: "Special" }]] })
    Expect("season 0 in its own field stays a special", special.seasons[0].name, "Specials")

    ' Keys and items.
    Expect("a film's key", Vod_ParseKey(Vod_MovieKey(10, "mkv")), { kind: "movie", id: 10, extension: "mkv" })
    Expect("a film's key with no extension", Vod_ParseKey(Vod_MovieKey(10, "")), { kind: "movie", id: 10, extension: "" })
    Expect("a show's key", Vod_ParseKey(Vod_ShowKey(5)), { kind: "show", id: 5 })
    Expect("a season's key", Vod_ParseKey(Vod_SeasonKey(5, 2)), { kind: "season", showId: 5, number: 2 })
    Expect("an episode's key", Vod_ParseKey(Vod_EpisodeKey(22, "mp4")), { kind: "episode", id: 22, extension: "mp4" })
    Expect("not ours", [Vod_ParseKey("12345"), Vod_ParseKey("x"), Vod_ParseKey("s5:x")], [invalid, invalid, invalid])
    two = Vod_ParseSeriesInfo({ info: {}, episodes: { "0": [{ id: "1", episode_num: 1, title: "Special" }], "1": [{ id: "11", episode_num: 1, title: "Pilot", container_extension: "mkv" }] } })
    sp = Vod_EpisodeItems(5, "Show", "", "", two.seasons[0])[0]
    first = Vod_EpisodeItems(5, "Show", "", "", two.seasons[1])[0]
    Expect("a special is outside any season", sp.parentIndex, invalid)
    Expect("an episode under its show", [first.parentIndex, first.grandparentRatingKey, first.parentRatingKey, first.serverBase, first.ratingKey], [1, "s5", "s5:1", "iptv:", "e11.mkv"])
    Expect("marked IPTV", [Vod_IsIptv(first), Vod_IsIptv(PlexMovie_("1", "Heat", 1995))], [true, false])
    Expect("the files' addresses", [Vod_MovieUrl({ base: "http://p.tv", username: "a b", password: "c" }, 10, "mkv"), Vod_EpisodeUrl({ base: "http://p.tv", username: "a", password: "c" }, 11, "")], ["http://p.tv/movie/a%20b/c/10.mkv", "http://p.tv/series/a/c/11.mp4"])

    ' Matching with Plex: by id, else by name and year.
    index = Vod_NewIndex()
    tmdbs = {}
    Vod_AddPlexEntry(index, tmdbs, { ratingKey: "1", title: "The Matrix", year: 1999, Guid: [{ id: "imdb://tt0133093" }, { id: "tmdb://603" }] }, "http://plex")
    Vod_AddPlexEntry(index, tmdbs, { ratingKey: "2", title: "Amélie", year: 2001, originalTitle: "Le Fabuleux Destin d'Amélie Poulain" }, "http://plex")
    reloaded = Film_(10, "EN - The Matrix Reloaded", 2003, { tmdb: "603" })
    Expect("by tmdb id", Vod_IndexHas(index, reloaded.tmdbId, reloaded.key, reloaded.year), true)
    amelie = Film_(11, "Amelie (2002)", invalid)
    Expect("by name, accents aside, a year either side", Vod_IndexHas(index, amelie.tmdbId, amelie.key, amelie.year), true)
    remake = Film_(12, "Amelie", 2021)
    Expect("not a remake", Vod_IndexHas(index, remake.tmdbId, remake.key, remake.year), false)
    undated = Film_(13, "The Matrix", invalid)
    Expect("by name alone when there's no year", Vod_IndexHas(index, undated.tmdbId, undated.key, undated.year), true)
    Expect("nothing in an empty index", Vod_IndexHas(Vod_NewIndex(), "603", "thematrix", 1999), false)
    Expect("Plex's tmdb id kept by where it is", tmdbs["http://plex|1"], "603")

    ' Where things were left.
    marks = {}
    heat = Vod_ItemOf(Film_(10, "Heat", 1995))
    Vod_Progress(marks, heat, 600000, 6000000, 1000)
    Expect("where it was left", Vod_Apply(marks, Vod_ItemOf(Film_(10, "Heat", 1995))).viewOffsetMs, 600000)
    lib = Vod_NewLibrary()
    Vod_SetCatalog(lib, [Film_(10, "Heat", 1995, { stream_icon: "http://img/h.jpg" })], [], [], [])
    cw = Vod_ContinueWatching(lib, marks)
    Expect("in Continue Watching, with its poster", [Titles_(cw), cw[0].thumb, cw[0].viewOffsetMs, cw[0].lastViewedAt], [[heat.ratingKey], "http://img/h.jpg", 600000, 1000])
    Vod_Progress(marks, heat, 5500000, 6000000, 2000)
    kept = Vod_UnpackMarks(ParseJson(FormatJson(Vod_PackMarks(marks, 50))))
    done = Vod_Apply(kept, Vod_ItemOf(Film_(10, "Heat", 1995)))
    Expect("near the end is watched, and kept", [Plex_IsWatched(done), done.viewOffsetMs], [true, 0])
    Expect("watched isn't continued", Vod_ContinueWatching(lib, kept), [])
    Vod_SetWatched(marks, [heat], false, 3000)
    Expect("unwatched again", Plex_IsWatched(Vod_Apply(marks, Vod_ItemOf(Film_(10, "Heat", 1995)))), false)
    many = {}
    for i = 1 to 70
        Vod_Progress(many, Vod_ItemOf(Film_(i, "Film " + i.ToStr(), invalid)), 1000, 100000, i)
    end for
    packed = Vod_PackMarks(many, 50)
    Expect("only the latest are kept", [packed.Count(), packed[0].at, packed[49].at], [50, 70, 21])

    ' A show counts its watched episodes.
    marks = {}
    two = Vod_ParseSeriesInfo({ info: {}, episodes: { "1": [{ id: "11", episode_num: 1, title: "A" }, { id: "12", episode_num: 2, title: "B" }] } })
    eps = Vod_EpisodeItems(5, "Show", "", "", two.seasons[0])
    season = Vod_SeasonItems(5, "Show", "", two)[0]
    Vod_SetWatched(marks, [eps[0]], true, 10)
    Expect("one of two watched", [Vod_Apply(marks, season).viewedLeafCount, Plex_IsWatched(Vod_Apply(marks, season))], [1, false])
    Vod_SetWatched(marks, [eps[1]], true, 11)
    Expect("the season watched", Plex_IsWatched(Vod_Apply(marks, season)), true)
    show = Vod_ItemOf(Vod_ReadTitles([{ name: "Show", series_id: 5 }], true)[0])
    Expect("the show counts them", Vod_Apply(marks, show).viewedLeafCount, 2)
    Vod_Progress(marks, eps[0], 1000, 100000, 20)
    shown = Vod_ContinueWatching(Vod_NewLibrary(), {})
    Expect("nothing started, nothing to continue", shown, [])
    Vod_SetWatched(marks, [eps[0]], false, 30)
    Vod_Progress(marks, eps[0], 5000, 100000, 40)
    back = Vod_ContinueWatching(Vod_NewLibrary(), marks)[0]
    Expect("an episode back from its mark", [back.ratingKey, back.grandparentTitle, back.parentIndex, back.index, back.parentRatingKey, back.grandparentRatingKey], ["e11", "Show", 1, 1, "s5:1", "s5"])

    ' The library: Plex winning leaves out what Plex has.
    lib = Library_()
    grid = Vod_Browse(lib, true, "titleSort:asc", "", false, false, {})
    Expect("Plex's copies win", Names_(grid), ["Amélie", "Zodiac"])
    Expect("categories with something in them", Names_(Vod_Categories(lib, true, false)), ["Drama"])
    Expect("IPTV winning keeps everything", Vod_Browse(lib, true, "titleSort:asc", "", false, true, {}).Count(), 4)
    Expect("and Plex's copy gives way", [Vod_Hides(lib, PlexMovie_("11", "Heat", 1995), true), Vod_Hides(lib, PlexMovie_("11", "Heat", 1995), false), Vod_Hides(lib, PlexMovie_("12", "Alien", 1979), true), Vod_Hides(lib, PlexMovie_("10", "Matrix", 2000), true)], [true, false, false, true])
    Expect("A–Z", Names_(Vod_Browse(lib, true, "titleSort:asc", "", false, true, {})), ["Amélie", "Heat", "The Matrix", "Zodiac"])
    Expect("recently added", Names_(Vod_Browse(lib, true, "addedAt:desc", "", false, true, {})), ["Heat", "Amélie", "The Matrix", "Zodiac"])
    Expect("critic rating", Names_(Vod_Browse(lib, true, "rating:desc", "", false, true, {})), ["The Matrix", "Heat", "Amélie", "Zodiac"])
    Expect("newest releases", Names_(Vod_Browse(lib, true, "originallyAvailableAt:desc", "", false, true, {})), ["Zodiac", "Amélie", "The Matrix", "Heat"])
    Expect("a category", Names_(Vod_Browse(lib, true, "titleSort:asc", "2", false, true, {})), ["Amélie", "Zodiac"])
    letters = Vod_Letters(Vod_Browse(lib, true, "titleSort:asc", "", false, true, {}))
    Expect("The Matrix under M, the rail's counts", letters, [{ letter: "A", count: 1 }, { letter: "H", count: 1 }, { letter: "M", count: 1 }, { letter: "Z", count: 1 }])
    Expect("where M starts", Plex_LetterStart(letters, "M"), 2)
    odd = Vod_NewLibrary()
    Vod_SetCatalog(odd, [Film_(1, "Zulu", invalid), Film_(2, "Émile", invalid), Film_(3, "2012", invalid), Film_(4, "Eden", invalid), Film_(5, "alpha", invalid)], [], [], [])
    sorted = Vod_Browse(odd, true, "titleSort:asc", "", false, true, {})
    Expect("accents with their letter, numbers first", Names_(sorted), ["2012", "alpha", "Eden", "Émile", "Zulu"])
    Expect("so the rail lands right", Vod_Letters(sorted), [{ letter: "#", count: 1 }, { letter: "A", count: 1 }, { letter: "E", count: 2 }, { letter: "Z", count: 1 }])
    marks = {}
    Vod_SetWatched(marks, [Vod_ItemOf(lib.movies[1])], true, 5)
    Expect("unwatched only leaves out what's been watched here", Names_(Vod_Browse(lib, true, "titleSort:asc", "", true, true, marks)), ["Amélie", "The Matrix", "Zodiac"])
    page = Vod_Items(Vod_Browse(lib, true, "titleSort:asc", "", false, true, marks), marks, 1, 2)
    Expect("a page of the grid, as items marked", [Names_(page), Plex_IsWatched(page[0]), page[0].serverBase], [["Heat", "The Matrix"], true, "iptv:"])
    Expect("Home's newest, less what Plex has", Names_(Vod_Newest(lib, true, false, {})), ["Amélie", "Zodiac"])
    Expect("Home's newest, all of it", Names_(Vod_Newest(lib, true, true, {})), ["Heat", "Amélie", "The Matrix", "Zodiac"])
    Expect("search, accents and all", Names_(Vod_Search(lib, "amelie", false, {})), ["Amélie"])
    Expect("search the other way", Names_(Vod_Search(lib, "Amélie", false, {})), ["Amélie"])
    Expect("search best first", Names_(Vod_Search(lib, "matrix", true, {})), ["The Matrix"])
    Expect("what Plex has isn't found twice", [Vod_Search(lib, "matrix", false, {}), Vod_Search(lib, "  ", true, {})], [[], []])
    Expect("more like this is the same category", Names_(Vod_Related(lib, Vod_ItemOf(lib.movies[2]), true, {})), ["Zodiac"])
    off = Vod_NewLibrary()
    Expect("switched off, there's nothing", [Vod_Browse(off, true, "titleSort:asc", "", false, true, {}), Vod_Newest(off, true, true, {}), Vod_Search(off, "heat", true, {})], [[], [], []])

    ' Home with the provider's in it.
    plexHeat = PlexMovie_("11", "Heat", 1995)
    plexHeat.lastViewedAt = 50
    plexHeat.viewOffsetMs = 1000
    marks = {}
    Vod_Progress(marks, Vod_ItemOf(lib.movies[3]), 2000, 100000, 60)
    home = Vod_ComposeHome(lib, marks, { continueWatching: [plexHeat], recentMovies: [PlexMovie_("12", "Alien", 1979), plexHeat], recentEpisodes: [], playlists: [], answered: true }, false)
    Expect("Continue Watching mixed, latest first", Names_(home.continueWatching), ["Zodiac", "Heat"])
    Expect("Plex's rows kept when Plex wins", Names_(home.recentMovies), ["Alien", "Heat"])
    Expect("the provider's newest rows", [Names_(home.iptvMovies), home.iptvShows, home.answered], [["Amélie", "Zodiac"], [], true])
    wins = Vod_ComposeHome(lib, marks, { continueWatching: [plexHeat], recentMovies: [plexHeat] }, true)
    Expect("Plex's copy left out when the provider's wins", [Names_(wins.continueWatching), Names_(wins.recentMovies)], [["Zodiac"], []])

    ' A title's page.
    d = Vod_DetailOf("m1", lib.movies[0], info, false)
    Expect("a film's page shaped as Plex's", [d.title, d.type, d.year, d.durationMs, d.genres, d.roles[0].name, d.art, d.serverBase, Plex_Facts(d)], ["The Matrix", "movie", 1999, 8160000, ["Action", "Sci-Fi"], "Keanu Reeves", "http://img/back.jpg", "iptv:", "1999  ·  2h 16m  ·  Action, Sci-Fi"])
    sd = Vod_DetailOf("s5", invalid, keyed, true)
    Expect("a series' page", [sd.title, sd.type, sd.leafCount], ["Show", "show", 4])
end sub
