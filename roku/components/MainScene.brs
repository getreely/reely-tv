' The whole app's comings and goings: signing in, finding the server, which page is up,
' the poster menu and the player. Each page is its own component in components/screens.

sub init()
    m.store = CreateObject("roRegistrySection", "reely")
    clientId = m.store.Read("clientId")
    if clientId = "" then
        clientId = "reely-roku-" + CreateObject("roDeviceInfo").GetRandomUUID()
        m.store.Write("clientId", clientId)
        m.store.Flush()
    end if
    ' The session is {} while signed out: a field made from invalid can't hold one later.
    m.global.addFields({ clientId: clientId, session: {}, home: {}, watchlist: [], recentSearches: ReadJson_("recentSearches", []), prefs: Prefs_() })
    m.http = {}
    m.signIn = m.top.findNode("signIn")
    m.shell = m.top.findNode("shell")
    m.nav = m.top.findNode("nav")
    m.screens = m.top.findNode("screens")
    m.player = m.top.findNode("player")
    m.overlays = m.top.findNode("overlays")
    m.status = m.top.findNode("status")
    m.top.findNode("signInTitle").font = Bold_(54)
    m.top.findNode("code").font = Bold_(96)
    for each id in ["signInHint", "signInNote", "status"]
        m.top.findNode(id).font = Regular_(30)
    end for
    m.nav.observeField("chosen", "onTab")
    m.nav.observeField("leave", "intoScreen")
    m.player.observeField("done", "onPlayerDone")
    m.top.findNode("claimTimer").observeField("fire", "claim")
    m.top.findNode("retryTimer").observeField("fire", "retryConnect")
    m.stack = []
    m.page = "start"
    token = m.store.Read("plexToken")
    if token <> "" then
        m.token = token
        connect()
    else
        startSignIn()
    end if
end sub

' The settings kept on this Roku, with the Fire TV's defaults.
function Prefs_() as object
    p = ReadJson_("prefs", {})
    defaults = { playbackMode: "auto", maxBitrateKbps: 0, skipIntros: false, skipCredits: false, upNextSeconds: 12, hiddenRows: [] }
    for each k in defaults
        if p[k] = invalid then p[k] = defaults[k]
    end for
    return p
end function

function ReadJson_(key as string, fallback as dynamic) as dynamic
    raw = m.store.Read(key)
    if raw = "" then return fallback
    v = ParseJson(raw)
    if v = invalid then return fallback
    return v
end function

sub WriteJson_(key as string, value as dynamic)
    m.store.Write(key, FormatJson(value))
    m.store.Flush()
end sub

function signedIn() as boolean
    return m.global.session.base <> invalid
end function

sub say(text as string)
    m.status.text = text
end sub

' ------------------------------------------------------------------ Asking plex.tv (sign-in)

sub askHttp(tag as string, url as string, method as string, body as string)
    task = CreateObject("roSGNode", "HttpTask")
    task.request = { tag: tag, url: url, method: method, headers: PlexHeaders_(invalid), body: body, timeoutMs: 15000 }
    task.observeField("response", "onHttp")
    m.http[tag] = task
    task.control = "run"
end sub

function PlexHeaders_(token as dynamic) as object
    h = { "Accept": "application/json", "X-Plex-Product": "Reely TV", "X-Plex-Version": "0.51.0", "X-Plex-Client-Identifier": m.global.clientId, "X-Plex-Platform": "Roku", "X-Plex-Device": "Roku", "X-Plex-Device-Name": "Reely on Roku" }
    if token <> invalid and token <> "" then h["X-Plex-Token"] = token
    return h
end function

sub onHttp(event as object)
    r = event.getData()
    m.http.Delete(r.tag)
    if r.tag = "pin" then onPin(r) else if r.tag = "claim" then onClaim(r)
end sub

' ------------------------------------------------------------------ Signing in

sub startSignIn()
    m.page = "signIn"
    showOnly(m.signIn)
    m.top.findNode("signInTitle").text = "Sign in to watch your library"
    m.top.findNode("signInHint").text = "Getting a sign-in code…"
    m.top.findNode("code").text = ""
    m.top.findNode("signInNote").text = ""
    askHttp("pin", m.global.plexTv + "/api/v2/pins", "POST", "strong=false")
end sub

sub onPin(r as object)
    if r.code < 200 or r.code > 299 or r.json = invalid then
        m.top.findNode("signInHint").text = "Couldn't get a sign-in code from Plex. Press OK to try again."
        return
    end if
    m.pinId = Str_(r.json.id)
    m.top.findNode("signInHint").text = "On your phone or computer, go to plex.tv/link and enter"
    m.top.findNode("code").text = Str_(r.json.code)
    m.top.findNode("signInNote").text = "Waiting for you to sign in…"
    m.claims = 0
    m.top.findNode("claimTimer").control = "start"
end sub

sub claim()
    m.claims = m.claims + 1
    if m.claims > 300 then
        m.top.findNode("claimTimer").control = "stop"
        m.top.findNode("signInHint").text = "That code has expired. Press OK for a new one."
        m.top.findNode("code").text = ""
        return
    end if
    askHttp("claim", m.global.plexTv + "/api/v2/pins/" + m.pinId, "GET", "")
end sub

sub onClaim(r as object)
    ' A slower answer to an earlier ask, after one already signed in.
    if m.page <> "signIn" or r.json = invalid then return
    token = Str_(r.json.authToken)
    if token = "" or token = "null" then return
    m.top.findNode("claimTimer").control = "stop"
    m.token = token
    m.store.Write("plexToken", token)
    m.store.Write("plexAccountToken", token)
    m.store.Flush()
    connect()
end sub

' ------------------------------------------------------------------ Finding the server

sub connect()
    m.page = "finding"
    if not signedIn() then
        showOnly(m.signIn)
        m.top.findNode("signInTitle").text = "Finding your Plex server"
        m.top.findNode("signInHint").text = "You're signed in. Looking for your server, at home first, then over the internet."
        m.top.findNode("code").text = ""
        m.top.findNode("signInNote").text = ""
    end if
    Ask_("connect", { token: m.token, last: m.store.Read("server") })
end sub

sub retryConnect()
    if m.page = "noServer" or m.lostServer = true then connect()
end sub

sub answered(r as object)
    if r.op = "connect" then
        onConnected(r.answer)
    else if r.op = "home" then
        onHome(r.answer)
    else if r.op = "watchlist" then
        if r.answer.ok then
            m.global.watchlist = r.answer.guids
            h = m.global.home
            h.watchlist = r.answer.items
            m.global.home = h
        end if
    else if r.op = "setWatchlisted" then
        loadWatchlist()
    else if r.op = "watched" or r.op = "removeCW" then
        loadHome()
        refreshCurrent()
    else if r.op = "nextEpisode" then
        if r.answer.episode <> invalid then playRequest({ item: r.answer.episode, resume: true, queue: r.answer.queue, mediaIndex: 0 })
    end if
end sub

sub onConnected(a as object)
    if a.signedOut = true then
        ' Signed out elsewhere: sign in again.
        m.store.Delete("plexToken")
        m.store.Flush()
        startSignIn()
        return
    end if
    if a.reached.Count() = 0 then
        if signedIn() then
            ' The server it was using doesn't answer: Home says so, and it looks again.
            m.lostServer = true
            h = m.global.home
            h.error = "Couldn't reach your Plex server. Trying again…"
            m.global.home = h
        else
            m.page = "noServer"
            m.top.findNode("signInTitle").text = "Can't find your Plex server"
            m.top.findNode("signInHint").text = Iif_(a.noServers, "No Plex servers on this account.", "Make sure Plex Media Server is running and signed in to the same account. Press OK to try again.")
        end if
        ' It keeps looking, every little while, as well as at OK.
        m.top.findNode("retryTimer").control = "start"
        return
    end if
    m.lostServer = false
    first = a.reached[0]
    m.store.Write("server", first.name)
    m.store.Flush()
    m.global.session = { token: m.token, accountToken: m.store.Read("plexAccountToken"), base: first.base, serverToken: first.token, serverName: first.name, servers: a.servers, libraries: a.libraries }
    if m.stack.Count() = 0 then
        showShell()
        openTab("home")
        ' At start the cursor is in Home's rows, as on the other apps.
        intoScreen()
    end if
    loadHome()
end sub

sub loadHome()
    s = m.global.session
    if not signedIn() then return
    Ask_("home", { libraries: s.libraries })
    loadWatchlist()
end sub

sub loadWatchlist()
    s = m.global.session
    if signedIn() then Ask_("watchlist", { token: s.token, libraries: s.libraries })
end sub

sub onHome(a as object)
    if a = invalid then return
    old = m.global.home
    if not a.answered then
        old.error = "Couldn't reach your Plex server. Trying again…"
        m.global.home = old
        ' Addresses change: it's looked up again, and used from wherever it answers now.
        m.lostServer = true
        m.top.findNode("retryTimer").control = "start"
        return
    end if
    a.watchlist = Arr_(old.watchlist)
    a.error = ""
    m.global.home = a
end sub

' ------------------------------------------------------------------ Pages

sub showShell()
    showOnly(m.shell)
end sub

sub showOnly(view as object)
    for each v in [m.signIn, m.shell]
        v.visible = v.isSameNode(view)
    end for
    say("")
end sub

' A tab: its page, alone on the stack.
sub onTab()
    openTab(m.nav.chosen)
end sub

sub openTab(id as string)
    routes = {
        home: { name: "home" },
        movies: { name: "library", kind: "movie" },
        shows: { name: "library", kind: "show" },
        search: { name: "search" },
        live: { name: "note", title: "Live TV", text: "Live TV is coming to Reely on Roku." },
        requests: { name: "note", title: "Requests", text: "Requests are coming to Reely on Roku." },
        settings: { name: "settings" }
    }
    route = routes[id]
    route.tab = id
    clearScreens()
    m.stack = []
    push(route, false)
    m.nav.current = id
    ' Opened by OK (Search and Settings), or by arriving: the cursor goes in on OK only.
    if id = "search" or id = "settings" then intoScreen()
end sub

sub clearScreens()
    while m.screens.getChildCount() > 0
        m.screens.removeChildIndex(0)
    end while
end sub

function screenFor(route as object) as object
    names = { home: "HomeScreen", library: "LibraryScreen", detail: "DetailScreen", list: "ListScreen", search: "SearchScreen", settings: "SettingsScreen", note: "NoteScreen" }
    node = CreateObject("roSGNode", names[route.name])
    node.observeField("go", "onGo")
    node.observeField("menu", "onMenu")
    if node.hasField("play") then node.observeField("play", "onPlay")
    node.route = route
    return node
end function

' Another page on top; the one under it kept, as it was, for Back.
sub push(route as object, takeFocus = true as boolean)
    if m.stack.Count() > 0 then m.stack[m.stack.Count() - 1].node.visible = false
    node = screenFor(route)
    m.screens.appendChild(node)
    m.stack.Push({ route: route, node: node })
    if takeFocus then node.focusIn = true
end sub

function topScreen() as dynamic
    if m.stack.Count() = 0 then return invalid
    return m.stack[m.stack.Count() - 1].node
end function

sub intoScreen()
    c = topScreen()
    if c <> invalid then c.focusIn = true
end sub

sub refreshCurrent()
    c = topScreen()
    if c <> invalid and c.hasField("refresh") then c.refresh = { episodeKey: "" }
end sub

sub onGo(event as object)
    route = event.getData()
    if route.name = "refreshHome" then
        loadHome()
    else if route.name = "watchlist" then
        ' Flipped at once; Plex told behind it.
        s = m.global.session
        list = []
        for each g in m.global.watchlist
            if g <> route.guid then list.Push(g)
        end for
        if route.on then list.Push(route.guid)
        m.global.watchlist = list
        Ask_("setWatchlisted", { token: s.token, guid: route.guid, on: route.on })
    else if route.name = "recentSearches" then
        m.global.recentSearches = route.list
        WriteJson_("recentSearches", route.list)
    else if route.name = "prefs" then
        m.global.prefs = route.prefs
        WriteJson_("prefs", route.prefs)
    else if route.name = "signOut" then
        signOut()
    else if route.name = "server" then
        ' Used first from now on: looked for again, Home asked of it.
        m.store.Write("server", route.server)
        m.store.Flush()
        connect()
    else
        push(route)
    end if
end sub

function goBack() as boolean
    if m.stack.Count() > 1 then
        top = m.stack.Pop()
        m.screens.removeChild(top.node)
        under = m.stack[m.stack.Count() - 1].node
        under.visible = true
        under.focusIn = true
        return true
    end if
    if m.nav.current <> "home" then
        openTab("home")
        intoScreen()
        return true
    end if
    return false
end function

sub signOut()
    for each key in ["plexToken", "plexAccountToken", "server"]
        m.store.Delete(key)
    end for
    m.store.Flush()
    m.global.session = {}
    m.global.home = {}
    m.global.watchlist = []
    clearScreens()
    m.stack = []
    startSignIn()
end sub

' ------------------------------------------------------------------ The poster menu

sub onMenu(event as object)
    item = event.getData().item
    inCW = false
    for each i in Arr_(m.global.home.continueWatching)
        if Plex_ListKey(i) = Plex_ListKey(item) then inCW = true
    end for
    m.menu = CreateObject("roSGNode", "ItemMenu")
    m.menu.inContinueWatching = inCW
    m.menu.item = item
    m.menuItem = item
    m.menu.observeField("chosen", "onMenuChosen")
    m.overlays.appendChild(m.menu)
    m.menuFrom = topScreen()
    m.menu.setFocus(true)
end sub

sub onMenuChosen()
    chosen = m.menu.chosen
    item = m.menuItem
    m.overlays.removeChild(m.menu)
    m.menu = invalid
    if m.menuFrom <> invalid then m.menuFrom.focusIn = true
    base = item.serverBase
    if base = invalid or base = "" then base = m.global.session.base
    token = TokenFor_(base)
    if chosen = "play" or chosen = "restart" then
        playRequest({ item: item, resume: chosen = "play", queue: [], mediaIndex: 0 })
    else if chosen = "next" then
        Ask_("nextEpisode", { base: base, token: token, showKey: item.ratingKey })
    else if chosen = "watched" or chosen = "unwatched" then
        Ask_("watched", { base: base, token: token, ratingKey: item.ratingKey, watched: chosen = "watched" })
    else if chosen = "details" then
        push(RouteFor_(item))
    else if chosen = "remove" then
        Ask_("removeCW", { base: base, token: token, ratingKey: item.ratingKey })
    end if
end sub

' ------------------------------------------------------------------ Playing

sub onPlay(event as object)
    playRequest(event.getData())
end sub

sub playRequest(request as object)
    m.playingFrom = topScreen()
    m.player.visible = true
    m.shell.visible = false
    m.page = "player"
    m.player.request = request
    m.player.takeFocus = true
end sub

sub onPlayerDone()
    d = m.player.done
    m.player.visible = false
    m.shell.visible = true
    m.page = "shell"
    ' Back where it was, what's just been watched marked as watched: on the episode if it
    ' was left part way, on the next one if it was finished. Home asked again behind it.
    c = topScreen()
    if c <> invalid then
        if c.hasField("refresh") then
            key = ""
            if not d.finished and d.item.type = "episode" then key = d.item.ratingKey
            c.refresh = { episodeKey: key }
        end if
        c.focusIn = true
    end if
    loadHome()
end sub

' ------------------------------------------------------------------ The remote

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.page = "signIn" and key = "OK" and m.top.findNode("code").text = "" then
        startSignIn()
        return true
    else if m.page = "noServer" and key = "OK" then
        connect()
        return true
    end if
    if m.shell.visible then
        if key = "back" then
            if m.nav.isInFocusChain() then
                ' Back on the tabs: into the page, or Home, or out of the app.
                if m.nav.current <> "home" then
                    openTab("home")
                    intoScreen()
                    return true
                end if
                return false
            end if
            return goBack()
        else if key = "up" and not m.nav.isInFocusChain() then
            m.nav.takeFocus = true
            return true
        end if
    end if
    return false
end function
