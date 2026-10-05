sub init()
    m.top.functionName = "watch"
end sub

' What the scene said comes on the port, so nothing here waits on the scene: it can be
' kept while the scene is held up.
sub watch()
    port = CreateObject("roMessagePort")
    m.top.observeField("beat", port)
    m.top.observeField("crumb", port)
    store = CreateObject("roRegistrySection", "reely")
    since = CreateObject("roTimespan")
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
                if kept > 0 and during = "" then
                    during = crumb
                    keep(store, kept, before, during)
                end if
            else
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
                keep(store, kept, before, during)
            end if
        end if
    end while
end sub

sub keep(store as object, seconds as integer, before as string, during as string)
    at = during
    if at = "" then at = before
    stall = { at: CreateObject("roDateTime").AsSeconds(), seconds: seconds, crumb: at, before: before }
    store.Write("stall", FormatJson(stall))
    store.Flush()
end sub
