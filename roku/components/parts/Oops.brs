' ------------------------------------------------------------------ When something goes wrong

' A mistake in the app, caught: kept on this Roku for Settings to show, and said once at the
' next start. Uncaught, a sideloaded Roku app stops where it is (it waits in the debugger)
' with the remote's presses piling up behind it.
sub Oops_(what as string, e as object)
    lines = []
    message = ""
    if e <> invalid then
        if type(e.message) = "String" or type(e.message) = "roString" then message = e.message
        if type(e.backtrace) = "roArray" then
            for each f in e.backtrace
                if lines.Count() < 6 and type(f) = "roAssociativeArray" then
                    at = ""
                    if type(f.filename) = "String" or type(f.filename) = "roString" then at = f.filename
                    if f.line_number <> invalid then at = at + ":" + f.line_number.ToStr()
                    lines.Push(at)
                end if
            end for
        end if
    end if
    p = { at: CreateObject("roDateTime").AsSeconds(), what: what, message: message, lines: lines }
    store = CreateObject("roRegistrySection", "reely")
    store.Write("problem", FormatJson(p))
    store.Flush()
    #if DEBUG
        print "OOPS "; what; ": "; message
    #end if
end sub

' What the app is doing, for the watchdog to say if the app is held up while doing it.
sub Crumb_(what as string)
    if m.global = invalid then return
    dog = m.global.watchdog
    if dog <> invalid then dog.crumb = what
end sub
