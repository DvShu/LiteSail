package com.joel.litesail.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Toast
import com.joel.litesail.R
import com.joel.litesail.databinding.ActivityBrowserLibraryBinding
import com.joel.litesail.databinding.ItemBrowserBookmarkBinding
import com.joel.litesail.databinding.ItemBrowserVisitBinding
import com.joel.litesail.databinding.ItemListDateBinding
import com.joel.litesail.ui.BottomSheetDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

class BrowserLibraryActivity : Activity() {
    private lateinit var binding: ActivityBrowserLibraryBinding
    private lateinit var adapter: LibraryAdapter
    private val bookmarks by lazy { BrowserBookmarks(this) }
    private var savedPages = emptyList<BrowserBookmark>()
    private var visits = emptyList<BrowserVisit>()
    private var historyEmptyMessage = R.string.browser_history_loading
    private var clearingHistory = false
    private var sheet: BottomSheetDialog? = null

    private val showingBookmarks get() = binding.libraryTabs.checkedRadioButtonId == R.id.library_bookmarks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 35) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val areas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                val padding = insets.getInsets(areas)
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
        }
        adapter = LibraryAdapter()
        binding.libraryList.itemsCanFocus = true
        binding.libraryList.emptyView = binding.libraryMessage
        binding.libraryList.adapter = adapter
        binding.libraryList.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position) as? BrowserLibraryRow.Visit ?: return@setOnItemClickListener
            openPage(row.entry.url)
        }
        binding.libraryBack.setOnClickListener { finish() }
        binding.clearBrowsingHistory.setOnClickListener { confirmClearHistory() }
        binding.libraryTabs.check(
            if (savedInstanceState?.getBoolean(BOOKMARKS_SELECTED) == true) R.id.library_bookmarks else R.id.library_history
        )
        binding.libraryTabs.setOnCheckedChangeListener { _, _ ->
            renderList()
            binding.libraryList.setSelection(0)
        }
        binding.librarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderList()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.librarySearch.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            true
        }
        renderList()
    }

    override fun onResume() {
        super.onResume()
        refreshBookmarks()
        BrowserHistory.read(this) { entries ->
            if (isFinishing || isDestroyed) return@read
            visits = entries.orEmpty()
            historyEmptyMessage = if (entries == null) R.string.browser_history_unavailable else R.string.browser_history_empty
            renderList()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(BOOKMARKS_SELECTED, showingBookmarks)
    }

    override fun onDestroy() {
        sheet?.dismiss()
        super.onDestroy()
    }

    private fun refreshBookmarks() {
        savedPages = bookmarks.entries()
        renderList()
    }

    private fun renderList() {
        val query = binding.librarySearch.text.toString().trim()
        val isBookmarks = showingBookmarks
        binding.librarySearch.setHint(if (isBookmarks) R.string.browser_bookmark_search else R.string.browser_history_search)
        binding.clearBrowsingHistory.visibility = if (isBookmarks) View.GONE else View.VISIBLE
        binding.clearBrowsingHistory.isEnabled = visits.isNotEmpty() && !clearingHistory
        val rows = if (isBookmarks) {
            savedPages.filter { matchesPage(it.title, it.url, query) }.map { BrowserLibraryRow.Bookmark(it) }
        } else {
            browserHistoryRows(visits, query)
        }
        val message = when {
            isBookmarks && savedPages.isEmpty() -> R.string.browser_bookmark_empty
            !isBookmarks && visits.isEmpty() -> historyEmptyMessage
            else -> R.string.browser_library_no_matches
        }
        binding.libraryMessage.setText(message)
        adapter.replaceRows(rows)
    }

    private fun openPage(url: String) {
        setResult(RESULT_OK, Intent().putExtra(SELECTED_URL, url))
        finish()
    }

    private fun confirmClearHistory() {
        hideKeyboard()
        val dialog = BottomSheetDialog(this, R.string.browser_history_confirm_clear)
        dialog.addAction(R.string.cancel)
        dialog.addAction(R.string.clear_search_history) {
            clearingHistory = true
            renderList()
            BrowserHistory.clear(this) { cleared ->
                if (isFinishing || isDestroyed) return@clear
                clearingHistory = false
                if (cleared) {
                    visits = emptyList()
                    historyEmptyMessage = R.string.browser_history_empty
                } else {
                    Toast.makeText(this, R.string.browser_history_clear_failed, Toast.LENGTH_SHORT).show()
                }
                renderList()
            }
        }
        showSheet(dialog)
    }

    private fun showSheet(dialog: BottomSheetDialog) {
        sheet?.dismiss()
        sheet = dialog
        dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
        dialog.show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private inner class LibraryAdapter : BaseAdapter() {
        private var rows = emptyList<BrowserLibraryRow>()
        private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(resources.configuration.locales[0])
        private val timeFormat = android.text.format.DateFormat.getTimeFormat(this@BrowserLibraryActivity)

        fun replaceRows(newRows: List<BrowserLibraryRow>) {
            if (rows == newRows) return
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = when (rows.getOrNull(position)) {
            is BrowserLibraryRow.Visit, is BrowserLibraryRow.Bookmark -> true
            else -> false
        }
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is BrowserLibraryRow.Day -> 0
            is BrowserLibraryRow.Visit -> 1
            is BrowserLibraryRow.Bookmark -> 2
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = getItem(position)) {
            is BrowserLibraryRow.Day -> {
                val item = convertView?.let(ItemListDateBinding::bind)
                    ?: ItemListDateBinding.inflate(layoutInflater, parent, false)
                val today = LocalDate.now()
                item.root.text = when (row.date) {
                    today -> getString(R.string.browser_history_today)
                    today.minusDays(1) -> getString(R.string.browser_history_yesterday)
                    else -> dateFormat.format(row.date)
                }
                item.root
            }
            is BrowserLibraryRow.Visit -> {
                val item = convertView?.let(ItemBrowserVisitBinding::bind)
                    ?: ItemBrowserVisitBinding.inflate(layoutInflater, parent, false)
                val visit = row.entry
                item.visitTitle.text = visit.title
                item.visitUrl.text = visit.url
                item.visitTime.text = timeFormat.format(Date(visit.visitedAt))
                item.visitIcon.text = Uri.parse(visit.url).host?.removePrefix("www.")?.firstOrNull()?.uppercaseChar()?.toString()
                item.root
            }
            is BrowserLibraryRow.Bookmark -> {
                val item = convertView?.let(ItemBrowserBookmarkBinding::bind)
                    ?: ItemBrowserBookmarkBinding.inflate(layoutInflater, parent, false).also { it.root.clipToOutline = true }
                val bookmark = row.entry
                item.bookmarkTitle.text = bookmark.title
                item.bookmarkAddress.text = bookmark.url
                item.bookmarkAddress.visibility = if (bookmark.title == bookmark.url) View.GONE else View.VISIBLE
                item.bookmarkDetails.setOnClickListener { openPage(bookmark.url) }
                item.editBookmark.contentDescription = getString(R.string.browser_bookmark_edit_named, bookmark.title)
                item.editBookmark.setOnClickListener {
                    showSheet(BookmarkEditorDialog(this@BrowserLibraryActivity, bookmarks, bookmark, ::refreshBookmarks))
                }
                item.root
            }
        }
    }

    companion object {
        internal const val SELECTED_URL = "selected_library_url"
        private const val BOOKMARKS_SELECTED = "library_bookmarks_selected"
    }
}

internal sealed interface BrowserLibraryRow {
    data class Day(val date: LocalDate) : BrowserLibraryRow
    data class Visit(val entry: BrowserVisit) : BrowserLibraryRow
    data class Bookmark(val entry: BrowserBookmark) : BrowserLibraryRow
}

internal fun browserHistoryRows(
    visits: List<BrowserVisit>, query: String, zone: ZoneId = ZoneId.systemDefault()
): List<BrowserLibraryRow> = buildList {
    var lastDay: LocalDate? = null
    for (visit in visits) {
        if (!matchesPage(visit.title, visit.url, query)) continue
        val date = Instant.ofEpochMilli(visit.visitedAt).atZone(zone).toLocalDate()
        if (date != lastDay) add(BrowserLibraryRow.Day(date))
        add(BrowserLibraryRow.Visit(visit))
        lastDay = date
    }
}

private fun matchesPage(title: String, url: String, query: String): Boolean =
    title.contains(query, ignoreCase = true) || url.contains(query, ignoreCase = true)
