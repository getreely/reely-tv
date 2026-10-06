import SwiftUI
import ReelyCore

/**
 * Reely on iPhone and iPad, laid out as the Android phone app: Home, Movies, TV Shows,
 * Live TV and Requests along the bottom; Search and Settings in each page's corner.
 */
struct PhoneRoot: View {
    @Environment(ReelyStore.self) private var store
    private let tabs: [(String, String, Route)] = [
        ("Home", "house", .home), ("Movies", "film", .library(kind: "movie")), ("TV Shows", "tv", .library(kind: "show")),
        ("Live TV", "dot.radiowaves.left.and.right", .live), ("Requests", "plus.circle", .requests),
    ]

    /// Settings or Search, or a page opened from one of them (an actor from a search): over the tabs.
    private var sheetUp: Bool { store.stack.first == .settings || store.stack.first == .search }

    var body: some View {
        let selected = Binding<Route>(
            get: { tabs.contains { $0.2 == store.tab } ? store.tab : .home },
            set: { store.navigate($0) })
        TabView(selection: selected) {
            ForEach(tabs, id: \.0) { label, icon, route in
                RouteContent(route: store.tab == route ? store.route : route)
                    .safeAreaInset(edge: .top) { if store.stack.count > 1 { BackBar() } }
                    .tabItem { Label(label, systemImage: icon) }
                    .tag(route)
            }
        }
        .sheet(isPresented: Binding(get: { sheetUp }, set: { if !$0 { store.closeSheet() } })) {
            RouteContent(route: store.route).background(Color.ink)
        }
        .onAppear { store.pagesLeaveSheets = true }
    }
}

/// Back to the page underneath.
private struct BackBar: View {
    @Environment(ReelyStore.self) private var store
    var body: some View {
        HStack {
            Button { store.back() } label: { Label("Back", systemImage: "chevron.left") }
                .font(Typeface.meta).foregroundStyle(Color.chalk)
            Spacer()
        }
        .padding(.horizontal, pageMargin).padding(.vertical, 6).background(Color.ink)
    }
}
