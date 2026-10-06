import XCTest
@testable import ReelyCore

/// Live TV in the store: a panel signed in to, its categories and channels, and what's kept between visits.
@MainActor
final class LiveTests: XCTestCase {
    let panel = "http://panel.example:8080"

    func fakePanel(_ fake: FakePlex) {
        fake.routes[panel + "/player_api.php"] = { request in
            let action = URLComponents(string: request.url)?.queryItems?.first { $0.name == "action" }?.value
            let text: String
            switch action {
            case nil:
                text = #"{"user_info": {"auth": 1, "status": "Active", "max_connections": "2", "active_cons": "0", "exp_date": null}, "server_info": {"timezone": "UTC"}}"#
            case "get_live_categories":
                text = #"[{"category_id": "1", "category_name": "News"}, {"category_id": "2", "category_name": "Sport"}]"#
            case "get_live_streams":
                text = #"[{"stream_id": 11, "num": 1, "name": "News 24", "epg_channel_id": "news", "tv_archive": 1, "tv_archive_duration": 3}, {"stream_id": "12", "num": 2, "name": "World News"}, {"stream_id": 21, "num": 7, "name": "Sport One"}]"#
            case "get_short_epg":
                text = #"{"epg_listings": []}"#
            default:
                text = "[]"
            }
            return HttpResponse(status: 200, data: Data(text.utf8))
        }
    }

    func signedIn() async -> (ReelyStore, MemoryStore) {
        let fake = FakePlex()
        fakePanel(fake)
        let secrets = MemoryStore()
        let store = makeStore(fake, secrets: secrets)
        await store.signInXtream(base: "panel.example:8080", username: "me", password: "secret")
        return (store, secrets)
    }

    func testSigningInKeepsTheLoginSecretAndListsCategories() async {
        let (store, secrets) = await signedIn()
        XCTAssertNil(store.live.error)
        XCTAssertEqual(store.live.credentials?.base, panel)
        XCTAssertNotNil(secrets.string("xtream"))
        XCTAssertEqual(store.shownCategories.map(\.name), ["News", "Sport"])
    }

    func testAWrongPasswordSaysSo() async {
        let fake = FakePlex()
        fake.json(panel, "/player_api.php", #"{"user_info": {"auth": 0}}"#)
        let store = makeStore(fake)
        await store.signInXtream(base: panel, username: "me", password: "wrong")
        XCTAssertEqual(store.live.error, "Incorrect username or password.")
        XCTAssertFalse(store.live.isSignedIn)
    }

    func testChannelUpAndDownGoRoundAndNumbersTune() async {
        let (store, _) = await signedIn()
        await store.openCategory(store.live.categories[0])
        XCTAssertEqual(store.live.channels.map(\.name), ["News 24", "World News", "Sport One"])
        store.watchChannel(0)
        store.stepChannel(-1)
        XCTAssertEqual(store.live.watchingChannel?.name, "Sport One")
        store.stepChannel(1)
        XCTAssertEqual(store.live.watchingChannel?.name, "News 24")
        XCTAssertTrue(store.tuneNumber(2))
        XCTAssertEqual(store.live.watchingChannel?.name, "World News")
        XCTAssertFalse(store.tuneNumber(99))
        XCTAssertEqual(store.live.recent, [12, 11, 21])
        XCTAssertEqual(store.shownCategories.first, RECENT_CATEGORY)
    }

    func testFavoritesComeFirstAndStayAfterSigningOut() async {
        let (store, _) = await signedIn()
        await store.openCategory(store.live.categories[0])
        store.toggleFavorite(store.live.channels[2])
        XCTAssertEqual(store.shownCategories.first, FAVORITES_CATEGORY)
        await store.openCategory(FAVORITES_CATEGORY)
        XCTAssertEqual(store.live.channels.map(\.name), ["Sport One"])
        store.signOutLive()
        XCTAssertFalse(store.live.isSignedIn)
        XCTAssertEqual(store.live.favorites, [21])
    }

    func testCatchUpOnlyWhileTheArchiveHoldsIt() async {
        let (store, _) = await signedIn()
        await store.openCategory(store.live.categories[0])
        let now = 1_710_100_800
        let news = store.live.channels[0], world = store.live.channels[1]
        let earlier = Programme(channelId: "news", start: now - 3600, stop: now - 1800, title: "Earlier")
        let longAgo = Programme(channelId: "news", start: now - 5 * 86400, stop: now - 5 * 86400 + 1800, title: "Last week")
        XCTAssertTrue(store.canCatchUp(news, earlier, now: now))
        XCTAssertFalse(store.canCatchUp(news, longAgo, now: now))
        XCTAssertFalse(store.canCatchUp(world, earlier, now: now))
        XCTAssertTrue(store.playCatchUp(0, earlier))
        XCTAssertEqual(store.live.catchUp?.programme, earlier)
        store.goLive()
        XCTAssertNil(store.live.catchUp)
        XCTAssertEqual(store.live.watching, 0)
    }

    func testARemindersComesDueOnceAndOldOnesGo() async {
        let (store, _) = await signedIn()
        await store.openCategory(store.live.categories[0])
        let now = 1_710_100_800
        let soon = Programme(channelId: "news", start: now + 30, stop: now + 1800, title: "Soon")
        let later = Programme(channelId: "news", start: now + 3600, stop: now + 5400, title: "Later")
        store.toggleReminder(store.live.channels[0], soon)
        store.toggleReminder(store.live.channels[1], later)
        XCTAssertTrue(store.hasReminder(store.live.channels[0], soon))
        store.checkReminders(now: now)
        XCTAssertEqual(store.live.due?.title, "Soon")
        XCTAssertEqual(store.live.reminders.map(\.title), ["Later"])
        await store.watchReminder()
        XCTAssertNil(store.live.due)
        XCTAssertEqual(store.live.watchingChannel?.name, "News 24")
        store.checkReminders(now: now + 3600 + 700)
        XCTAssertNil(store.live.due)
        XCTAssertTrue(store.live.reminders.isEmpty)
    }
}
