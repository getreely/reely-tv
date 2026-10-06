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
            let secrets = MemoryStore(["signin", "code"].contains(scene) ? [:] : ["plexToken": "demo"])
            _store = State(initialValue: ReelyStore(api: api, store: MemoryStore(), secrets: secrets))
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
                    if args.contains("library") { store.navigate(.library(kind: "movie")); store.setLibraryView("movie", .grid) }
                }
        }
    }
}

/// Every screen, by where the app is; the frame around them is the device's own.
struct RootView: View {
    @Environment(ReelyStore.self) private var store

    var body: some View {
        ZStack {
            Color.ink.ignoresSafeArea()
            #if os(tvOS)
            TVRoot()
            #else
            PhoneRoot()
            #endif
            // What's playing covers everything, as on the Fire TV.
            if store.playing != nil { PlayerView().transition(.opacity).zIndex(1) }
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
