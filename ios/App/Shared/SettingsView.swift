import SwiftUI
import ReelyCore
#if os(iOS)
import AVKit
#endif

/**
 * Settings, as on the Fire TV: Playback, Home, Theme, Live TV, Requests, Plex and About,
 * with the same rows, words and choices. On Apple TV the sections run down the left; on
 * a phone each opens on its own page. The Fire TV's own updater has no place here.
 */
struct SettingsView: View {
    @Environment(ReelyStore.self) private var store
    @State private var section: Section = .playback

    enum Section: String, CaseIterable, Identifiable {
        case playback = "Playback", home = "Home", theme = "Theme", live = "Live TV", requests = "Requests", plex = "Plex", about = "About"
        var id: String { rawValue }
    }

    var body: some View {
        #if os(tvOS)
        HStack(alignment: .top, spacing: 60) {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Section.allCases) { s in
                    Button { section = s } label: { SectionLabel(title: s.rawValue, on: section == s) }
                        .buttonStyle(PlainFocusStyle())
                }
            }
            .frame(width: 360).focusSection()
            ScrollView { SettingsSection(section: section).padding(.bottom, 60) }.focusSection()
        }
        .padding(.horizontal, pageMargin).padding(.top, topInset + 10)
        #else
        NavigationStack {
            List(Section.allCases) { s in
                NavigationLink(s.rawValue) { ScrollView { SettingsSection(section: s).padding(.vertical, 12) }.background(Color.ink).navigationTitle(s.rawValue) }
                    .font(Typeface.body)
                    .listRowBackground(Color.surfaceRaised)
            }
            // Reely's own black and greys, not the system's.
            .scrollContentBackground(.hidden)
            .background(Color.ink)
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { store.back() } } }
        }
        #endif
    }
}

#if os(tvOS)
private struct SectionLabel: View {
    let title: String
    let on: Bool
    @Environment(\.isFocused) private var focused
    var body: some View {
        Text(title).font(Typeface.meta)
            .foregroundStyle(focused ? Color.ink : on ? Color.chalk : Color.muted)
            .padding(.horizontal, 24).padding(.vertical, 14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(focused ? Color.chalk : on ? Color.surfaceHigh : .clear))
    }
}
#endif

struct PlainFocusStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

/// One of the values a setting can take, with a line on what it does if it needs one.
struct Option<T: Hashable>: Hashable {
    let value: T
    let label: String
    var note: String? = nil
}

private let MODES: [Option<Prefs.PlaybackMode>] = [
    Option(value: .auto, label: "Automatic", note: "Plays the original file. Plex converts it only when needed."),
    Option(value: .direct, label: "Original only", note: "Always plays the original file. Some may not play."),
    Option(value: .transcode, label: "Always convert", note: "Plex converts everything. Uses more of your server."),
]
private let BITRATES = [0, 20_000, 12_000, 8_000, 4_000, 2_000]
private func bitrateLabel(_ kbps: Int) -> String { kbps <= 0 ? "Original" : "Up to \(kbps / 1000) Mbps" }
private func bitrateNote(_ kbps: Int) -> String {
    switch kbps {
    case 0: return "As good as the file itself."
    case 20_000: return "4K"
    case 12_000: return "1080p, very high"
    case 8_000: return "1080p"
    case 4_000: return "720p"
    default: return "For a slow connection"
    }
}
private let UP_NEXT_CHOICES = [0, 5, 10, 15, 20, 30]
private let THEME_LEVELS: [(String, Double)] = [("Quiet", 0.05), ("Medium", 0.10), ("Loud", 0.20)]
private let SCREENSAVER_CHOICES = [0, 3, 5, 10]

struct SettingsSection: View {
    @Environment(ReelyStore.self) private var store
    let section: SettingsView.Section

    var body: some View {
        VStack(alignment: .leading, spacing: dp(22)) {
            switch section {
            case .playback: playback
            case .home: home
            case .theme: theme
            case .live: LiveSettings()
            case .requests:
                if let address = store.requests.address {
                    Group_(title: "Reely", note: "Requests go to Reely, signed in with your Plex account.") {
                        Row_(title: "Server", note: nil, value: Reely.hostOf(address), action: nil)
                        Row_(title: "Disconnect", note: nil, value: nil) { store.disconnectReely() }
                    }
                } else {
                    Group_(title: "Requests") { Row_(title: "Not connected", note: "Connect to Reely from the Request tab.", value: "Go to Requests") { store.navigate(.requests) } }
                }
            case .plex: plexSection
            case .about: about
            }
        }
        .padding(.horizontal, sidePadding)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var sidePadding: CGFloat {
        #if os(tvOS)
        return 0
        #else
        return 16
        #endif
    }

    private var prefs: Binding<Prefs> { Binding(get: { store.prefs }, set: { store.prefs = $0 }) }

    @ViewBuilder private var playback: some View {
        Group_(title: "Video") {
            Choice_(title: "Playback mode", options: MODES, selected: prefs.playbackMode)
            Choice_(title: "Conversion quality", note: "The highest quality Plex uses when it converts a video.",
                    options: BITRATES.map { Option(value: $0, label: bitrateLabel($0), note: bitrateNote($0)) }, selected: prefs.maxBitrateKbps)
            #if os(tvOS)
            Switch_(title: "Match frame rate", note: "Smoother motion. The screen may blink as it switches.", on: prefs.matchFrameRate)
            #endif
            Choice_(title: "Buffer", note: "Larger helps on a slow or unsteady connection.", options: [
                Option(value: false, label: "Normal", note: "Keeps about 50 seconds ahead. Starts quickly."),
                Option(value: true, label: "Larger", note: "Keeps up to two minutes ahead. Takes a moment longer to start."),
            ], selected: prefs.largerBuffer)
        }
        #if os(iOS)
        Group_(title: "Sound") {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Play sound on").font(Typeface.meta).foregroundStyle(Color.chalk)
                    Text("AirPlay speakers, headphones and TVs, as the rest of your iPhone or iPad uses them.").font(Typeface.label).foregroundStyle(Color.muted)
                }
                Spacer()
                RoutePicker().frame(width: 44, height: 44)
            }
            .padding(14)
        }
        #endif
        Group_(title: "Subtitles") {
            Choice_(title: "At the start", note: "Whether movies and episodes start with subtitles on.", options: [
                Option(value: Prefs.SubtitlesAtStart.plex, label: "As Plex has them", note: "Your Plex account's subtitle settings, or what was last picked for the title."),
                Option(value: Prefs.SubtitlesAtStart.off, label: "Off", note: "Off until you turn them on. Forced subtitles, for parts in another language, still show."),
            ], selected: prefs.subtitlesAtStart)
            Choice_(title: "Size", options: SUBTITLE_SIZES.map { Option(value: $0, label: "\(Int(($0 * 100).rounded()))%", note: $0 == 0.9 ? "Standard" : nil) },
                    selected: prefs.subtitleScale)
            Switch_(title: "Background", note: "A dark box behind the text, instead of an outline.", on: prefs.subtitleBackground)
        }
        Group_(title: "Episodes") {
            Switch_(title: "Skip intros", note: "Goes straight past an episode's intro when Plex has found it.", on: prefs.skipIntros)
            Switch_(title: "Skip credits", note: "Goes straight to the next episode when Plex finds the credits.", on: prefs.skipCredits)
            Choice_(title: "Up Next", note: "How long before the next episode starts by itself.",
                    options: UP_NEXT_CHOICES.map { Option(value: $0, label: $0 > 0 ? "\($0) seconds" : "Off", note: $0 == 0 ? "Up Next waits for you to choose." : nil) },
                    selected: prefs.upNextSeconds)
        }
        Group_(title: "Show pages") {
            Choice_(title: "Theme music", note: "Plays a show's theme song on its page.",
                    options: [Option(value: -1, label: "Off")] + THEME_LEVELS.enumerated().map { Option(value: $0.offset, label: $0.element.0) },
                    selected: Binding(
                        get: { store.prefs.themeMusic ? THEME_LEVELS.indices.min { abs(THEME_LEVELS[$0].1 - store.prefs.themeVolume) < abs(THEME_LEVELS[$1].1 - store.prefs.themeVolume) } ?? 1 : -1 },
                        set: { level in
                            store.prefs.themeMusic = level >= 0
                            if level >= 0 { store.prefs.themeVolume = THEME_LEVELS[level].1 }
                        }))
        }
    }

    @ViewBuilder private var home: some View {
        Group_(title: "Rows on Home") {
            ForEach(HomeRow.allCases.filter { !$0.fromReely && (store.prefs.iptvLibrary || ($0 != .iptvMovies && $0 != .iptvShows)) }, id: \.self) { row in
                Switch_(title: row.title, on: Binding(get: { !store.isHidden(row) }, set: { store.setHidden(row, !$0) }))
            }
        }
        Group_(title: "From Reely", note: store.requests.isConnected ? "What's trending and popular that you can ask for." : "Connect to Reely from the Request tab to show these.") {
            ForEach(HomeRow.allCases.filter(\.fromReely), id: \.self) { row in
                Switch_(title: row.title, on: Binding(get: { !store.isHidden(row) }, set: { store.setHidden(row, !$0) }))
            }
        }
        #if os(tvOS)
        Group_(title: "Screensaver") {
            Choice_(title: "Screensaver", note: "Your library's artwork and the time, when the remote's been put down.",
                    options: SCREENSAVER_CHOICES.map { Option(value: $0, label: $0 == 0 ? "Off" : "After \($0) minutes", note: $0 == 0 ? "Apple TV's own screensaver comes on instead." : nil) },
                    selected: prefs.screensaverMinutes)
        }
        #endif
    }

    private var theme: some View {
        Group_(title: "Theme") {
            Choice_(title: "Theme", note: "The color of what's highlighted, progress bars, and the Reely mark.",
                    options: Accent.all.map { Option(value: $0.id, label: $0.name, note: $0.id == "blue" ? "Reely's own" : nil) },
                    selected: prefs.accent)
        }
    }

    @ViewBuilder private var plexSection: some View {
        let plex = store.plex
        if let user = plex.user {
            Group_(title: "Profile", note: plex.homeUsers.count > 1 ? "Each profile in your Plex Home has its own libraries, watch history and Continue Watching." : nil) {
                Row_(title: user.title, note: plex.homeUsers.count > 1 ? "Plex Home member" : "Signed in to Plex",
                     value: plex.homeUsers.count > 1 ? "Switch" : nil,
                     action: plex.homeUsers.count > 1 ? { NotificationCenter.default.post(name: .chooseProfile, object: nil) } : nil)
            }
        }
        Group_(title: "Server") {
            Row_(title: "Server", note: nil, value: plex.serverName ?? "—", action: nil)
            Row_(title: "Connection", note: nil, value: ReelyStore.connectionKind(plex.baseUrl), action: nil)
        }
        if plex.libraries.count > 1 {
            Group_(title: "Libraries", note: "Switched on, a library is one of the only ones in the Movies and TV Shows menus and on Home. With none switched on, all of them are.") {
                ForEach(plex.libraries) { l in
                    Switch_(title: plex.servers.count > 1 ? "\(l.section.title) · \(l.serverName)" : l.section.title,
                            on: Binding(get: { store.prefs.favouriteSections.contains(l.id) }, set: { _ in store.togglePinned(l) }))
                }
            }
        }
        if plex.servers.count > 1 {
            Group_(title: "Servers", note: "Which of your servers Reely uses first. Libraries from all of them are on Home.") {
                ForEach(plex.servers, id: \.name) { s in
                    Row_(title: s.name, note: nil, value: plex.serverName == s.name ? "In use" : "Switch") {
                        if plex.serverName != s.name { Task { await store.chooseServer(s.name) } }
                    }
                }
            }
        }
        Group_(title: "Account") { Row_(title: "Sign out of Plex", note: nil, value: nil) { store.signOut() } }
    }

    @ViewBuilder private var about: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            Text("reely").font(Typeface.geist(40, .bold)).foregroundStyle(Accent.of(store.prefs.accent).swiftColor)
            Text("Your Plex library and live TV, in one place.").font(Typeface.meta).foregroundStyle(Color.chalk)
            Text("Version \(ReelyApp.version)").font(Typeface.label).foregroundStyle(Color.muted)
        }
        #if os(tvOS)
        Group_(title: "Help") {
            Row_(title: "Take the tour again", note: "How to get around with the remote.", value: nil) {
                NotificationCenter.default.post(name: .takeTour, object: nil)
            }
        }
        #endif
        Group_(title: "Licenses") {
            Row_(title: "Geist", note: "The typeface.", value: "SIL Open Font License", action: nil)
            Row_(title: "VLCKit", note: "VideoLAN's player, for files and streams Apple's can't open.", value: "LGPL 2.1", action: nil)
        }
    }
}

/// Live TV's settings, as on the Fire TV: the channels and guide, watching, and the provider.
private struct LiveSettings: View {
    @Environment(ReelyStore.self) private var store
    @State private var guideNamed: Bool?

    var body: some View {
        let live = store.live
        if let c = live.credentials {
            Group_(title: "Channels and guide") {
                Row_(title: "Refresh channels", note: nil, value: live.busy ? "Refreshing…" : "\(live.categories.count) categories") {
                    Task { await store.refreshChannels() }
                }
                if guideNamed == false {
                    Row_(title: "TV guide", note: "Your playlist doesn't name one. To add one, sign out and sign in again with a TV guide address.", value: "None", action: nil)
                } else {
                    Row_(title: "Refresh TV guide", note: failure(live.guideStatus) ?? "What's on, asked for again.",
                         value: c.isPlaylist ? guideValue(live.guideStatus) : nil) {
                        Task { await store.refreshGuide() }
                    }
                }
            }
            Group_(title: "Watching") {
                if !c.isPlaylist {
                    Choice_(title: "Stream type", note: "Try the other if channels stutter or won't start.", options: [
                        Option(value: Prefs.StreamFormat.m3u8, label: "HLS", note: "Works with most providers."),
                        Option(value: Prefs.StreamFormat.ts, label: "MPEG-TS", note: "Starts faster with some providers."),
                    ], selected: Binding(get: { store.prefs.streamFormat }, set: { store.prefs.streamFormat = $0 }))
                }
                #if os(tvOS)
                Switch_(title: "Guide preview", note: "Plays the highlighted channel in the guide.",
                        on: Binding(get: { store.prefs.guidePreview }, set: { store.prefs.guidePreview = $0 }))
                #endif
            }
            Group_(title: "Movies and shows", note: c.isPlaylist
                   ? "Your provider's movies and shows need an Xtream login rather than a playlist. Sign out and sign in with your server, username and password to use them."
                   : store.iptvStatus.error ?? "Your provider's movies and shows in the Movies and TV Shows tabs, on Home and in search, marked IPTV. Off, only Plex's are shown.") {
                if c.isPlaylist {
                    Row_(title: "Show IPTV movies and shows", note: nil, value: "Needs an Xtream login", action: nil)
                } else {
                    Switch_(title: "Show IPTV movies and shows", on: Binding(get: { store.prefs.iptvLibrary }, set: { store.setIptvLibrary($0) }))
                    if store.prefs.iptvLibrary {
                        Choice_(title: "When a title is in both", note: "Which copy shows on Home and in search, and which the IPTV library leaves out.", options: [
                            Option(value: false, label: "Plex", note: "Plex's copy. The IPTV library only has what Plex doesn't."),
                            Option(value: true, label: "IPTV", note: "The provider's copy, in place of Plex's on Home and in search."),
                        ], selected: Binding(get: { store.prefs.iptvWins }, set: { store.setIptvWins($0) }))
                        Switch_(title: "IPTV in the Movies and TV Shows menus", note: "As a library of its own, beside Plex's.",
                                on: Binding(get: { store.prefs.iptvInMenus }, set: { store.prefs.iptvInMenus = $0 }))
                        Row_(title: "Refresh movies and shows", note: nil,
                             value: store.iptvStatus.loading ? "Refreshing…" : store.iptvStatus.ready ? "\(store.iptv.catalog.movies.count) movies · \(store.iptv.catalog.series.count) shows" : "Not loaded") {
                            Task { await store.refreshIptv() }
                        }
                    }
                }
            }
            Group_(title: "Provider") {
                Row_(title: c.isPlaylist ? "Playlist" : "Server", note: nil, value: URL(string: c.playlistUrl ?? c.base)?.host ?? "—", action: nil)
                if !c.isPlaylist {
                    Row_(title: "Account", note: nil, value: account(live.account), action: nil)
                    Row_(title: "Connections", note: "Each channel on screen uses one, including the guide preview.",
                         value: "\(live.account?.activeConnections ?? "?") of \(live.account?.maxConnections ?? "?") in use", action: nil)
                }
                Row_(title: "Sign out of live TV", note: nil, value: nil) { store.signOutLive() }
            }
            .task(id: c) { guideNamed = await store.guideNamed() }
        } else {
            Group_(title: "Live TV") {
                Row_(title: "Not signed in", note: "Sign in to your provider from the Live TV tab.", value: "Go to Live TV") { store.navigate(.live) }
            }
        }
    }

    /// How the playlist's guide stands, as the Fire TV says it.
    private func guideValue(_ status: GuideStatus) -> String {
        switch status {
        case .updating: return "Updating…"
        case .ready(let at): return "Updated \(liveTime(at))"
        case .failed: return "Couldn't update"
        case .none: return "None"
        case .idle: return "Not loaded"
        }
    }

    private func failure(_ status: GuideStatus) -> String? {
        if case .failed(let message) = status { return message }
        return nil
    }

    private func account(_ a: XtreamAccount?) -> String {
        guard let a else { return "—" }
        let until = a.expiresAt.flatMap(Double.init).map { "until " + Date(timeIntervalSince1970: $0).formatted(date: .abbreviated, time: .omitted) }
        let status: String = a.status.prefix(1).uppercased() + String(a.status.dropFirst())
        return [status, until].compactMap { $0 }.joined(separator: " · ")
    }
}

// MARK: Rows

/// A group of rows under a heading, with a line under them when it needs one.
struct Group_<Content: View>: View {
    let title: String
    var note: String? = nil
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            Text(title.uppercased()).font(Typeface.label).kerning(1).foregroundStyle(Color.faint)
            VStack(spacing: 0) { content() }
                .background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.surfaceRaised))
            if let note { Text(note).font(Typeface.label).foregroundStyle(Color.muted) }
        }
    }
}

/// One setting: its name, a line of explanation, and its value on the right.
struct Row_: View {
    let title: String
    let note: String?
    let value: String?
    let action: (() -> Void)?
    var body: some View {
        let label = HStack(spacing: dp(12)) {
            VStack(alignment: .leading, spacing: dp(3)) {
                Text(title).font(Typeface.meta).foregroundStyle(Color.chalk)
                if let note { Text(note).font(Typeface.label).foregroundStyle(Color.muted).multilineTextAlignment(.leading) }
            }
            Spacer(minLength: 0)
            if let value { Text(value).font(Typeface.meta).foregroundStyle(Color.muted) }
        }
        .padding(dp(14)).contentShape(Rectangle())
        if let action { Button(action: action) { label }.buttonStyle(RowStyle()) } else { label }
    }
}

/// A setting that's on or off.
struct Switch_: View {
    let title: String
    var note: String? = nil
    @Binding var on: Bool
    @Environment(\.accent) private var accent
    var body: some View {
        Button { on.toggle() } label: {
            HStack(spacing: dp(12)) {
                VStack(alignment: .leading, spacing: dp(3)) {
                    Text(title).font(Typeface.meta).foregroundStyle(Color.chalk)
                    if let note { Text(note).font(Typeface.label).foregroundStyle(Color.muted).multilineTextAlignment(.leading) }
                }
                Spacer(minLength: 0)
                Capsule().fill(on ? accent.swiftColor : Color.line).frame(width: dp(44), height: dp(26))
                    .overlay(alignment: on ? .trailing : .leading) { Circle().fill(Color.chalk).padding(dp(3)) }
                    .animation(.easeOut(duration: 0.15), value: on)
            }
            .padding(dp(14)).contentShape(Rectangle())
        }
        .buttonStyle(RowStyle())
    }
}

/// A setting with a list of values: the one chosen on the right, the list on OK.
struct Choice_<T: Hashable>: View {
    let title: String
    var note: String? = nil
    let options: [Option<T>]
    @Binding var selected: T
    @State private var open = false
    @Environment(\.accent) private var accent

    var body: some View {
        Button { open = true } label: {
            HStack(spacing: dp(12)) {
                VStack(alignment: .leading, spacing: dp(3)) {
                    Text(title).font(Typeface.meta).foregroundStyle(Color.chalk)
                    if let note { Text(note).font(Typeface.label).foregroundStyle(Color.muted).multilineTextAlignment(.leading) }
                }
                Spacer(minLength: 0)
                Text(options.first { $0.value == selected }?.label ?? "").font(Typeface.meta).foregroundStyle(Color.muted)
                Image(systemName: "chevron.right").font(.system(size: dp(12), weight: .semibold)).foregroundStyle(Color.faint)
            }
            .padding(dp(14)).contentShape(Rectangle())
        }
        .buttonStyle(RowStyle())
        .sheet(isPresented: $open) {
            NavigationStack {
                List(options, id: \.self) { o in
                    Button { selected = o.value; open = false } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(o.label).font(Typeface.meta)
                                if let n = o.note { Text(n).font(Typeface.label).foregroundStyle(Color.muted) }
                            }
                            Spacer()
                            if o.value == selected { Image(systemName: "checkmark").foregroundStyle(accent.swiftColor) }
                        }
                    }
                }
                .navigationTitle(title)
            }
        }
    }
}

/// A row's look with the cursor on it: lighter, as the Fire TV's settings rows are.
struct RowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { RowBody(configuration: configuration) }
    private struct RowBody: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused
        var body: some View {
            configuration.label
                .background(RoundedRectangle(cornerRadius: dp(12)).fill(focused ? Color.surfaceHigh : configuration.isPressed ? Color.surfaceHigh.opacity(0.6) : .clear))
        }
    }
}

#if os(iOS)
/// Apple's AirPlay picker: where sound and picture go.
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
#endif
