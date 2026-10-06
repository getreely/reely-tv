import SwiftUI
import ReelyCore

/**
 * Who's watching: the people in the Plex Home, a PIN for those who have one, as on the
 * Fire TV. Each profile has its own libraries, watch history and Continue Watching.
 */
struct ProfilesView: View {
    @Environment(ReelyStore.self) private var store
    let onDone: () -> Void
    @State private var asking: PlexHomeUser?
    @State private var pin = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: dp(28)) {
            Text(asking == nil ? "Who's watching?" : "Enter \(asking!.title)'s PIN").font(Typeface.display).foregroundStyle(Color.chalk)
            if let user = asking {
                SecureField("PIN", text: $pin)
                    .font(Typeface.headline).multilineTextAlignment(.center)
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
                    .frame(maxWidth: dp(260)).padding(dp(12))
                    .background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.surfaceRaised))
                    .onSubmit { go(user, pin: pin) }
                    .onChange(of: pin) { _, p in if p.count == 4 { go(user, pin: p) } }
                if let error { Text(error).font(Typeface.meta).foregroundStyle(Color.danger) }
                HStack(spacing: dp(10)) {
                    PrimaryButton(title: busy ? "Switching…" : "OK") { go(user, pin: pin) }.frame(width: dp(180))
                    SecondaryButton(title: "Back") { asking = nil; pin = ""; error = nil }.frame(width: dp(180))
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: dp(28)) {
                        ForEach(store.plex.homeUsers, id: \.uuid) { user in
                            Button {
                                if user.uuid == store.plex.user?.uuid { onDone() }
                                else if user.protected { asking = user } else { go(user, pin: nil) }
                            } label: { ProfileTile(user: user, current: user.uuid == store.plex.user?.uuid) }
                            .buttonStyle(CardStyle())
                        }
                    }
                    .padding(dp(24))
                }
                if let error { Text(error).font(Typeface.meta).foregroundStyle(Color.danger) }
            }
        }
        .padding(pageMargin)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.ink.ignoresSafeArea())
        .task { if store.plex.homeUsers.isEmpty { await store.loadProfiles() } }
    }

    private func go(_ user: PlexHomeUser, pin: String?) {
        guard !busy else { return }
        busy = true
        Task {
            error = await store.switchUser(user, pin: pin)
            busy = false
            if error == nil { onDone() } else { self.pin = "" }
        }
    }
}

private struct ProfileTile: View {
    let user: PlexHomeUser
    let current: Bool
    @Environment(\.isFocused) private var focused
    @Environment(\.accent) private var accent

    var body: some View {
        VStack(spacing: dp(10)) {
            ZStack {
                Circle().fill(Color.surfaceHigh)
                if let thumb = user.thumb, let url = URL(string: thumb) { RemoteImage(url: url).clipShape(Circle()) }
                else { Text(String(user.title.prefix(1)).uppercased()).font(Typeface.display).foregroundStyle(Color.chalk) }
            }
            .frame(width: dp(110), height: dp(110))
            .overlay(Circle().strokeBorder(focused ? Color.white : current ? accent.swiftColor : .clear, lineWidth: dp(3)))
            HStack(spacing: dp(4)) {
                Text(user.title).font(Typeface.meta).foregroundStyle(Color.chalk)
                if user.protected { Image(systemName: "lock.fill").font(.system(size: dp(11))).foregroundStyle(Color.muted) }
            }
        }
    }
}
