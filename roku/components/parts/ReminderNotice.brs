sub init()
    m.top.findNode("kicker").color = Accent_()
    m.top.findNode("kicker").font = Semibold_(24)
    m.top.findNode("title").font = Bold_(36)
    m.top.findNode("channel").font = Regular_(26)
    m.actions = m.top.findNode("actions")
    m.actions.labels = ["Watch", "Dismiss"]
    m.actions.observeField("pressed", "onPressed")
    m.top.observeField("focusedChild", "onFocus")
end sub

sub show()
    r = m.top.reminder
    m.top.findNode("title").text = r.title
    m.top.findNode("channel").text = "On " + r.channel.name
end sub

sub onFocus()
    if m.top.hasFocus() then m.actions.setFocus(true)
end sub

sub onPressed()
    m.top.chosen = Iif_(m.actions.pressed = 0, "watch", "dismiss")
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" then
        m.top.chosen = "dismiss"
        return true
    end if
    return false
end function
