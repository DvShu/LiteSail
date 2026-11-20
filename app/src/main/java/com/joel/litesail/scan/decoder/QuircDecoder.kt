package com.joel.litesail.scan.decoder

import android.media.Image
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException

/** Owned and used by the camera analysis thread. */
internal class QuircDecoder : AutoCloseable {
    private var context = createContext()

    fun decode(image: Image, centerSize: Int = 0): String? {
        val luminance = image.planes[0]
        val buffer = luminance.buffer
        val width = if (centerSize > 0) centerSize else image.width
        val height = if (centerSize > 0) centerSize else image.height
        val left = (image.width - width) / 2
        val top = (image.height - height) / 2
        val offset = buffer.position() + top * luminance.rowStride + left
        val payload = decodeFrame(
            context, buffer, offset, buffer.limit(), width, height, luminance.rowStride
        ) ?: return null

        val payloadOffset = 1
        val length = payload.size - payloadOffset
        val encoding = when (val eci = payload[0].toInt() and 0xff) {
            1, 3 -> "ISO-8859-1"
            2 -> "IBM437"
            in 4..13, in 15..18 -> "ISO-8859-" + (eci - 2)
            20 -> "Shift_JIS"
            21 -> "windows-1250"
            22 -> "windows-1251"
            23 -> "windows-1252"
            24 -> "windows-1256"
            25 -> "UTF-16BE"
            26 -> "UTF-8"
            27 -> "US-ASCII"
            28 -> "Big5"
            29 -> "GB18030"
            30 -> "EUC-KR"
            else -> null
        }
        val charset = encoding?.let { runCatching { Charset.forName(it) }.getOrNull() }
        if (charset != null) return String(payload, payloadOffset, length, charset)

        // Codes without an ECI often contain UTF-8, even though the default byte encoding is Latin-1.
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload, payloadOffset, length)).toString()
        } catch (_: CharacterCodingException) {
            String(payload, payloadOffset, length, Charsets.ISO_8859_1)
        }
    }

    override fun close() {
        if (context != 0L) {
            destroyContext(context)
            context = 0L
        }
    }

    private external fun createContext(): Long
    private external fun destroyContext(context: Long)
    private external fun decodeFrame(
        context: Long,
        luminance: ByteBuffer,
        offset: Int,
        limit: Int,
        width: Int,
        height: Int,
        rowStride: Int
    ): ByteArray?

    private companion object {
        init {
            System.loadLibrary("litesail_quirc")
        }
    }
}
