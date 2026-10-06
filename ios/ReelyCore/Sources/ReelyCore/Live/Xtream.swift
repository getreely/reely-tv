import Foundation

/*
 * Live TV from the provider, as the Android app has it (XtreamApi.kt, M3uPlaylist.kt) and
 * the LG app after it: an Xtream login or an M3U playlist; categories, channels, the
 * guide, catch-up.
 */

public struct XtreamCredentials: Codable, Equatable, Hashable, Sendable {
    public var base: String
    public var username: String
    public var password: String
    /// Set when live TV comes from an M3U playlist.
    public var playlistUrl: String?
    public var guideUrl: String?
    public init(base: String, username: String, password: String, playlistUrl: String? = nil, guideUrl: String? = nil) {
        self.base = base; self.username = username; self.password = password; self.playlistUrl = playlistUrl; self.guideUrl = guideUrl
    }
    public var isPlaylist: Bool { !(playlistUrl ?? "").isEmpty }
}

public struct XtreamAccount: Codable, Equatable, Sendable {
    public var status: String
    public var maxConnections: String
    public var activeConnections: String
    public var expiresAt: String?
    public var timezone: String?
}

public struct XtreamCategory: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public init(id: String, name: String) { self.id = id; self.name = name }
}

public struct XtreamChannel: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var streamId: Int
    public var number: Int
    public var name: String
    public var icon: String?
    public var epgChannelId: String?
    public var url: String?
    public var group: String?
    public var archiveDays: Int
    public var id: Int { streamId }
    public init(streamId: Int, number: Int, name: String, icon: String? = nil, epgChannelId: String? = nil, url: String? = nil, group: String? = nil, archiveDays: Int = 0) {
        self.streamId = streamId; self.number = number; self.name = name; self.icon = icon; self.epgChannelId = epgChannelId
        self.url = url; self.group = group; self.archiveDays = archiveDays
    }
}

public struct Programme: Codable, Equatable, Hashable, Sendable {
    public var channelId: String
    /// Epoch seconds.
    public var start: Int
    public var stop: Int
    public var title: String
    public var description: String?
    public init(channelId: String, start: Int, stop: Int, title: String, description: String? = nil) {
        self.channelId = channelId; self.start = start; self.stop = stop; self.title = title; self.description = description
    }
    public func isOn(at: Int) -> Bool { at >= start && at < stop }
    /// How far through it is at [at], 0 to 1; nil when it isn't on or has no length.
    public func progress(at: Int) -> Double? {
        guard stop > start, at >= start, at <= stop else { return nil }
        return Double(at - start) / Double(stop - start)
    }
}

public struct M3uPlaylist: Equatable, Sendable {
    public var channels: [XtreamChannel]
    public var guideUrl: String?
}

public enum StreamFormat: String, Sendable { case m3u8, ts }

public let OTHER_GROUP = "Other"
private let VOD_EXTENSIONS = [".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm"]

public enum Xtream {
    /// Accepts "panel.example.com:8080", "http://…/" and the rest.
    public static func normalizeBase(_ raw: String) -> String {
        var t = raw.trimmingCharacters(in: .whitespaces)
        while t.hasSuffix("/") { t.removeLast() }
        guard !t.isEmpty else { return t }
        let low = t.lowercased()
        return low.hasPrefix("http://") || low.hasPrefix("https://") ? t : "http://\(t)"
    }

    /// As URLSearchParams writes a value: letters, digits and *-._ as they are, a space as +.
    public static func formEncode(_ s: String) -> String {
        s.utf8.map { b -> String in
            let c = Character(UnicodeScalar(b))
            if (b < 128) && (c.isLetter || c.isNumber || "*-._".contains(c)) { return String(c) }
            if b == 32 { return "+" }
            return String(format: "%%%02X", b)
        }.joined()
    }

    static func api(_ c: XtreamCredentials, _ action: String?, _ extras: [(String, String)] = []) -> String {
        var q = [("username", c.username), ("password", c.password)]
        if let action { q.append(("action", action)) }
        q += extras
        return "\(c.base)/player_api.php?" + q.map { "\($0.0)=\(formEncode($0.1))" }.joined(separator: "&")
    }

    /// The panel login inside an M3U address from a panel's get.php, when there is one.
    public static func panelLogin(in url: String) -> XtreamCredentials? {
        guard let u = URLComponents(string: url), let scheme = u.scheme, let host = u.host,
              u.path.lowercased().hasSuffix("get.php") else { return nil }
        let q = Dictionary((u.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        guard let user = q["username"], !user.isEmpty, let pass = q["password"], !pass.isEmpty else { return nil }
        return XtreamCredentials(base: "\(scheme)://\(host)\(u.port.map { ":\($0)" } ?? "")", username: user, password: pass)
    }

    /// The same number for the same address, as Java's String.hashCode makes it.
    public static func javaHash(_ text: String) -> Int {
        var h: Int32 = 0
        for unit in text.utf16 { h = h &* 31 &+ Int32(unit) }
        return Int(h) & 0x7fffffff
    }

    static func attributes(_ line: String) -> [String: String] {
        var out: [String: String] = [:]
        let re = try! NSRegularExpression(pattern: #"([A-Za-z0-9_-]+)="([^"]*)""#)
        let ns = line as NSString
        for m in re.matches(in: line, range: NSRange(location: 0, length: ns.length)) {
            out[ns.substring(with: m.range(at: 1)).lowercased()] = ns.substring(with: m.range(at: 2))
        }
        return out
    }

    /// A playlist, a line at a time; films and series in it are passed over, only channels kept.
    public static func parseM3u(_ text: String) -> M3uPlaylist {
        var guideUrl: String?
        var pending: (name: String?, id: String?, logo: String?, number: Int?, group: String?)?
        var channels: [XtreamChannel] = []
        var ids = Set<Int>()
        for raw in normalizedLines(text) {
            var line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("\u{FEFF}") { line.removeFirst() }
            if line.isEmpty { continue }
            let upper = line.uppercased()
            if upper.hasPrefix("#EXTM3U") {
                let a = attributes(line)
                let named = (a["url-tvg"] ?? a["x-tvg-url"])?.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                    .first { $0.lowercased().hasPrefix("http") }
                guideUrl = named ?? guideUrl
            } else if upper.hasPrefix("#EXTINF") {
                let a = attributes(line)
                var quoted = false
                var comma: String.Index?
                for i in line.indices {
                    if line[i] == "\"" { quoted.toggle() } else if line[i] == "," && !quoted { comma = i; break }
                }
                let name = comma.map { line[line.index(after: $0)...].trimmingCharacters(in: .whitespaces) } ?? ""
                let logo = a["tvg-logo"]?.trimmingCharacters(in: .whitespaces)
                pending = (name.isEmpty ? a["tvg-name"]?.trimmingCharacters(in: .whitespaces).nilIfEmpty : name,
                           a["tvg-id"]?.trimmingCharacters(in: .whitespaces).nilIfEmpty,
                           logo?.lowercased().hasPrefix("http") == true ? logo : nil,
                           Int(a["tvg-chno"] ?? "").flatMap { $0 > 0 ? $0 : nil },
                           a["group-title"]?.trimmingCharacters(in: .whitespaces).nilIfEmpty)
            } else if upper.hasPrefix("#EXTGRP:") {
                if pending != nil, pending?.group == nil, let colon = line.firstIndex(of: ":") {
                    pending?.group = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces).nilIfEmpty
                }
            } else if line.hasPrefix("#") {
                continue
            } else {
                let entry = pending ?? (nil, nil, nil, nil, nil)
                pending = nil
                let path = (line.split(separator: "?").first.map(String.init) ?? line).lowercased()
                if path.contains("/movie/") || path.contains("/series/") || VOD_EXTENSIONS.contains(where: { path.hasSuffix($0) }) { continue }
                var streamId = javaHash(line)
                while ids.contains(streamId) { streamId = (streamId + 1) & 0x7fffffff }
                ids.insert(streamId)
                channels.append(XtreamChannel(streamId: streamId, number: entry.number ?? channels.count + 1,
                                              name: entry.name ?? "Channel \(channels.count + 1)", icon: entry.logo,
                                              epgChannelId: entry.id?.lowercased(), url: line, group: entry.group))
            }
        }
        return M3uPlaylist(channels: channels, guideUrl: guideUrl)
    }

    public static func streamUrl(_ c: XtreamCredentials, _ channel: XtreamChannel, format: StreamFormat) -> String {
        channel.url ?? "\(c.base)/live/\(encodeComponent(c.username))/\(encodeComponent(c.password))/\(channel.streamId).\(format.rawValue)"
    }

    /// A programme from the channel's archive: the panel's timeshift address, in its own time zone.
    public static func catchUpUrl(_ c: XtreamCredentials, _ channel: XtreamChannel, start: Int, stop: Int, timezone: String?) -> String? {
        guard !c.isPlaylist, channel.archiveDays > 0 else { return nil }
        let minutes = max(1, (stop - start + 59) / 60)
        return "\(c.base)/timeshift/\(encodeComponent(c.username))/\(encodeComponent(c.password))/\(minutes)/\(panelTime(start, timezone: timezone))/\(channel.streamId).ts"
    }

    /// "2024-03-05:20-30": a moment as the panel's clock reads it.
    public static func panelTime(_ epoch: Int, timezone: String?) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timezone.flatMap(TimeZone.init(identifier:)) ?? .current
        let d = cal.dateComponents([.year, .month, .day, .hour, .minute], from: Date(timeIntervalSince1970: TimeInterval(epoch)))
        return String(format: "%04d-%02d-%02d:%02d-%02d", d.year!, d.month!, d.day!, d.hour!, d.minute!)
    }

    /// The earliest moment that can be watched again, or nil for a channel with no archive.
    public static func catchUpFrom(_ channel: XtreamChannel, now: Int) -> Int? {
        channel.archiveDays > 0 ? now - channel.archiveDays * 86_400 : nil
    }

    /// The programme at [at], when it's over and still in the archive; else nil (play live).
    public static func catchUpProgramme(_ channel: XtreamChannel, _ listing: [Programme], at: Int, now: Int) -> Programme? {
        guard let from = catchUpFrom(channel, now: now), let p = listing.first(where: { $0.isOn(at: at) }) else { return nil }
        return p.stop <= now && p.start >= from ? p : nil
    }

    public static func canCatchUp(_ channel: XtreamChannel, _ p: Programme, now: Int) -> Bool {
        guard let from = catchUpFrom(channel, now: now) else { return false }
        return p.stop <= now && p.start >= from
    }

    /// Titles come base64, mostly.
    public static func decodeField(_ raw: JSON) -> String {
        let text = raw.str
        guard !text.isEmpty else { return "" }
        let compact = text.filter { !$0.isWhitespace }
        let b64 = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        guard compact.unicodeScalars.allSatisfy(b64.contains), let data = Data(base64Encoded: compact),
              let decoded = String(data: data, encoding: .utf8) else { return text.trimmingCharacters(in: .whitespaces) }
        return decoded.trimmingCharacters(in: .whitespaces)
    }

    /// "2024-03-05 21:30:00" in the device's own time, as the panel writes it.
    static func parsePanelTime(_ raw: JSON) -> Int {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f.date(from: raw.str).map { Int($0.timeIntervalSince1970) } ?? 0
    }

    static func listings(_ root: JSON, streamId: Int) -> [Programme] {
        root["epg_listings"].array.compactMap { e -> Programme? in
            let start = e["start_timestamp"].positive ?? parsePanelTime(e["start"])
            let stop = e["stop_timestamp"].positive ?? parsePanelTime(e["end"])
            guard start > 0, stop > start else { return nil }
            let title = decodeField(e["title"])
            let desc = decodeField(e["description"])
            return Programme(channelId: String(streamId), start: start, stop: stop, title: title.isEmpty ? "Untitled" : title, description: desc.isEmpty ? nil : desc)
        }
    }

    /// "20240115143000 +0100" in epoch seconds; 0 when it isn't one.
    public static func parseXmltvTime(_ raw: String?) -> Int {
        guard let t = raw?.trimmingCharacters(in: .whitespaces), t.count >= 14 else { return 0 }
        let digits = String(t.prefix(14))
        guard digits.allSatisfy(\.isNumber) else { return 0 }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        func n(_ a: Int, _ len: Int) -> Int { Int(digits.dropFirst(a).prefix(len))! }
        guard let date = cal.date(from: DateComponents(year: n(0, 4), month: n(4, 2), day: n(6, 2), hour: n(8, 2), minute: n(10, 2), second: n(12, 2))) else { return 0 }
        var seconds = Int(date.timeIntervalSince1970)
        let zone = t.dropFirst(14).trimmingCharacters(in: .whitespaces)
        if zone.count == 5, let sign = zone.first, sign == "+" || sign == "-", let h = Int(zone.dropFirst().prefix(2)), let m = Int(zone.suffix(2)) {
            seconds -= (sign == "-" ? -1 : 1) * (h * 3600 + m * 60)
        }
        return seconds
    }
}

/**
 * Reads an XMLTV guide as it arrives, programme by programme, without holding the whole of
 * it: a big provider's runs to tens of megabytes.
 */
public struct XmltvReader {
    private var buffer = ""
    private let keep: (String) -> Bool
    private let emit: (Programme) -> Void

    public init(keep: @escaping (String) -> Bool = { _ in true }, emit: @escaping (Programme) -> Void) {
        self.keep = keep
        self.emit = emit
    }

    public mutating func push(_ chunk: String) {
        buffer += chunk
        while let end = buffer.range(of: "</programme>") {
            let head = buffer[..<end.lowerBound]
            let block = head.range(of: "<programme", options: .backwards).map { String(head[$0.lowerBound...]) } ?? ""
            buffer = String(buffer[end.upperBound...])
            if !block.isEmpty { read(block) }
        }
        // Nothing worth keeping before the last programme that's begun.
        if let open = buffer.range(of: "<programme", options: .backwards) {
            if open.lowerBound > buffer.startIndex { buffer = String(buffer[open.lowerBound...]) }
        } else if buffer.count > 4096 {
            buffer = String(buffer.suffix(4096))
        }
    }

    private func read(_ block: String) {
        guard let tagEnd = block.firstIndex(of: ">") else { return }
        let tag = String(block[..<tagEnd])
        func attr(_ name: String) -> String? {
            for lead in [" ", "\t", "\n", "\r"] {
                if let r = tag.range(of: lead + name + "=\"") {
                    let rest = tag[r.upperBound...]
                    return rest.firstIndex(of: "\"").map { String(rest[..<$0]) }
                }
            }
            return nil
        }
        guard let channel = attr("channel")?.lowercased(), keep(channel) else { return }
        let start = Xtream.parseXmltvTime(attr("start")), stop = Xtream.parseXmltvTime(attr("stop"))
        guard start > 0, stop > start else { return }
        let title = xmlText(element(block, "title")).trimmingCharacters(in: .whitespacesAndNewlines)
        let desc = xmlText(element(block, "desc")).trimmingCharacters(in: .whitespacesAndNewlines)
        emit(Programme(channelId: channel, start: start, stop: stop, title: title.isEmpty ? "Untitled" : title, description: desc.isEmpty ? nil : desc))
    }

    private func element(_ block: String, _ name: String) -> String {
        var from = block.startIndex
        while let r = block.range(of: "<" + name, range: from..<block.endIndex) {
            let after = block[r.upperBound...].first
            if after == ">" || after == " " || after == "\t" || after == "\n" {
                guard let open = block[r.upperBound...].firstIndex(of: ">"),
                      let close = block.range(of: "</\(name)>", range: open..<block.endIndex) else { return "" }
                return String(block[block.index(after: open)..<close.lowerBound])
            }
            from = r.upperBound
        }
        return ""
    }

    private func xmlText(_ text: String) -> String {
        // CDATA is taken as written; only what's outside it has entities to undo.
        var out = ""
        var rest = Substring(text)
        while let open = rest.range(of: "<![CDATA[") {
            out += entities(String(rest[..<open.lowerBound]))
            guard let close = rest.range(of: "]]>", range: open.upperBound..<rest.endIndex) else { out += rest[open.upperBound...]; return out }
            out += rest[open.upperBound..<close.lowerBound]
            rest = rest[close.upperBound...]
        }
        return out + entities(String(rest))
    }

    private func entities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var t = text.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&apos;", with: "'")
        let re = try! NSRegularExpression(pattern: "&#(x[0-9a-fA-F]+|[0-9]+);")
        for m in re.matches(in: t, range: NSRange(t.startIndex..., in: t)).reversed() {
            let code = (t as NSString).substring(with: m.range(at: 1))
            let n = code.hasPrefix("x") || code.hasPrefix("X") ? Int(code.dropFirst(), radix: 16) : Int(code)
            let ch = n.flatMap(UnicodeScalar.init).map { String(Character($0)) } ?? ""
            t = (t as NSString).replacingCharacters(in: m.range, with: ch)
        }
        return t.replacingOccurrences(of: "&amp;", with: "&")
    }
}
