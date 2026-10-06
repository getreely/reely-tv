import Foundation

/** "Starting now", asked for from the guide. */
public struct Reminder: Codable, Equatable, Hashable, Sendable {
    public var streamId: Int
    public var channelName: String
    public var title: String
    /// Epoch seconds.
    public var start: Int
}

/// A playlist's own guide: being read, read when, or what went wrong; none when it names none.
public enum GuideStatus: Equatable, Sendable {
    case idle, updating, ready(at: Int), none, failed(String)
}

public struct LiveState: Equatable, Sendable {
    public var credentials: XtreamCredentials?
    public var account: XtreamAccount?
    public var categories: [XtreamCategory] = []
    public var category: XtreamCategory?
    public var channels: [XtreamChannel] = []
    /// Now and next, by stream id.
    public var guide: [Int: [Programme]] = [:]
    /// The guide's listings, what's been as well as what's coming, by stream id.
    public var table: [Int: [Programme]] = [:]
    public var favorites: [Int] = []
    /// Channels watched lately, newest first.
    public var recent: [Int] = []
    public var busy = false
    public var error: String?
    /// The channel on screen, by its place in [channels].
    public var watching: Int?
    /// A programme from the archive playing instead of the channel live.
    public var catchUp: CatchUp?
    public var reminders: [Reminder] = []
    /// A reminder whose programme is starting: up on screen until it's answered.
    public var due: Reminder?
    public var guideStatus: GuideStatus = .idle

    public struct CatchUp: Equatable, Sendable {
        public var programme: Programme
        public var url: String
    }

    public var isSignedIn: Bool { credentials != nil }
    public var watchingChannel: XtreamChannel? { watching.flatMap { channels.indices.contains($0) ? channels[$0] : nil } }
}

public let FAVORITES_CATEGORY = XtreamCategory(id: "reely:favorites", name: "Favorites")
public let RECENT_CATEGORY = XtreamCategory(id: "reely:recent", name: "Recently watched")
let RECENT_KEPT = 20

extension ReelyStore {
    func loadLiveState() {
        live.credentials = secrets.json("xtream", as: XtreamCredentials.self)
        live.favorites = store.json("favoriteChannels", as: [Int].self) ?? []
        live.recent = store.json("recentChannels", as: [Int].self) ?? []
        live.reminders = store.json("reminders", as: [Reminder].self) ?? []
    }

    /// An Xtream login: the panel's address, a username and a password.
    public func signInXtream(base: String, username: String, password: String) async {
        guard !base.trimmingCharacters(in: .whitespaces).isEmpty, !username.trimmingCharacters(in: .whitespaces).isEmpty, !password.isEmpty else {
            live.error = "Enter the server address, username and password your provider gave you."
            return
        }
        let c = Xtream.panelLogin(in: base.trimmingCharacters(in: .whitespaces))
            ?? XtreamCredentials(base: Xtream.normalizeBase(base), username: username.trimmingCharacters(in: .whitespaces), password: password)
        await signInLive(c)
    }

    /// An M3U playlist; a panel's own playlist address signs in to the panel instead.
    public func signInPlaylist(url: String, guideUrl: String) async {
        let u = url.trimmingCharacters(in: .whitespaces)
        guard u.lowercased().hasPrefix("http://") || u.lowercased().hasPrefix("https://") else {
            live.error = "Enter the playlist's full address, starting http:// or https://."
            return
        }
        let g = guideUrl.trimmingCharacters(in: .whitespaces)
        await signInLive(Xtream.panelLogin(in: u) ?? XtreamCredentials(base: "", username: "", password: "", playlistUrl: u, guideUrl: g.isEmpty ? nil : g))
    }

    private func signInLive(_ c: XtreamCredentials) async {
        live.busy = true
        live.error = nil
        do {
            await xtream.forgetGuide()
            let account = try await xtream.login(c)
            secrets.setJson("xtream", c)
            live.credentials = c
            live.account = account
            live.busy = false
            live.categories = []
            await loadLive()
        } catch {
            live.busy = false
            live.error = (error as? HttpError)?.message ?? "Couldn't connect. Check the server address and port."
        }
    }

    public func signOutLive() {
        secrets.set("xtream", nil)
        Task { await xtream.forgetGuide() }
        let (favorites, recent, reminders) = (live.favorites, live.recent, live.reminders)
        live = LiveState()
        live.favorites = favorites
        live.recent = recent
        live.reminders = reminders
    }

    /// The provider's categories, after Favorites and Recently watched once there are any.
    public var shownCategories: [XtreamCategory] {
        (live.favorites.isEmpty ? [] : [FAVORITES_CATEGORY]) + (live.recent.isEmpty ? [] : [RECENT_CATEGORY]) + live.categories
    }

    public func loadLive() async {
        guard let c = live.credentials, live.categories.isEmpty else { return }
        live.busy = true
        live.error = nil
        do {
            if live.account == nil { live.account = try? await xtream.login(c) }
            live.categories = try await xtream.categories(c)
            live.busy = false
        } catch {
            live.busy = false
            live.error = (error as? HttpError)?.message ?? "Your provider couldn't do that. Try again."
        }
    }

    public func openCategory(_ category: XtreamCategory) async {
        live.category = category
        live.channels = []
        live.busy = true
        live.error = nil
        do {
            let channels = try await channelsOf(category)
            guard live.category == category else { return }
            live.channels = channels
            live.busy = false
            await loadGuide(Array(channels.prefix(40)))
        } catch {
            live.busy = false
            live.error = (error as? HttpError)?.message ?? "Your provider couldn't do that. Try again."
        }
    }

    /// Back from a category's channels to the list of categories.
    public func closeCategory() {
        live.category = nil
        live.channels = []
    }

    public func dismissLiveError() { live.error = nil }

    /// What's on a channel: the guide's whole listing where it's been read, else now and next.
    public func listing(for channel: XtreamChannel) -> [Programme] {
        let table = live.table[channel.streamId] ?? []
        return table.isEmpty ? live.guide[channel.streamId] ?? [] : table
    }

    /// A category's channels, without opening it: the guide over a channel browses them.
    public func channelsOf(_ category: XtreamCategory) async throws -> [XtreamChannel] {
        guard let c = live.credentials else { return [] }
        if category.id == FAVORITES_CATEGORY.id { return try await xtream.channels(c).filter { live.favorites.contains($0.streamId) } }
        if category.id == RECENT_CATEGORY.id {
            let recent = live.recent
            return stableSorted(try await xtream.channels(c).filter { recent.contains($0.streamId) }) {
                (recent.firstIndex(of: $0.streamId) ?? 0) < (recent.firstIndex(of: $1.streamId) ?? 0)
            }
        }
        return try await xtream.channels(c, categoryId: category.id)
    }

    /// Now and next for these channels; a playlist's from the XMLTV guide it names.
    public func loadGuide(_ channels: [XtreamChannel]) async {
        guard let c = live.credentials else { return }
        if c.isPlaylist { await fromPlaylistGuide(channels); return }
        for ch in channels where live.guide[ch.streamId] == nil {
            let found = (try? await xtream.shortEpg(c, streamId: ch.streamId, limit: 2)) ?? []
            guard live.credentials == c else { return }
            live.guide[ch.streamId] = found
        }
    }

    /// The guide's listings for these channels: what's been, for catch-up, and what's coming.
    public func loadTable(_ channels: [XtreamChannel]) async {
        guard let c = live.credentials else { return }
        if c.isPlaylist { await fromPlaylistGuide(channels); return }
        for ch in channels where live.table[ch.streamId] == nil {
            let found = (try? await xtream.table(c, streamId: ch.streamId)) ?? []
            guard live.credentials == c else { return }
            live.table[ch.streamId] = found
        }
    }

    /// The guide asked for afresh, for the channels on screen; a playlist's read again whole.
    public func refreshGuide() async {
        guard let c = live.credentials else { return }
        let channels = live.channels
        if c.isPlaylist { await fromPlaylistGuide(channels, fresh: true); return }
        live.guide = [:]
        live.table = [:]
        await loadGuide(Array(channels.prefix(40)))
    }

    /// The channels and categories asked for again.
    public func refreshChannels() async {
        live.categories = []
        live.category = nil
        live.channels = []
        await loadLive()
    }

    private func fromPlaylistGuide(_ channels: [XtreamChannel], fresh: Bool = false) async {
        guard let c = live.credentials, c.isPlaylist else { return }
        let now = Int(Date().timeIntervalSince1970)
        let at = await xtream.guideReadAt(c)
        if fresh || at == nil || now - (at ?? 0) >= XtreamClient.guideKeepSeconds { live.guideStatus = .updating }
        do {
            let byChannel = try await xtream.playlistGuide(c, fresh: fresh)
            guard live.credentials == c else { return }
            for ch in channels {
                let list = playlistListing(byChannel, ch)
                live.table[ch.streamId] = list
                live.guide[ch.streamId] = nowAndNext(list, now: now)
            }
            let read = await xtream.guideReadAt(c)
            live.guideStatus = read.map { .ready(at: $0) } ?? .none
        } catch {
            guard live.credentials == c else { return }
            live.guideStatus = .failed((error as? HttpError)?.message ?? "Couldn't download the TV guide.")
        }
    }

    /// Whether the playlist names a guide: nil until that's known.
    public func guideNamed() async -> Bool? {
        guard let c = live.credentials else { return nil }
        return await xtream.guideNamed(c)
    }

    // MARK: Watching

    /// A channel's stream. Apple's player plays HLS but not a bare MPEG-TS stream, so it's always HLS here.
    public func channelUrl(_ channel: XtreamChannel) -> String? {
        live.credentials.map { Xtream.streamUrl($0, channel, format: .m3u8) }
    }

    public func watchChannel(_ index: Int) {
        guard live.channels.indices.contains(index) else { return }
        noteWatched(live.channels[index])
        live.watching = index
        live.catchUp = nil
    }

    /// A channel picked from another category's list (the guide over a channel): that list becomes the one open.
    public func watchIn(_ category: XtreamCategory?, _ channels: [XtreamChannel], index: Int, programme: Programme? = nil) {
        live.category = category
        live.channels = channels
        if let programme, playCatchUp(index, programme) { return }
        watchChannel(index)
    }

    /// Channel up and down, round the list's ends.
    public func stepChannel(_ by: Int) {
        guard let at = live.watching, !live.channels.isEmpty else { return }
        watchChannel((at + by + live.channels.count) % live.channels.count)
    }

    /// A channel by the number on it, as typed on the remote.
    @discardableResult
    public func tuneNumber(_ number: Int) -> Bool {
        guard let at = live.channels.firstIndex(where: { $0.number == number }) else { return false }
        watchChannel(at)
        return true
    }

    public func stopLive() {
        live.watching = nil
        live.catchUp = nil
    }

    /// A programme that's over, from the channel's archive; or this one from its start.
    @discardableResult
    public func playCatchUp(_ index: Int, _ programme: Programme) -> Bool {
        guard live.channels.indices.contains(index), let c = live.credentials,
              let url = Xtream.catchUpUrl(c, live.channels[index], start: programme.start, stop: programme.stop,
                                            timezone: live.account?.timezone, format: .m3u8) else { return false }
        noteWatched(live.channels[index])
        live.watching = index
        live.catchUp = LiveState.CatchUp(programme: programme, url: url)
        return true
    }

    /// Back to the channel as it is now.
    public func goLive() { live.catchUp = nil }

    /// Whether [channel] keeps an archive this programme is still in.
    public func canCatchUp(_ channel: XtreamChannel, _ programme: Programme, now: Int = Int(Date().timeIntervalSince1970)) -> Bool {
        guard let c = live.credentials, !c.isPlaylist, let from = Xtream.catchUpFrom(channel, now: now) else { return false }
        return programme.start >= from && programme.start < now
    }

    private func noteWatched(_ channel: XtreamChannel) {
        live.recent = Array(([channel.streamId] + live.recent.filter { $0 != channel.streamId }).prefix(RECENT_KEPT))
        store.setJson("recentChannels", live.recent)
    }

    public func toggleFavorite(_ channel: XtreamChannel) {
        if live.favorites.contains(channel.streamId) { live.favorites.removeAll { $0 == channel.streamId } } else { live.favorites.append(channel.streamId) }
        store.setJson("favoriteChannels", live.favorites)
    }

    // MARK: Reminders

    public func toggleReminder(_ channel: XtreamChannel, _ programme: Programme) {
        if hasReminder(channel, programme) {
            live.reminders.removeAll { $0.streamId == channel.streamId && $0.start == programme.start }
        } else {
            live.reminders.append(Reminder(streamId: channel.streamId, channelName: channel.name, title: programme.title, start: programme.start))
        }
        store.setJson("reminders", live.reminders)
    }

    public func hasReminder(_ channel: XtreamChannel, _ programme: Programme) -> Bool {
        live.reminders.contains { $0.streamId == channel.streamId && $0.start == programme.start }
    }

    /// A reminder whose programme starts within the minute comes due, once; old ones go.
    public func checkReminders(now: Int = Int(Date().timeIntervalSince1970)) {
        guard live.due == nil else { return }
        let due = live.reminders.first { $0.start - now <= 60 && now - $0.start < 600 }
        let kept = live.reminders.filter { $0 != due && now - $0.start < 600 }
        if due != nil || kept.count != live.reminders.count {
            live.reminders = kept
            live.due = due
            store.setJson("reminders", kept)
        }
    }

    public func dismissReminder() { live.due = nil }

    /// The reminded programme's channel, from wherever it is.
    public func watchReminder() async {
        guard let due = live.due, live.credentials != nil else { return }
        live.due = nil
        if let at = live.channels.firstIndex(where: { $0.streamId == due.streamId }) { watchChannel(at); return }
        if let c = live.credentials, let channels = try? await xtream.channels(c), let at = channels.firstIndex(where: { $0.streamId == due.streamId }) {
            watchIn(nil, channels, index: at)
        }
    }
}
