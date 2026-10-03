' The provider's films and series, as the other apps have them (XtreamVod.kt, VodItems.kt,
' IptvLibrary.kt, webos/src/api/vod.ts): read, tidied, matched with Plex, and shown as the
' same items as Plex's, marked IPTV, with where each was left kept on the Roku.
' Reading only; the asking is vodapi.brs's.

function Vod_Source() as string
    return "iptv:"
end function

function Vod_IsIptv(item as dynamic) as boolean
    if item = invalid then return false
    return Str_(item.serverBase) = "iptv:"
end function

' Made once: a catalogue is thousands of names, each read with these.
function Vod_Re_() as object
    if m.vodRe_ = invalid then
        m.vodRe_ = {
            bracketed: CreateObject("roRegex", "^\s*[\[|(]\s*([A-Za-z0-9+ ]{1,8})\s*[\]|)]\s*[-:|]?\s*", ""),
            bare: CreateObject("roRegex", "^\s*([A-Z]{2}|4K|UHD|FHD|HD|SD|HEVC|VIP|MULTI|NF|AMZ|DSNP|ATV|HBO|D\+)\s*[-:|]\s+", ""),
            year: CreateObject("roRegex", "\s*(?:[(\[]\s*((?:19|20)\d{2})\s*[)\]]|(?:-|–)\s*((?:19|20)\d{2}))\s*$", ""),
            article: CreateObject("roRegex", "^(the|a|an)\s+", "i"),
            junk: CreateObject("roRegex", "[\x00-\x2F\x3A-\x40\x5B-\x60\x7B-\x7F]", ""),
            digits: CreateObject("roRegex", "^\d+$", ""),
            split: CreateObject("roRegex", "[,/]", ""),
            wide: CreateObject("roRegex", "[^\x00-\x7F]", ""),
            folds: []
        }
        ' Alternatives rather than a [class]: each accented letter is more than one byte.
        for each f in [["a", "à|á|â|ã|ä|å|À|Á|Â|Ã|Ä|Å"], ["c", "ç|Ç"], ["e", "è|é|ê|ë|È|É|Ê|Ë"], ["i", "ì|í|î|ï|Ì|Í|Î|Ï"], ["n", "ñ|Ñ"], ["o", "ò|ó|ô|õ|ö|ø|Ò|Ó|Ô|Õ|Ö|Ø"], ["u", "ù|ú|û|ü|Ù|Ú|Û|Ü"], ["y", "ý|ÿ|Ý"], ["ae", "æ|Æ"], ["oe", "œ|Œ"], ["ss", "ß"]]
            m.vodRe_.folds.Push({ to: f[0], re: CreateObject("roRegex", f[1], "") })
        end for
    end if
    return m.vodRe_
end function

' Lower case, accents aside: "Amélie" is "amelie", as the other apps match names.
function Vod_Fold_(text as string) as string
    low = LCase(text)
    re = Vod_Re_()
    if not re.wide.IsMatch(low) then return low
    for each f in re.folds
        low = f.re.ReplaceAll(low, f.to)
    end for
    return low
end function

' ---------------------------------------------------------------- Names

' "EN - The Matrix (1999)" as title, year and tag.
function Vod_ParseName(raw as string, knownYear = invalid as dynamic) as object
    re = Vod_Re_()
    name = raw.Trim()
    tag = invalid
    found = re.bracketed.Match(name)
    if found.Count() = 0 then found = re.bare.Match(name)
    if found.Count() > 1 then
        rest = Mid(name, Len(found[0]) + 1).Trim()
        if rest <> "" then
            tag = UCase(Str_(found[1]).Trim())
            name = rest
        end if
    end if
    year = knownYear
    y = re.year.Match(name)
    if y.Count() > 0 then
        rest = Left(name, Len(name) - Len(y[0])).Trim()
        if rest <> "" then
            if year = invalid then
                digits = Str_(y[1])
                if digits = "" and y.Count() > 2 then digits = Str_(y[2])
                if digits <> "" then year = digits.ToInt()
            end if
            name = rest
        end if
    end if
    if name = "" then name = raw.Trim()
    return { name: name, year: year, tag: tag }
end function

' The name as matching sees it: lower case, nothing but its letters and digits.
function Vod_NameKey(name as string) as string
    return Vod_Re_().junk.ReplaceAll(Vod_Fold_(name), "")
end function

' "Matrix, The" order: the name without a leading article, or "" when it has none.
function Vod_SortName(name as string) as string
    found = Vod_Re_().article.Match(name)
    if found.Count() = 0 then return ""
    rest = Mid(name, Len(found[0]) + 1).Trim()
    return rest
end function

' The A–Z order's key: titles under "#" first, then by letter, then by name.
function Vod_SortKey_(name as string) as string
    s = Vod_SortName(name)
    if s = "" then s = name.Trim()
    s = Vod_Fold_(s)
    first = Left(s, 1)
    if first >= "a" and first <= "z" then return "1" + s
    return "0" + s
end function

' The rail letter a title goes under: "2012" under #.
function Vod_LetterOf(sortKey as string) as string
    if Left(sortKey, 1) <> "1" then return "#"
    return UCase(Mid(sortKey, 2, 1))
end function

' ---------------------------------------------------------------- Reading the provider

' A year written at the start of [text], 1900 to 2100; else invalid.
function Vod_Year_(text as string) as dynamic
    head = Left(text.Trim(), 4)
    if not Vod_Re_().digits.IsMatch(head) then return invalid
    y = head.ToInt()
    if y < 1900 or y > 2100 then return invalid
    return y
end function

function Vod_ReadTitles(list as dynamic, series as boolean) as object
    out = []
    seen = {}
    for each entry in Arr_(list)
        if type(entry) = "roAssociativeArray" then
            t = Vod_TitleOf(entry, series)
            if t <> invalid and not seen.DoesExist(t.id.ToStr()) then
                seen[t.id.ToStr()] = true
                out.Push(t)
            end if
        end if
    end for
    return out
end function

' One film or series from the provider's list, or invalid when it can't be used.
function Vod_TitleOf(f as object, series as boolean) as dynamic
    re = Vod_Re_()
    if series then idText = Str_(f.series_id).Trim() else idText = Str_(f.stream_id).Trim()
    if not re.digits.IsMatch(idText) then return invalid
    raw = Str_(f.name).Trim()
    if raw = "" then raw = Str_(f.title).Trim()
    if raw = "" then return invalid
    ' The list's year when it's a number; else the release date's.
    listed = Vod_Year_(Str_(f.year))
    if listed = invalid then
        date = Str_(f.releaseDate)
        if date = "" then date = Str_(f.release_date)
        listed = Vod_Year_(date)
    end if
    parsed = Vod_ParseName(raw, listed)
    if series then poster = Str_(f.cover) else poster = Str_(f.stream_icon)
    if LCase(Left(poster, 4)) <> "http" then poster = ""
    rating = Val(Str_(f.rating))
    if rating < 0 then rating = 0
    tmdb = Str_(f.tmdb).Trim()
    if tmdb = "" then tmdb = Str_(f.tmdb_id).Trim()
    if tmdb = "0" then tmdb = ""
    if series then added = Num_(f.last_modified) else added = Num_(f.added)
    year = 0
    if parsed.year <> invalid then year = parsed.year
    return {
        series: series,
        id: idText.ToInt(),
        name: parsed.name,
        year: parsed.year,
        yr: year,
        tag: parsed.tag,
        poster: poster,
        rating: rating,
        added: Int(added),
        categoryId: Str_(f.category_id).Trim(),
        tmdbId: tmdb,
        extension: Str_(f.container_extension).Trim(),
        plot: Str_(f.plot).Trim(),
        genre: Str_(f.genre).Trim(),
        key: Vod_NameKey(parsed.name),
        sk: Vod_SortKey_(parsed.name)
    }
end function

function Vod_Text_(v as dynamic) as string
    t = Str_(v).Trim()
    if t = "null" then return ""
    return t
end function

function Vod_Url_(v as dynamic) as string
    t = Vod_Text_(v)
    if LCase(Left(t, 4)) = "http" then return t
    return ""
end function

function Vod_FirstUrl_(v as dynamic) as string
    if type(v) = "roArray" then
        for each x in v
            u = Vod_Url_(x)
            if u <> "" then return u
        end for
        return ""
    end if
    return Vod_Url_(v)
end function

' "Keanu Reeves, Carrie-Anne Moss" as names, each once.
function Vod_Names_(v as dynamic) as object
    out = []
    t = Vod_Text_(v)
    if t = "" then return out
    seen = {}
    for each part in Vod_Re_().split.Split(t)
        name = part.Trim()
        if name <> "" and not seen.DoesExist(name) then
            seen[name] = true
            out.Push(name)
        end if
    end for
    return out
end function

' "01:42:10" in seconds, or 0.
function Vod_DurationOf_(t as string) as integer
    if t = "" then return 0
    total = 0
    for each p in t.Split(":")
        part = p.Trim()
        if not Vod_Re_().digits.IsMatch(part) then return 0
        total = total * 60 + part.ToInt()
    end for
    return total
end function

function Vod_Seconds_(secs as dynamic, text as dynamic) as integer
    n = Int(Num_(secs))
    if n > 0 then return n
    return Vod_DurationOf_(Vod_Text_(text))
end function

function Vod_Obj_(v as dynamic) as object
    if v <> invalid and type(v) = "roAssociativeArray" then return v
    return {}
end function

function Vod_ParseMovieInfo(root as dynamic) as dynamic
    if root = invalid or type(root) <> "roAssociativeArray" then return invalid
    info = Vod_Obj_(root.info)
    movie = Vod_Obj_(root.movie_data)
    name = Vod_Text_(info.name)
    if name = "" then name = Vod_Text_(movie.name)
    plot = Vod_Text_(info.plot)
    if plot = "" then plot = Vod_Text_(info.description)
    cast = Vod_Names_(info.cast)
    if cast.Count() = 0 then cast = Vod_Names_(info.actors)
    poster = Vod_Url_(info.movie_image)
    if poster = "" then poster = Vod_Url_(info.cover_big)
    released = Vod_Text_(info.releasedate)
    if released = "" then released = Vod_Text_(info.release_date)
    age = Vod_Text_(info.age)
    if age = "" then age = Vod_Text_(info.mpaa)
    tmdb = Vod_Text_(info.tmdb_id)
    if tmdb = "0" then tmdb = ""
    rating = Val(Vod_Text_(info.rating))
    if rating < 0 then rating = 0
    return {
        name: name,
        plot: plot,
        cast: cast,
        directors: Vod_Names_(info.director),
        genres: Vod_Names_(info.genre),
        durationMs: Vod_Seconds_(info.duration_secs, info.duration) * 1000,
        backdrop: Vod_FirstUrl_(info.backdrop_path),
        poster: poster,
        releaseDate: released,
        rating: rating,
        tmdbId: tmdb,
        extension: Vod_Text_(movie.container_extension),
        ageRating: age
    }
end function

function Vod_ParseSeriesInfo(root as dynamic) as dynamic
    if root = invalid or type(root) <> "roAssociativeArray" then return invalid
    info = Vod_Obj_(root.info)
    episodes = []
    seen = {}
    all = root.episodes
    if type(all) = "roArray" then
        for each list in all
            Vod_ReadSeason_(episodes, seen, "", list)
        end for
    else if type(all) = "roAssociativeArray" then
        for each k in all
            Vod_ReadSeason_(episodes, seen, k, all[k])
        end for
    end if
    about = {}
    for each s in Arr_(root.seasons)
        if type(s) = "roAssociativeArray" then
            n = Str_(s.season_number).Trim()
            if Vod_Re_().digits.IsMatch(n) then about[n.ToInt().ToStr()] = s
        end if
    end for
    numbers = []
    have = {}
    for each e in episodes
        if not have.DoesExist(e.season.ToStr()) then
            have[e.season.ToStr()] = true
            numbers.Push({ n: e.season })
        end if
    end for
    numbers.SortBy("n")
    seasons = []
    for each x in numbers
        n = x.n
        list = []
        for each e in episodes
            if e.season = n then
                ' Unnumbered ones last, each kept in the provider's order.
                order = 100000
                if e.number <> invalid then order = e.number
                e.order = order
                list.Push(e)
            end if
        end for
        list.SortBy("order")
        poster = ""
        s = about[n.ToStr()]
        if s <> invalid then
            poster = Vod_Url_(s.cover_big)
            if poster = "" then poster = Vod_Url_(s.cover)
        end if
        seasons.Push({ number: n, name: Iif_(n = 0, "Specials", "Season " + n.ToStr()), poster: poster, episodes: list })
    end for
    tmdb = Vod_Text_(info.tmdb)
    if tmdb = "" or tmdb = "0" then tmdb = Vod_Text_(info.tmdb_id)
    if tmdb = "0" then tmdb = ""
    released = Vod_Text_(info.releaseDate)
    if released = "" then released = Vod_Text_(info.release_date)
    rating = Val(Vod_Text_(info.rating))
    if rating < 0 then rating = 0
    return {
        name: Vod_Text_(info.name),
        plot: Vod_Text_(info.plot),
        cast: Vod_Names_(info.cast),
        directors: Vod_Names_(info.director),
        genres: Vod_Names_(info.genre),
        backdrop: Vod_FirstUrl_(info.backdrop_path),
        poster: Vod_Url_(info.cover),
        releaseDate: released,
        rating: rating,
        tmdbId: tmdb,
        seasons: seasons
    }
end function

' A season's episodes, each once. Season 0 is the specials: a number, not a missing one.
sub Vod_ReadSeason_(out as object, seen as object, key as string, list as dynamic)
    re = Vod_Re_()
    for each e in Arr_(list)
        if type(e) = "roAssociativeArray" then
            idText = Str_(e.id).Trim()
            if re.digits.IsMatch(idText) and not seen.DoesExist(idText) then
                seen[idText] = true
                d = Vod_Obj_(e.info)
                season = 1
                st = Str_(e.season).Trim()
                if re.digits.IsMatch(st) then
                    season = st.ToInt()
                else if re.digits.IsMatch(key) then
                    season = key.ToInt()
                end if
                number = invalid
                nt = Str_(e.episode_num).Trim()
                if re.digits.IsMatch(nt) then number = nt.ToInt()
                title = Vod_Text_(e.title)
                if title <> "" then title = Vod_ParseName(title).name else title = "Episode " + nt
                aired = Vod_Text_(d.releasedate)
                if aired = "" then aired = Vod_Text_(d.air_date)
                out.Push({
                    id: idText.ToInt(),
                    season: season,
                    number: number,
                    title: title,
                    extension: Vod_Text_(e.container_extension),
                    plot: Vod_Text_(d.plot),
                    still: Vod_Url_(d.movie_image),
                    durationMs: Vod_Seconds_(d.duration_secs, d.duration) * 1000,
                    airDate: aired
                })
            end if
        end if
    end for
end sub

function Vod_MovieUrl(c as object, id as integer, ext as string) as string
    if ext = "" then ext = "mp4"
    return c.base + "/movie/" + UrlEncode_(c.username) + "/" + UrlEncode_(c.password) + "/" + id.ToStr() + "." + ext
end function

function Vod_EpisodeUrl(c as object, id as integer, ext as string) as string
    if ext = "" then ext = "mp4"
    return c.base + "/series/" + UrlEncode_(c.username) + "/" + UrlEncode_(c.password) + "/" + id.ToStr() + "." + ext
end function

' ---------------------------------------------------------------- Keys and items

function Vod_MovieKey(id as integer, ext as string) as string
    if ext = "" then return "m" + id.ToStr()
    return "m" + id.ToStr() + "." + ext
end function

function Vod_ShowKey(id as integer) as string
    return "s" + id.ToStr()
end function

function Vod_SeasonKey(showId as integer, n as integer) as string
    return "s" + showId.ToStr() + ":" + n.ToStr()
end function

function Vod_EpisodeKey(id as integer, ext as string) as string
    if ext = "" then return "e" + id.ToStr()
    return "e" + id.ToStr() + "." + ext
end function

' What a key stands for: { kind: movie|episode, id, extension }, { kind: show, id } or
' { kind: season, showId, number }; invalid when it isn't one of ours.
function Vod_ParseKey(key as string) as dynamic
    if Len(key) < 2 then return invalid
    digits = Vod_Re_().digits
    kind = Left(key, 1)
    body = Mid(key, 2)
    if kind = "m" or kind = "e" then
        idText = body
        ext = ""
        dot = Instr(1, body, ".")
        if dot > 0 then
            idText = Left(body, dot - 1)
            ext = Mid(body, dot + 1)
        end if
        if not digits.IsMatch(idText) then return invalid
        if kind = "m" then return { kind: "movie", id: idText.ToInt(), extension: ext }
        return { kind: "episode", id: idText.ToInt(), extension: ext }
    else if kind = "s" then
        colon = Instr(1, body, ":")
        if colon > 0 then
            s = Left(body, colon - 1)
            n = Mid(body, colon + 1)
            if digits.IsMatch(s) and digits.IsMatch(n) then return { kind: "season", showId: s.ToInt(), number: n.ToInt() }
            return invalid
        end if
        if digits.IsMatch(body) then return { kind: "show", id: body.ToInt() }
    end if
    return invalid
end function

' An item shaped as Plex's are, from the provider.
function Vod_Blank_(ratingKey as string, title as string, kind as string) as object
    return {
        ratingKey: ratingKey, title: title, titleSort: "", type: kind, thumb: "", art: "", summary: "",
        year: invalid, index: invalid, parentIndex: invalid, parentRatingKey: "", grandparentRatingKey: "",
        grandparentTitle: "", grandparentThumb: "", durationMs: 0, viewOffsetMs: 0, leafCount: 0,
        viewedLeafCount: 0, viewCount: 0, addedAt: 0, lastViewedAt: 0, librarySectionId: "", guid: "",
        parentTitle: "", serverBase: "iptv:"
    }
end function

function Vod_ItemOf(t as object) as object
    if t.series then
        i = Vod_Blank_(Vod_ShowKey(t.id), t.name, "show")
    else
        i = Vod_Blank_(Vod_MovieKey(t.id, t.extension), t.name, "movie")
    end if
    i.thumb = t.poster
    i.summary = t.plot
    i.year = t.year
    i.addedAt = t.added
    i.librarySectionId = t.categoryId
    i.titleSort = Vod_SortName(t.name)
    return i
end function

function Vod_SeasonItems(showId as integer, showName as string, poster as string, info as object) as object
    out = []
    for each s in info.seasons
        i = Vod_Blank_(Vod_SeasonKey(showId, s.number), s.name, "season")
        i.thumb = Iif_(s.poster <> "", s.poster, poster)
        i.index = s.number
        i.parentRatingKey = Vod_ShowKey(showId)
        i.parentTitle = showName
        i.leafCount = s.episodes.Count()
        out.Push(i)
    end for
    return out
end function

function Vod_EpisodeItems(showId as integer, showName as string, poster as string, backdrop as string, season as object) as object
    out = []
    for each e in season.episodes
        i = Vod_Blank_(Vod_EpisodeKey(e.id, e.extension), e.title, "episode")
        i.thumb = Iif_(e.still <> "", e.still, poster)
        i.art = backdrop
        i.summary = e.plot
        i.index = e.number
        if season.number > 0 then i.parentIndex = season.number
        i.parentRatingKey = Vod_SeasonKey(showId, season.number)
        i.parentTitle = season.name
        i.grandparentRatingKey = Vod_ShowKey(showId)
        i.grandparentTitle = showName
        i.grandparentThumb = poster
        i.durationMs = e.durationMs
        out.Push(i)
    end for
    return out
end function

' A title's page, shaped as Plex's: from the list's title and the panel's info.
function Vod_DetailOf(key as string, t as dynamic, info as dynamic, show as boolean) as object
    title = Iif_(show, "Series", "Film")
    if info <> invalid and info.name <> "" then title = Vod_ParseName(info.name).name
    if t <> invalid then title = t.name
    d = Vod_Blank_(key, title, Iif_(show, "show", "movie"))
    summary = ""
    if info <> invalid then summary = info.plot
    if summary = "" and t <> invalid then summary = t.plot
    d.summary = summary
    if t <> invalid then d.year = t.year
    if d.year = invalid and info <> invalid then d.year = Vod_Year_(info.releaseDate)
    d.thumb = ""
    if info <> invalid then d.thumb = info.poster
    if d.thumb = "" and t <> invalid then d.thumb = t.poster
    if info <> invalid then d.art = info.backdrop
    d.contentRating = ""
    d.durationMs = 0
    if info <> invalid and not show then
        d.contentRating = info.ageRating
        d.durationMs = info.durationMs
    end if
    d.studio = ""
    d.tagline = ""
    d.theme = ""
    d.genres = []
    if info <> invalid then d.genres = info.genres
    if d.genres.Count() = 0 and t <> invalid and t.genre <> "" then d.genres = [t.genre]
    d.directors = []
    roles = []
    if info <> invalid then
        d.directors = info.directors
        for each name in info.cast
            if roles.Count() < 24 then roles.Push({ id: "", name: name, role: "", thumb: "" })
        end for
    end if
    d.roles = roles
    d.versions = []
    d.onDeckKey = ""
    d.onDeckSeasonKey = ""
    if show and info <> invalid then
        total = 0
        for each s in info.seasons
            total = total + s.episodes.Count()
        end for
        d.leafCount = total
    end if
    return d
end function

' ---------------------------------------------------------------- Where things were left

' Kept on the Roku: the provider keeps nothing. Each mark: { k, o, d, w, at } and enough of
' the item to show it again in Continue Watching (t, g, p, s, n, y).
function Vod_WatchedFraction() as float
    return 0.9
end function

sub Vod_Put_(marks as object, item as object, offsetMs as integer, durationMs as integer, watched as boolean, at as integer)
    showId = 0
    season = -1
    if item.type = "episode" then
        k = Vod_ParseKey(item.parentRatingKey)
        if k <> invalid and k.kind = "season" then
            showId = k.showId
            season = k.number
        end if
    end if
    number = 0
    if item.index <> invalid then number = item.index
    year = 0
    if item.year <> invalid then year = item.year
    marks[item.ratingKey] = { k: item.ratingKey, o: offsetMs, d: durationMs, w: watched, at: at, t: item.title, g: item.grandparentTitle, p: showId, s: season, n: number, y: year }
end sub

sub Vod_Progress(marks as object, item as object, positionMs as integer, durationMs as integer, now as integer)
    if positionMs <= 0 then return
    done = durationMs > 0 and positionMs >= durationMs * Vod_WatchedFraction()
    before = marks[item.ratingKey]
    watched = done or (before <> invalid and before.w = true)
    offset = positionMs
    if done then offset = 0
    Vod_Put_(marks, item, offset, durationMs, watched, now)
end sub

sub Vod_SetWatched(marks as object, items as object, watched as boolean, now as integer)
    for each item in items
        before = marks[item.ratingKey]
        duration = item.durationMs
        at = now
        if before <> invalid then
            duration = before.d
            if not watched then at = before.at
        end if
        Vod_Put_(marks, item, 0, Int(duration), watched, at)
    end for
end sub

sub Vod_Forget(marks as object, key as string)
    mark = marks[key]
    if mark <> invalid then mark.o = 0
end sub

' How many of a show's (or a season's) episodes have been watched.
function Vod_WatchedUnder(marks as object, key as string, season as boolean) as integer
    k = Vod_ParseKey(key)
    if k = invalid then return 0
    count = 0
    for each id in marks
        mk = marks[id]
        if mk.w = true and mk.p > 0 then
            if season then
                if k.kind = "season" and mk.p = k.showId and mk.s = k.number then count = count + 1
            else if k.kind = "show" and mk.p = k.id then
                count = count + 1
            end if
        end if
    end for
    return count
end function

' [item] with where it was left: from the marks, as Plex's come with theirs.
function Vod_Apply(marks as object, item as object) as object
    if item.type = "show" or item.type = "season" then
        item.viewedLeafCount = Vod_WatchedUnder(marks, item.ratingKey, item.type = "season")
        return item
    end if
    mk = marks[item.ratingKey]
    if mk = invalid then return item
    item.viewOffsetMs = mk.o
    if mk.w = true then
        if item.viewCount < 1 then item.viewCount = 1
    else
        item.viewCount = 0
    end if
    if item.durationMs <= 0 then item.durationMs = mk.d
    item.lastViewedAt = mk.at
    return item
end function

' A mark as an item again, its poster from the catalogue where it's still there.
function Vod_MarkItem(lib as object, mk as object) as dynamic
    k = Vod_ParseKey(mk.k)
    if k = invalid then return invalid
    if k.kind = "movie" then
        t = lib.moviesById[k.id.ToStr()]
        if t <> invalid then
            i = Vod_ItemOf(t)
        else
            i = Vod_Blank_(mk.k, mk.t, "movie")
            if mk.y > 0 then i.year = mk.y
        end if
    else if k.kind = "episode" then
        i = Vod_Blank_(mk.k, mk.t, "episode")
        if mk.n > 0 then i.index = mk.n
        if mk.s > 0 then i.parentIndex = mk.s
        if mk.p > 0 then
            i.grandparentRatingKey = Vod_ShowKey(mk.p)
            if mk.s >= 0 then i.parentRatingKey = Vod_SeasonKey(mk.p, mk.s)
            show = lib.seriesById[mk.p.ToStr()]
            if show <> invalid then
                i.grandparentThumb = show.poster
                i.thumb = show.poster
            end if
        end if
        i.grandparentTitle = mk.g
    else
        return invalid
    end if
    i.ratingKey = mk.k
    i.durationMs = mk.d
    one = {}
    one[mk.k] = mk
    return Vod_Apply(one, i)
end function

' Started and not finished, the latest first.
function Vod_ContinueWatching(lib as object, marks as object) as object
    open = []
    for each id in marks
        mk = marks[id]
        if mk.o > 0 and mk.w <> true then open.Push(mk)
    end for
    open.SortBy("at", "r")
    out = []
    for each mk in open
        i = Vod_MarkItem(lib, mk)
        if i <> invalid then
            i.viewOffsetMs = mk.o
            i.lastViewedAt = mk.at
            out.Push(i)
        end if
    end for
    return out
end function

' The marks to keep, the latest [most]: the Roku's registry is small.
function Vod_PackMarks(marks as object, most as integer) as object
    list = []
    for each id in marks
        list.Push(marks[id])
    end for
    list.SortBy("at", "r")
    out = []
    for each mk in list
        if out.Count() >= most then exit for
        out.Push(mk)
    end for
    return out
end function

function Vod_UnpackMarks(list as dynamic) as object
    out = {}
    for each mk in Arr_(list)
        if type(mk) = "roAssociativeArray" and Str_(mk.k) <> "" then
            out[mk.k] = { k: mk.k, o: Int(Num_(mk.o)), d: Int(Num_(mk.d)), w: Bool_(mk.w), at: Int(Num_(mk.at)), t: Str_(mk.t), g: Str_(mk.g), p: Int(Num_(mk.p)), s: Int(Num_(mk.s)), n: Int(Num_(mk.n)), y: Int(Num_(mk.y)) }
        end if
    end for
    return out
end function

' ---------------------------------------------------------------- Matching with Plex

function Vod_NewIndex() as object
    return { tmdb: {}, named: {}, names: {} }
end function

sub Vod_IndexAdd(index as object, tmdb as string, key as string, year as dynamic)
    if tmdb <> "" then index.tmdb[tmdb] = true
    if key = "" then return
    index.names[key] = true
    if year <> invalid then
        for y = year - 1 to year + 1
            index.named[key + "|" + y.ToStr()] = true
        end for
    end if
end sub

function Vod_IndexHas(index as object, tmdb as string, key as string, year as dynamic) as boolean
    if tmdb <> "" and index.tmdb.DoesExist(tmdb) then return true
    if key = "" then return false
    if year <> invalid then return index.named.DoesExist(key + "|" + year.ToStr())
    return index.names.DoesExist(key)
end function

function Vod_IndexEmpty(index as object) as boolean
    return index.tmdb.Count() = 0 and index.names.Count() = 0
end function

function Vod_TmdbOf(guid as string) as string
    if Left(guid, 7) = "tmdb://" and Len(guid) > 7 then return Mid(guid, 8)
    return ""
end function

' A Plex title, from its library's list with guids: into [index], and its tmdb id kept
' by where it is, for leaving Plex's copy out when the provider's wins.
sub Vod_AddPlexEntry(index as object, tmdbs as object, meta as object, base as string)
    tmdb = ""
    for each g in Arr_(meta.Guid)
        if tmdb = "" and type(g) = "roAssociativeArray" then tmdb = Vod_TmdbOf(Str_(g.id))
    end for
    year = Pos_(meta.year)
    if tmdb <> "" then index.tmdb[tmdb] = true
    for each name in [Str_(meta.title), Str_(meta.originalTitle)]
        if name <> "" then Vod_IndexAdd(index, "", Vod_NameKey(name), year)
    end for
    tmdbs[base + "|" + Str_(meta.ratingKey)] = tmdb
end sub

function Vod_IptvIndex(titles as object) as object
    index = Vod_NewIndex()
    for each t in titles
        Vod_IndexAdd(index, t.tmdbId, t.key, t.year)
    end for
    return index
end function

' ---------------------------------------------------------------- The library

function Vod_NewLibrary() as object
    return {
        movies: [], series: [], movieCategories: [], seriesCategories: [],
        moviesById: {}, seriesById: {},
        iptvMovies: Vod_NewIndex(), iptvShows: Vod_NewIndex(),
        plexMovies: Vod_NewIndex(), plexShows: Vod_NewIndex(), plexTmdb: {},
        loadedAt: 0
    }
end function

sub Vod_SetCatalog(lib as object, movies as object, series as object, movieCategories as object, seriesCategories as object)
    lib.movies = movies
    lib.series = series
    lib.movieCategories = movieCategories
    lib.seriesCategories = seriesCategories
    lib.moviesById = {}
    for each t in movies
        lib.moviesById[t.id.ToStr()] = t
    end for
    lib.seriesById = {}
    for each t in series
        lib.seriesById[t.id.ToStr()] = t
    end for
    lib.iptvMovies = Vod_IptvIndex(movies)
    lib.iptvShows = Vod_IptvIndex(series)
end sub

' What the provider has that's shown: all of it when its copy wins, else what Plex hasn't.
function Vod_Shown(lib as object, movies as boolean, iptvWins as boolean) as object
    if movies then
        titles = lib.movies
        plex = lib.plexMovies
    else
        titles = lib.series
        plex = lib.plexShows
    end if
    if iptvWins or Vod_IndexEmpty(plex) then return titles
    out = []
    for each t in titles
        if not Vod_IndexHas(plex, t.tmdbId, t.key, t.year) then out.Push(t)
    end for
    return out
end function

' Plex's copy of something the provider has too, left out where the provider's wins.
function Vod_Hides(lib as object, item as object, iptvWins as boolean) as boolean
    if not iptvWins or Vod_IsIptv(item) then return false
    if item.type = "movie" then
        index = lib.iptvMovies
    else if item.type = "show" then
        index = lib.iptvShows
    else
        return false
    end if
    tmdb = Str_(lib.plexTmdb[Str_(item.serverBase) + "|" + item.ratingKey])
    return Vod_IndexHas(index, tmdb, Vod_NameKey(item.title), item.year)
end function

function Vod_Copy_(list as object) as object
    out = []
    out.Append(list)
    return out
end function

' The grid: the titles shown, narrowed and in order. Sorts are Plex's names for them.
function Vod_Browse(lib as object, movies as boolean, sort as string, categoryId as string, unwatched as boolean, iptvWins as boolean, marks as object) as object
    all = Vod_Shown(lib, movies, iptvWins)
    out = []
    for each t in all
        keep = categoryId = "" or t.categoryId = categoryId
        if keep and unwatched and movies then
            mk = marks[Vod_MovieKey(t.id, t.extension)]
            keep = mk = invalid or mk.w <> true
        end if
        if keep then out.Push(t)
    end for
    if sort = "addedAt:desc" then
        out.SortBy("added", "r")
    else if sort = "originallyAvailableAt:desc" then
        out.SortBy("yr", "r")
    else if sort = "rating:desc" then
        out.SortBy("rating", "r")
    else
        out.SortBy("sk")
    end if
    return out
end function

' How many titles start with each letter, in the A–Z order the grid is in.
function Vod_Letters(sorted as object) as object
    out = []
    for each t in sorted
        letter = Vod_LetterOf(t.sk)
        last = out.Count() - 1
        if last >= 0 and out[last].letter = letter then
            out[last].count = out[last].count + 1
        else
            out.Push({ letter: letter, count: 1 })
        end if
    end for
    return out
end function

' The provider's categories that have something shown in them, as the genre choice.
function Vod_Categories(lib as object, movies as boolean, iptvWins as boolean) as object
    used = {}
    for each t in Vod_Shown(lib, movies, iptvWins)
        if t.categoryId <> "" then used[t.categoryId] = true
    end for
    out = []
    list = Iif_(movies, lib.movieCategories, lib.seriesCategories)
    for each c in list
        if used.DoesExist(c.id) then out.Push({ id: c.id, title: c.name })
    end for
    return out
end function

function Vod_Items(titles as object, marks as object, start as integer, count as integer) as object
    out = []
    last = start + count - 1
    if last > titles.Count() - 1 then last = titles.Count() - 1
    for i = start to last
        out.Push(Vod_Apply(marks, Vod_ItemOf(titles[i])))
    end for
    return out
end function

function Vod_Newest(lib as object, movies as boolean, iptvWins as boolean, marks as object, count = 40 as integer) as object
    list = Vod_Copy_(Vod_Shown(lib, movies, iptvWins))
    list.SortBy("added", "r")
    return Vod_Items(list, marks, 0, count)
end function

' Newest releases first, by year.
function Vod_Released(lib as object, movies as boolean, iptvWins as boolean, marks as object, count = 40 as integer) as object
    list = Vod_Copy_(Vod_Shown(lib, movies, iptvWins))
    list.SortBy("yr", "r")
    return Vod_Items(list, marks, 0, count)
end function

' Titles with every word typed in their name, best matches first.
function Vod_Search(lib as object, query as string, iptvWins as boolean, marks as object, count = 40 as integer) as object
    wanted = Plex_Words(Vod_Fold_(query))
    if wanted.Count() = 0 then return []
    joined = ""
    for each w in wanted
        joined = joined + w
    end for
    found = []
    for each movies in [true, false]
        for each t in Vod_Shown(lib, movies, iptvWins)
            if found.Count() >= count * 4 then exit for
            all = true
            for each w in wanted
                if Instr(1, t.key, w) = 0 then
                    all = false
                    exit for
                end if
            end for
            if all then
                rank = Vod_Rank_(Plex_Words(Vod_Fold_(t.name)), wanted, joined)
                if rank >= 0 then found.Push({ t: t, rank: rank })
            end if
        end for
    end for
    found.SortBy("rank")
    out = []
    for each f in found
        if out.Count() >= count then exit for
        out.Push(Vod_Apply(marks, Vod_ItemOf(f.t)))
    end for
    return out
end function

' 0 the name exactly, 1 starts with it, 2 every word starts one of its words, 3 somewhere
' at a word's start; -1 not about it.
function Vod_Rank_(name as object, wanted as object, joined as string) as integer
    if name.Count() = 0 then return -1
    whole = ""
    for each n in name
        whole = whole + n
    end for
    if whole = joined then return 0
    if Left(whole, Len(joined)) = joined then return 1
    every = true
    for each w in wanted
        hit = false
        for each n in name
            if Left(n, Len(w)) = w then hit = true
        end for
        if not hit then every = false
    end for
    if every then return 2
    offset = 0
    for each n in name
        if Mid(whole, offset + 1, Len(joined)) = joined then return 3
        offset = offset + Len(n)
    end for
    return -1
end function

' More from the same category, newest first.
function Vod_Related(lib as object, item as object, iptvWins as boolean, marks as object, count = 40 as integer) as object
    k = Vod_ParseKey(item.ratingKey)
    if k = invalid or (k.kind <> "movie" and k.kind <> "show") then return []
    movies = k.kind = "movie"
    if movies then own = lib.moviesById[k.id.ToStr()] else own = lib.seriesById[k.id.ToStr()]
    if own = invalid or own.categoryId = "" then return []
    list = []
    for each t in Vod_Shown(lib, movies, iptvWins)
        if t.categoryId = own.categoryId and t.id <> k.id then list.Push(t)
    end for
    list.SortBy("added", "r")
    return Vod_Items(list, marks, 0, count)
end function

' Home with the provider's put in among Plex's rows: what was being watched from it in
' Continue Watching, its newest in rows of their own, and where its copy wins, Plex's copy
' of the same title left out.
function Vod_ComposeHome(lib as object, marks as object, home as object, iptvWins as boolean) as object
    out = {}
    for each k in home
        out[k] = home[k]
    end for
    continuing = []
    for each i in Arr_(home.continueWatching)
        if not Vod_Hides(lib, i, iptvWins) then continuing.Push(i)
    end for
    continuing.Append(Vod_ContinueWatching(lib, marks))
    continuing = StableSort_(continuing, function(a as object, b as object) as boolean
        return a.lastViewedAt > b.lastViewedAt
    end function)
    out.continueWatching = Head_(continuing, 40)
    movies = []
    for each i in Arr_(home.recentMovies)
        if not Vod_Hides(lib, i, iptvWins) then movies.Push(i)
    end for
    out.recentMovies = movies
    out.iptvMovies = Vod_Newest(lib, true, iptvWins, marks)
    out.iptvShows = Vod_Newest(lib, false, iptvWins, marks)
    return out
end function

' Plex's search with the provider's found titles put in among it: a title in both is the
' winner's copy only, and what matched by name comes first, as on the other apps.
function Vod_MergeSearch(lib as object, query as string, found as object, iptvWins as boolean, marks as object) as object
    fromIptv = Vod_Search(lib, query, iptvWins, marks)
    items = []
    for each list in [Arr_(found.results), Arr_(found.more)]
        for each i in list
            if not Vod_Hides(lib, i, iptvWins) then items.Push(i)
        end for
    end for
    items.Append(fromIptv)
    split = Plex_SplitMatches(query, items)
    out = {}
    for each k in found
        out[k] = found[k]
    end for
    out.results = split.matches
    out.more = split.others
    if out.results.Count() = 0 then
        out.results = out.more
        out.more = []
    end if
    out.unreachable = Bool_(found.unreachable) and fromIptv.Count() = 0
    return out
end function
