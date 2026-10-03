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
        guid: Str_(e.guid),
        parentTitle: Str_(e.parentTitle),
        serverBase: base
    }
end function

function Plex_Items(container as dynamic, base as dynamic) as object
    out = []
    if container = invalid then return out
    for each meta in Arr_(container.Metadata)
        out.Push(Plex_ParseItem(meta, base))
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
function Plex_TranscodeUrl(base as string, token as string, ratingKey as string, session as string, clientId as string, mediaIndex = 0 as integer, maxKbps = 0 as integer, subtitles = "burn" as string) as string
    ' 4K only when the quality chosen allows it; otherwise the TV's own 1080.
    resolution = "1920x1080"
    if maxKbps >= 20000 then resolution = "3840x2160"
    if maxKbps > 0 and maxKbps < 8000 then resolution = "1280x720"
    bitrate = ""
    if maxKbps > 0 then bitrate = "&maxVideoBitrate=" + maxKbps.ToStr()
    return base + "/video/:/transcode/universal/start.m3u8?path=" + UrlEncode_("/library/metadata/" + ratingKey) + "&mediaIndex=" + mediaIndex.ToStr() + "&partIndex=0" + "&protocol=hls&fastSeek=1&directPlay=0&directStream=1&subtitles=" + subtitles + "&audioBoost=100&videoQuality=100&videoResolution=" + resolution + bitrate + "&session=" + session + "&X-Plex-Client-Identifier=" + UrlEncode_(clientId) + "&X-Plex-Platform=Roku&X-Plex-Product=" + UrlEncode_("Reely TV") + "&X-Plex-Token=" + token
end function

' The episode after [item] in [queue], or invalid.
function Plex_NextInQueue(queue as object, item as object) as dynamic
    for i = 0 to queue.Count() - 2
        if queue[i].ratingKey = item.ratingKey then return queue[i + 1]
    end for
    return invalid
end function

' The intro or credits marker [ms] is in, or invalid.
function Plex_MarkerAt(markers as object, kind as string, ms as dynamic) as dynamic
    for each mk in markers
        if mk.type = kind and ms >= mk.startMs and ms < mk.endMs then return mk
    end for
    return invalid
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

' ---------------------------------------------------------------- A title's page

' What a title's page shows: the facts, the people, the copies of it, where a show is up to.
function Plex_ParseDetail(e as object, base as dynamic) as object
    d = Plex_ParseItem(e, base)
    d.contentRating = Str_(e.contentRating)
    d.studio = Str_(e.studio)
    d.tagline = Str_(e.tagline)
    d.theme = Str_(e.theme)
    d.genres = Plex_Tags(e.Genre)
    d.directors = Plex_Tags(e.Director)
    roles = []
    for each r in Arr_(e.Role)
        name = Str_(r.tag)
        if name <> "" then roles.Push({ id: Str_(r.id), name: name, role: Str_(r.role), thumb: Str_(r.thumb) })
    end for
    d.roles = roles
    versions = []
    for each md in Arr_(e.Media)
        versions.Push(Plex_VersionLabel(Str_(md.videoResolution), Str_(md.videoCodec)))
    end for
    d.versions = versions
    d.onDeckKey = ""
    d.onDeckSeasonKey = ""
    if e.OnDeck <> invalid then
        deck = Arr_(e.OnDeck.Metadata)
        if deck.Count() > 0 then
            d.onDeckKey = Str_(deck[0].ratingKey)
            d.onDeckSeasonKey = Str_(deck[0].parentRatingKey)
        end if
    end if
    return d
end function

function Plex_Tags(list as dynamic) as object
    out = []
    for each t in Arr_(list)
        name = Str_(t.tag)
        if name <> "" then out.Push(name)
    end for
    return out
end function

' "4K", "1080p", "SD": one of several copies of a title.
function Plex_VersionLabel(resolution as string, codec as string) as string
    r = LCase(resolution)
    if r = "4k" or r = "2160" then
        size = "4K"
    else if r = "sd" then
        size = "SD"
    else if r = "" then
        size = "Other"
    else if Val(r) > 0 then
        size = r + "p"
    else
        size = UCase(r)
    end if
    if codec <> "" then return size + " " + UCase(codec)
    return size
end function

' "2024 · PG-13 · Drama": the facts under a title.
function Plex_Facts(d as object) as string
    parts = []
    if d.year <> invalid then parts.Push(d.year.ToStr())
    if d.contentRating <> "" then parts.Push(d.contentRating)
    if d.durationMs > 0 and d.type = "movie" then parts.Push(Plex_Duration(d.durationMs))
    if d.type = "show" and d.leafCount > 0 then parts.Push(d.leafCount.ToStr() + " episodes")
    if d.genres.Count() > 0 then
        ' Iif_ reads both sides, so the second genre is only looked at when there is one.
        two = [d.genres[0]]
        if d.genres.Count() > 1 then two.Push(d.genres[1])
        parts.Push(Join_(two, ", "))
    end if
    return Join_(parts, "  ·  ")
end function

' "2h 18m", "42m".
function Plex_Duration(ms as dynamic) as string
    total = Int(ms / 60000)
    if total <= 0 then return ""
    h = Int(total / 60)
    if h > 0 then return h.ToStr() + "h " + (total - h * 60).ToStr() + "m"
    return total.ToStr() + "m"
end function

' ---------------------------------------------------------------- Search

' Movies, shows and episodes from Plex's search hubs, each once.
function Plex_ItemsFromHubs(hubs as object, base as string, wanted as object) as object
    out = []
    seen = {}
    for each hub in hubs
        for each meta in Arr_(hub.Metadata)
            item = Plex_ParseItem(meta, base)
            ok = false
            for each w in wanted
                if item.type = w then ok = true
            end for
            if ok and item.ratingKey <> "" and not seen.DoesExist(item.ratingKey) then
                seen[item.ratingKey] = true
                out.Push(item)
            end if
        end for
    end for
    return out
end function

function Plex_PeopleFromHubs(hubs as object, base as string) as object
    out = []
    seen = {}
    for each hub in hubs
        if Str_(hub.type) = "actor" then
            list = hub.Directory
            if list = invalid then list = hub.Metadata
            for each p in Arr_(list)
                id = Str_(p.id)
                name = Str_(p.tag)
                if name = "" then name = Str_(p.title)
                if id <> "" and name <> "" and not seen.DoesExist(id) then
                    seen[id] = true
                    out.Push({ id: id, name: name, thumb: Str_(p.thumb), serverBase: base })
                end if
            end for
        end if
    end for
    return out
end function

' Words, lower case, accents and punctuation aside: what search matches on.
function Plex_Words(text as string) as object
    out = []
    word = ""
    lower = LCase(text)
    for i = 1 to Len(lower)
        c = Mid(lower, i, 1)
        code = Asc(c)
        if (code >= 97 and code <= 122) or (code >= 48 and code <= 57) or code > 127 then
            word = word + c
        else if word <> "" then
            out.Push(word)
            word = ""
        end if
    end for
    if word <> "" then out.Push(word)
    return out
end function

' What matched by name first, then the rest: as SearchMatch.split on the other apps.
function Plex_SplitMatches(query as string, items as object) as object
    wanted = Plex_Words(query)
    matches = []
    others = []
    for each item in items
        name = Plex_Words(Plex_RowTitle(item) + " " + item.title)
        all = wanted.Count() > 0
        for each w in wanted
            found = false
            for each n in name
                if Left(n, Len(w)) = w then found = true
            end for
            if not found then all = false
        end for
        if all then matches.Push(item) else others.Push(item)
    end for
    return { matches: matches, others: others }
end function

' ---------------------------------------------------------------- Libraries

' A filter's values (genres, decades): its key and name.
function Plex_Directory(container as dynamic) as object
    out = []
    if container = invalid then return out
    for each d in Arr_(container.Directory)
        key = Str_(d.key)
        title = Str_(d.title)
        if key <> "" and title <> "" then out.Push({ id: key, title: title })
    end for
    return out
end function

' How many titles start with each letter, for the A–Z jump.
function Plex_Letters(container as dynamic) as object
    out = []
    if container = invalid then return out
    for each d in Arr_(container.Directory)
        letter = Str_(d.title)
        count = Int(Num_(d.size))
        if letter <> "" and count > 0 then out.Push({ letter: letter, count: count })
    end for
    return out
end function

' Where [letter]'s titles start in the A–Z order; -1 when it has none.
function Plex_LetterStart(letters as object, letter as string) as integer
    at = 0
    for each l in letters
        if l.letter = letter then return at
        at = at + l.count
    end for
    return -1
end function

' The narrowing asked of Plex, as query parameters.
function Plex_Filters(unwatched as boolean, genre as string, decade as string) as string
    out = ""
    if unwatched then out = out + "&unwatched=1"
    if genre <> "" then out = out + "&genre=" + UrlEncode_(genre)
    if decade <> "" then out = out + "&decade=" + UrlEncode_(decade)
    return out
end function

' ---------------------------------------------------------------- Playing

' Everything about the file the player needs: the address, the sound and subtitle tracks
' (with Plex's choices), the intro and credits, the chapters, and the trick-play pictures.
function Plex_PlaybackDetail(metadata as dynamic, base as string, token as string, mediaIndex as integer) as dynamic
    if metadata = invalid then return invalid
    media = Arr_(metadata.Media)
    if media.Count() = 0 then return invalid
    if mediaIndex < 0 or mediaIndex >= media.Count() then mediaIndex = 0
    chosen = media[mediaIndex]
    parts = Arr_(chosen.Part)
    if parts.Count() = 0 then return invalid
    part = parts[0]
    key = Str_(part.key)
    if key = "" then return invalid
    partId = Str_(part.id)
    audio = []
    subtitles = []
    for each st in Arr_(part.Stream)
        kind = Int(Num_(st.streamType))
        label = Str_(st.displayTitle)
        if label = "" then label = Str_(st.language)
        isOn = Bool_(st.selected)
        if kind = 2 then
            if label = "" then label = "Sound"
            audio.Push({ id: Str_(st.id), label: label, selected: isOn, codec: LCase(Str_(st.codec)), language: Str_(st.languageCode) })
        else if kind = 3 then
            if label = "" then label = "Subtitles"
            url = ""
            if Str_(st.key) <> "" then url = base + Str_(st.key) + "?X-Plex-Token=" + token
            subtitles.Push({ id: Str_(st.id), label: label, selected: isOn, codec: LCase(Str_(st.codec)), url: url, language: Str_(st.languageCode) })
        end if
    end for
    markers = []
    for each mk in Arr_(metadata.Marker)
        t = Str_(mk.type)
        startMs = Num_(mk.startTimeOffset)
        endMs = Num_(mk.endTimeOffset)
        if t <> "" and endMs > startMs then markers.Push({ type: t, startMs: startMs, endMs: endMs })
    end for
    chapters = []
    for each ch in Arr_(metadata.Chapter)
        title = Str_(ch.tag)
        if title = "" then title = "Chapter " + (chapters.Count() + 1).ToStr()
        chapters.Push({ title: title, startMs: Num_(ch.startTimeOffset) })
    end for
    bif = ""
    if partId <> "" then bif = base + "/library/parts/" + partId + "/indexes/sd?X-Plex-Token=" + token
    return {
        url: base + key + "?X-Plex-Token=" + token,
        container: LCase(Str_(chosen.container)),
        videoCodec: LCase(Str_(chosen.videoCodec)),
        audioCodec: LCase(Str_(chosen.audioCodec)),
        durationMs: Num_(metadata.duration),
        partId: partId,
        audio: audio,
        subtitles: subtitles,
        markers: markers,
        chapters: chapters,
        bif: bif
    }
end function

' The subtitles Plex has on: text in their own file the Roku draws itself (SRT, WebVTT),
' anything else only Plex's conversion can put in the picture.
function Plex_SubtitlePlan(p as object) as object
    for each s in p.subtitles
        if s.selected then
            text = s.url <> "" and (s.codec = "srt" or s.codec = "subrip" or s.codec = "vtt" or s.codec = "webvtt")
            return { on: s, text: text, burn: not text }
        end if
    end for
    return { on: invalid, text: false, burn: false }
end function
