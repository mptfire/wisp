package com.wisp.app.ui.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class QrCodeDecoderTest {

    private fun renderQrBitmap(payload: String, size: Int = 256, inverted: Boolean = false): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val fg = if (inverted) Color.WHITE else Color.BLACK
        val bg = if (inverted) Color.BLACK else Color.WHITE
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) fg else bg
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun renderQrYPlane(
        payload: String,
        width: Int = 256,
        height: Int = 256,
        rowStride: Int = width,
        inverted: Boolean = false
    ): ByteArray {
        val bitmap = renderQrBitmap(payload, size = minOf(width, height), inverted = inverted)
        val out = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val luma = if (Color.red(pixel) > 128) 240 else 16
                out[y * rowStride + x] = luma.toByte()
            }
            // Fill stride padding with garbage to ensure decoder strips padding correctly
            for (pad in width until rowStride) {
                out[y * rowStride + pad] = 0x55.toByte()
            }
        }
        return out
    }

    @Test
    fun decodesStandardQrFromBitmap() {
        val payload = "bunker://0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef?relay=wss%3A%2F%2Frelay.primal.net&secret=abc123"
        val bitmap = renderQrBitmap(payload)
        assertEquals(payload, QrCodeDecoder.decodeBitmap(bitmap))
    }

    @Test
    fun decodesInvertedDarkModeQrFromBitmap() {
        val payload = "lightning:lnbc100n1p3testinvoiceexample"
        val bitmap = renderQrBitmap(payload, inverted = true)
        assertEquals(payload, QrCodeDecoder.decodeBitmap(bitmap))
    }

    @Test
    fun decodesQrFromCameraYuvPlaneWithRowStridePadding() {
        val payload = "nostrconnect://abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789?relay=wss%3A%2F%2Fbucket.coracle.social"
        val yPlane = renderQrYPlane(payload, width = 256, height = 256, rowStride = 320)
        assertEquals(payload, QrCodeDecoder.decodeYuv(yPlane, width = 256, height = 256, rowStride = 320))
    }

    @Test
    fun decodesInvertedQrFromCameraYuvPlane() {
        val payload = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsutm2v"
        val yPlane = renderQrYPlane(payload, width = 256, height = 256, rowStride = 256, inverted = true)
        assertEquals(payload, QrCodeDecoder.decodeYuv(yPlane, width = 256, height = 256, rowStride = 256))
    }

    @Test
    fun returnsNullOnBlankFrameWithoutThrowing() {
        val blank = ByteArray(128 * 128) { 0x7F.toByte() }
        assertNull(QrCodeDecoder.decodeYuv(blank, width = 128, height = 128, rowStride = 128))

        val blankBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertNull(QrCodeDecoder.decodeBitmap(blankBmp))
    }
}
