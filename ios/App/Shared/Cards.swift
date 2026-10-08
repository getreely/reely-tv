import SwiftUI
import ReelyCore

/**
 * How a card shows it has the cursor, as on the Fire TV: a white ring, a glow in the
 * accent colour, and a lift. On a phone, a press dims it instead.
 */
struct CardStyle: ButtonStyle {
    var corner: CGFloat = dp(10)
    func makeBody(configuration: Configuration) -> some View { CardBody(configuration: configuration, corner: corner) }

    private struct CardBody: View {
        let configuration: ButtonStyleConfiguration
        let corner: CGFloat
        @Environment(\.isFocused) private var focused
        @Environment(\.accent) private var accent

        var body: some View {
            configuration.label
                #if os(tvOS)
                .scaleEffect(focused ? 1.07 : 1)
                .shadow(color: focused ? accent.swiftColor.opacity(0.45) : .clear, radius: focused ? 24 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
                #else
                .opacity(configuration.isPressed ? 0.7 : 1)
                #endif
        }
    }
}

/// The picture's frame: the ring goes round the picture itself, never cut off.
struct Artwork: View {
    let url: URL?
    let aspect: CGFloat
    var corner: CGFloat = dp(10)
    @Environment(\.isFocused) private var focused

    var body: some View {
        RemoteImage(url: url)
            .aspectRatio(aspect, contentMode: .fill)
            .clipShape(RoundedRectangle(cornerRadius: corner))
            .overlay(RoundedRectangle(cornerRadius: corner).strokeBorder(Color.white, lineWidth: focused ? dp(3) : 0))
    }
}

/// How far through it is: a bar along the bottom of the picture.
struct Progress: View {
    let fraction: Double
    @Environment(\.accent) private var accent
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.black.opacity(0.55))
                Capsule().fill(accent.swiftColor).frame(width: g.size.width * fraction)
            }
        }
        .frame(height: dp(4))
    }
}

/// A poster: the picture, its name and a line under it; a count or a tick on it.
struct PosterCard: View {
    let title: String
    let subtitle: String?
    let url: URL?
    var progress: Double? = nil
    var watched = false
    var badge: Int? = nil
    /// A word on it: "In library", "Requested" and the like, in Requests.
    var tag: String? = nil
    /// As wide as its column in a grid, rather than a row's fixed width.
    var fill = false
    let action: () -> Void
    @Environment(\.accent) private var accent

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(6)) {
                Artwork(url: url, aspect: 2 / 3)
                    .overlay(alignment: .topLeading) {
                        if let tag {
                            Text(tag).font(Typeface.geist(11, .semibold)).foregroundStyle(accent.onColor)
                                .padding(.horizontal, dp(8)).padding(.vertical, dp(4))
                                .background(Capsule().fill(accent.swiftColor)).padding(dp(6))
                        }
                    }
                    .overlay(alignment: .bottom) {
                        if let progress { Progress(fraction: progress).padding(dp(8)) }
                    }
                    .overlay(alignment: .topTrailing) {
                        if let badge, badge > 1 {
                            Text("\(badge)").font(Typeface.label).foregroundStyle(accent.onColor)
                                .frame(minWidth: dp(26), minHeight: dp(26)).background(Circle().fill(accent.swiftColor)).padding(dp(6))
                        } else if watched {
                            Image(systemName: "checkmark").font(.system(size: dp(11), weight: .bold)).foregroundStyle(accent.onColor)
                                .frame(width: dp(24), height: dp(24)).background(Circle().fill(accent.swiftColor)).padding(dp(6))
                        }
                    }
                Text(title).font(Typeface.meta).foregroundStyle(Color.chalk).lineLimit(1)
                if let subtitle { Text(subtitle).font(Typeface.label).foregroundStyle(Color.muted).lineLimit(1) }
            }
            .frame(width: fill ? nil : posterWidth)
            .frame(maxWidth: fill ? .infinity : nil, alignment: .leading)
        }
        .buttonStyle(CardStyle())
    }
}

/// A wide card, for Continue Watching: the scene, where it was left, the show and episode.
struct WideCard: View {
    @Environment(\.accent) private var accent
    let title: String
    let subtitle: String?
    let url: URL?
    var progress: Double? = nil
    /// Seen: the check in a circle, top right, as the posters and the Fire TV's episodes have it.
    var watched = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(6)) {
                Artwork(url: url, aspect: 16 / 9)
                    .overlay(alignment: .bottom) {
                        if let progress { Progress(fraction: progress).padding(dp(8)) }
                    }
                    .overlay(alignment: .topTrailing) {
                        if watched {
                            Image(systemName: "checkmark").font(.system(size: dp(11), weight: .bold)).foregroundStyle(accent.onColor)
                                .frame(width: dp(24), height: dp(24)).background(Circle().fill(accent.swiftColor)).padding(dp(6))
                        }
                    }
                Text(title).font(Typeface.meta).foregroundStyle(Color.chalk).lineLimit(1)
                if let subtitle, !subtitle.isEmpty { Text(subtitle).font(Typeface.label).foregroundStyle(Color.muted).lineLimit(1) }
            }
            .frame(width: wideWidth)
        }
        .buttonStyle(CardStyle())
    }
}

var posterWidth: CGFloat {
    #if os(tvOS)
    return 220
    #else
    return 116
    #endif
}

var wideWidth: CGFloat {
    #if os(tvOS)
    return 400
    #else
    return 220
    #endif
}

/// A titled row that scrolls across.
struct CardRow<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            Text(title).font(Typeface.rowTitle).foregroundStyle(Color.chalk).padding(.horizontal, pageMargin)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: dp(14)) { content() }
                    .padding(.horizontal, pageMargin)
                    // Room for the lift and glow of the card with the cursor.
                    .padding(.vertical, dp(12))
            }
            #if os(tvOS)
            .scrollClipDisabled()
            #endif
        }
    }
}

extension PlexItem {
    /// "S1 · E2  ·  Episode 2" for an episode, else its caption.
    var episodeLine: String? {
        guard type == "episode" else { return caption }
        return [caption, title].compactMap { $0 }.joined(separator: "  ·  ")
    }
}

/// The Fire TV's white ring round whatever has the cursor, drawn inside its shape so it's never cut off.
struct FocusRing<S: InsettableShape>: ViewModifier {
    let shape: S
    @Environment(\.isFocused) private var focused
    func body(content: Content) -> some View {
        content.overlay(shape.strokeBorder(Color.white, lineWidth: focused ? dp(3) : 0))
    }
}

/// A letter of the A–Z jump.
struct LetterLabel: View {
    let letter: String
    @Environment(\.isFocused) private var focused
    var body: some View {
        Text(letter).font(Typeface.label)
            .foregroundStyle(focused ? Color.ink : Color.muted)
            .frame(minWidth: dp(28), minHeight: dp(28))
            .background(Circle().fill(focused ? Color.chalk : Color.clear))
    }
}
