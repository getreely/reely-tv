import SwiftUI
import ReelyCore

/**
 * Reely on iPhone and iPad: Home, Movies, TV Shows, Live TV and Requests in a bar floating
 * over the bottom, each with iOS's own navigation, so a page opened from a tab goes back
 * with a swipe from the edge, under large titles and frosted bars. Search and Settings open
 * as sheets from every tab's corner.
 */
struct PhoneRoot: View {
    @Environment(ReelyStore.self) private var store
    private let tabs: [(String, String, Route)] = [
        ("Home", "house.fill", .home), ("Movies", "film.fill", .library(kind: "movie")), ("TV Shows", "tv.fill", .library(kind: "show")),
        ("Live TV", "dot.radiowaves.left.and.right", .live), ("Requests", "plus.circle.fill", .requests),
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
                // The system's own bar stays hidden; ReelyTabBar floats in its place.
                .toolbar(.hidden, for: .tabBar)
                .tag(route)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ReelyTabBar(tabs: tabs, selected: selected.wrappedValue) { store.navigate($0) }
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

/**
 * The tabs, in a frosted capsule floating over the bottom of the page: an icon each, and the
 * one showing in a pill of the accent color with its name beside it. Pressed again, a tab
 * goes back to its top. What's on the page scrolls on underneath.
 */
private struct ReelyTabBar: View {
    @Environment(\.accent) private var accent
    let tabs: [(String, String, Route)]
    let selected: Route
    let choose: (Route) -> Void
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 2) {
            ForEach(tabs, id: \.0) { label, icon, route in
                let on = route == selected
                Button { choose(route) } label: {
                    HStack(spacing: 7) {
                        Image(systemName: icon).font(.system(size: 17, weight: .semibold))
                        if on { Text(label).font(Typeface.geist(14, .semibold)).lineLimit(1).fixedSize() }
                    }
                    .foregroundStyle(on ? accent.onColor : Color.chalk.opacity(0.62))
                    .padding(.horizontal, on ? 16 : 13)
                    .frame(height: 46)
                    .background {
                        if on { Capsule().fill(accent.swiftColor).matchedGeometryEffect(id: "pill", in: pill) }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(label)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(5)
        .background(.ultraThinMaterial, in: Capsule())
        .background(Capsule().fill(Color.ink.opacity(0.35)))
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.45), radius: 18, y: 8)
        .environment(\.colorScheme, .dark)
        .animation(.spring(response: 0.34, dampingFraction: 0.82), value: selected)
        .sensoryFeedback(.selection, trigger: selected)
        .padding(.bottom, 6)
    }
}
