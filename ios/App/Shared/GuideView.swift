import SwiftUI
import AVFoundation
import ReelyCore

#if os(tvOS)
/// Over a channel that's playing, the guide's presses are the overlay's: OK watches, a
/// held OK has the channel's menu.
struct GuideOverlayActions {
    let press: (Int, XtreamChannel, Programme?) -> Void
    let isFavorite: (XtreamChannel) -> Bool
}

/**
 * The guide as a grid, as on the Fire TV: channels down, time across, from the half hour
 * before the last one for two and a half hours. OK on what's on watches it; on something
 * that's over, from the archive, where the channel keeps one; on something to come, a
 * reminder. With Guide preview on, the highlighted channel plays in the corner.
 */
struct GuideGrid: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    let channels: [XtreamChannel]
    let categoryName: String
    let now: Int
    var overlay: GuideOverlayActions? = nil
    /// The channel the cursor starts on: the one playing, under the overlay.
    var landOn: Int? = nil
    @FocusState private var focus: Cell?
    @State private var preview = AVPlayer()
    @State private var previewing: Int?

    struct Cell: Hashable { let streamId: Int; let start: Int }

    private struct Segment: Identifiable {
        let id: Int
        let gap: CGFloat
        let width: CGFloat
        let programme: Programme?
    }

    private let channelWidth: CGFloat = 220
    private let gutter: CGFloat = 16
    private let rowHeight: CGFloat = 96
    private var gridWidth: CGFloat { 1920 - 2 * pageMargin - channelWidth - gutter }
    private var perMinute: CGFloat { gridWidth / 150 }
    private var start: Int { now / 1800 * 1800 - 1800 }
    private var end: Int { start + 150 * 60 }

    private func x(_ t: Int) -> CGFloat { CGFloat(min(max(t, start), end) - start) / 60 * perMinute }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 40) {
                about.frame(maxWidth: .infinity, alignment: .leading)
                if overlay == nil && store.prefs.guidePreview {
                    VideoSurface(player: preview)
                        .frame(width: 480, height: 270)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
            .frame(height: overlay == nil ? 270 : 130, alignment: .top)
            .padding(.horizontal, pageMargin)
            timeline.padding(.leading, pageMargin + channelWidth + gutter)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(channels.enumerated()), id: \.element.streamId) { index, channel in
                            row(index, channel).id(channel.streamId)
                        }
                    }
                    .overlay(alignment: .topLeading) {
                        Rectangle().fill(accent.swiftColor).frame(width: 3)
                            .offset(x: channelWidth + gutter + x(now) - 1)
                            .allowsHitTesting(false)
                    }
                    .padding(.horizontal, pageMargin).padding(.bottom, 60)
                }
                .scrollClipDisabled()
                .onAppear {
                    if let landOn { proxy.scrollTo(landOn, anchor: .center) }
                    // Over a channel, the cursor starts on it, not on the categories above.
                    if overlay != nil {
                        Task { @MainActor in
                            try? await Task.sleep(nanoseconds: 150_000_000)
                            focus = landing
                        }
                    }
                }
            }
            .defaultFocus($focus, landing)
        }
        .task(id: channels.map(\.streamId)) { await store.loadTable(Array(channels.prefix(30))) }
        .task(id: focusedChannel?.streamId) { await playPreview() }
        .onDisappear { preview.replaceCurrentItem(with: nil) }
    }

    // MARK: What's highlighted

    /// What the cursor's on; before it's reached the grid, where it will land.
    private var shown: Cell? { focus ?? landing }

    private var focusedChannel: XtreamChannel? {
        guard let shown else { return nil }
        return channels.first { $0.streamId == shown.streamId }
    }

    private var focusedProgramme: Programme? {
        guard let shown, let channel = focusedChannel else { return nil }
        return store.listing(for: channel).first { $0.start == shown.start }
    }

    private var about: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(categoryName.uppercased()).font(Typeface.label).kerning(1).foregroundStyle(Color.faint)
            if let channel = focusedChannel {
                let p = focusedProgramme
                Text(p?.title ?? channel.name).font(Typeface.headline).foregroundStyle(Color.chalk).lineLimit(1)
                Text([channel.name, p.map { "\(liveTime($0.start)) – \(liveTime($0.stop))" }, hint(channel, p)].compactMap { $0 }.joined(separator: "  ·  "))
                    .font(Typeface.meta).foregroundStyle(Color.muted).lineLimit(1)
                if overlay == nil, let text = p?.description, !text.isEmpty {
                    Text(text).font(Typeface.meta).foregroundStyle(Color.muted).lineLimit(3)
                }
            } else {
                Text("Loading the guide…").font(Typeface.meta).foregroundStyle(Color.muted)
            }
        }
    }

    private func hint(_ channel: XtreamChannel, _ p: Programme?) -> String {
        if let p, p.stop <= now { return store.canCatchUp(channel, p, now: now) ? "OK to watch it again" : "Over, and not in this channel's archive" }
        // Over a channel, as on the Fire TV: OK watches; a reminder is in the held OK's menu.
        if overlay != nil { return "OK to watch  ·  hold OK for more" }
        guard let p, !p.isOn(at: now) else { return "OK to watch" }
        return store.hasReminder(channel, p) ? "Reminder set  ·  OK to cancel it" : "OK to be reminded when it starts"
    }

    /// Where the cursor starts: what's on now, on the channel playing or the first.
    private var landing: Cell? {
        guard let channel = channels.first(where: { $0.streamId == landOn }) ?? channels.first else { return nil }
        let on = segments(channel).first { $0.programme?.isOn(at: now) ?? true }
        return Cell(streamId: channel.streamId, start: on?.programme?.start ?? segments(channel).first?.programme?.start ?? 0)
    }

    private func playPreview() async {
        guard overlay == nil, store.prefs.guidePreview, let channel = focusedChannel else { return }
        // A moment after the cursor stops on a channel, not for each one it passes.
        try? await Task.sleep(nanoseconds: 800_000_000)
        guard !Task.isCancelled, previewing != channel.streamId, let url = store.channelUrl(channel).flatMap(URL.init(string:)) else { return }
        previewing = channel.streamId
        preview.isMuted = true
        preview.replaceCurrentItem(with: AVPlayerItem(url: url))
        preview.play()
    }

    // MARK: The grid

    private var timeline: some View {
        ZStack(alignment: .leading) {
            ForEach(0..<6, id: \.self) { i in
                Text(liveTime(start + i * 1800)).font(Typeface.label).foregroundStyle(Color.muted)
                    .offset(x: x(start + i * 1800))
            }
        }
        .frame(width: gridWidth, alignment: .leading)
    }

    private func segments(_ channel: XtreamChannel) -> [Segment] {
        let listing = store.listing(for: channel).filter { $0.stop > start && $0.start < end }.sorted { $0.start < $1.start }
        guard !listing.isEmpty else { return [Segment(id: 0, gap: 0, width: gridWidth, programme: nil)] }
        var out: [Segment] = []
        var cursor: CGFloat = 0
        for p in listing {
            let a = max(x(p.start), cursor), b = x(p.stop)
            guard b - a >= 6 else { continue }
            out.append(Segment(id: out.count, gap: a - cursor, width: b - a, programme: p))
            cursor = b
        }
        return out
    }

    private func row(_ index: Int, _ channel: XtreamChannel) -> some View {
        let favorite = store.live.favorites.contains(channel.streamId)
        let current = focus?.streamId == channel.streamId
        return HStack(spacing: gutter) {
            ChannelLogo(channel: channel)
                .overlay(alignment: .topTrailing) {
                    if favorite { Text("♥").font(Typeface.label).foregroundStyle(Color.chalk).padding(6) }
                }
                .overlay(RoundedRectangle(cornerRadius: dp(8)).strokeBorder(Color.chalk.opacity(current ? 0.8 : 0), lineWidth: 2))
                .frame(width: channelWidth, height: rowHeight)
            HStack(spacing: 0) {
                ForEach(segments(channel)) { segment in
                    if segment.gap > 0 { Color.clear.frame(width: segment.gap, height: rowHeight) }
                    cell(index, channel, segment)
                }
            }
            .frame(width: gridWidth, alignment: .leading)
        }
    }

    @ViewBuilder
    private func cell(_ index: Int, _ channel: XtreamChannel, _ segment: Segment) -> some View {
        let p = segment.programme
        let replay = p.map { $0.stop <= now && store.canCatchUp(channel, $0, now: now) } ?? false
        let reminded = p.map { store.hasReminder(channel, $0) } ?? false
        let kind: ProgrammeLabel.Kind = p == nil || p!.isOn(at: now) ? .now : p!.stop <= now ? (replay ? .replay : .past) : .later
        let slot = p.map { "\(liveTime($0.start)) – \(liveTime($0.stop))" }
        let button = Button { press(index, channel, p) } label: {
            ProgrammeLabel(title: p?.title ?? "No guide data",
                           slot: slot.map { replay ? "Watch again  ·  \($0)" : reminded ? "Reminder set  ·  \($0)" : $0 },
                           kind: kind, reminded: reminded)
                .frame(width: max(6, segment.width - 6), height: rowHeight)
        }
        .buttonStyle(PlainFocusStyle())
        .focused($focus, equals: Cell(streamId: channel.streamId, start: p?.start ?? 0))
        .padding(.trailing, 6)
        if let overlay {
            button.contextMenu {
                Button("Watch this channel") { overlay.press(index, channel, nil) }
                if let p, p.start > now {
                    Button(store.hasReminder(channel, p) ? "Cancel the reminder" : "Remind me") { store.toggleReminder(channel, p) }
                }
                Button(overlay.isFavorite(channel) ? "Remove from Favorites" : "Add to Favorites") { store.toggleFavorite(channel) }
            }
        } else {
            button
        }
    }

    private func press(_ index: Int, _ channel: XtreamChannel, _ p: Programme?) {
        if let overlay { overlay.press(index, channel, p); return }
        guard let p, !p.isOn(at: now) else { store.watchChannel(index); return }
        if p.stop <= now {
            if store.canCatchUp(channel, p, now: now) { store.playCatchUp(index, p) }
        } else {
            store.toggleReminder(channel, p)
        }
    }
}

/// A programme in the grid: its title and times, lit with the cursor on it.
private struct ProgrammeLabel: View {
    enum Kind { case now, past, replay, later }
    let title: String
    let slot: String?
    let kind: Kind
    let reminded: Bool
    @Environment(\.isFocused) private var focused
    @Environment(\.accent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(Typeface.geist(15, .semibold)).lineLimit(1)
                .foregroundStyle(focused ? Color.ink : kind == .past ? Color.faint : Color.chalk)
            if let slot {
                Text(slot).font(Typeface.label).lineLimit(1)
                    .foregroundStyle(focused ? Color.ink.opacity(0.7) : Color.muted)
            }
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(focused ? Color.chalk : kind == .now ? Color.surfaceHigh : Color.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(accent.swiftColor, lineWidth: reminded && !focused ? 2 : 0))
        .animation(.easeOut(duration: 0.12), value: focused)
    }
}

/**
 * The guide over a channel that keeps playing behind it, as on the Fire TV: down from the
 * channel. The categories are a row above; another category's channels are browsed here
 * without changing what's playing until one is chosen. Back closes it.
 */
struct GuideOverlay: View {
    @Environment(ReelyStore.self) private var store
    let playing: XtreamChannel
    let now: Int
    let onClose: () -> Void
    @State private var category: XtreamCategory?
    @State private var channels: [XtreamChannel] = []
    @State private var loading = false
    @State private var started = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            let categories = store.shownCategories
            if categories.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(categories) { c in Pill(title: c.name, on: c.id == category?.id) { choose(c) } }
                    }
                    .padding(.horizontal, pageMargin).padding(.vertical, 12)
                }
                .scrollClipDisabled()
                .focusSection()
            }
            if !channels.isEmpty {
                GuideGrid(channels: Array(channels.prefix(60)), categoryName: category?.name ?? "", now: now,
                          overlay: GuideOverlayActions(press: watch, isFavorite: { store.live.favorites.contains($0.streamId) }),
                          landOn: channels == store.live.channels ? playing.streamId : nil)
                    .id(category?.id)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text((category?.name ?? "").uppercased()).font(Typeface.label).kerning(1).foregroundStyle(Color.faint)
                    if loading { ProgressView() } else {
                        Text("Nothing in this category  ·  pick another above").font(Typeface.meta).foregroundStyle(Color.muted)
                    }
                }
                .padding(.horizontal, pageMargin)
                Spacer()
            }
        }
        .padding(.top, 60)
        .background(LinearGradient(colors: [Color.ink.opacity(0.75), Color.ink.opacity(0.95)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        .onAppear {
            guard !started else { return }
            started = true
            category = store.live.category
            channels = store.live.channels
        }
        .onExitCommand(perform: onClose)
    }

    private func choose(_ c: XtreamCategory) {
        guard c.id != category?.id else { return }
        category = c
        channels = []
        loading = true
        Task {
            let found = (try? await store.channelsOf(c)) ?? []
            guard category == c else { return }
            channels = found
            loading = false
            await store.loadGuide(Array(found.prefix(40)))
        }
    }

    private func watch(_ index: Int, _ channel: XtreamChannel, _ p: Programme?) {
        let over = p.map { $0.stop <= now } ?? false
        if over, let p, !store.canCatchUp(channel, p, now: now) { return }
        onClose()
        if channels != store.live.channels {
            store.watchIn(category, channels, index: index, programme: over ? p : nil)
        } else if over, let p {
            store.playCatchUp(index, p)
        } else {
            store.watchChannel(index)
        }
    }
}
#endif
