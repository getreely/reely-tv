import SwiftUI
import ReelyCore

/**
 * Home, as the Fire TV and phone apps have it: Continue Watching, new episodes gathered on
 * their show, new films, the Watchlist and playlists, each row switchable off in Settings.
 * On a television the title under the cursor fills the top of the screen.
 */
struct HomeView: View {
    @Environment(ReelyStore.self) private var store
    @FocusState private var focused: String?
    @State private var hero: PlexItem?

    var body: some View {
        let home = store.home
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(22)) {
                #if os(tvOS)
                HeroBanner(item: hero ?? home.continueWatching.first ?? home.recentMovies.first)
                #else
                PageHeader(title: "Home")
                #endif
                if let error = store.homeError { ErrorNote(text: error).padding(.horizontal, pageMargin) }

                if shown(.continueWatching) && !home.continueWatching.isEmpty {
                    CardRow(title: HomeRow.continueWatching.title) {
                        ForEach(home.continueWatching) { item in
                            WideCard(title: item.rowTitle, subtitle: item.episodeLine,
                                     url: store.imageUrl(item.serverBase, item.art ?? item.thumb, width: 480, height: 270),
                                     progress: item.resumeFraction) { open(item) }
                                .itemMenu(item, inContinueWatching: true)
                                .focused($focused, equals: "continue|" + item.id)
                        }
                    }
                }
                if shown(.recentEpisodes) && !home.recentEpisodes.isEmpty {
                    CardRow(title: HomeRow.recentEpisodes.title) {
                        ForEach(home.recentEpisodes) { group in
                            PosterCard(title: group.showTitle,
                                       subtitle: group.count > 1 ? "\(group.count) new episodes" : group.newest.caption,
                                       url: store.imageUrl(group.serverBase, group.thumb, width: 300, height: 450),
                                       badge: group.count) { open(group.newest) }
                                .itemMenu(group.newest)
                                .focused($focused, equals: "episodes|" + group.newest.id)
                        }
                    }
                }
                ForEach([(HomeRow.recentMovies, home.recentMovies), (.iptvMovies, home.iptvMovies), (.iptvShows, home.iptvShows), (.watchlist, home.watchlist)], id: \.0) { row, titles in
                    if shown(row) && !titles.isEmpty {
                        CardRow(title: row.title) {
                            ForEach(titles) { item in
                                PosterCard(title: item.title, subtitle: item.caption,
                                           url: store.imageUrl(item.serverBase, item.thumb, width: 300, height: 450),
                                           progress: item.resumeFraction, watched: item.isWatched) { open(item) }
                                    .itemMenu(item)
                                    .focused($focused, equals: row.rawValue + "|" + item.id)
                            }
                        }
                    }
                }
                if shown(.playlists) && !home.playlists.isEmpty {
                    CardRow(title: HomeRow.playlists.title) {
                        ForEach(home.playlists) { playlist in
                            PosterCard(title: playlist.title, subtitle: playlist.leafCount == 1 ? "1 item" : "\(playlist.leafCount) items",
                                       url: store.imageUrl(playlist.serverBase, playlist.thumb, width: 300, height: 450)) { open(playlist) }
                                .focused($focused, equals: "playlists|" + playlist.id)
                        }
                    }
                }
                if home.isEmpty {
                    if store.homeBusy {
                        ProgressView().frame(maxWidth: .infinity).padding(dp(40))
                    } else {
                        Text("Nothing to show yet. Watch something and it will appear here.")
                            .font(Typeface.body).foregroundStyle(Color.muted).padding(.horizontal, pageMargin)
                    }
                }
            }
            .padding(.bottom, dp(24))
        }
        .refreshable { await store.refreshHome() }
        .onChange(of: focused) { _, id in
            // Which row is said first, so a title in two rows is two places for the cursor.
            guard let focus = id, let bar = focus.firstIndex(of: "|") else { return }
            let id = String(focus[focus.index(after: bar)...])
            let all = home.continueWatching + home.recentEpisodes.map(\.newest) + home.recentMovies + home.watchlist + home.playlists
            if let item = all.first(where: { $0.id == id }) { withAnimation(.easeInOut(duration: 0.25)) { hero = item } }
        }
    }

    private func shown(_ row: HomeRow) -> Bool { !store.isHidden(row) }

    private func open(_ item: PlexItem) {
        switch item.type {
        case "playlist": store.navigate(.playlist(ratingKey: item.ratingKey, title: item.title, serverBase: item.serverBase))
        case "episode": store.navigate(.detail(ratingKey: item.grandparentRatingKey ?? item.ratingKey, serverBase: item.serverBase))
        default: store.navigate(.detail(ratingKey: item.ratingKey, serverBase: item.serverBase))
        }
    }
}

/// The title under the cursor, large: its backdrop, logo or name, what it is, and its story.
struct HeroBanner: View {
    @Environment(ReelyStore.self) private var store
    let item: PlexItem?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if let item {
                RemoteImage(url: store.imageUrl(item.serverBase, item.art ?? item.thumb, width: 1280, height: 720))
                    .frame(maxWidth: .infinity).frame(height: dp(300) + topInset).clipped()
                    .overlay(LinearGradient(colors: [.clear, Color.ink.opacity(0.6), Color.ink], startPoint: .top, endPoint: .bottom))
                    .overlay(LinearGradient(colors: [Color.ink.opacity(0.85), .clear], startPoint: .leading, endPoint: .center))
                    .id(item.id)
                    .transition(.opacity)
                VStack(alignment: .leading, spacing: dp(8)) {
                    Spacer(minLength: 0)
                    if let logo = store.logoUrl(item.serverBase, item.logo) {
                        RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: dp(320), maxHeight: dp(90), alignment: .leading)
                    } else {
                        Text(item.rowTitle).font(Typeface.display).foregroundStyle(Color.chalk).lineLimit(2)
                    }
                    Text(([item.episodeLine] + item.qualities.map { Optional($0) }).compactMap { $0 }.joined(separator: "  ·  "))
                        .font(Typeface.meta).foregroundStyle(Color.muted)
                    if let summary = item.summary {
                        Text(summary).font(Typeface.body).foregroundStyle(Color.chalk.opacity(0.85)).lineLimit(3).frame(maxWidth: dp(520), alignment: .leading)
                    }
                }
                .padding(.horizontal, pageMargin).padding(.bottom, dp(8))
            }
        }
        .frame(height: dp(300) + topInset)
    }
}

/// A phone page's top: its name, with Search and Settings in the corner, as on Android.
struct PageHeader: View {
    @Environment(ReelyStore.self) private var store
    let title: String

    var body: some View {
        HStack(spacing: dp(14)) {
            ReelyMark(height: 26)
            Text(title).font(Typeface.headline).foregroundStyle(Color.chalk)
            Spacer()
            Button { store.navigate(.search) } label: { Image(systemName: "magnifyingglass") }
                .accessibilityLabel("Search")
            Button { store.navigate(.settings) } label: { Image(systemName: "gearshape") }
                .accessibilityLabel("Settings")
        }
        .font(.system(size: 20, weight: .medium))
        .foregroundStyle(Color.chalk)
        .padding(.horizontal, pageMargin).padding(.top, 8)
    }
}
