package com.joel.litesail.browser

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object WebPageQrCode {
    private val hints = mapOf(
        EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )

    fun encode(url: String): BitMatrix {
        // Version 40 cannot hold more characters even in numeric mode.
        if (url.length > 7_089) throw WriterException("Data too big")
        return QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    fun displaySize(moduleCount: Int, availablePixels: Int): Int {
        val pixelsPerModule = availablePixels / moduleCount
        // Use the available space when a whole-pixel scale would leave only one pixel per module.
        return if (pixelsPerModule >= 2) pixelsPerModule * moduleCount else availablePixels
    }
}
