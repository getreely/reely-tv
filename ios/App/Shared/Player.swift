import SwiftUI
import AVFoundation
import AVKit
import ReelyCore

/**
 * What's playing, driven by Apple's player: where it is, whether it's playing, and the
 * reports to Plex every ten seconds, as the Fire TV makes them. A file that won't play as
 * it is goes to Plex to convert, from where it had got to.
 */
@MainActor
@Observable
final class PlayerModel {
    @ObservationIgnored let player = AVPlayer()
    private(set) var positionMs = 0
    private(set) var durationMs = 0
    private(set) var playing = false
    private(set) var buffering = true
    private(set) var failed: String?
    private(set) var ended = false
    private(set) var cues: [Cue] = []
    /// VLC playing instead of Apple's player: a provider's file Apple's can't open.
    private(set) var usingVLC = false
    @ObservationIgnored private(set) var vlc: VLCEngine?
    /// The file's own tracks, when VLC is playing it.
    private(set) var vlcAudio: [VLCEngine.Track] = []
    private(set) var vlcSubtitles: [VLCEngine.Track] = []
    private(set) var vlcAudioId: Int32 = -1
    private(set) var vlcSubtitleId: Int32 = -1
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var lastReport = Date.distantPast
    @ObservationIgnored private var loadedUrl: String?
    @ObservationIgnored private var loadedAttempt = 0
    /*
     * The connection dropped: fresh tries so far, and where it was when it gave up and
     * waits for Try again. Plex's own app picks up again the same way.
     */
    @ObservationIgnored private var drops = 0
    @ObservationIgnored private var retry: Task<Void, Never>?
    private(set) var stuckAt: Int?
    /// What the player last said went wrong, for Playback info: the codes say which it was.
    private(set) var lastProblem: String?
    @ObservationIgnored private var loadedSubtitle: String?
    @ObservationIgnored private weak var store: ReelyStore?
    @ObservationIgnored private var endObserver: NSObjectProtocol?

    init() {
        // Subtitles are Reely's to choose: drawn by the app, or by Plex into the picture.
        // Left to itself, Apple's player turns on any it finds in Plex's stream, and those
        // stayed on whatever was picked.
        player.appliesMediaSelectionCriteriaAutomatically = false
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let status = p.timeControlStatus
            Task { @MainActor in
                self?.playing = status == .playing
                self?.buffering = status == .waitingToPlayAtSpecifiedRate
            }
        })
    }

    func attach(_ store: ReelyStore) { self.store = store }

    /// What the store says is playing, loaded when it's changed: another file, or a conversion of this one.
    func load(_ p: Playing) {
        guard p.url != loadedUrl || p.attempt != loadedAttempt, let url = URL(string: p.url) else { loadSubtitle(p); return }
        // Another title, not the same one asked for afresh: its drops start from none.
        if p.attempt <= loadedAttempt { drops = 0 }
        loadedUrl = p.url
        loadedAttempt = p.attempt
        failed = nil
        stuckAt = nil
        ended = false
        // A provider's MKV and the like: VLC, as the Fire TV's ExoPlayer plays them.
        if p.item.isIptv && !applePlays(p.url, container: p.playback.container) {
            startVLC(url, startMs: p.startMs)
            return
        }
        stopVLC()
        let item = AVPlayerItem(url: url)
        // Apple's player takes HLS durations from the stream; a file's from the file.
        observations.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let status = item.status
            let error = item.error
            Task { @MainActor in self?.itemStatus(status, error: error) }
        })
        if let end = endObserver { NotificationCenter.default.removeObserver(end) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended = true }
        }
        // Buffer, from Settings: about fifty seconds ahead, or up to two minutes.
        item.preferredForwardBufferDuration = (store?.prefs.largerBuffer ?? false) ? 120 : 50
        // Let go of the old stream completely before the new one, so nothing of it is kept.
        player.replaceCurrentItem(with: nil)
        player.replaceCurrentItem(with: item)
        Task { @MainActor in
            if let group = try? await item.asset.loadMediaSelectionGroup(for: .legible) { item.select(nil, in: group) }
        }
        #if os(tvOS)
        // Match frame rate: the TV switches to the film's rate and range, as the Fire TV does.
        if store?.prefs.matchFrameRate ?? true {
            let asset = item.asset
            Task { @MainActor in
                if let criteria = try? await asset.load(.preferredDisplayCriteria) { PlayerModel.displayManager?.preferredDisplayCriteria = criteria }
            }
        }
        #endif
        if p.startMs > 0 { player.seek(to: CMTime(value: CMTimeValue(p.startMs), timescale: 1000)) }
        player.play()
        loadSubtitle(p)
    }

    private func itemStatus(_ status: AVPlayerItem.Status, error: Error?) {
        guard status == .failed else { return }
        // The stream being swapped for a sound or subtitle change: the new one is on its way.
        if store?.replacingStream == true { return }
        lastProblem = PlayerModel.describe(error)
        if PlayerModel.dropped(error), let p = store?.playing {
            let at = max(positionMs, p.startMs)
            if drops < RECONNECT_TRIES {
                drops += 1
                failed = "Reconnecting…"
                let wait = UInt64(RECONNECT_WAIT_SECONDS * drops) * 1_000_000_000
                retry?.cancel()
                retry = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: wait)
                    guard !Task.isCancelled, let store = self?.store, store.playing?.sessionId == p.sessionId else { return }
                    await store.reopen(positionMs: at)
                }
            } else {
                stuckAt = at
                failed = p.item.isIptv ? "Lost the connection to your IPTV provider." : "Lost the connection to your Plex server."
            }
            return
        }
        // Not as it is, then: Plex converts it, from where it had got to.
        if let store, store.playing?.direct == true, store.convert(positionMs: positionMs) { return }
        // The provider's files have no Plex to convert them: VLC tries instead, from where it got to.
        if let p = store?.playing, p.item.isIptv, !usingVLC, let url = URL(string: p.url) {
            startVLC(url, startMs: max(positionMs, p.startMs))
            return
        }
        failed = "This couldn't be played. Try again."
    }

    /**
     * A sound or subtitle change on its way: the old stream let go of at once. Still asking
     * for pieces of it kept Plex's old conversion going, and the new one came back the same.
     */
    func release() {
        guard !usingVLC else { return }
        retry?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        loadedUrl = nil
        buffering = true
    }

    /// After giving up on a dropped connection: from where it was, afresh.
    func tryAgain() {
        guard let at = stuckAt, let store else { return }
        stuckAt = nil
        drops = 0
        failed = "Reconnecting…"
        Task { await store.reopen(positionMs: at) }
    }

    /// "CoreMediaErrorDomain -12938 · NSURLErrorDomain -1004": each error and what was under it.
    private static func describe(_ error: Error?) -> String? {
        var parts: [String] = []
        var next = error as NSError?
        while let e = next, parts.count < 4 {
            parts.append("\(e.domain) \(e.code)")
            next = e.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// The network, or the server turning the stream away part-way, rather than the file.
    private static func dropped(_ error: Error?) -> Bool {
        var next = error as NSError?
        while let e = next {
            if e.domain == NSURLErrorDomain { return true }
            // CoreMedia's: an HTTP 403/404 for a piece of the stream, or pieces not arriving in time.
            if e.domain == "CoreMediaErrorDomain" && [-12660, -12938, -12884, -12889, -16839].contains(e.code) { return true }
            next = e.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    private func loadSubtitle(_ p: Playing) {
        let id = p.textSubtitle?.url
        guard id != loadedSubtitle else { return }
        loadedSubtitle = id
        cues = []
        guard let sub = p.textSubtitle else { return }
        Task {
            guard let response = try? await URLSessionTransport().send(HttpRequest(url: sub.url, timeout: 30)), response.ok else { return }
            let parsed = parseSubtitles(response.text, codec: sub.codec)
            if loadedSubtitle == sub.url { cues = parsed }
        }
    }

    private func tick(_ time: CMTime) {
        // Between streams there's no time to tell: nought would be reported to Plex as where it got to.
        guard !usingVLC, player.currentItem != nil else { return }
        positionMs = Int(time.seconds.isFinite ? time.seconds * 1000 : 0)
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { durationMs = Int(d * 1000) }
        else if let p = store?.playing { durationMs = p.item.durationMs }
        reportNow()
    }

    // MARK: VLC

    private func startVLC(_ url: URL, startMs: Int) {
        player.pause()
        player.replaceCurrentItem(with: nil)
        let engine = vlc ?? VLCEngine()
        vlc = engine
        usingVLC = true
        failed = nil
        buffering = true
        engine.onUpdate = { [weak self] in self?.vlcTick() }
        engine.load(url, startMs: startMs, cachingMs: (store?.prefs.largerBuffer ?? false) ? 10_000 : 3_000)
    }

    private func stopVLC() {
        guard usingVLC else { return }
        vlc?.stop()
        usingVLC = false
        vlcAudio = []
        vlcSubtitles = []
    }

    private func vlcTick() {
        guard usingVLC, let engine = vlc else { return }
        positionMs = engine.positionMs
        durationMs = engine.durationMs > 0 ? engine.durationMs : store?.playing?.item.durationMs ?? 0
        playing = engine.isPlaying
        buffering = engine.isBuffering
        if engine.hasEnded { ended = true }
        if engine.hasFailed { failed = "This couldn't be played. It may be in a form even VLC can't open, or your provider's connection limit reached." }
        let audio = engine.audioTracks, subtitles = engine.subtitleTracks
        if audio != vlcAudio { vlcAudio = audio }
        if subtitles != vlcSubtitles { vlcSubtitles = subtitles }
        vlcAudioId = engine.audioTrack
        vlcSubtitleId = engine.subtitleTrack
        reportNow()
    }

    func chooseVLCAudio(_ id: Int32) { vlc?.setAudioTrack(id); vlcAudioId = id }
    func chooseVLCSubtitle(_ id: Int32) { vlc?.setSubtitleTrack(id); vlcSubtitleId = id }

    /// Where it's got to, told every ten seconds.
    private func reportNow() {
        if Date().timeIntervalSince(lastReport) >= REPORT_EVERY_SECONDS, let store {
            lastReport = Date()
            let (pos, dur, state) = (positionMs, durationMs, playing ? "playing" : "paused")
            Task { await store.report(positionMs: pos, durationMs: dur, state: state) }
        }
    }

    func togglePlay() {
        if usingVLC { vlc?.togglePlay(); return }
        playing ? player.pause() : player.play()
    }

    func seek(toMs ms: Int) {
        let clamped = max(0, durationMs > 0 ? min(ms, durationMs - 1000) : ms)
        if usingVLC { vlc?.seek(toMs: clamped) }
        else { player.seek(to: CMTime(value: CMTimeValue(clamped), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) }
        positionMs = clamped
    }

    func skip(_ seconds: Int) { seek(toMs: positionMs + seconds * 1000) }

    func stop() {
        stopVLC()
        player.pause()
        player.replaceCurrentItem(with: nil)
        loadedUrl = nil
        #if os(tvOS)
        PlayerModel.displayManager?.preferredDisplayCriteria = nil
        #endif
    }

    #if os(tvOS)
    static var displayManager: AVDisplayManager? {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.avDisplayManager
    }
    #endif

    var subtitle: String? { cueAt(cues, positionMs) }
}

/**
 * Apple's player, drawn into a SwiftUI page. On iPhone and iPad, with [pip], it goes on in a
 * window of its own over other apps when you swipe home; without one — or with that turned
 * off on the phone — the sound plays on, as a phone's video apps do: iOS stops a player
 * whose picture is still drawing behind a closed app, so the picture lets go of it until
 * the app comes back.
 */
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    #if os(iOS)
    var pip: PictureInPicture? = nil
    #endif
    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.playerLayer.player = player
        v.playerLayer.videoGravity = .resizeAspect
        v.backgroundColor = .black
        #if os(iOS)
        v.pip = pip
        pip?.attach(v.playerLayer)
        #endif
        return v
    }
    func updateUIView(_ view: PlayerLayerView, context: Context) {
        if view.held == nil { view.playerLayer.player = player }
    }

    final class PlayerLayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        #if os(iOS)
        weak var pip: PictureInPicture?
        #endif
        /// The player, let go of while the app is out of sight; see VideoSurface.
        fileprivate(set) var held: AVPlayer?
        private var watching: [NSObjectProtocol] = []

        override init(frame: CGRect) {
            super.init(frame: frame)
            let center = NotificationCenter.default
            watching = [
                center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.wentAway() }
                },
                center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.cameBack() }
                },
            ]
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
        deinit { watching.forEach(NotificationCenter.default.removeObserver) }

        private func wentAway() {
            #if os(iOS)
            if pip?.active == true { return }
            #endif
            guard let player = playerLayer.player, player.timeControlStatus != .paused else { return }
            held = player
            playerLayer.player = nil
        }

        private func cameBack() {
            guard let player = held else { return }
            playerLayer.player = player
            held = nil
        }
    }
}

#if os(iOS)
/**
 * Picture in picture on iPhone and iPad: started from the player's button, or by itself on
 * swiping home while something plays, as the TV and Netflix apps do.
 */
@MainActor
@Observable
final class PictureInPicture: NSObject, AVPictureInPictureControllerDelegate {
    @ObservationIgnored private var controller: AVPictureInPictureController?
    @ObservationIgnored private var watch: NSKeyValueObservation?
    private(set) var possible = false
    private(set) var active = false

    func attach(_ layer: AVPlayerLayer) {
        guard AVPictureInPictureController.isPictureInPictureSupported(), controller?.playerLayer !== layer,
              let made = AVPictureInPictureController(playerLayer: layer) else { return }
        made.canStartPictureInPictureAutomaticallyFromInline = true
        made.delegate = self
        controller = made
        watch = made.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] c, _ in
            let possible = c.isPictureInPicturePossible
            Task { @MainActor in self?.possible = possible }
        }
    }

    func toggle() {
        guard let controller else { return }
        if controller.isPictureInPictureActive { controller.stopPictureInPicture() } else { controller.startPictureInPicture() }
    }

    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) {
        Task { @MainActor in self.active = true }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        Task { @MainActor in self.active = false }
    }

    nonisolated func pictureInPictureController(_ c: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler done: @escaping (Bool) -> Void) {
        done(true)
    }
}
#endif

/// "1:02:03" or "2:03".
func clock(_ ms: Int) -> String {
    let s = max(0, ms / 1000)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}
