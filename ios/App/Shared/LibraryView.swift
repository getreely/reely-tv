import SwiftUI
import ReelyCore

/**
 * The Movies and TV Shows tabs, as on the Fire TV: the tab's own home (Continue Watching,
 * Recently Added, Recently Released), everything in a grid with its sort, Unwatched, decade,
 * genres and the A–Z jump, or the library's collections; a pill for each library.
 */
struct LibraryView: View {
    @Environment(ReelyStore.self) private var store
    let kind: String
    @State private var choosingSort = false
    @State private var choosingDecade = false
    @State private var jumpTarget: String?

    var body: some View {
        let browse = store.browse[kind] ?? Browse()
        let libraries = store.libraries(of: kind)
        Group {
            if libraries.isEmpty {
                Text("No \(kind == "movie" ? "movie" : "TV") library on \(store.plex.serverName ?? "this server").")
                    .font(Typeface.body).foregroundStyle(Color.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: dp(18)) {
                            #if os(iOS)
                            PageHeader(title: kind == "movie" ? "Movies" : "TV Shows")
                            #endif
                            toolbar(browse, libraries)
                            switch browse.view {
                            case .home: tabHome(browse)
                            case .grid: grid(browse, proxy: proxy)
                            case .collections: collections(browse)
                            }
                        }
                        .padding(.bottom, dp(24))
                        .padding(.top, topInset)
                    }
                    .onChange(of: jumpTarget) { _, id in if let id { withAnimation { proxy.scrollTo(id, anchor: .top) } } }
                }
            }
        }
        .task(id: kind) { await store.openLibrary(kind) }
        .confirmationDialog("Sort by", isPresented: $choosingSort) {
            ForEach(LIBRARY_SORTS, id: \.id) { sort in Button(sort.label) { Task { await store.setSort(kind, sort.id) } } }
        }
        .confirmationDialog("Decade", isPresented: $choosingDecade) {
            Button("All decades") { Task { await store.setFilter(kind, decade: .some(nil)) } }
            ForEach(browse.decades, id: \.id) { d in Button(d.title) { Task { await store.setFilter(kind, decade: .some(d)) } } }
        }
    }

    private func toolbar(_ browse: Browse, _ libraries: [LibraryChoice]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: dp(8)) {
                Pill(title: "Home", on: browse.view == .home) { store.setLibraryView(kind, .home) }
                Pill(title: "All", on: browse.view == .grid) { store.setLibraryView(kind, .grid) }
                Pill(title: "Collections", on: browse.view == .collections) { store.setLibraryView(kind, .collections) }
                if libraries.count > 1 {
                    Divider().frame(height: dp(24))
                    ForEach(libraries) { l in
                        Pill(title: l.section.title, on: browse.choice == l) { Task { await store.openLibrary(kind, l) } }
                    }
                }
            }
            .padding(.horizontal, pageMargin).padding(.vertical, dp(8))
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    @ViewBuilder
    private func tabHome(_ browse: Browse) -> some View {
        let home = store.home
        let resumable = home.continueWatching.filter { kind == "movie" ? $0.type == "movie" : $0.type == "episode" }
        if !resumable.isEmpty {
            CardRow(title: "Continue Watching") {
                ForEach(resumable) { i in poster(i) }
            }
        }
        if kind == "movie" && !home.recentMovies.isEmpty {
            CardRow(title: "Recently Added") { ForEach(home.recentMovies) { i in poster(i) } }
        }
        if kind == "show" && !home.recentEpisodes.isEmpty {
            CardRow(title: "Recently Added") {
                ForEach(home.recentEpisodes) { g in
                    PosterCard(title: g.showTitle, subtitle: g.count > 1 ? "\(g.count) new episodes" : g.newest.caption,
                               url: store.imageUrl(g.serverBase, g.thumb, width: 300, height: 450), badge: g.count) { open(g.newest) }
                }
            }
        }
        if !browse.released.isEmpty {
            CardRow(title: "Recently Released") { ForEach(browse.released) { i in poster(i) } }
        }
        if browse.collections == nil { ProgressView().frame(maxWidth: .infinity).padding(dp(30)) }
    }

    private func poster(_ i: PlexItem) -> some View {
        PosterCard(title: i.rowTitle, subtitle: i.type == "episode" ? i.episodeLine : i.caption,
                   url: store.imageUrl(i.serverBase, i.type == "episode" ? (i.grandparentThumb ?? i.thumb) : i.thumb, width: 300, height: 450),
                   progress: i.resumeFraction, watched: i.isWatched, tag: i.sourceTag) { open(i) }
            .itemMenu(i)
    }

    @ViewBuilder
    private func grid(_ browse: Browse, proxy: ScrollViewProxy) -> some View {
        let sortLabel = LIBRARY_SORTS.first { $0.id == browse.sort }?.label ?? "A–Z"
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: dp(8)) {
                Pill(title: "Sort · \(sortLabel)", on: browse.sort != "titleSort:asc") { choosingSort = true }
                Pill(title: "Unwatched", on: browse.unwatched) { Task { await store.setFilter(kind, unwatched: !browse.unwatched) } }
                if browse.decades.count > 1 {
                    Pill(title: browse.decade?.title ?? "All decades", on: browse.decade != nil) { choosingDecade = true }
                }
                if !browse.genres.isEmpty {
                    Pill(title: "All genres", on: browse.genre == nil) { Task { await store.setFilter(kind, genre: .some(nil)) } }
                    ForEach(browse.genres, id: \.id) { g in
                        Pill(title: g.title, on: browse.genre?.id == g.id) { Task { await store.setFilter(kind, genre: .some(g)) } }
                    }
                }
            }
            .padding(.horizontal, pageMargin).padding(.vertical, dp(6))
        }
        #if os(tvOS)
        .focusSection()
        #endif
        if browse.sort == "titleSort:asc" && browse.letters.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: dp(4)) {
                    ForEach(browse.letters, id: \.letter) { l in
                        Button {
                            Task { if let at = await store.jumpTo(kind, letter: l.letter), let items = store.browse[kind]?.items, items.indices.contains(at) { jumpTarget = items[at].id } }
                        } label: { LetterLabel(letter: l.letter) }
                        .buttonStyle(PlainFocusStyle())
                    }
                }
                .padding(.horizontal, pageMargin)
            }
            #if os(tvOS)
            .focusSection()
            #endif
        }
        if let error = browse.error { ErrorNote(text: error).padding(.horizontal, pageMargin) }
        LazyVGrid(columns: [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth), spacing: dp(14))], alignment: .leading, spacing: dp(18)) {
            ForEach(browse.items) { i in
                PosterCard(title: i.title, subtitle: i.caption, url: store.imageUrl(i.serverBase, i.thumb, width: 300, height: 450),
                           progress: i.resumeFraction, watched: i.isWatched, tag: i.sourceTag) { open(i) }
                    .itemMenu(i)
                    .id(i.id)
                    .onAppear {
                        // The next page in before it's reached.
                        if i.id == browse.items.last?.id, browse.total > browse.items.count { Task { await store.loadMore(kind) } }
                    }
            }
        }
        .padding(.horizontal, pageMargin)
        if browse.busy { ProgressView().frame(maxWidth: .infinity).padding(dp(20)) }
    }

    @ViewBuilder
    private func collections(_ browse: Browse) -> some View {
        if let collections = browse.collections {
            if collections.isEmpty {
                Text("No collections in \(browse.choice?.section.title ?? "this library") yet. Collections made in Plex show up here.")
                    .font(Typeface.body).foregroundStyle(Color.muted).padding(.horizontal, pageMargin)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth), spacing: dp(14))], alignment: .leading, spacing: dp(18)) {
                    ForEach(collections) { c in
                        PosterCard(title: c.title, subtitle: c.caption, url: store.imageUrl(c.serverBase, c.thumb, width: 300, height: 450)) {
                            store.navigate(.collection(ratingKey: c.ratingKey, title: c.title, serverBase: c.serverBase))
                        }
                    }
                }
                .padding(.horizontal, pageMargin)
            }
        } else {
            ProgressView().frame(maxWidth: .infinity).padding(dp(30))
        }
    }

    private func open(_ item: PlexItem) {
        if item.type == "episode" { store.navigate(.detail(ratingKey: item.grandparentRatingKey ?? item.ratingKey, serverBase: item.serverBase)) }
        else { store.navigate(.detail(ratingKey: item.ratingKey, serverBase: item.serverBase)) }
    }
}
