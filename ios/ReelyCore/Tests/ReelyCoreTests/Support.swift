import XCTest
@testable import ReelyCore

@MainActor
func makeStore(_ fake: FakePlex, secrets: MemoryStore = MemoryStore()) -> ReelyStore {
    let api = PlexAPI(http: Http(transport: fake), identity: PlexIdentity(clientId: "c", version: "1", platform: "iOS", device: "iPhone", deviceName: "Reely"))
    return ReelyStore(api: api, store: MemoryStore(), secrets: secrets)
}

func fakeServer(_ fake: FakePlex, at base: String, address: String = "10.0.0.2", tv: String = "https://plex.tv") {
    fake.json(tv, "/api/v2/resources", """
    [{"name": "Living Room", "provides": "server", "owned": true, "accessToken": "server-token",
      "connections": [{"uri": "\(base)", "address": "\(address)", "port": 32400, "local": true}]}]
    """)
    fake.json(base, "/identity", "{}")
    fake.json(base, "/library/sections", #"{"MediaContainer": {"Directory": [{"key": "1", "title": "Movies", "type": "movie"}, {"key": "2", "title": "TV Shows", "type": "show"}, {"key": "3", "title": "Music", "type": "artist"}]}}"#)
    fake.json(base, "/hubs", #"{"MediaContainer": {"Hub": [{"Metadata": [{"ratingKey": "e2", "type": "episode", "title": "Episode 2", "grandparentTitle": "Northbound", "grandparentRatingKey": "show1", "viewOffset": 1000, "duration": 60000, "lastViewedAt": 5}]}]}}"#)
    fake.json(base, "/playlists", #"{"MediaContainer": {"Metadata": [{"ratingKey": "p1", "type": "playlist", "title": "Road Trip", "leafCount": 2}]}}"#)
    fake.json(base, "/library/sections/1/all", #"{"MediaContainer": {"Metadata": [{"ratingKey": "m1", "type": "movie", "title": "Low Orbit", "year": 2025, "addedAt": 9}]}}"#)
    fake.json(base, "/library/sections/2/all", #"{"MediaContainer": {"Metadata": [{"ratingKey": "e3", "type": "episode", "title": "Episode 3", "grandparentTitle": "Northbound", "grandparentRatingKey": "show1", "addedAt": 7}, {"ratingKey": "e4", "type": "episode", "title": "Episode 4", "grandparentTitle": "Northbound", "grandparentRatingKey": "show1", "addedAt": 8}]}}"#)
}

