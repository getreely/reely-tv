import UIKit
import AVFoundation

/**
 * What a video app tells iOS at launch: its sound is the point, so it plays with the
 * ring/silent switch on silent and carries on to AirPlay and in the background; and while
 * something's playing, the phone turns on its side, as the Android phone app does.
 */
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// What the screen may turn to: everything, or landscape while a video plays.
    static var orientations: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        return true
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : AppDelegate.orientations
    }

    /// On its side for a video; back to however it's held after.
    @MainActor
    static func playing(_ on: Bool) {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        orientations = on ? .landscape : .allButUpsideDown
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: on ? .landscapeRight : .portrait)) { _ in }
        }
    }
}
