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

' A playlist's whole XMLTV guide, downloaded to a file and read from it a piece at a time:
' a provider's runs to tens of megabytes, and only the playlist's own channels, from six hours
' back to a day and a half ahead, are kept. { byChannel } or { error }.
function XtreamApi_PlaylistGuide(url as string, known as dynamic, now as integer) as object
    path = "tmp:/reely-guide.xml"
    fs = CreateObject("roFileSystem")
    if fs.Exists(path) then fs.Delete(path)
    t = Http_Transfer(url, "GET", invalid)
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    if not t.AsyncGetToFile(path) then return { error: "Couldn't download the TV guide." }
    msg = Wait(180000, port)
    if type(msg) <> "roUrlEvent" then
        t.AsyncCancel()
        return { error: "Couldn't download the TV guide." }
    end if
    code = msg.GetResponseCode()
    if code < 200 or code > 299 then return { error: "Couldn't download the TV guide." }
    stat = fs.Stat(path)
    size = 0
    if stat <> invalid and stat.size <> invalid then size = stat.size
    bytes = CreateObject("roByteArray")
    if size >= 2 and bytes.ReadFile(path, 0, 2) and bytes[0] = 31 and bytes[1] = 139 then
        fs.Delete(path)
        return { error: "This Roku can't open the TV guide: it's compressed. Ask your provider for an uncompressed address." }
    end if
    reader = Xtream_XmltvReader(known, now - 6 * 3600, now + 36 * 3600)
    piece = 262144
    at = 0
    while at < size
        bytes = CreateObject("roByteArray")
        if not bytes.ReadFile(path, at, piece) or bytes.Count() = 0 then exit while
        n = bytes.Count()
        ' Cut after the last ">": never in the middle of a character.
        if at + n < size then
            keep = n
            while keep > 0 and bytes[keep - 1] <> 62
                keep = keep - 1
            end while
            if keep > 0 then
                while bytes.Count() > keep
                    bytes.Pop()
                end while
                n = keep
            end if
        end if
        Xtream_XmltvPush(reader, bytes.ToAsciiString())
        at = at + n
    end while
    fs.Delete(path)
    return { byChannel: Xtream_XmltvDone(reader) }
end function
