sub init()
    m.rows = m.top.findNode("rows")
    m.note = m.top.findNode("note")
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.rows.observeField("rowItemSelected", "onSelected")
    m.global.observeField("home", "render")
    m.global.observeField("prefs", "render")
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
    had = m.rows.content <> invalid and m.rows.content.getChildCount() > 0
    ShowRows_(m.rows, rows)
    m.note.text = Str_(h.error)
    m.rows.translation = Iif_(m.note.text <> "", [96, 90], [96, 30])
    if not had and m.top.isInFocusChain() then m.rows.setFocus(true)
end sub

sub focusIn()
    m.rows.setFocus(true)
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
    if key = "options" then
        item = FocusedRowItem_(m.rows)
        if item <> invalid then m.top.menu = { item: item }
        return true
    end if
    return false
end function
