' ------------------------------------------------------------------ Listening to the scene

' Listens to one of the scene's shared fields for as long as this page is on screen. A page
' that's been taken away (another tab, another profile) used to go on listening, and on
' drawing itself for nobody: after a few profile switches Home was drawn six times over at
' each change, fetching its pictures each time, until the Roku slowed. The scene sets the
' page's "gone" when it takes it away, and the page stops listening then.
sub Listen_(field as string, handler as string)
    if m.listens_ = invalid then
        m.listens_ = []
        if not m.top.hasField("gone") then m.top.addField("gone", "boolean", false)
        m.top.observeField("gone", "Release_")
    end if
    m.listens_.Push(field)
    m.global.observeFieldScoped(field, handler)
end sub

sub Release_()
    if m.top.gone <> true then return
    for each field in Arr_(m.listens_)
        m.global.unobserveFieldScoped(field)
    end for
    m.listens_ = []
end sub

' True once this page has been taken off the screen: nothing more to draw.
function Gone_() as boolean
    return m.top.gone = true
end function
