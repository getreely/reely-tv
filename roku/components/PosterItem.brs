sub init()
    m.ring = m.top.findNode("ring")
    m.frame = m.top.findNode("frame")
    m.art = m.top.findNode("art")
    m.track = m.top.findNode("track")
    m.progress = m.top.findNode("progress")
    m.badge = m.top.findNode("badge")
    m.countLabel = m.top.findNode("count")
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
    m.ring.translation = [-5, -5]
    m.ring.width = w + 10
    m.ring.height = pic + 10
    m.frame.width = w
    m.frame.height = pic
    m.art.width = w
    m.art.height = pic
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
    show()
end sub

sub show()
    c = m.top.itemContent
    if c = invalid then return
    m.art.uri = c.HDPOSTERURL
    m.title.text = c.title
    m.sub.text = c.description
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
        m.title.color = "0xF5F2F0FF"
    else
        m.title.color = "0xB9B4B1FF"
    end if
end sub

function PosterFont_(uri as string, size as integer) as object
    f = CreateObject("roSGNode", "Font")
    f.uri = uri
    f.size = size
    return f
end function
