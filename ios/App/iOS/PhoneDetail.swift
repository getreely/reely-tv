import SwiftUI
import ReelyCore

/**
 * A title's page on iPhone and iPad, as the Apple TV and Netflix apps lay theirs out: the
 * backdrop to the screen's edges with the logo over it, one wide Play, the story, a row of
 * actions, then for a show its seasons in a menu and the episodes as a list with their
 * pictures; the cast, and More like this.
 */
struct PhoneDetail: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    let ratingKey: String
    let serverBase: String?
    @State private var choosingVersion = false
    @State private var storyOpen = false

    var body: some View {
        Group {
            // The page's own title, not the last one opened: going back finds this one again.
            if let page = store.detail, let d = page.detail, d.ratingKey == ratingKey {
                content(page, d)
            } else if let error = store.detail?.error {
                VStack(spacing: 16) {
                    ErrorNote(text: error)
                    PrimaryButton(title: "Try again") { Task { await store.openDetail(ratingKey: ratingKey, serverBase: serverBase) } }
                }
                .frame(maxWidth: 480).padding(pageMargin).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color.ink)
        .task(id: ratingKey) {
            if store.detail?.detail?.ratingKey != ratingKey { await store.openDetail(ratingKey: ratingKey, serverBase: serverBase) }
        }
        .onChange(of: store.detail?.detail?.theme) { _, _ in themeMusic() }
        .onChange(of: store.playing == nil) { _, idle in if idle { themeMusic() } else { ThemePlayer.shared.stop() } }
        .onDisappear { ThemePlayer.shared.stop() }
    }

    @ViewBuilder
    private func content(_ page: DetailPage, _ d: PlexDetail) -> some View {
        let episode = d.isShow ? page.focused : nil
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 18) {
                header(page, d)
                VStack(alignment: .leading, spacing: 14) {
                    playButton(page, d, episode: episode)
                    if let error = store.playError { ErrorNote(text: error) }
                    story(d, episode: episode)
                    actions(page, d, episode: episode)
                }
                .padding(.horizontal, pageMargin)
                if d.isShow && !page.seasons.isEmpty { seasonPicker(page) }
                if d.isShow && !page.episodes.isEmpty { episodeList(page) }
                if !d.roles.isEmpty { cast(d, page) }
                if !page.related.isEmpty {
                    CardRow(title: "More like this") {
                        ForEach(page.related) { item in
                            PosterCard(title: item.title, subtitle: item.caption,
                                       url: store.imageUrl(item.serverBase, item.thumb, width: 300, height: 450),
                                       watched: item.isWatched, tag: item.sourceTag) { store.navigate(.detail(ratingKey: item.ratingKey, serverBase: item.serverBase)) }
                                .itemMenu(item)
                        }
                    }
                }
            }
            .padding(.bottom, 32)
        }
        .ignoresSafeArea(edges: .top)
        .confirmationDialog("Quality", isPresented: $choosingVersion) {
            ForEach(Array(d.versions.enumerated()), id: \.offset) { i, v in
                Button(v.detail.map { "\(v.label) · \($0)" } ?? v.label) { store.chooseVersion(i) }
            }
        }
    }

    // MARK: The top

    private func header(_ page: DetailPage, _ d: PlexDetail) -> some View {
        ZStack(alignment: .bottom) {
            Color.clear.frame(height: 440)
                .overlay(RemoteImage(url: store.imageUrl(page.serverBase, d.art ?? d.thumb, width: 1280, height: 720)))
                .clipped()
                .overlay(LinearGradient(stops: [.init(color: Color.ink.opacity(0.5), location: 0), .init(color: .clear, location: 0.3),
                                                .init(color: Color.ink.opacity(0.7), location: 0.75), .init(color: Color.ink, location: 1)],
                                        startPoint: .top, endPoint: .bottom))
            VStack(spacing: 10) {
                if let logo = store.logoUrl(page.serverBase, d.logo) {
                    RemoteImage(url: logo, contentMode: .fit, background: .clear).frame(maxWidth: 280, maxHeight: 100)
                } else {
                    Text(d.title).font(Typeface.geist(30, .bold)).foregroundStyle(Color.chalk)
                        .multilineTextAlignment(.center).lineLimit(3).padding(.horizontal, 24)
                }
                HStack(spacing: 8) {
                    Text(facts(d)).font(Typeface.geist(13, .medium)).foregroundStyle(Color.chalk.opacity(0.75))
                    ForEach((page.focused?.qualities ?? d.qualities).prefix(3), id: \.self) { q in
                        Text(q).font(Typeface.geist(11, .semibold)).foregroundStyle(Color.chalk)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: 4).strokeBorder(Color.chalk.opacity(0.4)))
                    }
                }
                .lineLimit(1)
            }
            .padding(.bottom, 6)
        }
    }

    private func facts(_ d: PlexDetail) -> String {
        var parts: [String] = []
        if let y = d.year { parts.append(String(y)) }
        if d.isShow && d.childCount > 0 { parts.append(d.childCount == 1 ? "1 season" : "\(d.childCount) seasons") }
        if !d.isShow, d.durationMs > 0 { parts.append(formatDuration(d.durationMs)) }
        if let r = d.contentRating { parts.append(r) }
        return parts.joined(separator: " · ")
    }

    /// One wide Play: the film, or the episode you're up to, from where it was left.
    private func playButton(_ page: DetailPage, _ d: PlexDetail, episode: PlexItem?) -> some View {
        let offset = episode?.viewOffsetMs ?? (d.isShow ? 0 : d.viewOffsetMs)
        let duration = episode?.durationMs ?? d.durationMs
        let label: String = {
            let verb = offset > 0 ? "Resume" : "Play"
            guard let episode else { return verb }
            return "\(verb) \(episode.caption ?? episode.title)"
        }()
        return VStack(spacing: 8) {
            Button { play(page, d, episode: episode, resume: true) } label: {
                Label(label, systemImage: "play.fill")
                    .font(Typeface.geist(17, .semibold)).foregroundStyle(Color.ink)
                    .frame(maxWidth: .infinity).frame(height: 50)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.chalk))
            }
            .buttonStyle(PressStyle())
            if offset > 0 && duration > 0 {
                HStack(spacing: 10) {
                    ProgressView(value: Double(offset), total: Double(duration)).tint(accent.swiftColor)
                    Text("\(max(1, (duration - offset) / 60_000)) min left").font(Typeface.geist(12, .medium)).foregroundStyle(Color.muted)
                }
            }
        }
    }

    private func story(_ d: PlexDetail, episode: PlexItem?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let episode {
                Text([episode.caption, episode.title].compactMap { $0 }.joined(separator: " · "))
                    .font(Typeface.geist(15, .semibold)).foregroundStyle(Color.chalk)
            }
            if let summary = episode?.summary ?? d.summary, !summary.isEmpty {
                Text(summary).font(Typeface.geist(15)).foregroundStyle(Color.chalk.opacity(0.82))
                    .lineLimit(storyOpen ? nil : 3)
                    .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { storyOpen.toggle() } }
            }
            let credits = [d.genres.prefix(3).joined(separator: ", "), d.directors.first.map { "Directed by \($0)" }].compactMap { $0 }.filter { !$0.isEmpty }
            if !credits.isEmpty {
                Text(credits.joined(separator: " · ")).font(Typeface.geist(12, .medium)).foregroundStyle(Color.muted).lineLimit(2)
            }
        }
    }

    /// The rest of what can be done with it, a row of symbols with their names under them.
    private func actions(_ page: DetailPage, _ d: PlexDetail, episode: PlexItem?) -> some View {
        let offset = episode?.viewOffsetMs ?? (d.isShow ? 0 : d.viewOffsetMs)
        let watched = d.isShow ? d.leafCount > 0 && d.viewedLeafCount >= d.leafCount : d.viewCount > 0
        return HStack(alignment: .top, spacing: 0) {
            if let guid = d.guid {
                let listed = store.watchlist.contains(guid)
                IconAction(title: "Watchlist", systemImage: listed ? "checkmark" : "plus", on: listed) { Task { await store.toggleWatchlist() } }
            }
            IconAction(title: watched ? "Watched" : "Mark watched", systemImage: watched ? "eye.fill" : "eye", on: watched) {
                Task { await store.setWatched(item(of: d, page), !watched) }
            }
            if offset > 0 {
                IconAction(title: "Restart", systemImage: "arrow.counterclockwise") { play(page, d, episode: episode, resume: false) }
            }
            if !page.trailers.isEmpty {
                IconAction(title: "Trailer", systemImage: "film") { Task { await store.playTrailer() } }
            }
            if d.versions.count > 1 && !d.isShow {
                IconAction(title: "Quality", systemImage: "sparkles.tv") { choosingVersion = true }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 4)
    }

    // MARK: A show's seasons and episodes

    private func seasonPicker(_ page: DetailPage) -> some View {
        Menu {
            ForEach(page.seasons) { season in
                Button { Task { await store.selectSeason(season) } } label: {
                    if season.ratingKey == page.season?.ratingKey { Label(season.title, systemImage: "checkmark") } else { Text(season.title) }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(page.season?.title ?? "Seasons").font(Typeface.geist(20, .bold))
                if page.seasons.count > 1 { Image(systemName: "chevron.down").font(.system(size: 13, weight: .bold)) }
            }
            .foregroundStyle(Color.chalk)
        }
        .disabled(page.seasons.count < 2)
        .padding(.horizontal, pageMargin)
        .padding(.top, 6)
    }

    private func episodeList(_ page: DetailPage) -> some View {
        LazyVStack(alignment: .leading, spacing: 16) {
            ForEach(page.episodes) { e in
                Button { Task { await store.play(e, resume: true, queue: page.episodes) } } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Color.clear.frame(width: 140, height: 79)
                            .overlay(RemoteImage(url: store.imageUrl(e.serverBase, e.thumb, width: 480, height: 270)))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(alignment: .bottom) {
                                if let p = e.resumeFraction { Progress(fraction: p).padding(.horizontal, 6).padding(.bottom, 5) }
                            }
                            .overlay(alignment: .topTrailing) {
                                if e.isWatched {
                                    Image(systemName: "checkmark.circle.fill").font(.system(size: 16)).foregroundStyle(Color.chalk, accent.swiftColor).padding(5)
                                }
                            }
                            .overlay(Image(systemName: "play.fill").font(.system(size: 18)).foregroundStyle(.white.opacity(0.9))
                                .frame(width: 34, height: 34).background(.ultraThinMaterial, in: Circle()))
                        VStack(alignment: .leading, spacing: 3) {
                            Text([e.index.map { "\($0)." }, e.title].compactMap { $0 }.joined(separator: " "))
                                .font(Typeface.geist(15, .semibold)).foregroundStyle(Color.chalk).lineLimit(2)
                            // Its length, and when it first aired.
                            let facts = [e.durationMs > 0 ? formatDuration(e.durationMs) : nil, formatAirDate(e.airDate)].compactMap { $0 }
                            if !facts.isEmpty {
                                Text(facts.joined(separator: "  ·  ")).font(Typeface.geist(12, .medium)).foregroundStyle(Color.muted)
                            }
                            if let summary = e.summary {
                                Text(summary).font(Typeface.geist(13)).foregroundStyle(Color.muted).lineLimit(3).multilineTextAlignment(.leading)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .itemMenu(e)
            }
        }
        .padding(.horizontal, pageMargin)
    }

    private func cast(_ d: PlexDetail, _ page: DetailPage) -> some View {
        CardRow(title: "Cast") {
            ForEach(Array(d.roles.prefix(30).enumerated()), id: \.offset) { _, role in
                Button {
                    if let id = role.id { store.navigate(.person(id: id, name: role.name, serverBase: page.serverBase)) }
                } label: {
                    VStack(spacing: 6) {
                        RemoteImage(url: role.thumb.flatMap { $0.hasPrefix("http") ? URL(string: $0) : store.imageUrl(page.serverBase, $0, width: 200, height: 200) })
                            .frame(width: 76, height: 76).clipShape(Circle())
                        Text(role.name).font(Typeface.geist(12, .semibold)).foregroundStyle(Color.chalk).lineLimit(1)
                        if let r = role.role { Text(r).font(Typeface.geist(11)).foregroundStyle(Color.muted).lineLimit(1) }
                    }
                    .frame(width: 92)
                }
                .buttonStyle(PressStyle())
            }
        }
    }

    // MARK: Doing

    private func item(of d: PlexDetail, _ page: DetailPage) -> PlexItem {
        PlexItem(ratingKey: d.ratingKey, title: d.title, type: d.type, durationMs: d.durationMs, viewOffsetMs: d.viewOffsetMs,
                 leafCount: d.leafCount, viewedLeafCount: d.viewedLeafCount, viewCount: d.viewCount, serverBase: page.serverBase)
    }

    private func play(_ page: DetailPage, _ d: PlexDetail, episode: PlexItem?, resume: Bool) {
        Task {
            if let episode { await store.play(episode, resume: resume, queue: page.episodes) }
            else { await store.play(item(of: d, page), resume: resume, mediaIndex: page.versionIndex) }
        }
    }

    private func themeMusic() {
        guard store.prefs.themeMusic, store.playing == nil, let page = store.detail, let d = page.detail, d.isShow,
              let theme = d.theme, let base = page.serverBase, let token = store.plex.tokenFor(base),
              let url = URL(string: PlexAPI.logoUrl(base, token, path: theme)) else { ThemePlayer.shared.stop(); return }
        ThemePlayer.shared.play(url, volume: Float(store.prefs.themeVolume))
    }
}

/// A symbol with its name under it, for a title's lesser actions.
struct IconAction: View {
    let title: String
    let systemImage: String
    var on = false
    let action: () -> Void
    @Environment(\.accent) private var accent

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: systemImage).font(.system(size: 21, weight: .medium))
                    .foregroundStyle(on ? accent.swiftColor : Color.chalk).frame(height: 26)
                Text(title).font(Typeface.geist(11, .medium)).foregroundStyle(Color.muted).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

/// A press that dims and gives a little, as iOS's own buttons do, with a tap felt.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.6 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .sensoryFeedback(.selection, trigger: configuration.isPressed) { _, pressed in pressed }
    }
}
