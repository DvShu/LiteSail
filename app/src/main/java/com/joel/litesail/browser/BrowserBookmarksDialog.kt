package com.joel.litesail.browser

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import com.joel.litesail.R
import com.joel.litesail.databinding.ItemBrowserBookmarkBinding
import com.joel.litesail.databinding.ViewBrowserBookmarksBinding

internal class BrowserBookmarksDialog(
    context: Context,
    private val bookmarks: BrowserBookmarks,
    private val onOpen: (String) -> Unit,
    private val onEdit: (BrowserBookmark) -> Unit
) : Dialog(context, R.style.Theme_LiteSail_BrowserMenu) {
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
        setTitle(R.string.browser_menu_library)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        binding.bookmarkList.emptyView = binding.emptyBookmarks
        binding.bookmarkList.adapter = adapter
        binding.closeBookmarks.setOnClickListener { dismiss() }

        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
        }
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
