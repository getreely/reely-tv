import Foundation

/// The badges that say what a file is: resolution, HDR, sound. As the Android app has them.
public func qualityBadges(_ resolution: String?, _ audioChannels: Int, dolbyVision: Bool = false, transfer: String? = nil) -> [String] {
    let r = resolution?.lowercased()
    let size: String? = r == "4k" || r == "2160" ? "4K" : r == "1080" || r == "720" ? "HD" : r == "sd" || r == "576" || r == "480" ? "SD" : nil
    let range = hdrName(dolbyVision: dolbyVision, transfer: transfer)
    let sound: String? = audioChannels >= 8 ? "7.1" : audioChannels >= 6 ? "5.1" : audioChannels == 2 ? "Stereo" : audioChannels == 1 ? "Mono" : nil
    return [size, range, sound].compactMap { $0 }
}

/// "4K Dolby Vision", "1080p": one of several copies of a title.
public func versionLabel(_ resolution: String?, dolbyVision: Bool = false, transfer: String? = nil) -> String {
    let r = resolution?.lowercased() ?? ""
    let size: String
    if r.isEmpty { size = "Other" }
    else if r == "4k" || r == "2160" { size = "4K" }
    else if r == "sd" { size = "SD" }
    else if r.allSatisfy(\.isNumber) { size = "\(r)p" }
    else { size = r.uppercased() }
    return [size, hdrName(dolbyVision: dolbyVision, transfer: transfer)].compactMap { $0 }.joined(separator: " ")
}

/// "HEVC · TrueHD 7.1 · 42 Mbps · 58.1 GB".
public func versionDetail(_ videoCodec: String?, _ audioCodec: String?, _ audioChannels: Int, _ bitrateKbps: Int, _ sizeBytes: Int64) -> String? {
    let videos = ["hevc": "HEVC", "h265": "HEVC", "h264": "H.264", "mpeg2video": "MPEG-2", "mpeg4": "MPEG-4", "vc1": "VC-1"]
    let audios = ["truehd": "TrueHD", "eac3": "Dolby Digital Plus", "ac3": "Dolby Digital", "dca": "DTS", "dts": "DTS",
                  "dca-ma": "DTS-HD MA", "dts-hd ma": "DTS-HD MA", "aac": "AAC", "flac": "FLAC", "opus": "Opus", "mp3": "MP3"]
    let v = videoCodec?.lowercased() ?? ""
    let video: String? = v.isEmpty ? nil : videos[v] ?? videoCodec!.uppercased()
    let a = audioCodec?.lowercased() ?? ""
    let name: String? = a.isEmpty ? nil : audios[a] ?? audioCodec!.uppercased()
    let layout: String? = audioChannels >= 8 ? "7.1" : audioChannels >= 6 ? "5.1" : audioChannels == 2 ? "Stereo" : nil
    let sound = name.map { [$0, layout].compactMap { $0 }.joined(separator: " ") }
    let rate: String? = bitrateKbps > 0 ? (bitrateKbps >= 1000 ? "\((bitrateKbps + 500) / 1000) Mbps" : "\(bitrateKbps) kbps") : nil
    let size: String? = sizeBytes > 0 ? (sizeBytes >= 1_000_000_000 ? String(format: "%.1f GB", Double(sizeBytes) / 1e9) : "\(sizeBytes / 1_000_000) MB") : nil
    let text = [video, sound, rate, size].compactMap { $0 }.joined(separator: " · ")
    return text.isEmpty ? nil : text
}

private func hdrName(dolbyVision: Bool, transfer: String?) -> String? {
    if dolbyVision { return "Dolby Vision" }
    switch transfer?.lowercased() {
    case "smpte2084": return "HDR10"
    case "arib-std-b67": return "HLG"
    default: return nil
    }
}

/// "2h 18m", "42m".
public func formatDuration(_ ms: Int) -> String {
    guard ms > 0 else { return "" }
    let total = ms / 60_000
    return total >= 60 ? "\(total / 60)h \(total % 60)m" : "\(total)m"
}

/// "Sep 16, 2008" from "2008-09-16"; nil for anything that isn't a whole date.
public func formatAirDate(_ date: String?, locale: Locale = Locale(identifier: "en_US")) -> String? {
    guard let date, date.count >= 10 else { return nil }
    let text = String(date.prefix(10))
    let parts = text.split(separator: "-")
    guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
          let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]) else { return nil }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    let components = DateComponents(year: y, month: m, day: d)
    guard components.isValidDate(in: calendar), let when = calendar.date(from: components) else { return nil }
    let f = DateFormatter()
    f.locale = locale
    f.timeZone = calendar.timeZone
    f.setLocalizedDateFormatFromTemplate("MMM d, yyyy")
    return f.string(from: when)
}

/// Sorting that keeps equal entries in the order they came.
public func stableSorted<T>(_ list: [T], by before: (T, T) -> Bool) -> [T] {
    list.enumerated().sorted { a, b in
        if before(a.element, b.element) { return true }
        if before(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map(\.element)
}
