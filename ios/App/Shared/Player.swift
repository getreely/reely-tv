import SwiftUI
import AVFoundation
#if os(tvOS)
import AVKit
#endif
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
    @ObservationIgnored private var loadedSubtitle: String?
    @ObservationIgnored private weak var store: ReelyStore?
    @ObservationIgnored private var endObserver: NSObjectProtocol?

    init() {
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
        guard p.url != loadedUrl, let url = URL(string: p.url) else { loadSubtitle(p); return }
        loadedUrl = p.url
        failed = nil
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
        player.replaceCurrentItem(with: item)
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
        // Not as it is, then: Plex converts it, from where it had got to.
        if let store, store.playing?.direct == true, store.convert(positionMs: positionMs) { return }
        // The provider's files have no Plex to convert them: VLC tries instead, from where it got to.
        if let p = store?.playing, p.item.isIptv, !usingVLC, let url = URL(string: p.url) {
            startVLC(url, startMs: max(positionMs, p.startMs))
            return
        }
        failed = "This couldn't be played. Try again."
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
        guard !usingVLC else { return }
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

/// Apple's player, drawn into a SwiftUI page.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.playerLayer.player = player
        v.playerLayer.videoGravity = .resizeAspect
        v.backgroundColor = .black
        return v
    }
    func updateUIView(_ view: PlayerLayerView, context: Context) { view.playerLayer.player = player }

    final class PlayerLayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// "1:02:03" or "2:03".
func clock(_ ms: Int) -> String {
    let s = max(0, ms / 1000)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}
