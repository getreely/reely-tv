' Reely, the owner's app for asking for movies and shows, read as the other apps read it
' (webos/src/api/reely.ts). Reading only; the asking is reelyapi.brs's.

function Reely_Images() as string
    return "https://image.tmdb.org/t/p"
end function

' "reely.example.com", "192.168.1.5:8788", "http://…/" and the rest, as an address.
function Reely_Normalize(raw as string) as string
    t = raw.Trim()
    while Len(t) > 0 and Right(t, 1) = "/"
        t = Left(t, Len(t) - 1)
    end while
    if t = "" then return t
    low = LCase(t)
    if Left(low, 7) = "http://" or Left(low, 8) = "https://" then return t
    return "http://" + t
end function

function Reely_IsValid(raw as string) as boolean
    t = Reely_Normalize(raw)
    re = CreateObject("roRegex", "^https?://[A-Za-z0-9.\-\[\]:]+(:\d+)?(/.*)?$", "i")
    return re.IsMatch(t)
end function

function Reely_HostOf(url as string) as string
    t = url
    at = Instr(1, t, "://")
    if at > 0 then t = Mid(t, at + 3)
    slash = Instr(1, t, "/")
    if slash > 0 then t = Left(t, slash - 1)
    return t
end function

' TMDB gives a path to put after its image address; TheTVDB gives a whole address.
function Reely_ImageUrl(value as string, image as string, size as string) as string
    v = value.Trim()
    if v = "" then return ""
    if LCase(Left(v, 4)) = "http" then return v
    base = image
    while Right(base, 1) = "/"
        base = Left(base, Len(base) - 1)
    end while
    while Left(v, 1) = "/"
        v = Mid(v, 2)
    end while
    return base + "/" + size + "/" + v
end function

' A title as Reely gives it, or invalid when it isn't one.
function Reely_TitleOf(o as dynamic, image as string) as dynamic
    if o = invalid or type(o) <> "roAssociativeArray" then return invalid
    kind = Str_(o.kind)
    if kind <> "movie" and kind <> "show" then return invalid
    tmdb = Int(Num_(o.tmdbId))
    tvdb = Int(Num_(o.tvdbId))
    if tmdb <= 0 and tvdb <= 0 then return invalid
    title = Str_(o.title)
    if title.Trim() = "" then return invalid
    poster = Str_(o.poster)
    year = Int(Num_(o.year))
    return { kind: kind, tmdbId: tmdb, tvdbId: tvdb, title: title, year: year, overview: Str_(o.overview).Trim(), poster: Reely_ImageUrl(poster, image, "w342"), posterPath: poster }
end function

' Unique across kinds: a film and a show can share a TMDB number.
function Reely_Key(t as object) as string
    if t.tmdbId > 0 then return t.kind + ":t" + t.tmdbId.ToStr()
    return t.kind + ":v" + t.tvdbId.ToStr()
end function

function Reely_TitlesOf(list as dynamic, image as string) as object
    out = []
    seen = {}
    for each o in Arr_(list)
        t = Reely_TitleOf(o, image)
        if t <> invalid and not seen.DoesExist(Reely_Key(t)) then
            seen[Reely_Key(t)] = true
            out.Push(t)
        end if
    end for
    return out
end function

' Reely's browsing rows, in the order they're shown and named as the other apps name them.
function Reely_ExploreRows(root as dynamic) as object
    rows = []
    if root = invalid or type(root) <> "roAssociativeArray" then return rows
    image = Str_(root.imageBase)
    if image = "" then image = Reely_Images()
    named = [["movies", "Trending Movies"], ["shows", "Trending Shows"], ["popularMovies", "Popular Movies"], ["popularShows", "Popular Shows"], ["topMovies", "Top Rated Movies"], ["topShows", "Top Rated Shows"]]
    for each pair in named
        titles = Reely_TitlesOf(root[pair[0]], image)
        if titles.Count() > 0 then rows.Push({ id: pair[0], title: pair[1], titles: titles })
    end for
    for each p in Arr_(root.providers)
        if type(p) = "roAssociativeArray" then
            titles = Reely_TitlesOf(p.results, image)
            if titles.Count() > 0 then
                kind = Iif_(Str_(p.kind) = "show", "Shows", "Movies")
                rows.Push({ id: "provider:" + Str_(p.key) + ":" + Str_(p.kind), title: kind + " on " + Str_(p.name), titles: titles })
            end if
        end if
    end for
    return rows
end function

' A title's page: { title, backdrop, genres, runtime, status, seasons, inLibraries }, or invalid.
function Reely_ParseDetail(root as dynamic, fallback as object) as dynamic
    if root = invalid or type(root) <> "roAssociativeArray" then return invalid
    p = root.preview
    if p = invalid or type(p) <> "roAssociativeArray" then return invalid
    image = Str_(root.imageBase)
    if image = "" then image = Reely_Images()
    t = Reely_TitleOf(p, image)
    if t = invalid then t = fallback
    genres = []
    for each g in Arr_(p.genres)
        if Str_(g).Trim() <> "" then genres.Push(Str_(g))
    end for
    seasons = []
    for each s in Arr_(p.seasons)
        if type(s) = "roAssociativeArray" then
            n = Int(Num_(s.number))
            ' Specials are season nought: asked for with the rest, not on their own.
            if n > 0 then
                name = Str_(s.name).Trim()
                if name = "" then name = "Season " + n.ToStr()
                seasons.Push({ number: n, name: name, episodes: Arr_(s.episodes).Count() })
            end if
        end if
    end for
    held = []
    for each id in Arr_(root.inLibraries)
        held.Push(Int(Num_(id)))
    end for
    return { title: t, backdrop: Reely_ImageUrl(Str_(p.backdrop), image, "w1280"), genres: genres, runtime: Int(Num_(p.runtime)), status: Str_(p.status).Trim(), seasons: seasons, inLibraries: held }
end function

' Where things go: { libraries, defaultLibraryId, adds } from /auth/me and /libraries.
function Reely_ParsePlaces(account as dynamic, libraries as dynamic) as object
    user = {}
    if account <> invalid and type(account) = "roAssociativeArray" and account.user <> invalid then user = account.user
    list = []
    if libraries <> invalid and type(libraries) = "roAssociativeArray" then
        for each l in Arr_(libraries.libraries)
            if type(l) = "roAssociativeArray" then list.Push({ id: Int(Num_(l.id)), name: Str_(l.name), kind: Str_(l.kind) })
        end for
    end if
    return { libraries: list, defaultLibraryId: Int(Num_(user.defaultLibraryId)), adds: Str_(user.role) = "admin" or user.mayAdd = true }
end function

' The libraries [t] could go to: the right kind, and not holding it already.
function Reely_LibrariesFor(places as object, t as object, holding as object) as object
    want = Iif_(t.kind = "show", "shows", "movies")
    held = {}
    for each id in holding
        held[id.ToStr()] = true
    end for
    out = []
    for each l in places.libraries
        if l.kind = want and not held.DoesExist(l.id.ToStr()) then out.Push(l)
    end for
    return out
end function

' Where it goes unless another is picked: the default, when it's one of them.
function Reely_PreferredLibrary(places as object, among as object) as dynamic
    for each l in among
        if l.id = places.defaultLibraryId then return l
    end for
    if among.Count() > 0 then return among[0]
    return invalid
end function

' How Reely marks a poster: Downloading, In library, Partial, or Requested.
function Reely_ParseMarks(movies as dynamic, shows as dynamic, asked as dynamic) as object
    marks = { movies: {}, showsByTmdb: {}, showsByTvdb: {}, requested: {} }
    for each mv in Arr_(movies)
        if type(mv) = "roAssociativeArray" and Int(Num_(mv.tmdbId)) > 0 then
            mark = "Requested"
            if Str_(mv.filePath).Trim() <> "" then mark = "In library"
            if mv.downloading = true then mark = "Downloading"
            marks.movies[Int(Num_(mv.tmdbId)).ToStr()] = mark
        end if
    end for
    for each s in Arr_(shows)
        if type(s) = "roAssociativeArray" then
            mark = "Partial"
            if Num_(s.aired) > 0 and Num_(s.wanted) = 0 then mark = "In library"
            if Num_(s.onDisk) = 0 then mark = "Requested"
            if s.downloading = true then mark = "Downloading"
            if Int(Num_(s.tmdbId)) > 0 then marks.showsByTmdb[Int(Num_(s.tmdbId)).ToStr()] = mark
            if Int(Num_(s.tvdbId)) > 0 then marks.showsByTvdb[Int(Num_(s.tvdbId)).ToStr()] = mark
        end if
    end for
    for each r in Arr_(asked)
        if type(r) = "roAssociativeArray" then
            kind = Str_(r.kind)
            if kind = "show" and Int(Num_(r.tvdbId)) > 0 then marks.requested["show-tvdb-" + Int(Num_(r.tvdbId)).ToStr()] = true
            if Int(Num_(r.tmdbId)) > 0 then marks.requested[kind + "-" + Int(Num_(r.tmdbId)).ToStr()] = true
        end if
    end for
    return marks
end function

function Reely_Badge(marks as object, t as object) as string
    held = invalid
    if t.kind = "show" then
        if t.tvdbId > 0 then held = marks.showsByTvdb[t.tvdbId.ToStr()]
        if held = invalid then held = marks.showsByTmdb[t.tmdbId.ToStr()]
    else
        held = marks.movies[t.tmdbId.ToStr()]
    end if
    if held <> invalid then return held
    if t.kind = "show" and t.tvdbId > 0 and marks.requested.DoesExist("show-tvdb-" + t.tvdbId.ToStr()) then return "Requested"
    if t.tmdbId > 0 and marks.requested.DoesExist(t.kind + "-" + t.tmdbId.ToStr()) then return "Requested"
    return ""
end function

' Whether Plex's own libraries have [t], by the outside ids its titles carry ("tmdb://603",
' "tvdb://81189"): marks.plexMovies and marks.plexShows, when they've been read.
function Reely_PlexHas(t as object, marks as object) as boolean
    if t.kind = "show" then
        shows = marks.plexShows
        if shows = invalid then return false
        if t.tvdbId > 0 and shows.DoesExist("tvdb://" + t.tvdbId.ToStr()) then return true
        return t.tmdbId > 0 and shows.DoesExist("tmdb://" + t.tmdbId.ToStr())
    end if
    movies = marks.plexMovies
    if movies = invalid then return false
    return t.tmdbId > 0 and movies.DoesExist("tmdb://" + t.tmdbId.ToStr())
end function

' This account's requests, newest first: { id, title, status, seasons }.
function Reely_ParseRecords(root as dynamic) as object
    out = []
    if root = invalid or type(root) <> "roAssociativeArray" then return out
    for each r in Arr_(root.requests)
        t = Reely_TitleOf(r, Reely_Images())
        if t <> invalid then
            seasons = invalid
            if type(r.seasons) = "roArray" then
                seasons = []
                for each n in r.seasons
                    seasons.Push(Int(Num_(n)))
                end for
            end if
            out.Push({ id: Int(Num_(r.id)), title: t, status: Str_(r.status), seasons: seasons })
        end if
    end for
    return StableSort_(out, function(a as object, b as object) as boolean
        return a.id > b.id
    end function)
end function

' What a poster says: Reely's own word, else where this account's ask has got to.
function Reely_BadgeFor(t as object, marks as object, mine as object) as string
    mark = Reely_Badge(marks, t)
    ' On the Plex server already, though Reely didn't add it: in the library all the same.
    if (mark = "" or mark = "Requested") and Reely_PlexHas(t, marks) then return "In library"
    if mark <> "" and mark <> "Requested" then return mark
    key = Reely_Key(t)
    for each r in mine
        if Reely_Key(r.title) = key then
            if r.status = "approved" then return "Approved"
            if r.status = "denied" then return "Declined"
            if r.status = "pending" then return "Requested"
        end if
    end for
    return mark
end function

' The rows less what's in the library already; a row left empty isn't shown.
function Reely_ShownRows(rows as object, marks as object, mine as object) as object
    out = []
    for each row in rows
        titles = []
        for each t in row.titles
            if Reely_BadgeFor(t, marks, mine) <> "In library" then titles.Push(t)
        end for
        if titles.Count() > 0 then out.Push({ id: row.id, title: row.title, titles: titles })
    end for
    return out
end function

' Reely's errors are {"error": "…"}, written to be shown as they are; never markup.
function Reely_ErrorOf(body as string) as string
    j = ParseJson(body)
    if j = invalid or type(j) <> "roAssociativeArray" then return ""
    e = Str_(j.error).Trim()
    if Instr(1, e, "<") > 0 and Instr(1, e, ">") > 0 then return ""
    return e
end function

function Reely_OwnerLinkBrokenText() as string
    return "Reely's link to Plex has stopped working, so it can't check who the server is shared with. The server's owner can fix it in Reely: Settings, Plex, Unlink, then Link my Plex account."
end function

function Reely_PlexRejectedText() as string
    return "Plex didn't accept this device's sign-in, so Reely can't sign you in. Sign out of Plex in Settings and sign in again, then connect."
end function

' What to say when signing in didn't work, from the answer's code and body.
function Reely_SignInProblem(code as integer, body as string) as string
    if code >= 200 and code < 300 then return ""
    if code = 0 then return "Couldn't reach Reely."
    j = ParseJson(body)
    raw = ""
    if j <> invalid and type(j) = "roAssociativeArray" then raw = LCase(Str_(j.error))
    if Instr(1, raw, "didn't accept that sign-in") > 0 then return Reely_PlexRejectedText()
    ' The sharing check, made with the owner's saved Plex sign-in, refused: the owner's to fix.
    if code = 502 and Left(raw, 7) = "plex.tv" then return Reely_OwnerLinkBrokenText()
    if code = 403 then return "Your Plex account doesn't have access to this server's requests."
    if code = 404 then return "That server doesn't sign in from the TV yet. Update Reely."
    if code = 412 then return "Signing in with Plex isn't set up on this Reely server yet."
    if code = 429 then return "Too many tries. Wait a minute and try again."
    e = Reely_ErrorOf(body)
    if e <> "" then return e
    return "Reely couldn't do that. Try again."
end function

' The cookie Reely set: "name=value" from a Set-Cookie header, or "".
function Reely_CookieFrom(headers as dynamic) as string
    for each h in Arr_(headers)
        for each k in h
            if LCase(k) = "set-cookie" then
                v = Str_(h[k])
                semi = Instr(1, v, ";")
                if semi > 0 then v = Left(v, semi - 1)
                return v.Trim()
            end if
        end for
    end for
    return ""
end function

' Which of this account's approved requests have arrived: in the library (a show partly
' there counts); anything in [seen], keyed by Reely_Key, has been said already.
function Reely_ReadyRequests(mine as object, marks as object, seen as object) as object
    out = []
    keys = {}
    for each r in mine
        key = Reely_Key(r.title)
        if r.status = "approved" and not seen.DoesExist(key) and not keys.DoesExist(key) then
            mark = Reely_Badge(marks, r.title)
            if mark = "In library" or (r.title.kind = "show" and mark = "Partial") then
                keys[key] = true
                out.Push(r.title)
            end if
        end if
    end for
    return out
end function

' The Plex title a request became: the same kind and name, and the same year when both say.
function Reely_FindInPlex(t as object, items as object) as dynamic
    for each i in items
        if i.type = t.kind and LCase(i.title) = LCase(t.title) then
            if t.year = 0 or i.year = invalid or i.year = 0 or i.year = t.year then return i
        end if
    end for
    return invalid
end function
