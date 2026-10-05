sub init()
    m.top.findNode("keyBack").blendColor = Accent_()
    m.top.findNode("key").color = AccentOn_()
    m.steps = Tour_Steps()
    m.step = 0
    m.at = 0
    m.finished = false
    m.buttons = m.top.findNode("buttons")
    m.top.findNode("count").font = Regular_(24)
    m.top.findNode("title").font = Bold_(52)
    m.top.findNode("key").font = Semibold_(26)
    m.top.findNode("body").font = Regular_(30)
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
    m.labels = [Iif_(last, "Done", "Next")]
    m.ids = ["next"]
    if m.step > 0 then
        m.labels.Push("Back")
        m.ids.Push("back")
    end if
    if not last then
        m.labels.Push("Skip tour")
        m.ids.Push("skip")
    end if
    m.at = 0
    drawButtons()
    Trace_("tour step " + (m.step + 1).ToStr())
end sub

sub drawButtons()
    m.buttons.removeChildrenIndex(m.buttons.getChildCount(), 0)
    m.fills = []
    m.texts = []
    font = Semibold_(26)
    x = 0
    for each text in m.labels
        w = Int(Len(text) * 26 * 0.56 + 42)
        fill = CreateObject("roSGNode", "Poster")
        fill.uri = "pkg:/images/pill.9.png"
        fill.width = w
        fill.height = 56
        fill.translation = [x, 0]
        label = CreateObject("roSGNode", "Label")
        label.text = text
        label.font = font
        label.width = w
        label.height = 56
        label.horizAlign = "center"
        label.vertAlign = "center"
        label.translation = [x, 0]
        m.buttons.appendChild(fill)
        m.buttons.appendChild(label)
        m.fills.Push(fill)
        m.texts.Push(label)
        x = x + w + 12
    end for
    paintButtons()
end sub

sub paintButtons()
    for i = 0 to m.fills.Count() - 1
        on = i = m.at
        m.fills[i].blendColor = Iif_(on, "0xF2F4F7FF", "0x1F2329FF")
        m.texts[i].color = Iif_(on, "0x08090BFF", "0xF2F4F7FF")
    end for
    #if DEBUG
        ' Where the cursor is, for the end-to-end test to follow.
        print "PILL "; m.labels[m.at]
    #end if
end sub

sub keepFocus()
    if m.finished then return
    ' Something put over the tour (a reminder) keeps the cursor while it's there.
    parent = m.top.getParent()
    if parent <> invalid and parent.getChildCount() > 0 then
        if not parent.getChild(parent.getChildCount() - 1).isSameNode(m.top) then return
    end if
    if not m.top.hasFocus() then
        Trace_("tour took the cursor back")
        m.top.setFocus(true)
    end if
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

' Every key is the tour's while it's up: along the buttons, OK on one, Back a step.
function onKeyEvent(key as string, press as boolean) as boolean
    if press then Crumb_("tour " + key)
    if not press or m.finished then return true
    if key = "back" then
        if m.step = 0 then
            finish()
        else
            m.step = m.step - 1
            paint()
        end if
    else if key = "left" and m.at > 0 then
        m.at = m.at - 1
        paintButtons()
    else if key = "right" and m.at < m.ids.Count() - 1 then
        m.at = m.at + 1
        paintButtons()
    else if key = "OK" or key = "play" then
        act(m.ids[m.at])
    end if
    ' Nothing under the tour moves while it's up.
    return true
end function
