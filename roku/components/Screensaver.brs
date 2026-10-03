sub init()
    m.top.backgroundColor = "0x08090BFF"
    m.top.backgroundUri = ""
    m.top.findNode("title").font = Bold_(52)
    m.top.findNode("caption").font = Regular_(30)
    m.top.findNode("clock").font = Semibold_(44)
    m.posters = [m.top.findNode("a"), m.top.findNode("b")]
    m.showing = -1
    m.index = -1
    m.slides = []
    store = CreateObject("roRegistrySection", "reely")
    prefs = ParseJsonOr_(store.Read("prefs"), {})
    ' Off in Settings: the time alone, on black.
    on = true
    if type(prefs) = "roAssociativeArray" and prefs.screensaver <> invalid then on = Bool_(prefs.screensaver)
    if on then m.slides = Arr_(ParseJsonOr_(store.Read("saverSlides"), []))
    Trace_("saver slides " + m.slides.Count().ToStr())
    m.next = m.top.findNode("next")
    m.next.duration = Saver_SlideSeconds()
    m.next.observeField("fire", "advance")
    m.top.findNode("tick").observeField("fire", "paintClock")
    m.top.findNode("tick").control = "start"
    paintClock()
    advance()
    if m.slides.Count() > 1 then m.next.control = "start"
end sub

function ParseJsonOr_(raw as string, fallback as dynamic) as dynamic
    if raw = "" then return fallback
    v = ParseJson(raw)
    if v = invalid then return fallback
    return v
end function

' The next picture faded in over the last, drifting a little while it's up.
sub advance()
    if m.slides.Count() = 0 then return
    m.index = (m.index + 1) mod m.slides.Count()
    s = m.slides[m.index]
    incoming = (m.showing + 1) mod 2
    outgoing = m.showing
    p = m.posters[incoming]
    p.uri = Str_(s.u)
    p.translation = [0, 0]
    m.top.findNode("title").text = Str_(s.t)
    m.top.findNode("caption").text = Str_(s.c)
    m.top.findNode("fadeIn").fieldToInterp = p.id + ".opacity"
    if outgoing >= 0 then
        m.top.findNode("fadeOut").fieldToInterp = m.posters[outgoing].id + ".opacity"
    else
        m.top.findNode("fadeOut").fieldToInterp = ""
    end if
    m.top.findNode("fade").control = "start"
    drift = m.top.findNode("drift")
    drift.duration = Saver_SlideSeconds() + 2
    m.top.findNode("driftAt").fieldToInterp = p.id + ".translation"
    drift.control = "start"
    m.showing = incoming
    Trace_("saver slide " + Str_(s.t))
end sub

' The time, as the tabs show it.
sub paintClock()
    d = CreateObject("roDateTime")
    d.ToLocalTime()
    minutes = d.GetMinutes().ToStr()
    if Len(minutes) < 2 then minutes = "0" + minutes
    m.top.findNode("clock").text = d.GetHours().ToStr() + ":" + minutes
end sub
