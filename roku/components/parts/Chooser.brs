sub init()
    m.list = m.top.findNode("list")
    m.top.findNode("heading").font = Bold_(44)
    m.items = []
    m.at = 0
end sub

sub build()
    m.top.findNode("heading").text = m.top.title
    m.list.removeChildrenIndex(m.list.getChildCount(), 0)
    m.items = []
    options = m.top.options
    if options = invalid then return
    notes = m.top.notes
    accent = m.global.accent
    if accent = invalid then accent = "0x2E6BFFFF"
    y = 0
    for i = 0 to options.Count() - 1
        note = ""
        if notes <> invalid and i < notes.Count() and notes[i] <> invalid then note = notes[i]
        h = 76
        if note <> "" then h = 76 + 34 * Lines_(note, 22, 520)
        fill = CreateObject("roSGNode", "Poster")
        fill.uri = "pkg:/images/card.9.png"
        fill.width = 632
        fill.height = h
        fill.translation = [0, y]
        fill.visible = false
        m.list.appendChild(fill)
        tick = CreateObject("roSGNode", "Poster")
        tick.uri = "pkg:/images/glyph_check.png"
        tick.width = 30
        tick.height = 30
        tick.translation = [22, y + 23]
        tick.blendColor = accent
        tick.visible = i = m.top.current
        m.list.appendChild(tick)
        label = CreateObject("roSGNode", "Label")
        label.text = options[i]
        label.font = Regular_(30)
        label.width = 540
        label.translation = [72, y + 19]
        m.list.appendChild(label)
        sub_ = invalid
        if note <> "" then
            sub_ = CreateObject("roSGNode", "Label")
            sub_.text = note
            sub_.font = Regular_(22)
            sub_.width = 520
            sub_.wrap = true
            sub_.translation = [72, y + 60]
            m.list.appendChild(sub_)
        end if
        m.items.Push({ fill: fill, tick: tick, label: label, note: sub_, top: y, height: h })
        y = y + h + 4
    end for
    m.height = y
    m.at = m.top.current
    if m.at < 0 or m.at >= m.items.Count() then m.at = 0
    paint()
end sub

' Roughly how many lines a note takes at this size and width.
function Lines_(text as string, size as integer, width as integer) as integer
    perLine = Int(width / (size * 0.5))
    if perLine < 1 then return 1
    n = Int((Len(text) + perLine - 1) / perLine)
    if n < 1 then n = 1
    return n
end function

sub paint()
    for i = 0 to m.items.Count() - 1
        it = m.items[i]
        on = i = m.at
        it.fill.visible = on
        it.fill.blendColor = "0xF2F4F7FF"
        it.label.color = Iif_(on, "0x08090BFF", "0xF2F4F7FF")
        it.label.font = Iif_(on, Semibold_(30), Regular_(30))
        if it.note <> invalid then it.note.color = Iif_(on, "0x08090BB3", "0xB3BAC4FF")
        if i = m.top.current then it.tick.blendColor = Iif_(on, "0x08090BFF", m.global.accent)
    end for
    if m.items.Count() = 0 then return
    #if DEBUG
        ' Where the cursor is, for the end-to-end test to follow.
        print "PILL "; m.top.options[m.at]
    #end if
    ' The cursor's choice in view.
    it = m.items[m.at]
    shift = 0
    if it.top + it.height > 880 then shift = it.top + it.height - 880
    most = m.height - 900
    if most < 0 then most = 0
    if shift > most then shift = most
    m.list.translation = [0, -shift]
end sub

function Iif_(test as boolean, a as dynamic, b as dynamic) as dynamic
    if test then return a
    return b
end function

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return true
    if key = "back" then
        m.top.picked = -1
    else if key = "up" and m.at > 0 then
        m.at = m.at - 1
        paint()
    else if key = "down" and m.at < m.items.Count() - 1 then
        m.at = m.at + 1
        paint()
    else if key = "OK" and m.items.Count() > 0 then
        m.top.picked = m.at
    end if
    ' Nothing reaches what's underneath while it's up.
    return true
end function
