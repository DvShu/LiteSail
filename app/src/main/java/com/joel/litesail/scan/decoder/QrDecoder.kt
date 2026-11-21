package com.joel.litesail.scan.decoder

import android.media.Image
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** Owned and used by the camera analysis thread. */
internal class QrDecoder {
    private val reader = QRCodeReader()
    private var pixels = ByteArray(0)

    fun decode(image: Image, centerSize: Int = 0): String? {
        val luminance = image.planes[0]
        return decode(luminance.buffer, image.width, image.height, luminance.rowStride, centerSize)
    }

    fun decode(frame: ByteBuffer, frameWidth: Int, frameHeight: Int, rowStride: Int, centerSize: Int = 0): String? {
        if (frameWidth <= 0 || frameHeight <= 0 || rowStride < frameWidth) return null
        if (centerSize > minOf(frameWidth, frameHeight)) return null

        val width = if (centerSize > 0) centerSize else frameWidth
        val height = if (centerSize > 0) centerSize else frameHeight
        val left = (frameWidth - width) / 2
        val top = (frameHeight - height) / 2
        val start = frame.position() + top.toLong() * rowStride + left
        val end = start + (height - 1L) * rowStride + width
        if (end > frame.limit()) return null

        // Copy only the luminance region and reuse the storage across frames.
        val size = width * height
        if (pixels.size < size) pixels = ByteArray(size)
        val source = frame.duplicate()
        if (width == rowStride) {
            source.position(start.toInt())
            source.get(pixels, 0, size)
        } else {
            for (row in 0 until height) {
                source.position((start + row.toLong() * rowStride).toInt())
                source.get(pixels, row * width, width)
            }
        }

        val image = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(image))).text
        } catch (_: ReaderException) {
            null
        }
    }
}
