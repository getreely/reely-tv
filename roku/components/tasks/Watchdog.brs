sub init()
    m.top.functionName = "watch"
end sub

' What the scene said comes on the port, so nothing here waits on the scene: it can be
' kept while the scene is held up.
sub watch()
    port = CreateObject("roMessagePort")
    m.top.observeField("beat", port)
    m.top.observeField("crumb", port)
    m.store = CreateObject("roRegistrySection", "reely")
    since = CreateObject("roTimespan")
    clock = CreateObject("roTimespan")
    ' The last few things the scene said, each with when, oldest first.
    m.trail = []
    crumb = ""
    ' What the scene said last before it was held up, and what it said first after: what it
    ' was doing can come only once it's done.
    before = ""
    during = ""
    kept = 0
    while true
        msg = Wait(500, port)
        held = since.TotalMilliseconds()
        if type(msg) = "roSGNodeEvent" then
            if msg.getField() = "crumb" then
                crumb = msg.getData()
                m.trail.Push({ ms: clock.TotalMilliseconds(), what: crumb })
                if m.trail.Count() > 12 then m.trail.Shift()
                if kept > 0 and during = "" then
                    during = crumb
                    keep(kept, before, during, false)
                end if
            else
                ' Going again: said so, with how long it was held up in all.
                if kept > 0 then keep(Int(held / 1000), before, during, true)
                since.Mark()
                held = 0
                kept = 0
                during = ""
            end if
        end if
        if held < 2500 then
            before = crumb
        else
            ' Kept as it goes on, each whole second: the app may be put away while held up.
            seconds = Int(held / 1000)
            if seconds > kept then
                kept = seconds
                keep(kept, before, during, false)
            end if
        end if
    end while
end sub

sub keep(seconds as integer, before as string, during as string, ended as boolean)
    at = during
    if at = "" then at = before
    trail = []
    for each c in m.trail
        trail.Push(c.what)
    end for
    stall = { at: CreateObject("roDateTime").AsSeconds(), seconds: seconds, crumb: at, before: before, trail: trail, ended: ended }
    m.store.Write("stall", FormatJson(stall))
    m.store.Flush()
end sub
