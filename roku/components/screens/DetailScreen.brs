sub init()
    m.page = m.top.findNode("page")
    m.actions = m.top.findNode("actions")
    m.versions = m.top.findNode("versions")
    m.seasons = m.top.findNode("seasons")
    m.episodes = m.top.findNode("episodes")
    m.rows = m.top.findNode("rows")
    m.note = m.top.findNode("note")
    m.top.findNode("title").font = Bold_(64)
    for each id in ["facts", "summary"]
        m.top.findNode(id).font = Regular_(30)
    end for
    m.top.findNode("upNext").font = Semibold_(30)
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    for each list in [m.seasons, m.episodes]
        list.font = Regular_(30)
        list.focusedFont = Semibold_(30)
    end for
    m.actions.observeField("pressed", "onAction")
    m.versions.observeField("pressed", "onVersion")
    m.seasons.observeField("itemFocused", "onSeasonFocused")
    m.episodes.observeField("itemSelected", "onEpisodePicked")
    m.episodes.observeField("itemFocused", "onEpisodeFocused")
    m.rows.observeField("rowItemSelected", "onRowPicked")
    m.global.observeField("watchlist", "paintActions")
    m.detail = invalid
    m.versionIndex = 0
end sub

sub open()
    r = m.top.route
    item = r.item
    m.base = item.serverBase
    if m.base = invalid or m.base = "" then m.base = Session_().base
    m.token = TokenFor_(m.base)
    m.key = item.ratingKey
    m.episodeKey = Str_(r.episodeKey)
    if item.type = "episode" and item.grandparentRatingKey <> "" then
        m.key = item.grandparentRatingKey
        m.episodeKey = item.ratingKey
    end if
    load()
end sub

sub refreshed()
    m.episodeKey = Str_(m.top.refresh.episodeKey)
    load()
end sub

sub load()
    m.note.text = ""
    if m.base = "iptv:" then
        Ask_("iptvDetail", { ratingKey: m.key, episodeKey: m.episodeKey, id: m.key })
    else
        Ask_("detail", { base: m.base, token: m.token, ratingKey: m.key, episodeKey: m.episodeKey, id: m.key })
    end if
end sub

sub answered(r as object)
    if r.op = "detail" or r.op = "iptvDetail" then
        a = r.answer
        if a = invalid then
            m.note.text = "Couldn't load that title. Try again."
            return
        else if a.error <> invalid then
            m.note.text = Str_(a.error)
            return
        end if
        hadFocus = m.top.isInFocusChain()
        if r.op = "iptvDetail" then Trace_("iptv page " + a.detail.title + " " + Arr_(a.episodes).Count().ToStr())
        m.detail = a.detail
        m.seasonList = a.seasons
        m.episodeList = a.episodes
        m.target = a.focused
        m.related = a.related
        m.trailer = a.trailer
        paint()
        if hadFocus and not m.rows.isInFocusChain() and not m.episodes.isInFocusChain() and not m.seasons.isInFocusChain() then m.actions.setFocus(true)
    else if r.op = "season" or r.op = "iptvSeason" then
        if r.answer = invalid then return
        m.episodeList = r.answer.episodes
        m.target = r.answer.focused
        paintEpisodes()
        paintActions()
    else if r.op = "watched" or r.op = "iptvWatched" then
        if r.ok = true then
            m.episodeKey = ""
            if m.target <> invalid then m.episodeKey = m.target.ratingKey
            load()
            m.top.go = { name: "refreshHome" }
        else
            m.note.text = "Plex couldn't update the watched status."
        end if
    end if
end sub

sub paint()
    d = m.detail
    m.top.findNode("title").text = d.title
    m.top.findNode("facts").text = Plex_Facts(d)
    m.top.findNode("summary").text = d.summary
    m.top.findNode("backdrop").uri = Image_(m.base, Iif_(d.art <> "", d.art, d.thumb), 1920, 1080)
    show = d.type = "show"
    m.seasons.visible = show
    m.episodes.visible = show
    if show then
        content = CreateObject("roSGNode", "ContentNode")
        start = 0
        for i = 0 to m.seasonList.Count() - 1
            c = content.createChild("ContentNode")
            c.title = m.seasonList[i].title
        end for
        m.seasons.content = content
        m.seasonKey = ""
        if m.episodeList.Count() > 0 then m.seasonKey = m.episodeList[0].parentRatingKey
        for i = 0 to m.seasonList.Count() - 1
            if m.seasonList[i].ratingKey = m.seasonKey then start = i
        end for
        m.seasons.jumpToItem = start
        paintEpisodes()
    end if
    versions = []
    for each v in d.versions
        versions.Push(v)
    end for
    m.versions.visible = not show and versions.Count() > 1
    if m.versions.visible then
        m.versions.labels = versions
        paintVersions()
    end if
    ' The cast and more like it: under the episodes for a show, under the buttons for a film.
    rows = [{ title: "Cast", items: Head_(d.roles, 30), people: true }, { title: "More like this", items: m.related }]
    for each person in d.roles
        person.serverBase = m.base
    end for
    ShowRows_(m.rows, rows)
    m.rows.translation = Iif_(show, [96, 940], Iif_(m.versions.visible, [96, 520], [96, 450]))
    m.rows.visible = m.rows.content.getChildCount() > 0
    paintActions()
end sub

sub paintEpisodes()
    content = CreateObject("roSGNode", "ContentNode")
    focus = 0
    for i = 0 to m.episodeList.Count() - 1
        e = m.episodeList[i]
        c = content.createChild("ContentNode")
        mark = ""
        if Plex_IsWatched(e) then mark = "✓  "
        index = ""
        if e.index <> invalid then index = e.index.ToStr()
        c.title = mark + Join_([index, e.title], ". ")
        if m.target <> invalid and e.ratingKey = m.target.ratingKey then focus = i
    end for
    m.episodes.content = content
    m.episodes.jumpToItem = focus
end sub

sub paintVersions()
    on = []
    for i = 0 to m.detail.versions.Count() - 1
        on.Push(i = m.versionIndex)
    end for
    m.versions.on = on
end sub

' Play or Resume, Restart, Watched, Watchlist, Trailer: as the Fire TV's buttons.
sub paintActions()
    d = m.detail
    if d = invalid then return
    show = d.type = "show"
    t = m.target
    if not show then t = d
    labels = []
    m.actionIds = []
    resume = t <> invalid and t.viewOffsetMs > 0 and not Plex_IsWatched(t)
    if t <> invalid then
        labels.Push(Iif_(resume, "Resume", "Play"))
        m.actionIds.Push("play")
        if resume then
            labels.Push("Restart")
            m.actionIds.Push("restart")
        end if
        labels.Push(Iif_(Plex_IsWatched(t), "Unwatch", "Watched"))
        m.actionIds.Push("watched")
    end if
    on = []
    for each x in labels
        on.Push(false)
    end for
    if d.guid <> "" then
        labels.Push("Watchlist")
        m.actionIds.Push("watchlist")
        on.Push(Watchlisted_(d.guid))
    end if
    if m.trailer <> invalid then
        labels.Push("Trailer")
        m.actionIds.Push("trailer")
        on.Push(false)
    end if
    m.actions.labels = labels
    m.actions.on = on
    upNext = m.top.findNode("upNext")
    upNext.text = ""
    if show and t <> invalid then upNext.text = Join_([Iif_(resume, "Continue", "Up next"), Plex_Caption(t), t.title], "  ·  ")
end sub

function Watchlisted_(guid as string) as boolean
    list = m.global.watchlist
    if list = invalid then return false
    for each g in list
        if g = guid then return true
    end for
    return false
end function

sub onAction()
    id = m.actionIds[m.actions.pressed]
    t = m.target
    if m.detail.type <> "show" then t = m.detail
    if id = "play" or id = "restart" then
        if t = invalid then return
        queue = []
        if t.type = "episode" then queue = m.episodeList
        m.top.play = { item: t, resume: id = "play", queue: queue, mediaIndex: m.versionIndex }
    else if id = "watched" then
        if m.base = "iptv:" then
            Ask_("iptvWatched", { item: t, watched: not Plex_IsWatched(t) })
        else
            Ask_("watched", { base: m.base, token: m.token, ratingKey: t.ratingKey, watched: not Plex_IsWatched(t) })
        end if
    else if id = "watchlist" then
        m.top.go = { name: "watchlist", guid: m.detail.guid, on: not Watchlisted_(m.detail.guid) }
    else if id = "trailer" then
        m.top.play = { item: m.trailer, resume: false, queue: [], mediaIndex: 0, trailer: true }
    end if
end sub

sub onVersion()
    m.versionIndex = m.versions.pressed
    paintVersions()
end sub

sub onSeasonFocused()
    i = m.seasons.itemFocused
    if i < 0 or i >= m.seasonList.Count() then return
    s = m.seasonList[i]
    if s.ratingKey = m.seasonKey then return
    m.seasonKey = s.ratingKey
    if m.base = "iptv:" then
        Ask_("iptvSeason", { seasonKey: s.ratingKey })
    else
        Ask_("season", { base: m.base, token: m.token, seasonKey: s.ratingKey, focusKey: "" })
    end if
end sub

sub onEpisodeFocused()
    i = m.episodes.itemFocused
    if i >= 0 and i < m.episodeList.Count() and m.episodes.hasFocus() then
        m.target = m.episodeList[i]
        paintActions()
    end if
end sub

sub onEpisodePicked()
    i = m.episodes.itemSelected
    if i >= 0 and i < m.episodeList.Count() then m.top.play = { item: m.episodeList[i], resume: true, queue: m.episodeList, mediaIndex: 0 }
end sub

sub onRowPicked()
    item = SelectedRowItem_(m.rows)
    if item = invalid then return
    if item.name <> invalid then
        ' A person: everything they're in.
        if item.id <> "" then m.top.go = { name: "list", kind: "person", person: { id: item.id, name: item.name, serverBase: m.base }, title: item.name }
    else
        m.top.go = RouteFor_(item)
    end if
end sub

sub focusIn()
    m.actions.setFocus(true)
end sub

' The page slides up to show the cast and more like it, and back down again.
sub scrollTo(lower as boolean)
    if lower then
        y = m.rows.translation[1] - 130
        m.page.translation = [0, -y]
    else
        m.page.translation = [0, 0]
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    show = m.detail <> invalid and m.detail.type = "show"
    if key = "down" then
        if m.actions.hasFocus() then
            if m.versions.visible then
                m.versions.setFocus(true)
            else if show and m.seasonList.Count() > 0 then
                m.episodes.setFocus(true)
            else if m.rows.visible then
                m.rows.setFocus(true)
                scrollTo(true)
            end if
            return true
        else if m.versions.hasFocus() and m.rows.visible then
            m.rows.setFocus(true)
            scrollTo(true)
            return true
        else if (m.episodes.hasFocus() or m.seasons.hasFocus()) and m.rows.visible then
            m.rows.setFocus(true)
            scrollTo(true)
            return true
        end if
    else if key = "up" then
        if m.rows.isInFocusChain() then
            scrollTo(false)
            if show and m.seasonList.Count() > 0 then m.episodes.setFocus(true) else m.actions.setFocus(true)
            return true
        else if m.versions.hasFocus() or m.episodes.hasFocus() or m.seasons.hasFocus() then
            m.actions.setFocus(true)
            return true
        end if
    else if key = "left" and m.episodes.hasFocus() then
        m.seasons.setFocus(true)
        return true
    else if key = "right" and m.seasons.hasFocus() then
        m.episodes.setFocus(true)
        return true
    else if key = "options" and m.rows.isInFocusChain() then
        item = FocusedRowItem_(m.rows)
        if item <> invalid and item.name = invalid then m.top.menu = { item: item }
        return true
    end if
    return false
end function

function Head_(list as object, count as integer) as object
    out = []
    for each x in list
        if out.Count() >= count then exit for
        out.Push(x)
    end for
    return out
end function
