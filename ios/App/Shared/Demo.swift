import SwiftUI
import UIKit
import ReelyCore

/**
 * A stand-in Plex, for screenshots and tests: started with -demo, the app signs in to this
 * instead of plex.tv, the way the LG and Roku apps' tests use a fake server. Pictures are
 * drawn here, a colour and the title, so every screen can be seen filled.
 */
final class DemoTransport: HttpTransport, @unchecked Sendable {
    static let server = "https://demo.reely.local:32400"
    /// What a test asked to see: signed out ("signin"), the code on screen ("code"), or signed in.
    let scene: String

    init(scene: String) { self.scene = scene }

    func send(_ request: HttpRequest) async throws -> HttpResponse {
        guard let url = URL(string: request.url) else { return HttpResponse(status: 404, data: Data()) }
        if url.path.hasPrefix("/photo/") { return HttpResponse(status: 200, data: DemoTransport.picture(for: request.url)) }
        if url.path == "/player_api.php" {
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let body = DemoTransport.panel(query.first { $0.name == "action" }?.value, streamId: query.first { $0.name == "stream_id" }?.value.flatMap(Int.init))
            return HttpResponse(status: 200, data: Data(body.utf8))
        }
        if url.path.hasPrefix("/api/v1/") { return HttpResponse(status: 200, data: Data(DemoTransport.reely(url.path).utf8)) }
        guard let body = DemoTransport.answer(url.path) else { return HttpResponse(status: 404, data: Data()) }
        return HttpResponse(status: 200, data: Data(body.utf8))
    }

    static func answer(_ path: String) -> String? {
        switch path {
        case "/api/v2/pins": return #"{"id": 1, "code": "R3LY"}"#
        case "/api/v2/pins/1": return #"{"authToken": null}"#
        case "/api/v2/user": return #"{"uuid": "u1", "title": "Ann"}"#
        case "/api/v2/home/users": return #"{"users": [{"uuid": "u1", "title": "Ann", "admin": true}, {"uuid": "u2", "title": "Kid", "restricted": true, "protected": true}]}"#
        case "/api/v2/resources":
            return """
            [{"name": "Living Room", "provides": "server", "owned": true, "accessToken": "demo",
              "connections": [{"uri": "\(server)", "address": "10.0.0.2", "port": 32400, "local": true}]}]
            """
        case "/identity": return "{}"
        case "/library/sections":
            return #"{"MediaContainer": {"Directory": [{"key": "1", "title": "Movies", "type": "movie"}, {"key": "2", "title": "TV Shows", "type": "show"}]}}"#
        case "/hubs":
            return container([
                episode("e12", show: "Northbound", season: 1, index: 2, offset: 1_400_000, viewed: 50),
                movie("m3", "The Long Field", year: 2023, offset: 2_300_000, viewed: 40),
                episode("e31", show: "Harbour Lights", season: 3, index: 1, offset: 0, viewed: 30),
            ], hub: true)
        case "/playlists": return #"{"MediaContainer": {"Metadata": [{"ratingKey": "p1", "type": "playlist", "title": "Road Trip", "leafCount": 12}]}}"#
        case "/library/sections/1/all":
            return container(["Low Orbit", "Paper Moons", "The Long Field", "Quiet Hours", "Red Coast", "Afterglow", "Northern Line"].enumerated().map { i, t in
                movie("m\(i + 1)", t, year: 2025 - i, offset: 0, viewed: 0, added: 100 - i)
            })
        case "/library/sections/2/all":
            return container((1...6).map { i in
                episode("n\(i)", show: ["Northbound", "Harbour Lights", "The Orchard", "Static", "Night Shift", "Coastline"][i - 1],
                        season: 1, index: i, offset: 0, viewed: 0, added: 90 - i)
            })
        case "/library/metadata/m1":
            return #"{"MediaContainer": {"Metadata": [\#(movieDetail)]}}"#
        case "/library/metadata/show-northbound":
            return """
            {"MediaContainer": {"Metadata": [{"ratingKey": "show-northbound", "type": "show", "title": "Northbound", "year": 2024, "childCount": 2,
              "leafCount": 16, "viewedLeafCount": 1, "contentRating": "TV-14", "studio": "Harbour Pictures", "art": "/art/northbound", "thumb": "/thumb/northbound",
              "summary": "A ferry crew on the last northern route keeps the islands connected through one long winter.",
              "Genre": [{"tag": "Drama"}], "Role": [{"tag": "Ada Moreno", "role": "Captain Lise", "id": "1"}, {"tag": "Tom Hale", "role": "Ivo", "id": "2"}],
              "OnDeck": {"Metadata": [{"ratingKey": "nb12", "parentRatingKey": "nb-s1"}]}}]}}
            """
        case "/library/metadata/show-northbound/children":
            return #"{"MediaContainer": {"Metadata": [{"ratingKey": "nb-s1", "type": "season", "title": "Season 1", "index": 1, "leafCount": 8}, {"ratingKey": "nb-s2", "type": "season", "title": "Season 2", "index": 2, "leafCount": 8}]}}"#
        case "/library/metadata/nb-s1/children", "/library/metadata/nb-s2/children":
            return container((1...8).map { i in
                episode("nb1\(i)", show: "Northbound", season: 1, index: i, offset: i == 2 ? 1_400_000 : 0, viewed: i == 1 ? 10 : 0)
                    .replacingOccurrences(of: "\"viewOffset\"", with: i == 1 ? "\"viewCount\": 1, \"viewOffset\"" : "\"viewOffset\"")
            })
        case "/library/metadata/m1/related", "/library/metadata/show-northbound/related":
            return #"{"MediaContainer": {"Hub": [{"Metadata": [\#(["Paper Moons", "Quiet Hours", "Red Coast", "Afterglow"].enumerated().map { i, t in movie("r\(i)", t, year: 2020 + i, offset: 0, viewed: 0) }.joined(separator: ","))]}]}}"#
        case "/hubs/search":
            return #"{"MediaContainer": {"Hub": [{"type": "movie", "Metadata": [\#(movie("m1", "Low Orbit", year: 2025, offset: 0, viewed: 0))]}, {"type": "actor", "Directory": [{"id": "1", "tag": "Ada Moreno"}]}]}}"#
        case "/library/sections/1/genre":
            return #"{"MediaContainer": {"Directory": [{"key": "1", "title": "Drama"}, {"key": "2", "title": "Science Fiction"}, {"key": "3", "title": "Thriller"}]}}"#
        case "/library/sections/1/decade":
            return #"{"MediaContainer": {"Directory": [{"key": "2020", "title": "2020s"}, {"key": "2010", "title": "2010s"}]}}"#
        case "/library/sections/1/firstCharacter":
            return #"{"MediaContainer": {"Directory": [{"title": "A", "size": 1}, {"title": "L", "size": 1}, {"title": "N", "size": 1}, {"title": "P", "size": 1}, {"title": "Q", "size": 1}, {"title": "R", "size": 1}, {"title": "T", "size": 1}]}}"#
        case "/library/sections/1/collections":
            return #"{"MediaContainer": {"Metadata": [{"ratingKey": "c1", "type": "collection", "title": "Space", "childCount": 4, "thumb": "/thumb/c1"}]}}"#
        default: return nil
        }
    }

    // MARK: A stand-in Reely

    static func reelyTitle(_ kind: String, _ id: Int, _ title: String, _ year: Int) -> String {
        #"{"kind": "\#(kind)", "tmdbId": \#(id), "title": "\#(title)", "year": \#(year), "poster": "\#(server)/photo/reely-\#(id)", "overview": "A story people keep asking for, finally within reach."}"#
    }

    static func reely(_ path: String) -> String {
        let movies = [("Glass Harbor", 2025), ("The Quiet Year", 2024), ("Signal Lost", 2025), ("Paper Moons", 2023), ("Far Fields", 2022)]
            .enumerated().map { reelyTitle("movie", 900 + $0.offset, $0.element.0, $0.element.1) }
        let shows = [("Saltwater", 2024), ("The Long Night Shift", 2025), ("Orchard Lane", 2023), ("Northbound", 2024)]
            .enumerated().map { reelyTitle("show", 800 + $0.offset, $0.element.0, $0.element.1) }
        switch path {
        case "/api/v1/auth/plex/token": return #"{"status": "ok"}"#
        case "/api/v1/explore":
            return #"{"movies": [\#(movies.joined(separator: ","))], "shows": [\#(shows.joined(separator: ","))], "popularMovies": [\#(movies.reversed().joined(separator: ","))], "popularShows": [\#(shows.reversed().joined(separator: ","))]}"#
        case "/api/v1/requests":
            return #"{"requests": [{"id": 3, "kind": "movie", "tmdbId": 901, "title": "The Quiet Year", "year": 2024, "poster": "\#(server)/photo/reely-901", "status": "approved"}]}"#
        case "/api/v1/movies": return #"{"movies": [{"tmdbId": 901, "filePath": "", "downloading": true}]}"#
        case "/api/v1/shows": return #"{"shows": [{"tmdbId": 801, "onDisk": 4, "aired": 10}]}"#
        case "/api/v1/auth/me": return #"{"user": {"role": "user", "defaultLibraryId": 3}}"#
        case "/api/v1/libraries": return #"{"libraries": [{"id": 1, "name": "Movies", "kind": "movies"}, {"id": 3, "name": "TV Shows", "kind": "shows"}, {"id": 4, "name": "Kids TV", "kind": "shows"}]}"#
        case "/api/v1/preview/show/800":
            return #"{"inLibraries": [], "preview": {\#(String(reelyTitle("show", 800, "Saltwater", 2024).dropFirst().dropLast())), "backdrop": "\#(server)/photo/art-reely-800", "genres": ["Drama", "Mystery"], "status": "Returning Series", "seasons": [{"number": 1, "name": "Season 1", "episodes": [{}, {}]}, {"number": 2, "name": "Season 2", "episodes": [{}]}, {"number": 3, "name": "Season 3", "episodes": [{}]}]}}"#
        default: return "{}"
        }
    }

    // MARK: A stand-in live TV provider

    static let channels: [(Int, String, [String])] = [
        (101, "News 24", ["Morning Briefing", "The World Today", "Business Live", "Weather Watch", "Evening Headlines", "Late Edition"]),
        (102, "UK: Sport One", ["Match Day Live", "Goals of the Week", "Racing from Ascot", "Cricket Highlights", "The Fight Night", "Sports Desk"]),
        (103, "Harbour TV", ["Northbound", "Coastline", "The Orchard", "Night Shift", "Static", "Harbour Lights"]),
        (104, "Film Four Plus", ["Low Orbit", "Paper Moons", "The Long Field", "Quiet Hours", "Red Coast", "Afterglow"]),
        (105, "Kids Club", ["Cartoon Morning", "Little Builders", "Story Time", "Puzzle Pals", "Wild Things", "Bedtime Tales"]),
        (106, "Discovery Deep", ["Ocean Floors", "Planet Builders", "Ice Worlds", "The Big Migration", "Volcano Week", "Night Skies"]),
        (107, "Music Hits", ["Top 40", "Throwback Hour", "Acoustic Sessions", "Live at the Hall", "Chart Countdown", "Late Mix"]),
    ]

    static func panel(_ action: String?, streamId: Int?) -> String {
        switch action {
        case nil:
            return #"{"user_info": {"auth": 1, "status": "Active", "max_connections": "2", "active_cons": "1", "exp_date": "1893456000"}, "server_info": {"timezone": "UTC"}}"#
        case "get_live_categories":
            return #"[{"category_id": "1", "category_name": "Entertainment"}, {"category_id": "2", "category_name": "News"}, {"category_id": "3", "category_name": "Sport"}, {"category_id": "4", "category_name": "Kids"}]"#
        case "get_live_streams":
            return "[" + channels.enumerated().map { i, c in
                #"{"stream_id": \#(c.0), "num": \#(i + 1), "name": "\#(c.1)", "epg_channel_id": "c\#(c.0)", "tv_archive": \#(i < 3 ? 1 : 0), "tv_archive_duration": 3}"#
            }.joined(separator: ",") + "]"
        case "get_short_epg", "get_simple_data_table":
            guard let streamId, let channel = channels.first(where: { $0.0 == streamId }) else { return #"{"epg_listings": []}"# }
            // Half-hour and hour programmes around now, the same each run.
            let now = Int(Date().timeIntervalSince1970)
            var at = now / 1800 * 1800 - 3600
            var items: [String] = []
            for i in 0..<10 {
                let length = (stableHash(channel.1 + String(i)) % 2 + 1) * 1800
                let title = Data(channel.2[i % channel.2.count].utf8).base64EncodedString()
                let about = Data("A look at what's happening, live from the studio.".utf8).base64EncodedString()
                items.append(#"{"title": "\#(title)", "description": "\#(about)", "start_timestamp": "\#(at)", "stop_timestamp": "\#(at + length)"}"#)
                at += length
            }
            return #"{"epg_listings": [\#(items.joined(separator: ","))]}"#
        default:
            return "[]"
        }
    }

    static let movieDetail = """
    {"ratingKey": "m1", "type": "movie", "title": "Low Orbit", "year": 2025, "duration": 6600000, "viewOffset": 2300000, "contentRating": "PG-13",
     "studio": "North Light", "art": "/art/m1", "thumb": "/thumb/m1", "tagline": "Four people. One last flight.",
     "summary": "A crew of four sets out on the last flight of an old station, and finds it isn't empty.",
     "Genre": [{"tag": "Science Fiction"}], "Director": [{"tag": "Mara Lind"}],
     "Role": [{"tag": "Ada Moreno", "role": "Commander Hale", "id": "1"}, {"tag": "Tom Hale", "role": "Ivo", "id": "2"}, {"tag": "June Park", "role": "Sana", "id": "3"}],
     "Media": [{"videoResolution": "4k", "videoCodec": "hevc", "audioCodec": "truehd", "audioChannels": 8, "bitrate": 58400, "Part": [{"size": 62400000000, "Stream": [{"streamType": 1, "DOVIPresent": true}]}]},
               {"videoResolution": "1080", "videoCodec": "h264", "audioCodec": "ac3", "audioChannels": 6, "bitrate": 9800, "Part": [{"size": 9800000000, "Stream": [{"streamType": 1}]}]}]}
    """

    private static func container(_ items: [String], hub: Bool = false) -> String {
        let list = items.joined(separator: ",")
        return hub ? #"{"MediaContainer": {"Hub": [{"Metadata": [\#(list)]}]}}"# : #"{"MediaContainer": {"Metadata": [\#(list)]}}"#
    }

    private static func movie(_ key: String, _ title: String, year: Int, offset: Int, viewed: Int, added: Int = 0) -> String {
        """
        {"ratingKey": "\(key)", "type": "movie", "title": "\(title)", "year": \(year), "duration": 6600000, "viewOffset": \(offset),
         "lastViewedAt": \(viewed), "addedAt": \(added), "thumb": "/thumb/\(key)", "art": "/art/\(key)",
         "summary": "A crew of four sets out on the last flight of an old station.",
         "Media": [{"videoResolution": "4k", "audioChannels": 6, "Part": [{"Stream": [{"streamType": 1, "DOVIPresent": true}]}]}]}
        """
    }

    private static func episode(_ key: String, show: String, season: Int, index: Int, offset: Int, viewed: Int, added: Int = 0) -> String {
        """
        {"ratingKey": "\(key)", "type": "episode", "title": "Episode \(index)", "grandparentTitle": "\(show)",
         "grandparentRatingKey": "show-\(show.lowercased().replacingOccurrences(of: " ", with: ""))", "parentIndex": \(season), "index": \(index), "duration": 3000000,
         "viewOffset": \(offset), "lastViewedAt": \(viewed), "addedAt": \(added), "thumb": "/thumb/\(key)", "art": "/art/\(key)",
         "grandparentThumb": "/thumb/show-\(key)", "summary": "The tide comes in early, and nobody is ready for it."}
        """
    }

    /// A picture: a colour from the address, and a soft light across it.
    static func picture(for address: String) -> Data {
        let wide = address.contains("art")
        let size = wide ? CGSize(width: 480, height: 270) : CGSize(width: 300, height: 450)
        let hues: [UInt32] = [0x2E6BFF, 0x14B8A6, 0x8B5CF6, 0xEC4899, 0xFF8A3D, 0xF5C542, 0x22C55E]
        let pick = hues[stableHash(address) % hues.count]
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.pngData { context in
            let colors = [UIColor(Color(hex: pick)).cgColor, UIColor(Color(hex: 0x08090B)).cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1])!
            context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
        }
    }
}

/// The same number for the same text on every run, unlike hashValue.
func stableHash(_ text: String) -> Int {
    var h = 5381
    for b in text.utf8 { h = (h &* 33 &+ Int(b)) & 0x7fffffff }
    return h
}
