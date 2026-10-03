sub init()
    m.top.findNode("title").font = Bold_(56)
    m.top.findNode("note").font = Regular_(28)
    m.people = m.top.findNode("people")
    m.pad = m.top.findNode("pad")
    m.people.observeField("itemSelected", "onPicked")
    m.pad.observeField("pin", "onPin")
    m.top.observeField("focusedChild", "onFocus")
    m.asking = invalid
end sub

sub build()
    users = m.top.users
    m.top.findNode("title").text = "Who's watching?"
    content = CreateObject("roSGNode", "ContentNode")
    at = 0
    for i = 0 to users.Count() - 1
        u = users[i]
        name = u.title
        if u.protected then name = name + "  🔒"
        c = content.createChild("ContentNode")
        c.title = name
        c.description = Plex_ProfileRole(u, m.top.currentUuid)
        c.HDPOSTERURL = u.thumb
        c.addFields({ round: true })
        if u.uuid = m.top.currentUuid then at = i
    end for
    n = users.Count()
    m.people.numColumns = n
    width = n * 200 + (n - 1) * 48
    m.people.translation = [(1920 - width) / 2, 360]
    m.people.content = content
    m.people.jumpToItem = at
    m.top.findNode("note").text = "Each profile has its own libraries, watch history and Continue Watching."
end sub

sub onFocus()
    if not m.top.hasFocus() then return
    if m.asking <> invalid then m.pad.setFocus(true) else m.people.setFocus(true)
end sub

sub onPicked()
    u = m.top.users[m.people.itemSelected]
    if u.uuid = m.top.currentUuid then
        m.top.chosen = { close: true }
    else if u.protected then
        askPin(u)
    else
        m.top.chosen = { uuid: u.uuid, pin: "" }
    end if
end sub

sub askPin(u as object)
    m.asking = u
    m.people.visible = false
    m.pad.pin = ""
    m.pad.visible = true
    m.top.findNode("title").text = u.title
    m.top.findNode("note").text = "Enter the PIN for this profile"
    m.pad.setFocus(true)
    Trace_("pin for " + u.title)
end sub

sub onPin()
    if m.asking = invalid then return
    pin = m.pad.pin
    if Len(pin) = 4 then m.top.chosen = { uuid: m.asking.uuid, pin: pin }
end sub

sub showError()
    if m.top.error = "" then return
    m.top.findNode("note").text = m.top.error
    if m.asking <> invalid then m.pad.pin = ""
end sub

sub showBusy()
    if m.top.busy <> "" then m.top.findNode("note").text = "Switching to " + m.top.busy + "…"
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" then
        if m.asking <> invalid then
            ' Back from the PIN: the profiles again.
            m.asking = invalid
            m.pad.visible = false
            m.people.visible = true
            build()
            m.people.setFocus(true)
        else
            m.top.chosen = { close: true }
        end if
    end if
    ' Nothing reaches what's underneath while it's up.
    return true
end function
