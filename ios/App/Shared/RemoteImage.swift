import SwiftUI
import ReelyCore
#if canImport(UIKit)
import UIKit
#endif

/**
 * Pictures from the server, asked for through the same transport as everything else, so
 * a test's stand-in server serves them too, and kept once they've come.
 */
@MainActor
final class ImageLoader {
    static let shared = ImageLoader()
    var transport: HttpTransport = URLSessionTransport()
    private let cache = NSCache<NSURL, UIImage>()
    private var waiting: [URL: Task<UIImage?, Never>] = [:]

    func image(_ url: URL) async -> UIImage? {
        if let hit = cache.object(forKey: url as NSURL) { return hit }
        if let running = waiting[url] { return await running.value }
        let transport = self.transport
        let task = Task<UIImage?, Never> {
            guard let response = try? await transport.send(HttpRequest(url: url.absoluteString, timeout: 30)), response.ok else { return nil }
            return UIImage(data: response.data)
        }
        waiting[url] = task
        let image = await task.value
        waiting[url] = nil
        if let image { cache.setObject(image, forKey: url as NSURL) }
        return image
    }
}

struct RemoteImage: View {
    let url: URL?
    var contentMode: ContentMode = .fill
    var background: Color = .surfaceHigh
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            background
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            }
        }
        .task(id: url) {
            image = nil
            guard let url else { return }
            let loaded = await ImageLoader.shared.image(url)
            withAnimation(.easeOut(duration: 0.2)) { image = loaded }
        }
    }
}
