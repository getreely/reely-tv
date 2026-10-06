import Foundation

public struct SearchState: Equatable, Sendable {
    public var query = ""
    public var busy = false
    public var results: [PlexItem] = []
    /// Plex's own guesses, when what was typed matched by name as well.
    public var more: [PlexItem] = []
    public var people: [PlexPerson] = []
    public var collections: [PlexItem] = []
    public var recent: [String] = []
    /// No server answered.
    public var unreachable = false
    /// People at the top only when the words are a person's name and no film or show's:
    /// most searches are for a title, and the people who were in it come last.
    public var peopleFirst = false
    public init() {}
}

/// A list of titles on a page of its own: an actor's, a collection's, a playlist's.
public struct ListPage: Equatable, Sendable {
    public var key: String
    public var items: [PlexItem] = []
    public var busy = true
    public var error: String?
}

let PEOPLE_RESULTS = 20
let WATCHLIST_ROW = 40

extension ReelyStore {
    /// The servers Home and search draw on, once each.
    func shownServers() -> [(String, String)] {
        var out = serversOf(shownLibraries()).map { ($0.base, $0.token) }
        if out.isEmpty, let b = plex.baseUrl, let t = plex.serverToken { out.append((b, t)) }
        return out
    }

    /// Movies, shows, people and collections, from every server, as the Fire TV searches.
    public func setQuery(_ query: String) async {
        searchRun += 1
        let run = searchRun
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            let recent = search.recent
            search = SearchState()
            search.recent = recent
            search.query = query
            return
        }
        search.query = query
        search.busy = true
        // Typing on a remote is slow: a pause, rather than a search for every letter.
        try? await Task.sleep(nanoseconds: 400_000_000)
        guard run == searchRun else { return }
        var answered: [PlexFound] = []
        for (base, token) in shownServers() {
            if let found = try? await api.searchAll(base, token, query: query) { answered.append(found) }
        }
        guard run == searchRun else { return }
        let on = iptvOn
        let fromIptv = on ? iptv.search(query, iptvWins: prefs.iptvWins) : []
        let plexFound = answered.flatMap(\.items).filter { !on || !iptv.hides($0, iptvWins: prefs.iptvWins) }
        let (matches, others) = splitResults(query, plexFound + fromIptv)
        var names = Set<String>()
        let people = answered.flatMap(\.people).filter { names.insert($0.name.lowercased()).inserted }.prefix(PEOPLE_RESULTS)
        var titles = Set<String>()
        let collections = answered.flatMap(\.collections).filter { titles.insert($0.title.lowercased()).inserted }
        search.busy = false
        // Nothing by name, a misspelling most likely: then Plex's own guesses are the results.
        search.results = matches.isEmpty ? others : matches
        search.more = matches.isEmpty ? [] : others
        search.people = Array(people)
        search.collections = collections
        search.unreachable = !shownServers().isEmpty && answered.isEmpty && fromIptv.isEmpty
        search.peopleFirst = matches.isEmpty && people.contains { namesAll($0.name, query) }
    }

    /// What was searched kept, when something it found is opened: a search that worked.
    public func rememberSearch() {
        search.recent = rememberedSearches(search.recent, search.query)
        store.setJson("recentSearches", search.recent)
    }

    public func clearRecentSearches() {
        search.recent = []
        store.setJson("recentSearches", [String]())
    }

    // MARK: Lists

    /// Everything they're in, from the libraries on the server they were found on, newest first.
    public func openPerson(id: String, serverBase: String?) async {
        let key = "person:\(serverBase ?? "")|\(id)"
        list = ListPage(key: key)
        let libraries = shownLibraries().filter { serverBase == nil || $0.baseUrl == plex.baseFor(serverBase) }
        var items: [PlexItem] = []
        var answered = false
        for l in libraries {
            if let found = try? await api.withActor(l.baseUrl, l.token, section: l.section.key, type: l.section.type == "movie" ? PLEX_TYPE_MOVIE : PLEX_TYPE_SHOW, personId: id) {
                answered = true
                items += found
            }
        }
        guard list?.key == key else { return }
        list = ListPage(key: key, items: stableSorted(items) { ($0.year ?? 0) > ($1.year ?? 0) }, busy: false,
                        error: !libraries.isEmpty && !answered ? "Couldn't reach your Plex server." : nil)
    }

    public func openCollection(ratingKey: String, serverBase: String?) async {
        await openList(key: "collection:\(serverBase ?? "")|\(ratingKey)", serverBase: serverBase) { base, token in
            try await self.api.collectionItems(base, token, ratingKey: ratingKey)
        }
    }

    public func openPlaylist(ratingKey: String, serverBase: String?) async {
        await openList(key: "playlist:\(serverBase ?? "")|\(ratingKey)", serverBase: serverBase) { base, token in
            try await self.api.playlistItems(base, token, ratingKey: ratingKey).filter(\.isPlayable)
        }
    }

    private func openList(key: String, serverBase: String?, load: (String, String) async throws -> [PlexItem]) async {
        list = ListPage(key: key)
        guard let base = plex.baseFor(serverBase), let token = plex.tokenFor(serverBase) else {
            list = ListPage(key: key, busy: false, error: "Couldn't reach the server this is on.")
            return
        }
        do {
            let items = try await load(base, token)
            if list?.key == key { list = ListPage(key: key, items: items, busy: false) }
        } catch {
            if list?.key == key { list = ListPage(key: key, busy: false, error: (error as? HttpError)?.message ?? "Couldn't load that.") }
        }
    }

    // MARK: Watchlist

    /// The account's Watchlist, and which of it the servers here have, in the Watchlist's own order.
    public func refreshWatchlist() async {
        guard let token = plex.token, let guids = try? await api.watchlist(token: token), plex.token == token else { return }
        if watchlistEdits == 0 { watchlist = Set(guids) }
        let servers = shownServers()
        var found: [PlexItem] = []
        for guid in guids.prefix(WATCHLIST_ROW) {
            for (base, t) in servers {
                if let item = try? await api.byGuid(base, t, guid: guid) { found.append(item); break }
            }
        }
        guard plex.token == token else { return }
        home.watchlist = found
    }

    /// On the Watchlist, or off it, for the title whose page this is; the button flips at once.
    public func toggleWatchlist() async {
        guard let guid = detail?.detail?.guid, let token = plex.token else { return }
        let on = !watchlist.contains(guid)
        watchlistEdits += 1
        if on { watchlist.insert(guid) } else { watchlist.remove(guid) }
        do {
            try await api.setWatchlisted(token: token, guid: guid, on: on)
            watchlistEdits -= 1
            await refreshWatchlist()
        } catch {
            watchlistEdits -= 1
            if on { watchlist.remove(guid) } else { watchlist.insert(guid) }
            if let key = detail?.key { setDetail(key) { $0.error = (error as? HttpError)?.message } }
        }
    }

    /// Gone from Continue Watching, as the poster's menu offers.
    public func removeFromContinueWatching(_ item: PlexItem) async {
        if item.isIptv {
            iptv.watch.forgetProgress(item.ratingKey)
            saveIptvWatch()
            composeHome()
            return
        }
        guard let base = plex.baseFor(item.serverBase), let token = plex.tokenFor(item.serverBase) else { return }
        try? await api.removeFromContinueWatching(base, token, ratingKey: item.ratingKey)
        home.continueWatching.removeAll { $0.id == item.id }
        await refreshHome()
    }

    /// A show's or season's next episode, played with the rest of its season after it.
    public func playNextEpisode(of item: PlexItem) async {
        if item.isIptv, let c = live.credentials {
            let showId: Int
            switch IptvKey.parse(item.ratingKey) {
            case .show(let id): showId = id
            case .season(let id, _): showId = id
            default: return
            }
            var info = iptv.cachedSeries(showId)
            if info == nil { info = await xtream.seriesInfo(c, id: showId) }
            guard let info else { return }
            iptv.keepSeries(showId, info)
            let name = item.type == "show" ? item.title : item.parentTitle ?? item.title
            var all = (info.seasons ?? []).flatMap { Vod.episodeItems(showId: showId, showName: name, poster: item.thumb, backdrop: nil, season: $0) }.map(iptv.marked)
            if item.type == "season" { all = all.filter { $0.parentRatingKey == item.ratingKey } }
            guard let next = PlexAPI.nextEpisode(all) else { return }
            await play(next, resume: true, queue: all.filter { $0.parentRatingKey == next.parentRatingKey })
            return
        }
        guard let base = plex.baseFor(item.serverBase), let token = plex.tokenFor(item.serverBase) else { return }
        let all: [PlexItem]
        if item.type == "season" {
            all = ((try? await api.children(base, token, ratingKey: item.ratingKey)) ?? []).filter { $0.type == "episode" }
        } else {
            all = (try? await api.episodes(base, token, of: item.ratingKey)) ?? []
        }
        guard let next = PlexAPI.nextEpisode(all) else { return }
        let season = all.filter { $0.parentRatingKey == next.parentRatingKey }
        await play(next, resume: true, queue: season)
    }

    /// A playlist, from the top, or shuffled.
    public func playList(shuffled: Bool) async {
        guard let items = list?.items, !items.isEmpty else { return }
        let queue = shuffled ? items.shuffled() : items
        await play(queue[0], resume: false, queue: queue)
    }
}
