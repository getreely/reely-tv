import SwiftUI
import ReelyCore

/**
 * The top of Home on iPhone and iPad: what you're watching and what's new, a page each,
 * swiped across. The backdrop runs under the bar; the title's logo, what it is, and Play.
 */
struct PhoneHero: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    let items: [PlexItem]
    @State private var page = 0

    var body: some View {
        if items.isEmpty {
            Color.clear.frame(height: 110)
        } else {
            TabView(selection: $page) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, item in slide(item).tag(i) }
            }
            .tabViewStyle(.page(indexDisplayMode: items.count > 1 ? .always : .never))
            .frame(height: 540)
        }
    }

    private func slide(_ item: PlexItem) -> some View {
        ZStack(alignment: .bottom) {
            Color.clear
                .overlay(RemoteImage(url: store.imageUrl(item.serverBase, item.art ?? item.thumb, width: 1280, height: 720)))
                .clipped()
                .overlay(LinearGradient(stops: [.init(color: Color.ink.opacity(0.35), location: 0), .init(color: .clear, location: 0.25),
                                                .init(color: .clear, location: 0.45), .init(color: Color.ink.opacity(0.85), location: 0.78),
                                                .init(color: Color.ink, location: 1)], startPoint: .top, endPoint: .bottom))
            VStack(spacing: 12) {
                if let logo = store.logoUrl(item.serverBase, item.logo) {
                    RemoteImage(url: logo, contentMode: .fit, background: .clear).frame(maxWidth: 260, maxHeight: 90)
                } else {
                    Text(item.rowTitle).font(Typeface.geist(32, .bold)).foregroundStyle(Color.chalk)
                        .multilineTextAlignment(.center).lineLimit(2).padding(.horizontal, 24)
                }
                Text(([item.episodeLine] + item.qualities.prefix(2).map { Optional($0) }).compactMap { $0 }.joined(separator: "  ·  "))
                    .font(Typeface.geist(13, .medium)).foregroundStyle(Color.chalk.opacity(0.75)).lineLimit(1)
                if let progress = item.resumeFraction {
                    ProgressView(value: progress).tint(accent.swiftColor).frame(width: 120)
                }
                HStack(spacing: 12) {
                    Button { Task { await store.play(item, resume: true) } } label: {
                        Label(item.viewOffsetMs > 0 ? "Resume" : "Play", systemImage: "play.fill")
                            .font(Typeface.geist(16, .semibold)).foregroundStyle(Color.ink)
                            .frame(width: 148, height: 46).background(Capsule().fill(Color.chalk))
                    }
                    Button { openPage(store, item) } label: {
                        Label("Details", systemImage: "info.circle")
                            .font(Typeface.geist(16, .semibold)).foregroundStyle(Color.chalk)
                            .frame(width: 148, height: 46).background(.ultraThinMaterial, in: Capsule())
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 46)
        }
        .contentShape(Rectangle())
        .onTapGesture { openPage(store, item) }
    }
}
