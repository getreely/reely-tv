sub init()
    m.top.findNode("keyBack").blendColor = Accent_()
    m.top.findNode("key").color = AccentOn_()
    m.steps = Tour_Steps()
    m.step = 0
    m.finished = false
    m.actions = m.top.findNode("actions")
    m.top.findNode("count").font = Regular_(24)
    m.top.findNode("title").font = Bold_(52)
    m.top.findNode("key").font = Semibold_(26)
    m.top.findNode("body").font = Regular_(30)
    m.actions.observeField("pressed", "onAction")
    m.top.observeField("focusedChild", "onFocus")
    ' Whatever else takes the cursor while the tour is up (Home arriving behind it, say),
    ' the tour takes it back: otherwise it sits on screen with nothing able to answer it.
    m.keeper = m.top.findNode("keeper")
    m.keeper.observeField("fire", "keepFocus")
    m.keeper.control = "start"
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

sub keepFocus()
    if m.finished then return
    ' Something put over the tour (a reminder) keeps the cursor while it's there.
    parent = m.top.getParent()
    if parent <> invalid and parent.getChildCount() > 0 then
        if not parent.getChild(parent.getChildCount() - 1).isSameNode(m.top) then return
    end if
    if not m.top.isInFocusChain() then
        Trace_("tour took the cursor back")
        m.actions.setFocus(true)
    end if
end sub

sub onAction()
    act(m.ids[m.actions.pressed])
end sub

sub act(id as string)
    if m.finished then return
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

' Gone from the screen at once, whatever the scene does next.
sub finish()
    if m.finished then return
    m.finished = true
    m.keeper.control = "stop"
    m.top.visible = false
    Trace_("tour done")
    m.top.done = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press or m.finished then return true
    if key = "back" then
        if m.step = 0 then
            finish()
        else
            m.step = m.step - 1
            paint()
        end if
        return true
    end if
    ' The buttons answer for themselves when they have the cursor; if a press reaches here
    ' instead, the tour answers it, so it can always be stepped through or skipped.
    i = m.actions.focusIndex
    if key = "left" and i > 0 then
        m.actions.focusIndex = i - 1
    else if key = "right" and i < m.ids.Count() - 1 then
        m.actions.focusIndex = i + 1
    else if key = "OK" and i >= 0 and i < m.ids.Count() then
        act(m.ids[i])
    end if
    if not m.finished then m.actions.setFocus(true)
    ' Nothing under the tour moves while it's up.
    return true
end function
