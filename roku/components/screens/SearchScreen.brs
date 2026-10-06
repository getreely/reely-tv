sub init()
    m.keyboard = m.top.findNode("keyboard")
    m.rows = m.top.findNode("rows")
    m.recent = m.top.findNode("recent")
    m.note = m.top.findNode("note")
    m.pause = m.top.findNode("pause")
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.top.findNode("recentTitle").font = Bold_(30)
    m.recent.font = Regular_(28)
    m.recent.focusedFont = Semibold_(28)
    m.keyboard.observeField("text", "onTyped")
    m.pause.observeField("fire", "search")
    m.rows.observeField("rowItemSelected", "onPicked")
    m.recent.observeField("itemSelected", "onRecent")
    m.query = ""
    m.run = 0
    showRecent()
    m.note.text = "Search movies, shows, people and collections."
end sub

'' Opened with words already in the box: a request that's arrived, looked for by name.
sub open()
    q = Str_(m.top.route.query)
    if q <> "" then m.keyboard.text = q
end sub

sub showRecent()
    list = Arr_(m.global.recentSearches)
    content = CreateObject("roSGNode", "ContentNode")
    for each q in list
        c = content.createChild("ContentNode")
        c.title = q
    end for
    if list.Count() > 0 then
        c = content.createChild("ContentNode")
        c.title = "Clear"
    end if
    m.recent.content = content
    m.top.findNode("recentTitle").visible = list.Count() > 0 and m.query = ""
    m.recent.visible = list.Count() > 0 and m.query = ""
end sub

' Typing on a remote is slow: a pause, rather than a search for every letter.
sub onTyped()
    m.query = m.keyboard.text
    m.pause.control = "stop"
    if m.query.Trim() = "" then
        m.rows.content = invalid
        m.note.text = "Search movies, shows, people and collections."
        showRecent()
        return
    end if
    m.note.text = "Searching…"
    m.pause.control = "start"
    showRecent()
end sub

sub search()
    m.run = m.run + 1
    Ask_("search", { libraries: Session_().libraries, query: m.query.Trim(), id: m.run })
end sub

sub answered(r as object)
    if r.id <> m.run or m.query.Trim() = "" then return
    a = r.answer
    if a = invalid then
        m.note.text = "Couldn't search just now. Try again in a moment."
        return
    end if
    ' The provider's films and series put in among Plex's, while they're on.
    if r.op = "search" and IptvReady_() then
        Ask_("iptvSearch", { query: m.query.Trim(), answer: a, id: r.id })
        return
    end if
    ' People last, unless it was a person's name that was typed.
    people = { title: "People", items: a.people, people: true }
    rows = [
        { title: "Movies and shows", items: a.results },
        { title: "Collections", items: a.collections },
        { title: "Other results", items: a.more }
    ]
    if Bool_(a.peopleFirst) then rows.Unshift(people) else rows.Push(people)
    #if DEBUG
        if r.op = "iptvSearch" then
            names = []
            for each i in Arr_(a.results)
                names.Push(i.title)
            end for
            Trace_("iptv search " + Join_(names, ", "))
        end if
    #end if
    ShowRows_(m.rows, rows)
    m.rows.itemSize = [1140, 500]
    parts = []
    found = a.results.Count() + a.more.Count()
    if found > 0 then parts.Push(found.ToStr() + " in your library")
    if a.people.Count() > 0 then parts.Push(Iif_(a.people.Count() = 1, "1 person", a.people.Count().ToStr() + " people"))
    if a.collections.Count() > 0 then parts.Push(Iif_(a.collections.Count() = 1, "1 collection", a.collections.Count().ToStr() + " collections"))
    if parts.Count() = 0 then
        m.note.text = Iif_(a.unreachable, "Couldn't reach your Plex server to search. Try again in a moment.", "Nothing matched " + Chr(34) + m.query.Trim() + Chr(34) + ".")
    else
        m.note.text = Join_(parts, "  ·  ")
    end if
end sub

sub onPicked()
    item = SelectedRowItem_(m.rows)
    if item = invalid then return
    remember()
    if item.name <> invalid then
        m.top.go = { name: "list", kind: "person", person: { id: item.id, name: item.name, serverBase: item.serverBase }, title: item.name }
    else
        m.top.go = RouteFor_(item)
    end if
end sub

' What was searched kept, when something it found is opened: a search that worked.
sub remember()
    text = m.query.Trim()
    if text = "" then return
    out = [text]
    for each q in Arr_(m.global.recentSearches)
        if LCase(q) <> LCase(text) and out.Count() < 8 then out.Push(q)
    end for
    m.top.go = { name: "recentSearches", list: out }
end sub

sub onRecent()
    i = m.recent.itemSelected
    list = Arr_(m.global.recentSearches)
    if i >= list.Count() then
        m.top.go = { name: "recentSearches", list: [] }
        m.top.findNode("recentTitle").visible = false
        m.recent.visible = false
        m.keyboard.setFocus(true)
        return
    end if
    m.keyboard.text = list[i]
    m.keyboard.setFocus(true)
end sub

sub focusIn()
    m.keyboard.setFocus(true)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "right" and m.keyboard.isInFocusChain() and m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then
        m.rows.setFocus(true)
        return true
    else if key = "left" and m.rows.isInFocusChain() then
        m.keyboard.setFocus(true)
        return true
    else if key = "down" and m.keyboard.isInFocusChain() and m.recent.visible then
        m.recent.setFocus(true)
        return true
    else if key = "up" and m.recent.hasFocus() then
        m.keyboard.setFocus(true)
        return true
    else if key = "options" and m.rows.isInFocusChain() then
        item = FocusedRowItem_(m.rows)
        if item <> invalid and item.name = invalid then m.top.menu = { item: item }
        return true
    end if
    return false
end function
