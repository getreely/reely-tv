' The screensaver's pictures, the remote tour and the problem report: as the Fire TV and
' LG apps have them, worded for a Roku and its remote.

' ---------------------------------------------------------------- The screensaver

' The artwork from Home, each picture once: what's being watched, what's new, what's
' wanted. { base, path, title, caption }; the address is made by whoever shows it.
function Saver_Slides(home as dynamic, most as integer) as object
    out = []
    if home = invalid then return out
    items = []
    items.Append(Arr_(home.continueWatching))
    items.Append(Arr_(home.recentMovies))
    for each g in Arr_(home.recentEpisodes)
        if g.newest <> invalid then items.Push(g.newest)
    end for
    items.Append(Arr_(home.watchlist))
    items.Append(Arr_(home.iptvMovies))
    seen = {}
    for each i in items
        if out.Count() >= most then exit for
        art = Str_(i.art)
        if art = "" and i.type = "episode" then art = Str_(i.thumb)
        key = Str_(i.serverBase) + "|" + art
        if art <> "" and not seen.DoesExist(key) then
            seen[key] = true
            out.Push({ base: Str_(i.serverBase), path: art, title: Plex_RowTitle(i), caption: Plex_Caption(i) })
        end if
    end for
    return out
end function

' How long each picture stays up, in seconds.
function Saver_SlideSeconds() as integer
    return 12
end function

' ---------------------------------------------------------------- The tour

' How to get around with the remote, a step at a time: what the buttons do where it isn't
' obvious. Written from what this app does on a Roku, so it can't promise what isn't so.
function Tour_Steps() as object
    return [
        { title: "Welcome to Reely", body: "A quick look at getting around with your remote. It takes a minute, and you can skip it.", key: "" },
        { title: "The top row", body: "Press Up to reach the tabs: Search, Home, Movies, TV Shows, Live TV and Request. Settings is the gear on the right. Moving onto a tab opens it.", key: "Up" },
        { title: "The ✱ key for more", body: "Press ✱ on any poster for more: carry on or start again, mark it watched, or go to its page. On a show, it can play the next episode.", key: "✱" },
        { title: "While you watch", body: "Left and Right, or Rewind and Fast Forward, move through it, with pictures of where you'll land. Down brings up sound, subtitles, chapters and the sleep timer. Back puts them away.", key: "Down" },
        { title: "Live TV", body: "Left and Right change channel, and ✱ adds it to Favorites or starts the programme over. In the guide, OK on something to come reminds you when it starts.", key: "Left · Right" },
        { title: "Can't find something?", body: "The Request tab finds movies and shows your server doesn't have yet and asks for them. The first time, enter your Reely address there.", key: "" },
        { title: "You're all set", body: "You can take this tour again from Settings, under About.", key: "" }
    ]
end function

' ---------------------------------------------------------------- The problem report

' What went wrong, as kept on the Roku: when, what, and where in the app.
function Problem_Entry(what as string, message as string, backtrace as dynamic, at as integer) as object
    lines = []
    for each f in Arr_(backtrace)
        if lines.Count() >= 6 then exit for
        if type(f) = "roAssociativeArray" then lines.Push(Str_(f.filename) + ":" + Str_(f.line_number))
    end for
    text = message
    if text = "" then text = "Something stopped working."
    return { at: at, what: what, message: text, lines: lines }
end function

' The report as it reads in Settings.
function Problem_Detail(p as object) as string
    out = Str_(p.message)
    if Str_(p.what) <> "" then out = out + Chr(10) + "While: " + Str_(p.what)
    for each l in Arr_(p.lines)
        out = out + Chr(10) + "  at " + l
    end for
    return out
end function

' Kept on the Roku, the latest only: Settings shows it. Nothing is sent anywhere.
sub Problem_Keep(what as string, e as object)
    p = Problem_Entry(what, Str_(e.message), e.backtrace, CreateObject("roDateTime").AsSeconds())
    store = CreateObject("roRegistrySection", "reely")
    store.Write("problem", FormatJson(p))
    store.Flush()
end sub
