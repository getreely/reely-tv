import SwiftUI
import UIKit
import ReelyCore

/**
 * The screensaver, as on the Fire TV: when the remote's been put down for the minutes set
 * in Settings, the library's artwork drifts by with the time over it. Any press puts it
 * away. Nothing's covered while something's playing.
 */
struct ScreensaverHost: ViewModifier {
    @Environment(ReelyStore.self) private var store
    @State private var lastActive = Date()
    @State private var showing = false

    func body(content: Content) -> some View {
        ZStack {
            content
            if showing {
                Screensaver { wake() }.transition(.opacity).zIndex(10)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIFocusSystem.didUpdateNotification)) { _ in lastActive = Date() }
        .onChange(of: store.route) { _, _ in lastActive = Date() }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                let minutes = store.prefs.screensaverMinutes
                let busy = store.playing != nil || store.live.watching != nil || !store.plex.isConnected
                if minutes > 0, !busy, !showing, Date().timeIntervalSince(lastActive) >= Double(minutes * 60) {
                    withAnimation(.easeInOut(duration: 1)) { showing = true }
                }
            }
        }
    }

    private func wake() {
        lastActive = Date()
        withAnimation(.easeOut(duration: 0.4)) { showing = false }
    }
}

private struct Screensaver: View {
    @Environment(ReelyStore.self) private var store
    let onWake: () -> Void
    @State private var index = 0
    @FocusState private var focused: Bool

    var body: some View {
        let pictures = artwork
        ZStack(alignment: .bottomTrailing) {
            Color.black.ignoresSafeArea()
            if !pictures.isEmpty {
                RemoteImage(url: pictures[index % pictures.count], background: .black)
                    .ignoresSafeArea()
                    .id(index)
                    .transition(.opacity)
                    .overlay(LinearGradient(colors: [.clear, .clear, Color.black.opacity(0.7)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
            }
            TimelineView(.everyMinute) { context in
                VStack(alignment: .trailing, spacing: 8) {
                    Text(context.date, format: .dateTime.hour().minute()).font(Typeface.geist(64, .semibold)).foregroundStyle(Color.chalk)
                    ReelyMark(height: 40)
                }
            }
            .padding(80)
            // Any press: the button has the cursor, so OK, Back, Play and the touch surface all land here.
            Button(action: onWake) { Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(PlainFocusStyle())
                .focused($focused)
                .onMoveCommand { _ in onWake() }
                .onExitCommand(perform: onWake)
                .onPlayPauseCommand(perform: onWake)
        }
        .onAppear { focused = true }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 12_000_000_000)
                withAnimation(.easeInOut(duration: 2)) { index += 1 }
            }
        }
    }

    /// Backdrops from Home's rows: what's in the library, each once.
    private var artwork: [URL] {
        let home = store.home
        let items = home.continueWatching + home.recentMovies + home.recentEpisodes.map(\.newest) + home.watchlist
        var seen = Set<String>()
        return items.compactMap { item -> URL? in
            guard let art = item.art, seen.insert(art).inserted else { return nil }
            return store.imageUrl(item.serverBase, art, width: 1920, height: 1080)
        }
    }
}
