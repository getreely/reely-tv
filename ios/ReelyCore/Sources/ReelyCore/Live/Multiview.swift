import Foundation

/*
 * Multiview: several channels on the screen at once, as on the Fire TV (MultiView.kt). Tiles
 * are numbered by what they are: 0 the channel the player is on, then the others in the
 * order they were added. Places are where they're drawn: `order` says which tile is in each
 * place, so a tile can be moved without its picture being started again.
 */

/// A tile's place, in whatever units the screen is measured in.
public struct TileRect: Equatable, Sendable {
    public var x: Double, y: Double, width: Double, height: Double
    public init(x: Double, y: Double, width: Double, height: Double) { self.x = x; self.y = y; self.width = width; self.height = height }
    static func from(_ left: Double, _ top: Double, _ right: Double, _ bottom: Double) -> TileRect {
        TileRect(x: left, y: top, width: right - left, height: bottom - top)
    }
}

/// A channel kept in the saved set: by id, its name for the menu.
public struct SavedChannel: Codable, Equatable, Sendable {
    public var streamId: Int
    public var name: String
}

public enum Multiview {
    /// The most channels at once: a 2x2 grid is where the screen runs out.
    public static let maxTiles = 4
    /// What a provider answers when it won't open another stream: over the account's limit.
    public static let refused: Set<Int> = [401, 403, 429, 458, 509]

    /// Every place in the even grid: two side by side, three with the first on the left, four in quarters.
    public static func gridRects(_ slots: Int, width: Double, height: Double, gap: Double) -> [TileRect] {
        let halfW = ((width - gap) / 2).rounded(.down)
        let halfH = ((height - gap) / 2).rounded(.down)
        let right = width - halfW
        let lower = height - halfH
        switch slots {
        case 2: return [.from(0, 0, halfW, height), .from(right, 0, width, height)]
        case 3: return [.from(0, 0, halfW, height), .from(right, 0, width, halfH), .from(right, lower, width, height)]
        case 4: return [.from(0, 0, halfW, halfH), .from(right, 0, width, halfH), .from(0, lower, halfW, height), .from(right, lower, width, height)]
        default: return [.from(0, 0, width, height)]
        }
    }

    /// The places either side of the large one in the focus layout, in their own order.
    public static func focusSides(_ slots: Int, focused: Int) -> (before: [Int], after: [Int]) {
        let large = max(0, min(focused, slots - 1))
        return (Array(0..<large), Array(min(slots, large + 1)..<max(slots, large + 1)))
    }

    /**
     * Every place in the focus layout: the one with the cursor large, full height, in its own
     * place in the line; those before and after it in picture-shaped boxes down the middle of
     * a column either side.
     */
    public static func focusRects(_ slots: Int, focused: Int, width: Double, height: Double, gap: Double) -> [TileRect] {
        guard slots > 1 else { return [.from(0, 0, width, height)] }
        let (before, after) = focusSides(slots, focused: focused)
        let columns = Double((before.isEmpty ? 0 : 1) + (after.isEmpty ? 0 : 1))
        let room = width - gap * columns
        let sideW = (room * (columns == 2 ? 0.2 : 0.3)).rounded(.down)
        let largeW = room - sideW * columns
        let largeLeft = before.isEmpty ? 0 : sideW + gap
        var rects = Array(repeating: TileRect(x: 0, y: 0, width: 0, height: 0), count: slots)
        rects[max(0, min(focused, slots - 1))] = .from(largeLeft, 0, largeLeft + largeW, height)
        func column(_ places: [Int], _ left: Double) {
            let tileH = (sideW * 9 / 16).rounded(.down)
            let total = tileH * Double(places.count) + gap * Double(places.count - 1)
            var top = ((height - total) / 2).rounded(.down)
            for place in places {
                rects[place] = .from(left, top, left + sideW, top + tileH)
                top += tileH + gap
            }
        }
        column(before, 0)
        column(after, largeLeft + largeW + gap)
        return rects
    }

    /// The place a direction leads to in the grid, or nil when there's none that way.
    public static func neighbour(_ slots: Int, from: Int, dx: Int, dy: Int) -> Int? {
        var to: Int?
        switch slots {
        case 2:
            if dx < 0 && from == 1 { to = 0 } else if dx > 0 && from == 0 { to = 1 }
        case 3:
            if dx > 0 && from == 0 { to = 1 } else if dx < 0 && from != 0 { to = 0 }
            else if dy > 0 && from == 1 { to = 2 } else if dy < 0 && from == 2 { to = 1 }
        case 4:
            if dx > 0 { to = from == 0 ? 1 : from == 2 ? 3 : nil }
            else if dx < 0 { to = from == 1 ? 0 : from == 3 ? 2 : nil }
            else if dy > 0 { to = from == 0 ? 2 : from == 1 ? 3 : nil }
            else if dy < 0 { to = from == 2 ? 0 : from == 3 ? 1 : nil }
        default: break
        }
        return to.flatMap { (0..<slots).contains($0) ? $0 : nil }
    }

    /// Where the arrows go from [from]: the focus layout is a line, the grid by [neighbour].
    public static func nextPlace(_ slots: Int, from: Int, dx: Int, dy: Int, focusLayout: Bool) -> Int? {
        guard focusLayout else { return neighbour(slots, from: from, dx: dx, dy: dy) }
        let to = from + dx + dy
        return (0..<slots).contains(to) ? to : nil
    }

    /// Room for a spare cell to add a channel in: from two in the focus layout, at three in the grid.
    public static func hasSpareCell(_ tiles: Int, focusLayout: Bool) -> Bool {
        focusLayout ? (2...3).contains(tiles) : tiles == 3
    }

    /// Which tile is in each place for [count] tiles, carried over from [order]: new ones join at the end.
    public static func tileOrder(_ order: [Int], count: Int) -> [Int] {
        var kept: [Int] = []
        for t in order where (0..<count).contains(t) && !kept.contains(t) { kept.append(t) }
        for t in 0..<count where !kept.contains(t) { kept.append(t) }
        return kept
    }

    /// [order] once tile [gone] is closed: the tiles after it are each numbered one less; places don't change.
    public static func withoutTile(_ order: [Int], _ gone: Int) -> [Int] {
        order.filter { $0 != gone }.map { $0 > gone ? $0 - 1 : $0 }
    }

    /// [order] with the tile in place [from] swapped with the one [delta] places along.
    public static func movedTile(_ order: [Int], from: Int, delta: Int) -> [Int] {
        let to = from + delta
        guard order.indices.contains(from), order.indices.contains(to) else { return order }
        var moved = order
        moved.swapAt(from, to)
        return moved
    }

    /// What to call a saved set in a menu: "BBC One and 2 more".
    public static func savedLabel(_ names: [String]) -> String {
        let first = names.first?.trimmingCharacters(in: .whitespaces) ?? ""
        return "\(first.isEmpty ? "Channel" : first) and \(names.count - 1) more"
    }
}

extension ReelyStore {
    /// The channel the player itself is on: the one the rest sit beside.
    var mainChannel: XtreamChannel? { live.watchingChannel }

    /// A channel beside the one playing, up to four in all; not one already up, nor over the archive.
    public func addToMultiview(_ channel: XtreamChannel) {
        guard let main = mainChannel, live.catchUp == nil, main.streamId != channel.streamId,
              !live.multiview.contains(where: { $0.streamId == channel.streamId }),
              live.multiview.count < Multiview.maxTiles - 1 else { return }
        live.multiview.append(channel)
    }

    /// The extra channel at [index] (tile index - 1) taken away.
    public func removeFromMultiview(_ index: Int) {
        guard live.multiview.indices.contains(index) else { return }
        live.multiview.remove(at: index)
    }

    public func clearMultiview() { live.multiview = [] }

    /// A different channel in tile [tile]. Tile 0 is the player's own, so that's changing channel.
    public func replaceInMultiview(_ tile: Int, _ channel: XtreamChannel) {
        if tile == 0 {
            if let at = live.channels.firstIndex(where: { $0.streamId == channel.streamId }) { watchChannel(at) }
            else { watchIn(nil, [channel], index: 0) }
            return
        }
        let index = tile - 1
        guard live.multiview.indices.contains(index), mainChannel?.streamId != channel.streamId,
              !live.multiview.contains(where: { $0.streamId == channel.streamId }) else { return }
        live.multiview[index] = channel
    }

    /// Keeps the channels up now, in their places ([order]: tiles left to right, 0 the main one). One set: saving again replaces it.
    public func saveMultiview(_ order: [Int]) {
        guard let main = mainChannel else { return }
        let tiles = [main] + live.multiview
        var set: [SavedChannel] = []
        for t in order where tiles.indices.contains(t) && !set.contains(where: { $0.streamId == tiles[t].streamId }) {
            set.append(SavedChannel(streamId: tiles[t].streamId, name: tiles[t].name))
        }
        guard set.count > 1 else { return }
        live.savedMultiview = set
        store.setJson("savedMultiview", set)
    }

    /// What to call the saved set in the tile menu, or nil when there's none or it's what's up.
    public var savedMultiviewLabel: String? {
        let saved = live.savedMultiview
        guard saved.count > 1 else { return nil }
        let up = ([mainChannel?.streamId] + live.multiview.map(\.streamId)).compactMap { $0 }
        if up.count == saved.count && saved.allSatisfy({ up.contains($0.streamId) }) { return nil }
        return Multiview.savedLabel(saved.map(\.name))
    }

    /// The saved channels up: the first in the player, the rest beside it. One the provider has dropped is left out.
    public func openSavedMultiview() async {
        let saved = live.savedMultiview
        guard let c = live.credentials, saved.count > 1 else { return }
        var known = live.channels
        if !saved.allSatisfy({ s in known.contains { $0.streamId == s.streamId } }) {
            known += (try? await xtream.channels(c)) ?? []
        }
        let channels = saved.compactMap { s in known.first { $0.streamId == s.streamId } }
        guard let main = channels.first, live.credentials == c else { return }
        if let at = live.channels.firstIndex(where: { $0.streamId == main.streamId }) { watchChannel(at) }
        else { watchIn(nil, [main], index: 0) }
        live.multiview = Array(channels.dropFirst().prefix(Multiview.maxTiles - 1))
    }
}
