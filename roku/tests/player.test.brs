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
end sub
