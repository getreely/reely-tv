import Foundation

/*
 * The IPTV provider's films and series, as the Android app has them (XtreamVod.kt,
 * VodItems.kt, IptvLibrary.kt) and the LG app after it (vod.ts): read, tidied, matched
 * with Plex, and shown as the same items as Plex's, marked IPTV, with where each was left
 * kept on the device.
 */

public let IPTV_SOURCE = "iptv:"

extension PlexItem {
    public var isIptv: Bool { serverBase == IPTV_SOURCE }
    /// The word on a poster that comes from the provider.
    public var sourceTag: String? { isIptv ? "IPTV" : nil }
}

public struct VodTitle: Equatable, Sendable {
    public var series: Bool
    public var id: Int
    public var name: String
    public var year: Int?
    public var tag: String?
    public var poster: String?
    public var rating: Double?
    public var addedAt: Int
    public var categoryId: String?
    public var tmdbId: String?
    public var fileExtension: String?
    public var plot: String?
    public var genre: String?
    public var key: String
}

public struct VodCatalog: Equatable, Sendable {
    public var movies: [VodTitle] = []
    public var series: [VodTitle] = []
    public var movieCategories: [XtreamCategory] = []
    public var seriesCategories: [XtreamCategory] = []
    public var loadedAt: Int = 0
    public init() {}
}

public struct VodInfo: Equatable, Sendable {
    public var name: String?
    public var plot: String?
    public var cast: [String]
    public var directors: [String]
    public var genres: [String]
    public var durationMs: Int
    public var backdrop: String?
    public var poster: String?
    public var releaseDate: String?
    public var rating: Double?
    public var tmdbId: String?
    public var fileExtension: String?
    public var ageRating: String?
    /// A series' seasons; nil for a film.
    public var seasons: [VodSeason]?
}

public struct VodEpisode: Equatable, Sendable {
    public var id: Int
    public var season: Int
    public var number: Int?
    public var title: String
    public var fileExtension: String?
    public var plot: String?
    public var still: String?
    public var durationMs: Int
    public var airDate: String?
}

public struct VodSeason: Equatable, Sendable {
    public var number: Int
    public var name: String
    public var poster: String?
    public var episodes: [VodEpisode]
}

public enum IptvKey: Equatable, Sendable {
    case movie(id: Int, ext: String?)
    case show(id: Int)
    case season(showId: Int, number: Int)
    case episode(id: Int, ext: String?)

    public var key: String {
        switch self {
        case .movie(let id, let ext): return "m\(id)" + (ext.map { ".\($0)" } ?? "")
        case .show(let id): return "s\(id)"
        case .season(let show, let n): return "s\(show):\(n)"
        case .episode(let id, let ext): return "e\(id)" + (ext.map { ".\($0)" } ?? "")
        }
    }

    public static func parse(_ key: String) -> IptvKey? {
        guard key.count >= 2, let first = key.first else { return nil }
        let body = String(key.dropFirst())
        func idExt() -> (Int, String?)? {
            let parts = body.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard let idText = parts.first, !idText.isEmpty, idText.allSatisfy(\.isASCIIDigit), let id = Int(idText) else { return nil }
            let ext = parts.count > 1 && !parts[1].isEmpty ? parts[1] : nil
            return (id, ext)
        }
        switch first {
        case "m": return idExt().map { .movie(id: $0.0, ext: $0.1) }
        case "e": return idExt().map { .episode(id: $0.0, ext: $0.1) }
        case "s":
            if body.contains(":") {
                let p = body.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
                guard p.count == 2, let s = Int(p[0]), let n = Int(p[1]), p[0].allSatisfy(\.isASCIIDigit), p[1].allSatisfy(\.isASCIIDigit) else { return nil }
                return .season(showId: s, number: n)
            }
            return body.allSatisfy(\.isASCIIDigit) ? Int(body).map { .show(id: $0) } : nil
        default: return nil
        }
    }
}

extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}

public enum Vod {
    // MARK: Names

    private static let bracketed = try! NSRegularExpression(pattern: #"^\s*[\[|(]\s*([A-Za-z0-9+ ]{1,8})\s*[\]|)]\s*[-:|]?\s*"#)
    private static let bare = try! NSRegularExpression(pattern: #"^\s*([A-Z]{2}|4K|UHD|FHD|HD|SD|HEVC|VIP|MULTI|NF|AMZ|DSNP|ATV|HBO|D\+)\s*[-:|]\s+"#)
    private static let trailingYear = try! NSRegularExpression(pattern: #"\s*(?:[(\[]\s*((?:19|20)\d{2})\s*[)\]]|[-–]\s*((?:19|20)\d{2}))\s*$"#)

    private static func group(_ m: NSTextCheckingResult, _ i: Int, in s: String) -> String? {
        guard let r = Range(m.range(at: i), in: s) else { return nil }
        return String(s[r])
    }

    /// "EN - The Matrix (1999)" as title, year and tag.
    public static func parseName(_ raw: String, knownYear: Int? = nil) -> (name: String, year: Int?, tag: String?) {
        let original = raw.trimmingCharacters(in: .whitespaces)
        var name = original
        var tag: String?
        let whole = NSRange(name.startIndex..., in: name)
        if let m = bracketed.firstMatch(in: name, range: whole) ?? bare.firstMatch(in: name, range: whole),
           let end = Range(m.range, in: name) {
            let rest = name[end.upperBound...].trimmingCharacters(in: .whitespaces)
            if !rest.isEmpty {
                tag = group(m, 1, in: name)?.trimmingCharacters(in: .whitespaces).uppercased()
                name = rest
            }
        }
        var year = knownYear
        if let m = trailingYear.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)), let r = Range(m.range, in: name) {
            let rest = name[..<r.lowerBound].trimmingCharacters(in: .whitespaces)
            if !rest.isEmpty {
                year = year ?? Int(group(m, 1, in: name) ?? group(m, 2, in: name) ?? "")
                name = rest
            }
        }
        return (name.isEmpty ? original : name, year, tag)
    }

    public static func nameKey(_ name: String) -> String { searchWords(name).joined() }

    public static func sortName(_ name: String) -> String? {
        let lower = name.lowercased()
        for article in ["the ", "a ", "an "] where lower.hasPrefix(article) {
            let rest = name.dropFirst(article.count).trimmingCharacters(in: .whitespaces)
            return rest.isEmpty ? nil : String(name.dropFirst(article.count))
        }
        return nil
    }

    // MARK: Reading the provider

    /// A whole number written out, and nothing else: "12" but not "12abc" or "".
    static func intOf(_ v: JSON) -> Int? { intOf(v.str) }
    static func intOf(_ s: String) -> Int? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return nil }
        let digits = t.hasPrefix("-") ? t.dropFirst() : Substring(t)
        guard !digits.isEmpty, digits.allSatisfy(\.isASCIIDigit) else { return nil }
        return Int(t)
    }

    static func text(_ v: JSON) -> String? {
        let t = v.str.trimmingCharacters(in: .whitespaces)
        return t.isEmpty || t == "null" ? nil : t
    }

    static func url(_ v: JSON) -> String? { text(v).flatMap { $0.hasPrefix("http") ? $0 : nil } }

    static func names(_ v: JSON) -> [String] {
        guard let t = text(v) else { return [] }
        var seen = Set<String>()
        return t.components(separatedBy: CharacterSet(charactersIn: ",/")).map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    static func firstUrl(_ v: JSON) -> String? {
        if v.isArray { return v.array.map(\.str).first { $0.hasPrefix("http") } }
        return url(v)
    }

    static func durationOf(_ t: String?) -> Int? {
        guard let t else { return nil }
        let parts = t.split(separator: ":").map { Int($0.trimmingCharacters(in: .whitespaces)) }
        guard !parts.contains(where: { $0 == nil }) else { return nil }
        let total = parts.reduce(0) { $0 * 60 + ($1 ?? 0) }
        return total > 0 ? total : nil
    }

    public static func readTitles(_ list: JSON, series: Bool) -> [VodTitle] {
        var seen = Set<Int>()
        return list.array.compactMap { titleOf($0, series: series) }.filter { seen.insert($0.id).inserted }
    }

    public static func titleOf(_ f: JSON, series: Bool) -> VodTitle? {
        guard f.isObject, let id = intOf(f[series ? "series_id" : "stream_id"]) else { return nil }
        let raw = f["name"].str.trimmingCharacters(in: .whitespaces).isEmpty ? f["title"].str : f["name"].str
        guard !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        // The list's year when it's a number; else the release date's.
        let release = f["releaseDate"].str.isEmpty ? f["release_date"].str : f["releaseDate"].str
        let listed = intOf(String(f["year"].str.prefix(4))) ?? intOf(String(release.prefix(4)))
        let parsed = parseName(raw, knownYear: listed.flatMap { (1900...2100).contains($0) ? $0 : nil })
        let poster = f[series ? "cover" : "stream_icon"].str
        let rating = Double(f["rating"].str) ?? 0
        let tmdb = (f["tmdb"].str.isEmpty ? f["tmdb_id"].str : f["tmdb"].str).trimmingCharacters(in: .whitespaces)
        func opt(_ k: String) -> String? { let t = f[k].str.trimmingCharacters(in: .whitespaces); return t.isEmpty ? nil : t }
        return VodTitle(series: series, id: id, name: parsed.name, year: parsed.year, tag: parsed.tag,
                        poster: poster.hasPrefix("http") ? poster : nil, rating: rating > 0 ? rating : nil,
                        addedAt: Int(f[series ? "last_modified" : "added"].str.trimmingCharacters(in: .whitespaces)) ?? 0,
                        categoryId: opt("category_id"), tmdbId: tmdb.isEmpty || tmdb == "0" ? nil : tmdb,
                        fileExtension: opt("container_extension"), plot: opt("plot"), genre: opt("genre"), key: nameKey(parsed.name))
    }

    public static func parseMovieInfo(_ root: JSON) -> VodInfo? {
        guard root.isObject else { return nil }
        let info = root["info"], movie = root["movie_data"]
        let cast = names(info["cast"]).isEmpty ? names(info["actors"]) : names(info["cast"])
        let seconds = info["duration_secs"].positive ?? durationOf(text(info["duration"])) ?? 0
        let tmdb = text(info["tmdb_id"])
        return VodInfo(name: text(info["name"]) ?? text(movie["name"]), plot: text(info["plot"]) ?? text(info["description"]),
                       cast: cast, directors: names(info["director"]), genres: names(info["genre"]), durationMs: seconds * 1000,
                       backdrop: firstUrl(info["backdrop_path"]), poster: url(info["movie_image"]) ?? url(info["cover_big"]),
                       releaseDate: text(info["releasedate"]) ?? text(info["release_date"]),
                       rating: info["rating"].num > 0 ? info["rating"].num : nil, tmdbId: tmdb == "0" ? nil : tmdb,
                       fileExtension: text(movie["container_extension"]), ageRating: text(info["age"]) ?? text(info["mpaa"]), seasons: nil)
    }

    public static func parseSeriesInfo(_ root: JSON) -> VodInfo? {
        guard root.isObject else { return nil }
        let info = root["info"]
        var episodes: [VodEpisode] = []
        func readSeason(_ key: String?, _ list: [JSON]) {
            for e in list where e.isObject {
                guard let id = intOf(e["id"]) else { continue }
                let d = e["info"]
                // Season 0 is the specials: a number, not a missing one.
                let season = intOf(e["season"]) ?? key.flatMap(intOf) ?? 1
                let seconds = d["duration_secs"].positive ?? durationOf(text(d["duration"])) ?? 0
                episodes.append(VodEpisode(id: id, season: season, number: intOf(e["episode_num"]),
                                           title: text(e["title"]).map { parseName($0).name } ?? "Episode \(e["episode_num"].str)",
                                           fileExtension: text(e["container_extension"]), plot: text(d["plot"]), still: url(d["movie_image"]),
                                           durationMs: seconds * 1000, airDate: text(d["releasedate"]) ?? text(d["air_date"])))
            }
        }
        let all = root["episodes"]
        if all.isArray { for l in all.array where l.isArray { readSeason(nil, l.array) } }
        else if case .object(let o) = all { for k in o.keys.sorted() where o[k]!.isArray { readSeason(k, o[k]!.array) } }
        var about: [Int: JSON] = [:]
        for s in root["seasons"].array where s.isObject { if let n = intOf(s["season_number"]) { about[n] = s } }
        var seen = Set<Int>()
        let unique = episodes.filter { seen.insert($0.id).inserted }
        let numbers = Array(Set(unique.map(\.season))).sorted()
        let tmdb = [text(info["tmdb"]), text(info["tmdb_id"])].compactMap { $0 }.first { $0 != "0" }
        return VodInfo(name: text(info["name"]), plot: text(info["plot"]), cast: names(info["cast"]), directors: names(info["director"]),
                       genres: names(info["genre"]), durationMs: 0, backdrop: firstUrl(info["backdrop_path"]), poster: url(info["cover"]),
                       releaseDate: text(info["releaseDate"]) ?? text(info["release_date"]), rating: info["rating"].num > 0 ? info["rating"].num : nil,
                       tmdbId: tmdb, fileExtension: nil, ageRating: nil,
                       seasons: numbers.map { n in
                           VodSeason(number: n, name: n == 0 ? "Specials" : "Season \(n)",
                                     poster: about[n].flatMap { url($0["cover_big"]) ?? url($0["cover"]) },
                                     episodes: stableSorted(unique.filter { $0.season == n }) {
                                         ($0.number ?? Int.max, $0.id) < ($1.number ?? Int.max, $1.id)
                                     })
                       })
    }

    public static func movieUrl(_ c: XtreamCredentials, id: Int, ext: String?) -> String {
        "\(c.base)/movie/\(encodeComponent(c.username))/\(encodeComponent(c.password))/\(id).\(ext ?? "mp4")"
    }

    public static func episodeUrl(_ c: XtreamCredentials, id: Int, ext: String?) -> String {
        "\(c.base)/series/\(encodeComponent(c.username))/\(encodeComponent(c.password))/\(id).\(ext ?? "mp4")"
    }

    // MARK: Items

    public static func itemOf(_ t: VodTitle) -> PlexItem {
        PlexItem(ratingKey: t.series ? IptvKey.show(id: t.id).key : IptvKey.movie(id: t.id, ext: t.fileExtension).key, title: t.name,
                 titleSort: sortName(t.name), type: t.series ? "show" : "movie", thumb: t.poster, summary: t.plot, year: t.year,
                 addedAt: t.addedAt, qualities: t.tag.map { [$0] } ?? [], librarySectionId: t.categoryId, serverBase: IPTV_SOURCE)
    }

    public static func seasonItems(showId: Int, showName: String, poster: String?, info: VodInfo) -> [PlexItem] {
        (info.seasons ?? []).map { s in
            PlexItem(ratingKey: IptvKey.season(showId: showId, number: s.number).key, title: s.name, type: "season", thumb: s.poster ?? poster,
                     index: s.number, parentRatingKey: IptvKey.show(id: showId).key, parentTitle: showName, leafCount: s.episodes.count,
                     serverBase: IPTV_SOURCE)
        }
    }

    public static func episodeItems(showId: Int, showName: String, poster: String?, backdrop: String?, season: VodSeason) -> [PlexItem] {
        season.episodes.map { e in
            PlexItem(ratingKey: IptvKey.episode(id: e.id, ext: e.fileExtension).key, title: e.title, type: "episode", thumb: e.still ?? poster,
                     art: backdrop, summary: e.plot, index: e.number, parentIndex: season.number > 0 ? season.number : nil,
                     parentRatingKey: IptvKey.season(showId: showId, number: season.number).key, parentTitle: season.name,
                     grandparentRatingKey: IptvKey.show(id: showId).key, grandparentTitle: showName, grandparentThumb: poster,
                     durationMs: e.durationMs, airDate: e.airDate, serverBase: IPTV_SOURCE)
        }
    }

    public static func detailOf(_ key: String, title: VodTitle?, info: VodInfo?, show: Bool) -> PlexDetail {
        let seasons = info?.seasons
        return PlexDetail(
            ratingKey: key, type: show ? "show" : "movie",
            title: title?.name ?? info?.name.map { parseName($0).name } ?? (show ? "Series" : "Film"),
            summary: info?.plot ?? title?.plot, tagline: nil,
            year: title?.year ?? info?.releaseDate.flatMap { Int($0.prefix(4)) },
            durationMs: show ? 0 : info?.durationMs ?? 0, viewOffsetMs: 0, contentRating: info?.ageRating,
            rating: info?.rating ?? title?.rating, audienceRating: nil, airDate: info?.releaseDate, viewCount: 0, viewedLeafCount: 0,
            studio: nil, thumb: info?.poster ?? title?.poster, art: info?.backdrop, theme: nil,
            genres: !(info?.genres.isEmpty ?? true) ? info!.genres : title?.genre.map { [$0] } ?? [],
            directors: info?.directors ?? [],
            roles: (info?.cast ?? []).prefix(24).map { PlexRole(name: $0, role: nil, thumb: nil, id: nil) },
            writers: [], childCount: seasons?.count ?? 0, leafCount: seasons?.reduce(0) { $0 + $1.episodes.count } ?? 0,
            grandparentTitle: nil, index: nil, parentIndex: nil, logo: nil, qualities: title?.tag.map { [$0] } ?? [],
            versions: [], guid: nil, onDeckKey: nil, onDeckSeasonKey: nil)
    }

    public static func tmdbOf(_ guid: String) -> String? {
        guid.hasPrefix("tmdb://") && guid.count > 7 ? String(guid.dropFirst(7)) : nil
    }
}

// MARK: Where things were left

public let IPTV_WATCHED_FRACTION = 0.9

public struct IptvMark: Codable, Equatable, Sendable {
    public var offsetMs: Int
    public var durationMs: Int
    public var watched: Bool
    /// Epoch seconds.
    public var at: Int
    public var item: PlexItem
}

/** What's been watched from the provider, kept on this device: the provider keeps nothing. */
public struct IptvWatch: Sendable {
    private var marks: [String: IptvMark] = [:]
    private var order: [String] = []

    public init(_ saved: [IptvMark] = []) {
        for m in saved where !m.item.ratingKey.isEmpty { put(m) }
    }

    public var all: [IptvMark] { order.compactMap { marks[$0] } }
    public func mark(_ key: String) -> IptvMark? { marks[key] }

    public mutating func progress(_ item: PlexItem, positionMs: Int, durationMs: Int, now: Int) {
        guard positionMs > 0 else { return }
        let done = durationMs > 0 && Double(positionMs) >= Double(durationMs) * IPTV_WATCHED_FRACTION
        put(IptvMark(offsetMs: done ? 0 : positionMs, durationMs: durationMs, watched: done || marks[item.ratingKey]?.watched == true, at: now, item: item))
    }

    public mutating func setWatched(_ items: [PlexItem], _ watched: Bool, now: Int) {
        for item in items {
            let before = marks[item.ratingKey]
            put(IptvMark(offsetMs: 0, durationMs: before?.durationMs ?? item.durationMs, watched: watched, at: watched ? now : before?.at ?? now, item: item))
        }
    }

    public mutating func forgetProgress(_ key: String) { marks[key]?.offsetMs = 0 }

    public func apply(_ item: PlexItem) -> PlexItem {
        var out = item
        if item.type == "show" || item.type == "season" {
            out.viewedLeafCount = watchedUnder(item.ratingKey, season: item.type == "season")
            return out
        }
        guard let m = marks[item.ratingKey] else { return item }
        out.viewOffsetMs = m.offsetMs
        out.viewCount = m.watched ? max(1, item.viewCount) : 0
        out.durationMs = item.durationMs > 0 ? item.durationMs : m.durationMs
        out.lastViewedAt = m.at
        return out
    }

    public func watchedUnder(_ key: String, season: Bool) -> Int {
        marks.values.filter { $0.watched && (season ? $0.item.parentRatingKey : $0.item.grandparentRatingKey) == key }.count
    }

    public func continueWatching() -> [PlexItem] {
        stableSorted(marks.values.filter { $0.offsetMs > 0 && !$0.watched }) { $0.at > $1.at }.map { apply($0.item) }
    }

    private mutating func put(_ mark: IptvMark) {
        var m = mark
        m.item.viewOffsetMs = 0
        m.item.viewCount = 0
        m.item.lastViewedAt = 0
        let key = m.item.ratingKey
        if marks[key] != nil { order.removeAll { $0 == key } }
        marks[key] = m
        order.append(key)
        while order.count > 3000 { marks[order.removeFirst()] = nil }
    }
}

// MARK: Matching with Plex

public struct TitleIndex: Sendable {
    var tmdb = Set<String>()
    var named = Set<String>()
    var names = Set<String>()

    public static let empty = TitleIndex()
    public var isEmpty: Bool { tmdb.isEmpty && names.isEmpty }

    public func has(tmdbId: String?, key: String, year: Int?) -> Bool {
        if let tmdbId, tmdb.contains(tmdbId) { return true }
        guard !key.isEmpty else { return false }
        return year.map { named.contains("\(key)|\($0)") } ?? names.contains(key)
    }

    public static func ofPlex(_ entries: [PlexIndexEntry]) -> TitleIndex {
        var index = TitleIndex()
        for e in entries {
            if let id = e.guids.lazy.compactMap(Vod.tmdbOf).first { index.tmdb.insert(id) }
            for t in [e.title, e.originalTitle].compactMap({ $0 }) {
                let key = Vod.nameKey(t)
                guard !key.isEmpty else { continue }
                index.names.insert(key)
                if let y = e.year { for year in (y - 1)...(y + 1) { index.named.insert("\(key)|\(year)") } }
            }
        }
        return index
    }

    public static func ofIptv(_ titles: [VodTitle]) -> TitleIndex {
        var index = TitleIndex()
        for t in titles {
            if let id = t.tmdbId { index.tmdb.insert(id) }
            guard !t.key.isEmpty else { continue }
            index.names.insert(t.key)
            if let y = t.year { for year in (y - 1)...(y + 1) { index.named.insert("\(t.key)|\(year)") } }
        }
        return index
    }
}

// MARK: The library

public enum IptvSort: String, Sendable, CaseIterable { case title, added, released, oldest, rated, watched }

public struct IptvGrid: Equatable, Sendable {
    public var items: [PlexItem]
    public var categories: [XtreamCategory]
    public var letters: [(letter: String, count: Int)]
    public static func == (a: IptvGrid, b: IptvGrid) -> Bool {
        a.items == b.items && a.categories == b.categories && a.letters.map(\.letter) == b.letters.map(\.letter) && a.letters.map(\.count) == b.letters.map(\.count)
    }
}

/// The rail letter a title goes under: "Émile" under E, "2012" under #.
public func letterOf(_ item: PlexItem) -> String {
    let first = (item.titleSort ?? item.title).trimmingCharacters(in: .whitespaces).prefix(1)
        .folding(options: .diacriticInsensitive, locale: nil).uppercased()
    return first.count == 1 && first >= "A" && first <= "Z" ? first : "#"
}

/** The provider's catalogue, and every view of it the screens ask for. */
public struct IptvLibrary: Sendable {
    public private(set) var catalog = VodCatalog()
    public var watch = IptvWatch()
    private var moviesById: [Int: VodTitle] = [:]
    private var seriesById: [Int: VodTitle] = [:]
    private var iptvMovies = TitleIndex.empty
    private var iptvShows = TitleIndex.empty
    private var plexMovies = TitleIndex.empty
    private var plexShows = TitleIndex.empty
    private var plexEntries: [String: PlexIndexEntry] = [:]
    private var seriesCache: [Int: VodInfo] = [:]
    private var seriesOrder: [Int] = []

    public init() {}

    public mutating func setCatalog(_ next: VodCatalog) {
        catalog = next
        moviesById = Dictionary(next.movies.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        seriesById = Dictionary(next.series.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        iptvMovies = .ofIptv(next.movies)
        iptvShows = .ofIptv(next.series)
    }

    public mutating func setPlex(movies: [PlexIndexEntry], shows: [PlexIndexEntry]) {
        plexMovies = .ofPlex(movies)
        plexShows = .ofPlex(shows)
        plexEntries = Dictionary((movies + shows).map { (($0.serverBase ?? "") + "|" + $0.ratingKey, $0) }, uniquingKeysWith: { a, _ in a })
    }

    public mutating func clear() {
        setCatalog(VodCatalog())
        seriesCache = [:]
        seriesOrder = []
    }

    public func movie(_ id: Int) -> VodTitle? { moviesById[id] }
    public func series(_ id: Int) -> VodTitle? { seriesById[id] }
    public func cachedSeries(_ id: Int) -> VodInfo? { seriesCache[id] }

    public mutating func keepSeries(_ id: Int, _ info: VodInfo) {
        seriesOrder.removeAll { $0 == id }
        seriesOrder.append(id)
        seriesCache[id] = info
        while seriesOrder.count > 12 { seriesCache[seriesOrder.removeFirst()] = nil }
    }

    public func marked(_ item: PlexItem) -> PlexItem { item.isIptv ? watch.apply(item) : item }

    public func shown(movies: Bool, iptvWins: Bool) -> [VodTitle] {
        let titles = movies ? catalog.movies : catalog.series
        if iptvWins { return titles }
        let plex = movies ? plexMovies : plexShows
        return plex.isEmpty ? titles : titles.filter { !plex.has(tmdbId: $0.tmdbId, key: $0.key, year: $0.year) }
    }

    /// Whether a Plex title gives way to the provider's copy of it.
    public func hides(_ item: PlexItem, iptvWins: Bool) -> Bool {
        guard iptvWins, !item.isIptv else { return false }
        let index: TitleIndex? = item.type == "movie" ? iptvMovies : item.type == "show" ? iptvShows : nil
        guard let index else { return false }
        let entry = plexEntries[(item.serverBase ?? "") + "|" + item.ratingKey]
        let tmdb = entry?.guids.lazy.compactMap(Vod.tmdbOf).first
        return index.has(tmdbId: tmdb, key: Vod.nameKey(item.title), year: item.year)
    }

    public func browse(movies: Bool, sort: IptvSort, categoryId: String?, unwatchedOnly: Bool, iptvWins: Bool) -> IptvGrid {
        let all = shown(movies: movies, iptvWins: iptvWins)
        let titles = categoryId.map { id in all.filter { $0.categoryId == id } } ?? all
        var rating: [String: Double] = [:]
        for t in titles { rating[t.series ? IptvKey.show(id: t.id).key : IptvKey.movie(id: t.id, ext: t.fileExtension).key] = t.rating ?? 0 }
        var items = titles.map { marked(Vod.itemOf($0)) }
        if unwatchedOnly { items = items.filter { !$0.isWatched } }
        let by: (PlexItem, PlexItem) -> Bool
        switch sort {
        case .title:
            by = { a, b in
                let la = letterOf(a), lb = letterOf(b)
                if la != lb { return la == "#" ? true : lb == "#" ? false : la < lb }
                return (a.titleSort ?? a.title).localizedCaseInsensitiveCompare(b.titleSort ?? b.title) == .orderedAscending
            }
        case .added: by = { $0.addedAt > $1.addedAt }
        case .released: by = { ($0.year ?? 0) > ($1.year ?? 0) }
        case .oldest: by = { ($0.year ?? Int.max) < ($1.year ?? Int.max) }
        case .rated: by = { (rating[$0.ratingKey] ?? 0) > (rating[$1.ratingKey] ?? 0) }
        case .watched: by = { $0.lastViewedAt > $1.lastViewedAt }
        }
        items = stableSorted(items, by: by)
        let used = Set(all.compactMap(\.categoryId))
        let categories = (movies ? catalog.movieCategories : catalog.seriesCategories).filter { used.contains($0.id) }
        var letters: [(letter: String, count: Int)] = []
        if sort == .title {
            // The title order keeps each letter's titles together ("#" first).
            for item in items {
                let l = letterOf(item)
                if letters.last?.letter == l { letters[letters.count - 1].count += 1 } else { letters.append((l, 1)) }
            }
        }
        return IptvGrid(items: items, categories: categories, letters: letters)
    }

    public func newest(movies: Bool, iptvWins: Bool, count: Int = 40) -> [PlexItem] {
        stableSorted(shown(movies: movies, iptvWins: iptvWins)) { $0.addedAt > $1.addedAt }.prefix(count).map { marked(Vod.itemOf($0)) }
    }

    public func search(_ query: String, iptvWins: Bool, count: Int = 40) -> [PlexItem] {
        let wanted = searchWords(query)
        guard !wanted.isEmpty else { return [] }
        var candidates: [PlexItem] = []
        for t in shown(movies: true, iptvWins: iptvWins) + shown(movies: false, iptvWins: iptvWins) where wanted.allSatisfy({ t.key.contains($0) }) {
            candidates.append(marked(Vod.itemOf(t)))
            if candidates.count >= count * 4 { break }
        }
        return Array(relevant(query, candidates).prefix(count))
    }

    /// More like this: the newest from the same category.
    public func related(_ item: PlexItem, iptvWins: Bool, count: Int = 40) -> [PlexItem] {
        let id: Int, movies: Bool
        switch IptvKey.parse(item.ratingKey) {
        case .movie(let m, _): id = m; movies = true
        case .show(let s): id = s; movies = false
        default: return []
        }
        guard let own = movies ? moviesById[id] : seriesById[id], let category = own.categoryId else { return [] }
        return stableSorted(shown(movies: movies, iptvWins: iptvWins).filter { $0.categoryId == category && $0.id != id }) { $0.addedAt > $1.addedAt }
            .prefix(count).map { marked(Vod.itemOf($0)) }
    }
}
