sub init()
    m.art = m.top.findNode("art")
    m.title = m.top.findNode("title")
    m.facts = m.top.findNode("facts")
    m.summary = m.top.findNode("summary")
    m.title.font = Bold_(64)
    m.facts.font = Regular_(28)
    m.summary.font = Regular_(26)
    ' The picture waits for the cursor to settle: walking along a row doesn't fetch one for
    ' every poster passed.
    m.settle = m.top.findNode("settle")
    m.settle.observeField("fire", "showArt")
    m.artUri = ""
end sub

sub showArt()
    if m.art.uri <> m.artUri then m.art.uri = m.artUri
end sub

sub show()
    i = m.top.item
    ' The same title again, as a page is drawn again while it loads: nothing to do, and the
    ' picture isn't fetched again.
    key = ""
    if i <> invalid and i.Count() > 0 then key = Str_(i.serverBase) + "|" + Str_(i.ratingKey) + "|" + Str_(i.type)
    key = key + "|" + m.top.fallback
    if m.shown = key then return
    m.shown = key
    if i = invalid or i.Count() = 0 then
        m.artUri = ""
        m.settle.control = "stop"
        m.art.uri = ""
        m.title.text = m.top.fallback
        m.facts.text = ""
        m.summary.text = ""
        return
    end if
    art = Str_(i.art)
    if art = "" then art = Str_(i.thumb)
    m.artUri = Image_(i.serverBase, art, 960, 540)
    if m.art.uri = "" then
        showArt()
    else
        m.settle.control = "stop"
        m.settle.control = "start"
    end if
    ' An episode is introduced by its show, with its own title among the details.
    episode = i.type = "episode" and Str_(i.grandparentTitle) <> ""
    m.title.text = Iif_(episode, Str_(i.grandparentTitle), Str_(i.title))
    facts = []
    caption = Plex_Caption(i)
    if caption <> "" then facts.Push(caption)
    if episode then facts.Push(Str_(i.title))
    if Num_(i.durationMs) > 0 then facts.Push(Plex_Duration(i.durationMs))
    m.facts.text = Join_(facts, "  ·  ")
    m.summary.text = Str_(i.summary)
end sub

' Asks nothing of Plex; the screen kit it borrows pictures' addresses from expects this.
sub answered(r as object)
end sub
