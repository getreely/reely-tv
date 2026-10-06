import Foundation

/**
 * Continue Watching as Plex's own apps order it (ContinueWatching.kt): one entry per show,
 * the part-watched episode winning, most recently watched first.
 */
public func continueWatchingOrder(_ items: [PlexItem]) -> [PlexItem] {
    var kept: [PlexItem] = []
    var showIndex: [String: Int] = [:]
    for item in items {
        guard item.type == "episode", let show = item.grandparentRatingKey else {
            if !kept.contains(where: { $0.serverBase == item.serverBase && $0.ratingKey == item.ratingKey }) { kept.append(item) }
            continue
        }
        let key = "\(item.serverBase ?? "")|\(show)"
        if let at = showIndex[key] {
            if kept[at].viewOffsetMs <= 0 && item.viewOffsetMs > 0 { kept[at] = item }
        } else {
            showIndex[key] = kept.count
            kept.append(item)
        }
    }
    return stableSorted(kept) { recency($0) > recency($1) }
}

public func recency(_ item: PlexItem) -> Int { item.lastViewedAt > 0 ? item.lastViewedAt : item.addedAt }
