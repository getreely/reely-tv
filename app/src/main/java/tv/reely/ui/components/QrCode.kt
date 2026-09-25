package tv.reely.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The dark squares of a QR code for [text], row by row, without its quiet zone. */
fun qrModules(text: String): List<BooleanArray> {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Asking for 1×1 gets the code at one pixel a module, however many modules it needs.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
    return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}

/**
 * A QR code a phone camera can read off a television: black on white whatever the
 * theme, since readers look for dark modules on light, with a white border around it
 * that scanners need to find its edges.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val modules = remember(text) { qrModules(text) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val count = modules.size
            // Whole pixels a module, so neighbouring squares don't blur into grey seams.
            val cell = kotlin.math.floor(this.size.minDimension / count)
            val inset = (this.size.minDimension - cell * count) / 2
            modules.forEachIndexed { y, row ->
                row.forEachIndexed { x, dark ->
                    if (dark) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(inset + x * cell, inset + y * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
    }
}
