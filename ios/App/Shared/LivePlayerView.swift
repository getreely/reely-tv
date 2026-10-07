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

    /// Silent, as a Multiview tile is when the cursor isn't on it.
    @ObservationIgnored var muted = false { didSet { applyMute() } }
    @ObservationIgnored private var largerBuffer = false

    private func applyMute() {
        player.isMuted = muted
        vlc?.setMuted(muted)
    }

    /// The same channel joined again, as it is now: a Multiview tile's stream that dropped.
    func reload() {
        guard let again = loaded else { return }
        loaded = nil
        load(again, largerBuffer: largerBuffer)
    }

    func load(_ url: String?, largerBuffer: Bool) {
        guard url != loaded else { return }
        loaded = url
        self.largerBuffer = largerBuffer
        failed = nil
        waiting = true
        cachingMs = largerBuffer ? 10_000 : 3_000
        guard let url, let address = URL(string: url) else { stopVLC(); player.replaceCurrentItem(with: nil); return }
        // MPEG-TS and the like: VLC, as the Fire TV's ExoPlayer plays them.
        if !applePlays(url) { startVLC(address); return }
        stopVLC()
        player.replaceCurrentItem(with: nil)
        /*
         * A provider's .m3u8 is usually a redirect to a short-lived address of its own. Apple's
         * player went back through it for every fresh look at the list of pieces, every few
         * seconds, and the provider may start a new session each time; so the redirect is
         * followed once here, and the player handed where it led.
         */
        Task { [weak self] in
            let final = await LiveModel.resolved(address)
            guard let self, self.loaded == url, !self.usingVLC else { return }
            self.play(final, largerBuffer: largerBuffer)
        }
    }

    /// Where [address] redirects to, if anywhere: asked once, with a short wait.
    private static func resolved(_ address: URL) async -> URL {
        var request = URLRequest(url: address, timeoutInterval: 8)
        request.httpMethod = "GET"
        // Only the start is wanted: the address it ended at, not the list itself.
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        guard let (_, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200..<400).contains(http.statusCode),
              let final = http.url else { return address }
        return final
    }

    private func play(_ address: URL, largerBuffer: Bool) {
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
        applyMute()
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
 *
 * Multiview, as on the Fire TV: other channels beside it, up to four in all. Holding OK (a
 * long press on a phone) has a tile's menu: full screen, another channel, a different one
 * there, moving it, the saved set, or closing it. The arrows walk the tiles (a tap on a
 * phone), and only the one with the cursor is heard; OK (a double tap) makes it full screen
 * and back; Back closes the tile the cursor's on before it leaves the channel.
 */
struct LivePlayerView: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = LiveModel()
    @State private var tiles = TilePlayers()
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
    /// The guide or the channel list, opened to put a channel in Multiview.
    @State private var pick: GuidePick?
    /// The place on screen the cursor's on; `order` is which tile is in each place.
    @State private var focusedPlace = 0
    @State private var order: [Int] = [0]
    /// One tile filling the screen, the rest still running behind it; held by which tile it is.
    @State private var zoomed: Int?
    #if os(tvOS)
    @FocusState private var surface: Bool
    @FocusState private var action: String?
    #else
    @State private var channels = false
    @State private var lastTap: (at: Date, tile: Int)?
    #endif

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            content(now: Int(context.date.timeIntervalSince1970))
        }
        .onAppear { load(); showBanner(); syncTiles() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.wentAway(); tiles.wentAway() }
            if phase == .active { model.cameBack(largerBuffer: store.prefs.largerBuffer); tiles.cameBack(largerBuffer: store.prefs.largerBuffer) }
        }
        #if os(iOS)
        .onAppear { AppDelegate.playing(true) }
        .onDisappear { AppDelegate.playing(false) }
        #endif
        .onChange(of: url) { _, _ in
            load()
            showBanner()
            // A channel changed: the cursor goes to it, wherever it's been moved to.
            focusedPlace = max(0, Multiview.tileOrder(order, count: tileCount).firstIndex(of: 0) ?? 0)
        }
        .onChange(of: extras.map(\.streamId)) { _, _ in syncTiles() }
        .onChange(of: focusedId) { _, _ in applySound() }
        .onChange(of: tileCount) { _, _ in applySound() }
        .onDisappear { model.stop(); tiles.stopAll() }
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

    // MARK: Multiview

    /// The channels beside the main one: none from the archive, which is one picture.
    private var extras: [XtreamChannel] { catchUp == nil ? store.live.multiview : [] }
    private var tileCount: Int { extras.count + 1 }
    private var focusLayout: Bool { store.prefs.multiviewLayout == .focus }
    private var spare: Bool { Multiview.hasSpareCell(tileCount, focusLayout: focusLayout) }
    private var slotCount: Int { tileCount + (spare ? 1 : 0) }
    private var addSlot: Int { spare ? tileCount : -1 }
    private var place: Int { focusedPlace < slotCount ? focusedPlace : 0 }
    private var places: [Int] { Multiview.tileOrder(order, count: tileCount) + (spare ? [addSlot] : []) }
    private var focusedId: Int { places.indices.contains(place) ? places[place] : 0 }
    private var multi: Bool { slotCount > 1 }
    private var zoomedTile: Int? { zoomed.flatMap { tileCount > 1 && $0 <= extras.count ? $0 : nil } }
    /// The channel in tile [tile].
    private func tileChannel(_ tile: Int) -> XtreamChannel? { tile == 0 ? channel : extras.indices.contains(tile - 1) ? extras[tile - 1] : nil }
    /// The channel being heard, whose name and actions the banner has.
    private var heardChannel: XtreamChannel? { tileChannel(multi ? focusedId : 0) ?? channel }

    private func syncTiles() {
        tiles.sync(extras.compactMap { c in store.channelUrl(c).map { (streamId: c.streamId, url: $0) } }, largerBuffer: store.prefs.largerBuffer)
        applySound()
    }

    /// Only the tile with the cursor on it is heard.
    private func applySound() {
        model.muted = multi && focusedId != 0
        for (i, c) in extras.enumerated() { tiles.model(c.streamId)?.muted = focusedId != i + 1 }
    }

    private func rects(_ size: CGSize) -> [TileRect] {
        focusLayout
            ? Multiview.focusRects(slotCount, focused: place, width: size.width, height: size.height, gap: 3)
            : Multiview.gridRects(slotCount, width: size.width, height: size.height, gap: 3)
    }

    private func rect(of tile: Int, _ all: [TileRect], _ size: CGSize) -> TileRect {
        if zoomedTile == tile || !multi { return TileRect(x: 0, y: 0, width: size.width, height: size.height) }
        let at = places.firstIndex(of: tile) ?? 0
        return all.indices.contains(at) ? all[at] : TileRect(x: 0, y: 0, width: size.width, height: size.height)
    }

    /// Tile [tile] (1 or more) closed: the main channel keeps the cursor, wherever it is.
    private func closeTile(_ tile: Int) {
        let kept = Multiview.withoutTile(places.filter { $0 < tileCount }, tile)
        order = kept
        focusedPlace = max(0, kept.firstIndex(of: 0) ?? 0)
        if zoomed == tile { zoomed = nil }
        store.removeFromMultiview(tile - 1)
    }

    private func moveTile(_ tile: Int, by delta: Int) {
        guard let at = places.firstIndex(of: tile) else { return }
        order = Multiview.movedTile(places.filter { $0 < tileCount }, from: at, delta: delta)
        // The cursor goes with the tile, so moving it again is one more hold.
        focusedPlace = at + delta
    }

    private func openPick(_ p: GuidePick) {
        hideTask?.cancel()
        pick = p
        #if os(tvOS)
        openGuide()
        #else
        channels = true
        #endif
    }

    private var hooks: MultiviewHooks? {
        guard catchUp == nil, channel != nil else { return nil }
        return MultiviewHooks(canAdd: tileCount < Multiview.maxTiles) { picked, replaces in
            if let replaces { store.replaceInMultiview(replaces, picked) } else { store.addToMultiview(picked) }
        }
    }

    /// A tile's menu, as holding OK on one has it on the Fire TV.
    @ViewBuilder
    private func tileMenu(_ tile: Int) -> some View {
        let at = places.firstIndex(of: tile) ?? 0
        if catchUp == nil, tile != addSlot {
            if multi { Button { zoomed = tile; focusedPlace = at } label: { Label("Full screen", systemImage: "arrow.up.left.and.arrow.down.right") } }
            if tileCount < Multiview.maxTiles { Button { openPick(GuidePick(replaces: nil)) } label: { Label("Add another channel", systemImage: "plus") } }
            Button { openPick(GuidePick(replaces: tile)) } label: { Label("Replace channel", systemImage: "rectangle.2.swap") }
            // Never into the spare cell: that stays last, where it's looked for.
            if tileCount > 1 && at > 0 { Button { moveTile(tile, by: -1) } label: { Label("Move left", systemImage: "arrow.left") } }
            if tileCount > 1 && at < tileCount - 1 { Button { moveTile(tile, by: 1) } label: { Label("Move right", systemImage: "arrow.right") } }
            if tileCount > 1 { Button { store.saveMultiview(places.filter { $0 < tileCount }) } label: { Label("Save these channels", systemImage: "heart") } }
            if let saved = store.savedMultiviewLabel {
                Button {
                    order = [0]; focusedPlace = 0; zoomed = nil
                    Task { await store.openSavedMultiview() }
                } label: { Label("Open saved: \(saved)", systemImage: "square.grid.2x2") }
            }
            if tile >= 1 && tile <= extras.count { Button(role: .destructive) { closeTile(tile) } label: { Label("Close channel", systemImage: "xmark") } }
        }
    }

    @ViewBuilder
    private func content(now: Int) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            GeometryReader { geo in
                let all = rects(geo.size)
                ZStack(alignment: .topLeading) {
                    tileView(0, all, geo.size) {
                        #if os(iOS)
                        LiveSurface(model: model, pip: pip)
                        #else
                        LiveSurface(model: model)
                        #endif
                    }
                    ForEach(Array(extras.enumerated()), id: \.element.streamId) { i, c in
                        if let tile = tiles.model(c.streamId) {
                            tileView(i + 1, all, geo.size) { LiveSurface(model: tile) }
                                .onChange(of: tile.failed) { _, failed in if failed != nil { tiles.failed(c.streamId) } }
                                .onChange(of: tile.playing) { _, playing in if playing { tiles.playing(c.streamId) } }
                        }
                    }
                    if spare, all.indices.contains(addSlot), zoomedTile == nil {
                        let r = all[addSlot]
                        AddTileView(focused: place == addSlot).frame(width: r.width, height: r.height).offset(x: r.x, y: r.y)
                    }
                    #if os(iOS)
                    touchLayer(all, geo.size)
                    #endif
                }
            }
            .ignoresSafeArea()
            #if os(tvOS)
            // The remote's, while nothing else on screen takes the cursor.
            Button { pressSurface() } label: { Color.black.opacity(0.001).frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(PlainFocusStyle())
                .focused($surface)
                .disabled(actions || guide)
                .onMoveCommand { move($0, now: now) }
                .contextMenu { tileMenu(focusedId) }
                .ignoresSafeArea()
            #endif
            if !guide && (!multi || zoomedTile == 0) {
                if model.waiting && model.failed == nil { ProgressView().tint(.white).scaleEffect(Typeface.scale).allowsHitTesting(false) }
            }
            if !guide, let error = notice ?? (multi && zoomedTile != 0 ? nil : model.failed) {
                Text(error).font(Typeface.body).foregroundStyle(Color.chalk).multilineTextAlignment(.center)
                    .padding(dp(16)).background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.black.opacity(0.7)))
                    .padding(pageMargin)
                    .allowsHitTesting(false)
            }
            #if os(tvOS)
            if guide, let channel {
                GuideOverlay(playing: channel, now: now, onClose: { closeGuide() }, pick: pick, multiview: hooks).transition(.opacity)
            } else if banner || actions {
                bannerView(now: now).transition(.opacity)
            }
            #else
            if banner { bannerView(now: now).transition(.opacity) }
            #endif
        }
        #if os(tvOS)
        .onExitCommand {
            if actions { hideActions() }
            else if zoomedTile != nil { zoomed = nil }
            else if multi && focusedId >= 1 && focusedId <= extras.count { closeTile(focusedId) }
            else { close() }
        }
        .onPlayPauseCommand { if catchUp != nil { model.togglePlay(); showBanner() } }
        .onAppear { surface = true }
        #else
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .sheet(isPresented: $channels, onDismiss: { pick = nil }) {
            ChannelPicker(pick: pick, multiview: hooks).presentationDetents([.medium, .large])
        }
        #endif
    }

    /// A tile in its place: its picture, and its outline and name once there's more than one.
    private func tileView<Surface: View>(_ tile: Int, _ all: [TileRect], _ size: CGSize, @ViewBuilder _ surface: () -> Surface) -> some View {
        let r = rect(of: tile, all, size)
        let heard = multi && focusedId == tile
        let failed = tile == 0 ? model.failed : tileChannel(tile).flatMap { tiles.model($0.streamId)?.failed }
        return ZStack {
            Color.black
            surface()
            if multi && zoomedTile != tile {
                TileFrame(name: tileChannel(tile)?.name ?? "", heard: heard, message: failed.map { _ in "This channel isn't playing." })
            }
        }
        .frame(width: r.width, height: r.height)
        .offset(x: r.x, y: r.y)
        .zIndex(zoomedTile == tile ? 1 : 0)
    }

    #if os(iOS)
    /*
     * Touches, over the pictures and under the controls, so a tap and a swipe work whether
     * the controls are up or not. With one channel: a tap shows or hides the controls, a
     * swipe up or down changes channel. With several: a tap hears that tile (and, on the one
     * already heard, shows the controls), a double tap makes it full screen and back, a long
     * press has its menu, and a swipe changes the channel in the tile being heard.
     */
    @ViewBuilder
    private func touchLayer(_ all: [TileRect], _ size: CGSize) -> some View {
        if !multi || zoomedTile != nil {
            let tile = zoomedTile ?? 0
            touchArea(TileRect(x: 0, y: 0, width: size.width, height: size.height), place: places.firstIndex(of: tile) ?? 0, tile: tile)
                .zIndex(2)
        } else {
            ForEach(Array(places.enumerated()), id: \.offset) { at, tile in
                if all.indices.contains(at) { touchArea(all[at], place: at, tile: tile).zIndex(2) }
            }
        }
    }

    private func touchArea(_ r: TileRect, place at: Int, tile: Int) -> some View {
        Color.clear
            .frame(width: r.width, height: r.height)
            .contentShape(Rectangle())
            .onTapGesture { tapped(at, tile) }
            .gesture(DragGesture(minimumDistance: 30).onEnded { drag in
                guard abs(drag.translation.height) > abs(drag.translation.width), tile != addSlot else { return }
                focusedPlace = at
                stepHeard(drag.translation.height < 0 ? 1 : -1, tile: tile)
            })
            .contextMenu { tileMenu(tile) }
            .offset(x: r.x, y: r.y)
    }

    private func tapped(_ at: Int, _ tile: Int) {
        if tile == addSlot { focusedPlace = at; openPick(GuidePick(replaces: nil)); return }
        // A second tap on the same tile, straight after: full screen and back. Told apart
        // here, as the film player tells a double tap, so a single tap isn't kept waiting.
        if multi, let last = lastTap, last.tile == tile, Date().timeIntervalSince(last.at) < 0.35 {
            lastTap = nil
            focusedPlace = at
            zoomed = zoomedTile == tile ? nil : tile
            withAnimation { banner = false }
            return
        }
        lastTap = (Date(), tile)
        if multi && zoomedTile == nil && place != at {
            focusedPlace = at
            return
        }
        if banner { withAnimation { banner = false } } else { showBanner(stay: true) }
    }
    #endif

    /// Channel up or down in tile [tile]: the player's own channel, or the one beside it, to the next not already up.
    private func stepHeard(_ by: Int, tile: Int) {
        guard tile != 0, let current = tileChannel(tile) else { store.stepChannel(by); return }
        let list = store.live.channels
        guard !list.isEmpty else { return }
        let up = Set([channel?.streamId].compactMap { $0 } + extras.map(\.streamId))
        var at = list.firstIndex(where: { $0.streamId == current.streamId }) ?? -1
        for _ in 0..<list.count {
            at = (at + by + list.count) % list.count
            if !up.contains(list[at].streamId) { store.replaceInMultiview(tile, list[at]); return }
        }
    }

    // MARK: Over the picture

    private func bannerView(now: Int) -> some View {
        let shown = heardChannel
        let listing = shown.map { store.listing(for: $0) } ?? []
        let on = listing.first { $0.isOn(at: now) }
        let favorite = shown.map { store.live.favorites.contains($0.streamId) } ?? false
        let startOver = !multi && catchUp == nil && on != nil && channel != nil && store.canCatchUp(channel!, on!, now: now)
        return VStack(alignment: .leading, spacing: dp(8)) {
            #if os(iOS)
            HStack {
                Button { close() } label: { Image(systemName: "xmark").font(.system(size: 20, weight: .semibold)) }
                    .foregroundStyle(Color.chalk).accessibilityLabel("Close")
                Spacer()
                // AirPlay, as the film player has it: where the sound (and, with Apple's player, the picture) goes.
                RoutePicker().frame(width: 40, height: 40)
                if pip.possible && !model.usingVLC {
                    Button { pip.toggle() } label: { Image(systemName: "pip.enter").font(.system(size: 20, weight: .semibold)) }
                        .foregroundStyle(Color.chalk).accessibilityLabel("Picture in picture")
                }
            }
            #endif
            Spacer()
            Text(([shown.flatMap { $0.number > 0 ? String($0.number) : nil }, shown?.name].compactMap { $0 }.joined(separator: "  ")) + (favorite ? "  ♥" : ""))
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
                Text(catchUp != nil ? "Left and right skip  ·  OK for more" : "Left and right change channel  ·  Down: the guide  ·  OK for more  ·  Hold OK: more channels")
                    .font(Typeface.label).foregroundStyle(Color.faint)
            }
            #else
            actionRow(on: on, favorite: favorite, startOver: startOver).padding(.top, 6)
            #endif
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, pageMargin).padding(.vertical, dp(24))
        // Only the buttons take touches: a tap or swipe anywhere else is the screen's, under it.
        .background(LinearGradient(colors: [.clear, .clear, Color.black.opacity(0.85)], startPoint: .top, endPoint: .bottom).ignoresSafeArea().allowsHitTesting(false))
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
                liveButton("Previous channel", "chevron.up") { stepHeard(-1, tile: multi ? focusedId : 0) }
                liveButton("Next channel", "chevron.down") { stepHeard(1, tile: multi ? focusedId : 0) }
                liveButton("Channels", "list.bullet", filled: true) { channels = true }
                if tileCount < Multiview.maxTiles {
                    liveButton("Add a channel beside this one", "square.grid.2x2") { openPick(GuidePick(replaces: nil)) }
                }
                if startOver, let on, let at = store.live.watching {
                    liveButton("Start over", "backward.end.fill") { store.playCatchUp(at, on) }
                }
            }
            if let shown = heardChannel {
                liveButton(favorite ? "Remove from Favorites" : "Add to Favorites", favorite ? "heart.fill" : "heart") { store.toggleFavorite(shown) }
            }
            if !multi { liveButton("Channel number", "number") { askNumber = true } }
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
                    control("Guide", "list.bullet.rectangle", filled: true) { openGuide() }
                    if tileCount < Multiview.maxTiles {
                        control("Add a channel", "square.grid.2x2") { openPick(GuidePick(replaces: nil)) }
                    }
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
        .focusSection()
        .defaultFocus($action, catchUp != nil ? "Play" : "Guide")
        .onChange(of: action) { _, _ in showActions() }
    }

    private func control(_ title: String, _ systemImage: String, filled: Bool = false, _ run: @escaping () -> Void) -> some View {
        PanelButton(title: title, systemImage: systemImage, filled: filled) {
            run()
            showBanner(stay: true)
        }
        .focused($action, equals: title == "Pause" ? "Play" : title)
    }
    #endif

    // MARK: The remote

    #if os(tvOS)
    /// OK on the screen: the actions with one channel; with several, a tile full screen and back, or the spare cell's guide.
    private func pressSurface() {
        guard multi else { showActions(); return }
        if place == addSlot { openPick(GuidePick(replaces: nil)); return }
        zoomed = zoomedTile == focusedId ? nil : focusedId
    }

    private func move(_ direction: MoveCommandDirection, now: Int) {
        if multi {
            // Full screen, the others aren't there to walk to.
            guard zoomedTile == nil else { return }
            let dx = direction == .left ? -1 : direction == .right ? 1 : 0
            let dy = direction == .up ? -1 : direction == .down ? 1 : 0
            if let next = Multiview.nextPlace(slotCount, from: place, dx: dx, dy: dy, focusLayout: focusLayout) { focusedPlace = next }
            // Off the bottom edge: the guide, as with one channel.
            else if dy > 0 { pick = nil; openGuide() }
            return
        }
        switch direction {
        case .left, .right:
            let by = direction == .left ? -1 : 1
            if catchUp != nil { model.skip(Double(by * 10)) } else { store.stepChannel(by) }
            showBanner()
        case .down: pick = nil; openGuide()
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
        pick = nil
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
        tiles.stopAll()
        store.stopLive()
    }
}

#if os(iOS)
/// Over a channel on a phone: the categories, and their channels to change to, without
/// leaving the one that's playing until another is chosen. Opened from Multiview, a channel
/// chosen goes beside what's playing, or into the tile it was opened for.
struct ChannelPicker: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    var pick: GuidePick? = nil
    var multiview: MultiviewHooks? = nil
    @State private var category: XtreamCategory?
    @State private var channels: [XtreamChannel] = []
    @State private var loading = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int(context.date.timeIntervalSince1970)
            VStack(alignment: .leading, spacing: 8) {
                if let pick, multiview != nil {
                    Text(pick.replaces != nil ? "Replace with a channel" : "Add a channel beside this one")
                        .font(Typeface.rowTitle).foregroundStyle(Color.chalk)
                        .padding(.horizontal, pageMargin).padding(.top, 18)
                    if pick.replaces == nil && multiview?.canAdd == false {
                        Text("You can watch up to four channels at once.").font(Typeface.meta).foregroundStyle(Color.muted)
                            .padding(.horizontal, pageMargin)
                    }
                }
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
                                .contextMenu {
                                    if let multiview, pick == nil {
                                        Button { dismiss(); multiview.add(channel, nil) } label: { Label("Add beside what's playing", systemImage: "square.grid.2x2") }
                                            .disabled(!multiview.canAdd)
                                    }
                                }
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
        // Opened to add or replace a tile: the channel goes there. The main tile is the
        // player's own channel, so replacing it is changing channel, as below.
        if let pick, let multiview, pick.replaces != 0, channels.indices.contains(index) {
            if pick.replaces == nil && !multiview.canAdd { return }
            multiview.add(channels[index], pick.replaces)
            return
        }
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
