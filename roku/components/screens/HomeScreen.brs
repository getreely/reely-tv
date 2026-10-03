sub init()
    m.rows = m.top.findNode("rows")
    m.note = m.top.findNode("note")
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.rows.observeField("rowItemSelected", "onSelected")
    m.global.observeField("home", "render")
    m.global.observeField("prefs", "render")
    m.global.observeField("ready", "paintReady")
    m.readyGroup = m.top.findNode("ready")
    m.readyActions = m.top.findNode("readyActions")
    m.readyActions.labels = ["Watch", "Dismiss"]
    m.readyActions.observeField("pressed", "onReady")
    m.top.findNode("readyName").font = Bold_(36)
    m.top.findNode("readyFacts").font = Regular_(26)
    render()
end sub

sub render()
    h = m.global.home
    if h = invalid then return
    hidden = {}
    prefs = m.global.prefs
    if prefs <> invalid and prefs.hiddenRows <> invalid then
        for each id in prefs.hiddenRows
            hidden[id] = true
        end for
    end if
    rows = []
    if not hidden.DoesExist("continueWatching") then rows.Push({ title: "Continue Watching", items: Arr_(h.continueWatching), wide: true })
    if not hidden.DoesExist("recentEpisodes") then rows.Push({ title: "Recently Added Episodes", items: Arr_(h.recentEpisodes), groups: true })
    if not hidden.DoesExist("recentMovies") then rows.Push({ title: "Recently Added Movies", items: Arr_(h.recentMovies) })
    if not hidden.DoesExist("watchlist") then rows.Push({ title: "Watchlist", items: Arr_(h.watchlist) })
    if not hidden.DoesExist("playlists") then rows.Push({ title: "Playlists", items: Arr_(h.playlists) })
    if not hidden.DoesExist("iptvMovies") then rows.Push({ title: "New Movies on IPTV", items: Arr_(h.iptvMovies) })
    if not hidden.DoesExist("iptvShows") then rows.Push({ title: "New Shows on IPTV", items: Arr_(h.iptvShows) })
    had = m.rows.content <> invalid and m.rows.content.getChildCount() > 0
    ShowRows_(m.rows, rows)
    m.note.text = Str_(h.error)
    m.note.color = "0xFF6B6BFF"
    if m.note.text = "" and h.continueWatching = invalid then
        ' Not asked yet, or not answered: said, rather than an empty page.
        m.note.text = "Loading your library…"
        m.note.color = "0xB3BAC4FF"
    end if
    top = 30
    if m.readyGroup.visible then top = 190
    if m.note.text <> "" then top = top + 60
    m.note.translation = [96, top - 50]
    m.rows.translation = [96, top]
    if not had and m.top.isInFocusChain() then m.rows.setFocus(true)
end sub

sub focusIn()
    m.rows.setFocus(true)
end sub

' "Ready to watch": the first of what's arrived, and how many after it.
sub paintReady()
    list = Arr_(m.global.ready)
    had = m.readyGroup.visible
    m.readyGroup.visible = list.Count() > 0
    if list.Count() > 0 then
        t = list[0]
        m.top.findNode("readyName").text = t.title + " is ready to watch"
        more = list.Count() - 1
        facts = "You asked for it, and it's here."
        if more = 1 then facts = "You asked for it. And 1 more after this."
        if more > 1 then facts = "You asked for it. And " + more.ToStr() + " more after this."
        m.top.findNode("readyFacts").text = facts
        m.top.findNode("readyArt").uri = Str_(t.poster)
    else if had and m.readyActions.hasFocus() then
        m.rows.setFocus(true)
    end if
    render()
end sub

sub onReady()
    list = Arr_(m.global.ready)
    if list.Count() = 0 then return
    m.top.go = { name: Iif_(m.readyActions.pressed = 0, "watchReady", "dismissReady"), title: list[0] }
end sub

sub onSelected()
    item = SelectedRowItem_(m.rows)
    if item <> invalid then m.top.go = RouteFor_(item)
end sub

sub answered(r as object)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    ' The Options key: the poster's menu, as holding OK is on the Fire TV.
    if key = "up" and m.rows.hasFocus() and m.readyGroup.visible and m.rows.rowItemFocused[0] = 0 then
        m.readyActions.setFocus(true)
        return true
    else if key = "down" and m.readyActions.hasFocus() then
        m.rows.setFocus(true)
        return true
    end if
    if key = "options" then
        item = FocusedRowItem_(m.rows)
        if item <> invalid then m.top.menu = { item: item }
        return true
    end if
    return false
end function
