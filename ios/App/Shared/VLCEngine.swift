import SwiftUI
import UIKit
import VLCKit

/**
 * VLC's player, for what Apple's can't open: an IPTV provider's MKV film, a channel sent as
 * bare MPEG-TS. The Fire TV plays these with ExoPlayer and its FFmpeg decoders; here VLC
 * does it, with VLCKit 4: it draws into a view of ours, and can take the picture out of the
 * app in picture in picture, as Apple's player does. Where it's got to is read twice a second.
 */
@MainActor
final class VLCEngine {
    let player: VLCMediaPlayer
    private let picture: VLCPictureView
    /// The view VLC's picture is drawn into.
    var view: UIView { picture.host }
    /// Called as it plays: where it is, and whether it's playing, buffering, ended or failed.
    var onUpdate: (() -> Void)?
    private var timer: Timer?
    /// Whether it ever got going, and whether it was stopped here: VLCKit 4 has no "ended",
    /// only stopped, so a stop that wasn't ours, after playing, is the end.
    private var started = false
    private var stoppedHere = false

    /// [pictureInPicture] false for a picture that never leaves the app: a Multiview tile.
    init(pictureInPicture: Bool = true) {
        let made = VLCMediaPlayer()
        player = made
        picture = VLCPictureView(player: made, pictureInPicture: pictureInPicture)
        made.drawable = picture.drawable
        // Called on the main thread (see VLCPictureView).
        picture.onChange = { [weak self] in MainActor.assumeIsolated { self?.onUpdate?() } }
    }

    /// [url] from [startMs], keeping [cachingMs] ahead.
    func load(_ url: URL, startMs: Int, cachingMs: Int) {
        guard let media = VLCMedia(url: url) else { return }
        var options: [String: Any] = ["network-caching": cachingMs]
        if startMs > 0 { options["start-time"] = Double(startMs) / 1000 }
        media.addOptions(options)
        started = false
        stoppedHere = false
        player.media = media
        player.play()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                if self.player.isPlaying { self.started = true }
                self.picture.invalidate()
                self.onUpdate?()
            }
        }
    }

    var positionMs: Int { Int(player.time.intValue) }
    var durationMs: Int { Int(player.media?.length.intValue ?? 0) }
    var isPlaying: Bool { player.isPlaying }
    var isBuffering: Bool { player.state == .opening || player.state == .buffering }
    var hasEnded: Bool { started && !stoppedHere && (player.state == .stopped || player.state == .stopping) }
    var hasFailed: Bool { player.state == .error }

    func togglePlay() { player.isPlaying ? player.pause() : player.play() }

    // MARK: Picture in picture

    var pictureInPicturePossible: Bool { picture.pictureInPicturePossible }
    var pictureInPictureActive: Bool { picture.pictureInPictureActive }
    func togglePictureInPicture() {
        if picture.pictureInPictureActive { picture.stopPictureInPicture() } else { picture.startPictureInPicture() }
    }

    /*
     * Out of sight with no picture in picture, the sound plays on with the picture off —
     * there's nothing to draw to — and the picture comes back with the app. Where picture in
     * picture can start, the picture is left on: leaving the app starts it, about as the app
     * hears it's gone, and a picture taken off first would leave it black.
     */
    private var shownTrack: Int?
    func wentAway() {
        guard shownTrack == nil, player.isPlaying, !picture.pictureInPicturePossible else { return }
        guard let at = player.videoTracks.firstIndex(where: { $0.isSelected }) else { return }
        shownTrack = at
        player.deselectAllVideoTracks()
    }
    func cameBack() {
        guard let at = shownTrack else { return }
        shownTrack = nil
        player.selectTrack(at: at, type: .video)
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
        stoppedHere = true
        player.stop()
        player.media = nil
    }

    // MARK: The file's own tracks

    /// A track by its place in the player's list of them.
    struct Track: Equatable, Hashable { let id: Int32; let name: String }

    var audioTracks: [Track] { player.audioTracks.enumerated().map { Track(id: Int32($0.offset), name: $0.element.trackName) } }
    var subtitleTracks: [Track] { player.textTracks.enumerated().map { Track(id: Int32($0.offset), name: $0.element.trackName) } }
    var audioTrack: Int32 { Int32(player.audioTracks.firstIndex { $0.isSelected } ?? -1) }
    var subtitleTrack: Int32 { Int32(player.textTracks.firstIndex { $0.isSelected } ?? -1) }
    func setAudioTrack(_ id: Int32) {
        guard id >= 0 else { return }
        player.selectTrack(at: Int(id), type: .audio)
    }
    /// -1 turns them off.
    func setSubtitleTrack(_ id: Int32) {
        if id < 0 { player.deselectAllTextTracks() } else { player.selectTrack(at: Int(id), type: .text) }
    }
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
