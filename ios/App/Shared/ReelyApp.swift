import SwiftUI
import ReelyCore

@main
struct ReelyApp: App {
    @State private var store: ReelyStore

    init() {
        let defaults = DefaultsStore()
        let clientId = ReelyStore.clientId(defaults)
        #if os(tvOS)
        let identity = PlexIdentity(clientId: clientId, version: ReelyApp.version, platform: "tvOS", device: "Apple TV", deviceName: "Reely on Apple TV")
        #else
        let identity = PlexIdentity(clientId: clientId, version: ReelyApp.version, platform: "iOS",
                                    device: UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone",
                                    deviceName: "Reely on \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")")
        #endif
        let arguments = ProcessInfo.processInfo.arguments
        if let at = arguments.firstIndex(of: "-demo") {
            // A stand-in server for screenshots and tests; see DemoTransport.
            let scene = arguments.indices.contains(at + 1) ? arguments[at + 1] : "home"
            let transport = DemoTransport(scene: scene)
            ImageLoader.shared.transport = transport
            let api = PlexAPI(http: Http(transport: transport), identity: identity, plexTv: DemoTransport.server, discover: DemoTransport.server)
            var kept = ["signin", "code"].contains(scene) ? [:] : ["plexToken": "demo"]
            // Signed in to a stand-in live TV provider, for the Live TV scenes.
            if ["live", "guide", "channel", "overguide"].contains(scene) {
                kept["xtream"] = #"{"base": "\#(DemoTransport.server)", "username": "demo", "password": "demo"}"#
            }
            let secrets = MemoryStore(kept)
            // Connected to a stand-in Reely too, for Requests and Home's Trending and Popular.
            let kept2 = ["signin", "code"].contains(scene) ? [:] : ["reelyUrl": DemoTransport.server]
            _store = State(initialValue: ReelyStore(api: api, store: MemoryStore(kept2), secrets: secrets))
        } else {
            _store = State(initialValue: ReelyStore(api: PlexAPI(identity: identity), store: defaults, secrets: KeychainStore()))
        }
    }

    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(\.accent, Accent.of(store.prefs.accent))
                .tint(Accent.of(store.prefs.accent).swiftColor)
                .preferredColorScheme(.dark)
                .task {
                    await store.start()
                    // Where a screenshot asks to be: a page opened, as somebody would open it.
                    let args = ProcessInfo.processInfo.arguments
                    if args.contains("code") { store.startLink() }
                    if args.contains("movie") { store.navigate(.detail(ratingKey: "m1", serverBase: nil)) }
                    if args.contains("show") { store.navigate(.detail(ratingKey: "show-northbound", serverBase: nil)) }
                    if args.contains("settings") { store.navigate(.settings) }
                    if args.contains("search") { store.navigate(.search); await store.setQuery("orbit") }
                    if args.contains("profiles") { NotificationCenter.default.post(name: .chooseProfile, object: nil) }
                    if args.contains("live") || args.contains("guide") || args.contains("channel") || args.contains("overguide") {
                        store.navigate(.live)
                        await store.loadLive()
                        if let first = store.live.categories.first { await store.openCategory(first) }
                        if args.contains("channel") || args.contains("overguide") { store.watchChannel(0) }
                    }
                    if args.contains("requests") { store.navigate(.requests) }
                    if args.contains("request") {
                        store.navigate(.requests)
                        store.navigate(.requestTitle(RequestTitle(kind: "show", tmdbId: 800, title: "Saltwater", year: 2024, poster: DemoTransport.server + "/photo/reely-800")))
                    }
                    if args.contains("library") { store.navigate(.library(kind: "movie")); store.setLibraryView("movie", .grid) }
                }
        }
    }
}

/// Every screen, by where the app is; the frame around them is the device's own.
struct RootView: View {
    @Environment(ReelyStore.self) private var store
    @State private var choosingProfile = false
    @State private var touring = false
    private let isDemo = ProcessInfo.processInfo.arguments.contains("-demo")

    var body: some View {
        ZStack {
            Color.ink.ignoresSafeArea()
                .onReceive(NotificationCenter.default.publisher(for: .chooseProfile)) { _ in choosingProfile = true }
                .onReceive(NotificationCenter.default.publisher(for: .takeTour)) { _ in touring = true }
            #if os(tvOS)
            TVRoot()
            #else
            PhoneRoot()
            #endif
            // Signed in to a Plex Home of several: who's watching, once.
            if store.askWho || choosingProfile { ProfilesView { store.askedWho(); choosingProfile = false }.zIndex(2) }
            // What's playing covers everything, as on the Fire TV.
            if store.playing != nil { PlayerView().transition(.opacity).zIndex(1) }
            if store.live.watching != nil { LivePlayerView().transition(.opacity).zIndex(1.5) }
            #if os(tvOS)
            // The first time in, signed in: how to get around with the remote.
            if (touring || !store.prefs.tourSeen) && store.plex.isConnected && !store.askWho && store.playing == nil && !isDemo {
                TourView { store.prefs.tourSeen = true; touring = false }.zIndex(2.5)
            }
            #endif
            // A reminder from the guide, over whatever's on.
            if let due = store.live.due {
                ReminderNotice(due: due)
                    .padding(.horizontal, pageMargin).padding(.top, dp(24))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(3)
            }
        }
        .animation(.easeOut(duration: 0.25), value: store.live.due)
        #if os(tvOS)
        .modifier(ScreensaverHost())
        #endif
        .task {
            // Reminders come due whatever's on screen, as on the Fire TV.
            while !Task.isCancelled {
                store.checkReminders()
                try? await Task.sleep(nanoseconds: 20_000_000_000)
            }
        }
    }
}

/// What a route shows, on either device.
struct RouteContent: View {
    @Environment(ReelyStore.self) private var store
    let route: Route

    var body: some View {
        if !store.plex.isConnected {
            SignInView()
        } else {
            switch route {
            case .home: HomeView()
            case .library(let kind): LibraryView(kind: kind).id(kind)
            case .detail(let key, let base): DetailView(ratingKey: key, serverBase: base).id(key)
            case .settings: SettingsView()
            case .search: SearchView()
            case .live: LiveView()
            case .requests: RequestsView()
            case .requestTitle(let title): RequestTitleView(title: title).id(title.key)
            case .person, .collection, .playlist: ListPageView(route: route).id(route)
            default: NotYet(route: route)
            }
        }
    }
}

/// A place still being built, said plainly.
struct NotYet: View {
    let route: Route
    var body: some View {
        VStack(spacing: dp(12)) {
            Text("On its way").font(Typeface.headline).foregroundStyle(Color.chalk)
            Text("This part of Reely for iPhone, iPad and Apple TV is still being built.")
                .font(Typeface.body).foregroundStyle(Color.muted).multilineTextAlignment(.center)
        }
        .padding(pageMargin)
        .padding(.top, topInset)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension Notification.Name {
    /// Settings' profile row, or the top bar's: the picker over everything.
    static let chooseProfile = Notification.Name("reely.chooseProfile")
    /// Settings' About: the remote tour again.
    static let takeTour = Notification.Name("reely.takeTour")
}
