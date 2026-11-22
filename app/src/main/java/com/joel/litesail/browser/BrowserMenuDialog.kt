package com.joel.litesail.browser

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import com.joel.litesail.R
import com.joel.litesail.databinding.ViewBrowserMenuBinding

internal class BrowserMenuDialog(
    context: Context,
    private val isWebDebugEnabled: () -> Boolean,
    private val onWebDebugChanged: (Boolean) -> Unit,
    private val isAdBlockingEnabled: () -> Boolean,
    private val onAdBlockingChanged: (Boolean) -> Unit,
    private val currentPageUrl: () -> String?,
    private val onPageQrCode: (String) -> Unit,
    private val isBookmarked: (String) -> Boolean,
    private val onBookmarkPage: (String) -> Unit,
    private val onBookmarks: () -> Unit,
    private val onDownloads: () -> Unit,
    private val onWindows: () -> Unit,
    private val onExit: () -> Unit
) : Dialog(context, R.style.Theme_LiteSail_BrowserMenu) {
    private lateinit var binding: ViewBrowserMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewBrowserMenuBinding.inflate(layoutInflater)
        setTitle(R.string.browser_menu)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        binding.closeMenu.setOnClickListener { dismiss() }
        binding.exitBrowser.setOnClickListener { onExit() }
        binding.openDownloads.setOnClickListener {
            dismiss()
            onDownloads()
        }
        binding.openBookmarks.setOnClickListener {
            dismiss()
            onBookmarks()
        }
        binding.bookmarkPage.setOnClickListener {
            val url = currentPageUrl()?.takeIf(BrowserAddress::isWebUrl) ?: return@setOnClickListener
            dismiss()
            onBookmarkPage(url)
        }
        binding.openWindows.setOnClickListener {
            dismiss()
            onWindows()
        }
        binding.pageQrCode.setOnClickListener {
            val url = currentPageUrl() ?: return@setOnClickListener
            dismiss()
            onPageQrCode(url)
        }

        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    override fun onStart() {
        super.onStart()
        val url = currentPageUrl()
        binding.pageQrCode.visibility = if (url == null) View.INVISIBLE else View.VISIBLE
        val bookmarkUrl = url?.takeIf(BrowserAddress::isWebUrl)
        binding.bookmarkPage.isEnabled = bookmarkUrl != null
        binding.bookmarkPage.isSelected = bookmarkUrl?.let(isBookmarked) == true
        binding.bookmarkPage.setText(
            if (binding.bookmarkPage.isSelected) R.string.browser_bookmark_saved else R.string.browser_menu_bookmark
        )
        binding.webDebug.setOnCheckedChangeListener(null)
        binding.webDebug.isChecked = isWebDebugEnabled()
        binding.webDebug.setOnCheckedChangeListener { _, checked -> onWebDebugChanged(checked) }
        binding.adBlock.setOnCheckedChangeListener(null)
        binding.adBlock.isChecked = isAdBlockingEnabled()
        binding.adBlock.setOnCheckedChangeListener { _, checked -> onAdBlockingChanged(checked) }
    }
}
