' Plex, as the Fire TV and LG apps read it: the same answers, read the same way.

function Plex_ParseItem(e as object, base as dynamic) as object
    t = Str_(e.type)
    leaf = Num_(e.leafCount)
    if leaf <= 0 and t = "collection" then leaf = Num_(e.childCount)
    return {
        ratingKey: Str_(e.ratingKey),
        title: Str_(e.title),
        titleSort: Str_(e.titleSort),
        type: t,
        thumb: Str_(e.thumb),
        art: Str_(e.art),
        summary: Str_(e.summary),
        year: Pos_(e.year),
        index: Pos_(e.index),
        parentIndex: Pos_(e.parentIndex),
        parentRatingKey: Str_(e.parentRatingKey),
        grandparentRatingKey: Str_(e.grandparentRatingKey),
        grandparentTitle: Str_(e.grandparentTitle),
        grandparentThumb: Str_(e.grandparentThumb),
        durationMs: Num_(e.duration),
        viewOffsetMs: Num_(e.viewOffset),
        leafCount: leaf,
        viewedLeafCount: Num_(e.viewedLeafCount),
        viewCount: Num_(e.viewCount),
        addedAt: Num_(e.addedAt),
        lastViewedAt: Num_(e.lastViewedAt),
        librarySectionId: Str_(e.librarySectionID),
        serverBase: base
    }
end function

function Plex_Items(container as dynamic, base as dynamic) as object
    out = []
    if container = invalid then return out
    for each m in Arr_(container.Metadata)
        out.Push(Plex_ParseItem(m, base))
    end for
    return out
end function

function Plex_IsWatched(i as object) as boolean
    if i.type = "movie" or i.type = "episode" then return i.viewCount > 0
    if i.type = "show" or i.type = "season" then return i.leafCount > 0 and i.viewedLeafCount >= i.leafCount
    return false
end function

' Where it was left, 0 to 1; invalid when it hasn't been started.
function Plex_ResumeFraction(i as object) as dynamic
    if i.viewOffsetMs > 0 and i.durationMs > 0 then
        f = i.viewOffsetMs / i.durationMs
        if f > 1 then f = 1
        return f
    end if
    return invalid
end function

function Plex_ListKey(i as object) as string
    return Str_(i.serverBase) + "|" + i.ratingKey
end function

function Plex_RowTitle(i as object) as string
    if i.type = "episode" and i.grandparentTitle <> "" then return i.grandparentTitle
    return i.title
end function

' "S2 · E7", a year, an episode count.
function Plex_Caption(i as object) as string
    if i.type = "episode" then
        s = ""
        e = ""
        if i.parentIndex <> invalid then s = "S" + i.parentIndex.ToStr()
        if i.index <> invalid then e = "E" + i.index.ToStr()
        return Join_([s, e], " · ")
    else if i.type = "season" then
        if i.leafCount > 0 then return i.leafCount.ToStr() + " episodes"
        return ""
    end if
    if i.year <> invalid then return i.year.ToStr()
    return ""
end function

' Best first: at home, then the internet, then the relay; each home address again over
' plain http, for routers that won't look up plex.direct names.
function Plex_ConnectionOrder(connections as object) as object
    usable = []
    for each c in connections
        if c.uri <> "" then usable.Push(c)
    end for
    sorted = StableSort_(usable, function(a as object, b as object) as boolean
        ra = 0
        if a.relay then ra = ra + 2
        if not a.local then ra = ra + 1
        rb = 0
        if b.relay then rb = rb + 2
        if not b.local then rb = rb + 1
        return ra < rb
    end function)
    out = []
    seen = {}
    for each c in sorted
        if not seen.DoesExist(c.uri) then
            seen[c.uri] = true
            out.Push(c.uri)
        end if
        if c.local and not c.relay and c.address <> "" then
            plain = "http://" + c.address + ":" + c.port.ToStr()
            if not seen.DoesExist(plain) then
                seen[plain] = true
                out.Push(plain)
            end if
        end if
    end for
    return out
end function

' The account's servers from plex.tv's resources answer: its own first.
function Plex_ServersFrom(json as dynamic, token as string) as object
    owned = []
    shared = []
    for each r in Arr_(json)
        if Instr(1, Str_(r.provides), "server") > 0 then
            conns = []
            for each c in Arr_(r.connections)
                port = Int(Num_(c.port))
                if port <= 0 then port = 32400
                conns.Push({ uri: Str_(c.uri), address: Str_(c.address), port: port, local: Bool_(c.local), relay: Bool_(c.relay) })
            end for
            uris = Plex_ConnectionOrder(conns)
            if uris.Count() > 0 then
                name = Str_(r.name)
                if name = "" then name = "Plex Media Server"
                access = Str_(r.accessToken)
                if access = "" then access = token
                server = { name: name, accessToken: access, connections: uris, owned: Bool_(r.owned) }
                if server.owned then owned.Push(server) else shared.Push(server)
            end if
        end if
    end for
    owned.Append(shared)
    return owned
end function

' Which episode to play next: the one part watched, else the first unwatched after the
' last watched, else the first unwatched, else the first. Specials aside, unless that's all.
function Plex_NextEpisode(episodes as object) as dynamic
    for each e in episodes
        if Plex_ResumeFraction(e) <> invalid and not Plex_IsWatched(e) then return e
    end for
    proper = []
    for each e in episodes
        if e.parentIndex <> invalid then proper.Push(e)
    end for
    list = proper
    if list.Count() = 0 then list = episodes
    if list.Count() = 0 then return invalid
    last = -1
    for i = 0 to list.Count() - 1
        if Plex_IsWatched(list[i]) then last = i
    end for
    if last >= 0 then
        for i = last + 1 to list.Count() - 1
            if not Plex_IsWatched(list[i]) then return list[i]
        end for
    end if
    for each e in list
        if not Plex_IsWatched(e) then return e
    end for
    return list[0]
end function

function Plex_RecencyOf(i as object) as dynamic
    if i.lastViewedAt > 0 then return i.lastViewedAt
    return i.addedAt
end function

' Continue Watching as Plex's own apps order it: one entry per show, the part-watched
' episode winning, most recently watched first.
function Plex_ContinueWatchingOrder(items as object) as object
    kept = []
    showAt = {}
    seen = {}
    for each it in items
        showKey = it.grandparentRatingKey
        if it.type <> "episode" or showKey = "" then
            k = Plex_ListKey(it)
            if not seen.DoesExist(k) then
                seen[k] = true
                kept.Push(it)
            end if
        else
            key = Str_(it.serverBase) + "|" + showKey
            if not showAt.DoesExist(key) then
                showAt[key] = kept.Count()
                kept.Push(it)
            else if kept[showAt[key]].viewOffsetMs <= 0 and it.viewOffsetMs > 0 then
                kept[showAt[key]] = it
            end if
        end if
    end for
    return StableSort_(kept, function(a as object, b as object) as boolean
        return Plex_RecencyOf(a) > Plex_RecencyOf(b)
    end function)
end function

' Several episodes of one show arriving at once, folded onto the show with a count.
sub Plex_FoldEpisodes(groups as object, order as object, page as object, base as string, sectionKey as string)
    for each ep in page
        showKey = ep.grandparentRatingKey
        if showKey = "" then showKey = ep.ratingKey
        key = base + "|" + showKey
        if groups.DoesExist(key) then
            groups[key].newCount = groups[key].newCount + 1
        else
            title = ep.grandparentTitle
            if title = "" then title = ep.title
            thumb = ep.grandparentThumb
            if thumb = "" then thumb = ep.thumb
            groups[key] = { showTitle: title, showRatingKey: showKey, thumb: thumb, newest: ep, newCount: 1, addedAt: ep.addedAt, serverBase: ep.serverBase }
            order.Push(key)
        end if
    end for
end sub

' As the Fire TV's badge: the number as it is up to 999, then "1K".
function Plex_BadgeText(count as integer) as string
    if count < 1000 then return count.ToStr()
    return Int(count / 1000).ToStr() + "K"
end function

function Plex_ImageUrl(base as string, token as string, path as string, w as integer, h as integer) as string
    if path = "" then return ""
    return base + "/photo/:/transcode?width=" + w.ToStr() + "&height=" + h.ToStr() + "&minSize=1&upscale=1&url=" + UrlEncode_(path) + "&X-Plex-Token=" + token
end function

' The server converting the file to HLS from the start; the player seeks, so every clock
' stays the file's own.
function Plex_TranscodeUrl(base as string, token as string, ratingKey as string, session as string, clientId as string) as string
    return base + "/video/:/transcode/universal/start.m3u8?path=" + UrlEncode_("/library/metadata/" + ratingKey) + "&mediaIndex=0&partIndex=0" + "&protocol=hls&fastSeek=1&directPlay=0&directStream=1&subtitles=burn&audioBoost=100&videoQuality=100&videoResolution=1920x1080" + "&session=" + session + "&X-Plex-Client-Identifier=" + UrlEncode_(clientId) + "&X-Plex-Platform=Roku&X-Plex-Product=" + UrlEncode_("Reely TV") + "&X-Plex-Token=" + token
end function

' Whether this Roku plays the file as it is, or Plex converts it to HLS. [video] and
' [audio] are what this Roku's own decoders said yes to, by codec.
function Plex_PlanDirect(container as string, videoCodec as string, audioCodec as string, video as object, audio as object) as object
    formats = { mp4: "mp4", m4v: "mp4", mov: "mp4", mkv: "mkv", ts: "ts", mpegts: "ts" }
    c = LCase(container)
    if not formats.DoesExist(c) then return { direct: false, format: "hls", reason: "Roku can't open " + UCase(container) + " files" }
    v = LCase(videoCodec)
    if v <> "" and not (video.DoesExist(v) and video[v] = true) then return { direct: false, format: "hls", reason: "Roku can't play " + UCase(videoCodec) + " video" }
    a = LCase(audioCodec)
    if a <> "" and not (audio.DoesExist(a) and audio[a] = true) then return { direct: false, format: "hls", reason: "Roku can't play " + UCase(audioCodec) + " sound" }
    return { direct: true, format: formats[c], reason: "" }
end function

' The file to play, from a title's metadata: its part's address and what it holds.
function Plex_PlaybackFrom(metadata as dynamic, base as string, token as string) as dynamic
    if metadata = invalid then return invalid
    media = Arr_(metadata.Media)
    if media.Count() = 0 then return invalid
    first = media[0]
    parts = Arr_(first.Part)
    if parts.Count() = 0 then return invalid
    key = Str_(parts[0].key)
    if key = "" then return invalid
    return {
        url: base + key + "?X-Plex-Token=" + token,
        container: LCase(Str_(first.container)),
        videoCodec: LCase(Str_(first.videoCodec)),
        audioCodec: LCase(Str_(first.audioCodec)),
        durationMs: Num_(metadata.duration)
    }
end function
