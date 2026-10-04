sub init()
    m.ring = m.top.findNode("ring")
    m.frame = m.top.findNode("frame")
    m.art = m.top.findNode("art")
    m.mask = m.top.findNode("mask")
    m.tick = m.top.findNode("tick")
    m.tickMark = m.top.findNode("tickMark")
    m.tickMark.font = PosterFont_("pkg:/fonts/geist_bold.ttf", 18)
    m.track = m.top.findNode("track")
    m.progress = m.top.findNode("progress")
    m.badge = m.top.findNode("badge")
    m.countLabel = m.top.findNode("count")
    m.tag = m.top.findNode("tag")
    m.tagBack = m.top.findNode("tagBack")
    m.tag.font = PosterFont_("pkg:/fonts/geist_bold.ttf", 17)
    m.title = m.top.findNode("title")
    m.sub = m.top.findNode("sub")
    m.title.font = PosterFont_("pkg:/fonts/geist_semibold.ttf", 24)
    m.sub.font = PosterFont_("pkg:/fonts/geist_regular.ttf", 20)
end sub

sub layout()
    w = m.top.width
    h = m.top.height
    if w <= 0 or h <= 0 then return
    pic = h - 64
    c = m.top.itemContent
    round = c <> invalid and c.round = true
    if round then
        ' A person: a circle the width of the slot, the name under it.
        pic = w
        m.mask.maskUri = "pkg:/images/circle.png"
        m.mask.maskSize = [w, w]
        m.frame.visible = false
    else
        m.mask.maskUri = ""
        m.frame.visible = true
    end if
    m.ring.translation = [-5, -5]
    m.ring.width = w + 10
    m.ring.height = pic + 10
    m.frame.width = w
    m.frame.height = pic
    m.art.width = w
    m.art.height = pic
    m.tagBack.translation = [8, 8]
    m.tag.translation = [8, 8]
    m.tick.translation = [w - 38, 8]
    m.tickMark.translation = [w - 38, 8]
    m.track.translation = [0, pic - 6]
    m.track.width = w
    m.track.height = 6
    m.progress.translation = [0, pic - 6]
    m.progress.height = 6
    m.badge.translation = [w - 52, 8]
    m.countLabel.translation = [w - 52, 8]
    m.title.translation = [0, pic + 8]
    m.title.width = w
    m.sub.translation = [0, pic + 34]
    m.sub.width = w
    m.title.horizAlign = Iif_(round, "center", "left")
    m.sub.horizAlign = Iif_(round, "center", "left")
    show()
end sub

sub show()
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
    m.tagBack.visible = tag <> ""
    m.tickMark.visible = watched
    count = c.badgeCount
    if count <> invalid and count > 1 then
        text = Plex_BadgeText(count)
        ' The circle stays one size; a longer number is drawn smaller, never wrapped.
        size = 26
        if Len(text) > 2 then size = 19
        m.countLabel.font = PosterFont_("pkg:/fonts/geist_bold.ttf", size)
        m.countLabel.text = text
        m.badge.visible = true
        m.countLabel.visible = true
    else
        m.badge.visible = false
        m.countLabel.visible = false
    end if
    f = c.progress
    if f <> invalid and f > 0 and m.top.width > 0 then
        m.track.visible = true
        m.progress.visible = true
        m.progress.width = m.top.width * f
    else
        m.track.visible = false
        m.progress.visible = false
    end if
end sub

sub focusChanged()
    focused = m.top.focusPercent > 0.5
    m.ring.visible = focused
    if focused then
        m.title.color = "0xF2F4F7FF"
    else
        m.title.color = "0xB3BAC4FF"
    end if
end sub

function PosterFont_(uri as string, size as integer) as object
    f = CreateObject("roSGNode", "Font")
    f.uri = uri
    f.size = size
    return f
end function

