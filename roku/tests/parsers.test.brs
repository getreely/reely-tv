sub Main()
    ' A title's page: the facts, the cast, the copies of it, where the show is up to.
    d = Plex_ParseDetail({
        ratingKey: "show1", type: "show", title: "Northbound", year: 2024, contentRating: "TV-14", leafCount: 30,
        Genre: [{ tag: "Drama" }, { tag: "Thriller" }, { tag: "Crime" }],
        Role: [{ id: "77", tag: "Ana Orbit", role: "Driver", thumb: "/p/77" }, { tag: "" }],
        Media: [{ videoResolution: "4k", videoCodec: "hevc" }, { videoResolution: "1080", videoCodec: "h264" }],
        OnDeck: { Metadata: [{ ratingKey: "e2", parentRatingKey: "s1" }] }
    }, "http://a")
    Expect("the cast, the nameless left out", d.roles.Count(), 1)
    Expect("an actor's id, for their page", d.roles[0].id, "77")
    Expect("each copy by its size", d.versions, ["4K HEVC", "1080p H264"])
    Expect("on deck: the episode and its season", [d.onDeckKey, d.onDeckSeasonKey], ["e2", "s1"])
    Expect("the facts under the title", Plex_Facts(d), "2024  ·  TV-14  ·  30 episodes  ·  Drama, Thriller")
    Expect("a running time", Plex_Duration(8280000), "2h 18m")
    Expect("a short one", Plex_Duration(2520000), "42m")

    ' The title page's scores, details and badges, as the Fire TV shows them.
    s = Plex_ParseDetail({
        ratingKey: "show1", type: "show", title: "Northbound", year: 2024, studio: "Harbourside", childCount: 3,
        rating: 8.1, audienceRating: 8.6,
        Media: [{ videoResolution: "4k", audioChannels: 6, Part: [{ Stream: [{ streamType: 1, DOVIPresent: true }] }] }]
    }, "http://a")
    Expect("the scores", [s.rating, s.audienceRating], [8.1, 8.6])
    Expect("a score to one place", [Plex_OneDecimal(8.14), Plex_OneDecimal(7)], ["8.1", "7.0"])
    Expect("the details after them", Plex_TitleFacts(s), ["2024", "3 seasons", "Harbourside"])
    Expect("the badges", s.qualities, ["4K", "Dolby Vision", "5.1"])
    Expect("no scores is none", Plex_ParseDetail({ ratingKey: "m", type: "movie", title: "M" }, "http://a").rating = invalid, true)
    Expect("HDR10 and stereo", Plex_Qualities([{ videoResolution: "1080", audioChannels: 2, Part: [{ Stream: [{ streamType: 1, colorTrc: "smpte2084" }] }] }]), ["HD", "HDR10", "Stereo"])

    ' Search: Plex's hubs read once each; what matches by name first.
    hubs = [
        { type: "movie", Metadata: [{ ratingKey: "x1", type: "movie", title: "Gravity" }, { ratingKey: "m1", type: "movie", title: "Low Orbit" }] },
        { type: "actor", Directory: [{ id: "77", tag: "Ana Orbit" }, { id: "77", tag: "Ana Orbit" }] },
        { type: "collection", Metadata: [{ ratingKey: "c1", type: "collection", title: "Orbit Films" }] }
    ]
    Expect("movies and shows from the hubs", Titles_(Plex_ItemsFromHubs(hubs, "http://a", ["movie", "show", "episode"])), ["x1", "m1"])
    Expect("collections apart", Titles_(Plex_ItemsFromHubs(hubs, "http://a", ["collection"])), ["c1"])
    Expect("each person once", Plex_PeopleFromHubs(hubs, "http://a").Count(), 1)
    split = Plex_SplitMatches("orbit", Plex_ItemsFromHubs(hubs, "http://a", ["movie"]))
    Expect("by name first", Titles_(split.matches), ["m1"])
    Expect("Plex's guesses after", Titles_(split.others), ["x1"])
    Expect("words: case and punctuation aside", Plex_Words("Low-Orbit: THE Return"), ["low", "orbit", "the", "return"])

    ' Libraries: the A–Z jump and the narrowing.
    letters = Plex_Letters({ Directory: [{ title: "#", size: 3 }, { title: "A", size: 100 }, { title: "B", size: 50 }, { title: "Q", size: 0 }] })
    Expect("letters with titles only", letters.Count(), 3)
    Expect("B starts after # and A", Plex_LetterStart(letters, "B"), 103)
    Expect("a letter with none", Plex_LetterStart(letters, "Z"), -1)
    Expect("unwatched, a genre and a decade", Plex_Filters(true, "9", "1990"), "&unwatched=1&genre=9&decade=1990")
    Expect("nothing narrowed", Plex_Filters(false, "", ""), "")
    Expect("genres: key and name", Plex_Directory({ Directory: [{ key: "9", title: "Drama" }, { key: "", title: "x" }] }), [{ id: "9", title: "Drama" }])

    ' Playing: the file, its tracks, markers, chapters and trick-play pictures.
    p = Plex_PlaybackDetail({
        duration: 60000,
        Marker: [{ type: "intro", startTimeOffset: 0, endTimeOffset: 4000 }, { type: "credits", startTimeOffset: 50000, endTimeOffset: 60000 }],
        Chapter: [{ tag: "Opening", startTimeOffset: 0 }, { startTimeOffset: 30000 }],
        Media: [{ container: "mkv", videoCodec: "h264", audioCodec: "dca", Part: [{ id: 5, key: "/library/parts/5/file.mkv", Stream: [
            { id: 11, streamType: 2, displayTitle: "English (DTS 5.1)", selected: 1, codec: "dca" },
            { id: 12, streamType: 2, displayTitle: "Commentary", codec: "aac" },
            { id: 21, streamType: 3, displayTitle: "English (SRT)", key: "/library/streams/21", codec: "srt", selected: true },
            { id: 22, streamType: 3, displayTitle: "English (PGS)", codec: "pgs" }
        ] }] }]
    }, "http://a", "tok", 0)
    Expect("the file's address", p.url, "http://a/library/parts/5/file.mkv?X-Plex-Token=tok")
    Expect("sound tracks, Plex's choice marked", [p.audio.Count(), p.audio[0].selected, p.audio[1].selected], [2, true, false])
    Expect("a subtitle file's address", p.subtitles[0].url, "http://a/library/streams/21?X-Plex-Token=tok")
    Expect("embedded pictures have none", p.subtitles[1].url, "")
    Expect("intro and credits", p.markers.Count(), 2)
    Expect("a chapter without a name is numbered", p.chapters[1].title, "Chapter 2")
    Expect("trick-play pictures from Plex's index", p.bif, "http://a/library/parts/5/indexes/sd?X-Plex-Token=tok")
    Expect("SRT on: the Roku draws it", Plex_SubtitlePlan(p).text, true)
    p.subtitles[0].selected = false
    p.subtitles[1].selected = true
    Expect("PGS on: Plex burns it in", Plex_SubtitlePlan(p).burn, true)
    p.subtitles[1].selected = false
    Expect("none on", Plex_SubtitlePlan(p).on = invalid, true)
    ' Subtitles set to start off: Plex's choice is passed over, unless it's forced.
    p.subtitles[0].selected = true
    Plex_AtStart(p, "plex")
    Expect("as Plex has them leaves its choice", p.subtitles[0].selected, true)
    Plex_AtStart(p, "off")
    Expect("off passes over Plex's choice", Plex_SubtitlePlan(p).on = invalid, true)
    p.subtitles[0].selected = true
    p.subtitles[0].forced = true
    Plex_AtStart(p, "off")
    Expect("off keeps a forced choice", p.subtitles[0].selected, true)
    Expect("a version without a size", Plex_VersionLabel("", ""), "Other")

    ' The player's helpers.
    q = [{ ratingKey: "e1" }, { ratingKey: "e2" }, { ratingKey: "e3" }]
    Expect("the next episode in the queue", Plex_NextInQueue(q, { ratingKey: "e2" }).ratingKey, "e3")
    Expect("none after the last", Plex_NextInQueue(q, { ratingKey: "e3" }) = invalid, true)
    Expect("in the intro", Plex_MarkerAt(p.markers, "intro", 2000).endMs, 4000)
    Expect("past it", Plex_MarkerAt(p.markers, "intro", 5000) = invalid, true)
    url = Plex_TranscodeUrl("http://a", "tok", "m1", "s", "cid", 1, 4000, "none")
    Expect("converted at 720p, 4 Mbps, the second copy, subtitles left to the Roku", [Instr(1, url, "videoResolution=1280x720") > 0, Instr(1, url, "maxVideoBitrate=4000") > 0, Instr(1, url, "mediaIndex=1") > 0, Instr(1, url, "subtitles=none") > 0], [true, true, true, true])
end sub
