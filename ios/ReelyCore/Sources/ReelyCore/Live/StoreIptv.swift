import Foundation

/// The provider's films and series: being read, read, or what went wrong.
public struct IptvStatus: Equatable, Sendable {
    public var loading = false
    public var error: String?
    public var ready = false
}

extension XtreamClient {
    /// The provider's whole catalogue of films and series, with their categories.
    public func vodCatalog(_ c: XtreamCredentials) async throws -> VodCatalog {
        guard !c.isPlaylist else { throw HttpError("Films and series need an Xtream login, not a playlist.") }
        let failure = "Your provider couldn't send its films and series. Try again."
        @Sendable func categories(_ action: String) async -> [XtreamCategory] {
            guard let list = try? await http.json(HttpRequest(url: Xtream.api(c, action))) else { return [] }
            var seen = Set<String>()
            return list.array.filter(\.isObject).map { XtreamCategory(id: $0["category_id"].str, name: $0["category_name"].text ?? "Unnamed") }
                .filter { !$0.id.isEmpty && seen.insert($0.id).inserted }
        }
        async let movieCategories = categories("get_vod_categories")
        async let seriesCategories = categories("get_series_categories")
        async let movies = http.json(HttpRequest(url: Xtream.api(c, "get_vod_streams"), timeout: 180), failure: failure)
        async let series = http.json(HttpRequest(url: Xtream.api(c, "get_series"), timeout: 180), failure: failure)
        var catalog = VodCatalog()
        catalog.movies = Vod.readTitles(try await movies, series: false)
        catalog.series = Vod.readTitles(try await series, series: true)
        catalog.movieCategories = await movieCategories
        catalog.seriesCategories = await seriesCategories
        catalog.loadedAt = Int(Date().timeIntervalSince1970)
        return catalog
    }

    public func movieInfo(_ c: XtreamCredentials, id: Int) async -> VodInfo? {
        guard let root = try? await http.json(HttpRequest(url: Xtream.api(c, "get_vod_info", [("vod_id", String(id))]))) else { return nil }
        return Vod.parseMovieInfo(root)
    }

    public func seriesInfo(_ c: XtreamCredentials, id: Int) async -> VodInfo? {
        guard let root = try? await http.json(HttpRequest(url: Xtream.api(c, "get_series_info", [("series_id", String(id))]))) else { return nil }
        return Vod.parseSeriesInfo(root)
    }
}

/// The Movies or TV Shows menu's IPTV entry, standing in for a library.
public func iptvChoice(_ kind: String) -> LibraryChoice {
    LibraryChoice(serverName: "IPTV", baseUrl: IPTV_SOURCE, token: "", section: PlexSection(key: "iptv-\(kind)", title: "IPTV", type: kind))
}

/// Plex's orders, as the provider's titles are ordered.
let IPTV_SORTS: [String: IptvSort] = ["titleSort:asc": .title, "addedAt:desc": .added, "originallyAvailableAt:desc": .released, "rating:desc": .rated]

extension ReelyStore {
    /// The provider's titles are in use: switched on, signed in with a login, and read.
    public var iptvOn: Bool {
        guard prefs.iptvLibrary, let c = live.credentials, !c.isPlaylist else { return false }
        return iptvStatus.ready
    }

    /// Home: Plex's rows, less what the provider's copy stands in for, with the provider's own.
    func composeHome() {
        let on = iptvOn, wins = prefs.iptvWins
        func keep(_ i: PlexItem) -> Bool { !on || !iptv.hides(i, iptvWins: wins) }
        var rows = plexHome
        rows.watchlist = home.watchlist
        let continuing = on ? iptv.watch.continueWatching() : []
        rows.continueWatching = Array(stableSorted(plexHome.continueWatching.filter(keep) + continuing) { $0.lastViewedAt > $1.lastViewedAt }.prefix(40))
        rows.recentMovies = plexHome.recentMovies.filter(keep)
        rows.iptvMovies = on ? iptv.newest(movies: true, iptvWins: wins) : []
        rows.iptvShows = on ? iptv.newest(movies: false, iptvWins: wins) : []
        home = rows
    }

    /// The provider's catalogue read, and Plex's titles indexed against it.
    public func loadIptv() async {
        guard prefs.iptvLibrary, let c = live.credentials, !c.isPlaylist, !iptvStatus.loading else { return }
        iptvStatus.loading = true
        iptvStatus.error = nil
        do {
            if !iptvStatus.ready { iptv.setCatalog(try await xtream.vodCatalog(c)) }
            var movies: [PlexIndexEntry] = [], shows: [PlexIndexEntry] = []
            for l in shownLibraries() {
                if l.section.type == "movie" { movies += (try? await api.libraryEntries(l.baseUrl, l.token, section: l.section.key, type: PLEX_TYPE_MOVIE)) ?? [] }
                if l.section.type == "show" { shows += (try? await api.libraryEntries(l.baseUrl, l.token, section: l.section.key, type: PLEX_TYPE_SHOW)) ?? [] }
            }
            guard live.credentials == c else { iptvStatus.loading = false; return }
            iptv.setPlex(movies: movies, shows: shows)
            iptvStatus = IptvStatus(loading: false, error: nil, ready: true)
            composeHome()
        } catch {
            iptvStatus.loading = false
            iptvStatus.error = (error as? HttpError)?.message ?? "Your provider couldn't send its films and series. Try again."
        }
    }

    public func refreshIptv() async {
        iptv.clear()
        iptvStatus = IptvStatus()
        await loadIptv()
    }

    public func setIptvLibrary(_ on: Bool) {
        prefs.iptvLibrary = on
        if on { Task { await loadIptv() } } else { leaveIptvTabs(); composeHome() }
    }

    public func setIptvWins(_ wins: Bool) {
        prefs.iptvWins = wins
        composeHome()
        for kind in ["movie", "show"] where browse[kind]?.choice?.baseUrl == IPTV_SOURCE { Task { await loadMore(kind) } }
    }

    /// Off, or signed out: a tab left on the provider's titles goes back to Plex's.
    func leaveIptvTabs() {
        for kind in ["movie", "show"] where browse[kind]?.choice?.baseUrl == IPTV_SOURCE { browse[kind] = Browse() }
    }

    /// Signed out of live TV, or signed in as somebody else: the provider's titles go.
    func forgetIptv() {
        iptv.clear()
        iptvStatus = IptvStatus()
        leaveIptvTabs()
        composeHome()
    }

    func saveIptvWatch() { store.setJson("iptvWatch", iptv.watch.all) }

    // MARK: A tab of the provider's titles

    /// The whole grid at once: it's all here already.
    func loadIptvGrid(_ kind: String) {
        guard let b = browse[kind] else { return }
        let grid = iptv.browse(movies: kind == "movie", sort: IPTV_SORTS[b.sort] ?? .title, categoryId: b.genre?.id,
                               unwatchedOnly: b.unwatched, iptvWins: prefs.iptvWins)
        setBrowse(kind) { x in
            x.items = grid.items
            x.total = grid.items.count
            x.busy = false
            x.letters = grid.letters.map { PlexLetter(letter: $0.letter, count: $0.count) }
            // The provider's categories stand in for genres.
            x.genres = grid.categories.map { PlexGenre(id: $0.id, title: $0.name) }
            x.decades = []
            x.released = Array(iptv.newest(movies: kind == "movie", iptvWins: prefs.iptvWins))
            x.collections = []
        }
    }

    // MARK: A title's page

    func openIptvDetail(_ key: String, ratingKey: String, episodeKey: String?) async {
        guard let c = live.credentials, !c.isPlaylist else {
            setDetail(key) { $0.busy = false; $0.error = "Sign in to your provider in Live TV to watch this." }
            return
        }
        let wins = prefs.iptvWins
        switch IptvKey.parse(ratingKey) {
        case .movie(let id, _):
            let title = iptv.movie(id)
            let info = await xtream.movieInfo(c, id: id)
            let item = iptv.marked(title.map(Vod.itemOf) ?? PlexItem(ratingKey: ratingKey, title: info?.name ?? "Film", type: "movie", serverBase: IPTV_SOURCE))
            var d = Vod.detailOf(ratingKey, title: title, info: info, show: false)
            d.durationMs = info?.durationMs ?? item.durationMs
            if d.durationMs == 0 { d.durationMs = item.durationMs }
            d.viewOffsetMs = item.viewOffsetMs
            d.viewCount = item.viewCount
            let related = iptv.related(item, iptvWins: wins)
            setDetail(key) { $0.detail = d; $0.busy = false; $0.related = related }
        case .show(let id):
            let title = iptv.series(id)
            var info = iptv.cachedSeries(id)
            if info == nil { info = await xtream.seriesInfo(c, id: id) }
            guard let info else {
                setDetail(key) { $0.busy = false; $0.error = "Your provider couldn't send this series. Try again." }
                return
            }
            iptv.keepSeries(id, info)
            var d = Vod.detailOf(ratingKey, title: title, info: info, show: true)
            let seasons = Vod.seasonItems(showId: id, showName: d.title, poster: d.thumb, info: info).map(iptv.marked)
            let all = (info.seasons ?? []).flatMap { Vod.episodeItems(showId: id, showName: d.title, poster: d.thumb, backdrop: d.art, season: $0) }.map(iptv.marked)
            d.viewedLeafCount = all.filter(\.isWatched).count
            // The season of the episode it was opened on, else of the one you're up to, else the first proper one.
            let upTo = episodeKey.flatMap { k in all.first { $0.ratingKey == k } } ?? PlexAPI.nextEpisode(all.filter { ($0.parentIndex ?? 0) > 0 }.isEmpty ? all : all.filter { ($0.parentIndex ?? 0) > 0 })
            let season = seasons.first { $0.ratingKey == upTo?.parentRatingKey } ?? seasons.first { ($0.index ?? 0) > 0 } ?? seasons.first
            let episodes = all.filter { $0.parentRatingKey == season?.ratingKey }
            let focused = episodes.first { $0.ratingKey == upTo?.ratingKey } ?? PlexAPI.nextEpisode(episodes)
            let related = iptv.related(title.map(Vod.itemOf) ?? PlexItem(ratingKey: ratingKey, title: d.title, type: "show", serverBase: IPTV_SOURCE), iptvWins: wins)
            setDetail(key) { p in
                p.detail = d; p.seasons = seasons; p.season = season; p.episodes = episodes; p.focused = focused; p.busy = false; p.related = related
            }
        default:
            setDetail(key) { $0.busy = false; $0.error = "That title isn't with your provider any more." }
        }
    }

    func selectIptvSeason(_ season: PlexItem) {
        guard let page = detail, let d = page.detail, case .season(let showId, let number) = IptvKey.parse(season.ratingKey),
              let raw = iptv.cachedSeries(showId)?.seasons?.first(where: { $0.number == number }) else { return }
        let episodes = Vod.episodeItems(showId: showId, showName: d.title, poster: d.thumb, backdrop: d.art, season: raw).map(iptv.marked)
        setDetail(page.key) { $0.season = season; $0.episodes = episodes; $0.focused = PlexAPI.nextEpisode(episodes) }
    }

    /// Watched, or not, kept here: a show or season is all of its episodes.
    func setIptvWatched(_ item: PlexItem, _ watched: Bool) async {
        var items = [item]
        if item.type == "show" || item.type == "season", let c = live.credentials {
            let showId: Int?
            switch IptvKey.parse(item.ratingKey) {
            case .show(let id): showId = id
            case .season(let id, _): showId = id
            default: showId = nil
            }
            if let showId {
                var info = iptv.cachedSeries(showId)
                if info == nil { info = await xtream.seriesInfo(c, id: showId) }
                if let info {
                    iptv.keepSeries(showId, info)
                    let showName = item.type == "show" ? item.title : item.parentTitle ?? item.title
                    items = (info.seasons ?? []).flatMap { Vod.episodeItems(showId: showId, showName: showName, poster: item.thumb, backdrop: nil, season: $0) }
                    if item.type == "season" { items = items.filter { $0.parentRatingKey == item.ratingKey } }
                }
            }
        }
        iptv.watch.setWatched(items, watched, now: Int(Date().timeIntervalSince1970))
        saveIptvWatch()
        composeHome()
        if let page = detail, let d = page.detail, d.ratingKey == item.ratingKey || d.ratingKey == item.grandparentRatingKey || d.ratingKey == item.parentRatingKey {
            await openIptvDetail(page.key, ratingKey: d.ratingKey, episodeKey: page.focused?.ratingKey)
        }
    }

    // MARK: Playing

    /// A film or episode straight from the provider: there's no Plex to convert it.
    func playIptv(_ item: PlexItem, resume: Bool, queue: [PlexItem]) {
        guard let c = live.credentials, !c.isPlaylist else { playError = "Sign in to your provider in Live TV to watch this."; return }
        let url: String
        let ext: String?
        switch IptvKey.parse(item.ratingKey) {
        case .movie(let id, let e): url = Vod.movieUrl(c, id: id, ext: e); ext = e
        case .episode(let id, let e): url = Vod.episodeUrl(c, id: id, ext: e); ext = e
        default: playError = "That can't be played."; return
        }
        let marked = iptv.marked(item)
        let nearEnd = marked.durationMs > 0 && Double(marked.viewOffsetMs) >= Double(marked.durationMs) * 0.95
        let playback = PlexPlayback(url: url, subtitles: [], markers: [], audioCodec: nil, audioChannels: 0, previewUrl: nil, chapters: [],
                                    partId: nil, audioStreams: [], subtitleStreams: [], container: ext, videoCodec: nil)
        playError = nil
        playing = Playing(item: marked, base: IPTV_SOURCE, token: "", playback: playback, url: url, direct: true,
                          reason: "From your IPTV provider", startMs: resume && marked.viewOffsetMs > 0 && !nearEnd ? marked.viewOffsetMs : 0,
                          sessionId: randomHex(12), queue: queue.map(iptv.marked), mediaIndex: 0, textSubtitle: nil)
    }

    /// Every episode of a provider's series, every season in order, for Up Next.
    func iptvEpisodes(of episode: PlexItem) async -> [PlexItem]? {
        guard let c = live.credentials, !c.isPlaylist, case .show(let id) = IptvKey.parse(episode.grandparentRatingKey ?? "") else { return nil }
        var info = iptv.cachedSeries(id)
        if info == nil { info = await xtream.seriesInfo(c, id: id) }
        guard let info else { return nil }
        iptv.keepSeries(id, info)
        return (info.seasons ?? []).flatMap {
            Vod.episodeItems(showId: id, showName: episode.grandparentTitle ?? "", poster: episode.grandparentThumb, backdrop: nil, season: $0)
        }.map(iptv.marked)
    }

    /// Where it got to, kept here: the provider keeps nothing.
    func noteIptvProgress(_ item: PlexItem, positionMs: Int, durationMs: Int) {
        iptv.watch.progress(item, positionMs: positionMs, durationMs: durationMs > 0 ? durationMs : item.durationMs, now: Int(Date().timeIntervalSince1970))
        saveIptvWatch()
    }
}
