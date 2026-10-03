sub init()
    m.list = m.top.findNode("list")
    m.top.findNode("heading").font = Bold_(44)
    m.list.font = Regular_(30)
    m.list.focusedFont = Semibold_(30)
    m.list.observeField("itemSelected", "onPicked")
    m.top.observeField("focusedChild", "onFocus")
end sub

sub build()
    m.top.findNode("heading").text = m.top.title
    content = CreateObject("roSGNode", "ContentNode")
    for each o in m.top.options
        c = content.createChild("ContentNode")
        c.title = o
    end for
    m.list.content = content
    if m.top.current >= 0 then m.list.jumpToItem = m.top.current
end sub

sub onFocus()
    if m.top.hasFocus() then m.list.setFocus(true)
end sub

sub onPicked()
    m.top.picked = m.list.itemSelected
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" then
        m.top.picked = -1
        return true
    end if
    ' Nothing reaches what's underneath while it's up.
    return true
end function
