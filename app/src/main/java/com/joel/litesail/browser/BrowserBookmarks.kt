package com.joel.litesail.browser

import android.content.SharedPreferences

internal data class BrowserBookmark(val url: String, val title: String)

internal class BrowserBookmarks(private val preferences: SharedPreferences) {
    fun entries(): List<BrowserBookmark> = preferences.all.mapNotNull { (url, title) ->
        if (title is String && BrowserAddress.isWebUrl(url)) BrowserBookmark(url, title) else null
    }.sortedWith(compareBy<BrowserBookmark, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.url })

    fun contains(url: String): Boolean = preferences.contains(url)

    operator fun get(url: String): BrowserBookmark? =
        preferences.getString(url, null)?.let { BrowserBookmark(url, it) }

    fun add(url: String, title: String?): Boolean {
        if (!BrowserAddress.isWebUrl(url) || contains(url)) return false
        save(url, title)
        return true
    }

    fun rename(url: String, title: String) {
        if (contains(url)) save(url, title)
    }

    fun remove(url: String) {
        preferences.edit().remove(url).apply()
    }

    private fun save(url: String, title: String?) {
        val name = title?.filterNot(Char::isISOControl)?.trim()?.takeIf(String::isNotEmpty) ?: url
        preferences.edit().putString(url, name).apply()
    }
}
