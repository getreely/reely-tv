import SwiftUI
import ReelyCore

/*
 * Reely's palette, as the Fire TV has it: black, cool greys, and an electric royal blue
 * used sparingly — the mark, progress, and anything that needs to be found.
 */
extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }

    static let ink = Color(hex: 0x08090B)
    static let surfaceRaised = Color(hex: 0x15171B)
    static let surfaceHigh = Color(hex: 0x1F2329)
    static let line = Color(hex: 0x2B3038)
    static let chalk = Color(hex: 0xF2F4F7)
    static let muted = Color(hex: 0xB3BAC4)
    static let faint = Color(hex: 0x7F8792)
    static let danger = Color(hex: 0xFF6B6B)
    static let good = Color(hex: 0x8CBE6E)
    static let warn = Color(hex: 0xE9A343)
}

/// The colour picked in Settings, and what's written on it.
struct AccentKey: EnvironmentKey {
    static let defaultValue = Accent.of("blue")
}

extension EnvironmentValues {
    var accent: Accent {
        get { self[AccentKey.self] }
        set { self[AccentKey.self] = newValue }
    }
}

extension Accent {
    var swiftColor: Color { Color(hex: color) }
    var onColor: Color { Color(hex: on) }
}

/**
 * Geist, at the Fire TV's sizes. A television's screen is drawn at twice a phone's points
 * (1920 across against the Fire TV's 960), so the TV sizes are doubled.
 */
enum Typeface {
    #if os(tvOS)
    static let scale: CGFloat = 2
    #else
    static let scale: CGFloat = 1
    #endif

    static func geist(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = "Geist-Bold"
        case .semibold: name = "Geist-SemiBold"
        case .medium: name = "Geist-Medium"
        default: name = "Geist-Regular"
        }
        return .custom(name, size: size * scale)
    }

    static let display = geist(36, .bold)
    static let headline = geist(24, .semibold)
    static let rowTitle = geist(20, .semibold)
    static let body = geist(18)
    static let meta = geist(16, .medium)
    static let label = geist(14, .medium)
}

/// Distances, scaled the same way as the type.
func dp(_ value: CGFloat) -> CGFloat { value * Typeface.scale }

/// The page's side margin: the Fire TV's 48dp, a phone's 16.
var pageMargin: CGFloat {
    #if os(tvOS)
    return 96
    #else
    return 16
    #endif
}
