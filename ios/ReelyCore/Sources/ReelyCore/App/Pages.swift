import Foundation

/// A tab's library: which one, its grid as far as it's been read, and how it's ordered and narrowed.
public struct Browse: Equatable, Sendable {
    public enum View: String, Sendable { case home, grid, collections }
    public var choice: LibraryChoice?
    public var items: [PlexItem] = []
    public var total = 0
    public var busy = false
    public var sort = "titleSort:asc"
    public var error: String?
    public var unwatched = false
    public var genre: PlexGenre?
    public var decade: PlexGenre?
    public var genres: [PlexGenre] = []
    public var decades: [PlexGenre] = []
    /// How many titles start with each letter, in the order shown: the A–Z jump.
    public var letters: [PlexLetter] = []
    public var view: View = .home
    /// The library's newest releases, for the tab's home.
    public var released: [PlexItem] = []
    /// Newest to this library, for the tab's home: films, or episodes gathered on their show.
    public var added: [PlexItem] = []
    public var addedShows: [EpisodeGroup] = []
    /// The library's collections; nil until they're in.
    public var collections: [PlexItem]?
    public init() {}

    /// The narrowing asked of Plex, as query parameters.
    public var filters: String {
        (unwatched ? "&unwatched=1" : "") + (genre.map { "&genre=\(encodeComponent($0.id))" } ?? "") + (decade.map { "&decade=\(encodeComponent($0.id))" } ?? "")
    }
}

/// The orders a library offers, as the Fire TV names them.
public let LIBRARY_SORTS: [(id: String, label: String)] = [
    ("titleSort:asc", "A–Z"), ("addedAt:desc", "Recently added"), ("originallyAvailableAt:desc", "Newest releases"), ("rating:desc", "Critic rating"),
]

public let GRID_PAGE = 120

/// A title's page: the title, and for a show its seasons and the episodes of the one chosen.
public struct DetailPage: Equatable, Sendable {
    public var key: String
    public var serverBase: String?
    public var detail: PlexDetail?
    public var seasons: [PlexItem] = []
    public var season: PlexItem?
    public var episodes: [PlexItem] = []
    /// The episode the page is about: the one it was opened on, else the one you're up to.
    public var focused: PlexItem?
    public var related: [PlexItem] = []
    public var trailers: [PlexExtra] = []
    /// Which of several copies of the title plays.
    public var versionIndex = 0
    public var busy = true
    public var error: String?
}

/**
 * Whether Apple's player plays a file as it is, or Plex converts it to HLS. Apple's player
 * opens MP4, M4V and MOV with H.264 or HEVC and AAC, Dolby Digital (Plus), MP3, ALAC or
 * FLAC; anything else (an MKV, DTS, TrueHD) Plex converts, as the Fire TV has it do when
 * its own decoders say no.
 */
public struct PlaybackPlan: Equatable, Sendable {
    public var direct: Bool
    /// Why it's being converted, for Playback info.
    public var reason: String?

    public static let containers: Set = ["mp4", "m4v", "mov"]
    public static let video: Set = ["h264", "hevc", "h265"]
    public static let audio: Set = ["aac", "ac3", "eac3", "mp3", "alac", "flac"]

    public static func plan(_ p: PlexPlayback, mode: Prefs.PlaybackMode) -> PlaybackPlan {
        switch mode {
        case .transcode: return PlaybackPlan(direct: false, reason: "Settings ask Plex to convert everything")
        case .direct: return PlaybackPlan(direct: true, reason: nil)
        case .auto: break
        }
        guard let container = p.container, containers.contains(container) else {
            return PlaybackPlan(direct: false, reason: "This device can't open \(p.container?.uppercased() ?? "these") files")
        }
        if let v = p.videoCodec, !video.contains(v) { return PlaybackPlan(direct: false, reason: "This device can't play \(v.uppercased()) video") }
        if let a = p.audioCodec, !audio.contains(a) { return PlaybackPlan(direct: false, reason: "This device can't play \(a.uppercased()) sound") }
        return PlaybackPlan(direct: true, reason: nil)
    }
}

/// Near enough the end is watched, as Plex counts it.
public let WATCHED_AT = 0.9
/// How often where playback is gets told to the server.
public let REPORT_EVERY_SECONDS = 10.0
