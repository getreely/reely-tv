' The screensaver's pictures, the tour's steps and the problem report.

sub Main()
    show = Plex_ParseItem({ ratingKey: "e1", type: "episode", title: "Pilot", art: "", thumb: "/t/e1", grandparentTitle: "Northbound", parentIndex: 1, index: 1 }, "http://a")
    film = Plex_ParseItem({ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, art: "/art/m1" }, "http://a")
    again = Plex_ParseItem({ ratingKey: "m1", type: "movie", title: "Low Orbit", year: 2025, art: "/art/m1" }, "http://a")
    bare = Plex_ParseItem({ ratingKey: "m2", type: "movie", title: "No Art" }, "http://a")
    newest = Plex_ParseItem({ ratingKey: "e9", type: "episode", title: "Finale", art: "/art/show9", grandparentTitle: "Tidewater" }, "http://b")
    home = { continueWatching: [show, film], recentMovies: [again, bare], recentEpisodes: [{ newest: newest }], watchlist: [], iptvMovies: [] }
    slides = Saver_Slides(home, 12)
    titles = []
    for each s in slides
        titles.Push(s.title)
    end for
    Expect("Home's artwork, each picture once, none without one", titles, ["Northbound", "Low Orbit", "Tidewater"])
    Expect("an episode by its still, under its show", [slides[0].path, slides[0].caption, slides[0].base], ["/t/e1", "S1 · E1", "http://a"])
    Expect("a film by its backdrop and year", [slides[1].path, slides[1].caption], ["/art/m1", "2025"])
    Expect("no more than asked for", Saver_Slides(home, 2).Count(), 2)
    Expect("no Home yet, no pictures", [Saver_Slides(invalid, 12), Saver_Slides({}, 12)], [[], []])

    steps = Tour_Steps()
    Expect("the tour's steps, first and last", [steps.Count(), steps[0].title, steps[steps.Count() - 1].title], [7, "Welcome to Reely", "You're all set"])
    Expect("the Roku's ✱ key, not holding OK", steps[2].key, "✱")

    p = Problem_Entry("libraryPage", "Type Mismatch.", [{ filename: "pkg:/source/lib/plexapi.brs", line_number: 212 }, "junk", { filename: "pkg:/components/tasks/PlexTask.brs", line_number: 30 }], 1700000000)
    Expect("what went wrong, kept", [p.at, p.what, p.message, p.lines], [1700000000, "libraryPage", "Type Mismatch.", ["pkg:/source/lib/plexapi.brs:212", "pkg:/components/tasks/PlexTask.brs:30"]])
    Expect("as it reads in Settings", Problem_Detail(p), "Type Mismatch." + Chr(10) + "While: libraryPage" + Chr(10) + "  at pkg:/source/lib/plexapi.brs:212" + Chr(10) + "  at pkg:/components/tasks/PlexTask.brs:30")
    Expect("no message is still a report", Problem_Entry("", "", invalid, 1).message, "Something stopped working.")
end sub
