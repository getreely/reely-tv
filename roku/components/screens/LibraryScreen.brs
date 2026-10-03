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
    m.grid.observeField("itemSelected", "onGridPicked")
    m.grid.observeField("itemFocused", "onGridFocused")
    m.global.observeField("home", "paintHome")
    m.sorts = [["titleSort:asc", "A–Z"], ["addedAt:desc", "Recently added"], ["originallyAvailableAt:desc", "Newest releases"], ["rating:desc", "Critic rating"]]
    m.view = "home"
    m.sort = "titleSort:asc"
    m.unwatched = false
    m.genre = invalid
    m.decade = invalid
    m.items = []
    m.total = 0
    m.loading = false
    m.meta = { genres: [], decades: [], letters: [], released: [], collections: invalid }
    m.at = "views"
end sub

sub open()
    r = m.top.route
    m.kind = r.kind
    m.libraries = []
    for each l in Session_().libraries
        if l.type = m.kind then m.libraries.Push(l)
    end for
    if m.libraries.Count() = 0 then
        m.note.text = "No " + Iif_(m.kind = "movie", "movie", "TV") + " library on your server."
        m.views.visible = false
        return
    end if
    m.library = m.libraries[0]
    loadLibrary()
end sub

sub loadLibrary()
    m.items = []
    m.total = 0
    m.meta = { genres: [], decades: [], letters: [], released: [], collections: invalid }
    m.genre = invalid
    m.decade = invalid
    paintViews()
    paint()
    Ask_("libraryMeta", { library: m.library, filters: filters() })
    loadPage()
end sub

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
    Ask_("libraryPage", { library: m.library, sort: m.sort, filters: filters(), start: m.items.Count(), size: PageSize_(), id: m.sort + filters() })
end sub

sub answered(r as object)
    if r.op = "libraryMeta" then
        if r.answer <> invalid then m.meta = r.answer
        paintTools()
        paint()
    else if r.op = "libraryPage" then
        m.loading = false
        ' Not over a different order or narrowing chosen meanwhile.
        if r.id <> m.sort + filters() then return
        page = Arr_(r.items)
        if r.items = invalid then m.note.text = "Couldn't reach your Plex server. Try again in a moment."
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
    labels = ["Home", "All", "Collections"]
    on = [m.view = "home", m.view = "grid", m.view = "collections"]
    if m.libraries.Count() > 1 then
        for each l in m.libraries
            labels.Push(l.title)
            on.Push(l.key = m.library.key and l.base = m.library.base)
        end for
    end if
    m.views.labels = labels
    m.views.on = on
end sub

sub paintTools()
    labels = []
    on = []
    for each s in m.sorts
        labels.Push(s[1])
        on.Push(s[0] = m.sort)
    end for
    labels.Push("Unwatched")
    on.Push(m.unwatched)
    m.toolIds = ["sort0", "sort1", "sort2", "sort3", "unwatched"]
    if m.meta.genres.Count() > 0 then
        if m.genre <> invalid then labels.Push(Str_(m.genre.title)) else labels.Push("Genre")
        on.Push(m.genre <> invalid)
        m.toolIds.Push("genre")
    end if
    if m.meta.decades.Count() > 0 then
        if m.decade <> invalid then labels.Push(Str_(m.decade.title)) else labels.Push("Decade")
        on.Push(m.decade <> invalid)
        m.toolIds.Push("decade")
    end if
    m.tools.labels = labels
    m.tools.on = on
    letters = []
    for each l in m.meta.letters
        letters.Push(l.letter)
    end for
    m.letters.labels = letters
end sub

sub paint()
    grid = m.view = "grid"
    m.tools.visible = grid
    m.letters.visible = grid and m.sort = "titleSort:asc" and m.meta.letters.Count() > 1
    m.rows.visible = m.view = "home"
    m.grid.visible = m.view <> "home"
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

' The tab's own home: Continue Watching of this kind, what's newly added and released.
sub paintHome()
    if m.view <> "home" or m.library = invalid then return
    h = m.global.home
    resumable = []
    added = []
    groups = false
    if h <> invalid then
        for each i in Arr_(h.continueWatching)
            if (m.kind = "movie" and i.type = "movie") or (m.kind = "show" and i.type = "episode") then resumable.Push(i)
        end for
        if m.kind = "movie" then
            added = Arr_(h.recentMovies)
        else
            added = Arr_(h.recentEpisodes)
            groups = true
        end if
    end if
    ShowRows_(m.rows, [
        { title: "Continue Watching", items: resumable },
        { title: "Recently Added", items: added, groups: groups },
        { title: "Recently Released", items: Arr_(m.meta.released) }
    ])
end sub

sub onView()
    i = m.views.pressed
    if i <= 2 then
        m.view = ["home", "grid", "collections"][i]
    else
        m.library = m.libraries[i - 3]
        loadLibrary()
        return
    end if
    paintViews()
    paintTools()
    paint()
end sub

sub onTool()
    id = m.toolIds[m.tools.pressed]
    if Left(id, 4) = "sort" then
        m.sort = m.sorts[Val(Mid(id, 5))][0]
        restart()
    else if id = "unwatched" then
        m.unwatched = not m.unwatched
        restart()
    else if id = "genre" or id = "decade" then
        choose(id)
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
    Ask_("libraryMeta", { library: m.library, filters: filters() })
    loadPage()
end sub

sub choose(what as string)
    values = m.meta.genres
    if what = "decade" then values = m.meta.decades
    names = ["All"]
    current = 0
    chosen = Iif_(what = "genre", m.genre, m.decade)
    for i = 0 to values.Count() - 1
        names.Push(values[i].title)
        if chosen <> invalid and values[i].id = chosen.id then current = i + 1
    end for
    m.choosing = what
    m.chooser = CreateObject("roSGNode", "Chooser")
    ' Over the whole screen, tabs and all, from inside a page that starts under them.
    m.chooser.translation = [0, -130]
    m.chooser.current = current
    m.chooser.options = names
    m.chooser.title = Iif_(what = "genre", "Genre", "Decade")
    m.chooser.observeField("picked", "onChosen")
    m.top.appendChild(m.chooser)
    m.chooser.setFocus(true)
end sub

sub onChosen()
    picked = m.chooser.picked
    values = Iif_(m.choosing = "genre", m.meta.genres, m.meta.decades)
    m.top.removeChild(m.chooser)
    m.chooser = invalid
    m.tools.setFocus(true)
    if picked < 0 then return
    value = invalid
    if picked > 0 then value = values[picked - 1]
    if m.choosing = "genre" then m.genre = value else m.decade = value
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
    if m.views.visible then
        m.views.setFocus(true)
        m.at = "views"
    end if
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
