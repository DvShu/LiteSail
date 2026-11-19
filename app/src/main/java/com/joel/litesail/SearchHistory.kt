package com.joel.litesail

import android.content.Context
import org.json.JSONArray

private const val ENTRIES_KEY = "entries"

internal class SearchHistory(context: Context) {
    private val preferences = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun entries(): List<String> {
        val saved = preferences.getString(ENTRIES_KEY, null) ?: return emptyList()
        val items = runCatching { JSONArray(saved) }.getOrNull() ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            (items.opt(index) as? String)?.takeIf { it.isNotBlank() }
        }
    }

    fun record(query: String) {
        val recent = (listOf(query) + entries().filterNot { it == query }).take(30)
        save(recent)
    }

    fun remove(query: String) {
        save(entries().filterNot { it == query })
    }

    fun clear() {
        preferences.edit().remove(ENTRIES_KEY).apply()
    }

    private fun save(entries: List<String>) {
        preferences.edit().putString(ENTRIES_KEY, JSONArray(entries).toString()).apply()
    }
}
