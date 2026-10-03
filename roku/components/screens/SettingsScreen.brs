' Settings: Playback, Subtitles, Episodes, Home, Plex and About, worded as on the other
' apps. A choice is kept at once (MainScene writes it to the registry).

sub init()
    m.body = m.top.findNode("body")
    m.rows = []
    m.at = 0
    m.global.observeField("prefs", "build")
    m.global.observeField("session", "build")
    m.global.observeField("live", "build")
    m.global.observeField("reely", "build")
    m.global.observeField("profile", "build")
end sub

function Modes_() as object
    return [
        { id: "auto", label: "Automatic", note: "Plays the original file. Plex converts it only when needed." },
        { id: "direct", label: "Original only", note: "Always plays the original file. Some may not play." },
        { id: "transcode", label: "Always convert", note: "Plex converts everything. Uses more of your server." }
    ]
end function

function HomeRows_() as object
    return [
        { id: "continueWatching", label: "Continue Watching" },
        { id: "recentEpisodes", label: "Recently Added Episodes" },
        { id: "recentMovies", label: "Recently Added Movies" },
        { id: "watchlist", label: "Watchlist" },
        { id: "playlists", label: "Playlists" }
    ]
end function

function BitrateNote_(kbps as integer) as string
    if kbps = 0 then return "As good as the file itself."
    if kbps = 20000 then return "4K"
    if kbps = 12000 then return "1080p, very high"
    if kbps = 8000 then return "1080p"
    if kbps = 4000 then return "720p"
    return "For a slow connection"
end function

' Reached at home or over the internet, as the Fire TV says it.
function ConnectionKind_(base as string) as string
    host = base
    at = Instr(1, host, "://")
    if at > 0 then host = Mid(host, at + 3)
    slash = Instr(1, host, "/")
    if slash > 0 then host = Left(host, slash - 1)
    if host = "" then return "—"
    host = host.Replace("-", ".")
    homeRe = CreateObject("roRegex", "^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))\.", "")
    if homeRe.IsMatch(host) then return "Home network"
    return "Internet"
end function

' What's shown: a list of settings, each { group, title, note, labels, on, key }.
function Settings_() as object
    p = m.global.prefs
    s = m.global.session
    out = []

    modes = Modes_()
    labels = []
    on = []
    note = ""
    for each md in modes
        labels.Push(md.label)
        on.Push(p.playbackMode = md.id)
        if p.playbackMode = md.id then note = md.note
    end for
    out.Push({ group: "Playback", title: "Playback mode", note: note, labels: labels, on: on, key: "mode" })

    labels = []
    on = []
    for each kbps in Bitrates_()
        labels.Push(Iif_(kbps = 0, "Original", "Up to " + (kbps / 1000).ToStr() + " Mbps"))
        on.Push(p.maxBitrateKbps = kbps)
    end for
    out.Push({ group: "", title: "Conversion quality", note: "The highest quality Plex uses when it converts a video. " + BitrateNote_(p.maxBitrateKbps), labels: labels, on: on, key: "bitrate" })

    out.Push({ group: "Subtitles", title: "Size and background", note: "Subtitles take the caption style set on your Roku, in Settings > Accessibility > Captions style.", labels: [], on: [], key: "" })

    out.Push({ group: "Episodes", title: "Skip intros", note: "Goes straight past an episode's intro when Plex has found it.", labels: ["On", "Off"], on: [p.skipIntros, not p.skipIntros], key: "skipIntros" })
    out.Push({ group: "", title: "Skip credits", note: "Goes straight to the next episode when Plex finds the credits.", labels: ["On", "Off"], on: [p.skipCredits, not p.skipCredits], key: "skipCredits" })
    labels = []
    on = []
    for each n in UpNext_()
        labels.Push(Iif_(n = 0, "Don't start by itself", n.ToStr() + " seconds"))
        on.Push(p.upNextSeconds = n)
    end for
    out.Push({ group: "", title: "Up Next", note: "How long before the next episode starts by itself.", labels: labels, on: on, key: "upNext" })

    hidden = {}
    for each id in Arr_(p.hiddenRows)
        hidden[id] = true
    end for
    labels = []
    on = []
    for each r in HomeRows_()
        labels.Push(r.label)
        on.Push(not hidden.DoesExist(r.id))
    end for
    out.Push({ group: "Home", title: "Rows on Home", note: "Switch a row off to leave it out of Home.", labels: labels, on: on, key: "rows" })

    if s <> invalid and s.base <> invalid then
        profile = m.global.profile
        who = "Signed in"
        canSwitch = false
        if profile <> invalid then
            if profile.user <> invalid then who = Str_(profile.user.title)
            canSwitch = Arr_(profile.homeUsers).Count() > 1
        end if
        if canSwitch then
            out.Push({ group: "Plex", title: who, note: "Watching from " + Str_(s.serverName), labels: ["Switch profile", "Sign out of Plex"], on: [false, false], key: "plexAccount" })
        else
            out.Push({ group: "Plex", title: who, note: "Watching from " + Str_(s.serverName), labels: ["Sign out of Plex"], on: [false], key: "signOut" })
        end if
        servers = Arr_(s.servers)
        if servers.Count() > 1 then
            labels = []
            on = []
            for each name in servers
                labels.Push(Str_(name))
                on.Push(Str_(name) = Str_(s.serverName))
            end for
            out.Push({ group: "", title: "Server", note: "Which of your servers Reely uses first. Libraries from all of them are on Home.", labels: labels, on: on, key: "server" })
        end if
        out.Push({ group: "", title: "Connection", note: ConnectionKind_(Str_(s.base)), labels: [], on: [], key: "" })
    end if

    live = m.global.live
    creds = invalid
    if live <> invalid then creds = live.credentials
    if creds <> invalid and (Str_(creds.base) <> "" or Str_(creds.playlistUrl) <> "") then
        playlist = Str_(creds.playlistUrl) <> ""
        where = HostOf_(Iif_(playlist, Str_(creds.playlistUrl), Str_(creds.base)))
        out.Push({ group: "Live TV", title: Iif_(playlist, "Playlist", "Server"), note: where, labels: ["Sign out of live TV"], on: [false], key: "liveSignOut" })
        account = live.account
        if not playlist and account <> invalid then
            facts = [Str_(account.status)]
            if Str_(account.maxConnections) <> "" then facts.Push(Str_(account.activeConnections) + " of " + Str_(account.maxConnections) + " connections in use")
            if Str_(account.expiresAt) <> "" then facts.Push("Until " + DateOf_(Num_(account.expiresAt)))
            out.Push({ group: "", title: "Account", note: Join_(facts, "  ·  "), labels: [], on: [], key: "" })
        end if
        format = Str_(p.streamFormat)
        out.Push({ group: "", title: "Stream type", note: "Try the other if channels stutter or won't start.", labels: ["HLS", "MPEG-TS"], on: [format <> "ts", format = "ts"], key: "streamFormat" })
    else
        out.Push({ group: "Live TV", title: "Not set up", note: "Sign in to your provider from the Live TV tab.", labels: ["Go to Live TV"], on: [false], key: "goLive" })
    end if

    reely = m.global.reely
    if reely <> invalid and Str_(reely.base) <> "" then
        out.Push({ group: "Requests", title: "Reely", note: HostOf_(Str_(reely.base)), labels: ["Disconnect"], on: [false], key: "reelyOff" })
    else
        out.Push({ group: "Requests", title: "Not connected", note: "Connect to Reely from the Request tab to ask for movies and shows.", labels: ["Go to Request"], on: [false], key: "goRequests" })
    end if

    out.Push({ group: "About", title: "Version", note: CreateObject("roAppInfo").GetVersion(), labels: [], on: [], key: "" })
    out.Push({ group: "", title: "Reely", note: "Your Plex library, live TV and requests, on your TV.", labels: [], on: [], key: "" })
    out.Push({ group: "", title: "Licenses", note: "Geist, the typeface (SIL Open Font License 1.1)", labels: [], on: [], key: "" })
    return out
end function

' Where from, without the login an address may carry.
function HostOf_(address as string) as string
    rest = address
    at = Instr(1, rest, "://")
    if at > 0 then rest = Mid(rest, at + 3)
    slash = Instr(1, rest, "/")
    if slash > 0 then rest = Left(rest, slash - 1)
    q = Instr(1, rest, "?")
    if q > 0 then rest = Left(rest, q - 1)
    login = Instr(1, rest, "@")
    if login > 0 then rest = Mid(rest, login + 1)
    return rest
end function

function DateOf_(epoch as dynamic) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(Int(epoch))
    d.ToLocalTime()
    return d.AsDateString("long-date")
end function

function Bitrates_() as object
    return [0, 20000, 12000, 8000, 4000, 2000]
end function

function UpNext_() as object
    return [0, 5, 10, 12, 15, 20, 30]
end function

' Laid out again after any change, the cursor kept where it was.
sub build()
    if m.top.route = invalid then return
    keepFocus = m.top.isInFocusChain()
    keepIndex = 0
    if m.at < m.rows.Count() then keepIndex = m.rows[m.at].pills.focusIndex
    m.body.removeChildrenIndex(m.body.getChildCount(), 0)
    m.rows = []
    y = 20
    for each st in Settings_()
        if st.group <> "" then
            if y > 20 then y = y + 30
            h = CreateObject("roSGNode", "Label")
            h.font = Bold_(36)
            h.color = "0xF5F2F0FF"
            h.text = st.group
            h.translation = [0, y]
            m.body.appendChild(h)
            y = y + 64
        end if
        t = CreateObject("roSGNode", "Label")
        t.font = Semibold_(30)
        t.color = "0xF5F2F0FF"
        t.text = st.title
        t.translation = [0, y]
        m.body.appendChild(t)
        n = CreateObject("roSGNode", "Label")
        n.font = Regular_(24)
        n.color = "0xB9B4B1FF"
        n.text = st.note
        n.width = 1600
        n.wrap = true
        n.translation = [0, y + 44]
        m.body.appendChild(n)
        y = y + 84
        if st.labels.Count() > 0 then
            pills = CreateObject("roSGNode", "PillRow")
            pills.fontSize = 24
            pills.labels = st.labels
            pills.on = st.on
            pills.translation = [0, y + 4]
            pills.observeField("pressed", "onPressed")
            m.body.appendChild(pills)
            m.rows.Push({ key: st.key, pills: pills, top: y - 84 })
            y = y + 74
        end if
        y = y + 16
    end for
    m.height = y
    if m.at >= m.rows.Count() then m.at = 0
    if m.rows.Count() > 0 then
        row = m.rows[m.at].pills
        if keepIndex < row.labels.Count() then row.focusIndex = keepIndex
    end if
    if keepFocus then focusIn()
end sub

sub focusIn()
    if m.rows.Count() = 0 then return
    if m.at = 0 and m.rows[0].pills.focusIndex = 0 then
        ' At first, on what's chosen.
        on = m.rows[0].pills.on
        for i = 0 to on.Count() - 1
            if on[i] = true then m.rows[0].pills.focusIndex = i
        end for
    end if
    m.rows[m.at].pills.setFocus(true)
    scrollTo(m.at)
end sub

' The setting under the cursor in view, with what's above it where there's room.
sub scrollTo(i as integer)
    top = m.rows[i].top - 140
    if i = 0 or top < 0 then top = 0
    most = m.height - 900
    if most < 0 then most = 0
    if top > most then top = most
    m.body.translation = [96, -top]
end sub

sub move(i as integer)
    m.at = i
    row = m.rows[i].pills
    ' Onto what's chosen there, as the other apps land.
    on = row.on
    for k = 0 to on.Count() - 1
        if on[k] = true then
            row.focusIndex = k
            exit for
        end if
    end for
    row.setFocus(true)
    scrollTo(i)
end sub

sub onPressed(event as object)
    row = event.getRoSGNode()
    i = row.pressed
    key = ""
    for k = 0 to m.rows.Count() - 1
        if m.rows[k].pills.isSameNode(row) then
            key = m.rows[k].key
            m.at = k
        end if
    end for
    p = m.global.prefs
    if key = "mode" then
        p.playbackMode = Modes_()[i].id
    else if key = "bitrate" then
        p.maxBitrateKbps = Bitrates_()[i]
    else if key = "skipIntros" then
        p.skipIntros = i = 0
    else if key = "skipCredits" then
        p.skipCredits = i = 0
    else if key = "upNext" then
        p.upNextSeconds = UpNext_()[i]
    else if key = "rows" then
        id = HomeRows_()[i].id
        list = []
        found = false
        for each h in Arr_(p.hiddenRows)
            if h = id then found = true else list.Push(h)
        end for
        if not found then list.Push(id)
        p.hiddenRows = list
    else if key = "streamFormat" then
        p.streamFormat = Iif_(i = 0, "m3u8", "ts")
    else if key = "liveSignOut" then
        live = m.global.live
        ' The login goes; Favorites and the rest are kept for when it comes back.
        m.top.go = { name: "live", live: { favorites: Arr_(live.favorites), recent: Arr_(live.recent), reminders: [] } }
        return
    else if key = "reelyOff" then
        m.top.go = { name: "reely", reely: {} }
        return
    else if key = "goRequests" then
        m.top.go = { name: "tab", tab: "requests" }
        return
    else if key = "goLive" then
        m.top.go = { name: "tab", tab: "live" }
        return
    else if key = "plexAccount" then
        m.top.go = Iif_(i = 0, { name: "profiles" }, { name: "signOut" })
        return
    else if key = "signOut" then
        m.top.go = { name: "signOut" }
        return
    else if key = "server" then
        m.top.go = { name: "server", server: Str_(Arr_(m.global.session.servers)[i]) }
        return
    else
        return
    end if
    m.top.go = { name: "prefs", prefs: p }
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press or m.rows.Count() = 0 then return false
    if key = "down" and m.at < m.rows.Count() - 1 then
        move(m.at + 1)
        return true
    else if key = "up" and m.at > 0 then
        move(m.at - 1)
        return true
    end if
    return false
end function
