import SwiftUI
import ReelyCore

/**
 * What holding OK (or a long press) on a title offers, as on the Fire TV: play it or carry
 * on, start it over, mark it, open its page (for an episode, its show's), and on Continue
 * Watching, take it off. Only what the title can do.
 */
struct ItemMenu: ViewModifier {
    @Environment(ReelyStore.self) private var store
    let item: PlexItem
    var inContinueWatching = false

    func body(content: Content) -> some View {
        content.contextMenu {
            let resumable = (item.resumeFraction ?? 0) > 0
            if (item.type == "show" || item.type == "season") && item.leafCount > 0 {
                Button { Task { await store.playNextEpisode(of: item) } } label: {
                    Label(item.viewedLeafCount == 0 ? "Play first episode" : item.isWatched ? "Play from the start" : "Play next episode", systemImage: "play.fill")
                }
            }
            if item.isPlayable {
                Button { Task { await store.play(item, resume: true) } } label: { Label(resumable ? "Resume" : "Play", systemImage: "play.fill") }
                if resumable {
                    Button { Task { await store.play(item, resume: false) } } label: { Label("Play from the beginning", systemImage: "arrow.counterclockwise") }
                }
            }
            Button { Task { await store.setWatched(item, !item.isWatched) } } label: {
                Label(item.isWatched ? "Mark as unwatched" : "Mark as watched", systemImage: "checkmark")
            }
            Button { openPage(store, item) } label: {
                Label(item.type == "episode" ? "Go to \(item.grandparentTitle ?? "the show")" : "Details", systemImage: "info.circle")
            }
            if inContinueWatching {
                Button(role: .destructive) { Task { await store.removeFromContinueWatching(item) } } label: {
                    Label("Remove from Continue Watching", systemImage: "xmark")
                }
            }
        }
    }
}

extension View {
    func itemMenu(_ item: PlexItem, inContinueWatching: Bool = false) -> some View {
        modifier(ItemMenu(item: item, inContinueWatching: inContinueWatching))
    }
}

/// A title's page: an episode's is its show's, at that episode; a playlist or collection its list.
@MainActor
func openPage(_ store: ReelyStore, _ item: PlexItem) {
    switch item.type {
    case "playlist": store.navigate(.playlist(ratingKey: item.ratingKey, title: item.title, serverBase: item.serverBase))
    case "collection": store.navigate(.collection(ratingKey: item.ratingKey, title: item.title, serverBase: item.serverBase))
    case "episode": store.navigate(.detail(ratingKey: item.grandparentRatingKey ?? item.ratingKey, serverBase: item.serverBase))
    case "season": store.navigate(.detail(ratingKey: item.parentRatingKey ?? item.ratingKey, serverBase: item.serverBase))
    default: store.navigate(.detail(ratingKey: item.ratingKey, serverBase: item.serverBase))
    }
}
