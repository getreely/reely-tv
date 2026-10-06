import SwiftUI
import AVFoundation
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
        positionMs = Int(time.seconds.isFinite ? time.seconds * 1000 : 0)
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { durationMs = Int(d * 1000) }
        else if let p = store?.playing { durationMs = p.item.durationMs }
        if Date().timeIntervalSince(lastReport) >= REPORT_EVERY_SECONDS, let store {
            lastReport = Date()
            let (pos, dur, state) = (positionMs, durationMs, playing ? "playing" : "paused")
            Task { await store.report(positionMs: pos, durationMs: dur, state: state) }
        }
    }

    func togglePlay() { playing ? player.pause() : player.play() }

    func seek(toMs ms: Int) {
        let clamped = max(0, durationMs > 0 ? min(ms, durationMs - 1000) : ms)
        player.seek(to: CMTime(value: CMTimeValue(clamped), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        positionMs = clamped
    }

    func skip(_ seconds: Int) { seek(toMs: positionMs + seconds * 1000) }

    func stop() {
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
