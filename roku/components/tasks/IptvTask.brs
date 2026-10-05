sub init()
    m.top.functionName = "serve"
end sub

' Requests one at a time, for as long as the app is open.
sub serve()
    port = CreateObject("roMessagePort")
    m.top.observeField("request", port)
    m.store = CreateObject("roRegistrySection", "reely")
    m.marks = {}
    raw = m.store.Read("iptvWatch")
    if raw <> "" then m.marks = Vod_UnpackMarks(ParseJson(raw))
    m.lib = Vod_NewLibrary()
    m.grids = {}
    m.series = {}
    m.seriesOrder = []
    m.lastSave = 0
    m.top.catalogState = {}
    while true
        msg = Wait(0, port)
        if type(msg) = "roSGNodeEvent" and msg.getField() = "request" then handleRequest(msg.getData())
    end while
end sub

' Whatever goes wrong, an answer comes back: a page waiting on it is never left waiting.
sub handleRequest(q as dynamic)
    if q = invalid or type(q) <> "roAssociativeArray" or q.op = invalid then return
    a = q.args
    if a = invalid then a = {}
    out = { op: q.op, id: a.id }
    try
        answerOp(q.op, a, out)
    catch e
        print "IptvTask "; q.op; " failed: "; e.message
        #if DEBUG
            for each f in e.backtrace
                print "  at "; f.filename; ":"; f.line_number
            end for
        #end if
        Problem_Keep(q.op, e)
        out.failed = true
    end try
    if q.reply <> invalid then q.reply.result = out
end sub

' The Xtream login from Live TV; invalid when there's none, or it's a playlist.
function loginOf() as dynamic
    live = m.global.live
    if live = invalid or live.credentials = invalid then return invalid
    c = live.credentials
    if Str_(c.base) = "" or Xtream_IsPlaylist(c) then return invalid
    return c
end function

function providerWins() as boolean
    p = m.global.prefs
    return p <> invalid and Bool_(p.iptvWins)
end function

' The playlist's guide by channel id, read when there's none yet, it's another, or it's six hours
' old; a failure is tried again after five minutes, not on every channel moved to.
function playlistGuide(a as object) as object
    url = Str_(a.guideUrl)
    now = clockNow()
    if url = "" then
        m.top.guideState = { kind: "none" }
        return {}
    end if
    held = m.xmltv
    if held <> invalid and held.url = url and not Bool_(a.fresh) then
        if held.byChannel <> invalid and now - held.at < 6 * 3600 then return held.byChannel
        if held.byChannel = invalid and now - held.at < 300 then return {}
    end if
    m.top.guideState = { kind: "updating" }
    known = a.known
    if known <> invalid and known.Count() = 0 then known = invalid
    got = XtreamApi_PlaylistGuide(url, known, now)
    if got.byChannel = invalid then
        m.xmltv = { url: url, at: now, byChannel: invalid }
        m.top.guideState = { kind: "failed", message: Str_(got.error) }
        return {}
    end if
    m.xmltv = { url: url, at: now, byChannel: got.byChannel }
    m.top.guideState = { kind: "ready", at: now }
    return got.byChannel
end function

function clockNow() as integer
    return CreateObject("roDateTime").AsSeconds()
end function

sub answerOp(op as string, a as object, out as object)
    if op = "iptvLoad" then
        loadCatalog(a, out)
    else if op = "iptvOff" then
        m.lib = Vod_NewLibrary()
        m.grids = {}
        m.series = {}
        m.seriesOrder = []
        m.top.catalogState = {}
        m.xmltv = invalid
        m.top.guideState = {}
    else if op = "iptvMeta" then
        movies = a.kind = "movie"
        sorted = gridOf(movies, "titleSort:asc", Str_(a.categoryId), Bool_(a.unwatched))
        out.answer = {
            genres: Vod_Categories(m.lib, movies, providerWins()),
            decades: [],
            letters: Vod_Letters(sorted),
            released: Vod_Released(m.lib, movies, providerWins(), m.marks),
            added: Vod_Newest(m.lib, movies, providerWins(), m.marks),
            collections: []
        }
    else if op = "iptvPage" then
        titles = gridOf(a.kind = "movie", Str_(a.sort), Str_(a.categoryId), Bool_(a.unwatched))
        out.items = Vod_Items(titles, m.marks, Int(Num_(a.start)), Int(Num_(a.size)))
    else if op = "iptvHome" then
        out.answer = Vod_ComposeHome(m.lib, m.marks, a.home, providerWins())
    else if op = "iptvSearch" then
        out.answer = Vod_MergeSearch(m.lib, Str_(a.query), a.answer, providerWins(), m.marks)
    else if op = "iptvDetail" then
        out.answer = titlePage(Str_(a.ratingKey), Str_(a.episodeKey))
    else if op = "iptvSeason" then
        out.answer = seasonPage(Str_(a.seasonKey))
    else if op = "iptvNext" then
        out.answer = nextUp(Str_(a.showKey))
    else if op = "iptvPlayback" then
        out.answer = fileOf(Str_(a.ratingKey), Int(Num_(a.durationMs)))
        if out.answer = invalid then out.error = "Sign in to your IPTV provider in Live TV to watch this."
    else if op = "iptvProgress" then
        Vod_Progress(m.marks, a.item, Int(Num_(a.ms)), Int(Num_(a.durationMs)), clockNow())
        m.grids = {}
        saveMarks(a.state <> "playing")
    else if op = "iptvWatched" then
        Vod_SetWatched(m.marks, markedTogether(a.item), Bool_(a.watched), clockNow())
        m.grids = {}
        saveMarks(true)
        out.ok = true
    else if op = "iptvForget" then
        Vod_Forget(m.marks, Str_(a.ratingKey))
        saveMarks(true)
        out.ok = true
    else if op = "iptvGuideStale" then
        if m.xmltv <> invalid then m.xmltv.at = 0
    else if op = "liveEpg" or op = "liveTable" then
        ' A playlist's guide: what's on from the XMLTV it names, read whole once and kept.
        byChannel = playlistGuide(a)
        now = clockNow()
        found = {}
        epg = a.epg
        if epg = invalid then epg = {}
        for each id in Arr_(a.streamIds)
            key = Str_(id)
            listing = Xtream_PlaylistListing(byChannel, key, Str_(epg[key]))
            if op = "liveEpg" then found[key] = Xtream_NowAndNext(listing, now) else found[key] = listing
        end for
        if op = "liveEpg" then out.guide = found else out.table = found
        out.guideState = m.top.guideState
    end if
end sub

' The catalogue, once (or again when asked), and what Plex has, for matching the two.
sub loadCatalog(a as object, out as object)
    c = loginOf()
    if c = invalid then
        m.top.catalogState = {}
        return
    end if
    before = m.top.catalogState
    m.top.catalogState = { loading: true, ready: Bool_(before.ready), error: "", movies: Int(Num_(before.movies)), shows: Int(Num_(before.shows)) }
    if Bool_(a.refresh) or m.lib.loadedAt = 0 then
        catalog = VodApi_Catalog(c)
        if catalog.error <> invalid then
            m.top.catalogState = { loading: false, ready: m.lib.loadedAt > 0, error: catalog.error, movies: m.lib.movies.Count(), shows: m.lib.series.Count() }
            return
        end if
        Vod_SetCatalog(m.lib, catalog.movies, catalog.series, catalog.movieCategories, catalog.seriesCategories)
        m.lib.loadedAt = clockNow()
        m.series = {}
        m.seriesOrder = []
    end if
    VodApi_IndexPlex(m.lib, Arr_(a.libraries), m.global.clientId)
    m.grids = {}
    out.ok = true
    m.top.catalogState = { loading: false, ready: true, error: "", movies: m.lib.movies.Count(), shows: m.lib.series.Count() }
end sub

' The grid's titles, kept until something changes them: paging through it asks again.
function gridOf(movies as boolean, sort as string, categoryId as string, unwatched as boolean) as object
    key = Iif_(movies, "m", "s") + "|" + sort + "|" + categoryId + "|" + Iif_(unwatched, "1", "0") + "|" + Iif_(providerWins(), "1", "0")
    g = m.grids[key]
    if g = invalid then
        g = Vod_Browse(m.lib, movies, sort, categoryId, unwatched, providerWins(), m.marks)
        m.grids[key] = g
    end if
    return g
end function

' Where things were left, written to the registry: at once when it matters, otherwise
' every minute or so while something plays.
sub saveMarks(atOnce as boolean)
    t = CreateObject("roDateTime").AsSeconds()
    if not atOnce and t - m.lastSave < 60 then return
    m.lastSave = t
    kept = Vod_PackMarks(m.marks, 50)
    m.marks = Vod_UnpackMarks(kept)
    m.store.Write("iptvWatch", FormatJson(kept))
    m.store.Flush()
end sub

' A series' seasons and episodes, from the panel; the last dozen looked at kept.
function seriesOf(id as integer) as dynamic
    key = id.ToStr()
    info = m.series[key]
    if info = invalid then
        c = loginOf()
        if c = invalid then return invalid
        info = VodApi_SeriesInfo(c, id)
        if info = invalid then return invalid
        m.series[key] = info
    end if
    order = []
    for each k in m.seriesOrder
        if k <> key then order.Push(k)
    end for
    order.Push(key)
    while order.Count() > 12
        m.series.Delete(order.Shift())
    end while
    m.seriesOrder = order
    return info
end function

function episodesOf(showId as integer, info as object, title as string, poster as string, backdrop as string) as object
    out = []
    for each s in info.seasons
        for each e in Vod_EpisodeItems(showId, title, poster, backdrop, s)
            out.Push(Vod_Apply(m.marks, e))
        end for
    end for
    return out
end function

function showPage(id as integer, info as object) as object
    t = m.lib.seriesById[id.ToStr()]
    d = Vod_DetailOf(Vod_ShowKey(id), t, info, true)
    return d
end function

' A title's page, shaped as PlexApi_Detail's: for a show, the season it's up to (or the
' episode it was opened on), and its episodes.
function titlePage(key as string, episodeKey as string) as object
    k = Vod_ParseKey(key)
    if loginOf() = invalid or k = invalid or (k.kind <> "movie" and k.kind <> "show") then return { error: "Sign in to your IPTV provider in Live TV to watch this." }
    out = { detail: invalid, seasons: [], season: invalid, episodes: [], focused: invalid, related: [], trailer: invalid }
    if k.kind = "movie" then
        t = m.lib.moviesById[k.id.ToStr()]
        info = VodApi_MovieInfo(loginOf(), k.id)
        d = Vod_Apply(m.marks, Vod_DetailOf(key, t, info, false))
        out.detail = d
        out.related = Vod_Related(m.lib, d, providerWins(), m.marks)
        return out
    end if
    info = seriesOf(k.id)
    if info = invalid then return { error: "Your provider doesn't have this series any more." }
    d = Vod_Apply(m.marks, showPage(k.id, info))
    out.detail = d
    for each s in Vod_SeasonItems(k.id, d.title, d.thumb, info)
        out.seasons.Push(Vod_Apply(m.marks, s))
    end for
    all = episodesOf(k.id, info, d.title, d.thumb, d.art)
    target = invalid
    for each e in all
        if e.ratingKey = episodeKey then target = e
    end for
    if target = invalid then target = Plex_NextEpisode(all)
    chosen = invalid
    if target <> invalid then
        for each s in out.seasons
            if s.ratingKey = target.parentRatingKey then chosen = s
        end for
    end if
    if chosen = invalid then
        for each s in out.seasons
            if chosen = invalid and s.index <> invalid and s.index > 0 then chosen = s
        end for
    end if
    if chosen = invalid and out.seasons.Count() > 0 then chosen = out.seasons[0]
    if chosen <> invalid then
        out.season = chosen
        for each e in all
            if e.parentRatingKey = chosen.ratingKey then out.episodes.Push(e)
        end for
        for each e in out.episodes
            if target <> invalid and e.ratingKey = target.ratingKey then out.focused = e
        end for
        if out.focused = invalid then out.focused = Plex_NextEpisode(out.episodes)
    end if
    out.related = Vod_Related(m.lib, d, providerWins(), m.marks)
    return out
end function

function seasonPage(seasonKey as string) as object
    k = Vod_ParseKey(seasonKey)
    out = { episodes: [], focused: invalid }
    if k = invalid or k.kind <> "season" then return out
    info = seriesOf(k.showId)
    if info = invalid then return out
    d = showPage(k.showId, info)
    for each s in info.seasons
        if s.number = k.number then
            for each e in Vod_EpisodeItems(k.showId, d.title, d.thumb, d.art, s)
                out.episodes.Push(Vod_Apply(m.marks, e))
            end for
        end if
    end for
    out.focused = Plex_NextEpisode(out.episodes)
    return out
end function

' A show's next episode, from all of them: the one it's up to.
function nextUp(showKey as string) as object
    k = Vod_ParseKey(showKey)
    if k = invalid or k.kind <> "show" then return { episode: invalid, queue: [] }
    info = seriesOf(k.id)
    if info = invalid then return { episode: invalid, queue: [] }
    d = showPage(k.id, info)
    all = episodesOf(k.id, info, d.title, d.thumb, d.art)
    return { episode: Plex_NextEpisode(all), queue: all }
end function

' What marking [item] watched covers: itself, or a show's (or season's) every episode.
function markedTogether(item as object) as object
    if item.type = "movie" or item.type = "episode" then return [item]
    k = Vod_ParseKey(item.ratingKey)
    if k = invalid then return []
    showId = 0
    if k.kind = "show" then showId = k.id
    if k.kind = "season" then showId = k.showId
    if showId = 0 then return []
    info = seriesOf(showId)
    if info = invalid then return []
    d = showPage(showId, info)
    out = []
    for each e in episodesOf(showId, info, d.title, d.thumb, d.art)
        if k.kind = "show" or e.parentRatingKey = item.ratingKey then out.Push(e)
    end for
    return out
end function

' The file, from the panel, as it is: there's no Plex to convert it.
function fileOf(key as string, durationMs as integer) as dynamic
    c = loginOf()
    k = Vod_ParseKey(key)
    if c = invalid or k = invalid then return invalid
    if k.kind = "movie" then
        url = Vod_MovieUrl(c, k.id, k.extension)
    else if k.kind = "episode" then
        url = Vod_EpisodeUrl(c, k.id, k.extension)
    else
        return invalid
    end if
    mk = m.marks[key]
    if durationMs <= 0 and mk <> invalid then durationMs = mk.d
    ext = LCase(k.extension)
    if ext = "" then ext = "mp4"
    return { iptv: true, url: url, container: ext, videoCodec: "", audioCodec: "", durationMs: durationMs, partId: "", audio: [], subtitles: [], markers: [], chapters: [], bif: "" }
end function
