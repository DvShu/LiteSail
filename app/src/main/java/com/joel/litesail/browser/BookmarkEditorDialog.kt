package com.joel.litesail.browser

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.joel.litesail.R
import com.joel.litesail.databinding.ViewBookmarkEditorBinding
import com.joel.litesail.ui.BottomSheetDialog

internal class BookmarkEditorDialog(
    context: Context,
    private val bookmarks: BrowserBookmarks,
    private val bookmark: BrowserBookmark,
    private val onChanged: () -> Unit
) : BottomSheetDialog(context, R.string.browser_bookmark_edit) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = ViewBookmarkEditorBinding.inflate(layoutInflater)
        editor.bookmarkName.setText(bookmark.title)
        editor.bookmarkAddress.text = bookmark.url
        setBody(editor.root)
        addAction(R.string.browser_bookmark_remove) {
            bookmarks.remove(bookmark.url)
            onChanged()
            Toast.makeText(context, R.string.browser_bookmark_removed, Toast.LENGTH_SHORT).show()
        }
        addAction(R.string.cancel)
        addAction(R.string.browser_bookmark_save) {
            bookmarks.rename(bookmark.url, editor.bookmarkName.text.toString())
            onChanged()
        }
        editor.bookmarkName.requestFocus()
    }
}
