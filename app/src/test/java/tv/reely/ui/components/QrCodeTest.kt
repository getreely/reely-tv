package tv.reely.ui.components

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.plex.PlexApi

class QrCodeTest {

    /** Paints the modules as a scanner would see them, with a quiet zone, and reads them back. */
    private fun scan(modules: List<BooleanArray>, scale: Int = 4, quiet: Int = 4): String {
        val side = (modules.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (!dark) return@forEachIndexed
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun theSignInAddressReadsBack() {
        val url = PlexApi.authUrl("0f3c9a2e-5b1d-4e7a-9c3f-2d8b6a1e4f70", "4k7xq2m9vj3p8wz6rt5ynb1hd")
        val modules = qrModules(url)
        assertTrue("square", modules.all { it.size == modules.size })
        assertEquals(url, scan(modules))
    }

    @Test fun theAddressIsPlexsAuthPage() {
        val url = PlexApi.authUrl("client id", "abc")
        assertEquals(
            "https://app.plex.tv/auth#?clientID=client%20id&code=abc&context%5Bdevice%5D%5Bproduct%5D=Reely%20TV",
            url,
        )
    }
}
