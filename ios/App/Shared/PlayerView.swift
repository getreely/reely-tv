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
    #if os(tvOS)
    @FocusState private var surface: Bool
    #endif

    /// The sleep timer: a time to stop at, or the end of this episode.
    @State private var sleepAt: Date?
    @State private var sleepAtEnd = false
    /// Subtitles found online by the Plex server, as they're looked for and added.
    @State private var found: [PlexOnlineSubtitle]?
    @State private var findNote: String?
    @State private var adding: String?
    #if os(iOS)
    /// Where the finger has the bar while scrubbing: the picture goes there on letting go.
    @State private var scrubMs: Int?
    /// The last tap, to tell a double tap (skip) from a single one (the controls).
    @State private var lastTap: (at: Date, left: Bool)?
    #endif

    enum Panel: Identifiable { case subtitles, audio, chapters, sleep, find, info; var id: Self { self } }
    enum Control: Hashable { case play, subtitles, audio, chapters, sleep, info, skip, upNext }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.usingVLC, let engine = model.vlc {
                VLCSurface(engine: engine).ignoresSafeArea()
            } else {
                VideoSurface(player: model.player).ignoresSafeArea()
            }
            #if os(tvOS)
            // The remote's, while the controls are put away: a press brings them back, left and right still skip.
            Button { showControls() } label: { Color.black.opacity(0.001).frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(PlainFocusStyle())
                .focused($surface)
                .disabled(controls)
                .ignoresSafeArea()
            #endif
            if let line = model.subtitle { subtitleView(line) }
            if model.buffering && !model.ended { ProgressView().tint(.white).scaleEffect(Typeface.scale) }
            if let error = model.failed ?? store.playError {
                VStack(spacing: dp(14)) {
                    Text(error).font(Typeface.body).foregroundStyle(Color.chalk)
                    if model.stuckAt != nil {
                        PrimaryButton(title: "Try again") { model.tryAgain() }.frame(maxWidth: dp(240))
                    }
                    PrimaryButton(title: "Back") { close() }.frame(maxWidth: dp(240))
                }
            }
            if let marker = activeSkip { skipButton(marker) }
            if let next = store.nextInQueue, showUpNext { upNext(next) }
            #if os(iOS)
            if controls && panel == nil { phoneControls }
            #else
            if controls && panel == nil { overlay }
            #endif
        }
        #if os(iOS)
        .onAppear { AppDelegate.playing(true) }
        .onDisappear { AppDelegate.playing(false) }
        #endif
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
            if panel != nil { panel = nil } else if controls { controls = false; surface = true } else { close() }
        }
        .onMoveCommand { direction in
            showControls()
            if focus == nil || focus == .play || surface {
                if direction == .left { model.skip(-10) } else if direction == .right { model.skip(10) }
            }
        }
        #else
        // A tap shows or hides the controls at once; a second on the same side skips, as on the Android phone.
        .gesture(SpatialTapGesture(coordinateSpace: .global).onEnded { tap in tapped(tap.location) })
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        #endif
    }

    // MARK: Controls

    #if os(iOS)
    private func tapped(_ at: CGPoint) {
        let left = at.x < UIScreen.main.bounds.width / 2
        if let last = lastTap, Date().timeIntervalSince(last.at) < 0.35, last.left == left {
            model.skip(left ? -10 : 10)
            lastTap = nil
            showControls()
            return
        }
        lastTap = (Date(), left)
        if controls { withAnimation(.easeIn(duration: 0.2)) { controls = false } } else { showControls() }
    }

    /// iPhone and iPad: the title and AirPlay along the top, the big play in the middle with a
    /// skip either side, and the bar with the tracks, chapters, sleep and info under it.
    private var phoneControls: some View {
        ZStack {
            LinearGradient(colors: [Color.black.opacity(0.7), .clear, .clear, Color.black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea().allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack(spacing: 14) {
                    roundButton("xmark", size: 16) { close() }.accessibilityLabel("Close")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(Typeface.geist(17, .semibold)).foregroundStyle(.white).lineLimit(1)
                        if let sub = subtitleLine { Text(sub).font(Typeface.geist(13, .medium)).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
                    }
                    Spacer()
                    RoutePicker().frame(width: 40, height: 40)
                }
                Spacer()
                HStack(spacing: 52) {
                    roundButton("gobackward.10", size: 24) { model.skip(-10); showControls() }.accessibilityLabel("Back 10 seconds")
                    Button { model.togglePlay(); showControls() } label: {
                        Image(systemName: model.playing ? "pause.fill" : "play.fill")
                            .font(.system(size: 34, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 78, height: 78).background(.ultraThinMaterial, in: Circle())
                    }
                    .buttonStyle(PressStyle())
                    .accessibilityLabel(model.playing ? "Pause" : "Play")
                    roundButton("goforward.10", size: 24) { model.skip(10); showControls() }.accessibilityLabel("Forward 10 seconds")
                }
                Spacer()
                phoneScrubber
                HStack(spacing: 10) {
                    subtitlesMenu
                    audioMenu
                    if !(store.playing?.playback.chapters.isEmpty ?? true) {
                        chip("list.bullet", "Chapters") { panel = .chapters }
                    }
                    sleepMenu
                    chip("info.circle", nil) { panel = .info }.accessibilityLabel("Playback info")
                    Spacer(minLength: 0)
                    if let next = store.nextInQueue {
                        chip("forward.end.fill", "Next") { goNext() }.accessibilityLabel("Next: \(next.title)")
                    }
                }
                .padding(.top, 10)
            }
            .padding(.horizontal, 20).padding(.vertical, 14)
        }
        .transition(.opacity)
    }

    private func roundButton(_ symbol: String, size: CGFloat, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: size, weight: .semibold)).foregroundStyle(.white)
                .frame(width: size * 2.3, height: size * 2.3).background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(PressStyle())
    }

    private func chip(_ symbol: String, _ label: String?, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { chipLabel(symbol, label) }.buttonStyle(PressStyle())
    }

    private func chipLabel(_ symbol: String, _ label: String?, on: Bool = false) -> some View {
        HStack(spacing: 6) {
            Image(systemName: symbol).font(.system(size: 14, weight: .semibold))
            if let label { Text(label).font(Typeface.geist(13, .semibold)).lineLimit(1) }
        }
        .foregroundStyle(on ? accent.onColor : .white)
        .padding(.horizontal, 12).frame(height: 34)
        .background(on ? AnyShapeStyle(accent.swiftColor) : AnyShapeStyle(.ultraThinMaterial), in: Capsule())
    }

    /// The file's subtitles in iOS's own menu, the one on ticked; and Find subtitles online.
    @ViewBuilder
    private var subtitlesMenu: some View {
        let p = store.playing
        if model.usingVLC ? !model.vlcSubtitles.isEmpty : (!(p?.playback.subtitleStreams.isEmpty ?? true) || p?.item.isIptv == false) {
            Menu {
                if model.usingVLC {
                    menuItem("Off", on: model.vlcSubtitleId < 0) { model.chooseVLCSubtitle(-1) }
                    ForEach(model.vlcSubtitles, id: \.self) { t in menuItem(t.name, on: t.id == model.vlcSubtitleId) { model.chooseVLCSubtitle(t.id) } }
                } else {
                    menuItem("Off", on: !(p?.playback.subtitleStreams.contains(where: \.selected) ?? false)) { choose(subtitle: "0") }
                    ForEach(p?.playback.subtitleStreams ?? [], id: \.id) { s in menuItem(s.label, on: s.selected) { choose(subtitle: s.id) } }
                    if p?.item.isIptv == false {
                        Divider()
                        Button { find() } label: { Label("Find subtitles online", systemImage: "magnifyingglass") }
                    }
                }
            } label: { chipLabel("captions.bubble", nil) }
            .accessibilityLabel("Subtitles")
        }
    }

    @ViewBuilder
    private var audioMenu: some View {
        let p = store.playing
        if model.usingVLC ? model.vlcAudio.count > 1 : (p?.playback.audioStreams.count ?? 0) > 1 {
            Menu {
                if model.usingVLC {
                    ForEach(model.vlcAudio, id: \.self) { t in menuItem(t.name, on: t.id == model.vlcAudioId) { model.chooseVLCAudio(t.id) } }
                } else {
                    ForEach(p?.playback.audioStreams ?? [], id: \.id) { s in menuItem(s.label, on: s.selected) { choose(audio: s.id) } }
                }
            } label: { chipLabel("speaker.wave.2", nil) }
            .accessibilityLabel("Audio")
        }
    }

    private var sleepMenu: some View {
        Menu {
            ForEach(SLEEP_CHOICES.filter { $0 != -1 || store.playing?.item.type == "episode" }, id: \.self) { m in
                menuItem(m == 0 ? "Off" : m == -1 ? "End of this episode" : "\(m) minutes",
                         on: m == 0 ? sleepAt == nil && !sleepAtEnd : m == -1 ? sleepAtEnd : false) { setSleep(m) }
            }
        } label: { chipLabel("moon.zzz", sleepLabel, on: sleepAt != nil || sleepAtEnd) }
        .accessibilityLabel("Sleep timer")
    }

    @ViewBuilder
    private func menuItem(_ title: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { if on { Label(title, systemImage: "checkmark") } else { Text(title) } }
    }

    /// The bar: dragged, it shows where it'll go, and goes there on letting go.
    private var phoneScrubber: some View {
        let shown = scrubMs ?? model.positionMs
        return VStack(spacing: 6) {
            GeometryReader { g in
                let fraction = model.durationMs > 0 ? Double(shown) / Double(model.durationMs) : 0
                let x = g.size.width * min(1, max(0, fraction))
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.25)).frame(height: scrubMs == nil ? 5 : 8)
                    Capsule().fill(accent.swiftColor).frame(width: x, height: scrubMs == nil ? 5 : 8)
                    Circle().fill(.white).frame(width: scrubMs == nil ? 13 : 20, height: scrubMs == nil ? 13 : 20)
                        .offset(x: x - (scrubMs == nil ? 6.5 : 10))
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                    hideTask?.cancel()
                    scrubMs = Int(Double(model.durationMs) * min(1, max(0, v.location.x / max(1, g.size.width))))
                }.onEnded { _ in
                    if let at = scrubMs { model.seek(toMs: at) }
                    scrubMs = nil
                    showControls()
                })
                .animation(.easeOut(duration: 0.15), value: scrubMs == nil)
            }
            .frame(height: 24)
            HStack {
                Text(clock(shown))
                Spacer()
                Text("-" + clock(max(0, model.durationMs - shown)))
            }
            .font(Typeface.geist(12, .medium)).foregroundStyle(.white.opacity(0.75)).monospacedDigit()
        }
    }
    #endif

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
                if !(store.playing?.playback.subtitleStreams.isEmpty ?? true) || store.playing?.item.isIptv == false || !model.vlcSubtitles.isEmpty {
                    PanelButton(title: "Subtitles", systemImage: "captions.bubble") { panel = .subtitles }.focused($focus, equals: .subtitles)
                }
                if (store.playing?.playback.audioStreams.count ?? 0) > 1 || model.vlcAudio.count > 1 {
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
            if !Task.isCancelled, model.playing, panel == nil {
                withAnimation(.easeIn(duration: 0.3)) { controls = false }
                #if os(tvOS)
                surface = true
                #endif
            }
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
                case .subtitles where model.usingVLC:
                    // The file's own, as VLC reads them.
                    Button { model.chooseVLCSubtitle(-1); self.panel = nil } label: { row("Off", on: model.vlcSubtitleId < 0) }
                    ForEach(model.vlcSubtitles, id: \.self) { t in
                        Button { model.chooseVLCSubtitle(t.id); self.panel = nil } label: { row(t.name, on: t.id == model.vlcSubtitleId) }
                    }
                case .audio where model.usingVLC:
                    ForEach(model.vlcAudio, id: \.self) { t in
                        Button { model.chooseVLCAudio(t.id); self.panel = nil } label: { row(t.name, on: t.id == model.vlcAudioId) }
                    }
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
                        infoRow("Playing", p.item.isIptv ? (model.usingVLC ? "From your IPTV provider, by VLC" : "From your IPTV provider") : p.direct ? "The original file" : "Converted by Plex")
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
