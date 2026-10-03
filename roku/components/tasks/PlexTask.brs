sub init()
    m.top.functionName = "work"
end sub

' Whatever goes wrong, an answer comes back: a page waiting on it is never left waiting.
sub work()
    a = m.top.args
    if a = invalid then a = {}
    op = m.top.op
    try
        out = answerFor(op, a)
    catch e
        print "PlexTask "; op; " failed: "; e.message
        #if DEBUG
            for each f in e.backtrace
                print "  at "; f.filename; ":"; f.line_number
            end for
        #end if
        out = { op: op, id: a.id, failed: true }
    end try
    m.top.result = out
end sub

function answerFor(op as string, a as object) as object
    g = m.global
    cid = g.clientId
    out = { op: op, id: a.id }
    if op = "connect" then
        out.answer = PlexApi_Connect(g.plexTv, a.token, cid, Str_(a.last))
    else if op = "home" then
        out.answer = PlexApi_Home(a.libraries, cid)
    else if op = "watchlist" then
        out.answer = PlexApi_Watchlist(g.discover, a.token, a.libraries, cid)
    else if op = "setWatchlisted" then
        out.ok = PlexApi_SetWatchlisted(g.discover, a.token, a.guid, a.on, cid)
    else if op = "libraryMeta" then
        out.answer = PlexApi_LibraryMeta(a.library, Str_(a.filters), cid)
    else if op = "libraryPage" then
        out.items = PlexApi_LibraryPage(a.library, a.sort, Str_(a.filters), a.start, a.size, cid)
    else if op = "detail" then
        out.answer = PlexApi_Detail(a.base, a.token, a.ratingKey, Str_(a.episodeKey), cid)
    else if op = "season" then
        out.answer = PlexApi_Season(a.base, a.token, a.seasonKey, Str_(a.focusKey), cid)
    else if op = "nextEpisode" then
        out.answer = PlexApi_NextEpisodeOf(a.base, a.token, a.showKey, cid)
    else if op = "search" then
        out.answer = PlexApi_Search(a.libraries, a.query, cid)
    else if op = "person" then
        out.items = PlexApi_Person(a.libraries, a.personId, Str_(a.serverBase), cid)
    else if op = "children" then
        out.items = PlexApi_Children(a.base, a.token, a.path, cid)
    else if op = "watched" then
        out.ok = PlexApi_SetWatched(a.base, a.token, a.ratingKey, a.watched, cid)
    else if op = "removeCW" then
        out.ok = PlexApi_RemoveFromContinueWatching(a.base, a.token, a.ratingKey, cid)
    else if op = "playback" then
        c = PlexApi_Container(a.base + "/library/metadata/" + a.ratingKey + "?includeMarkers=1&includeChapters=1", a.token, cid)
        meta = invalid
        if c <> invalid and Arr_(c.Metadata).Count() > 0 then meta = c.Metadata[0]
        out.answer = Plex_PlaybackDetail(meta, a.base, a.token, Int(Num_(a.mediaIndex)))
    else if op = "streams" then
        out.ok = PlexApi_SelectStreams(a.base, a.token, a.partId, Str_(a.audioId), Str_(a.subtitleId), cid)
    else if op = "timeline" then
        out.ok = PlexApi_Timeline(a.base, a.token, a.ratingKey, a.state, Int(a.ms), Int(a.durationMs), a.session, cid)
    else if op = "homeUsers" then
        out.users = PlexApi_HomeUsers(g.plexTv, a.token, cid)
    else if op = "switchUser" then
        out.answer = PlexApi_SwitchUser(g.plexTv, a.token, a.uuid, Str_(a.pin), cid)
    end if
    return out
end function
