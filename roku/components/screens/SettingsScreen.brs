' Settings: the sections down the left (Playback, Home, Theme, Live TV, Requests, Plex,
' About) and the one the cursor is on shown on the right, worded and grouped as on the
' other apps. A choice is kept at once (MainScene writes it to the registry).

sub init()
    m.rail = m.top.findNode("sections")
    m.page = m.top.findNode("page")
    m.top.findNode("heading").font = Bold_(44)
    m.section = 0
    m.zone = "rail"
    m.rows = []
    m.at = 0
    m.height = 0
    m.chooser = invalid
    paintRail()
    m.top.observeField("focusedChild", "onFocusMoved")
    Listen_("prefs", "build")
    Listen_("session", "build")
    Listen_("live", "build")
    Listen_("reely", "build")
    Listen_("profile", "build")
    Listen_("iptvState", "build")
end sub

function Sections_() as object
    return [
        { id: "playback", label: "Playback" },
        { id: "home", label: "Home" },
        { id: "theme", label: "Theme" },
        { id: "live", label: "Live TV" },
        { id: "requests", label: "Requests" },
        { id: "plex", label: "Plex" },
        { id: "about", label: "About" }
    ]
end function

' ------------------------------------------------------------------ What each section holds

function Modes_() as object
    return [
        { id: "auto", label: "Automatic", note: "Plays the original file. Plex converts it only when needed." },
        { id: "direct", label: "Original only", note: "Always plays the original file. Some may not play." },
        { id: "transcode", label: "Always convert", note: "Plex converts everything. Uses more of your server." }
    ]
end function

function HomeRows_() as object
    rows = [
        { id: "continueWatching", label: "Continue Watching" },
        { id: "recentEpisodes", label: "Recently Added Episodes" },
        { id: "recentMovies", label: "Recently Added Movies" },
        { id: "watchlist", label: "Watchlist" },
        { id: "playlists", label: "Playlists" }
    ]
    ' The provider's rows while its films and series are on.
    if Bool_(m.global.prefs.iptvLibrary) then
        rows.Push({ id: "iptvMovies", label: "New Movies on IPTV" })
        rows.Push({ id: "iptvShows", label: "New Shows on IPTV" })
    end if
    return rows
end function

function BitrateNote_(kbps as integer) as string
    if kbps = 0 then return "As good as the file itself."
    if kbps = 20000 then return "4K"
    if kbps = 12000 then return "1080p, very high"
    if kbps = 8000 then return "1080p"
    if kbps = 4000 then return "720p"
    return "For a slow connection"
end function

function Bitrates_() as object
    return [0, 20000, 12000, 8000, 4000, 2000]
end function

function UpNext_() as object
    return [0, 5, 10, 12, 15, 20, 30]
end function

' Reached at home or over the internet, as the Fire TV says it.
function ConnectionKind_(base as string) as string
    host = HostOf_(base)
    colon = Instr(1, host, ":")
    if colon > 0 then host = Left(host, colon - 1)
    if host = "" then return "—"
    host = host.Replace("-", ".")
    homeRe = CreateObject("roRegex", "^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))\.", "")
    if homeRe.IsMatch(host) then return "Home network"
    return "Internet"
end function

' A setting: { title, note, value, switch, key, options, selected }. With options, OK opens
' the list of them; with switch (true or false), OK flips it; with only a key, OK does it.
function Row_(title as string, note as string, value as string, key as string) as object
    return { title: title, note: note, value: value, switch: invalid, key: key, options: invalid, selected: 0 }
end function

function Switch_(title as string, note as string, on as boolean, key as string) as object
    r = Row_(title, note, "", key)
    r.switch = on
    return r
end function

' options: [{ label, note }]. The note under the title is the one given, else the value's own.
function Choice_(title as string, note as string, options as object, selected as integer, key as string) as object
    if selected < 0 then selected = 0
    current = options[selected]
    shown = note
    if shown = "" and current.note <> invalid then shown = current.note
    r = Row_(title, shown, current.label, key)
    r.options = options
    r.selected = selected
    return r
end function

function Group_(title as string, rows as object, note = "" as string) as object
    return { title: title, rows: rows, note: note }
end function

function Playback_(p as object) as object
    modes = Modes_()
    options = []
    selected = 0
    for i = 0 to modes.Count() - 1
        options.Push({ label: modes[i].label, note: modes[i].note })
        if p.playbackMode = modes[i].id then selected = i
    end for
    video = [Choice_("Playback mode", "", options, selected, "mode")]
    options = []
    selected = 0
    rates = Bitrates_()
    for i = 0 to rates.Count() - 1
        options.Push({ label: Iif_(rates[i] = 0, "Original", "Up to " + (rates[i] / 1000).ToStr() + " Mbps"), note: BitrateNote_(rates[i]) })
        if p.maxBitrateKbps = rates[i] then selected = i
    end for
    video.Push(Choice_("Conversion quality", "The highest quality Plex uses when it converts a video.", options, selected, "bitrate"))

    off = p.subtitlesAtStart = "off"
    subtitles = [
        Choice_("At the start", "Whether movies and episodes start with subtitles on.", [
            { label: "As Plex has them", note: "Your Plex account's subtitle settings, or what was last picked for the title." },
            { label: "Off", note: "Off until you turn them on. Forced subtitles, for parts in another language, still show." }
        ], Iif_(off, 1, 0), "subtitlesAtStart"),
        Row_("Size and background", "Subtitles take the caption style set on your Roku, in Settings > Accessibility > Captions style.", "", "")
    ]

    options = []
    selected = 0
    waits = UpNext_()
    for i = 0 to waits.Count() - 1
        options.Push({ label: Iif_(waits[i] = 0, "Off", waits[i].ToStr() + " seconds"), note: Iif_(waits[i] = 0, "Up Next waits for you to choose.", "") })
        if p.upNextSeconds = waits[i] then selected = i
    end for
    episodes = [
        Switch_("Skip intros", "Goes straight past an episode's intro when Plex has found it.", Bool_(p.skipIntros), "skipIntros"),
        Switch_("Skip credits", "Goes straight to the next episode when Plex finds the credits.", Bool_(p.skipCredits), "skipCredits"),
        Choice_("Up Next", "How long before the next episode starts by itself.", options, selected, "upNext")
    ]
    showPages = [Switch_("Theme music", "Plays a show's theme song on its page.", Bool_(p.themeMusic), "themeMusic")]
    return [Group_("Video", video), Group_("Subtitles", subtitles), Group_("Episodes", episodes), Group_("Show pages", showPages)]
end function

function Home_(p as object) as object
    hidden = {}
    for each id in Arr_(p.hiddenRows)
        hidden[id] = true
    end for
    rows = []
    for each r in HomeRows_()
        rows.Push(Switch_(r.label, "", not hidden.DoesExist(r.id), "row:" + r.id))
    end for
    saver = p.screensaver = invalid or Bool_(p.screensaver)
    screensaver = [Choice_("Screensaver", "", [
        { label: "Library artwork", note: "Your library's artwork and the time, when the remote's been put down. When it starts is set on your Roku: Settings > Theme > Screensaver." },
        { label: "Off", note: "The time alone, on black, when the remote's been put down." }
    ], Iif_(saver, 0, 1), "screensaver")]
    return [Group_("Rows on Home", rows), Group_("Screensaver", screensaver)]
end function

function Theme_(p as object) as object
    options = []
    selected = 0
    choices = Accent_Choices()
    for i = 0 to choices.Count() - 1
        options.Push({ label: choices[i].label, note: Iif_(choices[i].id = "blue", "Reely's own", "") })
        if Accent_Of(p.accent).id = choices[i].id then selected = i
    end for
    return [Group_("Theme", [Choice_("Theme", "The color of what's highlighted, progress bars, and the Reely mark.", options, selected, "accent")])]
end function

function Live_(p as object) as object
    live = m.global.live
    creds = invalid
    if live <> invalid then creds = live.credentials
    if creds = invalid or (Str_(creds.base) = "" and Str_(creds.playlistUrl) = "") then
        return [Group_("Live TV", [Row_("Not signed in", "Sign in to your provider from the Live TV tab.", "Go to Live TV", "goLive")])]
    end if
    playlist = Str_(creds.playlistUrl) <> ""
    out = []
    ' Read afresh each time the Live TV tab opens: these go there.
    out.Push(Group_("Channels and guide", [
        Row_("Refresh channels", "", "", "goLive"),
        Row_("Refresh TV guide", "", "", "goLive")
    ], "Channels and the guide are read again each time the Live TV tab opens."))
    watching = []
    if not playlist then
        format = Str_(p.streamFormat)
        watching.Push(Choice_("Stream type", "Try the other if channels stutter or won't start.", [
            { label: "HLS", note: "Works with most providers." },
            { label: "MPEG-TS", note: "Starts faster with some providers." }
        ], Iif_(format = "ts", 1, 0), "streamFormat"))
    end if
    watching.Push(Switch_("Guide preview", "Plays the highlighted channel in the guide.", p.guidePreview = invalid or Bool_(p.guidePreview), "guidePreview"))
    out.Push(Group_("Watching", watching))

    if playlist then
        out.Push(Group_("Movies and shows", [Row_("Show IPTV movies and shows", "", "Needs an Xtream login", "")], "Your provider's movies and shows need an Xtream login rather than a playlist. Sign out and sign in with your server, username and password to use them."))
    else
        on = Bool_(p.iptvLibrary)
        s = m.global.iptvState
        note = "Your provider's movies and shows in the Movies and TV Shows tabs, on Home and in search, marked IPTV. Off, only Plex's are shown."
        if on and Str_(s.error) <> "" then note = s.error
        rows = [Switch_("Show IPTV movies and shows", "", on, "iptvLibrary")]
        if on then
            wins = Bool_(p.iptvWins)
            rows.Push(Choice_("When a title is in both", "Which copy shows on Home and in search, and which the IPTV library leaves out.", [
                { label: "Plex", note: "Plex's copy. The IPTV library only has what Plex doesn't." },
                { label: "IPTV", note: "The provider's copy, in place of Plex's on Home and in search." }
            ], Iif_(wins, 1, 0), "iptvWins"))
            counts = "Not loaded"
            if Bool_(s.loading) then
                counts = "Refreshing…"
            else if Bool_(s.ready) then
                counts = Int(Num_(s.movies)).ToStr() + " movies · " + Int(Num_(s.shows)).ToStr() + " shows"
            end if
            rows.Push(Row_("Refresh movies and shows", "", counts, "iptvRefresh"))
        end if
        out.Push(Group_("Movies and shows", rows, note))
    end if

    provider = [Row_(Iif_(playlist, "Playlist", "Server"), "", HostOf_(Iif_(playlist, Str_(creds.playlistUrl), Str_(creds.base))), "")]
    account = live.account
    if not playlist and account <> invalid then
        facts = []
        status = Str_(account.status)
        if status <> "" then facts.Push(UCase(Left(status, 1)) + Mid(status, 2))
        if Str_(account.expiresAt) <> "" then facts.Push("until " + DateOf_(Num_(account.expiresAt)))
        provider.Push(Row_("Account", "", Iif_(facts.Count() > 0, Join_(facts, " · "), "—"), ""))
        if Str_(account.maxConnections) <> "" then
            provider.Push(Row_("Connections", "Each channel on screen uses one.", Str_(account.activeConnections) + " of " + Str_(account.maxConnections) + " in use", ""))
        end if
    end if
    provider.Push(Row_("Sign out of live TV", "", "", "liveSignOut"))
    out.Push(Group_("Provider", provider))
    return out
end function

function Requests_() as object
    reely = m.global.reely
    if reely = invalid or Str_(reely.base) = "" then
        return [Group_("Requests", [Row_("Not connected", "Connect to Reely from the Request tab to ask for movies and shows.", "Go to Request", "goRequests")])]
    end if
    return [Group_("Reely", [Row_("Server", "", HostOf_(Str_(reely.base)), ""), Row_("Disconnect", "", "", "reelyOff")], "Requests go to Reely, signed in with your Plex account.")]
end function

function Plex_(p as object) as object
    s = m.global.session
    if s = invalid or s.base = invalid then
        return [Group_("Plex", [Row_("Not signed in", "Sign in from the Home tab.", "", "")])]
    end if
    out = []
    profile = m.global.profile
    who = "Signed in"
    canSwitch = false
    user = invalid
    if profile <> invalid then
        user = profile.user
        if user <> invalid then who = Str_(user.title)
        canSwitch = Arr_(profile.homeUsers).Count() > 1
    end if
    role = "Signed in to Plex"
    if user <> invalid and Bool_(user.admin) then
        role = "Plex Home owner"
    else if user <> invalid and Bool_(user.restricted) then
        role = "Managed profile"
    else if canSwitch then
        role = "Plex Home member"
    end if
    out.Push(Group_("Profile", [Row_(who, role, Iif_(canSwitch, "Switch", ""), Iif_(canSwitch, "profile", ""))], Iif_(canSwitch, "Each profile in your Plex Home has its own libraries, watch history and Continue Watching.", "")))
    out.Push(Group_("Server", [Row_("Server", "", Str_(s.serverName), ""), Row_("Connection", "", ConnectionKind_(Str_(s.base)), "")]))
    if Bool_(p.iptvLibrary) then
        inMenus = p.iptvInMenus = invalid or Bool_(p.iptvInMenus)
        out.Push(Group_("Libraries", [Switch_("IPTV", "In the Movies and TV Shows menus.", inMenus, "iptvInMenus")], "IPTV is shown in the Movies and TV Shows menus unless switched off here."))
    end if
    servers = Arr_(s.servers)
    if servers.Count() > 1 then
        rows = []
        for i = 0 to servers.Count() - 1
            current = Str_(servers[i]) = Str_(s.serverName)
            rows.Push(Row_(Str_(servers[i]), "", Iif_(current, "In use", "Switch"), Iif_(current, "", "server:" + i.ToStr())))
        end for
        out.Push(Group_("Servers", rows, "Which of your servers Reely uses first. Libraries from all of them are on Home."))
    end if
    out.Push(Group_("Account", [Row_("Sign out of Plex", "", "", "signOut")]))
    return out
end function

function About_() as object
    out = [
        Group_("Help", [Row_("Take the tour", "How to get around with the remote.", "", "tour")]),
        Group_("Licenses", [Row_("Geist", "The typeface.", "SIL Open Font License", "")])
    ]
    ' What went wrong last, and the last time the app was held up, kept on this Roku only.
    store = CreateObject("roRegistrySection", "reely")
    rows = []
    raw = store.Read("problem")
    problem = invalid
    if raw <> "" then problem = ParseJson(raw)
    if problem <> invalid and type(problem) = "roAssociativeArray" then
        note = DateOf_(Num_(problem.at)) + "  ·  " + Str_(problem.message)
        if m.reading = true then note = Problem_Detail(problem)
        rows.Push(Row_("Something went wrong", note, Iif_(m.reading = true, "Hide", "View"), "problemView"))
    end if
    raw = store.Read("stall")
    stall = invalid
    if raw <> "" then stall = ParseJson(raw)
    if stall <> invalid and type(stall) = "roAssociativeArray" and not HeldBySaver_(stall, Num_(store.Read("saverAt"))) then
        note = DateOf_(Num_(stall.at)) + "  ·  For " + Str_(stall.seconds) + " seconds"
        if m.reading = true then
            note = note + Chr(10) + "While: " + Iif_(Str_(stall.crumb) = "", "starting", Str_(stall.crumb))
            if Arr_(stall.trail).Count() > 0 then note = note + Chr(10) + "Before that: " + Join_(Arr_(stall.trail), " › ")
            if stall.ended = true then note = note + Chr(10) + "It went on again afterwards."
        end if
        rows.Push(Row_("The app was held up", note, Iif_(m.reading = true, "Hide", "View"), "problemView"))
    end if
    if rows.Count() > 0 then
        rows.Push(Row_("Clear the report", "", "", "problemClear"))
        out.Push(Group_("Problem report", rows, "Kept on this Roku only. Nothing is sent anywhere."))
    end if
    return out
end function

function Groups_(id as string) as object
    p = m.global.prefs
    if id = "playback" then return Playback_(p)
    if id = "home" then return Home_(p)
    if id = "theme" then return Theme_(p)
    if id = "live" then return Live_(p)
    if id = "requests" then return Requests_()
    if id = "plex" then return Plex_(p)
    return About_()
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

' Held up while the Roku's screensaver was on: the app was only waiting, not stuck.
function HeldBySaver_(stall as object, saverAt as dynamic) as boolean
    ends = Num_(stall.at)
    starts = ends - Num_(stall.seconds)
    return saverAt >= starts and saverAt <= ends
end function

function DateOf_(epoch as dynamic) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(Int(epoch))
    d.ToLocalTime()
    return d.AsDateString("long-date")
end function

' Roughly how many lines a text takes at this size and width.
function Lines_(text as string, size as integer, width as integer) as integer
    n = 0
    for each part in text.Split(Chr(10))
        perLine = Int(width / (size * 0.5))
        k = Int((Len(part) + perLine - 1) / perLine)
        if k < 1 then k = 1
        n = n + k
    end for
    return n
end function

' ------------------------------------------------------------------ Drawing

sub paintRail()
    if m.railItems = invalid then
        m.railItems = []
        y = 0
        for each s in Sections_()
            fill = CreateObject("roSGNode", "Poster")
            fill.uri = "pkg:/images/pill.9.png"
            fill.width = 300
            fill.height = 62
            fill.translation = [0, y]
            label = CreateObject("roSGNode", "Label")
            label.text = s.label
            label.width = 260
            label.height = 62
            label.vertAlign = "center"
            label.translation = [28, y]
            m.rail.appendChild(fill)
            m.rail.appendChild(label)
            m.railItems.Push({ fill: fill, label: label })
            y = y + 72
        end for
    end if
    onRail = m.zone = "rail" and m.top.isInFocusChain() and m.chooser = invalid
    for i = 0 to m.railItems.Count() - 1
        it = m.railItems[i]
        if i = m.section and onRail then
            it.fill.visible = true
            it.fill.blendColor = "0xF2F4F7FF"
            it.label.color = "0x08090BFF"
            it.label.font = Semibold_(30)
        else if i = m.section then
            it.fill.visible = true
            it.fill.blendColor = "0x1F2329FF"
            it.label.color = "0xF2F4F7FF"
            it.label.font = Semibold_(30)
        else
            it.fill.visible = false
            it.label.color = "0xB3BAC4FF"
            it.label.font = Regular_(30)
        end if
    end for
end sub

' The section shown, laid out again after any change, the cursor kept on the same setting.
sub build()
    if Gone_() then return
    if m.top.route = invalid then return
    keepKey = ""
    if m.at < m.rows.Count() then keepKey = m.rows[m.at].row.key
    m.page.removeChildrenIndex(m.page.getChildCount(), 0)
    m.rows = []
    accent = m.global.accent
    if accent = invalid then accent = "0x2E6BFFFF"
    y = 6
    id = Sections_()[m.section].id
    if id = "about" then
        mark = CreateObject("roSGNode", "Label")
        mark.text = "reely"
        mark.font = Bold_(80)
        mark.color = accent
        mark.translation = [8, y - 10]
        m.page.appendChild(mark)
        tag = CreateObject("roSGNode", "Label")
        tag.text = "Your Plex library and live TV, in one place."
        tag.font = Regular_(28)
        tag.color = "0xF2F4F7FF"
        tag.translation = [8, y + 92]
        m.page.appendChild(tag)
        version = CreateObject("roSGNode", "Label")
        version.text = "Version " + CreateObject("roAppInfo").GetVersion()
        version.font = Regular_(24)
        version.color = "0xB3BAC4FF"
        version.translation = [8, y + 134]
        m.page.appendChild(version)
        y = y + 200
    end if
    for each g in Groups_(id)
        head = CreateObject("roSGNode", "Label")
        head.text = UCase(g.title)
        head.font = Semibold_(22)
        head.color = "0x7F8792FF"
        head.translation = [8, y]
        m.page.appendChild(head)
        y = y + 40
        panel = CreateObject("roSGNode", "Poster")
        panel.uri = "pkg:/images/card.9.png"
        panel.blendColor = "0x15171BFF"
        panel.width = 1380
        panel.translation = [0, y]
        m.page.appendChild(panel)
        top = y
        y = y + 6
        for each r in g.rows
            y = y + drawRow(r, y, accent) + 2
        end for
        y = y + 4
        panel.height = y - top
        if g.note <> "" then
            note = CreateObject("roSGNode", "Label")
            note.text = g.note
            note.font = Regular_(22)
            note.color = "0x7F8792FF"
            note.width = 1300
            note.wrap = true
            note.translation = [8, y + 10]
            m.page.appendChild(note)
            y = y + 14 + 30 * Lines_(g.note, 22, 1300)
        end if
        y = y + 34
    end for
    m.height = y
    m.at = 0
    for i = 0 to m.rows.Count() - 1
        if keepKey <> "" and m.rows[i].row.key = keepKey then m.at = i
    end for
    if m.rows.Count() = 0 and m.zone = "page" then m.zone = "rail"
    paintRows()
    paintRail()
end sub

' One setting at y; its height. Only those that do something can take the cursor.
function drawRow(r as object, y as integer, accent as string) as integer
    h = 84
    if r.note <> "" then h = 80 + 32 * Lines_(r.note, 24, 860)
    fill = CreateObject("roSGNode", "Poster")
    fill.uri = "pkg:/images/card.9.png"
    fill.blendColor = "0xF2F4F7FF"
    fill.width = 1368
    fill.height = h
    fill.translation = [6, y]
    fill.visible = false
    m.page.appendChild(fill)
    title = CreateObject("roSGNode", "Label")
    title.text = r.title
    title.font = Regular_(30)
    title.width = 860
    title.translation = [32, y + 22]
    m.page.appendChild(title)
    note = invalid
    if r.note <> "" then
        note = CreateObject("roSGNode", "Label")
        note.text = r.note
        note.font = Regular_(24)
        note.width = 860
        note.wrap = true
        note.translation = [32, y + 62]
        m.page.appendChild(note)
    end if
    right = 1380 - 30
    chevron = invalid
    if r.options <> invalid then
        chevron = CreateObject("roSGNode", "Poster")
        chevron.uri = "pkg:/images/chevron.png"
        chevron.width = 32
        chevron.height = 32
        chevron.translation = [right - 32, y + Int((h - 32) / 2)]
        m.page.appendChild(chevron)
        right = right - 48
    end if
    track = invalid
    knob = invalid
    value = invalid
    if r.switch <> invalid then
        track = CreateObject("roSGNode", "Poster")
        track.uri = "pkg:/images/pill.9.png"
        track.width = 64
        track.height = 38
        track.translation = [right - 64, y + Int((h - 38) / 2)]
        m.page.appendChild(track)
        knob = CreateObject("roSGNode", "Poster")
        knob.uri = "pkg:/images/circle.png"
        knob.width = 30
        knob.height = 30
        knob.translation = [right - 64 + Iif_(r.switch = true, 30, 4), y + Int((h - 38) / 2) + 4]
        m.page.appendChild(knob)
    else if r.value <> "" then
        value = CreateObject("roSGNode", "Label")
        value.text = r.value
        value.font = Regular_(28)
        value.width = 440
        value.horizAlign = "right"
        value.translation = [right - 440, y + Int((h - 36) / 2)]
        m.page.appendChild(value)
    end if
    item = { row: r, fill: fill, title: title, note: note, value: value, chevron: chevron, track: track, knob: knob, top: y, height: h, accent: accent }
    if r.key <> "" then m.rows.Push(item)
    paintRow(item, false)
    return h
end function

sub paintRow(it as object, focused as boolean)
    it.fill.visible = focused
    it.title.color = Iif_(focused, "0x08090BFF", "0xF2F4F7FF")
    if it.note <> invalid then it.note.color = Iif_(focused, "0x08090BB3", "0xB3BAC4FF")
    if it.value <> invalid then it.value.color = Iif_(focused, "0x08090BFF", "0xB3BAC4FF")
    if it.chevron <> invalid then it.chevron.blendColor = Iif_(focused, "0x08090BFF", "0x7F8792FF")
    if it.track <> invalid then
        on = it.row.switch = true
        if on then
            it.track.blendColor = it.accent
            it.knob.blendColor = "0xF2F4F7FF"
        else if focused then
            it.track.blendColor = "0xBFC1C4FF"
            it.knob.blendColor = "0x6F7175FF"
        else
            it.track.blendColor = "0x3A3F47FF"
            it.knob.blendColor = "0xF2F4F7FF"
        end if
    end if
end sub

sub paintRows()
    onPage = m.zone = "page" and m.chooser = invalid and m.top.isInFocusChain()
    for i = 0 to m.rows.Count() - 1
        paintRow(m.rows[i], onPage and i = m.at)
    end for
    ' Back on the sections, the page stays where it was scrolled to.
    if m.zone <> "page" then return
    ' The setting under the cursor in view, with what's above it where there's room.
    top = 0
    if m.zone = "page" and m.at < m.rows.Count() and m.at > 0 then top = m.rows[m.at].top - 200
    most = m.height - 940
    if most < 0 then most = 0
    if top < 0 then top = 0
    if top > most then top = most
    m.page.translation = [0, -top]
end sub

' ------------------------------------------------------------------ Moving about

sub focusIn()
    ' Entered at the sections, on the one showing.
    m.zone = "rail"
    Trace_("section " + Sections_()[m.section].id)
    m.top.setFocus(true)
    paintRows()
    paintRail()
end sub

' Up to the tabs, or back: nothing marked as under the cursor here.
sub onFocusMoved()
    if m.chooser <> invalid then return
    paintRows()
    paintRail()
end sub

sub showSection(i as integer)
    m.section = i
    m.at = 0
    m.rows = []
    m.page.translation = [0, 0]
    Trace_("section " + Sections_()[i].id)
    build()
end sub

sub moveTo(i as integer)
    m.at = i
    Trace_("setting " + m.rows[i].row.key)
    paintRows()
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.zone = "rail" then
        count = Sections_().Count()
        if key = "down" then
            if m.section < count - 1 then showSection(m.section + 1)
            return true
        else if key = "up" and m.section > 0 then
            showSection(m.section - 1)
            return true
        else if key = "right" or key = "OK" then
            if m.rows.Count() > 0 then
                m.zone = "page"
                moveTo(0)
                paintRail()
            end if
            return true
        end if
        return false
    end if
    if key = "down" then
        if m.at < m.rows.Count() - 1 then moveTo(m.at + 1)
        return true
    else if key = "up" then
        if m.at > 0 then
            moveTo(m.at - 1)
            return true
        end if
        return false
    else if key = "left" or key = "back" then
        ' Back to the section these belong to.
        m.zone = "rail"
        paintRows()
        paintRail()
        return true
    else if key = "OK" then
        press_(m.rows[m.at].row)
        return true
    else if key = "right" then
        return true
    end if
    return false
end function

' ------------------------------------------------------------------ Changing a setting

sub press_(r as object)
    if r.options <> invalid then
        labels = []
        notes = []
        for each o in r.options
            labels.Push(o.label)
            notes.Push(Iif_(o.note = invalid, "", o.note))
        end for
        m.choosing = r
        m.chooser = CreateObject("roSGNode", "Chooser")
        m.chooser.setFields({ title: r.title, notes: notes, current: r.selected })
        m.chooser.options = labels
        m.chooser.observeField("picked", "onChosen")
        ' Over the whole screen, tabs and all.
        Overlays_().appendChild(m.chooser)
        m.chooser.setFocus(true)
        paintRows()
        return
    end if
    apply(r.key, -1)
end sub

sub onChosen()
    picked = m.chooser.picked
    Overlays_().removeChild(m.chooser)
    m.chooser = invalid
    m.top.setFocus(true)
    r = m.choosing
    paintRows()
    if picked >= 0 and picked <> r.selected then apply(r.key, picked)
end sub

sub apply(key as string, i as integer)
    p = m.global.prefs
    if key = "mode" then
        p.playbackMode = Modes_()[i].id
    else if key = "bitrate" then
        p.maxBitrateKbps = Bitrates_()[i]
    else if key = "accent" then
        p.accent = Accent_Choices()[i].id
    else if key = "subtitlesAtStart" then
        p.subtitlesAtStart = Iif_(i = 0, "plex", "off")
    else if key = "skipIntros" then
        p.skipIntros = not Bool_(p.skipIntros)
    else if key = "skipCredits" then
        p.skipCredits = not Bool_(p.skipCredits)
    else if key = "guidePreview" then
        p.guidePreview = not (p.guidePreview = invalid or Bool_(p.guidePreview))
    else if key = "themeMusic" then
        p.themeMusic = not Bool_(p.themeMusic)
    else if key = "upNext" then
        p.upNextSeconds = UpNext_()[i]
    else if Left(key, 4) = "row:" then
        id = Mid(key, 5)
        list = []
        found = false
        for each h in Arr_(p.hiddenRows)
            if h = id then found = true else list.Push(h)
        end for
        if not found then list.Push(id)
        p.hiddenRows = list
    else if key = "streamFormat" then
        p.streamFormat = Iif_(i = 0, "m3u8", "ts")
    else if key = "screensaver" then
        p.screensaver = i = 0
    else if key = "iptvLibrary" then
        p.iptvLibrary = not Bool_(p.iptvLibrary)
    else if key = "iptvInMenus" then
        p.iptvInMenus = not (p.iptvInMenus = invalid or Bool_(p.iptvInMenus))
    else if key = "iptvWins" then
        p.iptvWins = i = 1
    else if key = "tour" then
        m.top.go = { name: "tour" }
        return
    else if key = "problemView" then
        m.reading = not (m.reading = true)
        build()
        return
    else if key = "problemClear" then
        store = CreateObject("roRegistrySection", "reely")
        store.Delete("problem")
        store.Delete("stall")
        store.Flush()
        m.reading = false
        build()
        return
    else if key = "iptvRefresh" then
        m.top.go = { name: "iptvRefresh" }
        return
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
    else if key = "profile" then
        m.top.go = { name: "profiles" }
        return
    else if key = "signOut" then
        m.top.go = { name: "signOut" }
        return
    else if Left(key, 7) = "server:" then
        m.top.go = { name: "server", server: Str_(Arr_(m.global.session.servers)[Val(Mid(key, 8))]) }
        return
    else
        return
    end if
    m.top.go = { name: "prefs", prefs: p }
end sub
