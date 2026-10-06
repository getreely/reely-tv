import SwiftUI
import ReelyCore

/**
 * A title's page, as on the Fire TV: its backdrop, logo or name, what it is, its story,
 * then Play or Resume, Restart, Watched, Quality, Trailer; for a show, its seasons and the
 * episodes of the one chosen; then the cast and More like this.
 */
struct DetailView: View {
    @Environment(ReelyStore.self) private var store
    let ratingKey: String
    let serverBase: String?
    @State private var choosingVersion = false

    var body: some View {
        Group {
            if let page = store.detail, let d = page.detail {
                content(page, d)
            } else if let error = store.detail?.error {
                VStack(spacing: dp(16)) {
                    ErrorNote(text: error)
                    PrimaryButton(title: "Try again") { Task { await store.openDetail(ratingKey: ratingKey, serverBase: serverBase) } }
                }
                .frame(maxWidth: dp(480)).padding(pageMargin).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: ratingKey) {
            if store.detail?.detail?.ratingKey != ratingKey { await store.openDetail(ratingKey: ratingKey, serverBase: serverBase) }
        }
    }

    @ViewBuilder
    private func content(_ page: DetailPage, _ d: PlexDetail) -> some View {
        let episode = d.isShow ? page.focused : nil
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(20)) {
                header(page, d, episode: episode)
                if d.isShow && !page.seasons.isEmpty { seasons(page) }
                if d.isShow && !page.episodes.isEmpty { episodes(page) }
                if !d.roles.isEmpty { cast(d, page) }
                if !page.related.isEmpty {
                    CardRow(title: "More like this") {
                        ForEach(page.related) { item in
                            PosterCard(title: item.title, subtitle: item.caption,
                                       url: store.imageUrl(item.serverBase, item.thumb, width: 300, height: 450),
                                       watched: item.isWatched) { store.navigate(.detail(ratingKey: item.ratingKey, serverBase: item.serverBase)) }
                        }
                    }
                }
            }
            .padding(.bottom, dp(32))
        }
        .background(alignment: .top) {
            RemoteImage(url: store.imageUrl(page.serverBase, d.art ?? d.thumb, width: 1280, height: 720))
                .frame(maxWidth: .infinity).frame(height: dp(380)).clipped()
                .overlay(LinearGradient(colors: [Color.ink.opacity(0.2), Color.ink.opacity(0.75), Color.ink], startPoint: .top, endPoint: .bottom))
                .overlay(LinearGradient(colors: [Color.ink.opacity(0.9), .clear], startPoint: .leading, endPoint: .trailing))
                .ignoresSafeArea()
        }
        .confirmationDialog("Quality", isPresented: $choosingVersion) {
            ForEach(Array(d.versions.enumerated()), id: \.offset) { i, v in
                Button(v.detail.map { "\(v.label) · \($0)" } ?? v.label) { store.chooseVersion(i) }
            }
        }
    }

    @ViewBuilder
    private func header(_ page: DetailPage, _ d: PlexDetail, episode: PlexItem?) -> some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            if let logo = store.logoUrl(page.serverBase, d.logo) {
                RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: dp(340), maxHeight: dp(100), alignment: .leading)
            } else {
                Text(d.title).font(Typeface.display).foregroundStyle(Color.chalk).lineLimit(2)
            }
            if let episode {
                Text([episode.caption, episode.title].compactMap { $0 }.joined(separator: "  ·  "))
                    .font(Typeface.rowTitle).foregroundStyle(Color.chalk)
            }
            Text(facts(d)).font(Typeface.meta).foregroundStyle(Color.muted)
            let qualities = episode?.qualities ?? d.qualities
            if !qualities.isEmpty {
                HStack(spacing: dp(6)) {
                    ForEach(qualities, id: \.self) { q in
                        Text(q).font(Typeface.label).foregroundStyle(Color.chalk)
                            .padding(.horizontal, dp(8)).padding(.vertical, dp(3))
                            .background(RoundedRectangle(cornerRadius: dp(5)).strokeBorder(Color.line))
                    }
                }
            }
            if let summary = episode?.summary ?? d.summary {
                Text(summary).font(Typeface.body).foregroundStyle(Color.chalk.opacity(0.85)).lineLimit(4)
                    .frame(maxWidth: dp(640), alignment: .leading)
            }
            if let error = store.playError { ErrorNote(text: error).frame(maxWidth: dp(640)) }
            actions(page, d, episode: episode)
        }
        .padding(.horizontal, pageMargin)
        .padding(.top, dp(110))
    }

    private func facts(_ d: PlexDetail) -> String {
        var parts: [String] = []
        if let y = d.year { parts.append(String(y)) }
        if d.isShow && d.childCount > 0 { parts.append(d.childCount == 1 ? "1 season" : "\(d.childCount) seasons") }
        if !d.isShow, d.durationMs > 0 { parts.append(formatDuration(d.durationMs)) }
        if let r = d.contentRating { parts.append(r) }
        if let s = d.studio { parts.append(s) }
        return parts.joined(separator: "  ·  ")
    }

    @ViewBuilder
    private func actions(_ page: DetailPage, _ d: PlexDetail, episode: PlexItem?) -> some View {
        // What Play would resume. For a show that is the part-watched episode.
        let resumeFrom = episode?.viewOffsetMs ?? (d.isShow ? page.episodes.first { $0.resumeFraction != nil }?.viewOffsetMs ?? 0 : d.viewOffsetMs)
        let watched = episode?.isWatched ?? (d.isShow ? d.leafCount > 0 && d.viewedLeafCount >= d.leafCount : d.viewCount > 0)
        HStack(spacing: dp(10)) {
            ActionButton(title: resumeFrom > 0 ? "Resume" : "Play", systemImage: "play.fill", filled: true) { playMain(page, d, episode: episode, resume: true) }
            if resumeFrom > 0 {
                ActionButton(title: "Restart", systemImage: "arrow.counterclockwise") { playMain(page, d, episode: episode, resume: false) }
            }
            ActionButton(title: watched ? "Unwatch" : "Watched", systemImage: "checkmark") {
                Task { await store.setWatched(episode ?? item(of: d, page), !watched) }
            }
            if d.versions.count > 1 && episode == nil && !d.isShow {
                ActionButton(title: "Quality", systemImage: "sparkles.tv") { choosingVersion = true }
            }
            if !page.trailers.isEmpty && episode == nil {
                ActionButton(title: "Trailer", systemImage: "film") { Task { await store.playTrailer() } }
            }
        }
        .padding(.top, dp(4))
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func item(of d: PlexDetail, _ page: DetailPage) -> PlexItem {
        PlexItem(ratingKey: d.ratingKey, title: d.title, type: d.type, durationMs: d.durationMs, viewOffsetMs: d.viewOffsetMs,
                 leafCount: d.leafCount, viewedLeafCount: d.viewedLeafCount, viewCount: d.viewCount, serverBase: page.serverBase)
    }

    private func playMain(_ page: DetailPage, _ d: PlexDetail, episode: PlexItem?, resume: Bool) {
        Task {
            if let episode { await store.play(episode, resume: resume, queue: page.episodes) }
            else { await store.play(item(of: d, page), resume: resume, mediaIndex: page.versionIndex) }
        }
    }

    private func seasons(_ page: DetailPage) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: dp(8)) {
                ForEach(page.seasons) { season in
                    Pill(title: season.title, on: season.ratingKey == page.season?.ratingKey) { Task { await store.selectSeason(season) } }
                }
            }
            .padding(.horizontal, pageMargin).padding(.vertical, dp(8))
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func episodes(_ page: DetailPage) -> some View {
        CardRow(title: page.season?.title ?? "Episodes") {
            ForEach(page.episodes) { e in
                WideCard(title: [e.caption, e.title].compactMap { $0 }.joined(separator: "  ·  "),
                         subtitle: e.durationMs > 0 ? formatDuration(e.durationMs) : nil,
                         url: store.imageUrl(e.serverBase, e.thumb, width: 480, height: 270),
                         progress: e.resumeFraction) {
                    Task { await store.play(e, resume: true, queue: page.episodes) }
                }
            }
        }
    }

    private func cast(_ d: PlexDetail, _ page: DetailPage) -> some View {
        CardRow(title: "Cast") {
            ForEach(Array(d.roles.prefix(30).enumerated()), id: \.offset) { _, role in
                Button {
                    if let id = role.id { store.navigate(.person(id: id, name: role.name, serverBase: page.serverBase)) }
                } label: {
                    VStack(spacing: dp(6)) {
                        RemoteImage(url: role.thumb.flatMap { $0.hasPrefix("http") ? URL(string: $0) : store.imageUrl(page.serverBase, $0, width: 200, height: 200) })
                            .frame(width: dp(84), height: dp(84)).clipShape(Circle())
                        Text(role.name).font(Typeface.label).foregroundStyle(Color.chalk).lineLimit(1)
                        if let r = role.role { Text(r).font(Typeface.label).foregroundStyle(Color.muted).lineLimit(1) }
                    }
                    .frame(width: dp(110))
                }
                .buttonStyle(CardStyle())
            }
        }
    }
}

/// A round-cornered button with a symbol and a word: the title page's actions.
struct ActionButton: View {
    let title: String
    let systemImage: String
    var filled = false
    let action: () -> Void
    @Environment(\.accent) private var accent

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(Typeface.meta)
                .foregroundStyle(filled ? accent.onColor : Color.chalk)
                .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
                .background(Capsule().fill(filled ? accent.swiftColor : Color.surfaceHigh))
        }
        .buttonStyle(CardStyle())
    }
}

/// One of a row of choices: seasons, sorts, filters.
struct Pill: View {
    let title: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(Typeface.meta)
                .foregroundStyle(on ? Color.ink : Color.chalk)
                .padding(.horizontal, dp(14)).padding(.vertical, dp(8))
                .background(Capsule().fill(on ? Color.chalk : Color.surfaceHigh))
        }
        .buttonStyle(CardStyle())
    }
}
