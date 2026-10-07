import SwiftUI
import UIKit
#if os(tvOS)
import TVVLCKit
#else
import MobileVLCKit
#endif

/**
 * VLC's player, for what Apple's can't open: an IPTV provider's MKV film, a channel sent as
 * bare MPEG-TS. The Fire TV plays these with ExoPlayer and its FFmpeg decoders; here VLC
 * does it. It draws into a view of its own; where it's got to is read twice a second.
 */
@MainActor
final class VLCEngine {
    let player = VLCMediaPlayer()
    let view: UIView = {
        let v = UIView()
        v.backgroundColor = .black
        return v
    }()
    /// Called as it plays: where it is, and whether it's playing, buffering, ended or failed.
    var onUpdate: (() -> Void)?
    private var timer: Timer?

    init() {
        player.drawable = view
    }

    /// [url] from [startMs], keeping [cachingMs] ahead.
    func load(_ url: URL, startMs: Int, cachingMs: Int) {
        let media = VLCMedia(url: url)
        var options: [String: Any] = ["network-caching": cachingMs]
        if startMs > 0 { options["start-time"] = Double(startMs) / 1000 }
        media.addOptions(options)
        player.media = media
        player.play()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.onUpdate?() }
        }
    }

    var positionMs: Int { Int(player.time.intValue) }
    var durationMs: Int { Int(player.media?.length.intValue ?? 0) }
    var isPlaying: Bool { player.isPlaying }
    var isBuffering: Bool { player.state == .opening || player.state == .buffering }
    var hasEnded: Bool { player.state == .ended }
    var hasFailed: Bool { player.state == .error }

    func togglePlay() { player.isPlaying ? player.pause() : player.play() }

    /*
     * Out of sight, the sound plays on with the picture off — VLC can't draw to a closed
     * app — and the picture comes back with the app, as VLC's own app does it.
     */
    private var shownTrack: Int32?
    func wentAway() {
        guard shownTrack == nil, player.isPlaying else { return }
        shownTrack = player.currentVideoTrackIndex
        player.currentVideoTrackIndex = -1
    }
    func cameBack() {
        guard let track = shownTrack else { return }
        shownTrack = nil
        player.currentVideoTrackIndex = track
    }

    /// Silent or not: a Multiview tile is heard only with the cursor on it.
    func setMuted(_ muted: Bool) {
        // Optional or not, as VLCKit's headers have it: either way, it's set if it's there.
        let audio: VLCAudio? = player.audio
        audio?.isMuted = muted
    }

    func seek(toMs ms: Int) { player.time = VLCTime(int: Int32(max(0, ms))) }

    func stop() {
        timer?.invalidate()
        timer = nil
        player.stop()
        player.media = nil
    }

    // MARK: The file's own tracks

    struct Track: Equatable, Hashable { let id: Int32; let name: String }

    private func tracks(_ names: [Any]?, _ ids: [Any]?) -> [Track] {
        let names = (names ?? []).map { "\($0)" }
        let ids = (ids ?? []).compactMap { ($0 as? NSNumber)?.int32Value }
        return zip(ids, names).filter { $0.0 >= 0 }.map { Track(id: $0.0, name: $0.1) }
    }

    var audioTracks: [Track] { tracks(player.audioTrackNames, player.audioTrackIndexes) }
    var subtitleTracks: [Track] { tracks(player.videoSubTitlesNames, player.videoSubTitlesIndexes) }
    var audioTrack: Int32 { player.currentAudioTrackIndex }
    var subtitleTrack: Int32 { player.currentVideoSubTitleIndex }
    func setAudioTrack(_ id: Int32) { player.currentAudioTrackIndex = id }
    /// -1 turns them off.
    func setSubtitleTrack(_ id: Int32) { player.currentVideoSubTitleIndex = id }
}

/// VLC's picture, drawn into a SwiftUI page.
struct VLCSurface: UIViewRepresentable {
    let engine: VLCEngine
    func makeUIView(context: Context) -> UIView {
        let host = UIView()
        host.backgroundColor = .black
        // Only a picture: a tap is the player's, to bring its controls back. VLC's view took
        // them for itself, and with a channel playing through VLC the controls never came back.
        host.isUserInteractionEnabled = false
        engine.view.isUserInteractionEnabled = false
        engine.view.frame = host.bounds
        engine.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(engine.view)
        return host
    }
    func updateUIView(_ view: UIView, context: Context) {}
}

/// Whether Apple's player opens this as it is: HLS, and MP4, M4V and MOV files.
func applePlays(_ url: String, container: String? = nil) -> Bool {
    if let container { return PlaybackPlanContainers.contains(container.lowercased()) }
    let path = URL(string: url)?.path.lowercased() ?? url.lowercased()
    let ext = (path as NSString).pathExtension
    return ext.isEmpty || ["m3u8", "mp4", "m4v", "mov"].contains(ext)
}

let PlaybackPlanContainers: Set<String> = ["mp4", "m4v", "mov"]
