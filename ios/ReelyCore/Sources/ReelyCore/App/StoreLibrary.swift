import Foundation

/*
 * The Movies and TV Shows tabs, and a title's page: the LG app's (and the Fire TV's) rules,
 * read the same way.
 */
extension ReelyStore {
    func setBrowse(_ kind: String, _ change: (inout Browse) -> Void) {
        var b = browse[kind] ?? Browse()
        change(&b)
        browse[kind] = b
    }

    /// The libraries in a tab's menu: those of its kind switched on, else all of its kind.
    public func libraries(of kind: String) -> [LibraryChoice] {
        shownLibraries(kind: kind) + (iptvOn && prefs.iptvInMenus ? [iptvChoice(kind)] : [])
    }

    public func openLibrary(_ kind: String, _ choice: LibraryChoice? = nil) async {
        guard let target = choice ?? browse[kind]?.choice ?? libraries(of: kind).first else { return }
        let current = browse[kind] ?? Browse()
        if current.choice == target && !current.items.isEmpty { return }
        let switching = current.choice != target
        setBrowse(kind) { b in
            b.choice = target; b.items = []; b.busy = true; b.error = nil
            // Another library's genres and decades aren't this one's.
            if switching { b.genre = nil; b.decade = nil; b.genres = []; b.decades = []; b.letters = []; b.released = []; b.added = []; b.addedShows = []; b.collections = nil }
        }
        // The provider's titles: all here already.
        if target.baseUrl == IPTV_SOURCE { loadIptvGrid(kind); return }
        let type = kind == "movie" ? PLEX_TYPE_MOVIE : PLEX_TYPE_SHOW
        Task {
            let genres = (try? await api.genres(target.baseUrl, target.token, section: target.section.key, type: type)) ?? []
            let decades = (try? await api.decades(target.baseUrl, target.token, section: target.section.key, type: type)) ?? []
            if browse[kind]?.choice == target { setBrowse(kind) { $0.genres = genres; $0.decades = decades } }
        }
        Task { await loadLetters(kind) }
        Task {
            // The tab's own home: this library's newest releases, and its collections.
            let released = (try? await api.items(target.baseUrl, target.token, "/library/sections/\(target.section.key)/all?type=\(type)&sort=originallyAvailableAt:desc", limit: 40)) ?? []
            let collections = (try? await api.collections(target.baseUrl, target.token, section: target.section.key)) ?? []
            if browse[kind]?.choice == target { setBrowse(kind) { $0.released = released; $0.collections = collections } }
        }
        Task {
            // What's newly arrived, asked of this library itself: Home's row is the newest
            // across every library, which may have none of this one's.
            if kind == "movie" {
                let added = (try? await api.recentlyAdded(target.baseUrl, target.token, section: target.section.key, type: PLEX_TYPE_MOVIE, limit: 40)) ?? []
                if browse[kind]?.choice == target { setBrowse(kind) { $0.added = added } }
            } else {
                let groups = await recentEpisodeGroups(api, [target])
                if browse[kind]?.choice == target { setBrowse(kind) { $0.addedShows = groups } }
            }
        }
        await loadMore(kind)
    }

    public func setLibraryView(_ kind: String, _ view: Browse.View) { setBrowse(kind) { $0.view = view } }

    public func setSort(_ kind: String, _ sort: String) async {
        setBrowse(kind) { $0.sort = sort; $0.items = []; $0.busy = true }
        await loadMore(kind)
    }

    /// Unwatched only, a genre, a decade: the grid again from the top, narrowed.
    public func setFilter(_ kind: String, unwatched: Bool? = nil, genre: PlexGenre?? = nil, decade: PlexGenre?? = nil) async {
        setBrowse(kind) { b in
            if let unwatched { b.unwatched = unwatched }
            if let genre { b.genre = genre }
            if let decade { b.decade = decade }
            b.items = []; b.total = 0; b.busy = true; b.error = nil
        }
        Task { await loadLetters(kind) }
        await loadMore(kind)
    }

    func loadLetters(_ kind: String) async {
        guard let b = browse[kind], let choice = b.choice, choice.baseUrl != IPTV_SOURCE else { return }
        let filters = b.filters
        let letters = (try? await api.firstCharacters(choice.baseUrl, choice.token, section: choice.section.key,
                                                       type: kind == "movie" ? PLEX_TYPE_MOVIE : PLEX_TYPE_SHOW, filters: filters)) ?? []
        if let now = browse[kind], now.choice == choice, now.filters == filters { setBrowse(kind) { $0.letters = letters } }
    }

    /// Where a letter starts in the A–Z grid, loading down to it first: its place, or nil.
    public func jumpTo(_ kind: String, letter: String) async -> Int? {
        guard let b = browse[kind], let at = b.letters.firstIndex(where: { $0.letter == letter }), b.sort == "titleSort:asc" else { return nil }
        let offset = b.letters[..<at].reduce(0) { $0 + $1.count }
        for _ in 0..<100 {
            guard let now = browse[kind] else { return nil }
            if now.items.count > offset { return offset }
            if now.total <= now.items.count && !now.busy && !now.items.isEmpty { return min(offset, now.items.count - 1) }
            let before = now.items.count
            await loadMore(kind)
            if browse[kind]?.items.count == before { return nil }
        }
        return nil
    }

    /// The next page of the grid, in before it's reached.
    public func loadMore(_ kind: String) async {
        guard let b = browse[kind], let choice = b.choice else { return }
        if choice.baseUrl == IPTV_SOURCE { if b.items.isEmpty || b.busy { loadIptvGrid(kind) }; return }
        let offset = b.items.count
        let type = kind == "movie" ? PLEX_TYPE_MOVIE : PLEX_TYPE_SHOW
        let filters = b.filters
        do {
            let page = try await api.items(choice.baseUrl, choice.token, "/library/sections/\(choice.section.key)/all?type=\(type)&sort=\(b.sort)\(filters)", limit: GRID_PAGE, offset: offset)
            guard let now = browse[kind], now.choice == choice, now.sort == b.sort, now.filters == filters, now.items.count == offset else { return }
            let seen = Set(now.items.map(\.id))
            let items = now.items + page.filter { !seen.contains($0.id) }
            setBrowse(kind) { x in
                x.items = items
                x.busy = false
                x.total = page.count < GRID_PAGE ? items.count : max(now.total, items.count + 1)
            }
        } catch {
            setBrowse(kind) { $0.busy = false; $0.error = (error as? HttpError)?.message ?? "Couldn't load this library." }
        }
    }

    // MARK: A title's page

    func setDetail(_ key: String, _ change: (inout DetailPage) -> Void) {
        guard var page = detail, page.key == key else { return }
        change(&page)
        detail = page
    }

    public func openDetail(ratingKey: String, serverBase: String?, episodeKey: String? = nil) async {
        if serverBase == IPTV_SOURCE {
            let key = "\(IPTV_SOURCE)|\(ratingKey)"
            detail = DetailPage(key: key, serverBase: IPTV_SOURCE)
            await openIptvDetail(key, ratingKey: ratingKey, episodeKey: episodeKey)
            return
        }
        let base = plex.baseFor(serverBase)
        let key = "\(base ?? "")|\(ratingKey)"
        detail = DetailPage(key: key, serverBase: base)
        guard let base, let token = plex.tokenFor(serverBase) else {
            setDetail(key) { $0.busy = false; $0.error = "Couldn't reach the server this title is on." }
            return
        }
        do {
            guard let d = try await api.detail(base, token, ratingKey: ratingKey) else { throw HttpError("That title isn't on the server any more.") }
            setDetail(key) { $0.detail = d }
            Task { let related = await api.related(base, token, ratingKey: ratingKey); setDetail(key) { $0.related = related } }
            Task { let trailers = await api.trailers(base, token, ratingKey: ratingKey); setDetail(key) { $0.trailers = trailers } }
            if d.isShow {
                let seasons = try await api.children(base, token, ratingKey: ratingKey).filter { $0.type == "season" }
                // The season it's up to (or the episode it was opened on), else the first proper one.
                let upTo = seasons.first { $0.ratingKey == d.onDeckSeasonKey }
                let first = seasons.first { ($0.index ?? 0) > 0 } ?? seasons.first
                await loadSeason(key, base, token, upTo ?? first, focusKey: episodeKey ?? d.onDeckKey, seasons: seasons)
            } else {
                setDetail(key) { $0.busy = false }
            }
        } catch {
            setDetail(key) { $0.busy = false; $0.error = (error as? HttpError)?.message ?? "Couldn't load that title." }
        }
    }

    public func selectSeason(_ season: PlexItem) async {
        if season.isIptv { selectIptvSeason(season); return }
        guard let page = detail, let base = page.serverBase, let token = plex.tokenFor(base) else { return }
        await loadSeason(page.key, base, token, season, focusKey: page.detail?.onDeckKey, seasons: page.seasons)
    }

    func loadSeason(_ key: String, _ base: String, _ token: String, _ season: PlexItem?, focusKey: String?, seasons: [PlexItem]) async {
        setDetail(key) { $0.seasons = seasons; $0.season = season; $0.episodes = []; $0.focused = nil; $0.busy = true }
        guard let season else { setDetail(key) { $0.busy = false }; return }
        let episodes = ((try? await api.children(base, token, ratingKey: season.ratingKey)) ?? []).filter { $0.type == "episode" }
        let focused = episodes.first { $0.ratingKey == focusKey } ?? PlexAPI.nextEpisode(episodes)
        setDetail(key) { $0.episodes = episodes; $0.focused = focused; $0.busy = false }
    }

    public func chooseVersion(_ index: Int) { if let key = detail?.key { setDetail(key) { $0.versionIndex = index } } }

    /**
     * The page open, read again where it stands after watching: what was watched shows so,
     * without the page going back to nothing first and the cursor with it.
     */
    public func refreshDetail(watched: PlexItem) async {
        if let page = detail, page.serverBase == IPTV_SOURCE, let d = page.detail {
            await openIptvDetail(page.key, ratingKey: d.ratingKey, episodeKey: watched.type == "episode" ? watched.ratingKey : page.focused?.ratingKey)
            return
        }
        guard let page = detail, let d = page.detail, let base = page.serverBase, let token = plex.tokenFor(base) else { return }
        let episodeKey = watched.type == "episode" ? watched.ratingKey : nil
        if let season = page.season, let parent = watched.parentRatingKey, episodeKey != nil, parent != season.ratingKey {
            await openDetail(ratingKey: d.ratingKey, serverBase: base, episodeKey: episodeKey)
            return
        }
        if let fresh = try? await api.detail(base, token, ratingKey: d.ratingKey) { setDetail(page.key) { $0.detail = fresh } }
        if let season = page.season, let episodes = try? await api.children(base, token, ratingKey: season.ratingKey) {
            let list = episodes.filter { $0.type == "episode" }
            setDetail(page.key) { $0.episodes = list; $0.focused = list.first { $0.ratingKey == episodeKey } ?? PlexAPI.nextEpisode(list) }
        }
    }

    // MARK: Watched

    public func setWatched(_ item: PlexItem, _ watched: Bool) async {
        if item.isIptv { await setIptvWatched(item, watched); return }
        guard let base = plex.baseFor(item.serverBase), let token = plex.tokenFor(item.serverBase) else { return }
        try? await api.setWatched(base, token, ratingKey: item.ratingKey, watched: watched)
        await refreshHome()
        if detail != nil { await refreshDetail(watched: item) }
    }
}
