' Live TV asked of the provider, from a Task: each function waits for its answers.

' Signing in: { ok, error, account }, and a playlist's channels when it's one.
function XtreamApi_Login(c as object) as object
    if Xtream_IsPlaylist(c) then
        list = XtreamApi_Playlist(c)
        if list = invalid then return { ok: false, error: "Couldn't download the playlist." }
        if list.channels.Count() = 0 then return { ok: false, error: "There are no live channels in that playlist." }
        return { ok: true, error: "", account: { status: "Active", maxConnections: "", activeConnections: "", expiresAt: "", clockOffset: 0 }, playlist: list }
    end if
    r = Http_Ask(Xtream_ApiUrl(c, ""), "GET", invalid, "", 20000)
    return Xtream_ParseLogin(r.json)
end function

function XtreamApi_Playlist(c as object) as dynamic
    r = Http_Ask(c.playlistUrl, "GET", invalid, "", 120000)
    if r.code < 200 or r.code > 299 then return invalid
    return Xtream_ParseM3u(r.body)
end function

' A provider's categories, or invalid when it didn't answer.
function XtreamApi_Categories(c as object) as dynamic
    r = Http_Ask(Xtream_ApiUrl(c, "get_live_categories"), "GET", invalid, "", 30000)
    if r.code < 200 or r.code > 299 then return invalid
    return Xtream_ParseCategories(r.json)
end function

' Every channel, or a category's; invalid when the provider didn't answer.
function XtreamApi_Channels(c as object, categoryId as string) as dynamic
    extras = invalid
    if categoryId <> "" then extras = { category_id: categoryId }
    r = Http_Ask(Xtream_ApiUrl(c, "get_live_streams", extras), "GET", invalid, "", 60000)
    if r.code < 200 or r.code > 299 then return invalid
    return Xtream_ParseChannels(r.json)
end function

' Now and next for each of [streamIds]: { "12": [programmes] }. A playlist has none here.
function XtreamApi_ShortEpg(c as object, streamIds as object) as object
    out = {}
    if Xtream_IsPlaylist(c) then return out
    for each id in streamIds
        key = Str_(id)
        r = Http_Ask(Xtream_ApiUrl(c, "get_short_epg", { stream_id: key, limit: "4" }), "GET", invalid, "", 15000)
        if r.json <> invalid then out[key] = Xtream_ParseListings(r.json, key)
    end for
    return out
end function

' Each channel's whole listing the panel holds, past and to come: the guide's grid.
function XtreamApi_Table(c as object, streamIds as object) as object
    out = {}
    if Xtream_IsPlaylist(c) then return out
    for each id in streamIds
        key = Str_(id)
        r = Http_Ask(Xtream_ApiUrl(c, "get_simple_data_table", { stream_id: key }), "GET", invalid, "", 30000)
        if r.json <> invalid then out[key] = Xtream_ParseListings(r.json, key)
    end for
    return out
end function
