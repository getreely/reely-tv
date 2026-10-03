sub init()
    m.group = m.top.findNode("pills")
    m.pills = []
    m.texts = []
    m.top.observeField("focusedChild", "paint")
end sub

sub build()
    m.group.removeChildrenIndex(m.group.getChildCount(), 0)
    m.pills = []
    m.texts = []
    font = FontOf_("pkg:/fonts/geist_semibold.ttf", m.top.fontSize)
    h = Int(m.top.fontSize * 2.1)
    x = 0
    for each text in m.top.labels
        w = Int(Len(text) * m.top.fontSize * 0.56 + m.top.fontSize * 1.6)
        pill = CreateObject("roSGNode", "Poster")
        pill.width = w
        pill.height = h
        pill.translation = [x, 0]
        label = CreateObject("roSGNode", "Label")
        label.text = text
        label.font = font
        label.width = w
        label.height = h
        label.horizAlign = "center"
        label.vertAlign = "center"
        label.translation = [x, 0]
        m.group.appendChild(pill)
        m.group.appendChild(label)
        m.pills.Push(pill)
        m.texts.Push(label)
        x = x + w + 12
    end for
    m.top.rowWidth = x
    if m.top.focusIndex >= m.pills.Count() then m.top.focusIndex = 0
    paint()
end sub

sub paint()
    focused = m.top.hasFocus()
    on = m.top.on
    for i = 0 to m.pills.Count() - 1
        isOn = on <> invalid and i < on.Count() and on[i] = true
        if focused and i = m.top.focusIndex then
            m.pills[i].uri = "pkg:/images/pill.9.png"
            m.pills[i].blendColor = "0xF5F2F0FF"
            m.texts[i].color = "0x0A0909FF"
        else if isOn then
            m.pills[i].uri = "pkg:/images/pillring.9.png"
            m.pills[i].blendColor = "0xF5F2F0FF"
            m.texts[i].color = "0xF5F2F0FF"
        else
            m.pills[i].uri = "pkg:/images/pill.9.png"
            m.pills[i].blendColor = "0x221E1FFF"
            m.texts[i].color = "0xF5F2F0FF"
        end if
    end for
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "left" and m.top.focusIndex > 0 then
        m.top.focusIndex = m.top.focusIndex - 1
        return true
    else if key = "right" and m.top.focusIndex < m.pills.Count() - 1 then
        m.top.focusIndex = m.top.focusIndex + 1
        return true
    else if key = "OK" and m.pills.Count() > 0 then
        m.top.pressed = m.top.focusIndex
        return true
    end if
    return false
end function
