sub init()
    m.list = m.top.findNode("list")
    m.top.findNode("title").font = Bold_(40)
    m.top.findNode("sub").font = Regular_(26)
    m.rows = []
    m.at = 0
end sub

' Each choice's icon, as the Fire TV's menu has them.
function Icon_(id as string) as string
    if id = "restart" then return "restart"
    if id = "watched" or id = "unwatched" then return "check"
    if id = "details" then return "info"
    if id = "remove" then return "cross"
    return "play"
end function

sub build()
    i = m.top.item
    m.top.findNode("title").text = Plex_RowTitle(i)
    m.top.findNode("sub").text = Iif_(i.type = "episode", i.title, Plex_Caption(i))
    art = i.art
    if art = "" then art = i.thumb
    m.top.findNode("art").uri = Image_(i.serverBase, art, 960, 540)
    resumable = Plex_ResumeFraction(i) <> invalid and not Plex_IsWatched(i)
    m.ids = []
    labels = []
    if (i.type = "show" or i.type = "season") and i.leafCount > 0 then
        labels.Push(Iif_(i.viewedLeafCount = 0, "Play first episode", Iif_(Plex_IsWatched(i), "Play from the start", "Play next episode")))
        m.ids.Push("next")
    end if
    if i.type = "movie" or i.type = "episode" then
        labels.Push(Iif_(resumable, "Resume", "Play"))
        m.ids.Push("play")
        if resumable then
            labels.Push("Play from the beginning")
            m.ids.Push("restart")
        end if
    end if
    labels.Push(Iif_(Plex_IsWatched(i), "Mark as unwatched", "Mark as watched"))
    m.ids.Push(Iif_(Plex_IsWatched(i), "unwatched", "watched"))
    labels.Push(Iif_(i.type = "episode", "Go to " + Iif_(i.grandparentTitle <> "", i.grandparentTitle, "the show"), "Details"))
    m.ids.Push("details")
    if m.top.inContinueWatching then
        labels.Push("Remove from Continue Watching")
        m.ids.Push("remove")
    end if
    m.list.removeChildrenIndex(m.list.getChildCount(), 0)
    m.rows = []
    for k = 0 to labels.Count() - 1
        y = k * 70
        fill = CreateObject("roSGNode", "Poster")
        fill.uri = "pkg:/images/card.9.png"
        fill.width = 532
        fill.height = 64
        fill.translation = [0, y]
        icon = CreateObject("roSGNode", "Poster")
        icon.uri = "pkg:/images/glyph_" + Icon_(m.ids[k]) + ".png"
        icon.width = 28
        icon.height = 28
        icon.translation = [22, y + 18]
        label = CreateObject("roSGNode", "Label")
        label.text = labels[k]
        label.width = 450
        label.height = 64
        label.vertAlign = "center"
        label.translation = [68, y]
        m.list.appendChild(fill)
        m.list.appendChild(icon)
        m.list.appendChild(label)
        m.rows.Push({ fill: fill, icon: icon, label: label })
    end for
    m.at = 0
    paint()
end sub

sub paint()
    for k = 0 to m.rows.Count() - 1
        on = k = m.at
        r = m.rows[k]
        r.fill.visible = on
        r.fill.blendColor = "0xF2F4F7FF"
        r.icon.blendColor = Iif_(on, "0x08090BFF", "0xB3BAC4FF")
        r.label.color = Iif_(on, "0x08090BFF", "0xF2F4F7FF")
        r.label.font = Iif_(on, Semibold_(30), Regular_(30))
    end for
    #if DEBUG
        if m.at < m.rows.Count() then print "PILL "; m.rows[m.at].label.text
    #end if
end sub

sub answered(r as object)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return true
    if key = "back" or key = "options" or key = "left" then
        m.top.chosen = ""
    else if key = "up" and m.at > 0 then
        m.at = m.at - 1
        paint()
    else if key = "down" and m.at < m.rows.Count() - 1 then
        m.at = m.at + 1
        paint()
    else if key = "OK" and m.rows.Count() > 0 then
        m.top.chosen = m.ids[m.at]
    end if
    ' Nothing reaches what's underneath while it's up.
    return true
end function
