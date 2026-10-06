import Foundation

/**
 * Every setting the Fire TV app keeps (Settings.kt), by the same names and defaults. The
 * Fire TV's built-in updater has no place here; Apple's App Store does that.
 */
public struct Prefs: Codable, Equatable, Sendable {
    public enum PlaybackMode: String, Codable, Sendable, CaseIterable { case auto, direct, transcode }
    public enum SubtitlesAtStart: String, Codable, Sendable { case plex, off }
    public enum StreamFormat: String, Codable, Sendable { case m3u8, ts }
    public enum MultiviewLayout: String, Codable, Sendable { case grid, focus }

    /// Automatic: the file as it is when this device can play it.
    public var playbackMode: PlaybackMode = .auto
    /// The most Plex sends when it converts; 0 for as good as the file.
    public var maxBitrateKbps: Int = 0
    public var skipIntros: Bool = false
    public var skipCredits: Bool = false
    /// Up Next's count before the next episode plays; 0 waits to be asked.
    public var upNextSeconds: Int = 12
    public var largerBuffer: Bool = false
    public var matchFrameRate: Bool = true
    public var subtitleScale: Double = 0.9
    public var subtitleBackground: Bool = false
    public var subtitlesAtStart: SubtitlesAtStart = .plex
    /// Home's rows switched off, by id.
    public var hiddenHomeRows: [String] = []
    /// The libraries switched on, as "server|section": the only ones on Home, searched and in the menus.
    public var favouriteSections: [String] = []
    public var iptvLibrary: Bool = false
    public var iptvWins: Bool = false
    public var iptvInMenus: Bool = true
    public var screensaverMinutes: Int = 3
    public var tourSeen: Bool = false
    public var accent: String = "blue"
    public var themeMusic: Bool = false
    public var themeVolume: Double = 0.10
    public var guidePreview: Bool = true
    public var streamFormat: StreamFormat = .m3u8
    public var multiviewLayout: MultiviewLayout = .grid

    public init() {}

    public init(from decoder: Decoder) throws {
        // A setting added since these were saved takes its default, rather than losing them all.
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Prefs()
        playbackMode = (try? c.decode(PlaybackMode.self, forKey: .playbackMode)) ?? d.playbackMode
        maxBitrateKbps = (try? c.decode(Int.self, forKey: .maxBitrateKbps)) ?? d.maxBitrateKbps
        skipIntros = (try? c.decode(Bool.self, forKey: .skipIntros)) ?? d.skipIntros
        skipCredits = (try? c.decode(Bool.self, forKey: .skipCredits)) ?? d.skipCredits
        upNextSeconds = (try? c.decode(Int.self, forKey: .upNextSeconds)) ?? d.upNextSeconds
        largerBuffer = (try? c.decode(Bool.self, forKey: .largerBuffer)) ?? d.largerBuffer
        matchFrameRate = (try? c.decode(Bool.self, forKey: .matchFrameRate)) ?? d.matchFrameRate
        subtitleScale = (try? c.decode(Double.self, forKey: .subtitleScale)) ?? d.subtitleScale
        subtitleBackground = (try? c.decode(Bool.self, forKey: .subtitleBackground)) ?? d.subtitleBackground
        subtitlesAtStart = (try? c.decode(SubtitlesAtStart.self, forKey: .subtitlesAtStart)) ?? d.subtitlesAtStart
        hiddenHomeRows = (try? c.decode([String].self, forKey: .hiddenHomeRows)) ?? d.hiddenHomeRows
        favouriteSections = (try? c.decode([String].self, forKey: .favouriteSections)) ?? d.favouriteSections
        iptvLibrary = (try? c.decode(Bool.self, forKey: .iptvLibrary)) ?? d.iptvLibrary
        iptvWins = (try? c.decode(Bool.self, forKey: .iptvWins)) ?? d.iptvWins
        iptvInMenus = (try? c.decode(Bool.self, forKey: .iptvInMenus)) ?? d.iptvInMenus
        screensaverMinutes = (try? c.decode(Int.self, forKey: .screensaverMinutes)) ?? d.screensaverMinutes
        tourSeen = (try? c.decode(Bool.self, forKey: .tourSeen)) ?? d.tourSeen
        accent = (try? c.decode(String.self, forKey: .accent)) ?? d.accent
        themeMusic = (try? c.decode(Bool.self, forKey: .themeMusic)) ?? d.themeMusic
        themeVolume = (try? c.decode(Double.self, forKey: .themeVolume)) ?? d.themeVolume
        guidePreview = (try? c.decode(Bool.self, forKey: .guidePreview)) ?? d.guidePreview
        streamFormat = (try? c.decode(StreamFormat.self, forKey: .streamFormat)) ?? d.streamFormat
        multiviewLayout = (try? c.decode(MultiviewLayout.self, forKey: .multiviewLayout)) ?? d.multiviewLayout
    }
}

/** Home's rows, in order, as Settings lists them to switch off: the Fire TV's, by its ids. */
public enum HomeRow: String, CaseIterable, Sendable {
    case continueWatching = "continue"
    case recentEpisodes = "episodes"
    case recentMovies = "movies"
    case watchlist = "watchlist"
    case playlists = "playlists"
    case iptvMovies = "iptvMovies"
    case iptvShows = "iptvShows"
    case trending = "trending"
    case popular = "popular"

    public var title: String {
        switch self {
        case .continueWatching: return "Continue Watching"
        case .recentEpisodes: return "Recently Added Episodes"
        case .recentMovies: return "Recently Added Movies"
        case .watchlist: return "Watchlist"
        case .playlists: return "Playlists"
        case .iptvMovies: return "New Movies on IPTV"
        case .iptvShows: return "New Shows on IPTV"
        case .trending: return "Trending"
        case .popular: return "Popular"
        }
    }

    /// Reely's own rows, from Requests.
    public var fromReely: Bool { self == .trending || self == .popular }
}

/** The colours Settings offers, by id, as the Fire TV has them (AccentChoice). */
public struct Accent: Sendable, Equatable {
    public let id: String
    public let name: String
    public let color: UInt32
    /// What's written on it.
    public let on: UInt32
    public static let all: [Accent] = [
        Accent(id: "blue", name: "Blue", color: 0x2E6BFF, on: 0xFFFFFF),
        Accent(id: "red", name: "Red", color: 0xFF5E69, on: 0x08090B),
        Accent(id: "purple", name: "Purple", color: 0x8B5CF6, on: 0xFFFFFF),
        Accent(id: "pink", name: "Pink", color: 0xEC4899, on: 0xFFFFFF),
        Accent(id: "orange", name: "Orange", color: 0xFF8A3D, on: 0x08090B),
        Accent(id: "gold", name: "Gold", color: 0xF5C542, on: 0x08090B),
        Accent(id: "teal", name: "Teal", color: 0x14B8A6, on: 0x08090B),
    ]
    public static func of(_ id: String?) -> Accent { all.first { $0.id == id } ?? all[0] }
}
