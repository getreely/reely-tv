sub init()
    m.top.findNode("name").font = Semibold_(30)
    m.top.findNode("now").font = Regular_(24)
    m.top.findNode("next").font = Regular_(22)
    m.top.findNode("initials").font = Semibold_(26)
end sub

sub layout()
    w = m.top.width
    h = m.top.height
    for each id in ["back", "ring"]
        n = m.top.findNode(id)
        n.width = w
        n.height = h
    end for
    m.top.findNode("name").width = w - 520
    m.top.findNode("now").width = w - 520
    m.top.findNode("track").width = w - 520
    nxt = m.top.findNode("next")
    nxt.width = 320
    nxt.translation = [w - 336, 50]
    nxt.horizAlign = "right"
end sub

sub show()
    c = m.top.itemContent
    if c = invalid then return
    m.top.findNode("name").text = c.title
    m.top.findNode("now").text = c.description
    m.top.findNode("logo").uri = c.HDPOSTERURL
    m.top.findNode("initials").text = Iif2_(c.HDPOSTERURL = "", UCase(Left(c.ShortDescriptionLine1, 3)), "")
    p = -1.0
    if c.hasField("progress") then p = c.progress
    m.top.findNode("track").visible = p >= 0
    bar = m.top.findNode("bar")
    bar.visible = p >= 0
    if p >= 0 then bar.width = (m.top.width - 520) * p
    nxt = ""
    if c.hasField("nextText") then nxt = c.nextText
    m.top.findNode("next").text = nxt
end sub

function Iif2_(test as boolean, yes as string, no as string) as string
    if test then return yes
    return no
end function

sub focusChanged()
    m.top.findNode("ring").visible = m.top.focusPercent > 0.5
end sub
