sub Main()
    ' Addresses as people type them.
    Expect("a bare address gets http", Reely_Normalize(" reely.example.com/ "), "http://reely.example.com")
    Expect("an address with a port", Reely_IsValid("192.168.1.5:8788"), true)
    Expect("not an address", Reely_IsValid("not an address"), false)
    Expect("the host alone", Reely_HostOf("https://reely.tailbba88a.ts.net/"), "reely.tailbba88a.ts.net")
    Expect("a TMDB poster path", Reely_ImageUrl("/m.jpg", "https://image.tmdb.org/t/p/", "w342"), "https://image.tmdb.org/t/p/w342/m.jpg")
    Expect("a TheTVDB address as it is", Reely_ImageUrl("https://artworks.thetvdb.com/bb.jpg", "x", "w342"), "https://artworks.thetvdb.com/bb.jpg")

    ' Titles: each once, the broken ones left out; a film and a show apart.
    titles = Reely_TitlesOf([
        { tmdbId: 603, kind: "movie", title: "The Matrix", year: 1999, poster: "/matrix.jpg" },
        { tmdbId: 603, kind: "movie", title: "The Matrix" },
        { tmdbId: 603, kind: "show", title: "Matrix (the show)" },
        { kind: "movie", title: "No ids" },
        { tmdbId: 9, kind: "album", title: "Not a kind" },
        { tvdbId: 81189, kind: "show", title: "Breaking Bad" }
    ], "https://image.tmdb.org/t/p")
    Expect("each title once", titles.Count(), 3)
    Expect("its poster", titles[0].poster, "https://image.tmdb.org/t/p/w342/matrix.jpg")
    Expect("keys by kind", [Reely_Key(titles[0]), Reely_Key(titles[1]), Reely_Key(titles[2])], ["movie:t603", "show:t603", "show:v81189"])

    rows = Reely_ExploreRows({ imageBase: "https://image.tmdb.org/t/p", movies: [{ tmdbId: 603, kind: "movie", title: "The Matrix" }], shows: [], popularMovies: invalid,
        providers: [{ key: "netflix", name: "Netflix", kind: "show", results: [{ tvdbId: 81189, kind: "show", title: "Breaking Bad" }] }] })
    Expect("rows with titles only, named as the other apps", [rows.Count(), rows[0].title, rows[1].title], [2, "Trending Movies", "Shows on Netflix"])

    ' A title's page.
    d = Reely_ParseDetail({ imageBase: "https://image.tmdb.org/t/p", inLibraries: [2], preview: { kind: "show", tmdbId: 1399, title: "Game of Thrones", backdrop: "/b.jpg", genres: ["Drama", ""], runtime: 60, status: "Ended",
        seasons: [{ number: 0, name: "Specials" }, { number: 1, name: "", episodes: [1, 2, 3] }] } }, titles[0])
    Expect("its backdrop", d.backdrop, "https://image.tmdb.org/t/p/w1280/b.jpg")
    Expect("seasons without the specials, named", [d.seasons.Count(), d.seasons[0].name, d.seasons[0].episodes], [1, "Season 1", 3])
    Expect("where it is already", d.inLibraries, [2])
    Expect("nothing to read", Reely_ParseDetail({}, titles[0]), invalid)

    ' Where it can go.
    places = Reely_ParsePlaces({ user: { role: "user", mayAdd: false, defaultLibraryId: 3 } }, { libraries: [{ id: 1, name: "Movies", kind: "movies" }, { id: 2, name: "TV", kind: "shows" }, { id: 3, name: "Kids TV", kind: "shows" }] })
    Expect("an account that asks", places.adds, false)
    show = titles[1]
    among = Reely_LibrariesFor(places, show, [2])
    Expect("show libraries not holding it", among.Count(), 1)
    Expect("the default when it's one of them", Reely_PreferredLibrary(places, among).name, "Kids TV")
    Expect("the owner adds", Reely_ParsePlaces({ user: { role: "admin" } }, invalid).adds, true)

    ' How posters are marked.
    marks = Reely_ParseMarks([{ tmdbId: 603, filePath: "/m/matrix.mkv" }, { tmdbId: 604, downloading: true }],
        [{ tmdbId: 1399, tvdbId: 121361, onDisk: 73, aired: 73, wanted: 0 }, { tvdbId: 81189, onDisk: 3, aired: 62, wanted: 59 }],
        [{ kind: "movie", tmdbId: 605 }, { kind: "show", tvdbId: 79168 }])
    Expect("a film on disk", Reely_Badge(marks, { kind: "movie", tmdbId: 603, tvdbId: 0 }), "In library")
    Expect("downloading", Reely_Badge(marks, { kind: "movie", tmdbId: 604, tvdbId: 0 }), "Downloading")
    Expect("a show complete", Reely_Badge(marks, { kind: "show", tmdbId: 1399, tvdbId: 0 }), "In library")
    Expect("a show partly there, by TheTVDB", Reely_Badge(marks, { kind: "show", tmdbId: 0, tvdbId: 81189 }), "Partial")
    Expect("asked for", Reely_Badge(marks, { kind: "movie", tmdbId: 605, tvdbId: 0 }), "Requested")
    Expect("a show asked for by TheTVDB, found by TMDB too", Reely_Badge(marks, { kind: "show", tmdbId: 1668, tvdbId: 79168 }), "Requested")
    Expect("nothing", Reely_Badge(marks, { kind: "movie", tmdbId: 999, tvdbId: 0 }), "")

    mine = Reely_ParseRecords({ requests: [{ id: 1, kind: "movie", tmdbId: 999, title: "Old", status: "denied" }, { id: 5, kind: "movie", tmdbId: 998, title: "New", status: "approved", seasons: invalid }] })
    Expect("this account's requests, newest first", [mine[0].title.title, mine[1].status], ["New", "denied"])
    Expect("approved", Reely_BadgeFor({ kind: "movie", tmdbId: 998, tvdbId: 0 }, marks, mine), "Approved")
    Expect("declined", Reely_BadgeFor({ kind: "movie", tmdbId: 999, tvdbId: 0 }, marks, mine), "Declined")
    shown = Reely_ShownRows([{ id: "movies", title: "Trending Movies", titles: [{ kind: "movie", tmdbId: 603, tvdbId: 0, title: "The Matrix" }] }, { id: "x", title: "X", titles: [{ kind: "movie", tmdbId: 1, tvdbId: 0, title: "One" }] }], marks, mine)
    Expect("what's in the library left out, an empty row with it", [shown.Count(), shown[0].id], [1, "x"])

    ' Signing in: what each refusal says.
    Expect("in", Reely_SignInProblem(200, ""), "")
    Expect("this device's sign-in refused", Reely_SignInProblem(401, FormatJson({ error: "plex.tv didn't accept that sign-in" })), Reely_PlexRejectedText())
    Expect("Reely's own Plex link refused: the owner's to fix", Reely_SignInProblem(502, FormatJson({ error: "plex.tv: Unauthorized: <?xml version=" + Chr(34) + "1.0" + Chr(34) + "?><errors><error>Invalid authentication token.</error></errors>" })), Reely_OwnerLinkBrokenText())
    Expect("not shared", Reely_SignInProblem(403, "{}"), "Your Plex account doesn't have access to this server's requests.")
    Expect("markup is never shown", Reely_SignInProblem(502, FormatJson({ error: "<html>Bad gateway</html>" })), "Reely couldn't do that. Try again.")
    Expect("Reely's own words", Reely_SignInProblem(500, FormatJson({ error: "Reely is updating." })), "Reely is updating.")
    Expect("the session cookie", Reely_CookieFrom([{ "Content-Type": "application/json" }, { "Set-Cookie": "reely_session=abc; Path=/; HttpOnly" }]), "reely_session=abc")
    Expect("the session sent back", ReelyApi_Headers("reely_session=abc", true), { "Accept": "application/json", "Content-Type": "application/json", "Cookie": "reely_session=abc" })
    Expect("no session, no Cookie", ReelyApi_Headers("", false), { "Accept": "application/json" })
    Expect("a header named in lower case", Reely_CookieFrom([{ "set-cookie": "reely_session=xyz; Path=/" }]), "reely_session=xyz")

    ' Ready to watch: approved, arrived (a show partly), not said already.
    readyMarks = Reely_ParseMarks([{ tmdbId: 10, filePath: "/a.mkv" }, { tmdbId: 11 }], [{ tmdbId: 20, onDisk: 2, aired: 10, wanted: 8 }], [])
    asked = [
        { title: { kind: "movie", tmdbId: 10, tvdbId: 0, title: "Here" }, status: "approved" },
        { title: { kind: "movie", tmdbId: 11, tvdbId: 0, title: "Not yet" }, status: "approved" },
        { title: { kind: "show", tmdbId: 20, tvdbId: 0, title: "Partly" }, status: "approved" },
        { title: { kind: "movie", tmdbId: 10, tvdbId: 0, title: "Here" }, status: "approved" },
        { title: { kind: "movie", tmdbId: 30, tvdbId: 0, title: "Asked" }, status: "pending" }]
    ready = Reely_ReadyRequests(asked, readyMarks, {})
    Expect("what's arrived, once each", [ready.Count(), ready[0].title, ready[1].title], [2, "Here", "Partly"])
    Expect("not said twice", Reely_ReadyRequests(asked, readyMarks, { "movie:t10": true }).Count(), 1)
    plexItems = [{ type: "show", title: "Here", year: 2020 }, { type: "movie", title: "here", year: 2021 }]
    Expect("found in Plex by kind, name and year", Reely_FindInPlex({ kind: "movie", title: "Here", year: 2021 }, plexItems).year, 2021)
    Expect("not another year's", Reely_FindInPlex({ kind: "movie", title: "Here", year: 1999 }, plexItems), invalid)

    ' What Plex has, by its titles' outside ids, is in the library too.
    plexMarks = { movies: {}, showsByTmdb: {}, showsByTvdb: {}, requested: { "movie-27205": true }, plexMovies: {}, plexShows: {} }
    Plex_AddGuids(plexMarks.plexMovies, [{ Guid: [{ id: "imdb://tt1375666" }, { id: "tmdb://27205" }] }, { Guid: [{ id: "tmdb://550" }] }, "junk", { title: "No guids" }])
    Plex_AddGuids(plexMarks.plexShows, [{ Guid: [{ id: "tvdb://121361" }] }])
    Expect("on Plex, though Reely didn't add it", Reely_BadgeFor({ kind: "movie", tmdbId: 550, tvdbId: 0 }, plexMarks, []), "In library")
    Expect("asked for and on Plex now", Reely_BadgeFor({ kind: "movie", tmdbId: 27205, tvdbId: 0 }, plexMarks, []), "In library")
    Expect("a show by its tvdb id", Reely_BadgeFor({ kind: "show", tmdbId: 1399, tvdbId: 121361 }, plexMarks, []), "In library")
    Expect("a film's id isn't a show's", Reely_BadgeFor({ kind: "show", tmdbId: 550, tvdbId: 0 }, plexMarks, []), "")
    Expect("not on Plex", Reely_BadgeFor({ kind: "movie", tmdbId: 1, tvdbId: 0 }, plexMarks, []), "")
    Expect("Plex not read, nothing assumed", Reely_PlexHas({ kind: "movie", tmdbId: 550, tvdbId: 0 }, { movies: {} }), false)
    rows = Reely_ShownRows([{ id: "movies", title: "Trending Movies", titles: [{ kind: "movie", tmdbId: 550, tvdbId: 0, title: "Fight Club" }, { kind: "movie", tmdbId: 1, tvdbId: 0, title: "One" }] }], plexMarks, [])
    Expect("left out of the rows", [rows.Count(), rows[0].titles.Count(), rows[0].titles[0].title], [1, 1, "One"])

    ' A show asked for one season of, and all of it here: still only part of the show.
    partMarks = Reely_ParseMarks([], [{ tmdbId: 1396, onDisk: 7, aired: 62, wanted: 0 }, { tmdbId: 1, onDisk: 62, aired: 62, wanted: 0 }], [])
    Expect("one season here is part of a show", Reely_Badge(partMarks, { kind: "show", tmdbId: 1396, tvdbId: 0 }), "Partial")
    Expect("every aired episode here is all of it", Reely_Badge(partMarks, { kind: "show", tmdbId: 1, tvdbId: 0 }), "In library")
    seasonsList = []
    for each n in [1, 2, 3]
        seasonsList.Push({ number: n, name: "Season " + n.ToStr(), episodes: [{}] })
    end for
    part = Reely_ParseDetail({ inLibraries: [4], seasonsAsked: [{ libraryId: 4, seasons: [1] }], preview: { kind: "show", tmdbId: 1396, title: "Breaking Bad", seasons: seasonsList } }, { kind: "show", tmdbId: 1396, tvdbId: 0, title: "Breaking Bad" })
    left = []
    for each s in Reely_SeasonsLeft(part, 4)
        left.Push(s.number)
    end for
    Expect("the seasons it hasn't got are offered", left, [2, 3])
    Expect("another library is offered all of it", Reely_SeasonsLeft(part, 5).Count(), 3)
    Expect("the library holding part of it isn't all of it", [Reely_CompleteIn(part, 4), Reely_HoldingAll(part)], [false, []])
    whole = Reely_ParseDetail({ inLibraries: [4], seasonsAsked: [{ libraryId: 4, seasons: [1, 2, 3] }], preview: { kind: "show", tmdbId: 1396, title: "Breaking Bad", seasons: seasonsList } }, { kind: "show", tmdbId: 1396, tvdbId: 0, title: "Breaking Bad" })
    older = Reely_ParseDetail({ inLibraries: [4], preview: { kind: "show", tmdbId: 1396, title: "Breaking Bad", seasons: seasonsList } }, { kind: "show", tmdbId: 1396, tvdbId: 0, title: "Breaking Bad" })
    Expect("every season asked for, or a Reely that doesn't say, is all of it", [Reely_CompleteIn(whole, 4), Reely_CompleteIn(older, 4), Reely_HoldingAll(older)], [true, true, [4]])
end sub
