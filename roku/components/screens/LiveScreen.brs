' Live TV: the provider signed in to here, then its channels and guide. What's kept
' (the login, Favorites, Recently watched, reminders) is m.global.live, which MainScene
' writes to the registry when it's sent { name: "live", live }.

sub init()
    m.signIn = m.top.findNode("signIn")
    m.live = m.top.findNode("live")
    m.modes = m.top.findNode("modes")
    m.form = m.top.findNode("form")
    m.views = m.top.findNode("views")
    m.categories = m.top.findNode("categories")
    m.channelList = m.top.findNode("channels")
    m.guideGroup = m.top.findNode("guide")
    m.grid = m.top.findNode("grid")
    m.note = m.top.findNode("note")
    m.top.findNode("signInTitle").font = Bold_(48)
    m.top.findNode("signInNote").font = Regular_(28)
    m.top.findNode("signInError").font = Regular_(26)
    m.top.findNode("about").font = Bold_(36)
    m.top.findNode("aboutFacts").font = Regular_(24)
    m.note.font = Regular_(28)
    for each list in [m.form, m.categories]
        list.font = Regular_(28)
        list.focusedFont = Semibold_(28)
    end for
    m.modes.labels = ["Server login", "M3U playlist"]
    m.modes.observeField("pressed", "onMode")
    m.form.observeField("itemSelected", "onForm")
    m.views.labels = ["Channels", "Guide"]
    m.views.observeField("pressed", "onView")
    m.categories.observeField("itemFocused", "onCategoryFocused")
    m.categories.observeField("itemSelected", "onCategorySelected")
    m.channelList.observeField("itemSelected", "onChannelSelected")
    m.channelList.observeField("itemFocused", "onChannelFocused")
    m.grid.observeField("programFocused", "onProgramFocused")
    m.grid.observeField("programSelected", "onProgramSelected")
    m.epgTimer = CreateObject("roSGNode", "Timer")
    m.epgTimer.duration = 0.5
    m.epgTimer.observeField("fire", "askGuideNearFocus")
    m.categoryTimer = CreateObject("roSGNode", "Timer")
    m.categoryTimer.duration = 0.4
    m.categoryTimer.observeField("fire", "openFocusedCategory")
    m.mode = "server"
    m.fields = { host: "", user: "", password: "", url: "", guide: "" }
    m.busy = false
    m.view = "channels"
    m.cats = []
    m.channels = []
    m.guide = {}
    m.table = {}
    m.asked = {}
    m.playlist = invalid
    m.categoryId = ""
    m.global.observeField("live", "onLiveChanged")
end sub

function LiveState_() as object
    s = m.global.live
    if s = invalid then s = {}
    return s
end function

function SignedIn_() as boolean
    c = LiveState_().credentials
    return c <> invalid and (Str_(c.base) <> "" or Str_(c.playlistUrl) <> "")
end function

sub open()
    render()
end sub

sub onLiveChanged()
    ' Signed in or out elsewhere (Settings): this page as it now is.
    if SignedIn_() <> m.live.visible then render()
end sub

sub render()
    if SignedIn_() then
        m.signIn.visible = false
        m.live.visible = true
        paintView()
        loadCategories()
    else
        m.live.visible = false
        m.signIn.visible = true
        m.playlist = invalid
        paintForm()
    end if
    if m.top.isInFocusChain() then focusIn()
end sub

sub focusIn()
    if m.signIn.visible then
        m.form.setFocus(true)
    else if m.view = "guide" then
        m.grid.setFocus(true)
    else if m.channels.Count() > 0 then
        m.channelList.setFocus(true)
    else
        m.categories.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ Signing in

sub onMode()
    m.mode = Iif_(m.modes.pressed = 0, "server", "playlist")
    m.top.findNode("signInError").text = ""
    paintForm()
    m.form.setFocus(true)
end sub

' The fields as rows: each its name and what's typed, then the button.
sub paintForm()
    m.modes.on = [m.mode = "server", m.mode = "playlist"]
    m.formIds = []
    content = CreateObject("roSGNode", "ContentNode")
    if m.mode = "server" then
        FormRow_(content, "host", "Server address", m.fields.host, false)
        FormRow_(content, "user", "Username", m.fields.user, false)
        FormRow_(content, "password", "Password", m.fields.password, true)
        FormRow_(content, "go", Iif_(m.busy, "Connecting…", "Sign in"), "", false)
    else
        FormRow_(content, "url", "Playlist address", m.fields.url, false)
        FormRow_(content, "guide", "TV guide address (optional)", m.fields.guide, false)
        FormRow_(content, "go", Iif_(m.busy, "Loading…", "Add playlist"), "", false)
    end if
    focused = m.form.itemFocused
    m.form.content = content
    if focused > 0 and focused < content.getChildCount() then m.form.jumpToItem = focused
end sub

sub FormRow_(content as object, id as string, label as string, value as string, secret as boolean)
    c = content.createChild("ContentNode")
    shown = value
    if secret and value <> "" then shown = String(Len(value), "•")
    if id = "go" then
        c.title = "    " + label
    else if shown = "" then
        c.title = "    " + label
    else
        c.title = "    " + label + ":  " + shown
    end if
    m.formIds.Push(id)
end sub

sub onForm()
    id = m.formIds[m.form.itemSelected]
    if id = "go" then
        submit()
        return
    end if
    names = { host: "Server address", user: "Username", password: "Password", url: "Playlist address", guide: "TV guide address" }
    m.editing = id
    dialog = CreateObject("roSGNode", "KeyboardDialog")
    dialog.title = names[id]
    dialog.text = m.fields[id]
    dialog.buttons = ["OK", "Cancel"]
    if id = "password" then dialog.keyboard.textEditBox.secureMode = true
    dialog.observeField("buttonSelected", "onTyped")
    m.top.getScene().dialog = dialog
    m.dialog = dialog
    Trace_("typing " + id)
end sub

sub onTyped()
    if m.dialog.buttonSelected = 0 then m.fields[m.editing] = m.dialog.text.Trim()
    m.dialog.close = true
    m.dialog = invalid
    paintForm()
    m.form.setFocus(true)
end sub

sub submit()
    if m.busy then return
    errorLabel = m.top.findNode("signInError")
    if m.mode = "server" then
        if m.fields.host = "" or m.fields.user = "" or m.fields.password = "" then
            errorLabel.text = "Enter the server address, username and password."
            return
        end if
        c = { base: Xtream_Base(m.fields.host), username: m.fields.user, password: m.fields.password, playlistUrl: "", guideUrl: "" }
    else
        url = m.fields.url
        if url = "" then
            errorLabel.text = "Enter the playlist's address."
            return
        end if
        if LCase(Left(url, 4)) <> "http" then url = "http://" + url
        c = { base: "", username: "", password: "", playlistUrl: url, guideUrl: m.fields.guide }
    end if
    errorLabel.text = ""
    m.busy = true
    paintForm()
    m.signingIn = c
    Ask_("liveLogin", { credentials: c })
end sub

sub onSignedIn(a as object)
    m.busy = false
    if a = invalid or not a.ok then
        m.top.findNode("signInError").text = Iif_(a = invalid, "Couldn't connect. Check the server address and port.", Str_(a.error))
        paintForm()
        return
    end if
    if a.playlist <> invalid then m.playlist = a.playlist
    kept = LiveState_()
    live = { credentials: m.signingIn, account: a.account, favorites: Arr_(kept.favorites), recent: Arr_(kept.recent), reminders: Arr_(kept.reminders) }
    Trace_("live signed in")
    ' Saved by MainScene; the page follows from onLiveChanged.
    m.top.go = { name: "live", live: live }
    if not m.live.visible then render()
end sub

' ------------------------------------------------------------------ Categories and channels

function OwnCategories_() as object
    return [{ id: "fav", name: "Favorites" }, { id: "recent", name: "Recently watched" }, { id: "all", name: "All channels" }]
end function

sub loadCategories()
    c = LiveState_().credentials
    m.cats = OwnCategories_()
    paintCategories()
    if Xtream_IsPlaylist(c) then
        if m.playlist = invalid then
            m.note.text = "Loading the playlist…"
            Ask_("liveLogin", { credentials: c, id: "reload" })
            return
        end if
        m.cats.Append(Xtream_PlaylistCategories(m.playlist.channels))
        paintCategories()
        openCategory(firstCategory())
    else
        m.note.text = "Loading channels…"
        Ask_("liveCategories", { credentials: c })
    end if
end sub

' Favorites when there are some, else everything.
function firstCategory() as string
    if Arr_(LiveState_().favorites).Count() > 0 then return "fav"
    return "all"
end function

sub paintCategories()
    content = CreateObject("roSGNode", "ContentNode")
    for each cat in m.cats
        c = content.createChild("ContentNode")
        c.title = "  " + cat.name
    end for
    m.categories.content = content
end sub

sub onCategoryFocused()
    ' Opened a moment after the cursor stops on it, as moving down the list goes past many.
    if not m.categories.hasFocus() then return
    m.categoryTimer.control = "stop"
    m.categoryTimer.control = "start"
end sub

sub openFocusedCategory()
    i = m.categories.itemFocused
    if i >= 0 and i < m.cats.Count() and m.cats[i].id <> m.categoryId then openCategory(m.cats[i].id)
end sub

sub onCategorySelected()
    i = m.categories.itemSelected
    if i < 0 or i >= m.cats.Count() then return
    if m.cats[i].id <> m.categoryId then openCategory(m.cats[i].id)
    if m.channels.Count() > 0 then m.channelList.setFocus(true)
end sub

sub openCategory(id as string)
    m.categoryId = id
    for i = 0 to m.cats.Count() - 1
        if m.cats[i].id = id and m.categories.itemFocused <> i then m.categories.jumpToItem = i
    end for
    Trace_("category " + id)
    s = LiveState_()
    c = s.credentials
    if id = "fav" then
        showChannels(Arr_(s.favorites), "No favorites yet. Press ✱ on a channel to add it.")
    else if id = "recent" then
        showChannels(Arr_(s.recent), "Channels you watch show up here.")
    else if Xtream_IsPlaylist(c) then
        if m.playlist = invalid then return
        if id = "all" then
            showChannels(m.playlist.channels, "There are no channels in this playlist.")
        else
            showChannels(Xtream_InCategory(m.playlist.channels, id), "There are no channels here.")
        end if
    else
        m.channels = []
        paintChannels()
        m.note.text = "Loading channels…"
        Ask_("liveChannels", { credentials: c, categoryId: Iif_(id = "all", "", id), id: id })
    end if
end sub

sub showChannels(list as object, empty as string)
    m.channels = list
    m.note.text = Iif_(list.Count() = 0, empty, "")
    paintChannels()
    if m.view = "guide" then paintGuide()
    askGuide(0)
end sub

sub paintChannels()
    now = CreateObject("roDateTime").AsSeconds()
    favs = favoriteIds()
    content = CreateObject("roSGNode", "ContentNode")
    for each ch in m.channels
        c = content.createChild("ContentNode")
        label = Xtream_ChannelLabel(ch)
        if favs.DoesExist(ch.streamId.ToStr()) then label = label + "  ♥"
        c.title = label
        c.ShortDescriptionLine1 = ch.name
        c.HDPOSTERURL = Str_(ch.icon)
        listing = listingFor(ch)
        on = Xtream_ProgrammeAt(listing, now)
        nxt = Xtream_NextAfter(listing, now)
        c.description = ""
        fields = { progress: -1.0, nextText: "" }
        if on <> invalid then
            c.description = on.title
            fields.progress = Xtream_Progress(on, now)
        end if
        if nxt <> invalid then fields.nextText = "Next  " + Clock_(nxt.start) + "  " + nxt.title
        c.addFields(fields)
    end for
    focused = m.channelList.itemFocused
    m.channelList.content = content
    if focused > 0 and focused < content.getChildCount() then m.channelList.jumpToItem = focused
end sub

function listingFor(ch as object) as object
    key = ch.streamId.ToStr()
    if m.table.DoesExist(key) then return m.table[key]
    if m.guide.DoesExist(key) then return m.guide[key]
    return []
end function

function favoriteIds() as object
    out = {}
    for each ch in Arr_(LiveState_().favorites)
        out[ch.streamId.ToStr()] = true
    end for
    return out
end function

' "20:30", in the Roku's own time.
function Clock_(epoch as integer) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(epoch)
    d.ToLocalTime()
    return d.GetHours().ToStr() + ":" + Two_(d.GetMinutes())
end function

sub onChannelFocused()
    m.epgTimer.control = "stop"
    m.epgTimer.control = "start"
end sub

sub askGuideNearFocus()
    askGuide(m.channelList.itemFocused)
end sub

' What's on for the channels around [at], each asked once.
sub askGuide(at as integer)
    c = LiveState_().credentials
    if c = invalid or Xtream_IsPlaylist(c) then return
    ids = []
    first = at - 3
    if first < 0 then first = 0
    for i = first to at + 9
        if i < m.channels.Count() then
            key = m.channels[i].streamId.ToStr()
            if not m.asked.DoesExist(key) then
                m.asked[key] = true
                ids.Push(m.channels[i].streamId)
            end if
        end if
    end for
    if ids.Count() > 0 then Ask_("liveEpg", { credentials: c, streamIds: ids })
end sub

sub onChannelSelected()
    watch(m.channelList.itemSelected, invalid)
end sub

' A channel to watch, or a programme from its archive; it goes into Recently watched.
sub watch(index as integer, catchUp as dynamic)
    if index < 0 or index >= m.channels.Count() then return
    ch = m.channels[index]
    s = LiveState_()
    recent = [ch]
    for each r in Arr_(s.recent)
        if r.streamId <> ch.streamId and recent.Count() < 30 then recent.Push(r)
    end for
    s.recent = recent
    m.top.go = { name: "live", live: s }
    m.top.watch = { channels: m.channels, index: index, catchUp: catchUp, guide: m.guide, table: m.table }
end sub

sub toggleFavorite(ch as object)
    s = LiveState_()
    list = []
    found = false
    for each f in Arr_(s.favorites)
        if f.streamId = ch.streamId then found = true else list.Push(f)
    end for
    if not found then list.Push(ch)
    s.favorites = list
    m.top.go = { name: "live", live: s }
    Trace_(Iif_(found, "unfavorited ", "favorited ") + ch.streamId.ToStr())
    if m.categoryId = "fav" then
        showChannels(list, "No favorites yet. Press ✱ on a channel to add it.")
    else
        paintChannels()
    end if
end sub

' ------------------------------------------------------------------ The guide

sub onView()
    m.view = Iif_(m.views.pressed = 0, "channels", "guide")
    paintView()
    focusIn()
end sub

sub paintView()
    guide = m.view = "guide"
    m.views.on = [not guide, guide]
    m.categories.visible = not guide
    m.channelList.visible = not guide
    m.guideGroup.visible = guide
    m.note.translation = Iif_(guide, [96, 110], [540, 110])
    if guide then paintGuide()
end sub

' The window: from the half hour before the last one, three hours across.
function GuideStart_() as integer
    now = CreateObject("roDateTime").AsSeconds()
    return Int(now / 1800) * 1800 - 1800
end function

sub paintGuide()
    start = GuideStart_()
    m.grid.contentStartTime = start
    m.shown = []
    content = CreateObject("roSGNode", "ContentNode")
    ids = []
    c = LiveState_().credentials
    for each ch in m.channels
        if m.shown.Count() >= 60 then exit for
        m.shown.Push(ch)
        row = content.createChild("ContentNode")
        row.title = Xtream_ChannelLabel(ch)
        row.HDSMALLICONURL = Str_(ch.icon)
        listing = listingFor(ch)
        any = false
        for each p in listing
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
        if not m.table.DoesExist(ch.streamId.ToStr()) and ids.Count() < 30 and not Xtream_IsPlaylist(c) then ids.Push(ch.streamId)
    end for
    m.grid.content = content
    ' The cursor on what's on now, as the other apps open the guide; on a repaint, where it was.
    if m.guideAt = invalid then m.guideAt = CreateObject("roDateTime").AsSeconds()
    m.grid.jumpToTime = m.guideAt
    if ids.Count() > 0 and m.askedTable <> m.categoryId then
        m.askedTable = m.categoryId
        Ask_("liveTable", { credentials: c, streamIds: ids })
    end if
end sub

' The programme at the grid's cursor: the channel's index and the programme, or invalid
' for a channel with nothing known.
function GridProgramme_(channelIndex as integer, programIndex as integer) as dynamic
    if channelIndex < 0 or m.shown = invalid or channelIndex >= m.shown.Count() then return invalid
    ch = m.shown[channelIndex]
    row = m.grid.content.getChild(channelIndex)
    if row = invalid then return invalid
    node = row.getChild(programIndex)
    if node = invalid then return invalid
    for each p in listingFor(ch)
        if p.start = node.PLAYSTART then return p
    end for
    return invalid
end function

sub onProgramFocused()
    ci = m.grid.channelFocused
    if ci < 0 or m.shown = invalid or ci >= m.shown.Count() then return
    ch = m.shown[ci]
    p = GridProgramme_(ci, m.grid.programFocused)
    Trace_("guide on " + Iif_(p = invalid, ch.name, p.title))
    if p <> invalid then m.guideAt = p.start + 1
    now = CreateObject("roDateTime").AsSeconds()
    if p = invalid then
        m.top.findNode("about").text = ch.name
        m.top.findNode("aboutFacts").text = "OK to watch"
        return
    end if
    hint = "OK to watch"
    if p.ends <= now then
        hint = Iif_(Xtream_CanCatchUp(ch, p, now), "OK to watch it again", "Over, and not in this channel's archive")
    else if p.start > now then
        hint = Iif_(hasReminder(ch, p), "Reminder set  ·  OK to cancel it", "OK to be reminded when it starts")
    end if
    m.top.findNode("about").text = p.title
    m.top.findNode("aboutFacts").text = Join_([ch.name, Clock_(p.start) + "–" + Clock_(p.ends), hint], "  ·  ")
end sub

' OK in the guide: what's on, watched; what's over, from the archive; what's to come, a reminder.
sub onProgramSelected()
    ci = m.grid.channelFocused
    if ci < 0 or m.shown = invalid or ci >= m.shown.Count() then return
    ch = m.shown[ci]
    index = ci
    p = GridProgramme_(ci, m.grid.programFocused)
    now = CreateObject("roDateTime").AsSeconds()
    if p = invalid or Xtream_IsOnAt(p, now) then
        watch(index, invalid)
    else if p.ends <= now then
        if Xtream_CanCatchUp(ch, p, now) then watch(index, p)
    else
        toggleReminder(ch, p)
        onProgramFocused()
    end if
end sub

function hasReminder(ch as object, p as object) as boolean
    for each r in Arr_(LiveState_().reminders)
        if r.channel.streamId = ch.streamId and r.start = p.start then return true
    end for
    return false
end function

sub toggleReminder(ch as object, p as object)
    s = LiveState_()
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

' ------------------------------------------------------------------ Answers

sub answered(r as object)
    if r.op = "liveLogin" then
        if r.id = "reload" then
            a = r.answer
            if a <> invalid and a.ok and a.playlist <> invalid then
                m.playlist = a.playlist
                m.note.text = ""
                loadCategories()
            else
                m.note.text = "Couldn't download the playlist. Try again in a moment."
            end if
        else
            onSignedIn(r.answer)
        end if
    else if r.op = "liveCategories" then
        if r.categories = invalid then
            m.note.text = "Your provider didn't answer. Try again in a moment."
            return
        end if
        m.cats = OwnCategories_()
        m.cats.Append(r.categories)
        paintCategories()
        openCategory(firstCategory())
    else if r.op = "liveChannels" then
        if r.id <> Iif_(m.categoryId = "all", "all", m.categoryId) then return
        if r.channels = invalid then
            m.note.text = "Your provider didn't answer. Try again in a moment."
            return
        end if
        showChannels(r.channels, "There are no channels here.")
    else if r.op = "liveEpg" then
        for each k in r.guide
            m.guide[k] = r.guide[k]
        end for
        paintChannels()
        if m.view = "guide" then paintGuide()
    else if r.op = "liveTable" then
        for each k in r.table
            m.table[k] = r.table[k]
        end for
        if m.view = "guide" then paintGuide()
        paintChannels()
    end if
end sub

' ------------------------------------------------------------------ The remote

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.signIn.visible then
        if key = "up" and m.form.hasFocus() and m.form.itemFocused = 0 then
            m.modes.setFocus(true)
            return true
        else if key = "down" and m.modes.hasFocus() then
            m.form.setFocus(true)
            return true
        end if
        return false
    end if
    if key = "options" and m.channelList.hasFocus() then
        i = m.channelList.itemFocused
        if i >= 0 and i < m.channels.Count() then toggleFavorite(m.channels[i])
        return true
    end if
    if key = "right" and m.categories.hasFocus() and m.channels.Count() > 0 then
        m.channelList.setFocus(true)
        return true
    else if key = "left" and m.channelList.hasFocus() then
        m.categories.setFocus(true)
        return true
    else if key = "up" and (m.categories.hasFocus() and m.categories.itemFocused = 0 or m.channelList.hasFocus() and m.channelList.itemFocused = 0) then
        m.views.setFocus(true)
        return true
    else if key = "up" and m.grid.isInFocusChain() and m.grid.channelFocused <= 0 then
        m.views.setFocus(true)
        return true
    else if key = "down" and m.views.hasFocus() then
        focusIn()
        return true
    end if
    return false
end function
