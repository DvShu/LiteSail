package com.joel.litesail.browser

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal object BrowserFavicon {
    private const val MAX_SIZE = 64

    fun thumbnail(icon: Bitmap): Bitmap? {
        if (icon.isRecycled) return null
        val longestSide = maxOf(icon.width, icon.height)
        if (longestSide <= MAX_SIZE) return icon
        val scale = MAX_SIZE.toFloat() / longestSide
        return Bitmap.createScaledBitmap(
            icon,
            (icon.width * scale).roundToInt().coerceAtLeast(1),
            (icon.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    fun encode(icon: Bitmap): ByteArray? {
        val thumbnail = thumbnail(icon) ?: return null
        val output = ByteArrayOutputStream()
        return if (thumbnail.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
    }
}
