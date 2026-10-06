import SwiftUI
import ReelyCore

/**
 * Search, as on the Fire TV: movies and shows from every server, best matches first and
 * Plex's own guesses after; people and collections; what was searched before.
 */
struct SearchView: View {
    @Environment(ReelyStore.self) private var store
    @State private var text = ""
    @FocusState private var typing: Bool

    var body: some View {
        let search = store.search
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(20)) {
                HStack(spacing: dp(10)) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Color.muted)
                    TextField("Movies, shows and people", text: $text)
                        .font(Typeface.body).foregroundStyle(Color.chalk)
                        .focused($typing)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .submitLabel(.search)
                        #endif
                }
                .padding(dp(14))
                .background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.surfaceRaised))
                .padding(.horizontal, pageMargin)

                if search.query.trimmingCharacters(in: .whitespaces).isEmpty {
                    if !search.recent.isEmpty {
                        VStack(alignment: .leading, spacing: dp(10)) {
                            HStack {
                                Text("Recent searches").font(Typeface.rowTitle).foregroundStyle(Color.chalk)
                                Spacer()
                                Pill(title: "Clear", on: false) { store.clearRecentSearches() }
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: dp(8)) {
                                    ForEach(search.recent, id: \.self) { r in Pill(title: r, on: false) { text = r } }
                                }
                                .padding(.vertical, dp(6))
                            }
                        }
                        .padding(.horizontal, pageMargin)
                    }
                } else if search.busy && search.results.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(dp(30))
                } else if search.unreachable {
                    ErrorNote(text: "Couldn't reach your Plex server.").padding(.horizontal, pageMargin)
                } else if search.results.isEmpty && search.people.isEmpty && search.collections.isEmpty {
                    Text("Nothing found for \u{201C}\(search.query)\u{201D}.").font(Typeface.body).foregroundStyle(Color.muted).padding(.horizontal, pageMargin)
                } else {
                    results(search)
                }
            }
            .padding(.top, topInset + dp(8)).padding(.bottom, dp(24))
        }
        .onChange(of: text) { _, q in Task { await store.setQuery(q) } }
        .onAppear { text = store.search.query; typing = text.isEmpty }
    }

    @ViewBuilder
    private func results(_ search: SearchState) -> some View {
        if !search.results.isEmpty {
            CardRow(title: "Movies and shows") { ForEach(search.results) { i in card(i) } }
        }
        if !search.people.isEmpty {
            CardRow(title: "People") {
                ForEach(search.people, id: \.id) { person in
                    Button {
                        store.rememberSearch()
                        store.navigate(.person(id: person.id, name: person.name, serverBase: person.serverBase))
                    } label: {
                        VStack(spacing: dp(6)) {
                            RemoteImage(url: person.thumb.flatMap { $0.hasPrefix("http") ? URL(string: $0) : store.imageUrl(person.serverBase, $0, width: 200, height: 200) })
                                .frame(width: dp(84), height: dp(84)).clipShape(Circle())
                            Text(person.name).font(Typeface.label).foregroundStyle(Color.chalk).lineLimit(1)
                        }
                        .frame(width: dp(110))
                    }
                    .buttonStyle(CardStyle())
                }
            }
        }
        if !search.collections.isEmpty {
            CardRow(title: "Collections") { ForEach(search.collections) { i in card(i) } }
        }
        if !search.more.isEmpty {
            CardRow(title: "More from Plex") { ForEach(search.more) { i in card(i) } }
        }
    }

    private func card(_ i: PlexItem) -> some View {
        PosterCard(title: i.rowTitle, subtitle: i.type == "episode" ? i.episodeLine : i.caption,
                   url: store.imageUrl(i.serverBase, i.type == "episode" ? (i.grandparentThumb ?? i.thumb) : i.thumb, width: 300, height: 450),
                   progress: i.resumeFraction, watched: i.isWatched, tag: i.sourceTag) {
            store.rememberSearch()
            openPage(store, i)
        }
        .itemMenu(i)
    }
}

/// An actor's titles, a collection's or a playlist's: a grid under the name; a playlist plays or shuffles.
struct ListPageView: View {
    @Environment(ReelyStore.self) private var store
    let route: Route

    var body: some View {
        let page = store.list
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(16)) {
                Text(title).font(Typeface.display).foregroundStyle(Color.chalk).padding(.horizontal, pageMargin)
                if isPlaylist, let items = page?.items, !items.isEmpty {
                    HStack(spacing: dp(10)) {
                        ActionButton(title: "Play", systemImage: "play.fill", filled: true) { Task { await store.playList(shuffled: false) } }
                        ActionButton(title: "Shuffle", systemImage: "shuffle") { Task { await store.playList(shuffled: true) } }
                    }
                    .padding(.horizontal, pageMargin)
                }
                if let error = page?.error { ErrorNote(text: error).padding(.horizontal, pageMargin) }
                if page?.busy ?? true {
                    ProgressView().frame(maxWidth: .infinity).padding(dp(30))
                } else if page?.items.isEmpty ?? true {
                    Text("Nothing here on your servers.").font(Typeface.body).foregroundStyle(Color.muted).padding(.horizontal, pageMargin)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth), spacing: dp(14))], alignment: .leading, spacing: dp(18)) {
                    ForEach(page?.items ?? []) { i in
                        PosterCard(title: i.rowTitle, subtitle: i.type == "episode" ? i.episodeLine : i.caption,
                                   url: store.imageUrl(i.serverBase, i.type == "episode" ? (i.grandparentThumb ?? i.thumb) : i.thumb, width: 300, height: 450),
                                   progress: i.resumeFraction, watched: i.isWatched, tag: i.sourceTag) {
                            if isPlaylist { Task { await store.play(i, resume: true, queue: page?.items ?? []) } } else { openPage(store, i) }
                        }
                        .itemMenu(i)
                    }
                }
                .padding(.horizontal, pageMargin)
            }
            .padding(.top, topInset + dp(8)).padding(.bottom, dp(24))
        }
        .task(id: route) {
            switch route {
            case .person(let id, _, let base): await store.openPerson(id: id, serverBase: base)
            case .collection(let key, _, let base): await store.openCollection(ratingKey: key, serverBase: base)
            case .playlist(let key, _, let base): await store.openPlaylist(ratingKey: key, serverBase: base)
            default: break
            }
        }
    }

    private var isPlaylist: Bool { if case .playlist = route { return true }; return false }

    private var title: String {
        switch route {
        case .person(_, let name, _): return name
        case .collection(_, let title, _), .playlist(_, let title, _): return title
        default: return ""
        }
    }
}
