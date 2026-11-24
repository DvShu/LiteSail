package com.joel.litesail.browser

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ItemBrowserBookmarkBinding
import com.joel.litesail.databinding.ViewBrowserBookmarksBinding
import com.joel.litesail.ui.BottomSheetDialog

internal class BrowserBookmarksDialog(
    context: Context,
    private val bookmarks: BrowserBookmarks,
    private val onOpen: (String) -> Unit,
    private val onEdit: (BrowserBookmark) -> Unit
) : BottomSheetDialog(context, R.string.browser_menu_library) {
    private var entries: List<BrowserBookmark> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let(ItemBrowserBookmarkBinding::bind)
                ?: ItemBrowserBookmarkBinding.inflate(layoutInflater, parent, false)
                    .also { it.root.clipToOutline = true }
            val bookmark = getItem(position)
            row.bookmarkTitle.text = bookmark.title
            row.bookmarkAddress.text = bookmark.url
            row.bookmarkAddress.visibility = if (bookmark.title == bookmark.url) View.GONE else View.VISIBLE
            row.bookmarkDetails.setOnClickListener {
                dismiss()
                onOpen(bookmark.url)
            }
            row.editBookmark.contentDescription = context.getString(R.string.browser_bookmark_edit_named, bookmark.title)
            row.editBookmark.setOnClickListener { onEdit(bookmark) }
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ViewBrowserBookmarksBinding.inflate(layoutInflater)
        setBody(binding.root)
        binding.bookmarkList.emptyView = binding.emptyBookmarks
        binding.bookmarkList.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    fun refresh() {
        entries = bookmarks.entries()
        adapter.notifyDataSetChanged()
    }
}
