' Plex, asked from a Task: each function waits for its answers and returns what the
' screens show. The reading itself is plex.brs's, which is tested on its own.

function PlexApi_Headers(token as dynamic, clientId as string) as object
    h = {
        "Accept": "application/json",
        "X-Plex-Product": "Reely TV",
        "X-Plex-Version": "0.51.0",
        "X-Plex-Client-Identifier": clientId,
        "X-Plex-Platform": "Roku",
        "X-Plex-Device": "Roku",
        "X-Plex-Device-Name": "Reely on Roku"
    }
    if token <> invalid and token <> "" then h["X-Plex-Token"] = token
    return h
end function

' A server's MediaContainer, or invalid when it didn't answer.
function PlexApi_Container(url as string, token as string, clientId as string, timeoutMs = 20000 as integer) as dynamic
    r = Http_Ask(url, "GET", PlexApi_Headers(token, clientId), "", timeoutMs)
    if r.code < 200 or r.code > 299 or r.json = invalid then return invalid
    if r.json.MediaContainer = invalid then return {}
    return r.json.MediaContainer
end function

' A list of titles from [path], a page of it; invalid when the server didn't answer.
function PlexApi_Items(base as string, token as string, clientId as string, path as string, size = 200 as integer, start = 0 as integer) as dynamic
    sep = "?"
    if Instr(1, path, "?") > 0 then sep = "&"
    c = PlexApi_Container(base + path + sep + "X-Plex-Container-Start=" + start.ToStr() + "&X-Plex-Container-Size=" + size.ToStr(), token, clientId)
    if c = invalid then return invalid
    return Plex_Items(c, base)
end function

' ---------------------------------------------------------------- The servers

' The account's servers, each at the best address that answers, and every library on them.
' [last] is the server used last time, tried first.
function PlexApi_Connect(plexTv as string, token as string, clientId as string, last as string) as object
    r = Http_Ask(plexTv + "/api/v2/resources?includeHttps=1&includeRelay=1", "GET", PlexApi_Headers(token, clientId), "", 20000)
    if r.code = 401 then return { signedOut: true }
    servers = Plex_ServersFrom(r.json, token)
    ordered = []
    for each s in servers
        if s.name = last then ordered.Push(s)
    end for
    for each s in servers
        if s.name <> last then ordered.Push(s)
    end for
    reached = []
    libraries = []
    for each s in ordered
        probes = []
        for each uri in s.connections
            probes.Push(uri + "/identity")
        end for
        at = Http_FirstAnswering(probes, PlexApi_Headers(s.accessToken, clientId), 6000)
        if at >= 0 then
            base = s.connections[at]
            reached.Push({ name: s.name, base: base, token: s.accessToken })
            c = PlexApi_Container(base + "/library/sections", s.accessToken, clientId)
            if c <> invalid then
                for each d in Arr_(c.Directory)
                    t = Str_(d.type)
                    if t = "movie" or t = "show" then libraries.Push({ serverName: s.name, base: base, token: s.accessToken, key: Str_(d.key), title: Str_(d.title), type: t })
                end for
            end if
        end if
    end for
    names = []
    for each s in servers
        names.Push(s.name)
    end for
    return { signedOut: false, servers: names, reached: reached, libraries: libraries, noServers: servers.Count() = 0 }
end function

' ---------------------------------------------------------------- Home

' Home as the other apps build it: Continue Watching from every server, new episodes
' gathered onto their show, new films, playlists.
function PlexApi_Home(libraries as object, clientId as string) as object
    servers = PlexApi_Servers(libraries)
    answered = false
    continuing = []
    playlists = []
    for each s in servers
        c = PlexApi_Container(s.base + "/hubs?identifier=" + UrlEncode_("home.continue,home.ondeck") + "&count=40", s.token, clientId, 15000)
        if c <> invalid then
            answered = true
            for each hub in Arr_(c.Hub)
                for each it in Plex_Items(hub, s.base)
                    if it.type = "movie" or it.type = "episode" then continuing.Push(it)
                end for
            end for
        end if
        lists = PlexApi_Items(s.base, s.token, clientId, "/playlists?playlistType=video", 50)
        if lists <> invalid then
            for each p in lists
                if p.leafCount > 0 then playlists.Push(p)
            end for
        end if
    end for
    movies = []
    groups = {}
    order = []
    for each l in libraries
        if l.type = "movie" then
            got = PlexApi_Items(l.base, l.token, clientId, "/library/sections/" + l.key + "/all?type=1&sort=addedAt:desc", 40)
            if got <> invalid then movies.Append(got)
        else
            ' Paged until thirty shows, or two thousand episodes.
            offset = 0
            while offset < 2000
                page = PlexApi_Items(l.base, l.token, clientId, "/library/sections/" + l.key + "/all?type=4&sort=addedAt:desc", 200, offset)
                if page = invalid then exit while
                Plex_FoldEpisodes(groups, order, page, l.base, l.key)
                if page.Count() < 200 or order.Count() >= 30 then exit while
                offset = offset + 200
            end while
        end if
    end for
    newest = []
    for each key in order
        newest.Push(groups[key])
    end for
    newest = StableSort_(newest, function(a as object, b as object) as boolean
        return a.addedAt > b.addedAt
    end function)
    movies = StableSort_(movies, function(a as object, b as object) as boolean
        return a.addedAt > b.addedAt
    end function)
    return {
        answered: answered or servers.Count() = 0,
        continueWatching: Head_(Plex_ContinueWatchingOrder(continuing), 40),
        recentEpisodes: Head_(newest, 30),
        recentMovies: Head_(movies, 40),
        playlists: playlists
    }
end function

' Each server once, from the libraries on it.
function PlexApi_Servers(libraries as object) as object
    out = []
    seen = {}
    for each l in libraries
        if not seen.DoesExist(l.base) then
            seen[l.base] = true
            out.Push({ base: l.base, token: l.token, serverName: l.serverName })
        end if
    end for
    return out
end function

' The account's Watchlist, and which of it the servers here have, in its own order.
function PlexApi_Watchlist(discover as string, token as string, libraries as object, clientId as string) as object
    r = Http_Ask(discover + "/library/sections/watchlist/all?includeFields=guid,type,title&X-Plex-Container-Start=0&X-Plex-Container-Size=100", "GET", PlexApi_Headers(token, clientId), "", 15000)
    guids = []
    if r.json <> invalid and r.json.MediaContainer <> invalid then
        for each meta in Arr_(r.json.MediaContainer.Metadata)
            g = Str_(meta.guid)
            if Left(g, 7) = "plex://" then guids.Push(g)
        end for
    end if
    items = []
    servers = PlexApi_Servers(libraries)
    for each g in Head_(guids, 40)
        for each s in servers
            found = PlexApi_Items(s.base, s.token, clientId, "/library/all?guid=" + UrlEncode_(g), 1)
            if found <> invalid and found.Count() > 0 then
                items.Push(found[0])
                exit for
            end if
        end for
    end for
    return { ok: r.code >= 200 and r.code < 300, guids: guids, items: items }
end function

function PlexApi_SetWatchlisted(discover as string, token as string, guid as string, on as boolean, clientId as string) as boolean
    key = guid
    slash = 0
    for i = 1 to Len(guid)
        if Mid(guid, i, 1) = "/" then slash = i
    end for
    if slash > 0 then key = Mid(guid, slash + 1)
    action = "removeFromWatchlist"
    if on then action = "addToWatchlist"
    r = Http_Ask(discover + "/actions/" + action + "?ratingKey=" + key, "PUT", PlexApi_Headers(token, clientId), "", 15000)
    return r.code >= 200 and r.code < 300
end function

' ---------------------------------------------------------------- A library

function PlexApi_LibraryMeta(l as object, filters as string, clientId as string) as object
    t = Iif_(l.type = "movie", "1", "2")
    return {
        genres: Plex_Directory(PlexApi_Container(l.base + "/library/sections/" + l.key + "/genre?type=" + t, l.token, clientId)),
        decades: PlexApi_Decades(PlexApi_Container(l.base + "/library/sections/" + l.key + "/decade?type=" + t, l.token, clientId)),
        letters: Plex_Letters(PlexApi_Container(l.base + "/library/sections/" + l.key + "/firstCharacter?type=" + t + filters, l.token, clientId)),
        released: Arr_(PlexApi_Items(l.base, l.token, clientId, "/library/sections/" + l.key + "/all?type=" + t + "&sort=originallyAvailableAt:desc", 40)),
        collections: Arr_(PlexApi_Items(l.base, l.token, clientId, "/library/sections/" + l.key + "/collections", 500))
    }
end function

function PlexApi_Decades(c as dynamic) as object
    return StableSort_(Plex_Directory(c), function(a as object, b as object) as boolean
        return Val(a.id) > Val(b.id)
    end function)
end function

' A page of a library's grid, sorted and narrowed.
function PlexApi_LibraryPage(l as object, sort as string, filters as string, start as integer, size as integer, clientId as string) as dynamic
    t = Iif_(l.type = "movie", "1", "2")
    return PlexApi_Items(l.base, l.token, clientId, "/library/sections/" + l.key + "/all?type=" + t + "&sort=" + sort + filters, size, start)
end function

' ---------------------------------------------------------------- A title's page

' Everything a page shows: the title, its seasons and the season it's up to (or the
' episode it was opened on), more like it, its trailer.
function PlexApi_Detail(base as string, token as string, ratingKey as string, episodeKey as string, clientId as string) as object
    c = PlexApi_Container(base + "/library/metadata/" + ratingKey + "?includeOnDeck=1", token, clientId)
    if c = invalid then return { error: "Couldn't load that title. Try again." }
    list = Arr_(c.Metadata)
    if list.Count() = 0 then return { error: "That title isn't on the server any more." }
    d = Plex_ParseDetail(list[0], base)
    out = { detail: d, seasons: [], season: invalid, episodes: [], focused: invalid, related: [], trailer: invalid }
    if d.type = "show" then
        all = PlexApi_Items(base, token, clientId, "/library/metadata/" + ratingKey + "/children", 400)
        for each s in Arr_(all)
            if s.type = "season" then out.seasons.Push(s)
        end for
        focusKey = episodeKey
        if focusKey = "" then focusKey = d.onDeckKey
        season = invalid
        ' The season of the episode it was opened on, or the one it's up to.
        wanted = d.onDeckSeasonKey
        if episodeKey <> "" then
            meta = PlexApi_Container(base + "/library/metadata/" + episodeKey, token, clientId)
            if meta <> invalid and Arr_(meta.Metadata).Count() > 0 then wanted = Str_(meta.Metadata[0].parentRatingKey)
        end if
        for each s in out.seasons
            if s.ratingKey = wanted then season = s
        end for
        if season = invalid then
            for each s in out.seasons
                if season = invalid and s.index <> invalid then season = s
            end for
        end if
        if season = invalid and out.seasons.Count() > 0 then season = out.seasons[0]
        if season <> invalid then
            out.season = season
            loaded = PlexApi_Season(base, token, season.ratingKey, focusKey, clientId)
            out.episodes = loaded.episodes
            out.focused = loaded.focused
        end if
    end if
    related = PlexApi_Container(base + "/library/metadata/" + ratingKey + "/related?count=24", token, clientId)
    if related <> invalid then
        for each hub in Arr_(related.Hub)
            for each it in Plex_Items(hub, base)
                if (it.type = "movie" or it.type = "show") and it.ratingKey <> ratingKey and out.related.Count() < 24 then out.related.Push(it)
            end for
        end for
    end if
    extras = PlexApi_Container(base + "/library/metadata/" + ratingKey + "/extras", token, clientId)
    if extras <> invalid then
        for each x in Arr_(extras.Metadata)
            if out.trailer = invalid and LCase(Str_(x.subtype)) = "trailer" then out.trailer = Plex_ParseItem(x, base)
        end for
    end if
    return out
end function

function PlexApi_Season(base as string, token as string, seasonKey as string, focusKey as string, clientId as string) as object
    episodes = []
    for each e in Arr_(PlexApi_Items(base, token, clientId, "/library/metadata/" + seasonKey + "/children", 400))
        if e.type = "episode" then episodes.Push(e)
    end for
    focused = invalid
    for each e in episodes
        if e.ratingKey = focusKey then focused = e
    end for
    if focused = invalid then focused = Plex_NextEpisode(episodes)
    return { episodes: episodes, focused: focused }
end function

' A show's next episode, from all of them: the one it's up to.
function PlexApi_NextEpisodeOf(base as string, token as string, showKey as string, clientId as string) as object
    episodes = []
    for each e in Arr_(PlexApi_Items(base, token, clientId, "/library/metadata/" + showKey + "/allLeaves", 2000))
        if e.type = "episode" then episodes.Push(e)
    end for
    return { episode: Plex_NextEpisode(episodes), queue: episodes }
end function

' ---------------------------------------------------------------- Search, people, lists

function PlexApi_Search(libraries as object, query as string, clientId as string) as object
    items = []
    people = []
    collections = []
    answered = false
    for each s in PlexApi_Servers(libraries)
        c = PlexApi_Container(s.base + "/hubs/search?query=" + UrlEncode_(query) + "&limit=30", s.token, clientId, 15000)
        if c <> invalid then
            answered = true
            hubs = Arr_(c.Hub)
            items.Append(Plex_ItemsFromHubs(hubs, s.base, ["movie", "show", "episode"]))
            people.Append(Plex_PeopleFromHubs(hubs, s.base))
            collections.Append(Plex_ItemsFromHubs(hubs, s.base, ["collection"]))
        end if
    end for
    split = Plex_SplitMatches(query, items)
    results = split.matches
    more = split.others
    ' Nothing by name, a misspelling most likely: then Plex's own guesses are the results.
    if results.Count() = 0 then
        results = more
        more = []
    end if
    return { results: results, more: more, people: Head_(people, 20), collections: collections, unreachable: not answered }
end function

' What a person is in, from the libraries on the server they were found on, newest first.
function PlexApi_Person(libraries as object, personId as string, serverBase as string, clientId as string) as object
    items = []
    for each l in libraries
        if serverBase = "" or l.base = serverBase then
            t = Iif_(l.type = "movie", "1", "2")
            got = PlexApi_Items(l.base, l.token, clientId, "/library/sections/" + l.key + "/all?type=" + t + "&actor=" + personId + "&sort=originallyAvailableAt:desc", 300)
            if got <> invalid then items.Append(got)
        end if
    end for
    return StableSort_(items, function(a as object, b as object) as boolean
        ya = 0
        yb = 0
        if a.year <> invalid then ya = a.year
        if b.year <> invalid then yb = b.year
        return ya > yb
    end function)
end function

function PlexApi_Children(base as string, token as string, path as string, clientId as string) as object
    return Arr_(PlexApi_Items(base, token, clientId, path, 500))
end function

' ---------------------------------------------------------------- Changing things

function PlexApi_SetWatched(base as string, token as string, ratingKey as string, watched as boolean, clientId as string) as boolean
    action = "unscrobble"
    if watched then action = "scrobble"
    r = Http_Ask(base + "/:/" + action + "?key=" + ratingKey + "&identifier=com.plexapp.plugins.library", "GET", PlexApi_Headers(token, clientId), "", 15000)
    return r.code >= 200 and r.code < 300
end function

function PlexApi_RemoveFromContinueWatching(base as string, token as string, ratingKey as string, clientId as string) as boolean
    r = Http_Ask(base + "/actions/removeFromContinueWatching?ratingKey=" + ratingKey, "PUT", PlexApi_Headers(token, clientId), "", 15000)
    return r.code >= 200 and r.code < 300
end function

' A sound track or subtitles chosen, kept with Plex as the other apps keep them. "0" turns subtitles off.
function PlexApi_SelectStreams(base as string, token as string, partId as string, audioId as string, subtitleId as string, clientId as string) as boolean
    q = []
    if audioId <> "" then q.Push("audioStreamID=" + audioId)
    if subtitleId <> "" then q.Push("subtitleStreamID=" + subtitleId)
    if q.Count() = 0 or partId = "" then return false
    r = Http_Ask(base + "/library/parts/" + partId + "?" + Join_(q, "&") + "&allParts=1", "PUT", PlexApi_Headers(token, clientId), "", 15000)
    return r.code >= 200 and r.code < 300
end function

' Where playback is, told to the server: what keeps Continue Watching right everywhere.
function PlexApi_Timeline(base as string, token as string, ratingKey as string, state as string, ms as integer, durationMs as integer, session as string, clientId as string) as boolean
    h = PlexApi_Headers(token, clientId)
    h["X-Plex-Session-Identifier"] = session
    url = base + "/:/timeline?ratingKey=" + ratingKey + "&key=" + UrlEncode_("/library/metadata/" + ratingKey) + "&identifier=com.plexapp.plugins.library&state=" + state + "&time=" + ms.ToStr() + "&duration=" + durationMs.ToStr() + "&playbackTime=" + ms.ToStr() + "&playQueueItemID=-1"
    r = Http_Ask(url, "GET", h, "", 10000)
    return r.code >= 200 and r.code < 300
end function

' ---------------------------------------------------------------- Profiles

' Who [token] belongs to, as a Home profile: { uuid, title, thumb, admin, restricted, protected },
' or invalid when plex.tv doesn't say.
function PlexApi_Account(plexTv as string, token as string, clientId as string) as dynamic
    r = Http_Ask(plexTv + "/api/v2/user", "GET", PlexApi_Headers(token, clientId), "", 15000)
    if r.code < 200 or r.code > 299 or r.json = invalid then return invalid
    return Plex_HomeUser(r.json)
end function

function PlexApi_HomeUsers(plexTv as string, token as string, clientId as string) as object
    r = Http_Ask(plexTv + "/api/v2/home/users", "GET", PlexApi_Headers(token, clientId), "", 15000)
    out = []
    if r.json = invalid then return out
    users = r.json.users
    if users = invalid then users = r.json
    for each u in Arr_(users)
        user = Plex_HomeUser(u)
        if user <> invalid then out.Push(user)
    end for
    return out
end function

' Another profile of the Plex Home: its own token, or invalid with why not.
function PlexApi_SwitchUser(plexTv as string, token as string, uuid as string, pin as string, clientId as string) as object
    url = plexTv + "/api/v2/home/users/" + uuid + "/switch"
    if pin <> "" then url = url + "?pin=" + pin
    r = Http_Ask(url, "POST", PlexApi_Headers(token, clientId), "", 15000)
    if r.code = 401 or r.code = 403 then return { token: invalid, error: "That PIN isn't right. Try again." }
    if r.json = invalid or Str_(r.json.authToken) = "" then return { token: invalid, error: "Couldn't switch profiles. Try again." }
    return { token: Str_(r.json.authToken), error: "" }
end function

function Head_(list as object, count as integer) as object
    out = []
    for each x in list
        if out.Count() >= count then exit for
        out.Push(x)
    end for
    return out
end function
