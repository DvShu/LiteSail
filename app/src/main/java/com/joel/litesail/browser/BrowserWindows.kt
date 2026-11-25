package com.joel.litesail.browser

import android.graphics.Bitmap
import android.os.Bundle

internal class BrowserWindows {
    class Window {
        var home = true
        var url: String? = null
            private set
        var title: String? = null
            private set
        var history: Bundle? = null
        var favicon: Bitmap? = null
            private set
        var preview: Bitmap? = null

        fun rememberAddress(url: String?, title: String?) {
            val address = url?.takeIf { it.length <= MAX_URL_LENGTH }
            if (this.url != address) {
                favicon = null
                preview = null
            }
            this.url = address
            this.title = title?.take(MAX_TITLE_LENGTH)
        }

        fun rememberFavicon(icon: Bitmap) {
            favicon = BrowserFavicon.thumbnail(icon)
        }

        fun clear() {
            url = null
            title = null
            history = null
            favicon = null
            preview = null
        }

        fun save(): Bundle = Bundle().apply {
            putBoolean("home", home)
            putString("url", url)
            putString("title", title)
            putBundle("history", history)
            putParcelable("favicon", favicon)
        }
    }

    private val entries = mutableListOf(Window())
    val items: List<Window> get() = entries
    var selectedIndex = 0
        private set
    val selected: Window get() = entries[selectedIndex]

    fun add(): Boolean {
        if (entries.size >= LIMIT) return false
        entries.add(Window())
        selectedIndex = entries.lastIndex
        return true
    }

    fun select(index: Int): Boolean {
        if (index !in entries.indices || index == selectedIndex) return false
        selectedIndex = index
        return true
    }

    /** Returns whether the active window changed. */
    fun close(index: Int): Boolean {
        if (index !in entries.indices) return false
        val closedActive = index == selectedIndex
        entries.removeAt(index).clear()
        if (entries.isEmpty()) entries.add(Window())
        if (index < selectedIndex) selectedIndex--
        selectedIndex = selectedIndex.coerceAtMost(entries.lastIndex)
        return closedActive
    }

    fun closeAll() {
        entries.forEach(Window::clear)
        entries.clear()
        entries.add(Window())
        selectedIndex = 0
    }

    fun save(): Bundle = Bundle().apply {
        putInt("selected", selectedIndex)
        putParcelableArrayList("windows", ArrayList(entries.map(Window::save)))
    }

    @Suppress("DEPRECATION")
    fun restore(state: Bundle) {
        val saved = state.getParcelableArrayList<Bundle>("windows").orEmpty()
        if (saved.isEmpty()) return
        entries.clear()
        saved.take(LIMIT).forEach { data ->
            entries.add(Window().apply {
                home = data.getBoolean("home", true)
                rememberAddress(data.getString("url"), data.getString("title"))
                history = data.getBundle("history")
                data.getParcelable<Bitmap>("favicon")?.let(::rememberFavicon)
            })
        }
        selectedIndex = state.getInt("selected").coerceIn(entries.indices)
    }

    companion object {
        const val LIMIT = 12
        private const val MAX_URL_LENGTH = 8_192
        private const val MAX_TITLE_LENGTH = 256
    }
}
