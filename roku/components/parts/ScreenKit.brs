' What every screen leans on: the session, asking Plex, posters in rows.

' The Plex session: the account, the server used first, and every library.
function Session_() as object
    ' Held while rows are made: each read of the scene's copy is a copy of all of it.
    if m.sessionHeld_ <> invalid then return m.sessionHeld_
    s = m.global.session
    if s = invalid or s.base = invalid then return { token: "", base: "", serverToken: "", libraries: [], servers: [], user: invalid, homeUsers: [] }
    return s
end function

' The token for the server at [base].
function TokenFor_(base as dynamic) as string
    s = Session_()
    if base = invalid or base = "" or base = s.base then return s.serverToken
    for each l in s.libraries
        if l.base = base then return l.token
    end for
    return ""
end function

function Image_(base as dynamic, path as string, w as integer, h as integer) as string
    if path = "" then return ""
    if Left(path, 4) = "http" then return path
    b = base
    if b = invalid or b = "" then b = Session_().base
    return Plex_ImageUrl(b, TokenFor_(b), path, w, h)
end function

' Something asked of Plex; its answer comes to answered(result) with the same op and id.
' The provider's films and series ("iptv…") are asked of the IPTV task that holds them.
sub Ask_(op as string, args as object)
    if m.tasks_ = invalid then m.tasks_ = {}
    if m.askSeq_ = invalid then m.askSeq_ = 0
    m.askSeq_ = m.askSeq_ + 1
    key = op + ":" + m.askSeq_.ToStr()
    if Left(op, 4) = "iptv" then
        server = m.global.iptv
        if server = invalid then return
        ' The answer comes back in a node of this page's own, so only this page hears it.
        reply = CreateObject("roSGNode", "Node")
        reply.addFields({ key_: key, result: {} })
        reply.observeField("result", "onAnswer_")
        m.tasks_[key] = reply
        server.request = { op: op, args: args, reply: reply }
        return
    end if
    task = CreateObject("roSGNode", "PlexTask")
    task.op = op
    task.args = args
    task.observeField("result", "onAnswer_")
    m.tasks_[key] = task
    task.addFields({ key_: key })
    task.control = "run"
end sub

' The provider's films and series are switched on and there to show.
function IptvReady_() as boolean
    p = m.global.prefs
    s = m.global.iptvState
    if p = invalid or not Bool_(p.iptvLibrary) or s = invalid then return false
    return Bool_(s.ready)
end function

sub onAnswer_(event as object)
    task = event.getRoSGNode()
    if task <> invalid and m.tasks_ <> invalid then m.tasks_.Delete(task.key_)
    answered(event.getData())
end sub

' A poster's content: its picture, words, count and how far through it is.
function PosterContent_(parent as object, title as string, caption as string, image as string, count as dynamic, progress as dynamic) as object
    c = parent.createChild("ContentNode")
    c.title = title
    c.description = caption
    c.HDPOSTERURL = image
    fields = {}
    if count <> invalid then fields.badgeCount = count
    if progress <> invalid then fields.progress = progress
    if fields.Count() > 0 then c.addFields(fields)
    return c
end function

' A Plex title as a poster: an episode under its show's name, by its own still where it's wide.
function ItemContent_(parent as object, i as object, wide as boolean) as object
    caption = Plex_Caption(i)
    if i.type = "episode" and i.title <> "" then caption = Join_([caption, i.title], " · ")
    if wide then
        art = i.art
        if art = "" then art = i.thumb
        image = Image_(i.serverBase, art, 480, 270)
    else
        art = i.thumb
        if i.type = "episode" and i.grandparentThumb <> "" then art = i.grandparentThumb
        image = Image_(i.serverBase, art, 300, 450)
    end if
    progress = invalid
    if not Plex_IsWatched(i) then progress = Plex_ResumeFraction(i)
    c = PosterContent_(parent, Plex_RowTitle(i), caption, image, invalid, progress)
    if Plex_IsWatched(i) then c.addFields({ watched: true })
    if Str_(i.serverBase) = "iptv:" then c.addFields({ tag: "IPTV" })
    return c
end function

' Rows of posters for a RowList: [{ title, items, wide, groups }] in, the content and
' each row's sizes out, and the items kept in [m.rowItems] for what's selected.
sub ShowRows_(list as object, rows as object)
    ' The same rows again (the Watchlist or a setting changed, not what's in them): left as
    ' they are. Building them anew made every poster and fetched every picture again, and
    ' on a Roku TV the remote went unanswered for seconds each time.
    key = RowsKey_(rows)
    if key = m.rowsKey_ and list.content <> invalid then return
    m.rowsKey_ = key
    m.sessionHeld_ = Session_()
    root = CreateObject("roSGNode", "ContentNode")
    sizes = []
    heights = []
    m.rowItems = []
    for each r in rows
        if r.items.Count() > 0 then
            row = root.createChild("ContentNode")
            row.title = r.title
            kept = []
            for each it in r.items
                if r.groups = true then
                    caption = Plex_Caption(it.newest)
                    if it.newCount > 1 then caption = it.newCount.ToStr() + " new episodes"
                    PosterContent_(row, it.showTitle, caption, Image_(it.serverBase, it.thumb, 300, 450), it.newCount, invalid)
                    kept.Push(it.newest)
                else if r.plain = true then
                    ' Not from Plex: a title, a line under it and a picture, as they come.
                    PosterContent_(row, it.title, Str_(it.caption), Str_(it.poster), invalid, invalid)
                    kept.Push(it)
                else if r.people = true then
                    c = PosterContent_(row, it.name, Str_(it.role), Image_(it.serverBase, Str_(it.thumb), 240, 240), invalid, invalid)
                    c.addFields({ round: true })
                    kept.Push(it)
                else
                    ItemContent_(row, it, r.wide = true)
                    kept.Push(it)
                end if
            end for
            m.rowItems.Push(kept)
            if r.wide = true then
                sizes.Push([427, 304])
                heights.Push(380)
            else if r.people = true then
                sizes.Push([200, 290])
                heights.Push(370)
            else
                sizes.Push([240, 424])
                heights.Push(500)
            end if
        end if
    end for
    m.sessionHeld_ = invalid
    list.itemSize = [1728, 500]
    list.rowItemSize = sizes
    list.rowHeights = heights
    list.content = root
end sub

'' What the rows show, in a few words per poster: what's in them, how far through, the color.
function RowsKey_(rows as object) as string
    parts = [Str_(m.global.accent)]
    for each r in rows
        if r.items.Count() > 0 then
            parts.Push(r.title + "#" + r.items.Count().ToStr())
            for each it in r.items
                if r.groups = true then
                    n = it.newest
                    parts.Push(Str_(it.serverBase) + Str_(n.ratingKey) + "/" + Str_(it.newCount))
                else if r.plain = true then
                    parts.Push(Str_(it.title) + Str_(it.caption) + Str_(it.poster))
                else if r.people = true then
                    parts.Push(Str_(it.name) + Str_(it.thumb))
                else
                    parts.Push(Str_(it.serverBase) + Str_(it.ratingKey) + "/" + Str_(it.viewOffsetMs) + "/" + Str_(it.viewCount) + "/" + Str_(it.viewedLeafCount))
                end if
            end for
        end if
    end for
    return Join_(parts, "|")
end function

' What's selected in a RowList shown by ShowRows_.
function SelectedRowItem_(list as object) as dynamic
    at = list.rowItemSelected
    if at = invalid or m.rowItems = invalid or at[0] >= m.rowItems.Count() then return invalid
    row = m.rowItems[at[0]]
    if at[1] >= row.Count() then return invalid
    return row[at[1]]
end function

function FocusedRowItem_(list as object) as dynamic
    at = list.rowItemFocused
    if at = invalid or m.rowItems = invalid or at.Count() < 2 or at[0] < 0 or at[0] >= m.rowItems.Count() then return invalid
    row = m.rowItems[at[0]]
    if at[1] < 0 or at[1] >= row.Count() then return invalid
    return row[at[1]]
end function

' Where a title opens: a collection or playlist its list, anything else its page.
function RouteFor_(i as object) as object
    if i.type = "collection" then return { name: "list", kind: "collection", item: i, title: i.title }
    if i.type = "playlist" then return { name: "list", kind: "playlist", item: i, title: i.title }
    return { name: "detail", item: i }
end function

