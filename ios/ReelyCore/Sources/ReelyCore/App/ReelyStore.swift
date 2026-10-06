import Foundation
import Observation

/** Where the app is: a tab, or a page opened on top of one, as on the Fire TV. */
public enum Route: Equatable, Hashable, Sendable {
    case home
    case library(kind: String)
    case search
    case live
    case requests
    case settings
    case detail(ratingKey: String, serverBase: String?)
    case person(id: String, name: String, serverBase: String?)
    case playlist(ratingKey: String, title: String, serverBase: String?)
    case collection(ratingKey: String, title: String, serverBase: String?)
    /// A title to ask for, from Requests.
    case requestTitle(RequestTitle)

    /// A tab replaces what was open; anything else goes on top of it.
    public var isTab: Bool {
        switch self {
        case .home, .library, .search, .live, .requests, .settings: return true
        default: return false
        }
    }
}

public struct PlexState: Equatable, Sendable {
    /// The profile's token, and the account's (the same until a Home profile is switched to).
    public var token: String?
    public var accountToken: String?
    public var user: PlexHomeUser?
    public var homeUsers: [PlexHomeUser] = []
    public var servers: [PlexServer] = []
    public var serverName: String?
    public var baseUrl: String?
    public var serverToken: String?
    /// Every movie and TV library on every server that answered.
    public var libraries: [LibraryChoice] = []
    /// Looking for the servers, after signing in or at start.
    public var finding = false
    public var error: String?
    /// Signing in: the code to type at plex.tv/link, and the link the QR code carries.
    public var linkCode: String?
    public var linkUrl: String?
    /// Addresses a server stopped answering at, and where it answers now. What's already on
    /// screen was read from the old one and still names it; this is how it reaches the new.
    public var moved: [String: String] = [:]

    public var isSignedIn: Bool { token != nil }
    public var isConnected: Bool { baseUrl != nil && serverToken != nil }

    /// Where a server is now, by an address it was read from. Nil means the one connected.
    public func baseFor(_ base: String?) -> String? { base.map { moved[$0] ?? $0 } ?? baseUrl }

    /// The token for a server, by its address. Nil means the one connected.
    public func tokenFor(_ base: String?) -> String? {
        let now = base.map { moved[$0] ?? $0 }
        if now == nil || now == baseUrl { return serverToken }
        return libraries.first { $0.baseUrl == now }?.token
    }
}

/**
 * Everything the app knows and does, for every screen: the Swift counterpart of the Fire
 * TV's ReelyViewModel. Screens read it and call into it; nothing else talks to a server.
 */
@MainActor
@Observable
public final class ReelyStore {
    public internal(set) var plex = PlexState()
    public internal(set) var home = HomeRows()
    public private(set) var homeBusy = false
    public private(set) var homeError: String?
    public var prefs: Prefs { didSet { if prefs != oldValue { store.setJson("prefs", prefs) } } }
    public internal(set) var browse: [String: Browse] = ["movie": Browse(), "show": Browse()]
    public internal(set) var detail: DetailPage?
    public internal(set) var search = SearchState()
    /// Just signed in to a Plex Home of several people.
    public internal(set) var askWho = false
    public internal(set) var list: ListPage?
    /// The account's Watchlist, by Plex's own ids.
    public internal(set) var watchlist: Set<String> = []
    var watchlistEdits = 0
    var searchRun = 0
    /// What's playing; nil when nothing is.
    public internal(set) var playing: Playing?
    /// A sound or subtitle change is swapping the stream: the old one stopping is expected, not a dropped connection.
    public internal(set) var replacingStream = false
    /// Asked to play, and on its way: the player's black screen goes up at once, not when Plex has answered.
    public internal(set) var opening: PlexItem?
    public internal(set) var playError: String?
    public private(set) var route: Route = .home
    public private(set) var stack: [Route] = [.home]
    /// What Settings or Search opened over.
    private var beneath: [Route] = []
    /// A phone shows Settings and Search as a sheet over the tabs, which a page opened from them leaves.
    @ObservationIgnored public var pagesLeaveSheets = false

    public let api: PlexAPI
    public let xtream: XtreamClient
    public internal(set) var live = LiveState()
    public internal(set) var requests = RequestsState()
    /// The provider's films and series, matched with Plex.
    public internal(set) var iptv = IptvLibrary()
    public internal(set) var iptvStatus = IptvStatus()
    /// Home as Plex has it, before the provider's titles go in.
    @ObservationIgnored var plexHome = HomeRows()
    public internal(set) var requestPage: RequestPage?
    @ObservationIgnored var reelyClient: ReelyClient?
    @ObservationIgnored var requestSearchRun = 0
    let store: KeyValueStore
    let secrets: KeyValueStore
    private var linkTask: Task<Void, Never>?
    private var homeRun = 0
    private var relocating: Task<Bool, Never>?

    public init(api: PlexAPI, store: KeyValueStore, secrets: KeyValueStore) {
        self.api = api
        self.xtream = XtreamClient(http: api.http)
        self.store = store
        self.secrets = secrets
        self.prefs = store.json("prefs", as: Prefs.self) ?? Prefs()
        plex.token = secrets.string("plexToken")
        plex.accountToken = secrets.string("plexAccountToken") ?? plex.token
        plex.user = store.json("plexUser", as: PlexHomeUser.self)
        search.recent = store.json("recentSearches", as: [String].self) ?? []
        loadLiveState()
        requests.address = store.string("reelyUrl")
        iptv.watch = IptvWatch(store.json("iptvWatch", as: [IptvMark].self) ?? [])
    }

    /// A client id kept for good: plex.tv knows each device by it.
    public static func clientId(_ store: KeyValueStore) -> String {
        if let id = store.string("clientId") { return id }
        let id = "reely-ios-" + UUID().uuidString.lowercased()
        store.set("clientId", id)
        return id
    }

    /// At start: signed in already, the servers are looked for.
    public func start() async {
        guard let token = plex.token else { return }
        Task { await loadProfiles() }
        await connect(token)
    }

    // MARK: Getting about

    public func navigate(_ to: Route) {
        // On a phone Settings and Search are a sheet: a title opened from one leaves it, and opens in the tab.
        if pagesLeaveSheets && !to.isTab && (stack.first == .settings || stack.first == .search) {
            stack = (beneath.isEmpty ? [.home] : beneath) + [to]
            route = to
            beneath = []
            return
        }
        // Settings and Search open over what was showing, and go back to it when put away.
        if (to == .settings || to == .search) && route != .settings && route != .search { beneath = stack }
        route = to
        stack = to.isTab ? [to] : stack + [to]
    }

    /// The pages over the tab, as iOS's own navigation has them after a swipe back.
    public func setPath(_ path: [Route]) {
        guard let root = stack.first, root.isTab else { return }
        stack = [root] + path
        route = stack.last ?? root
    }

    /// Settings or Search put away (a phone's sheet closed): back to what was under it.
    public func closeSheet() {
        guard route == .settings || route == .search || stack.first == .settings || stack.first == .search else { return }
        stack = beneath.isEmpty ? [.home] : beneath
        route = stack.last ?? .home
        beneath = []
    }

    @discardableResult
    public func back() -> Bool {
        guard stack.count > 1 else { return false }
        stack.removeLast()
        route = stack.last!
        return true
    }

    /// The tab a page belongs to: the one it was opened from.
    public var tab: Route { route == .settings ? route : stack.last(where: \.isTab) ?? route }

    // MARK: Signing in

    /**
     * Two PINs for one sign-in, as the Fire TV does: the short one to type at plex.tv/link,
     * and a strong one for the QR code. Whichever is approved first signs in.
     */
    public func startLink() {
        linkTask?.cancel()
        plex.error = nil
        linkTask = Task { [weak self] in
            guard let self else { return }
            let strong = try? await api.createPin(strong: true)
            let pin: PlexPin
            do { pin = try await api.createPin() } catch {
                plex.error = (error as? HttpError)?.message ?? "Couldn't get a sign-in code from Plex. Try again."
                return
            }
            plex.linkCode = pin.code
            plex.linkUrl = strong.map { self.api.authUrl(code: $0.code) }
            // plex.tv expires a PIN after 15 minutes; stop looking well before that.
            for _ in 0..<150 {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { return }
                for candidate in [pin, strong].compactMap({ $0 }) {
                    if let token = await api.claimPin(id: candidate.id) {
                        await signedIn(token)
                        return
                    }
                }
            }
            plex.linkCode = nil
            plex.linkUrl = nil
            plex.error = "That code has expired. Try signing in again."
        }
    }

    public func cancelLink() {
        linkTask?.cancel()
        linkTask = nil
        plex.linkCode = nil
        plex.linkUrl = nil
    }

    public func signedIn(_ token: String) async {
        secrets.set("plexToken", token)
        secrets.set("plexAccountToken", token)
        plex.token = token
        plex.accountToken = token
        plex.linkCode = nil
        plex.linkUrl = nil
        plex.user = await api.account(token: token)
        store.setJson("plexUser", plex.user)
        plex.homeUsers = await api.homeUsers(token: token)
        // Signed in to a Plex Home of several people: "Who's watching?" comes up once.
        askWho = plex.homeUsers.count > 1
        await connect(token)
    }

    /// The Home's people, asked for again: for the profile picker.
    public func loadProfiles() async {
        guard let account = plex.accountToken ?? plex.token else { return }
        plex.homeUsers = await api.homeUsers(token: account)
    }

    public func askedWho() { askWho = false }

    /// Becomes another member of the Home, with their PIN when they have one; nil when done, else why not.
    public func switchUser(_ user: PlexHomeUser, pin: String?) async -> String? {
        guard let account = plex.accountToken ?? plex.token else { return "Sign in to Plex first." }
        do {
            let token = try await api.switchHomeUser(token: account, uuid: user.uuid, pin: pin)
            secrets.set("plexToken", token)
            store.setJson("plexUser", user)
            store.set("server", nil)
            let users = plex.homeUsers
            plex = PlexState(token: token, accountToken: account, user: user, homeUsers: users)
            home = HomeRows()
            browse = ["movie": Browse(), "show": Browse()]
            detail = nil
            navigate(.home)
            await connect(token)
            return nil
        } catch {
            return (error as? HttpError)?.message ?? "Couldn't switch profiles. Try again."
        }
    }

    public func signOut() {
        cancelLink()
        secrets.set("plexToken", nil)
        secrets.set("plexAccountToken", nil)
        store.set("server", nil)
        store.set("plexUser", nil)
        plex = PlexState()
        home = HomeRows()
        stack = [.home]
        route = .home
    }

    /// Try again, on the screen saying no server could be reached.
    public func retryConnect() {
        guard let token = plex.token, !plex.isConnected, !plex.finding else { return }
        Task { await connect(token) }
    }

    public func dismissPlexError() { plex.error = nil }

    // MARK: Servers

    /// The servers, and the first that answers: the one last used, else the account's own.
    public func connect(_ token: String) async {
        plex.finding = true
        plex.error = nil
        let servers: [PlexServer]
        do { servers = try await api.servers(token: token) } catch {
            plex.finding = false
            plex.error = (error as? HttpError)?.message ?? "Couldn't load your Plex servers. Try again."
            return
        }
        guard plex.token == token else { return }
        let last = store.string("server")
        let ordered = servers.filter { $0.name == last } + servers.filter { $0.name != last }
        var reached: [(PlexServer, String?)] = []
        await withTaskGroup(of: (Int, String?).self) { group in
            for (i, server) in ordered.enumerated() { group.addTask { (i, await self.api.firstReachable(server)) } }
            var found = [String?](repeating: nil, count: ordered.count)
            for await (i, base) in group { found[i] = base }
            reached = zip(ordered, found).map { ($0, $1) }
        }
        var libraries: [LibraryChoice] = []
        var chosen: (PlexServer, String)?
        for (server, base) in reached {
            guard let base else { continue }
            if chosen == nil { chosen = (server, base) }
            for section in (try? await api.sections(base, server.accessToken)) ?? [] where section.type == "movie" || section.type == "show" {
                libraries.append(LibraryChoice(serverName: server.name, baseUrl: base, token: server.accessToken, section: section))
            }
        }
        guard plex.token == token else { return }
        plex.servers = servers
        plex.finding = false
        guard let (server, base) = chosen else {
            plex.error = servers.isEmpty
                ? "This Plex account has no Plex server of its own, and nobody has shared one with it yet."
                : "Found \(servers.map(\.name).joined(separator: " and ")), but couldn't reach \(servers.count > 1 ? "any of them" : "it"). Make sure it's on. Reely keeps trying."
            return
        }
        store.set("server", server.name)
        plex.serverName = server.name
        plex.baseUrl = base
        plex.serverToken = server.accessToken
        plex.libraries = libraries
        await refreshHome()
    }

    /**
     * The servers in use, looked for again where they are now, without starting over: an
     * address can stop working while the app stays open (a friend's server, a relay, a new
     * address from the router). plex.tv is asked where each is, and one that doesn't answer
     * where it was is used from where it does. True when anything moved.
     */
    @discardableResult
    public func relocateServers() async -> Bool {
        if let running = relocating { return await running.value }
        let look = Task { await lookForServers() }
        relocating = look
        let moved = await look.value
        relocating = nil
        return moved
    }

    private func lookForServers() async -> Bool {
        guard let account = plex.token, let servers = try? await api.servers(token: account) else { return false }
        plex.servers = servers
        var inUse: [String: (String, String)] = [:]
        for l in plex.libraries { inUse[l.serverName] = (l.baseUrl, l.token) }
        if let name = plex.serverName, let base = plex.baseUrl, let token = plex.serverToken { inUse[name] = (base, token) }
        var moves: [String: (String, String)] = [:]
        for (name, (base, token)) in inUse {
            guard let server = servers.first(where: { $0.name == name }) ?? servers.first(where: { $0.accessToken == token }) else { continue }
            let answers = await api.reachable(server, base)
            if answers && server.accessToken == token { continue }
            guard let now = answers ? base : await api.firstReachable(server) else { continue }
            if now != base || server.accessToken != token { moves[base] = (now, server.accessToken) }
        }
        guard !moves.isEmpty else { return false }
        plex = ReelyStore.afterMoves(plex, moves)
        return true
    }

    /// Servers found at other addresses, or with other tokens; the old addresses kept as aliases.
    nonisolated public static func afterMoves(_ p: PlexState, _ moves: [String: (String, String)]) -> PlexState {
        var next = p
        if let base = p.baseUrl, let (b, t) = moves[base] { next.baseUrl = b; next.serverToken = t }
        var aliases = p.moved.mapValues { to in moves[to]?.0 ?? to }
        for (from, (to, _)) in moves where from != to { aliases[from] = to }
        next.moved = aliases
        next.libraries = p.libraries.map { l in
            guard let (b, t) = moves[l.baseUrl] else { return l }
            var c = l
            c.baseUrl = b
            c.token = t
            return c
        }
        return next
    }

    /// Another of the account's servers first: its libraries and Home, from now on.
    public func chooseServer(_ name: String) async {
        guard let token = plex.token else { return }
        store.set("server", name)
        await connect(token)
    }

    /// Whether the server is reached at home or over the internet, as the Fire TV says it.
    public static func connectionKind(_ base: String?) -> String {
        guard let base, let host = URL(string: base)?.host else { return "—" }
        let dotted = host.replacingOccurrences(of: "-", with: ".")
        let home = ["10.", "127.", "192.168."].contains { dotted.hasPrefix($0) }
            || (16...31).contains { dotted.hasPrefix("172.\($0).") }
        return home ? "Home network" : "Internet"
    }

    // MARK: Libraries

    /// The libraries switched on in Settings, or all of them when none is; of a kind, or every kind.
    public func shownLibraries(kind: String? = nil) -> [LibraryChoice] {
        let all = plex.libraries.filter { kind == nil || $0.section.type == kind }
        let pinned = all.filter { prefs.favouriteSections.contains($0.id) }
        return pinned.isEmpty ? all : pinned
    }

    public func togglePinned(_ library: LibraryChoice) {
        if let i = prefs.favouriteSections.firstIndex(of: library.id) { prefs.favouriteSections.remove(at: i) }
        else { prefs.favouriteSections.append(library.id) }
        Task { await refreshHome() }
    }

    // MARK: Home

    public func refreshHome() async {
        let sources = shownLibraries()
        guard !sources.isEmpty else { return }
        homeRun += 1
        let run = homeRun
        homeBusy = true
        homeError = nil
        let rows = await loadHome(api, sources)
        guard run == homeRun else { return }
        guard let rows else {
            // Perhaps a server is somewhere else now: looked for, and if so, asked there.
            if await relocateServers() { await refreshHome(); return }
            homeBusy = false
            homeError = "Couldn't reach your Plex server. Trying again…"
            return
        }
        plexHome = rows
        composeHome()
        homeBusy = false
        if iptvStatus.ready == false && !iptvStatus.loading { Task { await loadIptv() } }
        Task { await refreshWatchlist() }
        Task { await checkReadyRequests() }
        // Home's Trending and Popular are Reely's.
        if requests.isConnected && requests.rows.isEmpty { Task { await loadRequests() } }
    }

    public func isHidden(_ row: HomeRow) -> Bool { prefs.hiddenHomeRows.contains(row.rawValue) }

    public func setHidden(_ row: HomeRow, _ hidden: Bool) {
        prefs.hiddenHomeRows.removeAll { $0 == row.rawValue }
        if hidden { prefs.hiddenHomeRows.append(row.rawValue) }
    }

    // MARK: Pictures

    /// A picture through its server's resizer, at the size it's drawn.
    public func imageUrl(_ serverBase: String?, _ path: String?, width: Int, height: Int) -> URL? {
        // The provider's pictures are whole addresses of their own.
        if serverBase == IPTV_SOURCE { return path.flatMap(URL.init(string:)) }
        guard let base = plex.baseFor(serverBase), let token = plex.tokenFor(serverBase),
              let url = PlexAPI.imageUrl(base, token, path: path, width: width, height: height) else { return nil }
        return URL(string: url)
    }

    public func logoUrl(_ serverBase: String?, _ path: String?) -> URL? {
        guard let path, let base = plex.baseFor(serverBase), let token = plex.tokenFor(serverBase) else { return nil }
        return URL(string: PlexAPI.logoUrl(base, token, path: path))
    }
}
