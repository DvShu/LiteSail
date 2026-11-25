package com.joel.litesail.browser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.util.concurrent.Executors

internal data class BrowserBookmark(val url: String, val title: String)

internal class BrowserBookmarks(context: Context) {
    private val preferences = context.getSharedPreferences("browser_bookmarks", Context.MODE_PRIVATE)

    fun entries(): List<BrowserBookmark> = preferences.all.mapNotNull { (url, title) ->
        if (title is String && BrowserAddress.isWebUrl(url)) BrowserBookmark(url, title) else null
    }.sortedWith(compareBy<BrowserBookmark, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.url })

    fun contains(url: String): Boolean = preferences.contains(url)

    operator fun get(url: String): BrowserBookmark? =
        preferences.getString(url, null)?.let { BrowserBookmark(url, it) }

    fun add(url: String, title: String?, favicon: Bitmap?): Boolean {
        if (!BrowserAddress.isWebUrl(url) || contains(url)) return false
        preferences.edit().putString(url, displayName(url, title)).apply()
        if (favicon != null) updateFavicon(url, favicon)
        return true
    }

    fun rename(url: String, title: String) {
        if (contains(url)) preferences.edit().putString(url, displayName(url, title)).apply()
    }

    fun remove(url: String) {
        pendingFavicons.remove(url)
        preferences.edit().remove(url).remove(faviconKey(url)).apply()
    }

    fun updateFavicon(url: String, icon: Bitmap) {
        if (!contains(url)) return
        val request = Any()
        pendingFavicons[url] = request
        faviconWorker.execute {
            val encoded = BrowserFavicon.encode(icon)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            main.post {
                if (pendingFavicons[url] !== request) return@post
                pendingFavicons.remove(url)
                val key = faviconKey(url)
                if (encoded != null && preferences.getString(key, null) != encoded) {
                    preferences.edit().putString(key, encoded).apply()
                }
            }
        }
    }

    fun favicon(url: String): ByteArray? {
        val encoded = preferences.getString(faviconKey(url), null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun displayName(url: String, title: String?): String =
        title?.filterNot(Char::isISOControl)?.trim()?.takeIf(String::isNotEmpty) ?: url

    fun observeFavicons(onChanged: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith(FAVICON_KEY_PREFIX) == true) onChanged()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun faviconKey(url: String) = "$FAVICON_KEY_PREFIX$url"

    private companion object {
        const val FAVICON_KEY_PREFIX = "favicon:"
        val faviconWorker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val pendingFavicons = mutableMapOf<String, Any>()
    }
}
