import SwiftUI
import AVFoundation
import ReelyCore

/// A channel, or a programme from its archive, in Apple's player.
@MainActor
@Observable
final class LiveModel {
    @ObservationIgnored let player = AVPlayer()
    private(set) var playing = false
    private(set) var waiting = true
    private(set) var failed: String?
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var loaded: String?
    @ObservationIgnored private var cachingMs = 3_000
    /// VLC playing: a channel sent as bare MPEG-TS, or one Apple's player wouldn't open.
    private(set) var usingVLC = false
    @ObservationIgnored private(set) var vlc: VLCEngine?

    init() {
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let status = p.timeControlStatus
            Task { @MainActor in
                guard self?.usingVLC != true else { return }
                self?.playing = status == .playing
                self?.waiting = status != .playing
            }
        })
    }

    func load(_ url: String?, largerBuffer: Bool) {
        guard url != loaded else { return }
        loaded = url
        failed = nil
        waiting = true
        cachingMs = largerBuffer ? 10_000 : 3_000
        guard let url, let address = URL(string: url) else { stopVLC(); player.replaceCurrentItem(with: nil); return }
        // MPEG-TS and the like: VLC, as the Fire TV's ExoPlayer plays them.
        if !applePlays(url) { startVLC(address); return }
        stopVLC()
        let item = AVPlayerItem(url: address)
        /*
         * Left to itself, Apple's player joins a live stream as near its newest moment as it
         * can, with a few seconds in hand; a provider's stream arriving in ten-second pieces,
         * a little late now and then, ran dry every piece or two — buffering every fifteen
         * seconds. A live stream can't be held further ahead than live itself, so what's in
         * hand is how far back from live it sits: 25 seconds, 45 with the larger buffer. The
         * forward buffer is only a ceiling, and matters for the archive (catch-up, Start
         * over), which is recorded and can be fetched ahead.
         */
        item.preferredForwardBufferDuration = largerBuffer ? 120 : 30
        item.automaticallyPreservesTimeOffsetFromLive = true
        item.configuredTimeOffsetFromLive = CMTime(seconds: largerBuffer ? 45 : 25, preferredTimescale: 1)
        observations.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let status = item.status
            Task { @MainActor in
                if status == .failed { self?.appleFailed() }
            }
        })
        player.replaceCurrentItem(with: item)
        player.play()
    }

    /// Apple's player wouldn't: VLC tries, asking for MPEG-TS where the provider sends HLS.
    private func appleFailed() {
        guard !usingVLC, let loaded else { return }
        let ts = loaded.hasSuffix(".m3u8") ? String(loaded.dropLast(5)) + ".ts" : loaded
        if let address = URL(string: ts) { startVLC(address) } else { failed = LiveModel.notPlaying }
    }

    static let notPlaying = "This channel isn't playing. It may be off air, or your provider's connection limit reached."

    private func startVLC(_ address: URL) {
        player.pause()
        player.replaceCurrentItem(with: nil)
        let engine = vlc ?? VLCEngine()
        vlc = engine
        usingVLC = true
        failed = nil
        waiting = true
        engine.onUpdate = { [weak self] in
            guard let self, self.usingVLC else { return }
            self.playing = engine.isPlaying
            self.waiting = !engine.isPlaying
            if engine.hasFailed || engine.hasEnded { self.failed = LiveModel.notPlaying }
        }
        engine.load(address, startMs: 0, cachingMs: cachingMs)
    }

    private func stopVLC() {
        guard usingVLC else { return }
        vlc?.stop()
        usingVLC = false
    }

    func togglePlay() {
        if usingVLC { vlc?.togglePlay(); return }
        playing ? player.pause() : player.play()
    }

    func skip(_ seconds: Double) {
        if usingVLC, let engine = vlc { engine.seek(toMs: engine.positionMs + Int(seconds * 1000)); return }
        let at = max(0, player.currentTime().seconds + seconds)
        player.seek(to: CMTime(seconds: at, preferredTimescale: 600))
    }

    func stop() {
        stopVLC()
        player.pause()
        player.replaceCurrentItem(with: nil)
        loaded = nil
    }

    /*
     * Out of the app and back. The sound plays on while it's away (see VideoSurface and
     * VLCEngine.wentAway); but a channel can't be paused and picked up later the way a film
     * can — the stream behind it has moved on or closed — so coming back to one that has
     * stopped joins it again as it is now.
     */
    @ObservationIgnored private var wasPlaying = false
    func wentAway() {
        wasPlaying = playing
        if usingVLC { vlc?.wentAway() }
    }
    func cameBack(largerBuffer: Bool) {
        if usingVLC { vlc?.cameBack() }
        let stalled = usingVLC ? !(vlc?.isPlaying ?? false) : player.timeControlStatus != .playing || player.currentItem?.status == .failed
        guard wasPlaying, stalled, let again = loaded else { return }
        loaded = nil
        load(again, largerBuffer: largerBuffer)
    }
}

/**
 * A channel, full screen, as on the Fire TV. On Apple TV: left and right change channel
 * (or skip, in the archive), down brings up the guide over it, OK or up has its actions —
 * the guide, Start over, Go live, Favorites, a channel by number — and Back goes back to
 * the list. On a phone: a tap has the same actions, and a swipe up or down changes channel.
 */
struct LivePlayerView: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = LiveModel()
    #if os(iOS)
    @State private var pip = PictureInPicture()
    #endif
    /// What's on, over the picture for a few seconds after a change.
    @State private var banner = true
    /// The actions under it, with the cursor in them.
    @State private var actions = false
    @State private var guide = ProcessInfo.processInfo.arguments.contains("overguide")
    @State private var hideTask: Task<Void, Never>?
    @State private var askNumber = false
    @State private var number = ""
    @State private var notice: String?
    #if os(tvOS)
    @FocusState private var surface: Bool
    @FocusState private var action: String?
    #else
    @State private var channels = false
    #endif

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            content(now: Int(context.date.timeIntervalSince1970))
        }
        .onAppear { load(); showBanner() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.wentAway() }
            if phase == .active { model.cameBack(largerBuffer: store.prefs.largerBuffer) }
        }
        #if os(iOS)
        .onAppear { AppDelegate.playing(true) }
        .onDisappear { AppDelegate.playing(false) }
        #endif
        .onChange(of: url) { _, _ in load(); showBanner() }
        .onDisappear { model.stop() }
        .alert("Channel number", isPresented: $askNumber) {
            TextField("Number", text: $number)
            #if os(iOS)
                .keyboardType(.numberPad)
            #endif
            Button("Watch") { tune() }
            Button("Cancel", role: .cancel) { number = "" }
        } message: {
            Text("A channel in \(store.live.category?.name ?? "this list"), by the number on it.")
        }
    }

    private var channel: XtreamChannel? { store.live.watchingChannel }
    private var catchUp: LiveState.CatchUp? { store.live.catchUp }
    private var url: String? { catchUp?.url ?? channel.flatMap { store.channelUrl($0) } }

    private func load() { model.load(url, largerBuffer: store.prefs.largerBuffer) }

    @ViewBuilder
    private func content(now: Int) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.usingVLC, let engine = model.vlc {
                VLCSurface(engine: engine).ignoresSafeArea().allowsHitTesting(false)
            } else {
                #if os(iOS)
                VideoSurface(player: model.player, pip: pip).ignoresSafeArea()
                #else
                VideoSurface(player: model.player).ignoresSafeArea()
                #endif
            }
            #if os(tvOS)
            // The remote's, while nothing else on screen takes the cursor.
            Button { showActions() } label: { Color.black.opacity(0.001).frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(PlainFocusStyle())
                .focused($surface)
                .disabled(actions || guide)
                .onMoveCommand { move($0, now: now) }
                .ignoresSafeArea()
            #endif
            if !guide {
                if model.waiting && model.failed == nil { ProgressView().tint(.white).scaleEffect(Typeface.scale) }
                if let error = notice ?? model.failed {
                    Text(error).font(Typeface.body).foregroundStyle(Color.chalk).multilineTextAlignment(.center)
                        .padding(dp(16)).background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.black.opacity(0.7)))
                        .padding(pageMargin)
                }
            }
            #if os(tvOS)
            if guide, let channel {
                GuideOverlay(playing: channel, now: now) { closeGuide() }.transition(.opacity)
            } else if banner || actions {
                bannerView(now: now).transition(.opacity)
            }
            #else
            if banner { bannerView(now: now).transition(.opacity) }
            #endif
        }
        #if os(tvOS)
        .onExitCommand {
            if actions { hideActions() } else { close() }
        }
        .onPlayPauseCommand { if catchUp != nil { model.togglePlay(); showBanner() } }
        .onAppear { surface = true }
        #else
        .contentShape(Rectangle())
        .onTapGesture { if banner { withAnimation { banner = false } } else { showBanner(stay: true) } }
        .gesture(DragGesture(minimumDistance: 40).onEnded { drag in
            guard abs(drag.translation.height) > abs(drag.translation.width) else { return }
            store.stepChannel(drag.translation.height < 0 ? 1 : -1)
        })
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .sheet(isPresented: $channels) { ChannelPicker().presentationDetents([.medium, .large]) }
        #endif
    }

    // MARK: Over the picture

    private func bannerView(now: Int) -> some View {
        let listing = channel.map { store.listing(for: $0) } ?? []
        let on = listing.first { $0.isOn(at: now) }
        let favorite = channel.map { store.live.favorites.contains($0.streamId) } ?? false
        let startOver = catchUp == nil && on != nil && channel != nil && store.canCatchUp(channel!, on!, now: now)
        return VStack(alignment: .leading, spacing: dp(8)) {
            #if os(iOS)
            HStack {
                Button { close() } label: { Image(systemName: "xmark").font(.system(size: 20, weight: .semibold)) }
                    .foregroundStyle(Color.chalk).accessibilityLabel("Close")
                Spacer()
                if pip.possible && !model.usingVLC {
                    Button { pip.toggle() } label: { Image(systemName: "pip.enter").font(.system(size: 20, weight: .semibold)) }
                        .foregroundStyle(Color.chalk).accessibilityLabel("Picture in picture")
                }
            }
            #endif
            Spacer()
            Text(([channel.flatMap { $0.number > 0 ? String($0.number) : nil }, channel?.name].compactMap { $0 }.joined(separator: "  ")) + (favorite ? "  ♥" : ""))
                .font(Typeface.headline).foregroundStyle(Color.chalk).lineLimit(1)
            if let catchUp {
                Text("\(catchUp.programme.title)  ·  \(liveTime(catchUp.programme.start))–\(liveTime(catchUp.programme.stop))  ·  From the archive")
                    .font(Typeface.meta).foregroundStyle(Color.muted).lineLimit(1)
            } else if let on {
                Text("\(on.title)  ·  \(liveTime(on.start))–\(liveTime(on.stop))").font(Typeface.meta).foregroundStyle(Color.muted).lineLimit(1)
                if let next = listing.first(where: { $0.start >= on.stop }) {
                    Text("Next: \(liveTime(next.start)) \(next.title)").font(Typeface.label).foregroundStyle(Color.faint).lineLimit(1)
                }
            }
            #if os(tvOS)
            if actions {
                actionRow(on: on, favorite: favorite, startOver: startOver).padding(.top, 8)
            } else {
                Text(catchUp != nil ? "Left and right skip  ·  OK for more" : "Left and right change channel  ·  Down: the guide  ·  OK for more")
                    .font(Typeface.label).foregroundStyle(Color.faint)
            }
            #else
            actionRow(on: on, favorite: favorite, startOver: startOver).padding(.top, 6)
            #endif
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, pageMargin).padding(.vertical, dp(24))
        .background(LinearGradient(colors: [.clear, .clear, Color.black.opacity(0.85)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        #if os(iOS)
        // A finger on the controls keeps them up, whatever it's doing.
        .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in scheduleHide(after: 8) })
        #endif
    }

    #if os(iOS)
    /*
     * On a phone: round symbols in one row, as the film player has them, each named for
     * VoiceOver. They fit across without scrolling — scrolling a strip of worded buttons
     * sideways didn't count as using the controls, and they went away mid-slide.
     */
    private func actionRow(on: Programme?, favorite: Bool, startOver: Bool) -> some View {
        HStack(spacing: 14) {
            if catchUp != nil {
                liveButton("Back 10 seconds", "gobackward.10") { model.skip(-10) }
                liveButton(model.playing ? "Pause" : "Play", model.playing ? "pause.fill" : "play.fill", big: true) { model.togglePlay() }
                liveButton("Forward 10 seconds", "goforward.10") { model.skip(10) }
                liveButton("Go live", "dot.radiowaves.left.and.right", filled: true) { store.goLive() }
            } else {
                liveButton("Previous channel", "chevron.up") { store.stepChannel(-1) }
                liveButton("Next channel", "chevron.down") { store.stepChannel(1) }
                liveButton("Channels", "list.bullet", filled: true) { channels = true }
                if startOver, let on, let at = store.live.watching {
                    liveButton("Start over", "backward.end.fill") { store.playCatchUp(at, on) }
                }
            }
            if let channel {
                liveButton(favorite ? "Remove from Favorites" : "Add to Favorites", favorite ? "heart.fill" : "heart") { store.toggleFavorite(channel) }
            }
            liveButton("Channel number", "number") { askNumber = true }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, dp(10))
    }

    private func liveButton(_ title: String, _ symbol: String, filled: Bool = false, big: Bool = false, _ run: @escaping () -> Void) -> some View {
        Button {
            run()
            showBanner(stay: true)
        } label: {
            Image(systemName: symbol).font(.system(size: big ? 22 : 18, weight: .semibold))
                .foregroundStyle(filled ? accent.onColor : .white)
                .frame(width: big ? 60 : 50, height: big ? 60 : 50)
                .background(filled ? AnyShapeStyle(accent.swiftColor) : AnyShapeStyle(.ultraThinMaterial), in: Circle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(title)
    }
    #else
    private func actionRow(on: Programme?, favorite: Bool, startOver: Bool) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: dp(10)) {
                if catchUp != nil {
                    control("Back 10", "gobackward.10") { model.skip(-10) }
                    control(model.playing ? "Pause" : "Play", model.playing ? "pause.fill" : "play.fill") { model.togglePlay() }
                    control("Forward 10", "goforward.10") { model.skip(10) }
                    control("Go live", "dot.radiowaves.left.and.right", filled: true) { store.goLive() }
                } else {
                    #if os(tvOS)
                    control("Guide", "list.bullet.rectangle", filled: true) { openGuide() }
                    #else
                    control("Previous", "chevron.up") { store.stepChannel(-1) }
                    control("Next", "chevron.down") { store.stepChannel(1) }
                    control("Channels", "list.bullet.rectangle", filled: true) { channels = true }
                    #endif
                    if startOver, let on, let at = store.live.watching {
                        control("Start over", "backward.end.fill") { store.playCatchUp(at, on) }
                    }
                }
                if let channel {
                    control(favorite ? "Remove from Favorites" : "Add to Favorites", favorite ? "heart.fill" : "heart") { store.toggleFavorite(channel) }
                }
                control("Channel number", "number") { askNumber = true }
            }
            .padding(.vertical, dp(10))
        }
        .scrollClipDisabled()
        #if os(tvOS)
        .focusSection()
        .defaultFocus($action, catchUp != nil ? "Play" : "Guide")
        .onChange(of: action) { _, _ in showActions() }
        #endif
    }

    #endif

    private func control(_ title: String, _ systemImage: String, filled: Bool = false, _ run: @escaping () -> Void) -> some View {
        PanelButton(title: title, systemImage: systemImage, filled: filled) {
            run()
            showBanner(stay: true)
        }
        #if os(tvOS)
        .focused($action, equals: title == "Pause" ? "Play" : title)
        #endif
    }

    // MARK: The remote

    #if os(tvOS)
    private func move(_ direction: MoveCommandDirection, now: Int) {
        switch direction {
        case .left, .right:
            let by = direction == .left ? -1 : 1
            if catchUp != nil { model.skip(Double(by * 10)) } else { store.stepChannel(by) }
            showBanner()
        case .down: openGuide()
        case .up: showActions()
        @unknown default: break
        }
    }

    private func openGuide() {
        hideTask?.cancel()
        actions = false
        banner = false
        withAnimation(.easeOut(duration: 0.2)) { guide = true }
    }

    private func closeGuide() {
        withAnimation(.easeIn(duration: 0.2)) { guide = false }
        surface = true
    }

    private func showActions() {
        withAnimation(.easeOut(duration: 0.2)) { banner = true; actions = true }
        scheduleHide(after: 8)
    }

    private func hideActions() {
        hideTask?.cancel()
        withAnimation(.easeIn(duration: 0.2)) { actions = false; banner = false }
        surface = true
    }
    #endif

    private func showBanner(stay: Bool = false) {
        withAnimation(.easeOut(duration: 0.2)) { banner = true }
        scheduleHide(after: stay ? 8 : 5)
    }

    private func scheduleHide(after seconds: UInt64) {
        hideTask?.cancel()
        // Screenshots keep it up.
        if ProcessInfo.processInfo.arguments.contains("-demo") { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.3)) {
                banner = false
                #if os(tvOS)
                actions = false
                #endif
            }
            #if os(tvOS)
            surface = true
            #endif
        }
    }

    /// The channel with the number typed, in the list that's open.
    private func tune() {
        let typed = number.trimmingCharacters(in: .whitespaces)
        number = ""
        guard let n = Int(typed) else { return }
        if !store.tuneNumber(n) { say("No channel \(n) in \(store.live.category?.name ?? "this list").") }
    }

    private func say(_ text: String) {
        notice = text
        Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if notice == text { notice = nil }
        }
    }

    private func close() {
        hideTask?.cancel()
        model.stop()
        store.stopLive()
    }
}

#if os(iOS)
/// Over a channel on a phone: the categories, and their channels to change to, without
/// leaving the one that's playing until another is chosen.
struct ChannelPicker: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var category: XtreamCategory?
    @State private var channels: [XtreamChannel] = []
    @State private var loading = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int(context.date.timeIntervalSince1970)
            VStack(alignment: .leading, spacing: 8) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(store.shownCategories) { c in Pill(title: c.name, on: c.id == category?.id) { choose(c) } }
                    }
                    .padding(.horizontal, pageMargin).padding(.vertical, 12)
                }
                ScrollView {
                    LazyVStack(spacing: 2) {
                        if loading { ProgressView().padding(40) }
                        ForEach(Array(channels.enumerated()), id: \.element.streamId) { index, channel in
                            ChannelRow(channel: channel, now: now, onWatch: { watch(index) })
                                .background(channel.streamId == store.live.watchingChannel?.streamId ? Color.surfaceHigh : .clear, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    .padding(.horizontal, pageMargin - 8)
                }
            }
        }
        .background(Color.surfaceRaised)
        .onAppear {
            category = store.live.category
            channels = store.live.channels
        }
    }

    private func choose(_ c: XtreamCategory) {
        guard c.id != category?.id else { return }
        category = c
        channels = []
        loading = true
        Task {
            let found = (try? await store.channelsOf(c)) ?? []
            guard category == c else { return }
            channels = found
            loading = false
            await store.loadGuide(Array(found.prefix(40)))
        }
    }

    private func watch(_ index: Int) {
        dismiss()
        if channels == store.live.channels { store.watchChannel(index) } else { store.watchIn(category, channels, index: index) }
    }
}
#endif

/// "Starting now": a reminder from the guide, as the Fire TV puts it up. Watch, or Dismiss.
struct ReminderNotice: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    let due: Reminder

    var body: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            Text("Starting now").font(Typeface.label).foregroundStyle(accent.swiftColor)
            Text(due.title).font(Typeface.rowTitle).foregroundStyle(Color.chalk).lineLimit(2)
            Text("On \(due.channelName)").font(Typeface.meta).foregroundStyle(Color.muted)
            HStack(spacing: dp(10)) {
                PanelButton(title: "Watch", systemImage: "play.fill", filled: true) { Task { await store.watchReminder() } }
                PanelButton(title: "Dismiss", systemImage: "xmark") { store.dismissReminder() }
            }
            .padding(.top, dp(6))
        }
        .padding(dp(18))
        .frame(maxWidth: dp(420), alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(16)).fill(Color.surfaceRaised.opacity(0.97)))
        .overlay(RoundedRectangle(cornerRadius: dp(16)).strokeBorder(Color.line))
        #if os(tvOS)
        .focusSection()
        #endif
    }
}
