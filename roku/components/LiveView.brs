sub init()
    m.video = m.top.findNode("video")
    m.banner = m.top.findNode("banner")
    m.menu = m.top.findNode("menu")
    m.menuList = m.top.findNode("menuList")
    m.top.findNode("title").font = Bold_(44)
    m.top.findNode("facts").font = Regular_(28)
    m.top.findNode("hint").font = Regular_(24)
    m.top.findNode("wait").font = Regular_(30)
    m.top.findNode("menuTitle").font = Regular_(26)
    m.menuList.font = Regular_(28)
    m.menuList.focusedFont = Semibold_(28)
    m.video.observeField("state", "onState")
    m.menuList.observeField("itemSelected", "onMenu")
    m.top.findNode("bannerTimer").observeField("fire", "hideBanner")
    m.top.findNode("ticker").observeField("fire", "paintBanner")
end sub

sub takeFocus()
    m.video.setFocus(true)
end sub

sub start()
    r = m.top.request
    m.channels = r.channels
    m.index = r.index
    m.guide = r.guide
    m.table = r.table
    if m.guide = invalid then m.guide = {}
    if m.table = invalid then m.table = {}
    m.leaving = false
    tune(r.catchUp)
    m.top.findNode("ticker").control = "start"
end sub

function credentials() as object
    return m.global.live.credentials
end function

' The channel at m.index, live, or [catchUp] from its archive.
sub tune(catchUp as dynamic)
    ch = m.channels[m.index]
    m.catchUp = catchUp
    c = credentials()
    content = CreateObject("roSGNode", "ContentNode")
    content.title = ch.name
    if catchUp <> invalid then
        account = m.global.live.account
        offset = 0
        if account <> invalid then offset = Int(Num_(account.clockOffset))
        content.url = Xtream_CatchUpUrl(c, ch, catchUp.start, catchUp.ends, offset)
        content.streamFormat = "ts"
        Trace_("catch-up from " + Mid(content.url, Instr(1, content.url, "/timeshift/")))
    else
        format = "m3u8"
        prefs = m.global.prefs
        if prefs <> invalid and prefs.streamFormat = "ts" then format = "ts"
        content.url = Xtream_StreamUrl(c, ch, format)
        content.streamFormat = Iif_(format = "ts" or LCase(Right(content.url.Split("?")[0], 3)) = ".ts", "ts", "hls")
        content.live = true
    end if
    m.top.findNode("wait").text = "Loading…"
    m.video.content = content
    m.video.control = "play"
    Trace_("tuned " + ch.streamId.ToStr() + Iif_(catchUp <> invalid, " from the archive", ""))
    showBanner()
end sub

sub onState()
    s = m.video.state
    Trace_("live video " + s)
    if s = "playing" then
        m.top.findNode("wait").text = ""
    else if s = "error" then
        m.top.findNode("wait").text = "This channel isn't playing. It may be off air, or your provider's connection limit reached."
    end if
end sub

function listing() as object
    key = m.channels[m.index].streamId.ToStr()
    if m.table.DoesExist(key) then return m.table[key]
    if m.guide.DoesExist(key) then return m.guide[key]
    return []
end function

function onNow() as dynamic
    return Xtream_ProgrammeAt(listing(), CreateObject("roDateTime").AsSeconds())
end function

function isFavorite(ch as object) as boolean
    for each f in Arr_(m.global.live.favorites)
        if f.streamId = ch.streamId then return true
    end for
    return false
end function

sub showBanner()
    paintBanner()
    m.banner.visible = true
    t = m.top.findNode("bannerTimer")
    t.control = "stop"
    t.control = "start"
end sub

sub hideBanner()
    if not m.menu.visible then m.banner.visible = false
end sub

sub paintBanner()
    if m.channels = invalid then return
    ch = m.channels[m.index]
    now = CreateObject("roDateTime").AsSeconds()
    title = Xtream_ChannelLabel(ch)
    if isFavorite(ch) then title = title + "  ♥"
    m.top.findNode("title").text = title
    p = m.catchUp
    if p = invalid then p = onNow()
    facts = ""
    progress = -1.0
    if p <> invalid then
        facts = p.title + "  ·  " + Clock_(p.start) + "–" + Clock_(p.ends)
        if m.catchUp <> invalid then
            facts = facts + "  ·  From the archive"
        else
            progress = Xtream_Progress(p, now)
        end if
    end if
    m.top.findNode("facts").text = facts
    m.top.findNode("track").visible = progress >= 0
    m.top.findNode("bar").visible = progress >= 0
    if progress >= 0 then m.top.findNode("bar").width = 900 * progress
    if m.catchUp <> invalid then
        hint = "Left and right skip  ·  ✱ for more  ·  Back to the channels"
    else
        hint = "Left and right change channel  ·  ✱ for Favorites and Start over  ·  Back to the channels"
    end if
    m.top.findNode("hint").text = hint
end sub

function Clock_(epoch as integer) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(epoch)
    d.ToLocalTime()
    return d.GetHours().ToStr() + ":" + Two_(d.GetMinutes())
end function

sub changeChannel(by as integer)
    n = m.channels.Count()
    if n = 0 then return
    m.index = (m.index + by + n) mod n
    tune(invalid)
end sub

' ------------------------------------------------------------------ ✱: the channel's menu

sub showMenu()
    ch = m.channels[m.index]
    m.menuIds = []
    content = CreateObject("roSGNode", "ContentNode")
    MenuRow_(content, Iif_(isFavorite(ch), "Remove from Favorites", "Add to Favorites"), "favorite")
    p = onNow()
    now = CreateObject("roDateTime").AsSeconds()
    if m.catchUp = invalid and p <> invalid and ch.archiveDays > 0 and p.start >= now - ch.archiveDays * 86400 then MenuRow_(content, "Start over", "startOver")
    if m.catchUp <> invalid then MenuRow_(content, "Go live", "live")
    m.top.findNode("menuTitle").text = ch.name
    m.menuList.content = content
    m.menu.visible = true
    m.menuList.setFocus(true)
    Trace_("live menu shown")
end sub

sub MenuRow_(content as object, title as string, id as string)
    c = content.createChild("ContentNode")
    c.title = "  " + title
    m.menuIds.Push(id)
end sub

sub hideMenu()
    Trace_("live menu hidden")
    m.menu.visible = false
    m.video.setFocus(true)
end sub

sub onMenu()
    id = m.menuIds[m.menuList.itemSelected]
    hideMenu()
    if id = "favorite" then
        toggleFavorite()
    else if id = "startOver" then
        startOver()
    else if id = "live" then
        tune(invalid)
    end if
end sub

sub toggleFavorite()
    ch = m.channels[m.index]
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
    showBanner()
end sub

' This programme from its beginning, from the channel's archive.
sub startOver()
    p = onNow()
    if p = invalid then return
    tune({ start: p.start, ends: p.ends, title: p.title })
end sub

sub finish()
    if m.leaving then return
    m.leaving = true
    Trace_("live stopped")
    m.top.findNode("ticker").control = "stop"
    m.video.control = "stop"
    m.top.done = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.menu.visible then
        if key = "back" or key = "left" then hideMenu()
        return true
    end if
    if key = "back" then
        finish()
    else if key = "options" then
        showMenu()
    else if key = "replay" then
        if m.catchUp = invalid then startOver() else m.video.seek = 0
    else if key = "left" or key = "rewind" then
        if m.catchUp <> invalid then
            m.video.seek = m.video.position - 10
            showBanner()
        else if key = "left" then
            changeChannel(-1)
        end if
    else if key = "right" or key = "fastforward" then
        if m.catchUp <> invalid then
            m.video.seek = m.video.position + 10
            showBanner()
        else if key = "right" then
            changeChannel(1)
        end if
    else if key = "play" then
        if m.catchUp <> invalid then
            if m.video.state = "paused" then m.video.control = "resume" else m.video.control = "pause"
        end if
    else if key = "up" or key = "OK" or key = "down" then
        if m.banner.visible then hideBanner() else showBanner()
    end if
    return true
end function
