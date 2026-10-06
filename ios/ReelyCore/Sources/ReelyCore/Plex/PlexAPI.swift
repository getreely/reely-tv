import Foundation

/** Who's asking: set once at start, sent with everything. */
public struct PlexIdentity: Sendable, Equatable {
    public var clientId: String
    public var version: String
    public var platform: String
    public var device: String
    public var deviceName: String
    public init(clientId: String, version: String, platform: String, device: String, deviceName: String) {
        self.clientId = clientId; self.version = version; self.platform = platform; self.device = device; self.deviceName = deviceName
    }
}

public let PLEX_TYPE_MOVIE = 1
public let PLEX_TYPE_SHOW = 2
public let PLEX_TYPE_EPISODE = 4
private let AUDIO_STREAM = 2
private let SUBTITLE_STREAM = 3

/**
 * Plex, as the Android app talks to it: plex.tv for signing in and the list of servers,
 * then each server for its libraries, pages, files and where things are up to.
 */
public struct PlexAPI: Sendable {
    public static let plexTv = "https://plex.tv"
    public static let discover = "https://discover.provider.plex.tv"
    public static let product = "Reely TV"

    public var http: Http
    public var identity: PlexIdentity
    public var plexTv: String
    public var discover: String

    public init(http: Http = Http(), identity: PlexIdentity, plexTv: String = PlexAPI.plexTv, discover: String = PlexAPI.discover) {
        self.http = http; self.identity = identity; self.plexTv = plexTv; self.discover = discover
    }

    public func headers(_ token: String? = nil) -> [String: String] {
        var h = [
            "accept": "application/json",
            "X-Plex-Product": PlexAPI.product,
            "X-Plex-Version": identity.version,
            "X-Plex-Client-Identifier": identity.clientId,
            "X-Plex-Platform": identity.platform,
            "X-Plex-Device": identity.device,
            "X-Plex-Device-Name": identity.deviceName,
        ]
        if let token, !token.isEmpty { h["X-Plex-Token"] = token }
        return h
    }

    // MARK: Signing in

    /// A sign-in PIN: the short one to type at plex.tv/link, or a strong one only a link carries.
    public func createPin(strong: Bool = false) async throws -> PlexPin {
        var h = headers()
        h["content-type"] = "application/x-www-form-urlencoded"
        let json = try await http.json(
            HttpRequest(url: "\(plexTv)/api/v2/pins", method: "POST", headers: h, body: Data("strong=\(strong)".utf8)),
            failure: "Couldn't get a sign-in code from Plex. Try again.")
        return PlexPin(id: json["id"].int, code: json["code"].str)
    }

    /// Plex's own sign-in page for a strong PIN, for the QR code.
    public func authUrl(code: String) -> String {
        "https://app.plex.tv/auth#?clientID=\(encodeComponent(identity.clientId))&code=\(encodeComponent(code))&context%5Bdevice%5D%5Bproduct%5D=\(encodeComponent(PlexAPI.product))"
    }

    /// The account's token once the code's been entered; nil while it's still waiting.
    public func claimPin(id: Int) async -> String? {
        guard let json = try? await http.json(HttpRequest(url: "\(plexTv)/api/v2/pins/\(id)", headers: headers())) else { return nil }
        let token = json["authToken"].str
        return token.isEmpty || token == "null" ? nil : token
    }

    public func account(token: String) async -> PlexHomeUser? {
        guard let json = try? await http.json(HttpRequest(url: "\(plexTv)/api/v2/user", headers: headers(token))) else { return nil }
        return PlexAPI.homeUser(json)
    }

    public func homeUsers(token: String) async -> [PlexHomeUser] {
        guard let json = try? await http.json(HttpRequest(url: "\(plexTv)/api/v2/home/users", headers: headers(token))) else { return [] }
        return PlexAPI.homeUsers(from: json)
    }

    /// The users in a home/users answer: the list, or the Home around it.
    public static func homeUsers(from json: JSON) -> [PlexHomeUser] {
        let users = json.isArray ? json.array : json["users"].array
        return users.compactMap(homeUser)
    }

    static func homeUser(_ entry: JSON) -> PlexHomeUser? {
        let uuid = entry["uuid"].str
        guard !uuid.isEmpty else { return nil }
        let thumb = entry["thumb"].str
        return PlexHomeUser(
            uuid: uuid,
            title: entry["title"].text ?? entry["username"].text ?? "Plex user",
            thumb: thumb.hasPrefix("http") ? thumb : nil,
            protected: entry["protected"].isTrue,
            admin: entry["admin"].isTrue,
            restricted: entry["restricted"].isTrue)
    }

    /// Becomes another member of the Home; their PIN when they have one.
    public func switchHomeUser(token: String, uuid: String, pin: String?) async throws -> String {
        let url = "\(plexTv)/api/v2/home/users/\(uuid)/switch" + (pin.map { "?pin=\(encodeComponent($0))" } ?? "")
        let response: HttpResponse
        do {
            response = try await http.ask(HttpRequest(url: url, method: "POST", headers: headers(token)))
        } catch let e as HttpError where e.status == 401 || e.status == 403 {
            throw HttpError(pin != nil ? "That PIN isn't right. Try again." : "Plex didn't allow switching to this profile.", status: e.status)
        } catch {
            throw HttpError("Couldn't switch profiles. Try again.")
        }
        let next = JSON.parse(response.data)?["authToken"].str ?? ""
        guard !next.isEmpty, next != "null" else { throw HttpError("Couldn't switch profiles. Try again.") }
        return next
    }

    // MARK: Servers

    /// The account's servers and those shared with it, its own first.
    public func servers(token: String) async throws -> [PlexServer] {
        let json = try await http.json(
            HttpRequest(url: "\(plexTv)/api/v2/resources?includeHttps=1&includeRelay=1", headers: headers(token)),
            failure: "Couldn't load your Plex servers. Try again.")
        return PlexAPI.servers(from: json, token: token)
    }

    public static func servers(from json: JSON, token: String) -> [PlexServer] {
        var list: [PlexServer] = []
        for resource in json.array where resource["provides"].str.contains("server") {
            let connections = connectionOrder(resource["connections"].array.map {
                PlexConnection(uri: $0["uri"].str, address: $0["address"].str, port: $0["port"].positive ?? 32400,
                               local: $0["local"].isTrue, relay: $0["relay"].isTrue)
            })
            if connections.isEmpty { continue }
            list.append(PlexServer(name: resource["name"].text ?? "Plex Media Server",
                                   accessToken: resource["accessToken"].text ?? token,
                                   connections: connections, owned: resource["owned"].isTrue))
        }
        // Signing in connects to the first that answers: the account's own before a friend's.
        return stableSorted(list) { $0.owned && !$1.owned }
    }

    /**
     * Best first: at home, then the internet, then Plex's relay; and each home address again
     * over plain http, for routers that won't look up plex.direct names.
     */
    public static func connectionOrder(_ connections: [PlexConnection]) -> [String] {
        let sorted = stableSorted(connections.filter { !$0.uri.isEmpty }) { a, b in
            if a.relay != b.relay { return !a.relay }
            return a.local && !b.local
        }
        var out: [String] = []
        for c in sorted {
            if !out.contains(c.uri) { out.append(c.uri) }
            if c.local && !c.relay && !c.address.isEmpty {
                let plain = "http://\(c.address):\(c.port)"
                if !out.contains(plain) { out.append(plain) }
            }
        }
        return out
    }

    public func reachable(_ server: PlexServer, _ uri: String) async -> Bool {
        (try? await http.ask(HttpRequest(url: "\(uri)/identity", headers: ["accept": "application/json", "X-Plex-Token": server.accessToken], timeout: 5))) != nil
    }

    /// The best address that answers. All asked at once; the best of those that answer wins.
    public func firstReachable(_ server: PlexServer) async -> String? {
        await withTaskGroup(of: (Int, Bool).self) { group in
            for (i, uri) in server.connections.enumerated() {
                group.addTask { (i, await reachable(server, uri)) }
            }
            var answers = [Bool?](repeating: nil, count: server.connections.count)
            for await (i, ok) in group {
                answers[i] = ok
                // The best is known once everything better than it has said no.
                if let best = answers.firstIndex(where: { $0 != false }), answers[best] == true {
                    group.cancelAll()
                    return server.connections[best]
                }
            }
            return answers.firstIndex(where: { $0 == true }).map { server.connections[$0] }
        }
    }

    // MARK: Libraries

    func container(_ url: String, _ token: String) async throws -> JSON {
        let json = try await http.json(HttpRequest(url: url, headers: headers(token)), failure: "Your Plex server couldn't do that. Try again.")
        return json["MediaContainer"]
    }

    public func sections(_ base: String, _ token: String) async throws -> [PlexSection] {
        try await container("\(base)/library/sections", token)["Directory"].array
            .map { PlexSection(key: $0["key"].str, title: $0["title"].str, type: $0["type"].str) }
            .filter { !$0.key.isEmpty }
    }

    private func filterValues(_ base: String, _ token: String, _ section: String, _ field: String, _ type: Int) async throws -> [PlexGenre] {
        try await container("\(base)/library/sections/\(section)/\(field)?type=\(type)", token)["Directory"].array
            .map { PlexGenre(id: $0["key"].str, title: $0["title"].str) }
            .filter { !$0.id.isEmpty && !$0.title.isEmpty }
    }

    public func genres(_ base: String, _ token: String, section: String, type: Int) async throws -> [PlexGenre] {
        try await filterValues(base, token, section, "genre", type)
    }

    public func decades(_ base: String, _ token: String, section: String, type: Int) async throws -> [PlexGenre] {
        stableSorted(try await filterValues(base, token, section, "decade", type)) { (Int($0.id) ?? 0) > (Int($1.id) ?? 0) }
    }

    public func firstCharacters(_ base: String, _ token: String, section: String, type: Int, filters: String) async throws -> [PlexLetter] {
        try await container("\(base)/library/sections/\(section)/firstCharacter?type=\(type)\(filters)", token)["Directory"].array
            .map { d in
                var letter = d["title"].str
                if letter.isEmpty { letter = d["key"].str.removingPercentEncoding ?? "" }
                return PlexLetter(letter: letter, count: d["size"].int)
            }
            .filter { !$0.letter.isEmpty && $0.count > 0 }
    }

    public func items(_ base: String, _ token: String, _ path: String, limit: Int = 200, offset: Int = 0) async throws -> [PlexItem] {
        let sep = path.contains("?") ? "&" : "?"
        return try await container("\(base)\(path)\(sep)X-Plex-Container-Start=\(offset)&X-Plex-Container-Size=\(limit)", token)["Metadata"].array
            .map { var item = PlexAPI.parseItem($0); item.serverBase = base; return item }
    }

    /// Continue Watching as Plex's own apps build it; On Deck for a server too old for that.
    public func continueWatching(_ base: String, _ token: String) async throws -> [PlexItem] {
        let c = try await container("\(base)/hubs?identifier=\(encodeComponent("home.continue,home.ondeck"))&count=40", token)
        guard c["Hub"].isArray else { return try await items(base, token, "/library/onDeck", limit: 40) }
        return PlexAPI.metadata(of: c["Hub"].array).filter(\.isPlayable).map { var i = $0; i.serverBase = base; return i }
    }

    public func recentlyAdded(_ base: String, _ token: String, section: String, type: Int, limit: Int = 60, offset: Int = 0) async throws -> [PlexItem] {
        try await items(base, token, "/library/sections/\(section)/all?type=\(type)&sort=addedAt:desc", limit: limit, offset: offset)
    }

    public func children(_ base: String, _ token: String, ratingKey: String) async throws -> [PlexItem] {
        try await items(base, token, "/library/metadata/\(ratingKey)/children", limit: 400)
    }

    public func episodes(_ base: String, _ token: String, of ratingKey: String) async throws -> [PlexItem] {
        try await items(base, token, "/library/metadata/\(ratingKey)/allLeaves", limit: 2000).filter { $0.type == "episode" }
    }

    /**
     * Which episode to play next: the one part watched, else the first unwatched after the
     * last watched, else the first unwatched, else the first. Specials aside, unless they're
     * all there is.
     */
    public static func nextEpisode(_ episodes: [PlexItem]) -> PlexItem? {
        if let partly = episodes.first(where: { $0.resumeFraction != nil && !$0.isWatched }) { return partly }
        let main = episodes.filter { $0.parentIndex != nil }
        let list = main.isEmpty ? episodes : main
        if let last = list.lastIndex(where: \.isWatched), let after = list[(last + 1)...].first(where: { !$0.isWatched }) {
            return after
        }
        return list.first(where: { !$0.isWatched }) ?? list.first
    }

    public func playlists(_ base: String, _ token: String) async throws -> [PlexItem] {
        try await container("\(base)/playlists?playlistType=video", token)["Metadata"].array
            .filter { $0["leafCount"].num > 0 }
            .map { m in
                var item = PlexAPI.parseItem(m)
                item.serverBase = base
                item.thumb = m["composite"].text ?? m["thumb"].text
                return item
            }
    }

    public func playlistItems(_ base: String, _ token: String, ratingKey: String) async throws -> [PlexItem] {
        try await items(base, token, "/playlists/\(ratingKey)/items", limit: 500)
    }

    public func collections(_ base: String, _ token: String, section: String) async throws -> [PlexItem] {
        try await items(base, token, "/library/sections/\(section)/collections", limit: 500)
    }

    public func collectionItems(_ base: String, _ token: String, ratingKey: String) async throws -> [PlexItem] {
        try await items(base, token, "/library/collections/\(ratingKey)/children", limit: 500)
    }

    public func withActor(_ base: String, _ token: String, section: String, type: Int, personId: String) async throws -> [PlexItem] {
        try await items(base, token, "/library/sections/\(section)/all?type=\(type)&actor=\(personId)&sort=originallyAvailableAt:desc", limit: 300)
    }

    public func libraryEntries(_ base: String, _ token: String, section: String, type: Int) async throws -> [PlexIndexEntry] {
        let c = try await container("\(base)/library/sections/\(section)/all?type=\(type)&includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=100000", token)
        return PlexAPI.entries(in: c["Metadata"], base: base)
    }

    public static func entries(in metadata: JSON, base: String?) -> [PlexIndexEntry] {
        metadata.array.map { m in
            PlexIndexEntry(ratingKey: m["ratingKey"].str, serverBase: base, title: m["title"].str,
                           originalTitle: m["originalTitle"].text, year: m["year"].positive,
                           guids: m["Guid"].array.map { $0["id"].str }.filter { !$0.isEmpty })
        }
    }

    // MARK: A title's page

    public func detail(_ base: String, _ token: String, ratingKey: String) async throws -> PlexDetail? {
        let entry = try await container("\(base)/library/metadata/\(ratingKey)?includeOnDeck=1", token)["Metadata"][0]
        return entry.isNull ? nil : PlexAPI.parseDetail(entry)
    }

    public static func parseDetail(_ e: JSON) -> PlexDetail {
        let onDeck = e["OnDeck"]["Metadata"][0]
        let type = e["type"].str
        return PlexDetail(
            ratingKey: e["ratingKey"].str, type: type, title: e["title"].str,
            summary: e["summary"].text, tagline: e["tagline"].text, year: e["year"].positive,
            durationMs: e["duration"].int, viewOffsetMs: e["viewOffset"].int,
            contentRating: e["contentRating"].text,
            rating: e["rating"].num > 0 ? e["rating"].num : nil,
            audienceRating: e["audienceRating"].num > 0 ? e["audienceRating"].num : nil,
            airDate: e["originallyAvailableAt"].text, viewCount: e["viewCount"].int,
            viewedLeafCount: e["viewedLeafCount"].int, studio: e["studio"].text,
            thumb: e["thumb"].text, art: e["art"].text, theme: e["theme"].text,
            genres: tags(e, "Genre"), directors: tags(e, "Director"),
            roles: e["Role"].array.map { PlexRole(name: $0["tag"].str, role: $0["role"].text, thumb: $0["thumb"].text, id: $0["id"].text) }
                .filter { !$0.name.isEmpty },
            writers: tags(e, "Writer"), childCount: e["childCount"].int,
            leafCount: e["leafCount"].num > 0 ? e["leafCount"].int : (type == "collection" ? e["childCount"].int : 0),
            grandparentTitle: e["grandparentTitle"].text, index: e["index"].positive, parentIndex: e["parentIndex"].positive,
            logo: logo(of: e), qualities: qualities(of: e), versions: versions(of: e),
            guid: e["guid"].str.hasPrefix("plex://") ? e["guid"].str : nil,
            onDeckKey: onDeck["ratingKey"].text, onDeckSeasonKey: onDeck["parentRatingKey"].text)
    }

    static func versions(of entry: JSON) -> [PlexVersion] {
        let media = entry["Media"].array
        guard media.count >= 2 else { return [] }
        return media.map { file in
            let part = file["Part"][0]
            let video = part["Stream"].array.first { $0["streamType"].num == 1 } ?? .null
            return PlexVersion(
                label: versionLabel(file["videoResolution"].text, dolbyVision: video["DOVIPresent"].isTrue, transfer: video["colorTrc"].text),
                detail: versionDetail(file["videoCodec"].str, file["audioCodec"].str, file["audioChannels"].int, file["bitrate"].int, Int64(part["size"].num)))
        }
    }

    /// The file to play, its subtitles, markers and chapters.
    public func playback(_ base: String, _ token: String, ratingKey: String, mediaIndex: Int = 0) async throws -> PlexPlayback? {
        let metadata = try await container("\(base)/library/metadata/\(ratingKey)?includeMarkers=1&includeChapters=1", token)["Metadata"][0]
        return metadata.isNull ? nil : PlexAPI.parsePlayback(metadata, base: base, token: token, mediaIndex: mediaIndex)
    }

    public static func parsePlayback(_ metadata: JSON, base: String, token: String, mediaIndex: Int = 0) -> PlexPlayback? {
        let files = metadata["Media"].array
        guard let media = files.indices.contains(mediaIndex) ? files[mediaIndex] : files.first else { return nil }
        let part = media["Part"][0]
        let key = part["key"].str
        guard !key.isEmpty else { return nil }
        let streams = part["Stream"].array
        let audio = streams.filter { $0["streamType"].num == Double(AUDIO_STREAM) }
        let chosenAudio = audio.first { $0["selected"].flag } ?? audio.first ?? .null
        let partId = part["id"].positive
        let subtitleCodecs: Set = ["srt", "subrip", "vtt", "webvtt", "ass", "ssa"]
        return PlexPlayback(
            url: "\(base)\(key)?X-Plex-Token=\(token)",
            subtitles: streams
                .filter { $0["streamType"].num == Double(SUBTITLE_STREAM) && !$0["key"].str.isEmpty }
                .map { s in
                    PlexSubtitle(id: s["id"].str, label: s["displayTitle"].text ?? s["language"].text ?? "Subtitles",
                                 url: "\(base)\(s["key"].str)?X-Plex-Token=\(token)", codec: s["codec"].str.lowercased(),
                                 language: s["languageTag"].text ?? s["language"].text)
                }
                .filter { subtitleCodecs.contains($0.codec) },
            markers: markerArray(metadata)
                .map { PlexMarker(type: $0["type"].str, startMs: $0["startTimeOffset"].number.map { Int($0) } ?? -1,
                                  endMs: $0["endTimeOffset"].number.map { Int($0) } ?? -1) }
                .filter { !$0.type.isEmpty && $0.startMs >= 0 && $0.endMs > $0.startMs },
            audioCodec: (chosenAudio["codec"].text ?? media["audioCodec"].str).lowercased().nilIfEmpty,
            audioChannels: chosenAudio["channels"].positive ?? media["audioChannels"].int,
            previewUrl: partId.map { "\(base)/library/parts/\($0)/indexes/sd/{ms}?X-Plex-Token=\(token)" },
            chapters: chapters(of: metadata, base: base, token: token),
            partId: partId,
            audioStreams: streamsOf(streams, type: AUDIO_STREAM),
            subtitleStreams: streamsOf(streams, type: SUBTITLE_STREAM),
            container: media["container"].str.lowercased().nilIfEmpty,
            videoCodec: media["videoCodec"].str.lowercased().nilIfEmpty)
    }

    public static func streamsOf(_ streams: [JSON], type: Int) -> [PlexStream] {
        streams
            .filter { $0["streamType"].num == Double(type) && !$0["id"].str.isEmpty }
            .map { s in
                PlexStream(id: s["id"].str, language: s["languageTag"].text ?? s["languageCode"].text,
                           selected: s["selected"].flag, external: !s["key"].str.isEmpty, forced: s["forced"].flag,
                           label: s["displayTitle"].text ?? s["language"].text ?? (type == AUDIO_STREAM ? "Sound" : "Subtitles"))
            }
    }

    static func markerArray(_ metadata: JSON) -> [JSON] {
        let m = metadata["Marker"]
        return m.isArray ? m.array : m.isObject ? [m] : []
    }

    static func chapters(of metadata: JSON, base: String, token: String) -> [PlexChapter] {
        let raw = metadata["Chapter"]
        let list = raw.isArray ? raw.array : raw.isObject ? [raw] : []
        let chapters = stableSorted(list.enumerated().map { i, c -> PlexChapter in
            let thumb = c["thumb"].text
            return PlexChapter(
                title: c["tag"].text ?? "Chapter \(c["index"].positive ?? i + 1)",
                startMs: c["startTimeOffset"].int, endMs: c["endTimeOffset"].int,
                thumbUrl: thumb.map { $0.hasPrefix("http") ? $0 : "\(base)\($0)?X-Plex-Token=\(token)" })
        }) { $0.startMs < $1.startMs }
        return chapters.count > 1 ? chapters : []
    }

    /// Where playback is, told to the server: what makes Continue Watching right everywhere.
    public func reportTimeline(_ base: String, _ token: String, ratingKey: String, positionMs: Int, durationMs: Int, state: String, sessionId: String) async {
        let url = "\(base)/:/timeline?ratingKey=\(ratingKey)&key=\(encodeComponent("/library/metadata/\(ratingKey)"))"
            + "&identifier=com.plexapp.plugins.library&state=\(state)&time=\(positionMs)&duration=\(durationMs)"
            + "&playbackTime=\(positionMs)&playQueueItemID=-1"
        var h = headers(token)
        h["X-Plex-Session-Identifier"] = sessionId
        _ = try? await http.ask(HttpRequest(url: url, headers: h))
    }

    /**
     * The server converting the file to HLS, from the start; the player seeks. Only H.264 is
     * passed through as it is: Apple's player won't take HEVC from Plex's HLS segments either.
     */
    public func transcodeUrl(_ base: String, _ token: String, ratingKey: String, sessionId: String, maxBitrateKbps: Int,
                             resolution: String, mediaIndex: Int = 0, subtitles: String = "burn", subtitleSize: Int = 100,
                             videoCodec: String? = nil, audioStreamId: String? = nil, subtitleStreamId: String? = nil) -> String {
        let bitrate = maxBitrateKbps > 0 ? "&maxVideoBitrate=\(maxBitrateKbps)" : ""
        let copyVideo = videoCodec == nil || videoCodec?.lowercased() == "h264"
        return "\(base)/video/:/transcode/universal/start.m3u8?path=\(encodeComponent("/library/metadata/\(ratingKey)"))&mediaIndex=\(mediaIndex)&partIndex=0"
            + "&protocol=hls&fastSeek=1&directPlay=0&directStream=\(copyVideo ? 1 : 0)&subtitles=\(subtitles)&subtitleSize=\(subtitleSize)&audioBoost=100&videoQuality=100"
            + "&videoResolution=\(resolution)\(bitrate)&session=\(sessionId)"
            // The tracks chosen, said outright, so a conversion started part-way through has
            // them rather than whatever the server last had on record; see chooseStreams.
            + (audioStreamId.map { "&audioStreamID=\($0)" } ?? "") + (subtitleStreamId.map { "&subtitleStreamID=\($0)" } ?? "")
            + "&X-Plex-Client-Identifier=\(encodeComponent(identity.clientId))&X-Plex-Platform=\(encodeComponent(identity.platform))&X-Plex-Product=\(encodeComponent(PlexAPI.product))&X-Plex-Token=\(token)"
    }

    public func stopTranscode(_ base: String, _ token: String, sessionId: String) async {
        _ = try? await http.ask(HttpRequest(url: "\(base)/video/:/transcode/universal/stop?session=\(sessionId)&X-Plex-Token=\(token)"))
    }

    public func selectStream(_ base: String, _ token: String, partId: Int, audioStreamId: String? = nil, subtitleStreamId: String? = nil) async -> Bool {
        let choice = [audioStreamId.map { "audioStreamID=\($0)" }, subtitleStreamId.map { "subtitleStreamID=\($0)" }].compactMap { $0 }
        guard !choice.isEmpty else { return false }
        return (try? await http.ask(HttpRequest(url: "\(base)/library/parts/\(partId)?\(choice.joined(separator: "&"))&allParts=1", method: "PUT", headers: headers(token)))) != nil
    }

    public func searchSubtitles(_ base: String, _ token: String, ratingKey: String, language: String) async throws -> [PlexOnlineSubtitle] {
        try await container("\(base)/library/metadata/\(ratingKey)/subtitles?language=\(language)&hearingImpaired=0&forced=0", token)["Stream"].array
            .filter { !$0["key"].str.isEmpty }
            .map { s in
                PlexOnlineSubtitle(key: s["key"].str, title: s["title"].text ?? s["displayTitle"].text ?? s["languageTag"].text ?? "Subtitles",
                                   provider: s["providerTitle"].text, language: s["languageCode"].text ?? s["languageTag"].text,
                                   codec: s["codec"].text, hearingImpaired: s["hearingImpaired"].isTrue, forced: s["forced"].isTrue)
            }
    }

    public func addSubtitle(_ base: String, _ token: String, ratingKey: String, subtitle: PlexOnlineSubtitle, language: String) async -> Bool {
        let query = [
            "key=\(encodeComponent(subtitle.key))",
            subtitle.codec.map { "codec=\(encodeComponent($0))" },
            "language=\(encodeComponent(subtitle.language ?? language))",
            "hearingImpaired=\(subtitle.hearingImpaired ? 1 : 0)",
            "forced=\(subtitle.forced ? 1 : 0)",
            subtitle.provider.map { "providerTitle=\(encodeComponent($0))" },
        ].compactMap { $0 }.joined(separator: "&")
        return (try? await http.ask(HttpRequest(url: "\(base)/library/metadata/\(ratingKey)/subtitles?\(query)", method: "PUT", headers: headers(token)))) != nil
    }

    // MARK: Search, extras, related

    public func searchAll(_ base: String, _ token: String, query: String) async throws -> PlexFound {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return PlexFound(items: [], people: [], collections: []) }
        let hubs = try await container("\(base)/hubs/search?query=\(encodeComponent(q))&limit=30", token)["Hub"].array
        return PlexFound(items: PlexAPI.items(fromHubs: hubs, base: base), people: PlexAPI.people(fromHubs: hubs, base: base),
                         collections: PlexAPI.items(fromHubs: hubs, base: base, wanted: ["collection"]))
    }

    /// Films and shows, not the shows' single episodes, as on the Fire TV.
    public static func items(fromHubs hubs: [JSON], base: String, wanted: Set<String> = ["movie", "show"]) -> [PlexItem] {
        var seen = Set<String>()
        var out: [PlexItem] = []
        for hub in hubs {
            for m in hub["Metadata"].array {
                var item = parseItem(m)
                item.serverBase = base
                guard wanted.contains(item.type), !item.ratingKey.isEmpty, !seen.contains(item.ratingKey) else { continue }
                seen.insert(item.ratingKey)
                out.append(item)
            }
        }
        return out
    }

    public static func people(fromHubs hubs: [JSON], base: String) -> [PlexPerson] {
        var seen = Set<String>()
        var out: [PlexPerson] = []
        for hub in hubs where hub["type"].str == "actor" {
            let list = hub["Directory"].isArray ? hub["Directory"].array : hub["Metadata"].array
            for p in list {
                let id = p["id"].str
                let name = p["tag"].text ?? p["title"].str
                guard !id.isEmpty, !name.isEmpty, !seen.contains(id) else { continue }
                seen.insert(id)
                out.append(PlexPerson(id: id, name: name, thumb: p["thumb"].text, serverBase: base))
            }
        }
        return out
    }

    public func trailers(_ base: String, _ token: String, ratingKey: String) async -> [PlexExtra] {
        guard let c = try? await container("\(base)/library/metadata/\(ratingKey)/extras", token) else { return [] }
        return c["Metadata"].array
            .filter { $0["subtype"].str.lowercased() == "trailer" && !$0["ratingKey"].str.isEmpty }
            .map { PlexExtra(ratingKey: $0["ratingKey"].str, title: $0["title"].text ?? "Trailer", durationMs: $0["duration"].int) }
    }

    public func related(_ base: String, _ token: String, ratingKey: String) async -> [PlexItem] {
        guard let c = try? await container("\(base)/library/metadata/\(ratingKey)/related?count=24", token) else { return [] }
        var seen = Set<String>()
        return Array(PlexAPI.metadata(of: c["Hub"].array)
            .filter { i in
                guard i.ratingKey != ratingKey, i.type == "movie" || i.type == "show", !seen.contains(i.ratingKey) else { return false }
                seen.insert(i.ratingKey)
                return true
            }
            .prefix(24))
            .map { var i = $0; i.serverBase = base; return i }
    }

    // MARK: Watched, Watchlist

    public func setWatched(_ base: String, _ token: String, ratingKey: String, watched: Bool) async throws {
        _ = try await http.ask(
            HttpRequest(url: "\(base)/:/\(watched ? "scrobble" : "unscrobble")?key=\(ratingKey)&identifier=com.plexapp.plugins.library&X-Plex-Token=\(token)",
                        headers: ["accept": "application/json"]),
            failure: "Plex couldn't update the watched status.")
    }

    public func removeFromContinueWatching(_ base: String, _ token: String, ratingKey: String) async throws {
        _ = try await http.ask(HttpRequest(url: "\(base)/actions/removeFromContinueWatching?ratingKey=\(ratingKey)", method: "PUT", headers: headers(token)),
                               failure: "Couldn't remove that from Continue Watching.")
    }

    public func watchlist(token: String) async throws -> [String] {
        try await container("\(discover)/library/sections/watchlist/all?includeFields=guid,type,title&X-Plex-Container-Start=0&X-Plex-Container-Size=100", token)["Metadata"].array
            .map { $0["guid"].str }.filter { $0.hasPrefix("plex://") }
    }

    public func byGuid(_ base: String, _ token: String, guid: String) async throws -> PlexItem? {
        try await items(base, token, "/library/all?guid=\(encodeComponent(guid))", limit: 1).first
    }

    public func setWatchlisted(token: String, guid: String, on: Bool) async throws {
        let key = guid.split(separator: "/").last.map(String.init) ?? guid
        _ = try await http.ask(HttpRequest(url: "\(discover)/actions/\(on ? "addToWatchlist" : "removeFromWatchlist")?ratingKey=\(key)", method: "PUT", headers: headers(token)),
                               failure: "Couldn't change your Watchlist.")
    }

    // MARK: Pictures

    /// A picture at the size it's drawn, through the server's resizer.
    public static func imageUrl(_ base: String, _ token: String, path: String?, width: Int, height: Int) -> String? {
        guard let path, !path.isEmpty else { return nil }
        return "\(base)/photo/:/transcode?width=\(width)&height=\(height)&minSize=1&upscale=1&url=\(encodeComponent(path))&X-Plex-Token=\(token)"
    }

    public static func logoUrl(_ base: String, _ token: String, path: String) -> String {
        "\(base)\(path)\(path.contains("?") ? "&" : "?")X-Plex-Token=\(token)"
    }

    // MARK: Reading answers

    public static func parseItem(_ e: JSON) -> PlexItem {
        let type = e["type"].str
        return PlexItem(
            ratingKey: e["ratingKey"].str, title: e["title"].str, titleSort: e["titleSort"].text, type: type,
            thumb: e["thumb"].text, art: e["art"].text, summary: e["summary"].text, year: e["year"].positive,
            index: e["index"].positive, parentIndex: e["parentIndex"].positive, parentRatingKey: e["parentRatingKey"].text,
            parentTitle: e["parentTitle"].text, grandparentRatingKey: e["grandparentRatingKey"].text,
            grandparentTitle: e["grandparentTitle"].text, grandparentThumb: e["grandparentThumb"].text,
            durationMs: e["duration"].int, viewOffsetMs: e["viewOffset"].int,
            leafCount: e["leafCount"].num > 0 ? e["leafCount"].int : (type == "collection" ? e["childCount"].int : 0),
            viewedLeafCount: e["viewedLeafCount"].int, viewCount: e["viewCount"].int, addedAt: e["addedAt"].int,
            lastViewedAt: e["lastViewedAt"].int, logo: logo(of: e), qualities: qualities(of: e),
            airDate: e["originallyAvailableAt"].text, librarySectionId: e["librarySectionID"].text, serverBase: nil)
    }

    static func qualities(of e: JSON) -> [String] {
        let media = e["Media"][0]
        guard !media.isNull else { return [] }
        let video = media["Part"][0]["Stream"].array.first { $0["streamType"].num == 1 } ?? .null
        return qualityBadges(media["videoResolution"].text, media["audioChannels"].int, dolbyVision: video["DOVIPresent"].isTrue, transfer: video["colorTrc"].text)
    }

    static func logo(of e: JSON) -> String? {
        e["Image"].array.first { $0["type"].str == "clearLogo" }?["url"].text
    }

    static func tags(_ e: JSON, _ field: String) -> [String] {
        e[field].array.map { $0["tag"].str }.filter { !$0.isEmpty }
    }

    /// Every hub's items, in order.
    static func metadata(of hubs: [JSON]) -> [PlexItem] {
        hubs.flatMap { $0["Metadata"].array.map(parseItem) }
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
