import Foundation

/*
 * Plex, as the Fire TV app reads it (PlexApi.kt) and the LG app after it (plex.ts): the
 * same fields, read the same way, so the apps agree about every title.
 */

public struct PlexItem: Equatable, Hashable, Sendable, Identifiable {
    public var ratingKey: String
    public var title: String
    public var titleSort: String?
    public var type: String
    public var thumb: String?
    public var art: String?
    public var summary: String?
    public var year: Int?
    public var index: Int?
    public var parentIndex: Int?
    public var parentRatingKey: String?
    public var parentTitle: String?
    public var grandparentRatingKey: String?
    public var grandparentTitle: String?
    public var grandparentThumb: String?
    public var durationMs: Int
    public var viewOffsetMs: Int
    public var leafCount: Int
    public var viewedLeafCount: Int
    public var viewCount: Int
    public var addedAt: Int
    public var lastViewedAt: Int
    public var logo: String?
    public var qualities: [String]
    public var airDate: String?
    public var librarySectionId: String?
    /// Which server it's from. Rows hold several servers' titles at once.
    public var serverBase: String?

    public init(
        ratingKey: String, title: String = "", titleSort: String? = nil, type: String = "movie", thumb: String? = nil,
        art: String? = nil, summary: String? = nil, year: Int? = nil, index: Int? = nil, parentIndex: Int? = nil,
        parentRatingKey: String? = nil, parentTitle: String? = nil, grandparentRatingKey: String? = nil,
        grandparentTitle: String? = nil, grandparentThumb: String? = nil, durationMs: Int = 0, viewOffsetMs: Int = 0,
        leafCount: Int = 0, viewedLeafCount: Int = 0, viewCount: Int = 0, addedAt: Int = 0, lastViewedAt: Int = 0,
        logo: String? = nil, qualities: [String] = [], airDate: String? = nil, librarySectionId: String? = nil,
        serverBase: String? = nil
    ) {
        self.ratingKey = ratingKey; self.title = title; self.titleSort = titleSort; self.type = type
        self.thumb = thumb; self.art = art; self.summary = summary; self.year = year; self.index = index
        self.parentIndex = parentIndex; self.parentRatingKey = parentRatingKey; self.parentTitle = parentTitle
        self.grandparentRatingKey = grandparentRatingKey; self.grandparentTitle = grandparentTitle
        self.grandparentThumb = grandparentThumb; self.durationMs = durationMs; self.viewOffsetMs = viewOffsetMs
        self.leafCount = leafCount; self.viewedLeafCount = viewedLeafCount; self.viewCount = viewCount
        self.addedAt = addedAt; self.lastViewedAt = lastViewedAt; self.logo = logo; self.qualities = qualities
        self.airDate = airDate; self.librarySectionId = librarySectionId; self.serverBase = serverBase
    }

    /// Unique across servers, which a rating key is not.
    public var id: String { (serverBase ?? "") + "|" + ratingKey }
    public var isPlayable: Bool { type == "movie" || type == "episode" }

    public var isWatched: Bool {
        if type == "movie" || type == "episode" { return viewCount > 0 }
        if type == "show" || type == "season" { return leafCount > 0 && viewedLeafCount >= leafCount }
        return false
    }

    /// Where it was left, as a fraction; nil when it hasn't been started.
    public var resumeFraction: Double? {
        guard viewOffsetMs > 0, durationMs > 0 else { return nil }
        return min(1, max(0, Double(viewOffsetMs) / Double(durationMs)))
    }

    /// "S2 · E7", a year, an episode count.
    public var caption: String? {
        switch type {
        case "episode":
            let text = [parentIndex.map { "S\($0)" }, index.map { "E\($0)" }].compactMap { $0 }.joined(separator: " · ")
            return text.isEmpty ? nil : text
        case "season":
            return leafCount > 0 ? "\(leafCount) episodes" : nil
        case "collection":
            return leafCount > 0 ? (leafCount == 1 ? "1 title" : "\(leafCount) titles") : nil
        default:
            return year.map(String.init)
        }
    }

    /// The show for an episode, else the title.
    public var rowTitle: String { type == "episode" ? (grandparentTitle ?? title) : title }
}

public struct PlexRole: Equatable, Hashable, Sendable {
    public var name: String
    public var role: String?
    public var thumb: String?
    public var id: String?
}

public struct PlexVersion: Equatable, Hashable, Sendable {
    public var label: String
    public var detail: String?
}

public struct PlexDetail: Equatable, Sendable {
    public var ratingKey: String
    public var type: String
    public var title: String
    public var summary: String?
    public var tagline: String?
    public var year: Int?
    public var durationMs: Int
    public var viewOffsetMs: Int
    public var contentRating: String?
    public var rating: Double?
    public var audienceRating: Double?
    public var airDate: String?
    public var viewCount: Int
    public var viewedLeafCount: Int
    public var studio: String?
    public var thumb: String?
    public var art: String?
    public var theme: String?
    public var genres: [String]
    public var directors: [String]
    public var roles: [PlexRole]
    public var writers: [String]
    public var childCount: Int
    public var leafCount: Int
    public var grandparentTitle: String?
    public var index: Int?
    public var parentIndex: Int?
    public var logo: String?
    public var qualities: [String]
    public var versions: [PlexVersion]
    public var guid: String?
    public var onDeckKey: String?
    public var onDeckSeasonKey: String?

    public var isShow: Bool { type == "show" }

    /// "2014  ·  2h 18m  ·  TV-MA  ·  Studio".
    public var facts: String {
        let minutes = durationMs > 0 ? durationMs / 60_000 : 0
        let duration: String? = minutes > 0 ? (minutes >= 60 ? "\(minutes / 60)h \(minutes % 60)m" : "\(minutes)m") : nil
        return [year.map(String.init), duration, contentRating, studio].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ·  ")
    }
}

public struct PlexServer: Equatable, Hashable, Sendable {
    public var name: String
    public var accessToken: String
    public var connections: [String]
    public var owned: Bool
    public init(name: String, accessToken: String, connections: [String], owned: Bool) {
        self.name = name; self.accessToken = accessToken; self.connections = connections; self.owned = owned
    }
}

public struct PlexSection: Equatable, Hashable, Sendable, Codable {
    public var key: String
    public var title: String
    public var type: String
    public init(key: String, title: String, type: String) { self.key = key; self.title = title; self.type = type }
}

public struct PlexGenre: Equatable, Hashable, Sendable {
    public var id: String
    public var title: String
}

public struct PlexLetter: Equatable, Hashable, Sendable {
    public var letter: String
    public var count: Int
}

public struct PlexHomeUser: Equatable, Hashable, Sendable, Codable {
    public var uuid: String
    public var title: String
    public var thumb: String?
    public var protected: Bool
    public var admin: Bool
    public var restricted: Bool
}

public struct PlexPin: Equatable, Sendable {
    public var id: Int
    public var code: String
}

public struct PlexMarker: Equatable, Sendable {
    public var type: String
    public var startMs: Int
    public var endMs: Int
}

public struct PlexChapter: Equatable, Sendable {
    public var title: String
    public var startMs: Int
    public var endMs: Int
    public var thumbUrl: String?
}

public struct PlexSubtitle: Equatable, Sendable {
    public var id: String
    public var label: String
    public var url: String
    public var codec: String
    public var language: String?
}

public struct PlexStream: Equatable, Sendable {
    public var id: String
    public var language: String?
    public var selected: Bool
    public var external: Bool
    /// Only the parts in another language: shown even with subtitles off.
    public var forced: Bool
    public var label: String
}

public struct PlexPlayback: Equatable, Sendable {
    public var url: String
    public var subtitles: [PlexSubtitle]
    public var markers: [PlexMarker]
    public var audioCodec: String?
    public var audioChannels: Int
    public var previewUrl: String?
    public var chapters: [PlexChapter]
    public var partId: Int?
    public var audioStreams: [PlexStream]
    public var subtitleStreams: [PlexStream]
    public var container: String?
    public var videoCodec: String?
}

public struct PlexExtra: Equatable, Sendable {
    public var ratingKey: String
    public var title: String
    public var durationMs: Int
}

public struct PlexPerson: Equatable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var thumb: String?
    public var serverBase: String?
}

public struct PlexFound: Equatable, Sendable {
    public var items: [PlexItem]
    public var people: [PlexPerson]
    public var collections: [PlexItem]
}

public struct PlexOnlineSubtitle: Equatable, Sendable {
    public var key: String
    public var title: String
    public var provider: String?
    public var language: String?
    public var codec: String?
    public var hearingImpaired: Bool
    public var forced: Bool
}

public struct PlexIndexEntry: Equatable, Sendable {
    public var ratingKey: String
    public var serverBase: String?
    public var title: String
    public var originalTitle: String?
    public var year: Int?
    public var guids: [String]
}

public struct PlexConnection: Equatable, Sendable {
    public var uri: String
    public var address: String
    public var port: Int
    public var local: Bool
    public var relay: Bool
    public init(uri: String, address: String, port: Int, local: Bool, relay: Bool) {
        self.uri = uri; self.address = address; self.port = port; self.local = local; self.relay = relay
    }
}
