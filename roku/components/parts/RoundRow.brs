sub init()
    m.group = m.top.findNode("buttons")
    m.discs = []
    m.rings = []
    m.icons = []
    m.names = []
    m.top.observeField("focusedChild", "paint")
end sub

sub build()
    m.group.removeChildrenIndex(m.group.getChildCount(), 0)
    m.discs = []
    m.rings = []
    m.icons = []
    m.names = []
    labels = Arr_(m.top.labels)
    glyphs = Arr_(m.top.glyphs)
    font = Regular_(26)
    x = 0
    for i = 0 to labels.Count() - 1
        wide = i = 0 and m.top.wideFirst
        disc = CreateObject("roSGNode", "Poster")
        disc.uri = "pkg:/images/circle.png"
        disc.width = 88
        disc.height = 88
        disc.translation = [x + 26, 0]
        if wide then
            ' Play, wide: the symbol and what it plays, in a pill as tall as the circles.
            disc.uri = "pkg:/images/pill.9.png"
            disc.width = Len(labels[i]) * 17 + 120
            disc.translation = [x + 26, 0]
        end if
        ring = CreateObject("roSGNode", "Poster")
        ring.uri = "pkg:/images/circlering.png"
        ring.width = 88
        ring.height = 88
        ring.translation = [x + 26, 0]
        icon = CreateObject("roSGNode", "Poster")
        glyph = "play"
        if i < glyphs.Count() then glyph = glyphs[i]
        icon.uri = "pkg:/images/glyph_" + glyph + ".png"
        icon.width = 40
        icon.height = 40
        icon.translation = [x + 50, 24]
        name = CreateObject("roSGNode", "Label")
        name.text = labels[i]
        name.font = font
        name.width = 140
        name.horizAlign = "center"
        name.translation = [x, 102]
        if wide then
            icon.width = 32
            icon.height = 32
            icon.translation = [x + 60, 28]
            ring.visible = false
            name.font = Semibold_(32)
            name.width = disc.width - 90
            name.height = 88
            name.horizAlign = "left"
            name.vertAlign = "center"
            name.translation = [x + 106, 0]
        end if
        for each n in [disc, ring, icon, name]
            m.group.appendChild(n)
        end for
        m.discs.Push(disc)
        m.rings.Push(ring)
        m.icons.Push(icon)
        m.names.Push(name)
        x = x + 150
        if wide then x = x + disc.width - 88 + 20
    end for
    if m.top.focusIndex >= labels.Count() then m.top.focusIndex = 0
    paint()
end sub

sub paint()
    try
        paint__()
    catch e
        Oops_("buttons: draw", e)
    end try
end sub

sub paint__()
    focused = m.top.hasFocus()
    for i = 0 to m.discs.Count() - 1
        here = focused and i = m.top.focusIndex
        wide = i = 0 and m.top.wideFirst
        if here then
            #if DEBUG
                ' Where the cursor is, for the end-to-end test to follow, as PillRow says it.
                print "PILL "; m.top.labels[i]
            #end if
            m.discs[i].blendColor = "0xF2F4F7FF"
            m.icons[i].blendColor = "0x08090BFF"
            m.rings[i].visible = false
            m.names[i].color = Iif_(wide, "0x08090BFF", "0xF2F4F7FF")
        else if i = 0 then
            ' The first, Play or Resume, in the accent.
            m.discs[i].blendColor = Accent_()
            m.icons[i].blendColor = AccentOn_()
            m.rings[i].visible = false
            m.names[i].color = Iif_(wide, AccentOn_(), "0xB3BAC4FF")
        else
            m.discs[i].blendColor = "0x15171BE6"
            m.icons[i].blendColor = "0xF2F4F7FF"
            m.rings[i].visible = true
            m.rings[i].blendColor = "0x2B3038FF"
            m.names[i].color = "0xB3BAC4FF"
        end if
    end for
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "left" and m.top.focusIndex > 0 then
        m.top.focusIndex = m.top.focusIndex - 1
        return true
    else if key = "right" and m.top.focusIndex < m.discs.Count() - 1 then
        m.top.focusIndex = m.top.focusIndex + 1
        return true
    else if key = "OK" and m.discs.Count() > 0 then
        m.top.pressed = m.top.focusIndex
        return true
    end if
    return false
end function
