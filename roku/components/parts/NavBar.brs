sub init()
    m.ids = ["search", "home", "movies", "shows", "live", "requests", "settings"]
    m.names = ["Search", "Home", "Movies", "TV Shows", "Live TV", "Request", "Settings"]
    ' Search and Settings as the Fire TV draws them: a glass and a gear, no words.
    m.icons = { search: "pkg:/images/search.png", settings: "pkg:/images/gear.png" }
    m.tabs = m.top.findNode("tabs")
    m.pills = []
    m.labels = []
    font = FontOf_("pkg:/fonts/geist_semibold.ttf", 30)
    m.top.findNode("clock").font = FontOf_("pkg:/fonts/geist_regular.ttf", 28)
    x = 170
    for i = 0 to m.ids.Count() - 1
        id = m.ids[i]
        icon = m.icons[id]
        w = Int(Len(m.names[i]) * 16 + 56)
        if icon <> invalid then w = 64
        ' The gear at the right-hand end, before the time.
        if id = "settings" then x = 1620
        pill = CreateObject("roSGNode", "Poster")
        pill.uri = "pkg:/images/pill.9.png"
        pill.width = w
        pill.height = 64
        pill.translation = [x, 28]
        pill.visible = false
        m.tabs.appendChild(pill)
        if icon <> invalid then
            label = CreateObject("roSGNode", "Poster")
            label.uri = icon
            label.width = 36
            label.height = 36
            label.translation = [x + 14, 42]
        else
            label = CreateObject("roSGNode", "Label")
            label.text = m.names[i]
            label.font = font
            label.width = w
            label.height = 64
            label.horizAlign = "center"
            label.vertAlign = "center"
            label.translation = [x, 28]
        end if
        m.tabs.appendChild(label)
        m.pills.Push(pill)
        m.labels.Push(label)
        x = x + w + 10
    end for
    m.at = 1
    m.focused = false
    m.pending = ""
    m.settle = m.top.findNode("settle")
    m.settle.observeField("fire", "openPending")
    m.top.observeField("focusedChild", "focusChanged")
    timer = m.top.findNode("clockTimer")
    timer.observeField("fire", "tick")
    timer.control = "start"
    tick()
    paint()
end sub

' The time at the far right, quietly, as on the Fire TV.
sub tick()
    d = CreateObject("roDateTime")
    d.ToLocalTime()
    minutes = d.GetMinutes()
    m.top.findNode("clock").text = d.GetHours().ToStr() + ":" + Iif3_(minutes < 10, "0", "") + minutes.ToStr()
end sub

function Iif3_(test as boolean, yes as string, no as string) as string
    if test then return yes
    return no
end function

sub takeFocus()
    Crumb_("tabs: cursor in")
    try
        takeFocus__()
    catch e
        Oops_("tabs: cursor in", e)
    end try
end sub

sub takeFocus__()
    ' Into the tabs on the one that's open, as on the Fire TV.
    for i = 0 to m.ids.Count() - 1
        if m.ids[i] = m.top.current then m.at = i
    end for
    m.top.setFocus(true)
    m.focused = true
    paint()
end sub

sub focusChanged()
    try
        focusChanged__()
    catch e
        Oops_("tabs: focus", e)
    end try
end sub

sub focusChanged__()
    focused = m.top.hasFocus()
    if focused = m.focused then return
    m.focused = focused
    paint()
end sub

sub paint()
    try
        paint__()
    catch e
        Oops_("tabs: draw", e)
    end try
end sub

sub paint__()
    for i = 0 to m.ids.Count() - 1
        open = m.ids[i] = m.top.current
        here = m.focused and i = m.at
        #if DEBUG
            ' Where the cursor is, for the end-to-end test to follow.
            if here then print "TAB "; m.ids[i]
        #end if
        m.pills[i].visible = here or open
        if here then
            m.pills[i].uri = "pkg:/images/pill.9.png"
            m.pills[i].blendColor = "0xF2F4F7FF"
            Tint_(m.labels[i], "0x08090BFF")
        else if open then
            ' The open tab, while the cursor is elsewhere: outlined.
            m.pills[i].uri = "pkg:/images/pillring.9.png"
            m.pills[i].blendColor = "0xF2F4F766"
            Tint_(m.labels[i], "0xF2F4F7FF")
        else
            Tint_(m.labels[i], "0xB3BAC4FF")
        end if
    end for
end sub

' A word's colour, or an icon's tint.
sub Tint_(node as object, color as string)
    if node.subtype() = "Poster" then node.blendColor = color else node.color = color
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press then Crumb_("tabs " + key)
    try
        return onKeyEvent__(key, press)
    catch e
        Oops_("tabs " + key, e)
    end try
    return true
end function

function onKeyEvent__(key as string, press as boolean) as boolean
    if not press then return false
    if key = "left" and m.at > 0 then
        m.at = m.at - 1
        arrive()
        return true
    else if key = "right" and m.at < m.ids.Count() - 1 then
        m.at = m.at + 1
        arrive()
        return true
    else if key = "OK" then
        m.pending = ""
        m.settle.control = "stop"
        m.top.chosen = m.ids[m.at]
        return true
    else if key = "down" then
        ' Down before the tab moved onto has opened: it opens now, and the cursor goes in.
        openPending()
        m.top.leave = true
        return true
    end if
    return false
end function

' Moving onto a tab opens it, as the Fire TV's do; Search and Settings wait for OK. It opens
' once the cursor rests there: making a page holds up the remote for a moment on a Roku TV,
' and walking along the tabs made every page passed.
sub arrive()
    paint()
    id = m.ids[m.at]
    m.settle.control = "stop"
    if id = "search" or id = "settings" or id = m.top.current then
        m.pending = ""
    else
        m.pending = id
        m.settle.control = "start"
    end if
end sub

sub openPending()
    Crumb_("tabs: open")
    try
        openPending__()
    catch e
        Oops_("tabs: open", e)
    end try
end sub

sub openPending__()
    m.settle.control = "stop"
    id = m.pending
    m.pending = ""
    if id <> "" and id <> m.top.current then m.top.chosen = id
end sub

sub paintMark()
    if m.top.accent <> "" then m.top.findNode("mark").blendColor = m.top.accent
end sub
