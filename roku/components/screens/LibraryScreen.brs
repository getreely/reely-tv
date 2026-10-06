' The library's grid comes a page at a time, before the cursor gets to the end of it.
function PageSize_() as integer
    return 120
end function

sub init()
    m.views = m.top.findNode("views")
    m.tools = m.top.findNode("tools")
    m.letters = m.top.findNode("letters")
    m.rows = m.top.findNode("rows")
    m.grid = m.top.findNode("grid")
    m.note = m.top.findNode("note")
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.views.observeField("pressed", "onView")
    m.tools.observeField("pressed", "onTool")
    m.letters.observeField("pressed", "onLetter")
    m.rows.observeField("rowItemSelected", "onRowPicked")
    m.hero = m.top.findNode("hero")
    m.rows.observeField("rowItemFocused", "showHero")
    m.grid.observeField("itemSelected", "onGridPicked")
    m.grid.observeField("itemFocused", "onGridFocused")
    Listen_("home", "paintHome")
    m.sorts = [["titleSort:asc", "A–Z"], ["addedAt:desc", "Recently added"], ["originallyAvailableAt:desc", "Newest releases"], ["rating:desc", "Critic rating"]]
    m.view = "home"
    m.sort = "titleSort:asc"
    m.unwatched = false
    m.genre = invalid
    m.decade = invalid
    m.items = []
    m.total = 0
    m.loading = false
    m.meta = { genres: [], decades: [], letters: [], released: [], added: [], collections: invalid }
    m.at = "views"
end sub

sub open()
    Crumb_("library: open")
    try
        open__()
    catch e
        Oops_("library: open", e)
    end try
end sub

sub open__()
    r = m.top.route
    m.kind = r.kind
    m.libraries = ShownLibraries_(m.kind)
    ' The provider's, after Plex's, while its films and series are on, unless taken out of
    ' the menus in Settings.
    inMenus = m.global.prefs.iptvInMenus = invalid or Bool_(m.global.prefs.iptvInMenus)
    if IptvReady_() and inMenus then m.libraries.Push({ base: "iptv:", key: "iptv-" + m.kind, title: "IPTV", type: m.kind, token: "" })
    if m.libraries.Count() = 0 then
        m.note.text = "No " + Iif_(m.kind = "movie", "movie", "TV") + " library on your server."
        m.views.visible = false
        return
    end if
    m.library = m.libraries[0]
    loadLibrary()
end sub

' Part of the library open: from its server, and from it where Plex says which library it's in.
function inLibrary(i as object) as boolean
    if m.library = invalid then return true
    if Str_(i.serverBase) <> "" and Str_(i.serverBase) <> Str_(m.library.base) then return false
    return Str_(i.librarySectionId) = "" or Str_(i.librarySectionId) = Str_(m.library.key)
end function

function isIptv() as boolean
    return m.library <> invalid and m.library.base = "iptv:"
end function

sub loadLibrary()
    ' The provider's library is a grid only, as on the other apps.
    if isIptv() then m.view = "grid"
    m.items = []
    m.total = 0
    m.meta = { genres: [], decades: [], letters: [], released: [], added: [], collections: invalid }
    m.genre = invalid
    m.decade = invalid
    paintViews()
    paint()
    askMeta()
    loadPage()
end sub

sub askMeta()
    if isIptv() then
        Ask_("iptvMeta", { kind: m.kind, categoryId: genreId(), unwatched: m.unwatched })
    else
        Ask_("libraryMeta", { library: m.library, filters: filters() })
    end if
end sub

function genreId() as string
    if m.genre = invalid then return ""
    return Str_(m.genre.id)
end function

function filters() as string
    g = ""
    d = ""
    if m.genre <> invalid then g = m.genre.id
    if m.decade <> invalid then d = m.decade.id
    return Plex_Filters(m.unwatched, g, d)
end function

sub loadPage()
    if m.loading then return
    m.loading = true
    if isIptv() then
        Ask_("iptvPage", { kind: m.kind, sort: m.sort, categoryId: genreId(), unwatched: m.unwatched, start: m.items.Count(), size: PageSize_(), id: m.sort + filters() })
    else
        Ask_("libraryPage", { library: m.library, sort: m.sort, filters: filters(), start: m.items.Count(), size: PageSize_(), id: m.sort + filters() })
    end if
end sub

sub answered(r as object)
    if r.op = "libraryMeta" or r.op = "iptvMeta" then
        if r.answer <> invalid then m.meta = r.answer
        paintTools()
        paint()
    else if r.op = "libraryPage" or r.op = "iptvPage" then
        m.loading = false
        ' Not over a different order or narrowing chosen meanwhile.
        if r.id <> m.sort + filters() then return
        page = Arr_(r.items)
        #if DEBUG
            if r.op = "iptvPage" then
                names = []
                for each i in page
                    names.Push(i.title)
                end for
                Trace_("iptv grid " + Join_(names, ", "))
            end if
        #end if
        if r.items = invalid then m.note.text = Iif_(isIptv(), "Couldn't read your provider's library. Try again in a moment.", "Couldn't reach your Plex server. Try again in a moment.")
        if m.grid.content = invalid or m.items.Count() = 0 then m.grid.content = CreateObject("roSGNode", "ContentNode")
        for each it in page
            m.items.Push(it)
            ItemContent_(m.grid.content, it, false)
        end for
        if page.Count() < PageSize_() then m.total = m.items.Count() else m.total = m.items.Count() + 1
        if m.jumpWanted <> invalid then jumpWhenLoaded()
        paint()
    end if
end sub

sub paintViews()
    labels = []
    on = []
    m.viewIds = []
    if not isIptv() then
        labels = ["Home", "All", "Collections"]
        on = [m.view = "home", m.view = "grid", m.view = "collections"]
        m.viewIds = ["home", "grid", "collections"]
    end if
    if m.libraries.Count() > 1 then
        ' With more than one server, which one each library is on: two called Movies otherwise look the same.
        named = Arr_(Session_().servers).Count() > 1
        for each l in m.libraries
            if named and l.base <> "iptv:" then labels.Push(Str_(l.title) + " · " + Str_(l.serverName)) else labels.Push(l.title)
            on.Push(l.key = m.library.key and l.base = m.library.base)
        end for
    end if
    m.views.labels = labels
    m.views.on = on
    m.views.visible = labels.Count() > 0
end sub

' As on the Fire TV: sort, the watched filter, the decade, then the genres, in one row
' that slides along. Sort and decade open their lists from the right.
sub paintTools()
    sortLabel = m.sorts[0][1]
    for each s in m.sorts
        if s[0] = m.sort then sortLabel = s[1]
    end for
    labels = ["Sort · " + sortLabel, "Unwatched"]
    on = [m.sort <> m.sorts[0][0], m.unwatched]
    m.toolIds = ["sort", "unwatched"]
    if m.meta.decades.Count() > 1 then
        if m.decade <> invalid then labels.Push(Str_(m.decade.title)) else labels.Push("All decades")
        on.Push(m.decade <> invalid)
        m.toolIds.Push("decade")
    end if
    if m.meta.genres.Count() > 0 then
        labels.Push(Iif_(isIptv(), "All categories", "All genres"))
        on.Push(m.genre = invalid)
        m.toolIds.Push("genre:")
        for each g in m.meta.genres
            labels.Push(Str_(g.title))
            on.Push(m.genre <> invalid and m.genre.id = g.id)
            m.toolIds.Push("genre:" + Str_(g.id))
        end for
    end if
    m.tools.visibleWidth = 1728
    m.tools.labels = labels
    m.tools.on = on
    letters = []
    for each l in m.meta.letters
        letters.Push(l.letter)
    end for
    m.letters.labels = letters
end sub

sub paint()
    Crumb_("library: draw")
    try
        paint__()
    catch e
        Oops_("library: draw", e)
    end try
end sub

sub paint__()
    grid = m.view = "grid"
    m.tools.visible = grid
    m.letters.visible = grid and m.sort = "titleSort:asc" and m.meta.letters.Count() > 1
    m.rows.visible = m.view = "home"
    m.grid.visible = m.view <> "home"
    ' The tab's home has the hero at the top, with the views and the rows under it.
    heroOn = m.view = "home"
    m.hero.visible = heroOn
    m.hero.fallback = Iif_(m.kind = "movie", "Movies", "TV Shows")
    m.views.translation = Iif_(heroOn, [96, 300], [96, 10])
    m.rows.translation = Iif_(heroOn, [96, 390], [96, 100])
    m.grid.translation = Iif_(m.letters.visible, [96, 230], Iif_(grid, [96, 160], [96, 100]))
    m.note.text = ""
    if m.view = "collections" then
        content = CreateObject("roSGNode", "ContentNode")
        if m.meta.collections <> invalid then
            for each c in m.meta.collections
                ItemContent_(content, c, false)
            end for
            if m.meta.collections.Count() = 0 then m.note.text = "No collections in " + m.library.title + " yet. Collections made in Plex show up here."
        end if
        m.grid.content = content
    else if grid then
        content = CreateObject("roSGNode", "ContentNode")
        for each it in m.items
            ItemContent_(content, it, false)
        end for
        m.grid.content = content
        if not m.loading and m.items.Count() = 0 then m.note.text = "Nothing here matches. Try fewer filters."
    else
        paintHome()
    end if
end sub

sub paintHome()
    Crumb_("library: home")
    try
        paintHome__()
    catch e
        Oops_("library: home", e)
    end try
end sub

' The tab's own home: Continue Watching of this kind, what's newly added and released.
sub paintHome__()
    if Gone_() then return
    if m.view <> "home" or m.library = invalid then return
    h = m.global.home
    resumable = []
    added = []
    groups = false
    if h <> invalid then
        for each i in Arr_(h.continueWatching)
            if ((m.kind = "movie" and i.type = "movie") or (m.kind = "show" and i.type = "episode")) and inLibrary(i) then resumable.Push(i)
        end for
    end if
    ' This library's own newest, not Home's newest across every library.
    added = Arr_(m.meta.added)
    groups = m.kind = "show"
    ShowRows_(m.rows, [
        { title: "Continue Watching", items: resumable },
        { title: "Recently Added", items: added, groups: groups },
        { title: "Recently Released", items: Arr_(m.meta.released) }
    ])
    showHero()
end sub

sub showHero()
    Crumb_("library: hero")
    try
        showHero__()
    catch e
        Oops_("library: hero", e)
    end try
end sub

' The hero: the title with the cursor in the rows, else the first title on the page.
sub showHero__()
    if m.view <> "home" then return
    item = invalid
    if m.rows.isInFocusChain() then item = FocusedRowItem_(m.rows)
    if item = invalid and m.rowItems <> invalid and m.rowItems.Count() > 0 and m.rowItems[0].Count() > 0 then item = m.rowItems[0][0]
    if item = invalid then item = {}
    ' Set only when it's another title: each setting draws the hero again.
    was = m.hero.item
    if was <> invalid and Str_(was.ratingKey) = Str_(item.ratingKey) and Str_(was.serverBase) = Str_(item.serverBase) and was.Count() = item.Count() then return
    m.hero.item = item
end sub

sub onView()
    i = m.views.pressed
    if i < m.viewIds.Count() then
        m.view = m.viewIds[i]
    else
        m.library = m.libraries[i - m.viewIds.Count()]
        loadLibrary()
        return
    end if
    paintViews()
    paintTools()
    paint()
end sub

sub onTool()
    id = m.toolIds[m.tools.pressed]
    if id = "sort" then
        choose("sort")
    else if id = "unwatched" then
        m.unwatched = not m.unwatched
        restart()
    else if id = "decade" then
        choose("decade")
    else if Left(id, 6) = "genre:" then
        m.genre = invalid
        for each g in m.meta.genres
            if "genre:" + Str_(g.id) = id then m.genre = g
        end for
        restart()
    end if
end sub

' The grid again from the top: another order, or narrowed.
sub restart()
    m.items = []
    m.total = 0
    m.loading = false
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    paintTools()
    paint()
    askMeta()
    loadPage()
end sub

sub choose(what as string)
    names = []
    current = 0
    title = "Sort by"
    if what = "sort" then
        for i = 0 to m.sorts.Count() - 1
            names.Push(m.sorts[i][1])
            if m.sorts[i][0] = m.sort then current = i
        end for
    else
        title = "Decade"
        names.Push("All decades")
        for i = 0 to m.meta.decades.Count() - 1
            names.Push(m.meta.decades[i].title)
            if m.decade <> invalid and m.meta.decades[i].id = m.decade.id then current = i + 1
        end for
    end if
    m.choosing = what
    m.chooser = CreateObject("roSGNode", "Chooser")
    m.chooser.current = current
    m.chooser.options = names
    m.chooser.title = title
    m.chooser.observeField("picked", "onChosen")
    ' Over the whole screen, tabs and all.
    Overlays_().appendChild(m.chooser)
    m.chooser.setFocus(true)
end sub

sub onChosen()
    picked = m.chooser.picked
    Overlays_().removeChild(m.chooser)
    m.chooser = invalid
    m.tools.setFocus(true)
    if picked < 0 then return
    if m.choosing = "sort" then
        m.sort = m.sorts[picked][0]
    else if picked = 0 then
        m.decade = invalid
    else
        m.decade = m.meta.decades[picked - 1]
    end if
    restart()
end sub

' A letter further down than what's loaded loads down to it, then the cursor goes there.
sub onLetter()
    letter = m.meta.letters[m.letters.pressed].letter
    m.jumpWanted = Plex_LetterStart(m.meta.letters, letter)
    jumpWhenLoaded()
end sub

sub jumpWhenLoaded()
    at = m.jumpWanted
    if at = invalid or at < 0 then
        m.jumpWanted = invalid
        return
    end if
    if m.items.Count() > at then
        m.jumpWanted = invalid
        m.grid.jumpToItem = at
        m.grid.setFocus(true)
        m.at = "grid"
    else if m.total > m.items.Count() then
        loadPage()
    else
        m.jumpWanted = invalid
    end if
end sub

sub onGridFocused()
    Crumb_("library: cursor")
    try
        onGridFocused__()
    catch e
        Oops_("library: cursor", e)
    end try
end sub

sub onGridFocused__()
    if m.view = "grid" and m.grid.itemFocused >= m.items.Count() - 24 and m.total > m.items.Count() then loadPage()
end sub

sub onGridPicked()
    i = m.grid.itemSelected
    list = m.items
    if m.view = "collections" then list = Arr_(m.meta.collections)
    if i >= 0 and i < list.Count() then m.top.go = RouteFor_(list[i])
end sub

sub onRowPicked()
    item = SelectedRowItem_(m.rows)
    if item <> invalid then m.top.go = RouteFor_(item)
end sub

sub focusIn()
    Crumb_("library: cursor in")
    try
        focusIn__()
    catch e
        Oops_("library: cursor in", e)
    end try
end sub

sub focusIn__()
    controls = controlsInOrder()
    if controls.Count() = 0 then return
    m.at = controls[0]
    nodeOf(m.at).setFocus(true)
end sub

' The controls down the screen, in order, for up and down between them.
function controlsInOrder() as object
    out = []
    if m.views.visible then out.Push("views")
    if m.tools.visible then out.Push("tools")
    if m.letters.visible then out.Push("letters")
    if m.view = "home" then
        if m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then out.Push("rows")
    else if m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then
        out.Push("grid")
    end if
    return out
end function

function nodeOf(name as string) as object
    if name = "views" then return m.views
    if name = "tools" then return m.tools
    if name = "letters" then return m.letters
    if name = "rows" then return m.rows
    return m.grid
end function

function onKeyEvent(key as string, press as boolean) as boolean
    if press then Crumb_("library " + key)
    try
        return onKeyEvent__(key, press)
    catch e
        Oops_("library " + key, e)
    end try
    return true
end function

function onKeyEvent__(key as string, press as boolean) as boolean
    if not press then return false
    controls = controlsInOrder()
    at = -1
    for i = 0 to controls.Count() - 1
        if nodeOf(controls[i]).isInFocusChain() then at = i
    end for
    if key = "down" and at >= 0 and at < controls.Count() - 1 then
        m.at = controls[at + 1]
        nodeOf(m.at).setFocus(true)
        return true
    else if key = "up" and at > 0 then
        m.at = controls[at - 1]
        nodeOf(m.at).setFocus(true)
        return true
    else if key = "options" then
        item = invalid
        if m.at = "rows" then item = FocusedRowItem_(m.rows)
        if m.at = "grid" and m.view = "grid" and m.grid.itemFocused >= 0 and m.grid.itemFocused < m.items.Count() then item = m.items[m.grid.itemFocused]
        if item <> invalid then m.top.menu = { item: item }
        return true
    end if
    return false
end function
