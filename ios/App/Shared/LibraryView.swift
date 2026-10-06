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
                    // The A–Z down the right edge, as on the Fire TV and the Android phone.
                    .overlay(alignment: .trailing) {
                        if browse.view == .grid && browse.sort == "titleSort:asc" && browse.letters.count > 1 {
                            LetterRail(letters: browse.letters.map(\.letter)) { letter in jump(letter) }
                                .padding(.top, topInset)
                        }
                    }
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
                        // With more than one server, which one each library is on: two called Movies otherwise look the same.
                        Pill(title: store.plex.servers.count > 1 && l.baseUrl != IPTV_SOURCE ? "\(l.section.title) · \(l.serverName)" : l.section.title,
                             on: browse.choice == l) { Task { await store.openLibrary(kind, l) } }
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
        // This library's: what's part-watched in it, and what's newly arrived in it.
        let resumable = home.continueWatching.filter { (kind == "movie" ? $0.type == "movie" : $0.type == "episode") && inLibrary($0, browse.choice) }
        if !resumable.isEmpty {
            CardRow(title: "Continue Watching") {
                ForEach(resumable) { i in poster(i) }
            }
        }
        if kind == "movie" && !browse.added.isEmpty {
            CardRow(title: "Recently Added") { ForEach(browse.added) { i in poster(i) } }
        }
        if kind == "show" && !browse.addedShows.isEmpty {
            CardRow(title: "Recently Added") {
                ForEach(browse.addedShows) { g in
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
        if let error = browse.error { ErrorNote(text: error).padding(.horizontal, pageMargin) }
        LazyVGrid(columns: gridColumns, alignment: .leading, spacing: dp(18)) {
            ForEach(browse.items) { i in
                PosterCard(title: i.title, subtitle: i.caption, url: store.imageUrl(i.serverBase, i.thumb, width: 300, height: 450),
                           progress: i.resumeFraction, watched: i.isWatched, tag: i.sourceTag, fill: true) { open(i) }
                    .itemMenu(i)
                    .id(i.id)
                    .onAppear {
                        // The next page in before it's reached.
                        if i.id == browse.items.last?.id, browse.total > browse.items.count { Task { await store.loadMore(kind) } }
                    }
            }
        }
        .padding(.leading, pageMargin)
        // Room for the A–Z down the side.
        .padding(.trailing, browse.sort == "titleSort:asc" && browse.letters.count > 1 ? railWidth + dp(6) : pageMargin)
        if browse.busy { ProgressView().frame(maxWidth: .infinity).padding(dp(20)) }
    }

    @ViewBuilder
    private func collections(_ browse: Browse) -> some View {
        if let collections = browse.collections {
            if collections.isEmpty {
                Text("No collections in \(browse.choice?.section.title ?? "this library") yet. Collections made in Plex show up here.")
                    .font(Typeface.body).foregroundStyle(Color.muted).padding(.horizontal, pageMargin)
            } else {
                LazyVGrid(columns: gridColumns, alignment: .leading, spacing: dp(18)) {
                    ForEach(collections) { c in
                        PosterCard(title: c.title, subtitle: c.caption, url: store.imageUrl(c.serverBase, c.thumb, width: 300, height: 450), fill: true) {
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

    /// Three across on a phone, as many as fit on an iPad or a television.
    private var gridColumns: [GridItem] {
        #if os(tvOS)
        return [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth + 40), spacing: dp(14))]
        #else
        if UIDevice.current.userInterfaceIdiom == .pad { return [GridItem(.adaptive(minimum: 130), spacing: 14)] }
        return Array(repeating: GridItem(.flexible(), spacing: 12), count: 3)
        #endif
    }

    private func jump(_ letter: String) {
        Task {
            if let at = await store.jumpTo(kind, letter: letter), let items = store.browse[kind]?.items, items.indices.contains(at) { jumpTarget = items[at].id }
        }
    }

    private func open(_ item: PlexItem) {
        if item.type == "episode" { store.navigate(.detail(ratingKey: item.grandparentRatingKey ?? item.ratingKey, serverBase: item.serverBase)) }
        else { store.navigate(.detail(ratingKey: item.ratingKey, serverBase: item.serverBase)) }
    }
}

/// Part of the library open: from its server, and from it where Plex says which library it's in.
func inLibrary(_ item: PlexItem, _ choice: LibraryChoice?) -> Bool {
    guard let choice, choice.baseUrl != IPTV_SOURCE else { return true }
    if let base = item.serverBase, base != choice.baseUrl { return false }
    return item.librarySectionId == nil || item.librarySectionId == choice.section.key
}

#if os(tvOS)
let railWidth: CGFloat = 70
#else
let railWidth: CGFloat = 22
#endif

/**
 * The A–Z down the right edge. On a phone, a tap on a letter goes there, and so does a finger
 * run down it, the letter under it shown big; on a television each letter takes the cursor.
 */
struct LetterRail: View {
    let letters: [String]
    let onLetter: (String) -> Void
    @State private var pointing: String?

    var body: some View {
        #if os(tvOS)
        ScrollView(showsIndicators: false) {
            VStack(spacing: 2) {
                ForEach(letters, id: \.self) { l in
                    Button { onLetter(l) } label: { LetterLabel(letter: l) }.buttonStyle(PlainFocusStyle())
                }
            }
            .padding(.vertical, 20)
        }
        .frame(width: railWidth)
        .focusSection()
        .padding(.trailing, 16)
        #else
        GeometryReader { g in
            VStack(spacing: 0) {
                ForEach(letters, id: \.self) { l in
                    Text(l).font(.system(size: 11, weight: .semibold)).foregroundStyle(Color.muted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                let at = min(letters.count - 1, max(0, Int(v.location.y / max(1, g.size.height) * CGFloat(letters.count))))
                if letters[at] != pointing {
                    pointing = letters[at]
                    onLetter(letters[at])
                }
            }.onEnded { _ in pointing = nil })
        }
        .frame(width: railWidth)
        .frame(maxHeight: min(CGFloat(letters.count) * 20, 520))
        .padding(.trailing, 2)
        .overlay(alignment: .leading) {
            if let pointing {
                Text(pointing).font(.system(size: 34, weight: .bold)).foregroundStyle(Color.chalk)
                    .frame(width: 64, height: 64).background(Circle().fill(Color.surfaceHigh))
                    .offset(x: -80)
            }
        }
        #endif
    }
}
