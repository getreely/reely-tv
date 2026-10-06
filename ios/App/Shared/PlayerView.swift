import SwiftUI
import ReelyCore

/**
 * The player, as on the Fire TV: the picture, and over it when asked for, the title, the
 * bar with where it's got to and how long is left, and Subtitles, Audio, Chapters and
 * Playback info. Skip Intro and Skip Credits come up when Plex has marked them; near the
 * end, Up Next counts down to the next episode.
 */
struct PlayerView: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    @State private var model = PlayerModel()
    @State private var controls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var panel: Panel?
    @State private var upNextLeft: Int?
    @State private var upNextDismissed = false
    @FocusState private var focus: Control?

    /// The sleep timer: a time to stop at, or the end of this episode.
    @State private var sleepAt: Date?
    @State private var sleepAtEnd = false
    /// Subtitles found online by the Plex server, as they're looked for and added.
    @State private var found: [PlexOnlineSubtitle]?
    @State private var findNote: String?
    @State private var adding: String?

    enum Panel: Identifiable { case subtitles, audio, chapters, sleep, find, info; var id: Self { self } }
    enum Control: Hashable { case play, subtitles, audio, chapters, sleep, info, skip, upNext }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(player: model.player).ignoresSafeArea()
            if let line = model.subtitle { subtitleView(line) }
            if model.buffering && !model.ended { ProgressView().tint(.white).scaleEffect(Typeface.scale) }
            if let error = model.failed ?? store.playError {
                VStack(spacing: dp(14)) {
                    Text(error).font(Typeface.body).foregroundStyle(Color.chalk)
                    PrimaryButton(title: "Back") { close() }.frame(maxWidth: dp(240))
                }
            }
            if let marker = activeSkip { skipButton(marker) }
            if let next = store.nextInQueue, showUpNext { upNext(next) }
            if controls && panel == nil { overlay }
        }
        .onAppear {
            model.attach(store)
            if let p = store.playing { model.load(p) }
            showControls()
        }
        .onChange(of: store.playing) { _, p in if let p { model.load(p) } }
        .onChange(of: model.positionMs) { _, _ in autoSkip(); countUpNext() }
        .onChange(of: model.ended) { _, ended in
            // The sleep timer set for this episode's end: it ends here, rather than going on.
            if ended { if store.nextInQueue != nil && !sleepAtEnd { goNext() } else { close() } }
        }
        .task(id: sleepAt) {
            guard let at = sleepAt else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(0, at.timeIntervalSinceNow) * 1_000_000_000))
            if !Task.isCancelled, sleepAt == at { close() }
        }
        .sheet(item: $panel) { panel in
            #if os(iOS)
            panelView(panel).presentationDetents([.medium, .large])
            #else
            panelView(panel)
            #endif
        }
        #if os(tvOS)
        .onPlayPauseCommand { model.togglePlay(); showControls() }
        .onExitCommand {
            if panel != nil { panel = nil } else if controls { controls = false } else { close() }
        }
        .onMoveCommand { direction in
            showControls()
            if focus == nil || focus == .play {
                if direction == .left { model.skip(-10) } else if direction == .right { model.skip(10) }
            }
        }
        #else
        .onTapGesture { controls ? (controls = false) : showControls() }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        #endif
    }

    // MARK: Controls

    private var overlay: some View {
        VStack(alignment: .leading) {
            HStack {
                #if os(iOS)
                Button { close() } label: { Image(systemName: "xmark").font(.system(size: 20, weight: .semibold)) }
                    .foregroundStyle(Color.chalk).padding(.trailing, 8)
                #endif
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(title).font(Typeface.headline).foregroundStyle(Color.chalk).lineLimit(1)
                    if let sub = subtitleLine { Text(sub).font(Typeface.meta).foregroundStyle(Color.muted).lineLimit(1) }
                }
                Spacer()
            }
            Spacer()
            #if os(iOS)
            HStack(spacing: 48) {
                Button { model.skip(-10) } label: { Image(systemName: "gobackward.10") }
                Button { model.togglePlay(); showControls() } label: { Image(systemName: model.playing ? "pause.fill" : "play.fill") }
                Button { model.skip(10) } label: { Image(systemName: "goforward.10") }
            }
            .font(.system(size: 34, weight: .semibold)).foregroundStyle(Color.chalk).frame(maxWidth: .infinity)
            Spacer()
            #endif
            scrubber
            HStack(spacing: dp(10)) {
                #if os(tvOS)
                PanelButton(title: model.playing ? "Pause" : "Play", systemImage: model.playing ? "pause.fill" : "play.fill") { model.togglePlay() }
                    .focused($focus, equals: .play)
                #endif
                // With none in the file, Subtitles still finds some online.
                if !(store.playing?.playback.subtitleStreams.isEmpty ?? true) || store.playing?.item.isIptv == false {
                    PanelButton(title: "Subtitles", systemImage: "captions.bubble") { panel = .subtitles }.focused($focus, equals: .subtitles)
                }
                if (store.playing?.playback.audioStreams.count ?? 0) > 1 {
                    PanelButton(title: "Audio", systemImage: "speaker.wave.2") { panel = .audio }.focused($focus, equals: .audio)
                }
                if !(store.playing?.playback.chapters.isEmpty ?? true) {
                    PanelButton(title: "Chapters", systemImage: "list.bullet") { panel = .chapters }.focused($focus, equals: .chapters)
                }
                PanelButton(title: sleepLabel ?? "Sleep timer", systemImage: "moon.zzz", filled: sleepAt != nil || sleepAtEnd) { panel = .sleep }
                    .focused($focus, equals: .sleep)
                PanelButton(title: "Playback info", systemImage: "info.circle") { panel = .info }.focused($focus, equals: .info)
            }
        }
        .padding(.horizontal, pageMargin).padding(.vertical, dp(24))
        .background(LinearGradient(colors: [Color.black.opacity(0.7), .clear, .clear, Color.black.opacity(0.8)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        .transition(.opacity)
    }

    private var scrubber: some View {
        VStack(spacing: dp(6)) {
            GeometryReader { g in
                let fraction = model.durationMs > 0 ? Double(model.positionMs) / Double(model.durationMs) : 0
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.25))
                    Capsule().fill(accent.swiftColor).frame(width: g.size.width * min(1, max(0, fraction)))
                }
                #if os(iOS)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                    showControls()
                    model.seek(toMs: Int(Double(model.durationMs) * min(1, max(0, v.location.x / g.size.width))))
                })
                #endif
            }
            .frame(height: dp(6))
            HStack {
                Text(clock(model.positionMs))
                Spacer()
                Text("-" + clock(max(0, model.durationMs - model.positionMs)))
            }
            .font(Typeface.label).foregroundStyle(Color.muted).monospacedDigit()
        }
    }

    private var title: String { store.playing?.item.rowTitle ?? "" }

    private var subtitleLine: String? {
        guard let item = store.playing?.item else { return nil }
        return item.type == "episode" ? item.episodeLine : item.year.map(String.init)
    }

    private func showControls() {
        withAnimation(.easeOut(duration: 0.2)) { controls = true }
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if !Task.isCancelled, model.playing, panel == nil { withAnimation(.easeIn(duration: 0.3)) { controls = false } }
        }
    }

    private func close() {
        let (pos, dur) = (model.positionMs, model.durationMs)
        model.stop()
        Task { await store.stop(positionMs: pos, durationMs: dur) }
    }

    // MARK: Subtitles drawn by the app

    private func subtitleView(_ line: String) -> some View {
        let scale = store.prefs.subtitleScale
        return VStack {
            Spacer()
            Text(line)
                .font(Typeface.geist(CGFloat(26 * scale), .medium)).multilineTextAlignment(.center).foregroundStyle(.white)
                .shadow(color: .black, radius: store.prefs.subtitleBackground ? 0 : 3)
                .padding(.horizontal, dp(10)).padding(.vertical, dp(4))
                .background(store.prefs.subtitleBackground ? Color.black.opacity(0.75) : .clear, in: RoundedRectangle(cornerRadius: dp(6)))
                .padding(.bottom, controls ? dp(120) : dp(40))
        }
        .padding(.horizontal, pageMargin)
        .allowsHitTesting(false)
    }

    // MARK: Skip Intro, Skip Credits, Up Next

    private var activeSkip: PlexMarker? {
        guard let markers = store.playing?.playback.markers else { return nil }
        return markers.first { $0.startMs <= model.positionMs && model.positionMs < $0.endMs - 1000 && ($0.type == "intro" || $0.type == "credits") }
    }

    private func skipButton(_ marker: PlexMarker) -> some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                PanelButton(title: marker.type == "intro" ? "Skip Intro" : "Skip Credits", systemImage: "forward.end.fill", filled: true) {
                    if marker.type == "credits", store.nextInQueue != nil { goNext() } else { model.seek(toMs: marker.endMs) }
                }
                .focused($focus, equals: .skip)
            }
        }
        .padding(.horizontal, pageMargin).padding(.bottom, controls ? dp(150) : dp(60))
    }

    /// Past the intro without asking, and on from the credits, when Settings say so.
    private func autoSkip() {
        guard let marker = activeSkip else { return }
        if marker.type == "intro" && store.prefs.skipIntros && model.positionMs < marker.startMs + 1500 { model.seek(toMs: marker.endMs) }
        if marker.type == "credits" && store.prefs.skipCredits && store.nextInQueue != nil && !sleepAtEnd { goNext() }
    }

    /// Up Next comes up at the credits, or the last half minute when there are none marked.
    private var showUpNext: Bool {
        guard !upNextDismissed, !sleepAtEnd, model.durationMs > 0 else { return false }
        let credits = store.playing?.playback.markers.first { $0.type == "credits" }?.startMs
        let from = credits ?? (model.durationMs - 30_000)
        return model.positionMs >= from && activeSkip?.type != "intro"
    }

    private func countUpNext() {
        guard showUpNext else { upNextLeft = nil; return }
        let seconds = store.prefs.upNextSeconds
        guard seconds > 0 else { return }
        let credits = store.playing?.playback.markers.first { $0.type == "credits" }?.startMs ?? (model.durationMs - 30_000)
        let left = seconds - (model.positionMs - credits) / 1000
        upNextLeft = max(0, left)
        if left <= 0 { goNext() }
    }

    private func upNext(_ next: PlexItem) -> some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                VStack(alignment: .leading, spacing: dp(8)) {
                    Text("Up Next").font(Typeface.label).foregroundStyle(Color.muted)
                    Text(next.episodeLine ?? next.title).font(Typeface.rowTitle).foregroundStyle(Color.chalk).lineLimit(2)
                    HStack(spacing: dp(8)) {
                        PanelButton(title: upNextLeft.map { "Play now · \($0)" } ?? "Play now", systemImage: "play.fill", filled: true) { goNext() }
                            .focused($focus, equals: .upNext)
                        PanelButton(title: "Not now", systemImage: "xmark") { upNextDismissed = true }
                    }
                }
                .padding(dp(16)).frame(maxWidth: dp(420), alignment: .leading)
                .background(RoundedRectangle(cornerRadius: dp(14)).fill(Color.surfaceRaised.opacity(0.95)))
            }
        }
        .padding(.horizontal, pageMargin).padding(.bottom, dp(40))
    }

    private func goNext() {
        upNextDismissed = false
        upNextLeft = nil
        let (pos, dur) = (model.positionMs, model.durationMs)
        Task { await store.playNext(positionMs: pos, durationMs: dur) }
    }

    // MARK: Panels

    @ViewBuilder
    private func panelView(_ panel: Panel) -> some View {
        let p = store.playing
        NavigationStack {
            List {
                switch panel {
                case .subtitles:
                    Button { choose(subtitle: "0") } label: { row("Off", on: !(p?.playback.subtitleStreams.contains(where: \.selected) ?? false)) }
                    ForEach(p?.playback.subtitleStreams ?? [], id: \.id) { s in
                        Button { choose(subtitle: s.id) } label: { row(s.label, on: s.selected) }
                    }
                    if p?.item.isIptv == false {
                        Button { find() } label: { Label("Find subtitles online", systemImage: "magnifyingglass").font(Typeface.body) }
                    }
                case .find:
                    Text(findNote ?? (found == nil ? "Looking for subtitles…" : adding != nil ? "Adding them…"
                                      : found!.isEmpty ? "No \(languageName) subtitles were found for this." : "\(languageName), found by your Plex server."))
                        .font(Typeface.meta).foregroundStyle(Color.muted)
                    ForEach(found ?? [], id: \.key) { s in
                        Button { add(s) } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(s.title).font(Typeface.body)
                                Text([s.provider, s.hearingImpaired ? "For the hard of hearing" : nil, s.forced ? "Forced" : nil].compactMap { $0 }.joined(separator: "  ·  "))
                                    .font(Typeface.label).foregroundStyle(Color.muted)
                            }
                        }
                        .disabled(adding != nil)
                    }
                case .sleep:
                    if let at = sleepAt { Text("Stops in \(max(1, Int(ceil(at.timeIntervalSinceNow / 60)))) min.").font(Typeface.meta).foregroundStyle(Color.muted) }
                    ForEach(SLEEP_CHOICES.filter { $0 != -1 || p?.item.type == "episode" }, id: \.self) { m in
                        Button { setSleep(m) } label: {
                            row(m == 0 ? "Off" : m == -1 ? "End of this episode" : "\(m) minutes",
                                on: m == 0 ? sleepAt == nil && !sleepAtEnd : m == -1 ? sleepAtEnd : false)
                        }
                    }
                case .audio:
                    ForEach(p?.playback.audioStreams ?? [], id: \.id) { s in
                        Button { choose(audio: s.id) } label: { row(s.label, on: s.selected) }
                    }
                case .chapters:
                    ForEach(Array((p?.playback.chapters ?? []).enumerated()), id: \.offset) { _, c in
                        Button { model.seek(toMs: c.startMs); self.panel = nil } label: {
                            row("\(c.title)  ·  \(clock(c.startMs))", on: model.positionMs >= c.startMs && model.positionMs < c.endMs)
                        }
                    }
                case .info:
                    if let p {
                        let audio = p.playback.audioStreams.first(where: \.selected) ?? p.playback.audioStreams.first
                        infoRow("Playing", p.item.isIptv ? "From your IPTV provider" : p.direct ? "The original file" : "Converted by Plex")
                        if let reason = p.reason, !p.item.isIptv { infoRow("Why", reason) }
                        if let v = p.playback.videoCodec { infoRow("Video", v.uppercased()) }
                        if let a = audio?.label ?? p.playback.audioCodec.map({ $0.uppercased() + (p.playback.audioChannels > 0 ? " · \(p.playback.audioChannels) channels" : "") }) {
                            infoRow("Audio", a)
                        }
                        if let c = p.playback.container { infoRow("File", c.uppercased()) }
                        infoRow("Subtitles", p.textSubtitle != nil ? "Drawn by Reely" : p.playback.subtitleStreams.contains(where: \.selected) ? "Burned in by Plex" : "Off")
                    }
                }
            }
            .navigationTitle(panelTitle(panel))
        }
    }

    private func panelTitle(_ panel: Panel) -> String {
        switch panel {
        case .subtitles: return "Subtitles"
        case .audio: return "Audio"
        case .chapters: return "Chapters"
        case .sleep: return "Sleep timer"
        case .find: return "Find subtitles"
        case .info: return "Playback info"
        }
    }

    private func row(_ label: String, on: Bool) -> some View {
        HStack {
            Text(label).font(Typeface.body)
            Spacer()
            if on { Image(systemName: "checkmark").foregroundStyle(accent.swiftColor) }
        }
    }

    private func infoRow(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(Color.muted); Spacer(); Text(value) }.font(Typeface.meta)
    }

    private var sleepLabel: String? {
        if sleepAtEnd { return "Sleep at the end of this" }
        guard let at = sleepAt else { return nil }
        return "Sleep in \(max(1, Int(ceil(at.timeIntervalSinceNow / 60)))) min"
    }

    private func setSleep(_ minutes: Int) {
        panel = nil
        sleepAtEnd = minutes == -1
        sleepAt = minutes > 0 ? Date().addingTimeInterval(TimeInterval(minutes * 60)) : nil
    }

    private var languageCode: String { Locale.current.language.languageCode?.identifier ?? "en" }
    private var languageName: String { Locale.current.localizedString(forLanguageCode: languageCode) ?? languageCode.uppercased() }

    private func find() {
        found = nil
        findNote = nil
        adding = nil
        panel = .find
        let language = languageCode
        Task {
            let result = await store.findSubtitles(language: language)
            found = result.results
            findNote = result.error
        }
    }

    private func add(_ s: PlexOnlineSubtitle) {
        adding = s.key
        let pos = model.positionMs
        Task {
            let problem = await store.addFoundSubtitle(s, language: languageCode, positionMs: pos)
            adding = nil
            if let problem { findNote = problem } else { panel = nil }
        }
    }

    private func choose(audio: String? = nil, subtitle: String? = nil) {
        let pos = model.positionMs
        panel = nil
        Task { await store.chooseStreams(audioId: audio, subtitleId: subtitle, positionMs: pos) }
    }
}

/// Minutes the sleep timer offers, as the Fire TV's does; -1 is the end of this episode.
let SLEEP_CHOICES = [0, 15, 30, 45, 60, 90, -1]

/// A control in the player: a symbol and a word, filled for the one that matters most.
struct PanelButton: View {
    let title: String
    let systemImage: String
    var filled = false
    let action: () -> Void
    @Environment(\.accent) private var accent

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage).font(Typeface.meta).lineLimit(1).fixedSize()
                .foregroundStyle(filled ? accent.onColor : Color.chalk)
                .padding(.horizontal, dp(14)).padding(.vertical, dp(9))
                .background(Capsule().fill(filled ? accent.swiftColor : Color.white.opacity(0.14)))
                .modifier(FocusRing(shape: Capsule()))
        }
        .buttonStyle(CardStyle())
    }
}
