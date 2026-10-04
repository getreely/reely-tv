sub Main()
    ' The player's intro and credits, at each moment of an episode.
    marks = [{ type: "intro", startMs: 0, endMs: 30000 }, { type: "credits", startMs: 50000, endMs: 60000 }]
    fresh = { introDone: false, creditsOffered: false, hasNext: true, sleepAtEnd: false }
    off = { skipIntros: false, skipCredits: false }
    c = Plex_PlayerCues(marks, 10000, off, fresh)
    Expect("Skip Intro offered in the intro", c.showSkip, true)
    Expect("not skipped by itself unless Settings says", c.skipTo, -1)
    Expect("no Skip Intro in its last second", Plex_PlayerCues(marks, 29500, off, fresh).showSkip, false)
    Expect("skipped by itself with skip intros on", Plex_PlayerCues(marks, 10000, { skipIntros: true }, fresh).skipTo, 30000)
    Expect("only once: back into the intro isn't skipped again", Plex_PlayerCues(marks, 10000, { skipIntros: true }, { introDone: true, creditsOffered: false, hasNext: true, sleepAtEnd: false }).skipTo, -1)
    Expect("nothing between intro and credits", Plex_PlayerCues(marks, 40000, off, fresh).showSkip or Plex_PlayerCues(marks, 40000, off, fresh).upNext, false)
    c = Plex_PlayerCues(marks, 52000, off, fresh)
    Expect("Up Next at the credits", [c.upNext, c.playNext], [true, false])
    c = Plex_PlayerCues(marks, 52000, { skipCredits: true }, fresh)
    Expect("straight on with skip credits", [c.upNext, c.playNext], [false, true])
    Expect("offered once", Plex_PlayerCues(marks, 55000, off, { introDone: true, creditsOffered: true, hasNext: true, sleepAtEnd: false }).upNext, false)
    Expect("nothing next, nothing offered", Plex_PlayerCues(marks, 52000, off, { introDone: false, creditsOffered: false, hasNext: false, sleepAtEnd: false }).upNext, false)
    Expect("sleeping at the end: not on to the next", Plex_PlayerCues(marks, 52000, { skipCredits: true }, { introDone: false, creditsOffered: false, hasNext: true, sleepAtEnd: true }).playNext, false)
    Expect("no Settings yet: offered, not skipped", Plex_PlayerCues(marks, 52000, invalid, fresh).upNext, true)

    ' What comes next in a queue.
    q = [{ ratingKey: "e1" }, { ratingKey: "e2" }, { ratingKey: "e3" }]
    Expect("the next episode", Plex_NextInQueue(q, { ratingKey: "e2" }).ratingKey, "e3")
    Expect("none after the last", Plex_NextInQueue(q, { ratingKey: "e3" }), invalid)

    ' Plex Home profiles.
    owner = Plex_HomeUser({ uuid: "u1", title: "", username: "ann", thumb: "https://plex.tv/users/1/avatar", homeAdmin: true })
    Expect("named by username when untitled, its picture", [owner.title, owner.thumb, owner.admin], ["ann", "https://plex.tv/users/1/avatar", true])
    kid = Plex_HomeUser({ uuid: "u2", title: "Kid", restricted: true, protected: true, thumb: "/rel" })
    Expect("a managed profile with a PIN", [kid.restricted, kid.protected, kid.thumb], [true, true, ""])
    Expect("no id, no profile", Plex_HomeUser({ title: "x" }), invalid)
    Expect("whose this is", [Plex_ProfileRole(owner, "u1"), Plex_ProfileRole(owner, "u2"), Plex_ProfileRole(kid, "u1")], ["Watching now", "Owner", "Managed"])

    ' Subtitles found online by the Plex server.
    found = Plex_OnlineSubtitles({ Stream: [
        { key: "/sub/1", title: "English", providerTitle: "OpenSubtitles", languageCode: "en", codec: "srt" },
        { key: "", title: "No key" },
        { key: "/sub/2", displayTitle: "English (SDH)", languageTag: "en-US", hearingImpaired: true }
    ] })
    Expect("those the server can fetch", found.Count(), 2)
    Expect("as the list shows them", [Plex_OnlineSubtitleLabel(found[0]), Plex_OnlineSubtitleLabel(found[1])], ["English · OpenSubtitles", "English (SDH) · SDH"])
    Expect("nothing found is an empty list", [Plex_OnlineSubtitles(invalid), Plex_OnlineSubtitles({})], [[], []])
    Expect("what the server is asked to fetch", Plex_AddSubtitleQuery(found[0], "fr"), "key=%2Fsub%2F1&codec=srt&language=en&hearingImpaired=0&forced=0&providerTitle=OpenSubtitles")
    Expect("its own language, else the TV's", Plex_AddSubtitleQuery({ key: "k", codec: "", language: "", hearingImpaired: true, forced: false, provider: "" }, "fr"), "key=k&language=fr&hearingImpaired=1&forced=0")
    Expect("the track that's new", Plex_NewSubtitleId([{ id: "21" }], [{ id: "21" }, { id: "22" }]), "22")
    Expect("none new", Plex_NewSubtitleId([{ id: "21" }], [{ id: "21" }]), "")
end sub
