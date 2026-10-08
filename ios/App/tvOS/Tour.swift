import SwiftUI
import ReelyCore

/*
 * How to get around with the Siri remote, a step at a time, as the Fire TV's tour: what
 * the buttons do where it isn't obvious. Written from what this app does on Apple TV, so
 * it can't promise what isn't so.
 */
let TOUR: [(title: String, body: String, key: String?)] = [
    ("Welcome to Reely", "A quick look at getting around with your remote. It takes a minute, and you can skip it.", nil),
    ("The top row", "Press Up to reach the tabs: Search, Home, Movies, TV Shows, Live TV and Request. Settings is the gear on the right. Moving onto a tab opens it.", "Up"),
    ("Hold for more", "Press and hold the clickpad on any poster for more: carry on or start again, mark it watched, or go to its page. On a show, it plays the next episode.", "Press and hold"),
    ("While you watch", "Left and Right move back and forward, ten seconds a press, with a picture of where you'll land; it goes there when you stop. Press the clickpad for subtitles, sound, chapters and the sleep timer. Back puts them away.", "Left · Right"),
    ("Live TV", "Left and Right change channel, and Down opens the guide over it. Press the clickpad for Start over, Favorites and a channel by its number; press and hold it to watch up to four channels at once. In the guide, choosing something to come reminds you when it starts.", "Down"),
    ("Can't find something?", "The Request tab finds movies and shows your server doesn't have yet and asks for them. The first time, enter your Reely address there.", nil),
    ("You're all set", "You can take this tour again from Settings, under About.", nil),
]

struct TourView: View {
    let onDone: () -> Void
    @State private var step = 0
    @Environment(\.accent) private var accent

    var body: some View {
        let s = TOUR[step]
        let last = step == TOUR.count - 1
        ZStack {
            Color.ink.opacity(0.92).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                Text("\(step + 1) of \(TOUR.count)").font(Typeface.label).foregroundStyle(Color.faint)
                Text(s.title).font(Typeface.display).foregroundStyle(Color.chalk)
                if let key = s.key {
                    Text(key).font(Typeface.meta).foregroundStyle(accent.onColor)
                        .padding(.horizontal, 20).padding(.vertical, 10)
                        .background(Capsule().fill(accent.swiftColor))
                }
                Text(s.body).font(Typeface.body).foregroundStyle(Color.muted).frame(maxWidth: 1000, alignment: .leading)
                HStack(spacing: 20) {
                    PanelButton(title: last ? "Done" : "Next", systemImage: last ? "checkmark" : "arrow.right", filled: true) {
                        if last { onDone() } else { step += 1 }
                    }
                    .id("next\(step)")
                    if step > 0 { PanelButton(title: "Back", systemImage: "arrow.left") { step -= 1 } }
                    if !last { PanelButton(title: "Skip tour", systemImage: "xmark", action: onDone) }
                }
                .padding(.top, 12)
            }
            .padding(80)
            .frame(maxWidth: 1300, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 32).fill(Color.surfaceRaised))
        }
        .onExitCommand { if step == 0 { onDone() } else { step -= 1 } }
    }
}
