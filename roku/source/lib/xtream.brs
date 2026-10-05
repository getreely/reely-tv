' Live TV from the provider, as the other apps read it: an Xtream login or an M3U playlist;
' categories, channels, the guide, catch-up. Reading only; the asking is xtreamapi.brs's.

' "panel.example.com:8080", "http://…/" and the rest, as an address.
function Xtream_Base(raw as string) as string
    t = raw.Trim()
    while Len(t) > 0 and Right(t, 1) = "/"
        t = Left(t, Len(t) - 1)
    end while
    if t = "" then return t
    low = LCase(t)
    if Left(low, 7) = "http://" or Left(low, 8) = "https://" then return t
    return "http://" + t
end function

function Xtream_IsPlaylist(c as object) as boolean
    return Str_(c.playlistUrl) <> ""
end function

function Xtream_ApiUrl(c as object, action as string, extras = invalid as dynamic) as string
    url = c.base + "/player_api.php?username=" + UrlEncode_(c.username) + "&password=" + UrlEncode_(c.password)
    if action <> "" then url = url + "&action=" + action
    if extras <> invalid then
        for each k in extras
            url = url + "&" + k + "=" + UrlEncode_(Str_(extras[k]))
        end for
    end if
    return url
end function

' The panel's answer to signing in: { ok, error, account }. The account's clock is kept as
' how far the panel's time is from UTC, for catch-up addresses in its own time.
function Xtream_ParseLogin(root as dynamic) as object
    bad = "Couldn't connect. Check the server address and port."
    if root = invalid or type(root) <> "roAssociativeArray" then return { ok: false, error: bad }
    user = root.user_info
    if user = invalid or type(user) <> "roAssociativeArray" then return { ok: false, error: bad }
    if Num_(user.auth) <> 1 then return { ok: false, error: "Incorrect username or password." }
    status = Str_(user.status)
    if status = "" then status = "Unknown"
    if LCase(status) <> "active" then return { ok: false, error: "This account is " + status + ". Contact your provider." }
    offset = 0
    server = root.server_info
    if server <> invalid and type(server) = "roAssociativeArray" then offset = Xtream_ClockOffset(Str_(server.time_now), Num_(server.timestamp_now))
    expires = Str_(user.exp_date)
    if expires = "null" then expires = ""
    return { ok: true, error: "", account: { status: status, maxConnections: Str_(user.max_connections), activeConnections: Str_(user.active_cons), expiresAt: expires, clockOffset: offset } }
end function

' How far the panel's clock ("2024-03-05 20:30:00") is ahead of UTC, to the quarter hour.
function Xtream_ClockOffset(timeNow as string, timestampNow as dynamic) as integer
    if timeNow = "" or timestampNow = invalid or timestampNow <= 0 then return 0
    panel = Xtream_ParseClock(timeNow)
    if panel = 0 then return 0
    diff = panel - timestampNow
    quarters = Int(diff / 900 + 0.5)
    if diff < 0 then quarters = -Int(-diff / 900 + 0.5)
    return quarters * 900
end function

' "2024-03-05 20:30:00" read as if it were UTC, in seconds; 0 when it isn't a time.
function Xtream_ParseClock(text as string) as integer
    re = CreateObject("roRegex", "^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$", "")
    m = re.Match(text.Trim())
    if m.Count() < 7 then return 0
    d = CreateObject("roDateTime")
    d.FromISO8601String(m[1] + "-" + m[2] + "-" + m[3] + "T" + m[4] + ":" + m[5] + ":" + m[6] + "Z")
    return d.AsSeconds()
end function

function Xtream_ParseCategories(list as dynamic) as object
    out = []
    seen = {}
    for each x in Arr_(list)
        if type(x) = "roAssociativeArray" then
            id = Str_(x.category_id)
            name = Str_(x.category_name)
            if name = "" then name = "Unnamed"
            if id <> "" and not seen.DoesExist(id) then
                seen[id] = true
                out.Push({ id: id, name: name })
            end if
        end if
    end for
    return out
end function

function Xtream_ParseChannels(list as dynamic) as object
    out = []
    seen = {}
    digits = CreateObject("roRegex", "^\d+$", "")
    for each x in Arr_(list)
        if type(x) = "roAssociativeArray" then
            raw = Str_(x.stream_id).Trim()
            if digits.IsMatch(raw) and not seen.DoesExist(raw) then
                seen[raw] = true
                icon = Str_(x.stream_icon)
                if LCase(Left(icon, 4)) <> "http" then icon = ""
                name = Str_(x.name)
                if name = "" then name = "Channel " + raw
                archive = 0
                if Str_(x.tv_archive) = "1" then archive = Int(Num_(x.tv_archive_duration))
                out.Push({ streamId: raw.ToInt(), number: Int(Num_(x.num)), name: name, icon: icon, epgChannelId: LCase(Str_(x.epg_channel_id).Trim()), url: "", group: Str_(x.category_id), archiveDays: archive })
            end if
        end if
    end for
    return out
end function

' A playlist, a line at a time; the films and series in it passed over, only channels kept.
function Xtream_ParseM3u(text as string) as object
    guideUrl = ""
    pending = invalid
    channels = []
    ids = {}
    vod = [".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm"]
    for each raw in text.Split(Chr(10))
        line = raw.Replace(Chr(13), "").Trim()
        if Left(line, 3) = Chr(239) + Chr(187) + Chr(191) then line = Mid(line, 4)
        upper = UCase(line)
        if line = "" then
            ' nothing
        else if Left(upper, 7) = "#EXTM3U" then
            a = Xtream_Attributes(line)
            for each key in ["url-tvg", "x-tvg-url"]
                if guideUrl = "" and a.DoesExist(key) then
                    for each part in a[key].Split(",")
                        if guideUrl = "" and LCase(Left(part.Trim(), 4)) = "http" then guideUrl = part.Trim()
                    end for
                end if
            end for
        else if Left(upper, 7) = "#EXTINF" then
            a = Xtream_Attributes(line)
            name = Xtream_AfterComma(line)
            if name = "" then name = Str_(a["tvg-name"]).Trim()
            logo = Str_(a["tvg-logo"]).Trim()
            if LCase(Left(logo, 4)) <> "http" then logo = ""
            number = Int(Val(Str_(a["tvg-chno"])))
            pending = { name: name, id: LCase(Str_(a["tvg-id"]).Trim()), logo: logo, number: number, group: Str_(a["group-title"]).Trim() }
        else if Left(upper, 8) = "#EXTGRP:" then
            if pending <> invalid and pending.group = "" then pending.group = Mid(line, Instr(1, line, ":") + 1).Trim()
        else if Left(line, 1) = "#" then
            ' another directive
        else
            entry = pending
            if entry = invalid then entry = { name: "", id: "", logo: "", number: 0, group: "" }
            pending = invalid
            path = LCase(line.Split("?")[0])
            isVod = Instr(1, path, "/movie/") > 0 or Instr(1, path, "/series/") > 0
            for each ext in vod
                if Right(path, Len(ext)) = ext then isVod = true
            end for
            if not isVod then
                id = Xtream_Hash(line)
                while ids.DoesExist(id.ToStr())
                    id = (id + 1) mod 2147483647
                end while
                ids[id.ToStr()] = true
                number = entry.number
                if number <= 0 then number = channels.Count() + 1
                name = entry.name
                if name = "" then name = "Channel " + (channels.Count() + 1).ToStr()
                channels.Push({ streamId: id, number: number, name: name, icon: entry.logo, epgChannelId: entry.id, url: line, group: entry.group, archiveDays: 0 })
            end if
        end if
    end for
    return { channels: channels, guideUrl: guideUrl }
end function

' The categories of a playlist: its groups, in the order they first come; "Other" for none.
function Xtream_PlaylistCategories(channels as object) as object
    out = []
    seen = {}
    for each ch in channels
        g = ch.group
        if g = "" then g = "Other"
        if not seen.DoesExist(g) then
            seen[g] = true
            out.Push({ id: g, name: g })
        end if
    end for
    return out
end function

function Xtream_InCategory(channels as object, id as string) as object
    out = []
    for each ch in channels
        g = ch.group
        if g = "" then g = "Other"
        if g = id then out.Push(ch)
    end for
    return out
end function

function Xtream_Attributes(line as string) as object
    out = {}
    re = CreateObject("roRegex", "([A-Za-z0-9_-]+)=" + Chr(34) + "([^" + Chr(34) + "]*)" + Chr(34), "")
    rest = line
    while true
        m = re.Match(rest)
        if m.Count() < 3 then exit while
        out[LCase(m[1])] = m[2]
        at = Instr(1, rest, m[0])
        rest = Mid(rest, at + Len(m[0]))
    end while
    return out
end function

' The channel's name: after the first comma outside quotes.
function Xtream_AfterComma(line as string) as string
    quoted = false
    for i = 1 to Len(line)
        ch = Mid(line, i, 1)
        if ch = Chr(34) then
            quoted = not quoted
        else if ch = "," and not quoted then
            return Mid(line, i + 1).Trim()
        end if
    end for
    return ""
end function

' The same number for the same address, as Java's String.hashCode makes it, kept positive.
function Xtream_Hash(text as string) as integer
    h& = 0
    for i = 1 to Len(text)
        h& = (h& * 31 + Asc(Mid(text, i, 1))) mod 4294967296&
    end for
    return h& mod 2147483648&
end function

' The panel's listing for a channel, in order, each start once: { start, ends, title, description }.
function Xtream_ParseListings(root as dynamic, channelId as string) as object
    out = []
    if root = invalid or type(root) <> "roAssociativeArray" then return out
    seen = {}
    for each e in Arr_(root.epg_listings)
        if type(e) = "roAssociativeArray" then
            begins = Int(Num_(e.start_timestamp))
            ends = Int(Num_(e.stop_timestamp))
            if begins <= 0 then begins = Xtream_ParseClock(Str_(e.start))
            if ends <= 0 then ends = Xtream_ParseClock(Str_(e.end))
            if begins > 0 and ends > begins and not seen.DoesExist(begins.ToStr()) then
                seen[begins.ToStr()] = true
                title = Xtream_DecodeField(Str_(e.title))
                if title = "" then title = "Untitled"
                out.Push({ channelId: channelId, start: begins, ends: ends, title: title, description: Xtream_DecodeField(Str_(e.description)) })
            end if
        end if
    end for
    return StableSort_(out, function(a as object, b as object) as boolean
        return a.start < b.start
    end function)
end function

' Titles come base64, mostly.
function Xtream_DecodeField(text as string) as string
    t = text.Trim()
    if t = "" then return ""
    b64 = CreateObject("roRegex", "^[A-Za-z0-9+/=\s]+$", "")
    compact = t.Replace(" ", "").Replace(Chr(10), "").Replace(Chr(13), "")
    if not b64.IsMatch(t) or Len(compact) mod 4 <> 0 then return t
    bytes = CreateObject("roByteArray")
    bytes.FromBase64String(compact)
    if bytes.Count() = 0 then return t
    return bytes.ToAsciiString().Trim()
end function

function Xtream_StreamUrl(c as object, ch as object, format as string) as string
    if ch.url <> invalid and ch.url <> "" then return ch.url
    return c.base + "/live/" + UrlEncode_(c.username) + "/" + UrlEncode_(c.password) + "/" + ch.streamId.ToStr() + "." + format
end function

' A programme from the channel's archive: the panel's timeshift address, in its own time.
function Xtream_CatchUpUrl(c as object, ch as object, begins as integer, ends as integer, clockOffset as integer) as string
    if Xtream_IsPlaylist(c) or ch.archiveDays <= 0 then return ""
    minutes = Int((ends - begins + 59) / 60)
    if minutes < 1 then minutes = 1
    return c.base + "/timeshift/" + UrlEncode_(c.username) + "/" + UrlEncode_(c.password) + "/" + minutes.ToStr() + "/" + Xtream_PanelTime(begins, clockOffset) + "/" + ch.streamId.ToStr() + ".ts"
end function

' "2024-03-05:20-30": a moment as the panel's clock reads it.
function Xtream_PanelTime(epoch as integer, clockOffset as integer) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(epoch + clockOffset)
    return d.GetYear().ToStr() + "-" + Two_(d.GetMonth()) + "-" + Two_(d.GetDayOfMonth()) + ":" + Two_(d.GetHours()) + "-" + Two_(d.GetMinutes())
end function

function Two_(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

function Xtream_IsOnAt(p as object, at as integer) as boolean
    return at >= p.start and at < p.ends
end function

' What's on at [at], or invalid.
function Xtream_ProgrammeAt(programmes as object, at as integer) as dynamic
    for each p in programmes
        if Xtream_IsOnAt(p, at) then return p
    end for
    return invalid
end function

' The first programme to start after [at], or invalid.
function Xtream_NextAfter(programmes as object, at as integer) as dynamic
    for each p in programmes
        if p.start >= at then return p
    end for
    return invalid
end function

' How far through it is at [at], 0 to 1; -1 when it isn't on.
function Xtream_Progress(p as object, at as integer) as float
    if p.ends <= p.start or at < p.start or at > p.ends then return -1
    return (at - p.start) / (p.ends - p.start)
end function

' Whether [p] can be watched again from the channel's archive: over, and not too long ago.
function Xtream_CanCatchUp(ch as object, p as object, now as integer) as boolean
    if ch.archiveDays <= 0 then return false
    return p.ends <= now and p.start >= now - ch.archiveDays * 86400
end function

' A channel's number and name, as every list shows it.
function Xtream_ChannelLabel(ch as object) as string
    if ch.number > 0 then return ch.number.ToStr() + "  " + ch.name
    return ch.name
end function

' Reminders at [now]: the one that's due (starting, not yet put up, within ten minutes of
' its start), and those still worth keeping (not over). [shown] holds keys already put up.
function Xtream_Reminders(reminders as object, now as integer, shown as object) as object
    kept = []
    due = invalid
    for each r in reminders
        key = Xtream_ReminderKey(r)
        if r.ends > now then kept.Push(r)
        if due = invalid and r.start <= now and now < r.start + 600 and not shown.DoesExist(key) then due = r
    end for
    return { due: due, kept: kept }
end function

function Xtream_ReminderKey(r as object) as string
    return r.channel.streamId.ToStr() + ":" + r.start.ToStr()
end function

' ------------------------------------------------------------------ A playlist's own guide

' What a guide request carries: the channels' guide ids, the guide's address, and every guide
' id the playlist has, so reading the whole guide keeps only those.
function Xtream_GuideArgs(c as object, listGuide as string, channels as object, ids as object, playlist as dynamic) as object
    out = { credentials: c, streamIds: ids }
    if not Xtream_IsPlaylist(c) then return out
    out.guideUrl = Str_(c.guideUrl)
    if out.guideUrl = "" then out.guideUrl = listGuide
    wanted = {}
    for each id in ids
        wanted[Str_(id)] = true
    end for
    epg = {}
    for each ch in channels
        key = Str_(ch.streamId)
        if wanted.DoesExist(key) and Str_(ch.epgChannelId) <> "" then epg[key] = LCase(Str_(ch.epgChannelId))
    end for
    out.epg = epg
    known = {}
    for each ch in Arr_(playlist)
        if Str_(ch.epgChannelId) <> "" then known[LCase(Str_(ch.epgChannelId))] = true
    end for
    out.known = known
    return out
end function

' Reads an XMLTV guide a piece at a time, keeping the programmes of [known] channels (all, when
' invalid) between [from] and [upTo]: by channel id, in no order yet.
function Xtream_XmltvReader(known as dynamic, from as integer, upTo as integer) as object
    return { buffer: "", known: known, from: from, upTo: upTo, byChannel: {}, count: 0, clock: CreateObject("roDateTime") }
end function

sub Xtream_XmltvPush(r as object, chunk as string)
    r.buffer = r.buffer + chunk
    closing = "</programme>"
    while true
        ends = Instr(1, r.buffer, closing)
        if ends = 0 then exit while
        block = Left(r.buffer, ends - 1)
        r.buffer = Mid(r.buffer, ends + Len(closing))
        starts = Xtream_LastInstr(block, "<programme")
        if starts > 0 then Xtream_XmltvKeep(r, Mid(block, starts))
    end while
    ' Nothing worth keeping before the programme that's begun.
    begun = Xtream_LastInstr(r.buffer, "<programme")
    if begun > 1 then
        r.buffer = Mid(r.buffer, begun)
    else if begun = 0 and Len(r.buffer) > 4096 then
        r.buffer = Right(r.buffer, 4096)
    end if
end sub

function Xtream_LastInstr(text as string, part as string) as integer
    at = 0
    while true
        n = Instr(at + 1, text, part)
        if n = 0 then return at
        at = n
    end while
    return at
end function

sub Xtream_XmltvKeep(r as object, block as string)
    tagEnd = Instr(1, block, ">")
    if tagEnd = 0 then return
    tag = Left(block, tagEnd)
    channel = LCase(Xtream_XmlAttr(tag, "channel"))
    if channel = "" then return
    if r.known <> invalid and not r.known.DoesExist(channel) then return
    begins = Xtream_ParseXmltvTime(Xtream_XmlAttr(tag, "start"), r.clock)
    ends = Xtream_ParseXmltvTime(Xtream_XmlAttr(tag, "stop"), r.clock)
    if begins <= 0 or ends <= begins or ends <= r.from or begins >= r.upTo then return
    title = Xtream_XmlText(Xtream_XmlElement(block, "title")).Trim()
    if title = "" then title = "Untitled"
    description = Xtream_XmlText(Xtream_XmlElement(block, "desc")).Trim()
    if Len(description) > 400 then description = Left(description, 397) + "…"
    list = r.byChannel[channel]
    if list = invalid then
        list = []
        r.byChannel[channel] = list
    end if
    list.Push({ channelId: channel, start: begins, ends: ends, title: title, description: description })
    r.count = r.count + 1
end sub

' The programmes kept, each channel's in order and each start once.
function Xtream_XmltvDone(r as object) as object
    out = {}
    for each id in r.byChannel
        seen = {}
        list = []
        sorted = StableSort_(r.byChannel[id], function(a as object, b as object) as boolean
            return a.start < b.start
        end function)
        for each p in sorted
            if not seen.DoesExist(p.start.ToStr()) then
                seen[p.start.ToStr()] = true
                list.Push(p)
            end if
        end for
        out[id] = list
    end for
    return out
end function

' A whole attribute: "start" isn't the end of "catchup-start".
function Xtream_XmlAttr(tag as string, name as string) as string
    for each lead in [" ", Chr(9), Chr(10), Chr(13)]
        at = Instr(1, tag, lead + name + "=" + Chr(34))
        if at > 0 then
            from = at + Len(lead + name) + 2
            ends = Instr(from, tag, Chr(34))
            if ends = 0 then return ""
            return Mid(tag, from, ends - from)
        end if
    end for
    return ""
end function

' The first <name ...>…</name> in [block], as written.
function Xtream_XmlElement(block as string, name as string) as string
    at = 0
    while true
        at = Instr(at + 1, block, "<" + name)
        if at = 0 then return ""
        after = Mid(block, at + Len(name) + 1, 1)
        if after = ">" or after = " " or after = Chr(9) or after = Chr(10) or after = Chr(13) then exit while
    end while
    opens = Instr(at, block, ">")
    if opens = 0 or Mid(block, opens - 1, 1) = "/" then return ""
    closes = Instr(opens, block, "</" + name + ">")
    if closes = 0 then return ""
    return Mid(block, opens + 1, closes - opens - 1)
end function

' Text from XML: CDATA as written, entities undone outside it.
function Xtream_XmlText(text as string) as string
    out = ""
    rest = text
    while true
        at = Instr(1, rest, "<![CDATA[")
        if at = 0 then exit while
        out = out + Xtream_XmlEntities(Left(rest, at - 1))
        ends = Instr(at + 9, rest, "]]>")
        if ends = 0 then
            out = out + Mid(rest, at + 9)
            return out
        end if
        out = out + Mid(rest, at + 9, ends - at - 9)
        rest = Mid(rest, ends + 3)
    end while
    return out + Xtream_XmlEntities(rest)
end function

function Xtream_XmlEntities(text as string) as string
    if Instr(1, text, "&") = 0 then return text
    t = text.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", Chr(34)).Replace("&apos;", "'")
    out = ""
    while true
        at = Instr(1, t, "&#")
        if at = 0 then exit while
        ends = Instr(at, t, ";")
        if ends = 0 or ends - at > 10 then
            out = out + Left(t, at + 1)
            t = Mid(t, at + 2)
        else
            code = Mid(t, at + 2, ends - at - 2)
            n = 0
            if LCase(Left(code, 1)) = "x" then n = Val(Mid(code, 2), 16) else n = Val(code, 10)
            out = out + Left(t, at - 1)
            if n > 0 and n <= 1114111 then out = out + Chr(n)
            t = Mid(t, ends + 1)
        end if
    end while
    return (out + t).Replace("&amp;", "&")
end function

' "20240115143000 +0100" in epoch seconds; 0 when it isn't one.
function Xtream_ParseXmltvTime(raw as string, clock = invalid as dynamic) as integer
    t = raw.Trim()
    if Len(t) < 14 then return 0
    digits = Left(t, 14)
    for i = 1 to 14
        c = Asc(Mid(digits, i, 1))
        if c < 48 or c > 57 then return 0
    end for
    if clock = invalid then clock = CreateObject("roDateTime")
    clock.FromISO8601String(Left(digits, 4) + "-" + Mid(digits, 5, 2) + "-" + Mid(digits, 7, 2) + "T" + Mid(digits, 9, 2) + ":" + Mid(digits, 11, 2) + ":" + Mid(digits, 13, 2) + "Z")
    seconds = clock.AsSeconds()
    zone = Mid(t, 15).Trim()
    if Len(zone) = 5 and (Left(zone, 1) = "+" or Left(zone, 1) = "-") then
        shift = Val(Mid(zone, 2, 2), 10) * 3600 + Val(Mid(zone, 4, 2), 10) * 60
        if Left(zone, 1) = "+" then seconds = seconds - shift else seconds = seconds + shift
    end if
    return seconds
end function

' A channel's programmes from the playlist's guide, under its stream id as the panel's are.
function Xtream_PlaylistListing(byChannel as object, streamId as string, epgId as string) as object
    out = []
    for each p in Arr_(byChannel[epgId])
        out.Push({ channelId: streamId, start: p.start, ends: p.ends, title: p.title, description: p.description })
    end for
    return out
end function

' What's on and what's next, as the panel's short guide gives them.
function Xtream_NowAndNext(list as object, now as integer, count = 4 as integer) as object
    out = []
    for each p in list
        if p.ends > now and out.Count() < count then out.Push(p)
    end for
    return out
end function
