sub init()
    m.list = m.top.findNode("list")
    m.top.findNode("title").font = Bold_(40)
    m.top.findNode("sub").font = Regular_(26)
    m.list.font = Regular_(30)
    m.list.focusedFont = Semibold_(30)
    m.list.observeField("itemSelected", "onPicked")
    m.top.observeField("focusedChild", "onFocus")
end sub

sub build()
    i = m.top.item
    m.top.findNode("title").text = Plex_RowTitle(i)
    m.top.findNode("sub").text = Iif_(i.type = "episode", i.title, Plex_Caption(i))
    art = i.art
    if art = "" then art = i.thumb
    m.top.findNode("art").uri = Image_(i.serverBase, art, 960, 540)
    resumable = Plex_ResumeFraction(i) <> invalid and not Plex_IsWatched(i)
    m.ids = []
    labels = []
    if (i.type = "show" or i.type = "season") and i.leafCount > 0 then
        labels.Push(Iif_(i.viewedLeafCount = 0, "Play first episode", Iif_(Plex_IsWatched(i), "Play from the start", "Play next episode")))
        m.ids.Push("next")
    end if
    if i.type = "movie" or i.type = "episode" then
        labels.Push(Iif_(resumable, "Resume", "Play"))
        m.ids.Push("play")
        if resumable then
            labels.Push("Play from the beginning")
            m.ids.Push("restart")
        end if
    end if
    labels.Push(Iif_(Plex_IsWatched(i), "Mark as unwatched", "Mark as watched"))
    m.ids.Push(Iif_(Plex_IsWatched(i), "unwatched", "watched"))
    labels.Push(Iif_(i.type = "episode", "Go to " + Iif_(i.grandparentTitle <> "", i.grandparentTitle, "the show"), "Details"))
    m.ids.Push("details")
    if m.top.inContinueWatching then
        labels.Push("Remove from Continue Watching")
        m.ids.Push("remove")
    end if
    content = CreateObject("roSGNode", "ContentNode")
    for each l in labels
        c = content.createChild("ContentNode")
        c.title = l
    end for
    m.list.content = content
end sub

sub onFocus()
    if m.top.hasFocus() then m.list.setFocus(true)
end sub

sub onPicked()
    m.top.chosen = m.ids[m.list.itemSelected]
end sub

sub answered(r as object)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and (key = "back" or key = "options" or key = "left") then
        m.top.chosen = ""
        return true
    end if
    return true
end function
