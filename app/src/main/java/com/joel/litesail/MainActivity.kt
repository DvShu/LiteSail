package com.joel.litesail

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.joel.litesail.databinding.ActivityMainBinding

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private var loading = false
    private var failedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 35) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val safeArea = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
                insets
            }
        }
        configureWebView()
        bindControls()
        if (savedInstanceState == null || binding.webView.restoreState(savedInstanceState) == null) {
            open(BrowserAddress.HOME)
        } else {
            updateAddress(binding.webView.url)
            updateNavigationButtons()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (BrowserAddress.isWebScheme(request.url.scheme)) return false
                if (request.isForMainFrame) {
                    Toast.makeText(this@MainActivity, R.string.unsupported_link, Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                failedUrl = null
                binding.pageError.visibility = View.GONE
                binding.progress.progress = 0
                setLoading(true)
                updateAddress(url)
                updateNavigationButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                updateAddress(url)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                updateAddress(failedUrl ?: view.url ?: url)
                updateNavigationButtons()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || !BrowserAddress.isWebScheme(request.url.scheme)) return
                failedUrl = request.url.toString()
                binding.pageError.visibility = View.VISIBLE
                updateAddress(failedUrl)
                updateNavigationButtons()
                setLoading(false)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                if (newProgress == 100) setLoading(false)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                this@MainActivity.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
            }
        }
    }

    private fun bindControls() = with(binding) {
        go.setOnClickListener { submitAddress() }
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            submitAddress()
            true
        }
        back.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        home.setOnClickListener { open(BrowserAddress.HOME) }
        retry.setOnClickListener { failedUrl?.let(::open) }
        reload.setOnClickListener {
            if (loading) {
                webView.stopLoading()
                setLoading(false)
                updateNavigationButtons()
            } else {
                failedUrl?.let(::open) ?: webView.reload()
            }
        }
    }

    private fun submitAddress() {
        val url = BrowserAddress.resolve(binding.address.text.toString())
        if (url == null) {
            binding.address.error = getString(R.string.invalid_address)
            return
        }
        open(url)
    }

    private fun open(url: String) {
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(binding.address.windowToken, 0)
        binding.root.requestFocus()
        binding.address.error = null
        updateAddress(url)
        binding.webView.loadUrl(url)
    }

    private fun updateAddress(url: String?) {
        if (url != null && !binding.address.hasFocus()) binding.address.setText(url)
    }

    private fun updateNavigationButtons() = with(binding) {
        back.isEnabled = webView.canGoBack()
        forward.isEnabled = webView.canGoForward()
    }

    private fun setLoading(active: Boolean) {
        loading = active
        binding.progress.visibility = if (active) View.VISIBLE else View.INVISIBLE
        binding.reload.setText(if (active) R.string.stop else R.string.refresh)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        with(binding.webView) {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }
}
