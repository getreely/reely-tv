sub Main()
    PlanTests()
    ' Which episode "Play" starts: the same cases as the Fire TV's NextEpisodeTest.
    Expect("the one part watched comes first", Plex_NextEpisode([Ep_("1", true, 0, 1), Ep_("2", false, 0, 1), Ep_("3", false, 500000, 1)]).ratingKey, "3")
    Expect("otherwise the one after the last watched", Plex_NextEpisode([Ep_("1", true, 0, 1), Ep_("2", false, 0, 1), Ep_("3", true, 0, 1), Ep_("4", false, 0, 1)]).ratingKey, "4")
    Expect("nothing watched starts at the beginning", Plex_NextEpisode([Ep_("1", false, 0, 1), Ep_("2", false, 0, 1)]).ratingKey, "1")
    Expect("a gap left behind", Plex_NextEpisode([Ep_("1", true, 0, 1), Ep_("2", false, 0, 1), Ep_("3", true, 0, 1)]).ratingKey, "2")
    Expect("all watched: from the start", Plex_NextEpisode([Ep_("1", true, 0, 1), Ep_("2", true, 0, 1)]).ratingKey, "1")
    Expect("none at all", Plex_NextEpisode([]) = invalid, true)
    Expect("not started: the first episode, not a special", Plex_NextEpisode([Ep_("special", false, 0, invalid), Ep_("s1e1", false, 0, 1), Ep_("s1e2", false, 0, 1)]).ratingKey, "s1e1")
    Expect("specials don't count towards where it's up to", Plex_NextEpisode([Ep_("special", false, 0, invalid), Ep_("s1e1", true, 0, 1), Ep_("s1e2", false, 0, 1)]).ratingKey, "s1e2")
    Expect("a special part watched is carried on with", Plex_NextEpisode([Ep_("special", false, 300000, invalid), Ep_("s1e1", true, 0, 1), Ep_("s1e2", false, 0, 1)]).ratingKey, "special")

    ' The order to try a server's addresses in.
    local = { uri: "https://192-168-1-20.abc.plex.direct:32400", address: "192.168.1.20", port: 32400, local: true, relay: false }
    remote = { uri: "https://104-138-202-217.abc.plex.direct:32400", address: "104.138.202.217", port: 32400, local: false, relay: false }
    relay = { uri: "https://relay.plex.direct:8443", address: "relay", port: 8443, local: false, relay: true }
    Expect("local, plain local, internet, relay", Plex_ConnectionOrder([relay, remote, local]), ["https://192-168-1-20.abc.plex.direct:32400", "http://192.168.1.20:32400", "https://104-138-202-217.abc.plex.direct:32400", "https://relay.plex.direct:8443"])
    Expect("a remote address gets no plain twin", Plex_ConnectionOrder([remote]), [remote.uri])

    ' The account's own server before a friend's.
    servers = Plex_ServersFrom([
        { name: "Friend", provides: "server", owned: false, accessToken: "f", connections: [{ uri: "https://f", address: "", port: 32400, local: false, relay: false }] },
        { name: "Player", provides: "client", connections: [{ uri: "https://p" }] },
        { name: "Mine", provides: "server", owned: true, connections: [{ uri: "https://m", address: "", port: 32400, local: false, relay: false }] }
    ], "account")
    names = []
    for each s in servers
        names.Push(s.name)
    end for
    Expect("own server first, players left out", names, ["Mine", "Friend"])
    Expect("a server without its own token uses the account's", servers[0].accessToken, "account")

    ' Continue Watching: most recently watched first, one per show.
    Expect("watched just now before added last week", Titles_(Plex_ContinueWatchingOrder([
        Plex_ParseItem({ ratingKey: "film", type: "movie", addedAt: 9000, lastViewedAt: 1000 }, "http://a"),
        Plex_ParseItem({ ratingKey: "ep", type: "episode", grandparentRatingKey: "kong", addedAt: 100, lastViewedAt: 5000 }, "http://a")
    ])), ["ep", "film"])
    Expect("one per show, the one in progress", Titles_(Plex_ContinueWatchingOrder([
        Plex_ParseItem({ ratingKey: "next", type: "episode", grandparentRatingKey: "kong", addedAt: 50 }, "http://a"),
        Plex_ParseItem({ ratingKey: "half", type: "episode", grandparentRatingKey: "kong", lastViewedAt: 4000, viewOffset: 600000 }, "http://a")
    ])), ["half"])
    Expect("the same show on two servers isn't one show", Titles_(Plex_ContinueWatchingOrder([
        Plex_ParseItem({ ratingKey: "a", type: "episode", grandparentRatingKey: "10", lastViewedAt: 2000 }, "http://a"),
        Plex_ParseItem({ ratingKey: "b", type: "episode", grandparentRatingKey: "10", lastViewedAt: 1000 }, "http://b")
    ])), ["a", "b"])
    Expect("ties keep the server's order", Titles_(Plex_ContinueWatchingOrder([
        Plex_ParseItem({ ratingKey: "first", type: "movie", lastViewedAt: 1000 }, "http://a"),
        Plex_ParseItem({ ratingKey: "second", type: "movie", lastViewedAt: 1000 }, "http://a")
    ])), ["first", "second"])

    ' New episodes fold onto their show; the badge fits its circle.
    groups = {}
    order = []
    page = []
    for i = 1 to 333
        page.Push(Plex_ParseItem({ ratingKey: "n" + i.ToStr(), type: "episode", grandparentRatingKey: "show1", grandparentTitle: "Northbound" }, "http://a"))
    end for
    page.Push(Plex_ParseItem({ ratingKey: "h1", type: "episode", grandparentRatingKey: "show2", grandparentTitle: "Harbor" }, "http://a"))
    Plex_FoldEpisodes(groups, order, page, "http://a", "2")
    Expect("one group per show", order.Count(), 2)
    Expect("with the count", groups[order[0]].newCount, 333)
    Expect("badge up to 999 as it is", Plex_BadgeText(333), "333")
    Expect("then 1K", Plex_BadgeText(1500), "1K")

    ' Reading Plex's answers.
    item = Plex_ParseItem({ ratingKey: "7", type: "episode", title: "Pilot", index: 1, parentIndex: 2, year: 2024, duration: 60000, viewOffset: 30000 }, "http://a")
    Expect("caption", Plex_Caption(item), "S2 · E1")
    Expect("halfway", Plex_ResumeFraction(item), 0.5)
    Expect("a year reads as a year", Plex_Caption(Plex_ParseItem({ ratingKey: "m", type: "movie", year: 1999 }, invalid)), "1999")
    Expect("watched films", Plex_IsWatched(Plex_ParseItem({ ratingKey: "m", type: "movie", viewCount: 2 }, invalid)), true)
    Expect("a show is watched when every episode is", Plex_IsWatched(Plex_ParseItem({ ratingKey: "s", type: "show", leafCount: 10, viewedLeafCount: 10 }, invalid)), true)
end sub

sub PlanTests()
    yes = { h264: true, hevc: true, aac: true, ac3: true, eac3: true }
    Expect("H.264 and AAC in MP4 plays as it is", Plex_PlanDirect("mp4", "h264", "aac", yes, yes).direct, true)
    Expect("as MKV too", Plex_PlanDirect("mkv", "hevc", "eac3", yes, yes).format, "mkv")
    Expect("DTS is converted, and says why", Plex_PlanDirect("mkv", "h264", "dca", yes, yes).reason, "Roku can't play DCA sound")
    Expect("HEVC on a Roku without it", Plex_PlanDirect("mkv", "hevc", "aac", { h264: true }, yes).direct, false)
    Expect("AVI isn't opened", Plex_PlanDirect("avi", "mpeg4", "mp3", yes, yes).reason, "Roku can't open AVI files")
    p = Plex_PlaybackFrom({ duration: 60000, Media: [{ container: "MKV", videoCodec: "h264", audioCodec: "dca", Part: [{ key: "/library/parts/5/file.mkv" }] }] }, "http://s", "t")
    Expect("the file's address", p.url, "http://s/library/parts/5/file.mkv?X-Plex-Token=t")
    Expect("its container, in lower case", p.container, "mkv")
    Expect("no file, nothing to play", Plex_PlaybackFrom({ Media: [] }, "http://s", "t") = invalid, true)
end sub
