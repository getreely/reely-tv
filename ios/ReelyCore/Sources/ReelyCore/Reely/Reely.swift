import Foundation

/*
 * Reely — the owner's requesting app — as the Fire TV reaches it (ReelyRequests.kt), and
 * the LG app after it (reely.ts). Signing in is with the Plex account already in use;
 * Reely's session is a cookie, kept by the system's cookie store. When it lapses, the
 * next call signs in again rather than failing.
 */

public struct RequestTitle: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var kind: String
    public var tmdbId: Int
    public var tvdbId: Int
    public var title: String
    public var year: Int?
    public var overview: String?
    /// The poster's full address, ready to load.
    public var poster: String?
    /// Reely's own poster value, handed back as it came when the title is requested.
    public var posterPath: String?

    public init(kind: String, tmdbId: Int, tvdbId: Int = 0, title: String, year: Int? = nil, overview: String? = nil, poster: String? = nil, posterPath: String? = nil) {
        self.kind = kind; self.tmdbId = tmdbId; self.tvdbId = tvdbId; self.title = title; self.year = year
        self.overview = overview; self.poster = poster; self.posterPath = posterPath
    }

    public var isShow: Bool { kind == "show" }
    /// Unique across kinds: a film and a show can share a TMDB number.
    public var key: String { "\(kind):\(tmdbId > 0 ? "t\(tmdbId)" : "v\(tvdbId)")" }
    public var id: String { key }
}

public struct RequestRow: Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var titles: [RequestTitle]
}

public struct RequestSeason: Equatable, Sendable, Identifiable {
    public var number: Int
    public var name: String
    public var episodes: Int
    public var id: Int { number }
}

public struct RequestDetail: Equatable, Sendable {
    public var title: RequestTitle
    public var backdrop: String?
    public var genres: [String]
    /// Minutes.
    public var runtime: Int?
    public var status: String?
    public var seasons: [RequestSeason]
    public var inLibraries: [Int]
    /// For a show, which seasons each library holding it has been asked for. Nil when Reely
    /// doesn't say (one older than this), and then a library holding it is taken to have all of it.
    public var seasonsAsked: [Int: [Int]]?

    public var inLibrary: Bool { !inLibraries.isEmpty }

    /// The seasons already asked for in [libraryId]: none where it doesn't hold the show.
    public func askedIn(_ libraryId: Int?) -> [Int] { libraryId.flatMap { seasonsAsked?[$0] } ?? [] }

    /// Whether [libraryId] has the whole of it: a film it holds, or a show with every season asked for.
    public func completeIn(_ libraryId: Int) -> Bool {
        guard inLibraries.contains(libraryId) else { return false }
        guard title.isShow, let asked = seasonsAsked?[libraryId] else { return true }
        return seasons.allSatisfy { asked.contains($0.number) }
    }

    /// The libraries holding all of it: where there's nothing left to ask for.
    public var holdingAll: [Int] { inLibraries.filter(completeIn) }

    /// The seasons that can still be asked for in [libraryId].
    public func seasonsLeft(_ libraryId: Int?) -> [RequestSeason] {
        let asked = askedIn(libraryId)
        return seasons.filter { !asked.contains($0.number) }
    }
}

public struct RequestLibrary: Equatable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var kind: String
    public func takes(_ title: RequestTitle) -> Bool { kind == (title.isShow ? "shows" : "movies") }
}

public struct RequestPlaces: Equatable, Sendable {
    public var libraries: [RequestLibrary]
    public var defaultLibraryId: Int
    /// The owner, or an account that adds without asking.
    public var adds: Bool

    /// The libraries [title] could go to: the right kind, and not holding it already.
    public func librariesFor(_ title: RequestTitle, holding: [Int]) -> [RequestLibrary] {
        libraries.filter { $0.takes(title) && !holding.contains($0.id) }
    }

    /// Where it goes unless another is picked: the default, when it's one of them.
    public func preferred(among: [RequestLibrary]) -> RequestLibrary? {
        among.first { $0.id == defaultLibraryId } ?? among.first
    }
}

public struct RequestRecord: Equatable, Sendable {
    public var id: Int
    public var title: RequestTitle
    /// pending, approved or denied.
    public var status: String
    /// Nil for the whole show, and always for a film.
    public var seasons: [Int]?
}

/// How Reely marks a poster: Downloading, In library, Partial, or Requested.
public struct TitleMarks: Equatable, Sendable {
    public var movies: [Int: String] = [:]
    public var showsByTmdb: [Int: String] = [:]
    public var showsByTvdb: [Int: String] = [:]
    public var requested: Set<String> = []
    public init() {}

    public func badge(_ title: RequestTitle) -> String? {
        let held = title.isShow
            ? (title.tvdbId > 0 ? showsByTvdb[title.tvdbId] : nil) ?? showsByTmdb[title.tmdbId]
            : movies[title.tmdbId]
        if let held { return held }
        return Reely.requestedKeys(title).contains { requested.contains($0) } ? "Requested" : nil
    }
}

public enum RequestOutcome: Equatable, Sendable {
    case sent(approved: Bool)
    case already
    case refused(String)
}

public enum Reely {
    static let tmdbImages = "https://image.tmdb.org/t/p"

    /// Reely's explore rows, in the order they're shown, and what they're called here.
    static let rows: [(String, String)] = [
        ("movies", "Trending Movies"), ("shows", "Trending Shows"), ("popularMovies", "Popular Movies"),
        ("popularShows", "Popular Shows"), ("topMovies", "Top Rated Movies"), ("topShows", "Top Rated Shows"),
    ]

    /// Accepts "reely.example.com", "192.168.1.5:8788", "http://…/", and so on.
    public static func normalize(_ raw: String) -> String {
        var trimmed = raw.trimmingCharacters(in: .whitespaces)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        if trimmed.isEmpty { return trimmed }
        let lower = trimmed.lowercased()
        return lower.hasPrefix("http://") || lower.hasPrefix("https://") ? trimmed : "http://\(trimmed)"
    }

    public static func isValid(_ raw: String) -> Bool {
        guard let url = URL(string: normalize(raw)), let scheme = url.scheme?.lowercased(), let host = url.host else { return false }
        return (scheme == "http" || scheme == "https") && !host.isEmpty
    }

    public static func hostOf(_ url: String) -> String {
        let rest = url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url
        return String(rest.split(separator: "/", omittingEmptySubsequences: false).first ?? "")
    }

    /// TMDB gives a path to put after its image address; TheTVDB gives a whole address.
    static func imageUrl(_ value: String?, _ image: String, _ size: String) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        if value.lowercased().hasPrefix("http") { return value }
        var base = image
        while base.hasSuffix("/") { base.removeLast() }
        var path = Substring(value)
        while path.hasPrefix("/") { path = path.dropFirst() }
        return "\(base)/\(size)/\(path)"
    }

    static func title(_ o: JSON, _ image: String) -> RequestTitle? {
        guard o.isObject else { return nil }
        let kind = o["kind"].str
        guard kind == "movie" || kind == "show" else { return nil }
        let tmdb = o["tmdbId"].int, tvdb = o["tvdbId"].int
        guard tmdb > 0 || tvdb > 0 else { return nil }
        let name = o["title"].str
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let poster = o["poster"].str.trimmingCharacters(in: .whitespaces).isEmpty ? nil : o["poster"].str
        let overview = o["overview"].str
        return RequestTitle(kind: kind, tmdbId: tmdb, tvdbId: tvdb, title: name, year: o["year"].positive,
                            overview: overview.trimmingCharacters(in: .whitespaces).isEmpty ? nil : overview,
                            poster: imageUrl(poster, image, "w342"), posterPath: poster)
    }

    static func titles(_ array: JSON, _ image: String) -> [RequestTitle] {
        var seen = Set<String>()
        return array.array.compactMap { title($0, image) }.filter { seen.insert($0.key).inserted }
    }

    static func imageBase(_ root: JSON) -> String { root["imageBase"].text ?? tmdbImages }

    public static func exploreRows(_ root: JSON) -> [RequestRow] {
        let image = imageBase(root)
        var out: [RequestRow] = []
        for (field, name) in rows {
            let list = titles(root[field], image)
            if !list.isEmpty { out.append(RequestRow(id: field, title: name, titles: list)) }
        }
        for row in root["providers"].array where row.isObject {
            let list = titles(row["results"], image)
            guard !list.isEmpty else { continue }
            let kind = row["kind"].str == "show" ? "Shows" : "Movies"
            out.append(RequestRow(id: "provider:\(row["key"].str):\(row["kind"].str)", title: "\(kind) on \(row["name"].str)", titles: list))
        }
        return out
    }

    public static func parseDetail(_ root: JSON, fallback: RequestTitle) throws -> RequestDetail {
        let image = imageBase(root)
        let p = root["preview"]
        guard p.isObject else { throw HttpError("Reely couldn't do that. Try again.") }
        let status = p["status"].str
        var asked: [Int: [Int]]? = nil
        if root["seasonsAsked"].isArray {
            var map: [Int: [Int]] = [:]
            for x in root["seasonsAsked"].array where x.isObject { map[x["libraryId"].int] = x["seasons"].array.map(\.int) }
            asked = map
        }
        return RequestDetail(
            title: title(p, image) ?? fallback,
            backdrop: imageUrl(p["backdrop"].str, image, "w1280"),
            genres: p["genres"].array.map(\.str).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty },
            runtime: p["runtime"].positive,
            status: status.trimmingCharacters(in: .whitespaces).isEmpty ? nil : status,
            // Specials are season nought; asked for with the rest, not on their own.
            seasons: p["seasons"].array.filter(\.isObject).map {
                RequestSeason(number: $0["number"].int, name: $0["name"].text?.trimmingCharacters(in: .whitespaces).nilIfEmpty ?? "Season \($0["number"].int)",
                              episodes: $0["episodes"].array.count)
            }.filter { $0.number > 0 },
            inLibraries: root["inLibraries"].array.map(\.int),
            seasonsAsked: asked)
    }

    public static func parseMarks(movies: [JSON], shows: [JSON], open: [JSON]) -> TitleMarks {
        var marks = TitleMarks()
        for m in movies where m["tmdbId"].int > 0 {
            marks.movies[m["tmdbId"].int] = m["downloading"].isTrue ? "Downloading" : m["filePath"].str.trimmingCharacters(in: .whitespaces).isEmpty ? "Requested" : "In library"
        }
        func showMark(_ s: JSON) -> String {
            if s["downloading"].isTrue { return "Downloading" }
            if s["onDisk"].int == 0 { return "Requested" }
            // Every aired episode here. A show asked for one season of is that season, not all of it.
            return s["aired"].int > 0 && s["onDisk"].int >= s["aired"].int ? "In library" : "Partial"
        }
        for s in shows where s.isObject {
            if s["tmdbId"].int > 0 { marks.showsByTmdb[s["tmdbId"].int] = showMark(s) }
            if s["tvdbId"].int > 0 { marks.showsByTvdb[s["tvdbId"].int] = showMark(s) }
        }
        for r in open where r.isObject {
            let kind = r["kind"].str
            if kind == "show" && r["tvdbId"].int > 0 { marks.requested.insert("show-tvdb-\(r["tvdbId"].int)") }
            if r["tmdbId"].int > 0 { marks.requested.insert("\(kind)-\(r["tmdbId"].int)") }
        }
        return marks
    }

    public static func parseRecords(_ root: JSON) -> [RequestRecord] {
        let out = root["requests"].array.compactMap { r -> RequestRecord? in
            guard let t = title(r, tmdbImages) else { return nil }
            return RequestRecord(id: r["id"].int, title: t, status: r["status"].str, seasons: r["seasons"].isArray ? r["seasons"].array.map(\.int) : nil)
        }
        return stableSorted(out) { $0.id > $1.id }
    }

    /// Reely's own rule: a show asked for by TheTVDB is still the same show by TMDB.
    public static func requestedKeys(_ title: RequestTitle) -> [String] {
        var keys: [String] = []
        if title.isShow && title.tvdbId > 0 { keys.append("show-tvdb-\(title.tvdbId)") }
        if title.tmdbId > 0 { keys.append("\(title.kind)-\(title.tmdbId)") }
        return keys
    }

    /// Which of this account's approved requests have arrived; anything in [seen] has been said.
    public static func readyRequests(_ mine: [RequestRecord], _ marks: TitleMarks, seen: Set<String>) -> [RequestTitle] {
        var out: [RequestTitle] = []
        var keys = Set<String>()
        for record in mine {
            let key = record.title.key
            guard record.status == "approved", !seen.contains(key), !keys.contains(key) else { continue }
            let mark = marks.badge(record.title)
            if mark == "In library" || (record.title.isShow && mark == "Partial") {
                keys.insert(key)
                out.append(record.title)
            }
        }
        return out
    }

    /// Whether a Plex library here has [title], by the outside ids its items carry.
    public static func plexHas(_ title: RequestTitle, movies: Set<String>, shows: Set<String>) -> Bool {
        if title.isShow {
            return (title.tvdbId > 0 && shows.contains("tvdb://\(title.tvdbId)")) || (title.tmdbId > 0 && shows.contains("tmdb://\(title.tmdbId)"))
        }
        return title.tmdbId > 0 && movies.contains("tmdb://\(title.tmdbId)")
    }

    /// What a poster says, as the Fire TV's Requests has it: in Plex already, else Reely's own
    /// word, else where this account's ask has got to.
    public static func badgeFor(_ title: RequestTitle, marks: TitleMarks, mine: [RequestRecord], plexMovies: Set<String>, plexShows: Set<String>) -> String? {
        let mark = marks.badge(title)
        if (mark == nil || mark == "Requested") && plexHas(title, movies: plexMovies, shows: plexShows) { return "In library" }
        if let mark, mark != "Requested" { return mark }
        switch mine.first(where: { $0.title.key == title.key })?.status {
        case "approved": return "Approved"
        case "denied": return "Declined"
        case "pending": return "Requested"
        default: return mark
        }
    }

    /// Reely's answer when plex.tv turned down the sign-in this device gave it.
    public static let plexRejected =
        "Plex didn't accept this device's sign-in, so Reely can't sign you in. Sign out of Plex in Settings and sign in again, then connect."

    /// Reely couldn't check who the server is shared with: the owner's link to Plex has stopped working.
    public static let ownerLinkBroken =
        "Reely's link to Plex has stopped working, so it can't check who the server is shared with. The server's owner can fix it in Reely: Settings, Plex, Unlink, then Link my Plex account."

    /// Reely's own words, but never a page of markup passed on from somewhere else.
    public static func readable(_ error: String?) -> String? {
        guard let error, !(error.contains("<") && error.contains(">")) else { return nil }
        return error
    }

    static func errorOf(_ data: Data) -> String? {
        guard let e = JSON.parse(data)?["error"].text, !e.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return e
    }
}

/** Reely at one address, signed in with the Plex account in use. */
public actor ReelyClient {
    public nonisolated let base: String
    private let transport: HttpTransport
    private let plexToken: @Sendable () async -> String?
    private var signedIn = false

    public init(base: String, transport: HttpTransport, plexToken: @escaping @Sendable () async -> String?) {
        self.base = Reely.normalize(base)
        self.transport = transport
        self.plexToken = plexToken
    }

    private var unreachable: String { "Couldn't reach Reely at \(Reely.hostOf(base))." }

    private func send(_ path: String, method: String = "GET", body: Data? = nil) async throws -> HttpResponse {
        var headers = ["Accept": "application/json"]
        if body != nil { headers["Content-Type"] = "application/json" }
        return try await transport.send(HttpRequest(url: base + path, method: method, headers: headers, body: body, timeout: 20))
    }

    /// Signs in with the Plex account; the message to show when that can't be done.
    public func signIn() async -> String? {
        guard let token = await plexToken() else { return "Sign in to Plex first." }
        do {
            let r = try await send("/api/v1/auth/plex/token", method: "POST", body: try JSONSerialization.data(withJSONObject: ["token": token]))
            signedIn = r.ok
            if signedIn { return nil }
            let error = Reely.errorOf(r.data)
            if let error, error.lowercased().contains("didn't accept that sign-in") { return Reely.plexRejected }
            if r.status == 502, let error, error.lowercased().hasPrefix("plex.tv") { return Reely.ownerLinkBroken }
            switch r.status {
            case 403: return "Your Plex account doesn't have access to this server's requests."
            case 404: return "That server doesn't sign in from the TV yet. Update Reely."
            case 412: return "Signing in with Plex isn't set up on this Reely server yet."
            case 429: return "Too many tries. Wait a minute and try again."
            default: return Reely.readable(error) ?? "Reely couldn't do that. Try again."
            }
        } catch {
            signedIn = false
            return unreachable
        }
    }

    /// Makes a call, signing in first when there's no session, and once more if it has lapsed.
    private func call(_ path: String, method: String = "GET", body: Data? = nil) async throws -> HttpResponse {
        if !signedIn, let problem = await signIn() { throw HttpError(problem) }
        var answer: HttpResponse
        do { answer = try await send(path, method: method, body: body) } catch { throw HttpError(unreachable) }
        if answer.status == 401 {
            if let problem = await signIn() { throw HttpError(problem) }
            do { answer = try await send(path, method: method, body: body) } catch { throw HttpError(unreachable) }
        }
        return answer
    }

    private func get(_ path: String) async throws -> JSON {
        let r = try await call(path)
        guard r.ok else { throw HttpError(Reely.errorOf(r.data) ?? "Reely couldn't do that. Try again.", status: r.status) }
        guard let json = JSON.parse(r.data) else { throw HttpError("Reely couldn't do that. Try again.") }
        return json
    }

    public func explore() async throws -> [RequestRow] { Reely.exploreRows(try await get("/api/v1/explore")) }

    public func search(_ query: String) async throws -> [RequestTitle] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return [] }
        let root = try await get("/api/v1/search?q=\(encodeComponent(q))")
        return Reely.titles(root["results"], Reely.imageBase(root))
    }

    public func detail(_ title: RequestTitle) async throws -> RequestDetail {
        // A show found through TheTVDB has only that id; Reely looks it up there.
        let path = title.isShow && title.tmdbId == 0 && title.tvdbId > 0
            ? "/api/v1/preview/show/\(title.tvdbId)?src=tvdb"
            : "/api/v1/preview/\(title.kind)/\(title.tmdbId)"
        return try Reely.parseDetail(try await get(path), fallback: title)
    }

    public func places() async throws -> RequestPlaces {
        let user = try await get("/api/v1/auth/me")["user"]
        let libraries = try await get("/api/v1/libraries")["libraries"]
        return RequestPlaces(
            libraries: libraries.array.filter(\.isObject).map { RequestLibrary(id: $0["id"].int, name: $0["name"].str, kind: $0["kind"].str) },
            defaultLibraryId: user["defaultLibraryId"].int,
            adds: user["role"].str == "admin" || user["mayAdd"].isTrue)
    }

    /// Best effort: each list on its own, so one that can't be had leaves the others' marks.
    public func marks() async -> TitleMarks {
        async let movies = list("/api/v1/movies", "movies")
        async let shows = list("/api/v1/shows", "shows")
        async let open = list("/api/v1/requests", "requests")
        return Reely.parseMarks(movies: await movies, shows: await shows, open: await open)
    }

    private func list(_ path: String, _ field: String) async -> [JSON] {
        ((try? await get(path)) ?? .null)[field].array
    }

    public func myRequests() async throws -> [RequestRecord] { Reely.parseRecords(try await get("/api/v1/requests?mine=1")) }

    public func request(_ title: RequestTitle, seasons: [Int]?, libraryId: Int?) async throws -> RequestOutcome {
        var body: [String: Any] = ["kind": title.kind, "tmdbId": title.tmdbId, "tvdbId": title.tvdbId, "title": title.title,
                                   "year": title.year ?? 0, "poster": title.posterPath ?? ""]
        if let seasons { body["seasons"] = seasons }
        if let libraryId { body["libraryId"] = libraryId }
        let r = try await call("/api/v1/requests", method: "POST", body: try JSONSerialization.data(withJSONObject: body))
        if r.ok { return .sent(approved: JSON.parse(r.data)?["status"].str == "approved") }
        if r.status == 409 { return .already }
        return .refused(Reely.errorOf(r.data) ?? "Reely couldn't take that request. Try again.")
    }
}
