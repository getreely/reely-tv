import Foundation

public struct RequestsState: Equatable, Sendable {
    public var address: String?
    public var connecting = false
    public var loading = false
    public var error: String?
    public var rows: [RequestRow] = []
    public var mine: [RequestRecord] = []
    public var marks = TitleMarks()
    /// Every outside id the Plex libraries' titles carry: what's here already.
    public var plexMovies: Set<String> = []
    public var plexShows: Set<String> = []
    public var query = ""
    public var results: [RequestTitle] = []
    public var searching = false
    /// Asked for, and now on the server: "Dune is ready to watch".
    public var ready: [RequestTitle] = []

    public var isConnected: Bool { address != nil }

    /// A poster's word: In library, Downloading, Approved and the rest.
    public func badge(_ title: RequestTitle) -> String? {
        Reely.badgeFor(title, marks: marks, mine: mine, plexMovies: plexMovies, plexShows: plexShows)
    }

    /// The browsing rows less what's in the library already; a row left empty isn't shown.
    public var shownRows: [RequestRow] {
        rows.compactMap { row in
            let titles = row.titles.filter { badge($0) != "In library" }
            return titles.isEmpty ? nil : RequestRow(id: row.id, title: row.title, titles: titles)
        }
    }

    /// This account's requests, each title once, newest first.
    public var mineTitles: [RequestTitle] {
        var seen = Set<String>()
        return mine.map(\.title).filter { seen.insert($0.key).inserted }
    }

    /// Home's Trending or Popular: Reely's films and shows of that kind, taken in turn.
    public func homeRow(_ row: HomeRow) -> [RequestTitle] {
        let ids = row == .trending ? ["movies", "shows"] : ["popularMovies", "popularShows"]
        let lists = ids.map { id in rows.first { $0.id == id }?.titles ?? [] }
        var out: [RequestTitle] = []
        for i in 0..<(lists.map(\.count).max() ?? 0) {
            for list in lists where i < list.count { out.append(list[i]) }
        }
        return out
    }
}

/// A title's page in Requests: what Reely knows of it, where it could go, and what's picked.
public struct RequestPage: Equatable, Sendable {
    public var title: RequestTitle
    public var detail: RequestDetail?
    public var places: RequestPlaces?
    public var chosen: [Int] = []
    public var libraryId: Int?
    public var busy = true
    public var sending = false
    public var outcome: String?
    public var error: String?

    /// The libraries it could still go to.
    public var addable: [RequestLibrary] {
        guard let detail, let places else { return [] }
        return places.librariesFor(detail.title, holding: detail.holdingAll)
    }

    public var canAsk: Bool {
        guard let detail else { return false }
        return places != nil ? !addable.isEmpty : !detail.inLibrary
    }

    /// Of a show partly here, only the seasons it hasn't got are offered.
    public var offered: [RequestSeason] { detail?.seasonsLeft(libraryId) ?? [] }
    public var here: [RequestSeason] {
        guard let detail else { return [] }
        let asked = detail.askedIn(libraryId)
        return detail.seasons.filter { asked.contains($0.number) }
    }

    /// The button's words, as the Fire TV has them.
    public var actionLabel: String {
        let verb = places?.adds == true ? "Add" : "Request"
        if sending { return verb == "Add" ? "Adding…" : "Requesting…" }
        guard title.isShow, !offered.isEmpty else { return verb }
        let picked = offered.filter { chosen.contains($0.number) }.count
        if picked == offered.count && here.isEmpty { return "\(verb) all seasons" }
        if picked == offered.count && offered.count > 1 { return "\(verb) the other \(offered.count) seasons" }
        return picked == 1 ? "\(verb) 1 season" : "\(verb) \(picked) seasons"
    }

    /// What's said under it about where it is already.
    public var heldNote: String? {
        guard let detail else { return nil }
        let held = detail.inLibraries.count
        if !canAsk { return held > 1 ? "Already in \(held) libraries: there's nowhere left for it to go." : "Already in your library." }
        if !here.isEmpty && !offered.isEmpty {
            return "\(here.count == 1 ? here[0].name : "\(here.count) seasons") here already. Pick more to ask for."
        }
        if held > 0 { return held == 1 ? "Already in a library. It can go in another as well." : "Already in \(held) libraries. It can go in another as well." }
        return nil
    }
}

/// Where this account's own ask has got to, in words.
public func requestStatusWords(_ status: String?) -> String? {
    switch status {
    case "pending": return "You asked for this. It's waiting to be approved."
    case "approved": return "You asked for this. It's been approved and is on its way."
    case "denied": return "You asked for this. It was declined."
    default: return nil
    }
}

extension ReelyStore {
    func reely() -> ReelyClient? {
        guard let address = requests.address ?? store.string("reelyUrl") else { return nil }
        if let client = reelyClient, client.base == Reely.normalize(address) { return client }
        let client = makeReelyClient(address)
        reelyClient = client
        return client
    }

    private func makeReelyClient(_ address: String) -> ReelyClient {
        // The account's own sign-in, not a Home profile's: Reely knows the account.
        let token = plex.accountToken ?? plex.token
        return ReelyClient(base: address, transport: api.http.transport) { token }
    }

    /// Reely at [address], signed in with the Plex account in use.
    public func connectReely(_ address: String) async {
        guard Reely.isValid(address) else {
            requests.error = "That doesn't look like an address. Try reely.example.com or 192.168.1.5:8788."
            return
        }
        requests.connecting = true
        requests.error = nil
        let client = makeReelyClient(address)
        if let problem = await client.signIn() {
            requests.connecting = false
            requests.error = problem
            return
        }
        reelyClient = client
        store.set("reelyUrl", client.base)
        requests.connecting = false
        requests.address = client.base
        await loadRequests()
    }

    public func disconnectReely() {
        store.set("reelyUrl", nil)
        reelyClient = nil
        requests = RequestsState()
        requestPage = nil
    }

    public func dismissRequestsError() { requests.error = nil }

    /**
     * Reely's rows, this account's requests and Reely's marks, with what the Plex libraries
     * hold read first: titles already there are left out of the rows.
     */
    public func loadRequests() async {
        guard let client = reely() else { return }
        requests.loading = requests.rows.isEmpty
        requests.error = nil
        async let held = plexHoldings()
        async let rows: Result<[RequestRow], Error> = { do { return .success(try await client.explore()) } catch { return .failure(error) } }()
        async let mine = try? client.myRequests()
        async let marks = client.marks()
        let (h, r, m, k) = await (held, rows, mine, marks)
        requests.loading = false
        if case .success(let list) = r { requests.rows = list } else if case .failure(let e) = r {
            requests.error = Reely.readable((e as? HttpError)?.message) ?? "Reely couldn't do that. Try again."
        }
        if let m { requests.mine = m }
        requests.marks = k
        if let h {
            requests.plexMovies = h.movies
            requests.plexShows = h.shows
        }
    }

    private func plexHoldings() async -> (movies: Set<String>, shows: Set<String>)? {
        let libraries = shownLibraries()
        guard !libraries.isEmpty else { return nil }
        var movies = Set<String>(), shows = Set<String>()
        for l in libraries where l.section.type == "movie" || l.section.type == "show" {
            let movie = l.section.type == "movie"
            let entries = (try? await api.libraryEntries(l.baseUrl, l.token, section: l.section.key, type: movie ? PLEX_TYPE_MOVIE : PLEX_TYPE_SHOW)) ?? []
            for e in entries { for g in e.guids { if movie { movies.insert(g) } else { shows.insert(g) } } }
        }
        return (movies, shows)
    }

    public func searchRequests(_ query: String) async {
        requestSearchRun += 1
        let run = requestSearchRun
        let q = query.trimmingCharacters(in: .whitespaces)
        requests.query = query
        requests.searching = !q.isEmpty
        if q.isEmpty { requests.results = []; return }
        guard let client = reely() else { return }
        do {
            let results = try await client.search(q)
            guard run == requestSearchRun else { return }
            requests.results = results
            requests.searching = false
        } catch {
            guard run == requestSearchRun else { return }
            requests.searching = false
            requests.error = Reely.readable((error as? HttpError)?.message)
        }
    }

    // MARK: A title's page

    public func openRequestTitle(_ title: RequestTitle) async {
        requestPage = RequestPage(title: title)
        guard let client = reely() else { return }
        do {
            async let detail = client.detail(title)
            async let places = try? client.places()
            let (d, p) = (try await detail, await places)
            guard requestPage?.title.key == title.key else { return }
            var page = RequestPage(title: title, detail: d, places: p, chosen: d.seasons.map(\.number), busy: false)
            page.libraryId = p?.preferred(among: page.addable)?.id
            requestPage = page
        } catch {
            guard requestPage?.title.key == title.key else { return }
            requestPage?.busy = false
            requestPage?.error = Reely.readable((error as? HttpError)?.message) ?? "Reely couldn't do that. Try again."
        }
    }

    public func toggleRequestSeason(_ number: Int) {
        guard var page = requestPage else { return }
        if page.chosen.contains(number) { page.chosen.removeAll { $0 == number } } else { page.chosen = (page.chosen + [number]).sorted() }
        requestPage = page
    }

    public func chooseRequestLibrary(_ id: Int) { requestPage?.libraryId = id }

    public func submitRequest() async {
        guard let page = requestPage, let detail = page.detail, let client = reely(), !page.sending else { return }
        let key = page.title.key
        // Of a show partly here, the seasons it hasn't got: Reely keeps the ones already asked for.
        let offered = page.offered.map(\.number)
        let picked = page.chosen.filter { offered.contains($0) }
        let show = page.title.isShow && !offered.isEmpty
        if show && picked.isEmpty {
            requestPage?.outcome = "Pick at least one season."
            return
        }
        // Every season is the whole show, which also takes in seasons still to come.
        let all = show && picked.count == offered.count && detail.askedIn(page.libraryId).isEmpty
        requestPage?.sending = true
        requestPage?.outcome = nil
        let said: String?
        do {
            let outcome = try await client.request(detail.title, seasons: show && !all ? picked : nil, libraryId: page.libraryId)
            // As the Fire TV words it.
            let library = page.places?.libraries.first { $0.id == page.libraryId }
            switch outcome {
            case .sent(let approved):
                said = approved ? "Adding it\(library.map { " to \($0.name)" } ?? "") now."
                    : "Requested\(library.map { " for \($0.name)" } ?? ""). You'll see it here once it's approved."
            case .already: said = "This has already been requested."
            case .refused(let message): said = message
            }
        } catch {
            said = Reely.readable((error as? HttpError)?.message)
        }
        guard requestPage?.title.key == key else { return }
        requestPage?.sending = false
        requestPage?.outcome = said
        await loadRequests()
    }

    // MARK: Ready to watch

    /**
     * Whether anything asked for has arrived, as the Fire TV looks. The first look takes in
     * what was ready already without saying so: that isn't news.
     */
    public func checkReadyRequests() async {
        guard let client = reely(), let mine = try? await client.myRequests() else { return }
        let kept = store.json("readySeen", as: [String].self)
        let seen = Set(kept ?? [])
        guard mine.contains(where: { $0.status == "approved" && !seen.contains($0.title.key) }) else {
            if kept == nil { store.setJson("readySeen", [String]()) }
            requests.mine = mine
            return
        }
        let marks = await client.marks()
        let arrived = Reely.readyRequests(mine, marks, seen: seen)
        requests.mine = mine
        requests.marks = marks
        if kept == nil {
            store.setJson("readySeen", arrived.map(\.key))
            return
        }
        requests.ready = arrived
    }

    /// Put away without watching: not said again.
    public func dismissReady(_ title: RequestTitle) {
        var seen = store.json("readySeen", as: [String].self) ?? []
        if !seen.contains(title.key) { seen.append(title.key) }
        store.setJson("readySeen", seen)
        requests.ready.removeAll { $0.key == title.key }
    }

    /// What arrived: its page on the server, found by name, kind and year; else Search with its name.
    public func openReady(_ title: RequestTitle) async {
        dismissReady(title)
        let kind = title.isShow ? "show" : "movie"
        var servers: [(String, String)] = []
        for l in shownLibraries() where !servers.contains(where: { $0.0 == l.baseUrl }) { servers.append((l.baseUrl, l.token)) }
        for (base, token) in servers {
            let found = (try? await api.searchAll(base, token, query: title.title))?.items.first {
                $0.type == kind && $0.title.lowercased() == title.title.lowercased() && (title.year == nil || $0.year == nil || $0.year == title.year)
            }
            if let found {
                navigate(.detail(ratingKey: found.ratingKey, serverBase: found.serverBase))
                return
            }
        }
        navigate(.search)
        await setQuery(title.title)
    }
}
