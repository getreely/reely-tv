sub init()
    m.grid = m.top.findNode("grid")
    m.cats = m.top.findNode("cats")
    m.top.findNode("aboutCategory").font = Semibold_(22)
    m.top.findNode("about").font = Bold_(44)
    m.top.findNode("aboutFacts").font = Regular_(26)
    m.top.findNode("note").font = Regular_(28)
    m.grid.observeField("programFocused", "onProgramFocused")
    m.grid.observeField("channelFocused", "onChannelMoved")
    m.grid.observeField("programSelected", "onProgramSelected")
    m.cats.observeField("pressed", "onCategory")
    m.chooser = invalid
end sub

sub open()
    r = m.top.request
    m.channels = Arr_(r.channels)
    m.guide = r.guide
    m.table = r.table
    if m.guide = invalid then m.guide = {}
    if m.table = invalid then m.table = {}
    m.catList = Arr_(r.cats)
    m.categoryId = Str_(r.categoryId)
    m.playlist = r.playlist
    m.askedTable = {}
    m.guideAt = CreateObject("roDateTime").AsSeconds()
    m.playingAt = Int(Num_(r.index))
    paintCats()
    paint(m.playingAt)
    Trace_("live guide shown")
end sub

sub takeFocus()
    if m.channels <> invalid and m.channels.Count() > 0 then
        m.grid.setFocus(true)
        ' Told again with the cursor in it: what's on now, on the channel playing.
        if m.playingAt >= 0 then m.grid.jumpToChannel = m.playingAt
        m.grid.jumpToTime = m.guideAt
    else
        m.cats.setFocus(true)
    end if
end sub

' The categories in a row, the one browsed outlined.
sub paintCats()
    labels = []
    on = []
    at = 0
    for i = 0 to m.catList.Count() - 1
        labels.Push(Str_(m.catList[i].name))
        on.Push(m.catList[i].id = m.categoryId)
        if m.catList[i].id = m.categoryId then at = i
    end for
    m.cats.labels = labels
    m.cats.on = on
    m.cats.focusIndex = at
    m.cats.visible = labels.Count() > 1
end sub

function listingFor(ch as object) as object
    key = ch.streamId.ToStr()
    if m.table.DoesExist(key) then return m.table[key]
    if m.guide.DoesExist(key) then return m.guide[key]
    return []
end function

' The grid, as the guide page draws it, the cursor on [channel] at the time it was at.
sub paint(channel as integer)
    now = CreateObject("roDateTime").AsSeconds()
    start = Int(now / 1800) * 1800 - 1800
    name = ""
    for each cat in m.catList
        if cat.id = m.categoryId then name = Str_(cat.name)
    end for
    m.top.findNode("aboutCategory").text = UCase(name)
    m.top.findNode("note").text = Iif_(m.channels.Count() = 0, "Nothing in this category  ·  pick another above", "")
    m.grid.visible = m.channels.Count() > 0
    m.grid.contentStartTime = start
    m.shown = []
    content = CreateObject("roSGNode", "ContentNode")
    ids = []
    c = m.global.live.credentials
    for each ch in m.channels
        if m.shown.Count() >= 60 then exit for
        m.shown.Push(ch)
        row = content.createChild("ContentNode")
        row.title = Xtream_ChannelLabel(ch)
        row.HDSMALLICONURL = Str_(ch.icon)
        any = false
        for each p in listingFor(ch)
            if p.ends > start and p.start < start + 3 * 86400 then
                prog = row.createChild("ContentNode")
                prog.title = p.title
                prog.PLAYSTART = p.start
                prog.PLAYDURATION = p.ends - p.start
                any = true
            end if
        end for
        if not any then
            ' Nothing known: the channel itself, across the window.
            prog = row.createChild("ContentNode")
            prog.title = ch.name
            prog.PLAYSTART = start
            prog.PLAYDURATION = 3 * 3600
        end if
        key = ch.streamId.ToStr()
        if not m.table.DoesExist(key) and not m.askedTable.DoesExist(key) and ids.Count() < 30 then
            m.askedTable[key] = true
            ids.Push(ch.streamId)
        end if
    end for
    m.grid.content = content
    ' The channel, then the time: moving to a channel puts the cursor at the start of its row.
    m.lastChannel = channel
    if channel >= 0 and channel < m.shown.Count() then m.grid.jumpToChannel = channel
    m.grid.jumpToTime = m.guideAt
    if ids.Count() > 0 then Ask_("liveTable", Xtream_GuideArgs(c, Str_(m.global.live.listGuide), m.shown, ids, m.playlist))
end sub

' The programme at the grid's cursor, or invalid for a channel with nothing known.
function GridProgramme_(channelIndex as integer, programIndex as integer) as dynamic
    if channelIndex < 0 or m.shown = invalid or channelIndex >= m.shown.Count() then return invalid
    row = m.grid.content.getChild(channelIndex)
    if row = invalid then return invalid
    node = row.getChild(programIndex)
    if node = invalid then return invalid
    for each p in listingFor(m.shown[channelIndex])
        if p.start = node.PLAYSTART then return p
    end for
    return invalid
end function

' Up off the first channel: a Roku's grid goes round to the last, so the key never comes
' here. Up from the top is the categories, as on the Fire TV; the cursor stays where it was.
' (With two channels, up and down look the same: ✱ has the categories too.)
sub onChannelMoved()
    now = m.grid.channelFocused
    was = m.lastChannel
    m.lastChannel = now
    n = 0
    if m.shown <> invalid then n = m.shown.Count()
    if was = 0 and now = n - 1 and n > 2 and m.cats.visible and m.grid.hasFocus() then
        m.lastChannel = 0
        m.grid.jumpToChannel = 0
        m.cats.setFocus(true)
        return
    end if
    onProgramFocused()
end sub

sub onProgramFocused()
    ci = m.grid.channelFocused
    if ci < 0 or m.shown = invalid or ci >= m.shown.Count() then return
    ch = m.shown[ci]
    p = GridProgramme_(ci, m.grid.programFocused)
    Trace_("over guide on " + Iif_(p = invalid, ch.name, p.title))
    if p = invalid then
        m.top.findNode("about").text = ch.name
        m.top.findNode("aboutFacts").text = "OK to watch  ·  ✱ for more"
        return
    end if
    ' Where the cursor's got to, kept for a repaint: only once it's in the grid, not while
    ' the grid is filled and lands on its first programme.
    if m.grid.hasFocus() then m.guideAt = p.start + 1
    now = CreateObject("roDateTime").AsSeconds()
    hint = "OK to watch  ·  ✱ for more"
    if p.ends <= now then hint = Iif_(Xtream_CanCatchUp(ch, p, now), "OK to watch it again", "Over, and not in this channel's archive")
    m.top.findNode("about").text = p.title
    m.top.findNode("aboutFacts").text = Join_([ch.name, Clock_(p.start) + " – " + Clock_(p.ends), hint], "  ·  ")
end sub

function Clock_(epoch as integer) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(epoch)
    d.ToLocalTime()
    minutes = d.GetMinutes()
    return d.GetHours().ToStr() + ":" + Iif_(minutes < 10, "0", "") + minutes.ToStr()
end function

' OK: the channel; what's over, from its archive.
sub onProgramSelected()
    ci = m.grid.channelFocused
    if ci < 0 or m.shown = invalid or ci >= m.shown.Count() then return
    p = GridProgramme_(ci, m.grid.programFocused)
    now = CreateObject("roDateTime").AsSeconds()
    if p <> invalid and p.ends <= now then
        if Xtream_CanCatchUp(m.shown[ci], p, now) then pick(ci, p)
        return
    end if
    pick(ci, invalid)
end sub

sub pick(index as integer, catchUp as dynamic)
    m.top.picked = { channels: m.channels, index: index, catchUp: catchUp, guide: m.guide, table: m.table, categoryId: m.categoryId }
end sub

' ------------------------------------------------------------------ Another category

sub onCategory()
    chooseCategory(m.cats.pressed)
end sub

sub chooseCategory(i as integer)
    if i < 0 or i >= m.catList.Count() then return
    id = m.catList[i].id
    if id = m.categoryId then
        m.grid.setFocus(m.channels.Count() > 0)
        return
    end if
    m.categoryId = id
    paintCats()
    Trace_("over guide category " + id)
    s = m.global.live
    if id = "fav" then
        showChannels(Arr_(s.favorites))
    else if id = "recent" then
        showChannels(Arr_(s.recent))
    else if m.playlist <> invalid then
        if id = "all" then showChannels(Arr_(m.playlist)) else showChannels(Xtream_InCategory(Arr_(m.playlist), id))
    else
        m.channels = []
        paint(-1)
        m.top.findNode("note").text = "Loading channels…"
        Ask_("liveChannels", { credentials: s.credentials, categoryId: Iif_(id = "all", "", id), id: id })
    end if
end sub

sub showChannels(list as object)
    m.channels = list
    paint(0)
    if m.channels.Count() > 0 then m.grid.setFocus(true)
end sub

sub answered(r as object)
    if r.op = "liveChannels" then
        if Str_(r.id) <> m.categoryId then return
        showChannels(Arr_(r.channels))
    else if r.op = "liveTable" then
        for each k in r.table
            m.table[k] = r.table[k]
        end for
        at = m.grid.channelFocused
        hadFocus = m.grid.hasFocus()
        paint(at)
        if hadFocus then m.grid.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ ✱: the channel's menu

sub showMenu()
    ci = m.grid.channelFocused
    if ci < 0 or m.shown = invalid or ci >= m.shown.Count() then return
    ch = m.shown[ci]
    p = GridProgramme_(ci, m.grid.programFocused)
    now = CreateObject("roDateTime").AsSeconds()
    m.menuFor = { index: ci, channel: ch, upcoming: invalid }
    labels = ["Watch this channel"]
    notes = [""]
    m.menuIds = ["watch"]
    if p <> invalid and p.start > now then
        m.menuFor.upcoming = p
        labels.Push(Iif_(hasReminder(ch, p), "Cancel the reminder", "Remind me"))
        notes.Push(p.title)
        m.menuIds.Push("remind")
    end if
    labels.Push(Iif_(isFavorite(ch), "Remove from Favorites", "Add to Favorites"))
    notes.Push("")
    m.menuIds.Push("favorite")
    if m.catList.Count() > 1 then
        labels.Push("Another category")
        notes.Push(CategoryName_())
        m.menuIds.Push("category")
    end if
    m.chooser = CreateObject("roSGNode", "Chooser")
    m.chooser.setFields({ title: ch.name, notes: notes, current: -1 })
    m.chooser.options = labels
    m.chooser.observeField("picked", "onMenu")
    m.top.appendChild(m.chooser)
    m.chooser.setFocus(true)
    Trace_("over guide menu shown")
end sub

sub onMenu()
    i = m.chooser.picked
    m.chooser.unobserveField("picked")
    m.top.removeChild(m.chooser)
    m.chooser = invalid
    m.grid.setFocus(true)
    if i < 0 or i >= m.menuIds.Count() then return
    id = m.menuIds[i]
    f = m.menuFor
    if id = "watch" then
        pick(f.index, invalid)
    else if id = "remind" then
        toggleReminder(f.channel, f.upcoming)
    else if id = "favorite" then
        toggleFavorite(f.channel)
    else if id = "category" then
        showCategories()
    end if
end sub

function CategoryName_() as string
    for each cat in m.catList
        if cat.id = m.categoryId then return Str_(cat.name)
    end for
    return ""
end function

' The categories as a list from the right, the one browsed ticked.
sub showCategories()
    labels = []
    notes = []
    current = -1
    for i = 0 to m.catList.Count() - 1
        labels.Push(Str_(m.catList[i].name))
        notes.Push("")
        if m.catList[i].id = m.categoryId then current = i
    end for
    m.chooser = CreateObject("roSGNode", "Chooser")
    m.chooser.setFields({ title: "Categories", notes: notes, current: current })
    m.chooser.options = labels
    m.chooser.observeField("picked", "onCategoryPicked")
    m.top.appendChild(m.chooser)
    m.chooser.setFocus(true)
end sub

sub onCategoryPicked()
    i = m.chooser.picked
    m.chooser.unobserveField("picked")
    m.top.removeChild(m.chooser)
    m.chooser = invalid
    m.grid.setFocus(true)
    if i >= 0 then chooseCategory(i)
end sub

function isFavorite(ch as object) as boolean
    for each f in Arr_(m.global.live.favorites)
        if f.streamId = ch.streamId then return true
    end for
    return false
end function

function hasReminder(ch as object, p as object) as boolean
    for each r in Arr_(m.global.live.reminders)
        if r.channel.streamId = ch.streamId and r.start = p.start then return true
    end for
    return false
end function

sub toggleReminder(ch as object, p as object)
    s = m.global.live
    list = []
    found = false
    for each r in Arr_(s.reminders)
        if r.channel.streamId = ch.streamId and r.start = p.start then found = true else list.Push(r)
    end for
    if not found then list.Push({ channel: ch, start: p.start, ends: p.ends, title: p.title })
    s.reminders = list
    m.top.go = { name: "live", live: s }
    Trace_(Iif_(found, "reminder off ", "reminder on ") + p.title)
end sub

sub toggleFavorite(ch as object)
    s = m.global.live
    list = []
    found = false
    for each f in Arr_(s.favorites)
        if f.streamId = ch.streamId then found = true else list.Push(f)
    end for
    if not found then list.Push(ch)
    s.favorites = list
    m.top.go = { name: "live", live: s }
    Trace_(Iif_(found, "unfavorited ", "favorited ") + ch.streamId.ToStr())
end sub

' ------------------------------------------------------------------ The remote

' Every key is the guide's while it's up: nothing changes the channel behind it.
function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return true
    if m.chooser <> invalid then return true
    if key = "back" then
        m.top.closed = true
    else if key = "up" and m.grid.isInFocusChain() and m.grid.channelFocused <= 0 and m.cats.visible then
        m.cats.setFocus(true)
    else if key = "down" and m.cats.hasFocus() and m.channels.Count() > 0 then
        m.grid.setFocus(true)
    else if key = "options" and m.grid.isInFocusChain() then
        showMenu()
    end if
    return true
end function
