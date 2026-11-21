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
    private val currentPageUrl: () -> String?,
    private val onPageQrCode: (String) -> Unit,
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
        binding.pageQrCode.visibility = if (currentPageUrl() == null) View.GONE else View.VISIBLE
        binding.webDebug.setOnCheckedChangeListener(null)
        binding.webDebug.isChecked = isWebDebugEnabled()
        binding.webDebug.setOnCheckedChangeListener { _, checked -> onWebDebugChanged(checked) }
    }
}
