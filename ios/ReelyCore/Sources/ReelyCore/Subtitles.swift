import Foundation

/*
 * Text subtitles, read here and drawn by the app rather than burned into the picture by
 * Plex: so the file can still play as it is, and the words take the size and background
 * chosen in Settings, as on the Fire TV. SRT, WebVTT and ASS/SSA (as plain lines).
 */

public struct Cue: Equatable, Sendable {
    public var startMs: Int
    public var endMs: Int
    public var text: String
}

public let TEXT_SUBTITLE_CODECS: Set = ["srt", "subrip", "vtt", "webvtt", "ass", "ssa"]
public func isTextCodec(_ codec: String?) -> Bool { codec.map { TEXT_SUBTITLE_CODECS.contains($0.lowercased()) } ?? false }

/// "00:01:02,345", "01:02.345" or ASS's "0:01:02.34", in milliseconds; nil when it isn't a time.
public func subtitleTime(_ raw: String) -> Int? {
    let t = raw.trimmingCharacters(in: .whitespaces)
    guard let sep = t.lastIndex(where: { $0 == "," || $0 == "." }) else { return nil }
    let frac = String(t[t.index(after: sep)...])
    let clock = t[..<sep].split(separator: ":", omittingEmptySubsequences: false).map(String.init)
    guard (1...3).contains(frac.count), frac.allSatisfy(\.isNumber), (2...3).contains(clock.count),
          clock.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }), clock.suffix(2).allSatisfy({ $0.count <= 2 }) else { return nil }
    let n = clock.compactMap { Int($0) }
    let (h, m, s) = n.count == 3 ? (n[0], n[1], n[2]) : (0, n[0], n[1])
    let ms = Int(frac.padding(toLength: 3, withPad: "0", startingAt: 0))!
    return ((h * 60 + m) * 60 + s) * 1000 + ms
}

private func cleanCue(_ text: String) -> String {
    var t = text.replacingOccurrences(of: "\\{[^}]*\\}", with: "", options: .regularExpression)
    t = t.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
    t = t.replacingOccurrences(of: "\\N", with: "\n").replacingOccurrences(of: "\\n", with: "\n")
    t = t.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&lt;", with: "<")
        .replacingOccurrences(of: "&gt;", with: ">").replacingOccurrences(of: "&nbsp;", with: " ")
    return t.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: "\n")
}

public func parseSubtitles(_ text: String, codec: String?) -> [Cue] {
    var body = normalizedLines(text).joined(separator: "\n")
    if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
    let c = (codec ?? "").lowercased()
    let ass = c == "ass" || c == "ssa" || body.range(of: "^\\[Script Info\\]", options: [.regularExpression, .anchored]) != nil || body.contains("\n[Script Info]")
    let cues = ass ? parseAss(body) : parseBlocks(body)
    return stableSorted(cues) { $0.startMs < $1.startMs || ($0.startMs == $1.startMs && $0.endMs < $1.endMs) }
}

/// SRT and WebVTT: blocks with a "start --> end" line, then the words.
private func parseBlocks(_ body: String) -> [Cue] {
    var out: [Cue] = []
    for block in body.components(separatedBy: "\n\n") {
        let lines = block.components(separatedBy: "\n")
        guard let at = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
        let parts = lines[at].components(separatedBy: "-->")
        guard parts.count >= 2, let start = subtitleTime(parts[0]),
              let end = subtitleTime(parts[1].trimmingCharacters(in: .whitespaces).components(separatedBy: " ").first ?? "") else { continue }
        let words = cleanCue(lines[(at + 1)...].joined(separator: "\n"))
        if end > start && !words.isEmpty { out.append(Cue(startMs: start, endMs: end, text: words)) }
    }
    return out
}

/// ASS/SSA: the Dialogue lines, in the order the Format line gives.
private func parseAss(_ body: String) -> [Cue] {
    var out: [Cue] = []
    var fields = ["layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"]
    var inEvents = false
    for line in body.components(separatedBy: "\n") {
        let t = line.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("[") && t.hasSuffix("]") { inEvents = t.lowercased() == "[events]"; continue }
        guard inEvents else { continue }
        if t.lowercased().hasPrefix("format:") {
            fields = t.dropFirst(7).split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            continue
        }
        guard t.lowercased().hasPrefix("dialogue:") else { continue }
        let parts = t.dropFirst(9).components(separatedBy: ",")
        func value(_ name: String) -> String { fields.firstIndex(of: name).flatMap { parts.indices.contains($0) ? parts[$0] : nil } ?? "" }
        guard let textAt = fields.firstIndex(of: "text"), let start = subtitleTime(value("start")), let end = subtitleTime(value("end")) else { continue }
        let words = cleanCue(parts.dropFirst(textAt).joined(separator: ","))
        if end > start && !words.isEmpty { out.append(Cue(startMs: start, endMs: end, text: words)) }
    }
    return out
}

/// What's on screen at [ms]: every cue showing then, top first, in one.
public func cueAt(_ cues: [Cue], _ ms: Int) -> String? {
    let on = cues.filter { $0.startMs <= ms && ms < $0.endMs }
    return on.isEmpty ? nil : on.map(\.text).joined(separator: "\n")
}

/// As the Fire TV offers them; 0.9 is its standard.
public let SUBTITLE_SIZES: [Double] = [0.7, 0.8, 0.9, 1.0, 1.2, 1.4]

/// What a title starts with: the subtitles Plex has on, or none but forced ones.
public func atStart(_ playback: PlexPlayback, _ setting: Prefs.SubtitlesAtStart) -> PlexPlayback {
    guard setting == .off, let on = playback.subtitleStreams.first(where: \.selected), !on.forced else { return playback }
    var p = playback
    p.subtitleStreams = p.subtitleStreams.map { var s = $0; s.selected = false; return s }
    return p
}

/// The subtitles Plex has on: text ones the app can draw, or ones only Plex's conversion can put in.
public func subtitlePlan(_ playback: PlexPlayback) -> (text: PlexSubtitle?, burn: Bool) {
    guard let on = playback.subtitleStreams.first(where: \.selected) else { return (nil, false) }
    let text = playback.subtitles.first { $0.id == on.id && isTextCodec($0.codec) }
    return (text, text == nil)
}

/// Lines, however they end: "\r\n" is one character to Swift, so it's looked for as such.
public func normalizedLines(_ text: String) -> [String] {
    text.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r\n" || $0 == "\r" }).map(String.init)
}
