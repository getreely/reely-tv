import SwiftUI
import UIKit
import CoreImage.CIFilterBuiltins

/// A QR code for an address, drawn sharp at any size.
struct QRCode: View {
    let text: String

    var body: some View {
        if let image = QRCode.make(text) {
            Image(uiImage: image).interpolation(.none).resizable().aspectRatio(1, contentMode: .fit)
                .padding(12).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel("QR code to sign in")
        }
    }

    static func make(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage, let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
