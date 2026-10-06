import SwiftUI

/// The lowercase r with its underline: Reely's mark, drawn so it takes the colour picked.
struct ReelyMark: View {
    @Environment(\.accent) private var accent
    var height: CGFloat

    var body: some View {
        MarkShape().fill(accent.swiftColor).frame(width: height * 22 / 46, height: height)
            .accessibilityLabel("Reely")
    }
}

/// The LG and Fire TV mark's path (viewBox 43.1 30 22 46), drawn to any size.
private struct MarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 22, sy = rect.height / 46
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + (x - 43.1) * sx, y: rect.minY + (y - 30) * sy) }
        var path = Path()
        path.move(to: p(43.19, 66))
        path.addLine(to: p(43.19, 30))
        path.addLine(to: p(52.72, 30))
        path.addLine(to: p(52.99, 37.19))
        path.addQuadCurve(to: p(56.15, 31.71), control: p(54.07, 33.43))
        path.addQuadCurve(to: p(61.52, 30), control: p(58.23, 30))
        path.addLine(to: p(64.81, 30))
        path.addLine(to: p(64.81, 38.33))
        path.addLine(to: p(61.52, 38.33))
        path.addQuadCurve(to: p(55.28, 40.04), control: p(57.29, 38.33))
        path.addQuadCurve(to: p(53.26, 45.78), control: p(53.26, 41.75))
        path.addLine(to: p(53.26, 66))
        path.closeSubpath()
        let bar = CGRect(x: rect.minX + (45.5 - 43.1) * sx, y: rect.minY + (72 - 30) * sy, width: 17 * sx, height: 4 * sy)
        path.addRoundedRect(in: bar, cornerSize: CGSize(width: 2 * sx, height: 2 * sy))
        return path
    }
}
