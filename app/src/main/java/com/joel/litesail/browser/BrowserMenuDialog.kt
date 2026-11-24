package com.joel.litesail.browser

import android.content.Context
import android.os.Bundle
import com.joel.litesail.R
import com.joel.litesail.databinding.ViewBrowserMenuBinding
import com.joel.litesail.ui.BottomSheetDialog

internal class BrowserMenuDialog(
    context: Context,
    private val isWebDebugEnabled: () -> Boolean,
    private val onWebDebugChanged: (Boolean) -> Unit,
    private val isAdBlockingEnabled: () -> Boolean,
    private val onAdBlockingChanged: (Boolean) -> Unit,
    private val currentPageUrl: () -> String?,
    private val onPageQrCode: (String) -> Unit,
    private val onLibrary: () -> Unit,
    private val onDownloads: () -> Unit,
    private val onWindows: () -> Unit,
    private val onExit: () -> Unit
) : BottomSheetDialog(context, R.string.browser_menu) {
    private lateinit var binding: ViewBrowserMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewBrowserMenuBinding.inflate(layoutInflater)
        setBody(binding.root)
        binding.closeMenu.setOnClickListener { dismiss() }
        binding.exitBrowser.setOnClickListener { onExit() }
        binding.openDownloads.setOnClickListener {
            dismiss()
            onDownloads()
        }
        binding.openLibrary.setOnClickListener {
            dismiss()
            onLibrary()
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
    }

    override fun onStart() {
        super.onStart()
        binding.pageQrCode.isEnabled = currentPageUrl() != null
        binding.webDebug.setOnCheckedChangeListener(null)
        binding.webDebug.isChecked = isWebDebugEnabled()
        binding.webDebug.setOnCheckedChangeListener { _, checked -> onWebDebugChanged(checked) }
        binding.adBlock.setOnCheckedChangeListener(null)
        binding.adBlock.isChecked = isAdBlockingEnabled()
        binding.adBlock.setOnCheckedChangeListener { _, checked -> onAdBlockingChanged(checked) }
    }
}
