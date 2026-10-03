' The whole app's state lives here, in m; requests go out through HttpTask nodes.

sub init()
    m.store = CreateObject("roRegistrySection", "reely")
    m.clientId = m.store.Read("clientId")
    if m.clientId = "" then
        m.clientId = "reely-roku-" + CreateObject("roDeviceInfo").GetRandomUUID()
        m.store.Write("clientId", m.clientId)
        m.store.Flush()
    end if
    m.tasks = {}
    m.homeStale = false
    m.homeWaiting = 0
    m.signIn = m.top.findNode("signIn")
    m.home = m.top.findNode("home")
    m.detail = m.top.findNode("detail")
    m.player = m.top.findNode("player")
    m.status = m.top.findNode("status")
    m.actions = m.top.findNode("actions")
    m.seasons = m.top.findNode("seasons")
    m.episodes = m.top.findNode("episodes")
    m.top.findNode("signInTitle").font = Font_(54)
    m.top.findNode("code").font = Font_(96)
    m.top.findNode("detailTitle").font = Font_(60)
    m.home.rowLabelFont = Font_(32)
    semibold = "pkg:/fonts/geist_semibold.ttf"
    m.actions.textFont = FontOf_(semibold, 32)
    m.actions.focusedTextFont = FontOf_(semibold, 32)
    regular = "pkg:/fonts/geist_regular.ttf"
    for each id in ["signInHint", "signInNote", "detailFacts", "upNext", "detailSummary", "status"]
        m.top.findNode(id).font = FontOf_(regular, 30)
    end for
    for each list in [m.seasons, m.episodes]
        list.font = FontOf_(regular, 32)
        list.focusedFont = FontOf_(semibold, 32)
    end for

    m.home.observeField("rowItemSelected", "onHomeSelected")
    m.actions.observeField("buttonSelected", "onAction")
    m.seasons.observeField("itemFocused", "onSeasonFocused")
    m.episodes.observeField("itemSelected", "onEpisodeSelected")
    m.player.observeField("state", "onPlayerState")
    m.player.observeField("position", "onPlayerPosition")

    m.page = "start"
    token = m.store.Read("plexToken")
    if token <> "" then
        m.token = token
        showFinding()
        findServer()
    else
        startSignIn()
    end if
end sub

' Geist, as the other apps have it, bundled: a TV draws it the same whatever its firmware.
function Font_(size as integer) as object
    return FontOf_("pkg:/fonts/geist_bold.ttf", size)
end function

function FontOf_(uri as string, size as integer) as object
    f = CreateObject("roSGNode", "Font")
    f.uri = uri
    f.size = size
    return f
end function

' ------------------------------------------------------------------ Asking servers

function PlexHeaders_(token as dynamic) as object
    h = {
        "Accept": "application/json",
        "X-Plex-Product": "Reely TV",
        "X-Plex-Version": "0.51.0",
        "X-Plex-Client-Identifier": m.clientId,
        "X-Plex-Platform": "Roku",
        "X-Plex-Device": "Roku",
        "X-Plex-Device-Name": "Reely on Roku"
    }
    if token <> invalid and token <> "" then h["X-Plex-Token"] = token
    return h
end function

' Sends a request; its answer comes to onResponse with [tag].
sub ask(tag as string, url as string, method as string, headers as object, body as string, timeoutMs as integer)
    task = CreateObject("roSGNode", "HttpTask")
    task.request = { tag: tag, url: url, method: method, headers: headers, body: body, timeoutMs: timeoutMs }
    task.observeField("response", "onResponse")
    m.tasks[tag] = task
    task.control = "run"
end sub

sub onResponse(event as object)
    r = event.getData()
    tag = r.tag
    m.tasks.Delete(tag)
    if tag = "pin" then
        onPin(r)
    else if Left(tag, 5) = "claim" then
        onClaim(r)
    else if tag = "resources" then
        onResources(r)
    else if Left(tag, 6) = "probe:" then
        onProbe(tag, r)
    else if tag = "sections" then
        onSections(r)
    else if Left(tag, 5) = "home:" then
        onHomePart(tag, r)
    else if tag = "detail" then
        onDetail(r)
    else if tag = "children" then
        onSeasons(r)
    else if tag = "episodes" then
        onEpisodes(r)
    else if tag = "playback" then
        onPlayback(r)
    end if
end sub

sub say(text as string)
    m.status.text = text
end sub

' ------------------------------------------------------------------ Signing in

sub startSignIn()
    m.page = "signIn"
    showOnly(m.signIn)
    m.top.findNode("signInHint").text = "Getting a sign-in code…"
    m.top.findNode("code").text = ""
    m.top.findNode("signInNote").text = ""
    ask("pin", m.global.plexTv + "/api/v2/pins", "POST", PlexHeaders_(invalid), "strong=false", 15000)
end sub

sub onPin(r as object)
    if r.code < 200 or r.code > 299 or r.json = invalid then
        m.top.findNode("signInHint").text = "Couldn't get a sign-in code from Plex. Press OK to try again."
        return
    end if
    m.pinId = Str_(r.json.id)
    m.top.findNode("signInHint").text = "On your phone or computer, go to plex.tv/link and enter"
    m.top.findNode("code").text = Str_(r.json.code)
    m.top.findNode("signInNote").text = "Waiting for you to sign in…"
    m.claims = 0
    m.claimTimer = CreateObject("roSGNode", "Timer")
    m.claimTimer.duration = 2
    m.claimTimer.repeat = true
    m.claimTimer.observeField("fire", "claim")
    m.claimTimer.control = "start"
end sub

sub claim()
    m.claims = m.claims + 1
    if m.claims > 300 then
        m.claimTimer.control = "stop"
        m.top.findNode("signInHint").text = "That code has expired. Press OK for a new one."
        m.top.findNode("code").text = ""
        return
    end if
    ask("claim", m.global.plexTv + "/api/v2/pins/" + m.pinId, "GET", PlexHeaders_(invalid), "", 10000)
end sub

sub onClaim(r as object)
    ' A slower answer to an earlier ask, after one already signed in.
    if m.page <> "signIn" or r.json = invalid then return
    token = Str_(r.json.authToken)
    if token = "" or token = "null" then return
    m.claimTimer.control = "stop"
    m.token = token
    m.store.Write("plexToken", token)
    m.store.Flush()
    showFinding()
    findServer()
end sub

' ------------------------------------------------------------------ Finding the server

sub showFinding()
    m.page = "finding"
    showOnly(m.signIn)
    m.top.findNode("signInTitle").text = "Finding your Plex server"
    m.top.findNode("signInHint").text = "You're signed in. Looking for your server, at home first, then over the internet."
    m.top.findNode("code").text = ""
    m.top.findNode("signInNote").text = ""
end sub

sub findServer()
    ask("resources", m.global.plexTv + "/api/v2/resources?includeHttps=1&includeRelay=1", "GET", PlexHeaders_(m.token), "", 20000)
end sub

sub onResources(r as object)
    if r.code = 401 then
        ' Signed out elsewhere: sign in again.
        m.store.Delete("plexToken")
        m.store.Flush()
        startSignIn()
        return
    end if
    servers = Plex_ServersFrom(r.json, m.token)
    if servers.Count() = 0 then
        m.top.findNode("signInTitle").text = "Can't find your Plex server"
        m.top.findNode("signInHint").text = "Make sure Plex Media Server is running and signed in to the same account. Press OK to try again."
        m.page = "noServer"
        return
    end if
    ' Every address of the first server at once; the best of those that answer wins.
    m.server = servers[0]
    m.probes = {}
    m.probeCount = m.server.connections.Count()
    for i = 0 to m.probeCount - 1
        ask("probe:" + i.ToStr(), m.server.connections[i] + "/identity", "GET", PlexHeaders_(m.server.accessToken), "", 5000)
    end for
end sub

sub onProbe(tag as string, r as object)
    index = Val(Mid(tag, 7))
    m.probes[index.ToStr()] = (r.code >= 200 and r.code < 300)
    if m.probes.Count() < m.probeCount then
        ' Waiting on a better address than any that has answered.
        best = -1
        for i = 0 to m.probeCount - 1
            if not m.probes.DoesExist(i.ToStr()) then exit for
            if m.probes[i.ToStr()] then
                best = i
                exit for
            end if
        end for
        if best < 0 then return
    end if
    if m.base <> invalid then return
    for i = 0 to m.probeCount - 1
        if m.probes.DoesExist(i.ToStr()) and m.probes[i.ToStr()] then
            m.base = m.server.connections[i]
            m.serverToken = m.server.accessToken
            ask("sections", m.base + "/library/sections", "GET", PlexHeaders_(m.serverToken), "", 15000)
            return
        end if
    end for
    m.top.findNode("signInTitle").text = "Can't find your Plex server"
    m.top.findNode("signInHint").text = "Make sure Plex Media Server is running and signed in to the same account. Press OK to try again."
    m.page = "noServer"
end sub

sub onSections(r as object)
    m.sections = []
    if r.json <> invalid and r.json.MediaContainer <> invalid then
        for each d in Arr_(r.json.MediaContainer.Directory)
            t = Str_(d.type)
            if t = "movie" or t = "show" then m.sections.Push({ key: Str_(d.key), title: Str_(d.title), type: t })
        end for
    end if
    loadHome()
end sub

' ------------------------------------------------------------------ Home

sub loadHome()
    ' Already on its way.
    if m.homeWaiting > 0 then return
    m.page = "home"
    m.homeParts = { cw: invalid, movies: [], groups: {}, order: [] }
    m.homeWaiting = 1
    ask("home:cw", m.base + "/hubs?identifier=" + UrlEncode_("home.continue,home.ondeck") + "&count=40", "GET", PlexHeaders_(m.serverToken), "", 15000)
    for each s in m.sections
        m.homeWaiting = m.homeWaiting + 1
        if s.type = "movie" then
            ask("home:movies:" + s.key, m.base + "/library/sections/" + s.key + "/all?type=1&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=40", "GET", PlexHeaders_(m.serverToken), "", 15000)
        else
            askEpisodes(s.key, 0)
        end if
    end for
end sub

sub askEpisodes(key as string, offset as integer)
    ask("home:eps:" + key + ":" + offset.ToStr(), m.base + "/library/sections/" + key + "/all?type=4&sort=addedAt:desc&X-Plex-Container-Start=" + offset.ToStr() + "&X-Plex-Container-Size=200", "GET", PlexHeaders_(m.serverToken), "", 15000)
end sub

sub onHomePart(tag as string, r as object)
    container = invalid
    if r.json <> invalid then container = r.json.MediaContainer
    if tag = "home:cw" then
        items = []
        if container <> invalid then
            for each hub in Arr_(container.Hub)
                for each it in Plex_Items(hub, m.base)
                    if it.type = "movie" or it.type = "episode" then items.Push(it)
                end for
            end for
        end if
        m.homeParts.cw = Plex_ContinueWatchingOrder(items)
    else if Left(tag, 12) = "home:movies:" then
        m.homeParts.movies.Append(Plex_Items(container, m.base))
    else
        ' home:eps:<key>:<offset>; paged until thirty shows, or two thousand episodes.
        rest = Mid(tag, 10)
        sep = Instr(1, rest, ":")
        key = Left(rest, sep - 1)
        offset = Val(Mid(rest, sep + 1))
        page = Plex_Items(container, m.base)
        Plex_FoldEpisodes(m.homeParts.groups, m.homeParts.order, page, m.base, key)
        if page.Count() = 200 and m.homeParts.order.Count() < 30 and offset + 200 < 2000 then
            askEpisodes(key, offset + 200)
            return
        end if
    end if
    m.homeWaiting = m.homeWaiting - 1
    if m.homeWaiting = 0 then showHome()
end sub

sub showHome()
    root = CreateObject("roSGNode", "ContentNode")
    sizes = []
    heights = []
    m.homeRows = []
    if m.homeParts.cw <> invalid and m.homeParts.cw.Count() > 0 then
        row = root.createChild("ContentNode")
        row.title = "Continue Watching"
        for each it in m.homeParts.cw
            art = it.art
            if art = "" then art = it.thumb
            addItem(row, Plex_RowTitle(it), Plex_Caption(it), Plex_ImageUrl(m.base, m.serverToken, art, 480, 270), invalid, Plex_ResumeFraction(it))
        end for
        sizes.Push([427, 304])
        heights.Push(380)
        m.homeRows.Push(m.homeParts.cw)
    end if
    groups = []
    for each key in m.homeParts.order
        groups.Push(m.homeParts.groups[key])
    end for
    groups = StableSort_(groups, function(a as object, b as object) as boolean
        return a.addedAt > b.addedAt
    end function)
    if groups.Count() > 30 then groups = Slice_(groups, 30)
    if groups.Count() > 0 then
        row = root.createChild("ContentNode")
        row.title = "Recently Added Episodes"
        newest = []
        for each g in groups
            caption = Plex_Caption(g.newest)
            if g.newCount > 1 then caption = g.newCount.ToStr() + " new episodes"
            addItem(row, g.showTitle, caption, Plex_ImageUrl(m.base, m.serverToken, g.thumb, 300, 450), g.newCount, invalid)
            newest.Push(g.newest)
        end for
        sizes.Push([240, 424])
        heights.Push(500)
        m.homeRows.Push(newest)
    end if
    movies = StableSort_(m.homeParts.movies, function(a as object, b as object) as boolean
        return a.addedAt > b.addedAt
    end function)
    if movies.Count() > 40 then movies = Slice_(movies, 40)
    if movies.Count() > 0 then
        row = root.createChild("ContentNode")
        row.title = "Recently Added Movies"
        for each it in movies
            addItem(row, it.title, Plex_Caption(it), Plex_ImageUrl(m.base, m.serverToken, it.thumb, 300, 450), invalid, Plex_ResumeFraction(it))
        end for
        sizes.Push([240, 424])
        heights.Push(500)
        m.homeRows.Push(movies)
    end if
    if m.homeRows.Count() = 0 then
        showOnly(m.signIn)
        m.top.findNode("signInTitle").text = "Nothing to show yet"
        m.top.findNode("signInHint").text = "Watch something and it will appear here."
        return
    end if
    m.home.itemSize = [1728, 500]
    m.home.rowItemSize = sizes
    m.home.rowHeights = heights
    m.home.content = root
    ' Fresh rows arriving behind a title's page don't pull the viewer away from it.
    if m.page <> "home" then return
    showOnly(m.home)
    m.home.setFocus(true)
end sub

function Slice_(list as object, count as integer) as object
    out = []
    for i = 0 to count - 1
        if i < list.Count() then out.Push(list[i])
    end for
    return out
end function

sub addItem(row as object, title as string, caption as string, image as string, count as dynamic, progress as dynamic)
    c = row.createChild("ContentNode")
    c.title = title
    c.description = caption
    c.HDPOSTERURL = image
    fields = {}
    if count <> invalid then fields.badgeCount = count
    if progress <> invalid then fields.progress = progress
    if fields.Count() > 0 then c.addFields(fields)
end sub

sub onHomeSelected()
    at = m.home.rowItemSelected
    item = m.homeRows[at[0]][at[1]]
    openTitle(item, invalid)
end sub

' ------------------------------------------------------------------ A title's page

' An episode opens its show, landing on that episode; landOn (an episode of the show)
' does the same for a show, else it lands where the show is up to.
sub openTitle(item as object, landOn as dynamic)
    m.focusKey = ""
    m.focusSeason = ""
    m.loadedSeason = invalid
    key = item.ratingKey
    if item.type = "episode" and item.grandparentRatingKey <> "" then
        key = item.grandparentRatingKey
        landOn = item
    end if
    if type(landOn) = "roAssociativeArray" then
        m.focusKey = landOn.ratingKey
        m.focusSeason = landOn.parentRatingKey
    end if
    m.page = "detail"
    m.title = invalid
    m.target = invalid
    m.episodeList = []
    m.seasonList = []
    showOnly(m.detail)
    m.top.findNode("detailTitle").text = ""
    m.top.findNode("upNext").text = ""
    m.seasons.content = invalid
    m.episodes.content = invalid
    ask("detail", m.base + "/library/metadata/" + key + "?includeOnDeck=1", "GET", PlexHeaders_(m.serverToken), "", 15000)
end sub

sub onDetail(r as object)
    if r.json = invalid or r.json.MediaContainer = invalid then
        say("Couldn't load that title. Try again.")
        return
    end if
    list = Arr_(r.json.MediaContainer.Metadata)
    if list.Count() = 0 then return
    e = list[0]
    m.title = Plex_ParseItem(e, m.base)
    if m.focusKey = "" and e.OnDeck <> invalid then
        onDeck = Arr_(e.OnDeck.Metadata)
        if onDeck.Count() > 0 then
            m.focusKey = Str_(onDeck[0].ratingKey)
            m.focusSeason = Str_(onDeck[0].parentRatingKey)
        end if
    end if
    m.top.findNode("detailTitle").text = m.title.title
    m.top.findNode("detailSummary").text = m.title.summary
    facts = [Plex_Caption(m.title), Str_(e.contentRating), Str_(e.studio)]
    m.top.findNode("detailFacts").text = Join_(facts, "  ·  ")
    m.top.findNode("backdrop").uri = Plex_ImageUrl(m.base, m.serverToken, m.title.art, 1920, 1080)
    if m.title.type = "show" then
        ask("children", m.base + "/library/metadata/" + m.title.ratingKey + "/children", "GET", PlexHeaders_(m.serverToken), "", 15000)
    else
        m.target = m.title
        showActions()
    end if
end sub

sub onSeasons(r as object)
    m.seasonList = []
    container = invalid
    if r.json <> invalid then container = r.json.MediaContainer
    for each s in Plex_Items(container, m.base)
        if s.type = "season" then m.seasonList.Push(s)
    end for
    content = CreateObject("roSGNode", "ContentNode")
    for each s in m.seasonList
        c = content.createChild("ContentNode")
        c.title = s.title
    end for
    m.seasons.content = content
    ' The season it's up to, else the first proper one (not Specials).
    start = -1
    for i = 0 to m.seasonList.Count() - 1
        if m.seasonList[i].ratingKey = m.focusSeason then start = i
    end for
    if start < 0 then
        start = 0
        for i = 0 to m.seasonList.Count() - 1
            if m.seasonList[i].index <> invalid then
                start = i
                exit for
            end if
        end for
    end if
    if m.seasonList.Count() > 0 then
        m.seasons.jumpToItem = start
        loadSeason(m.seasonList[start])
    end if
end sub

sub onSeasonFocused()
    i = m.seasons.itemFocused
    if i >= 0 and i < m.seasonList.Count() and m.page = "detail" then loadSeason(m.seasonList[i])
end sub

sub loadSeason(season as object)
    if m.loadedSeason <> invalid and m.loadedSeason = season.ratingKey then return
    m.loadedSeason = season.ratingKey
    ask("episodes", m.base + "/library/metadata/" + season.ratingKey + "/children", "GET", PlexHeaders_(m.serverToken), "", 15000)
end sub

sub onEpisodes(r as object)
    container = invalid
    if r.json <> invalid then container = r.json.MediaContainer
    m.episodeList = []
    for each e in Plex_Items(container, m.base)
        if e.type = "episode" then m.episodeList.Push(e)
    end for
    content = CreateObject("roSGNode", "ContentNode")
    for each e in m.episodeList
        c = content.createChild("ContentNode")
        mark = ""
        if Plex_IsWatched(e) then mark = "✓  "
        c.title = mark + Join_([Str_(e.index), e.title], ". ")
    end for
    m.episodes.content = content
    ' The episode it was opened on, else the one it's up to.
    m.target = invalid
    for each e in m.episodeList
        if e.ratingKey = m.focusKey then m.target = e
    end for
    if m.target = invalid then m.target = Plex_NextEpisode(m.episodeList)
    ' The list starts on the episode the page is about.
    for i = 0 to m.episodeList.Count() - 1
        if m.target <> invalid and m.episodeList[i].ratingKey = m.target.ratingKey then m.episodes.jumpToItem = i
    end for
    showActions()
end sub

sub showActions()
    t = m.target
    if t = invalid then return
    resume = t.viewOffsetMs > 0 and not Plex_IsWatched(t)
    if t.type = "episode" then
        word = "Up next"
        if resume then word = "Continue"
        m.top.findNode("upNext").text = Join_([word, Plex_Caption(t), t.title], "  ·  ")
    end if
    if resume then
        m.actions.buttons = ["Resume", "Restart"]
    else
        m.actions.buttons = ["Play"]
    end if
    m.actions.setFocus(true)
end sub

sub onAction()
    if m.target = invalid then return
    ' Resume is the first of two; a lone Play starts from the beginning.
    resume = m.actions.buttons.Count() = 2 and m.actions.buttonSelected = 0
    play(m.target, resume)
end sub

sub onEpisodeSelected()
    i = m.episodes.itemSelected
    if i >= 0 and i < m.episodeList.Count() then play(m.episodeList[i], true)
end sub

' ------------------------------------------------------------------ Playing

sub play(item as object, resume as boolean)
    m.playing = item
    m.startMs = 0
    if resume and item.viewOffsetMs > 0 then m.startMs = item.viewOffsetMs
    ask("playback", m.base + "/library/metadata/" + item.ratingKey, "GET", PlexHeaders_(m.serverToken), "", 15000)
end sub

sub onPlayback(r as object)
    metadata = invalid
    if r.json <> invalid and r.json.MediaContainer <> invalid then
        list = Arr_(r.json.MediaContainer.Metadata)
        if list.Count() > 0 then metadata = list[0]
    end if
    p = Plex_PlaybackFrom(metadata, m.base, m.serverToken)
    if p = invalid then
        say("That file isn't on the server any more.")
        return
    end if
    info = CreateObject("roDeviceInfo")
    video = {}
    for each codec in ["h264", "hevc", "vp9", "av1", "mpeg2video"]
        name = codec
        if codec = "h264" then name = "mpeg4 avc"
        video[codec] = info.CanDecodeVideo({ codec: name }).result = true
    end for
    audio = {}
    for each codec in ["aac", "ac3", "eac3", "mp3", "flac", "opus"]
        audio[codec] = info.CanDecodeAudio({ codec: codec }).result = true
    end for
    plan = Plex_PlanDirect(p.container, p.videoCodec, p.audioCodec, video, audio)
    m.session = Mid(info.GetRandomUUID(), 1, 12)
    content = CreateObject("roSGNode", "ContentNode")
    content.title = Plex_RowTitle(m.playing)
    if plan.direct then
        content.url = p.url
        content.streamFormat = plan.format
    else
        content.url = Plex_TranscodeUrl(m.base, m.serverToken, m.playing.ratingKey, m.session, m.clientId)
        content.streamFormat = "hls"
    end if
    m.direct = plan.direct
    m.durationMs = p.durationMs
    m.lastReport = 0
    m.player.content = content
    m.player.seek = m.startMs / 1000
    m.player.visible = true
    m.player.setFocus(true)
    m.player.control = "play"
    m.page = "player"
end sub

sub onPlayerPosition()
    if m.playing = invalid then return
    now = m.player.position
    if Abs(now - m.lastReport) >= 10 then
        m.lastReport = now
        report("playing")
    end if
end sub

sub onPlayerState()
    s = m.player.state
    if s = "paused" then
        report("paused")
    else if s = "finished" then
        stopPlaying(true)
    else if s = "error" then
        ' The file wouldn't play as it is: Plex converts it, from where it got to.
        if m.direct then
            m.direct = false
            m.startMs = Int(m.player.position * 1000)
            content = m.player.content
            content.url = Plex_TranscodeUrl(m.base, m.serverToken, m.playing.ratingKey, m.session, m.clientId)
            content.streamFormat = "hls"
            m.player.content = content
            m.player.seek = m.startMs / 1000
            m.player.control = "play"
        else
            say("This didn't play. Check the connection to your Plex server and try again.")
            stopPlaying()
        end if
    end if
end sub

' Where playback is, told to the server: what keeps Continue Watching right everywhere.
sub report(state as string)
    if m.playing = invalid then return
    ms = Int(m.player.position * 1000)
    url = m.base + "/:/timeline?ratingKey=" + m.playing.ratingKey + "&key=" + UrlEncode_("/library/metadata/" + m.playing.ratingKey) + "&identifier=com.plexapp.plugins.library&state=" + state + "&time=" + ms.ToStr() + "&duration=" + Int(m.durationMs).ToStr() + "&playbackTime=" + ms.ToStr() + "&playQueueItemID=-1"
    h = PlexHeaders_(m.serverToken)
    h["X-Plex-Session-Identifier"] = m.session
    ask("timeline:" + state + ":" + ms.ToStr(), url, "GET", h, "", 10000)
end sub

sub stopPlaying(finished = false as boolean)
    report("stopped")
    played = m.playing
    m.player.control = "stop"
    m.player.visible = false
    m.playing = invalid
    m.homeStale = true
    ' Back where it was, with what's just been watched marked as watched: on the episode
    ' if it was left part way, on the next one if it was finished.
    if m.title <> invalid then
        landOn = invalid
        if not finished and played <> invalid and played.type = "episode" then landOn = played
        openTitle(m.title, landOn)
    else
        loadHome()
    end if
end sub

' ------------------------------------------------------------------ The remote

sub showOnly(view as object)
    for each v in [m.signIn, m.home, m.detail]
        v.visible = v.isSameNode(view)
    end for
    say("")
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" then
        if m.page = "player" then
            stopPlaying()
            return true
        else if m.page = "detail" then
            m.title = invalid
            m.loadedSeason = invalid
            showOnly(m.home)
            m.home.setFocus(true)
            m.page = "home"
            if m.homeStale then
                ' Something was watched: Continue Watching has moved on.
                m.homeStale = false
                loadHome()
            end if
            return true
        end if
        ' Home, or signing in: Back leaves the app.
        return false
    end if
    if key = "OK" then
        if m.page = "signIn" and m.top.findNode("code").text = "" then
            startSignIn()
            return true
        else if m.page = "noServer" then
            showFinding()
            findServer()
            return true
        end if
    end if
    if m.page = "detail" then
        if key = "down" and m.actions.hasFocus() and m.seasonList.Count() > 0 then
            m.seasons.setFocus(true)
            return true
        else if key = "right" and m.seasons.hasFocus() then
            m.episodes.setFocus(true)
            return true
        else if key = "left" and m.episodes.hasFocus() then
            m.seasons.setFocus(true)
            return true
        else if key = "up" and (m.seasons.hasFocus() or m.episodes.hasFocus()) then
            m.actions.setFocus(true)
            return true
        end if
    end if
    return false
end function
