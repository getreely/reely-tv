sub Main()
    ' Addresses as people type them.
    Expect("a bare host gets http", Xtream_Base(" panel.example.com:8080/ "), "http://panel.example.com:8080")
    Expect("https kept", Xtream_Base("https://tv.example.com//"), "https://tv.example.com")
    c = { base: "http://p.tv:8080", username: "ann lee", password: "p&w", playlistUrl: "" }
    Expect("the API address, its login escaped", Xtream_ApiUrl(c, "get_live_streams", { category_id: "7" }), "http://p.tv:8080/player_api.php?username=ann%20lee&password=p%26w&action=get_live_streams&category_id=7")

    ' Signing in.
    Expect("nothing back", Xtream_ParseLogin(invalid).error, "Couldn't connect. Check the server address and port.")
    Expect("wrong password", Xtream_ParseLogin({ user_info: { auth: 0 } }).error, "Incorrect username or password.")
    Expect("an expired account", Xtream_ParseLogin({ user_info: { auth: 1, status: "Expired" } }).error, "This account is Expired. Contact your provider.")
    ok = Xtream_ParseLogin({ user_info: { auth: 1, status: "Active", max_connections: "2", active_cons: "0", exp_date: "1767225600" }, server_info: { time_now: "2024-03-05 21:30:00", timestamp_now: 1709670600 } })
    Expect("signed in", ok.ok, true)
    Expect("the panel's clock, an hour ahead", ok.account.clockOffset, 3600)
    Expect("its connections", [ok.account.maxConnections, ok.account.expiresAt], ["2", "1767225600"])

    ' Categories and channels: each once, the broken ones left out.
    cats = Xtream_ParseCategories([{ category_id: "1", category_name: "News" }, { category_id: "1", category_name: "News" }, { category_id: "", category_name: "x" }, "junk", { category_id: 2 }])
    Expect("categories once each", cats, [{ id: "1", name: "News" }, { id: "2", name: "Unnamed" }])
    chs = Xtream_ParseChannels([
        { stream_id: "12", num: 4, name: "BBC One", stream_icon: "http://i/1.png", epg_channel_id: "BBC1.uk", tv_archive: 1, tv_archive_duration: "7", category_id: "1" },
        { stream_id: 12, name: "again" },
        { stream_id: "x9", name: "bad id" },
        { stream_id: 13, name: "", stream_icon: "/rel.png" }
    ])
    Expect("channels once each, bad ids out", chs.Count(), 2)
    Expect("a channel read", [chs[0].streamId, chs[0].number, chs[0].epgChannelId, chs[0].archiveDays], [12, 4, "bbc1.uk", 7])
    Expect("unnamed and no icon", [chs[1].name, chs[1].icon], ["Channel 13", ""])
    Expect("its number and name", Xtream_ChannelLabel(chs[0]), "4  BBC One")
    Expect("the live address", Xtream_StreamUrl(c, chs[0], "m3u8"), "http://p.tv:8080/live/ann%20lee/p%26w/12.m3u8")

    ' An M3U playlist: channels kept, films and series passed over.
    q = Chr(34)
    m3u = "#EXTM3U url-tvg=" + q + "http://g/epg.xml" + q + Chr(13) + Chr(10)
    m3u = m3u + "#EXTINF:-1 tvg-id=" + q + "News.uk" + q + " tvg-chno=" + q + "101" + q + " tvg-logo=" + q + "http://l/n.png" + q + " group-title=" + q + "News, UK" + q + ",News 24" + Chr(10)
    m3u = m3u + "http://s/live/1.ts" + Chr(10)
    m3u = m3u + "#EXTINF:-1,A Film" + Chr(10) + "http://s/movie/u/p/5.mkv" + Chr(10)
    m3u = m3u + "#EXTINF:-1 tvg-name=" + q + "Sport" + q + "," + Chr(10) + "#EXTGRP:Sport" + Chr(10) + "http://s/live/2.ts" + Chr(10)
    list = Xtream_ParseM3u(m3u)
    Expect("the playlist's guide", list.guideUrl, "http://g/epg.xml")
    Expect("films left out", list.channels.Count(), 2)
    Expect("a channel from its line", [list.channels[0].name, list.channels[0].number, list.channels[0].group, list.channels[0].epgChannelId, list.channels[0].url], ["News 24", 101, "News, UK", "news.uk", "http://s/live/1.ts"])
    Expect("its name from tvg-name, its group from #EXTGRP", [list.channels[1].name, list.channels[1].group, list.channels[1].number], ["Sport", "Sport", 2])
    Expect("the same id for the same address", list.channels[0].streamId, Xtream_ParseM3u(m3u).channels[0].streamId)
    Expect("Java's hashCode", Xtream_Hash("hello"), 99162322)
    Expect("a long one kept positive", Xtream_Hash("http://example.com/live/user/pass/123456.ts") >= 0, true)
    Expect("groups as categories", Xtream_PlaylistCategories(list.channels), [{ id: "News, UK", name: "News, UK" }, { id: "Sport", name: "Sport" }])
    Expect("a group's channels", Xtream_InCategory(list.channels, "Sport").Count(), 1)

    ' The guide: base64 titles, in order, each once.
    listing = Xtream_ParseListings({ epg_listings: [
        { start_timestamp: "2000", stop_timestamp: "3000", title: "TGF0ZXI=", description: "" },
        { start_timestamp: "1000", stop_timestamp: "2000", title: "TmV3cw==", description: "VGhlIGRheQ==" },
        { start_timestamp: "1000", stop_timestamp: "2000", title: "TmV3cw==" },
        { start_timestamp: "5000", stop_timestamp: "4000", title: "eA==" }
    ] }, "12")
    Expect("in order, once each, nonsense out", [listing.Count(), listing[0].title, listing[0].description, listing[1].title], [2, "News", "The day", "Later"])
    Expect("plain words left as they are", Xtream_DecodeField("Match of the Day"), "Match of the Day")
    Expect("what's on", Xtream_ProgrammeAt(listing, 1500).title, "News")
    Expect("what's next", Xtream_NextAfter(listing, 1500).title, "Later")
    Expect("halfway through", Xtream_Progress(listing[0], 1500), 0.5)
    Expect("nothing on", Xtream_ProgrammeAt(listing, 9000), invalid)
    real = Xtream_ParseListings({ epg_listings: [{ start_timestamp: "1759521600", stop_timestamp: "1759525200", title: "TmV3cw==" }, { start_timestamp: 1759525200, stop_timestamp: 1759527000, title: "TmV3cw==" }] }, "1")
    Expect("times to the second, as text or as numbers", [real[0].start, real[0].ends, real[1].start], [1759521600, 1759525200, 1759525200])

    ' Catch-up: over, inside the archive, the address in the panel's time.
    ch = { streamId: 12, archiveDays: 1, url: "" }
    Expect("over and kept", Xtream_CanCatchUp(ch, { start: 1000, ends: 2000 }, 3000), true)
    Expect("still on", Xtream_CanCatchUp(ch, { start: 1000, ends: 4000 }, 3000), false)
    Expect("too long ago", Xtream_CanCatchUp(ch, { start: 1000, ends: 2000 }, 1000 + 86400 * 2), false)
    Expect("no archive", Xtream_CanCatchUp({ archiveDays: 0 }, { start: 1000, ends: 2000 }, 3000), false)
    Expect("the panel's clock", Xtream_PanelTime(1709670600, 3600), "2024-03-05:21-30")
    Expect("the timeshift address", Xtream_CatchUpUrl(c, ch, 1709670600, 1709674200, 0), "http://p.tv:8080/timeshift/ann%20lee/p%26w/60/2024-03-05:20-30/12.ts")
    Expect("none from a playlist", Xtream_CatchUpUrl({ base: "", username: "", password: "", playlistUrl: "http://x" }, ch, 1, 2, 0), "")

    ' Reminders: put up as the programme starts, once, and let go when it's over.
    rs = [{ channel: { streamId: 1 }, start: 1000, ends: 2000, title: "A" }, { channel: { streamId: 2 }, start: 5000, ends: 6000, title: "B" }]
    Expect("nothing due before it starts", Xtream_Reminders(rs, 900, {}).due, invalid)
    Expect("due as it starts", Xtream_Reminders(rs, 1000, {}).due.title, "A")
    Expect("not put up twice", Xtream_Reminders(rs, 1100, { "1:1000": true }).due, invalid)
    Expect("not put up long after it started", Xtream_Reminders(rs, 1700, {}).due, invalid)
    Expect("let go once over", Xtream_Reminders(rs, 2500, {}).kept.Count(), 1)

    ' A playlist's own XMLTV guide, read in pieces: split anywhere, it reads the same.
    q = Chr(34)
    guide = "<?xml version=" + q + "1.0" + q + "?><tv><channel id=" + q + "news.uk" + q + "><display-name>News</display-name></channel>"
    guide = guide + "<programme catchup-start=" + q + "20000101000000" + q + " start=" + q + "20240115143000 +0000" + q + " stop=" + q + "20240115150000 +0000" + q + " channel=" + q + "News.uk" + q + ">"
    guide = guide + "<title lang=" + q + "en" + q + ">Lunchtime &amp; Weather</title><desc>Headlines &lt;live&gt; &#233;</desc></programme>"
    guide = guide + "<programme start=" + q + "20240115150000 +0100" + q + " stop=" + q + "20240115153000 +0100" + q + " channel=" + q + "sport" + q + "><title><![CDATA[Match & Goals]]></title></programme>"
    guide = guide + "<programme start=" + q + "20240115140000 +0000" + q + " stop=" + q + "20240115143000 +0000" + q + " channel=" + q + "news.uk" + q + "><title>Earlier</title></programme>"
    guide = guide + "<programme start=" + q + "20240115140000 +0000" + q + " stop=" + q + "20240115143000 +0000" + q + " channel=" + q + "news.uk" + q + "><title>Earlier</title></programme>"
    guide = guide + "<programme start=" + q + "20200101000000 +0000" + q + " stop=" + q + "20200101003000 +0000" + q + " channel=" + q + "news.uk" + q + "><title>Long gone</title></programme></tv>"
    Expect("XMLTV time", Xtream_ParseXmltvTime("20240115143000 +0000"), 1705329000)
    Expect("XMLTV time, an hour ahead of UTC", Xtream_ParseXmltvTime("20240115153000 +0100"), 1705329000)
    Expect("not a time", Xtream_ParseXmltvTime("soon"), 0)
    now = 1705329000
    r = Xtream_XmltvReader(invalid, now - 6 * 3600, now + 36 * 3600)
    i = 1
    while i <= Len(guide)
        Xtream_XmltvPush(r, Mid(guide, i, 7))
        i = i + 7
    end while
    read = Xtream_XmltvDone(r)
    titles = []
    for each p in read["news.uk"]
        titles.Push(p.title)
    end for
    Expect("in order, each once, the long gone left out", titles, ["Earlier", "Lunchtime & Weather"])
    Expect("entities undone, attributes matched whole", [read["news.uk"][1].start, read["news.uk"][1].description], [1705329000, "Headlines <live> " + Chr(233)])
    Expect("CDATA as written", read["sport"][0].title, "Match & Goals")
    only = Xtream_XmltvReader({ "sport": true }, 0, 2000000000)
    Xtream_XmltvPush(only, guide)
    Expect("only the playlist's channels kept", Xtream_XmltvDone(only).Keys(), ["sport"])
    listing = Xtream_PlaylistListing(read, "77", "news.uk")
    Expect("under the channel's stream id", [listing[0].channelId, listing.Count()], ["77", 2])
    Expect("now and next", Xtream_NowAndNext(listing, now + 60, 1)[0].title, "Lunchtime & Weather")
    pl = { base: "", username: "", password: "", playlistUrl: "http://s/list.m3u", guideUrl: "" }
    args = Xtream_GuideArgs(pl, "http://g/epg.xml", [{ streamId: 5, epgChannelId: "News.UK" }, { streamId: 6, epgChannelId: "" }], [5, 6], [{ streamId: 5, epgChannelId: "News.UK" }, { streamId: 9, epgChannelId: "Sport" }])
    Expect("the playlist's guide asked for", [args.guideUrl, args.epg, args.known], ["http://g/epg.xml", { "5": "news.uk" }, { "news.uk": true, "sport": true }])
    pl.guideUrl = "http://mine/guide.xml"
    Expect("a guide entered at sign-in comes first", Xtream_GuideArgs(pl, "http://g/epg.xml", [], [], invalid).guideUrl, "http://mine/guide.xml")
end sub
