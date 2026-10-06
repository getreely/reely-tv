import Foundation

/*
 * Home as the Fire TV app builds it (ReelyViewModel.refreshHome): Continue Watching from
 * every server, most recently watched first; new episodes gathered onto their show; new
 * films; playlists. The same rules, so the apps show the same rows.
 */

/** A library on one server, with what's needed to ask it things. */
public struct LibraryChoice: Equatable, Hashable, Sendable, Identifiable {
    public var serverName: String
    public var baseUrl: String
    public var token: String
    public var section: PlexSection
    public init(serverName: String, baseUrl: String, token: String, section: PlexSection) {
        self.serverName = serverName; self.baseUrl = baseUrl; self.token = token; self.section = section
    }
    /// Section keys are only unique within a server, so the server is in this.
    public var id: String { "\(serverName)|\(section.key)" }
}

/** Several episodes of one show arriving at once, shown as the show with a count. */
public struct EpisodeGroup: Equatable, Sendable, Identifiable {
    public var showTitle: String
    public var showRatingKey: String?
    public var thumb: String?
    public var newest: PlexItem
    public var count: Int
    public var addedAt: Int
    public var librarySectionId: String?
    public var serverBase: String?
    public var id: String { (serverBase ?? "") + "|" + (showRatingKey ?? showTitle) }
}

public struct HomeRows: Equatable, Sendable {
    public var continueWatching: [PlexItem] = []
    public var recentEpisodes: [EpisodeGroup] = []
    public var recentMovies: [PlexItem] = []
    public var playlists: [PlexItem] = []
    public var watchlist: [PlexItem] = []
    public var iptvMovies: [PlexItem] = []
    public var iptvShows: [PlexItem] = []
    public init() {}
    public var isEmpty: Bool {
        continueWatching.isEmpty && recentEpisodes.isEmpty && recentMovies.isEmpty && playlists.isEmpty && watchlist.isEmpty
    }
}

public let TARGET_SHOW_COUNT = 30
public let EPISODE_PAGE = 200
public let MAX_EPISODE_SCAN = 2_000

/// Episodes, newest first, folded onto their shows: the newest kept, the rest a tally.
public func foldEpisodes(_ groups: inout [String: EpisodeGroup], order: inout [String], page: [PlexItem], base: String, sectionKey: String) {
    for episode in page {
        let key = base + "|" + (episode.grandparentRatingKey ?? episode.ratingKey)
        if groups[key] != nil {
            groups[key]!.count += 1
        } else {
            order.append(key)
            groups[key] = EpisodeGroup(
                showTitle: episode.grandparentTitle ?? episode.title, showRatingKey: episode.grandparentRatingKey ?? episode.ratingKey,
                thumb: episode.grandparentThumb ?? episode.thumb, newest: episode, count: 1, addedAt: episode.addedAt,
                librarySectionId: episode.librarySectionId ?? sectionKey, serverBase: episode.serverBase)
        }
    }
}

/// The row measured in shows, paged until there are enough: one big import mustn't fill it.
public func recentEpisodeGroups(_ plex: PlexAPI, _ sources: [LibraryChoice]) async -> [EpisodeGroup] {
    var groups: [String: EpisodeGroup] = [:]
    var order: [String] = []
    for source in sources {
        var offset = 0
        var scanned = 0
        while groups.count < TARGET_SHOW_COUNT && scanned < MAX_EPISODE_SCAN {
            guard let page = try? await plex.recentlyAdded(source.baseUrl, source.token, section: source.section.key, type: PLEX_TYPE_EPISODE, limit: EPISODE_PAGE, offset: offset),
                  !page.isEmpty else { break }
            foldEpisodes(&groups, order: &order, page: page, base: source.baseUrl, sectionKey: source.section.key)
            scanned += page.count
            offset += page.count
            if page.count < EPISODE_PAGE { break }
        }
    }
    return Array(stableSorted(order.compactMap { groups[$0] }) { $0.addedAt > $1.addedAt }.prefix(TARGET_SHOW_COUNT))
}

/// Every server Home draws on, once each.
public func serversOf(_ sources: [LibraryChoice]) -> [(base: String, token: String)] {
    var seen = Set<String>()
    var out: [(String, String)] = []
    for s in sources where seen.insert(s.baseUrl + "|" + s.token).inserted { out.append((s.baseUrl, s.token)) }
    return out
}

/**
 * Home's Plex rows. Nil when no server answered at all: the rows on screen are left as they
 * are, rather than wiped blank by a moment's lost connection.
 */
public func loadHome(_ plex: PlexAPI, _ sources: [LibraryChoice]) async -> HomeRows? {
    let servers = serversOf(sources)
    var answered = false
    var continuing: [PlexItem] = []
    for (base, token) in servers {
        if let items = try? await plex.continueWatching(base, token) {
            answered = true
            continuing += items
        }
    }
    guard answered else { return nil }
    var playlists: [PlexItem] = []
    for (base, token) in servers { playlists += (try? await plex.playlists(base, token)) ?? [] }
    var movies: [PlexItem] = []
    for s in sources where s.section.type == "movie" {
        movies += (try? await plex.recentlyAdded(s.baseUrl, s.token, section: s.section.key, type: PLEX_TYPE_MOVIE, limit: 40)) ?? []
    }
    var rows = HomeRows()
    rows.continueWatching = Array(continueWatchingOrder(continuing).prefix(40))
    rows.recentEpisodes = await recentEpisodeGroups(plex, sources.filter { $0.section.type == "show" })
    rows.recentMovies = Array(stableSorted(movies) { $0.addedAt > $1.addedAt }.prefix(40))
    rows.playlists = playlists
    return rows
}
