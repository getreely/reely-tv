import SwiftUI
import AVFoundation
import ReelyCore

/*
 * Multiview: several channels on the screen at once, as on the Fire TV (MultiView.kt). The
 * channel the player is on is tile 0; the others play beside it, each in its own player,
 * and only the one with the cursor (or the last one tapped) is heard.
 */

/// The guide, or the channel list on a phone, opened to put a channel in Multiview.
struct GuidePick: Equatable, Identifiable {
    /// The tile a chosen channel goes into; nil to add one beside what's playing.
    let replaces: Int?
    var id: Int { replaces ?? -1 }
    var verb: String { replaces != nil ? "Replace with" : "Add" }
}

/// What the guide or the channel list can do for Multiview: add a channel beside, or put one in a tile.
struct MultiviewHooks {
    /// False once four channels are up, which is all the screen holds.
    let canAdd: Bool
    let add: (XtreamChannel, Int?) -> Void
}

/**
 * The extra channels' players, kept by the screen rather than by the tiles that draw them,
 * so a tile can move about or go full screen without its stream being torn down and asked
 * for again. A provider doesn't hand out connections freely enough for that.
 */
@MainActor
@Observable
final class TilePlayers {
    private(set) var models: [Int: LiveModel] = [:]
    @ObservationIgnored private var attempts: [Int: Int] = [:]

    /// Players for exactly these channels: new ones started, gone ones stopped.
    func sync(_ wanted: [(streamId: Int, url: String)], largerBuffer: Bool) {
        let ids = Set(wanted.map(\.streamId))
        for (id, model) in models where !ids.contains(id) {
            model.stop()
            models[id] = nil
            attempts[id] = nil
        }
        for (id, url) in wanted where models[id] == nil {
            // Only the main channel's picture leaves the app in picture in picture.
            let model = LiveModel(pictureInPicture: false)
            model.muted = true
            model.load(url, largerBuffer: largerBuffer)
            models[id] = model
        }
    }

    func model(_ streamId: Int) -> LiveModel? { models[streamId] }

    /// A tile whose channel dropped: tried again a few times, a little longer each time.
    func failed(_ streamId: Int) {
        let tries = (attempts[streamId] ?? 0) + 1
        guard tries <= 3, let model = models[streamId] else { return }
        attempts[streamId] = tries
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(tries) * 3_000_000_000)
            guard self?.models[streamId] === model else { return }
            model.reload()
        }
    }

    func playing(_ streamId: Int) { attempts[streamId] = 0 }

    func wentAway() { models.values.forEach { $0.wentAway() } }
    func cameBack(largerBuffer: Bool) { models.values.forEach { $0.cameBack(largerBuffer: largerBuffer) } }

    func stopAll() {
        models.values.forEach { $0.stop() }
        models = [:]
        attempts = [:]
    }
}

/// A channel's picture, in Apple's player or VLC's: a picture only, the screen takes the touches.
struct LiveSurface: View {
    let model: LiveModel
    #if os(iOS)
    var pip: PictureInPicture? = nil
    #endif

    var body: some View {
        Group {
            if model.usingVLC, let engine = model.vlc {
                VLCSurface(engine: engine)
            } else {
                #if os(iOS)
                VideoSurface(player: model.player, pip: pip)
                #else
                VideoSurface(player: model.player)
                #endif
            }
        }
        .allowsHitTesting(false)
    }
}

/**
 * The outline and name every tile wears once there's more than one. The outline is drawn
 * round a sixteen-by-nine picture in the middle of the tile, as channels are, rather than
 * round the cell: in a two-way split the picture is a band across a tall cell.
 */
struct TileFrame: View {
    let name: String
    let heard: Bool
    var message: String? = nil

    var body: some View {
        GeometryReader { geo in
            let byWidth = geo.size.width * 9 / 16 <= geo.size.height
            let w = byWidth ? geo.size.width : geo.size.height * 16 / 9
            let h = byWidth ? geo.size.width * 9 / 16 : geo.size.height
            ZStack(alignment: .bottomLeading) {
                Rectangle().strokeBorder(Color.chalk, lineWidth: heard ? 3 : 0)
                Text(name)
                    .font(Typeface.geist(13, heard ? .semibold : .medium))
                    .foregroundStyle(Color.chalk.opacity(heard ? 1 : 0.7))
                    .lineLimit(1)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(Color.ink.opacity(0.72), in: RoundedRectangle(cornerRadius: 5))
                    .padding(6)
            }
            .frame(width: w, height: h)
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
            if let message {
                Text(message).font(Typeface.meta).foregroundStyle(Color.chalk).multilineTextAlignment(.center)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.75)))
                    .frame(maxWidth: min(geo.size.width - 16, 320))
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
            }
        }
        .allowsHitTesting(false)
    }
}

/// The spare cell: somewhere obvious to put another channel.
struct AddTileView: View {
    let focused: Bool

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "plus").font(.system(size: 30, weight: .light))
            Text("Add a channel").font(Typeface.label)
        }
        .foregroundStyle(Color.chalk.opacity(focused ? 1 : 0.5))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.ink.opacity(0.6))
        .overlay(Rectangle().strokeBorder(Color.chalk.opacity(focused ? 1 : 0.22), lineWidth: focused ? 3 : 1))
        .allowsHitTesting(false)
    }
}
