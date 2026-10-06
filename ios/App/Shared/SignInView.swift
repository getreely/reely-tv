import SwiftUI
import ReelyCore

/**
 * Signing in, as on the Fire TV: a code to type at plex.tv/link, or a QR code to scan. On
 * a phone, Plex's own page opens in the browser instead, with the code as well.
 */
struct SignInView: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.openURL) private var openURL
    @Environment(\.accent) private var accent

    var body: some View {
        let plex = store.plex
        ScrollView {
            VStack(alignment: .leading, spacing: dp(16)) {
                if plex.token != nil && plex.linkCode == nil {
                    // Signed in, with no server to show yet.
                    Text(plex.finding ? "Finding a Plex server" : "Couldn't reach a Plex server").font(Typeface.headline).foregroundStyle(Color.chalk)
                    Text(plex.finding
                         ? "You're signed in. Looking for the servers you can use, your own or shared with you, at home first, then over the internet."
                         : "Any server this account owns or has been given access to will do. Make sure it's on. Reely keeps looking, or try again now.")
                        .font(Typeface.body).foregroundStyle(Color.muted)
                    if let error = plex.error, !plex.finding { ErrorNote(text: error) }
                    if plex.finding {
                        ProgressView().tint(accent.swiftColor)
                    } else {
                        PrimaryButton(title: "Try again") { store.retryConnect() }
                        SecondaryButton(title: "Use a different Plex account") { store.signOut() }
                    }
                } else {
                    Text("Sign in to watch your library").font(Typeface.display).foregroundStyle(Color.chalk)
                    Text("Your movies and shows from Plex, including libraries shared with you.").font(Typeface.body).foregroundStyle(Color.muted)
                    if let error = plex.error { ErrorNote(text: error) }
                    if let code = plex.linkCode {
                        codePanel(code: code, link: plex.linkUrl)
                    } else {
                        PrimaryButton(title: "Sign in with Plex") { store.startLink() }
                    }
                }
            }
            .frame(maxWidth: dp(640), alignment: .leading)
            .padding(pageMargin)
            .padding(.top, topInset)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onChange(of: store.plex.linkUrl) { _, url in
            #if os(iOS)
            // A phone signs in in its browser: Plex's page opens once per code.
            if let url, let address = URL(string: url), !ProcessInfo.processInfo.arguments.contains("-demo") { openURL(address) }
            #endif
        }
    }

    @ViewBuilder
    private func codePanel(code: String, link: String?) -> some View {
        #if os(tvOS)
        HStack(alignment: .top, spacing: dp(40)) {
            VStack(alignment: .leading, spacing: dp(12)) {
                Text("On your phone or computer, go to").font(Typeface.body).foregroundStyle(Color.muted)
                Text("plex.tv/link").font(Typeface.headline).foregroundStyle(Color.chalk)
                Text("and enter").font(Typeface.body).foregroundStyle(Color.muted)
                Text(code).font(Typeface.geist(48, .bold)).kerning(dp(8)).foregroundStyle(Color.chalk)
                    .accessibilityIdentifier("code")
                waiting
            }
            if let link {
                VStack(spacing: dp(8)) {
                    QRCode(text: link).frame(width: dp(150), height: dp(150))
                    Text("Or scan this with your phone").font(Typeface.label).foregroundStyle(Color.muted)
                }
            }
        }
        #else
        VStack(alignment: .leading, spacing: dp(12)) {
            Text("Finish signing in on Plex's page in your browser, then come back here.").font(Typeface.body).foregroundStyle(Color.chalk)
            if let link, let url = URL(string: link) {
                PrimaryButton(title: "Open Plex sign-in") { openURL(url) }
            }
            Text("Or, on another device, go to plex.tv/link and enter").font(Typeface.meta).foregroundStyle(Color.muted)
            Text(code).font(Typeface.geist(34, .bold)).kerning(6).foregroundStyle(Color.chalk).accessibilityIdentifier("code")
            waiting
        }
        #endif
    }

    private var waiting: some View {
        HStack(spacing: dp(10)) {
            ProgressView().tint(accent.swiftColor)
            Text("Waiting for you to sign in…").font(Typeface.meta).foregroundStyle(Color.muted)
            Spacer(minLength: 0)
            Pill(title: "Cancel", on: false) { store.cancelLink() }
        }
    }
}

struct PrimaryButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.accent) private var accent
    var body: some View {
        Button(action: action) {
            Text(title).font(Typeface.meta).foregroundStyle(accent.onColor)
                .padding(.vertical, dp(12)).padding(.horizontal, dp(22))
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: dp(10)).fill(accent.swiftColor))
                .modifier(FocusRing(shape: RoundedRectangle(cornerRadius: dp(10))))
        }
        .buttonStyle(CardStyle())
    }
}

struct SecondaryButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(Typeface.meta).foregroundStyle(Color.chalk)
                .padding(.vertical, dp(12)).padding(.horizontal, dp(22))
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: dp(10)).fill(Color.surfaceHigh))
                .modifier(FocusRing(shape: RoundedRectangle(cornerRadius: dp(10))))
        }
        .buttonStyle(CardStyle())
    }
}

/// Something went wrong, in the colour kept for problems.
struct ErrorNote: View {
    let text: String
    var body: some View {
        Text(text).font(Typeface.meta).foregroundStyle(Color.danger)
            .padding(dp(12)).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(10)).fill(Color.danger.opacity(0.12)))
    }
}
