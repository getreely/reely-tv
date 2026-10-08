import UIKit
import AVFoundation
import MetricKit

/**
 * What a video app tells iOS at launch: its sound is the point, so it plays with the
 * ring/silent switch on silent and carries on to AirPlay and in the background. The player
 * turns with the phone: upright for a picture across the top, on its side for the whole
 * screen.
 */
final class AppDelegate: NSObject, UIApplicationDelegate, MXMetricManagerSubscriber {
    /// What the screen may turn to, playing or not.
    static var orientations: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        // The bars' titles in Reely's type, white on the frosted dark.
        let bar = UINavigationBar.appearance()
        if let large = UIFont(name: "Geist-Bold", size: 32) { bar.largeTitleTextAttributes = [.font: large, .foregroundColor: UIColor.white] }
        if let small = UIFont(name: "Geist-SemiBold", size: 17) { bar.titleTextAttributes = [.font: small, .foregroundColor: UIColor.white] }
        // iOS's own report of a crash, handed over after the next launch, for the problem report.
        MXMetricManager.shared.add(self)
        return true
    }

    /// Where a crash goes, once the app has somewhere to keep it; until then it waits.
    @MainActor static var onProblem: (@MainActor (String, String?, Int) -> Void)? {
        didSet {
            guard let onProblem else { return }
            waiting.forEach { onProblem($0.0, $0.1, $0.2) }
            waiting = []
        }
    }
    @MainActor private static var waiting: [(String, String?, Int)] = []

    @MainActor private static func report(_ message: String, _ detail: String?, _ at: Int) {
        if let onProblem { onProblem(message, detail, at) } else { waiting.append((message, detail, at)) }
    }

    nonisolated func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            for crash in payload.crashDiagnostics ?? [] {
                let what = [crash.exceptionType.map { "exception \($0)" }, crash.signal.map { "signal \($0)" }, crash.terminationReason]
                    .compactMap { $0 }.joined(separator: ", ")
                let message = what.isEmpty ? "Reely crashed" : "Reely crashed (\(what))"
                let detail = String(data: crash.callStackTree.jsonRepresentation(), encoding: .utf8)
                let at = Int(payload.timeStampEnd.timeIntervalSince1970)
                Task { @MainActor in AppDelegate.report(message, detail, at) }
            }
        }
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : AppDelegate.orientations
    }

    /*
     * Playing used to lock the phone on its side, so it couldn't be watched upright. Now it
     * follows however the phone is held, as YouTube and the TV app do; kept as the one place
     * to change if that's ever wanted back.
     */
    @MainActor
    static func playing(_ on: Bool) {}
}
