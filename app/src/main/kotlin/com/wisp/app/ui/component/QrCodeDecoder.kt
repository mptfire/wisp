package com.wisp.app.ui.component

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Pure open-source ZXing QR decoder (zero Google ML Kit / Play Services dependencies).
 * Supports both live CameraX YUV_420_888 frames and gallery [Bitmap] images, including
 * inverted dark-mode QR codes (light modules on dark backgrounds).
 */
object QrCodeDecoder {

    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    private const val MAX_BITMAP_DIM = 1600

    fun decodeImageProxy(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return decodeYuv(
            yPlane = data,
            width = imageProxy.width,
            height = imageProxy.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride
        )
    }

    fun decodeYuv(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1
    ): String? {
        if (width <= 0 || height <= 0 || rowStride < width || pixelStride <= 0) return null
        val tight = if (rowStride == width && pixelStride == 1 && yPlane.size >= width * height) {
            yPlane
        } else {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val srcRow = y * rowStride
                val dstRow = y * width
                if (pixelStride == 1) {
                    if (srcRow + width > yPlane.size) return null
                    System.arraycopy(yPlane, srcRow, out, dstRow, width)
                } else {
                    for (x in 0 until width) {
                        val srcIdx = srcRow + x * pixelStride
                        if (srcIdx >= yPlane.size) return null
                        out[dstRow + x] = yPlane[srcIdx]
                    }
                }
            }
            out
        }
        val source = PlanarYUVLuminanceSource(
            tight,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        return decodeSource(source)
    }

    fun decodeBitmap(bitmap: Bitmap): String? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        var working = bitmap
        var ownsWorking = false
        try {
            if (working.config == Bitmap.Config.HARDWARE) {
                working = working.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                ownsWorking = true
            }
            val maxSide = maxOf(working.width, working.height)
            if (maxSide > MAX_BITMAP_DIM) {
                val scale = MAX_BITMAP_DIM.toFloat() / maxSide
                val targetW = (working.width * scale).toInt().coerceAtLeast(1)
                val targetH = (working.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(working, targetW, targetH, true)
                if (ownsWorking && scaled !== working) working.recycle()
                working = scaled
                ownsWorking = scaled !== bitmap
            }
            val w = working.width
            val h = working.height
            val pixels = IntArray(w * h)
            working.getPixels(pixels, 0, w, 0, 0, w, h)
            return decodeSource(RGBLuminanceSource(w, h, pixels))
        } finally {
            if (ownsWorking && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        val candidates = listOf(source, source.invert())
        for (candidate in candidates) {
            tryDecode(reader, BinaryBitmap(HybridBinarizer(candidate)))?.let { return it }
            tryDecode(reader, BinaryBitmap(GlobalHistogramBinarizer(candidate)))?.let { return it }
        }
        return null
    }

    private fun tryDecode(reader: QRCodeReader, bitmap: BinaryBitmap): String? {
        return try {
            reader.decode(bitmap, decodeHints).text?.takeIf { it.isNotEmpty() }
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
