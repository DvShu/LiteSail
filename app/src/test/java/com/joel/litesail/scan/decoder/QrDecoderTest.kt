package com.joel.litesail.scan.decoder

import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.joel.litesail.browser.WebPageQrCode
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test
    fun readsUnicodeWebPageQrCodeFromPaddedFrame() {
        val url = "https://example.org/路径?词=轻舟#片段"
        val frame = frameWithQr(url)
        val decoder = QrDecoder()

        assertEquals(url, decoder.decode(frame, WIDTH, HEIGHT, ROW_STRIDE))
        assertEquals(url, decoder.decode(frame, WIDTH, HEIGHT, ROW_STRIDE, HEIGHT))
        assertEquals(PREFIX, frame.position())
    }

    @Test
    fun readsMirroredQrCode() {
        val url = "https://example.org/mirrored"

        assertEquals(url, QrDecoder().decode(frameWithQr(url, mirrored = true), WIDTH, HEIGHT, ROW_STRIDE))
    }

    @Test
    fun readsLegacyUtf8WithoutEci() {
        val content = "轻舟浏览器的二维码"
        val legacyBytes = String(content.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val matrix = QRCodeWriter().encode(legacyBytes, BarcodeFormat.QR_CODE, 0, 0)

        assertEquals(content, QrDecoder().decode(frameWithQr(matrix), WIDTH, HEIGHT, ROW_STRIDE))
    }

    @Test
    fun usesWholePixelsForDenseQrCodeWhenTheyFit() {
        assertEquals(370, WebPageQrCode.displaySize(185, 480))
        assertEquals(288, WebPageQrCode.displaySize(185, 288))
    }

    @Test
    fun rejectsTruncatedFrame() {
        val frame = frameWithQr("https://example.org/")
        frame.limit(frame.limit() - 1)

        assertNull(QrDecoder().decode(frame, WIDTH, HEIGHT, ROW_STRIDE))
    }

    @Test(expected = WriterException::class)
    fun rejectsAddressesBeyondQrCapacity() {
        WebPageQrCode.encode("https://example.org/" + "x".repeat(8_000))
    }

    private fun frameWithQr(url: String, mirrored: Boolean = false): ByteBuffer =
        frameWithQr(WebPageQrCode.encode(url), mirrored)

    private fun frameWithQr(matrix: BitMatrix, mirrored: Boolean = false): ByteBuffer {
        val scale = 4
        val side = matrix.width * scale
        val left = (WIDTH - side) / 2
        val top = (HEIGHT - side) / 2
        val bytes = ByteArray(PREFIX + (HEIGHT - 1) * ROW_STRIDE + WIDTH) { 0xff.toByte() }
        for (y in 0 until side) {
            for (x in 0 until side) {
                val column = if (mirrored) side - 1 - x else x
                if (matrix[column / scale, y / scale]) {
                    bytes[PREFIX + (top + y) * ROW_STRIDE + left + x] = 0
                }
            }
        }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            position(PREFIX)
        }
    }

    private companion object {
        const val WIDTH = 272
        const val HEIGHT = 240
        const val ROW_STRIDE = 288
        const val PREFIX = 7
    }
}
