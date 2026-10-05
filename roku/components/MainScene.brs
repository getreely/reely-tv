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
    m.global.addFields({ clientId: clientId, session: {}, home: {}, watchlist: [], recentSearches: ReadJson_("recentSearches", []), prefs: Prefs_(), live: ReadJson_("live", {}), reely: ReadJson_("reely", {}), profile: ReadJson_("plexProfile", {}), ready: [], iptvState: {} })
    ' The provider's films and series, held for as long as the app is open.
    m.iptv = CreateObject("roSGNode", "IptvTask")
    m.iptv.observeField("catalogState", "onIptvState")
    m.iptv.control = "run"
    m.global.addFields({ iptv: m.iptv })
    a = Accent_Of(m.global.prefs.accent)
    m.global.addFields({ accent: a.color, accentOn: a.on })
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
    paintAccent()
    m.nav.observeField("leave", "intoScreen")
    m.player.observeField("done", "onPlayerDone")
    m.liveView = m.top.findNode("liveView")
    m.liveView.observeField("done", "onLiveDone")
    m.liveView.observeField("go", "onGo")
    m.notified = {}
    m.top.findNode("reminderTimer").observeField("fire", "checkReminders")
    m.top.findNode("reminderTimer").control = "start"
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
    defaults = { playbackMode: "auto", maxBitrateKbps: 0, skipIntros: false, skipCredits: false, upNextSeconds: 12, hiddenRows: [], streamFormat: "m3u8", iptvLibrary: false, iptvWins: false, screensaver: true, tourSeen: false, subtitlesAtStart: "plex", accent: "blue", iptvInMenus: true, themeMusic: false, guidePreview: true }
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
    ' A Plex Home of several people: "Who's watching?" once, straight after signing in.
    m.askWho = true
    connect()
end sub

' ------------------------------------------------------------------ Finding the server

sub connect()
    m.page = "finding"
    if not signedIn() then
        showOnly(m.signIn)
        m.top.findNode("signInTitle").text = "Finding a Plex server"
        m.top.findNode("signInHint").text = "You're signed in. Looking for the servers you can use, your own or shared with you, at home first, then over the internet."
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
            if m.plexHome <> invalid then m.plexHome.watchlist = r.answer.items
        end if
    else if r.op = "setWatchlisted" then
        loadWatchlist()
    else if r.op = "watched" or r.op = "removeCW" then
        loadHome()
        refreshCurrent()
    else if r.op = "iptvWatched" or r.op = "iptvForget" then
        ' Kept on the Roku: Home put together again, without asking Plex.
        showHome()
        refreshCurrent()
    else if r.op = "iptvHome" then
        if r.answer <> invalid then
            h = r.answer
            #if DEBUG
                names = []
                for each i in Arr_(h.iptvMovies)
                    names.Push(i.title)
                end for
                Trace_("iptv home " + Join_(names, ", "))
            #end if
            h.watchlist = Arr_(m.global.home.watchlist)
            setHome(h)
        else if m.plexHome <> invalid then
            setHome(m.plexHome)
        end if
    else if r.op = "homeUsers" then
        profile = { user: r.user, homeUsers: Arr_(r.users) }
        m.global.profile = profile
        WriteJson_("plexProfile", profile)
        if m.askWho = true and profile.homeUsers.Count() > 1 then showProfiles() else maybeTour()
        m.askWho = false
    else if r.op = "switchUser" then
        onSwitched(r.answer)
    else if r.op = "reelyReady" then
        onReady(r.answer)
    else if r.op = "search" and r.id = "ready" then
        found = invalid
        if r.answer <> invalid then found = Reely_FindInPlex(m.readyTitle, Arr_(r.answer.results))
        if found <> invalid then
            push(RouteFor_(found))
        else
            ' Not found by name in the libraries: the search, with it typed in.
            openTab("search")
            topScreen().route = { name: "search", tab: "search", query: m.readyTitle.title }
            intoScreen()
        end if
    else if r.op = "nextEpisode" or r.op = "iptvNext" then
        if r.answer <> invalid and r.answer.episode <> invalid then playRequest({ item: r.answer.episode, resume: true, queue: r.answer.queue, mediaIndex: 0 })
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
            m.top.findNode("signInTitle").text = "Couldn't reach a Plex server"
            m.top.findNode("signInHint").text = Iif_(a.noServers, "This Plex account has no Plex server of its own, and nobody has shared one with it yet.", "Any server this account owns or has been given access to will do. Make sure it's on. Press OK to try again.")
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
    loadProfiles()
    if m.stack.Count() = 0 then
        showShell()
        openTab("home")
        ' At start the cursor is in Home's rows, as on the other apps.
        intoScreen()
    end if
    loadHome()
    loadIptv(false)
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
    m.plexHome = a
    showHome()
    checkReady()
end sub

' Plex's Home, with the provider's put in among it while its films and series are on.
sub showHome()
    a = m.plexHome
    if a = invalid then return
    if IptvReady_() then
        Ask_("iptvHome", { home: a })
    else
        setHome(a)
    end if
end sub

' Home as shown, and its artwork kept for the screensaver, which runs on its own.
sub setHome(h as object)
    m.global.home = h
    slides = []
    for each s in Saver_Slides(h, 12)
        slides.Push({ u: Image_(s.base, s.path, 1920, 1080), t: s.title, c: s.caption })
    end for
    kept = FormatJson(slides)
    if slides.Count() > 0 and kept <> m.store.Read("saverSlides") then
        m.store.Write("saverSlides", kept)
        m.store.Flush()
        Trace_("saver kept " + slides.Count().ToStr())
    end if
end sub

' ------------------------------------------------------------------ The provider's films and series

' Read when they're switched on, there's an Xtream login, and Plex's libraries are known
' (to leave out what Plex has).
sub loadIptv(refresh as boolean)
    p = m.global.prefs
    creds = m.global.live.credentials
    if not Bool_(p.iptvLibrary) or not signedIn() or creds = invalid or Str_(creds.base) = "" or Str_(creds.playlistUrl) <> "" then return
    Ask_("iptvLoad", { libraries: m.global.session.libraries, refresh: refresh })
end sub

sub onIptvState()
    s = m.iptv.catalogState
    was = m.global.iptvState
    m.global.iptvState = s
    if Bool_(s.ready) and not Bool_(s.loading) then Trace_("iptv ready " + Str_(s.movies) + " " + Str_(s.shows))
    ' Ready now, or gone: Home put together again.
    if Bool_(s.ready) <> Bool_(was.ready) or (Bool_(was.loading) and not Bool_(s.loading)) then showHome()
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
        live: { name: "live" },
        requests: { name: "requests" },
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
        release(m.screens.getChild(0))
        m.screens.removeChildIndex(0)
    end while
end sub

' A page taken away stops listening to the scene's shared fields (see Listen.brs).
sub release(node as object)
    if node <> invalid and node.hasField("gone") then node.gone = true
end sub

function screenFor(route as object) as object
    names = { home: "HomeScreen", library: "LibraryScreen", detail: "DetailScreen", list: "ListScreen", search: "SearchScreen", settings: "SettingsScreen", note: "NoteScreen", live: "LiveScreen", requests: "RequestsScreen", requestTitle: "RequestTitleScreen" }
    node = CreateObject("roSGNode", names[route.name])
    node.observeField("go", "onGo")
    node.observeField("menu", "onMenu")
    if node.hasField("play") then node.observeField("play", "onPlay")
    if node.hasField("watch") then node.observeField("watch", "onWatch")
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
        before = m.global.prefs
        m.global.prefs = route.prefs
        WriteJson_("prefs", route.prefs)
        a = Accent_Of(route.prefs.accent)
        if a.color <> m.global.accent then
            m.global.setFields({ accent: a.color, accentOn: a.on })
            paintAccent()
        end if
        on = Bool_(route.prefs.iptvLibrary)
        if on <> Bool_(before.iptvLibrary) then
            if on then loadIptv(false) else Ask_("iptvOff", {})
            showHome()
        else if Bool_(route.prefs.iptvWins) <> Bool_(before.iptvWins) then
            showHome()
        end if
    else if route.name = "tour" then
        showTour()
    else if route.name = "iptvRefresh" then
        loadIptv(true)
    else if route.name = "tab" then
        openTab(route.tab)
        intoScreen()
    else if route.name = "watchReady" then
        dismissReady(route.title)
        m.readyTitle = route.title
        Ask_("search", { libraries: m.global.session.libraries, query: route.title.title, id: "ready" })
    else if route.name = "dismissReady" then
        dismissReady(route.title)
    else if route.name = "profiles" then
        showProfiles()
    else if route.name = "reely" then
        m.global.reely = route.reely
        WriteJson_("reely", route.reely)
    else if route.name = "refreshRequests" then
        ' The tab under a title just asked for: its marks asked again.
        for each entry in m.stack
            if entry.node.subtype() = "RequestsScreen" then entry.node.refresh = {}
        end for
    else if route.name = "live" then
        before = m.global.live.credentials
        m.global.live = route.live
        WriteJson_("live", route.live)
        ' Another login, or none: the provider's films and series go with the old one.
        if FormatJson(before) <> FormatJson(route.live.credentials) then
            Ask_("iptvOff", {})
            loadIptv(true)
            showHome()
        end if
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
        release(top.node)
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
    m.global.profile = {}
    m.store.Delete("plexProfile")
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
    else if base = "iptv:" then
        ' The provider's: kept on the Roku rather than told to Plex.
        if chosen = "next" then
            Ask_("iptvNext", { showKey: item.ratingKey })
        else if chosen = "watched" or chosen = "unwatched" then
            Ask_("iptvWatched", { item: item, watched: chosen = "watched" })
        else if chosen = "details" then
            push(RouteFor_(item))
        else if chosen = "remove" then
            Ask_("iptvForget", { ratingKey: item.ratingKey })
        end if
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

' ------------------------------------------------------------------ Requests that have arrived

' Whether anything asked for has arrived, as the other apps look, each time Home is read.
sub checkReady()
    r = m.global.reely
    s = m.global.session
    if r = invalid or Str_(r.base) = "" or not signedIn() then return
    Ask_("reelyReady", { reely: { base: Str_(r.base), cookie: Str_(r.cookie), token: Str_(s.token), accountToken: Str_(s.accountToken) } })
end sub

' The first look takes in what was ready already without saying so: that isn't news.
sub onReady(a as object)
    if a = invalid or a.problem <> "" then return
    r = m.global.reely
    if a.cookie <> "" and a.cookie <> Str_(r.cookie) then
        r.cookie = a.cookie
        m.global.reely = r
        WriteJson_("reely", r)
    end if
    kept = ReadJson_("readySeen", invalid)
    seen = {}
    for each key in Arr_(kept)
        seen[key] = true
    end for
    arrived = Reely_ReadyRequests(a.mine, a.marks, seen)
    if kept = invalid then
        keys = []
        for each t in arrived
            keys.Push(Reely_Key(t))
        end for
        WriteJson_("readySeen", keys)
        return
    end if
    m.global.ready = arrived
    if arrived.Count() > 0 then Trace_("ready " + arrived[0].title)
end sub

' Put away, watched or not: not said again.
sub dismissReady(t as object)
    key = Reely_Key(t)
    seen = Arr_(ReadJson_("readySeen", []))
    found = false
    for each k in seen
        if k = key then found = true
    end for
    if not found then seen.Push(key)
    WriteJson_("readySeen", seen)
    rest = []
    for each x in Arr_(m.global.ready)
        if Reely_Key(x) <> key then rest.Push(x)
    end for
    m.global.ready = rest
end sub

' ------------------------------------------------------------------ Profiles

' Who's signed in, and who else is in their Plex Home to switch to.
sub loadProfiles()
    account = m.store.Read("plexAccountToken")
    if account = "" then account = m.token
    Ask_("homeUsers", { token: account, current: m.token })
end sub

sub showProfiles()
    profile = m.global.profile
    if profile = invalid or Arr_(profile.homeUsers).Count() < 2 or m.profiles <> invalid then return
    m.profiles = CreateObject("roSGNode", "ProfilesView")
    m.profiles.currentUuid = Iif_(profile.user <> invalid, Str_(profile.user.uuid), "")
    m.profiles.users = profile.homeUsers
    m.profiles.observeField("chosen", "onProfileChosen")
    m.overlays.appendChild(m.profiles)
    m.profiles.setFocus(true)
    Trace_("who's watching")
end sub

sub closeProfiles()
    if m.profiles = invalid then return
    m.overlays.removeChild(m.profiles)
    m.profiles = invalid
    c = topScreen()
    if c <> invalid then c.focusIn = true
    maybeTour()
end sub

' ------------------------------------------------------------------ The tour

' Once, after signing in (and choosing who's watching), as the other apps show it.
sub maybeTour()
    if not Bool_(m.global.prefs.tourSeen) then showTour()
end sub

sub showTour()
    if m.tour <> invalid or m.profiles <> invalid or m.page = "player" then return
    m.tour = CreateObject("roSGNode", "TourView")
    m.tour.observeField("done", "onTourDone")
    m.overlays.appendChild(m.tour)
    m.tour.setFocus(true)
end sub

sub onTourDone()
    m.overlays.removeChild(m.tour)
    m.tour = invalid
    p = m.global.prefs
    if not Bool_(p.tourSeen) then
        p.tourSeen = true
        m.global.prefs = p
        WriteJson_("prefs", p)
    end if
    c = topScreen()
    if c <> invalid then c.focusIn = true
end sub

sub onProfileChosen()
    chosen = m.profiles.chosen
    if chosen.close = true then
        closeProfiles()
        return
    end if
    for each u in m.global.profile.homeUsers
        if u.uuid = chosen.uuid then
            m.switchingTo = u
            m.profiles.busy = u.title
        end if
    end for
    account = m.store.Read("plexAccountToken")
    if account = "" then account = m.token
    Ask_("switchUser", { token: account, uuid: chosen.uuid, pin: Str_(chosen.pin) })
end sub

' Another profile: its own token, and everything on screen, the last profile's, read again.
sub onSwitched(a as object)
    if m.profiles = invalid then return
    if a = invalid or a.token = invalid then
        m.profiles.busy = ""
        m.profiles.error = Iif_(a = invalid, "Couldn't switch profiles. Try again.", Str_(a.error))
        return
    end if
    Trace_("switched to " + m.switchingTo.title)
    m.token = a.token
    m.store.Write("plexToken", a.token)
    m.store.Flush()
    profile = m.global.profile
    profile.user = m.switchingTo
    m.global.profile = profile
    WriteJson_("plexProfile", profile)
    m.overlays.removeChild(m.profiles)
    m.profiles = invalid
    clearScreens()
    m.stack = []
    m.global.home = {}
    m.global.watchlist = []
    connect()
end sub

' ------------------------------------------------------------------ Live TV

sub onWatch(event as object)
    watchLive(event.getData())
end sub

sub watchLive(request as object)
    m.watchingFrom = topScreen()
    m.liveView.visible = true
    m.shell.visible = false
    m.page = "live"
    m.liveView.request = request
    m.liveView.takeFocus = true
end sub

sub onLiveDone()
    m.liveView.visible = false
    m.shell.visible = true
    m.page = "shell"
    c = topScreen()
    if c <> invalid then c.focusIn = true
end sub

' A reminder from the guide comes up as its programme starts: Watch, or Dismiss. Those
' long over are let go.
sub checkReminders()
    s = m.global.live
    if s = invalid or s.reminders = invalid or m.notice <> invalid then return
    now = CreateObject("roDateTime").AsSeconds()
    found = Xtream_Reminders(s.reminders, now, m.notified)
    kept = found.kept
    due = found.due
    if due <> invalid then m.notified[Xtream_ReminderKey(due)] = true
    if kept.Count() <> s.reminders.Count() then
        s.reminders = kept
        m.global.live = s
        WriteJson_("live", s)
    end if
    if due <> invalid then showReminder(due)
end sub

sub showReminder(r as object)
    m.notice = CreateObject("roSGNode", "ReminderNotice")
    m.notice.reminder = r
    m.notice.observeField("chosen", "onReminderChosen")
    m.overlays.appendChild(m.notice)
    m.notice.setFocus(true)
    Trace_("reminder due " + r.title)
end sub

sub onReminderChosen()
    chosen = m.notice.chosen
    r = m.notice.reminder
    m.overlays.removeChild(m.notice)
    m.notice = invalid
    if chosen = "watch" then
        ' Whatever was playing stops first, told to Plex as ever.
        if m.page = "player" then m.player.stopNow = true
        watchLive({ channels: [r.channel], index: 0, catchUp: invalid, guide: {}, table: {} })
    else if m.page = "live" then
        m.liveView.takeFocus = true
    else if m.page = "player" then
        m.player.takeFocus = true
    else
        c = topScreen()
        if c <> invalid then c.focusIn = true
    end if
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

' The colour picked in Settings on what's already drawn: the mark, here and in the tabs.
' Everything else takes it as it's drawn.
sub paintAccent()
    for each id in ["signInMark"]
        n = m.top.findNode(id)
        if n <> invalid then n.blendColor = Accent_()
    end for
    m.nav.accent = Accent_()
end sub
