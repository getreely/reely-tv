' Reely asked from a Task. Its session is a cookie, kept by the page and handed in with
' each ask; when there's none, or it has lapsed, the ask signs in first with the Plex
' account and hands the new one back.

function ReelyApi_Headers(cookie as string, json as boolean) as object
    h = { "Accept": "application/json" }
    if json then h["Content-Type"] = "application/json"
    if cookie <> "" then h["Cookie"] = cookie
    return h
end function

' Signing in: { problem, cookie }; problem "" when it worked.
function ReelyApi_SignIn(base as string, token as string) as object
    if token = "" then return { problem: "Sign in to Plex first.", cookie: "" }
    r = Http_Ask(base + "/api/v1/auth/plex/token", "POST", ReelyApi_Headers("", true), FormatJson({ token: token }), 20000)
    problem = Reely_SignInProblem(r.code, r.body)
    if r.code = 0 then problem = "Couldn't reach Reely at " + Reely_HostOf(base) + "."
    return { problem: problem, cookie: Reely_CookieFrom(r.headers) }
end function

' One ask of Reely, signing in when needed: { code, json, body, cookie, problem }. Tokens
' are tried in turn: the one in use, then the account's own (a profile's may be refused).
function ReelyApi_Call(c as object, path as string, method = "GET" as string, body = "" as string) as object
    cookie = Str_(c.cookie)
    if cookie = "" then
        signed = ReelyApi_SignInAny(c)
        if signed.problem <> "" then return { code: 0, json: invalid, body: "", cookie: "", problem: signed.problem }
        cookie = signed.cookie
    end if
    r = Http_Ask(c.base + path, method, ReelyApi_Headers(cookie, body <> ""), body, 30000)
    if r.code = 401 then
        signed = ReelyApi_SignInAny(c)
        if signed.problem <> "" then return { code: 0, json: invalid, body: "", cookie: "", problem: signed.problem }
        cookie = signed.cookie
        r = Http_Ask(c.base + path, method, ReelyApi_Headers(cookie, body <> ""), body, 30000)
    end if
    problem = ""
    if r.code = 0 then
        problem = "Couldn't reach Reely at " + Reely_HostOf(c.base) + "."
    else if r.code < 200 or r.code > 299 then
        problem = Reely_ErrorOf(r.body)
        if problem = "" then problem = "Reely couldn't do that. Try again."
    end if
    return { code: r.code, json: r.json, body: r.body, cookie: cookie, problem: problem }
end function

function ReelyApi_SignInAny(c as object) as object
    first = ReelyApi_SignIn(c.base, Str_(c.token))
    account = Str_(c.accountToken)
    if first.problem = Reely_PlexRejectedText() and account <> "" and account <> Str_(c.token) then return ReelyApi_SignIn(c.base, account)
    return first
end function

' The tab's page: { problem, cookie, rows, mine, marks }.
function ReelyApi_Home(c as object) as object
    ex = ReelyApi_Call(c, "/api/v1/explore")
    if ex.problem <> "" then return { problem: ex.problem, cookie: "" }
    c.cookie = ex.cookie
    mine = ReelyApi_Call(c, "/api/v1/requests?mine=1")
    movies = ReelyApi_Call(c, "/api/v1/movies")
    shows = ReelyApi_Call(c, "/api/v1/shows")
    open = ReelyApi_Call(c, "/api/v1/requests")
    m1 = invalid
    if movies.json <> invalid then m1 = movies.json.movies
    s1 = invalid
    if shows.json <> invalid then s1 = shows.json.shows
    o1 = invalid
    if open.json <> invalid then o1 = open.json.requests
    return { problem: "", cookie: c.cookie, rows: Reely_ExploreRows(ex.json), mine: Reely_ParseRecords(mine.json), marks: Reely_ParseMarks(m1, s1, o1) }
end function

function ReelyApi_Search(c as object, query as string) as object
    r = ReelyApi_Call(c, "/api/v1/search?q=" + UrlEncode_(query.Trim()))
    if r.problem <> "" then return { problem: r.problem, cookie: "", results: [] }
    image = Reely_Images()
    if r.json <> invalid and Str_(r.json.imageBase) <> "" then image = Str_(r.json.imageBase)
    results = []
    if r.json <> invalid then results = Reely_TitlesOf(r.json.results, image)
    return { problem: "", cookie: r.cookie, results: results }
end function

' A title's page and where it could go: { problem, cookie, detail, places }.
function ReelyApi_Detail(c as object, t as object) as object
    path = "/api/v1/preview/" + t.kind + "/" + t.tmdbId.ToStr()
    ' A show found through TheTVDB has only that id; Reely looks it up there.
    if t.kind = "show" and t.tmdbId = 0 and t.tvdbId > 0 then path = "/api/v1/preview/show/" + t.tvdbId.ToStr() + "?src=tvdb"
    r = ReelyApi_Call(c, path)
    if r.problem <> "" then return { problem: r.problem, cookie: "" }
    c.cookie = r.cookie
    detail = Reely_ParseDetail(r.json, t)
    if detail = invalid then return { problem: "Reely couldn't do that. Try again.", cookie: c.cookie }
    account = ReelyApi_Call(c, "/api/v1/auth/me")
    libraries = ReelyApi_Call(c, "/api/v1/libraries")
    return { problem: "", cookie: c.cookie, detail: detail, places: Reely_ParsePlaces(account.json, libraries.json) }
end function

' Asking: { outcome: "sent" | "already" | "refused", approved, message, cookie }.
function ReelyApi_Request(c as object, t as object, seasons as dynamic, libraryId as integer) as object
    body = { kind: t.kind, tmdbId: t.tmdbId, tvdbId: t.tvdbId, title: t.title, year: t.year, poster: Str_(t.posterPath) }
    if seasons <> invalid then body.seasons = seasons
    ' Set by name in brackets: a dotted name is kept in lower case, and Reely wants libraryId.
    if libraryId > 0 then body["libraryId"] = libraryId
    r = ReelyApi_Call(c, "/api/v1/requests", "POST", FormatJson(body))
    if r.code >= 200 and r.code < 300 then
        approved = r.json <> invalid and Str_(r.json.status) = "approved"
        return { outcome: "sent", approved: approved, message: "", cookie: r.cookie }
    end if
    if r.code = 409 then return { outcome: "already", approved: false, message: "", cookie: r.cookie }
    message = r.problem
    if message = "" then message = "Reely couldn't take that request. Try again."
    return { outcome: "refused", approved: false, message: message, cookie: r.cookie }
end function

' Whether anything asked for has arrived: { problem, cookie, mine, marks }.
function ReelyApi_Ready(c as object) as object
    mine = ReelyApi_Call(c, "/api/v1/requests?mine=1")
    if mine.problem <> "" then return { problem: mine.problem, cookie: "" }
    c.cookie = mine.cookie
    movies = ReelyApi_Call(c, "/api/v1/movies")
    shows = ReelyApi_Call(c, "/api/v1/shows")
    asked = ReelyApi_Call(c, "/api/v1/requests")
    m1 = invalid
    if movies.json <> invalid then m1 = movies.json.movies
    s1 = invalid
    if shows.json <> invalid then s1 = shows.json.shows
    o1 = invalid
    if asked.json <> invalid then o1 = asked.json.requests
    return { problem: "", cookie: c.cookie, mine: Reely_ParseRecords(mine.json), marks: Reely_ParseMarks(m1, s1, o1) }
end function
