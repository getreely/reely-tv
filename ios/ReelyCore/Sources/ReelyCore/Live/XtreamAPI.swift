import Foundation

/** Live TV asked of the provider: the panel, or the playlist and the XMLTV guide it names. */
public actor XtreamClient {
    let http: Http
    private var playlist: (url: String, list: M3uPlaylist)?
    private var guideHeld: (url: String, at: Int, byChannel: [String: [Programme]])?
    private var guideReading: (url: String, task: Task<[String: [Programme]], Error>)?

    /// How long a playlist's guide is kept before it's read again.
    public static let guideKeepSeconds = 6 * 3600

    public init(http: Http) { self.http = http }

    public func login(_ c: XtreamCredentials) async throws -> XtreamAccount {
        if c.isPlaylist {
            let list = try await playlist(for: c, fresh: true)
            guard !list.channels.isEmpty else { throw HttpError("There are no live channels in that playlist.") }
            return XtreamAccount(status: "Active", maxConnections: "?", activeConnections: "0", expiresAt: nil, timezone: nil)
        }
        let root = try await http.json(HttpRequest(url: Xtream.api(c, nil)), failure: "Couldn't connect. Check the server address and port.")
        let user = root["user_info"]
        guard user.isObject else { throw HttpError("Couldn't connect. Check the server address and port.") }
        guard user["auth"].num == 1 else { throw HttpError("Incorrect username or password.") }
        let status = user["status"].text ?? "Unknown"
        guard status.lowercased() == "active" else { throw HttpError("This account is \(status). Contact your provider.") }
        let exp = user["exp_date"].str
        return XtreamAccount(status: status, maxConnections: user["max_connections"].text ?? "?", activeConnections: user["active_cons"].text ?? "0",
                             expiresAt: exp.isEmpty || exp == "null" ? nil : exp, timezone: root["server_info"]["timezone"].text)
    }

    public func playlist(for c: XtreamCredentials, fresh: Bool = false) async throws -> M3uPlaylist {
        let url = c.playlistUrl ?? ""
        if !fresh, let held = playlist, held.url == url { return held.list }
        let response = try await http.ask(HttpRequest(url: url, timeout: 120), failure: "Couldn't download the playlist.")
        let list = Xtream.parseM3u(response.text)
        playlist = (url, list)
        return list
    }

    public func categories(_ c: XtreamCredentials) async throws -> [XtreamCategory] {
        if c.isPlaylist {
            var seen = Set<String>()
            return try await playlist(for: c).channels.map { $0.group ?? OTHER_GROUP }.filter { seen.insert($0).inserted }.map { XtreamCategory(id: $0, name: $0) }
        }
        let list = try await http.json(HttpRequest(url: Xtream.api(c, "get_live_categories")), failure: "Your provider couldn't do that. Try again.")
        var seen = Set<String>()
        return list.array.filter(\.isObject)
            .map { XtreamCategory(id: $0["category_id"].str, name: $0["category_name"].text ?? "Unnamed") }
            .filter { !$0.id.isEmpty && seen.insert($0.id).inserted }
    }

    public func channels(_ c: XtreamCredentials, categoryId: String? = nil) async throws -> [XtreamChannel] {
        if c.isPlaylist {
            let all = try await playlist(for: c).channels
            return categoryId.map { id in all.filter { ($0.group ?? OTHER_GROUP) == id } } ?? all
        }
        let list = try await http.json(HttpRequest(url: Xtream.api(c, "get_live_streams", categoryId.map { [("category_id", $0)] } ?? []), timeout: 60),
                                       failure: "Your provider couldn't do that. Try again.")
        var seen = Set<Int>()
        return list.array.filter(\.isObject).compactMap { x -> XtreamChannel? in
            let raw = x["stream_id"].str.trimmingCharacters(in: .whitespaces)
            guard !raw.isEmpty, raw.allSatisfy(\.isNumber), let id = Int(raw), seen.insert(id).inserted else { return nil }
            let icon = x["stream_icon"].str
            let epg = x["epg_channel_id"].str.trimmingCharacters(in: .whitespaces)
            return XtreamChannel(streamId: id, number: x["num"].int, name: x["name"].text ?? "Channel \(id)",
                                 icon: icon.hasPrefix("http") ? icon : nil, epgChannelId: epg.isEmpty ? nil : epg.lowercased(),
                                 archiveDays: x["tv_archive"].str == "1" ? x["tv_archive_duration"].int : 0)
        }
    }

    /// A channel's whole listing the panel holds, what's been as well as what's coming: the guide's grid.
    public func table(_ c: XtreamCredentials, streamId: Int) async throws -> [Programme] {
        if c.isPlaylist { return [] }
        let root = try await http.json(HttpRequest(url: Xtream.api(c, "get_simple_data_table", [("stream_id", String(streamId))]), timeout: 30))
        var seen = Set<Int>()
        return stableSorted(Xtream.listings(root, streamId: streamId)) { $0.start < $1.start }.filter { seen.insert($0.start).inserted }
    }

    /// Now and next for one channel, from the panel.
    public func shortEpg(_ c: XtreamCredentials, streamId: Int, limit: Int = 4) async throws -> [Programme] {
        if c.isPlaylist { return [] }
        let root = try await http.json(HttpRequest(url: Xtream.api(c, "get_short_epg", [("stream_id", String(streamId)), ("limit", String(limit))])))
        return stableSorted(Xtream.listings(root, streamId: streamId)) { $0.start < $1.start }
    }

    // MARK: A playlist's own guide

    /// Where the whole XMLTV guide is: the panel's, or the playlist's own.
    public func xmltvUrl(_ c: XtreamCredentials) -> String? {
        if c.isPlaylist {
            if let g = c.guideUrl, !g.isEmpty { return g }
            if let held = playlist, held.url == c.playlistUrl { return held.list.guideUrl }
            return nil
        }
        return "\(c.base)/xmltv.php?username=\(Xtream.formEncode(c.username))&password=\(Xtream.formEncode(c.password))"
    }

    /// Whether the playlist names a guide: nil until the playlist has been read and that's known.
    public func guideNamed(_ c: XtreamCredentials) -> Bool? {
        guard c.isPlaylist else { return true }
        if let g = c.guideUrl, !g.isEmpty { return true }
        guard let held = playlist, held.url == c.playlistUrl else { return nil }
        return held.list.guideUrl != nil
    }

    public func guideReadAt(_ c: XtreamCredentials) -> Int? {
        guard let url = xmltvUrl(c), let held = guideHeld, held.url == url else { return nil }
        return held.at
    }

    public func forgetGuide() { guideHeld = nil; guideReading = nil; playlist = nil }

    /**
     * The playlist's whole XMLTV guide, for its own channels from a day back to three days
     * ahead, as the Fire TV keeps it; read once and kept for six hours.
     */
    public func playlistGuide(_ c: XtreamCredentials, fresh: Bool = false, now: Int = Int(Date().timeIntervalSince1970)) async throws -> [String: [Programme]] {
        guard c.isPlaylist else { return [:] }
        let list = try await playlist(for: c)
        guard let url = xmltvUrl(c) else { return [:] }
        if !fresh, let held = guideHeld, held.url == url, now - held.at < XtreamClient.guideKeepSeconds { return held.byChannel }
        if let reading = guideReading, reading.url == url { return try await reading.task.value }
        let ids = Set(list.channels.compactMap { $0.epgChannelId?.lowercased() })
        let http = self.http
        let task = Task<[String: [Programme]], Error> {
            let response = try await http.ask(HttpRequest(url: url, timeout: 180), failure: "Couldn't download the TV guide.")
            var data = response.data
            if data.count >= 2 && data[data.startIndex] == 0x1f && data[data.startIndex + 1] == 0x8b {
                guard let unzipped = gunzip(data) else { throw HttpError("This device can't open the TV guide: it's compressed in a way it doesn't know.") }
                data = unzipped
            }
            var byChannel: [String: [Programme]] = [:]
            let from = now - 86_400, to = now + 3 * 86_400
            var reader = XmltvReader(keep: { ids.contains($0) }) { p in
                if p.stop > from && p.start < to { byChannel[p.channelId, default: []].append(p) }
            }
            reader.push(String(decoding: data, as: UTF8.self))
            for (id, list) in byChannel {
                var seen = Set<Int>()
                byChannel[id] = stableSorted(list) { $0.start < $1.start }.filter { seen.insert($0.start).inserted }
            }
            return byChannel
        }
        guideReading = (url, task)
        defer { if guideReading?.url == url { guideReading = nil } }
        let byChannel = try await task.value
        guideHeld = (url, now, byChannel)
        return byChannel
    }
}

/// A channel's programmes from the playlist's guide, under its stream id as the panel's are.
public func playlistListing(_ byChannel: [String: [Programme]], _ channel: XtreamChannel) -> [Programme] {
    guard let id = channel.epgChannelId?.lowercased(), let list = byChannel[id] else { return [] }
    return list.map { var p = $0; p.channelId = String(channel.streamId); return p }
}

/// Now and what's next from a listing, as the panel's short guide gives them.
public func nowAndNext(_ list: [Programme], now: Int, count: Int = 4) -> [Programme] {
    guard let at = list.firstIndex(where: { $0.stop > now }) else { return [] }
    return Array(list[at...].prefix(count))
}

#if canImport(Compression)
import Compression
/// A gzip file opened: its header passed over, the deflate stream inside undone.
func gunzip(_ data: Data) -> Data? {
    guard data.count > 18 else { return nil }
    let bytes = [UInt8](data)
    var at = 10
    let flags = bytes[3]
    if flags & 4 != 0 { at += 2 + Int(bytes[10]) | Int(bytes[11]) << 8 }
    if flags & 8 != 0 { while at < bytes.count && bytes[at] != 0 { at += 1 }; at += 1 }
    if flags & 16 != 0 { while at < bytes.count && bytes[at] != 0 { at += 1 }; at += 1 }
    if flags & 2 != 0 { at += 2 }
    guard at < bytes.count - 8 else { return nil }
    let body = Data(bytes[at..<(bytes.count - 8)])
    var out = Data()
    let size = 1 << 20
    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
    defer { buffer.deallocate() }
    let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
    defer { stream.deallocate() }
    guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
    defer { compression_stream_destroy(stream) }
    return body.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Data? in
        stream.pointee.src_ptr = raw.bindMemory(to: UInt8.self).baseAddress!
        stream.pointee.src_size = body.count
        while true {
            stream.pointee.dst_ptr = buffer
            stream.pointee.dst_size = size
            let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
            out.append(buffer, count: size - stream.pointee.dst_size)
            if status == COMPRESSION_STATUS_END { return out }
            if status == COMPRESSION_STATUS_ERROR { return nil }
        }
    }
}
#else
/// Linux, for tests: no gzip.
func gunzip(_ data: Data) -> Data? { nil }
#endif
