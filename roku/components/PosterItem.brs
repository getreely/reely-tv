sub init()
    m.ring = m.top.findNode("ring")
    m.ringRound = m.top.findNode("ringRound")
    m.frame = m.top.findNode("frame")
    m.art = m.top.findNode("art")
    m.mask = m.top.findNode("mask")
    m.tick = m.top.findNode("tick")
    m.tickMark = m.top.findNode("tickMark")
    m.tickMark.font = FontOf_("pkg:/fonts/geist_bold.ttf", 18)
    m.track = m.top.findNode("track")
    m.progress = m.top.findNode("progress")
    m.badge = m.top.findNode("badge")
    m.countLabel = m.top.findNode("count")
    m.tag = m.top.findNode("tag")
    m.tagBack = m.top.findNode("tagBack")
    m.tag.font = FontOf_("pkg:/fonts/geist_bold.ttf", 17)
    m.title = m.top.findNode("title")
    m.sub = m.top.findNode("sub")
    m.title.font = FontOf_("pkg:/fonts/geist_semibold.ttf", 24)
    m.sub.font = FontOf_("pkg:/fonts/geist_regular.ttf", 20)
    m.focused = false
end sub

sub layout()
    try
        layout__()
    catch e
        Oops_("poster", e)
    end try
end sub

sub layout__()
    w = m.top.width
    h = m.top.height
    if w <= 0 or h <= 0 then return
    pic = h - 64
    c = m.top.itemContent
    round = c <> invalid and c.round = true
    m.round = round
    ' The cursor's ring goes round the picture inside the slot, the picture drawn that much
    ' in from its edges: a list draws nothing of an item outside the slot, and a ring
    ' outside it lost its top, and its left at the start of a row.
    pad = RING_()
    if round then pic = w
    inner = w - pad * 2
    innerH = pic - pad * 2
    m.picW = inner
    if round then
        ' A person: a circle the width of the slot, the name under it.
        m.mask.maskUri = "pkg:/images/circle.png"
        m.mask.maskSize = [inner, inner]
        m.frame.visible = false
    else
        m.mask.maskUri = ""
        m.frame.visible = true
    end if
    m.ring.width = w
    m.ring.height = pic
    m.ringRound.width = w
    m.ringRound.height = w
    m.frame.translation = [pad, pad]
    m.frame.width = inner
    m.frame.height = innerH
    m.mask.translation = [pad, pad]
    m.art.width = inner
    m.art.height = innerH
    m.tagBack.translation = [pad + 8, pad + 8]
    m.tag.translation = [pad + 8, pad + 8]
    m.tick.translation = [w - pad - 38, pad + 8]
    m.tickMark.translation = [w - pad - 38, pad + 8]
    m.track.translation = [pad, pic - pad - 6]
    m.track.width = inner
    m.track.height = 6
    m.progress.translation = [pad, pic - pad - 6]
    m.progress.height = 6
    m.badge.translation = [w - pad - 52, pad + 8]
    m.countLabel.translation = [w - pad - 52, pad + 8]
    m.title.translation = [0, pic + 8]
    m.title.width = w
    m.sub.translation = [0, pic + 34]
    m.sub.width = w
    m.title.horizAlign = Iif_(round, "center", "left")
    m.sub.horizAlign = Iif_(round, "center", "left")
    show()
end sub

sub show()
    try
        show__()
    catch e
        Oops_("poster", e)
    end try
end sub

sub show__()
    c = m.top.itemContent
    if c = invalid then return
    m.art.uri = c.HDPOSTERURL
    m.title.text = c.title
    m.sub.text = c.description
    watched = c.watched = true and (c.badgeCount = invalid or c.badgeCount <= 1)
    m.tick.visible = watched
    tag = ""
    if c.tag <> invalid then tag = c.tag
    m.tag.text = tag
    m.tag.visible = tag <> ""
    m.tagBack.color = Accent_()
    m.tag.color = AccentOn_()
    m.progress.color = Accent_()
    m.tagBack.visible = tag <> ""
    m.tickMark.visible = watched
    count = c.badgeCount
    if count <> invalid and count > 1 then
        text = Plex_BadgeText(count)
        ' The circle stays one size; a longer number is drawn smaller, never wrapped.
        size = 26
        if Len(text) > 2 then size = 19
        m.countLabel.font = FontOf_("pkg:/fonts/geist_bold.ttf", size)
        m.countLabel.text = text
        m.badge.visible = true
        m.countLabel.visible = true
    else
        m.badge.visible = false
        m.countLabel.visible = false
    end if
    f = c.progress
    if f <> invalid and f > 0 and m.picW <> invalid and m.picW > 0 then
        m.track.visible = true
        m.progress.visible = true
        m.progress.width = m.picW * f
    else
        m.track.visible = false
        m.progress.visible = false
    end if
end sub

sub focusChanged()
    try
        focusChanged__()
    catch e
        Oops_("poster: focus", e)
    end try
end sub

sub focusChanged__()
    focused = m.top.focusPercent > 0.5 and (m.top.rowListHasFocus or m.top.gridHasFocus)
    ' Told many times a second while the cursor glides: only a change is drawn.
    if m.focused = focused then return
    m.focused = focused
    m.ring.visible = focused and m.round <> true
    m.ringRound.visible = focused and m.round = true
    if focused then
        m.title.color = "0xF2F4F7FF"
    else
        m.title.color = "0xB3BAC4FF"
    end if
end sub

' How thick the cursor's ring is, inside the slot.
function RING_() as integer
    return 5
end function
