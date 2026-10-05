sub init()
    m.page = m.top.findNode("page")
    m.actions = m.top.findNode("actions")
    m.versions = m.top.findNode("versions")
    m.seasons = m.top.findNode("seasons")
    m.episodes = m.top.findNode("episodes")
    m.rows = m.top.findNode("rows")
    m.note = m.top.findNode("note")
    m.top.findNode("title").font = Bold_(72)
    m.top.findNode("summary").font = Regular_(30)
    m.top.findNode("upNext").font = Semibold_(28)
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.episodes.rowLabelFont = Bold_(32)
    m.actions.observeField("pressed", "onAction")
    m.versions.observeField("pressed", "onVersion")
    m.seasons.observeField("pressed", "onSeasonPicked")
    m.episodes.observeField("rowItemSelected", "onEpisodePicked")
    m.episodes.observeField("rowItemFocused", "onEpisodeFocused")
    m.rows.observeField("rowItemSelected", "onRowPicked")
    Listen_("watchlist", "paintActions")
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
    paintFacts(d)
    m.top.findNode("summary").text = d.summary
    m.top.findNode("backdrop").uri = Image_(m.base, Iif_(d.art <> "", d.art, d.thumb), 1920, 1080)
    show = d.type = "show"
    m.episodes.visible = show
    ' The seasons as chips, the one shown outlined, when there's more than one.
    m.seasons.visible = show and m.seasonList.Count() > 1
    m.episodes.translation = Iif_(m.seasons.visible, [96, 570], [96, 490])
    if show then
        m.seasonKey = ""
        if m.episodeList.Count() > 0 then m.seasonKey = m.episodeList[0].parentRatingKey
        labels = []
        for each season in m.seasonList
            labels.Push(season.title)
        end for
        m.seasons.labels = labels
        paintSeasons()
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
    m.rows.translation = Iif_(show, [96, m.episodes.translation[1] + 420], Iif_(m.versions.visible, [96, 570], [96, 500]))
    m.rows.visible = m.rows.content.getChildCount() > 0
    paintActions()
end sub

' The season's episodes as pictures, as the Fire TV shows them: "3. Dead Air", how long,
' what's watched and how far through.
sub paintEpisodes()
    root = CreateObject("roSGNode", "ContentNode")
    row = root.createChild("ContentNode")
    row.title = "Episodes"
    for each season in m.seasonList
        if season.ratingKey = m.seasonKey then row.title = season.title
    end for
    focus = 0
    for i = 0 to m.episodeList.Count() - 1
        e = m.episodeList[i]
        index = ""
        if e.index <> invalid then index = e.index.ToStr()
        progress = invalid
        if not Plex_IsWatched(e) then progress = Plex_ResumeFraction(e)
        art = e.thumb
        if art = "" then art = e.art
        c = PosterContent_(row, Join_([index, e.title], ". "), Plex_Duration(e.durationMs), Image_(Iif_(Str_(e.serverBase) <> "", e.serverBase, m.base), art, 480, 270), invalid, progress)
        if Plex_IsWatched(e) then c.addFields({ watched: true })
        if m.target <> invalid and e.ratingKey = m.target.ratingKey then focus = i
    end for
    m.episodes.content = root
    if m.episodeList.Count() > 0 then m.episodes.jumpToRowItem = [0, focus]
end sub

' The seasons' chips: the one shown outlined.
sub paintSeasons()
    on = []
    for each season in m.seasonList
        on.Push(season.ratingKey = m.seasonKey)
    end for
    m.seasons.on = on
end sub

' The scores as outlined badges, the details, then the quality badges, as the Fire TV's.
sub paintFacts(d as object)
    row = m.top.findNode("facts")
    row.removeChildrenIndex(row.getChildCount(), 0)
    x = 0
    if d.rating <> invalid then x = Badge_(row, x, "★ " + Plex_OneDecimal(d.rating), "outline", Accent_())
    if d.audienceRating <> invalid then x = Badge_(row, x, Int(d.audienceRating * 10 + 0.5).ToStr() + "%", "outline", "0x8CBE6EFF")
    if Str_(d.contentRating) <> "" then x = Badge_(row, x, d.contentRating, "outline", "0xF2F4F78C")
    for each f in Plex_TitleFacts(d)
        x = Badge_(row, x, f, "plain", "")
    end for
    for each q in Arr_(d.qualities)
        x = Badge_(row, x, q, "fill", "")
    end for
end sub

' One badge at [x]: an outline in [color], a translucent fill, or plain words. The next one's place back.
function Badge_(row as object, x as integer, text as string, kind as string, color as string) as integer
    size = 28
    ' Wide enough for figures, which run wider than letters.
    w = Int(Len(text) * size * 0.6) + 4
    pad = 0
    if kind <> "plain" then pad = 14
    if kind <> "plain" then
        back = CreateObject("roSGNode", "Poster")
        back.uri = Iif_(kind = "outline", "pkg:/images/ring.9.png", "pkg:/images/card.9.png")
        back.blendColor = Iif_(kind = "outline", color, "0xF2F4F724")
        back.width = w + pad * 2
        back.height = 44
        back.translation = [x, 0]
        row.appendChild(back)
    end if
    label = CreateObject("roSGNode", "Label")
    label.text = text
    label.font = Iif_(kind = "fill", Semibold_(size - 2), Regular_(size))
    label.color = "0xF2F4F7FF"
    label.width = w + pad * 2
    label.height = 44
    label.horizAlign = "center"
    label.vertAlign = "center"
    label.translation = [x, 0]
    row.appendChild(label)
    return x + w + pad * 2 + 16
end function

sub paintVersions()
    on = []
    for i = 0 to m.detail.versions.Count() - 1
        on.Push(i = m.versionIndex)
    end for
    m.versions.on = on
end sub

' Play or Resume, Restart, Watched, Watchlist, Trailer: as the Fire TV's buttons.
sub paintActions()
    if Gone_() then return
    d = m.detail
    if d = invalid then return
    show = d.type = "show"
    t = m.target
    if not show then t = d
    labels = []
    m.actionIds = []
    resume = t <> invalid and t.viewOffsetMs > 0 and not Plex_IsWatched(t)
    glyphs = []
    if t <> invalid then
        labels.Push(Iif_(resume, "Resume", "Play"))
        m.actionIds.Push("play")
        glyphs.Push("play")
        if resume then
            labels.Push("Restart")
            m.actionIds.Push("restart")
            glyphs.Push("restart")
        end if
        labels.Push(Iif_(Plex_IsWatched(t), "Unwatch", "Watched"))
        m.actionIds.Push("watched")
        glyphs.Push("check")
    end if
    on = []
    for each x in labels
        on.Push(false)
    end for
    if d.guid <> "" then
        labels.Push("Watchlist")
        m.actionIds.Push("watchlist")
        on.Push(Watchlisted_(d.guid))
        glyphs.Push(Iif_(Watchlisted_(d.guid), "saved", "plus"))
    end if
    if m.trailer <> invalid then
        labels.Push("Trailer")
        m.actionIds.Push("trailer")
        on.Push(false)
        glyphs.Push("film")
    end if
    m.actions.glyphs = glyphs
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

sub onSeasonPicked()
    i = m.seasons.pressed
    if i < 0 or i >= m.seasonList.Count() then return
    s = m.seasonList[i]
    if s.ratingKey = m.seasonKey then return
    m.seasonKey = s.ratingKey
    paintSeasons()
    if m.base = "iptv:" then
        Ask_("iptvSeason", { seasonKey: s.ratingKey })
    else
        Ask_("season", { base: m.base, token: m.token, seasonKey: s.ratingKey, focusKey: "" })
    end if
end sub

sub onEpisodeFocused()
    at = m.episodes.rowItemFocused
    if at = invalid or at.Count() < 2 then return
    i = at[1]
    if i >= 0 and i < m.episodeList.Count() and m.episodes.isInFocusChain() then
        m.target = m.episodeList[i]
        paintActions()
    end if
end sub

sub onEpisodePicked()
    at = m.episodes.rowItemSelected
    if at = invalid or at.Count() < 2 then return
    i = at[1]
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
    ' Down the page in order: the buttons, a film's copies, a show's seasons and then its
    ' episodes, then the cast and more like it. Up comes back the same way.
    order = [m.actions]
    if m.versions.visible then order.Push(m.versions)
    if m.seasons.visible then order.Push(m.seasons)
    if m.episodes.visible and m.episodeList <> invalid and m.episodeList.Count() > 0 then order.Push(m.episodes)
    if m.rows.visible then order.Push(m.rows)
    at = -1
    for i = 0 to order.Count() - 1
        if order[i].isInFocusChain() then at = i
    end for
    if key = "down" and at >= 0 and at < order.Count() - 1 then
        target = order[at + 1]
        target.setFocus(true)
        scrollTo(target.isSameNode(m.rows))
        return true
    else if key = "up" and at > 0 then
        target = order[at - 1]
        target.setFocus(true)
        scrollTo(false)
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
