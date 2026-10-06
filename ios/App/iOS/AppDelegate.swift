import UIKit
import AVFoundation

/**
 * What a video app tells iOS at launch: its sound is the point, so it plays with the
 * ring/silent switch on silent and carries on to AirPlay and in the background. The player
 * turns with the phone: upright for a picture across the top, on its side for the whole
 * screen.
 */
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// What the screen may turn to, playing or not.
    static var orientations: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        // The bars' titles in Reely's type, white on the frosted dark.
        let bar = UINavigationBar.appearance()
        if let large = UIFont(name: "Geist-Bold", size: 32) { bar.largeTitleTextAttributes = [.font: large, .foregroundColor: UIColor.white] }
        if let small = UIFont(name: "Geist-SemiBold", size: 17) { bar.titleTextAttributes = [.font: small, .foregroundColor: UIColor.white] }
        return true
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
