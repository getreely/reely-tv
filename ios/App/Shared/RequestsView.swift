import SwiftUI
import ReelyCore

/**
 * Requests, as on the Fire TV: connecting to Reely, the server owner's app for adding
 * movies and shows, then searching it and browsing what's trending and popular. Each
 * poster says where it's got to; its page asks for it, a show season by season.
 */
struct RequestsView: View {
    @Environment(ReelyStore.self) private var store
    @State private var address = ""
    @State private var query = ""

    var body: some View {
        let r = store.requests
        Group {
            if !r.isConnected { connect } else { browse }
        }
        .task(id: r.address) { if r.isConnected && r.rows.isEmpty { await store.loadRequests() } }
    }

    private var connect: some View {
        let r = store.requests
        return ScrollView {
            VStack(alignment: .leading, spacing: dp(14)) {
                #if os(iOS)
                PageHeader(title: "Requests").padding(.horizontal, -pageMargin)
                #endif
                Text("Ask for movies and shows").font(Typeface.display).foregroundStyle(Color.chalk)
                Text("Requests go to Reely, the server owner's app for adding movies and shows. Enter its address; you're signed in with your Plex account.")
                    .font(Typeface.body).foregroundStyle(Color.muted)
                if let error = r.error { ErrorNote(text: error) }
                LiveField(label: "Reely address, like 192.168.1.5:8788", text: $address, address: true)
                    .onSubmit { Task { await store.connectReely(address) } }
                PrimaryButton(title: r.connecting ? "Connecting…" : "Connect") { Task { await store.connectReely(address) } }
                    .disabled(r.connecting)
            }
            .frame(maxWidth: dp(640), alignment: .leading)
            .padding(.horizontal, pageMargin)
            .padding(.top, topInset)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var browse: some View {
        let r = store.requests
        let rows = r.shownRows
        let mine = r.mineTitles
        return ScrollView {
            VStack(alignment: .leading, spacing: dp(22)) {
                #if os(iOS)
                PageHeader(title: "Requests")
                #endif
                HStack(spacing: dp(10)) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Color.muted)
                    TextField("Search movies and shows to request", text: $query)
                        .font(Typeface.body).autocorrectionDisabled()
                }
                .padding(dp(12))
                .background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.surfaceRaised))
                .padding(.horizontal, pageMargin)
                if let error = r.error { ErrorNote(text: error).padding(.horizontal, pageMargin) }
                if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    if r.searching && r.results.isEmpty {
                        ProgressView().frame(maxWidth: .infinity).padding(dp(40))
                    } else if r.results.isEmpty {
                        Text("Nothing matched \u{201C}\(query)\u{201D}.").font(Typeface.body).foregroundStyle(Color.muted).padding(.horizontal, pageMargin)
                    } else {
                        RequestRowView(title: "Results", titles: r.results)
                    }
                } else {
                    if r.loading && rows.isEmpty { ProgressView().frame(maxWidth: .infinity).padding(dp(40)) }
                    if !mine.isEmpty { RequestRowView(title: "Your requests", titles: mine) }
                    ForEach(rows) { row in RequestRowView(title: row.title, titles: row.titles) }
                }
            }
            .padding(.top, topInset)
            .padding(.bottom, dp(24))
        }
        .refreshable { await store.loadRequests() }
        .task(id: query) {
            // A moment after the typing stops.
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            await store.searchRequests(query)
        }
    }
}

/// A row of titles to ask for, each saying where it's got to.
struct RequestRowView: View {
    @Environment(ReelyStore.self) private var store
    let title: String
    let titles: [RequestTitle]

    var body: some View {
        CardRow(title: title) {
            ForEach(titles) { t in
                PosterCard(title: t.title, subtitle: t.year.map(String.init), url: t.poster.flatMap(URL.init(string:)),
                           tag: store.requests.badge(t)) { store.navigate(.requestTitle(t)) }
            }
        }
    }
}

/// A title's page in Requests: what it is, where it is already, and the button to ask for it.
struct RequestTitleView: View {
    @Environment(ReelyStore.self) private var store
    let title: RequestTitle

    var body: some View {
        let page = store.requestPage?.title.key == title.key ? store.requestPage : nil
        let detail = page?.detail
        let status = store.requests.mine.first { $0.title.key == title.key }?.status
        ScrollView {
            VStack(alignment: .leading, spacing: dp(14)) {
                ZStack(alignment: .bottomLeading) {
                    // The picture fills the width without widening the page.
                    Color.clear.frame(maxWidth: .infinity).frame(height: dp(260) + topInset)
                        .overlay(RemoteImage(url: (detail?.backdrop ?? title.poster).flatMap(URL.init(string:))))
                        .clipped()
                        .overlay(LinearGradient(colors: [.clear, Color.ink.opacity(0.7), Color.ink], startPoint: .top, endPoint: .bottom))
                    VStack(alignment: .leading, spacing: dp(8)) {
                        Text(title.title).font(Typeface.display).foregroundStyle(Color.chalk).lineLimit(2)
                        Text(facts(detail)).font(Typeface.meta).foregroundStyle(Color.muted)
                    }
                    .padding(.horizontal, pageMargin)
                }
                VStack(alignment: .leading, spacing: dp(12)) {
                    if let overview = detail?.title.overview ?? title.overview {
                        Text(overview).font(Typeface.body).foregroundStyle(Color.chalk.opacity(0.85)).frame(maxWidth: dp(720), alignment: .leading)
                    }
                    if page == nil || page?.busy == true { ProgressView() }
                    if let error = page?.error { ErrorNote(text: error) }
                    if let note = page?.heldNote { Text(note).font(Typeface.meta).foregroundStyle(Color.muted) }
                    if let words = requestStatusWords(status) { Text(words).font(Typeface.meta).foregroundStyle(Color.muted) }
                    if let outcome = page?.outcome { Text(outcome).font(Typeface.meta).foregroundStyle(Color.chalk) }
                    if let page, page.canAsk {
                        ActionButton(title: page.actionLabel, systemImage: page.places?.adds == true ? "plus" : "paperplane", filled: true) {
                            Task { await store.submitRequest() }
                        }
                        .disabled(page.sending)
                        if page.addable.count > 1 {
                            Text("Library").font(Typeface.label).foregroundStyle(Color.faint)
                            chips(page.addable.map { ($0.id, $0.name, page.libraryId == $0.id) }) { store.chooseRequestLibrary($0) }
                        }
                        if title.isShow && !page.offered.isEmpty {
                            Text("Seasons").font(Typeface.label).foregroundStyle(Color.faint)
                            chips(page.offered.map { ($0.number, $0.name, page.chosen.contains($0.number)) }) { store.toggleRequestSeason($0) }
                        }
                    }
                }
                .padding(.horizontal, pageMargin)
            }
            .padding(.bottom, dp(24))
        }
        .ignoresSafeArea(edges: .top)
        .task(id: title.key) { await store.openRequestTitle(title) }
    }

    private func chips(_ items: [(Int, String, Bool)], _ pick: @escaping (Int) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: dp(8)) {
                ForEach(items, id: \.0) { id, name, on in Pill(title: name, on: on) { pick(id) } }
            }
            .padding(.vertical, dp(8))
        }
        #if os(tvOS)
        .scrollClipDisabled()
        .focusSection()
        #endif
    }

    private func facts(_ detail: RequestDetail?) -> String {
        let runtime = detail?.runtime.map { $0 >= 60 ? "\($0 / 60)h \($0 % 60)m" : "\($0)m" }
        return [title.year.map(String.init), title.isShow ? "Show" : "Movie", runtime, detail?.status,
                detail.map { $0.genres.prefix(3).joined(separator: ", ") }.flatMap { $0.isEmpty ? nil : $0 }]
            .compactMap { $0 }.joined(separator: "  ·  ")
    }
}

/// "Dune is ready to watch": something asked for through Requests, now on the server.
struct ReadyCard: View {
    @Environment(ReelyStore.self) private var store
    let title: RequestTitle
    let more: Int

    var body: some View {
        HStack(spacing: dp(14)) {
            RemoteImage(url: title.poster.flatMap(URL.init(string:)))
                .frame(width: dp(48), height: dp(72)).clipShape(RoundedRectangle(cornerRadius: dp(6)))
            VStack(alignment: .leading, spacing: dp(4)) {
                Text("\(title.title) is ready to watch").font(Typeface.meta).foregroundStyle(Color.chalk).lineLimit(2)
                Text(more > 0 ? "You asked for it. And \(more == 1 ? "1 more" : "\(more) more") after this." : "You asked for it, and it's here.")
                    .font(Typeface.label).foregroundStyle(Color.muted)
            }
            Spacer(minLength: 0)
            PanelButton(title: "Watch", systemImage: "play.fill", filled: true) { Task { await store.openReady(title) } }
            PanelButton(title: "Dismiss", systemImage: "xmark") { store.dismissReady(title) }
        }
        .padding(dp(14))
        .background(RoundedRectangle(cornerRadius: dp(14)).fill(Color.surfaceRaised))
        .padding(.horizontal, pageMargin)
        #if os(tvOS)
        .focusSection()
        #endif
    }
}
