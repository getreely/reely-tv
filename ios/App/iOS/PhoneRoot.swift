import SwiftUI
import ReelyCore

/**
 * Reely on iPhone and iPad: Home, Movies, TV Shows, Live TV and Requests along the bottom,
 * each with iOS's own navigation, so a page opened from a tab goes back with a swipe from
 * the edge, under large titles and frosted bars. Search and Settings open as sheets from
 * every tab's corner.
 */
struct PhoneRoot: View {
    @Environment(ReelyStore.self) private var store
    private let tabs: [(String, String, Route)] = [
        ("Home", "house.fill", .home), ("Movies", "film", .library(kind: "movie")), ("TV Shows", "tv", .library(kind: "show")),
        ("Live TV", "dot.radiowaves.left.and.right", .live), ("Requests", "plus.circle", .requests),
    ]

    /// Settings or Search, over the tabs.
    private var sheetUp: Bool { store.stack.first == .settings || store.stack.first == .search }

    var body: some View {
        let selected = Binding<Route>(
            get: { tabs.contains { $0.2 == store.tab } ? store.tab : .home },
            set: { store.navigate($0) })
        TabView(selection: selected) {
            ForEach(tabs, id: \.0) { label, icon, route in
                NavigationStack(path: path(route)) {
                    RouteContent(route: route)
                        .modifier(TabChrome(title: label, route: route))
                        .navigationDestination(for: Route.self) { page in
                            RouteContent(route: page).modifier(PageChrome(route: page))
                        }
                }
                .tabItem { Label(label, systemImage: icon) }
                .tag(route)
            }
        }
        .sheet(isPresented: Binding(get: { sheetUp }, set: { if !$0 { store.closeSheet() } })) {
            RouteContent(route: store.route).background(Color.ink)
        }
        .onAppear { store.pagesLeaveSheets = true }
    }

    /// The pages over a tab: the store's, while it's the tab showing.
    private func path(_ tab: Route) -> Binding<[Route]> {
        Binding(
            get: { store.stack.first == tab ? Array(store.stack.dropFirst()) : [] },
            set: { if store.stack.first == tab { store.setPath($0) } })
    }
}

/// A tab's own page: its name large, and Search, Settings and the profile in the corner.
private struct TabChrome: ViewModifier {
    @Environment(ReelyStore.self) private var store
    let title: String
    let route: Route

    func body(content: Content) -> some View {
        content
            .navigationTitle(route == .home ? "" : title)
            .navigationBarTitleDisplayMode(route == .home ? .inline : .large)
            .toolbar {
                if route == .home {
                    ToolbarItem(placement: .topBarLeading) { ReelyMark(height: 26) }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if store.plex.isConnected {
                        Button { store.navigate(.search) } label: { Image(systemName: "magnifyingglass") }
                            .accessibilityLabel("Search")
                        Button { store.navigate(.settings) } label: { Image(systemName: "gearshape") }
                            .accessibilityLabel("Settings")
                        if store.plex.homeUsers.count > 1 {
                            Button { NotificationCenter.default.post(name: .chooseProfile, object: nil) } label: {
                                Text(String((store.plex.user?.title ?? "?").prefix(1)).uppercased())
                                    .font(.system(size: 13, weight: .bold)).foregroundStyle(Color.ink)
                                    .frame(width: 28, height: 28).background(Circle().fill(Color.chalk))
                            }
                            .accessibilityLabel("Switch profile")
                        }
                    }
                }
            }
            .tint(Color.chalk)
    }
}

/// A page opened from a tab: its name small in the bar, or none where the page shows it large itself.
private struct PageChrome: ViewModifier {
    let route: Route

    func body(content: Content) -> some View {
        content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(title.isEmpty ? .inline : .large)
            .tint(Color.chalk)
    }

    private var title: String {
        switch route {
        case .person(_, let name, _): return name
        case .collection(_, let title, _), .playlist(_, let title, _): return title
        default: return ""
        }
    }
}
