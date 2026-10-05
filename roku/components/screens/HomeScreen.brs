sub init()
    m.rows = m.top.findNode("rows")
    m.note = m.top.findNode("note")
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.rows.observeField("rowItemSelected", "onSelected")
    m.hero = m.top.findNode("hero")
    m.rows.observeField("rowItemFocused", "onFocused")
    Listen_("home", "render")
    Listen_("prefs", "render")
    Listen_("ready", "paintReady")
    m.readyGroup = m.top.findNode("ready")
    m.readyActions = m.top.findNode("readyActions")
    m.readyActions.labels = ["Watch", "Dismiss"]
    m.readyActions.observeField("pressed", "onReady")
    m.top.findNode("readyName").font = Bold_(36)
    m.top.findNode("readyFacts").font = Regular_(26)
    render()
end sub

sub render()
    if Gone_() then return
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
    #if DEBUG
        names = []
        for each r in rows
            if r.items.Count() > 0 then names.Push(r.title)
        end for
        Trace_("home rows " + Join_(names, ", "))
    #end if
    ShowRows_(m.rows, rows)
    m.note.text = Str_(h.error)
    m.note.color = "0xFF6B6BFF"
    if m.note.text = "" and h.continueWatching = invalid then
        ' Not asked yet, or not answered: said, rather than an empty page.
        m.note.text = "Loading your library…"
        m.note.color = "0xB3BAC4FF"
    end if
    ' Under the hero, which shows what has the cursor, or the first title before it does.
    top = HERO_ROWS_TOP()
    ' A request that's arrived goes between the hero and the rows.
    m.readyGroup.translation = [0, top - 10]
    if m.readyGroup.visible then top = top + 170
    if m.note.text <> "" then top = top + 60
    m.note.translation = [96, top - 50]
    m.rows.translation = [96, top]
    ' The page held the cursor while there was nothing to put it on: the rows have it now.
    hasRows = m.rows.content <> invalid and m.rows.content.getChildCount() > 0
    if hasRows and (m.top.hasFocus() or (not had and m.top.isInFocusChain() and not m.readyActions.isInFocusChain())) then m.rows.setFocus(true)
    showHero()
end sub

' The hero: the title with the cursor in the rows, else the first title on the page.
sub onFocused()
    showHero()
end sub

sub showHero()
    item = invalid
    if m.rows.hasFocus() or m.rows.isInFocusChain() then item = FocusedRowItem_(m.rows)
    if item = invalid and m.rowItems <> invalid and m.rowItems.Count() > 0 and m.rowItems[0].Count() > 0 then item = m.rowItems[0][0]
    if item = invalid then item = {}
    ' Set only when it's another title: each setting draws the hero again.
    was = m.hero.item
    if was <> invalid and Str_(was.ratingKey) = Str_(item.ratingKey) and Str_(was.serverBase) = Str_(item.serverBase) and was.Count() = item.Count() then return
    m.hero.item = item
end sub

sub focusIn()
    ' A Roku won't give the cursor to a list with nothing in it: until the rows come, the
    ' page itself holds it, so it isn't lost (the remote's arrows then went nowhere).
    if m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then
        m.rows.setFocus(true)
    else
        m.top.setFocus(true)
    end if
end sub

' "Ready to watch": the first of what's arrived, and how many after it.
sub paintReady()
    if Gone_() then return
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

' Where the rows start: under the hero's name, details and summary.
function HERO_ROWS_TOP() as integer
    return 330
end function
