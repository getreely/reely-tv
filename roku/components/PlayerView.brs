' Minutes the sleep timer offers, as the Fire TV's; -1 is the end of this episode.
function SleepChoices_() as object
    return [0, 15, 30, 45, 60, 90, -1]
end function

sub init()
    m.video = m.top.findNode("video")
    m.wait = m.top.findNode("wait")
    m.skip = m.top.findNode("skip")
    m.post = m.top.findNode("post")
    m.controls = m.top.findNode("controls")
    m.ctlRow = m.top.findNode("ctlRow")
    m.ticker = m.top.findNode("ticker")
    m.sleepTimer = m.top.findNode("sleepTimer")
    m.wait.font = Regular_(30)
    m.top.findNode("skipLabel").font = Semibold_(30)
    m.top.findNode("sleepNote").font = Regular_(26)
    m.top.findNode("postNow").font = Regular_(24)
    m.top.findNode("postLabel").font = Semibold_(24)
    m.top.findNode("postShow").font = Bold_(72)
    m.top.findNode("postLine").font = Regular_(30)
    m.top.findNode("postTitle").font = Bold_(44)
    m.top.findNode("postSummary").font = Regular_(28)
    m.top.findNode("playNextLabel").font = Semibold_(30)
    m.top.findNode("creditsLabel").font = Semibold_(30)
    m.top.findNode("barAt").font = Regular_(24)
    m.top.findNode("barLeft").font = Regular_(24)
    m.top.findNode("ctlTitle").font = Semibold_(34)
    m.top.findNode("ctlSub").font = Regular_(26)
    m.video.observeField("state", "onState")
    m.video.observeField("position", "onPosition")
    m.ticker.observeField("fire", "onTick")
    m.sleepTimer.observeField("fire", "onSleep")
    m.retryTimer = m.top.findNode("retryTimer")
    m.retryTimer.observeField("fire", "onRetry")
    m.drops = 0
    m.stuckAt = -1
    m.lastMs = 0
    m.sleepEnd = false
    m.sleepUntil = 0
    m.ctlAt = -1
    m.chooser = invalid
end sub

sub takeFocus()
    m.video.setFocus(true)
end sub

sub start()
    r = m.top.request
    m.item = r.item
    m.queue = Arr_(r.queue)
    m.trailer = r.trailer = true
    m.mediaIndex = Int(Num_(r.mediaIndex))
    m.base = m.item.serverBase
    if m.base = invalid or m.base = "" then m.base = Session_().base
    m.token = TokenFor_(m.base)
    m.startMs = 0
    if r.resume = true and m.item.viewOffsetMs > 0 and not (m.item.durationMs > 0 and m.item.viewOffsetMs >= m.item.durationMs * 0.95) then m.startMs = m.item.viewOffsetMs
    m.leaving = false
    m.drops = 0
    m.stuckAt = -1
    m.lastMs = 0
    m.retryTimer.control = "stop"
    m.introDone = false
    m.creditsOffered = false
    m.upNextAt = 0
    m.lastReport = 0
    m.skip.visible = false
    hidePostPlay()
    hideControls()
    closePanel()
    m.wait.text = "Loading…"
    m.video.control = "stop"
    m.video.setFocus(true)
    m.p = invalid
    if m.base = "iptv:" then
        Ask_("iptvPlayback", { ratingKey: m.item.ratingKey, durationMs: m.item.durationMs })
    else
        Ask_("playback", { base: m.base, token: m.token, ratingKey: m.item.ratingKey, mediaIndex: m.mediaIndex })
    end if
    ' An episode started from Home, Continue Watching, search or a poster's menu came with
    ' nothing after it, and the last of a season had nothing either: no Up Next, and the
    ' player just closed. The whole show is asked for instead, every season in order: the
    ' provider's from what it says of the series, else Plex's.
    showKey = Str_(m.item.grandparentRatingKey)
    if Str_(m.item.type) = "episode" and showKey <> "" and Plex_NextInQueue(m.queue, m.item) = invalid then
        if m.base = "iptv:" then
            Ask_("iptvNext", { showKey: showKey, id: m.item.ratingKey })
        else
            Ask_("nextEpisode", { base: m.base, token: m.token, showKey: showKey, id: m.item.ratingKey })
        end if
    end if
end sub

sub answered(r as object)
    if r.op = "findSubtitles" then
        showFound(r.results)
        return
    else if r.op = "addSubtitle" then
        onAdded(r.answer)
        return
    else if r.op = "nextEpisode" or r.op = "iptvNext" then
        ' Still the episode it was asked for, and in what came back.
        if r.answer = invalid or m.item = invalid or Str_(r.id) <> m.item.ratingKey then return
        queue = Arr_(r.answer.queue)
        for each e in queue
            if e.ratingKey = m.item.ratingKey then
                m.queue = queue
                return
            end if
        end for
        return
    end if
    if r.op = "playback" or r.op = "iptvPlayback" then
        p = r.answer
        if p = invalid then
            m.wait.text = "That file isn't on the server any more."
            if r.op = "iptvPlayback" then m.wait.text = Iif_(Str_(r.error) <> "", Str_(r.error), "Your provider couldn't send this. Try again in a moment.")
            return
        end if
        if r.op = "playback" and m.global.prefs <> invalid then Plex_AtStart(p, Str_(m.global.prefs.subtitlesAtStart))
        m.p = p
        begin(m.startMs)
    end if
end sub

' Whether this Roku plays the file as it is, by what its own decoders say yes to.
function canDirect(p as object) as boolean
    info = CreateObject("roDeviceInfo")
    video = {}
    for each codec in ["h264", "hevc", "vp9", "av1", "mpeg2video"]
        name = codec
        if codec = "h264" then name = "mpeg4 avc"
        video[codec] = info.CanDecodeVideo({ codec: name }).result = true
    end for
    audio = {}
    for each codec in ["aac", "ac3", "eac3", "mp3", "flac", "opus"]
        audio[codec] = info.CanDecodeAudio({ codec: codec }).result = true
    end for
    m.plan = Plex_PlanDirect(p.container, p.videoCodec, p.audioCodec, video, audio)
    return m.plan.direct
end function

' Playing from [fromMs]: the file as it is when the Roku can and nothing needs Plex's
' conversion; otherwise converted, at the quality chosen in Settings.
sub begin(fromMs as dynamic)
    p = m.p
    if Bool_(p.iptv) then
        beginIptv(fromMs)
        return
    end if
    prefs = m.global.prefs
    mode = "auto"
    kbps = 0
    if prefs <> invalid then
        mode = Str_(prefs.playbackMode)
        kbps = Int(Num_(prefs.maxBitrateKbps))
    end if
    subs = Plex_SubtitlePlan(p)
    if mode = "direct" then
        direct = true
        canDirect(p)
    else if mode = "transcode" or subs.burn then
        direct = false
    else
        direct = canDirect(p)
    end if
    if m.forceConvert = true then direct = false
    m.session = Mid(CreateObject("roDeviceInfo").GetRandomUUID(), 1, 12)
    content = CreateObject("roSGNode", "ContentNode")
    content.title = Plex_RowTitle(m.item)
    if direct then
        content.url = p.url
        content.streamFormat = m.plan.format
    else
        content.url = Plex_TranscodeUrl(m.base, m.token, m.item.ratingKey, m.session, m.global.clientId, m.mediaIndex, kbps, Iif_(subs.burn, "burn", "none"))
        content.streamFormat = "hls"
    end if
    ' The Roku's own trick-play pictures, from Plex's index of the file.
    if p.bif <> "" then
        content.SDBifUrl = p.bif
        content.HDBifUrl = p.bif
    end if
    ' Text subtitles in their own file, drawn by the Roku in the style set on the Roku.
    if subs.text then
        content.SubtitleTracks = [{ TrackName: subs.on.url, Language: Iif_(subs.on.language <> "", subs.on.language, "eng"), Description: subs.on.label }]
    end if
    m.direct = direct
    m.durationMs = p.durationMs
    m.video.content = content
    if subs.text then m.video.subtitleTrack = subs.on.url
    m.video.seek = fromMs / 1000
    m.video.control = "play"
    m.ticker.control = "start"
end sub

' The provider's file, as it is: there's no Plex to convert it.
sub beginIptv(fromMs as dynamic)
    p = m.p
    formats = { mp4: "mp4", m4v: "mp4", mov: "mp4", mkv: "mkv", ts: "ts", m3u8: "hls" }
    format = formats[p.container]
    if format = invalid then format = "mp4"
    m.plan = { direct: true, format: format, reason: "" }
    m.session = ""
    content = CreateObject("roSGNode", "ContentNode")
    content.title = Plex_RowTitle(m.item)
    content.url = p.url
    content.streamFormat = format
    Trace_("iptv file " + p.url)
    m.direct = true
    m.durationMs = p.durationMs
    m.video.content = content
    m.video.seek = fromMs / 1000
    m.video.control = "play"
    m.ticker.control = "start"
end sub

sub onState()
    s = m.video.state
    Trace_("video " + s)
    if s = "playing" then
        m.wait.text = ""
        ' Playing again: the next drop gets its full set of tries.
        m.drops = 0
    else if s = "paused" then
        report("paused")
    else if s = "finished" then
        ended()
    else if s = "error" then
        code = m.video.errorCode
        Trace_("video error " + Str(code).Trim() + " " + Str_(m.video.errorMsg))
        ' The connection dropped (unreachable, an HTTP failure, timed out): started again
        ' as a fresh stream from where it got to, a few times with a growing wait, then
        ' OK. The same address again is no use when Plex has ended the session behind it.
        if m.p <> invalid and (code = 0 or code = -1 or code = -2) then
            at = Int(m.video.position * 1000)
            if at <= 0 then at = m.lastMs
            ' The last few seconds not arriving is the end, not a lost connection: a file whose
            ' tail can't be read failed there every time, and each fresh try went back to the
            ' same spot. Anywhere earlier, the credits included, it reconnects and plays on.
            length = Num_(m.durationMs)
            if m.video.duration > 0 then length = m.video.duration * 1000
            if length > 0 and at >= length - 15000 then
                ended()
                return
            end if
            if m.drops < 3 then
                m.drops = m.drops + 1
                m.retryAt = at
                m.wait.text = "Reconnecting…"
                m.retryTimer.duration = 3 * m.drops
                m.retryTimer.control = "start"
            else
                m.stuckAt = at
                m.wait.text = Iif_(Bool_(m.p.iptv), "Lost the connection to your IPTV provider. Press OK to try again.", "Lost the connection to your Plex server. Press OK to try again.")
            end if
            return
        end if
        ' The file wouldn't play as it is: Plex converts it, from where it got to.
        if m.p <> invalid and Bool_(m.p.iptv) then
            m.wait.text = "Your provider couldn't play this. Try again in a moment."
            report("stopped")
        else if m.direct and m.p <> invalid then
            m.forceConvert = true
            begin(Int(m.video.position * 1000))
        else
            m.wait.text = "This didn't play. Check the connection to your Plex server and try again."
            report("stopped")
        end if
    end if
end sub

' The wait after a dropped connection is over: a fresh stream, from where it was.
sub onRetry()
    if m.leaving or m.p = invalid then return
    begin(m.retryAt)
end sub

sub onPosition()
    now = m.video.position
    if now > 0 then m.lastMs = Int(now * 1000)
    if Abs(now - m.lastReport) >= 10 then
        m.lastReport = now
        report("playing")
    end if
end sub

' Skip Intro over an intro; Up Next when the credits start; the sleep timer's word.
sub onTick()
    if m.sayUntil <> invalid and m.sayUntil > 0 and CreateObject("roDateTime").AsSeconds() >= m.sayUntil then say("", 0)
    if m.p = invalid then return
    ms = m.video.position * 1000
    prefs = m.global.prefs
    nextUp = Plex_NextInQueue(m.queue, m.item)
    cues = Plex_PlayerCues(m.p.markers, ms, prefs, { introDone: m.introDone, creditsOffered: m.creditsOffered, hasNext: nextUp <> invalid, sleepAtEnd: m.sleepEnd })
    if cues.skipTo >= 0 then
        m.introDone = true
        m.video.seek = cues.skipTo / 1000
    end if
    showSkip = cues.showSkip and m.chooser = invalid and not m.post.visible and m.ctlAt < 0
    if showSkip and not m.skip.visible then
        Trace_("skip intro shown")
        m.skip.visible = true
        m.skip.setFocus(true)
    else if not showSkip and m.skip.visible then
        m.skip.visible = false
        if m.skip.isInFocusChain() then m.video.setFocus(true)
    end if
    if cues.playNext or cues.upNext then
        m.creditsOffered = true
        if cues.playNext then
            playNext(nextUp)
            return
        end if
        showPostPlay(nextUp)
    end if
    if m.post.visible then
        if m.upNextAt > 0 and not m.upNextHeld then
            remaining = m.upNextAt - CreateObject("roDateTime").AsSeconds()
            if remaining <= 0 then
                playNext(m.upNextItem)
                return
            end if
        end if
        paintPostPlay()
    end if
    if m.ctlAt >= 0 then
        paintBar()
        ' Put away after a while untouched, unless paused.
        if m.video.state <> "paused" and CreateObject("roDateTime").AsSeconds() >= m.ctlHideAt then hideControls()
    end if
    note = ""
    if m.sleepEnd then note = "Sleep at the end of this"
    if m.sleepUntil > 0 then note = "Sleep in " + Int((m.sleepUntil - CreateObject("roDateTime").AsSeconds()) / 60 + 1).ToStr() + " min"
    m.top.findNode("sleepNote").text = Iif_(m.video.state = "paused", note, "")
end sub

sub ended()
    following = invalid
    if not m.sleepEnd then following = Plex_NextInQueue(m.queue, m.item)
    if following <> invalid then
        playNext(following)
    else
        finish(true)
    end if
end sub

' On to [item], the stop told to Plex first.
sub playNext(item as object)
    report("stopped")
    m.forceConvert = false
    m.top.request = { item: item, resume: false, queue: m.queue, mediaIndex: 0 }
end sub

sub finish(finished as boolean)
    if m.leaving then return
    m.leaving = true
    report("stopped")
    m.ticker.control = "stop"
    m.sleepTimer.control = "stop"
    m.retryTimer.control = "stop"
    m.video.control = "stop"
    m.forceConvert = false
    m.top.done = { item: m.item, finished: finished }
end sub

' Where playback is, told to the server. A trailer isn't something to pick up again.
sub report(state as string)
    if m.item = invalid or m.trailer then return
    ms = Int(m.video.position * 1000)
    if m.base = "iptv:" then
        ' Kept on the Roku: the provider keeps nothing.
        duration = Int(m.durationMs)
        if duration <= 0 and m.video.duration <> invalid then duration = Int(m.video.duration * 1000)
        Ask_("iptvProgress", { item: m.item, ms: ms, durationMs: duration, state: state })
        return
    end if
    Ask_("timeline", { base: m.base, token: m.token, ratingKey: m.item.ratingKey, state: state, ms: ms, durationMs: Int(m.durationMs), session: Str_(m.session) })
end sub

'' ------------------------------------------------------------------ The controls

' The row under the bar: the episodes either side of play, then a button for each panel.
function Controls_() as object
    out = []
    episodes = m.item.type = "episode" and m.queue.Count() > 1
    if episodes then out.Push({ id: "previous", glyph: "previous", enabled: Previous_() <> invalid })
    out.Push({ id: "play", glyph: Iif_(m.video.state = "paused", "play", "pause"), enabled: true, primary: true })
    if episodes then out.Push({ id: "next", glyph: "next", enabled: Plex_NextInQueue(m.queue, m.item) <> invalid })
    if m.p <> invalid and m.p.chapters.Count() > 1 then out.Push({ id: "chapters", glyph: "chapters", enabled: true })
    out.Push({ id: "subtitles", glyph: "subtitles", enabled: true })
    out.Push({ id: "audio", glyph: "audio", enabled: true })
    out.Push({ id: "sleep", glyph: "sleep", enabled: true, lit: m.sleepEnd or m.sleepUntil > 0 })
    out.Push({ id: "info", glyph: "info", enabled: true })
    return out
end function

function Previous_() as dynamic
    for i = 1 to m.queue.Count() - 1
        if m.queue[i].ratingKey = m.item.ratingKey then return m.queue[i - 1]
    end for
    return invalid
end function

sub showControls()
    if m.p = invalid then return
    m.buttons = Controls_()
    if m.ctlAt < 0 or m.ctlAt >= m.buttons.Count() then
        for i = 0 to m.buttons.Count() - 1
            if m.buttons[i].id = "play" then m.ctlAt = i
        end for
    end if
    m.controls.visible = true
    m.skip.visible = false
    m.controls.setFocus(true)
    m.ctlHideAt = CreateObject("roDateTime").AsSeconds() + 5
    title = m.item.title
    if m.item.type = "episode" and Str_(m.item.grandparentTitle) <> "" then title = m.item.grandparentTitle
    m.top.findNode("ctlTitle").text = title
    m.top.findNode("ctlSub").text = Iif_(m.item.type = "episode", Join_([Plex_Caption(m.item), m.item.title], " · "), "")
    buildControls()
    paintBar()
    Trace_("controls shown")
end sub

sub hideControls()
    m.ctlAt = -1
    m.controls.visible = false
    if m.controls.isInFocusChain() then m.video.setFocus(true)
end sub

sub buildControls()
    m.ctlRow.removeChildrenIndex(m.ctlRow.getChildCount(), 0)
    m.ctlNodes = []
    ' The transport in the middle of the screen; the panels' buttons to the right.
    transport = []
    panels = []
    for i = 0 to m.buttons.Count() - 1
        b = m.buttons[i]
        if b.id = "previous" or b.id = "play" or b.id = "next" then transport.Push(i) else panels.Push(i)
    end for
    width = 0
    for each i in transport
        width = width + Iif_(m.buttons[i].id = "play", 84, 64) + 28
    end for
    x = 960 - Int((width - 28) / 2)
    for each i in transport
        size = Iif_(m.buttons[i].id = "play", 84, 64)
        m.ctlNodes[i] = ControlNode_(m.buttons[i], x, 952 - Int(size / 2), size)
        x = x + size + 28
    end for
    x = 1824 - panels.Count() * 64 - (panels.Count() - 1) * 20
    for each i in panels
        m.ctlNodes[i] = ControlNode_(m.buttons[i], x, 920, 64)
        x = x + 84
    end for
    paintControls()
end sub

function ControlNode_(b as object, x as integer, y as integer, size as integer) as object
    g = CreateObject("roSGNode", "Group")
    g.translation = [x, y]
    ring = CreateObject("roSGNode", "Poster")
    ring.uri = "pkg:/images/circlering.png"
    ring.width = size + 12
    ring.height = size + 12
    ring.translation = [-6, -6]
    ring.visible = false
    disc = CreateObject("roSGNode", "Poster")
    disc.uri = "pkg:/images/circle.png"
    disc.width = size
    disc.height = size
    glyph = CreateObject("roSGNode", "Poster")
    glyph.uri = "pkg:/images/glyph_" + b.glyph + ".png"
    g.appendChild(ring)
    g.appendChild(disc)
    g.appendChild(glyph)
    gs = Int(size * 0.44)
    glyph.width = gs
    glyph.height = gs
    glyph.translation = [Int((size - gs) / 2), Int((size - gs) / 2)]
    m.ctlRow.appendChild(g)
    return { group: g, ring: ring, disc: disc, glyph: glyph }
end function

sub paintControls()
    accent = m.global.accent
    if accent = invalid then accent = "0x2E6BFFFF"
    onAccent = m.global.accentOn
    if onAccent = invalid then onAccent = "0xFFFFFFFF"
    for i = 0 to m.buttons.Count() - 1
        b = m.buttons[i]
        n = m.ctlNodes[i]
        on = i = m.ctlAt
        n.ring.visible = on
        n.group.opacity = Iif_(b.enabled, 1.0, 0.35)
        if b.primary = true then
            n.disc.blendColor = Iif_(on, accent, "0xF2F4F7FF")
            n.glyph.blendColor = Iif_(on, onAccent, "0x08090BFF")
        else if on then
            n.disc.blendColor = "0xF2F4F7FF"
            n.glyph.blendColor = "0x08090BFF"
        else if b.lit = true then
            n.disc.blendColor = accent
            n.glyph.blendColor = onAccent
        else
            n.disc.blendColor = "0x2A2E35FF"
            n.glyph.blendColor = "0xF2F4F7FF"
        end if
    end for
end sub

' Where it's got to, along the bar, and what's left.
sub paintBar()
    total = m.durationMs
    if (total = invalid or total <= 0) and m.video.duration <> invalid then total = m.video.duration * 1000
    at = m.video.position * 1000
    fraction = 0
    if total <> invalid and total > 0 then fraction = at / total
    if fraction > 1 then fraction = 1
    fill = m.top.findNode("barFill")
    fill.width = Int(1728 * fraction)
    accent = m.global.accent
    if accent = invalid then accent = "0x2E6BFFFF"
    fill.color = accent
    m.top.findNode("barAt").text = Iif_(m.video.state = "paused", "Paused  ·  ", "") + Clock_(at)
    left = 0
    if total <> invalid and total > at then left = total - at
    m.top.findNode("barLeft").text = "−" + Clock_(left)
end sub

function Clock_(ms as dynamic) as string
    total = Int(ms / 1000)
    if total < 0 then total = 0
    h = Int(total / 3600)
    mm = Int((total MOD 3600) / 60)
    ss = total MOD 60
    if h > 0 then return h.ToStr() + ":" + Two_(mm) + ":" + Two_(ss)
    return mm.ToStr() + ":" + Two_(ss)
end function

function Two_(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

' Along the row, past any that can't be pressed.
sub moveControl(stepBy as integer)
    i = m.ctlAt + stepBy
    while i >= 0 and i < m.buttons.Count()
        if m.buttons[i].enabled then
            m.ctlAt = i
            Trace_("control " + m.buttons[i].id)
            paintControls()
            return
        end if
        i = i + stepBy
    end while
end sub

sub pressControl()
    b = m.buttons[m.ctlAt]
    if not b.enabled then return
    if b.id = "play" then
        if m.video.state = "paused" then m.video.control = "resume" else m.video.control = "pause"
        m.buttons[m.ctlAt].glyph = Iif_(m.video.state = "paused", "pause", "play")
        m.ctlNodes[m.ctlAt].glyph.uri = "pkg:/images/glyph_" + m.buttons[m.ctlAt].glyph + ".png"
    else if b.id = "previous" then
        playNext(Previous_())
    else if b.id = "next" then
        playNext(Plex_NextInQueue(m.queue, m.item))
    else
        openPanel(b.id)
    end if
end sub

' ------------------------------------------------------------------ The panels

' A panel from the right, as the other apps' players have them; the one in use ticked.
sub openPanel(kind as string)
    labels = []
    notes = []
    current = -1
    m.panelIds = []
    title = ""
    if kind = "audio" then
        title = "Audio"
        tracks = m.video.availableAudioTracks
        ' The Roku's own tracks while the file plays as it is; Plex's otherwise.
        if m.direct and tracks <> invalid and tracks.Count() > 1 then
            for each t in tracks
                name = Str_(t.Name)
                if name = "" then name = Str_(t.Language)
                if m.video.currentAudioTrack = t.Track then current = labels.Count()
                labels.Push(name)
                notes.Push("")
                m.panelIds.Push("rokuAudio:" + Str_(t.Track))
            end for
        else
            for each a in m.p.audio
                if a.selected then current = labels.Count()
                labels.Push(a.label)
                notes.Push("")
                m.panelIds.Push("audio:" + a.id)
            end for
        end if
        if labels.Count() <= 1 then
            notes = ["There's only one audio track."]
            if labels.Count() = 0 then
                labels = ["Default"]
                m.panelIds = [""]
            end if
            current = 0
        end if
    else if kind = "subtitles" then
        title = "Subtitles"
        if m.p.subtitles.Count() > 0 then
            anyOn = Plex_SubtitlePlan(m.p).on <> invalid
            if not anyOn then current = 0
            labels.Push("Off")
            notes.Push("")
            m.panelIds.Push("subtitle:0")
            for each s in m.p.subtitles
                if s.selected then current = labels.Count()
                labels.Push(s.label)
                notes.Push("")
                m.panelIds.Push("subtitle:" + s.id)
            end for
        end if
        ' More found online by the Plex server, for what's on it (not the provider's, nor a trailer).
        if not m.trailer and not Bool_(m.p.iptv) then
            labels.Push("Find subtitles online")
            notes.Push(Iif_(m.p.subtitles.Count() = 0, "None came with this video.", ""))
            m.panelIds.Push("find:")
        end if
        labels.Push("Size and style")
        notes.Push("Set on your Roku: Settings > Accessibility > Captions style.")
        m.panelIds.Push("")
    else if kind = "chapters" then
        title = "Chapters"
        at = m.video.position * 1000
        for each ch in m.p.chapters
            if ch.startMs <= at then current = labels.Count()
            labels.Push(ch.title)
            notes.Push(Clock_(ch.startMs))
            m.panelIds.Push("chapter:" + Str_(ch.startMs))
        end for
    else if kind = "sleep" then
        title = "Sleep timer"
        for each minutes in SleepChoices_()
            if minutes <> -1 or m.item.type = "episode" then
                on = (minutes = 0 and not m.sleepEnd and m.sleepUntil = 0) or (minutes = -1 and m.sleepEnd)
                if on then current = labels.Count()
                labels.Push(Iif_(minutes = 0, "Off", Iif_(minutes = -1, "End of this episode", minutes.ToStr() + " minutes")))
                notes.Push("")
                m.panelIds.Push("sleep:" + minutes.ToStr())
            end if
        end for
    else if kind = "info" then
        title = "Playback info"
        facts = [["Playing", Iif_(m.direct, "The original file", "Converted by Plex")]]
        if Str_(m.p.videoCodec) <> "" then facts.Push(["Video", UCase(Str_(m.p.videoCodec))])
        if Str_(m.p.audioCodec) <> "" then facts.Push(["Audio", UCase(Str_(m.p.audioCodec))])
        if Str_(m.p.container) <> "" then facts.Push(["File", UCase(Str_(m.p.container))])
        for each f in facts
            labels.Push(f[1])
            notes.Push(f[0])
            m.panelIds.Push("")
        end for
    end if
    m.panelKind = kind
    showChooser(title, labels, notes, current)
    Trace_("panel " + kind)
end sub

sub showChooser(title as string, labels as object, notes as object, current as integer)
    closePanel()
    m.controls.visible = false
    m.skip.visible = false
    m.chooser = CreateObject("roSGNode", "Chooser")
    m.chooser.setFields({ title: title, notes: notes, current: current })
    m.chooser.options = labels
    m.chooser.observeField("picked", "onPicked")
    m.top.appendChild(m.chooser)
    m.chooser.setFocus(true)
end sub

sub closePanel()
    if m.chooser = invalid then return
    m.chooser.unobserveField("picked")
    m.top.removeChild(m.chooser)
    m.chooser = invalid
end sub

sub onPicked()
    picked = m.chooser.picked
    id = ""
    if picked >= 0 and picked < m.panelIds.Count() then id = m.panelIds[picked]
    if picked >= 0 and id = "" and m.panelKind <> "finding" then return
    closePanel()
    if picked < 0 or id = "" then
        ' Back: to the controls, on the button that opened it.
        if m.ctlAt >= 0 then
            m.controls.visible = true
            m.controls.setFocus(true)
            m.ctlHideAt = CreateObject("roDateTime").AsSeconds() + 5
        else
            m.video.setFocus(true)
        end if
        return
    end if
    hideControls()
    parts = id.Split(":")
    kind = parts[0]
    value = parts[1]
    if kind = "find" then
        findSubtitles()
    else if kind = "found" then
        addFound(Int(Val(value)))
    else if kind = "rokuAudio" then
        m.video.audioTrack = value
    else if kind = "audio" or kind = "subtitle" then
        chooseStream(kind, value)
    else if kind = "chapter" then
        m.video.seek = Num_(value) / 1000
    else if kind = "sleep" then
        minutes = Int(Val(value))
        Trace_("sleep " + minutes.ToStr())
        m.sleepTimer.control = "stop"
        m.sleepEnd = minutes = -1
        m.sleepUntil = 0
        if minutes > 0 then
            m.sleepUntil = CreateObject("roDateTime").AsSeconds() + minutes * 60
            m.sleepTimer.duration = minutes * 60
            m.sleepTimer.control = "start"
        end if
    end if
end sub

' ------------------------------------------------------------------ Up Next

' The next episode over the screen; what's finishing goes on in a window in the corner.
sub showPostPlay(nextUp as object)
    prefs = m.global.prefs
    seconds = 12
    if prefs <> invalid and prefs.upNextSeconds <> invalid then seconds = Int(prefs.upNextSeconds)
    m.upNextItem = nextUp
    m.upNextSeconds = seconds
    m.upNextHeld = false
    m.upNextAt = 0
    if seconds > 0 then m.upNextAt = CreateObject("roDateTime").AsSeconds() + seconds
    hideControls()
    closePanel()
    m.skip.visible = false
    path = Str_(nextUp.thumb)
    if path = "" then path = Str_(nextUp.art)
    m.top.findNode("postArt").uri = Image_(nextUp.serverBase, path, 1280, 720)
    accent = m.global.accent
    if accent = invalid then accent = "0x2E6BFFFF"
    m.top.findNode("postLabel").color = accent
    show = Str_(nextUp.grandparentTitle)
    m.top.findNode("postShow").text = Iif_(show <> "", show, nextUp.title)
    facts = []
    if nextUp.parentIndex <> invalid then facts.Push("Season " + nextUp.parentIndex.ToStr())
    if nextUp.index <> invalid then facts.Push("Episode " + nextUp.index.ToStr())
    if Num_(nextUp.durationMs) > 0 then facts.Push(Int(Num_(nextUp.durationMs) / 60000 + 0.5).ToStr() + " min")
    m.top.findNode("postLine").text = Join_(facts, "  ·  ")
    m.top.findNode("postTitle").text = Iif_(show <> "", nextUp.title, "")
    m.top.findNode("postSummary").text = Str_(nextUp.summary)
    m.top.findNode("postNow").text = "Credits  ·  " + Iif_(Str_(m.item.grandparentTitle) <> "", m.item.grandparentTitle, m.item.title)
    m.video.translation = [1150, 52]
    m.video.width = 672
    m.video.height = 378
    m.postAt = 0
    m.post.visible = true
    m.post.setFocus(true)
    paintPostPlay()
    Trace_("up next " + nextUp.ratingKey)
end sub

sub hidePostPlay()
    m.post.visible = false
    m.video.translation = [0, 0]
    m.video.width = 1920
    m.video.height = 1080
    if m.post.isInFocusChain() then m.video.setFocus(true)
end sub

sub paintPostPlay()
    accent = m.global.accent
    if accent = invalid then accent = "0x2E6BFFFF"
    onPlay = m.postAt = 0
    counting = m.upNextAt > 0 and not m.upNextHeld
    left = 0
    if counting then left = m.upNextAt - CreateObject("roDateTime").AsSeconds()
    if left < 0 then left = 0
    m.top.findNode("playNextFill").blendColor = Iif_(onPlay, "0xF2F4F7FF", "0x1F2329FF")
    count = m.top.findNode("playNextCount")
    count.blendColor = accent
    count.opacity = 0.45
    count.width = 0
    if counting and m.upNextSeconds > 0 then count.width = Int(360 * (1 - left / m.upNextSeconds))
    label = m.top.findNode("playNextLabel")
    label.text = Iif_(counting, "Play next  ·  " + left.ToStr(), "Play next")
    label.color = Iif_(onPlay, "0x08090BFF", "0xF2F4F7FF")
    m.top.findNode("creditsFill").blendColor = Iif_(onPlay, "0x1F2329FF", "0xF2F4F7FF")
    m.top.findNode("creditsLabel").color = Iif_(onPlay, "0xF2F4F7FF", "0x08090BFF")
end sub

' Another sound track or subtitles: kept with Plex, as the other apps keep them, and
' playing on from here with them.
sub chooseStream(kind as string, id as string)
    for each a in m.p.audio
        if kind = "audio" then a.selected = a.id = id
    end for
    for each s in m.p.subtitles
        if kind = "subtitle" then s.selected = s.id = id
    end for
    Ask_("streams", { base: m.base, token: m.token, partId: m.p.partId, audioId: Iif_(kind = "audio", id, ""), subtitleId: Iif_(kind = "subtitle", id, "") })
    ' A sound track other than the file's first is Plex's to mix in.
    first = m.p.audio.Count() > 0 and m.p.audio[0].selected
    m.forceConvert = kind = "audio" and not first
    begin(Int(m.video.position * 1000))
end sub

' ------------------------------------------------------------------ Subtitles found online

' In the TV's language, as the other apps look.
function Language_() as string
    locale = CreateObject("roDeviceInfo").GetCurrentLocale()
    if Len(locale) >= 2 then return LCase(Left(locale, 2))
    return "en"
end function

sub findSubtitles()
    m.findLanguage = Language_()
    m.found = []
    m.panelIds = [""]
    m.panelKind = "finding"
    showChooser("Find subtitles online", ["Looking for subtitles…"], [""], -1)
    Ask_("findSubtitles", { base: m.base, token: m.token, ratingKey: m.item.ratingKey, language: m.findLanguage })
end sub

sub showFound(results as dynamic)
    if m.chooser = invalid or m.panelKind <> "finding" then return
    m.found = Arr_(results)
    labels = []
    notes = []
    m.panelIds = []
    if results = invalid then
        labels = ["Couldn't look for subtitles"]
        notes = [""]
        m.panelIds = [""]
    else if m.found.Count() = 0 then
        labels = ["None found online"]
        notes = [""]
        m.panelIds = [""]
    else
        for i = 0 to m.found.Count() - 1
            labels.Push(Plex_OnlineSubtitleLabel(m.found[i]))
            notes.Push("")
            m.panelIds.Push("found:" + i.ToStr())
        end for
    end if
    Trace_("subtitles found " + m.found.Count().ToStr())
    m.chooser.options = labels
end sub

' The server fetches it and adds it to the file; then it's played on with.
sub addFound(i as integer)
    if m.found = invalid or i < 0 or i >= m.found.Count() then return
    say("Adding subtitles…", 0)
    Ask_("addSubtitle", { base: m.base, token: m.token, ratingKey: m.item.ratingKey, subtitle: m.found[i], language: m.findLanguage, mediaIndex: m.mediaIndex })
end sub

sub onAdded(fresh as dynamic)
    newId = ""
    if fresh <> invalid and m.p <> invalid then newId = Plex_NewSubtitleId(m.p.subtitles, fresh.subtitles)
    if newId = "" then
        say("Plex couldn't add those subtitles. Try another.", 5)
        return
    end if
    say("", 0)
    m.p.subtitles = fresh.subtitles
    Trace_("subtitle added " + newId)
    chooseStream("subtitle", newId)
end sub

' A word over the picture, gone after [seconds] (0: until it's replaced).
sub say(text as string, seconds as integer)
    m.wait.text = text
    m.sayUntil = 0
    if seconds > 0 then m.sayUntil = CreateObject("roDateTime").AsSeconds() + seconds
end sub

sub stopNow()
    finish(false)
end sub

sub onSleep()
    finish(false)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.chooser <> invalid then return true
    ' Given up after the connection dropped: OK, or Play, tries again from there.
    if m.stuckAt >= 0 and (key = "OK" or key = "play") and m.p <> invalid then
        at = m.stuckAt
        m.stuckAt = -1
        m.drops = 0
        m.wait.text = "Reconnecting…"
        begin(at)
        return true
    end if
    if m.post.visible then
        ' Any press but OK on Play next stops the countdown: somebody reaching for the
        ' remote is making up their mind.
        if not (key = "OK" and m.postAt = 0) then m.upNextHeld = true
        if key = "left" or key = "right" then
            m.postAt = Iif_(key = "left", 0, 1)
        else if key = "OK" then
            if m.postAt = 0 then
                playNext(m.upNextItem)
            else
                hidePostPlay()
            end if
        else if key = "back" then
            hidePostPlay()
        end if
        if m.post.visible then paintPostPlay()
        return true
    end if
    if m.ctlAt >= 0 then
        m.ctlHideAt = CreateObject("roDateTime").AsSeconds() + 5
        if key = "left" or key = "right" then
            moveControl(Iif_(key = "left", -1, 1))
        else if key = "OK" then
            pressControl()
        else if key = "back" or key = "up" then
            hideControls()
        end if
        return true
    end if
    if m.skip.visible and m.skip.isInFocusChain() and key = "OK" then
        intro = Plex_MarkerAt(m.p.markers, "intro", m.video.position * 1000)
        m.introDone = true
        if intro <> invalid then m.video.seek = intro.endMs / 1000
        Trace_("skipped intro")
        m.skip.visible = false
        m.video.setFocus(true)
        return true
    end if
    if key = "back" then
        finish(false)
        return true
    else if key = "down" or key = "options" then
        showControls()
        return true
    end if
    return false
end function
