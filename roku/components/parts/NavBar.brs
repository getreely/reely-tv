sub init()
    m.ids = ["home", "movies", "shows", "live", "requests", "search", "settings"]
    m.names = ["Home", "Movies", "TV Shows", "Live TV", "Requests", "Search", "Settings"]
    m.tabs = m.top.findNode("tabs")
    m.pills = []
    m.labels = []
    font = FontOf_("pkg:/fonts/geist_semibold.ttf", 30)
    x = 170
    for i = 0 to m.ids.Count() - 1
        ' Search and Settings at the right-hand end.
        if i = 5 then x = 1500
        w = Int(Len(m.names[i]) * 16 + 56)
        pill = CreateObject("roSGNode", "Poster")
        pill.uri = "pkg:/images/pill.9.png"
        pill.width = w
        pill.height = 64
        pill.translation = [x, 28]
        pill.visible = false
        label = CreateObject("roSGNode", "Label")
        label.text = m.names[i]
        label.font = font
        label.width = w
        label.height = 64
        label.horizAlign = "center"
        label.vertAlign = "center"
        label.translation = [x, 28]
        m.tabs.appendChild(pill)
        m.tabs.appendChild(label)
        m.pills.Push(pill)
        m.labels.Push(label)
        x = x + w + 10
    end for
    m.at = 0
    m.focused = false
    m.top.observeField("focusedChild", "focusChanged")
    paint()
end sub

sub takeFocus()
    ' Into the tabs on the one that's open, as on the Fire TV.
    for i = 0 to m.ids.Count() - 1
        if m.ids[i] = m.top.current then m.at = i
    end for
    m.top.setFocus(true)
end sub

sub focusChanged()
    m.focused = m.top.hasFocus()
    paint()
end sub

sub paint()
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
            m.pills[i].blendColor = "0xF5F2F0FF"
            m.labels[i].color = "0x0A0909FF"
        else if open then
            ' The open tab, while the cursor is elsewhere: outlined.
            m.pills[i].uri = "pkg:/images/pillring.9.png"
            m.pills[i].blendColor = "0xF5F2F066"
            m.labels[i].color = "0xF5F2F0FF"
        else
            m.labels[i].color = "0xB9B4B1FF"
        end if
    end for
end sub

function onKeyEvent(key as string, press as boolean) as boolean
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
        m.top.chosen = m.ids[m.at]
        return true
    else if key = "down" then
        m.top.leave = true
        return true
    end if
    return false
end function

' Moving onto a tab opens it, as the Fire TV's do; Search and Settings wait for OK.
sub arrive()
    paint()
    id = m.ids[m.at]
    if id <> "search" and id <> "settings" then m.top.chosen = id
end sub
