import SwiftUI
import ReelyCore

/**
 * Reely on Apple TV, laid out as on the Fire TV: the mark, Search, the tabs, then the
 * profile, Settings and the clock along the top; the page below. Moving onto a tab opens
 * it; up from a page goes to the tab it belongs to.
 */
struct TVRoot: View {
    @Environment(ReelyStore.self) private var store

    var body: some View {
        // The page fills the screen, its pictures to the edges, and the tabs sit over its top.
        ZStack(alignment: .top) {
            RouteContent(route: store.route)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            TopBar()
                .background(LinearGradient(colors: [Color.ink.opacity(0.9), Color.ink.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        }
        .ignoresSafeArea()
        .onExitCommand {
            // Back: the page under this one; from a tab, Home.
            if !store.back(), store.route != .home { store.navigate(.home) }
        }
    }
}

private struct TopBar: View {
    @Environment(ReelyStore.self) private var store
    @FocusState private var focused: Route?
    private let tabs: [(String, Route)] = [("Home", .home), ("Movies", .library(kind: "movie")), ("TV Shows", .library(kind: "show")),
                                           ("Live TV", .live), ("Request", .requests)]

    var body: some View {
        HStack(spacing: 18) {
            ReelyMark(height: 46).padding(.trailing, 16)
            if store.plex.isConnected {
                TabButton(label: nil, systemImage: "magnifyingglass", on: store.tab == .search) { store.navigate(.search) }
                    .focused($focused, equals: .search)
                ForEach(tabs, id: \.0) { label, route in
                    TabButton(label: label, systemImage: nil, on: store.tab == route) { store.navigate(route) }
                        .focused($focused, equals: route)
                }
            }
            Spacer()
            if store.plex.isConnected {
                TabButton(label: nil, systemImage: "gearshape", on: store.tab == .settings) { store.navigate(.settings) }
                    .focused($focused, equals: .settings)
            }
            Clock()
        }
        .padding(.horizontal, pageMargin).padding(.top, 50).padding(.bottom, 24)
        .focusSection()
        .onChange(of: focused) { _, route in
            // Moving onto a tab opens it, as the Fire TV's do; Search and Settings open on OK.
            if let route, route != .search, route != .settings, store.tab != route { store.navigate(route) }
        }
    }
}

private struct TabButton: View {
    let label: String?
    let systemImage: String?
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) { TabLabel(label: label, systemImage: systemImage, on: on) }
            .buttonStyle(TabStyle())
    }
}

private struct TabLabel: View {
    let label: String?
    let systemImage: String?
    let on: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        Group {
            if let label { Text(label).font(Typeface.geist(17, .semibold)) }
            if let systemImage { Image(systemName: systemImage).font(.system(size: 32, weight: .semibold)) }
        }
        .foregroundStyle(focused ? Color.ink : on ? Color.chalk : Color.muted)
        .padding(.horizontal, label == nil ? 18 : 28).padding(.vertical, 14)
        .background(Capsule().fill(focused ? Color.chalk : on ? Color.surfaceHigh : .clear))
    }
}

private struct TabStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

/// The time, as the Fire TV shows it in the corner.
private struct Clock: View {
    var body: some View {
        TimelineView(.everyMinute) { context in
            Text(context.date, format: .dateTime.hour().minute()).font(Typeface.meta).foregroundStyle(Color.muted)
        }
    }
}
