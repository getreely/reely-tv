' Minutes the sleep timer offers, as the Fire TV's; -1 is the end of this episode.
function SleepChoices_() as object
    return [0, 15, 30, 45, 60, 90, -1]
end function

sub init()
    m.video = m.top.findNode("video")
    m.wait = m.top.findNode("wait")
    m.skip = m.top.findNode("skip")
    m.upNext = m.top.findNode("upNext")
    m.options = m.top.findNode("options")
    m.optionList = m.top.findNode("optionList")
    m.ticker = m.top.findNode("ticker")
    m.sleepTimer = m.top.findNode("sleepTimer")
    m.wait.font = Regular_(30)
    m.top.findNode("skipLabel").font = Semibold_(30)
    m.top.findNode("upNextLabel").font = Regular_(26)
    m.top.findNode("upNextTitle").font = Bold_(36)
    m.top.findNode("upNextHint").font = Regular_(24)
    m.top.findNode("optionsTitle").font = Regular_(26)
    m.top.findNode("sleepNote").font = Regular_(26)
    m.optionList.font = Regular_(28)
    m.optionList.focusedFont = Semibold_(28)
    m.video.observeField("state", "onState")
    m.video.observeField("position", "onPosition")
    m.optionList.observeField("itemSelected", "onOption")
    m.optionList.observeField("itemFocused", "onOptionFocused")
    m.ticker.observeField("fire", "onTick")
    m.sleepTimer.observeField("fire", "onSleep")
    m.sleepEnd = false
    m.sleepUntil = 0
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
    m.introDone = false
    m.creditsOffered = false
    m.upNextAt = 0
    m.lastReport = 0
    m.skip.visible = false
    m.upNext.visible = false
    m.options.visible = false
    m.wait.text = "Loading…"
    m.video.control = "stop"
    m.video.setFocus(true)
    m.p = invalid
    if m.base = "iptv:" then
        Ask_("iptvPlayback", { ratingKey: m.item.ratingKey, durationMs: m.item.durationMs })
    else
        Ask_("playback", { base: m.base, token: m.token, ratingKey: m.item.ratingKey, mediaIndex: m.mediaIndex })
    end if
end sub

sub answered(r as object)
    if r.op = "playback" or r.op = "iptvPlayback" then
        p = r.answer
        if p = invalid then
            m.wait.text = "That file isn't on the server any more."
            if r.op = "iptvPlayback" then m.wait.text = Iif_(Str_(r.error) <> "", Str_(r.error), "Your provider couldn't send this. Try again in a moment.")
            return
        end if
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
    if p.iptv = true then
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
        content.url = Plex_TranscodeUrl(m.base, m.token, m.item.ratingKey, m.session, m.global.clientId, m.mediaIndex, kbps, Iif_(subs.text, "none", "burn"))
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
    else if s = "paused" then
        report("paused")
    else if s = "finished" then
        ended()
    else if s = "error" then
        ' The file wouldn't play as it is: Plex converts it, from where it got to.
        if m.p <> invalid and m.p.iptv = true then
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

sub onPosition()
    now = m.video.position
    if Abs(now - m.lastReport) >= 10 then
        m.lastReport = now
        report("playing")
    end if
end sub

' Skip Intro over an intro; Up Next when the credits start; the sleep timer's word.
sub onTick()
    if m.p = invalid then return
    ms = m.video.position * 1000
    prefs = m.global.prefs
    nextUp = Plex_NextInQueue(m.queue, m.item)
    cues = Plex_PlayerCues(m.p.markers, ms, prefs, { introDone: m.introDone, creditsOffered: m.creditsOffered, hasNext: nextUp <> invalid, sleepAtEnd: m.sleepEnd })
    if cues.skipTo >= 0 then
        m.introDone = true
        m.video.seek = cues.skipTo / 1000
    end if
    showSkip = cues.showSkip and not m.options.visible and not m.upNext.visible
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
        seconds = 12
        if prefs <> invalid and prefs.upNextSeconds <> invalid then seconds = Int(prefs.upNextSeconds)
        m.upNextItem = nextUp
        m.upNextAt = 0
        if seconds > 0 then m.upNextAt = CreateObject("roDateTime").AsSeconds() + seconds
        m.top.findNode("upNextTitle").text = Join_([Plex_Caption(nextUp), nextUp.title], " · ")
        m.upNext.visible = true
        m.upNext.setFocus(true)
        Trace_("up next " + nextUp.ratingKey)
    end if
    if m.upNext.visible then
        hint = "OK to play  ·  Back to keep watching"
        if m.upNextAt > 0 then
            remaining = m.upNextAt - CreateObject("roDateTime").AsSeconds()
            if remaining <= 0 then
                playNext(m.upNextItem)
                return
            end if
            hint = "Playing in " + remaining.ToStr() + "  ·  OK to play now  ·  Back to keep watching"
        end if
        m.top.findNode("upNextHint").text = hint
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

' ------------------------------------------------------------------ The options panel

sub showOptions()
    if m.p = invalid then return
    content = CreateObject("roSGNode", "ContentNode")
    m.optionIds = []
    ' Sound: the Roku's own tracks while the file plays as it is; Plex's otherwise.
    tracks = m.video.availableAudioTracks
    if m.direct and tracks <> invalid and tracks.Count() > 1 then
        Heading_(content, "Sound")
        for each t in tracks
            name = Str_(t.Name)
            if name = "" then name = Str_(t.Language)
            Option_(content, m.optionIds, Iif_(m.video.currentAudioTrack = t.Track, "✓  ", "    ") + name, "rokuAudio:" + Str_(t.Track))
        end for
    else if m.p.audio.Count() > 1 then
        Heading_(content, "Sound")
        for each a in m.p.audio
            Option_(content, m.optionIds, Iif_(a.selected, "✓  ", "    ") + a.label, "audio:" + a.id)
        end for
    end if
    if m.p.subtitles.Count() > 0 then
        Heading_(content, "Subtitles")
        anyOn = Plex_SubtitlePlan(m.p).on <> invalid
        Option_(content, m.optionIds, Iif_(not anyOn, "✓  ", "    ") + "Off", "subtitle:0")
        for each s in m.p.subtitles
            Option_(content, m.optionIds, Iif_(s.selected, "✓  ", "    ") + s.label, "subtitle:" + s.id)
        end for
    end if
    if m.p.chapters.Count() > 1 then
        Heading_(content, "Chapters")
        for each ch in m.p.chapters
            Option_(content, m.optionIds, "    " + ch.title, "chapter:" + Str_(ch.startMs))
        end for
    end if
    Heading_(content, "Sleep timer")
    for each minutes in SleepChoices_()
        if minutes <> -1 or m.item.type = "episode" then
            label = Iif_(minutes = 0, "Off", Iif_(minutes = -1, "End of this episode", minutes.ToStr() + " minutes"))
            on = (minutes = 0 and not m.sleepEnd and m.sleepUntil = 0) or (minutes = -1 and m.sleepEnd)
            Option_(content, m.optionIds, Iif_(on, "✓  ", "    ") + label, "sleep:" + minutes.ToStr())
        end if
    end for
    m.optionList.content = content
    m.options.visible = true
    Trace_("options shown")
    m.skip.visible = false
    m.optionList.setFocus(true)
    ' The first thing that can be chosen, not a heading.
    for i = 0 to m.optionIds.Count() - 1
        if m.optionIds[i] <> "" then
            m.optionList.jumpToItem = i
            exit for
        end if
    end for
end sub

sub Heading_(content as object, title as string)
    c = content.createChild("ContentNode")
    c.title = UCase(title)
    m.optionIds.Push("")
end sub

sub Option_(content as object, ids as object, title as string, id as string)
    c = content.createChild("ContentNode")
    c.title = title
    ids.Push(id)
end sub

sub hideOptions()
    m.options.visible = false
    m.video.setFocus(true)
end sub

sub onOptionFocused()
    i = m.optionList.itemFocused
    if m.optionIds <> invalid and i >= 0 and i < m.optionIds.Count() then Trace_("option " + m.optionIds[i])
end sub

sub onOption()
    id = m.optionIds[m.optionList.itemSelected]
    if id = "" then return
    parts = id.Split(":")
    kind = parts[0]
    value = parts[1]
    hideOptions()
    if kind = "rokuAudio" then
        m.video.audioTrack = value
    else if kind = "audio" or kind = "subtitle" then
        chooseStream(kind, value)
    else if kind = "chapter" then
        m.video.seek = Num_(value) / 1000
    else if kind = "sleep" then
        minutes = Int(Val(value))
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

sub stopNow()
    finish(false)
end sub

sub onSleep()
    finish(false)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.options.visible then
        if key = "back" or key = "left" then hideOptions()
        return true
    end if
    if m.upNext.visible then
        if key = "OK" then
            playNext(m.upNextItem)
            return true
        else if key = "back" then
            m.upNext.visible = false
            m.video.setFocus(true)
            return true
        end if
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
        showOptions()
        return true
    end if
    return false
end function
