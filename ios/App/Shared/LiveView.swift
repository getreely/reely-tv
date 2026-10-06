import SwiftUI
import ReelyCore

/**
 * Live TV, as on the Fire TV: signing in to a provider, then its categories (Favorites and
 * Recently watched first), then a category's channels with what's on now and next. On
 * Apple TV the guide's grid is a press away; on a phone, as on the Android phone app, a
 * hold on a channel has its schedule.
 */
struct LiveView: View {
    @Environment(ReelyStore.self) private var store

    var body: some View {
        Group {
            if !store.live.isSignedIn {
                LiveSignInView()
            } else if let category = store.live.category {
                ChannelsView(category: category)
            } else {
                CategoriesView()
            }
        }
        .task(id: store.live.credentials) { await store.loadLive() }
    }
}

// MARK: Signing in

/// An Xtream login, or an M3U playlist: the same two as the Fire TV.
struct LiveSignInView: View {
    @Environment(ReelyStore.self) private var store
    @State private var playlist = false
    @State private var host = ""
    @State private var username = ""
    @State private var password = ""
    @State private var url = ""
    @State private var guide = ""

    var body: some View {
        let live = store.live
        ScrollView {
            VStack(alignment: .leading, spacing: dp(14)) {
                #if os(iOS)
                PageHeader(title: "Live TV").padding(.horizontal, -pageMargin)
                #endif
                Text("Watch live TV from your provider").font(Typeface.display).foregroundStyle(Color.chalk)
                Text("Sign in with the details your IPTV provider gave you. Reely doesn't provide channels.")
                    .font(Typeface.body).foregroundStyle(Color.muted)
                HStack(spacing: dp(10)) {
                    Pill(title: "Server login", on: !playlist) { playlist = false }
                    Pill(title: "M3U playlist", on: playlist) { playlist = true }
                }
                if let error = live.error { ErrorNote(text: error) }
                if !playlist {
                    LiveField(label: "Server address", text: $host, address: true)
                    LiveField(label: "Username", text: $username)
                    LiveField(label: "Password", text: $password, secret: true)
                    PrimaryButton(title: live.busy ? "Connecting…" : "Sign in") {
                        Task { await store.signInXtream(base: host, username: username, password: password) }
                    }
                    .disabled(live.busy)
                } else {
                    LiveField(label: "Playlist address", text: $url, address: true)
                    LiveField(label: "TV guide address (optional)", text: $guide, address: true)
                    PrimaryButton(title: live.busy ? "Loading…" : "Add playlist") {
                        Task { await store.signInPlaylist(url: url, guideUrl: guide) }
                    }
                    .disabled(live.busy)
                }
            }
            .frame(maxWidth: dp(640), alignment: .leading)
            .padding(.horizontal, pageMargin)
            .padding(.top, topInset)
            .padding(.bottom, dp(24))
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A box to type in, for a provider's details.
struct LiveField: View {
    let label: String
    @Binding var text: String
    var secret = false
    var address = false

    var body: some View {
        Group {
            if secret {
                SecureField(label, text: $text)
            } else {
                TextField(label, text: $text)
                    .keyboardType(address ? .URL : .default)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .font(Typeface.body)
        #if os(iOS)
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color.line))
        #endif
    }
}

// MARK: Categories

struct CategoriesView: View {
    @Environment(ReelyStore.self) private var store

    var body: some View {
        let live = store.live
        ScrollView {
            VStack(alignment: .leading, spacing: dp(12)) {
                #if os(iOS)
                PageHeader(title: "Live TV")
                #endif
                if let error = live.error { ErrorNote(text: error).padding(.horizontal, pageMargin) }
                LazyVStack(spacing: dp(2)) {
                    ForEach(store.shownCategories) { category in
                        ListRow(title: category.name, systemImage: category == FAVORITES_CATEGORY ? "heart.fill" : category == RECENT_CATEGORY ? "clock" : nil) {
                            Task { await store.openCategory(category) }
                        }
                    }
                }
                .padding(.horizontal, pageMargin - dp(8))
                if live.busy && live.categories.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(dp(40))
                }
            }
            .padding(.top, topInset)
            .padding(.bottom, dp(24))
        }
    }
}

/// A row in a list: its name, a symbol before it if it has one, and an arrow.
struct ListRow: View {
    let title: String
    var subtitle: String? = nil
    var systemImage: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                if let systemImage { Image(systemName: systemImage).foregroundStyle(Color.muted).frame(width: dp(22)) }
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(title).font(Typeface.body).foregroundStyle(Color.chalk).lineLimit(1)
                    if let subtitle { Text(subtitle).font(Typeface.label).foregroundStyle(Color.muted).lineLimit(1) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: dp(12), weight: .semibold)).foregroundStyle(Color.faint)
            }
            .padding(.horizontal, dp(14)).padding(.vertical, dp(12))
            .contentShape(Rectangle())
        }
        .buttonStyle(RowStyle())
    }
}

// MARK: A category's channels

struct ChannelsView: View {
    @Environment(ReelyStore.self) private var store
    let category: XtreamCategory
    /// The grid, on Apple TV; a screenshot of it asks with "guide".
    @State private var showGuide = ProcessInfo.processInfo.arguments.contains("guide")
    @State private var schedule: XtreamChannel?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int(context.date.timeIntervalSince1970)
            #if os(tvOS)
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 16) {
                    Text(category.name).font(Typeface.headline).foregroundStyle(Color.chalk).padding(.trailing, 12)
                    Pill(title: "Channels", on: !showGuide) { showGuide = false }
                    Pill(title: "Guide", on: showGuide) { showGuide = true }
                    Spacer()
                }
                .padding(.horizontal, pageMargin)
                .focusSection()
                if showGuide {
                    GuideGrid(channels: Array(store.live.channels.prefix(60)), categoryName: category.name, now: now)
                } else {
                    channelList(now)
                }
            }
            .padding(.top, topInset)
            .onExitCommand { store.closeCategory() }
            #else
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Button { store.closeCategory() } label: { Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)) }
                        .accessibilityLabel("Back")
                    Text(category.name).font(Typeface.headline).lineLimit(1)
                    Spacer()
                    Button { Task { await store.refreshGuide() } } label: { Image(systemName: "arrow.clockwise") }
                        .accessibilityLabel("Refresh the guide")
                }
                .font(.system(size: 18, weight: .medium)).foregroundStyle(Color.chalk)
                .padding(.horizontal, pageMargin).padding(.vertical, 10)
                channelList(now)
            }
            .sheet(item: $schedule) { channel in ScheduleSheet(channel: channel).presentationDetents([.medium, .large]) }
            #endif
        }
    }

    private func channelList(_ now: Int) -> some View {
        let live = store.live
        return ScrollView {
            LazyVStack(spacing: dp(2)) {
                if let error = live.error { ErrorNote(text: error).padding(.horizontal, dp(8)) }
                if live.busy && live.channels.isEmpty { ProgressView().padding(dp(40)) }
                ForEach(Array(live.channels.enumerated()), id: \.element.streamId) { index, channel in
                    ChannelRow(channel: channel, now: now, onWatch: { store.watchChannel(index) }, onHold: { schedule = channel })
                        .task {
                            // The first forty come with the category; the rest as they're scrolled to.
                            if index >= 40 { await store.loadGuide([channel]) }
                        }
                }
            }
            .padding(.horizontal, pageMargin - dp(8))
            .padding(.bottom, dp(24))
        }
    }
}

/// A channel: its logo, number and name, what's on with how far through, and what's next.
struct ChannelRow: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.accent) private var accent
    let channel: XtreamChannel
    let now: Int
    let onWatch: () -> Void
    var onHold: (() -> Void)? = nil

    var body: some View {
        let listing = store.listing(for: channel)
        let on = listing.first { $0.isOn(at: now) }
        let next = listing.first { $0.start >= now }
        let favorite = store.live.favorites.contains(channel.streamId)
        let content = HStack(spacing: dp(12)) {
            ChannelLogo(channel: channel).frame(width: dp(64), height: dp(44))
            VStack(alignment: .leading, spacing: dp(3)) {
                Text(([channel.number > 0 ? String(channel.number) : nil, channel.name].compactMap { $0 }.joined(separator: "  ")) + (favorite ? "  ♥" : ""))
                    .font(Typeface.body).foregroundStyle(Color.chalk).lineLimit(1)
                if let on {
                    Text(on.title).font(Typeface.label).foregroundStyle(Color.muted).lineLimit(1)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.line)
                            Capsule().fill(accent.swiftColor).frame(width: g.size.width * (on.progress(at: now) ?? 0))
                        }
                    }
                    .frame(height: dp(3))
                }
                if let next {
                    Text("Next: \(liveTime(next.start)) \(next.title)").font(Typeface.label).foregroundStyle(Color.faint).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if channel.archiveDays > 0 { Image(systemName: "gobackward").foregroundStyle(Color.faint).accessibilityLabel("Keeps an archive") }
        }
        .padding(.horizontal, dp(10)).padding(.vertical, dp(10))
        .contentShape(Rectangle())

        #if os(tvOS)
        Button(action: onWatch) { content }
            .buttonStyle(RowStyle())
            .contextMenu {
                Button(favorite ? "Remove from Favorites" : "Add to Favorites") { store.toggleFavorite(channel) }
            }
        #else
        content
            .onTapGesture(perform: onWatch)
            .onLongPressGesture { onHold?() }
            .accessibilityAddTraits(.isButton)
        #endif
    }
}

/// A channel's logo on its own colour, or its name where it has none.
struct ChannelLogo: View {
    let channel: XtreamChannel

    var body: some View {
        let tint = channelTint(channel.streamId)
        ZStack {
            if let icon = channel.icon, let url = URL(string: icon) {
                RemoteImage(url: url, contentMode: .fit, background: tint).padding(dp(4))
            } else {
                Text(channelLabel(channel.name)).font(Typeface.label).foregroundStyle(Color.chalk)
                    .multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.6).padding(dp(4))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RoundedRectangle(cornerRadius: dp(8)).fill(tint))
        .clipShape(RoundedRectangle(cornerRadius: dp(8)))
    }
}

/// "9:30 PM", as the device tells the time.
func liveTime(_ epoch: Int) -> String {
    Date(timeIntervalSince1970: TimeInterval(epoch)).formatted(date: .omitted, time: .shortened)
}

/// A channel's own colour behind its logo, the same each time, as the Fire TV and LG pick it.
func channelTint(_ streamId: Int) -> Color {
    let palette: [UInt32] = [0x2C3563, 0x7A3B42, 0x2F5E52, 0x6A4E2A, 0x473469, 0x2B5066, 0x6B3559, 0x3F5A2E]
    var hash: Int32 = 7
    for unit in String(streamId).utf16 { hash = hash &* 31 &+ Int32(unit) }
    let n = Int32(palette.count)
    return Color(hex: palette[Int(((hash % n) + n) % n)])
}

/// A channel's name to stand in for its logo, without a provider's prefix ("UK | ", "US: ", "[DE] ").
func channelLabel(_ name: String) -> String {
    let trimmed = name.trimmingCharacters(in: .whitespaces)
    guard let range = trimmed.range(of: #"^(\[[^\]]{1,6}\]|[A-Z0-9]{2,4}\s*[|:\-])\s*"#, options: .regularExpression) else { return trimmed }
    let rest = String(trimmed[range.upperBound...])
    return rest.isEmpty ? trimmed : rest
}

// MARK: A channel's schedule, on a phone

#if os(iOS)
/// From a hold on a channel, as on the Android phone app: watch it, favorite it, and its
/// schedule: reminders for what's to come, and what's been on to watch again where the
/// channel keeps an archive.
struct ScheduleSheet: View {
    @Environment(ReelyStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let channel: XtreamChannel

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int(context.date.timeIntervalSince1970)
            let listing = store.listing(for: channel).filter { $0.stop > now - channel.archiveDays * 86_400 }
            let favorite = store.live.favorites.contains(channel.streamId)
            List {
                Section {
                    HStack(spacing: 10) {
                        PrimaryButton(title: "Watch") { dismiss(); watch(nil) }
                        SecondaryButton(title: favorite ? "Remove favorite" : "Add to Favorites") { store.toggleFavorite(channel) }
                    }
                    .listRowBackground(Color.clear)
                }
                Section {
                    if listing.isEmpty { Text("No guide for this channel.").foregroundStyle(Color.muted) }
                    ForEach(Array(listing.enumerated()), id: \.offset) { _, p in
                        let past = p.stop <= now
                        let onNow = p.isOn(at: now)
                        let again = past && store.canCatchUp(channel, p, now: now)
                        let reminded = store.hasReminder(channel, p)
                        Button {
                            if onNow { dismiss(); watch(nil) } else if again { dismiss(); watch(p) } else if !past { store.toggleReminder(channel, p) }
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(p.title).font(Typeface.body).foregroundStyle(past && !again ? Color.faint : Color.chalk)
                                Text(["\(day(p.start)) \(liveTime(p.start))–\(liveTime(p.stop))",
                                      onNow ? "On now" : again ? "Watch again" : !past && reminded ? "Reminder set" : nil].compactMap { $0 }.joined(separator: " · "))
                                    .font(Typeface.label).foregroundStyle(onNow || reminded ? Color.chalk : Color.muted)
                            }
                        }
                        .disabled(past && !again)
                    }
                }
            }
            .navigationTitle(channel.name)
            .scrollContentBackground(.hidden)
            .background(Color.surfaceRaised)
        }
        .task { await store.loadTable([channel]) }
    }

    private func watch(_ programme: Programme?) {
        guard let index = store.live.channels.firstIndex(where: { $0.streamId == channel.streamId }) else { return }
        if let programme { store.playCatchUp(index, programme) } else { store.watchChannel(index) }
    }

    private func day(_ epoch: Int) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epoch))
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInTomorrow(date) { return "Tomorrow" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return date.formatted(date: .numeric, time: .omitted)
    }
}
#endif
