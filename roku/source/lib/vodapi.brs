' The provider's films and series asked of it, and what Plex has for matching them, from a
' Task: each function waits for its answers. The reading is vod.brs's.

' { movies, series, movieCategories, seriesCategories }, or { error }.
function VodApi_Catalog(c as object) as object
    if Xtream_IsPlaylist(c) then return { error: "Films and series need an Xtream login, not a playlist." }
    failure = "Your provider couldn't send its films and series. Try again."
    movieCategories = Xtream_ParseCategories(Http_Ask(Xtream_ApiUrl(c, "get_vod_categories"), "GET", invalid, "", 30000).json)
    seriesCategories = Xtream_ParseCategories(Http_Ask(Xtream_ApiUrl(c, "get_series_categories"), "GET", invalid, "", 30000).json)
    ' Each list read and let go before the next: they can be large.
    r = Http_Ask(Xtream_ApiUrl(c, "get_vod_streams"), "GET", invalid, "", 180000)
    if r.code < 200 or r.code > 299 or r.json = invalid then return { error: failure }
    movies = Vod_ReadTitles(r.json, false)
    r = invalid
    r = Http_Ask(Xtream_ApiUrl(c, "get_series"), "GET", invalid, "", 180000)
    if r.code < 200 or r.code > 299 or r.json = invalid then return { error: failure }
    series = Vod_ReadTitles(r.json, true)
    r = invalid
    return { movies: movies, series: series, movieCategories: movieCategories, seriesCategories: seriesCategories }
end function

function VodApi_MovieInfo(c as object, id as integer) as dynamic
    r = Http_Ask(Xtream_ApiUrl(c, "get_vod_info", { vod_id: id.ToStr() }), "GET", invalid, "", 30000)
    return Vod_ParseMovieInfo(r.json)
end function

function VodApi_SeriesInfo(c as object, id as integer) as dynamic
    r = Http_Ask(Xtream_ApiUrl(c, "get_series_info", { series_id: id.ToStr() }), "GET", invalid, "", 30000)
    return Vod_ParseSeriesInfo(r.json)
end function

' Every film and show on Plex, by name, year and tmdb id, into [lib]: a page at a time,
' with only the fields matching needs.
sub VodApi_IndexPlex(lib as object, libraries as object, clientId as string)
    movies = Vod_NewIndex()
    shows = Vod_NewIndex()
    tmdbs = {}
    size = 1000
    for each l in libraries
        if l.type = "movie" or l.type = "show" then
            index = Iif_(l.type = "movie", movies, shows)
            t = Iif_(l.type = "movie", "1", "2")
            start = 0
            while start < 100000
                c = PlexApi_Container(l.base + "/library/sections/" + l.key + "/all?type=" + t + "&includeGuids=1&includeFields=ratingKey,title,originalTitle,year,guid&X-Plex-Container-Start=" + start.ToStr() + "&X-Plex-Container-Size=" + size.ToStr(), l.token, clientId, 60000)
                if c = invalid then exit while
                list = Arr_(c.Metadata)
                for each meta in list
                    if type(meta) = "roAssociativeArray" then Vod_AddPlexEntry(index, tmdbs, meta, l.base)
                end for
                if list.Count() < size then exit while
                start = start + size
            end while
        end if
    end for
    lib.plexMovies = movies
    lib.plexShows = shows
    lib.plexTmdb = tmdbs
end sub
