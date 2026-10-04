sub init()
    m.top.findNode("keyBack").blendColor = Accent_()
    m.top.findNode("key").color = AccentOn_()
    m.steps = Tour_Steps()
    m.step = 0
    m.actions = m.top.findNode("actions")
    m.top.findNode("count").font = Regular_(24)
    m.top.findNode("title").font = Bold_(52)
    m.top.findNode("key").font = Semibold_(26)
    m.top.findNode("body").font = Regular_(30)
    m.actions.observeField("pressed", "onAction")
    m.top.observeField("focusedChild", "onFocus")
    paint()
end sub

sub paint()
    s = m.steps[m.step]
    last = m.step = m.steps.Count() - 1
    m.top.findNode("count").text = (m.step + 1).ToStr() + " of " + m.steps.Count().ToStr()
    m.top.findNode("title").text = s.title
    m.top.findNode("body").text = s.body
    key = m.top.findNode("key")
    keyBack = m.top.findNode("keyBack")
    key.text = s.key
    key.visible = s.key <> ""
    keyBack.visible = s.key <> ""
    ' The words up under the title when there's no button to show.
    m.top.findNode("body").translation = Iif_(s.key <> "", [540, 530], [540, 450])
    ' The pill as wide as what's on it.
    width = 56 + Len(s.key) * 18
    key.width = width
    keyBack.width = width
    labels = [Iif_(last, "Done", "Next")]
    m.ids = ["next"]
    if m.step > 0 then
        labels.Push("Back")
        m.ids.Push("back")
    end if
    if not last then
        labels.Push("Skip tour")
        m.ids.Push("skip")
    end if
    m.actions.labels = labels
    m.actions.focusIndex = 0
    Trace_("tour step " + (m.step + 1).ToStr())
end sub

sub onFocus()
    if m.top.hasFocus() then m.actions.setFocus(true)
end sub

sub onAction()
    id = m.ids[m.actions.pressed]
    if id = "next" then
        if m.step = m.steps.Count() - 1 then
            finish()
        else
            m.step = m.step + 1
            paint()
        end if
    else if id = "back" then
        m.step = m.step - 1
        paint()
    else
        finish()
    end if
end sub

sub finish()
    Trace_("tour done")
    m.top.done = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" then
        if m.step = 0 then
            finish()
        else
            m.step = m.step - 1
            paint()
        end if
        return true
    end if
    ' Nothing under the tour moves while it's up.
    return key <> "left" and key <> "right" and key <> "OK"
end function
