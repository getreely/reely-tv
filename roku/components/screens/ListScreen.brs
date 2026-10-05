sub init()
    m.top.findNode("title").font = Bold_(56)
    m.top.findNode("count").font = Regular_(28)
    m.grid = m.top.findNode("grid")
    m.actions = m.top.findNode("actions")
    m.grid.observeField("itemSelected", "onPicked")
    m.actions.observeField("pressed", "onAction")
    m.items = []
end sub

sub open()
    r = m.top.route
    m.top.findNode("title").text = r.title
    m.top.findNode("count").text = "Loading…"
    if r.kind = "person" then
        Ask_("person", { libraries: ShownLibraries_(""), personId: r.person.id, serverBase: Str_(r.person.serverBase) })
    else
        item = r.item
        base = item.serverBase
        if base = invalid or base = "" then base = Session_().base
        path = "/library/collections/" + item.ratingKey + "/children"
        if r.kind = "playlist" then path = "/playlists/" + item.ratingKey + "/items"
        Ask_("children", { base: base, token: TokenFor_(base), path: path })
    end if
end sub

sub answered(r as object)
    m.items = []
    for each i in Arr_(r.items)
        if m.top.route.kind <> "playlist" or i.type = "movie" or i.type = "episode" then m.items.Push(i)
    end for
    content = CreateObject("roSGNode", "ContentNode")
    for each i in m.items
        ItemContent_(content, i, false)
    end for
    m.grid.content = content
    n = m.items.Count()
    if n = 0 then
        m.top.findNode("count").text = Iif_(m.top.route.kind = "person", "Nothing they're in is in your libraries.", "This is empty.")
    else
        m.top.findNode("count").text = Iif_(n = 1, "1 title", n.ToStr() + " titles")
    end if
    playlist = m.top.route.kind = "playlist" and n > 0
    m.actions.visible = playlist
    if playlist then m.actions.labels = ["Play", "Shuffle"]
    m.grid.translation = Iif_(playlist, [96, 240], [96, 160])
    if m.top.isInFocusChain() then focusIn()
end sub

sub onPicked()
    i = m.grid.itemSelected
    if i >= 0 and i < m.items.Count() then m.top.go = RouteFor_(m.items[i])
end sub

' A playlist played in order, or shuffled, each on to the next.
sub onAction()
    queue = m.items
    if m.actions.pressed = 1 then queue = Shuffled_(m.items)
    if queue.Count() > 0 then m.top.play = { item: queue[0], resume: false, queue: queue, mediaIndex: 0 }
end sub

function Shuffled_(list as object) as object
    out = []
    for each x in list
        out.Push(x)
    end for
    for i = out.Count() - 1 to 1 step -1
        j = Rnd(i + 1) - 1
        t = out[i]
        out[i] = out[j]
        out[j] = t
    end for
    return out
end function

sub focusIn()
    if m.actions.visible then
        m.actions.setFocus(true)
    else if m.items.Count() > 0 then
        m.grid.setFocus(true)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "down" and m.actions.hasFocus() and m.items.Count() > 0 then
        m.grid.setFocus(true)
        return true
    else if key = "up" and m.grid.hasFocus() and m.actions.visible then
        m.actions.setFocus(true)
        return true
    else if key = "options" and m.grid.hasFocus() then
        i = m.grid.itemFocused
        if i >= 0 and i < m.items.Count() then m.top.menu = { item: m.items[i] }
        return true
    end if
    return false
end function
